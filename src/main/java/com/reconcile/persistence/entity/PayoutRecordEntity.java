package com.reconcile.persistence.entity;

import com.reconcile.shared.CurrencyCode;
import jakarta.persistence.Column;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Read model of {@code payout_records} — one merchant payout covering many payments.
 *
 * <p>v1 keeps a payout as a single record with lines rather than a batch hierarchy (decision D9),
 * because the ledger posting is per record: one balanced {@code MERCHANT_PAYOUT} for the record
 * total. An {@code EXECUTED} record must therefore name the transaction that moved the money, which
 * {@code ck_payout_executed} enforces.
 */
@Entity
@Table(name = "payout_records")
public class PayoutRecordEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "merchant_reference", nullable = false, length = 64)
    private String merchantReference;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

    @Column(name = "total_net_minor", nullable = false)
    private long totalNetMinor;

    @Column(name = "line_count", nullable = false)
    private int lineCount;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "ledger_transaction_id", length = 64)
    private String ledgerTransactionId;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "created_by_actor", nullable = false, length = 64)
    private String createdByActor;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PayoutRecordEntity() {
        // for JPA
    }

    public String getId() {
        return id;
    }

    public String getMerchantReference() {
        return merchantReference;
    }

    public CurrencyCode getCurrency() {
        return currency;
    }

    public long getTotalNetMinor() {
        return totalNetMinor;
    }

    public int getLineCount() {
        return lineCount;
    }

    public String getStatus() {
        return status;
    }

    public String getLedgerTransactionId() {
        return ledgerTransactionId;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    public String getCreatedByActor() {
        return createdByActor;
    }

    public String getRequestId() {
        return requestId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}