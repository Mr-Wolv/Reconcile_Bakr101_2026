package com.reconcile.ledger;

import com.reconcile.shared.CurrencyCode;
import com.reconcile.shared.Money;

/**
 * A merchant fee schedule: a basis-point rate applied to the gross amount.
 *
 * <p>The only guarantee this class makes, and the reason it exists rather than an inline
 * calculation, is:
 *
 * <pre>{@code gross == net + fee   exactly,   for every amount and every rate}</pre>
 *
 * <p>The fee is rounded half up; {@code net} is then the remainder rather than a separately rounded
 * number. Rounding both sides independently is how a ledger ends up a minor unit out of balance
 * forever, with no row pointing at the cause.
 *
 * <p>The rate is frozen onto the payment at creation. Recomputing it later would mean a policy edit
 * could retroactively change what a captured payment is worth — which is precisely the discrepancy
 * the reconciliation demo is built to surface, and it should be found by reconciliation rather than
 * manufactured by a background job.
 */
public record FeePolicy(String code, int bps, CurrencyCode currency) {

    public FeePolicy {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("fee policy code must not be blank");
        }
        if (bps < 0 || bps > 10_000) {
            throw new IllegalArgumentException("bps must be between 0 and 10000: " + bps);
        }
        if (currency == null) {
            throw new IllegalArgumentException("fee policy currency is required");
        }
    }

    /** The default schedule used by the seed migration: 3.00% of gross. */
    public static FeePolicy defaultPolicy() {
        return new FeePolicy("DEFAULT", 300, CurrencyCode.EGP);
    }

    /** The fee for a gross amount. */
    public Money feeFor(Money gross) {
        if (gross.currency() != currency) {
            throw new IllegalArgumentException(
                    "fee policy is denominated in " + currency + " but the payment is in "
                            + gross.currency());
        }
        return gross.multipliedByBps(bps);
    }

    /** The merchant's share: gross minus the fee, always non-negative. */
    public Money netFor(Money gross) {
        return gross.minus(feeFor(gross));
    }
}