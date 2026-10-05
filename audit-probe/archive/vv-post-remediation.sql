-- Post-remediation adversarial invariant suite.
--
-- Runs against a database migrated from the current V1__baseline.sql. Every scenario below
-- bypasses the application: it talks to PostgreSQL directly, so a pass means the DATABASE refuses
-- the violation, not that some Java layer happened to notice.
--
-- Read the output as: a line beginning ERROR is the database refusing, which is the pass. A block
-- that reaches its COMMIT marker committed, which is also a pass when the block is a positive
-- control and a FAILURE when it is an attack.

\set ON_ERROR_STOP off
\pset pager off

-- =====================================================================
-- L2: every entry's currency must equal its transaction's currency.
-- The reported defect: the deferred trigger only noticed entries that disagreed with EACH OTHER,
-- so a transaction whose entries were unanimously USD under an EGP header committed cleanly.
-- =====================================================================
\echo '=== L2a: EGP header, all-USD entries (the exact reported violation) ==='
BEGIN;
  INSERT INTO ledger_transactions (id, type, currency, source_type, source_id, description, created_by_actor)
       VALUES ('t_l2a','PAYMENT_CAPTURE','EGP','SYSTEM','l2a','probe','vv');
  INSERT INTO ledger_entries (id, transaction_id, account_id, account_code, direction, amount_minor, currency, line_no)
       VALUES ('e_l2a1','t_l2a',(SELECT id FROM ledger_accounts WHERE code='PSP_CLEARING'),'PSP_CLEARING','DEBIT',100,'USD',1),
              ('e_l2a2','t_l2a',(SELECT id FROM ledger_accounts WHERE code='MERCHANT_PAYABLE'),'MERCHANT_PAYABLE','CREDIT',100,'USD',2);
COMMIT;
\echo '  ^^ if no ERROR above, L2 IS BROKEN';

\echo '=== L2b: USD header, one EGP entry among USD entries ==='
BEGIN;
  INSERT INTO ledger_transactions (id, type, currency, source_type, source_id, description, created_by_actor)
       VALUES ('t_l2b','PAYMENT_CAPTURE','USD','SYSTEM','l2b','probe','vv');
  INSERT INTO ledger_entries (id, transaction_id, account_id, account_code, direction, amount_minor, currency, line_no)
       VALUES ('e_l2b1','t_l2b',(SELECT id FROM ledger_accounts WHERE code='PSP_CLEARING'),'PSP_CLEARING','DEBIT',100,'USD',1),
              ('e_l2b2','t_l2b',(SELECT id FROM ledger_accounts WHERE code='MERCHANT_PAYABLE'),'MERCHANT_PAYABLE','CREDIT',40,'EGP',2);
COMMIT;
\echo '  ^^ if no ERROR above, L2 IS BROKEN';

\echo '=== L2c: USD header, single wrong-currency entry ==='
BEGIN;
  INSERT INTO ledger_transactions (id, type, currency, source_type, source_id, description, created_by_actor)
       VALUES ('t_l2c','PAYMENT_CAPTURE','USD','SYSTEM','l2c','probe','vv');
  INSERT INTO ledger_entries (id, transaction_id, account_id, account_code, direction, amount_minor, currency, line_no)
       VALUES ('e_l2c1','t_l2c',(SELECT id FROM ledger_accounts WHERE code='PSP_CLEARING'),'PSP_CLEARING','DEBIT',100,'EGP',1);
COMMIT;
\echo '  ^^ if no ERROR above, L2 IS BROKEN';

\echo '=== L2d: POSITIVE CONTROL, matching currency must COMMIT ==='
BEGIN;
  INSERT INTO ledger_transactions (id, type, currency, source_type, source_id, description, created_by_actor)
       VALUES ('t_l2d','PAYMENT_CAPTURE','EGP','SYSTEM','l2d','probe','vv');
  INSERT INTO ledger_entries (id, transaction_id, account_id, account_code, direction, amount_minor, currency, line_no)
       VALUES ('e_l2d1','t_l2d',(SELECT id FROM ledger_accounts WHERE code='PSP_CLEARING'),'PSP_CLEARING','DEBIT',100,'EGP',1),
              ('e_l2d2','t_l2d',(SELECT id FROM ledger_accounts WHERE code='MERCHANT_PAYABLE'),'MERCHANT_PAYABLE','CREDIT',100,'EGP',2);
