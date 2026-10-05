package com.reconcile.service;

import com.reconcile.persistence.jdbc.Sql;
import com.reconcile.shared.Ulid;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains {@code provider_events}: claims a batch, applies each effect, records the outcome.
 *
 * <p>Claiming is {@code FOR UPDATE SKIP LOCKED} (spec 03 §B4). That is what makes a second worker —
 * or a second instance — safe without a lock: it takes the rows nobody else holds and moves on, so
 * an event is never claimed twice and a slow event never blocks the queue behind it.
 *
 * <p>Retries use exponential backoff with <b>full jitter</b>. Jitter is not decoration: without it,
 * every event that failed at the same moment retries at the same moment, and a provider's outage
 * turns into a synchronised thundering herd the instant it recovers.
 */
@Component
public class InboundEventWorker {

    private static final Logger log = LoggerFactory.getLogger(InboundEventWorker.class);

    /** Spec 03 §B4: base 2s, factor 2, cap 5 minutes, full jitter, 8 attempts. */
    static final Duration BACKOFF_BASE = Duration.ofSeconds(2);
    static final Duration BACKOFF_CAP = Duration.ofMinutes(5);
    static final int MAX_ATTEMPTS = 8;
    static final int DEFAULT_BATCH = 50;

    /**
     * A claim older than this is treated as abandoned by a crashed worker.
     *
     * <p>Five minutes, as specified. It has to exceed the time a legitimate worker may hold a claim —
     * including waiting on a lock behind a slow peer — or a healthy worker would have its work stolen
     * mid-flight and processed twice.
     */
    static final Duration STALE_AFTER = Duration.ofMinutes(5);

    /**
     * How many times an event may be found not-yet-applicable before it is called dead.
     *
     * <p>Separated from {@link #MAX_ATTEMPTS} on purpose. Attempts measure "the world is failing
     * this event"; deferrals measure "the world is not ready yet". They have different meanings,
     * different budgets, and — defect 22 — different consequences for sharing one: a settlement
     * that merely overtook its own capture used to spend its whole retry budget on being early,
     * and die holding a settlement the provider had really paid.
     *
     * <p>The budget exists so deferral is not a way of never failing. An event that stays blocked
     * for this many passes is an operator's problem, and it ends on
     * {@code GET /provider/events/failed} like any other dead event.
     */
    static final int MAX_DEFERRALS = 100;

    private final Sql sql;
    private final ProviderEventService events;
    private final ProviderEventProcessor processor;
    private final Clock clock;
    private final String workerId;
    private final Random jitter;

    public InboundEventWorker(
            Sql sql,
            ProviderEventService events,
            ProviderEventProcessor processor,
            Clock clock) {

        this.sql = sql;
        this.events = events;
        this.processor = processor;
        this.clock = clock;
        this.workerId = "wkr_" + Ulid.next();
        // Seeded from the clock rather than fixed: every worker must jitter differently, and two
        // workers sharing a seed would reintroduce exactly the synchronisation jitter prevents.
        this.jitter = new Random(clock.millis() ^ workerId.hashCode());
    }

    @Scheduled(fixedDelayString = "${reconcile.provider.event-poll-interval:2s}",
            initialDelayString = "${reconcile.provider.event-poll-interval:2s}")
    public void poll() {
        reclaimStaleClaims();
        int claimed = processBatch(DEFAULT_BATCH);
        if (claimed > 0) {
            log.info("processed {} provider event(s) as {}", claimed, workerId);
        }
    }

    /**
     * Claims and processes one batch.
     *
     * @return how many events were processed, for the integration tests to assert on
     */
    public int processBatch(int limit) {
        List<Claimed> claimed = claim(limit);
        for (Claimed event : claimed) {
            applyOne(event);
        }
        return claimed.size();
    }

