# 01 — Money and the Double-Entry Ledger

The ledger is the most important part of this system. If the ledger is wrong, nothing else in
the project matters.

---

## 1. `Money`

```java
public record Money(long amountMinor, CurrencyCode currency) implements Comparable<Money> { }
```

Rules, all enforced in the constructor and therefore impossible to bypass:

| Rule | Enforcement |
| --- | --- |
| `amountMinor` is a `long`. Never `double`, `float`, `BigDecimal`, or `int` for money. | Type system. |
| `amountMinor >= 0`. A negative amount is a *direction*, and direction belongs to the entry, not the amount. | Constructor throws. |
| `currency` is a supported ISO-4217 code from `currency_units`. | Lookup fails loudly. |
| `amountMinor` must be representable at the currency's exponent (e.g. `1000` is not a valid EGP minor amount). | Constructor throws. |
| Addition/subtraction require equal currency. | `IllegalArgumentException`. |
| No division, ever. | Not on the API surface. |
| JSON representation is exactly `{"amountMinor": 12550, "currency": "EGP"}`. | Record + `@JsonProperty` naming. Never a decimal string, never a float. |

### Supported currencies (v1)

| Code | Exponent | Active |
| --- | --- | --- |
| EGP | 2 | yes |
| USD | 2 | yes |
| EUR | 2 | yes |
| GBP | 2 | yes |

Zero-decimal currencies (JPY, KWD) are **excluded from v1** rather than half-supported: the
rounding rules for fees differ and getting them subtly wrong is worse than not offering them.
`currency_units` is a table, so adding one later is a migration, not a code change.

### Fee computation

```
platformFee = round_half_up(grossMinor × bps / 10_000)     // integer arithmetic
net         = grossMinor − platformFee
```

Invariant that must hold for every payment, and is asserted in a unit test and re-asserted at
post time: **`grossMinor == netMinor + platformFeeMinor` exactly**. No residue, no drift. The
rounding is applied to the fee only; `net` is always the remainder. This is why we never divide.

## 2. Chart of accounts

The chart is **data**, seeded by migration, not code. Adding an account is a migration.

| Code | Name | Type | Normal balance | Active in v1 | Meaning |
| --- | --- | --- | --- | --- | --- |
| `PSP_CLEARING` | Receivable from the PSP | ASSET | DEBIT | yes | Money captured from customers that the PSP has not yet paid us. |
| `PLATFORM_CASH` | Platform cash | ASSET | DEBIT | yes | Money the PSP has paid us and that we hold. |
| `MERCHANT_PAYABLE` | Amount owed to merchants | LIABILITY | CREDIT | yes | Merchant's share of captured payments, not yet paid out. |
| `PLATFORM_FEE_REVENUE` | Platform fee revenue | REVENUE | CREDIT | yes | Our fee on captured payments. |
| `PSP_FEE_EXPENSE` | Fees charged by the PSP | EXPENSE | DEBIT | **reserved** | Activated by the v1.5 settlement-fee model. Defined now, posted to never in v1. |

Sign convention for the stored balance: `balance_minor` is expressed in the account's **normal
direction**, so a positive number always means "more of this account", and a negative number is
unambiguously a contra position.

```
ASSET, EXPENSE   → balance = Σ debits − Σ credits
LIABILITY, REVENUE → balance = Σ credits − Σ debits
```

## 3. Ledger transaction types (v1)

| Type | Trigger | Entries |
| --- | --- | --- |
| `PAYMENT_CAPTURE` | Payment captured | `Dr PSP_CLEARING gross` / `Cr MERCHANT_PAYABLE net` / `Cr PLATFORM_FEE_REVENUE fee` |
| `REVERSAL` | Reversal of an existing transaction | Exact sign-flipped mirror of the referenced transaction |
| `PAYMENT_REFUND_SETTLED` | Refund of an already-settled payment | `Dr MERCHANT_PAYABLE net` / `Dr PLATFORM_FEE_REVENUE fee` / `Cr PLATFORM_CASH gross` |
| `SETTLEMENT_RECEIVED` | Settlement record ingested from the PSP | `Dr PLATFORM_CASH gross` / `Cr PSP_CLEARING gross` |
| `MERCHANT_PAYOUT` | Merchant payout record executed | `Dr MERCHANT_PAYABLE net` / `Cr PLATFORM_CASH net` |
| `PSP_ADJUSTMENT` | Operator correction attached to a reconciliation case | Value moved between `PSP_CLEARING` and `PLATFORM_CASH` only |

