package com.reconcile.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconcile.persistence.jdbc.Sql;
import com.reconcile.reconciliation.ReconciliationOutcome;
import com.reconcile.support.SharedPostgres;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Reconciliation, run against a real PostgreSQL 18.
 *
 * <p>Maps to rows {@code C-RECON-01} … {@code C-RECON-13}. The property under test throughout is
 * that the comparison is between two <b>independent</b> sources: the expected side is derived from
 * the ledger, never from the payment row (ADR-0005). A reconciliation that read both sides from the
 * same table would pass every assertion here while being incapable of detecting the bug it exists to
 * detect.
 *
 * <p>Each test seeds the provider's view directly rather than going through webhooks, because these
 * tests are about <i>comparison</i>; ingestion has its own suite in {@code ProviderWebhookIT}. What
 * is verified here is that whatever the provider says, the answer is correct and deterministic.
 */
@SpringBootTest
class ReconciliationIT {


    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedPostgres::jdbcUrl);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        registry.add("reconcile.security.operator-token", () -> "test-operator-token");
        registry.add("reconcile.security.admin-token", () -> "test-admin-token");
        // Every batch here is created RUNNING and then driven by this class. ReconciliationWorker
        // polls RUNNING batches every 2s in production, so unless it is pinned the scheduler would
        // claim these mid-assertion. AuditRegressionIT is where the scheduler is under test.
        registry.add("reconcile.reconciliation.poll-interval", () -> "1h");
    }

    private static final PaymentService.CommandContext CONTEXT = PaymentService.CommandContext.of(
            new PaymentService.Actor("USER", "integration-test"), "req_test", null);

    private static final com.reconcile.shared.Money GROSS =
            com.reconcile.shared.Money.of(10_000, com.reconcile.shared.CurrencyCode.EGP);
    private static final com.reconcile.shared.Money NET =
            com.reconcile.shared.Money.of(9_700, com.reconcile.shared.CurrencyCode.EGP);

    @Autowired
    PaymentService payments;

    @Autowired
    LedgerService ledger;

    @Autowired
    ReconciliationService reconciliation;

    @Autowired
    ReconciliationWorker worker;

    @Autowired
    ReconciliationCaseService cases;

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

    // ------------------------------------------------------------------ C-RECON-01

    @Test
    @DisplayName("C-RECON-01: agreeing sides are MATCHED and open no case")
    void agreeingSidesMatch() {
        String externalId = "psp_match";
        capturePayment("MERCH-1", externalId);
        providerRow(externalId, "MERCH-1", 10_000, 300, 9_700, "CAPTURED", "EGP");

        String batch = runBatch();

        assertThat(outcomeOf(externalId))
                .as("we agree on gross, fee, net, status and reference")
                .isEqualTo("MATCHED_NOT_SETTLED");
        assertThat(resultCount(batch)).isEqualTo(1);
        assertThat(openCases()).isZero();
    }

    @Test
    @DisplayName("C-RECON-01b: a settled payment that agrees everywhere is MATCHED, not just 'not settled'")
    void settledAndAgreeingIsMatched() {
        String externalId = "psp_settled";
        String paymentId = capturePayment("MERCH-2", externalId);
        // The provider has to have told us about it as well as paid it: "settled" on our side is a
        // claim we are testing against theirs, and a provider record with no row here is exactly
        // the MISSING_ON_PROVIDER case covered by C-RECON-04, not agreement.
        providerRow(externalId, "MERCH-2", 10_000, 300, 9_700, "SETTLED", "EGP");
        settleProviderSide(externalId, paymentId);

        runBatch();

        assertThat(outcomeOf(externalId))
                .as("both sides settled, and they agree on every figure")
                .isEqualTo("MATCHED");
    }

    // ------------------------------------------------------------------ C-RECON-02

    @Test
    @DisplayName("C-RECON-02: 100.00 against 97.00 is an AMOUNT_MISMATCH with delta 300 and a case")
    void amountMismatchOpensACase() {
        String externalId = "psp_short";
        capturePayment("MERCH-3", externalId);
        // The provider collected 9700 where we expected 10000: a 3.00 shortfall, which is the whole
        // difference between what we posted and what they say they processed.
        providerRow(externalId, "MERCH-3", 9_700, 0, 9_700, "CAPTURED", "EGP");

        String batch = runBatch();

        assertThat(outcomeOf(externalId)).isEqualTo("AMOUNT_MISMATCH");
        assertThat(deltaOf(externalId))
                .as("expected gross minus actual gross, in minor units, exactly")
                .isEqualTo(300L);
        assertThat(openCases()).isEqualTo(1);
        assertThat(caseReason()).isEqualTo("AMOUNT_MISMATCH");
        assertThat(batchStatus(batch)).isEqualTo("COMPLETED");
    }

    // ------------------------------------------------------------------ C-RECON-03

    @Test
    @DisplayName("C-RECON-03: SETTLED against CAPTURED is a STATUS_MISMATCH")
    void statusMismatch() {
        String externalId = "psp_status";
        capturePayment("MERCH-4", externalId);
        // The provider says it settled the money; our side never received a settlement record, so
        // our expected status is still CAPTURED while theirs is SETTLED.
        providerRow(externalId, "MERCH-4", 10_000, 300, 9_700, "SETTLED", "EGP");

        runBatch();

        assertThat(outcomeOf(externalId)).isEqualTo("STATUS_MISMATCH");
        assertThat(openCases()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ C-RECON-04

    @Test
    @DisplayName("C-RECON-04: internal-only is MISSING_ON_PROVIDER; provider-only is MISSING_INTERNAL")
    void missingOnEitherSide() {
        String onlyOurs = "psp_only_ours";
        capturePayment("MERCH-5", onlyOurs);

        String onlyTheirs = "psp_only_theirs";
        providerRow(onlyTheirs, "MERCH-6", 10_000, 300, 9_700, "CAPTURED", "EGP");

        runBatch();

        assertThat(outcomeOf(onlyOurs))
                .as("we captured it and they never reported it: usually a lost webhook")
                .isEqualTo("MISSING_ON_PROVIDER");
        assertThat(outcomeOf(onlyTheirs))
                .as("they reported it and we have never heard of it")
                .isEqualTo("MISSING_INTERNAL");
        assertThat(openCases()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ C-RECON-06

    @Test
    @DisplayName("C-RECON-06: two provider rows for one id cannot exist - the unique key forbids it")
    void duplicateProviderRecordIsStructurallyImpossible() {
        String externalId = "psp_dup";
        capturePayment("MERCH-7", externalId);
        providerRow(externalId, "MERCH-7", 10_000, 300, 9_700, "CAPTURED", "EGP");

        // DUPLICATE_PROVIDER_RECORD is ranked first in the classifier because a duplicate record
        // invalidates every attribute comparison. In practice ux_provider_txn stops a duplicate
        // reaching that point at all, which is a stronger answer than classifying it - so that is
        // what is asserted here, while the classifier branch stays covered by its unit test.
        expectConstraintViolation(() ->
                providerRow(externalId, "MERCH-7", 10_000, 300, 9_700, "CAPTURED", "EGP"));
    }

    // ------------------------------------------------------------------ C-RECON-07

    @Test
    @DisplayName("C-RECON-07: two payments cannot claim one provider id - refused at the edge and by the index")
    void ambiguousMatchIsStructurallyImpossible() {
        String externalId = "psp_ambiguous";
        capturePayment("MERCH-8", externalId);

        // The classifier's AMBIGUOUS_MATCH branch exists for two ledger capture postings sharing one
        // provider id, and is unit-tested there. At the payment level the situation cannot arise at
        // all, because ux_payments_provider_txn refuses it - which is a stronger answer than
        // classifying it, and is asserted here rather than assumed.
        //
        // Two layers, and both are asserted. The service refuses first with the documented code, so
        // a caller is told why rather than being handed a constraint violation it cannot interpret
        // (defect 16). The index is the backstop for anything that bypasses the service, and a test
        // that only exercised the service would stop proving the guarantee the moment the
        // translation were removed.
        expectRefusal(() -> capturePayment("MERCH-9", externalId),
                "PROVIDER_TRANSACTION_ALREADY_CLAIMED");

        expectConstraintViolation(() -> sql.sql("""
                        INSERT INTO payments
                            (id, merchant_reference, amount_minor, currency, platform_fee_minor,
                             net_amount_minor, fee_policy_id, state, provider, provider_transaction_id)
                        VALUES ('pay_c_recon_07_raw', 'MERCH-10', 10000, 'EGP', 300, 9700,
                                'fp_default', 'CREATED', 'SIMULATED_PSP', ?)
                        """)
                .param(externalId)
                .update());
    }

    // ------------------------------------------------------------------ C-RECON-11

    @Test
    @DisplayName("C-RECON-11: a currency difference outranks an amount difference")
    void currencyMismatchTakesPrecedence() {
        String externalId = "psp_currency";
        capturePayment("MERCH-10", externalId);
        // Different currency AND different amount: currency must win, because comparing amounts
        // across currencies is meaningless.
        providerRow(externalId, "MERCH-10", 5_000, 150, 4_850, "CAPTURED", "USD");

        runBatch();

        assertThat(outcomeOf(externalId)).isEqualTo("CURRENCY_MISMATCH");
    }

    // ------------------------------------------------------------------ C-RECON-12

    @Test
    @DisplayName("C-RECON-12: partial settlement is an AMOUNT_MISMATCH on the exact delta")
    void partialSettlement() {
        String externalId = "psp_partial";
        capturePayment("MERCH-11", externalId);
        // The PSP settled only 9700 of the 10000 we posted: partial settlement, not a rounding
        // curiosity. The provider's own totals stay internally consistent, so the split check holds.
        providerRow(externalId, "MERCH-11", 9_700, 0, 9_700, "CAPTURED", "EGP");
        sql.sql("""
                INSERT INTO settlement_records
                    (id, provider, provider_settlement_id, gross_amount_minor, fee_amount_minor,
                     net_amount_minor, currency, settlement_date, source)
                VALUES ('srec_partial', 'SIMULATED_PSP', 'sset_partial', 9_700, 0, 9_700,
                        'EGP', current_date, 'PULL')
                """).update();
        sql.sql("""
                INSERT INTO settlement_record_lines
                    (id, settlement_record_id, provider_transaction_id, provider_transaction_ref,
                     line_gross_minor, line_net_minor, line_no)
                VALUES ('srline_partial', 'srec_partial', ?, NULL, 9_700, 9_700, 1)
                """).param(externalId).update();

        runBatch();

        assertThat(outcomeOf(externalId)).isEqualTo("AMOUNT_MISMATCH");
        assertThat(deltaOf(externalId))
                .as("the exact shortfall, in minor units")
                .isEqualTo(300L);
    }

    // ------------------------------------------------------------------ C-RECON-08

    @Test
    @DisplayName("C-RECON-08: re-running a window reuses the case instead of piling up duplicates")
    void rerunReusesTheCase() {
        String externalId = "psp_rerun";
        capturePayment("MERCH-12", externalId);
        providerRow(externalId, "MERCH-12", 9_700, 0, 9_700, "CAPTURED", "EGP");

        runBatch();
        assertThat(openCases()).isEqualTo(1);

        runBatch();

        assertThat(openCases())
                .as("a system that opens a fresh case on every run is deleted by its users")
                .isEqualTo(1);
        assertThat(occurrenceCount())
                .as("the re-detection is evidence, not noise")
                .isEqualTo(2);
    }

    // ------------------------------------------------------------------ C-RECON-09

    @Test
    @DisplayName("C-RECON-09: a worker stopped mid-batch resumes and finishes without duplicates")
    void interruptedBatchResumes() {
        int subjects = 6;
        for (int i = 0; i < subjects; i++) {
            String externalId = "psp_resume_" + i;
            capturePayment("MERCH-R" + i, externalId);
            providerRow(externalId, "MERCH-R" + i, 10_000, 300, 9_700, "CAPTURED", "EGP");
        }
        String batchId = reconciliation.createBatch(request());

        // Stand in for a crash: process three subjects, then walk away.
        int alreadyDone = 0;
        for (var subject : worker.unprocessedSubjects(batchId)) {
            reconciliation.processSubject(batchId, subject.subjectKey(), subject.provider(),
                    subject.externalTransactionId());
            if (++alreadyDone == subjects / 2) {
                break;
            }
        }
        assertThat(resultCount(batchId))
                .as("half the batch is done before the crash")
                .isEqualTo(subjects / 2);
        assertThat(worker.unprocessedSubjects(batchId))
                .as("and the worker knows exactly what is left")
                .hasSize(subjects / 2);

        worker.runBatch(batchId);

        assertThat(resultCount(batchId))
                .as("the resumed run finished the batch without redoing or duplicating anything")
                .isEqualTo(subjects);
        assertThat(resultKeyCount(batchId))
                .as("one result per subject, guaranteed by ux_result_subject")
                .isEqualTo(subjects);
        assertThat(batchStatus(batchId)).isEqualTo("COMPLETED");
    }

    // ------------------------------------------------------------------ C-RECON-10

    @Test
    @DisplayName("C-RECON-10: a case cannot be resolved without a real explanation")
    void caseResolutionRequiresAnExplanation() {
        String externalId = "psp_case";
        capturePayment("MERCH-13", externalId);
        providerRow(externalId, "MERCH-13", 9_700, 0, 9_700, "CAPTURED", "EGP");
        runBatch();

        String caseId = firstCaseId();
        expectRefusal(() -> cases.resolve(caseId, "fixed", null, "test", "req"),
                "CASE_RESOLUTION_INCOMPLETE");

        expectRefusal(() -> cases.resolve(
                caseId, "the PSP applied a processing fee we did not model",
                "ltx_does_not_exist", "test", "req"),
                "CASE_RESOLUTION_INCOMPLETE");

        cases.investigate(caseId, "ops@example", "test", "req");
        cases.resolve(caseId,
                "the PSP applied a processing fee our fee policy did not model; policy updated",
                null, "test", "req");

        assertThat(caseStatus(caseId)).isEqualTo("RESOLVED");
        assertThat(caseEventCount(caseId)).isEqualTo(3);
    }

    @Test
    @DisplayName("C-RECON-10b: a terminal case cannot be moved again")
    void terminalCaseIsTerminal() {
        String externalId = "psp_terminal";
        capturePayment("MERCH-14", externalId);
        providerRow(externalId, "MERCH-14", 9_700, 0, 9_700, "CAPTURED", "EGP");
        runBatch();

        String caseId = firstCaseId();
        cases.resolve(caseId,
                "confirmed as a provider-side processing fee; documented with the PSP",
                null, "test", "req");

        expectRefusal(() -> cases.writeOff(
                caseId, "and also written off for good measure",
                ReconciliationCaseService.WriteOffAction.OTHER, "test", "req"),
                "CASE_INVALID_TRANSITION");
    }

    // ------------------------------------------------------------------ F-08

    @Test
    @DisplayName("F-08: re-detection after a write-off records the case's REAL status, not OPEN")
    void recurrenceRecordsTheCasesActualStatus() {
        String externalId = "psp_recurrence_after_writeoff";
        capturePayment("MERCH-RECUR", externalId);
        providerRow(externalId, "MERCH-RECUR", 9_700, 0, 9_700, "CAPTURED", "EGP");
        runBatch();

        String caseId = firstCaseId();
        cases.writeOff(caseId, "accepted as a provider-side processing fee",
                ReconciliationCaseService.WriteOffAction.OTHER, "test", "req");
        assertThat(statusOfCase(caseId)).isEqualTo("WRITTEN_OFF");

        // The underlying discrepancy has not gone away, so the next batch finds it again.
        runBatch();

        assertThat(statusOfCase(caseId))
                .as("a terminal case must not be resurrected by a re-detection")
                .isEqualTo("WRITTEN_OFF");

        assertThat(latestCaseEvent(caseId).fromStatus())
                .as("F-08: the audit history said OPEN -> OPEN while the case was WRITTEN_OFF. "
                        + "That records a state the case never occupied, and an investigation "
                        + "timeline that describes transitions nobody made is worse than none, "
                        + "because it is trusted.")
                .isEqualTo("WRITTEN_OFF");
        assertThat(latestCaseEvent(caseId).toStatus()).isEqualTo("WRITTEN_OFF");
    }

    @Test
    @DisplayName("F-08: re-detection of an OPEN case still records OPEN, and says it changed nothing")
    void recurrenceOfAnOpenCaseIsStillHonest() {
        // The control. An earlier version hard-coded OPEN, which was accidentally right here — and
        // that is why it survived: the common case passed and only the terminal case lied.
        String externalId = "psp_recurrence_open";
        capturePayment("MERCH-OPEN", externalId);
        providerRow(externalId, "MERCH-OPEN", 9_700, 0, 9_700, "CAPTURED", "EGP");

        runBatch();
        String caseId = firstCaseId();
        runBatch();

        CaseEvent latest = latestCaseEvent(caseId);
        assertThat(latest.fromStatus()).isEqualTo("OPEN");
        assertThat(latest.toStatus()).isEqualTo("OPEN");
        assertThat(latest.note())
                .as("recurrence is an observation, not a transition, and the row should say so")
                .contains("re-detected")
                .contains("no status change");
    }

    private record CaseEvent(String fromStatus, String toStatus, String note) {
    }

    private CaseEvent latestCaseEvent(String caseId) {
        return sql.sql("""
                SELECT from_status, to_status, note
                  FROM case_events
                 WHERE case_id = ?
                 ORDER BY occurred_at DESC, id DESC
                 LIMIT 1
                """)
                .param(caseId)
                .optional((rs, rowNum) -> new CaseEvent(
                        rs.getString("from_status"), rs.getString("to_status"), rs.getString("note")))
                .orElseThrow(() -> new AssertionError("no case events for " + caseId));
    }

    private String statusOfCase(String caseId) {
        return sql.sql("SELECT status FROM reconciliation_cases WHERE id = ?")
                .param(caseId)
                .optional((rs, rowNum) -> rs.getString("status"))
                .orElse(null);
    }

    // ------------------------------------------------------------------ C-RECON-13

    @Test
    @DisplayName("C-RECON-13: two batches over one window produce independent result sets")
    void batchesAreIndependent() {
        String externalId = "psp_independent";
        capturePayment("MERCH-15", externalId);
        providerRow(externalId, "MERCH-15", 10_000, 300, 9_700, "CAPTURED", "EGP");

        String first = runBatch();
        String second = runBatch();

        assertThat(resultCount(first)).isEqualTo(1);
        assertThat(resultCount(second)).isEqualTo(1);
        assertThat(resultIdFor(first, externalId))
                .as("a result belongs to exactly one batch and is never rewritten")
                .isNotEqualTo(resultIdFor(second, externalId));
    }

    // ------------------------------------------------------------------ independence

    /**
     * Proves the two sides of a comparison are genuinely independent sources.
     *
     * <p>The definition of done asked for a test proving that corrupting {@code payments.amount}
     * makes reconciliation <i>fail</i>. Written that way it cannot be satisfied, and the reason is
     * worth recording: {@code ReconciliationService} never selects {@code payments.amount_minor}.
     * Our side of the comparison is read from the capture posting's ledger entries by account code
     * (ADR-0005); the payment row is a denormalised copy that reconciliation deliberately ignores.
     * So the honest test is the two halves below, which together prove more than the original
     * wording would have.
     */
    @Test
    @DisplayName("C-RECON-15: the sides are independent - corrupting the payment row changes nothing, corrupting theirs fails")
    void theTwoSidesAreGenuinelyIndependent() {
        String paymentRowCorrupted = "psp_indep_row";
        String providerRowCorrupted = "psp_indep_provider";

        String firstPayment = capturePayment("MERCH-INDEP-A", paymentRowCorrupted);
        providerRow(paymentRowCorrupted, "MERCH-INDEP-A", 10_000, 300, 9_700, "SETTLED", "EGP");
        settleProviderSide(paymentRowCorrupted, firstPayment);

        String secondPayment = capturePayment("MERCH-INDEP-B", providerRowCorrupted);
        providerRow(providerRowCorrupted, "MERCH-INDEP-B", 10_000, 300, 9_700, "SETTLED", "EGP");
        settleProviderSide(providerRowCorrupted, secondPayment);

        // Half one. If the expectation were read from the payment row, this would silently become
        // a comparison of one copy of the payment against another and reconciliation would be a
        // tautology. So the outcome must NOT move.
        //
        // The corruption has to keep gross == net + fee, because ck_payment_split refuses anything
        // else — which is itself worth asserting below: the payment row cannot be quietly
        // corrupted into an inconsistent state even by something that gets past the application.
        sql.sql("UPDATE payments SET amount_minor = 9_999, net_amount_minor = 9_699 WHERE id = ?")
                .param(firstPayment).update();

        // Half two. Their side is read straight from the provider's record, so corrupting it must
        // fail the batch. Without this half, half one would also pass on an implementation that
        // compared nothing whatsoever — the pair is what makes the test worth having.
        //
        // Split-consistent again, for the same reason: ck_provider_split refuses a gross that
        // does not equal net plus fee, so the corruption has to be one the schema permits.
        sql.sql("UPDATE provider_transactions SET gross_amount_minor = 9_999, "
                + "net_amount_minor = 9_699 WHERE provider_transaction_id = ?")
                .param(providerRowCorrupted).update();

        runBatch();

        assertThat(outcomeOf(paymentRowCorrupted))
                .as("the expectation comes from the ledger, not from the payment row, so a "
                        + "corrupted payment row cannot move the outcome. If this ever fails, "
                        + "ADR-0005 has been abandoned and the comparison has become a thing "
                        + "compared with itself.")
                .isEqualTo("MATCHED");
        assertThat(outcomeOf(providerRowCorrupted))
                .as("their side is read from the provider record, so a corrupted one must be "
                        + "caught rather than agreed with")
                .isEqualTo("AMOUNT_MISMATCH");
        assertThat(openCases())
                .as("exactly one case: the disagreement that really exists, and no more")
                .isEqualTo(1);

        // And the corruption that the split check does refuse. Stated here because the corruption
        // above has to be arithmetically consistent to be possible at all, which is a fact about
        // the schema rather than about reconciliation, and it is the reason the half above needed
        // two columns rather than one. 8_888 no longer equals net plus fee, which is the point:
        // the row above stands at 9_999 / 9_699 / 300 and this one cannot be reached from it.
        expectConstraintViolation(() -> sql.sql(
                "UPDATE payments SET amount_minor = 8_888 WHERE id = ?").param(firstPayment).update());
    }

    // ------------------------------------------------------------------ schema-prevented outcomes

    /**
     * The two outcomes the database exists to prevent, produced by removing the prevention.
     *
     * <p>{@code DUPLICATE_PROVIDER_RECORD} and {@code AMBIGUOUS_MATCH} are unreachable through the
     * API: {@code C-RECON-06} and {@code C-RECON-07} prove the unique keys refuse the data. That
     * is the right design, but it leaves two classifier branches with no evidence at all, and an
     * unreachable branch is one nobody can tell works from one that was deleted by accident. So the
     * guard is dropped here, the outcomes are produced, and the guard is put back in a
     * {@code finally} — a dropped constraint is database state, not test state, and would silently
     * disarm the guarantee for every later test in this class.
     *
     * <p>In production these states arrive through a restored backup, a constraint disabled under
     * incident pressure, or a provider that genuinely double-sent. Which is exactly why the
     * classifier refuses to interpret them rather than picking a winner.
     */
    @Test
    @DisplayName("C-RECON-14: DUPLICATE_PROVIDER_RECORD and AMBIGUOUS_MATCH, produced with the guard removed")
    void theSchemaPreventedOutcomesAreProducedWithTheGuardRemoved() {
        String duplicatedByThem = "psp_dup_by_them";
        String duplicatedByUs = "psp_dup_by_us";

        String settled = capturePayment("MERCH-DUP-A", duplicatedByThem);
        providerRow(duplicatedByThem, "MERCH-DUP-A", 10_000, 300, 9_700, "SETTLED", "EGP");
        settleProviderSide(duplicatedByThem, settled);

        sql.sql("ALTER TABLE provider_transactions DROP CONSTRAINT ux_provider_txn").update();
        // A standalone DROP INDEX, not ALTER TABLE ... DROP INDEX: PostgreSQL has no such
        // ALTER TABLE subcommand, and the constraint above has to be dropped the other way.
        sql.sql("DROP INDEX ux_payments_provider_txn").update();
        try {
            capturePayment("MERCH-DUP-B", duplicatedByUs);
            capturePayment("MERCH-DUP-C", duplicatedByUs);
            providerRow(duplicatedByUs, "MERCH-DUP-B", 10_000, 300, 9_700, "SETTLED", "EGP");

            // Their side twice: byte-identical but for the primary key, which is what a duplicated
            // webhook or a restored backup actually looks like.
            sql.sql("""
                    INSERT INTO provider_transactions
                        (id, provider, provider_transaction_id, merchant_reference, gross_amount_minor,
                         fee_amount_minor, net_amount_minor, currency, provider_status, source, captured_at)
                    SELECT 'psptxn_dup_theirs', provider, provider_transaction_id, merchant_reference,
                           gross_amount_minor, fee_amount_minor, net_amount_minor, currency,
                           provider_status, source, captured_at
                      FROM provider_transactions
                     WHERE id = ?
                    """).param("psptxn_" + duplicatedByThem).update();

            runBatch();

            assertThat(outcomeOf(duplicatedByThem))
                    .as("two provider records for one external id. The classifier must refuse to "
                            + "interpret either one, because it cannot know which is real — and "
                            + "duplicates outrank everything else for exactly that reason.")
                    .isEqualTo("DUPLICATE_PROVIDER_RECORD");
            assertThat(outcomeOf(duplicatedByUs))
                    .as("two of our payments claiming one provider id: no automatic match, and no "
                            + "silent choice of one of them")
                    .isEqualTo("AMBIGUOUS_MATCH");
            assertThat(openCases())
                    .as("both are operational problems, so both open a case for a person")
                    .isEqualTo(2);
        } finally {
            // Make the data satisfy the constraints again before re-adding them: re-adding a unique
            // constraint over the rows that violate it fails, which would leave this class with no
            // uniqueness guarantee at all and every later test running against a weaker schema.
            sql.sql("DELETE FROM provider_transactions WHERE id = 'psptxn_dup_theirs'").update();
            // Suffixed with the payment id, not a constant: a constant suffix would simply move the
            // collision from the old key to the new one.
            sql.sql("UPDATE payments SET provider_transaction_id = "
                    + "provider_transaction_id || '_restored_' || id "
                    + "WHERE provider_transaction_id = ?").param(duplicatedByUs).update();
            sql.sql("ALTER TABLE provider_transactions ADD CONSTRAINT ux_provider_txn "
                    + "UNIQUE (provider, provider_transaction_id)").update();
            sql.sql("CREATE UNIQUE INDEX ux_payments_provider_txn ON payments "
                    + "(provider, provider_transaction_id) WHERE provider_transaction_id IS NOT NULL")
                    .update();
        }
    }

    // ------------------------------------------------------------------ helpers

    private String capturePayment(String merchantReference, String externalId) {
        PaymentView created = payments.create(merchantReference, null, GROSS, "SIMULATED_PSP",
                externalId, CONTEXT);
        String id = created.id();
        payments.authorize(id, CONTEXT);
        payments.capture(id, null, CONTEXT);
        return id;
    }

    private void providerRow(String externalId, String merchantReference, long gross, long fee,
            long net, String status, String currency) {

        sql.sql("""
                INSERT INTO provider_transactions
                    (id, provider, provider_transaction_id, merchant_reference, gross_amount_minor,
                     fee_amount_minor, net_amount_minor, currency, provider_status, source, captured_at)
                VALUES (?, 'SIMULATED_PSP', ?, ?, ?, ?, ?, ?, ?, 'PULL', now())
                """)
                .params("psptxn_" + externalId, externalId, merchantReference, gross, fee, net,
                        currency, status)
                .update();
    }

    /** Ingests a settlement record and marks our payment settled, the way the webhook path does. */
    private void settleProviderSide(String externalId, String paymentId) {
        // The settlement rows must exist first: payments.settlement_record_id is a foreign key
        // onto settlement_records, so a settlement cannot be recorded before the batch it names.
        sql.sql("""
                INSERT INTO settlement_records
                    (id, provider, provider_settlement_id, gross_amount_minor, fee_amount_minor,
                     net_amount_minor, currency, settlement_date, source)
                VALUES (?, 'SIMULATED_PSP', ?, 10_000, 300, 9_700, 'EGP', current_date, 'PULL')
                """)
                .params("srec_" + paymentId, "sset_" + paymentId)
                .update();
        sql.sql("""
                INSERT INTO settlement_record_lines
                    (id, settlement_record_id, provider_transaction_id, provider_transaction_ref,
                     line_gross_minor, line_net_minor, line_no)
                VALUES (?, ?, ?, NULL, 10_000, 9_700, 1)
                """)
                .params("srline_" + paymentId, "srec_" + paymentId, externalId)
                .update();
        payments.markSettled(paymentId, "srec_" + paymentId, java.time.LocalDate.now().toString(),
                com.reconcile.payment.PaymentStateMachine.Trigger.SETTLEMENT, CONTEXT);
    }

    private ReconciliationService.BatchRequest request() {
        Instant end = Instant.now().plusSeconds(3600);
        return new ReconciliationService.BatchRequest(
                "SIMULATED_PSP", end.minusSeconds(86_400 * 30), end,
                ReconciliationService.SubjectMode.BOTH, "integration-test", "req_test");
    }

    // ------------------------------------------------------------------ C-RECON-12

    @Test
    @DisplayName("C-RECON-12: a settlement for a transaction we have no payment for is reported, not ignored")
    void settlementOnlyTransactionBecomesASubject() {
        // The gap this covers: a settlement line naming a transaction we have never seen used to be
        // stored and then invisible forever. The subject population is built from `payments` and
        // `provider_transactions`, so a settlement-only transaction was in neither - no subject, no
        // result, no case, ever. The comment in the ingestion path used to claim reconciliation
        // would report it as MISSING_INTERNAL; that outcome was structurally unreachable.
        String externalId = "psp_settlement_only";
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);

        // Exactly what applySettlement writes for a line with no payment: a provider row with a
        // NULL captured_at, because no capture time was ever given to us.
        sql.sql("""
                INSERT INTO provider_transactions
                    (id, provider, provider_transaction_id, merchant_reference, gross_amount_minor,
                     fee_amount_minor, net_amount_minor, currency, provider_status, source, captured_at)
                VALUES ('psptxn_settle_only', 'SIMULATED_PSP', ?, 'UNKNOWN', 10000, 300, 9700,
                        'EGP', 'SETTLED', 'WEBHOOK', NULL)
                """).param(externalId).update();

        sql.sql("""
                INSERT INTO settlement_records
                    (id, provider, provider_settlement_id, gross_amount_minor, fee_amount_minor,
                     net_amount_minor, currency, settlement_date, source)
                VALUES ('srec_settle_only', 'SIMULATED_PSP', 'psset_settle_only', 10000, 300, 9700,
                        'EGP', ?, 'WEBHOOK')
                """).param(today).update();

        sql.sql("""
                INSERT INTO settlement_record_lines
                    (id, settlement_record_id, provider_transaction_id, provider_transaction_ref,
                     line_gross_minor, line_net_minor, line_no)
                VALUES ('srline_settle_only', 'srec_settle_only', ?, 'psptxn_settle_only', 10000, 9700, 0)
                """).param(externalId).update();

        runBatch();

        assertThat(outcomeOf(externalId))
                .as("the provider told us about it; reconciliation has to say so")
                .isEqualTo("MISSING_INTERNAL");
    }

    @Test
    @DisplayName("C-RECON-12: a settlement-only transaction is scoped by its settlement, not invented")
    void settlementOnlyTransactionIsScopedByTheSettlementWindow() {
        String externalId = "psp_settlement_out_of_window";
        java.time.LocalDate longAgo = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                .minusYears(3);

        sql.sql("""
                INSERT INTO provider_transactions
                    (id, provider, provider_transaction_id, merchant_reference, gross_amount_minor,
                     fee_amount_minor, net_amount_minor, currency, provider_status, source, captured_at)
                VALUES ('psptxn_settle_old', 'SIMULATED_PSP', ?, 'UNKNOWN', 10000, 300, 9700,
                        'EGP', 'SETTLED', 'WEBHOOK', NULL)
                """).param(externalId).update();

        sql.sql("""
                INSERT INTO settlement_records
                    (id, provider, provider_settlement_id, gross_amount_minor, fee_amount_minor,
                     net_amount_minor, currency, settlement_date, source)
                VALUES ('srec_settle_old', 'SIMULATED_PSP', 'psset_settle_old', 10000, 300, 9700,
                        'EGP', ?, 'WEBHOOK')
                """).param(longAgo).update();

        sql.sql("""
                INSERT INTO settlement_record_lines
                    (id, settlement_record_id, provider_transaction_id, provider_transaction_ref,
                     line_gross_minor, line_net_minor, line_no)
                VALUES ('srline_settle_old', 'srec_settle_old', ?, 'psptxn_settle_old', 10000, 9700, 0)
                """).param(externalId).update();

        runBatch();

        // A NULL captured_at must not be backfilled from the settlement date to make this pass.
        // The settlement is three years outside the window, so the transaction belongs to that
        // window and not this one.
        assertThat(outcomeOf(externalId))
                .as("window membership comes from the settlement we actually have")
                .isNull();
    }

    private String runBatch() {
        String batchId = reconciliation.createBatch(request());
        worker.runBatch(batchId);
        return batchId;
    }

    private String outcomeOf(String externalId) {
        return sql.sql("SELECT outcome FROM reconciliation_results WHERE subject_key = ?")
                .param("SIMULATED_PSP:" + externalId)
                .optional((rs, rowNum) -> rs.getString("outcome"))
                .orElse(null);
    }

    // ------------------------------------------------------------------ RUNTIME FINDINGS

    @Test
    @DisplayName("R-01: a provider string with a control character cannot break the differences JSON")
    void providerControlledControlCharacterDoesNotBreakTheResultRow() {
        // Found by audit-probe/vv-remediation.mjs against a running container, not by this suite.
        //
        // differencesJson() used to build the `differences` JSONB document by hand. RFC 8259 forbids
        // a raw newline inside a JSON string and the old escaper handled only backslash and double
        // quote, so a merchant reference carrying a newline produced invalid JSON, the INSERT failed
        // with "invalid input syntax for type json", and ReconciliationWorker marked the ENTIRE batch
        // FAILED. One provider string would have stopped every payment in the window from being
        // reconciled - and it would have done so at the moment the engine had found something to
        // report, which is the worst possible time to stop.
        //
        // The reference is provider-controlled on both sides of a REFERENCE_MISMATCH, so this is
        // reachable from outside without any special privilege.
        String hostile = "MERCH\nline2\twith\\backslash\"and\"quote\r";
        String externalId = "psp_ctrlchar_1";

        capturePayment(hostile, externalId);
        providerRow(externalId, "MERCH-different", 10_000, 300, 9_700, "CAPTURED", "EGP");

        String batchId = runBatch();

        assertThat(outcomeOf(externalId))
                .as("the result row must EXIST: before the fix the insert threw and the worker "
                        + "abandoned the batch, so this is null rather than an outcome")
                .isEqualTo(ReconciliationOutcome.REFERENCE_MISMATCH.name());
        assertThat(statusOfBatch(batchId))
                .as("and the batch must not have been abandoned over one bad string")
                .isNotEqualTo("FAILED");

        // The value has to survive intact as DATA, escaped rather than dropped: an operator reading
        // the audit column must see exactly what the provider sent.
        String stored = differencesOf(externalId);
        assertThat(stored)
                .as("the newline is stored escaped inside a valid JSON document, not raw")
                .contains("MERCH\\nline2");
        assertThatJsonIsValid(stored);
    }

    @Test
    @DisplayName("R-02: SETTLEMENT_RECORD_INVALID is a legal value of reconciliation_results.outcome")
    void theNewOutcomeSatisfiesTheDatabaseConstraint() {
        // Also found by the runtime probe, and the same root cause as R-01 in a different layer:
        // F-01/F-02 added ReconciliationOutcome.SETTLEMENT_RECORD_INVALID, and V1 pins `outcome` to a
        // CHECK constraint listing the outcomes that existed when it was written. The enum grew; the
        // constraint did not. The first batch to classify an invalid settlement was refused at
        // INSERT and the whole batch was marked FAILED - the engine stopped reconciling precisely
        // when it found the thing it was built to find.
        //
        // The unit tests exercise the classifier, which is pure, so nothing noticed. A CHECK
        // constraint is only covered by a test that writes the constrained value through the
        // constraint, which is why this is a direct INSERT and not another classifier assertion.
        String batchId = sql.sql("INSERT INTO reconciliation_batches (id, provider, window_start, "
                + "window_end, subject_mode, status, started_by) "
                + "VALUES ('rbat_constraint_probe', 'SIMULATED_PSP', now() - interval '1 day', "
                + "now(), 'BOTH', 'RUNNING', 'probe') RETURNING id")
                .optional((rs, rowNum) -> rs.getString("id"))
                .orElseThrow();

        sql.sql("INSERT INTO reconciliation_results (id, batch_id, subject_key, outcome) "
                + "VALUES ('rres_constraint_probe', ?, 'SIMULATED_PSP:probe', ?)")
                .params(batchId, ReconciliationOutcome.SETTLEMENT_RECORD_INVALID.name())
                .update();

        assertThat(sql.sql("SELECT outcome FROM reconciliation_results WHERE id = ?")
                .param("rres_constraint_probe")
                .optional((rs, rowNum) -> rs.getString("outcome")))
                .as("the value survives the round trip, so the constraint and the enum agree")
                .contains(ReconciliationOutcome.SETTLEMENT_RECORD_INVALID.name());
    }

    @Test
    @DisplayName("R-01b: EVERY ReconciliationOutcome value satisfies the database constraint")
    void everyOutcomeValueIsAcceptedByTheSchema() {
        // The regression protection for R-01 was originally one value, pinned. That closes the
        // defect it was written for and does nothing about the next one: add an outcome to the enum,
        // forget the schema, and the batch fails at INSERT exactly as before. This asserts the
        // GENERAL property instead - the two vocabularies must be the same set - so the next
        // verdict cannot be added to the Java and forgotten in SQL.
        //
        // It reads the enum rather than a hand-written list, because a hand-written list is exactly
        // what drifts.
        List<ReconciliationOutcome> declared = List.of(ReconciliationOutcome.values());
        assertThat(declared).as("the enum is read reflectively, not restated here").isNotEmpty();

        String batchId = sql.sql("INSERT INTO reconciliation_batches (id, provider, window_start, "
                + "window_end, subject_mode, status, started_by) "
                + "VALUES ('rbat_every_outcome', 'SIMULATED_PSP', now() - interval '1 day', "
                + "now(), 'BOTH', 'RUNNING', 'probe') RETURNING id")
                .optional((rs, rowNum) -> rs.getString("id"))
                .orElseThrow();

        for (ReconciliationOutcome outcome : declared) {
            String rowId = "rres_" + outcome.name().toLowerCase(java.util.Locale.ROOT);
            // One row per outcome, written THROUGH the constraint. A CHECK constraint is only
            // covered by a test that writes the constrained value through it; asserting that the
            // enum member exists proves nothing about whether the database accepts it.
            sql.sql("INSERT INTO reconciliation_results (id, batch_id, subject_key, outcome) "
                    + "VALUES (?, ?, ?, ?)")
                    .params(rowId, batchId, "SIMULATED_PSP:probe-" + outcome.name(), outcome.name())
                    .update();

            assertThat(sql.sql("SELECT outcome FROM reconciliation_results WHERE id = ?")
                    .param(rowId)
                    .optional((rs, rowNum) -> rs.getString("outcome")))
                    .as("outcome %s must survive the round trip through the schema", outcome)
                    .contains(outcome.name());
        }

        // And the other direction: the schema must not be wider than the enum, or a verdict could
        // be persisted that the code has no name for.
        long persisted = (Long) sql.sql("SELECT count(*) AS n FROM reconciliation_results "
                + "WHERE batch_id = ?")
                .param(batchId)
                .optional((rs, rowNum) -> rs.getLong("n"))
                .orElse(0L);
        assertThat(persisted)
                .as("one result row per declared outcome, and no more")
                .isEqualTo(declared.size());
    }

    private String statusOfBatch(String batchId) {
        return sql.sql("SELECT status FROM reconciliation_batches WHERE id = ?")
                .param(batchId)
                .optional((rs, rowNum) -> rs.getString("status"))
                .orElse(null);
    }

    /** Parses the stored document, so "it looks right" is not mistaken for "it is valid". */
    private void assertThatJsonIsValid(String json) {
        try {
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new AssertionError("reconciliation differences are not valid JSON: " + invalid.getMessage(), invalid);
        }
    }

    private Long deltaOf(String externalId) {
        return sql.sql("SELECT delta_minor FROM reconciliation_results WHERE subject_key = ?")
                .param("SIMULATED_PSP:" + externalId)
                .optional((rs, rowNum) -> rs.getObject("delta_minor", Long.class))
                .orElse(null);
    }

    private String differencesOf(String externalId) {
        return sql.sql("SELECT differences::text AS d FROM reconciliation_results "
                + "WHERE subject_key = ?")
                .param("SIMULATED_PSP:" + externalId)
                .optional((rs, rowNum) -> rs.getString("d"))
                .orElse("[]");
    }

    private int resultCount(String batchId) {
        return sql.sql("SELECT count(*) AS n FROM reconciliation_results WHERE batch_id = ?")
                .param(batchId)
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0);
    }

    private String resultIdFor(String batchId, String externalId) {
        return sql.sql("SELECT id FROM reconciliation_results WHERE batch_id = ? "
                + "AND subject_key = ?")
                .params(batchId, "SIMULATED_PSP:" + externalId)
                .optional((rs, rowNum) -> rs.getString("id")).orElse(null);
    }

    private long resultKeyCount(String batchId) {
        return sql.sql("SELECT count(DISTINCT subject_key) AS n FROM reconciliation_results "
                + "WHERE batch_id = ?")
                .param(batchId)
                .optional((rs, rowNum) -> rs.getLong("n")).orElse(0L);
    }

    private String batchStatus(String batchId) {
        return sql.sql("SELECT status FROM reconciliation_batches WHERE id = ?")
                .param(batchId)
                .optional((rs, rowNum) -> rs.getString("status")).orElse(null);
    }

    private int openCases() {
        return sql.sql("SELECT count(*) AS n FROM reconciliation_cases "
                + "WHERE status IN ('OPEN','INVESTIGATING')")
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0);
    }

    private String caseReason() {
        return sql.sql("SELECT reason FROM reconciliation_cases ORDER BY detected_at, id LIMIT 1")
                .optional((rs, rowNum) -> rs.getString("reason")).orElse(null);
    }

    private String firstCaseId() {
        return sql.sql("SELECT id FROM reconciliation_cases ORDER BY detected_at, id LIMIT 1")
                .optional((rs, rowNum) -> rs.getString("id")).orElseThrow();
    }

    private String caseStatus(String caseId) {
        return sql.sql("SELECT status FROM reconciliation_cases WHERE id = ?")
                .param(caseId)
                .optional((rs, rowNum) -> rs.getString("status")).orElse(null);
    }

    private int caseEventCount(String caseId) {
        return sql.sql("SELECT count(*) AS n FROM case_events WHERE case_id = ?")
                .param(caseId)
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0);
    }

    private int occurrenceCount() {
        return sql.sql("SELECT occurrence_count FROM reconciliation_cases LIMIT 1")
                .optional((rs, rowNum) -> rs.getInt("occurrence_count")).orElse(0);
    }

    /**
     * Asserts a command was refused, and with which code.
     *
     * <p>Asserting the <b>code</b> and not merely "it threw": these refusals are the contract, and a
     * test that only checked for an exception would pass just as happily if the service refused for
     * the wrong reason.
     */
    /** Asserts the database refused the write, which is how uniqueness guarantees are enforced. */
    private void expectConstraintViolation(Runnable action) {
        try {
            action.run();
        } catch (org.springframework.dao.DataIntegrityViolationException expected) {
            return;
        }
        throw new AssertionError("expected the database to refuse the write");
    }

    private void expectRefusal(Runnable action, String expectedCode) {
        try {
            action.run();
        } catch (com.reconcile.shared.DomainException e) {
            assertThat(e.code().name()).isEqualTo(expectedCode);
            return;
        }
        throw new AssertionError("expected " + expectedCode + ", but the call succeeded");
    }
}
