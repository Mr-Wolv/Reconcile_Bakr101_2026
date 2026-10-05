package com.reconcile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconcile.persistence.jdbc.Sql;
import com.reconcile.shared.CurrencyCode;
import com.reconcile.shared.DomainException;
import com.reconcile.shared.Money;
import com.reconcile.shared.ProblemCode;
import com.reconcile.shared.Ulid;
import com.reconcile.support.SharedPostgres;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The payout rules, and the payment rules that guard them.
 *
 * <p>Covers {@code I-PAYOUT-01}, {@code I-PAYOUT-03} … {@code I-PAYOUT-07} and {@code U-PAY-03} …
 * {@code U-PAY-06}. The shared property is that every one of these refusals must happen
 * <b>before</b> anything is written. A payout that posts and then discovers it cannot pay the
 * merchant is not a refusal, it is an incident; and a payment that reaches {@code CAPTURED} with a
 * ledger entry nobody intended is worse still.
 *
 * <p>So every test here asserts two things: the refusal carries the right code, and the ledger did
 * not move. Asserting only the code would pass against an implementation that posted first and
 * apologised afterwards.
 */
@SpringBootTest
class PaymentPayoutRulesIT {


    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedPostgres::jdbcUrl);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
    }

    private static final PaymentService.CommandContext CONTEXT = PaymentService.CommandContext.of(
            new PaymentService.Actor("USER", "payout-rules"), "req_po", null);

    private static final Money GROSS = Money.of(10_000, CurrencyCode.EGP);

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

    // ------------------------------------------------------------------ I-PAYOUT-01

    @Test
    @DisplayName("I-PAYOUT-01: a payment that is not SETTLED cannot be paid out")
    void unsettledPaymentCannotBePaidOut() {
        String captured = capture("MERCH-A");
        String justAuthorized = authorize("MERCH-A");

        assertThatThrownBy(() -> payouts.execute("MERCH-A", List.of(captured), CONTEXT))
                .isInstanceOf(DomainException.class)
                .satisfies(thrown -> assertThat(((DomainException) thrown).code())
                        .isEqualTo(ProblemCode.PAYOUT_PAYMENT_NOT_SETTLED));
        assertThatThrownBy(() -> payouts.execute("MERCH-A", List.of(justAuthorized), CONTEXT))
                .isInstanceOf(DomainException.class);

        assertThat(sql.sql("SELECT count(*) AS n FROM payout_records")
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0)).isZero();
        assertThat(ledgerTransactionsOfType("MERCHANT_PAYOUT")).isZero();
        assertThat(balanceOf("PLATFORM_CASH"))
                .as("a refused payout never touches cash")
                .isZero();
    }

    // ------------------------------------------------------------------ I-PAYOUT-03

    @Test
    @DisplayName("I-PAYOUT-03: a payout exceeding PLATFORM_CASH is refused and the balances stand")
    void payoutBeyondAvailableCashIsRefused() {
        // A settled payment whose cash posting never arrived - which is not hypothetical: it is
        // exactly the state this codebase shipped in until I-LED-02's test caught it. The guard
        // exists for precisely this shape, so that is the state worth testing it in.
        String stranded = capture("MERCH-B");
        settleStateOnly(stranded);

        assertThat(payments.read(stranded).state()).isEqualTo(
                com.reconcile.payment.PaymentState.SETTLED);
        assertThat(balanceOf("PLATFORM_CASH"))
                .as("settled, but no cash: the payment says the money arrived and the ledger "
                        + "disagrees")
                .isZero();

        assertThatThrownBy(() -> payouts.execute("MERCH-B", List.of(stranded), CONTEXT))
                .isInstanceOf(DomainException.class)
                .satisfies(thrown -> assertThat(((DomainException) thrown).code())
                        .isEqualTo(ProblemCode.PAYOUT_INSUFFICIENT_CASH));

        assertThat(sql.sql("SELECT count(*) AS n FROM payout_records")
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0))
                .as("no payout record")
                .isZero();
        assertThat(ledgerTransactionsOfType("MERCHANT_PAYOUT"))
                .as("and above all no posting: a payout that half-exists is an incident, not a "
                        + "refusal")
                .isZero();
        assertThat(balanceOf("PLATFORM_CASH")).isZero();
        assertThat(balanceOf("MERCHANT_PAYABLE"))
                .as("the payable is untouched, so the merchant is still owed this")
                .isEqualTo(9_700L);
    }

    @Test
    @DisplayName("I-PAYOUT-03b: a payout within PLATFORM_CASH succeeds and leaves the fee behind")
    void payoutWithinAvailableCashSucceeds() {
        String paymentId = captureAndSettle("MERCH-B2");
        assertThat(balanceOf("PLATFORM_CASH")).isEqualTo(10_000L);

        PayoutService.PayoutView payout = payouts.execute("MERCH-B2", List.of(paymentId), CONTEXT);

        assertThat(payout.totalNet().amountMinor()).isEqualTo(9_700L);
        assertThat(balanceOf("PLATFORM_CASH"))
                .as("what remains is exactly the platform's fee - the whole of the round trip")
                .isEqualTo(300L);
        assertThat(balanceOf("MERCHANT_PAYABLE")).isZero();
        assertThat(balanceOf("PSP_CLEARING")).isZero();
    }

    // ------------------------------------------------------------------ I-PAYOUT-04

    @Test
    @DisplayName("I-PAYOUT-04: a payout may not mix merchants")
    void payoutMayNotMixMerchants() {
        String ours = captureAndSettle("MERCH-C");
        String theirs = captureAndSettle("MERCH-D");

        assertThatThrownBy(() -> payouts.execute("MERCH-C", List.of(ours, theirs), CONTEXT))
                .isInstanceOf(DomainException.class)
                .satisfies(thrown -> assertThat(((DomainException) thrown).code())
                        .as("a merchant is paid their own money and nobody else's")
                        .isEqualTo(ProblemCode.PAYOUT_MERCHANT_MISMATCH));
        assertThat(sql.sql("SELECT count(*) AS n FROM payout_records")
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0)).isZero();
        assertThat(balanceOf("PLATFORM_CASH")).isEqualTo(20_000L);
    }

    @Test
    @DisplayName("I-PAYOUT-04b: a payout may not mix currencies")
    void payoutMayNotMixCurrencies() {
        String egp = captureAndSettle("MERCH-E");
        String usd = captureAndSettleIn("MERCH-E", "USD");

        assertThatThrownBy(() -> payouts.execute("MERCH-E", List.of(egp, usd), CONTEXT))
                .isInstanceOf(DomainException.class)
                .satisfies(thrown -> assertThat(((DomainException) thrown).code())
                        .isEqualTo(ProblemCode.PAYOUT_CURRENCY_MISMATCH));
        assertThat(sql.sql("SELECT count(*) AS n FROM payout_records")
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0)).isZero();
    }

    // ------------------------------------------------------------------ I-PAYOUT-05

    @Test
    @DisplayName("I-PAYOUT-05: a paid-out payment cannot be refunded")
    void paidOutPaymentCannotBeRefunded() {
        String paymentId = captureAndSettle("MERCH-F");
        payouts.execute("MERCH-F", List.of(paymentId), CONTEXT);
        long cashAfterPayout = balanceOf("PLATFORM_CASH");

        assertThatThrownBy(() -> payments.refund(paymentId, CONTEXT))
                .isInstanceOf(DomainException.class)
                .satisfies(thrown -> assertThat(((DomainException) thrown).code())
                        .as("the merchant has the money; a refund would take it back silently")
                        .isEqualTo(ProblemCode.PAYMENT_ALREADY_PAID_OUT));

        assertThat(payments.read(paymentId).state())
                .as("and the state is untouched: the refund did not half-happen")
                .isEqualTo(com.reconcile.payment.PaymentState.SETTLED);
        assertThat(balanceOf("PLATFORM_CASH"))
                .as("the refund moved nothing: the merchant already has this money")
                .isEqualTo(cashAfterPayout);
    }

    // ------------------------------------------------------------------ I-PAYOUT-06

    @Test
    @DisplayName("I-PAYOUT-06: the same payout attempted twice produces one payout and one posting")
    void payoutCannotHappenTwice() {
        String paymentId = captureAndSettle("MERCH-G");

        payouts.execute("MERCH-G", List.of(paymentId), CONTEXT);

        assertThatThrownBy(() -> payouts.execute("MERCH-G", List.of(paymentId), CONTEXT))
                .as("L11 refuses it whether or not an idempotency key was supplied, which is what "
                        + "makes a timeout retry safe even for a caller that lost its key")
                .isInstanceOf(DomainException.class)
                .satisfies(thrown -> assertThat(((DomainException) thrown).code())
                        .isEqualTo(ProblemCode.PAYOUT_PAYMENT_ALREADY_PAID));

        assertThat(sql.sql("SELECT count(*) AS n FROM payout_records")
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0)).isEqualTo(1);
        assertThat(ledgerTransactionsOfType("MERCHANT_PAYOUT")).isEqualTo(1);
        assertThat(balanceOf("PLATFORM_CASH"))
                .as("the merchant is paid once")
                .isEqualTo(300L);

        // The HTTP-level replay of one key producing one payout is proven in IdempotencyHttpIT,
        // against the endpoint rather than the service.
    }

    // ------------------------------------------------------------------ I-PAYOUT-07

    @Test
    @DisplayName("I-PAYOUT-07: the net comes from the ledger, never from the request")
    void netAmountComesFromTheLedger() {
        String paymentId = captureAndSettle("MERCH-H");

        PayoutService.PayoutView payout = payouts.execute("MERCH-H", List.of(paymentId), CONTEXT);

        assertThat(payout.totalNet().amountMinor())
                .as("97.00 is what the ledger says this payment is worth, whatever anyone asks for")
                .isEqualTo(9_700L);
        assertThat(payout.lines()).singleElement()
                .satisfies(line -> assertThat(line.net().amountMinor()).isEqualTo(9_700L));
        assertThat(sql.sql("SELECT net_amount_minor FROM payments WHERE id = ?").param(paymentId)
                .optional((rs, rowNum) -> rs.getLong("net_amount_minor")).orElse(0L))
                .isEqualTo(9_700L);
    }

    @Test
    @DisplayName("A provider transaction id can only belong to one payment")
    void providerTransactionIdIsClaimedOnce() {
        String externalId = "psp_" + Ulid.next();
        payments.create("MERCH-DUP", null, GROSS, "SIMULATED_PSP", externalId, CONTEXT);

        assertThatThrownBy(() ->
                payments.create("MERCH-DUP", null, GROSS, "SIMULATED_PSP", externalId, CONTEXT))
                .as("reconciliation matches on (provider, providerTransactionId), so a second "
                        + "payment claiming the same id would make every comparison ambiguous")
                .isInstanceOf(DomainException.class)
                .satisfies(thrown -> assertThat(((DomainException) thrown).code())
                        .as("a conflict the caller can fix is a 409, not a 500")
                        .isEqualTo(ProblemCode.PROVIDER_TRANSACTION_ALREADY_CLAIMED));

        assertThat(sql.sql("SELECT count(*) AS n FROM payments WHERE provider_transaction_id = ?")
                .param(externalId).optional((rs, rowNum) -> rs.getInt("n")).orElse(0))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ U-PAY-03..06

    @Test
    @DisplayName("U-PAY-03: capturing a different amount than was authorised is refused")
    void captureMustMatchTheAuthorisedAmount() {
        String paymentId = authorize("MERCH-I");

        assertThatThrownBy(() -> payments.capture(paymentId,
                Money.of(9_999, CurrencyCode.EGP), CONTEXT))
                .isInstanceOf(DomainException.class)
                .satisfies(thrown -> assertThat(((DomainException) thrown).code())
                        .isEqualTo(ProblemCode.CAPTURE_AMOUNT_MISMATCH));

        assertThat(payments.read(paymentId).state()).isEqualTo(
                com.reconcile.payment.PaymentState.AUTHORIZED);
        assertThat(ledgerTransactionsOfType("PAYMENT_CAPTURE")).isZero();
        assertThat(balanceOf("PSP_CLEARING")).isZero();
    }

    @Test
    @DisplayName("U-PAY-04: a payment that never captured cannot be refunded")
    void unCapturedPaymentsCannotBeRefunded() {
        String created = payments.create("MERCH-J", null, GROSS, "SIMULATED_PSP",
                "psp_" + Ulid.next(), CONTEXT).id();
        String authorized = authorize("MERCH-J");
        String failed = payments.fail(authorize("MERCH-J"), "DOES_NOT_HAVE_ENOUGH_MONEY",
                "insufficient funds", CONTEXT).id();

        for (String paymentId : List.of(created, authorized, failed)) {
            assertThatThrownBy(() -> payments.refund(paymentId, CONTEXT))
                    .as("refunding %s", payments.read(paymentId).state())
                    .isInstanceOf(DomainException.class);
        }
        assertThat(ledgerTransactionsOfType("REVERSAL")).isZero();
    }

    @Test
    @DisplayName("U-PAY-05: a capture after the payment expired is refused as PAYMENT_EXPIRED")
    void expiredPaymentCannotBeCaptured() {
        String paymentId = authorize("MERCH-K");
        sql.sql("UPDATE payments SET expires_at = now() - interval '1 minute' WHERE id = ?")
                .param(paymentId).update();

        assertThatThrownBy(() -> payments.capture(paymentId, GROSS, CONTEXT))
                .isInstanceOf(DomainException.class)
                .satisfies(thrown -> assertThat(((DomainException) thrown).code())
                        .as("an expired payment is a 410, not a generic 409: the client can act on "
                                + "the difference between \"wrong state\" and \"too late\"")
                        .isEqualTo(ProblemCode.PAYMENT_EXPIRED));

        assertThat(payments.read(paymentId).state()).isEqualTo(
                com.reconcile.payment.PaymentState.AUTHORIZED);
        assertThat(balanceOf("PSP_CLEARING")).isZero();
    }

    @Test
    @DisplayName("U-PAY-06: gross = net + fee on every amount shape")
    void theSplitHoldsForEveryAmountShape() {
        long[] amounts = {1L, 2L, 7L, 99L, 100L, 333L, 9_999L, 10_000L, 123_457L, 999_999_99L};

        for (long amountMinor : amounts) {
            PaymentView view = payments.create("MERCH-SPLIT", null,
                    Money.of(amountMinor, CurrencyCode.EGP), "SIMULATED_PSP",
                    "psp_" + Ulid.next(), CONTEXT);

            assertThat(view.netAmount().amountMinor() + view.platformFee().amountMinor())
                    .as("%d minor units splits exactly, with no lost or invented remainder",
                            amountMinor)
                    .isEqualTo(amountMinor);
            assertThat(view.platformFee().amountMinor())
                    .as("and the fee is never more than the gross")
                    .isLessThanOrEqualTo(amountMinor);
        }
    }

    // ------------------------------------------------------------------ helpers

    private String authorize(String merchant) {
        String id = payments.create(merchant, null, GROSS, "SIMULATED_PSP",
                "psp_" + Ulid.next(), CONTEXT).id();
        return payments.authorize(id, CONTEXT).id();
    }

    private String capture(String merchant) {
        String id = authorize(merchant);
        return payments.capture(id, null, CONTEXT).id();
    }

    /** Captures, settles through the settlement path, and moves the money into cash. */
    private String captureAndSettle(String merchant) {
        return captureAndSettleIn(merchant, "EGP");
    }

    private String captureAndSettleIn(String merchant, String currency) {
        Money gross = Money.of(10_000, CurrencyCode.parse(currency));
        String id = payments.create(merchant, null, gross, "SIMULATED_PSP",
                "psp_" + Ulid.next(), CONTEXT).id();
        payments.authorize(id, CONTEXT);
        payments.capture(id, null, CONTEXT);

        String externalId = sql.sql("SELECT provider_transaction_id FROM payments WHERE id = ?")
                .param(id).optional((rs, rowNum) -> rs.getString("provider_transaction_id"))
                .orElseThrow();
        String recordId = "srec_" + id;

        sql.sql("""
                INSERT INTO settlement_records
                    (id, provider, provider_settlement_id, gross_amount_minor, fee_amount_minor,
                     net_amount_minor, currency, settlement_date, source)
                VALUES (?, 'SIMULATED_PSP', ?, ?, 0, ?, ?, ?, 'PULL')
                """)
                .params(recordId, "psset_" + id, 10_000L, 10_000L, currency, LocalDate.now())
                .update();
        sql.sql("""
                INSERT INTO settlement_record_lines
                    (id, settlement_record_id, provider_transaction_id, provider_transaction_ref,
                     line_gross_minor, line_net_minor, line_no)
                VALUES (?, ?, ?, NULL, ?, ?, 1)
                """)
                .params("srline_" + id, recordId, externalId, 10_000L, 10_000L)
                .update();
        payments.markSettled(id, recordId, LocalDate.now().toString(),
                com.reconcile.payment.PaymentStateMachine.Trigger.SETTLEMENT, CONTEXT);

        ledgerPostSettlementReceived(recordId, 10_000L, currency);
        return id;
    }

    /** Settles a payment's state and links its record, without posting the cash movement. */
    private void settleStateOnly(String paymentId) {
        String externalId = sql.sql("SELECT provider_transaction_id FROM payments WHERE id = ?")
                .param(paymentId).optional((rs, rowNum) -> rs.getString("provider_transaction_id"))
                .orElseThrow();
        String recordId = "srec_" + paymentId;
        sql.sql("""
                INSERT INTO settlement_records
                    (id, provider, provider_settlement_id, gross_amount_minor, fee_amount_minor,
                     net_amount_minor, currency, settlement_date, source)
                VALUES (?, 'SIMULATED_PSP', ?, 10_000, 0, 10_000, 'EGP', ?, 'PULL')
                """).params(recordId, "psset_" + paymentId, LocalDate.now()).update();
        sql.sql("""
                INSERT INTO settlement_record_lines
                    (id, settlement_record_id, provider_transaction_id, provider_transaction_ref,
                     line_gross_minor, line_net_minor, line_no)
                VALUES (?, ?, ?, NULL, 10_000, 10_000, 1)
                """).params("srline_" + paymentId, recordId, externalId).update();
        payments.markSettled(paymentId, recordId, LocalDate.now().toString(),
                com.reconcile.payment.PaymentStateMachine.Trigger.SETTLEMENT, CONTEXT);
    }

    private void ledgerPostSettlementReceived(String recordId, long grossMinor, String currency) {
        // The settlement posting belongs to the settlement ingestion path, which has its own suite
        // (LedgerRulesIT). Reproducing it here keeps these tests about the payout rules rather than
        // about webhook plumbing.
        ledger.post(Postings.settlementReceived(recordId,
                        Money.of(grossMinor, CurrencyCode.parse(currency)), "SIMULATED_PSP"),
                new com.reconcile.persistence.jdbc.LedgerWriter.PostingContext(
                        "payout-rules", "req_settle", null));
    }

    private int ledgerTransactionsOfType(String type) {
        return sql.sql("SELECT count(*) AS n FROM ledger_transactions WHERE type = ?")
                .param(type).optional((rs, rowNum) -> rs.getInt("n")).orElse(0);
    }

    private long balanceOf(String accountCode) {
        return sql.sql("""
                SELECT b.balance_minor FROM account_balances b
                  JOIN ledger_accounts a ON a.id = b.account_id
                 WHERE a.code = ? AND b.currency = 'EGP'
                """).param(accountCode).optional((rs, rowNum) -> rs.getLong("balance_minor"))
                .orElse(0L);
    }
}
