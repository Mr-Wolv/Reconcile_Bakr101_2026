# 07 — Failure Matrix

A financial system is defined less by what it does when things work than by what it does when
they do not. This is the complete list of failures v1 designs for.

Every row has a **verifying test**, and every test is listed in
[08-test-matrix-and-dod](08-test-matrix-and-dod.md). A row with no test is a wish.

Legend for **Behaviour**:
`handled` — absorbed, correct result, nothing for a human to do.
`visible` — absorbed, and a human is told.
`blocked` — refused before any state changes.

---

## 1. Client-driven failures

| # | Scenario | Detection | Behaviour | Resulting state | Operator action | Test |
| --- | --- | --- | --- | --- | --- | --- |
| F-01 | Client retries `POST /payments` with the same key | Idempotency record found, fingerprint matches | handled | One payment; identical response replayed with `Idempotency-Replayed: true` | none | `I-IDEM-01` |
| F-02 | Same key, different body | Fingerprint differs | blocked | No change; `409 IDEMPOTENCY_KEY_CONFLICT` | use a new key | `I-IDEM-02` |
| F-03 | 32 concurrent retries of one key | Unique-index contention | handled | Exactly one payment; 32 identical bodies | none | `I-IDEM-03` |
| F-04 | Request is still executing when a duplicate arrives | Record found in `IN_PROGRESS` | visible | `409 REQUEST_IN_PROGRESS`, `Retry-After: 1` | retry after 1 s | `I-IDEM-03` |
| F-05 | Retried request whose original failed on business rules | Stored 4xx | handled | Same 4xx replayed; no state change | none | `I-IDEM-04` |
| F-06 | Original attempt died with a 5xx | Record released | handled | Key free; retry executes cleanly, one ledger posting | none | `I-IDEM-05` |
| F-07 | Key replayed after the 24 h retention window | No record | visible | Executes again as a new command | documented limitation | `C-IDEM-06` |
| F-08 | Illegal state transition, e.g. `CREATED → SETTLED` | State machine table | blocked | `409 PAYMENT_INVALID_STATE`, no ledger effect | none | `U-PAY-01` |
| F-09 | Two concurrent captures of one payment | `SELECT … FOR UPDATE` on the payment | blocked | One capture; second sees `CAPTURED`, `409` | none | `C-CONC-02` |
| F-10 | Capture amount ≠ authorised amount | Amount comparison | blocked | `409 CAPTURE_AMOUNT_MISMATCH`, no ledger effect | none | `U-PAY-03` |
| F-11 | Refund of a payment that was never captured | State machine | blocked | `409 PAYMENT_INVALID_STATE` | none | `U-PAY-04` |
| F-12 | Malformed or oversized request body | Body limit / JSON binding | blocked | `413` / `400`, nothing persisted | none | `E2E-API-02` |
| F-13 | Unknown field in a payment request | `FAIL_ON_UNKNOWN_PROPERTIES` | blocked | `400 VALIDATION_FAILED` — the field is never silently dropped | fix the client | `E2E-API-03` |
| F-14 | Caller has insufficient role | Spring Security | blocked | `403 FORBIDDEN`, audited | request the right role | `E2E-SEC-02` |

## 2. Provider / external failures

