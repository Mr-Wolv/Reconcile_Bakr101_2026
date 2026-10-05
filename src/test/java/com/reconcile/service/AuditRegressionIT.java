package com.reconcile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconcile.persistence.jdbc.Sql;
import com.reconcile.shared.CurrencyCode;
import com.reconcile.shared.DomainException;
import com.reconcile.shared.Money;
import com.reconcile.shared.ProblemCode;
import com.reconcile.support.SharedPostgres;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Regression tests for defects an independent audit found against the running image while this
 * suite was fully green.
 *
 * <p>They are collected in one class on purpose. The common thread is not the subsystem — it is the
 * <i>shape</i> of the miss. Each defect below was reachable through the public API or the ordinary
 * database, and none of them was reachable through a test, for the same reason three times over:
 * every subject, every batch and every posting the suite exercised had already passed the
 * precondition that the defect lived behind.
 *
 * <ul>
 *   <li>every reconciliation fixture payment had a capture posting, so the capture lookup always
 *       succeeded;</li>
 *   <li>every batch was created with {@code async: false}, so the documented default — which never
 *       ran anything — was never taken;</li>
 *   <li>every balance projection row existed, so the silent no-op on a missing one was never
 *       reached.</li>
 * </ul>
 *
 * <p>A suite whose fixtures all satisfy an invariant cannot falsify the code that maintains it.
 * Each test below removes one of those preconditions on purpose.
 */
@SpringBootTest
class AuditRegressionIT {

    private static final PaymentService.CommandContext CONTEXT = PaymentService.CommandContext.of(
            new PaymentService.Actor("USER", "audit"), "req_audit", null);

