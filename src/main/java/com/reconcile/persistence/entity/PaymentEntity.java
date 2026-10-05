package com.reconcile.persistence.entity;

import com.reconcile.payment.PaymentState;
import com.reconcile.shared.CurrencyCode;
import jakarta.persistence.Column;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * Read model of {@code payments}.
 *
 * <p>This is operational state, not money. The ledger is the source of truth for amounts; these
 * columns are what an operator looks at. {@code amountMinor = netAmountMinor + platformFeeMinor}
 * is a database {@code CHECK}, and reconciliation deliberately does <i>not</i> trust these columns
 * when it decides what the provider should have reported (ADR-0005).
 *
 * <p>Writes to this table go through the payment service's native-SQL path rather than through this
 * entity, because capture must lock the payment row, the ledger accounts and the payout lines in
 * one transaction with a defined order. This mapping exists so the API and the read queries have a
 * type-checked view of the table, and so {@code ddl-auto: validate} proves it agrees with
 * {@code V1__baseline.sql}.
 */
@Entity
@Table(name = "payments")
public class PaymentEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "merchant_reference", nullable = false, length = 64)
    private String merchantReference;

    @Column(name = "description", length = 280)
    private String description;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

    @Column(name = "platform_fee_minor", nullable = false)
    private long platformFeeMinor;

    @Column(name = "net_amount_minor", nullable = false)
    private long netAmountMinor;

    @Column(name = "fee_policy_id", nullable = false, length = 64)
    private String feePolicyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private PaymentState state;

    @Column(name = "failure_code", length = 32)
    private String failureCode;

    @Column(name = "failure_reason", length = 280)
    private String failureReason;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    @Column(name = "provider_transaction_id", length = 64)
    private String providerTransactionId;

    @Column(name = "settlement_record_id", length = 64)
    private String settlementRecordId;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "paid_out_at")
    private Instant paidOutAt;

    @Column(name = "idempotency_key", length = 255)
    private String idempotencyKey;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected PaymentEntity() {
        // for JPA
    }

    public String getId() {
        return id;
    }

    public String getMerchantReference() {
        return merchantReference;
    }

    public String getDescription() {
        return description;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public CurrencyCode getCurrency() {
        return currency;
    }

    public long getPlatformFeeMinor() {
        return platformFeeMinor;
    }

    public long getNetAmountMinor() {
        return netAmountMinor;
    }

    public String getFeePolicyId() {
        return feePolicyId;
    }

    public PaymentState getState() {
        return state;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderTransactionId() {
        return providerTransactionId;
    }

    public String getSettlementRecordId() {
        return settlementRecordId;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public Instant getPaidOutAt() {
        return paidOutAt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}