COMMIT;
\echo '  L2d committed (expected)';

-- =====================================================================
-- NEW: ledger_entries.account_code is a denormalised copy that reconciliation's expected view
-- trusts. Before remediation it was unconstrained, so an entry could name a code that does not
-- exist, or contradict its own account_id, and the expected view would read the wrong row.
-- =====================================================================
\echo '=== NEW-1: account_code that names no account ==='
BEGIN;
  INSERT INTO ledger_transactions (id, type, currency, source_type, source_id, description, created_by_actor)
       VALUES ('t_n1','PAYMENT_CAPTURE','EGP','SYSTEM','n1','probe','vv');
  INSERT INTO ledger_entries (id, transaction_id, account_id, account_code, direction, amount_minor, currency, line_no)
       VALUES ('e_n1','t_n1',(SELECT id FROM ledger_accounts WHERE code='PSP_CLEARING'),'NO_SUCH_ACCOUNT','DEBIT',100,'EGP',1);
COMMIT;
\echo '  ^^ if no ERROR above, account_code IS UNCONSTRAINED';

\echo '=== NEW-2: account_code contradicting its own account_id ==='
BEGIN;
  INSERT INTO ledger_transactions (id, type, currency, source_type, source_id, description, created_by_actor)
       VALUES ('t_n2','PAYMENT_CAPTURE','EGP','SYSTEM','n2','probe','vv');
  INSERT INTO ledger_entries (id, transaction_id, account_id, account_code, direction, amount_minor, currency, line_no)
       VALUES ('e_n2','t_n2',(SELECT id FROM ledger_accounts WHERE code='PSP_CLEARING'),'MERCHANT_AVAILABLE','DEBIT',100,'EGP',1);
COMMIT;
\echo '  ^^ if no ERROR above, account_code IS UNCONSTRAINED';

-- =====================================================================
-- L1: a transaction must balance at COMMIT.
-- =====================================================================
\echo '=== L1a: single debit, unbalanced at COMMIT ==='
BEGIN;
  INSERT INTO ledger_transactions (id, type, currency, source_type, source_id, description, created_by_actor)
       VALUES ('t_l1a','PAYMENT_CAPTURE','EGP','SYSTEM','l1a','probe','vv');
  INSERT INTO ledger_entries (id, transaction_id, account_id, account_code, direction, amount_minor, currency, line_no)
       VALUES ('e_l1a','t_l1a',(SELECT id FROM ledger_accounts WHERE code='PSP_CLEARING'),'PSP_CLEARING','DEBIT',100,'EGP',1);
COMMIT;
\echo '  ^^ if no ERROR above, L1 IS BROKEN';

\echo '=== L1b: debits != credits at COMMIT ==='
BEGIN;
  INSERT INTO ledger_transactions (id, type, currency, source_type, source_id, description, created_by_actor)
       VALUES ('t_l1b','PAYMENT_CAPTURE','EGP','SYSTEM','l1b','probe','vv');
  INSERT INTO ledger_entries (id, transaction_id, account_id, account_code, direction, amount_minor, currency, line_no)
       VALUES ('e_l1b1','t_l1b',(SELECT id FROM ledger_accounts WHERE code='PSP_CLEARING'),'PSP_CLEARING','DEBIT',100,'EGP',1),
              ('e_l1b2','t_l1b',(SELECT id FROM ledger_accounts WHERE code='MERCHANT_PAYABLE'),'MERCHANT_PAYABLE','CREDIT',60,'EGP',2);
COMMIT;
\echo '  ^^ if no ERROR above, L1 IS BROKEN';

