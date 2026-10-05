package com.reconcile.service;

import com.reconcile.persistence.jdbc.Sql;
import com.reconcile.shared.DomainException;
import com.reconcile.shared.ProblemCode;
import com.reconcile.shared.Ulid;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reconciliation case lifecycle.
 *
 * <p>Cases are the human end of the pipeline, so this class is mostly about <b>friction that is
 * deliberate</b>. Resolving a case requires a written explanation; write-off additionally requires a
 * categorised reason and is admin-only. Both exist because the alternative — a case closed with a
 * click and no record of why — makes the reconciliation output unauditable, which would defeat the
 * purpose of running it at all.
 */
@Service
public class ReconciliationCaseService {

    /** Closed enum, per spec 04 §6. A free-text reason is not a categorised one. */
    public enum WriteOffAction {
        PROVIDER_ERROR_CONFIRMED,
        INTERNAL_ERROR_CONFIRMED,
        DUPLICATE_OF_CASE,
        TIMING_DIFFERENCE,
        OTHER
    }

    private static final int MINIMUM_NOTE_LENGTH = 20;

    private final Sql sql;
    private final AuditService audit;
    private final Clock clock;

    public ReconciliationCaseService(Sql sql, AuditService audit, Clock clock) {
        this.sql = sql;
        this.audit = audit;
        this.clock = clock;
    }

    /** {@code OPEN → INVESTIGATING}. Requires an owner: an unassigned case is nobody's problem. */
    @Transactional
    public void investigate(String caseId, String assignedTo, String actorId, String requestId) {
        if (assignedTo == null || assignedTo.isBlank()) {
            throw DomainException.validation("assignedTo", "is required to start investigating", null);
        }
        CaseRow current = load(caseId);
        if (!"OPEN".equals(current.status())) {
            throw invalidTransition(current, "INVESTIGATING");
        }

        sql.sql("""
                UPDATE reconciliation_cases
                   SET status = 'INVESTIGATING', assigned_to = ?, updated_at = ?, version = version + 1
                 WHERE id = ?
                """)
                .params(assignedTo, clock.instant(), caseId)
                .update();

        appendEvent(caseId, "OPEN", "INVESTIGATING", "assigned to " + assignedTo, "USER", actorId);
        audit.record("USER", actorId, "CASE_INVESTIGATING", "RECONCILIATION_CASE", caseId, requestId,
                java.util.Map.of("assignedTo", assignedTo));
    }

    /**
     * {@code → RESOLVED}.
     *
     * <p>If {@code adjustmentLedgerTransactionId} is supplied it is verified, not trusted: the
     * transaction must exist, be {@code POSTED}, and be linked to this payment. Accepting an
     * unverified id would let a case be closed with a reference to a transaction that has nothing to
     * do with the difference — an audit trail that points at nothing.
     */
    @Transactional
    public void resolve(String caseId, String note, String adjustmentLedgerTransactionId,
            String actorId, String requestId) {

        requireNote(note);
        CaseRow current = load(caseId);
        if (!isOpen(current)) {
            throw invalidTransition(current, "RESOLVED");
        }

        if (adjustmentLedgerTransactionId != null && !adjustmentLedgerTransactionId.isBlank()) {
            verifyAdjustment(caseId, adjustmentLedgerTransactionId);
        }

        sql.sql("""
                UPDATE reconciliation_cases
                   SET status = 'RESOLVED', resolution_note = ?, resolved_at = ?,
                       adjustment_ledger_transaction_id = ?, updated_at = ?, version = version + 1
                 WHERE id = ?
                """)
                .params(note.trim(), clock.instant(),
                        blankToNull(adjustmentLedgerTransactionId), clock.instant(), caseId)
                .update();

        appendEvent(caseId, current.status(), "RESOLVED", note.trim(), "USER", actorId);
        audit.record("USER", actorId, "CASE_RESOLVED", "RECONCILIATION_CASE", caseId, requestId,
                java.util.Map.of("hasAdjustment", adjustmentLedgerTransactionId != null));
    }

    /** {@code → WRITTEN_OFF}. Admin-only at the route table; categorised reason required here. */
    @Transactional
    public void writeOff(String caseId, String note, WriteOffAction action,
            String actorId, String requestId) {

        requireNote(note);
        if (action == null) {
            throw DomainException.validation("resolutionAction", "is required to write off a case", null);
        }
        CaseRow current = load(caseId);
        if (!isOpen(current)) {
            throw invalidTransition(current, "WRITTEN_OFF");
        }

        sql.sql("""
                UPDATE reconciliation_cases
                   SET status = 'WRITTEN_OFF', resolution_note = ?, resolution_action = ?,
                       resolved_at = ?, updated_at = ?, version = version + 1
                 WHERE id = ?
                """)
                .params(note.trim(), action.name(), clock.instant(), clock.instant(), caseId)
                .update();

        appendEvent(caseId, current.status(), "WRITTEN_OFF", action.name(), "USER", actorId);
        audit.record("USER", actorId, "CASE_WRITTEN_OFF", "RECONCILIATION_CASE", caseId, requestId,
                java.util.Map.of("action", action.name()));
    }

