package com.reconcile.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconcile.ledger.Direction;
import com.reconcile.ledger.LedgerPostingCommand;
import com.reconcile.ledger.LedgerTransactionType;
import com.reconcile.persistence.jdbc.LedgerWriter;
import com.reconcile.persistence.jdbc.Sql;
import com.reconcile.shared.CurrencyCode;
import com.reconcile.shared.DomainException;
import com.reconcile.shared.Money;
import com.reconcile.shared.ProblemCode;
import com.reconcile.shared.Ulid;
import com.reconcile.support.SharedPostgres;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Concurrency, against a real PostgreSQL 18 with real threads.
 *
 * <p>Maps to {@code C-CONC-01}, {@code C-CONC-02}, {@code C-CONC-04} and {@code C-CONC-05}. None of
 * these can be tested with mocks, and it is worth being explicit about why: every one of them is a
 * claim about what the <b>database</b> does under contention — {@code SELECT … FOR UPDATE} blocking,
 * a unique index refusing the loser of a race, {@code ON CONFLICT DO NOTHING} collapsing two
 * workers onto one row. A mocked repository agrees with all of it instantly and proves nothing.
 *
 * <p>The bar every test here clears is not "no exception" but <b>no corruption</b>. Losing a race is
 * fine and expected; losing a race and then posting the money twice, or producing two answers to one
 * reconciliation question, is the failure these tests exist to catch.
 *
 * <p>Threads are released together by a {@link CyclicBarrier} so they collide as hard as the machine
 * allows. Releasing them in sequence would make a locking bug almost impossible to hit, which would
 * make the suite green for the wrong reason.
 */
@SpringBootTest
class ConcurrencyIT {

    private static final int THREADS = 32;

    private static final PaymentService.CommandContext CONTEXT = PaymentService.CommandContext.of(
            new PaymentService.Actor("USER", "concurrency-test"), "req_conc", null);