\echo '=== L1c: POSITIVE CONTROL, balanced must COMMIT ==='
BEGIN;
  INSERT INTO ledger_transactions (id, type, currency, source_type, source_id, description, created_by_actor)
       VALUES ('t_l1c','PAYMENT_CAPTURE','EGP','SYSTEM','l1c','probe','vv');
  INSERT INTO ledger_entries (id, transaction_id, account_id, account_code, direction, amount_minor, currency, line_no)
       VALUES ('e_l1c1','t_l1c',(SELECT id FROM ledger_accounts WHERE code='PSP_CLEARING'),'PSP_CLEARING','DEBIT',100,'EGP',1),
              ('e_l1c2','t_l1c',(SELECT id FROM ledger_accounts WHERE code='MERCHANT_PAYABLE'),'MERCHANT_PAYABLE','CREDIT',100,'EGP',2);
COMMIT;
\echo '  L1c committed (expected)';

-- =====================================================================
-- L3: amount_minor strictly positive; direction carries the sign.
-- =====================================================================
\echo '=== L3a: zero amount ==='
BEGIN;
  INSERT INTO ledger_transactions (id, type, currency, source_type, source_id, description, created_by_actor)
       VALUES ('t_l3a','PAYMENT_CAPTURE','EGP','SYSTEM','l3a','probe','vv');
  INSERT INTO ledger_entries (id, transaction_id, account_id, account_code, direction, amount_minor, currency, line_no)
       VALUES ('e_l3a','t_l3a',(SELECT id FROM ledger_accounts WHERE code='PSP_CLEARING'),'PSP_CLEARING','DEBIT',0,'EGP',1);
COMMIT;
\echo '  ^^ if no ERROR above, L3 IS BROKEN';

\echo '=== L3b: negative amount ==='
BEGIN;
  INSERT INTO ledger_transactions (id, type, currency, source_type, source_id, description, created_by_actor)
       VALUES ('t_l3b','PAYMENT_CAPTURE','EGP','SYSTEM','l3b','probe','vv');
  INSERT INTO ledger_entries (id, transaction_id, account_id, account_code, direction, amount_minor, currency, line_no)
       VALUES ('e_l3b','t_l3b',(SELECT id FROM ledger_accounts WHERE code='PSP_CLEARING'),'PSP_CLEARING','DEBIT',-100,'EGP',1);
COMMIT;
\echo '  ^^ if no ERROR above, L3 IS BROKEN';

-- =====================================================================
-- L4 / L5: posted history is immutable. Now that a row legitimately exists, these must bite.
-- =====================================================================
\echo '=== L4: UPDATE a posted entry ==='
UPDATE ledger_entries SET amount_minor = 999999 WHERE id = 'e_l1c1';
\echo '=== L5: DELETE a posted entry ==='
DELETE FROM ledger_entries WHERE id = 'e_l1c1';
\echo '=== L4b: UPDATE a posted transaction header ==='
UPDATE ledger_transactions SET description = 'tampered' WHERE id = 't_l1c';
\echo '=== L4c: DELETE a posted transaction header ==='
DELETE FROM ledger_transactions WHERE id = 't_l1c';

-- =====================================================================
-- L10: the same source cannot post twice.
-- =====================================================================
\echo '=== L10: POSITIVE CONTROL, first posting for a source must COMMIT ==='
BEGIN;
  INSERT INTO ledger_transactions (id, type, currency, source_type, source_id, description, created_by_actor)
       VALUES ('t_l10','PAYMENT_CAPTURE','EGP','SYSTEM','l10','probe','vv');
  INSERT INTO ledger_entries (id, transaction_id, account_id, account_code, direction, amount_minor, currency, line_no)
       VALUES ('e_l10a','t_l10',(SELECT id FROM ledger_accounts WHERE code='PSP_CLEARING'),'PSP_CLEARING','DEBIT',100,'EGP',1),
              ('e_l10b','t_l10',(SELECT id FROM ledger_accounts WHERE code='MERCHANT_PAYABLE'),'MERCHANT_PAYABLE','CREDIT',100,'EGP',2);
