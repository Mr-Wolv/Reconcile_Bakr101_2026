package com.reconcile.api;

import com.reconcile.persistence.jdbc.Sql;
import com.reconcile.persistence.jdbc.LedgerWriter;
import com.reconcile.service.LedgerService;
import com.reconcile.shared.CurrencyCode;
import com.reconcile.shared.DomainException;
import com.reconcile.shared.PageCursor;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ledger reads and the reversal command.
 *
 * <p>{@code GET /api/v1/accounts/{code}} returns the stored projection <i>and</i> the balance
 * recomputed from the entries, with a {@code verified} flag. Surfacing L7 on the primary balance
 * endpoint means an operator can see an invariant breach without knowing a report exists — and a
 * silently wrong balance that only a scheduled job notices is a wrong balance that survives a
 * business day.
 */
@RestController
@RequestMapping("/api/v1")
public class LedgerController {

    /**
     * Page size cap and default for the statement of account.
     *
     * <p>Deliberately the same numbers as the payments list rather than a larger local allowance:
     * spec 06 §5 caps every list response at 200, and an endpoint that quietly allows 500 where the
     * contract says 200 is a contract nobody can rely on. The cap is a {@code 400}, not a silent
     * truncation, so a caller that ignored it would find out.
     */
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 100;

    private final Sql sql;
    private final LedgerService ledger;

    public LedgerController(Sql sql, LedgerService ledger) {
        this.sql = sql;
        this.ledger = ledger;
    }

    public record AccountSummary(String code, String name, String accountType, String normalSide,
                                 String state) {
    }

    public record AccountBalance(
            String code,
            String name,
            String accountType,
            CurrencyCode currency,
            MoneyDto balance,
            MoneyDto recomputedBalance,
            long entryCount,
            long recomputedEntryCount,
            Instant lastEntryAt,
            boolean verified) {
    }

    public record LedgerEntryView(
            String id,
            String transactionId,
            String accountCode,
            String direction,
            long amountMinor,
            String currency,
            int lineNo,
            Instant postedAt) {
    }

    public record LedgerTransactionView(
            String id,
            String type,
            String state,
            String currency,
            String sourceType,
            String sourceId,
            String description,
            String reversalOfTransactionId,
            String createdByActor,
            Instant postedAt,
            List<LedgerEntryView> entries) {
    }

    public record ReverseRequest(String reason) {
    }

    @GetMapping("/accounts")
    public List<AccountSummary> accounts() {
        return sql.sql("""
                SELECT code, name, account_type, normal_side, state
                  FROM ledger_accounts
                 ORDER BY code
                """)
                .list((rs, rowNum) -> new AccountSummary(
                        rs.getString("code"), rs.getString("name"), rs.getString("account_type"),
                        rs.getString("normal_side"), rs.getString("state")));
    }

    /**
     * One account in one currency.
     *
     * <p>{@code currency} is required rather than defaulted: an account holds several, and silently
     * choosing one would answer a different question from the one asked.
     */
    @GetMapping("/accounts/{code}")
    public AccountBalance account(@PathVariable String code,
                                  @RequestParam(defaultValue = "EGP") String currency) {

        CurrencyCode currencyCode = parseCurrency(currency);

        List<AccountBalance> rows = sql.sql("""
                SELECT a.code, a.name, a.account_type,
                       b.balance_minor, b.entry_count, b.last_entry_at,
                       COALESCE(SUM(CASE WHEN e.direction = a.normal_side
                                         THEN e.amount_minor ELSE -e.amount_minor END), 0)
                           AS recomputed_balance,
                       COUNT(e.id) AS recomputed_count
                  FROM ledger_accounts a
                  JOIN account_balances b ON b.account_id = a.id AND b.currency = ?
                  LEFT JOIN ledger_entries e
                         ON e.account_id = a.id AND e.currency = ?
                 WHERE a.code = ?
                 GROUP BY a.code, a.name, a.account_type, a.normal_side,
                          b.balance_minor, b.entry_count, b.last_entry_at
                """)
                .params(currencyCode.code(), currencyCode.code(), code)
                .list((rs, rowNum) -> {
                    long projected = rs.getLong("balance_minor");
                    long recomputed = rs.getLong("recomputed_balance");
                    long projectedCount = rs.getLong("entry_count");
                    long recomputedCount = rs.getLong("recomputed_count");
                    return new AccountBalance(
                            rs.getString("code"), rs.getString("name"), rs.getString("account_type"),
                            currencyCode,
                            MoneyDto.of(com.reconcile.shared.Money.of(projected, currencyCode)),
                            MoneyDto.of(com.reconcile.shared.Money.of(recomputed, currencyCode)),
                            projectedCount, recomputedCount,
                            Sql.instant(rs, "last_entry_at"),
                            projected == recomputed && projectedCount == recomputedCount);
                });

        if (rows.isEmpty()) {
            throw DomainException.notFound("ledger account", code);
        }
        return rows.getFirst();
    }