### Worked example — the demo numbers

Payment of **100.00 EGP**, policy 300 bps:

```
platformFee = round_half_up(grossMinor × bps / 10_000)
            = round_half_up(10_000 × 300 / 10_000) = 300 minor units   →  3.00 EGP
net         = 10_000 − 300 = 9_700 minor units                          → 97.00 EGP
```

The divisor is `10_000` exactly once, because bps are ten-thousandths of a unit. Keeping this
arithmetic in one named place (`FeePolicy`) and asserting it in a unit test is the entire reason
fees are not computed inline wherever they happen to be needed.


```
PAYMENT_CAPTURE  (gross 100.00, fee 3.00, net 97.00)
  Dr  PSP_CLEARING           100.00
  Cr  MERCHANT_PAYABLE        97.00
  Cr  PLATFORM_FEE_REVENUE     3.00
  Σ debits = 100.00 = Σ credits = 100.00   ✔

SETTLEMENT_RECEIVED (PSP pays us 100.00)
  Dr  PLATFORM_CASH          100.00
  Cr  PSP_CLEARING           100.00
  Σ debits = 100.00 = Σ credits = 100.00   ✔

  PSP_CLEARING:   100.00 − 100.00 = 0.00    ✔
  PLATFORM_CASH: 100.00                    ✔
  MERCHANT_PAYABLE: 97.00  (still owed to the merchant)
  PLATFORM_FEE_REVENUE: 3.00
```

The three steps together — capture, settlement, payout — unwind every account:

```
MERCHANT_PAYOUT  (merchant is paid 97.00)
  Dr  MERCHANT_PAYABLE        97.00
  Cr  PLATFORM_CASH           97.00
  Σ debits = 97.00 = Σ credits = 97.00   ✔

  FINAL STATE
  PSP_CLEARING            0.00    cleared — the PSP owes us nothing
  MERCHANT_PAYABLE        0.00    cleared — we owe the merchant nothing
  PLATFORM_CASH            3.00    the fee, held as cash
  PLATFORM_FEE_REVENUE     3.00    the same fee, recognised as revenue
```

Every account that holds somebody else's money has unwound to exactly zero, and the only residue
is the platform's own 3.00 fee. That residue appears in **two** accounts, and it has to: `3.00` in
`PLATFORM_CASH` is a statement about what we hold, while `3.00` in `PLATFORM_FEE_REVENUE` is a
statement about what we have earned. They are equal only because the merchant's 97.00 has now been
paid out; one step earlier — after settlement, before the payout — the same platform holds
`PLATFORM_CASH = 100.00` while having recognised only `3.00` of revenue and still owing
`MERCHANT_PAYABLE = 97.00`.

That gap is the whole point. A balance counter can hold one number per currency and would have to
choose which of those truths to report. A ledger holds every position at once and lets them
disagree, because they genuinely do: money we have, money we are owed, and money we have earned are
three different facts about the same 100.00 that only reconcile once the cycle completes.

*(`PLATFORM_CASH` is `100.00 − 97.00 = 3.00`, not `97.00`. An earlier draft of this document put
`97.00` here, which is the merchant's share and the amount just paid **out** — it contradicted the
closing sentence of its own table, and `spec 07` and `U-LED-10` repeated it. The implementation was
always right; `LedgerInvariantsIT.fullCycleUnwindsTheLedger` has always asserted `300`.)*

Refund paths, verified by hand and by test:

```
(a) Refund while still CAPTURED  → REVERSAL of PAYMENT_CAPTURE
  Cr  PSP_CLEARING           100.00
  Dr  MERCHANT_PAYABLE        97.00
  Dr  PLATFORM_FEE_REVENUE     3.00
  All four accounts return to their pre-capture balances.  ✔

(b) Refund after SETTLED  → PAYMENT_REFUND_SETTLED
  Dr  MERCHANT_PAYABLE        97.00
  Dr  PLATFORM_FEE_REVENUE     3.00
  Cr  PLATFORM_CASH           100.00
  PLATFORM_CASH 100.00 − 100.00 = 0.00; payable and revenue released.  ✔
  (The contra account is cash, not clearing, because the money already reached our bank
   account at settlement. This is exactly why (b) cannot be a reversal of the capture.)
```

