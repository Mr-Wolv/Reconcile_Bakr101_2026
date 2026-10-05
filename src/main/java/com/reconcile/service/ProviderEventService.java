package com.reconcile.service;

import com.reconcile.shared.Ulid;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.reconcile.persistence.jdbc.Sql;

/**
 * The durable inbox: {@code provider_event_deliveries} and {@code provider_events}.
 *
 * <p>Two tables because deduplication and auditability need different shapes (decision D4).
 * Deduplication needs a uniqueness constraint that <i>rejects</i> the second copy; auditability
 * needs that second copy <i>recorded</i>. One table cannot do both, and the scenario "three
 * deliveries, one effect" is only demonstrable when both exist.
 *
 * <p>Every method here is {@link Propagation#REQUIRES_NEW}. A delivery row is the record of an HTTP
 * attempt that already happened — if it rolled back with whatever the caller was doing, the record
 * of the attempt would disappear along with it, which is exactly the case worth keeping.
 */
@Service
public class ProviderEventService {

    /** Mirrors the {@code outcome} CHECK constraint, so the vocabulary cannot drift from the schema. */
    public enum DeliveryOutcome {
        ACCEPTED,
        DUPLICATE,
        REJECTED_SIGNATURE,
        REJECTED_TIMESTAMP,
        REJECTED_MALFORMED,
        TOO_LARGE
    }

    /** One HTTP attempt, as recorded. */
    public record Delivery(
            String provider,
            String eventId,
            String eventType,
            DeliveryOutcome outcome,
            int httpStatus,
            boolean signatureValid,
            String reason,
            String requestId) {
    }

    private final Sql sql;
    private final AuditService audit;
    private final Clock clock;

    public ProviderEventService(Sql sql, AuditService audit, Clock clock) {
        this.sql = sql;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Records a delivery we refused.
     *
     * <p>Refusals are recorded too. A provider that suddenly gets 401s needs to be able to answer
     * "did you receive my events?", and a log line that rotation will eventually eat is not an
     * answer.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRejection(Delivery delivery) {
        insertDelivery(delivery);
    }

    /**
     * Persists an accepted delivery and its canonical event in one transaction, deduplicating on
     * {@code (provider, event_id)}.
     *
     * @return {@code true} when this was the first delivery of the event, {@code false} when it is a
     *         duplicate — the caller answers {@code 202} and {@code 200} respectively, and the
     *         difference is a promise to the provider about whether to retry
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean accept(
            String provider,
            String eventId,
            String eventType,
            Instant occurredAt,
            String occurredOffset,
            String payloadJson,
            String payloadHash,
            String requestId) {

        boolean firstDelivery = acceptOnce(provider, eventId, eventType, occurredAt, occurredOffset,
                payloadJson, payloadHash, requestId);

        if (firstDelivery) {
            insertDelivery(new Delivery(provider, eventId, eventType, DeliveryOutcome.ACCEPTED,
                    202, true, null, requestId));
            audit.record("WEBHOOK", provider, "WEBHOOK_ACCEPTED", "PROVIDER_EVENT", eventId,
                    requestId, java.util.Map.of("eventType", eventType));
        } else {
            // The duplicate count is the whole point of the second table: "delivered three times"
            // has to be visible as three, not as one.
            sql.sql("""
                    UPDATE provider_events SET duplicate_deliveries = duplicate_deliveries + 1
                     WHERE provider = ? AND event_id = ?
                    """)
                    .params(provider, eventId)
                    .update();
            insertDelivery(new Delivery(provider, eventId, eventType, DeliveryOutcome.DUPLICATE,
                    200, true, "already received", requestId));
            audit.record("WEBHOOK", provider, "WEBHOOK_DUPLICATE", "PROVIDER_EVENT", eventId,
                    requestId, java.util.Map.of("eventType", eventType));
        }
        return firstDelivery;
    }

    /**
     * The deduplicating insert.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than catching a unique violation: the blocking
     * behaviour of the index is what makes two simultaneous deliveries of one event safe — the
     * second waits for the first to commit and then inserts nothing. A caught exception would
     * behave the same here, but the row count is the answer to "was this the first delivery?",
     * which is the question the caller actually asked.
     */
    private boolean acceptOnce(
            String provider,
            String eventId,
            String eventType,
            Instant occurredAt,
            String occurredOffset,
            String payloadJson,
            String payloadHash,
            String requestId) {

        return sql.sql("""
                INSERT INTO provider_events
                    (id, provider, event_id, event_type, provider_occurred_at,
                     provider_occurred_offset, payload, payload_hash, status, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, 'PENDING', ?)
                ON CONFLICT (provider, event_id) DO NOTHING
                """)
                .params(Ulid.of("pev"), provider, eventId, eventType, occurredAt, occurredOffset,
                        payloadJson, payloadHash, clock.instant())
                .update() == 1;
    }

    private void insertDelivery(Delivery delivery) {
        sql.sql("""
                INSERT INTO provider_event_deliveries
                    (id, provider, event_id, event_type, outcome, http_status, signature_valid,
                     reason, request_id, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(Ulid.of("ped"), delivery.provider(), delivery.eventId(), delivery.eventType(),
                        delivery.outcome().name(), delivery.httpStatus(), delivery.signatureValid(),
                        truncate(delivery.reason()), delivery.requestId(), clock.instant())
                .update();
    }

    /**
     * Events that have exhausted their attempts.
     *
     * <p>An event that failed permanently has no business effect and opens no case, so nothing else
     * in the system would ever mention it again. This is the only place it stays visible.
     */
    public java.util.List<FailedEvent> failed(int limit) {
        // Both terminal states, and the distinction between them is the interesting part rather
        // than an implementation detail: FAILED means the retry budget ran out and the world might
        // still change; DEAD means retrying cannot change the answer and never will. An operator
        // triaging this list needs to tell those apart, which is why `status` is in the row and why
        // this query is not simply "everything that is not PENDING".
        return sql.sql("""
                SELECT event_id, event_type, attempts, last_error, status
                  FROM provider_events
                 WHERE status IN ('FAILED', 'DEAD')
                 ORDER BY next_attempt_at, id
                 LIMIT ?
                """)
                .param(limit)
                .list((rs, rowNum) -> new FailedEvent(
                        rs.getString("event_id"),
                        rs.getString("event_type"),
                        rs.getInt("attempts"),
                        rs.getString("last_error"),
                        rs.getString("status")));
    }

    /** A permanently failed event, as the operations endpoint reports it. */
    public record FailedEvent(
            String eventId, String eventType, int attempts, String lastError, String status) {
    }

    /** {@code reason} is {@code varchar(280)}; a longer diagnostic must not lose the whole row. */
    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 280 ? reason : reason.substring(0, 277) + "...";
    }
}