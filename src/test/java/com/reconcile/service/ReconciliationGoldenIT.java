package com.reconcile.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconcile.persistence.jdbc.Sql;
import com.reconcile.support.SharedPostgres;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * {@code C-RECON-05}: fifty subjects, compared byte for byte against a committed file.
 *
 * <p>Fifty rather than the forty the matrix asks for: there are nine reachable outcomes and not
 * eight, and {@code MISSING_INTERNAL} has two distinct shapes worth pinning separately - a
 * transaction we have never heard of, and one we hold a payment for that never captured. Coverage
 * is the point; the exact count is not.
 *
 * <p>Three claims are being proved at once, and each is worth having on its own:
 *
 * <ul>
 *   <li><b>Determinism</b> — the same window produces the same result set, every run, on any
 *       machine. A reconciliation whose answer depends on insertion order or on hash iteration is
 *       not one anybody can sign off on, and that is the entire product.
 *   <li><b>Coverage</b> — every reachable outcome appears in the fixture, so a change to the
 *       classifier that quietly stops producing one of them fails here rather than in production.
 *   <li><b>Stability</b> — the committed file turns "the results changed" from something a reviewer
 *       might skim past in a test report into a diff they have to read.
 * </ul>
 *
 * <p>Two outcomes are deliberately absent: {@code DUPLICATE_PROVIDER_RECORD} and
 * {@code AMBIGUOUS_MATCH} both require two rows sharing one transaction id on one side, and
 * {@code ux_provider_txn} and {@code ux_payments_provider_txn} make that impossible to create. The
 * schema refuses the condition before reconciliation can classify it, which is a stronger outcome
 * than a correct classification would have been. Those two stay covered against the classifier
 * directly in {@code ReconciliationClassifierTest}, and against the schema in {@code C-RECON-06}.
 *
 * <p>The file holds no ids, no timestamps and no case numbers. Those are allocation details that
 * legitimately differ between two identical runs, and a golden file containing them would fail
 * every time and be "fixed" by regenerating it — which is how a golden test becomes a test that can
 * never fail again.
 */
