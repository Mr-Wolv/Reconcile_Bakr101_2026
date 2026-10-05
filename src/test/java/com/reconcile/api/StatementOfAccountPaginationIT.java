package com.reconcile.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconcile.shared.DomainException;
import com.reconcile.support.SharedPostgres;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Keyset pagination on {@code GET /api/v1/accounts/{code}/entries}, against real PostgreSQL 18.
 *
 * <p>This endpoint had no test at all, which is why two separate defects survived a fully green
 * suite: it returned a bare array with no {@code nextCursor}, it accepted a page size of 500 where
 * the contract says 200, and it accepted a {@code cursor} query parameter that no signature
 * declared — so Spring ignored it silently and returned the first page while the caller believed
 * it had asked for another. A parameter that is quietly discarded is worse than one that is
 * refused, because the caller gets a successful answer to a question that was never asked.
 *
 * <p>Entries are written directly rather than by posting through the service. Posting would work
 * and would be slower, but it would also make the <em>ordering</em> under test a function of wall
 * clock and transaction scheduling. Pagination bugs live exactly at page boundaries, so the test
 * pins {@code posted_at} to known instants and asserts on the resulting order, which is the only
 * way a "no duplicates, nothing skipped" claim means anything.
 */
@SpringBootTest
class StatementOfAccountPaginationIT {

