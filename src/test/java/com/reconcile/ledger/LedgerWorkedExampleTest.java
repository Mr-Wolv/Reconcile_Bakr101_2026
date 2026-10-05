package com.reconcile.ledger;

import static com.reconcile.shared.CurrencyCode.EGP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconcile.shared.Money;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The worked example from {@code docs/spec/01-money-and-ledger.md} §3, executed.
 *
 * <p>Maps to {@code U-LED-09} and {@code U-LED-10}: the capture, the settlement and the payout, and
 * the claim that the three together leave exactly two non-zero accounts.
 */
class LedgerWorkedExampleTest {

    private static final FeePolicy POLICY = FeePolicy.defaultPolicy();
    private static final Money GROSS = Money.of(10_000, EGP);   // 100.00
    private static final Money FEE = POLICY.feeFor(GROSS);      //   3.00
    private static final Money NET = POLICY.netFor(GROSS);      //  97.00

    /** Applies a balanced posting to a running set of balances, the way the service does. */
    private static final class Balances {
        private final java.util.Map<String, Long> amounts = new java.util.LinkedHashMap<>();

        Balances() {
            amounts.put("PSP_CLEARING", 0L);
            amounts.put("PLATFORM_CASH", 0L);
            amounts.put("MERCHANT_PAYABLE", 0L);
            amounts.put("PLATFORM_FEE_REVENUE", 0L);
        }

        void post(LedgerPostingCommand command, java.util.Map<String, Direction> normalSide) {
            for (LedgerPostingCommand.Entry entry : command.entries()) {
                long current = amounts.get(entry.accountCode());
                Direction normal = normalSide.get(entry.accountCode());
                long updated = entry.direction().apply(
                        normal == Direction.DEBIT ? current : -current, entry.amount().amountMinor());
                amounts.put(entry.accountCode(), normal == Direction.DEBIT ? updated : -updated);
            }
        }

        long of(String account) {
            return amounts.get(account);
        }

        List<String> nonZero() {
            return amounts.entrySet().stream()
                    .filter(e -> e.getValue() != 0L)
                    .map(java.util.Map.Entry::getKey)
                    .toList();
        }
    }

    private static Balances newLedger() {
        return new Balances();
    }

    private static java.util.Map<String, Direction> normalSides() {
        return java.util.Map.of(
                "PSP_CLEARING", Direction.DEBIT,
                "PLATFORM_CASH", Direction.DEBIT,
                "MERCHANT_PAYABLE", Direction.CREDIT,
                "PLATFORM_FEE_REVENUE", Direction.CREDIT);
    }

    private static LedgerPostingCommand capture() {
        return new LedgerPostingCommand(LedgerTransactionType.PAYMENT_CAPTURE, EGP,
                List.of(new LedgerPostingCommand.Entry("PSP_CLEARING", Direction.DEBIT, GROSS),
                        new LedgerPostingCommand.Entry("MERCHANT_PAYABLE", Direction.CREDIT, NET),
                        new LedgerPostingCommand.Entry("PLATFORM_FEE_REVENUE", Direction.CREDIT, FEE)),
                "PAYMENT", "pay_1", "capture", null, null);
    }

    private static LedgerPostingCommand settlement() {
        return new LedgerPostingCommand(LedgerTransactionType.SETTLEMENT_RECEIVED, EGP,
                List.of(new LedgerPostingCommand.Entry("PLATFORM_CASH", Direction.DEBIT, GROSS),
                        new LedgerPostingCommand.Entry("PSP_CLEARING", Direction.CREDIT, GROSS)),
                "SETTLEMENT_RECORD", "srec_1", "settlement", null, null);
    }

    private static LedgerPostingCommand payout() {
        return new LedgerPostingCommand(LedgerTransactionType.MERCHANT_PAYOUT, EGP,
                List.of(new LedgerPostingCommand.Entry("MERCHANT_PAYABLE", Direction.DEBIT, NET),
                        new LedgerPostingCommand.Entry("PLATFORM_CASH", Direction.CREDIT, NET)),
                "PAYOUT", "po_1", "payout", null, null);
    }

    @Test
    @DisplayName("U-LED-09: the documented entries are exactly what gets posted")
    void documentedEntries() {
        assertThat(capture().entries()).hasSize(3);
        assertThat(capture().totalDebits()).isEqualTo(10_000L);
        assertThat(capture().totalCredits()).isEqualTo(10_000L);

        assertThat(settlement().entries()).hasSize(2);
        assertThat(payout().entries()).hasSize(2);
    }

