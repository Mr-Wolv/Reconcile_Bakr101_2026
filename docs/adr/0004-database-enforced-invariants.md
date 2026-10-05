# ADR-0004 — Enforce financial invariants in the database, not only in Java

**Status:** Accepted · 2026-10-04 · Amended 2026-10-05 (see "Amendment: the header-side trigger")

## Context

Every financial invariant has an obvious home: the Java validator that constructs the ledger
transaction. Putting the rule there is natural, readable, and gives good error messages.

The argument for putting it in the database as well is that the database is the only place the
invariant cannot be bypassed. Layers that can write financial rows without going through your
validator include: a future service, a data migration, a maintenance script, an ORM bulk update, a
`psql` session run under pressure at 2 a.m., a test fixture that "just needs to set the balance",
and the bug you have not written yet.

Of the eleven ledger invariants, the Java layer defends all eleven. The database defends **eight**
of them — every one except L7 and L9, whose exceptions are argued below:

| Invariant | Java | Database mechanism |
| --- | --- | --- |
| L1 debits = credits | yes | deferred constraint triggers on `ledger_entries` **and** `ledger_transactions` |
| L2 single currency | yes | column type + the trigger's distinct-currency check |
| L3 well-formed entries | yes | the same two deferred triggers (entry count) + `CHECK (amount_minor > 0)` |
| L4/L5 immutability | no update path exists | `BEFORE UPDATE OR DELETE` triggers that raise |
| L6 one reversal per transaction | yes | unique index on `reversal_of_transaction_id` |
| L7 balance projection integrity | verifier | `LedgerVerifier` (see below) |
| L8 no negative assets | yes | trigger reading `ledger_accounts.account_type` |
| L9 active accounts only | yes | `state` column + service check |
| L10 no double posting | yes | partial unique index on `(source_type, source_id, type)` where `type <> 'REVERSAL'` |
| one open case per subject | yes | partial unique index |
| resumable batches | yes | unique on `(batch_id, subject_key)` |
| L11 a payment is paid out at most once | yes | unique on `payout_lines (payment_id)` |

## Decision

**Enforce every invariant that PostgreSQL can express, in addition to the Java validator.** Where
the two disagree, the database wins.

Three mechanisms carry the weight:

**1. `fn_deny_mutation()`** — one `BEFORE UPDATE OR DELETE` trigger function attached to
`ledger_transactions`, `ledger_entries`, `audit_events`, `payment_state_history`,
`reconciliation_results`, `case_events`, and `provider_event_deliveries`. Seven tables of financial
history that simply cannot be rewritten, by any client, with any credentials that can write to them.

**2. `tg_txn_balances` and `tg_txn_header_balances`** — `DEFERRABLE INITIALLY DEFERRED` constraint
triggers on `ledger_entries` and on `ledger_transactions`, so a transaction's entries are only
checked at **commit**, once every entry exists. An immediate trigger would fire on the first entry
inserted and fail every legitimate multi-entry posting.

There are two because one is not enough, and the reason is recorded here rather than left to be
rediscovered. See "Amendment" below.

**3. Unique indexes as the last line of defence** — `(source_type, source_id, type)` on
`ledger_transactions`, partial on `type <> 'REVERSAL'`, makes double-posting impossible even if the
application is bypassed entirely. Excluding reversals is not a convenience: a reversal is
identified by the transaction it reverses, and a chain of reversals is legitimate accounting, so a
single index over all types would have forbidden the chain. The application catches the violation and maps it to `409 LEDGER_ALREADY_POSTED`, which
is both a better response than a `500` and an audit signal that something tried.

## Consequences

**Positive**

- The invariants that matter most are properties of the **data**, not of the code that happens to
  be running. They survive refactoring, new entry points, and future contributors.
- `I-LED-04` and `I-LED-06` prove it from raw JDBC, bypassing the application entirely. That test
  is the strongest evidence in the project, because it attacks the guarantee from the outside.
- Restore-from-backup and manual repair become *impossible to do silently*, which is exactly the
  property a financial ledger needs.

**Negative, and accepted**

