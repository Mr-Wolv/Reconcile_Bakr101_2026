package com.reconcile.service;

import com.reconcile.config.ReconcileProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reclaims idempotency records past their retention window — the sweeper {@code docs/spec/03 §A6}
 * specifies.
 *
 * <p>It exists because {@code @EnableScheduling} was on the application class with nothing
 * scheduled behind it: {@link IdempotencyService#purgeExpired()} was written, correct, and called
 * by no one. The retention promise was therefore true in the schema and false in practice — rows
 * accumulated forever from the table that every mutating endpoint writes to.
 *
 * <p>{@code fixedDelay} rather than {@code fixedRate}, so a slow sweep cannot queue overlapping
 * runs, and the same value as the initial delay so startup does not race the first batch of real
 * traffic. Deletion is bounded to {@link IdempotencyService#PURGE_BATCH} rows per statement and
 * drains batches, so a burst of expiring keys is spread over several transactions instead of one
 * long-held lock on {@code ix_idem_expiry}.
 *
 * <p>Nothing here is load-bearing for correctness. A key whose record has been swept is treated as
 * new and will execute again — the trade-off documented in §A6, and the behaviour Stripe documents.
 * The sweeper controls how long the table grows, not what any request is allowed to do.
 *
 * <p>The interval is bound from {@code reconcile.idempotency.sweep-interval} rather than read off
 * {@link ReconcileProperties}, because a {@code @Scheduled} SpEL bean reference would have to guess
 * the configuration-properties bean name. The placeholder's default and
 * {@code ReconcileProperties.Idempotency.sweepInterval}'s default are both one hour, so an operator
 * who sets it in one place and not the other still gets the same behaviour.
 */
@Component
public class IdempotencyRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyRetentionSweeper.class);

    private final IdempotencyService idempotency;

    public IdempotencyRetentionSweeper(
            IdempotencyService idempotency, ReconcileProperties properties) {

        this.idempotency = idempotency;
        log.info("idempotency responses are replayable for {}; sweeping every {}",
                properties.idempotency().retention(),
                properties.idempotency().sweepInterval());
    }

    @Scheduled(
            fixedDelayString = "${reconcile.idempotency.sweep-interval:1h}",
            initialDelayString = "${reconcile.idempotency.sweep-interval:1h}")
    public void sweep() {
        int deleted;
        try {
            deleted = idempotency.purgeExpired();
        } catch (RuntimeException e) {
            // A sweeper that kills its own scheduler on a transient database blip is worse than one
            // that misses a cycle: the failure would then be silent, and a silently dead sweeper is
            // the original problem restated.
            log.error("idempotency retention sweep failed; the next tick will retry", e);
            return;
        }
        if (deleted > 0) {
            log.info("swept {} expired idempotency record(s)", deleted);
        }
    }
}