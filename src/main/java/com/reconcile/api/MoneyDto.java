package com.reconcile.api;

import com.reconcile.shared.CurrencyCode;
import com.reconcile.shared.Money;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Money on the wire.
 *
 * <p>Exactly {@code {"amountMinor": 12550, "currency": "EGP"}} in both directions, per
 * {@code docs/spec/06-api-contract.md}. Never a decimal, never a float, never a string of digits:
 * a JSON number like {@code 100.00} has no defined representation for a non-decimal binary base,
 * so whatever the client sends may not be the figure it meant. Integer minor units is the only
 * encoding with a round trip that is exact by construction.
 *
 * <p>Declared explicitly rather than relying on the implicit record constructor so the properties
 * are named, which keeps the JSON field names stable if the Java component names ever change.
 */
public record MoneyDto(long amountMinor, String currency) {

    @JsonCreator
    public MoneyDto(@JsonProperty("amountMinor") long amountMinor,
                    @JsonProperty("currency") String currency) {
        this.amountMinor = amountMinor;
        this.currency = currency;
    }

    /** Renders a domain {@link Money}. */
    public static MoneyDto of(Money money) {
        return new MoneyDto(money.amountMinor(), money.currency().code());
    }

    /**
     * Parses back to a domain {@link Money}.
     *
     * @throws com.reconcile.shared.DomainException {@code VALIDATION_FAILED} on a bad currency or a
     *         negative amount, so the caller gets a 400 rather than a deserialisation stack trace
     */
    public Money toMoney() {
        CurrencyCode parsed;
        try {
            parsed = CurrencyCode.parse(currency);
        } catch (IllegalArgumentException e) {
            throw com.reconcile.shared.DomainException.validation(
                    "currency", "is not a supported ISO-4217 code", currency);
        }
        try {
            return Money.of(amountMinor, parsed);
        } catch (IllegalArgumentException e) {
            throw com.reconcile.shared.DomainException.validation("amountMinor", e.getMessage(),
                    amountMinor);
        }
    }
}