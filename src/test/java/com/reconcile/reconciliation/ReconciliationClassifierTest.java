package com.reconcile.reconciliation;

import static com.reconcile.shared.CurrencyCode.EGP;
import static com.reconcile.shared.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;

import com.reconcile.payment.PaymentState;
import com.reconcile.shared.Money;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Maps to rows {@code U-RECON-01}, {@code U-RECON-02}, {@code U-RECON-03} and several C-RECON rows. */
class ReconciliationClassifierTest {

    private static final Money GROSS = Money.of(10_000, EGP);
    private static final Money FEE = Money.of(300, EGP);
    private static final Money NET = Money.of(9_700, EGP);
    private static final LocalDate DATE = LocalDate.of(2026, 10, 4);

    private static ReconciliationSubject.Expected expected() {
        return new ReconciliationSubject.Expected("pay_1", GROSS, FEE, NET,
                PaymentState.SETTLED, "MERCH-77", DATE);
    }

    private static ReconciliationSubject.Expected expected(PaymentState status) {
        // An unsettled payment has no settlement date on either side.
        return new ReconciliationSubject.Expected("pay_1", GROSS, FEE, NET, status, "MERCH-77",
                status == PaymentState.SETTLED ? DATE : null);
    }

    private static ReconciliationSubject.ProviderRecord provider(Money gross, Money fee, Money net,
            PaymentState status, String reference, LocalDate date) {
        return provider(gross, fee, net, status, reference, date, SettlementValidity.VALID);
    }

    /**
     * A provider record under a settlement whose validity is stated.
     *
     * <p>Defaults to {@code VALID}, which reads as "covered by a settlement that was accepted" —
     * correct for the settled cases and harmless for the rest, because the classifier's only
     * question is whether the covering record was refused. The invalid case is reachable only
     * through the second overload, so a test that means to exercise it has to say so rather than
     * inheriting a default.
     */
    private static ReconciliationSubject.ProviderRecord provider(Money gross, Money fee, Money net,
            PaymentState status, String reference, LocalDate date, SettlementValidity validity) {
        return new ReconciliationSubject.ProviderRecord(gross, fee, net, status, reference, date,
                validity);
    }

    private static ReconciliationSubject subject(List<ReconciliationSubject.Expected> internal,
            List<ReconciliationSubject.ProviderRecord> provider) {
        return new ReconciliationSubject("SIMULATED_PSP", "EXT-1", internal, provider);
    }

    private static ReconciliationClassification classify(
            List<ReconciliationSubject.Expected> internal,
            List<ReconciliationSubject.ProviderRecord> provider) {
        return ReconciliationClassifier.classify(subject(internal, provider));
    }

