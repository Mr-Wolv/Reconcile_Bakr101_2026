# ADR-0002 — Pessimistic row locking for ledger posting

**Status:** Accepted · 2026-10-04

## Context

Two processes posting to overlapping ledger accounts must not lose an update. The classic failure
is read-modify-write on a balance:

```
worker A reads 100 ─┐
worker B reads 100 ─┴─ both compute 130, last writer wins, one posting is lost
```

Three candidate strategies:

| Strategy | How | Costs |
| --- | --- | --- |
| **A. Pessimistic row lock** | `SELECT … FOR UPDATE` on the accounts before reading | Lock held for the transaction's duration; contention serialises; possible lock waits and lock timeouts |
| **B. Optimistic version** | `version` column, `UPDATE … WHERE version = ?`, retry on 0 rows | Retry loops in every caller; under contention, retries can livelock; a transaction touching two accounts needs both versions tracked |
| **C. `SERIALIZABLE` isolation** | Let PostgreSQL detect the anomaly | Serialization failures surface as 40001 errors at arbitrary commit points, forcing retries around whole commands |

## Decision

**Option A: `SELECT … FOR UPDATE`, with the accounts locked in ascending `account_code` order,
inside the same transaction that writes the entries and updates the balances.**

Isolation stays at PostgreSQL's default `READ COMMITTED`.

The `ORDER BY` is load-bearing, not stylistic. Two concurrent postings that both touch
`{PLATFORM_CASH, PSP_CLEARING}` could otherwise acquire their two row locks in opposite orders and
deadlock. Sorting the lock set first makes the acquisition order globally consistent across every
posting path, so deadlock becomes structurally impossible rather than statistically unlikely. This
is the classic deadlock-avoidance invariant, and it costs one `ORDER BY`.

`READ COMMITTED` is sufficient precisely *because* explicit row locks are taken. The correctness
argument does not depend on snapshot behaviour: once a row lock is held, no other transaction can
read or write it until commit.

Deadlock/lock-timeout safety net: retry up to **3 times** with 25/75/200 ms jittered backoff inside
a `REQUIRES_NEW` boundary. The retry is provably safe because posting is atomic and guarded by the
unique constraint on `(source_type, source_id, transaction_type)` — a retry after a partial success
cannot double-post, because the database would reject it.

## Consequences

**Positive**

- The correctness argument is one sentence: *every posting takes the same locks in the same order
  and commits atomically*. That is a sentence an interviewer can follow.
- No retry logic anywhere in the domain layer; posting is a straight-line method.
- `READ_COMMITTED` avoids the 40001 retry storms that `SERIALIZABLE` produces under posting
  contention, and avoids the write-skew anomalies it would otherwise need to guard.
- Contention is observable and bounded, so performance characteristics are predictable rather
  than emergent.

**Negative, and accepted**

- Lock hold time equals transaction duration, so long transactions would serialise badly. Posting
  transactions are short and contain no external calls — this is a rule the code must keep.
- Throughput on a single hot account is serial by construction. If a real deployment needed that,
  sharding the chart of accounts would be the answer, and the deterministic lock order stays valid
  within a shard.

## Why not optimistic locking

Option B was rejected for a specific reason rather than as a matter of taste. Two postings that
touch *different* accounts but overlap on one of them (`PSP_CLEARING` + `MERCHANT_PAYABLE` versus
`PSP_CLEARING` + `PLATFORM_CASH`) do not conflict under optimistic concurrency at the row level,
so both succeed and both write the shared account — write skew. Correctness would then depend on
locking *every* account in the transaction, which is pessimistic locking by another name with extra
steps. Worse, the retry loop leaks into every posting call site, and a caller who forgets to retry
gets silent data loss instead of a failed transaction.

A `@Version` column is still present on `payments` as a second line of defence behind the payment
row lock, but it is not the mechanism.

## Evidence

`C-CONC-01` runs 32 concurrent postings against the same account pair and asserts the ledger
balances, the final balances are correct, the entry count is exact, and no deadlock was observed.
`C-CONC-03` forces lock contention and asserts that the retry path cannot double-post.