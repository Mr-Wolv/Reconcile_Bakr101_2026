package com.reconcile.ledger;

import static com.reconcile.shared.CurrencyCode.EGP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconcile.shared.CurrencyCode;
import com.reconcile.shared.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Maps to row {@code U-MONEY-04}, plus the reference arithmetic quoted in the specification. */
class FeePolicyTest {

    @ParameterizedTest(name = "gross={0} bps={1} -> fee={2} net={3}")
    @CsvSource({
            "10000, 300,  300, 9700",   // the reference example: 100.00 EGP, 3.00% -> 3.00 fee
            "10000, 0,     0, 10000",
            "10000, 10000, 10000, 0",
            "1,     300,   0,    1",    // 0.01 at 3% rounds to 0, and net keeps the unit
            "3,     300,   0,    3",
            "7,     300,   0,    7",
            "17,    300,   1,    16",   // half-up boundary: 0.51 minor units -> 1
            "999,   300,   30,   969",
            "1,     1,     0,    1",
            "12345, 1375, 1697, 10648"   // an awkward rate, exactly represented
    })
    @DisplayName("U-MONEY-04: gross = net + fee holds exactly, including at rounding boundaries")
    void grossEqualsNetPlusFee(long grossMinor, int bps, long expectedFee, long expectedNet) {
        FeePolicy policy = new FeePolicy("TEST", bps, EGP);
        Money gross = Money.of(grossMinor, EGP);

        Money fee = policy.feeFor(gross);
        Money net = policy.netFor(gross);

        assertThat(fee.amountMinor()).isEqualTo(expectedFee);
        assertThat(net.amountMinor()).isEqualTo(expectedNet);
        assertThat(gross.amountMinor()).isEqualTo(net.amountMinor() + fee.amountMinor());
    }

    @Test
    @DisplayName("The documented worked example reproduces exactly")
    void referenceExample() {
        FeePolicy policy = FeePolicy.defaultPolicy();
        Money gross = Money.of(10_000, EGP);

        assertThat(policy.feeFor(gross)).isEqualTo(Money.of(300, EGP));
        assertThat(policy.netFor(gross)).isEqualTo(Money.of(9_700, EGP));
        assertThat(policy.feeFor(gross).toDisplayString()).isEqualTo("EGP 3.00");
    }

    @Test
    @DisplayName("A fee is never negative and net never exceeds gross")
    void feeIsBounded() {
        FeePolicy policy = new FeePolicy("TEST", 10_000, EGP);
        Money gross = Money.of(999, EGP);

        assertThat(policy.feeFor(gross)).isEqualTo(gross);
        assertThat(policy.netFor(gross).isZero()).isTrue();
    }

    @Test
    @DisplayName("A policy in the wrong currency is refused, not silently applied")
    void currencyMustMatch() {
        FeePolicy egpPolicy = FeePolicy.defaultPolicy();
        Money usd = Money.of(10_000, CurrencyCode.USD);

        assertThatThrownBy(() -> egpPolicy.feeFor(usd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("denominated in EGP");
    }

    @Test
    @DisplayName("bps outside 0..10000 are refused at construction")
    void bpsIsBounded() {
        assertThatThrownBy(() -> new FeePolicy("X", -1, EGP))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeePolicy("X", 10_001, EGP))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
