package com.reconcile.payment;

/**
 * The lifecycle of a payment.
 *
 * <p>Two states are terminal: there is no outgoing transition from either, and the state machine
 * enforces that rather than relying on every caller to remember it.
 */
public enum PaymentState {

    /** Accepted, not yet authorised. No ledger effect. */
    CREATED,

    /** Funds committed, not yet taken. No ledger effect. */
    AUTHORIZED,

    /** Money taken from the customer and owed to the merchant. A {@code PAYMENT_CAPTURE} is posted. */
    CAPTURED,

    /** The PSP has paid us. A {@code SETTLEMENT_RECEIVED} covers this payment. */
    SETTLED,

    /** Terminal failure. No money moved. */
    FAILED,

    /** Terminal. Money returned. */
    REFUNDED;

    public boolean isTerminal() {
        return this == FAILED || this == REFUNDED;
    }

    /**
     * Whether this state is at least as far along as {@code other} on the happy path.
     *
     * <p>Used by webhook handling to tell a genuinely out-of-order event (already superseded, so it
     * has no effect) from one that merely arrived late (our record is behind the provider's, so it
     * should still be applied). Getting that distinction wrong either drops real updates or
     * resurrects dead ones.
     */
    public boolean isAtLeastAsFarAs(PaymentState other) {
        return ordinal() >= other.ordinal();
    }
}