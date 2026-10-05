package com.reconcile.shared;

/**
 * ISO-4217 currencies this build supports.
 *
 * <p>Deliberately excludes zero-decimal currencies such as JPY and KWD. Rounding rules for fees
 * differ for those, and half-supporting a currency is worse than not offering it: a wrong amount is
 * a wrong amount. {@code currency_units} in the database holds the same four rows, so adding one
 * later is a migration rather than a code change.
 */
public enum CurrencyCode {

    EGP(2, "Egyptian Pound"),
    USD(2, "US Dollar"),
    EUR(2, "Euro"),
    GBP(2, "Pound Sterling");

    private final int exponent;
    private final String displayName;

    CurrencyCode(int exponent, String displayName) {
        this.exponent = exponent;
        this.displayName = displayName;
    }

    /** Number of decimal places the currency is quoted in. All supported currencies use 2. */
    public int exponent() {
        return exponent;
    }

    /** The currency code as a database {@code CHAR(3)} value. */
    public String code() {
        return name();
    }

    public String displayName() {
        return displayName;
    }

    /**
     * Parses a code, rejecting anything unsupported rather than defaulting to a guess.
     *
     * @throws IllegalArgumentException if the code is null, blank, or unsupported
     */
    public static CurrencyCode parse(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("currency must not be null or blank");
        }
        String normalised = code.strip().toUpperCase();
        for (CurrencyCode candidate : values()) {
            if (candidate.name().equals(normalised)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("unsupported currency: " + code);
    }
}