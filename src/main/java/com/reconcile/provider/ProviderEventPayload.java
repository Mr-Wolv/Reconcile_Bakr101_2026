package com.reconcile.provider;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The body of a provider webhook, parsed.
 *
 * <p>One shape for every event type rather than a polymorphic hierarchy, because the payload is
 * stored verbatim in {@code provider_events.payload} and processed <i>asynchronously</i> — by which
 * time the original bytes are a {@code jsonb} column, not a request. A per-type hierarchy would have
 * to be reconstructed from that column anyway, and one flat record with nullable fields keeps the
 * stored document and the parsed document trivially convertible.
 *
 * <p>The settlement fields are the reason for {@code settlement}: a {@code settlement.paid} event
 * carries a whole settlement record with its lines, and that shape is what makes partial settlement
 * representable (see {@code docs/spec/04-reconciliation.md §2}).
 *
 * <p>Parsed <b>after</b> signature verification, never before. The signature covers the raw bytes
 * ({@code docs/spec/03 §B1}); parsing first would mean verifying a re-serialisation, which is broken
 * by construction.
 */
public record ProviderEventPayload(
        String type,
        Instant occurredAt,
        String providerTransactionId,
        String merchantReference,
        Amount gross,
        Amount fee,
        Amount net,
        String status,
        Settlement settlement) {

    public ProviderEventPayload {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    /** Minor units plus currency. Never a decimal, for the same reason as everywhere else. */
    public record Amount(long amountMinor, String currency) {
        public Amount {
            Objects.requireNonNull(currency, "currency");
        }
    }

    /**
     * A settlement covering many provider transactions.
     *
     * <p>The record totals and the per-line totals are both carried, and both are checked. A
     * provider whose totals disagree with its own lines is reporting a discrepancy that
     * reconciliation must surface rather than silently normalise away.
     */
    public record Settlement(
            String providerSettlementId,
            LocalDate settlementDate,
            String currency,
            long grossAmountMinor,
            long feeAmountMinor,
            long netAmountMinor,
            List<Line> lines) {

        public Settlement {
            Objects.requireNonNull(providerSettlementId, "providerSettlementId");
            Objects.requireNonNull(settlementDate, "settlementDate");
            Objects.requireNonNull(currency, "currency");
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** One transaction inside a settlement record. */
    public record Line(String providerTransactionId, long grossAmountMinor, long netAmountMinor) {
        public Line {
            Objects.requireNonNull(providerTransactionId, "providerTransactionId");
        }
    }
}