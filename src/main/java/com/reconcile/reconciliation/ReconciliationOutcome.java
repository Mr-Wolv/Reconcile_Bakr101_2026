package com.reconcile.reconciliation;

/**
 * What reconciliation concluded about one subject.
 *
 * <p>The order of declaration is the precedence order used by {@link ReconciliationClassifier}, and
 * it encodes one idea: <b>a difference is untrustworthy until you know which record you are looking
 * at.</b> You cannot interpret an amount mismatch when the record is a duplicate, and you cannot
 * interpret anything when two of our own payments claim the same provider id.
 */
public enum ReconciliationOutcome {

    /** The two sides agree on every compared attribute and the payment has been settled. */
    MATCHED,

    /**
     * The two sides agree, but the PSP has not paid yet.
     *
     * <p>Kept distinct from {@link #MATCHED} on purpose. Conflating "we agree" with "we are done"
     * produces an alert stream nobody reads, because every unsettled payment looks identical to
     * every satisfied one.
     */
    MATCHED_NOT_SETTLED,

    /** A monetary amount differs. The specific component is carried in the difference detail. */
    AMOUNT_MISMATCH,

    /** The currencies differ — checked before any amount, because comparing across currencies is meaningless. */
    CURRENCY_MISMATCH,

    /** One side says SETTLED and the other does not. */
    STATUS_MISMATCH,

    /** The merchant reference differs. */
    REFERENCE_MISMATCH,

    /** The settlement date differs. */
    SETTLEMENT_DATE_MISMATCH,

    /** We captured it; the provider never reported it. Usually means a webhook was lost. */
    MISSING_ON_PROVIDER,

    /** The provider reported it; we have never heard of it. */
    MISSING_INTERNAL,

    /** The same provider transaction appears more than once. Amounts are not even compared. */
    DUPLICATE_PROVIDER_RECORD,

    /** More than one of our payments claims the same provider transaction id. No automatic match. */
    AMBIGUOUS_MATCH,

    /**
     * The settlement record covering this transaction is internally inconsistent — its totals
     * contradict its own lines, its currency contradicts the payment, or it lists the same
     * transaction twice.
     *
     * <p>It exists because "the two sides disagree" and "one of the two sides is not a coherent
     * document" are different problems, and reporting the first when the second is true sends an
     * operator to compare numbers that were never commensurable. Before this outcome existed, a
     * cross-currency settlement produced {@code MATCHED}: the provider-side row had been written
     * with our own currency rather than the provider's, so the comparison agreed with itself.
     *
     * <p>Ranked below the identity checks and above attribute comparison, because a broken record
     * makes every attribute difference it could produce untrustworthy as a diagnosis.
     */
    SETTLEMENT_RECORD_INVALID;

    /** Whether this outcome should open an operational case. */
    public boolean requiresCase() {
        return this != MATCHED && this != MATCHED_NOT_SETTLED;
    }

    /** Operational severity of a case opened for this outcome. */
    public Severity severity() {
        return switch (this) {
            case AMOUNT_MISMATCH, CURRENCY_MISMATCH, STATUS_MISMATCH,
                 DUPLICATE_PROVIDER_RECORD, SETTLEMENT_RECORD_INVALID -> Severity.HIGH;
            case MISSING_ON_PROVIDER, MISSING_INTERNAL, AMBIGUOUS_MATCH,
                 REFERENCE_MISMATCH -> Severity.MEDIUM;
            case SETTLEMENT_DATE_MISMATCH -> Severity.LOW;
            case MATCHED, MATCHED_NOT_SETTLED -> Severity.LOW;
        };
    }

    public enum Severity {
        HIGH, MEDIUM, LOW
    }
}