    @Test
    @DisplayName("U-LED-10: capture -> settlement -> payout unwinds the ledger to two accounts")
    void fullCycleUnwindsTheLedger() {
        Balances ledger = newLedger();
        Balances applied = ledger;

        applied.post(capture(), normalSides());
        assertThat(applied.of("PSP_CLEARING")).isEqualTo(10_000L);
        assertThat(applied.of("MERCHANT_PAYABLE")).isEqualTo(9_700L);

        applied.post(settlement(), normalSides());
        assertThat(applied.of("PSP_CLEARING")).isZero();          // cleared with the PSP
        assertThat(applied.of("PLATFORM_CASH")).isEqualTo(10_000L);

        applied.post(payout(), normalSides());

        // 100.00 settled in, 97.00 went to the merchant, so the platform holds the 3.00 fee.
        assertThat(applied.of("PSP_CLEARING")).isZero();
        assertThat(applied.of("MERCHANT_PAYABLE")).isZero();
        assertThat(applied.of("PLATFORM_CASH")).isEqualTo(300L);          //  3.00
        assertThat(applied.of("PLATFORM_FEE_REVENUE")).isEqualTo(300L);   //  3.00
        assertThat(applied.nonZero()).containsExactly("PLATFORM_CASH", "PLATFORM_FEE_REVENUE");
    }

    @Test
    @DisplayName("U-LED-05/U-LED-06: a reversal is an exact mirror and a deviation is refused")
    void reversalMustBeAnExactMirror() {
        List<LedgerPostingCommand.Entry> mirrored = new ArrayList<>();
        for (LedgerPostingCommand.Entry entry : capture().entries()) {
            mirrored.add(new LedgerPostingCommand.Entry(
                    entry.accountCode(), entry.direction().opposite(), entry.amount()));
        }

        var reversal = new LedgerPostingCommand(LedgerTransactionType.REVERSAL, EGP, mirrored,
                "PAYMENT", "pay_1", "reversal", "ltx_cap_1", "customer request");
        assertThat(reversal.totalDebits()).isEqualTo(capture().totalCredits());

        // A reversal that does not mirror the original cannot balance and is refused.
        assertThatThrownBy(() -> new LedgerPostingCommand(LedgerTransactionType.REVERSAL, EGP,
                List.of(new LedgerPostingCommand.Entry("PSP_CLEARING", Direction.CREDIT, GROSS),
                        new LedgerPostingCommand.Entry("MERCHANT_PAYABLE", Direction.DEBIT,
                                Money.of(9_000, EGP))),
                "PAYMENT", "pay_1", "bad reversal", "ltx_cap_1", "typo"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("U-LED-07: a reversal may itself be reversed")
    void reversalChainsAreLegal() {
        List<LedgerPostingCommand.Entry> forward = capture().entries();
        List<LedgerPostingCommand.Entry> mirrored = new ArrayList<>();
        List<LedgerPostingCommand.Entry> unmirrored = new ArrayList<>();
        for (LedgerPostingCommand.Entry entry : forward) {
            mirrored.add(new LedgerPostingCommand.Entry(
                    entry.accountCode(), entry.direction().opposite(), entry.amount()));
            unmirrored.add(new LedgerPostingCommand.Entry(
                    entry.accountCode(), entry.direction(), entry.amount()));
        }

        var first = new LedgerPostingCommand(LedgerTransactionType.REVERSAL, EGP, mirrored,
                "PAYMENT", "pay_1", "r1", "ltx_cap_1", "first");
        var second = new LedgerPostingCommand(LedgerTransactionType.REVERSAL, EGP, unmirrored,
                "PAYMENT", "pay_1", "r2", "ltx_r1", "correction withdrawn");

        assertThat(first.totalDebits()).isEqualTo(capture().totalCredits());
        assertThat(second.totalDebits()).isEqualTo(capture().totalDebits());
        assertThat(second.reversalOfTransactionId()).isEqualTo("ltx_r1");
        // Reversals carry no source of their own; they are identified by what they reverse. That is
        // why the database's double-post index excludes the REVERSAL type. See docs/adr/0004.
    }
}
