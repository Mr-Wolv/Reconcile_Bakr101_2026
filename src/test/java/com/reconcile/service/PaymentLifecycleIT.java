package com.reconcile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconcile.ledger.LedgerPostingCommand;
import com.reconcile.payment.PaymentState;
import com.reconcile.persistence.jdbc.LedgerWriter;
import com.reconcile.persistence.jdbc.Sql;
import com.reconcile.shared.CurrencyCode;
import com.reconcile.shared.DomainException;
import com.reconcile.shared.Money;
import com.reconcile.shared.ProblemCode;
import com.reconcile.support.SharedPostgres;
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
 * The documented money cycle, run through the real application services against a real PostgreSQL 18.
 *
 * <p>Maps to rows {@code E2E-01}, {@code E2E-02} and {@code E2E-03} in the test matrix, plus the
 * invariant rows they rest on.
 *
 * <p>Where {@code LedgerInvariantsIT} proves the <i>database</i> refuses bad data by bypassing the
 * application, this proves the opposite direction: that the services, used correctly, produce the
 * ledger the specification describes. Without this, a service could satisfy every database
 * constraint and still post the wrong entries — the constraints would simply be constraining a
 * smaller mistake.
 *
 * <p>Every test ends by asserting L7. A projection that disagrees with its entries is the signature
 * of a service that wrote money it did not mean to write, and it is the one failure mode that a
 * balance assertion alone could miss.
 */
