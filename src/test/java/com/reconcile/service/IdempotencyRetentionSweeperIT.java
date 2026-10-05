package com.reconcile.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconcile.persistence.jdbc.Sql;
import com.reconcile.support.SharedPostgres;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Retention: spec 03 §A6 — expired idempotency records are deleted in batches of 1,000, oldest
 * first.
 *
 * <p>Written because the sweeper <b>did not exist</b> while {@code @EnableScheduling} sat on the
 * application class and {@code purgeExpired()} sat uncalled. A test that only exercised the deletion
 * method would have passed in that state and missed the actual defect, which was that nothing ever
 * invoked it — so this asserts both halves: that the method reclaims the right rows, and that a
 * trigger is genuinely registered against it.
 */
@SpringBootTest
class IdempotencyRetentionSweeperIT {


    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedPostgres::jdbcUrl);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        registry.add("reconcile.security.operator-token", () -> "test-operator-token");
        registry.add("reconcile.security.admin-token", () -> "test-admin-token");
    }

    @Autowired
    IdempotencyService idempotency;

    @Autowired
    IdempotencyRetentionSweeper sweeper;

    @Autowired
    ScheduledTaskHolder scheduledTasks;

    @Autowired
    Sql sql;

    @BeforeEach
    void reset() {
        sql.sql("DELETE FROM idempotency_records").update();
    }

    @Test
    @DisplayName("A6: expired records are reclaimed and live ones are left alone")
    void sweepReclaimsOnlyExpiredRecords() {
        seed("idem_stale_old", -3);
        seed("idem_stale_new", -1);
        seed("idem_live", 24);

        int deleted = idempotency.purgeExpired();

        assertThat(deleted).isEqualTo(2);
        assertThat(remainingKeys()).containsExactly("idem_live");
    }

    @Test
    @DisplayName("A6: the sweep is scheduled, not merely implemented")
    void theSweepIsActuallyScheduled() {
        // The defect this class exists for was a correct method with no caller. Invoking
        // purgeExpired() directly would have passed in exactly that state, so the trigger is
        // asserted here: the bean must be wired to a real repeating schedule.
        List<ScheduledTask> tasks = List.copyOf(scheduledTasks.getScheduledTasks());
        String qualified = sweeper.getClass().getName() + ".sweep";

        // Asserted on the schedule's description rather than on the runnable's runtime type: Spring
        // wraps scheduled runnables for observation, so the declared class is not what reaches the
        // scheduler. The description is derived from the target bean and method, which is exactly
        // the fact under test — that this bean's method is wired to a repeating trigger.
        boolean registered = tasks.stream().map(ScheduledTask::toString).anyMatch(
                description -> description.equals(qualified) || description.contains(qualified));

        assertThat(registered)
                .as("a retention window that nothing enforces is a promise written only in the "
                        + "schema; registered tasks were %s", tasks)
                .isTrue();
    }

    @Test
    @DisplayName("A6: sweeping twice is safe and non-destructive")
    void sweepingIsIdempotent() {
        seed("idem_stale", -1);

        sweeper.sweep();
        sweeper.sweep();

        assertThat(remainingKeys()).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Inserts a completed record whose retention window ends {@code expiresInHours} from now.
     *
     * <p>Positive means still replayable; negative means already expired and reclaimable. Written
     * that way rather than as "hours past expiry" so that a caller reading {@code seed(id, 24)} gets
     * a record that is unambiguously <i>live</i> — the earlier phrasing silently produced an
     * expired record for the case meant to prove live ones survive.
     */
    private void seed(String id, int expiresInHours) {
        sql.sql("""
                INSERT INTO idempotency_records
                    (id, endpoint, idem_key, fingerprint, status, response_status, response_body,
                     resource_type, resource_id, request_id, created_at, completed_at, expires_at)
                VALUES (?, 'POST /api/v1/payments', ?, repeat('a', 64), 'COMPLETED', 201,
                        '{"ok":true}'::jsonb, 'PAYMENT', 'pay_probe', 'req_probe',
                        now() + (? || ' hours')::interval,
                        now() + (? || ' hours')::interval,
                        now() + (? || ' hours')::interval)
                """)
                .params(id, id, expiresInHours, expiresInHours, expiresInHours)
                .update();
    }

    private List<String> remainingKeys() {
        return sql.sql("SELECT idem_key FROM idempotency_records ORDER BY idem_key")
                .list((rs, rowNum) -> rs.getString("idem_key"));
    }
}
