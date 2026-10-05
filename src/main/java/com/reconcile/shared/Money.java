package com.reconcile.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An amount of money in a single currency, held as an integer number of minor units.
 *
 * <p>This is the type the entire system moves money with. There is no {@code double}, no
 * {@code float} and no {@code BigDecimal} on any accounting path: {@code 0.1 + 0.2} is not 0.3,
 * and in a ledger that is not a rounding curiosity, it is a wrong balance.
 *
 * <p>Every rule below is enforced in the canonical constructor, so an invalid {@code Money} cannot
 * exist anywhere in the application — including values built by JPA, Jackson, or a test.
 *
 * <p><b>Sign convention.</b> A {@code Money} amount is always non-negative. A negative amount is a
 * <i>direction</i>, and direction belongs to the ledger entry, not to the amount. This is why
 * {@code Dr} and {@code Cr} are separate from the value.
 */
public record Money(long amountMinor, CurrencyCode currency) implements Comparable<Money> {

    /**
     * Upper bound on a single amount, in minor units. Chosen so that summing many of them cannot
     * overflow a {@code long}; the application rejects absurd input loudly rather than wrapping
     * silently, because a wrapped total is an undetectable corruption.
     */
    public static final long MAX_AMOUNT_MINOR = 1_000_000_000_000L; // 10 billion major units

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (amountMinor < 0) {
            throw new IllegalArgumentException(
                    "Money amount must be non-negative; a negative amount is a direction, not a value: "
                            + amountMinor);
        }
        if (amountMinor > MAX_AMOUNT_MINOR) {
            throw new IllegalArgumentException("amount exceeds the supported maximum: " + amountMinor);
        }
    }

    public static Money of(long amountMinor, CurrencyCode currency) {
        return new Money(amountMinor, currency);
    }

    public static Money zero(CurrencyCode currency) {
        return new Money(0L, currency);
    }

    public boolean isZero() {
        return amountMinor == 0L;
    }

    /** @throws IllegalArgumentException if the currencies differ. */
    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amountMinor, other.amountMinor), currency);
    }

    /** @throws IllegalArgumentException if the currencies differ, or the result would be negative. */
    public Money minus(Money other) {
        requireSameCurrency(other);
        long result = Math.subtractExact(amountMinor, other.amountMinor);
        if (result < 0) {
            throw new IllegalArgumentException(
                    "result would be negative: " + amountMinor + " - " + other.amountMinor);
        }
        return new Money(result, currency);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (currency != other.currency) {
            throw new IllegalArgumentException(
                    "cannot combine " + currency + " with " + other.currency);
        }
    }

    /**
     * Applies a basis-point rate and rounds half up on the result.
     *
     * <p>Used only for fee computation. {@code bps} are ten-thousandths of a unit, so the divisor is
     * exactly {@code 10_000} — the single most common place to get a fee subtly wrong.
     *
     * @throws IllegalArgumentException if bps is negative or above 10 000
     */
    public Money multipliedByBps(int bps) {
        if (bps < 0 || bps > 10_000) {
            throw new IllegalArgumentException("bps must be between 0 and 10000: " + bps);
        }
        // BigDecimal is used here ONLY as an integer-rounding helper on the way back to a long.
        // No monetary value is ever represented as a decimal.
        long result = BigDecimal.valueOf(amountMinor)
                .multiply(BigDecimal.valueOf(bps))
                .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP)
                .longValueExact();
        return new Money(result, currency);
    }

    /** @return a decimal rendering for logs and human-facing output only. Never for arithmetic. */
    public String toDisplayString() {
        return currency + " " + BigDecimal.valueOf(amountMinor, currency.exponent())
                .setScale(currency.exponent(), RoundingMode.UNNECESSARY)
                .toPlainString();
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(amountMinor, other.amountMinor);
    }

    @Override
    public String toString() {
        return toDisplayString();
    }
}