| # | Scenario | Detection | Behaviour | Resulting state | Operator action | Test |
| --- | --- | --- | --- | --- | --- | --- |
| F-15 | Webhook signature invalid | HMAC comparison fails | blocked | `401`; delivery row `REJECTED_SIGNATURE`; no event row | fix the secret | `I-WEB-03` |
| F-16 | Webhook timestamp outside ±300 s | Clock skew check | blocked | `401`; delivery row `REJECTED_TIMESTAMP` | none (usually a retry) | `I-WEB-04` |
| F-17 | Same webhook delivered 3× | Unique `(provider, event_id)` | handled | 3 delivery rows, **1** event row, 1 payment transition, 1 ledger posting | none | `I-WEB-02` |
| F-18 | Webhook arrives in the wrong order | Transition table + `provider_occurred_at` | handled | Stale event marked `NO_EFFECT`, audit records both states | none | `I-WEB-06` |
| F-19 | Webhook arrives before the payment exists | No matching payment | visible | Event `FAILED`, backoff, retried up to 8× | none if the payment appears in time | `I-WEB-09` |
| F-20 | Provider event permanently unprocessable | 8 attempts exhausted | visible | `status = FAILED`, `WEBHOOK_FAILED` audited, exposed by `/provider/events/failed` | replay or correct manually | `I-WEB-10` |
| F-21 | Webhook body > 256 KiB | bounded read of at most `limit + 1` bytes, whether or not `Content-Length` was sent | blocked | `413`, delivery row `TOO_LARGE`, connection closed rather than drained | none | `E2E-WEB-04`, `F-04` |
| F-22 | Valid JSON that re-serialises differently | Verification uses the raw bytes | handled | `202` — proves raw-byte signing | none | `I-WEB-05` |
| F-23 | Provider call times out during `provider/sync` | Client timeout | visible | `503 PROVIDER_UNAVAILABLE`; **no** partial ingestion | retry | `C-PROV-02` |
| F-24 | Settlement record references an unknown provider transaction | FK / lookup miss at ingestion | visible | Line stored unresolved; warning audit; no cash recognised | investigate | `C-PROV-03` |
| F-25 | **Webhook never arrives at all** (the common real failure) | Reconciliation finds `MISSING_ON_PROVIDER` | visible | Case opened | run `provider/sync` for the window | `E2E-DEMO-02` |
| F-26 | Settlement record re-delivered | Unique `(provider, provider_settlement_id)` | handled | One settlement record; ledger posts once via `ux_ledger_source` | none | `C-PROV-04` |

## 3. Ledger / data-integrity failures

| # | Scenario | Detection | Behaviour | Resulting state | Operator action | Test |
| --- | --- | --- | --- | --- | --- | --- |
| F-27 | Posting command does not balance | Java validator, then deferred trigger | blocked | `422 LEDGER_IMBALANCE`; nothing written | fix the caller | `U-LED-02` |
| F-28 | Same business fact posted twice | `ux_ledger_source` | blocked | `409 LEDGER_ALREADY_POSTED`; one posting survives | none | `I-LED-03` |
| F-29 | Posting would drive an asset negative | `tg_asset_non_negative` | blocked | Transaction rolls back; error names the account and amount | investigate | `U-LED-04` |
| F-30 | Reversal of an already-reversed transaction | `ux_ledger_reversal` | blocked | `409`; originals untouched | none | `U-LED-05` |
| F-31 | Reversal payload ≠ exact mirror | Domain validator | blocked | `422`; no reversal written | fix the caller | `U-LED-06` |
| F-32 | An `UPDATE` or `DELETE` is attempted on ledger history | `fn_deny_mutation` trigger | blocked | Exception, even from a raw `psql` session | none — this is the point | `I-LED-04` |
| F-33 | Two postings touch the same accounts in opposite order | Sorted `FOR UPDATE` lock order | handled | Serialised; deadlock structurally impossible | none | `C-CONC-01` |
| F-34 | Deadlock / lock timeout despite that | Spring exception translation | handled | Up to 3 retries with jitter; `ux_ledger_source` makes retries safe | none | `C-CONC-03` |
| F-35 | `account_balances` drifts from the entries (bug, restore, or manual edit) | `LedgerVerifier` (L7) | visible | `LEDGER_BALANCE_BREACH`; `/health` → `503`; metric per account | investigate | `I-LED-05` |

F-35 deliberately **does not self-repair**. A silently corrected balance destroys the only
evidence that the bug happened. The system reports the breach and refuses to call itself healthy.

