package com.reconcile.persistence.jdbc;

import com.reconcile.ledger.Direction;
import com.reconcile.ledger.LedgerPostingCommand;
import com.reconcile.shared.CurrencyCode;
import com.reconcile.shared.DomainException;
import com.reconcile.shared.ProblemCode;
import com.reconcile.shared.Ulid;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

/**
 * Writes a ledger transaction with native SQL.
 *
 * <p>Deliberately not JPA. The posting path is the one place in this system where the order of
 * operations is the correctness argument, and expressing it as a sequence of explicit statements
 * keeps that argument visible in the code instead of hidden in a flush ordering. It also has to
 * take {@code SELECT … FOR UPDATE} locks in a specific order, which an ORM has no reason to
 * preserve.
 *
 * <p>The method must be called inside a transaction. {@code LedgerService} owns that boundary so
 * that the retry-on-deadlock loop wraps the whole thing, not this fragment.
 *
 * <p>Numbered steps are the algorithm in {@code docs/spec/01-money-and-ledger.md §5}.
 */
@Repository
public class LedgerWriter {

    private final Sql sql;

    public LedgerWriter(Sql sql) {
        this.sql = sql;
    }

    /** What was written, for the caller to link and audit. */
    public record PostedTransaction(String transactionId, List<String> entryIds) {

        public PostedTransaction {
            entryIds = List.copyOf(entryIds);
        }

        public String totalMinor() {
            return String.valueOf(entryIds.size());
        }
    }

    /** Audit context carried onto the transaction row so a posting can be traced to its caller. */
    public record PostingContext(String actor, String requestId, String idempotencyKey) {
    }

    /**
     * Posts {@code command} and returns the ids written.
     *
     * @throws com.reconcile.shared.DomainException if an account is unknown or not active (L9), or
     *         the same business fact has already been posted (L10)
     */
    public PostedTransaction post(LedgerPostingCommand command, PostingContext context) {
        // Step 1 - L9: reject unknown or inactive accounts before taking any lock, so a typo fails
        // fast without holding anything.
        Map<String, AccountRow> accounts = lockAccounts(command);

        String transactionId = Ulid.of("ltx");
        CurrencyCode currency = command.currency();

        // Step 4 - L10: the unique index on (source_type, source_id, type) makes a duplicate
        // posting impossible; the violation is translated below rather than surfacing as a 500.
        try {
            sql.sql("""
                    INSERT INTO ledger_transactions
                        (id, type, currency, source_type, source_id, description,
                         reversal_of_transaction_id, reversal_reason, idempotency_key, request_id,
                         created_by_actor)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)
                    .params(transactionId, command.type().name(), currency.code(), command.sourceType(),
                            command.sourceId(), command.description(),
                            command.reversalOfTransactionId(), command.reversalReason(),
                            context.idempotencyKey(), context.requestId(), context.actor())
                    .update();
        } catch (DataIntegrityViolationException e) {
            throw alreadyPosted(command, e);
        }

        // Steps 5 and 6 - entries and the projection move together. There is no window in which an
        // entry exists without its balance having been updated, because both are in one
        // transaction and the deferred L1 trigger cannot fire until COMMIT.
        List<String> entryIds = new ArrayList<>(command.entries().size());
        for (int index = 0; index < command.entries().size(); index++) {
            LedgerPostingCommand.Entry entry = command.entries().get(index);
            AccountRow account = accounts.get(entry.accountCode());

            String entryId = Ulid.of("len");
            sql.sql("""
                    INSERT INTO ledger_entries
                        (id, transaction_id, account_id, direction, amount_minor, currency,
                         account_code, line_no)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)
                    .params(entryId, transactionId, account.id(), entry.direction().name(),
                            entry.amount().amountMinor(), currency.code(), account.code(),
                            (short) (index + 1))
                    .update();            // L7 projection: express the delta in the account's normal direction, so one UPDATE
            // works for assets, liabilities, revenue and expenses alike.
            long delta = entry.direction() == account.normalSide()
                    ? entry.amount().amountMinor()
                    : -entry.amount().amountMinor();

            // The row count is checked, and this is not defensive noise - it is the fix for a defect
            // where an UPDATE matching zero rows was silently ignored. The entry above is already
            // written when this runs, so a missing projection row meant the transaction committed
            // with real money in the immutable ledger and none in the balance, and L7 verification
            // could not see it: the verification query is driven FROM account_balances, so an absent
            // row is invisible to it by construction. 500.00 EGP of capture vanished that way while
            // /api/v1/health reported ledger: PASS.
            //
            // Failing here rolls the whole posting back, which is the only honest outcome: this is
            // a corrupted or mis-migrated ledger, not a business refusal, so it is INTERNAL_ERROR
            // (500) rather than a 4xx the caller could act on.
            int updated = sql.sql("""
                    UPDATE account_balances
                       SET balance_minor = balance_minor + ?,
                           entry_count   = entry_count + 1,
                           last_entry_at = now(),
                           updated_at    = now()
                     WHERE account_id = ? AND currency = ?
                    """)
                .params(delta, account.id(), currency.code())
                .update();

            if (updated != 1) {
                throw new DomainException(
                        ProblemCode.INTERNAL_ERROR,
                        "no balance projection row exists for account " + account.code() + " in "
                                + currency.code() + "; the ledger projection is missing or corrupt, "
                                + "so this posting has been rolled back rather than silently lost");
            }

            entryIds.add(entryId);
        }

        return new PostedTransaction(transactionId, entryIds);
    }

