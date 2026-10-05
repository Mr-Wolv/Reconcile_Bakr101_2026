-- Adversarial database probe — ledger invariants.
--
-- Runs against a clean PostgreSQL with V1 + V2 applied. Every statement is raw SQL: no Java, no
-- ORM, no application code path. If any of these refusals depends on the application, the
-- "database-enforced" claim in ADR-0004 and spec 01 §4 is false.
--
--   psql -f vv-ledger-invariants.sql                          # exit 0 iff every invariant held
--
-- ON HOW THIS PROBE REPORTS TRUTH
--
-- The previous audit's SQL probe was criticised for leaving error-stop off so that expected
-- constraint violations and unexpected SQL failures could coexist with exit 0, leaving a human to
-- interpret the output. That criticism was right, and it is not fixed here by simply turning
-- error-stop on: doing that stops the script at the FIRST attack, which is itself an attack, and
-- reports nothing about the other thirteen.
--
-- So the verdict is derived from observed state rather than from captured error text. Every attack
-- runs in its own transaction; a refused transaction leaves nothing behind. Section D then asks the
-- database whether the things that must not exist do not exist, and section E raises -- which, with
-- ON_ERROR_STOP=1, is a nonzero exit -- if the answer is no.
--
-- An error message is not evidence: "0A000 / EXECUTE of transaction commands is not implemented"
-- is a refusal, and so is "23514 / violates check constraint", and so is being killed by the
-- container. Only what survives in the tables counts.
--
-- The contract is symmetric on purpose. A probe that only proves that bad things fail proves
-- nothing if the database has simply stopped accepting anything, so section B asserts that the
-- legal shapes are still accepted.

-- Error-stop is deliberately OFF for section A. Every statement there is an attack that is
-- SUPPOSED to be refused, so stopping at the first one would report on one attack and say nothing
-- about the other thirteen. It is switched back on before section E, where a failure is a failure.
\set ON_ERROR_STOP off
\pset pager off

CREATE SCHEMA IF NOT EXISTS probe;
SET search_path TO probe, public;

\echo '=== A. attacks: each of these is its own transaction ==='
\echo '--- A1 zero-entry POSTED header (must NOT survive) ---'
BEGIN;
INSERT INTO ledger_transactions
    (id, state, type, currency, source_type, source_id, description, created_by_actor)
VALUES ('lt_zerofill', 'POSTED', 'PAYMENT_CAPTURE', 'EGP',
        'PAYMENT', 'pay_zerofill', 'adversarial: header with no entries', 'probe');
COMMIT;

\echo '--- A2 single-entry transaction (must NOT survive) ---'
BEGIN;
INSERT INTO ledger_transactions
    (id, state, type, currency, source_type, source_id, description, created_by_actor)
VALUES ('lt_one', 'POSTED', 'PAYMENT_CAPTURE', 'EGP',
        'PAYMENT', 'pay_one', 'adversarial: one entry', 'probe');
INSERT INTO ledger_entries
    (id, transaction_id, account_id, direction, amount_minor, currency, account_code, line_no)
SELECT 'le_one', 'lt_one', id, 'DEBIT', 10000, 'EGP', code, 1
  FROM ledger_accounts WHERE code = 'PSP_CLEARING';
COMMIT;

\echo '--- A3 unbalanced two-entry transaction (must NOT survive) ---'
BEGIN;
INSERT INTO ledger_transactions
    (id, state, type, currency, source_type, source_id, description, created_by_actor)
VALUES ('lt_unbal', 'POSTED', 'PAYMENT_CAPTURE', 'EGP',
        'PAYMENT', 'pay_unbal', 'adversarial: unbalanced', 'probe');
INSERT INTO ledger_entries
    (id, transaction_id, account_id, direction, amount_minor, currency, account_code, line_no)
SELECT 'le_unbal_d', 'lt_unbal', id, 'DEBIT', 10000, 'EGP', code, 1
  FROM ledger_accounts WHERE code = 'PSP_CLEARING';
INSERT INTO ledger_entries
    (id, transaction_id, account_id, direction, amount_minor, currency, account_code, line_no)
SELECT 'le_unbal_c', 'lt_unbal', id, 'CREDIT', 9999, 'EGP', code, 2
  FROM ledger_accounts WHERE code = 'MERCHANT_PAYABLE';
COMMIT;

\echo '--- A4 entry denominated in another currency (must NOT survive) ---'
BEGIN;
INSERT INTO ledger_transactions
    (id, state, type, currency, source_type, source_id, description, created_by_actor)
VALUES ('lt_fx', 'POSTED', 'PAYMENT_CAPTURE', 'EGP',
        'PAYMENT', 'pay_fx', 'adversarial: mixed currency', 'probe');