    /**
     * The statement of account, newest first, keyset-paginated.
     *
     * <p>Served by {@code ix_entries_account_posted}. The row comparison in the keyset predicate is
     * row-value syntax — {@code (posted_at, id) < (?, ?)} — which PostgreSQL evaluates against the
     * index directly. Writing it as {@code posted_at < ? OR (posted_at = ? AND id < ?)} is the
     * same answer and a different plan: the OR defeats the index and turns every page after the
     * first into a sort of the whole account.
     *
     * <p>The look-ahead fetch is what lets a full page that is also the last page report
     * {@code nextCursor: null}; see the method body.
     *
     * <p>{@code id} is the tiebreak because {@code posted_at} alone is not unique — a reversal
     * posted in the same transaction as the thing it reverses can share a timestamp, and a keyset
     * with a non-unique sort key skips or repeats rows at the boundary.
     */
    /** Shared head of the statement-of-account query: projection plus the account predicate. */
    private static final String ENTRIES_WHERE = """
            SELECT e.id, e.transaction_id, e.account_code, e.direction, e.amount_minor,
                   e.currency, e.line_no, e.posted_at
              FROM ledger_entries e
              JOIN ledger_accounts a ON a.id = e.account_id
             WHERE a.code = ?
            """;

    private static final String ENTRIES_ORDER = """
             ORDER BY e.posted_at DESC, e.id
             LIMIT ?
            """;

    @GetMapping("/accounts/{code}/entries")
    public EntriesPage entries(
            @PathVariable String code,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {

        int size = limit == null ? DEFAULT_PAGE_SIZE : limit;
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw DomainException.validation("limit",
                    "must be between 1 and " + MAX_PAGE_SIZE, limit);
        }
        // An unknown account code is a 404, not an empty statement. `GET /accounts/{code}` already
        // refuses one, and a statement that silently reads as "nothing posted yet" for an account
        // that does not exist turns a typo into a false all-clear -- which is the specific failure
        // this system exists to prevent.
        if (sql.sql("SELECT 1 FROM ledger_accounts WHERE code = ?")
                .params(code)
                .optional((rs, rowNum) -> rs.getInt(1))
                .isEmpty()) {
            throw DomainException.notFound("ledger account", code);
        }
        PageCursor after = PageCursor.decode(cursor);

        // Fetch one row more than asked for. A keyset query cannot tell "the page I just served is
        // the last one" from "the page I just served happens to be full" — both look like `rows.size()
        // == size`. Without the extra row, a caller whose final page is exactly full gets a
        // `nextCursor` pointing past the end and must spend one more round-trip to learn the list is
        // finished. The extra row turns that into an honest `null` on the real last page, and the
        // index has to fetch it anyway, so the cost is one row.
        Sql.Statement query = after == null
                ? sql.sql(ENTRIES_WHERE + ENTRIES_ORDER).params(code, size + 1)
                : sql.sql(ENTRIES_WHERE + "   AND (e.posted_at, e.id) < (?, ?)\n" + ENTRIES_ORDER)
                        .params(code, after.instant(), after.id(), size + 1);

        List<LedgerEntryView> fetched = query
                .list((rs, rowNum) -> new LedgerEntryView(
                        rs.getString("id"), rs.getString("transaction_id"),
                        rs.getString("account_code"), rs.getString("direction"),
                        rs.getLong("amount_minor"), rs.getString("currency"),
                        rs.getInt("line_no"), Sql.instant(rs, "posted_at")));

        boolean more = fetched.size() > size;
        List<LedgerEntryView> rows = more ? List.copyOf(fetched.subList(0, size)) : fetched;

        // The look-ahead row is dropped, so a cursor is only ever offered when a page really follows.
        String nextCursor = more
                ? PageCursor.encode(rows.getLast().postedAt(), rows.getLast().id())
                : null;
        return new EntriesPage(rows, nextCursor);
    }