@SpringBootTest
class ReconciliationGoldenIT {


    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedPostgres::jdbcUrl);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        // This class drives runBatch itself and then photographs the outcome. A scheduled poll
        // racing that would complete the batch underneath the snapshot, which is the one thing a
        // golden file cannot tolerate. AuditRegressionIT covers the scheduler itself.
        registry.add("reconcile.reconciliation.poll-interval", () -> "1h");
    }

    private static final PaymentService.CommandContext CONTEXT = PaymentService.CommandContext.of(
            new PaymentService.Actor("USER", "golden-fixture"), "req_golden", null);

    private static final com.reconcile.shared.Money GROSS =
            com.reconcile.shared.Money.of(10_000, com.reconcile.shared.CurrencyCode.EGP);

    private static final String GOLDEN = "/golden/c-recon-05.tsv";

    @Autowired
    PaymentService payments;

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
        truncate();
    }

    private void truncate() {
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

    // ------------------------------------------------------------------ C-RECON-05

    @Test
    @DisplayName("C-RECON-05: every reachable outcome produces the committed result set")
    void goldenFixtureMatchesTheCommittedFile() {
        seedFixture();

        String batch = runBatch();

        assertThat(snapshot(batch)).isEqualTo(committed());
    }

    @Test
    @DisplayName("C-RECON-05b: re-seeding and re-running reproduces the fixture exactly")
    void goldenFixtureIsReproducible() {
        seedFixture();
        String first = snapshot(runBatch());

        // Wipe and rebuild: the fixture uses fixed transaction ids, because a golden test needs
        // stable input more than it needs a virgin database. Re-seeding on top of itself would
        // collide on ux_payments_provider_txn rather than proving anything.
        truncate();
        seedFixture();
        String second = snapshot(runBatch());

        assertThat(second).isEqualTo(first);
        assertThat(first.split("\n"))
                .as("fifty subjects: five for each of the nine reachable outcomes, plus five "
                        + "never-captured payments that must classify as MISSING_INTERNAL rather "
                        + "than crash the batch")
                .hasSize(50);
    }

    @Test
    @DisplayName("C-RECON-05c: the fixture covers every outcome the schema permits")
    void goldenFixtureCoversEveryReachableOutcome() {
        seedFixture();
        String batch = runBatch();

        List<String> outcomes = sql.sql("""
                SELECT DISTINCT outcome FROM reconciliation_results WHERE batch_id = ?
                 ORDER BY outcome
                """).param(batch).list((rs, rowNum) -> rs.getString("outcome"));

        assertThat(outcomes).containsExactly(
                "AMOUNT_MISMATCH",
                "CURRENCY_MISMATCH",
                "MATCHED",
                "MATCHED_NOT_SETTLED",
                "MISSING_INTERNAL",
                "MISSING_ON_PROVIDER",
                "REFERENCE_MISMATCH",
                "SETTLEMENT_DATE_MISMATCH",
                "STATUS_MISMATCH");
        assertThat(sql.sql("SELECT count(*) AS n FROM reconciliation_results WHERE batch_id = ?")
                .param(batch).optional((rs, rowNum) -> rs.getInt("n")).orElse(0)).isEqualTo(50);
        assertThat(sql.sql("""
                SELECT count(*) AS n FROM reconciliation_cases
                 WHERE status IN ('OPEN','INVESTIGATING')
                """).optional((rs, rowNum) -> rs.getInt("n")).orElse(0))
                .as("the seven outcomes that need a human each opened exactly one case, five times "
                        + "over - forty, with MISSING_INTERNAL appearing in both of its forms")
                .isEqualTo(40);
    }

    /**
     * Regenerates the committed file, on request.
     *
     * <p>Skipped unless {@code -Dreconcile.regenerate-golden=true}, because the one thing a golden
     * test must never do is rewrite its own expectation: a failure that silently fixes itself is a
     * test that has stopped testing. Regeneration is deliberate, and the regenerated file is a diff
     * a reviewer reads before it is committed.
     */
    @Test
    @DisplayName("C-RECON-05 (maintenance): rewrite the golden file when asked to")
    void regenerateTheGoldenFile() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Boolean.getBoolean("reconcile.regenerate-golden"),
                "regeneration is opt-in: pass -Dreconcile.regenerate-golden=true to rewrite it");

        seedFixture();
        String content = snapshot(runBatch());

        try {
            java.nio.file.Path target = java.nio.file.Path.of("src/test/resources/golden/c-recon-05.tsv");
            java.nio.file.Files.createDirectories(target.getParent());
            java.nio.file.Files.writeString(target, content, StandardCharsets.UTF_8);
            System.out.println("regenerated " + target + " with "
                    + content.lines().count() + " subjects");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("the golden file could not be written", e);
        }
    }

    // ------------------------------------------------------------------ the fixture

    /** Fifty subjects: five for each of the nine outcomes the schema permits, plus five more. */
    private void seedFixture() {
        for (int i = 0; i < 5; i++) {
            // MATCHED: settled on both sides, agreeing on every figure.
            String matched = "psp_g_matched_" + i;
            String matchedPayment = capturePayment("MERCH-M" + i, matched);
            providerRow(matched, "MERCH-M" + i, 10_000, 300, 9_700, "SETTLED", "EGP");
            settleProviderSide(matched, matchedPayment, LocalDate.now());

            // MATCHED_NOT_SETTLED: agreed, but the provider has not paid yet.
            String notSettled = "psp_g_notsettled_" + i;
            capturePayment("MERCH-N" + i, notSettled);
            providerRow(notSettled, "MERCH-N" + i, 10_000, 300, 9_700, "CAPTURED", "EGP");

            // AMOUNT_MISMATCH: one minor unit short - the smallest difference there is, and one
            // that no tolerance band may absorb.
            String shortBy = "psp_g_short_" + i;
            capturePayment("MERCH-S" + i, shortBy);
            providerRow(shortBy, "MERCH-S" + i, 9_999, 300, 9_699, "CAPTURED", "EGP");

            // CURRENCY_MISMATCH: the same number in a different currency.
            String wrongCurrency = "psp_g_currency_" + i;
            capturePayment("MERCH-F" + i, wrongCurrency);
            providerRow(wrongCurrency, "MERCH-F" + i, 10_000, 300, 9_700, "CAPTURED", "USD");

            // STATUS_MISMATCH: we have it settled, they still call it captured.
            String statusDiffers = "psp_g_status_" + i;
            String statusPayment = capturePayment("MERCH-T" + i, statusDiffers);
            providerRow(statusDiffers, "MERCH-T" + i, 10_000, 300, 9_700, "CAPTURED", "EGP");
            settleProviderSide(statusDiffers, statusPayment, LocalDate.now());

            // REFERENCE_MISMATCH: the same money under two different merchant references.
            String referenceDiffers = "psp_g_reference_" + i;
            capturePayment("MERCH-R" + i, referenceDiffers);
            providerRow(referenceDiffers, "MERCH-OTHER" + i, 10_000, 300, 9_700, "CAPTURED", "EGP");

            // SETTLEMENT_DATE_MISMATCH: the same payment, paid on different days.
            String dateDiffers = "psp_g_date_" + i;
            String datePayment = capturePayment("MERCH-D" + i, dateDiffers);
            providerRow(dateDiffers, "MERCH-D" + i, 10_000, 300, 9_700, "SETTLED", "EGP");
            settleProviderSide(dateDiffers, datePayment, LocalDate.now().minusDays(3));
            // ...and then the provider re-issues the settlement on a later date. Our side still
            // points at the first record, so the two genuinely disagree about when it paid - which
            // is the only way this outcome is reachable, and the reason the provider-side lookup
            // has to pick a deterministically when two records cover one transaction.
            providerSettlementRecord(dateDiffers, datePayment, LocalDate.now());

            // MISSING_ON_PROVIDER: captured by us, never confirmed by them.
            capturePayment("MERCH-O" + i, "psp_g_onlyours_" + i);

            // MISSING_INTERNAL: reported by them, never seen by us.
            providerRow("psp_g_onlytheirs_" + i, "MERCH-P" + i, 10_000, 300, 9_700, "CAPTURED",
                    "EGP");

            // MISSING_INTERNAL, second flavour, and the reason this fixture grew by five.
            //
            // We DO hold a payment for this transaction - but it never captured, so nothing was
            // ever booked against it. The provider reports money moved and our ledger says nothing
            // did, which is a real finding and not an error.
            //
            // This subject exists because the previous fixture could not express it: every payment
            // in it had a capture posting, so `expectedView`'s capture lookup always succeeded. The
            // engine therefore returned HTTP 500 on a payment a `payment.captured` webhook left
            // behind against a FAILED payment, and a 45-subject golden fixture over captured-only
            // payments could never have caught it. A fixture whose population cannot reach the
            // defect is not evidence about the defect.
            String neverCaptured = "psp_g_uncaptured_" + i;
            uncapturedPayment("MERCH-U" + i, neverCaptured);
            providerRow(neverCaptured, "MERCH-U" + i, 10_000, 300, 9_700, "CAPTURED", "EGP");
        }
    }

    // ------------------------------------------------------------------ helpers

    private String capturePayment(String merchantReference, String externalId) {
        String id = payments.create(merchantReference, null, GROSS, "SIMULATED_PSP", externalId,
                CONTEXT).id();
        payments.authorize(id, CONTEXT);
        payments.capture(id, null, CONTEXT);
        return id;
    }

    /**
     * A payment bound to a provider transaction id that was never captured.
     *
     * <p>Reachable in production whenever a provider event names a transaction whose payment has
     * not captured: {@code upsertProviderTransaction} writes the provider row before the payment
     * state decision is taken, so a {@code payment.captured} webhook against a payment that is
     * already {@code FAILED} leaves exactly this shape behind.
     */
    private String uncapturedPayment(String merchantReference, String externalId) {
        return payments.create(merchantReference, null, GROSS, "SIMULATED_PSP", externalId,
                CONTEXT).id();
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

    /** Ingests a settlement and settles our payment, the way the webhook path does. */
    private void settleProviderSide(String externalId, String paymentId, LocalDate date) {
        // The settlement rows come first: payments.settlement_record_id is a foreign key onto
        // settlement_records, so a payment cannot name a record that does not exist yet.
        sql.sql("""
                INSERT INTO settlement_records
                    (id, provider, provider_settlement_id, gross_amount_minor, fee_amount_minor,
                     net_amount_minor, currency, settlement_date, source)
                VALUES (?, 'SIMULATED_PSP', ?, 10_000, 300, 9_700, 'EGP', ?, 'PULL')
                """)
                .params("srec_" + paymentId, "sset_" + paymentId, date)
                .update();
        sql.sql("""
                INSERT INTO settlement_record_lines
                    (id, settlement_record_id, provider_transaction_id, provider_transaction_ref,
                     line_gross_minor, line_net_minor, line_no)
                VALUES (?, ?, ?, NULL, 10_000, 9_700, 1)
                """)
                .params("srline_" + paymentId, "srec_" + paymentId, externalId)
                .update();
        payments.markSettled(paymentId, "srec_" + paymentId, date.toString(),
                com.reconcile.payment.PaymentStateMachine.Trigger.SETTLEMENT, CONTEXT);
    }

    /** A second settlement covering a transaction we have already settled, on a later date. */
    private void providerSettlementRecord(String externalId, String paymentId, LocalDate date) {
        String recordId = "srec2_" + paymentId;
        sql.sql("""
                INSERT INTO settlement_records
                    (id, provider, provider_settlement_id, gross_amount_minor, fee_amount_minor,
                     net_amount_minor, currency, settlement_date, source)
                VALUES (?, 'SIMULATED_PSP', ?, 10_000, 300, 9_700, 'EGP', ?, 'PULL')
                """)
                .params(recordId, "psset2_" + paymentId, date)
                .update();
        sql.sql("""
                INSERT INTO settlement_record_lines
                    (id, settlement_record_id, provider_transaction_id, provider_transaction_ref,
                     line_gross_minor, line_net_minor, line_no)
                VALUES (?, ?, ?, NULL, 10_000, 9_700, 1)
                """)
                .params("srline2_" + paymentId, recordId, externalId)
                .update();
    }

    private String runBatch() {
        Instant end = Instant.now().plusSeconds(3600);
        String batchId = reconciliation.createBatch(new ReconciliationService.BatchRequest(
                "SIMULATED_PSP", end.minusSeconds(86_400L * 30), end,
                ReconciliationService.SubjectMode.BOTH, "golden-fixture", "req_golden"));
        worker.runBatch(batchId);
        return batchId;
    }

    /**
     * The batch's result set as text, projected onto values that are a function of the input.
     *
     * <p>Sorted by subject key, so the order is a property of the data rather than of the plan.
     */
    private String snapshot(String batchId) {
        List<String> lines = sql.sql("""
                SELECT subject_key, outcome, expected_gross_minor, expected_fee_minor,
                       expected_net_minor, expected_currency, expected_status,
                       actual_gross_minor, actual_fee_minor, actual_net_minor,
                       actual_currency, actual_status, delta_minor,
                       (case_id IS NOT NULL) AS has_case, differences
                  FROM reconciliation_results
                 WHERE batch_id = ?
                 ORDER BY subject_key
                """)
                .param(batchId)
                .list((rs, rowNum) -> String.join("|",
                        rs.getString("subject_key"),
                        rs.getString("outcome"),
                        text(rs, "expected_gross_minor"),
                        text(rs, "expected_fee_minor"),
                        text(rs, "expected_net_minor"),
                        text(rs, "expected_currency"),
                        text(rs, "expected_status"),
                        text(rs, "actual_gross_minor"),
                        text(rs, "actual_fee_minor"),
                        text(rs, "actual_net_minor"),
                        text(rs, "actual_currency"),
                        text(rs, "actual_status"),
                        text(rs, "delta_minor"),
                        text(rs, "has_case"),
                        reasonsIn(rs.getString("differences"))));
        return String.join("\n", lines) + "\n";
    }

    private static String text(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Object value = rs.getObject(column);
        return value == null ? "-" : String.valueOf(value);
    }

    /**
     * The differences, reduced to the reasons they carry.
     *
     * <p>Only the reasons, because {@code differences} is {@code jsonb}: PostgreSQL does not promise
     * a key order for it, so a byte comparison over the raw document would fail on a machine that
     * merely stored the same facts differently.
     */
    private static String reasonsIn(String json) {
        if (json == null || json.isBlank()) {
            return "[]";
        }
        List<String> reasons = new ArrayList<>();
        int from = 0;
        while (true) {
            int at = json.indexOf(REASON_KEY, from);
            if (at < 0) {
                break;
            }
            int colon = json.indexOf(':', at + REASON_KEY.length());
            int open = json.indexOf(QUOTE, colon + 1);
            if (open < 0) {
                break;
            }
            int close = json.indexOf(QUOTE, open + 1);
            if (close < 0) {
                break;
            }
            reasons.add(json.substring(open + 1, close));
            from = close + 1;
        }
        return reasons.toString();
    }

    private static final char QUOTE = '"';

    private static final String REASON_KEY = String.valueOf(QUOTE) + "reason" + QUOTE;

    /** The committed expectation. A missing file fails loudly rather than comparing two empties. */
    private String committed() {
        try (InputStream in = getClass().getResourceAsStream(GOLDEN)) {
            if (in == null) {
                throw new IllegalStateException(
                        "the golden fixture is missing: src/test/resources/golden/c-recon-05.tsv");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("the golden fixture could not be read", e);
        }
    }
}