INSERT INTO ledger_entries
    (id, transaction_id, account_id, direction, amount_minor, currency, account_code, line_no)
SELECT 'le_fx', 'lt_fx', id, 'DEBIT', 10000, 'USD', code, 1
  FROM ledger_accounts WHERE code = 'PSP_CLEARING';
COMMIT;

\echo '--- A5 the same business fact posted twice (only the first may survive) ---'
-- Two separate transactions on purpose. Putting both posts in one transaction would prove nothing:
-- the duplicate would abort the whole thing and take the legal post with it, which is correct
-- behaviour but not the invariant. L10 is about a SECOND attempt to post an existing fact.
BEGIN;
INSERT INTO ledger_transactions
    (id, state, type, currency, source_type, source_id, description, created_by_actor)
VALUES ('lt_dup', 'POSTED', 'PAYMENT_CAPTURE', 'EGP',
        'PAYMENT', 'pay_dup', 'adversarial: first post', 'probe');
INSERT INTO ledger_entries
    (id, transaction_id, account_id, direction, amount_minor, currency, account_code, line_no)
SELECT 'le_dup_d1', 'lt_dup', id, 'DEBIT', 10000, 'EGP', code, 1
  FROM ledger_accounts WHERE code = 'PSP_CLEARING';
INSERT INTO ledger_entries
    (id, transaction_id, account_id, direction, amount_minor, currency, account_code, line_no)
SELECT 'le_dup_c1', 'lt_dup', id, 'CREDIT', 10000, 'EGP', code, 2
  FROM ledger_accounts WHERE code = 'MERCHANT_PAYABLE';
COMMIT;
BEGIN;
-- The second post of the same (source_type, source_id, type). L10.
INSERT INTO ledger_transactions
    (id, state, type, currency, source_type, source_id, description, created_by_actor)
VALUES ('lt_dup2', 'POSTED', 'PAYMENT_CAPTURE', 'EGP',
        'PAYMENT', 'pay_dup', 'adversarial: second post of the same fact', 'probe');
INSERT INTO ledger_entries
    (id, transaction_id, account_id, direction, amount_minor, currency, account_code, line_no)
SELECT 'le_dup_d2', 'lt_dup2', id, 'DEBIT', 10000, 'EGP', code, 1
  FROM ledger_accounts WHERE code = 'PSP_CLEARING';
INSERT INTO ledger_entries
    (id, transaction_id, account_id, direction, amount_minor, currency, account_code, line_no)
SELECT 'le_dup_c2', 'lt_dup2', id, 'CREDIT', 10000, 'EGP', code, 2
  FROM ledger_accounts WHERE code = 'MERCHANT_PAYABLE';
COMMIT;

\echo '--- A6 entry whose denormalised account code is not its account (must NOT survive) ---'
BEGIN;
INSERT INTO ledger_entries
    (id, transaction_id, account_id, direction, amount_minor, currency, account_code, line_no)
SELECT 'le_liar', 'lt_dup', id, 'DEBIT', 1, 'EGP', 'PLATFORM_CASH', 9
  FROM ledger_accounts WHERE code = 'PSP_CLEARING';
COMMIT;

\echo '--- A7 zero-amount entry (must NOT survive) ---'
BEGIN;
INSERT INTO ledger_entries
    (id, transaction_id, account_id, direction, amount_minor, currency, account_code, line_no)
SELECT 'le_zero', 'lt_dup', id, 'DEBIT', 0, 'EGP', code, 8
  FROM ledger_accounts WHERE code = 'PSP_CLEARING';
COMMIT;

\echo '--- A8 a reversal naming nothing (must NOT survive) ---'
INSERT INTO ledger_transactions
    (id, state, type, currency, source_type, source_id, description, created_by_actor)
VALUES ('lt_badrev', 'POSTED', 'REVERSAL', 'EGP',
        'SYSTEM', 'nothing', 'adversarial: reversal naming nothing', 'probe');

\echo '--- A9/A10/A11 rewrite posted history through the back door (must all be refused) ---'
BEGIN;
UPDATE ledger_transactions SET description = 'rewritten' WHERE id = 'lt_dup';
COMMIT;
BEGIN;
UPDATE ledger_entries SET amount_minor = 1 WHERE id = 'le_dup_d1';
COMMIT;
-- Deleting the credit leg would leave lt_dup with a single entry: the exact zero-entry shape A1
-- refuses at INSERT time, reached by a different road.
BEGIN;
DELETE FROM ledger_entries WHERE id = 'le_dup_c1';
COMMIT;

\echo ''
\echo '=== B. control: the legal shapes MUST still be accepted ==='

\echo '--- B1 one debit, one credit, equal amounts ---'
BEGIN;
INSERT INTO ledger_transactions
    (id, state, type, currency, source_type, source_id, description, created_by_actor)
