package com.reconcile.service;

import com.reconcile.ledger.Direction;
import com.reconcile.ledger.LedgerPostingCommand;
import com.reconcile.ledger.LedgerTransactionType;
import com.reconcile.persistence.jdbc.LedgerWriter;
import com.reconcile.shared.CurrencyCode;
import com.reconcile.shared.DomainException;
import com.reconcile.shared.Money;
import com.reconcile.shared.ProblemCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import com.reconcile.persistence.jdbc.Sql;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The only path by which money moves.
 *
 * <p><b>Transaction boundary.</b> When a command already has a transaction — capture, payout,
 * settlement — the posting <i>joins</i> it. The specification requires one transaction per command,
 * and a ledger posting that committed independently would leave a payment marked {@code CAPTURED}
 * with no money moved if the second half failed, which is the exact corruption this system exists
 * to prevent. So the propagation is {@code REQUIRED}, not {@code REQUIRES_NEW}.
 *
 * <p><b>Retry.</b> Retrying is only sound when this method owns the transaction. Inside a caller's
 * transaction a deadlock has already poisoned it, and retrying there would be retrying on a
 * connection PostgreSQL has marked rollback-only. So the retry loop runs only for callers with no
 * ambient transaction (the background workers), which is exactly the case ADR-0002 describes.
 *
 * <p>Retrying is safe because posting is atomic and guarded by L10: if the first attempt actually
 * committed before the client saw an error, the retry's {@code INSERT} violates
 * {@code ux_ledger_source} and is refused rather than double-posting. That is the property which
 * makes an optimistic retry sound instead of merely convenient.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    /** Attempt 1 is the normal path; two retries matches the ADR's 25/75/200 ms schedule. */
    private static final int MAX_ATTEMPTS = 3;
    private static final long[] BACKOFF_MILLIS = {25L, 75L, 200L};

    private final LedgerWriter writer;
    private final Sql sql;
    private final TransactionTemplate requiresNew;

    public LedgerService(
            LedgerWriter writer,
            Sql sql,
            PlatformTransactionManager transactionManager) {
        this.writer = writer;
        this.sql = sql;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Posts a balanced transaction and returns its id.
     *
     * <p>Joins the caller's transaction if there is one, and owns a retrying transaction if there
     * is not.
     *
     * @throws DomainException {@code LEDGER_ALREADY_POSTED} if the same business fact was posted
     *         before, {@code LEDGER_IMBALANCE} if the command or the chart is unusable
     */
    public String post(LedgerPostingCommand command, LedgerWriter.PostingContext context) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return writer.post(command, context).transactionId();
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return requiresNew.execute(status -> writer.post(command, context)).transactionId();
            } catch (DeadlockLoserDataAccessException | CannotAcquireLockException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw new DomainException(
                            ProblemCode.INTERNAL_ERROR,
                            "the ledger could not be written after " + MAX_ATTEMPTS
                                    + " attempts due to lock contention",
                            e);
                }
                backOff(attempt, command);
            }
        }
    }

    /**
     * Reverses a posted transaction by mirroring it.
     *
     * <p>The original is never touched (L4). "Is this reversed?" is answered by looking for a row
     * whose {@code reversal_of_transaction_id} points at it, which {@code ux_ledger_reversal} makes
     * a unique, race-free check — so two concurrent reversal requests cannot both succeed.
     *
     * @throws DomainException {@code LEDGER_ALREADY_POSTED} if it has already been reversed (L6)
     */
    public String reverse(String originalTransactionId, String reason, LedgerWriter.PostingContext context) {
        ReversalSource source = readReversalSource(originalTransactionId);

        List<LedgerPostingCommand.Entry> mirrored = new ArrayList<>(source.entries().size());
        for (EntryRow entry : source.entries()) {
            // Exact sign flip: same account, same amount, opposite direction. Anything else would
            // be a correction dressed up as a reversal.
            mirrored.add(new LedgerPostingCommand.Entry(
                    entry.accountCode(),
                    entry.direction().opposite(),
                    Money.of(entry.amountMinor(), source.currency())));
        }

        LedgerPostingCommand reversal = new LedgerPostingCommand(
                LedgerTransactionType.REVERSAL,
                source.currency(),
                mirrored,
                source.sourceType(),
                source.sourceId(),
                "Reversal of " + originalTransactionId + ": " + reason,
                originalTransactionId,
                reason);

        return post(reversal, context);
    }

    /**
     * L7: recomputes every balance from its entries and reports the accounts that disagree.
     *
     * <p>An empty result is the pass condition. A non-empty result is a critical breach and is
     * deliberately <i>not</i> repaired here — silently fixing a projection destroys the evidence
     * that the bug happened, and an operator needs that evidence to find the cause.
     *
     * <p>The {@code direction = normal_side} comparison is what makes one expression correct for
     * every account type: an {@code ASSET} yields debits − credits and a {@code LIABILITY} yields
     * credits − debits, matching the stored convention.
     *
     * <p><b>Two questions, not one.</b> The first query asks whether each projection row agrees
     * with the entries. The second asks whether every (account, currency) that <i>has</i> entries
     * has a projection row at all. The second was missing, and its absence was a real blind spot:
     * the first query is driven {@code FROM account_balances}, so a row that had been deleted was
     * simply not there to disagree with anything, and health reported a ledger with committed
     * entries and no balances for them as healthy. Both directions of L7 have to be checked or the
     * invariant is only half verified.
     */
    public List<BalanceDrift> verifyBalances() {
        List<BalanceDrift> disagreements = sql.sql("""
                SELECT a.code,
                       b.currency,
                       b.balance_minor                                       AS projected,
                       COALESCE(SUM(CASE WHEN e.direction = a.normal_side
                                         THEN e.amount_minor ELSE -e.amount_minor END), 0)
                                                                            AS recomputed,
                       b.entry_count                                         AS projected_count,
                       COUNT(e.id)                                           AS actual_count
                  FROM account_balances b
                  JOIN ledger_accounts a ON a.id = b.account_id
                  LEFT JOIN ledger_entries e
                         ON e.account_id = b.account_id AND e.currency = b.currency
                 GROUP BY a.code, a.normal_side, b.account_id, b.currency,
                          b.balance_minor, b.entry_count
                HAVING b.balance_minor <> COALESCE(SUM(CASE WHEN e.direction = a.normal_side
                                                       THEN e.amount_minor
                                                       ELSE -e.amount_minor END), 0)
                    OR b.entry_count <> COUNT(e.id)
                ORDER BY a.code
                """)
                .list((rs, rowNum) -> new BalanceDrift(
                        rs.getString("code"),
                        rs.getString("currency"),
                        rs.getLong("projected"),
                        rs.getLong("recomputed"),
                        rs.getLong("projected_count"),
                        rs.getLong("actual_count")));

        // Driven from ledger_entries instead, which is the only way to see a projection row that is
        // not there. Reported as projected = 0 against the entries' own total, so the same record
        // shape and the same delta arithmetic describe both kinds of breach.
        List<BalanceDrift> missing = sql.sql("""
                SELECT a.code,
                       e.currency,
                       0                                                              AS projected,
                       SUM(CASE WHEN e.direction = a.normal_side
                                THEN e.amount_minor ELSE -e.amount_minor END) AS recomputed,
                       0                                                              AS projected_count,
                       COUNT(e.id)                                                    AS actual_count
                  FROM ledger_entries e
                  JOIN ledger_accounts a ON a.id = e.account_id
                 GROUP BY a.code, a.normal_side, e.account_id, e.currency
                HAVING NOT EXISTS (
                           SELECT 1 FROM account_balances b
                            WHERE b.account_id = e.account_id AND b.currency = e.currency)
                 ORDER BY a.code
                """)
                .list((rs, rowNum) -> new BalanceDrift(
                        rs.getString("code"),
                        rs.getString("currency"),
                        rs.getLong("projected"),
                        rs.getLong("recomputed"),
                        rs.getLong("projected_count"),
                        rs.getLong("actual_count")));

        List<BalanceDrift> all = new ArrayList<>(disagreements);
        all.addAll(missing);
        return List.copyOf(all);
    }

    /**
     * One account whose projection disagrees with its entries. A breach, not a warning.
     *
     * @param projectedMinor      the stored balance; {@code 0} when no projection row exists at all
     * @param projectedEntryCount the stored entry count; {@code 0} when no projection row exists
     */
    public record BalanceDrift(
            String accountCode,
            String currency,
            long projectedMinor,
            long recomputedMinor,
            long projectedEntryCount,
            long actualEntryCount) {

        public long deltaMinor() {
            return projectedMinor - recomputedMinor;
        }

        /**
         * Whether the breach is a projection row that is missing entirely rather than one that
         * disagrees. Both are critical, but they have different causes and an operator needs to be
         * able to tell them apart from the health output alone.
         */
        public boolean isMissingProjection() {
            return projectedEntryCount == 0L && actualEntryCount > 0L;
        }
    }

    private void backOff(int attempt, LedgerPostingCommand command) {
        long base = BACKOFF_MILLIS[Math.min(attempt, BACKOFF_MILLIS.length - 1)];
        // Jitter matters: without it, two transactions that deadlock retry in lockstep and collide
        // again at the same instant.
        long delay = base + ThreadLocalRandom.current().nextLong(base);
        log.warn("lock contention posting {} for {}/{}; retrying in {} ms (attempt {})",
                command.type(), command.sourceType(), command.sourceId(), delay, attempt);
        try {
            Thread.sleep(Duration.ofMillis(delay).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DomainException(
                    ProblemCode.INTERNAL_ERROR, "interrupted while retrying a ledger posting", e);
        }
    }

    private ReversalSource readReversalSource(String transactionId) {
        ReversalSource source = sql.sql("""
                SELECT type, currency, source_type, source_id
                  FROM ledger_transactions
                 WHERE id = ?
                """)
                .param(transactionId)
                .optional((rs, rowNum) -> new ReversalSource(
                        rs.getString("type"),
                        CurrencyCode.parse(rs.getString("currency")),
                        rs.getString("source_type"),
                        rs.getString("source_id"),
                        List.of()))
                .orElseThrow(() -> DomainException.notFound("ledger transaction", transactionId));

        List<EntryRow> entries = sql.sql("""
                SELECT account_code, direction, amount_minor
                  FROM ledger_entries
                 WHERE transaction_id = ?
                 ORDER BY line_no
                """)
                .param(transactionId)
                .list((rs, rowNum) -> new EntryRow(
                        rs.getString("account_code"),
                        Direction.valueOf(rs.getString("direction")),
                        rs.getLong("amount_minor")));

        if (entries.isEmpty()) {
            throw new DomainException(
                    ProblemCode.LEDGER_IMBALANCE,
                    "ledger transaction " + transactionId + " has no entries to reverse");
        }
        return new ReversalSource(
                source.type(), source.currency(), source.sourceType(), source.sourceId(), entries);
    }

    private record EntryRow(String accountCode, Direction direction, long amountMinor) {
    }

    private record ReversalSource(
            String type,
            CurrencyCode currency,
            String sourceType,
            String sourceId,
            List<EntryRow> entries) {

        ReversalSource {
            entries = List.copyOf(entries);
        }
    }
}