### (c) Refund after the merchant has already been paid out — refused

The tempting third case is the one that breaks the ledger. After the payout above, the platform
holds 97.00 in cash. A 100.00 refund would credit cash by 100.00 and drive it to **−3.00**, which
L8 forbids — and rightly so. The platform would be paying the customer money it does not have.

So v1 **refuses** it:

```
payment whose net appears in a payout line  ──refund──▶  409 PAYMENT_ALREADY_PAID_OUT
```

This is not a limitation papered over with a workaround; it is the correct boundary. Once the
merchant has been paid, a customer refund is no longer a refund — it is a **recovery from the
merchant**, and the platform must fund the difference from its own float. v1 does not model
merchant balance recovery (see [§9](#9-what-is-explicitly-not-modelled)), so the correct v1
behaviour is to refuse loudly and let an operator handle it through a case-attached
`PSP_ADJUSTMENT`, where `MERCHANT_PAYABLE` is permitted to go negative as the clawback receivable.

Introducing payouts is what makes this case visible at all. Without them it never arose, and the
ledger would never have been forced to say what it cannot do.

## 4. Ledger invariants

These are the numbered contract. Every one has at least one automated test, and several are
additionally enforced by the database (§7).

| ID | Invariant | Enforced by |
| --- | --- | --- |
| **L1** | For every transaction, Σ debits = Σ credits, per currency. | Domain validator (throws) + **two deferred constraint triggers** (`tg_txn_balances` on entries, `tg_txn_header_balances` on the header) + `LedgerVerifier` + test |
| **L2** | A transaction has exactly one currency, and every entry is denominated in it. | `ledger_entries.currency → ledger_transactions.currency` composite foreign key (`fk_entry_txn_currency`), immediate; plus `LedgerPostingCommand`'s constructor |
| **L3** | A transaction has ≥ 2 entries, ≥ 1 debit and ≥ 1 credit; every amount is > 0; no zero-value entries. | Domain validator + **the same two deferred constraint triggers** + `ledger_entries.amount_minor > 0` column CHECK |
| **L4** | Posted entries and posted transactions are immutable — never updated, never deleted. | DB trigger + no mutator on the repository |
| **L5** | Money is never edited. A correction is a new `REVERSAL` transaction. | No update path exists in the application |
| **L6** | A transaction may be reversed at most once, directly. | Partial unique index on `reversal_of_transaction_id` |
| **L7** | `account_balances.balance_minor` equals the recomputed sum of that account's entries. | `LedgerVerifier`, run in tests and on a schedule |
| **L8** | After posting, `PSP_CLEARING` and `PLATFORM_CASH` balances are ≥ 0. Posting is rejected otherwise. This also bounds a `MERCHANT_PAYOUT` by the cash actually available. | Trigger `tg_asset_non_negative` + domain check |
| **L9** | No posting may target a disabled or closed account. | `ledger_accounts.state = 'ACTIVE'` check |
| **L10** | The same `(source_type, source_id, transaction_type)` can be posted at most once, ever — for non-reversal postings. Reversals are governed by L6 instead, so chains stay legal. | Partial unique index `ux_ledger_source WHERE type <> 'REVERSAL'` |
| **L11** | A payment may be paid out at most once, ever. | `UNIQUE (payment_id)` on `payout_lines` |

### L1 and L3 are enforced from the header as well as from the entries

This is worth stating separately, because the baseline got it half right and the gap was a real
financial-integrity hole.

`tg_txn_balances` is a `DEFERRABLE INITIALLY DEFERRED` constraint trigger on
`AFTER INSERT ON ledger_entries`, which is the correct place to ask whether entries balance: it
runs at `COMMIT`, when the transaction is complete, so it can never observe a half-written
posting. But it only fires if there *are* entries.

`INSERT INTO ledger_transactions (id, state, type, currency, source_type, source_id, description)
VALUES (…, 'POSTED', …)` with no entries therefore committed cleanly and produced a ledger
transaction in state `POSTED` whose entry list was empty: a financial record claiming to be posted
while representing no money movement at all. Nothing in the application ever saw it, because it
had been written without the application.

`tg_txn_header_balances` closes that from the other side. It is the same deferred mechanism on
`AFTER INSERT ON ledger_transactions`, so at `COMMIT` it can ask "does this transaction have at
least two entries, and do they balance?" and answer about the final state rather than about one
statement.

Two enforcement points for one invariant is normally duplication. Here the header trigger's
question is *presence* — does this transaction have entries at all — and the entry trigger's is
*arithmetic* — do they balance. Only the first can be answered from the header, and only the
second reports usefully against the row that caused it. A caller that inserts a header and its
entries in one transaction is unaffected: both triggers fire at `COMMIT` and both see the
complete posting.

The check is `n < 2 OR d <> c`, which also covers L3's "at least one debit and at least one
credit": two entries whose totals match cannot both be debits or both credits without one of them
being zero, and `amount_minor > 0` is a column `CHECK`. A zero-amount entry cannot exist at all.

**This is asserted by raw SQL, not by the application.** `LedgerInvariantsIT` inserts a zero-entry
header over plain JDBC and requires the commit to be refused, and `audit-probe/vv-ledger-invariants.sql`
runs the same attacks plus eleven others against an isolated PostgreSQL and exits nonzero if any
of them survives. A claim about what the database refuses is only worth something if the test
bypasses everything except the database.

### L7 and negative balances

L8 is restricted to assets deliberately. `MERCHANT_PAYABLE` and `PLATFORM_FEE_REVENUE` may go
negative: a refund after a merchant has been paid leaves a clawback receivable against the
merchant, and a refunded fee is negative revenue. That is correct accounting, not a bug, and
forbidding it would force fake transactions.

## 5. Posting

```java
public interface LedgerService {
    LedgerTransaction post(LedgerPostingCommand command);
}
```

`LedgerPostingCommand` carries: transaction type, currency, the ordered list of
`(accountCode, direction, Money)` entries, an idempotency/source reference, a description, and
audit context. It does **not** carry an outcome — the caller cannot influence balances.

Posting algorithm, all inside one `@Transactional` method:

```
1. Reject if any account code is unknown or not ACTIVE.            (L9)
2. Reject unless the command balances and is well-formed.          (L1, L3)
3. SELECT id, code FROM ledger_accounts
     WHERE code IN (:codes) ORDER BY code FOR UPDATE;               (deterministic lock order)
4. Insert ledger_transactions (state = POSTED).
5. Insert ledger_entries.
6. Apply the delta to account_balances for each touched account.
7. Reject the whole thing if an asset balance would go negative.    (L8)
8. Insert the payment↔ledger link and the audit event(s).
9. Commit.
```

Step 4 writes `POSTED` directly (decision **D1**): there is no `PENDING` state to reconcile
after a crash, because the transaction and its entries are never separately observable. If we
later need two-phase posting across services, that is a v2 change with a real migration.

The unique constraint on `(source_type, source_id, transaction_type)` (L10) makes step 4 fail
with a constraint violation if the same business fact is posted twice. The application maps that
to `409 LEDGER_ALREADY_POSTED` rather than a 500, and the audit trail shows which command tried.

### Locking details

```sql
SELECT id, code, state FROM ledger_accounts
 WHERE code IN (:codes)
 ORDER BY code
 FOR UPDATE;
```

`ORDER BY code` is not decoration. Two concurrent postings that both touch
`{PLATFORM_CASH, PSP_CLEARING}` would otherwise be able to acquire their two locks in opposite
orders and deadlock. Sorting first makes the acquisition order globally consistent, so the
deadlock is structurally impossible rather than statistically unlikely.

Deadlock/lock-timeout safety net: a `DeadlockLoserDataAccessException` or
`CannotAcquireLockException` is retried up to **3 times** with 25/75/200 ms jittered backoff,
inside a `REQUIRES_NEW` boundary. The retry is safe precisely because posting is atomic and
guarded by L10 — a retry cannot double-post.

## 6. Reversal

```java
public interface LedgerService {
    LedgerTransaction reverse(LedgerTransactionId original, ReversalReason reason);
}
```

Rules:

- A reversal contains **exactly** the mirror image of the original: same accounts, same
  currencies, same amounts, directions flipped. Any deviation is rejected.
- The reversal is itself a `POSTED` ledger transaction with `type = REVERSAL` and
  `reversal_of_transaction_id` set.
- **The original is not touched.** It gains no "reversed" flag, because mutating it would
  violate L4. "Is this transaction reversed?" is answered by
  `SELECT 1 FROM ledger_transactions WHERE reversal_of_transaction_id = ?`, which the partial
  unique index makes O(1) and race-free.
- A reversal may itself be reversed (chained), under the same one-direct-reversal rule. This is why
the L10 double-post index excludes `REVERSAL`: a reversal is identified by the transaction it
reverses, not by the business source it compensates.
- Reversal is **refused** for a payment that has already been refunded
  (`PAYMENT_INVALID_STATE`), because that payment's capture has already been compensated.
- Every reversal writes `LEDGER_REVERSAL` to the audit trail with the reason code.

Correction flow, always three transactions, never an edit:

```
Original transaction  ──►  Reversal transaction  ──►  Correct transaction
```

## 7. Balance projection

`account_balances` is a **projection**, not the source of truth. `ledger_entries` is.

Comparing an entry to the account's `normal_side` implements the §2 sign convention directly,
so one expression covers assets, liabilities, revenue, and expenses without a special case:

```sql
-- L7 verification query, run by LedgerVerifier.
-- 'returning any row is an invariant breach'.
SELECT a.code, b.currency,
       b.balance_minor                                        AS projected,
       COALESCE(SUM(CASE WHEN e.direction = a.normal_side
                         THEN e.amount_minor ELSE -e.amount_minor END), 0) AS recomputed,
       COUNT(e.id)                                            AS entry_count
  FROM account_balances b
  JOIN ledger_accounts a ON a.id = b.account_id
  LEFT JOIN ledger_entries e ON e.account_id = b.account_id AND e.currency = b.currency
 GROUP BY a.code, a.normal_side, b.account_id, b.currency, b.balance_minor, b.entry_count
HAVING b.balance_minor <> COALESCE(SUM(CASE WHEN e.direction = a.normal_side
                                            THEN e.amount_minor ELSE -e.amount_minor END), 0)
    OR b.entry_count <> COUNT(e.id);
```

Any row returned is a **critical invariant breach**: the service logs an error, emits a metric
with the account code, and exposes the failure through `/api/v1/health`. It does not attempt a
silent repair — a silently repaired balance destroys the evidence that the bug happened.

The `direction = normal_side` comparison is what makes the query correct for every account type:
an `ASSET` (normal side `DEBIT`) yields `debits − credits`, and a `LIABILITY` (normal side
`CREDIT`) yields `credits − debits`, matching the stored convention. `U-LED-09` and `I-LED-05`
cover both forms, so the asymmetry cannot regress unnoticed.

## 8. Read models

Three queries the system must answer well, each backed by a dedicated index:

| Query | Index that serves it |
| --- | --- |
| Current balance of an account | `account_balances` PK (O(1)) |
| Statement of account for an account over a period | `ledger_entries(account_id, posted_at DESC)` |
| Everything that happened to one payment | `ledger_transactions(source_type, source_id)` |

The third is the investigative path the whole audit design exists to support: given a payment
id, a single query returns its full ledger story.

## 9. What is explicitly not modelled

- Multi-currency transactions and FX gain/loss.
- Accruals, deferrals, and revenue recognition schedules.
- `PENDING` transactions and two-phase posting across services.
- Merchants as first-class entities (a payment carries a `merchantReference` string).
- Historical balance snapshots (v1 keeps only the current projection plus entries).
- Merchant balance recovery: a refund of a payment whose net has already been paid out is
  refused rather than funded, because funding it would require modelling a merchant receivable
  and a platform float.

Each of these is a real accounting concept. Naming them here is the point: the repo should not
imply coverage it does not have.