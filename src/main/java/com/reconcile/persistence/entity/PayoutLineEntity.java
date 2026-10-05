package com.reconcile.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Read model of {@code payout_lines} — one payment inside one payout record.
 *
 * <p>{@code ux_payout_line_payment} is a unique index on {@code payment_id} alone, which makes L11
 * ("a payment may be paid out at most once, ever") a structural property of the table rather than
 * something the payout service is trusted to check. That is why a payout retried after a timeout
 * cannot pay a merchant twice even with a different idempotency key, and why two concurrent payout
 * requests covering the same payment cannot both succeed: one of them loses on this index.
 */
@Entity
@Table(name = "payout_lines")
public class PayoutLineEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "payout_record_id", nullable = false, length = 64)
    private String payoutRecordId;

    @Column(name = "payment_id", nullable = false, length = 64)
    private String paymentId;

    @Column(name = "line_net_minor", nullable = false)
    private long lineNetMinor;

    @Column(name = "line_no", nullable = false)
    private short lineNo;

    protected PayoutLineEntity() {
        // for JPA
    }

    public String getId() {
        return id;
    }

    public String getPayoutRecordId() {
        return payoutRecordId;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public long getLineNetMinor() {
        return lineNetMinor;
    }

    public int getLineNo() {
        return lineNo;
    }
}