COMMIT;
\echo '  L10 first posting committed (expected)';

\echo '=== L10b: second posting for the same source must be refused ==='
BEGIN;
  INSERT INTO ledger_transactions (id, type, currency, source_type, source_id, description, created_by_actor)
       VALUES ('t_l10b','PAYMENT_CAPTURE','EGP','SYSTEM','l10','probe','vv');
COMMIT;
\echo '  ^^ if no ERROR above, L10 IS BROKEN';

-- =====================================================================
-- L6: a transaction may be reversed at most once (ux_ledger_reversal).
-- =====================================================================
\echo '=== L6: POSITIVE CONTROL, the first reversal must COMMIT ==='
BEGIN;
  INSERT INTO ledger_transactions (id, type, currency, source_type, source_id, description, created_by_actor, reversal_of_transaction_id, reversal_reason)
       VALUES ('t_rev1','REVERSAL','EGP','SYSTEM','rev1','probe','vv','t_l1c','probe reversal');
COMMIT;
\echo '  L6 first reversal committed (expected)';

\echo '=== L6b: a second reversal of the same transaction must be refused ==='
BEGIN;
  INSERT INTO ledger_transactions (id, type, currency, source_type, source_id, description, created_by_actor, reversal_of_transaction_id, reversal_reason)
       VALUES ('t_rev2','REVERSAL','EGP','SYSTEM','rev2','probe','vv','t_l1c','probe reversal');
COMMIT;
\echo '  ^^ if no ERROR above, L6 IS BROKEN';

-- =====================================================================
-- Final state. Everything above that was supposed to be refused must have left nothing behind.
-- =====================================================================
\echo '=== committed transactions (expect exactly the 4 positive controls) ==='
SELECT id, type, currency FROM ledger_transactions ORDER BY id;

\echo '=== L2 violations across committed rows (must be 0) ==='
SELECT count(*) AS l2_violations
  FROM ledger_entries e JOIN ledger_transactions t ON t.id = e.transaction_id
 WHERE e.currency <> t.currency;

\echo '=== account_code violations across committed rows (must be 0) ==='
SELECT count(*) AS account_code_violations
  FROM ledger_entries e JOIN ledger_accounts a ON a.id = e.account_id
 WHERE e.account_code <> a.code;

-- =====================================================================
-- L7: drift must be VISIBLE, and the projection is written by the application.
--
-- The L1 trigger validates a transaction's balance; it does not maintain `account_balances`.
-- LedgerWriter updates the projection inside the same application transaction, so posting
-- entries with raw SQL above deliberately leaves the projection behind. That is not a defect —
-- it is what F-35 describes — and the property worth proving is the one the project claims:
-- the drift is DETECTED, not silent. The query below is the verifier's own, copied from
-- LedgerService, and it must now report the accounts this file moved.
--
-- A clean database with zero rows here is proved separately, by driving a real posting through
-- the HTTP API and reading /health.
-- =====================================================================
\echo '=== L7: the verifier must now SEE the drift this file created (rows expected, not 0) ==='
SELECT a.code, b.currency, b.balance_minor AS projected,
       COALESCE(SUM(CASE WHEN e.direction = a.normal_side
                         THEN e.amount_minor ELSE -e.amount_minor END), 0) AS recomputed,
       b.entry_count AS projected_count, COUNT(e.id) AS actual_count
  FROM account_balances b
  JOIN ledger_accounts a ON a.id = b.account_id
  LEFT JOIN ledger_entries e ON e.account_id = b.account_id AND e.currency = b.currency
 GROUP BY a.code, a.normal_side, b.account_id, b.currency, b.balance_minor, b.entry_count
HAVING b.balance_minor <> COALESCE(SUM(CASE WHEN e.direction = a.normal_side
                                       THEN e.amount_minor ELSE -e.amount_minor END), 0)
    OR b.entry_count <> COUNT(e.id)
 ORDER BY a.code;
