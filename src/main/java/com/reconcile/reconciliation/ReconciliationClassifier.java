package com.reconcile.reconciliation;

import com.reconcile.payment.PaymentState;
import com.reconcile.shared.CurrencyCode;
import com.reconcile.shared.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides what a reconciliation subject means.
 *
 * <p>Pure, deterministic, and free of I/O — the same inputs always produce the same classification,
 * which is what makes the golden-fixture test ({@code C-RECON-05}) possible and what makes a
 * reconciliation report trustworthy enough to act on.
 *
 * <p>There is no fuzzy matching and no amount-based fallback. Matching a provider record to a
 * payment by "close enough" amount would raise the match rate and quietly lower the correctness of
 * every match it produced; a wrong confident match is far more expensive than an honest
 * {@link ReconciliationOutcome#AMBIGUOUS_MATCH} that opens a case. Identity is
 * {@code (provider, externalTransactionId)}, exact.
 */
public final class ReconciliationClassifier {

    private ReconciliationClassifier() {
    }

    /**
     * Classifies one subject.
     *
     * <p>Stage 3 of the algorithm in {@code docs/spec/04-reconciliation.md}, evaluated strictly in
     * order. The ordering is the design: identity problems are resolved before attribute
     * comparison, because interpreting an amount difference requires knowing <i>which</i> record you
     * are looking at.
     */
    public static ReconciliationClassification classify(ReconciliationSubject subject) {
        Objects.requireNonNull(subject, "subject");
        String subjectKey = subject.provider() + ":" + subject.externalTransactionId();

        List<ReconciliationSubject.ProviderRecord> provider = subject.providerRecords();
        List<ReconciliationSubject.Expected> internal = subject.internal();

        // 1. Duplicate provider records make every attribute comparison meaningless.
        if (provider.size() > 1) {
            return plain(subjectKey, ReconciliationOutcome.DUPLICATE_PROVIDER_RECORD,
                    provider.size() + " provider records share this transaction id");
        }
        // 2. They reported it; we have never heard of it.
        if (internal.isEmpty()) {
            return plain(subjectKey, ReconciliationOutcome.MISSING_INTERNAL,
                    "no internal payment carries this transaction id");
        }
        // 3. We captured it; they never reported it.
        if (provider.isEmpty()) {
            return plain(subjectKey, ReconciliationOutcome.MISSING_ON_PROVIDER,
                    "internal payment has no provider record");
        }
        // 4. Two of our payments claim the same id. Refuse to guess which one.
        if (internal.size() > 1) {
            return plain(subjectKey, ReconciliationOutcome.AMBIGUOUS_MATCH,
                    internal.size() + " internal payments claim this transaction id");
        }
        // 5. The covering settlement record is not a coherent document.
        //
        // Ranked here rather than inside compare(), and before any attribute comparison, because a
        // broken record makes every difference it could produce a poor diagnosis. A USD settlement
        // covering an EGP payment produces a currency difference AND a status difference, and
        // reporting either of them tells an operator to compare amounts that were never
        // commensurable rather than telling them the document is wrong.
        //
        // The attribute differences are still recorded, because they are the evidence for why the
        // record is in trouble — they are just not the headline.
        if (provider.get(0).coveredByInvalidSettlement()) {
            return compareWithInvalidSettlement(subjectKey, internal.get(0), provider.get(0));
        }

        return compare(subjectKey, internal.get(0), provider.get(0));
    }

    /**
     * Classifies a transaction whose covering settlement record was refused at ingestion.
     *
     * <p>Runs the full attribute comparison so the operator gets the specifics, then reports the
     * real problem as the primary reason.
     */
    private static ReconciliationClassification compareWithInvalidSettlement(
            String subjectKey,
            ReconciliationSubject.Expected expected,
            ReconciliationSubject.ProviderRecord actual) {

        ReconciliationClassification whatTheComparisonFound =
                compare(subjectKey, expected, actual);

        List<ReconciliationClassification.Difference> differences =
                whatTheComparisonFound.differences().isEmpty()
                        // A case with an empty differences[] and a reason nobody can act on is how a
                        // finding gets quietly closed. The record itself is the finding, so it is
                        // stated as one whenever nothing else disagrees.
                        ? List.of(ReconciliationClassification.Difference.of(
                                ReconciliationOutcome.SETTLEMENT_RECORD_INVALID,
                                "no settlement was applied to this transaction",
                                "the covering settlement record was refused as inconsistent"))
                        : whatTheComparisonFound.differences();

        return new ReconciliationClassification(
                ReconciliationOutcome.SETTLEMENT_RECORD_INVALID,
                subjectKey,
                differences,
                whatTheComparisonFound.expectedGross(),
                whatTheComparisonFound.actualGross(),
                whatTheComparisonFound.deltaMinor());
    }

    private static ReconciliationClassification compare(
            String subjectKey,
            ReconciliationSubject.Expected expected,
            ReconciliationSubject.ProviderRecord actual) {

        List<ReconciliationClassification.Difference> differences = new ArrayList<>();

        // Attribute comparison order is fixed. The first difference becomes the case's primary
        // reason, so this ordering is part of the contract and must not be reordered casually.
        boolean currencyDiffers = expected.gross().currency() != actual.gross().currency();

        if (currencyDiffers) {
            differences.add(ReconciliationClassification.Difference.of(
                    ReconciliationOutcome.CURRENCY_MISMATCH,
                    expected.gross().currency().code(), actual.gross().currency().code()));
        } else {
            addIfAmountDiffers(differences, ReconciliationOutcome.AMOUNT_MISMATCH, "GROSS",
                    expected.gross(), actual.gross());
            addIfAmountDiffers(differences, ReconciliationOutcome.AMOUNT_MISMATCH, "FEE",
                    expected.fee(), actual.fee());
            addIfAmountDiffers(differences, ReconciliationOutcome.AMOUNT_MISMATCH, "NET",
                    expected.net(), actual.net());
        }

        if (expected.status() != actual.status()) {
            differences.add(ReconciliationClassification.Difference.of(
                    ReconciliationOutcome.STATUS_MISMATCH,
                    expected.status().name(), actual.status().name()));
        }

        if (!Objects.equals(expected.merchantReference(), actual.merchantReference())) {
            differences.add(ReconciliationClassification.Difference.of(
                    ReconciliationOutcome.REFERENCE_MISMATCH,
                    expected.merchantReference(), actual.merchantReference()));
        }

        if (!Objects.equals(expected.settlementDate(), actual.settlementDate())) {
            differences.add(ReconciliationClassification.Difference.of(
                    ReconciliationOutcome.SETTLEMENT_DATE_MISMATCH,
                    String.valueOf(expected.settlementDate()), String.valueOf(actual.settlementDate())));
        }

        // A currency disagreement makes the monetary deltas meaningless, so no delta is offered.
        Optional<Long> delta = currencyDiffers ? Optional.empty()
                : Optional.of(expected.gross().amountMinor() - actual.gross().amountMinor());

        if (differences.isEmpty()) {
            ReconciliationOutcome outcome = expected.status() == PaymentState.SETTLED
                    ? ReconciliationOutcome.MATCHED
                    : ReconciliationOutcome.MATCHED_NOT_SETTLED;
            return new ReconciliationClassification(outcome, subjectKey, List.of(),
                    Optional.of(expected.gross()), Optional.of(actual.gross()), delta);
        }

        return new ReconciliationClassification(differences.get(0).component(), subjectKey, differences,
                Optional.of(expected.gross()), Optional.of(actual.gross()), delta);
    }

    private static void addIfAmountDiffers(
            List<ReconciliationClassification.Difference> into,
            ReconciliationOutcome reason,
            String component,
            Money expected,
            Money actual) {

        if (expected.amountMinor() == actual.amountMinor()) {
            return;
        }
        into.add(new ReconciliationClassification.Difference(reason, component,
                expected.amountMinor() + " " + expected.currency().code(),
                actual.amountMinor() + " " + actual.currency().code(),
                expected.amountMinor() - actual.amountMinor()));
    }

    private static ReconciliationClassification plain(
            String subjectKey, ReconciliationOutcome outcome, String detail) {
        return new ReconciliationClassification(outcome, subjectKey,
                List.of(ReconciliationClassification.Difference.of(outcome, "-", detail)),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * Convenience for tests and the ops dashboard: the currency two sides agree on, if any.
     *
     * @return the common currency, or empty when the sides disagree or one is absent
     */
    public static Optional<CurrencyCode> commonCurrency(ReconciliationSubject subject) {
        return subject.internal().isEmpty() || subject.providerRecords().isEmpty()
                ? Optional.empty()
                : Optional.of(subject.internal().get(0).gross().currency());
    }
}