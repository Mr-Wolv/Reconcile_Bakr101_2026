# 04 — Reconciliation

This is the part of the system the project is named for.

---

## 1. What reconciliation answers

> For a period, does our record of the money agree with the provider's record of the money?

Everything else in the system exists to make that question answerable with evidence.

## 2. Two-sided inputs

| Side | Source | Table |
| --- | --- | --- |
| Internal | Our own payments and ledger entries | `payments`, `ledger_transactions`, `ledger_entries` |
| Provider | Settlement records and their lines, received by webhook push or by pull | `settlement_records`, `settlement_record_lines`, `provider_transactions` |

Settlement records model the realistic PSP shape: **one settlement record covers many provider
transactions** and carries gross, fee, and net totals.

```
settlement_records        1 ──< settlement_record_lines >── 1  provider_transactions
  srec_01K4…                                            psp_txn_771
  gross 1000.00 EGP                                        │
  fee     10.00 EGP                                        └── payment_id → pay_01K4…
  net    990.00 EGP
  settlement_date 2026-10-04
```

This shape is what makes **partial settlement** a real scenario: a record covering three
transactions but paying only two of them in full is an `AMOUNT_MISMATCH` and a case, not a
rounding curiosity.

### 2.1 When a settlement record may be acted on

A settlement record is a *claim*: "on this date I paid you this gross, less this fee, leaving this
net, for exactly these transactions, in this currency." Several things about that claim can be
false while every individual field still looks reasonable, and none of them is a column constraint,
because each is a relationship between rows.

`SettlementRecordValidator` decides it, before anything is written, and states the rule as:

| ID | Invariant |
| --- | --- |
| S1 | The record has at least one line. A settlement paying nothing settles nothing. |
| S2 | `record.gross == Σ line.gross` |
| S3 | `record.net == Σ line.net` |
| S4 | `record.fee == Σ line.fee`, where `line.fee = line.gross − line.net` |
| S5 | Every line has `gross >= net`, so a derived fee is never negative. |
| S6 | No provider transaction appears on two lines of the same record. |
| S7 | Every amount is non-negative. |
| S8 | The record's currency is one the platform supports. |
| S9 | The record's currency equals the currency of every payment it covers. |

**Why S2, S3 and S4 all, and not just `gross = net + fee`.** The record-level split
(`ck_settlement_split`) says the three stated figures are consistent *with each other*. It says
nothing about whether they describe the same set of transactions as the lines do. A record of
gross 10,000 / fee 400 / net 9,600 over a single line of gross 10,000 / net 9,700 satisfies the
split perfectly and is still 100 minor units wrong: the line implies a fee of 300. All three must
hold independently, and they are not redundant — S2 and S3 can both hold while S4 fails, which is
exactly that record.

**Why a line's fee is derived rather than supplied.** A line carries a gross and a net and nothing
else, so `line.fee = line.gross − line.net` is a *definition*, not an invented business rule. It is
the same relation the platform holds for its own payments (`gross == net + fee`, [01 §1](01-money-and-ledger.md)),
so the two sides of a reconciliation are expressed in the same terms. S5 exists because the
difference can come out negative, and a negative fee would let S4 be satisfied arithmetically by a
transaction that paid more than it took.

**Why S9 is a refusal rather than a conversion.** v1 has no FX ([01 §9](01-money-and-ledger.md)).
A settlement in a currency other than the payment's cannot be honoured at all — not by converting,
not by posting in the record's currency, not by coercing the payment. Only two answers exist:
settle it, or do not settle it; when the currencies differ, only the second is true.

**A line whose payment we have never seen is not an S9 violation.** There is no currency to
disagree with. The transaction is recorded provider-side and reconciliation reports it
`MISSING_INTERNAL`, which is the true finding. Inventing a currency for it here would be inventing
money.

### 2.2 What happens to a record that fails

**Accept it durably, apply none of it, and make it visible.** `settlement_records.validity` is
`VALID` or `INVALID`, and an `INVALID` row carries `validity_reason` — the provider's own figures,
stored verbatim.

Storing the broken document rather than rejecting it is a deliberate choice. A provider whose
statement does not add up is exactly the case an operator needs to be able to read back, complain
about, and diff against the corrected statement they send next. An earlier implementation threw a
non-retryable exception on a gross mismatch, which destroyed the document on the grounds that it was
inconsistent — the one thing most worth keeping.