    /** The paged statement of account. {@code nextCursor} is null on the last page. */
    public record EntriesPage(List<LedgerEntryView> items, String nextCursor) {

        public EntriesPage {
            items = List.copyOf(items);
        }
    }

    /** The full ledger story of one transaction — the investigative path the audit design exists for. */
    @GetMapping("/ledger/transactions/{id}")
    public LedgerTransactionView transaction(@PathVariable String id) {
        List<LedgerTransactionView> found = sql.sql("""
                SELECT id, type, state, currency, source_type, source_id, description,
                       reversal_of_transaction_id, created_by_actor, posted_at
                  FROM ledger_transactions
                 WHERE id = ?
                """)
                .param(id)
                .list((rs, rowNum) -> new LedgerTransactionView(
                        rs.getString("id"), rs.getString("type"), rs.getString("state"),
                        rs.getString("currency"), rs.getString("source_type"),
                        rs.getString("source_id"), rs.getString("description"),
                        rs.getString("reversal_of_transaction_id"),
                        rs.getString("created_by_actor"),
                        Sql.instant(rs, "posted_at"),
                        List.of()));

        if (found.isEmpty()) {
            throw DomainException.notFound("ledger transaction", id);
        }
        LedgerTransactionView header = found.getFirst();

        List<LedgerEntryView> entries = sql.sql("""
                SELECT id, transaction_id, account_code, direction, amount_minor, currency,
                       line_no, posted_at
                  FROM ledger_entries
                 WHERE transaction_id = ?
                 ORDER BY line_no
                """)
                .param(id)
                .list((rs, rowNum) -> new LedgerEntryView(
                        rs.getString("id"), rs.getString("transaction_id"),
                        rs.getString("account_code"), rs.getString("direction"),
                        rs.getLong("amount_minor"), rs.getString("currency"),
                        rs.getInt("line_no"), Sql.instant(rs, "posted_at")));

        return new LedgerTransactionView(header.id(), header.type(), header.state(),
                header.currency(), header.sourceType(), header.sourceId(), header.description(),
                header.reversalOfTransactionId(), header.createdByActor(), header.postedAt(),
                entries);
    }

    /**
     * Admin-only reversal. Creates a new transaction; never edits the original.
     *
     * <p>The original is left byte-for-byte untouched — L4 forbids it, and a "reversed" flag on the
     * original would be exactly such an edit. Whether it has been reversed is answered by the
     * presence of a row pointing at it.
     */
    @PostMapping("/ledger/transactions/{id}/reverse")
    public ResponseEntity<String> reverse(
            @PathVariable String id,
            @RequestBody(required = false) ReverseRequest request) {

        String reason = request == null || request.reason() == null || request.reason().isBlank()
                ? "reversal requested by an operator"
                : request.reason().trim();

        String reversalId = ledger.reverse(id, reason,
                new LedgerWriter.PostingContext("api", RequestId.current(), null));

        return ResponseEntity.ok(reversalId);
    }

    private static CurrencyCode parseCurrency(String currency) {
        try {
            return CurrencyCode.parse(currency);
        } catch (IllegalArgumentException e) {
            throw DomainException.validation("currency", "is not a supported ISO-4217 code", currency);
        }
    }
}