    /** Seven entries, newest first: 07, 06, 05, 04, 03, 02, 01. */
    private static final int ENTRY_COUNT = 7;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedPostgres::jdbcUrl);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
    }

    @Autowired
    LedgerController controller;

    @Autowired
    DataSource dataSource;

    @BeforeEach
    void seed() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("""
                    TRUNCATE payment_state_history, payment_ledger_transactions, ledger_entries,
                             ledger_transactions, account_balances, payments, payout_lines,
                             payout_records, settlement_record_lines, settlement_records,
                             provider_transactions, provider_events, provider_event_deliveries,
                             idempotency_records, reconciliation_cases, reconciliation_results,
                             reconciliation_subjects, reconciliation_batches, case_events,
                             audit_events, fee_policies
                    RESTART IDENTITY CASCADE
                    """);
            s.execute("INSERT INTO fee_policies (id, code, bps, currency, effective_from) "
                    + "VALUES ('fp_default','DEFAULT',300,'EGP','2026-01-01T00:00:00Z')");
            s.execute("""
                    INSERT INTO account_balances (account_id, currency)
                    SELECT a.id, c.code FROM ledger_accounts a CROSS JOIN currency_units c
                     WHERE c.active
                    """);
        }
        // One balanced transaction per entry, so the account's history is a run of distinct,
        // known timestamps rather than whatever the clock produced.
        for (int i = 1; i <= ENTRY_COUNT; i++) {
            String txn = "ltx_soa_" + i;
            // Header and entries in ONE call. Since V2, tg_txn_header_balances is a deferred
            // constraint trigger on ledger_transactions, so a header committed without its entries
            // is refused. Two exec() calls are two transactions; this is one posting.
            exec("INSERT INTO ledger_transactions (id,type,currency,source_type,source_id,"
                    + "description,created_by_actor) VALUES ('" + txn
                    + "','PAYMENT_CAPTURE','EGP','PAYMENT','pay_soa_" + i + "','soa','t');"
                    + "INSERT INTO ledger_entries (id,transaction_id,account_id,direction,amount_minor,"
                    + "currency,account_code,line_no,posted_at) VALUES "
                    + "('len_soa_" + i + "a','" + txn + "','acct_psp_clearing','DEBIT',1000,'EGP',"
                    + "'PSP_CLEARING',1,'2026-01-0" + i + "T00:00:00Z'),"
                    + "('len_soa_" + i + "b','" + txn + "','acct_merchant_payable','CREDIT',1000,"
                    + "'EGP','MERCHANT_PAYABLE',2,'2026-01-0" + i + "T00:00:00Z')");
        }
    }

    // ------------------------------------------------------------------ the contract

    @Test
    @DisplayName("The page is an object with items and nextCursor, not a bare array")
    void pageShapeIsItemsAndNextCursor() {
        LedgerController.EntriesPage page = page("PSP_CLEARING", 2, null);

        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).isNotNull();
        assertThat(page.items()).extracting(LedgerController.LedgerEntryView::id)
                .as("newest first")
                .containsExactly("len_soa_7a", "len_soa_6a");
    }

    @Test
    @DisplayName("Walking every page returns every entry exactly once, in order")
    void pagingCoversEveryEntryExactlyOnce() {
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;

        do {
            LedgerController.EntriesPage page = page("PSP_CLEARING", 2, cursor);
            page.items().forEach(e -> seen.add(e.id()));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null && pages < 20);

        assertThat(seen).as("nothing skipped, nothing repeated, strictly newest-first")
                .containsExactly("len_soa_7a", "len_soa_6a", "len_soa_5a", "len_soa_4a",
                        "len_soa_3a", "len_soa_2a", "len_soa_1a");
        assertThat(seen).doesNotHaveDuplicates();
        assertThat(pages).as("7 entries at 2 per page is 4 pages").isEqualTo(4);
    }

    @Test
    @DisplayName("A page size of one walks the whole account")
    void singleEntryPagesStillTerminate() {
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            LedgerController.EntriesPage page = page("PSP_CLEARING", 1, cursor);
            seen.addAll(page.items().stream().map(LedgerController.LedgerEntryView::id).toList());
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null && pages < 20);

        assertThat(seen).hasSize(ENTRY_COUNT).doesNotHaveDuplicates();
        assertThat(pages).isEqualTo(ENTRY_COUNT);
    }

    @Test
    @DisplayName("The last page reports nextCursor = null")
    void lastPageHasNoNextCursor() {
        LedgerController.EntriesPage last = page("PSP_CLEARING", ENTRY_COUNT, null);
        assertThat(last.items()).hasSize(ENTRY_COUNT);
        assertThat(last.nextCursor()).as("a full page that is also the end still says so").isNull();

        LedgerController.EntriesPage partial = page("PSP_CLEARING", ENTRY_COUNT + 1, null);
        assertThat(partial.items()).hasSize(ENTRY_COUNT);
        assertThat(partial.nextCursor()).isNull();
    }

    @Test
    @DisplayName("Replaying a cursor returns the same page - it is a position, not a token that expires")
    void cursorReplayIsStable() {
        LedgerController.EntriesPage first = page("PSP_CLEARING", 2, null);
        LedgerController.EntriesPage again = page("PSP_CLEARING", 2, first.nextCursor());
        LedgerController.EntriesPage andAgain = page("PSP_CLEARING", 2, first.nextCursor());

        assertThat(again.items()).extracting(LedgerController.LedgerEntryView::id)
                .containsExactlyElementsOf(andAgain.items().stream()
                        .map(LedgerController.LedgerEntryView::id).toList());
    }

    @Test
    @DisplayName("A row inserted between two pages does not shift or duplicate the next page")
    void insertionBetweenPagesDoesNotCorruptTheWalk() throws SQLException {
        LedgerController.EntriesPage first = page("PSP_CLEARING", 2, null);
        assertThat(first.items()).extracting(LedgerController.LedgerEntryView::id)
                .containsExactly("len_soa_7a", "len_soa_6a");

        // A newer entry arrives between the two calls, exactly as a capture would.
        // One posting, one transaction — see the note on the seed above.
        exec("INSERT INTO ledger_transactions (id,type,currency,source_type,source_id,description,"
                + "created_by_actor) VALUES ('ltx_soa_new','PAYMENT_CAPTURE','EGP','PAYMENT',"
                + "'pay_soa_new','newest','t');"
                + "INSERT INTO ledger_entries (id,transaction_id,account_id,direction,amount_minor,"
                + "currency,account_code,line_no,posted_at) VALUES "
                + "('len_soa_newa','ltx_soa_new','acct_psp_clearing','DEBIT',1000,'EGP',"
                + "'PSP_CLEARING',1,'2026-01-08T00:00:00Z'),"
                + "('len_soa_newb','ltx_soa_new','acct_merchant_payable','CREDIT',1000,'EGP',"
                + "'MERCHANT_PAYABLE',2,'2026-01-08T00:00:00Z')");

        LedgerController.EntriesPage second = page("PSP_CLEARING", 2, first.nextCursor());

        assertThat(second.items()).extracting(LedgerController.LedgerEntryView::id)
                .as("the cursor names a position, so the new row does not push anything onto it")
                .containsExactly("len_soa_5a", "len_soa_4a");

        assertThat(page("PSP_CLEARING", 3, null).items())
                .extracting(LedgerController.LedgerEntryView::id)
                .as("a fresh walk does see the newcomer, on page one")
                .containsExactly("len_soa_newa", "len_soa_7a", "len_soa_6a");
    }

    @Test
    @DisplayName("An invalid cursor is a clean 400, never a silently ignored parameter")
    void invalidCursorIsRefused() {
        assertThatThrownBy(() -> page("PSP_CLEARING", 2, "not-a-real-cursor"))
                .hasMessageContaining("cursor");
        assertThatThrownBy(() -> page("PSP_CLEARING", 2, "!!!not base64!!!"))
                .hasMessageContaining("cursor");
        // A well-formed base64 payload that is not `instant|id` is equally refused.
        assertThatThrownBy(() -> page("PSP_CLEARING", 2,
                java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString("nonsense".getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .hasMessageContaining("cursor");
    }

    @Test
    @DisplayName("An empty account is an empty page, not an error and not a null cursor loop")
    void emptyAccountIsAnEmptyPage() {
        LedgerController.EntriesPage page = page("PSP_FEE_EXPENSE", 10, null);
        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("An unknown account code is a 404, not an empty statement")
    void unknownAccountIsNotAnEmptyStatement() {
        // The endpoint returned `200 {"items":[]}` for an account code that does not exist. That
        // is the worst possible answer here: an auditor asking for a mistyped account is told the
        // account is empty and concludes nothing was ever posted. `GET /accounts/{code}` already
        // refused the same code, so this also removes an inconsistency between two sibling routes.
        assertThatThrownBy(() -> page("NO_SUCH_ACCOUNT", 5, null))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("NO_SUCH_ACCOUNT");
    }

    @Test
    @DisplayName("The page size cap is the documented 200, and exceeding it is a 400")
    void limitCapMatchesTheContract() {
        assertThat(page("PSP_CLEARING", 200, null).items())
                .as("200 is allowed")
                .hasSize(ENTRY_COUNT);
        assertThatThrownBy(() -> page("PSP_CLEARING", 201, null))
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> page("PSP_CLEARING", 0, null))
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> page("PSP_CLEARING", -1, null))
                .hasMessageContaining("limit");
    }

    @Test
    @DisplayName("Pagination is scoped to the account that was asked for")
    void pagesAreScopedToOneAccount() {
        List<String> clearing = page("PSP_CLEARING", ENTRY_COUNT, null).items().stream()
                .map(LedgerController.LedgerEntryView::id).toList();
        List<String> payable = page("MERCHANT_PAYABLE", ENTRY_COUNT, null).items().stream()
                .map(LedgerController.LedgerEntryView::id).toList();

        assertThat(clearing).allSatisfy(id -> assertThat(id).endsWith("a"));
        assertThat(payable).allSatisfy(id -> assertThat(id).endsWith("b"));
        assertThat(clearing).doesNotContainAnyElementsOf(payable);
    }

    // ------------------------------------------------------------------ helpers

    private LedgerController.EntriesPage page(String code, int limit, String cursor) {
        return controller.entries(code, cursor, limit);
    }

    /**
     * Runs one statement <i>in a transaction</i>, committing afterwards.
     *
     * <p>The explicit commit is not decoration. Since V2, {@code tg_txn_header_balances} is a
     * deferred constraint trigger on {@code ledger_transactions}: a header committed on its own is
     * a POSTED transaction with no entries and is refused, which is the invariant this project
     * wants and which this fixture's auto-commit helper used to rely on the absence of. A
     * statement of account that spans a transaction is precisely what is being asserted here, so
     * it is the right place for the pairing to be required rather than worked around.
     */
    private void exec(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute(sql);
            }
            c.commit();
        } finally {
            // Rolled back implicitly on close when the statement threw, which is what lets the
            // negative tests assert that nothing was persisted.
        }
    }
}
