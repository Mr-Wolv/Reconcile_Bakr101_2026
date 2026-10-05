package com.reconcile.ledger;

/**
 * Direction of a ledger entry.
 *
 * <p>This enum <i>is</i> the sign. {@link com.reconcile.shared.Money} is always non-negative, so
 * every debit and credit in the system is distinguished by this value rather than by a negative
 * amount. That separation is what makes "sum of debits equals sum of credits" a checkable
 * statement rather than a tautology.
 */
public enum Direction {
    DEBIT,
    CREDIT;

    /** The opposite side of the entry — what this one becomes when mirrored in a reversal. */
    public Direction opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }

    /**
     * Applies this direction to a stored balance in the account's <b>normal</b> direction.
     *
     * @param balanceMinor balance expressed in the account's normal direction
     * @param amountMinor  the (always positive) entry amount
     * @return the new balance
     */
    public long apply(long balanceMinor, long amountMinor) {
        return this == DEBIT ? Math.addExact(balanceMinor, amountMinor)
                             : Math.subtractExact(balanceMinor, amountMinor);
    }
}