package com.reconcile.ledger;

import com.reconcile.shared.CurrencyCode;
import com.reconcile.shared.Money;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * An instruction to post a ledger transaction, validated before it can reach persistence.
 *
 * <p>This is the only way money moves in the application. A caller assembles entries; it cannot
 * influence an outcome, cannot supply a balance, and cannot bypass the checks below. Validating
 * here means the domain rejects a malformed posting <i>before</i> a single row is written — the
 * database triggers then defend the same invariants against everything that is not this class.
 *
 * <p>Enforced in the constructor:
 * <ul>
 *   <li><b>L2</b> every entry shares one currency;</li>
 *   <li><b>L3</b> at least two entries, at least one debit and one credit, every amount &gt; 0;</li>
 *   <li><b>L1</b> total debits equal total credits;</li>
 *   <li>reversal entries are only permitted on a {@link LedgerTransactionType#REVERSAL} command.</li>
 * </ul>
 */
public record LedgerPostingCommand(
        LedgerTransactionType type,
        CurrencyCode currency,
        List<Entry> entries,
        String sourceType,
        String sourceId,
        String description,
        String reversalOfTransactionId,
        String reversalReason) {

    /**
     * A single line of a posting: which account, which way, how much.
     *
     * <p>{@code amount} is always positive; {@link Direction} carries the sign.
     */
    public record Entry(String accountCode, Direction direction, Money amount) {
        public Entry {
            Objects.requireNonNull(accountCode, "accountCode");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(amount, "amount");
            if (amount.isZero()) {
                throw new IllegalArgumentException(
                        "zero-value ledger entries are not allowed (account " + accountCode + ")");
            }
        }
    }

    private static final Set<String> ALLOWED_SOURCE_TYPES =
            Set.of("PAYMENT", "SETTLEMENT_RECORD", "PAYOUT", "RECONCILIATION_CASE", "SYSTEM");

    public LedgerPostingCommand {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(description, "description");

        if (entries.size() < 2) {
            throw new IllegalArgumentException(
                    "a ledger transaction needs at least two entries, got " + entries.size());
        }
        if (!ALLOWED_SOURCE_TYPES.contains(sourceType)) {
            throw new IllegalArgumentException("unsupported source type: " + sourceType);
        }

        long debits = 0L;
        long credits = 0L;
        boolean sawDebit = false;
        boolean sawCredit = false;

        for (Entry entry : entries) {
            if (entry.amount().currency() != currency) {
                throw new IllegalArgumentException(
                        "L2 violated: entry for account " + entry.accountCode() + " is in "
                                + entry.amount().currency() + " but the transaction is in " + currency);
            }
            if (entry.direction() == Direction.DEBIT) {
                debits += entry.amount().amountMinor();
                sawDebit = true;
            } else {
                credits += entry.amount().amountMinor();
                sawCredit = true;
            }
        }

        if (!sawDebit || !sawCredit) {
            throw new IllegalArgumentException("L3 violated: a transaction needs both a debit and a credit");
        }
        if (debits != credits) {
            throw new IllegalArgumentException(
                    "L1 violated: debits " + debits + " do not equal credits " + credits);
        }

        if (type.isReversal()) {
            if (reversalOfTransactionId == null || reversalOfTransactionId.isBlank()) {
                throw new IllegalArgumentException("a REVERSAL must name the transaction it reverses");
            }
            if (reversalReason == null || reversalReason.isBlank()) {
                throw new IllegalArgumentException("a REVERSAL must carry a reason");
            }
        } else if (reversalOfTransactionId != null) {
            throw new IllegalArgumentException(
                    "only a REVERSAL may reference another transaction, got " + type);
        }
    }

    public long totalDebits() {
        return entries.stream()
                .filter(e -> e.direction() == Direction.DEBIT)
                .mapToLong(e -> e.amount().amountMinor())
                .sum();
    }

    public long totalCredits() {
        return entries.stream()
                .filter(e -> e.direction() == Direction.CREDIT)
                .mapToLong(e -> e.amount().amountMinor())
                .sum();
    }

    /** The total moved, i.e. the amount on either side of the balanced transaction. */
    public Money total() {
        return Money.of(totalDebits(), currency);
    }
}