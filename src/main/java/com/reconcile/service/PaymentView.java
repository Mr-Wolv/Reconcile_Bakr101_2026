package com.reconcile.service;

import com.reconcile.payment.PaymentState;
import com.reconcile.shared.CurrencyCode;
import com.reconcile.shared.Money;
import java.time.Instant;
import java.util.List;

/**
 * A payment as callers see it.
 *
 * <p>A deliberate projection rather than the database row: the API returns amounts as {@link Money},
 * omits internal columns like {@code version} bookkeeping details it does not use, and never exposes
 * {@code fee_policy_id} as if it were an amount.
 */
public record PaymentView(
        String id,
        String merchantReference,
        String description,
        Money amount,
        Money platformFee,
        Money netAmount,
        String feePolicyId,
        PaymentState state,
        String failureCode,
        String failureReason,
        String provider,
        String providerTransactionId,
        Instant capturedAt,
        Instant settledAt,
        Instant paidOutAt,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    /** One row of a payment's state history. Append-only and never edited. */
    /**
     * One line of a payment's story, drawn from any of the four sources that touch it.
     *
     * <p>The shape is deliberately uniform across kinds. A merged timeline whose rows had a
     * different shape per source would push the "which source is this?" decision onto every
     * reader, which is the problem the endpoint exists to remove.
     *
     * @param kind {@code STATE}, {@code LEDGER}, {@code WEBHOOK} or {@code RECONCILIATION}
     * @param reference the id of the row this line describes, so a reader can go straight to it
     * @param requestId the correlation id, where the underlying source carries one. Nullable and
     *                  honestly so: a ledger posting records the request that caused it, a
     *                  reconciliation result records the request that started its batch, and a
     *                  state-history row records whatever triggered it - which is a request id, a
     *                  provider event id or a settlement record id, and is reported as-is rather
     *                  than relabelled as something it is not
     */
    public record TimelineEntry(
            Instant occurredAt,
            String kind,
            String reference,
            String summary,
            String requestId) {
    }

    /**
     * The gross/fee/net split, re-checked on read.
     *
     * <p>{@code ck_payment_split} already makes this impossible in the database. Re-deriving it here
     * is not belt-and-braces for its own sake: a read model that silently presented an unbalanced
     * split would let a client commit an order against numbers that do not add up, and the operator
     * would only discover it at settlement.
     */
    public boolean splitIsBalanced() {
        return amount.amountMinor() == netAmount.amountMinor() + platformFee.amountMinor()
                && amount.currency() == netAmount.currency()
                && amount.currency() == platformFee.currency();
    }

    /** Convenience for assertions and error messages. */
    public Money amountIn(CurrencyCode currency) {
        if (amount.currency() != currency) {
            throw new IllegalStateException(
                    "payment " + id + " is in " + amount.currency() + ", not " + currency);
        }
        return amount;
    }

    /** A page of payments plus the cursor for the next one, or null when the last page was reached. */
    public record Page(List<PaymentView> items, String nextCursor) {
        public Page {
            items = List.copyOf(items);
        }
    }
}