VALUES ('lt_ok', 'POSTED', 'PAYMENT_CAPTURE', 'EGP',
        'PAYMENT', 'pay_ok', 'control: the minimum legal transaction', 'probe');
INSERT INTO ledger_entries
    (id, transaction_id, account_id, direction, amount_minor, currency, account_code, line_no)
SELECT 'le_ok_d', 'lt_ok', id, 'DEBIT', 10000, 'EGP', code, 1
  FROM ledger_accounts WHERE code = 'PSP_CLEARING';
INSERT INTO ledger_entries
    (id, transaction_id, account_id, direction, amount_minor, currency, account_code, line_no)
SELECT 'le_ok_c', 'lt_ok', id, 'CREDIT', 10000, 'EGP', code, 2
  FROM ledger_accounts WHERE code = 'MERCHANT_PAYABLE';
COMMIT;

\echo '--- B2 three legs: Dr gross / Cr net / Cr fee ---'
BEGIN;
INSERT INTO ledger_transactions
    (id, state, type, currency, source_type, source_id, description, created_by_actor)
VALUES ('lt_ok3', 'POSTED', 'PAYMENT_CAPTURE', 'EGP',
        'PAYMENT', 'pay_ok3', 'control: gross = net + fee', 'probe');
INSERT INTO ledger_entries
    (id, transaction_id, account_id, direction, amount_minor, currency, account_code, line_no)
SELECT 'le_ok3_d', 'lt_ok3', id, 'DEBIT', 10000, 'EGP', code, 1
  FROM ledger_accounts WHERE code = 'PSP_CLEARING';
INSERT INTO ledger_entries
    (id, transaction_id, account_id, direction, amount_minor, currency, account_code, line_no)
SELECT 'le_ok3_n', 'lt_ok3', id, 'CREDIT', 9700, 'EGP', code, 2
  FROM ledger_accounts WHERE code = 'MERCHANT_PAYABLE';
INSERT INTO ledger_entries
    (id, transaction_id, account_id, direction, amount_minor, currency, account_code, line_no)
SELECT 'le_ok3_f', 'lt_ok3', id, 'CREDIT', 300, 'EGP', code, 3
  FROM ledger_accounts WHERE code = 'PLATFORM_FEE_REVENUE';
COMMIT;

\echo '--- B3 a second, independent balanced transaction ---'
BEGIN;
INSERT INTO ledger_transactions
    (id, state, type, currency, source_type, source_id, description, created_by_actor)
VALUES ('lt_ok2', 'POSTED', 'PSP_ADJUSTMENT', 'EGP',
        'SYSTEM', 'adj_ok2', 'control: second balanced transaction', 'probe');
INSERT INTO ledger_entries
    (id, transaction_id, account_id, direction, amount_minor, currency, account_code, line_no)
SELECT 'le_ok2_d', 'lt_ok2', id, 'DEBIT', 500, 'EGP', code, 1
  FROM ledger_accounts WHERE code = 'PLATFORM_CASH';
INSERT INTO ledger_entries
    (id, transaction_id, account_id, direction, amount_minor, currency, account_code, line_no)
SELECT 'le_ok2_c', 'lt_ok2', id, 'CREDIT', 500, 'EGP', code, 2
  FROM ledger_accounts WHERE code = 'PSP_CLEARING';
COMMIT;

\echo ''
\echo '=== C. what actually survived ==='
SELECT id, state, type, currency, description FROM ledger_transactions ORDER BY id;
SELECT transaction_id, count(*) AS entries,
       sum(amount_minor) FILTER (WHERE direction = 'DEBIT')  AS debits,
       sum(amount_minor) FILTER (WHERE direction = 'CREDIT') AS credits
  FROM ledger_entries GROUP BY transaction_id ORDER BY transaction_id;

\echo ''
\echo '=== D. the verdict, stated over the state rather than over the error log ==='
\echo '--- D1 no POSTED transaction may have fewer than two entries ---'
SELECT count(*) AS posted_transactions_without_two_entries
  FROM ledger_transactions t
 WHERE t.state = 'POSTED'
   AND (SELECT count(*) FROM ledger_entries e WHERE e.transaction_id = t.id) < 2;

\echo '--- D2 every surviving transaction must balance ---'
SELECT count(*) AS unbalanced_transactions
  FROM (SELECT transaction_id
          FROM ledger_entries
         GROUP BY transaction_id
        HAVING sum(amount_minor) FILTER (WHERE direction = 'DEBIT')
            <> sum(amount_minor) FILTER (WHERE direction = 'CREDIT')) x;

