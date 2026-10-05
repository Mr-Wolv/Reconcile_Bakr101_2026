package com.reconcile.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Read model of {@code ledger_accounts} — the chart of accounts. Seeded by V1 and never written by the application. */
@Entity
@Table(name = "ledger_accounts")
public class LedgerAccountEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "code", nullable = false, length = 32)
    private String code;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "account_type", nullable = false, length = 16)
    private String accountType;

    @Column(name = "normal_side", nullable = false, length = 6)
    private String normalSide;

    @Column(name = "state", nullable = false, length = 8)
    private String state;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerAccountEntity() {
        // for JPA
    }

    public String getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getAccountType() {
        return accountType;
    }

    public String getNormalSide() {
        return normalSide;
    }

    public String getState() {
        return state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}