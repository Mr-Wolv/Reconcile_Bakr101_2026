-- Adversarial probe for ledger invariant L2:
-- "A transaction has exactly one currency. Mixed-currency transactions are impossible."
-- Enforced by: "ledger_transactions.currency is a single column"
--                + fn_txn_balances' COUNT(DISTINCT currency) > 1 check on ledger_entries
--
-- The claim is that this is impossible. Test it from outside the application.

\set ON_ERROR_STOP off
\pset pager off

-- A balanced transaction whose HEADER says EGP but whose ENTRIES are all USD.
BEGIN;
INSERT INTO ledger_transactions
    (id, type, currency, source_type, source_id, description, created_by_actor)
VALUES
    ('ltx_probe_l2', 'PAYMENT_CAPTURE', 'EGP', 'PAYMENT', 'pay_probe_l2',
     'probe: header EGP, entries USD', 'probe');

INSERT INTO ledger_entries
    (id, transaction_id, account_id, direction, amount_minor, currency, account_code, line_no)
VALUES
    ('len_probe_l2_a', 'ltx_probe_l2', 'acct_psp_clearing', 'DEBIT',  10000, 'USD', 'PSP_CLEARING', 1),
    ('len_probe_l2_b', 'ltx_probe_l2', 'acct_merchant_payable', 'CREDIT', 9700, 'USD', 'MERCHANT_PAYABLE', 2),
    ('len_probe_l2_c', 'ltx_probe_l2', 'acct_platform_fee_rev', 'CREDIT',  300, 'USD', 'PLATFORM_FEE_REVENUE', 3);
COMMIT;

\echo '--- RESULT: did the L2 violation commit? ---'
SELECT id, currency AS header_currency FROM ledger_transactions WHERE id = 'ltx_probe_l2';
SELECT DISTINCT currency AS entry_currency FROM ledger_entries WHERE transaction_id = 'ltx_probe_l2';