    /**
     * Step 3: lock every account the posting touches, in ascending code order.
     *
     * <p>The {@code ORDER BY} is load-bearing (ADR-0002). Without it, two postings that both touch
     * {@code {PLATFORM_CASH, PSP_CLEARING}} can take their two row locks in opposite orders and
     * deadlock. Sorting the lock set first makes acquisition order globally consistent across every
     * posting path, so deadlock becomes structurally impossible rather than merely unlikely.
     */
    private Map<String, AccountRow> lockAccounts(LedgerPostingCommand command) {
        List<String> codes = command.entries().stream()
                .map(LedgerPostingCommand.Entry::accountCode)
                .distinct()
                .toList();

        Map<String, AccountRow> found = new LinkedHashMap<>();
        List<AccountRow> locked = sql.sql("""
                SELECT id, code, normal_side, state
                  FROM ledger_accounts
                 WHERE code IN (:codes)
                 ORDER BY code
                   FOR UPDATE
                """)
                .param("codes", codes)
                .list((rs, rowNum) -> new AccountRow(
                        rs.getString("id"),
                        rs.getString("code"),
                        Direction.valueOf(rs.getString("normal_side")),
                        rs.getString("state")));
        locked.forEach(account -> found.put(account.code(), account));

        // The chart is data, not code, so a missing account is a deployment mistake rather than a
        // coding one. Naming the code makes it obvious which account needs seeding.
        List<String> unknown = codes.stream().filter(code -> !found.containsKey(code)).toList();
        if (!unknown.isEmpty()) {
            throw new DomainException(
                    ProblemCode.LEDGER_IMBALANCE,
                    "unknown ledger account(s): " + String.join(", ", unknown));
        }
        List<String> inactive = found.values().stream()
                .filter(account -> !"ACTIVE".equals(account.state()))
                .map(AccountRow::code)
                .toList();
        if (!inactive.isEmpty()) {
            throw new DomainException(
                    ProblemCode.LEDGER_IMBALANCE,
                    "cannot post to a non-active ledger account: " + String.join(", ", inactive));
        }
        return found;
    }

    private DomainException alreadyPosted(LedgerPostingCommand command, DataIntegrityViolationException cause) {
        return new DomainException(
                ProblemCode.LEDGER_ALREADY_POSTED,
                "ledger transaction " + command.type() + " already posted for "
                        + command.sourceType() + " " + command.sourceId(),
                cause);
    }

    /** One locked ledger account. */
    private record AccountRow(String id, String code, Direction normalSide, String state) {
    }
}