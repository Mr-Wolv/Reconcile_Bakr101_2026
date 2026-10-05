package com.reconcile.reconciliation;

/**
 * Whether a stored settlement record may be acted on.
 *
 * <p>The distinction exists because a provider's statement is evidence before it is an instruction.
 * A record marked {@link #INVALID} is kept in full and is still the provider's own words — but it
 * does not settle a payment, does not post to the ledger, and cannot certify a reconciliation.
 *
 * @see SettlementRecordValidator
 */
public enum SettlementValidity {

    /** Every invariant holds. The record may settle payments and move cash. */
    VALID,

    /**
     * The record contradicts itself, or contradicts the payments it claims to cover.
     *
     * <p>Stored, surfaced, and inert. The reason is written to
     * {@code settlement_records.validity_reason} so an operator can read what was wrong without
     * having to reconstruct it from logs.
     */
    INVALID
}