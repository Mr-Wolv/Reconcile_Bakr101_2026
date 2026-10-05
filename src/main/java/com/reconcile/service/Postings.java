package com.reconcile.service;

import com.reconcile.ledger.Direction;
import com.reconcile.ledger.LedgerPostingCommand;
import com.reconcile.ledger.LedgerTransactionType;
import com.reconcile.shared.CurrencyCode;
import com.reconcile.shared.Money;
import java.util.List;

/**
 * The ledger postings this system knows how to make, in one place.
 *
 * <p>The shapes come from {@code docs/spec/01-money-and-ledger.md §3}. They live here rather than
 * inline in each service for a specific reason: if the capture posting is written in two places,
 * the two copies drift, and the drift shows up as a reconciliation mismatch weeks later with no way
 * to tell which one was wrong. One definition, referenced by name, is the only version that can be
 * reviewed against the specification.
 *
 * <p>Every method returns a balanced {@link LedgerPostingCommand}; the constructor enforces that,
 * so an unbalanced posting cannot be built here at all.
 */
public final class Postings {

    private Postings() {
    }

    /**
     * {@code PAYMENT_CAPTURE} — money taken from a customer, split between merchant and platform.
     *
     * <pre>
     *   Dr PSP_CLEARING        gross
     *   Cr MERCHANT_PAYABLE     net
     *   Cr PLATFORM_FEE_REVENUE fee
     * </pre>
     */
    public static LedgerPostingCommand capture(
            String paymentId, Money gross, Money fee, Money net, String actor) {

        return new LedgerPostingCommand(
                LedgerTransactionType.PAYMENT_CAPTURE,
                gross.currency(),
                List.of(
                        entry("PSP_CLEARING", Direction.DEBIT, gross),
                        entry("MERCHANT_PAYABLE", Direction.CREDIT, net),
                        entry("PLATFORM_FEE_REVENUE", Direction.CREDIT, fee)),
                "PAYMENT",
                paymentId,
                "Capture of payment " + paymentId,
                null,
                null);
    }

    /**
     * {@code SETTLEMENT_RECEIVED} — the PSP has paid us, so clearing becomes cash.
     *
     * <pre>
     *   Dr PLATFORM_CASH   gross
     *   Cr PSP_CLEARING    gross
     * </pre>
     *
     * <p>Both legs are the gross amount. The platform fee is not a cash movement between these two
     * accounts — it is already recognised as revenue at capture — which is why {@code PSP_FEE_EXPENSE}
     * is never posted in v1.
     */
    public static LedgerPostingCommand settlementReceived(
            String settlementRecordId, Money gross, String actor) {

        return new LedgerPostingCommand(
                LedgerTransactionType.SETTLEMENT_RECEIVED,
                gross.currency(),
                List.of(
                        entry("PLATFORM_CASH", Direction.DEBIT, gross),
                        entry("PSP_CLEARING", Direction.CREDIT, gross)),
                "SETTLEMENT_RECORD",
                settlementRecordId,
                "Settlement received from the PSP",
                null,
                null);
    }

    /**
     * {@code MERCHANT_PAYOUT} — paying a merchant the net of a set of settled payments.
     *
     * <pre>
     *   Dr MERCHANT_PAYABLE   net
     *   Cr PLATFORM_CASH      net
     * </pre>
     *
     * <p>The platform keeps the fee, so cash falls by the net and never by the gross. That is why a
     * completed capture → settlement → payout cycle leaves {@code PLATFORM_CASH} holding exactly the
     * platform's revenue.
     */
    public static LedgerPostingCommand merchantPayout(
            String payoutRecordId, Money net, String merchantReference) {

        return new LedgerPostingCommand(
                LedgerTransactionType.MERCHANT_PAYOUT,
                net.currency(),
                List.of(
                        entry("MERCHANT_PAYABLE", Direction.DEBIT, net),
                        entry("PLATFORM_CASH", Direction.CREDIT, net)),
                "PAYOUT",
                payoutRecordId,
                "Payout to merchant " + merchantReference,
                null,
                null);
    }

    /**
     * {@code PAYMENT_REFUND_SETTLED} — refunding after the money has reached our bank account.
     *
     * <pre>
     *   Dr MERCHANT_PAYABLE        net
     *   Dr PLATFORM_FEE_REVENUE   fee
     *   Cr PLATFORM_CASH          gross
     * </pre>
     *
     * <p>The contra account is cash, not clearing, precisely because settlement already moved the
     * money to {@code PLATFORM_CASH}. That difference is the whole reason this is a distinct
     * transaction type and not a reversal of the capture.
     */
    public static LedgerPostingCommand refundAfterSettlement(
            String paymentId, Money gross, Money fee, Money net, String actor) {

        return new LedgerPostingCommand(
                LedgerTransactionType.PAYMENT_REFUND_SETTLED,
                gross.currency(),
                List.of(
                        entry("MERCHANT_PAYABLE", Direction.DEBIT, net),
                        entry("PLATFORM_FEE_REVENUE", Direction.DEBIT, fee),
                        entry("PLATFORM_CASH", Direction.CREDIT, gross)),
                "PAYMENT",
                paymentId,
                "Refund of settled payment " + paymentId,
                null,
                null);
    }

    /**
     * {@code PSP_ADJUSTMENT} — an operator correction attached to a reconciliation case.
     *
     * <p>Restricted to the two clearing accounts. An adjustment that could touch revenue or a
     * merchant payable would be a second, undocumented way to move money, and every such path is a
     * way for the ledger to drift from its own explanation.
     */
    public static LedgerPostingCommand pspAdjustment(
            String caseId, Money amount, Direction direction, CurrencyCode currency) {

        if (direction != Direction.DEBIT && direction != Direction.CREDIT) {
            throw new IllegalArgumentException("adjustment direction must be DEBIT or CREDIT");
        }
        Direction cashLeg = direction.opposite();
        return new LedgerPostingCommand(
                LedgerTransactionType.PSP_ADJUSTMENT,
                currency,
                List.of(
                        entry("PSP_CLEARING", direction, amount),
                        entry("PLATFORM_CASH", cashLeg, amount)),
                "RECONCILIATION_CASE",
                caseId,
                "PSP adjustment for reconciliation case " + caseId,
                null,
                null);
    }

    private static LedgerPostingCommand.Entry entry(
            String accountCode, Direction direction, Money amount) {
        return new LedgerPostingCommand.Entry(accountCode, direction, amount);
    }
}