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
 * Read model of {@code provider_transactions} — the provider's own record of a payment.
 *
 * <p>This is one half of what reconciliation compares. The other half is derived from
 * {@code ledger_entries}, not from {@code payments}: if both sides of the comparison were derived
 * from the same row, a bug that wrote a wrong amount into both the payment and the ledger would
 * reconcile clean forever (ADR-0005).
 */
@Entity
@Table(name = "provider_transactions")
public class ProviderTransactionEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    @Column(name = "provider_transaction_id", nullable = false, length = 64)
    private String providerTransactionId;

    @Column(name = "payment_id", length = 64)
    private String paymentId;

    @Column(name = "merchant_reference", nullable = false, length = 64)
    private String merchantReference;

    @Column(name = "gross_amount_minor", nullable = false)
    private long grossAmountMinor;

    @Column(name = "fee_amount_minor", nullable = false)
    private long feeAmountMinor;

    @Column(name = "net_amount_minor", nullable = false)
    private long netAmountMinor;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

    @Column(name = "provider_status", nullable = false, length = 24)
    private String providerStatus;

    @Column(name = "source", nullable = false, length = 8)
    private String source;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProviderTransactionEntity() {
        // for JPA
    }

    public String getId() {
        return id;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderTransactionId() {
        return providerTransactionId;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getMerchantReference() {
        return merchantReference;
    }

    public long getGrossAmountMinor() {
        return grossAmountMinor;
    }

    public long getFeeAmountMinor() {
        return feeAmountMinor;
    }

    public long getNetAmountMinor() {
        return netAmountMinor;
    }

    public CurrencyCode getCurrency() {
        return currency;
    }

    public String getProviderStatus() {
        return providerStatus;
    }

    public String getSource() {
        return source;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}