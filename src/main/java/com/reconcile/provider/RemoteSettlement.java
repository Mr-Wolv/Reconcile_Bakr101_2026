package com.reconcile.provider;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A settlement record as the provider reports it, before any of it is trusted.
 *
 * <p>A plain record with no JSON annotations. The wire shape is mapped by the client that fetches
 * it, which is where the distinction between "the provider sent us rubbish" ({@code 502}) and "our
 * ingestion broke" ({@code 500}) has to be drawn — bound straight onto {@link ProviderEventPayload}
 * here, the two would arrive as the same exception.
 */
public record RemoteSettlement(
        String providerSettlementId,
        LocalDate settlementDate,
        String currency,
        long grossAmountMinor,
        long feeAmountMinor,
        long netAmountMinor,
        List<RemoteLine> lines) {

    public RemoteSettlement {
        Objects.requireNonNull(providerSettlementId, "providerSettlementId");
        Objects.requireNonNull(settlementDate, "settlementDate");
        Objects.requireNonNull(currency, "currency");
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** One transaction inside a provider settlement. */
    public record RemoteLine(String providerTransactionId, long grossAmountMinor, long netAmountMinor) {
        public RemoteLine {
            Objects.requireNonNull(providerTransactionId, "providerTransactionId");
        }
    }

    /** The same report as the event body, so push and pull share one ingestion path. */
    public ProviderEventPayload asEventPayload(String provider, java.time.Instant occurredAt) {
        List<ProviderEventPayload.Line> payloadLines = new ArrayList<>(lines.size());
        for (RemoteLine line : lines) {
            payloadLines.add(new ProviderEventPayload.Line(
                    line.providerTransactionId(), line.grossAmountMinor(), line.netAmountMinor()));
        }
        return new ProviderEventPayload(
                "settlement.paid",
                occurredAt,
                null,
                null,
                null,
                null,
                null,
                null,
                new ProviderEventPayload.Settlement(
                        providerSettlementId,
                        settlementDate,
                        currency,
                        grossAmountMinor,
                        feeAmountMinor,
                        netAmountMinor,
                        payloadLines));
    }
}