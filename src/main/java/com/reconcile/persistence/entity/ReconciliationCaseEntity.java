package com.reconcile.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * Read model of {@code reconciliation_cases} — an operational follow-up for one discrepancy.
 *
 * <p>{@code case_number} is a database-generated identity column rather than a primary key: it is a
 * human-facing handle ("RC-104") that stays small and readable across thousands of cases, while the
 * ULID {@code id} remains the actual key and keeps its time-ordering for index locality.
 *
 * <p>The partial unique index {@code ux_case_open_subject_reason} is what stops a daily re-run from
 * flooding the queue: a repeat finding increments {@code occurrence_count} on the open case instead
 * of opening another one.
 */
@Entity
@Table(name = "reconciliation_cases")
public class ReconciliationCaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    /**
     * Database-generated identity column, never written by the application. Marked read-only so a
     * future JPA insert cannot try to supply it — {@code @GeneratedValue} is not an option here
     * because it is only legal on an identifier, and this is a display handle, not the key.
     */
    @Column(name = "case_number", nullable = false, insertable = false, updatable = false)
    private Long caseNumber;

    @Column(name = "batch_id", nullable = false, length = 64)
    private String batchId;

    @Column(name = "result_id", nullable = false, length = 64)
    private String resultId;

    @Column(name = "payment_id", length = 64)
    private String paymentId;

    @Column(name = "provider_transaction_id", length = 64)
    private String providerTransactionId;

    @Column(name = "reason", nullable = false, length = 32)
    private String reason;

    @Column(name = "severity", nullable = false, length = 8)
    private String severity;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "occurrence_count", nullable = false)
    private int occurrenceCount;

    @Column(name = "assigned_to", length = 64)
    private String assignedTo;

    @Column(name = "resolution_note", length = 2000)
    private String resolutionNote;

    @Column(name = "resolution_action", length = 32)
    private String resolutionAction;

    @Column(name = "adjustment_ledger_transaction_id", length = 64)
    private String adjustmentLedgerTransactionId;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ReconciliationCaseEntity() {
        // for JPA
    }

    public String getId() {
        return id;
    }

    public Long getCaseNumber() {
        return caseNumber;
    }

    public String getBatchId() {
        return batchId;
    }

    public String getResultId() {
        return resultId;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getProviderTransactionId() {
        return providerTransactionId;
    }

    public String getReason() {
        return reason;
    }

    public String getSeverity() {
        return severity;
    }

    public String getStatus() {
        return status;
    }

    public int getOccurrenceCount() {
        return occurrenceCount;
    }

    public String getAssignedTo() {
        return assignedTo;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public String getResolutionAction() {
        return resolutionAction;
    }

    public String getAdjustmentLedgerTransactionId() {
        return adjustmentLedgerTransactionId;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}