`ck_settlement_split` is relaxed in exactly one direction to allow this: an `INVALID` record may
carry inconsistent figures, a `VALID` one may not. The relaxation cannot be used to smuggle a
broken record past the check, because validity is decided by the validator, not by the writer.

What an `INVALID` record does **not** do:

- it does not settle any payment;
- it does not post to the ledger, in any currency;
- it cannot produce a `MATCHED` reconciliation result (see `SETTLEMENT_RECORD_INVALID` in §4);
- the event terminates normally rather than being retried — a second attempt produces the same
  answer, and spending the retry budget on a deterministic outcome pushes a fixable backlog behind
  it.

An audit event `SETTLEMENT_REJECTED_INVALID` names the record, the currency, the line count and the
reason.

**The provider's claim is recorded either way.** For every line, `provider_transactions` is written
with the figures and **the currency the provider stated** — not ours. That detail is the whole of
the cross-currency fix: the provider-side row used to be written with the payment's currency, which
silently replaced the provider's claim with ours, and then reconciliation compared the payment
against that substituted value, agreed with itself, and reported `MATCHED` for a settlement that had
just posted USD ledger entries against an EGP-cleared receivable. Recording what the provider
actually said is what makes the comparison mean anything.

**Merchant payouts are deliberately not a reconciliation subject.** The provider never sees them,
so there is nothing on the other side of the comparison; including them would invent differences
out of nothing. A payout is proven correct by the ledger — its lines sum to its total, its total
equals its posting — not by agreement with a third party. Payout only matters to reconciliation
indirectly, through the refund guard in [02 §2](02-payments.md): a payment that has been paid out
can no longer be refunded normally.

## 3. The expected provider view (decision **D7**)

The comparison is never "payment row vs provider row". It is **ledger vs provider**.

```
ExpectedProviderView
  externalTransactionId   ← the payment's provider_transaction_id
  grossAmount             ← sum of the payment's capture ledger entries (PSP_CLEARING side)
  feeAmount               ← sum of the fee leg (PLATFORM_FEE_REVENUE)
  netAmount               ← sum of the merchant leg (MERCHANT_PAYABLE)
  currency                ← the ledger transaction's currency
  status                  ← SETTLED iff a settlement record line covers this payment,
                            else CAPTURED
  merchantReference       ← payments.merchant_reference
  settlementDate          ← the covering settlement record's settlement_date, else null
```

Deriving the expectation from the ledger means a bug in the payment row cannot make a money bug
invisible: if the two disagree, the comparison catches it. If the expectation came from
`payments.amount`, a bug that wrote the wrong amount into both the payment and the ledger would
reconcile clean forever. That is the failure mode this design exists to prevent.

The three legs are read from the **capture transaction's entries by account code**, which is why
the fee split is a ledger concern and not a payment-row concern.

## 4. Algorithm

Three stages, deterministic, no randomness, no fuzzy matching, no heuristics. Same inputs, same
output, every time — asserted by a golden-file fixture test (`C-RECON-05`).

### Stage 0 — scope

A batch fixes `(provider, window_start, window_end, subject_type)`. The window is on the
**provider's capture/settlement date**, half-open `[start, end)`, so adjacent batches never
overlap and never skip. Half-open intervals are used everywhere in this project for exactly that
reason.

Two subject modes:

| Mode | Meaning |
| --- | --- |
| `INTERNAL_LEDGER` | Our captured payments in the window are the population; each is checked against the provider. Also produces `MISSING_ON_PROVIDER` for captured payments the provider never reported. |
| `PROVIDER_ONLY` | The provider's records in the window are the population; produces `MISSING_INTERNAL` for records we have never seen. |

The default batch runs **both**, which is what the demo needs: it finds both our missing
transactions and theirs.

The population is snapshotted into the batch at creation time (`rbat_subject_keys`), so a
re-run of a finished batch compares against exactly the same subjects even if data changed in
between. Re-running a window creates a **new batch**; results are immutable per batch.

### Stage 1 — identity match

Identity key: `(provider, external_transaction_id)`. Exact string equality, after trimming
whitespace and normalising case per the provider's documented rule. There is no partial matching
and no amount-based fallback matching — amount matching would produce exactly the kind of
plausible-but-wrong reconciliation that this system exists to avoid.

