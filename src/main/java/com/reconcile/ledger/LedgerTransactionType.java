package com.reconcile.ledger;

/**
 * The kinds of posting the ledger accepts.
 *
 * <p>The names must match the {@code ledger_transactions.type} check constraint in
 * {@code V1__baseline.sql}. There is deliberately no {@code ADJUSTMENT} catch-all here: corrections
 * are {@link #REVERSAL} plus a new transaction, never an edit, and an open-ended "fix it up" type
 * would quietly reopen that door.
 */
public enum LedgerTransactionType {

    /** Capture: money taken from the customer, split between the merchant and the platform fee. */
    PAYMENT_CAPTURE,

    /**
     * The exact sign-flipped mirror of another transaction.
     *
     * <p>A reversal carries no business source of its own; it is identified by the transaction it
     * reverses. That is why the double-posting index excludes this type.
     */
    REVERSAL,

    /** Refund of a payment whose money has already reached {@code PLATFORM_CASH}. */
    PAYMENT_REFUND_SETTLED,

    /** Cash received from the PSP, clearing {@code PSP_CLEARING}. */
    SETTLEMENT_RECEIVED,

    /** Paying a merchant the net amounts of a set of settled payments. */
    MERCHANT_PAYOUT,

    /** Operator correction attached to a reconciliation case. Restricted to the clearing accounts. */
    PSP_ADJUSTMENT;

    public boolean isReversal() {
        return this == REVERSAL;
    }
}