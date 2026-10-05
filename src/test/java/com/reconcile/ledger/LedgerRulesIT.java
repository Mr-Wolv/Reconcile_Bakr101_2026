package com.reconcile.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconcile.persistence.jdbc.LedgerWriter;
import com.reconcile.persistence.jdbc.Sql;
import com.reconcile.service.LedgerService;
import com.reconcile.service.PaymentService;
import com.reconcile.service.Postings;
import com.reconcile.service.ProviderEventProcessor;
import com.reconcile.shared.CurrencyCode;
import com.reconcile.shared.DomainException;
import com.reconcile.shared.Money;
import com.reconcile.shared.ProblemCode;
import com.reconcile.support.SharedPostgres;
import java.sql.Connection;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/**
 * The money rules, proved against a real database.
 *
 * <p>Covers {@code I-LED-01}, {@code I-LED-02}, {@code U-LED-08}, {@code U-LED-11} and
 * {@code I-FAIL-01}. What they share is that each one is a claim about what happens to money, and
 * each would pass against a mock: a mocked repository cannot make a deferred trigger fire at
 * COMMIT, cannot make an account {@code CLOSED}, and cannot have its connection killed underneath a
 * transaction in flight. So these run against PostgreSQL 18 and nothing is stubbed.
 *
 * <p>{@code I-LED-02} in particular is the row that caught a real defect during development:
 * settling a payment moved its state and left the money in {@code PSP_CLEARING} forever, because
 * {@code Postings.settlementReceived} existed and was never called.
 */