    /**
     * Returns abandoned {@code PROCESSING} rows to the queue.
     *
     * <p>Crash recovery: a worker killed between claiming and applying leaves a row locked and
     * {@code PROCESSING} forever unless somebody notices. Nothing else would notice — the event has
     * no effect and no case — so this is the only thing that makes the queue self-healing.
     */
    public int reclaimStaleClaims() {
        int reclaimed = sql.sql("""
                UPDATE provider_events
                   SET status = 'PENDING', locked_at = NULL, locked_by = NULL,
                       next_attempt_at = now()
                 WHERE status = 'PROCESSING'
                   AND locked_at < ?
                """)
                .param(clock.instant().minus(STALE_AFTER))
                .update();
        if (reclaimed > 0) {
            log.warn("reclaimed {} provider event(s) abandoned mid-flight", reclaimed);
        }
        return reclaimed;
    }

    /**
     * Puts an event back because the world was not ready for it, not because it failed.
     *
     * <p>No backoff, and no attempt spent. The reason is in the word "blocked": the missing
     * precondition is usually already in this queue, one pass away. A backoff here would delay the
     * event by seconds for no reason, and — worse — would let a burst of reorderings spend the retry
     * budget that exists for genuine faults.
     *
     * <p>{@code attempts = attempts - 1} is what makes "no attempt spent" true. The claim query
     * increments it before the handler runs, so without handing the increment back a deferred event
     * would still burn one retry per deferral — which is how a settlement that overtook its own
     * capture ended up dead at {@code attempts = 8} while every one of those eight "attempts" had
     * been a perfectly healthy early arrival.
     *
     * <p>{@code next_attempt_at = now()} puts it at the back of the next claim rather than the
     * front, so the rest of the queue drains first and the precondition has a chance to land.
     */
    private void defer(Claimed event, String reason) {
        int deferrals = event.deferredAttempts() + 1;
        if (deferrals >= MAX_DEFERRALS) {
            // Terminal, but terminal for a different reason than a broken event: this one was fine
            // and the world never became ready. It still must not be claimed again, so it still
            // goes to DEAD rather than FAILED.
            fail(event, "still blocked after " + deferrals + " deferrals: " + reason, null);
            return;
        }
        sql.sql("""
                UPDATE provider_events
                   SET status = 'PENDING', deferred_attempts = ?, next_attempt_at = now(),
                       attempts = greatest(attempts - 1, 0),
                       locked_at = NULL, locked_by = NULL, last_error = ?
                 WHERE id = ?
                """)
                .params(deferrals, reason.length() <= 500 ? reason : reason.substring(0, 497) + "...",
                        event.id())
                .update();

        log.info("deferred provider event {} ({} of {}): {}",
                event.eventId(), deferrals, MAX_DEFERRALS, reason);
    }