```
providerIndex  : Map<(provider, extTxnId), List<ProviderRecord>>
internalIndex  : Map<(provider, extTxnId), List<Expected>>   // captured payments only; see §4
```

Subject enumeration, in a fixed order so results are deterministic:

```
for extId in sorted(union(providerIndex.keys, internalIndex.keys)):
    provider = providerIndex[extId]     (0, 1, or n rows)
    internal = internalIndex[extId]     (0, 1, or n payments)
    classify(extId, provider, internal)
```

### Stage 2 — attribute comparison

Compared in this fixed order:

| # | Attribute | Internal source | Provider source | Difference → |
| --- | --- | --- | --- | --- |
| 1 | currency | ledger transaction currency | record currency | `CURRENCY_MISMATCH` |
| 2 | gross amount | Σ capture legs | record gross | `AMOUNT_MISMATCH` (component `GROSS`) |
| 3 | fee amount | Σ fee legs | record fee | `AMOUNT_MISMATCH` (component `FEE`) |
| 4 | net amount | Σ merchant legs | record net | `AMOUNT_MISMATCH` (component `NET`) |
| 5 | status | payment state | record status | `STATUS_MISMATCH` |
| 6 | merchant reference | `payments.merchant_reference` | record merchant reference | `REFERENCE_MISMATCH` |
| 7 | settlement date | covering record's date | record date | `SETTLEMENT_DATE_MISMATCH` |

**All** differences are recorded in `differences[]`, with internal value, provider value, and the
delta for each. The **first** in the table above becomes the primary reason. So a settlement that
is both 3.00 short and a day late produces `AMOUNT_MISMATCH` with both differences recorded — the
operator sees the full picture, and the primary reason is a stable, testable function of the
inputs rather than a judgement call.

A difference of exactly zero minor units is not a difference. There is no tolerance band and no
floating-point comparison anywhere in this code path.

### Stage 3 — classification

Evaluated top to bottom; first match wins. The ordering encodes "which problem would make the
others untrustworthy" — you cannot interpret an amount difference if the record itself is a
duplicate or if we matched two payments to one record.

| # | Condition | Result | Case? |
| --- | --- | --- | --- |
| 1 | `provider.size > 1` | `DUPLICATE_PROVIDER_RECORD` | yes |
| 2 | `internal.isEmpty()` | `MISSING_INTERNAL` | yes |
| 3 | `provider.isEmpty()` | `MISSING_ON_PROVIDER` | yes |
| 4 | `internal.size > 1` | `AMBIGUOUS_MATCH` | yes |
| 5 | the covering settlement record is `INVALID` (§2.1) | `SETTLEMENT_RECORD_INVALID` | yes |
| 6 | any Stage 2 difference | first difference's reason | yes |
| 7 | otherwise | `MATCHED` | no |

**Why row 5 sits above attribute comparison.** A broken record makes every difference it could
produce a poor diagnosis. A USD settlement covering an EGP payment produces a currency difference
*and* a status difference, and reporting either of them tells an operator to compare amounts that
were never commensurable rather than telling them the document is wrong.

The attribute differences are still computed and recorded — they are the evidence for why the
record is in trouble — but the headline is the record itself. When nothing else disagrees, a
synthetic difference is added rather than leaving `differences[]` empty: a case with an empty
difference list and a reason nobody can act on is how a finding gets quietly closed.

**"Internal" means captured.** Row 2 reads `internal.isEmpty()`, and the internal side is built
from the **ledger**: a payment contributes an expected view only if it has a `CAPTURE` posting. A
payment the provider names but that never captured therefore has no internal record, and the
subject classifies as `MISSING_INTERNAL` — the provider reports money moved and our ledger holds
none. That is a real finding and it opens a case.

This is not a corner case invented for completeness. It arises whenever a provider event names a
transaction whose payment has not captured: `upsertProviderTransaction` writes the provider row
*before* the payment-state decision is taken, so a `payment.captured` webhook delivered against a
payment that is already `FAILED` leaves exactly this shape behind. The first implementation threw
instead of classifying, which failed the whole batch and orphaned it; `C-RECON-05` carries five
such subjects precisely so the behaviour is pinned rather than assumed.

Full reason enum:

```
MATCHED
MATCHED_NOT_SETTLED          -- agrees, but the PSP has not paid yet; informational, no case
AMOUNT_MISMATCH              -- detail carries component GROSS | FEE | NET and the delta
CURRENCY_MISMATCH
STATUS_MISMATCH
REFERENCE_MISMATCH           -- added in decision D3
SETTLEMENT_DATE_MISMATCH     -- added in decision D3
MISSING_ON_PROVIDER
MISSING_INTERNAL
DUPLICATE_PROVIDER_RECORD
AMBIGUOUS_MATCH
SETTLEMENT_RECORD_INVALID    -- the covering settlement contradicts itself or the payment (§2.1)
```

`MATCHED_NOT_SETTLED` exists because conflating "we agree" with "we are done" produces a
useless alert stream. A captured payment the PSP has simply not paid yet is normal and expected
during the settlement window; it is reported, not escalated.

## 5. Results

`reconciliation_results` — one immutable row per subject per batch.

| Column | Notes |
| --- | --- |
| `id` | ULID `rres_…` |
| `batch_id` | FK |
| `subject_key` | `(provider, extTxnId)` — unique per batch |
| `outcome` | The reason enum |
| `internal_payment_id` | nullable |
| `provider_transaction_id` | nullable |
| `expected_gross`, `expected_fee`, `expected_net`, `expected_currency`, `expected_status` | Our side |
| `actual_gross`, `actual_fee`, `actual_net`, `actual_currency`, `actual_status` | Their side |
| `delta_minor` | `expected_gross − actual_gross`, nullable when either side is absent |
| `differences` | `jsonb` array of every differing attribute |
| `case_id` | nullable, set when a case was opened or matched to an existing one |
| `classified_at` | |

Unique: `(batch_id, subject_key)`. No update path.

## 6. Cases

A non-`MATCHED` result opens or reuses a case.

| Column | Notes |
| --- | --- |
| `id` | ULID `rcs_…` |
| `case_number` | `BIGSERIAL`, displayed as `RC-104` |
| `batch_id` | Batch that first found it |
| `result_id` | First result that found it |
| `payment_id` / `provider_transaction_id` | Subject |
| `reason` | The reason enum |
| `severity` | `HIGH` for amount/status/currency and duplicates; `MEDIUM` for missing/ambiguous/reference; `LOW` for date |
| `status` | `OPEN` \| `INVESTIGATING` \| `RESOLVED` \| `WRITTEN_OFF` |
| `detected_at`, `resolved_at` | |
| `assigned_to`, `resolution_note`, `resolution_action`, `adjustment_ledger_transaction_id` | |
| `occurrence_count` | Incremented when a later batch re-detects the same subject and reason |

Case transitions:

```
OPEN ──▶ INVESTIGATING ──▶ RESOLVED            (terminal)
  │              │
  └──────────────┴──────▶ WRITTEN_OFF           (terminal)
```

- `OPEN → INVESTIGATING` requires `assigned_to`.
- `→ RESOLVED` requires `resolution_note` (≥ 20 chars). If money moved, the resolution **must**
  reference the `ledger_transactions.id` that corrected it, and the service verifies that
  transaction exists, is `POSTED`, and touches the payment in question. "Marked resolved with no
  explanation" is rejected at the API, not merely discouraged.
- `→ WRITTEN_OFF` requires a `resolution_action` from a closed enum
  (`PROVIDER_ERROR_CONFIRMED`, `INTERNAL_ERROR_CONFIRMED`, `DUPLICATE_OF_CASE`, `TIMING_DIFFERENCE`,
  `OTHER`) plus a note. Write-off is an accounting decision and it is deliberately more
  frictionful than resolution.
- Cases are never deleted. A case may be reopened only by an administrator, and doing so writes an
  audit event.

**One open case per subject per reason.** Enforced by a partial unique index:

```sql
CREATE UNIQUE INDEX ux_case_open_subject_reason
    ON reconciliation_cases (COALESCE(payment_id, '~'), provider_transaction_id, reason)
 WHERE status IN ('OPEN','INVESTIGATING');
```

The `COALESCE` is load-bearing, not decoration. In PostgreSQL a unique index treats every `NULL` as
distinct from every other `NULL`, so indexing `payment_id` bare would let a `MISSING_ON_PROVIDER`
subject — which has no internal payment and therefore no `payment_id` — open a brand new case on
every batch forever. The index as written in an earlier draft of this spec was that index, and the
migration has always had the correct one.

So re-running reconciliation over the same window increments `occurrence_count` on the existing
case instead of creating a pile of identical cases. This is a real operational requirement — the
first version of any reconciliation system that gets this wrong is deleted by its users within a
week.

