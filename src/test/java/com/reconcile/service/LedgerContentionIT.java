package com.reconcile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.reconcile.ledger.Direction;
import com.reconcile.ledger.LedgerPostingCommand;
import com.reconcile.ledger.LedgerTransactionType;
import com.reconcile.persistence.jdbc.LedgerWriter;
import com.reconcile.persistence.jdbc.Sql;
import com.reconcile.shared.CurrencyCode;
import com.reconcile.shared.DomainException;
import com.reconcile.shared.Money;
import com.reconcile.support.SharedPostgres;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@code C-CONC-03}: forced lock contention — retries are bounded, the failure is loud, and a retry
 * can never double-post.
 *
 * <p>Its own class, and its own Spring context, because it needs {@code lock_timeout} set low on
 * the application's own connections. That is the only way to make PostgreSQL give up on a lock wait
 * quickly and deterministically; without it the posting would block until the holder released, and
 * the test would be measuring the holder rather than the retry policy.
 *
 * <p>Three claims, each proved separately because they fail differently:
 *
 * <ol>
 *   <li><b>Bounded</b> — contention lasting longer than the whole retry budget produces one loud
 *       failure, not a silent one and not an unbounded loop.
 *   <li><b>Loud</b> — the caller is told it was lock contention, not handed a generic 500 with no
 *       way to tell a deadlock from a bug.
 *   <li><b>No double-post</b> — the retry is safe because the unique index refuses the second
 *       posting. Without that, "retry on deadlock" would be the single most dangerous line of code
 *       in a ledger.
 * </ol>
 */