    private static final Money GROSS = Money.of(10_000, CurrencyCode.EGP);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedPostgres::jdbcUrl);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        // A background scheduler claiming the batch while 32 threads race for it would turn a
        // locking test into a scheduler test. This class owns the run.
        registry.add("reconcile.reconciliation.poll-interval", () -> "1h");
    }

    @Autowired
    LedgerService ledger;

    @Autowired
    PaymentService payments;

    @Autowired
    PayoutService payouts;

    @Autowired
    ReconciliationService reconciliation;

    @Autowired
    ReconciliationWorker worker;

    @Autowired
    Sql sql;

    @Autowired
    DataSource dataSource;

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

    // ------------------------------------------------------------------ C-CONC-01

    @Test
    @DisplayName("C-CONC-01: 32 threads posting at once leave the ledger exactly right")
    void concurrentPostingsLeaveTheLedgerConsistent() throws Exception {
        // Seeding through the API keeps the accounting honest: PSP_CLEARING holds real capture
        // value, so the concurrent postings below have something real to contend over rather than
        // racing over two empty accounts.
        for (int i = 0; i < THREADS; i++) {
            String paymentId = capturePayment();
            payments.refund(paymentId, CONTEXT);
        }
        long expectedClearing = balanceOf("PSP_CLEARING");
        assertThat(expectedClearing).as("the seed is 32 captures fully reversed").isZero();

        List<String> postings = inParallel(THREADS, index -> ledger.post(
                capturePosting("conc_" + index),
                new LedgerWriter.PostingContext("concurrency-test", "req_conc_" + index, null)));
        List<String> failures = postings.stream().filter(v -> v.startsWith("FAILED:")).toList();

        assertThat(failures)
                .as("no thread may fail: a loser of a lock race retries, and exhausting the retries "
                        + "would be a real defect, not an acceptable outcome")
                .isEmpty();
        assertThat(postings)
                .as("and every one of them posted exactly once")
                .hasSize(THREADS);

        assertThat(balanceOf("PSP_CLEARING"))
                .as("PSP_CLEARING == the sum of the concurrent postings, to the minor unit")
                .isEqualTo(THREADS * GROSS.amountMinor());
        assertThat(balanceOf("MERCHANT_PAYABLE"))
                .as("the payable is the net of every posting, and never negative")
                .isEqualTo(THREADS * 9_700L);
        assertThat(balanceOf("PLATFORM_FEE_REVENUE")).isEqualTo(THREADS * 300L);

        assertThat(entryCount())
                .as("three entries for each seeded capture, three for its reversal, and three for "
                        + "each concurrent posting: nothing posted twice, nothing lost")
                .isEqualTo((long) THREADS * 6 + (long) THREADS * 3);
        assertThat(ledger.verifyBalances())
                .as("L7: the projection still equals the entries after 64 concurrent postings")
                .isEmpty();
    }

    // ------------------------------------------------------------------ C-CONC-02

    @Test
    @DisplayName("C-CONC-02: 16 threads capturing one payment: one capture, fifteen refusals")
    void oneCaptureWins() throws Exception {
        String paymentId = authorizePayment();

        CyclicBarrier gate = new CyclicBarrier(16);
        List<Outcome> outcomes = race(16, index -> {
            gate.await(10, TimeUnit.SECONDS);
            return captureOutcome(paymentId);
        });

        assertThat(outcomes.stream().filter(Outcome::succeeded).count())
                .as("exactly one thread may capture; the payment row lock is what decides it")
                .isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> !o.succeeded()).count()).isEqualTo(15);
        assertThat(outcomes.stream()
                .filter(o -> !o.succeeded())
                .map(o -> o.problemCode())
                .distinct())
                .as("every loser is refused with one stable code, not a variety of surprises")
                .containsExactly(ProblemCode.PAYMENT_INVALID_STATE);

        assertThat(ledgerTransactionsOfType("PAYMENT_CAPTURE"))
                .as("one capture posting, however many threads asked")
                .isEqualTo(1);
        assertThat(balanceOf("PSP_CLEARING")).isEqualTo(GROSS.amountMinor());
        assertThat(ledger.verifyBalances()).isEmpty();
    }

    // ------------------------------------------------------------------ C-CONC-05

    @Test
    @DisplayName("C-CONC-05: two payouts naming the same payment: one wins, one is refused")
    void onePayoutWins() throws Exception {
        String first = capturePayment();
        String second = capturePayment();
        settle(first);
        settle(second);

        long cashBefore = balanceOf("PLATFORM_CASH");

        CyclicBarrier gate = new CyclicBarrier(2);
        List<Outcome> outcomes = race(2, index -> {
            gate.await(10, TimeUnit.SECONDS);
            try {
                String payoutId = payouts.execute("MERCH-RACE", List.of(first, second), CONTEXT).id();
                return Outcome.success(payoutId);
            } catch (DomainException refused) {
                return Outcome.failure(refused.code());
            }
        });

        assertThat(outcomes.stream().filter(Outcome::succeeded).count())
                .as("L11 makes a second payout of the same payments impossible, not merely unlikely")
                .isEqualTo(1);
        assertThat(outcomes.stream()
                .filter(o -> !o.succeeded())
                .map(o -> o.problemCode())
                .distinct())
                .as("the loser is told the business reason, which is L11 and not a lock error")
                .containsExactly(ProblemCode.PAYOUT_PAYMENT_ALREADY_PAID);

        assertThat(sql.sql("SELECT COUNT(*) AS total FROM payout_records")
                .optional((rs, rowNum) -> rs.getInt("total")).orElse(-1)).isEqualTo(1);
        assertThat(ledgerTransactionsOfType("MERCHANT_PAYOUT")).isEqualTo(1);
        assertThat(balanceOf("PLATFORM_CASH"))
                .as("the merchant is paid once, so cash falls by exactly the two nets")
                .isEqualTo(cashBefore - 2 * 9_700L);
        assertThat(ledger.verifyBalances()).isEmpty();
    }

    // ------------------------------------------------------------------ C-CONC-04

    @Test
    @DisplayName("C-CONC-04: two workers on one batch produce one result per subject and correct totals")
    void twoWorkersOnOneBatchDoNotDuplicate() throws Exception {
        int subjects = 200;
        int mismatches = 0;
        for (int i = 0; i < subjects; i++) {
            String paymentId = capturePayment();
            String externalId = paymentProviderTransactionId(paymentId);
            boolean shortPaid = i % 7 == 0;
            providerRow(externalId, shortPaid ? 9_700 : 10_000, "CAPTURED");
            if (shortPaid) {
                mismatches++;
            }
        }

        java.time.Instant windowStart = java.time.Instant.now().minusSeconds(365 * 24 * 3600L);
        String batch = reconciliation.createBatch(new ReconciliationService.BatchRequest(
                "SIMULATED_PSP", windowStart, java.time.Instant.now().plusSeconds(86_400),
                ReconciliationService.SubjectMode.BOTH, "concurrency-test", "req_conc"));

        CyclicBarrier gate = new CyclicBarrier(2);
        List<Outcome> outcomes = race(2, index -> {
            gate.await(10, TimeUnit.SECONDS);
            return Outcome.success(worker.runBatch(batch) >= 0 ? batch : batch);
        });

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(resultsIn(batch))
                .as("the results table is keyed (batch, subject); the race must not add rows")
                .isEqualTo(subjects);
        assertThat(sql.sql("""
                SELECT COUNT(*) AS total FROM (
                    SELECT subject_key FROM reconciliation_results WHERE batch_id = ?
                     GROUP BY subject_key HAVING COUNT(*) > 1) duplicated
                """).param(batch).optional((rs, rowNum) -> rs.getInt("total")).orElse(-1))
                .as("and no subject is answered twice")
                .isZero();
        assertThat(sql.sql("SELECT COUNT(*) AS total FROM reconciliation_cases WHERE batch_id = ?")
                .param(batch).optional((rs, rowNum) -> rs.getInt("total")).orElse(-1))
                .as("one case per mismatching subject, however many workers noticed it")
                .isEqualTo(mismatches);
        assertThat(batchCounters(batch))
                .as("the batch totals agree with the rows that were actually written")
                .containsEntry("processed_subjects", subjects)
                .containsEntry("total_subjects", subjects);
        assertThat(ledger.verifyBalances()).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Runs one task per thread, all released together, and returns what each thread returned.
     *
     * <p>{@link Outcome} rather than a bare exception: the point of several of these tests is that a
     * loser is refused <i>in the right way</i>, so the refusal has to survive into the assertion
     * rather than being flattened into a boolean.
     */
    private <T> List<T> race(int threads, IndexedTask<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(pool.submit((Callable<T>) () -> task.run(index)));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(120, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Every thread returns its posting id; any refusal comes back as a message instead. */
    private List<String> inParallel(int threads, IndexedTask<String> task) throws Exception {
        return race(threads, index -> {
            try {
                return task.run(index);
            } catch (RuntimeException refused) {
                return "FAILED: " + refused.getMessage();
            }
        });
    }

    private interface IndexedTask<T> {
        T run(int index) throws Exception;
    }

    private record Outcome(boolean succeeded, String value, ProblemCode problemCode) {

        static Outcome success(String value) {
            return new Outcome(true, value, null);
        }

        static Outcome failure(ProblemCode code) {
            return new Outcome(false, null, code);
        }
    }

    private Outcome captureOutcome(String paymentId) {
        try {
            payments.capture(paymentId, GROSS, CONTEXT);
            return Outcome.success(paymentId);
        } catch (DomainException refused) {
            return Outcome.failure(refused.code());
        }
    }

    /** Create → authorize → capture: a payment whose money has actually moved. */
    private String capturePayment() {
        String id = authorizePayment();
        payments.capture(id, GROSS, CONTEXT);
        return id;
    }

    private String authorizePayment() {
        String id = payments.create(
                "MERCH-RACE", "seed", GROSS, "SIMULATED_PSP", "psp_" + Ulid.next(), CONTEXT).id();
        payments.authorize(id, CONTEXT);
        return id;
    }

    /** Settles a captured payment through the settlement path, so cash really arrives. */
    private void settle(String paymentId) {
        String externalId = paymentProviderTransactionId(paymentId);
        String recordId = "srec_" + paymentId;
        sql.sql("""
                INSERT INTO settlement_records
                    (id, provider, provider_settlement_id, gross_amount_minor, fee_amount_minor,
                     net_amount_minor, currency, settlement_date, source)
                VALUES (?, 'SIMULATED_PSP', ?, 10000, 300, 9700, 'EGP', current_date, 'PULL')
                """).params(recordId, "psset_" + paymentId).update();
        sql.sql("""
                INSERT INTO settlement_record_lines
                    (id, settlement_record_id, provider_transaction_id, provider_transaction_ref,
                     line_gross_minor, line_net_minor, line_no)
                VALUES (?, ?, ?, NULL, 10000, 9700, 1)
                """).params("srline_" + paymentId, recordId, externalId).update();
        payments.markSettled(paymentId, recordId, java.time.LocalDate.now().toString(),
                com.reconcile.payment.PaymentStateMachine.Trigger.SETTLEMENT, CONTEXT);
        ledger.post(com.reconcile.service.Postings.settlementReceived(
                        recordId, GROSS, "SIMULATED_PSP"),
                new LedgerWriter.PostingContext("concurrency-test", "req_conc", null));
    }

    private LedgerPostingCommand capturePosting(String sourceId) {
        return new LedgerPostingCommand(
                LedgerTransactionType.PAYMENT_CAPTURE,
                CurrencyCode.EGP,
                List.of(
                        new LedgerPostingCommand.Entry(
                                "PSP_CLEARING", Direction.DEBIT, GROSS),
                        new LedgerPostingCommand.Entry(
                                "MERCHANT_PAYABLE", Direction.CREDIT, Money.of(9_700, CurrencyCode.EGP)),
                        new LedgerPostingCommand.Entry(
                                "PLATFORM_FEE_REVENUE", Direction.CREDIT, Money.of(300, CurrencyCode.EGP))),
                "PAYMENT", sourceId, "capture " + sourceId, null, null);
    }

    private String paymentProviderTransactionId(String paymentId) {
        return sql.sql("SELECT provider_transaction_id FROM payments WHERE id = ?")
                .param(paymentId)
                .optional((rs, rowNum) -> rs.getString("provider_transaction_id"))
                .orElseThrow();
    }

    private void providerRow(String externalId, long gross, String status) {
        sql.sql("""
                INSERT INTO provider_transactions
                    (id, provider, provider_transaction_id, merchant_reference, gross_amount_minor,
                     fee_amount_minor, net_amount_minor, currency, provider_status, source, captured_at)
                VALUES (?, 'SIMULATED_PSP', ?, 'MERCH-RACE', ?, 300, ?, 'EGP', ?, 'PULL', now())
                """)
                .params("psptxn_" + externalId, externalId, gross, gross - 300, status)
                .update();
    }

    private int resultsIn(String batchId) {
        return sql.sql("SELECT COUNT(*) AS total FROM reconciliation_results WHERE batch_id = ?")
                .param(batchId)
                .optional((rs, rowNum) -> rs.getInt("total")).orElse(-1);
    }

    private java.util.Map<String, Integer> batchCounters(String batchId) {
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        out.put("total_subjects", sql.sql("SELECT total_subjects AS total FROM reconciliation_batches "
                + "WHERE id = ?").param(batchId)
                .optional((rs, rowNum) -> rs.getInt("total")).orElse(-1));
        out.put("processed_subjects", sql.sql("SELECT processed_subjects AS total "
                + "FROM reconciliation_batches WHERE id = ?").param(batchId)
                .optional((rs, rowNum) -> rs.getInt("total")).orElse(-1));
        return out;
    }

    private int entryCount() {
        return sql.sql("SELECT COUNT(*) AS total FROM ledger_entries")
                .optional((rs, rowNum) -> rs.getInt("total")).orElse(-1);
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

}