\echo '--- D3 the attacks that must have left nothing behind ---'
SELECT count(*) AS attack_transactions_that_survived
  FROM ledger_transactions
 WHERE id IN ('lt_zerofill', 'lt_one', 'lt_unbal', 'lt_fx', 'lt_dup2', 'lt_badrev');

\echo '--- D4 the illegal entries that must not exist ---'
SELECT count(*) AS illegal_entries_that_survived
  FROM ledger_entries WHERE id IN ('le_one', 'le_unbal_d', 'le_unbal_c', 'le_fx',
                                   'le_dup_d2', 'le_dup_c2', 'le_liar', 'le_zero');

\echo '--- D5 lt_dup must still have its original two entries, unmodified ---'
SELECT count(*) AS lt_dup_entries, coalesce(sum(amount_minor), 0) AS total
  FROM ledger_entries WHERE transaction_id = 'lt_dup';

\echo '--- D6 lt_dup description must be the original, not "rewritten" ---'
SELECT description FROM ledger_transactions WHERE id = 'lt_dup';

\echo '--- D7 the controls must have survived: three legal transactions ---'
SELECT count(*) AS control_transactions
  FROM ledger_transactions WHERE id IN ('lt_ok', 'lt_ok3', 'lt_ok2');

\echo ''
\echo '=== E. assertions (a failure here is a nonzero psql exit) ==='
\set ON_ERROR_STOP on
DO $assert$
DECLARE
    survivors       int;
    illegal_entries int;
    postings        int;
    lt_dup_entries  int;
    lt_dup_total    bigint;
    lt_dup_desc     text;
    controls        int;
    unbalanced      int;
    underfilled     int;
    failures        int := 0;
BEGIN
    SELECT count(*) INTO survivors FROM ledger_transactions
     WHERE id IN ('lt_zerofill', 'lt_one', 'lt_unbal', 'lt_fx', 'lt_dup2', 'lt_badrev');
    SELECT count(*) INTO illegal_entries FROM ledger_entries
     WHERE id IN ('le_one', 'le_unbal_d', 'le_unbal_c', 'le_fx',
                  'le_dup_d2', 'le_dup_c2', 'le_liar', 'le_zero');
    SELECT count(*) INTO postings FROM ledger_transactions WHERE id = 'lt_dup';
    SELECT count(*), coalesce(sum(amount_minor), 0) INTO lt_dup_entries, lt_dup_total
      FROM ledger_entries WHERE transaction_id = 'lt_dup';
    SELECT description INTO lt_dup_desc FROM ledger_transactions WHERE id = 'lt_dup';
    SELECT count(*) INTO controls FROM ledger_transactions WHERE id IN ('lt_ok', 'lt_ok3', 'lt_ok2');
    SELECT count(*) INTO unbalanced FROM (
        SELECT transaction_id FROM ledger_entries GROUP BY transaction_id
         HAVING sum(amount_minor) FILTER (WHERE direction = 'DEBIT')
             <> sum(amount_minor) FILTER (WHERE direction = 'CREDIT')) x;
    SELECT count(*) INTO underfilled FROM ledger_transactions t
     WHERE t.state = 'POSTED'
       AND (SELECT count(*) FROM ledger_entries e WHERE e.transaction_id = t.id) < 2;

    IF survivors <> 0 THEN
        RAISE EXCEPTION 'F-03 STILL OPEN: % adversarial ledger transaction(s) committed', survivors;
    END IF;
    IF illegal_entries <> 0 THEN
        RAISE EXCEPTION 'F-03 STILL OPEN: % illegal ledger entr(ies) committed', illegal_entries;
    END IF;
    IF postings <> 1 THEN
        RAISE EXCEPTION 'L10: expected exactly one posting of pay_dup, found %', postings;
    END IF;
    IF lt_dup_entries <> 2 OR lt_dup_total <> 20000 THEN
        RAISE EXCEPTION 'L4: lt_dup was modified: % entries totalling %', lt_dup_entries, lt_dup_total;
    END IF;
    IF lt_dup_desc LIKE 'rewritten%' THEN
        RAISE EXCEPTION 'L4: lt_dup description was rewritten to "%"', lt_dup_desc;
    END IF;
    IF controls <> 3 THEN
        RAISE EXCEPTION 'only % of 3 legal control transactions were accepted', controls;
    END IF;
    IF unbalanced <> 0 THEN
        RAISE EXCEPTION 'L1: % transaction(s) do not balance', unbalanced;
    END IF;
    IF underfilled <> 0 THEN
        RAISE EXCEPTION 'L3: % POSTED transaction(s) have fewer than two entries', underfilled;
    END IF;

    RAISE NOTICE 'ledger probe: all invariants held; % controls accepted, % attack rows survived',
                 controls, survivors;
END $assert$;

\echo 'LEDGER PROBE: PASS'