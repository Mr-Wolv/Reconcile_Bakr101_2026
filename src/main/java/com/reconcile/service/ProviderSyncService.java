package com.reconcile.service;

import com.reconcile.provider.Providers;
import com.reconcile.persistence.jdbc.Sql;
import com.reconcile.provider.RemoteSettlement;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Decision D5: pull settlements for a window, and ingest them exactly as a webhook would.
 *
 * <p>The "exactly as a webhook would" is the requirement, not a convenience. Push and pull must not
 * be two ingestion paths, because the moment they are, the same settlement produces different
 * results depending on which one arrived — and reconciliation, whose entire value is that the same
 * inputs always give the same answer, cannot tolerate that. Both end up here, calling the same
 * {@link ProviderEventProcessor#apply}.
 *
 * <p>Fetch everything first, ingest afterwards. If the provider times out halfway through a page we
 * must not have ingested the part that did arrive: a partial pull would settle some payments and not
 * others for no stated reason, which is the hardest class of bug to find afterwards.
 *
 * <p>Re-running a window is safe. Each pulled settlement is recorded as a provider event under a
 * deterministic id derived from its provider settlement id, so the second run recognises its own
 * work instead of posting a second {@code SETTLEMENT_RECEIVED}.
 */
@Service
public class ProviderSyncService {

    private static final String PROVIDER = Providers.SIMULATED_PSP;

    private final ProviderSettlementClient client;
    private final ProviderEventService events;
    private final ProviderEventProcessor processor;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final Sql sql;
    private final Clock clock;

    public ProviderSyncService(
            ProviderSettlementClient client,
            ProviderEventService events,
            ProviderEventProcessor processor,
            AuditService audit,
            ObjectMapper mapper,
            Sql sql,
            Clock clock) {

        this.client = client;
        this.events = events;
        this.processor = processor;
        this.audit = audit;
        this.mapper = mapper;
        this.sql = sql;
        this.clock = clock;
    }

    /** What one sync attempt did. */
    public record SyncResult(int fetched, int ingested, int alreadyKnown, List<String> errors) {

        public SyncResult {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }

    /**
     * Pulls and ingests every settlement in the window.
     *
     * <p>Provider failures propagate as exceptions — {@code 503} or {@code 502} — rather than being
     * folded into a result with an error string. A caller that sees {@code 200} must be able to rely
     * on the window having been pulled, and an operator reading a failure needs a status code that
     * distinguishes "the provider is down" from "the provider is broken".
     */
    public SyncResult sync(Instant windowStart, Instant windowEnd, String requestId) {
        List<RemoteSettlement> settlements = client.settlements(windowStart, windowEnd);

        int ingested = 0;
        int alreadyKnown = 0;
        java.util.List<String> errors = new java.util.ArrayList<>();

        for (RemoteSettlement settlement : settlements) {
            try {
                if (ingestOne(settlement, requestId)) {
                    ingested++;
                } else {
                    alreadyKnown++;
                }
            } catch (RuntimeException oneFailed) {
                // One unusable settlement must not abandon the rest of the page: the others are
                // real and their arrival is not in doubt. Recorded, reported, and the pull is
                // retried later - where it will be recognised as already done and skip.
                errors.add(settlement.providerSettlementId() + ": " + oneFailed.getMessage());
            }
        }

        audit.record("SERVICE", "provider-sync", "PROVIDER_SYNC_COMPLETED", "PROVIDER", PROVIDER,
                requestId, Map.of(
                        "fetched", settlements.size(),
                        "ingested", ingested,
                        "alreadyKnown", alreadyKnown,
                        "errors", errors.size()));

        return new SyncResult(settlements.size(), ingested, alreadyKnown, errors);
    }

    /**
     * Ingests one settlement, returning whether it was new.
     *
     * <p>The event id is derived from the provider's own settlement id, so a re-pull of the same
     * window is recognised. An id that were random would make every retry a second settlement.
     */
    private boolean ingestOne(RemoteSettlement settlement, String requestId) {
        String payloadJson = mapper.writeValueAsString(
                settlement.asEventPayload(PROVIDER, clock.instant()));
        String eventId = "sync_" + settlement.providerSettlementId();

        boolean first = events.accept(
                PROVIDER,
                eventId,
                "settlement.paid",
                clock.instant(),
                null,
                payloadJson,
                sha256(payloadJson),
                requestId);
        if (!first) {
            return false;
        }

        processor.apply(PROVIDER, eventId, payloadJson, requestId);
        return true;
    }

    /**
     * The same digest a webhook would carry.
     *
     * <p>Computed rather than left null so a pulled settlement is indistinguishable from a pushed
     * one in the event table. An auditor asking "were these two deliveries the same bytes" must not
     * get "unknown" for half the answer.
     */
    private static String sha256(String body) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    /** The window a {@code provider/sync} call covered, for the audit trail. */
    public String describeWindow(Instant start, Instant end) {
        return sql.sql("""
                SELECT COUNT(*) AS total FROM settlement_records
                 WHERE provider = ? AND created_at >= ? AND created_at < ?
                """)
                .params(PROVIDER, start, end)
                .optional((rs, rowNum) -> Integer.toString(rs.getInt("total")))
                .orElse("0");
    }
}