    /** The claim query from §B4, verbatim in shape: SKIP LOCKED, bounded, oldest first. */
    List<Claimed> claim(int limit) {
        return sql.sql("""
                WITH claimed AS (
                    SELECT id FROM provider_events
                     WHERE status IN ('PENDING','FAILED')
                       AND next_attempt_at <= now()
                       AND attempts < ?
                       AND deferred_attempts < ?
                     ORDER BY next_attempt_at
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                )
                UPDATE provider_events e
                   SET status = 'PROCESSING', locked_at = now(), locked_by = ?,
                       attempts = e.attempts + 1
                  FROM claimed WHERE e.id = claimed.id
                RETURNING e.id, e.provider, e.event_id, e.event_type, e.payload::text AS payload,
                          e.attempts, e.deferred_attempts, e.last_error
                """)
                .params(MAX_ATTEMPTS, MAX_DEFERRALS, limit, workerId)
                .list((rs, rowNum) -> new Claimed(
                        rs.getString("id"),
                        rs.getString("provider"),
                        rs.getString("event_id"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getInt("attempts"),
                        rs.getInt("deferred_attempts"),
                        rs.getString("last_error")));
    }

    private void applyOne(Claimed event) {
        try {
            ProviderEventProcessor.Effect effect = processor.apply(
                    event.provider(), event.eventId(), event.payload(), event.requestId());

            sql.sql("""
                    UPDATE provider_events
                       SET status = ?, processed_at = ?, locked_at = NULL, locked_by = NULL,
                           last_error = NULL
                     WHERE id = ?
                    """)
                    .params(effect.name(), clock.instant(), event.id())
                    .update();

        } catch (ProviderEventProcessor.NonRetryableEventException permanent) {
            fail(event, permanent.getMessage(), null);

        } catch (ProviderEventProcessor.BlockedEventException blocked) {
            defer(event, blocked.getMessage());

        } catch (RuntimeException transientFailure) {
            Duration backoff = backoffFor(event.attempts());
            fail(event, transientFailure.getClass().getSimpleName() + ": "
                    + transientFailure.getMessage(), backoff);
        }
    }

    /**
     * Records a failure, choosing between "try again later", "we've run out of tries", and "never
     * again".
     *
     * <p>The distinction is the same one idempotency makes: a transient fault must not become a
     * permanent answer, and a permanent one must not consume eight attempts and five minutes of
     * backoff to reach the conclusion it already had.
     *
     * <p>Three states rather than two, and the third is the one that was missing (F-07). DEAD means
     * retrying cannot change the answer: an unknown event type, a payload that no longer parses, a
     * settlement whose own totals contradict its lines. Such an event used to be written FAILED with
     * {@code next_attempt_at = now()}, which the claim query — {@code status IN ('PENDING','FAILED')}
     * — immediately picked up again. A permanently broken event therefore spent the full eight
     * attempts and five minutes of backoff to arrive at the same answer, and pushed a genuinely
     * fixable backlog behind it while it did. DEAD is outside the claim query entirely and still
     * appears on {@code GET /provider/events/failed}, so nothing is lost — it just stops being
     * retried.
     */
    private void fail(Claimed event, String reason, Duration retryIn) {
        boolean exhausted = event.attempts() >= MAX_ATTEMPTS;
        String status;
        if (retryIn == null) {
            status = "DEAD";
        } else if (exhausted) {
            status = "FAILED";
        } else {
            status = "PENDING";
        }
        Instant nextAttempt = clock.instant().plus(retryIn == null ? Duration.ZERO : retryIn);

        sql.sql("""
                UPDATE provider_events
                   SET status = ?, last_error = ?, next_attempt_at = ?,
                       locked_at = NULL, locked_by = NULL
                 WHERE id = ?
                """)
                .params(status, reason.length() <= 500 ? reason : reason.substring(0, 497) + "...",
                        nextAttempt, event.id())
                .update();

        if ("DEAD".equals(status)) {
            log.error("provider event {} is permanently unusable and will not be retried: {}",
                    event.eventId(), reason);
        } else if ("FAILED".equals(status)) {
            log.error("provider event {} failed permanently after {} attempts: {}",
                    event.eventId(), event.attempts(), reason);
        } else {
            log.warn("provider event {} failed (attempt {}), retrying in {}: {}",
                    event.eventId(), event.attempts(), retryIn, reason);
        }
    }

    /**
     * Exponential backoff with full jitter: {@code random(0, min(cap, base × 2^attempt))}.
     *
     * <p>Full jitter rather than a fixed delay because a synchronised retry storm is worse than a
     * late retry — it re-applies the load that caused the failure to a system that has just come
     * back.
     */
    Duration backoffFor(int attempt) {
        long exponentialMillis;
        try {
            exponentialMillis = Math.multiplyExact(
                    BACKOFF_BASE.toMillis(), 1L << Math.min(attempt, 20));
        } catch (ArithmeticException overflow) {
            exponentialMillis = BACKOFF_CAP.toMillis();
        }
        long capped = Math.min(exponentialMillis, BACKOFF_CAP.toMillis());
        return Duration.ofMillis(jitter.nextLong(capped + 1));
    }

    /** A claimed event, as returned by the claim query. */
    record Claimed(
            String id,
            String provider,
            String eventId,
            String eventType,
            String payload,
            int attempts,
            int deferredAttempts,
            String lastError) {

        String requestId() {
            return "req_event_" + eventId;
        }
    }
}