## 4. Infrastructure failures

| # | Scenario | Detection | Behaviour | Resulting state | Operator action | Test |
| --- | --- | --- | --- | --- | --- | --- |
| F-36 | Database unreachable during command | Connection failure | visible | `500`/`503`; transaction rolled back; **no** partial write | retry with the same key | `I-IDEM-05` |
| F-37 | Database dies mid-command | Transaction rollback | handled | Nothing persisted; the key is free again | retry | `I-FAIL-01` |
| F-38 | Worker claims an event, then the process dies | `locked_at` older than 5 min | handled | Sweep reclaims it; processed exactly once | none | `I-WEB-07` |
| F-39 | Reconciliation worker dies at subject 500 of 1000 | Processed count < snapshot size | handled | Batch stays `RUNNING`; restart resumes at 501 | none | `C-RECON-09` |
| F-40 | Two workers run the same batch | `FOR UPDATE SKIP LOCKED` claiming | handled | Each subject claimed once; 1000 results, 0 duplicates | none | `C-CONC-04` |
| F-41 | Event processing throws a non-retryable error | Typed exception handling | visible | `FAILED` immediately, no backoff attempts wasted | fix and replay | `I-WEB-10` |
| F-42 | Process killed between the ledger commit and the response | Idempotency record already committed | handled | Client retry replays the stored response | none | `I-IDEM-01` |
| F-43 | Clock skew on the app host | Timestamp check uses host time | visible | Legitimate webhooks rejected | fix NTP | documented |
| F-44 | Migration fails on deploy | Flyway checksum / execution | blocked | App refuses to start; no schema is half-upgraded | fix the migration | `E2E-MIG-01` |

## 5. Payout failures

| # | Scenario | Detection | Behaviour | Resulting state | Operator action | Test |
| --- | --- | --- | --- | --- | --- | --- |
| F-57 | Payout includes a payment that is not `SETTLED` | State check per payment | blocked | `422 PAYOUT_PAYMENT_NOT_SETTLED`; no posting, no lines | settle it first | `I-PAYOUT-01` |
| F-58 | Payout re-pays a payment already in a payout | `ux_payout_line_payment` (L11) | blocked | `409 PAYOUT_PAYMENT_ALREADY_PAID`; nothing written | none — the guard is the point | `I-PAYOUT-02` |
| F-59 | Payout exceeds available `PLATFORM_CASH` | `tg_asset_non_negative` (L8) | blocked | `409 PAYOUT_INSUFFICIENT_CASH`; nothing written | settle more, or pay a smaller batch | `I-PAYOUT-03` |
| F-60 | Payout mixes merchants or currencies | Per-line merchant and currency check | blocked | `422`; nothing written | fix the request | `I-PAYOUT-04` |
| F-61 | Two concurrent payouts name the same payment | Row lock on the payments, then L11 | blocked | One succeeds, one gets `409` | none | `C-CONC-05` |
| F-62 | Refund of a payment already paid out | `paid_out_at` guard on T7 | blocked | `409 PAYMENT_ALREADY_PAID_OUT`; no posting | resolve via a case-attached adjustment | `I-PAYOUT-05` |
| F-63 | Payout submitted twice with the same idempotency key | Idempotency record | handled | One payout; identical response replayed | none | `I-PAYOUT-06` |

## 6. Reconciliation-domain failures

