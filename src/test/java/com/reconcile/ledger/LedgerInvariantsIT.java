package com.reconcile.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconcile.support.SharedPostgres;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
 * Proves, against a real PostgreSQL 18, that the financial invariants are properties of the
 * <b>database</b> and not merely of the Java that is supposed to write to it.
 *
 * <p>Every statement here is raw JDBC. The application is deliberately bypassed, because the claim
 * under test is precisely that a bypass cannot break the invariants — see ADR-0004. A test that went
 * through the repositories would only prove that the repositories behave, which is a weaker and
 * less interesting claim.
 *
 * <p>Maps to rows {@code I-MIG-01}, {@code I-LED-03}, {@code I-LED-04}, {@code I-LED-05},
 * {@code I-LED-06} and {@code I-PAYOUT-02}.
 */
@SpringBootTest
class LedgerInvariantsIT {


    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedPostgres::jdbcUrl);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
    }

    @Autowired
    DataSource dataSource;

    @BeforeEach
    void reset() throws SQLException {
        // The chart of accounts (ledger_accounts) is never truncated. Everything a test creates is
        // removed so tests cannot influence each other through leftover rows - including the balance
        // projection, which must then be re-seeded exactly as V1__baseline.sql seeds it. Truncating
        // it without re-seeding would leave the projection empty, and an UPDATE against a missing
        // row silently matches nothing - which is precisely the hole L8's trigger cannot guard
        // against, and precisely why the migration seeds these rows eagerly.
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
                    SELECT a.id, c.code
                      FROM ledger_accounts a
                     CROSS JOIN currency_units c
                     WHERE c.active
                    """);
        }
    }

    // ---------------------------------------------------------------- I-MIG-01

    @Test
    @DisplayName("I-MIG-01: V1__baseline.sql applied cleanly - the context would not start otherwise")
    void baselineMigrationApplied() throws SQLException {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT COUNT(*) FROM flyway_schema_history WHERE success")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isPositive();
        }
        assertThat(tableExists("ledger_transactions")).isTrue();
        assertThat(tableExists("payout_lines")).isTrue();
        assertThat(tableExists("reconciliation_cases")).isTrue();
    }

    @Test
    @DisplayName("The chart is seeded and every account has a balance row per currency")
    void chartAndBalancesAreSeeded() throws SQLException {
        assertThat(count("SELECT COUNT(*) FROM ledger_accounts")).isEqualTo(5L);
        // 5 accounts x 4 currencies. Seeding eagerly is what stops the L8 trigger being side-stepped
        // by an update against a row that does not exist.
        assertThat(count("SELECT COUNT(*) FROM account_balances")).isEqualTo(20L);
    }

    // ---------------------------------------------------------------- F-03

    /**
     * The hole the previous audit found, kept as a regression test.
     *
     * <p>Under V1 this exact statement committed. The ledger API then reported the transaction as
     * POSTED with an empty entry list: a financial record claiming to be posted while representing
     * no money movement at all. It went unnoticed because the balance trigger fires from entry
     * insertion, and there were no entries.
     */
    @Test
    @DisplayName("F-03: a POSTED header with no entries is refused at COMMIT by the database")
    void aPostedHeaderWithNoEntriesIsRefused() throws SQLException {
        assertThatThrownBy(() -> exec(
                "INSERT INTO ledger_transactions (id,type,currency,source_type,source_id,description,"
                        + "created_by_actor) VALUES ('ltx_zerofill','PAYMENT_CAPTURE','EGP','PAYMENT',"
                        + "'pay_zerofill','adversarial: header with no entries','probe')"))
                .as("the trigger is DEFERRABLE INITIALLY DEFERRED, so it fires when the statement's "
                        + "implicit transaction commits")
                .hasMessageContaining("at least 2 are required");

        assertThat(count("SELECT COUNT(*) FROM ledger_transactions WHERE id = 'ltx_zerofill'"))
                .as("nothing may be left behind by a refused posting")
                .isZero();
    }

    @Test
    @DisplayName("F-03: a one-entry transaction is refused, and so is an unbalanced one")
    void malformedTransactionsAreRefused() throws SQLException {
        // Each attack below is ONE transaction carrying the header and its entries together.
        // Since V2 tg_txn_header_balances refuses a header committed on its own, so a header
        // written in a separate exec() would be rejected before a single entry exists - and both
        // assertions would then pass without ever reaching the branch each one names.
        assertThatThrownBy(() -> exec(
                "INSERT INTO ledger_transactions (id,type,currency,source_type,source_id,description,"
                        + "created_by_actor) VALUES ('ltx_one','PAYMENT_CAPTURE','EGP','PAYMENT','pay_one',"
                        + "'one entry','probe');"
                        + "INSERT INTO ledger_entries (id,transaction_id,account_id,direction,amount_minor,"
                        + "currency,account_code,line_no) VALUES "
                        + "('len_one','ltx_one','acct_psp_clearing','DEBIT',10000,'EGP','PSP_CLEARING',1)"))
                .hasMessageContaining("at least 2 are required");

        assertThatThrownBy(() -> exec(
                "INSERT INTO ledger_transactions (id,type,currency,source_type,source_id,description,"
                        + "created_by_actor) VALUES ('ltx_unbal','PAYMENT_CAPTURE','EGP','PAYMENT',"
                        + "'pay_unbal','unbalanced','probe');"
                        + "INSERT INTO ledger_entries (id,transaction_id,account_id,direction,amount_minor,"
                        + "currency,account_code,line_no) VALUES "
                        + "('len_ub_d','ltx_unbal','acct_psp_clearing','DEBIT',10000,'EGP','PSP_CLEARING',1),"
                        + "('len_ub_c','ltx_unbal','acct_merchant_payable','CREDIT',9999,'EGP',"
                        + "'MERCHANT_PAYABLE',2)"))
                .hasMessageContaining("does not balance");

        // The control matters as much as the attack: the point is that the database refuses the
        // shapes that cannot be money, not that it has stopped accepting anything.
        exec("INSERT INTO ledger_transactions (id,type,currency,source_type,source_id,description,"
                + "created_by_actor) VALUES ('ltx_ok','PAYMENT_CAPTURE','EGP','PAYMENT','pay_ok',"
                + "'the minimum legal transaction','probe');"
                + "INSERT INTO ledger_entries (id,transaction_id,account_id,direction,amount_minor,"
                + "currency,account_code,line_no) VALUES "
                + "('len_ok_d','ltx_ok','acct_psp_clearing','DEBIT',10000,'EGP','PSP_CLEARING',1),"
                + "('len_ok_c','ltx_ok','acct_merchant_payable','CREDIT',10000,'EGP',"
                + "'MERCHANT_PAYABLE',2)");

        assertThat(count("SELECT COUNT(*) FROM ledger_transactions t WHERE t.state = 'POSTED' "
                + "AND (SELECT COUNT(*) FROM ledger_entries e WHERE e.transaction_id = t.id) < 2"))
                .as("the invariant as a query, independent of how it is enforced")
                .isZero();
    }

    // ---------------------------------------------------------------- I-LED-04

    @Test
    @DisplayName("I-LED-04: UPDATE and DELETE on ledger history are refused, even from raw SQL")
    void ledgerHistoryIsImmutable() throws SQLException {
        postBalancedCapture();

        assertThatThrownBy(() -> exec(
                "UPDATE ledger_transactions SET description = 'tampered' WHERE id = 'ltx_cap_1'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> exec("DELETE FROM ledger_entries WHERE id = 'len_c3'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> exec("DELETE FROM ledger_transactions WHERE id = 'ltx_cap_1'"))
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("I-LED-04: the audit trail and state history are append-only too")
    void auditAndHistoryAreImmutable() throws SQLException {
        exec("INSERT INTO audit_events (id, actor_type, actor_id, action, entity_type) "
                + "VALUES ('aud_1','SYSTEM','t','LEDGER_POSTED','LEDGER_TRANSACTION')");
        assertThatThrownBy(() -> exec("UPDATE audit_events SET action = 'TAMPERED'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> exec("DELETE FROM audit_events"))
                .hasMessageContaining("append-only");
    }

    // ---------------------------------------------------------------- I-LED-06

    @Test
    @DisplayName("I-LED-06: an unbalanced transaction is refused at COMMIT by the deferred trigger")
    void unbalancedTransactionIsRefusedAtCommit() {
        // Two statements that are individually fine; only the COMMIT reveals the imbalance. This is
        // exactly why the trigger is DEFERRABLE - an immediate trigger would fail the first entry.
        assertThatThrownBy(() -> {
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                exec(c, "INSERT INTO ledger_transactions (id,type,currency,source_type,source_id,"
                        + "description,created_by_actor) VALUES ('ltx_bad','PAYMENT_CAPTURE','EGP',"
                        + "'PAYMENT','pay_bad','bad','t')");
                // Two entries, so this is a BALANCE failure and not a minimum-arity failure: the
                // header trigger rejects a one-entry transaction earlier in the commit, with a
                // different message, which would make this assertion pass for the wrong reason.
                exec(c, "INSERT INTO ledger_entries (id,transaction_id,account_id,direction,"
                        + "amount_minor,currency,account_code,line_no) VALUES "
                        + "('len_b1','ltx_bad','acct_psp_clearing','DEBIT',5000,'EGP','PSP_CLEARING',1),"
                        + "('len_b2','ltx_bad','acct_merchant_payable','CREDIT',4999,'EGP',"
                        + "'MERCHANT_PAYABLE',2)");
                c.commit();
            }
        }).hasMessageContaining("does not balance");
    }

    @Test
    @DisplayName("L2: an entry may not be denominated in a currency other than its transaction's")
    void entryCurrencyMustMatchTransactionCurrency() throws SQLException {
        // The case an independent audit found, and the one the old schema got wrong. All three
        // entries agree with EACH OTHER, so the deferred trigger's "these entries mix currencies"
        // branch was FALSE, the balance check passed, and the transaction committed: an EGP
        // transaction carrying three USD entries, with the money moved in the wrong currency and
        // the header claiming otherwise. Only fk_entry_txn_currency makes it impossible.
        //
        // Note WHERE it now fails: on the offending INSERT, not at COMMIT. There is nothing to wait
        // for - the row is wrong the moment it is written - so the write is refused where it
        // happened. That is strictly earlier and strictly more precise than the deferred check.
        assertThatThrownBy(() -> {
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                exec(c, "INSERT INTO ledger_transactions (id,type,currency,source_type,source_id,"
                        + "description,created_by_actor) VALUES ('ltx_hdr','PAYMENT_CAPTURE','EGP',"
                        + "'PAYMENT','pay_hdr','header says EGP, entries say USD','t')");
                exec(c, "INSERT INTO ledger_entries (id,transaction_id,account_id,direction,"
                        + "amount_minor,currency,account_code,line_no) VALUES "
                        + "('len_h1','ltx_hdr','acct_psp_clearing','DEBIT',10000,'USD','PSP_CLEARING',1),"
                        + "('len_h2','ltx_hdr','acct_merchant_payable','CREDIT',9700,'USD','MERCHANT_PAYABLE',2),"
                        + "('len_h3','ltx_hdr','acct_platform_fee_rev','CREDIT',300,'USD','PLATFORM_FEE_REVENUE',3)");
                c.commit();
            }
        }).hasMessageContaining("fk_entry_txn_currency");

        assertThat(count("SELECT COUNT(*) FROM ledger_transactions WHERE id = 'ltx_hdr'"))
                .as("nothing survives: the whole posting is rolled back, not half-applied")
                .isZero();
    }

    @Test
    @DisplayName("L2: a transaction in any supported currency is still legal - the rule is "
            + "consistency, not EGP")
    void internallyConsistentNonEgpTransactionIsAllowed() throws SQLException {
        // The guard against over-correcting. fk_entry_txn_currency must pin an entry to ITS OWN
        // transaction's currency, not to EGP and not to the currency of the first entry. A USD
        // posting of USD entries is exactly as valid as an EGP one, and a rule that refused it
        // would be a bug wearing the costume of an invariant.
        // Header and entries in one script, hence one transaction. Since V2,
        // tg_txn_header_balances refuses a header committed on its own, and two exec() calls are
        // two transactions. A posting is one business fact and belongs in one transaction.
        exec("INSERT INTO ledger_transactions (id,type,currency,source_type,source_id,description,"
                + "created_by_actor) VALUES ('ltx_usd','PAYMENT_CAPTURE','USD','PAYMENT',"
                + "'pay_usd','a USD capture','t');"
                + "INSERT INTO ledger_entries (id,transaction_id,account_id,direction,amount_minor,"
                + "currency,account_code,line_no) VALUES "
                + "('len_u1','ltx_usd','acct_psp_clearing','DEBIT',10000,'USD','PSP_CLEARING',1),"
                + "('len_u2','ltx_usd','acct_merchant_payable','CREDIT',10000,'USD','MERCHANT_PAYABLE',2)");

        assertThat(count("SELECT COUNT(*) FROM ledger_entries WHERE transaction_id = 'ltx_usd'"))
                .isEqualTo(2L);

        // Move the projection too, because that is what a real posting does and what L7 compares
        // against. Writing entries without it is exactly the drift the verifier exists to report,
        // so the assertion below only means something once the projection is written too.
        exec("UPDATE account_balances SET balance_minor = balance_minor + 10000, entry_count = 1, "
                + "last_entry_at = now() WHERE account_id = 'acct_psp_clearing' AND currency = 'USD'");
        exec("UPDATE account_balances SET balance_minor = balance_minor + 10000, entry_count = 1, "
                + "last_entry_at = now() WHERE account_id = 'acct_merchant_payable' AND currency = 'USD'");

        assertThat(verifierFindings())
                .as("a currency-consistent USD transaction satisfies L1 exactly as an EGP one does")
                .isZero();
    }

    @Test
    @DisplayName("L2: entries that disagree among themselves are refused too")
    void entriesMixingCurrenciesAreRefused() throws SQLException {
        // The shape the old deferred trigger was written for, kept because it is a real case and
        // because the trigger's `mixed` branch is retained as a backstop. The foreign key now
        // catches it first - entry two already names a currency its EGP transaction does not have -
        // so the constraint that fires is the one the schema documents as primary.
        postBalancedCapture();

        assertThatThrownBy(() -> exec("INSERT INTO ledger_entries (id,transaction_id,account_id,"
                + "direction,amount_minor,currency,account_code,line_no) VALUES "
                + "('len_m2','ltx_cap_1','acct_merchant_payable','CREDIT',9700,'USD','MERCHANT_PAYABLE',9)"))
                .hasMessageContaining("fk_entry_txn_currency");
    }

    @Test
    @DisplayName("A ledger entry's denormalised account_code is pinned to the account it names")
    void entryAccountCodeCannotDriftFromItsAccount() throws SQLException {
        // Found while probing the L2 fix, not by the original spec: ledger_entries carries a copy of
        // the account's code, and nothing tied that copy to the account the entry actually posts to.
        // A raw writer could therefore record money against MERCHANT_PAYABLE while labelling it
        // MERCHANT_AVAILABLE - and two readers trust the label. LedgerService.readReversalSource
        // mirrors a reversal from it, and ReconciliationService.captureLegs splits a capture's
        // gross/fee/net legs from it, resolving an absent leg to ZERO rather than to an error. A
        // drifted label would therefore move expected money to 0.00 and open a case blaming the
        // provider for a bug of ours. The database is where that stops being possible.
        postBalancedCapture();

        assertThatThrownBy(() -> exec("INSERT INTO ledger_entries (id,transaction_id,account_id,"
                + "direction,amount_minor,currency,account_code,line_no) VALUES "
                + "('len_drift','ltx_cap_1','acct_merchant_payable','CREDIT',100,'EGP',"
                + "'MERCHANT_AVAILABLE',9)"))
                .hasMessageContaining("fk_entry_account_code");
    }

    // ---------------------------------------------------------------- I-LED-03

    @Test
    @DisplayName("I-LED-03: the same business fact cannot be posted twice")
    void doublePostingIsRefused() throws SQLException {
        postBalancedCapture();

        assertThatThrownBy(() -> exec("INSERT INTO ledger_transactions (id,type,currency,"
                + "source_type,source_id,description,created_by_actor) VALUES "
                + "('ltx_dup','PAYMENT_CAPTURE','EGP','PAYMENT','pay_1','dup','t')"))
                .hasMessageContaining("ux_ledger_source");

        assertThat(count("SELECT COUNT(*) FROM ledger_transactions WHERE source_id = 'pay_1'"))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("L6: a second reversal is refused, but a reversal may itself be reversed")
    void reversalRulesHold() throws SQLException {
        postBalancedCapture();
        exec(reversal("ltx_r1", "ltx_cap_1"));

        assertThatThrownBy(() -> exec(reversal("ltx_r2", "ltx_cap_1")))
                .hasMessageContaining("ux_ledger_reversal");

        // The chain is legal, which is exactly why ux_ledger_source excludes the REVERSAL type.
        List<String> unmirrored = List.of(
                "('len_r4','ltx_r3','acct_psp_clearing','DEBIT',10000,'EGP','PSP_CLEARING',1)",
                "('len_r5','ltx_r3','acct_merchant_payable','CREDIT',9700,'EGP','MERCHANT_PAYABLE',2)",
                "('len_r6','ltx_r3','acct_platform_fee_rev','CREDIT',300,'EGP','PLATFORM_FEE_REVENUE',3)");
        exec("INSERT INTO ledger_transactions (id,type,currency,source_type,source_id,description,"
                + "reversal_of_transaction_id,reversal_reason,created_by_actor) VALUES "
                + "('ltx_r3','REVERSAL','EGP','PAYMENT','pay_1','r2','ltx_r1','withdrawn','t');"
                + "INSERT INTO ledger_entries (id,transaction_id,account_id,direction,amount_minor,"
                + "currency,account_code,line_no) VALUES " + String.join(",", unmirrored));
    }

    // ---------------------------------------------------------------- L8 / L11

    @Test
    @DisplayName("L8: an asset may not go negative, but a liability may (a clawback)")
    void assetNegativeRefusedLiabilityAllowed() throws SQLException {
        assertThatThrownBy(() -> exec("UPDATE account_balances SET balance_minor = -1 "
                + "WHERE account_id = 'acct_psp_clearing' AND currency = 'EGP'"))
                .hasMessageContaining("would go negative");

        exec("UPDATE account_balances SET balance_minor = -500 "
                + "WHERE account_id = 'acct_merchant_payable' AND currency = 'EGP'");
        assertThat(count("SELECT COUNT(*) FROM account_balances WHERE balance_minor = -500"))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("I-PAYOUT-02 / L11: a payment cannot be paid out twice")
    void paymentCannotBePaidOutTwice() throws SQLException {
        postBalancedCapture();
        postPayout();

        assertThatThrownBy(() -> exec("INSERT INTO payout_lines (id,payout_record_id,payment_id,"
                + "line_net_minor,line_no) VALUES ('pl_2','po_1','pay_1',9700,2)"))
                .hasMessageContaining("ux_payout_line_payment");
    }

    @Test
    @DisplayName("An EXECUTED payout must name the posting that moved the money")
    void executedPayoutNeedsItsPosting() {
        assertThatThrownBy(() -> exec("INSERT INTO payout_records (id,merchant_reference,currency,"
                + "total_net_minor,line_count,status,created_by_actor) "
                + "VALUES ('po_9','M','EGP',100,1,'EXECUTED','t')"))
                .hasMessageContaining("ck_payout_executed");
    }

    // ---------------------------------------------------------------- I-LED-05

    @Test
    @DisplayName("I-LED-05: the L7 verifier is silent on a consistent posting and names drift")
    void balanceVerifierDetectsCorruption() throws SQLException {
        postBalancedCapture();

        assertThat(verifierFindings()).isZero();

        // Corrupt the projection behind the application's back - exactly the shape of bug or
        // bad restore that the verifier exists to surface.
        exec("UPDATE account_balances SET balance_minor = balance_minor + 1 "
                + "WHERE account_id = 'acct_psp_clearing' AND currency = 'EGP'");

        assertThat(verifierFindings()).isEqualTo(1L);
        assertThat(verifierFindings()).as("it must name the account, not just report a count")
                .isPositive();
    }

    @Test
    @DisplayName("The documented capture -> settlement -> payout cycle unwinds the ledger")
    void fullCycleUnwindsTheLedger() throws SQLException {
        postBalancedCapture();
        exec("INSERT INTO ledger_transactions (id,type,currency,source_type,source_id,description,"
                + "created_by_actor) VALUES ('ltx_set_1','SETTLEMENT_RECEIVED','EGP',"
                + "'SETTLEMENT_RECORD','srec_1','settlement','t');"
                + "INSERT INTO ledger_entries (id,transaction_id,account_id,direction,amount_minor,"
                + "currency,account_code,line_no) VALUES "
                + "('len_s1','ltx_set_1','acct_platform_cash','DEBIT',10000,'EGP','PLATFORM_CASH',1),"
                + "('len_s2','ltx_set_1','acct_psp_clearing','CREDIT',10000,'EGP','PSP_CLEARING',2)");
        exec("UPDATE payments SET state = 'SETTLED', settled_at = now() WHERE id = 'pay_1'");
        applyProjection("acct_platform_cash", 10000);
        applyProjection("acct_psp_clearing", -10000);

        postPayout();
        applyProjection("acct_platform_cash", -9700);
        applyProjection("acct_merchant_payable", -9700);
        exec("UPDATE payments SET paid_out_at = now() WHERE id = 'pay_1'");

        assertThat(balanceOf("acct_psp_clearing")).isZero();
        assertThat(balanceOf("acct_merchant_payable")).isZero();
        assertThat(balanceOf("acct_platform_cash")).isEqualTo(300L);       // the platform's fee
        assertThat(balanceOf("acct_platform_fee_rev")).isEqualTo(300L);
        assertThat(verifierFindings()).isZero();
    }

    // ---------------------------------------------------------------- helpers

    /** One capture, written the way the service writes it: entries and projection together. */
    private void postBalancedCapture() throws SQLException {
        exec("INSERT INTO payments (id,merchant_reference,amount_minor,currency,platform_fee_minor,"
                + "net_amount_minor,fee_policy_id,state,provider,captured_at) VALUES "
                + "('pay_1','MERCH-77',10000,'EGP',300,9700,'fp_default','CAPTURED','SIMULATED_PSP',now())");
        exec("INSERT INTO ledger_transactions (id,type,currency,source_type,source_id,description,"
                + "created_by_actor) VALUES ('ltx_cap_1','PAYMENT_CAPTURE','EGP','PAYMENT','pay_1',"
                + "'capture','t');"
                + "INSERT INTO ledger_entries (id,transaction_id,account_id,direction,amount_minor,"
                + "currency,account_code,line_no) VALUES "
                + "('len_c1','ltx_cap_1','acct_psp_clearing','DEBIT',10000,'EGP','PSP_CLEARING',1),"
                + "('len_c2','ltx_cap_1','acct_merchant_payable','CREDIT',9700,'EGP','MERCHANT_PAYABLE',2),"
                + "('len_c3','ltx_cap_1','acct_platform_fee_rev','CREDIT',300,'EGP','PLATFORM_FEE_REVENUE',3)");
        applyProjection("acct_psp_clearing", 10000);
        applyProjection("acct_merchant_payable", 9700);
        applyProjection("acct_platform_fee_rev", 300);
    }

    /**
     * Moves the balance projection for exactly one freshly written ledger entry.
     *
     * <p>{@code entry_count} is not optional bookkeeping: the L7 verifier in
     * {@link #verifierFindings()} compares it against the real number of entries, so a helper that
     * adjusted only {@code balance_minor} would manufacture drift out of nothing. The delta may be
     * negative - the projection is stored in the account's normal direction, so an account's balance
     * falls when it is debited relative to its normal side.
     */
    private void applyProjection(String accountId, long deltaMinor) throws SQLException {
        exec("UPDATE account_balances SET balance_minor = balance_minor + " + deltaMinor
                + ", entry_count = entry_count + 1, last_entry_at = now()"
                + " WHERE account_id = '" + accountId + "' AND currency = 'EGP'");
    }

    private void postPayout() throws SQLException {
        exec("INSERT INTO ledger_transactions (id,type,currency,source_type,source_id,description,"
                + "created_by_actor) VALUES ('ltx_po_1','MERCHANT_PAYOUT','EGP','PAYOUT','po_1',"
                + "'payout','t');"
                + "INSERT INTO ledger_entries (id,transaction_id,account_id,direction,amount_minor,"
                + "currency,account_code,line_no) VALUES "
                + "('len_p1','ltx_po_1','acct_merchant_payable','DEBIT',9700,'EGP','MERCHANT_PAYABLE',1),"
                + "('len_p2','ltx_po_1','acct_platform_cash','CREDIT',9700,'EGP','PLATFORM_CASH',2)");
        exec("INSERT INTO payout_records (id,merchant_reference,currency,total_net_minor,line_count,"
                + "status,ledger_transaction_id,executed_at,created_by_actor) VALUES "
                + "('po_1','MERCH-77','EGP',9700,1,'EXECUTED','ltx_po_1',now(),'t')");
        exec("INSERT INTO payout_lines (id,payout_record_id,payment_id,line_net_minor,line_no) "
                + "VALUES ('pl_1','po_1','pay_1',9700,1)");
    }

    private String reversal(String id, String ofTransactionId) {
        return "INSERT INTO ledger_transactions (id,type,currency,source_type,source_id,description,"
                + "reversal_of_transaction_id,reversal_reason,created_by_actor) VALUES ('" + id
                + "','REVERSAL','EGP','PAYMENT','pay_1','reversal','" + ofTransactionId
                + "','test','t');"
                + "INSERT INTO ledger_entries (id,transaction_id,account_id,direction,amount_minor,"
                + "currency,account_code,line_no) VALUES "
                + "('len_" + id + "1','" + id + "','acct_psp_clearing','CREDIT',10000,'EGP','PSP_CLEARING',1),"
                + "('len_" + id + "2','" + id + "','acct_merchant_payable','DEBIT',9700,'EGP','MERCHANT_PAYABLE',2),"
                + "('len_" + id + "3','" + id + "','acct_platform_fee_rev','DEBIT',300,'EGP','PLATFORM_FEE_REVENUE',3)";
    }

    /** The exact verifier query documented in docs/spec/01-money-and-ledger.md §7. */
    private long verifierFindings() throws SQLException {
        String sql = """
                SELECT COUNT(*) FROM (
                  SELECT a.code
                    FROM account_balances b
                    JOIN ledger_accounts a ON a.id = b.account_id
                    LEFT JOIN ledger_entries e
                           ON e.account_id = b.account_id AND e.currency = b.currency
                   GROUP BY a.code, a.normal_side, b.account_id, b.currency, b.balance_minor, b.entry_count
                  HAVING b.balance_minor <> COALESCE(SUM(CASE WHEN e.direction = a.normal_side
                            THEN e.amount_minor ELSE -e.amount_minor END), 0)
                      OR b.entry_count <> COUNT(e.id)
                ) drift
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long balanceOf(String accountId) throws SQLException {
        return count("SELECT balance_minor FROM account_balances WHERE account_id = '" + accountId
                + "' AND currency = 'EGP'");
    }

    private boolean tableExists(String table) throws SQLException {
        return count("SELECT COUNT(*) FROM information_schema.tables WHERE table_name = '" + table
                + "'") > 0;
    }

    private long count(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * Runs a script of statements in one transaction and commits it.
     *
     * <p>Transactional because V2 makes {@code tg_txn_header_balances} a deferred constraint trigger
     * on {@code ledger_transactions}: a header committed without its entries is refused. Every
     * fixture here writes a header and its entries as one business fact, so they belong in one
     * transaction — and the tests that assert a refusal work precisely because the throw leaves the
     * whole script rolled back.
     */
    private void exec(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            exec(c, sql);
            c.commit();
        }
    }

    private void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            for (String statement : sql.split(";")) {
                if (!statement.isBlank()) {
                    s.execute(statement);
                }
            }
        }
    }
}