@SpringBootTest
class PaymentLifecycleIT {


    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedPostgres::jdbcUrl);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        registry.add("reconcile.security.operator-token", () -> "test-operator-token");
        registry.add("reconcile.security.admin-token", () -> "test-admin-token");
    }

    @Autowired
    PaymentService payments;

    @Autowired
    PayoutService payouts;

    @Autowired
    LedgerService ledger;

    @Autowired
    Sql sql;

    @Autowired
    DataSource dataSource;

    private static final PaymentService.CommandContext CONTEXT = PaymentService.CommandContext.of(
            new PaymentService.Actor("USER", "integration-test"), "req_test", "idem_test");

    private static final Money GROSS = Money.of(10_000, CurrencyCode.EGP);
    private static final Money FEE = Money.of(300, CurrencyCode.EGP);
    private static final Money NET = Money.of(9_700, CurrencyCode.EGP);

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

    // ------------------------------------------------------------------ E2E-01

    @Test
    @DisplayName("E2E-01: a payment is created with the fee split frozen at 300/9700 of 10000")
    void createFreezesTheFeeSplit() {
        PaymentView payment = payments.create("MERCH-77", "a widget", GROSS, "SIMULATED_PSP", CONTEXT);

        assertThat(payment.state()).isEqualTo(PaymentState.CREATED);
        assertThat(payment.amount()).isEqualTo(GROSS);
        assertThat(payment.platformFee()).isEqualTo(FEE);
        assertThat(payment.netAmount()).isEqualTo(NET);
        assertThat(payment.splitIsBalanced())
                .as("ck_payment_split holds, and the read model agrees")
                .isTrue();
        // A freshly created payment has exactly one thing to say about itself: it exists. The
        // timeline merges four sources, and three of them are legitimately silent this early.
        assertThat(payments.timeline(payment.id())).hasSize(1);
        assertThat(payments.timeline(payment.id()).getFirst().kind()).isEqualTo("STATE");
        assertThat(payments.timeline(payment.id()).getFirst().summary()).isEqualTo("payment created");
        assertThatLedgerIsConsistent();
    }

    // ------------------------------------------------------------------ E2E-02

    @Test
    @DisplayName("E2E-02: capture posts Dr PSP_CLEARING / Cr MERCHANT_PAYABLE / Cr PLATFORM_FEE_REVENUE")
    void capturePostsTheWorkedExample() {
        String paymentId = capturedPaymentId();

        assertThat(balanceOf("PSP_CLEARING")).isEqualTo(10_000L);
        assertThat(balanceOf("MERCHANT_PAYABLE")).isEqualTo(9_700L);
        assertThat(balanceOf("PLATFORM_FEE_REVENUE")).isEqualTo(300L);
        assertThat(balanceOf("PLATFORM_CASH")).isZero();

        // The three entries of one PAYMENT_CAPTURE, exactly as 01-money-and-ledger.md §3 specifies.
        assertThat(entryDirections(paymentId)).containsExactly(
                "PSP_CLEARING:DEBIT:10000",
                "MERCHANT_PAYABLE:CREDIT:9700",
                "PLATFORM_FEE_REVENUE:CREDIT:300");

        assertThat(payments.read(paymentId).capturedAt()).isNotNull();
        assertLedgerTransactionCountFor(paymentId, "CAPTURE");
        assertThatLedgerIsConsistent();
    }

    // ------------------------------------------------------------------ E2E-03

    @Test
    @DisplayName("E2E-03: capture then settlement then payout unwinds the ledger to the platform fee")
    void fullCycleUnwindsToThePlatformFee() {
        String paymentId = capturedPaymentId();
        settle(paymentId);

        assertThat(balanceOf("PSP_CLEARING")).as("the PSP owes us nothing after settlement").isZero();
        assertThat(balanceOf("PLATFORM_CASH")).isEqualTo(10_000L);

        PayoutService.PayoutView payout = payouts.execute("MERCH-77", List.of(paymentId), CONTEXT);

        // Ending on exactly one non-zero account is what makes this a ledger rather than a counter.
        assertThat(balanceOf("PSP_CLEARING")).isZero();
        assertThat(balanceOf("MERCHANT_PAYABLE")).as("we owe the merchant nothing").isZero();
        assertThat(balanceOf("PLATFORM_CASH")).as("what the platform earned and holds").isEqualTo(300L);
        assertThat(balanceOf("PLATFORM_FEE_REVENUE")).isEqualTo(300L);

        assertThat(payout.totalNet()).isEqualTo(NET);
        assertThat(payout.status()).isEqualTo("EXECUTED");
        assertThat(payout.ledgerTransactionId()).isNotBlank();
        assertThat(payout.lines()).singleElement()
                .extracting(PayoutService.PayoutView.Line::paymentId)
                .isEqualTo(paymentId);
        assertThat(payments.read(paymentId).paidOutAt()).isNotNull();

        assertThatLedgerIsConsistent();
    }

    // ------------------------------------------------------------------- L11

    @Test
    @DisplayName("L11: a payment cannot be paid out twice, and the database is what says so")
    void aPaymentCannotBePaidOutTwice() {
        String paymentId = capturedPaymentId();
        settle(paymentId);
        payouts.execute("MERCH-77", List.of(paymentId), CONTEXT);

        // A second payout with a different idempotency key, which is exactly what a client retry
        // after a timeout looks like. The service check catches it first...
        assertThatThrownBy(() -> payouts.execute("MERCH-77", List.of(paymentId), CONTEXT))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo(ProblemCode.PAYOUT_PAYMENT_ALREADY_PAID);

        // ...and the unique index on payout_lines.payment_id is the structural guarantee behind it.
        assertThat(sql.sql("SELECT COUNT(*) FROM payout_lines WHERE payment_id = ?")
                .param(paymentId).single(Long.class)).isEqualTo(1L);
        assertThatLedgerIsConsistent();
    }

    // ------------------------------------------------------------------- D10

    @Test
    @DisplayName("D10: refunding a payment that has already been paid out is refused")
    void refundAfterPayoutIsRefused() {
        String paymentId = capturedPaymentId();
        settle(paymentId);
        payouts.execute("MERCH-77", List.of(paymentId), CONTEXT);

        // The tempting behaviour would post a refund that drives PLATFORM_CASH to -9700, paying out
        // money the platform does not have. L8 forbids it, and the correct answer is to refuse.
        assertThatThrownBy(() -> payments.refund(paymentId, CONTEXT))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo(ProblemCode.PAYMENT_ALREADY_PAID_OUT);

        assertThat(payments.read(paymentId).state()).isEqualTo(PaymentState.SETTLED);
        assertThatLedgerIsConsistent();
    }

    // ---------------------------------------------------------------- refunds

    @Test
    @DisplayName("A refund of a settled payment credits cash, releasing payable and revenue")
    void refundOfASettledPaymentReleasesBalances() {
        String paymentId = capturedPaymentId();
        settle(paymentId);

        payments.refund(paymentId, CONTEXT);

        assertThat(payments.read(paymentId).state()).isEqualTo(PaymentState.REFUNDED);
        assertThat(balanceOf("PLATFORM_CASH")).isZero();
        assertThat(balanceOf("MERCHANT_PAYABLE")).isZero();
        assertThat(balanceOf("PLATFORM_FEE_REVENUE")).isZero();
        assertThat(balanceOf("PSP_CLEARING")).isZero();
        assertThatLedgerIsConsistent();
    }

    // ------------------------------------------------------------- refusals

    @Test
    @DisplayName("An illegal transition is refused and leaves no trace")
    void illegalTransitionIsRefused() {
        PaymentView payment = payments.create("MERCH-77", null, GROSS, "SIMULATED_PSP", CONTEXT);

        // CREATED -> CAPTURED is not in the table: there is no un-capture shortcut, and money is
        // not edited.
        assertThatThrownBy(() -> payments.capture(payment.id(), null, CONTEXT))
                .isInstanceOf(com.reconcile.payment.IllegalStateTransitionException.class);

        assertThat(payments.read(payment.id()).state()).isEqualTo(PaymentState.CREATED);
        assertThat(countLedgerTransactions()).as("a refused capture posts nothing").isZero();
        assertThatLedgerIsConsistent();
    }

    @Test
    @DisplayName("A payout naming an unsettled payment is refused")
    void payoutOfAnUnsettledPaymentIsRefused() {
        String paymentId = capturedPaymentId();

        assertThatThrownBy(() -> payouts.execute("MERCH-77", List.of(paymentId), CONTEXT))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo(ProblemCode.PAYOUT_PAYMENT_NOT_SETTLED);

        assertThat(countLedgerTransactions()).as("a refused payout posts nothing").isEqualTo(1);
        assertThatLedgerIsConsistent();
    }

    @Test
    @DisplayName("A payout naming another merchant's payment is refused")
    void payoutAcrossMerchantsIsRefused() {
        String paymentId = capturedPaymentId();
        settle(paymentId);

        assertThatThrownBy(() -> payouts.execute("MERCH-OTHER", List.of(paymentId), CONTEXT))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo(ProblemCode.PAYOUT_MERCHANT_MISMATCH);
        assertThatLedgerIsConsistent();
    }

    @Test
    @DisplayName("Posting the same business fact twice is refused by the database (L10)")
    void doublePostingIsRefused() {
        String paymentId = capturedPaymentId();

        // Same (source_type, source_id, type) as the capture that already happened, so
        // ux_ledger_source is what refuses this rather than any check in Java.
        assertThatThrownBy(() -> ledger.post(
                Postings.capture(paymentId, GROSS, FEE, NET, "test"), contextFor(paymentId)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo(ProblemCode.LEDGER_ALREADY_POSTED);

        assertThatLedgerIsConsistent();
    }

    // ---------------------------------------------------------------- helpers

    @Test
    @DisplayName("The timeline merges all four sources, in order, with a correlation id where one exists")
    void timelineMergesEverySourceThatTouchesThePayment() {
        // Before this test the timeline read payment_state_history and nothing else, so it reported
        // "created" and "captured" for a payment that had also been posted to the ledger, delivered
        // by webhook, and judged by reconciliation. Every assertion below fails against that
        // implementation; the endpoint existed and answered 200 the whole time, which is the failure
        // mode this whole project exists to argue against.
        String paymentId = capturedPaymentId();
        settle(paymentId);

        // A webhook the inbox holds for this transaction. Three deliveries of it must still be ONE
        // timeline line: the inbox deduplicates by design and the timeline must not undo that.
        sql.sql("UPDATE payments SET provider_transaction_id = ? WHERE id = ?")
                .params("psp_" + paymentId, paymentId).update();
        for (int delivery = 1; delivery <= 3; delivery++) {
            sql.sql("""
                    INSERT INTO provider_event_deliveries
                        (id, provider, event_id, outcome, http_status, signature_valid, request_id,
                         event_type, received_at)
                    VALUES (?, 'SIMULATED_PSP', 'evt_tl_1', ?, 202, true, 'req_tl_1',
                            'payment.captured', now())
                    """).params("ped_tl_" + delivery,
                    delivery == 1 ? "ACCEPTED" : "DUPLICATE").update();
        }
        sql.sql("""
                INSERT INTO provider_events
                    (id, provider, event_id, event_type, provider_occurred_at, payload, payload_hash,
                     status, next_attempt_at)
                VALUES ('pev_tl_1', 'SIMULATED_PSP', 'evt_tl_1', 'payment.captured', now(),
                        CAST(? AS jsonb), repeat('a', 64), 'PROCESSED', now())
                """).params("{\"type\":\"payment.captured\",\"providerTransactionId\":\"psp_"
                + paymentId + "\"}").update();

        // And a reconciliation verdict.
        sql.sql("""
                INSERT INTO reconciliation_batches
                    (id, provider, window_start, window_end, subject_mode, status, started_by,
                     request_id, completed_at)
                VALUES ('rbat_tl_1', 'SIMULATED_PSP', now() - interval '1 day', now(),
                        'BOTH', 'COMPLETED', 'test', 'req_batch_tl', now())
                """).update();
        sql.sql("""
                INSERT INTO reconciliation_results
                    (id, batch_id, subject_key, outcome, internal_payment_id, provider_transaction_id,
                     expected_gross_minor, expected_fee_minor, expected_net_minor, expected_currency,
                     expected_status, actual_gross_minor, actual_fee_minor, actual_net_minor,
                     actual_currency, actual_status)
                VALUES ('rres_tl_1', 'rbat_tl_1', 'SIMULATED_PSP:psp_', 'MATCHED', ?, ?, 10000, 300,
                        9700, 'EGP', 'SETTLED', 10000, 300, 9700, 'EGP', 'SETTLED')
                """).params(paymentId, "psp_" + paymentId).update();

        List<PaymentView.TimelineEntry> timeline = payments.timeline(paymentId);

        assertThat(timeline).extracting(PaymentView.TimelineEntry::kind)
                .as("all four sources reach the timeline")
                .contains("STATE", "LEDGER", "WEBHOOK", "RECONCILIATION");

        assertThat(timeline).filteredOn(e -> "WEBHOOK".equals(e.kind()))
                .as("three deliveries of one event are one line, not three")
                .hasSize(1);
        assertThat(timeline).filteredOn(e -> "WEBHOOK".equals(e.kind())).first()
                .extracting(PaymentView.TimelineEntry::requestId)
                .isEqualTo("req_tl_1");
        assertThat(timeline).filteredOn(e -> "LEDGER".equals(e.kind()))
                .as("every posting carries the request that caused it")
                .isNotEmpty()
                .allSatisfy(e -> assertThat(e.requestId()).isEqualTo("req_test"));
        assertThat(timeline).filteredOn(e -> "RECONCILIATION".equals(e.kind())).first()
                .extracting(PaymentView.TimelineEntry::requestId)
                .isEqualTo("req_batch_tl");

        assertThat(timeline).as("chronological, with a deterministic tiebreak")
                .isSortedAccordingTo((a, b) -> {
                    int byTime = a.occurredAt().compareTo(b.occurredAt());
                    return byTime != 0 ? byTime
                            : (a.kind() + a.reference()).compareTo(b.kind() + b.reference());
                });
        assertThat(timeline).extracting(PaymentView.TimelineEntry::reference)
                .as("references are unique - no source row appears twice")
                .doesNotHaveDuplicates();
    }

    private String capturedPaymentId() {
        PaymentView created = payments.create("MERCH-77", "a widget", GROSS, "SIMULATED_PSP", CONTEXT);
        payments.authorize(created.id(), CONTEXT);
        payments.capture(created.id(), null, CONTEXT);
        return created.id();
    }

    /** Settles a payment the way settlement ingestion does: a posting plus a state transition. */
    private void settle(String paymentId) {
        String settlementId = "srec_" + paymentId;
        sql.sql("""
                INSERT INTO settlement_records
                    (id, provider, provider_settlement_id, gross_amount_minor, fee_amount_minor,
                     net_amount_minor, currency, settlement_date, source)
                VALUES (?, 'SIMULATED_PSP', ?, 10000, 300, 9700, 'EGP', current_date, 'WEBHOOK')
                """).params(settlementId, "psp_settle_" + paymentId).update();

        ledger.post(Postings.settlementReceived(settlementId, GROSS, "test"), contextFor(settlementId));
        sql.sql("UPDATE settlement_records SET ledger_transaction_id = "
                + "(SELECT id FROM ledger_transactions WHERE source_type = 'SETTLEMENT_RECORD' "
                + "AND source_id = ?) WHERE id = ?").params(settlementId, settlementId).update();

        payments.markSettled(paymentId, settlementId, null,
                com.reconcile.payment.PaymentStateMachine.Trigger.SETTLEMENT, CONTEXT);
    }

    private LedgerWriter.PostingContext contextFor(String sourceId) {
        return new LedgerWriter.PostingContext("integration-test", "req_test", null);
    }

    private long balanceOf(String accountCode) {
        return sql.sql("""
                SELECT b.balance_minor
                  FROM account_balances b
                  JOIN ledger_accounts a ON a.id = b.account_id
                 WHERE a.code = ? AND b.currency = 'EGP'
                """)
                .param(accountCode)
                .single(Long.class);
    }

    private List<String> entryDirections(String paymentId) {
        return sql.sql("""
                SELECT e.account_code || ':' || e.direction || ':' || e.amount_minor AS line
                  FROM ledger_entries e
                  JOIN payment_ledger_transactions l ON l.ledger_transaction_id = e.transaction_id
                 WHERE l.payment_id = ?
                 ORDER BY e.line_no
                """)
                .param(paymentId)
                .list(String.class);
    }

    /** Exactly one posting of the given role must be linked to this payment. */
    private void assertLedgerTransactionCountFor(String paymentId, String role) {
        long count = sql.sql("""
                SELECT COUNT(*) FROM payment_ledger_transactions
                 WHERE payment_id = ? AND role = ?
                """)
                .params(paymentId, role)
                .single(Long.class);
        assertThat(count).as("linked postings with role %s for %s", role, paymentId).isEqualTo(1L);
    }

    private long countLedgerTransactions() {
        return sql.sql("SELECT COUNT(*) FROM ledger_transactions")
                .single(Long.class);
    }

    /** L7, asserted at the end of every test in this class. */
    private void assertThatLedgerIsConsistent() {
        assertThat(ledger.verifyBalances())
                .as("L7: the balance projection must equal the recomputed sum of its entries")
                .isEmpty();
    }
}
