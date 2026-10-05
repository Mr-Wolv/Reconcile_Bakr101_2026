package com.reconcile.reconciliation;

import com.reconcile.shared.Money;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The result of classifying one subject.
 *
 * <p>Two things are recorded, and the distinction matters. The <b>primary reason</b> is the first
 * difference in a fixed order, which keeps it a deterministic function of the inputs rather than a
 * judgement call. The <b>differences</b> list holds <i>every</i> differing attribute, so an operator
 * sees the whole picture even though the case is filed under one reason.
 *
 * <p>Recording all differences and escalating only one is the difference between a useful
 * reconciliation report and one that sends someone hunting for a third problem they will only
 * discover after fixing the first two.
 */
public record ReconciliationClassification(
        ReconciliationOutcome outcome,
        String subjectKey,
        List<Difference> differences,
        Optional<Money> expectedGross,
        Optional<Money> actualGross,
        Optional<Long> deltaMinor) {

    public ReconciliationClassification {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(subjectKey, "subjectKey");
        differences = List.copyOf(Objects.requireNonNull(differences, "differences"));
    }

    /**
     * One differing attribute.
     *
     * @param component which field disagrees; the primary reason when this is the first difference
     * @param attribute the specific field, e.g. {@code GROSS}, {@code FEE}, {@code NET} for an
     *                 {@link ReconciliationOutcome#AMOUNT_MISMATCH}. Null for non-amount
     *                 differences, where {@code component} already names the field precisely.
     * @param expected  our value, as text, for the case record
     * @param actual    the provider's value
     * @param delta     expected minus actual in minor units, or null when not comparable
     */
    public record Difference(
            ReconciliationOutcome component,
            String attribute,
            String expected,
            String actual,
            Long deltaMinor) {
        public Difference {
            Objects.requireNonNull(component, "component");
        }

        /** Convenience for differences where the attribute is the component itself. */
        public static Difference of(ReconciliationOutcome component, String expected, String actual) {
            return new Difference(component, null, expected, actual, null);
        }
    }

    /** The single reason a case is filed under, or empty when there are no differences. */
    public Optional<ReconciliationOutcome> primaryReason() {
        return differences.isEmpty() ? Optional.empty() : Optional.of(differences.get(0).component());
    }

    /** Whether an operational case should be opened for this result. */
    public boolean requiresCase() {
        return outcome.requiresCase();
    }
}