    @Test
    @DisplayName("C-RECON-01: identical sides match and raise no case")
    void identicalSidesMatch() {
        var result = classify(List.of(expected()),
                List.of(provider(GROSS, FEE, NET, PaymentState.SETTLED, "MERCH-77", DATE)));

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.MATCHED);
        assertThat(result.differences()).isEmpty();
        assertThat(result.primaryReason()).isEmpty();
        assertThat(result.requiresCase()).isFalse();
        assertThat(result.deltaMinor()).contains(0L);
    }

    @Test
    @DisplayName("An unsettled but agreeing payment is MATCHED_NOT_SETTLED, not MATCHED")
    void unsettledAgreementIsDistinct() {
        var result = classify(List.of(expected(PaymentState.CAPTURED)),
                List.of(provider(GROSS, FEE, NET, PaymentState.CAPTURED, "MERCH-77", null)));

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.MATCHED_NOT_SETTLED);
        assertThat(result.requiresCase()).isFalse();
        assertThat(result.outcome().requiresCase()).isFalse();
    }

    @Test
    @DisplayName("C-RECON-02: 100.00 against 97.00 is an amount mismatch with an exact delta")
    void amountMismatch() {
        var result = classify(List.of(expected()),
                List.of(provider(Money.of(9_700, EGP), FEE, NET,
                        PaymentState.SETTLED, "MERCH-77", DATE)));

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.AMOUNT_MISMATCH);
        assertThat(result.deltaMinor()).contains(300L);
        assertThat(result.differences()).hasSize(1);
        assertThat(result.differences().get(0).attribute())
                .as("an amount mismatch must name which amount, per spec 04 §4")
                .isEqualTo("GROSS");
        assertThat(result.differences().get(0).expected()).isEqualTo("10000 EGP");
        assertThat(result.differences().get(0).actual()).isEqualTo("9700 EGP");
        assertThat(result.differences().get(0).deltaMinor()).isEqualTo(300L);
        assertThat(result.requiresCase()).isTrue();
    }

    @Test
    @DisplayName("U-RECON-03: a one-minor-unit difference is a difference; zero is not")
    void zeroIsNotADifference() {
        var same = classify(List.of(expected()),
                List.of(provider(GROSS, FEE, NET, PaymentState.SETTLED, "MERCH-77", DATE)));
        assertThat(same.differences()).isEmpty();

        var oneUnit = classify(List.of(expected()),
                List.of(provider(Money.of(9_999, EGP), FEE, NET, PaymentState.SETTLED, "MERCH-77", DATE)));
        assertThat(oneUnit.outcome()).isEqualTo(ReconciliationOutcome.AMOUNT_MISMATCH);
        assertThat(oneUnit.deltaMinor()).contains(1L);
    }

    @Test
    @DisplayName("U-RECON-02: the primary reason is the first difference, but every difference is kept")
    void firstDifferenceWinsButAllAreRecorded() {
        var result = classify(List.of(expected()),
                List.of(provider(Money.of(9_000, EGP), Money.of(100, EGP), NET,
                        PaymentState.CAPTURED, "SOMEONE-ELSE", LocalDate.of(2026, 10, 9))));

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.AMOUNT_MISMATCH);
        // gross, fee, status, reference, date - five differences, one primary reason.
        assertThat(result.differences()).hasSize(5);
        assertThat(result.primaryReason()).contains(ReconciliationOutcome.AMOUNT_MISMATCH);
        assertThat(result.differences()).extracting(ReconciliationClassification.Difference::component)
                .containsExactly(
                        ReconciliationOutcome.AMOUNT_MISMATCH,
                        ReconciliationOutcome.AMOUNT_MISMATCH,
                        ReconciliationOutcome.STATUS_MISMATCH,
                        ReconciliationOutcome.REFERENCE_MISMATCH,
                        ReconciliationOutcome.SETTLEMENT_DATE_MISMATCH);
        assertThat(result.differences()).extracting(ReconciliationClassification.Difference::attribute)
                .containsExactly("GROSS", "FEE", null, null, null);
    }

    @Test
    @DisplayName("C-RECON-11: currency takes precedence over amount, and no delta is offered")
    void currencyOutranksAmount() {
        var result = classify(List.of(expected()),
                List.of(provider(Money.of(9_000, USD), Money.of(100, USD), Money.of(8_900, USD),
                        PaymentState.SETTLED, "MERCH-77", DATE)));

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.CURRENCY_MISMATCH);
        assertThat(result.primaryReason()).contains(ReconciliationOutcome.CURRENCY_MISMATCH);
        // Comparing amounts across currencies would be meaningless, so no delta is claimed.
        assertThat(result.deltaMinor()).isEmpty();
        assertThat(result.outcome().severity()).isEqualTo(ReconciliationOutcome.Severity.HIGH);

        // Suppressing the money comparison must not suppress everything. An operator still needs
        // the non-monetary differences, which are meaningful across a currency boundary: telling
        // them "the currency differs" and nothing else would hide a settlement-date break behind it.
        var mixed = classify(List.of(expected()),
                List.of(provider(Money.of(9_000, USD), Money.of(100, USD), Money.of(8_900, USD),
                        PaymentState.CAPTURED, "MERCH-OTHER", DATE.plusDays(1))));

        assertThat(mixed.differences()).extracting(ReconciliationClassification.Difference::component)
                .containsExactly(ReconciliationOutcome.CURRENCY_MISMATCH,
                        ReconciliationOutcome.STATUS_MISMATCH,
                        ReconciliationOutcome.REFERENCE_MISMATCH,
                        ReconciliationOutcome.SETTLEMENT_DATE_MISMATCH);
        assertThat(mixed.differences())
                .extracting(ReconciliationClassification.Difference::component)
                .doesNotContain(ReconciliationOutcome.AMOUNT_MISMATCH);
        assertThat(mixed.deltaMinor()).isEmpty();
    }

    @Test
    @DisplayName("C-RECON-03: a status disagreement is a status mismatch")
    void statusMismatch() {
        var result = classify(List.of(expected()),
                List.of(provider(GROSS, FEE, NET, PaymentState.REFUNDED, "MERCH-77", DATE)));

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.STATUS_MISMATCH);
        assertThat(result.outcome().severity()).isEqualTo(ReconciliationOutcome.Severity.HIGH);
    }

    @Test
    @DisplayName("C-RECON-04: present on one side only, in both directions")
    void missingOnEitherSide() {
        var internalOnly = classify(List.of(expected()), List.of());
        assertThat(internalOnly.outcome()).isEqualTo(ReconciliationOutcome.MISSING_ON_PROVIDER);
        assertThat(internalOnly.requiresCase()).isTrue();

        var providerOnly = classify(List.of(),
                List.of(provider(GROSS, FEE, NET, PaymentState.SETTLED, "MERCH-77", DATE)));
        assertThat(providerOnly.outcome()).isEqualTo(ReconciliationOutcome.MISSING_INTERNAL);
        assertThat(providerOnly.requiresCase()).isTrue();
    }

    @Test
    @DisplayName("U-RECON-01: duplicate provider records win over everything else")
    void duplicatesOutrankAttributeComparison() {
        var result = classify(List.of(expected()),
                List.of(provider(Money.of(1, EGP), FEE, NET, PaymentState.FAILED, "X", null),
                        provider(Money.of(2, EGP), FEE, NET, PaymentState.FAILED, "Y", null)));

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.DUPLICATE_PROVIDER_RECORD);
        assertThat(result.requiresCase()).isTrue();
        assertThat(result.outcome().severity()).isEqualTo(ReconciliationOutcome.Severity.HIGH);
    }

    @Test
    @DisplayName("U-RECON-01: ambiguity outranks attribute comparison, and nothing is compared")
    void ambiguityOutranksAttributeComparison() {
        var result = classify(
                List.of(expected(), new ReconciliationSubject.Expected("pay_2",
                        Money.of(1, EGP), FEE, NET, PaymentState.SETTLED, "MERCH-77", DATE)),
                List.of(provider(GROSS, FEE, NET, PaymentState.SETTLED, "MERCH-77", DATE)));

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.AMBIGUOUS_MATCH);
        assertThat(result.primaryReason()).contains(ReconciliationOutcome.AMBIGUOUS_MATCH);
    }

    // ------------------------------------------------------------------ F-01 / F-02

    @Test
    @DisplayName("F-01: an invalid settlement is never MATCHED, even when every amount agrees")
    void anInvalidSettlementIsNeverMatched() {
        // The F-01 shape: our ledger holds an EGP capture, the provider-side row states the same
        // gross / fee / net, and the currency on that row is the provider's USD rather than ours.
        // Before the fix the row was written with OUR currency, so this comparison agreed with
        // itself and reported MATCHED for a settlement that had posted USD ledger entries.
        var result = classify(
                List.of(expected(PaymentState.CAPTURED)),
                List.of(provider(Money.of(10_000, USD), Money.of(300, USD), Money.of(9_700, USD),
                        PaymentState.SETTLED, "MERCH-77", DATE, SettlementValidity.INVALID)));

        assertThat(result.outcome())
                .as("a transaction covered by a broken document cannot be certified")
                .isEqualTo(ReconciliationOutcome.SETTLEMENT_RECORD_INVALID);
        assertThat(result.requiresCase()).isTrue();
        assertThat(result.outcome().severity()).isEqualTo(ReconciliationOutcome.Severity.HIGH);

        assertThat(result.differences())
                .as("the specifics are still recorded, not discarded in favour of the headline")
                .extracting(ReconciliationClassification.Difference::component)
                .contains(ReconciliationOutcome.CURRENCY_MISMATCH);
    }

    @Test
    @DisplayName("F-02: a settlement whose totals contradict its lines is SETTLEMENT_RECORD_INVALID")
    void anIncoherentSettlementIsReportedAsSuch() {
        // Currency agrees, so the F-01 reason does not apply, and nothing else about the
        // transaction differs. The only thing wrong is the document — which is exactly the case that
        // previously fell through as MATCHED.
        var result = classify(
                List.of(expected(PaymentState.CAPTURED)),
                List.of(provider(GROSS, FEE, NET, PaymentState.SETTLED, "MERCH-77", DATE,
                        SettlementValidity.INVALID)));

        assertThat(result.outcome())
                .isEqualTo(ReconciliationOutcome.SETTLEMENT_RECORD_INVALID);
        assertThat(result.differences())
                .as("a case with an empty differences[] and no actionable reason gets closed quietly")
                .isNotEmpty();
    }

    @Test
    @DisplayName("A valid settlement is classified by its attributes exactly as before")
    void aValidSettlementChangesNothing() {
        // The control. A new outcome that also fires on good data would silence real differences.
        assertThat(classify(List.of(expected(PaymentState.SETTLED)),
                List.of(provider(GROSS, FEE, NET, PaymentState.SETTLED, "MERCH-77", DATE)))
                .outcome()).isEqualTo(ReconciliationOutcome.MATCHED);

        assertThat(classify(List.of(expected(PaymentState.SETTLED)),
                List.of(provider(Money.of(9_700, EGP), FEE, NET, PaymentState.SETTLED, "MERCH-77",
                        DATE)))
                .outcome()).isEqualTo(ReconciliationOutcome.AMOUNT_MISMATCH);
    }

    @Test
    @DisplayName("Classification is deterministic: identical input, identical output, every time")
    void classificationIsDeterministic() {
        var input = subject(List.of(expected()),
                List.of(provider(Money.of(9_000, EGP), FEE, NET, PaymentState.CAPTURED, "OTHER",
                        LocalDate.of(2026, 9, 1))));

        var first = ReconciliationClassifier.classify(input);
        for (int i = 0; i < 50; i++) {
            assertThat(ReconciliationClassifier.classify(input)).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("A null-safe subject still classifies rather than throwing")
    void emptySubjectIsHandled() {
        var result = ReconciliationClassifier.classify(
                new ReconciliationSubject("SIMULATED_PSP", "EXT-X", List.of(), List.of()));

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.MISSING_INTERNAL);
        assertThat(result.subjectKey()).isEqualTo("SIMULATED_PSP:EXT-X");
    }
}