    private static final Money GROSS = Money.of(10_000, CurrencyCode.EGP);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedPostgres::jdbcUrl);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        // The scheduler is driven explicitly below rather than waited for, so a background tick
        // cannot race a test that is asserting on a batch it is about to claim.
        registry.add("reconcile.reconciliation.poll-interval", () -> "1h");
    }

    @Autowired
    PaymentService payments;

    @Autowired
    ReconciliationService reconciliation;

    @Autowired
    ReconciliationWorker worker;

    @Autowired
    LedgerService ledger;

    @Autowired
    HealthService health;

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

    // ------------------------------------------------- reconciliation: the never-captured payment

    @Test
    @DisplayName("AUDIT-05: a payment the provider names but that never captured reconciles, not crashes")
    void aNeverCapturedPaymentIsClassifiedRatherThanFatal() {
        // Reachable without any misbehaviour: upsertProviderTransaction writes the provider row
        // BEFORE the payment-state decision, so a `payment.captured` webhook delivered against an
        // already-FAILED payment leaves exactly this shape. The old code then threw
        // IllegalStateException out of expectedView, which escaped runBatch, killed every other
        // subject in the batch and orphaned the batch in RUNNING forever.
        String paymentId = payments.create("MERCH-U", null, GROSS, "SIMULATED_PSP",
                "psp_never_captured", CONTEXT).id();
        payments.fail(paymentId, "DECLINED", "the issuer declined", CONTEXT);
        providerRow("psp_never_captured", "MERCH-U", 10_000, 300, 9_700, "CAPTURED");

        String batchId = createBatch();
        int processed = worker.runBatch(batchId);

        assertThat(processed).as("the subject is processed, not skipped").isEqualTo(1);
        assertThat(outcomeOf(batchId, "SIMULATED_PSP:psp_never_captured"))
                .as("the provider reports money we never booked: that is MISSING_INTERNAL, the "
                        + "finding an operator needs, and it opens a case")
                .isEqualTo("MISSING_INTERNAL");
        assertThat(batchStatus(batchId)).isEqualTo("COMPLETED");
        assertThat(openCases()).as("a discrepancy nobody booked still raises a case").isEqualTo(1);
    }

    @Test
    @DisplayName("AUDIT-06: one unevaluable payment does not cost the whole batch its results")
    void aNeverCapturedPaymentDoesNotSuppressOtherSubjects() {
        // The blast radius is the point. Before the fix this returned HTTP 500 and produced NO
        // results at all, including for subjects that reconciled perfectly.
        String good = payments.create("MERCH-G", null, GROSS, "SIMULATED_PSP", "psp_good", CONTEXT)
                .id();
        payments.authorize(good, CONTEXT);
        payments.capture(good, null, CONTEXT);
        providerRow("psp_good", "MERCH-G", 10_000, 300, 9_700, "CAPTURED");

        String bad = payments.create("MERCH-B", null, GROSS, "SIMULATED_PSP", "psp_bad", CONTEXT)
                .id();
        payments.fail(bad, "DECLINED", "declined", CONTEXT);
        providerRow("psp_bad", "MERCH-B", 10_000, 300, 9_700, "CAPTURED");

        String batchId = createBatch();
        worker.runBatch(batchId);

        assertThat(resultCount(batchId))
                .as("both subjects are answered; the healthy one is not collateral damage")
                .isEqualTo(2);
        assertThat(outcomeOf(batchId, "SIMULATED_PSP:psp_good")).isEqualTo("MATCHED_NOT_SETTLED");
        assertThat(outcomeOf(batchId, "SIMULATED_PSP:psp_bad")).isEqualTo("MISSING_INTERNAL");
    }

    @Test
    @DisplayName("AUDIT-07: the ledger still balances after a never-captured payment is reconciled")
    void reconciliationNeverTouchesTheLedger() {
        String bad = payments.create("MERCH-B", null, GROSS, "SIMULATED_PSP", "psp_bad", CONTEXT)
                .id();
        payments.fail(bad, "DECLINED", "declined", CONTEXT);
        providerRow("psp_bad", "MERCH-B", 10_000, 300, 9_700, "CAPTURED");

        worker.runBatch(createBatch());

        assertThat(ledger.verifyBalances()).as("L7 clean").isEmpty();
        assertThat(sql.sql("SELECT count(*) AS n FROM ledger_transactions").optional(
                (rs, rowNum) -> rs.getInt("n")).orElse(0))
                .as("reconciliation observes; it never posts (spec 04 §7)")
                .isZero();
    }

    // ------------------------------------------------- the async batch that never ran

    @Test
    @DisplayName("AUDIT-08: a batch created with the documented async default is actually run")
    void anAsyncBatchIsPickedUpAndCompleted() {
        String good = payments.create("MERCH-A", null, GROSS, "SIMULATED_PSP", "psp_async", CONTEXT)
                .id();
        payments.authorize(good, CONTEXT);
        payments.capture(good, null, CONTEXT);
        providerRow("psp_async", "MERCH-A", 10_000, 300, 9_700, "CAPTURED");

        String batchId = createBatch();
        assertThat(batchStatus(batchId)).isEqualTo("RUNNING");
        assertThat(resultCount(batchId)).as("created but not yet processed").isZero();

        // Previously: POST returned 202 and nothing ever picked the batch up. There was no scheduler
        // at all, and `reconcile.reconciliation.poll-interval` was bound into configuration and read
        // by nothing. After the lease expired /health turned 503 and took the service out of
        // rotation, caused by calling the documented endpoint once.
        assertThat(worker.runningBatchIds()).contains(batchId);
        worker.poll();

        assertThat(batchStatus(batchId)).isEqualTo("COMPLETED");
        assertThat(resultCount(batchId)).isEqualTo(1);
        assertThat(outcomeOf(batchId, "SIMULATED_PSP:psp_async")).isEqualTo("MATCHED_NOT_SETTLED");
    }

    @Test
    @DisplayName("AUDIT-09: the poller drains the queue and never revisits a finished batch")
    void thePollerDrainsAndDoesNotRepeat() {
        String first = createBatch();
        String second = createBatch();

        worker.poll();

        assertThat(batchStatus(first)).isEqualTo("COMPLETED");
        assertThat(batchStatus(second)).isEqualTo("COMPLETED");
        assertThat(worker.runningBatchIds())
                .as("a COMPLETED batch is never claimed again")
                .doesNotContain(first, second);
    }

    // ------------------------------------------------- the orphaned batch

    @Test
    @DisplayName("AUDIT-10: a batch that cannot complete is FAILED with a reason, never orphaned")
    void aFailedBatchIsMarkedAndExplained() {
        String batchId = createBatch();

        worker.fail(batchId, new IllegalStateException("the engine could not evaluate a subject"));

        assertThat(batchStatus(batchId)).isEqualTo("FAILED");
        assertThat(failureReason(batchId)).contains("IllegalStateException");
        assertThat(failureReason(batchId)).contains("could not evaluate");
        assertThat(sql.sql("""
                SELECT count(*) AS n FROM audit_events
                 WHERE action = 'RECONCILIATION_BATCH_FAILED' AND entity_id = ?
                """).param(batchId).optional((rs, rowNum) -> rs.getInt("n")).orElse(0))
                .as("the failure is in the audit trail, not only in a column")
                .isEqualTo(1);
        assertThat(worker.runningBatchIds())
                .as("a dead batch must not be picked up and retried forever")
                .doesNotContain(batchId);
    }

    @Test
    @DisplayName("AUDIT-11: a FAILED batch stops being reported as stuck by /health")
    void aFailedBatchIsNotAStuckBatch() {
        String batchId = createBatch();
        worker.fail(batchId, new IllegalStateException("boom"));

        assertThat(health.check().checks())
                .as("stuck-batch detection looks for RUNNING only, so a recorded failure is not an "
                        + "unexplained one")
                .filteredOn(check -> check.name().equals("reconciliation"))
                .allSatisfy(check -> assertThat(check.status()).isEqualTo("PASS"));
    }

    // ------------------------------------------------- the missing balance projection

    @Test
    @DisplayName("AUDIT-12: a posting against a missing projection row fails loudly, losing no money")
    void aPostingWithoutAProjectionRowIsRefused() {
        // account_balances carries no immutability trigger, so the row can be deleted by a bad
        // restore or a rogue script. The UPDATE then matched zero rows, the update count was never
        // checked, and the entries committed anyway: real money in the immutable ledger, none in
        // the projection, and /health reported ledger: PASS.
        sql.sql("DELETE FROM account_balances WHERE account_id = 'acct_psp_clearing' "
                + "AND currency = 'EGP'").update();

        String paymentId = payments.create("MERCH-H", null, GROSS, "SIMULATED_PSP", null,
                CONTEXT).id();
        payments.authorize(paymentId, CONTEXT);

        assertThatThrownBy(() -> payments.capture(paymentId, null, CONTEXT))
                .as("this is a corrupted ledger, not a business refusal, so it is a 500 and not a 4xx")
                .isInstanceOf(DomainException.class)
                .satisfies(thrown -> assertThat(((DomainException) thrown).code())
                        .isEqualTo(ProblemCode.INTERNAL_ERROR));

        assertThat(sql.sql("SELECT count(*) AS n FROM ledger_entries WHERE account_code = "
                        + "'PSP_CLEARING'").optional((rs, rowNum) -> rs.getInt("n")).orElse(0))
                .as("the whole posting rolls back; no entry outlives the balance it could not move")
                .isZero();
        assertThat(sql.sql("SELECT count(*) AS n FROM ledger_transactions").optional(
                (rs, rowNum) -> rs.getInt("n")).orElse(0)).isZero();
    }

    @Test
    @DisplayName("AUDIT-13: L7 reports a projection row that is missing, not only one that disagrees")
    void l7DetectsAMissingProjectionRow() {
        String paymentId = payments.create("MERCH-H", null, GROSS, "SIMULATED_PSP", null,
                CONTEXT).id();
        payments.authorize(paymentId, CONTEXT);
        payments.capture(paymentId, null, CONTEXT);

        assertThat(ledger.verifyBalances()).as("clean to begin with").isEmpty();

        sql.sql("DELETE FROM account_balances WHERE account_id = 'acct_psp_clearing' "
                + "AND currency = 'EGP'").update();

        List<LedgerService.BalanceDrift> drift = ledger.verifyBalances();
        assertThat(drift).as("the verification is driven FROM account_balances, so a deleted row "
                + "used to be invisible to it").hasSize(1);
        assertThat(drift.getFirst().isMissingProjection()).isTrue();
        assertThat(drift.getFirst().accountCode()).isEqualTo("PSP_CLEARING");
        assertThat(drift.getFirst().actualEntryCount()).isEqualTo(1);
        assertThat(drift.getFirst().recomputedMinor()).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("AUDIT-14: /health reports a missing projection row as a ledger breach")
    void healthReportsAMissingProjectionRow() {
        String paymentId = payments.create("MERCH-H", null, GROSS, "SIMULATED_PSP", null,
                CONTEXT).id();
        payments.authorize(paymentId, CONTEXT);
        payments.capture(paymentId, null, CONTEXT);

        sql.sql("DELETE FROM account_balances WHERE account_id = 'acct_psp_clearing' "
                + "AND currency = 'EGP'").update();

        assertThat(health.check().status())
                .as("a ledger with committed entries and no balance for them is not healthy")
                .isEqualTo("DOWN");
        assertThat(health.check().checks())
                .filteredOn(check -> check.name().equals("ledger"))
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.status()).isEqualTo("FAIL");
                    assertThat(check.detail()).contains("NO projection row");
                });
    }

    @Test
    @DisplayName("AUDIT-15: a balance row that exists but disagrees is still reported")
    void l7StillDetectsDisagreement() {
        // The other half of L7 must not have been traded away to gain the first half.
        String paymentId = payments.create("MERCH-H", null, GROSS, "SIMULATED_PSP", null,
                CONTEXT).id();
        payments.authorize(paymentId, CONTEXT);
        payments.capture(paymentId, null, CONTEXT);

        sql.sql("UPDATE account_balances SET balance_minor = balance_minor + 1 "
                + "WHERE account_id = 'acct_psp_clearing' AND currency = 'EGP'").update();

        List<LedgerService.BalanceDrift> drift = ledger.verifyBalances();
        assertThat(drift).hasSize(1);
        assertThat(drift.getFirst().isMissingProjection()).isFalse();
        assertThat(drift.getFirst().deltaMinor()).isEqualTo(1L);
    }

    // ------------------------------------------------------------------ helpers

    private String createBatch() {
        Instant end = Instant.now().plusSeconds(3600);
        return reconciliation.createBatch(new ReconciliationService.BatchRequest(
                "SIMULATED_PSP", end.minusSeconds(86_400L * 30), end,
                ReconciliationService.SubjectMode.BOTH, "audit", "req_audit"));
    }

    private void providerRow(String externalId, String merchantReference, long gross, long fee,
            long net, String status) {

        sql.sql("""
                INSERT INTO provider_transactions
                    (id, provider, provider_transaction_id, merchant_reference,
                     gross_amount_minor, fee_amount_minor, net_amount_minor, currency,
                     provider_status, source, captured_at)
                VALUES (?, 'SIMULATED_PSP', ?, ?, ?, ?, ?, 'EGP', ?, 'PULL', now())
                """)
                .params("psptxn_" + externalId, externalId, merchantReference, gross, fee, net,
                        status)
                .update();
    }

    private String outcomeOf(String batchId, String subjectKey) {
        return sql.sql("SELECT outcome FROM reconciliation_results WHERE batch_id = ? "
                        + "AND subject_key = ?")
                .params(batchId, subjectKey)
                .optional((rs, rowNum) -> rs.getString("outcome"))
                .orElse(null);
    }

    private int resultCount(String batchId) {
        return sql.sql("SELECT count(*) AS n FROM reconciliation_results WHERE batch_id = ?")
                .param(batchId).optional((rs, rowNum) -> rs.getInt("n")).orElse(0);
    }

    private String batchStatus(String batchId) {
        return sql.sql("SELECT status FROM reconciliation_batches WHERE id = ?")
                .param(batchId).optional((rs, rowNum) -> rs.getString("status")).orElse(null);
    }

    private String failureReason(String batchId) {
        return sql.sql("SELECT failure_reason FROM reconciliation_batches WHERE id = ?")
                .param(batchId).optional((rs, rowNum) -> rs.getString("failure_reason")).orElse("");
    }

    private int openCases() {
        return sql.sql("SELECT count(*) AS n FROM reconciliation_cases "
                        + "WHERE status IN ('OPEN','INVESTIGATING')")
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0);
    }
}