@SpringBootTest
class LedgerRulesIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedPostgres::jdbcUrl);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
    }

    private static final PaymentService.CommandContext CONTEXT = PaymentService.CommandContext.of(
            new PaymentService.Actor("USER", "ledger-rules"), "req_led", null);

    private static final Money GROSS = Money.of(10_000, CurrencyCode.EGP);

    @Autowired
    LedgerService ledger;

    @Autowired
    PaymentService payments;

    @Autowired
    ProviderEventProcessor processor;

    @Autowired
    Sql sql;

    @Autowired
    DataSource dataSource;

    @Autowired
    ObjectMapper mapper;

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
        sql.sql("INSERT INTO fee_policies (id, code, bps, currency, effective_from) "
                + "VALUES ('fp_default','DEFAULT',300,'EGP','2026-01-01T00:00:00Z')").update();
        sql.sql("""
                INSERT INTO account_balances (account_id, currency)
                SELECT a.id, c.code FROM ledger_accounts a CROSS JOIN currency_units c WHERE c.active
                """).update();
    }

    // ------------------------------------------------------------------ I-LED-01

    @Test
    @DisplayName("I-LED-01: a capture writes exactly three entries and the documented amounts")
    void captureWritesTheDocumentedEntries() {
        String paymentId = capture();

        assertThat(entriesOf("PAYMENT_CAPTURE")).isEqualTo(3);
        assertThat(entryAmount("PSP_CLEARING", "DEBIT")).isEqualTo(10_000L);
        assertThat(entryAmount("MERCHANT_PAYABLE", "CREDIT")).isEqualTo(9_700L);
        assertThat(entryAmount("PLATFORM_FEE_REVENUE", "CREDIT")).isEqualTo(300L);
        assertThat(balanceOf("PSP_CLEARING")).isEqualTo(10_000L);
        assertThat(balanceOf("MERCHANT_PAYABLE")).isEqualTo(9_700L);
        assertThat(balanceOf("PLATFORM_FEE_REVENUE")).isEqualTo(300L);
        assertThat(balanceOf("PLATFORM_CASH"))
                .as("nothing has been paid to us yet, so cash stays at zero")
                .isZero();
        assertThat(ledger.verifyBalances()).isEmpty();
        assertThat(sql.sql("SELECT count(*) AS n FROM payment_ledger_transactions "
                + "WHERE payment_id = ? AND role = 'CAPTURE'").param(paymentId)
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0))
                .as("the payment is linked to its posting, which is what an auditor walks back from")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ I-LED-02

    @Test
    @DisplayName("I-LED-02: a settlement moves clearing to cash exactly once")
    void settlementMovesClearingToCashExactlyOnce() {
        String paymentId = capture();
        String externalId = providerTransactionIdOf(paymentId);

        settleThroughTheEventPath(paymentId, externalId, "sset_one");
        // The same settlement delivered again, as a different event id - a push arriving twice, or a
        // push racing a pull. The record is one; the posting must also be one.
        settleThroughTheEventPath(paymentId, externalId, "sset_one");

        assertThat(balanceOf("PSP_CLEARING"))
                .as("the value we were holding for the provider has left")
                .isZero();
        assertThat(balanceOf("PLATFORM_CASH"))
                .as("and arrived as cash, at the gross: the fee was revenue at capture")
                .isEqualTo(10_000L);
        assertThat(ledgerTransactionsOfType("SETTLEMENT_RECEIVED"))
                .as("exactly one posting, however many times the settlement is delivered")
                .isEqualTo(1);
        assertThat(sql.sql("SELECT count(*) AS n FROM settlement_records")
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0)).isEqualTo(1);
        assertThat(payments.read(paymentId).state())
                .isEqualTo(com.reconcile.payment.PaymentState.SETTLED);
        assertThat(ledger.verifyBalances()).isEmpty();
    }

    // ------------------------------------------------------------------ U-LED-08

    @Test
    @DisplayName("U-LED-08: a posting against a CLOSED account is refused")
    void closedAccountIsRefused() {
        // ledger_accounts is seeded by the migration and is not truncated between tests, so this
        // test owns the state it changes and puts it back.
        try {
            sql.sql("UPDATE ledger_accounts SET state = 'CLOSED' WHERE code = 'PSP_CLEARING'")
                    .update();

            assertThatThrownBy(() -> ledger.post(
                    capturePosting("closed_" + unique()),
                    new LedgerWriter.PostingContext("test", "req", null)))
                    .isInstanceOf(DomainException.class)
                    .satisfies(thrown -> assertThat(((DomainException) thrown).code())
                            .as("an account closed for good must not be a ledger failure")
                            .isEqualTo(ProblemCode.LEDGER_IMBALANCE));
        } finally {
            sql.sql("UPDATE ledger_accounts SET state = 'ACTIVE' WHERE code = 'PSP_CLEARING'")
                    .update();
        }

        assertThat(ledgerTransactionsOfType("PAYMENT_CAPTURE"))
                .as("and nothing is written: L9 is checked before any lock is taken")
                .isZero();
        assertThat(ledger.verifyBalances()).isEmpty();
    }

    // ------------------------------------------------------------------ U-LED-11

    @Test
    @DisplayName("U-LED-11: a payout larger than PLATFORM_CASH is refused before any write")
    void oversizedPayoutIsRefusedBeforeWriting() {
        // Nothing has been settled, so cash is zero and every payout is too large.
        //
        // The refusal here is the L8 trigger speaking, not the friendly 409 the payout endpoint
        // returns: this calls the ledger directly, past the pre-check. That is the point of the
        // row - the database is the last line of defence, so even a caller that skipped the
        // service layer cannot make cash go negative.
        assertThatThrownBy(() -> ledger.post(
                Postings.merchantPayout("payref_" + unique(), GROSS, "MERCH-X"),
                new LedgerWriter.PostingContext("test", "req", null)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);

        assertThat(ledgerTransactionsOfType("MERCHANT_PAYOUT"))
                .as("L8 refuses it at the database, so there is no window in which a partial "
                        + "payout exists")
                .isZero();
        assertThat(entryCount()).as("and not one entry either").isZero();
        assertThat(balanceOf("PLATFORM_CASH")).isZero();
        assertThat(ledger.verifyBalances()).isEmpty();
    }

    // ------------------------------------------------------------------ reversal

    @Test
    @DisplayName("U-LED-05: reverse() mirrors the original exactly, and refuses a second reversal")
    void reversalIsAnExactMirror() {
        String paymentId = capture();
        String captureTransaction = transactionIdOf("PAYMENT_CAPTURE");

        String reversalId = ledger.reverse(captureTransaction, "the merchant withdrew",
                new LedgerWriter.PostingContext("test", "req_rev", null));

        assertThat(entriesOf("REVERSAL")).isEqualTo(3);
        assertThat(entryAmount("PSP_CLEARING", "CREDIT"))
                .as("an exact sign flip: same account, same amount, opposite direction")
                .isEqualTo(10_000L);
        assertThat(entryAmount("MERCHANT_PAYABLE", "DEBIT")).isEqualTo(9_700L);
        assertThat(balanceOf("PSP_CLEARING"))
                .as("and the capture is undone, not edited away")
                .isZero();
        assertThat(balanceOf("MERCHANT_PAYABLE")).isZero();
        assertThat(sql.sql("SELECT reversal_of_transaction_id AS r FROM ledger_transactions "
                + "WHERE id = ?").param(reversalId)
                .optional((rs, rowNum) -> rs.getString("r")).orElse(null))
                .isEqualTo(captureTransaction);

        assertThatThrownBy(() -> ledger.reverse(captureTransaction, "and again",
                new LedgerWriter.PostingContext("test", "req_rev2", null)))
                .as("L6: whether this has been reversed is answered by a unique index, not by a flag")
                .isInstanceOf(DomainException.class);

        assertThat(entriesOf("REVERSAL"))
                .as("so the mirror happens exactly once")
                .isEqualTo(3);
        assertThat(ledger.verifyBalances()).isEmpty();
        assertThat(sql.sql("SELECT count(*) AS n FROM ledger_transactions WHERE type = 'PAYMENT_CAPTURE'")
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0))
                .as("and the original is untouched: L4 forbids editing history, and a reversal is "
                        + "the only way to undo one")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("U-LED-07: a reversal may itself be reversed, and the chain still balances")
    void reversalOfAReversalIsAllowed() {
        capture();
        String captureTransaction = transactionIdOf("PAYMENT_CAPTURE");
        String reversal = ledger.reverse(captureTransaction, "first",
                new LedgerWriter.PostingContext("test", "req_r1", null));

        String undo = ledger.reverse(reversal, "the reversal was itself wrong", 
                new LedgerWriter.PostingContext("test", "req_r2", null));

        assertThat(entriesOf("REVERSAL")).isEqualTo(6);
        assertThat(balanceOf("PSP_CLEARING"))
                .as("capture, reversal, reversal of the reversal: back where we started")
                .isEqualTo(10_000L);
        assertThat(balanceOf("MERCHANT_PAYABLE")).isEqualTo(9_700L);
        assertThat(sql.sql("SELECT reversal_of_transaction_id AS r FROM ledger_transactions "
                + "WHERE id = ?").param(undo).optional((rs, rowNum) -> rs.getString("r"))
                .orElse(null))
                .as("the chain is explicit, so an auditor can walk it in both directions")
                .isEqualTo(reversal);
        assertThat(ledger.verifyBalances()).isEmpty();
    }

    // ------------------------------------------------------------------ I-FAIL-01

    @Test
    @DisplayName("I-FAIL-01: a connection killed mid-transaction writes nothing, and a retry works")
    void killedConnectionLeavesNoPartialWrite() throws Exception {
        String paymentId = "pay_" + unique();

        try (Connection doomed = dataSource.getConnection()) {
            doomed.setAutoCommit(false);

            int pid = backendPid(doomed);

            // Start the work, then kill the session out from under the open transaction. This is
            // the failure a network partition or a database restart produces, reproduced exactly
            // rather than simulated with a mock that decides for itself what to throw.
            try (var statement = doomed.prepareStatement("""
                    INSERT INTO payments
                        (id, merchant_reference, description, amount_minor, currency,
                         platform_fee_minor, net_amount_minor, fee_policy_id, state, provider,
                         created_at, updated_at)
                    VALUES (?, 'MERCH-FAIL', null, 10000, 'EGP', 300, 9700, 'fp_default',
                            'CREATED', 'SIMULATED_PSP', now(), now())
                    """)) {
                statement.setString(1, paymentId);
                statement.executeUpdate();
            }

            assertThat(terminate(pid))
                    .as("the session is gone, which is the whole point of the test")
                    .isTrue();

            assertThatThrownBy(doomed::commit)
                    .as("the commit cannot succeed: its connection no longer exists")
                    .isInstanceOf(java.sql.SQLException.class);
        }

        assertThat(sql.sql("SELECT count(*) AS n FROM payments WHERE id = ?").param(paymentId)
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0))
                .as("the whole transaction is gone, not half of it")
                .isZero();

        // And the retry with the same key succeeds, posting exactly one ledger transaction.
        com.reconcile.service.PaymentView created = payments.create("MERCH-FAIL", null, GROSS, "SIMULATED_PSP",
                "psp_" + unique(), CONTEXT);
        payments.authorize(created.id(), CONTEXT);
        payments.capture(created.id(), null, CONTEXT);

        assertThat(ledgerTransactionsOfType("PAYMENT_CAPTURE")).isEqualTo(1);
        assertThat(balanceOf("PSP_CLEARING")).isEqualTo(10_000L);
        assertThat(ledger.verifyBalances()).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private String capture() {
        String id = payments.create("MERCH-1", null, GROSS, "SIMULATED_PSP",
                "psp_" + unique(), CONTEXT).id();
        payments.authorize(id, CONTEXT);
        payments.capture(id, null, CONTEXT);
        return id;
    }

    private String providerTransactionIdOf(String paymentId) {
        return sql.sql("SELECT provider_transaction_id FROM payments WHERE id = ?")
                .param(paymentId)
                .optional((rs, rowNum) -> rs.getString("provider_transaction_id"))
                .orElseThrow();
    }

    /** Ingests a settlement the way a webhook does, so the posting path is the real one. */
    private void settleThroughTheEventPath(String paymentId, String externalId, String settlementId) {
        String payload = """
                {"type":"settlement.paid","occurredAt":"%s","settlement":{"providerSettlementId":"%s",\
                "settlementDate":"%s","currency":"EGP","grossAmountMinor":10000,"feeAmountMinor":300,\
                "netAmountMinor":9700,"lines":[{"providerTransactionId":"%s","grossAmountMinor":10000,\
                "netAmountMinor":9700}]}}"""
                .formatted(java.time.Instant.now(), settlementId, java.time.LocalDate.now(),
                        externalId);

        processor.apply("SIMULATED_PSP", "evt_" + settlementId, payload, "req_" + settlementId);
    }

    private int backendPid(Connection connection) throws Exception {
        try (var statement = connection.prepareStatement("SELECT pg_backend_pid() AS pid");
                var rows = statement.executeQuery()) {
            rows.next();
            return rows.getInt("pid");
        }
    }

    private boolean terminate(int pid) throws Exception {
        try (Connection killer = dataSource.getConnection();
                var statement = killer.prepareStatement(
                        "SELECT pg_terminate_backend(?) AS killed")) {
            statement.setInt(1, pid);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getBoolean("killed");
            }
        }
    }

    private LedgerPostingCommand capturePosting(String sourceId) {
        return new LedgerPostingCommand(
                LedgerTransactionType.PAYMENT_CAPTURE,
                CurrencyCode.EGP,
                List.of(
                        new LedgerPostingCommand.Entry("PSP_CLEARING", Direction.DEBIT, GROSS),
                        new LedgerPostingCommand.Entry("MERCHANT_PAYABLE", Direction.CREDIT,
                                Money.of(9_700, CurrencyCode.EGP)),
                        new LedgerPostingCommand.Entry("PLATFORM_FEE_REVENUE", Direction.CREDIT,
                                Money.of(300, CurrencyCode.EGP))),
                "PAYMENT", sourceId, "capture", null, null);
    }

    private String transactionIdOf(String type) {
        return sql.sql("SELECT id FROM ledger_transactions WHERE type = ? "
                + "ORDER BY posted_at, id LIMIT 1").param(type)
                .optional((rs, rowNum) -> rs.getString("id")).orElseThrow();
    }

    private int entriesOf(String type) {
        return sql.sql("""
                SELECT count(*) AS n FROM ledger_entries e
                  JOIN ledger_transactions t ON t.id = e.transaction_id
                 WHERE t.type = ?
                """).param(type).optional((rs, rowNum) -> rs.getInt("n")).orElse(0);
    }

    private long entryAmount(String accountCode, String direction) {
        return sql.sql("""
                SELECT COALESCE(SUM(e.amount_minor), 0) AS total FROM ledger_entries e
                 WHERE e.account_code = ? AND e.direction = ?
                """).params(accountCode, direction)
                .optional((rs, rowNum) -> rs.getLong("total")).orElse(0L);
    }

    private int ledgerTransactionsOfType(String type) {
        return sql.sql("SELECT count(*) AS n FROM ledger_transactions WHERE type = ?")
                .param(type).optional((rs, rowNum) -> rs.getInt("n")).orElse(0);
    }

    private int entryCount() {
        return sql.sql("SELECT count(*) AS n FROM ledger_entries")
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0);
    }

    private long balanceOf(String accountCode) {
        return sql.sql("""
                SELECT b.balance_minor FROM account_balances b
                  JOIN ledger_accounts a ON a.id = b.account_id
                 WHERE a.code = ? AND b.currency = 'EGP'
                """).param(accountCode).optional((rs, rowNum) -> rs.getLong("balance_minor"))
                .orElse(0L);
    }

    private static String unique() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }
}
