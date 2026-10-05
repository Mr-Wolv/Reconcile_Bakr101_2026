package com.reconcile.ledger;

import static com.reconcile.shared.CurrencyCode.EGP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconcile.shared.CurrencyCode;
import com.reconcile.shared.Money;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Maps to rows {@code U-LED-01} … {@code U-LED-04}. */
class LedgerPostingCommandTest {

    private static Money egp(long amountMinor) {
        return Money.of(amountMinor, EGP);
    }

    private static LedgerPostingCommand.Entry debit(String account, long amount) {
        return new LedgerPostingCommand.Entry(account, Direction.DEBIT, egp(amount));
    }

    private static LedgerPostingCommand.Entry credit(String account, long amount) {
        return new LedgerPostingCommand.Entry(account, Direction.CREDIT, egp(amount));
    }

    @Test
    @DisplayName("U-LED-01: a balanced posting is accepted")
    void balancedPostingIsAccepted() {
        var command = new LedgerPostingCommand(
                LedgerTransactionType.PAYMENT_CAPTURE, EGP,
                List.of(debit("PSP_CLEARING", 10_000),
                        credit("MERCHANT_PAYABLE", 9_700),
                        credit("PLATFORM_FEE_REVENUE", 300)),
                "PAYMENT", "pay_1", "capture", null, null);

        assertThat(command.totalDebits()).isEqualTo(10_000L);
        assertThat(command.totalCredits()).isEqualTo(10_000L);
        assertThat(command.total()).isEqualTo(egp(10_000));
    }

    @Test
    @DisplayName("U-LED-02: an unbalanced posting is rejected before anything could be persisted")
    void unbalancedPostingIsRejected() {
        assertThatThrownBy(() -> new LedgerPostingCommand(
                LedgerTransactionType.PAYMENT_CAPTURE, EGP,
                List.of(debit("PSP_CLEARING", 10_000),
                        credit("MERCHANT_PAYABLE", 9_700)),
                "PAYMENT", "pay_1", "capture", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("L1 violated")
                .hasMessageContaining("do not equal");
    }

    @Test
    @DisplayName("L3: fewer than two entries, or only one direction, is rejected")
    void malformedShapeIsRejected() {
        assertThatThrownBy(() -> new LedgerPostingCommand(
                LedgerTransactionType.PAYMENT_CAPTURE, EGP,
                List.of(debit("PSP_CLEARING", 10_000)),
                "PAYMENT", "pay_1", "capture", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least two entries");

        assertThatThrownBy(() -> new LedgerPostingCommand(
                LedgerTransactionType.PAYMENT_CAPTURE, EGP,
                List.of(debit("PSP_CLEARING", 5_000), debit("PLATFORM_CASH", 5_000)),
                "PAYMENT", "pay_1", "capture", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both a debit and a credit");
    }

    @Test
    @DisplayName("L3: a zero-value entry is rejected")
    void zeroValueEntryIsRejected() {
        assertThatThrownBy(() -> new LedgerPostingCommand.Entry("PSP_CLEARING", Direction.DEBIT, egp(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zero-value");
    }

    @Test
    @DisplayName("U-LED-03: a mixed-currency transaction is rejected")
    void mixedCurrencyIsRejected() {
        assertThatThrownBy(() -> new LedgerPostingCommand(
                LedgerTransactionType.PAYMENT_CAPTURE, EGP,
                List.of(debit("PSP_CLEARING", 10_000),
                        credit("MERCHANT_PAYABLE", 9_700),
                        new LedgerPostingCommand.Entry("PLATFORM_FEE_REVENUE", Direction.CREDIT,
                                Money.of(300, CurrencyCode.USD))),
                "PAYMENT", "pay_1", "capture", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("L2 violated");
    }

    @Test
    @DisplayName("Only a REVERSAL may reference another transaction, and it must carry a reason")
    void reversalBookkeepingIsEnforced() {
        List<LedgerPostingCommand.Entry> mirror = List.of(
                credit("PSP_CLEARING", 10_000),
                debit("MERCHANT_PAYABLE", 9_700),
                debit("PLATFORM_FEE_REVENUE", 300));

        assertThatThrownBy(() -> new LedgerPostingCommand(
                LedgerTransactionType.REVERSAL, EGP, mirror, "PAYMENT", "pay_1", "r", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must name the transaction it reverses");

        assertThatThrownBy(() -> new LedgerPostingCommand(
                LedgerTransactionType.REVERSAL, EGP, mirror,
                "PAYMENT", "pay_1", "r", "ltx_1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must carry a reason");

        assertThatThrownBy(() -> new LedgerPostingCommand(
                LedgerTransactionType.PAYMENT_CAPTURE, EGP, mirror,
                "PAYMENT", "pay_1", "c", "ltx_1", "because"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only a REVERSAL");

        assertThatCode(() -> new LedgerPostingCommand(
                LedgerTransactionType.REVERSAL, EGP, mirror,
                "PAYMENT", "pay_1", "r", "ltx_1", "customer request"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("An unknown source type is rejected")
    void unknownSourceTypeIsRejected() {
        assertThatThrownBy(() -> new LedgerPostingCommand(
                LedgerTransactionType.PAYMENT_CAPTURE, EGP,
                List.of(debit("PSP_CLEARING", 100), credit("PLATFORM_CASH", 100)),
                "MADE_UP", "x", "c", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported source type");
    }
}