| # | Scenario | Detection | Behaviour | Resulting state | Operator action | Test |
| --- | --- | --- | --- | --- | --- | --- |
| F-45 | Provider reports a different amount | Attribute comparison | visible | `AMOUNT_MISMATCH`, `delta_minor` recorded, case opened | resolve or adjust | `C-RECON-02` |
| F-46 | Provider reports a different status | Attribute comparison | visible | `STATUS_MISMATCH`, case opened | investigate | `C-RECON-03` |
| F-47 | Provider reports a different currency | Attribute comparison (checked first) | visible | `CURRENCY_MISMATCH`, case opened | investigate | `C-RECON-11` |
| F-48 | Provider record we have never seen | Identity stage | visible | `MISSING_INTERNAL`, case opened | investigate | `C-RECON-04` |
| F-49 | Our captured payment the provider never reported | Identity stage | visible | `MISSING_ON_PROVIDER`, case opened | run `provider/sync` | `C-RECON-04` |
| F-50 | Same provider transaction appears twice | Identity stage, checked first | visible | `DUPLICATE_PROVIDER_RECORD`, case opened — amounts are not even compared | investigate | `C-RECON-06` |
| F-51 | Two internal payments share one external id | Identity stage | visible | `AMBIGUOUS_MATCH`, no auto-match, case opened | fix the mapping | `C-RECON-07` |
| F-52 | Settlement covers only part of a payment | Amount comparison | visible | `AMOUNT_MISMATCH` with the component named | resolve | `C-RECON-12` |
| F-53 | Re-running the same batch | Partial unique index on open cases | handled | `occurrence_count` increments; **no** second case | none | `C-RECON-08` |
| F-53b | A case cites an adjustment that does not touch the payment | Verification on resolve | blocked | `422`; the case stays open | cite the correct posting | `C-RECON-10` |
| F-54 | A case is marked resolved with no explanation | API + `ck_case_resolution` | blocked | `422 CASE_RESOLUTION_INCOMPLETE` | write a real note | `C-RECON-10` |
| F-55 | A resolution cites a ledger transaction that does not exist or is unrelated | Verification on resolve | blocked | `422`; case stays open | cite the correct posting | `C-RECON-10` |
| F-56 | Reconciliation batch window overlaps a previous one | Half-open intervals; no enforcement | handled | Two batches may legitimately cover the same window; each has its own immutable results | none | `C-RECON-13` |

## 7. Known limitations (documented, not hidden)

These are real gaps. They are listed here rather than left to be discovered, because a README
that lists its own limitations is more credible than one that lists only its features.

| # | Limitation | Consequence | Planned |
| --- | --- | --- | --- |
| L-1 | No sweeper auto-fails expired payments | An abandoned `CREATED` payment stays open until someone acts; capture is refused with `410` | v1.5 |
| L-2 | Static bearer tokens, no user store, no token rotation | Not a production auth design | OIDC before any real deployment |
| L-3 | No rate limiting (headers only) | The webhook endpoint is protected by signature verification, not by throttling | v1.5 |
| L-4 | Reconciliation holds its population snapshot forever | Long-lived batches are unaffected by later data, which is intended, but storage grows | archive policy, v1.5 |
| L-5 | Audit and entry tables are never pruned | Unbounded growth; fine for a demo scale, wrong for production | retention policy, v1.5 |
| L-6 | Single application instance assumed for in-process scheduled workers | `SKIP LOCKED` makes multi-instance safe, but the schedule is not distributed | leader election, if ever needed |
| L-7 | No merchant balance recovery | A refund of an already-paid-out payment is refused rather than funded (D10); handling it needs a merchant receivable and a platform float | v1.5 |
| L-8 | The PSP simulator is in-process | It cannot demonstrate cross-process failure modes | out of scope |

---

## 8. Demo scenarios mapped to failures

The four README demonstrations are not illustrations; each one is a cluster of rows above.

| Demo | Rows exercised |
| --- | --- |
| **1. Happy path → `MATCHED`** | F-01, F-08, F-33 (the demo also runs a payout, so the ledger unwinds to `PLATFORM_CASH = 3.00` and `PLATFORM_FEE_REVENUE = 3.00`) |
| **2. Wrong settlement amount → case → resolve** | F-45, F-54, F-55 |
| **3. One webhook delivered three times** | F-17, F-18, F-22 |
| **4. Worker crash → restart → resume** | F-38, F-39, F-40 |