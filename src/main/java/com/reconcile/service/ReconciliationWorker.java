package com.reconcile.service;

import com.reconcile.persistence.jdbc.Sql;
import com.reconcile.shared.Ulid;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs reconciliation batches, and resumes them after a crash.
 *
 * <p>Resume is the whole point of the subject snapshot plus the results guard: a worker killed at
 * subject 500 of 1000 restarts, finds 500 results already present, and processes only the remaining
 * 500. Nothing is recomputed and nothing is duplicated, because the one thing that must never happen
 * in a reconciliation engine is producing two answers to the same question (spec 04 §7).
 */
@Component
public class ReconciliationWorker {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationWorker.class);

    /** Batches one scheduler tick will pick up. Bounds the work a single tick can start. */
    static final int MAX_BATCHES_PER_TICK = 5;

    /** Ceiling on a stored failure reason, matching the column. */
    private static final int MAX_REASON_LENGTH = 500;

    private final Sql sql;
    private final ReconciliationService reconciliation;
    private final Clock clock;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;

    public ReconciliationWorker(
            Sql sql,
            ReconciliationService reconciliation,
            Clock clock,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.sql = sql;
        this.reconciliation = reconciliation;
        this.clock = clock;
        this.transactionManager = transactionManager;
    }

    /**
     * Picks up batches that were created asynchronously and runs them.
     *
     * <p>Without this, {@code POST /api/v1/reconciliation/batches} with its documented default
     * ({@code async: true}) returned {@code 202} and nothing else ever happened: the batch sat in
     * {@code RUNNING} with zero results, forever, until the lease expired and
     * {@code /api/v1/health} started answering {@code 503} — which in any real deployment is a
     * readiness failure that takes the whole service out of rotation. The endpoint's primary
     * documented usage did nothing at all.
     *
     * <p>Only {@code RUNNING} batches are claimed, so a batch marked {@code FAILED} is never
     * resurrected. Running one here is safe even if an operator triggers the same window inline at
     * the same moment: {@code processSubject} inserts results {@code ON CONFLICT DO NOTHING} and
     * case creation is conflict-tolerant, so two workers produce one answer rather than two.
     */
    @Scheduled(fixedDelayString = "${reconcile.reconciliation.poll-interval:2s}",
            initialDelayString = "${reconcile.reconciliation.poll-interval:2s}")
    public void poll() {
        for (String batchId : runningBatchIds()) {
            try {
                runBatch(batchId);
            } catch (RuntimeException failure) {
                // runBatch has already marked the batch FAILED and recorded why. This catch exists
                // so one bad batch cannot stop the scheduler from draining the rest of the queue:
                // an exception escaping a @Scheduled method cancels that task for the life of the
                // JVM, which would turn one bad batch into a permanently dead worker.
                log.error("reconciliation batch {} could not be completed; it has been marked "
                        + "FAILED and will not be retried", batchId, failure);
            }
        }
    }

    /** Batches that were created but never run, oldest first. */
    List<String> runningBatchIds() {
        return sql.sql("""
                SELECT id FROM reconciliation_batches
                 WHERE status = 'RUNNING'
                 ORDER BY started_at, id
                 LIMIT ?
                """)
                .param(MAX_BATCHES_PER_TICK)
                .list((rs, rowNum) -> rs.getString("id"));
    }

    /**
     * Processes every unprocessed subject of a running batch, then completes it.
     *
     * <p>A failure on any subject marks the batch {@code FAILED} and rethrows, rather than being
     * swallowed. Previously an exception escaped with nothing recorded: the batch stayed
     * {@code RUNNING} with a partial result set, {@code failure_reason} empty, and was
     * indistinguishable from a slow batch — so the one place in the system that most needed a
     * failure boundary did not have one.
     *
     * <p>Failing the whole batch rather than skipping the bad subject is deliberate. A subject that
     * cannot be evaluated means either a defect or a state the engine does not understand, and
     * continuing would produce a batch reporting {@code COMPLETED} while having silently dropped
     * questions it was asked. An operator must be told which ones are missing.
     *
     * @return the number of subjects processed in this attempt, including those skipped as already
     *         done — so a resumed batch reports progress rather than appearing to start over
     */
    public int runBatch(String batchId) {
        List<Subject> subjects = unprocessedSubjects(batchId);
        int processed = 0;

        try {
            for (Subject subject : subjects) {
                boolean wrote = reconciliation.processSubject(
                        batchId, subject.subjectKey(), subject.provider(),
                        subject.externalTransactionId());
                if (wrote) {
                    processed++;
                }
                sql.sql("UPDATE reconciliation_subjects SET claimed_at = now() "
                        + "WHERE batch_id = ? AND subject_key = ?")
                        .params(batchId, subject.subjectKey())
                        .update();
            }
        } catch (RuntimeException failure) {
            fail(batchId, failure);
            throw failure;
        }

        complete(batchId);
        log.info("reconciliation batch {} processed {} new subject(s)", batchId, processed);
        return processed;
    }

    /**
     * Records why a batch could not be completed.
     *
     * <p>In its own transaction: the caller's transaction is already aborted by whatever threw, so
     * writing there would roll the failure record back along with the cause and leave the batch in
     * exactly the orphaned state this method exists to prevent.
     */
    void fail(String batchId, RuntimeException cause) {
        String full = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        String reason = full.length() > MAX_REASON_LENGTH
                ? full.substring(0, MAX_REASON_LENGTH - 3) + "..."
                : full;
        try {
            new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        sql.sql("""
                                UPDATE reconciliation_batches
                                   SET status = 'FAILED', failure_reason = ?, completed_at = ?
                                 WHERE id = ?
                                """)
                                .params(reason, clock.instant(), batchId)
                                .update();
                        sql.sql("""
                                INSERT INTO audit_events
                                    (id, actor_type, actor_id, action, entity_type, entity_id,
                                     request_id, metadata)
                                VALUES (?, 'WORKER', 'reconciliation',
                                        'RECONCILIATION_BATCH_FAILED', 'RECONCILIATION_BATCH',
                                        ?, NULL, CAST(? AS jsonb))
                                """)
                                .params(Ulid.of("aud"), batchId,
                                        "{\"reason\":\"" + jsonEscape(reason) + "\"}")
                                .update();
                    });
        } catch (RuntimeException unwritable) {
            // Nothing more can be done, and hiding it would be worse than saying so.
            log.error("could not record the failure of reconciliation batch {}; it is still RUNNING",
                    batchId, unwritable);
        }
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Subjects with no result row yet — the definition of "not done" after a crash. */
    List<Subject> unprocessedSubjects(String batchId) {
        return sql.sql("""
                SELECT s.subject_key, s.provider, s.external_transaction_id
                  FROM reconciliation_subjects s
                 WHERE s.batch_id = ?
                   AND NOT EXISTS (
                       SELECT 1 FROM reconciliation_results r
                        WHERE r.batch_id = s.batch_id AND r.subject_key = s.subject_key)
                 ORDER BY s.subject_key
                """)
                .param(batchId)
                .list((rs, rowNum) -> new Subject(
                        rs.getString("subject_key"),
                        rs.getString("provider"),
                        rs.getString("external_transaction_id")));
    }

    /**
     * Writes the summary and marks the batch complete.
     *
     * <p>{@code grossDeltaMinor} is the headline figure: a batch whose gross delta is not zero is
     * the one an operator looks at first, because it says the two sides disagree about money.
     */
    public void complete(String batchId) {
        sql.sql("""
                UPDATE reconciliation_batches b
                   SET status = 'COMPLETED',
                       matched_count = (SELECT count(*) FROM reconciliation_results r
                                         WHERE r.batch_id = b.id AND r.outcome = 'MATCHED'),
                       matched_not_settled_count =
                           (SELECT count(*) FROM reconciliation_results r
                             WHERE r.batch_id = b.id AND r.outcome = 'MATCHED_NOT_SETTLED'),
                       mismatched_count =
                           (SELECT count(*) FROM reconciliation_results r
                             WHERE r.batch_id = b.id AND r.outcome LIKE '%MISMATCH%'),
                       missing_on_provider_count =
                           (SELECT count(*) FROM reconciliation_results r
                             WHERE r.batch_id = b.id AND r.outcome = 'MISSING_ON_PROVIDER'),
                       missing_internal_count =
                           (SELECT count(*) FROM reconciliation_results r
                             WHERE r.batch_id = b.id AND r.outcome = 'MISSING_INTERNAL'),
                       duplicate_count =
                           (SELECT count(*) FROM reconciliation_results r
                             WHERE r.batch_id = b.id AND r.outcome = 'DUPLICATE_PROVIDER_RECORD'),
                       ambiguous_count =
                           (SELECT count(*) FROM reconciliation_results r
                             WHERE r.batch_id = b.id AND r.outcome = 'AMBIGUOUS_MATCH'),
                       gross_delta_minor = COALESCE(
                           (SELECT sum(delta_minor) FROM reconciliation_results r
                             WHERE r.batch_id = b.id AND r.delta_minor IS NOT NULL), 0),
                       completed_at = ?
                 WHERE b.id = ?
                """)
                .params(clock.instant(), batchId)
                .update();

        auditBatchCompleted(batchId);
    }

    private void auditBatchCompleted(String batchId) {
        String outcome = sql.sql("""
                SELECT outcome, count(*) AS n FROM reconciliation_results
                 WHERE batch_id = ? GROUP BY outcome ORDER BY outcome
                """)
                .param(batchId)
                .list((rs, rowNum) -> rs.getString("outcome") + "=" + rs.getInt("n"))
                .stream()
                .reduce((a, b) -> a + "," + b)
                .orElse("none");

        sql.sql("""
                UPDATE reconciliation_batches
                   SET processed_subjects = (SELECT count(*) FROM reconciliation_results
                                              WHERE batch_id = ?)
                 WHERE id = ?
                """)
                .params(batchId, batchId)
                .update();

        auditCompleted(batchId, outcome);
    }

    private void auditCompleted(String batchId, String outcome) {
        // Written through the same audit table as everything else, so "what happened to this batch"
        // is answerable from the database alone.
        sql.sql("""
                INSERT INTO audit_events
                    (id, actor_type, actor_id, action, entity_type, entity_id, request_id, metadata)
                VALUES (?, 'WORKER', 'reconciliation', 'RECONCILIATION_BATCH_COMPLETED',
                        'RECONCILIATION_BATCH', ?, NULL, CAST(? AS jsonb))
                """)
                .params(Ulid.of("aud"), batchId, "{\"outcomes\":\"" + outcome + "\"}")
                .update();
    }

    /** One not-yet-classified subject. Package-private so the integration test can drive a resume. */
    record Subject(String subjectKey, String provider, String externalTransactionId) {
    }
}