## 7. Batch lifecycle and crash recovery

`reconciliation_batches.status`: `RUNNING` → `COMPLETED` \| `FAILED`.

A batch created with `async: true` is picked up by a scheduler (`ReconciliationWorker.poll`, cadence
`reconcile.reconciliation.poll-interval`) rather than by the request. A batch that cannot complete —
a subject that raises — is marked `FAILED` with `failure_reason` and audited, and is **not** retried.
It is never left in `RUNNING`: an orphaned batch is indistinguishable from a slow one, and after its
lease expires `/health` reports a system that is unhealthy for a reason no operator can find.

```
T1  BEGIN; insert batch (status RUNNING); insert subject_keys snapshot; COMMIT;
T2  BEGIN; insert audit RECONCILIATION_BATCH_STARTED; COMMIT;

T3  per subject, in its own transaction:
      INSERT INTO reconciliation_results (batch_id, subject_key, ...) ON CONFLICT DO NOTHING
      -- 0 rows ⇒ this subject was already processed by a previous attempt; skip.
      upsert case (respecting the partial unique index)
      insert audit events
    COMMIT

T4  BEGIN; update batch → COMPLETED with counts and totals; audit; COMMIT;
```

Resume logic: on start, count processed subjects (`count(reconciliation_results where batch_id = ?`).
If it is less than the snapshot size, the worker **resumes** rather than restarting, processing
only unprocessed subject keys. `ON CONFLICT DO NOTHING` on the results insert means a subject
processed twice (crash between result insert and case insert within the same transaction — not
possible, but the guard costs nothing) still cannot produce two results.

Progress is visible via `GET /api/v1/reconciliation/batches/{id}`: `processed / total`, plus
running counts by outcome, updated every N subjects so the endpoint stays cheap.

**A reconciliation batch never posts to the ledger.** Corrections are separate, explicit,
case-attached actions. Reconciliation observes; it does not silently "fix" money — that would
destroy the audit trail and is the single most dangerous thing a system like this could do.

## 8. Totals

Each batch records summary figures used for the ops dashboard and for CI assertions:

```json
{ "subjects": 1000, "matched": 962, "matchedNotSettled": 12, "mismatched": 19,
  "missingOnProvider": 5, "missingInternal": 1, "duplicates": 1, "ambiguous": 0,
  "grossDeltaMinor": -300, "casesOpened": 26, "casesReused": 21 }
```

`grossDeltaMinor` is the net sum of all amount deltas. A batch whose `grossDeltaMinor != 0` is the
headline number an operator looks at first.

## 9. Evidence

| Test | Assertion |
| --- | --- |
| `C-RECON-01` | Identical internal/provider records → `MATCHED`, no case. |
| `C-RECON-02` | 100.00 vs 97.00 → `AMOUNT_MISMATCH`, `delta_minor = 300`, case opened. |
| `C-RECON-03` | `SETTLED` vs `REFUNDED` → `STATUS_MISMATCH`. |
| `C-RECON-04` | Internal only → `MISSING_ON_PROVIDER`; provider only → `MISSING_INTERNAL`. |
| `C-RECON-05` | 50-subject golden fixture → byte-identical result set on every run. |
| `C-RECON-06` | Two provider records for one ext id → `DUPLICATE_PROVIDER_RECORD` regardless of whether amounts also differ. |
| `C-RECON-07` | Two internal payments share one ext id → `AMBIGUOUS_MATCH`, no amounts compared. |
| `C-RECON-08` | Re-running a batch → `occurrence_count` increments, **no** second case row. |
| `C-RECON-09` | Worker killed at subject 500/1000, restarted → completes, 1000 results, 0 duplicates, correct totals. |
| `C-RECON-10` | Case resolved without a note → `422`; with a note and a valid adjustment transaction → `200`, and `GET /audit` shows the full trail. |
| `C-RECON-11` | Currency difference → `CURRENCY_MISMATCH`, and it takes precedence over an amount difference. Amount attributes are not compared at all in this case and no delta is produced, since comparing two different currencies arithmetically is meaningless. Non-monetary differences are still compared. |
| `C-RECON-12` | Partial settlement → `AMOUNT_MISMATCH` with component `NET` and the exact delta. |
| `C-RECON-13` | Two overlapping batches produce independent, immutable result sets. |