- PostgreSQL-specific. The domain tests stay portable; the schema tests do not.
- Triggers are invisible to ORM tooling, so schema-aware tooling may show them as unexplained
  behaviour. Mitigated by documentation and by the DDL living in one reviewed migration file.
- Slightly slower writes. Irrelevant at this scale, and a deliberate trade.
- Schema changes involving triggers need care, which is the cost of keeping financial history.

## Amendment (2026-10-05): the header-side trigger

**This ADR originally overclaimed.** It said the database enforces L1 and L3, and it did — for
every transaction that had entries.

`tg_txn_balances` fires `AFTER INSERT ON ledger_entries`. A transaction with no entries never
fires it. So:

```sql
INSERT INTO ledger_transactions
    (id, state, type, currency, source_type, source_id, description)
VALUES ('lt_x', 'POSTED', 'PAYMENT_CAPTURE', 'EGP', 'PAYMENT', 'pay_x', '…');
COMMIT;
```

committed successfully, and produced a `ledger_transactions` row in state `POSTED` whose entry list
was empty. The ledger API read it back as `POSTED` with `entries: []`: a financial record
asserting that it was posted while representing no money movement. The application never saw it,
because it had been written without the application — which is precisely the threat model this ADR
was written for.

This is the failure mode the ADR is about, occurring inside the ADR. The defence that did not
exist was the one for the case where there is nothing to defend.

**Fix.** `tg_txn_header_balances`, the same `DEFERRABLE INITIALLY DEFERRED` mechanism on
`AFTER INSERT ON ledger_transactions`, checking `n < 2 OR d <> c` over the transaction's final
entry set at commit.

**Why not one trigger on the header alone?** It would work, because at commit it sees everything.
The entry-side trigger is kept because it reports an arithmetic failure against the row that caused
it, which is more useful than a header-side message naming the transaction. Their questions differ
— *presence* and *arithmetic* — and only one of them can be asked from the header.

**The lesson, recorded because it generalises.** "The database enforces X" is a claim about a set
of writes, not about a rule. The rule here was correct and the implementation covered every case
except the degenerate one, and the degenerate one is the only one that is interesting. Any future
invariant that a trigger only sees *because something else happened first* has the same hole.

**Evidence.** `LedgerInvariantsIT.aPostedHeaderWithNoEntriesIsRefused` inserts the header over raw
JDBC and requires the commit to be refused.
`audit-probe/vv-ledger-invariants.sql` runs it plus eleven further attacks — one entry, unbalanced,
mixed currency, duplicate source, lying account code, zero amount, malformed reversal, and three
routes to rewriting posted history — against an isolated PostgreSQL, asserts over the resulting
*state* rather than over captured error text, and exits nonzero if any attack survived or any
legal shape was refused.

## Where the database deliberately does **not** fix things

**L7 (balance projection integrity) is not enforced by a trigger.** A trigger would have to
recompute the balance on every entry write, which turns each posting into a full aggregate over
the account's history — quadratic behaviour on the hot path.

Instead the projection is checked by `LedgerVerifier`, which recomputes balances from
`ledger_entries` on a schedule and in tests, and a mismatch is reported rather than repaired:

- `LEDGER_BALANCE_BREACH` is audited, `/api/v1/health` returns `503`, and a metric is emitted with
  the account code.
- **It does not self-heal.** A silently corrected balance destroys the only evidence that the bug
  happened, and a ledger that quietly fixes itself is worse than one that loudly reports itself
  broken.

This is a deliberate asymmetry: cheap invariants are enforced on every write, and the expensive
invariant is enforced continuously by an independent observer.

**L9 (no posting to a closed account) is a service-level check.** `ledger_accounts.state` is a
column, and the service refuses to build a posting that names anything other than an `ACTIVE`
account. A trigger could enforce it, but it would have to fire on `ledger_entries` inserts — after
the transaction row that names the source is already committed — so it would protect the entry
while leaving the transaction behind it. A check at the service boundary, before anything is
written, keeps the failure atomic instead of half-applied.

The account is closed by a migration or an operator action, never by ordinary posting, so this is
a rare administrative path rather than a hot one. It is the one invariant where the database's
help is worth less than the application's atomicity.