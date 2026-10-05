package com.reconcile.persistence.entity;

import com.reconcile.shared.CurrencyCode;
import jakarta.persistence.Column;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Read model of {@code account_balances} — the L7 balance projection, keyed by account <i>and</i>
 * currency.
 *
 * <p>This row is a cache of {@code ledger_entries}, and the only one in the system. It exists so
 * that "what does PLATFORM_CASH hold?" is one indexed read rather than a sum over every entry ever
 * written. The {@link LedgerBalanceVerifier} recomputes the truth on demand, and the two are
 * compared rather than assumed equal.
 *
 * <p>Signed in the account's <b>normal</b> direction, so positive always means "more of this
 * account" regardless of whether it is an asset or a liability. That convention is what lets L8 —
 * "an asset may not go negative" — be a single trigger rather than a type-specific expression.
 */
@Entity
@Table(name = "account_balances")
public class AccountBalanceEntity {

    /** Composite key: one account holds several currencies, so the currency is part of identity. */
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "account_id", nullable = false, length = 64)
        private String accountId;

        @Enumerated(EnumType.STRING)
        @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
        private CurrencyCode currency;

        protected Key() {
            // for JPA
        }

        public Key(String accountId, CurrencyCode currency) {
            this.accountId = Objects.requireNonNull(accountId, "accountId");
            this.currency = Objects.requireNonNull(currency, "currency");
        }

        public String getAccountId() {
            return accountId;
        }

        public CurrencyCode getCurrency() {
            return currency;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key other)) {
                return false;
            }
            return Objects.equals(accountId, other.accountId) && currency == other.currency;
        }

        @Override
        public int hashCode() {
            return Objects.hash(accountId, currency);
        }
    }

    @EmbeddedId
    private Key id;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    @Column(name = "entry_count", nullable = false)
    private long entryCount;

    @Column(name = "last_entry_at")
    private Instant lastEntryAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AccountBalanceEntity() {
        // for JPA
    }

    public Key getId() {
        return id;
    }

    public String getAccountId() {
        return id.getAccountId();
    }

    public CurrencyCode getCurrency() {
        return id.getCurrency();
    }

    public long getBalanceMinor() {
        return balanceMinor;
    }

    public long getEntryCount() {
        return entryCount;
    }

    public Instant getLastEntryAt() {
        return lastEntryAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}