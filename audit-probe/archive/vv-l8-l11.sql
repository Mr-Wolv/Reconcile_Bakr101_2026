-- L8 and L11, attacked directly at the database layer.
--
-- L8  an ASSET account may not go negative, and the guard must fire on the UPDATE that takes it
--     below zero -- not only on the row that first created the account.
-- L11 a payment may be paid out at most once, ever, enforced by ux_payout_line_payment.

\set ON_ERROR_STOP off
\pset pager off

\echo '=== setup: one payment and two payout records ==='
INSERT INTO payments (id, merchant_reference, amount_minor, currency, platform_fee_minor,
                      net_amount_minor, fee_policy_id, state, provider)
VALUES ('pay_l11', 'MERCH-L11', 10000, 'EGP', 0, 10000, 'fp_default', 'SETTLED', 'SIMULATED_PSP');

INSERT INTO payout_records (id, merchant_reference, currency, total_net_minor, line_count,
                            status, created_by_actor)
VALUES ('pr_1', 'MERCH-OUT-1', 'EGP', 5000, 1, 'PLANNED', 'vv'),
       ('pr_2', 'MERCH-OUT-2', 'EGP', 5000, 1, 'PLANNED', 'vv');

\echo '=== L11: POSITIVE CONTROL, first payout line for the payment must COMMIT ==='
BEGIN;
  INSERT INTO payout_lines (id, payout_record_id, payment_id, line_net_minor, line_no)
  VALUES ('pl_1', 'pr_1', 'pay_l11', 5000, 1);
COMMIT;
\echo '  L11 first payout line committed (expected)';

\echo '=== L11b: a SECOND payout line for the same payment must be refused ==='
BEGIN;
  INSERT INTO payout_lines (id, payout_record_id, payment_id, line_net_minor, line_no)
  VALUES ('pl_2', 'pr_2', 'pay_l11', 5000, 1);
COMMIT;
\echo '  ^^ if no ERROR above, L11 IS BROKEN';

\echo '=== L11c: same payment, same payout record, different line_no must still be refused ==='
BEGIN;
  INSERT INTO payout_lines (id, payout_record_id, payment_id, line_net_minor, line_no)
  VALUES ('pl_3', 'pr_1', 'pay_l11', 1000, 2);
COMMIT;
\echo '  ^^ if no ERROR above, L11 IS BROKEN';

-- =====================================================================
-- L8: the asset guard. account_balances is keyed by (account, currency), so the attack has to
-- target that row directly -- exactly what a rogue migration or a hand-run UPDATE would do.
-- =====================================================================
\echo '=== L8: POSITIVE CONTROL, adding to an asset must COMMIT ==='
BEGIN;
  UPDATE account_balances SET balance_minor = 5000
   WHERE account_id = 'acct_psp_clearing' AND currency = 'EGP';
COMMIT;
\echo '  L8 credit committed (expected)';

\echo '=== L8a: taking an asset negative must be refused ==='
BEGIN;
  UPDATE account_balances SET balance_minor = -1
   WHERE account_id = 'acct_psp_clearing' AND currency = 'EGP';
COMMIT;
\echo '  ^^ if no ERROR above, L8 IS BROKEN';

\echo '=== L8b: a large negative must be refused too ==='
BEGIN;
  UPDATE account_balances SET balance_minor = -999999
   WHERE account_id = 'acct_psp_clearing' AND currency = 'EGP';
COMMIT;
\echo '  ^^ if no ERROR above, L8 IS BROKEN';

\echo '=== L8c: POSITIVE CONTROL, a LIABILITY may go negative (that is what a liability is) ==='
BEGIN;
  UPDATE account_balances SET balance_minor = -5000
   WHERE account_id = 'acct_merchant_payable' AND currency = 'EGP';
COMMIT;
\echo '  L8c negative liability committed (expected)';

\echo '=== final: payout lines for pay_l11 (expect exactly one) ==='
SELECT id, payout_record_id, payment_id, line_net_minor FROM payout_lines ORDER BY id;

\echo '=== final: PSP_CLEARING/EGP balance (expect 5000, not negative) ==='
SELECT account_id, currency, balance_minor FROM account_balances
 WHERE account_id IN ('acct_psp_clearing','acct_merchant_payable') AND currency = 'EGP'
 ORDER BY account_id;