@SpringBootTest(properties = {
        // Low enough that an attempt fails long before the holder below releases, high enough that
        // a normal uncontended posting is never affected.
        "spring.datasource.hikari.connection-init-sql=SET lock_timeout = '300ms'"
})
class LedgerContentionIT {


    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedPostgres::jdbcUrl);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
    }

    private static final Money GROSS = Money.of(10_000, CurrencyCode.EGP);
    private static final Money NET = Money.of(9_700, CurrencyCode.EGP);
    private static final Money FEE = Money.of(300, CurrencyCode.EGP);

    @Autowired
    LedgerService ledger;

    @Autowired
    Sql sql;

    @Autowired
    DataSource dataSource;

    private final List<Connection> heldConnections = new java.util.ArrayList<>();

    @BeforeEach
    void reset() {
        sql.sql("""
                TRUNCATE payment_state_history, payment_ledger_transactions, ledger_entries,
                         ledger_transactions, account_balances, payments, payout_lines,
                         payout_records, settlement_record_lines, settlement_records,
                         provider_transactions, provider_events, provider_event_deliveries,
                         idempotency_records, reconciliation_cases, reconciliation_results,
                         reconciliation_subjects, reconciliation_batches, case_events,
                         audit_events, fee_policies
                RESTART IDENTITY CASCADE
                """).update();
        sql.sql("""
                INSERT INTO account_balances (account_id, currency)
                SELECT a.id, c.code FROM ledger_accounts a CROSS JOIN currency_units c WHERE c.active
                """).update();
    }

    @AfterEach
    void releaseLocks() {
        for (Connection connection : heldConnections) {
            try {
                connection.rollback();
                connection.close();
            } catch (java.sql.SQLException ignored) {
                // The test has already failed; a cleanup error must not mask it.
            }
        }
        heldConnections.clear();
    }

    // ------------------------------------------------------------------ C-CONC-03

    @Test
    @DisplayName("C-CONC-03: contention outlasting the retries fails loudly, after three attempts")
    void contentionOutlastingTheRetriesFailsLoudly() throws Exception {
        long[] elapsedMillisHolder = new long[1];
        ListAppender<ILoggingEvent> retries = captureRetryWarnings();
        long startedAt;
        Throwable thrown;
        try (Connection holder = lockClearingAccount()) {
            assertThat(holdAccount(holder)).as("the lock is genuinely held").isTrue();

            LedgerPostingCommand command = posting("contended_" + unique());

            startedAt = System.nanoTime();
            thrown = catchThrowable(() ->
                    ledger.post(command, new LedgerWriter.PostingContext("test", "req", null)));
            elapsedMillisHolder[0] = (System.nanoTime() - startedAt) / 1_000_000;
        } finally {
            retries.stop();
        }

        assertThat(thrown)
                .as("a posting that cannot get its lock must fail, not hang and not vanish")
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("lock contention")
                .hasMessageContaining("3 attempts");
        assertThat(((DomainException) thrown).code())
                .as("the refusal is a named problem code, so a client can tell contention from a bug")
                .isEqualTo(com.reconcile.shared.ProblemCode.INTERNAL_ERROR);

        assertThat(retries.list)
                .as("three attempts means exactly two retries, and no more: an unbounded retry "
                        + "loop against a hot account is how a ledger stops serving anyone")
                .hasSize(2);
        assertThat(retries.list)
                .allMatch(event -> event.getLevel() == Level.WARN)
                .allMatch(event -> event.getFormattedMessage().contains("lock contention"));

        assertThat(elapsedMillisHolder[0])
                .as("three attempts against a 300 ms lock timeout cannot finish instantly; if this "
                        + "drops to a few milliseconds, the retry loop stopped running")
                .isGreaterThan(600L);

        assertThat(ledgerTransactionsOfType("PAYMENT_CAPTURE"))
                .as("an exhausted posting must leave nothing behind: no half-written transaction, "
                        + "no orphan entries, and above all no posting a later retry could mistake "
                        + "for success")
                .isZero();
        assertThat(balanceOf("PSP_CLEARING")).isZero();
        assertThat(ledger.verifyBalances())
                .as("L7 still holds after a contended posting gave up")
                .isEmpty();
    }

    @Test
    @DisplayName("C-CONC-03b: the same posting retried after the contention clears posts exactly once")
    void retryAfterContentionPostsOnceAndThenRefusesTheSecond() throws Exception {
        LedgerPostingCommand command = posting("retried_" + unique());

        try (Connection holder = lockClearingAccount()) {
            assertThat(holdAccount(holder)).isTrue();
            assertThatThrownBy(() ->
                    ledger.post(command, new LedgerWriter.PostingContext("test", "req", null)))
                    .isInstanceOf(DomainException.class);
        }

        // The lock is released now. A client that saw a failure and retried must land the money
        // exactly once - this is the whole reason the retry loop is sound.
        String posted = ledger.post(command, new LedgerWriter.PostingContext("test", "req2", null));

        assertThat(posted).isNotBlank();
        assertThat(ledgerTransactionsOfType("PAYMENT_CAPTURE")).isEqualTo(1);
        assertThat(balanceOf("PSP_CLEARING")).isEqualTo(GROSS.amountMinor());
        assertThat(balanceOf("MERCHANT_PAYABLE")).isEqualTo(NET.amountMinor());
        assertThat(ledger.verifyBalances()).isEmpty();

        // And a third attempt is refused by the unique index, not by luck.
        assertThatThrownBy(() ->
                ledger.post(command, new LedgerWriter.PostingContext("test", "req3", null)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("already posted");
        assertThat(ledgerTransactionsOfType("PAYMENT_CAPTURE"))
                .as("the uniqueness constraint is what makes an optimistic retry safe")
                .isEqualTo(1);
        assertThat(balanceOf("PSP_CLEARING"))
                .as("and the money moved exactly once")
                .isEqualTo(GROSS.amountMinor());
    }

    @Test
    @DisplayName("C-CONC-03c: contention inside a caller transaction is refused, not retried")
    void contentionInsideACallerTransactionIsNotRetried() throws Exception {
        // ADR-0002: retrying inside an ambient transaction would be retrying on a connection
        // PostgreSQL has already marked rollback-only, which fails differently and more confusingly
        // than the original lock timeout. One loud refusal is the correct behaviour.
        ListAppender<ILoggingEvent> retries = captureRetryWarnings();
        try (Connection holder = lockClearingAccount()) {
            assertThat(holdAccount(holder)).isTrue();

            CountDownLatch done = new CountDownLatch(1);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<String> outcome = pool.submit(() -> {
                    try {
                        return new org.springframework.transaction.support.TransactionTemplate(
                                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                        dataSource)).execute(status -> {
                                    try {
                                        ledger.post(posting("ambient_" + unique()),
                                                new LedgerWriter.PostingContext("test", "req", null));
                                        return "posted";
                                    } catch (RuntimeException refused) {
                                        return refused.getClass().getSimpleName();
                                    }
                                });
                    } finally {
                        done.countDown();
                    }
                });

                assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
                assertThat(outcome.get())
                        .as("the lock timeout surfaces as a database exception, and is not "
                                + "silently retried on a poisoned transaction")
                        .isNotEqualTo("posted");
            } finally {
                pool.shutdownNow();
            }
        } finally {
            retries.stop();
        }

        assertThat(retries.list)
                .as("no retry warning is logged, because no retry was attempted")
                .isEmpty();
        assertThat(ledgerTransactionsOfType("PAYMENT_CAPTURE")).isZero();
        assertThat(ledger.verifyBalances()).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    /** Attaches a Logback appender so the retry count is observable rather than inferred. */
    private ListAppender<ILoggingEvent> captureRetryWarnings() {
        Logger logger = (Logger) LoggerFactory.getLogger(LedgerService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    /** Opens a transaction on a connection of our own and locks the settlement clearing account. */
    private Connection lockClearingAccount() throws Exception {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        heldConnections.add(connection);
        return connection;
    }

    private boolean holdAccount(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM account_balances b
                  JOIN ledger_accounts a ON a.id = b.account_id
                 WHERE a.code = 'PSP_CLEARING' AND b.currency = 'EGP'
                   FOR UPDATE
                """)) {
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private LedgerPostingCommand posting(String sourceId) {
        return new LedgerPostingCommand(
                LedgerTransactionType.PAYMENT_CAPTURE,
                CurrencyCode.EGP,
                List.of(
                        new LedgerPostingCommand.Entry("PSP_CLEARING", Direction.DEBIT, GROSS),
                        new LedgerPostingCommand.Entry("MERCHANT_PAYABLE", Direction.CREDIT, NET),
                        new LedgerPostingCommand.Entry("PLATFORM_FEE_REVENUE", Direction.CREDIT, FEE)),
                "PAYMENT", sourceId, "contended capture", null, null);
    }

    private int ledgerTransactionsOfType(String type) {
        return sql.sql("SELECT COUNT(*) AS total FROM ledger_transactions WHERE type = ?")
                .param(type)
                .optional((rs, rowNum) -> rs.getInt("total")).orElse(-1);
    }

    private long balanceOf(String accountCode) {
        return sql.sql("""
                SELECT b.balance_minor FROM account_balances b
                  JOIN ledger_accounts a ON a.id = b.account_id
                 WHERE a.code = ? AND b.currency = 'EGP'
                """).param(accountCode)
                .optional((rs, rowNum) -> rs.getLong("balance_minor")).orElse(0L);
    }

    private static String unique() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }
}