    /**
     * Confirms an adjustment transaction is real, posted, and about this payment.
     *
     * <p>All three, or the case cannot be closed. A reference to an unposted transaction is a claim
     * about work that has not happened, and a reference to another payment's transaction is a
     * reference to nothing relevant.
     */
    private void verifyAdjustment(String caseId, String ledgerTransactionId) {
        Optional<String> state = sql.sql("SELECT state FROM ledger_transactions WHERE id = ?")
                .param(ledgerTransactionId)
                .optional((rs, rowNum) -> rs.getString("state"));

        if (state.isEmpty()) {
            throw new DomainException(
                    ProblemCode.CASE_RESOLUTION_INCOMPLETE,
                    "adjustment transaction " + ledgerTransactionId + " does not exist");
        }
        if (!"POSTED".equals(state.get())) {
            throw new DomainException(
                    ProblemCode.CASE_RESOLUTION_INCOMPLETE,
                    "adjustment transaction " + ledgerTransactionId + " is " + state.get()
                            + ", not POSTED");
        }

        String paymentId = load(caseId).paymentId();
        if (paymentId == null) {
            return;
        }
        boolean linked = sql.sql("""
                SELECT 1 FROM payment_ledger_transactions
                 WHERE payment_id = ? AND ledger_transaction_id = ?
                """)
                .params(paymentId, ledgerTransactionId)
                .optional((rs, rowNum) -> Boolean.TRUE)
                .orElse(false);

        if (!linked) {
            throw new DomainException(
                    ProblemCode.CASE_RESOLUTION_INCOMPLETE,
                    "adjustment transaction " + ledgerTransactionId + " does not touch payment "
                            + paymentId);
        }
    }

    /** Whether a case may still be worked on. Uses {@code equals}, never {@code ==} on a DB string. */
    private static boolean isOpen(CaseRow current) {
        return "OPEN".equals(current.status()) || "INVESTIGATING".equals(current.status());
    }

    private void requireNote(String note) {
        if (note == null || note.trim().length() < MINIMUM_NOTE_LENGTH) {
            throw new DomainException(
                    ProblemCode.CASE_RESOLUTION_INCOMPLETE,
                    "a resolution note of at least " + MINIMUM_NOTE_LENGTH
                            + " characters is required; 'marked resolved' is not an explanation");
        }
    }

    private CaseRow load(String caseId) {
        return sql.sql("SELECT status, payment_id FROM reconciliation_cases WHERE id = ?")
                .param(caseId)
                .optional((rs, rowNum) -> new CaseRow(rs.getString("status"), rs.getString("payment_id")))
                .orElseThrow(() -> DomainException.notFound("reconciliation case", caseId));
    }

    private static DomainException invalidTransition(CaseRow current, String target) {
        // The raw status is quoted and measured, not just printed: a status that does not match what
        // it looks like (padding, a stray character) produces a message that reads as a contradiction
        // and sends the reader looking for the bug in the wrong place.
        return new DomainException(
                ProblemCode.CASE_INVALID_TRANSITION,
                "a case in [" + current.status() + "] (length " + current.status().length()
                        + ") cannot move to " + target);
    }

    private void appendEvent(String caseId, String from, String to, String note,
            String actorType, String actorId) {

        sql.sql("""
                INSERT INTO case_events
                    (id, case_id, from_status, to_status, note, actor_type, actor_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(Ulid.of("cev"), caseId, from, to, truncate(note), actorType, actorId,
                        clock.instant())
                .update();
    }

    private static String truncate(String note) {
        return note != null && note.length() <= 2000 ? note : (note == null ? null : note.substring(0, 1997));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Open or investigating cases, most severe first. */
    public List<CaseView> open() {
        return sql.sql("""
                SELECT id, case_number, payment_id, provider_transaction_id, reason, severity,
                       status, occurrence_count, detected_at
                  FROM reconciliation_cases
                 WHERE status IN ('OPEN','INVESTIGATING')
                 ORDER BY CASE severity WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END,
                          detected_at, id
                """)
                .list((rs, rowNum) -> new CaseView(
                        rs.getString("id"),
                        "RC-" + rs.getLong("case_number"),
                        rs.getString("payment_id"),
                        rs.getString("provider_transaction_id"),
                        rs.getString("reason"),
                        rs.getString("severity"),
                        rs.getString("status"),
                        rs.getInt("occurrence_count"),
                        Sql.instant(rs, "detected_at")));
    }

    public record CaseView(
            String id,
            String caseNumber,
            String paymentId,
            String providerTransactionId,
            String reason,
            String severity,
            String status,
            int occurrenceCount,
            java.time.Instant detectedAt) {
    }

    private record CaseRow(String status, String paymentId) {
    }
}