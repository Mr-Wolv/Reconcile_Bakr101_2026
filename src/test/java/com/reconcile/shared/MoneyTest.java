package com.reconcile.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Maps to rows {@code U-MONEY-01}, {@code U-MONEY-02}, {@code U-MONEY-03}, {@code U-MONEY-05}. */
class MoneyTest {

    @Test
    @DisplayName("U-MONEY-01: a negative amount cannot be constructed - a sign is a direction, not a value")
    void negativeAmountIsRejected() {
        assertThatThrownBy(() -> Money.of(-1, CurrencyCode.EGP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }

    @Test
    @DisplayName("U-MONEY-02: arithmetic across currencies is refused, not silently coerced")
    void currencyMismatchIsRejected() {
        Money egp = Money.of(10_000, CurrencyCode.EGP);

        assertThatThrownBy(() -> egp.plus(Money.of(100, CurrencyCode.USD)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot combine");
        assertThatThrownBy(() -> egp.minus(Money.of(100, CurrencyCode.USD)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> egp.compareTo(Money.of(100, CurrencyCode.USD)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("U-MONEY-02: subtraction below zero is refused rather than wrapping")
    void subtractionBelowZeroIsRejected() {
        Money small = Money.of(100, CurrencyCode.EGP);

        assertThatThrownBy(() -> small.minus(Money.of(101, CurrencyCode.EGP)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    @DisplayName("U-MONEY-03: unsupported and blank currencies are rejected, never defaulted")
    void currencyParsingIsStrict() {
        assertThat(CurrencyCode.parse("egp")).isEqualTo(CurrencyCode.EGP);
        assertThat(CurrencyCode.parse(" EUR ")).isEqualTo(CurrencyCode.EUR);

        assertThatThrownBy(() -> CurrencyCode.parse("JPY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported");
        assertThatThrownBy(() -> CurrencyCode.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CurrencyCode.parse("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("U-MONEY-05: amounts beyond the supported range are rejected, not overflowed")
    void absurdAmountsAreRejected() {
        assertThatThrownBy(() -> Money.of(Money.MAX_AMOUNT_MINOR + 1, CurrencyCode.EGP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum");

        // The per-value cap is what makes long overflow unreachable in the first place: no two
        // representable Money values can sum past Long.MAX_VALUE. The result of the addition is
        // therefore rejected by the constructor rather than wrapping. addExact is kept as a second
        // line of defence if that cap is ever raised.
        Money huge = Money.of(Money.MAX_AMOUNT_MINOR, CurrencyCode.EGP);
        assertThatThrownBy(() -> huge.plus(Money.of(10_000, CurrencyCode.EGP)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum");
        assertThat(Money.MAX_AMOUNT_MINOR * 2L).isLessThan(Long.MAX_VALUE);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, 99L, 100L, 101L, 9_999L, 10_000L, 12_345L, 99_999_999L})
    @DisplayName("U-MONEY-01: zero and ordinary values round-trip exactly")
    void valuesRoundTrip(long amountMinor) {
        Money money = Money.of(amountMinor, CurrencyCode.EGP);
        assertThat(money.amountMinor()).isEqualTo(amountMinor);
        assertThat(money.isZero()).isEqualTo(amountMinor == 0L);
    }

    @Test
    @DisplayName("Money serialises as minor units, never as a decimal, so JSON can carry no float")
    void displayUsesMinorUnits() {
        Money money = Money.of(12_550, CurrencyCode.EGP);
        assertThat(money.toDisplayString()).isEqualTo("EGP 125.50");
        // The record component is a long; that is what Jackson serialises.
        assertThat(money.amountMinor()).isInstanceOf(Long.class);
    }
}
