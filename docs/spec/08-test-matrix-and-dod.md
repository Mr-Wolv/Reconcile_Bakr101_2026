# 08 — Test Matrix and Definition of Done

Tests are the evidence. A claim that the system is correct is worthless without the test that
proves it, and a test nobody can find does not count as evidence either — so every test has a
stable ID that appears in this document, in the
[failure matrix](07-failure-matrix.md), and in the failure message when it fails.

**Counts are not declared here.** The numbers in the README are the measured output of a CI run,
never an estimate written in advance.

---

## 1. Layers

| Layer | Speed | Needs | Proves |
| --- | --- | --- | --- |
| `U-` Unit | < 100 ms | nothing | Domain rules in isolation |
| `I-` Integration | seconds | Testcontainers PostgreSQL 18.6 | Schema, migrations, constraints, locking, real transactions |
| `C-` Contract / concurrency | seconds | PostgreSQL + WireMock | Provider behaviour, races, resume |
| `E2E-` Scenario | ~1 min | full app + Postgres + provider simulator | The four demonstrations in the README |

Integration and contract tests share one PostgreSQL container per module and clean between
tests with `TRUNCATE … RESTART IDENTITY CASCADE`, except the migration tests, which get a fresh
container. That keeps the suite fast enough to run on every push without making the tests
interdepend.

---

## 2. Unit tests

### Money

| ID | Assertion |
| --- | --- |
| `U-MONEY-01` | `Money(-1, EGP)` throws. `Money` cannot represent a negative amount. |
| `U-MONEY-02` | Adding `100.00 EGP` to `1.00 USD` throws `IllegalArgumentException`. |
| `U-MONEY-03` | `Money` rejects an unsupported currency and rejects a minor amount that violates the exponent. |
| `U-MONEY-04` | Fee policy: `gross = net + fee` holds for a table of amounts and bps, including the rounding boundary at 1 minor unit and an odd bps value. |
| `U-MONEY-05` | Arithmetic on `long` cannot overflow within the supported amount range; the range check rejects absurd values instead of wrapping. |
| `U-MONEY-06` | **Every** main source is read and checked for `double`, `float`, `Double` or `Float`; `BigDecimal` is confined to `Money` by name. A floor on the file count and a self-check prove the scan is not vacuous. Added in this pass: `U-RECON-04` covered four files, which is not the claim. |

### Ledger

| ID | Assertion |
| --- | --- |
| `U-LED-01` | A balanced posting is accepted; debits equal credits. |
| `U-LED-02` | An unbalanced posting is rejected **before** any repository call is made. |
| `U-LED-03` | A mixed-currency posting is rejected; a single-currency posting is accepted. |
| `U-LED-04` | A posting that would drive `PSP_CLEARING` negative is rejected; a posting that would drive `MERCHANT_PAYABLE` negative is accepted (clawback is legitimate). |
| `U-LED-05` | Reversal is an exact mirror; a second reversal of the same transaction is refused. |
| `U-LED-06` | A reversal whose entries differ from the original mirror is refused. |
| `U-LED-07` | A reversal may itself be reversed (chained), and the chain still balances. |
| `U-LED-08` | Posting to a `CLOSED` account is refused (L9). |
| `U-LED-09` | Worked example from [01 §3](01-money-and-ledger.md): capture 100.00 / fee 3.00 / net 97.00 produces exactly the documented entries. |
| `U-LED-10` | Full unwind: capture → settlement → payout leaves `PSP_CLEARING = 0`, `MERCHANT_PAYABLE = 0`, `PLATFORM_CASH = 3.00`, `PLATFORM_FEE_REVENUE = 3.00` — two non-zero accounts, because the platform's fee is both held and earned. Every transaction balances individually. |
| `U-LED-11` | A `MERCHANT_PAYOUT` whose total exceeds `PLATFORM_CASH` is refused before any write. |

### Payments

| ID | Assertion |
| --- | --- |
| `U-PAY-01` | **The full 6×6 transition matrix**: every pair is either in the table or rejected. A new state forces this test to be updated — which is the point. |
| `U-PAY-02` | State history gains exactly one row per transition, plus the initial row. |
| `U-PAY-03` | Capture with `expectedAmount` ≠ authorised amount is rejected. |
| `U-PAY-04` | Refunding a `CREATED`, `AUTHORIZED`, or `FAILED` payment is rejected. |
| `U-PAY-05` | Capture after `expires_at` is rejected with `PAYMENT_EXPIRED`. |
| `U-PAY-06` | `net + fee == gross` holds on every created payment regardless of amount shape. |

### Reconciliation classifier

| ID | Assertion |
| --- | --- |
| `U-RECON-01` | Classification precedence: duplicates beat missing-beats-ambiguous-beats-attribute-difference. |
| `U-RECON-02` | The primary reason is the **first** difference in the fixed comparison order; all differences are still recorded. The one exception is arithmetic, not reporting: when the two sides disagree on currency, the monetary attributes (`GROSS`, `FEE`, `NET`) are not compared and no `deltaMinor` is produced, because `100 EGP - 100 USD` is not a discrepancy but a type error. `CURRENCY_MISMATCH` is still recorded and still wins the primary reason, and the non-monetary differences (status, reference, settlement date) are still compared and recorded. See `C-RECON-11`. |
| `U-RECON-03` | A zero delta is not a difference; a 1-minor-unit delta is. |
| `U-RECON-04` | Comparison uses integer minor units only — the classifier source file contains no `double`/`float`/`BigDecimal` (an ArchUnit-style source assertion, not a comment). |

### Webhook signature

| ID | Assertion |
| --- | --- |
| `U-SIG-01` | Signature is `HMAC-SHA256(secret, timestamp + "." + rawBody)`, lowercase hex; a one-byte body change invalidates it. |
| `U-SIG-02` | Timestamps at exactly ±300 s are accepted; ±301 s are rejected (boundary, not approximate). |
| `U-SIG-03` | Comparison is constant-time (verified by asserting `MessageDigest.isEqual` is used, not `String.equals`). |

---

## 3. Integration tests (Testcontainers PostgreSQL 18.6)

### Schema and migrations

| ID | Assertion |
| --- | --- |
| `I-MIG-01` | `V1__baseline.sql` runs on an empty database with no errors; `flyway validate` then reports no pending changes and a clean checksum history. |
| `I-MIG-02` | Re-running the app against a migrated database is a no-op (idempotent startup). |
| `E2E-MIG-01` | A deliberately failing migration leaves the schema at the previous version and the app refuses to start. |

### Ledger

| ID | Assertion |
| --- | --- |
| `I-LED-01` | Capture writes exactly three entries and moves `PSP_CLEARING`, `MERCHANT_PAYABLE`, `PLATFORM_FEE_REVENUE` by the documented amounts. |
| `I-LED-02` | Settlement ingestion moves value from `PSP_CLEARING` to `PLATFORM_CASH` exactly once. |
| `I-LED-03` | A second posting for the same `(source_type, source_id, type)` is refused by the unique constraint and surfaces as `409 LEDGER_ALREADY_POSTED`. |
| `I-LED-04` | `UPDATE` and `DELETE` on `ledger_transactions`, `ledger_entries`, `audit_events`, `payment_state_history`, `reconciliation_results`, `case_events` all raise — executed from raw JDBC, not from the application. |
| `I-LED-05` | An out-of-band balance edit (via a session that disables the balance trigger, simulating restore-from-backup corruption) is detected by `LedgerVerifier`, reported as `LEDGER_BALANCE_BREACH`, and `/health` returns `503`. |
| `I-LED-06` | A transaction whose entries do not balance is refused **at COMMIT** by the deferred constraint trigger, even when inserted by raw SQL that bypasses the application. |

### Payouts

| ID | Assertion |
| --- | --- |
| `I-PAYOUT-01` | A payout naming a non-`SETTLED` payment is refused; no posting, no lines. |
| `I-PAYOUT-02` | Re-paying a payment already in a payout is refused by `ux_payout_line_payment`; the first payout is untouched. |
| `I-PAYOUT-03` | A payout exceeding `PLATFORM_CASH` is refused by the L8 trigger; balances unchanged. |
| `I-PAYOUT-04` | Mixed merchants or mixed currencies are refused; `422`. |
| `I-PAYOUT-05` | Refunding a paid-out payment returns `409 PAYMENT_ALREADY_PAID_OUT` and posts nothing; `PLATFORM_CASH` does not move. |
| `I-PAYOUT-06` | The same payout submitted twice with one idempotency key produces one record, one posting, and two identical responses. |
| `I-PAYOUT-07` | Net amounts are read from the ledger, not the request: a payout request cannot specify its own amount. |

### Idempotency

| ID | Assertion |
| --- | --- |
| `I-IDEM-01` | Same key + same body twice → one payment row, byte-identical response, `Idempotency-Replayed: true`. |
| `I-IDEM-02` | Same key + different body → `409`, and no second payment exists. |
| `I-IDEM-03` | 32 threads, same key, same body → exactly one payment, 32 identical responses, no `5xx`. |
| `I-IDEM-04` | Business failure replays identically and changes nothing. |
| `I-IDEM-05` | An injected `DataAccessResourceFailureException` releases the key; the retry succeeds and posts exactly one ledger transaction. |

### Webhooks

| ID | Assertion |
| --- | --- |
| `I-WEB-01` | Valid signature → `202`, one delivery row, one event row. |
| `I-WEB-02` | Same event id ×3 → 3 delivery rows, **one** event row, one payment transition, one ledger posting. |
| `I-WEB-03` | Invalid signature → `401`, delivery `REJECTED_SIGNATURE`, no event row, no business effect. |
| `I-WEB-04` | Timestamp −600 s → `401`; timestamp now → `202`. |
| `I-WEB-05` | Valid JSON whose re-serialisation differs from the raw bytes → `202` (proves raw-byte signing). |
| `I-WEB-06` | `payment.captured` after `payment.refunded` → payment stays `REFUNDED`, event `NO_EFFECT`, audit records both states. |
| `I-WEB-07` | Row stranded in `PROCESSING` by a killed worker is reclaimed after the stale sweep and processed exactly once. |
| `I-WEB-08` | Handler invoked with the database down → `500`, nothing partially written, redelivery succeeds. |
| `I-WEB-09` | Event referencing a payment that does not exist yet → `FAILED` with backoff, then succeeds once the payment appears. |
| `I-WEB-10` | A non-retryable error fails the event immediately (attempts == 1) and surfaces on `/provider/events/failed`. |
| `C-WEB-11` | An event applied *after* a settlement does not regress `provider_transactions.provider_status` from `SETTLED`. Added by the final audit after defect 21; every other test delivers events in order, and the defect needs the worker to apply them out of it. Found by `scripts/probe.sh`, not by the suite. |
| `C-ORD-01` | All **24** orderings of `payment.created` / `payment.authorized` / `payment.captured` / `settlement.paid` on a captured payment converge on `SETTLED`, post `PAYMENT_CAPTURE` and `SETTLEMENT_RECEIVED` once each, leave the ledger balanced, and reconcile with no discrepancy. Complete, not sampled. |
| `C-ORD-02` | All **6** orderings of a webhook-only lifecycle — the payment reaches `CAPTURED` only through events — converge. This is spec 03 §B5's apply-and-flag row, and the one an in-order suite structurally cannot reach. |
| `C-ORD-03` | All **6** orderings of `captured` / `settlement.paid` / `refunded` end `REFUNDED` on both sides with exactly one refund posting, and every ordering yields the *same* reconciliation verdict. |
| `C-ORD-04` | 16 payments, 64 events, 16 senders released on a barrier, 8 workers draining through `FOR UPDATE SKIP LOCKED`: no event fails, none is stranded, all 16 reconcile `MATCHED`. Seed printed and overridable with `-Dreconcile.ordering.seed`. |
| `E2E-WEB-04` | Body of 300 KiB → `413` with a `TOO_LARGE` delivery row. |

### Settlements and audit

| ID | Assertion |
| --- | --- |
| `I-SETTLE-01` | A settlement record covering three payments posts one `SETTLEMENT_RECEIVED` and transitions exactly those three payments to `SETTLED`. |
| `I-SETTLE-02` | Re-delivering the same settlement record posts nothing further. |
| `I-AUDIT-01` | One captured, settled, reconciled payment yields the complete trail: payment created/authorized/captured/settled, ledger posted ×2, events received ×n, batch started/completed, case opened/resolved — all linked by `request_id`. |

### Failure injection

| ID | Assertion |
| --- | --- |
| `I-FAIL-01` | Database connection killed mid-command → rollback, no partial write, retry with the same key succeeds. |

---

## 4. Contract and concurrency tests

### Concurrency

| ID | Assertion |
| --- | --- |
| `C-CONC-01` | **32 threads** post 32 distinct transactions against `PSP_CLEARING`/`MERCHANT_PAYABLE`. Asserts: ledger balances; `PSP_CLEARING` == Σ expected; entry count exactly right; **no deadlock errors observed**. |
| `C-CONC-02` | 16 threads capture one payment → exactly one `PAYMENT_CAPTURE`, 15 × `409`. |
| `C-CONC-03` | Forced lock contention retries at most 3 times and then fails loudly — and the retry cannot double-post (the uniqueness constraint proves it). |
| `C-CONC-04` | Two workers run the same batch simultaneously → 1000 results, 0 duplicates, correct totals. |
| `C-CONC-05` | Two concurrent payouts naming the same payment → exactly one succeeds, one `409`, and the merchant is paid once. |

### Reconciliation

| ID | Assertion |
| --- | --- |
| `C-RECON-01` | Identical sides → `MATCHED`, no case. |
| `C-RECON-02` | 100.00 vs 97.00 → `AMOUNT_MISMATCH`, `delta_minor = 300`, one case. |
| `C-RECON-03` | `SETTLED` vs `REFUNDED` → `STATUS_MISMATCH`. |
| `C-RECON-04` | Internal-only → `MISSING_ON_PROVIDER`; provider-only → `MISSING_INTERNAL`. |
| `C-RECON-05` | **Golden fixture**: 50 subjects covering the nine outcomes the schema permits — ten per batch of five, because `MISSING_INTERNAL` has two distinct shapes worth pinning separately; result set is byte-identical across runs and across machines. |
| `C-RECON-06` | Two provider records for one external id → `DUPLICATE_PROVIDER_RECORD`, amounts not compared. |
| `C-RECON-07` | Two internal payments sharing one external id → `AMBIGUOUS_MATCH`, no auto-match. |
| `C-RECON-08` | Re-running the batch → `occurrence_count` increments, **no** second case row. |
| `C-RECON-09` | Worker killed at subject 500/1000, restarted → 1000 results, 0 duplicates, correct totals, batch `COMPLETED`. |
| `C-RECON-10` | Resolve without a note → `422`; with a note but a bogus adjustment reference → `422`; with both → `200` and the full audit trail. |
| `C-RECON-11` | Currency difference → `CURRENCY_MISMATCH` and it takes precedence over an amount difference. |
| `C-RECON-12` | Partial settlement → `AMOUNT_MISMATCH` with component `NET` and the exact delta recorded. |
| `C-RECON-13` | Two overlapping batches → independent, immutable result sets; neither is affected by the other. |
| `C-RECON-14` | `DUPLICATE_PROVIDER_RECORD` and `AMBIGUOUS_MATCH`, produced by dropping the unique keys the API relies on to refuse them, with the keys restored afterwards. Added in this pass: both were unreachable through the API, so both classifier branches had no evidence. |
| `C-RECON-15` | The two sides are independent: corrupting `payments.amount_minor` split-consistently does **not** move the outcome, and corrupting the provider's row does. Added in this pass. |

### Provider behaviour

| ID | Assertion |
| --- | --- |
| `C-PROV-01` | A settlement record covering an unknown provider transaction is stored unresolved, warns, and recognises no cash. |
| `C-PROV-02` | Provider timeout during `provider/sync` → `503`, no partial ingestion, safe retry. |
| `C-PROV-03` | Malformed provider response body → `502`, nothing ingested. |
| `C-PROV-04` | Duplicate settlement delivery → one record, one posting. |

### Architecture

| ID | Assertion |
| --- | --- |
| `C-ARCH-01` | ArchUnit: `shared` depends on nothing; `ledger` does not depend on `payment`; the domain packages contain no Spring or JPA imports. |
| `C-IDEM-06` | A key replayed after the retention window is treated as new — the documented behaviour, asserted so it cannot change silently. **This test found defect 20**: the reservation path did not check expiry, so a key stayed replayable until the sweeper happened to run. |

---

## 5. End-to-end scenarios

These are the four README demonstrations, as automated tests. If one of these fails, the README's
claim is false, and the test says so.

| ID | Scenario | Asserts |
| --- | --- | --- |
| `E2E-DEMO-01` | Create → authorize → capture → settle → reconcile → `MATCHED` → payout | One payment, correct ledger entries, `PSP_CLEARING` back to 0, one `MATCHED` result, zero cases, and after the payout only `PLATFORM_CASH` and `PLATFORM_FEE_REVENUE` are non-zero. |
| `E2E-DEMO-02` | Capture → PSP reports a wrong amount → reconcile → `AMOUNT_MISMATCH` → case → resolve | `delta_minor` recorded, case `OPEN`, resolve without a note → `422`, resolve with a note → `RESOLVED`, audit trail complete, and re-running reconciliation does not open a second case. |
| `E2E-DEMO-03` | One webhook delivered three times | 3 delivery rows, 1 event, 1 state change, 1 ledger posting, and the audit trail shows all three deliveries. |
| `E2E-DEMO-04` | Reconciliation worker crashes mid-batch and restarts | Batch completes, exactly one result per subject, no duplicate ledger posting, balances still verify. |
| `E2E-API-01` | OpenAPI drift check | The committed `docs/openapi/reconcile-v1.yaml` matches the running application's specification. |
| `E2E-API-02` | Malformed and oversized bodies | `400` and `413`, nothing persisted. **Covered under other IDs**, so this row has no test of its own: `413` by `E2E-WEB-04`, `400` for a malformed payment body by `ApiHttpIT.unknownFieldOnPaymentRequestIsRefused` (a body that does not bind) |
| `E2E-API-03` | Unknown JSON field on a payment request | `400` `MALFORMED_REQUEST` per the error table, and no payment row. Added in this pass; the setting was configured and unasserted. |
| `E2E-SEC-01` | Unauthenticated and wrong-role calls | `401` / `403`; all seven admin-only routes are refused for an operator token, over the real filter chain. `ApiHttpIT.bearerTokenIsAccepted` (`401`), `ApiHttpIT.rolesAreEnforcedOverHttp` and `ApiHttpIT.everyAdminOnlyRouteIsRefusedForAnOperator` (`403`). Added in this pass: the route-table walk previously existed only as a single `/payments/*/fail` call, so six admin-only routes were configured but unexercised |
| `E2E-API-07` | `GET /ledger/transactions/{id}` | `200` with the transaction **and its entries** — the investigative view is the point of the endpoint — and `404 RESOURCE_NOT_FOUND` for an unknown id, never an empty `200`. |
| `E2E-API-08` | `POST /ledger/transactions/{id}/reverse` | `200` with the new id; L4 pinned by reading the database (original still `POSTED`, reversal points at it, every entry is the sign-flipped mirror); `404` for an unknown id, with and without a body. |
| `E2E-API-09` | `GET /reconciliation/batches/{id}` | `200` with the window it ran over, its `COMPLETED` status and the summary counts; `404` for an unknown id. |
| `E2E-API-10` | `POST /reconciliation/cases/{id}/write-off` | Both fields bind; `400` when `resolutionAction` is missing or the note is under its floor, leaving the case `OPEN`; `204` with an empty body on success; `404` for an unknown case. |
| `E2E-API-11` | `GET /provider/events/failed` | `200` with `count` and the backlog, naming each dead event and why; an event still retrying is not reported as dead. |
| `E2E-SEC-02` | The app refuses to start outside `dev` with a default webhook secret | Startup fails with a named reason. **Covered at unit level by `SecurityConfigIT`** (`ReconcilePropertiesTest` too), which asserts the guard raises for an unknown profile. It does **not** boot a real application and watch it fail — that half is untested |

---

## 6. CI pipeline

Four workflows. Two of them — `verify` and `security` — have executed on a hosted runner and are
green on `main` as of `d1e9af8`; `release` and `dependency-scan` have not, for reasons that are
about their triggers rather than about the repository (see §7a). What follows describes what each
one does, and `scripts/check_workflows.py` remains the one check here that does not need a runner.

| Workflow | Trigger | What it gates |
| --- | --- | --- |
| `verify.yml` | push to `main`, any pull request, nightly at 03:41 UTC, manual | The full gate: the same `docker run … mvn verify` a developer runs locally, then `scripts/check_suites.py` parsed from the XML reports, then a separate `probe` job that builds the image with compose and runs `scripts/probe.sh` against it. A third `soak` job runs `EventOrderingIT`'s randomised leg, seeded from the UTC date, and gates — it never runs on a push, so it cannot block a merge |
| `release.yml` | a `v*` tag | Re-runs the whole gate plus the image build and the probe against the tagged commit, so a tag cannot point at a tree that does not pass |
| `security.yml` | push to `main`, any pull request, twice weekly (Mon/Thu 04:23 UTC), manual | OSV dependency scan (gated on the triaged baseline), Semgrep SAST over `p/java` at `ERROR` severity — which also fails when the scan loaded zero rules, because a scan that did not run and a clean scan both report nothing — GitHub's dependency review on pull requests, and a non-gating ZAP baseline scan whose report is uploaded |
| `dependency-scan.yml` | weekly, manual | OWASP dependency-check against the NVD. Scheduled and manual only: a cold NVD download is tens of minutes, and gating on it would fail pushes for reasons unrelated to the change |

Three properties worth stating, because most portfolios quietly lack them:

- **The Docker image is the tested artifact.** The probe job does not test a jar the image happens
  to contain; it starts the image.
- **The pipeline cannot report a number it did not measure.** Counts are parsed from the surefire
  XML reports, never written by hand into a badge.
- **The dependency gate fails on what is *new*.** `security.yml` compares OSV's answer against
  `docs/security/osv-baseline.json` — the seven advisories a person has already triaged and written
  down. A gate that reports the same findings forever is a gate nobody reads, and a baseline entry
  with no written reason behind it is a silenced finding, which is why the script that writes the
  baseline refuses to run unattended.

---

## 7. Definition of Done

Reconcile v1 is done when **every** box below is true and demonstrable from the repository. No box
is satisfied by intent.

### Domain and ledger

- [x] `Money` has no floating-point representation anywhere in the codebase (enforced by a source-scan test).
      — `U-MONEY-06` reads **all 72** main sources, not a hand-kept list, and checks both floating-point
      *types* and floating-point *literals* (F-10: `0.03d` has no floating-point type, only a value);
      `U-RECON-04` covers the
      comparison path. `BigDecimal` is confined to `Money` by name, for a stated reason.
- [x] Ledger invariants L1–L11 each have at least one automated test; L1, L2, L3, L4/L5, L6, L8, L10 and L11 are enforced by the database as well as by Java.
      — `LedgerInvariantsIT`, from raw JDBC rather than through the application.
- [x] Posted ledger rows cannot be updated or deleted, verified from raw JDBC.
      — `I-LED-04`.
- [x] A reversal is always a new transaction; the original is byte-identical before and after.
      — `U-LED-05`, `U-LED-06`.
- [x] `LedgerVerifier` reports zero discrepancies on a clean database and detects an injected corruption.
      — `I-LED-05`, `LedgerInvariantsIT`.
- [x] The worked example from [01 §3](01-money-and-ledger.md) is asserted by a test.
      — `U-LED-09`.
- [x] A full capture → settle → payout cycle unwinds the ledger to the two platform accounts, asserted by test.
      — `U-LED-10`.
- [x] A payment cannot be paid out twice (L11) or refunded after being paid out, both proven against a real database.
      — `I-PAYOUT-02`, `I-PAYOUT-05`.

### Payments

- [x] The complete 6×6 transition matrix is covered by one parameterised test.
      — `U-PAY-01`.
- [x] Every illegal transition returns `409 PAYMENT_INVALID_STATE`, not `404` and not `500`.
      — `U-PAY-01`, `U-PAY-04`, `U-PAY-05`.
- [x] Concurrent capture of one payment yields exactly one ledger posting.
      — `C-CONC-02`.

### Idempotency and webhooks

- [x] Same key + same body replays; same key + different body conflicts; both are proven.
      — `I-IDEM-01`, `I-IDEM-02`.
- [x] 32 concurrent retries of one key create exactly one payment.
      — `I-IDEM-03`.
- [x] Three deliveries of one webhook event produce one effect.
      — `I-WEB-02`, `E2E-DEMO-03`.
- [x] Invalid signature, stale timestamp, and oversized body are all refused and all recorded.
      — `I-WEB-03`, `I-WEB-04`, `E2E-WEB-04`, `E2E-WEB-04b`. The 413 path existed without a test
      until this pass; the ordering assertion in `E2E-WEB-04b` is what proves size is decided
      before the signature.
- [x] A webhook is durable before any business effect is applied.
      — `I-WEB-01`, `I-WEB-07`, and the controller's own structure.
- [x] Out-of-order events are recorded as `NO_EFFECT`, not silently dropped.
      — `I-WEB-06`.
- [x] A webhook delivered while the database is unreachable is a server fault that writes nothing,
      and the same event is accepted once it returns.
      — `I-WEB-08`, `WebhookWithoutDatabaseIT`. Added in this pass; the refusal path had no
      coverage, and the test asserts its own precondition (the role genuinely cannot connect) so
      it cannot pass for the wrong reason.

### Reconciliation

- [x] All eleven outcomes are produced, and the test that produces each one is named.
      — **Corrected in this pass.** The original box said the golden fixture produces all eleven.
      It produces nine; the fixture has 50 subjects (ten per batch of five, `MISSING_INTERNAL`
      appearing in both of its shapes). The remaining two,
      `DUPLICATE_PROVIDER_RECORD` and `AMBIGUOUS_MATCH`, are *unreachable through the API by
      design* — `C-RECON-06` and `C-RECON-07` prove the unique keys refuse the data. So the box is
      satisfied by the union: `C-RECON-05` for the nine, and `C-RECON-14` for the other two, which
      drops the constraint deliberately and restores it in a `finally`. An unreachable classifier
      branch is one nobody can tell apart from a deleted one.
- [x] Classification is deterministic (byte-identical results across runs).
      — `C-RECON-05` against the committed fixture.
- [x] Every non-`MATCHED` outcome opens or reuses a case; re-running never duplicates cases.
      — `C-RECON-08`.
- [x] A case cannot be resolved without a note, nor resolved citing a nonexistent adjustment.
      — `C-RECON-10`.
- [x] A batch interrupted at subject 500 resumes and completes with no duplicates and no
      double posting.
      — `C-RECON-09`.
- [x] Expected values are derived from the ledger, and a test proves the two sides are
      independent sources rather than one source compared with a copy of itself.
      — **Corrected in this pass.** The original box asked for a test proving that corrupting
      `payments.amount` makes reconciliation fail. It cannot: `ReconciliationService` never
      selects `payments.amount_minor` (ADR-0005 reads the capture posting's entries by account
      code instead), so that corruption provably cannot move the outcome. `C-RECON-15` asserts the
      property the box was reaching for, in the two halves that actually establish it — the
      payment row may be corrupted split-consistently and the outcome must not move; the
      provider's row may be corrupted and reconciliation must fail.

### Operations

- [x] One payment's full story is retrievable from a single endpoint.
      — `I-AUDIT-01`, `OperationsApiIT`.
- [x] Every state change, webhook decision, ledger posting, and case transition is in the audit
      trail, correlated by request id.
      — `I-AUDIT-01`.
- [x] `/api/v1/health` reports `503` when a balance check fails.
      — `I-LED-05`, `OperationsApiIT`.
- [x] Secrets come from the environment only, and startup fails outside `dev` with a default secret.
      — `ReconcilePropertiesTest`, `SecurityConfigIT`.

### Delivery

- [x] `docker compose up` brings up the app, PostgreSQL 18.6, and produces a healthy system.
      — `scripts/probe.sh`, 33 HTTP assertions against the running image.
- [x] The committed OpenAPI document matches the running application (drift check in CI).
      — `E2E-API-01`, `OpenApiContractIT`.
- [x] All four demo scenarios pass in CI and are reproducible locally with documented commands.
      — `E2E-DEMO-01` … `E2E-DEMO-04`, `EndToEndScenarioIT`.
- [x] README states the non-goals, the known limitations, and the simulation disclaimer.
      — `README.md`.
- [x] **No compliance, security, or performance claim appears anywhere that a test does not
      support.** Numbers in the README come from a CI run, not from this document.
      — and the ones this repository cannot support are listed as limitations rather than omitted.

### Migrations

Added in this pass; these were specified but had no test.

- [x] Re-running against an already-migrated database is a no-op.
      — `I-MIG-02`, `FlywayMigrationIT`.
- [x] A failing migration leaves the schema at the previous version, and the application refuses
      to start.
      — `E2E-MIG-01` and `E2E-MIG-01b`. The fixture creates its table *before* failing, so the
      test proves the migration was atomic rather than merely stopped early.

---

## 7a. What the Definition of Done does not cover

Stated so the ticked boxes above are not read as a claim of completeness. Deferred work with a
scoped plan behind it is in [`docs/roadmap.md`](../roadmap.md); this section is the honest list,
and the roadmap is the answer to it.

- The dependency tree is vetted against the OSV database and the scan now **runs on a schedule and
  on every push to `main`** (`.github/workflows/security.yml`, gated on
  `docs/security/osv-baseline.json`). The baseline holds the seven advisories already triaged in
  `docs/security/dependency-findings.md`, so the gate fails on a *new* advisory rather than on the
  seven everyone has already decided about — a check that reports the same findings forever is a
  check nobody reads. **Two keyless scanners now cover what one did:** `actions/dependency-review-action`
  (the GitHub Advisory Database) runs on every pull request and answers "what did this add". The
  third opinion — OWASP dependency-check against the NVD, the only one of the three that reads
  bytecode and so the only one that catches a shaded artifact with an innocent coordinate — is
  blocked on an API key this repository cannot create for itself; its workflow is written and
  corrected, and [`docs/roadmap.md §1`](../roadmap.md) has the arithmetic. One finding applied
  (CVE-2026-65182, fixed by pinning Tomcat to 11.0.26); the rest are recorded as
  unreachable-by-configuration.
- **GitHub Actions has now executed, and the first execution found what no offline check could.**
  `verify` and `security` are green on `main` as of `d1e9af8`. Two workflows have still never run,
  and both are facts about their triggers rather than gaps in the repository: `release.yml` fires on
  a `v*` tag that has not been pushed, and `dependency-scan.yml` needs an `NVD_API_KEY`. The runner,
  the concurrency groups, the health wait and the artifact uploads are therefore exercised now —
  `release.yml`'s are not. Actions are pinned to full commit SHAs and
  `scripts/pin_actions.py --check` verifies that each one still resolves, so the earlier caveat —
  that an invented SHA is a workflow that fails at its first step — no longer applies
  ([`docs/roadmap.md §4`](../roadmap.md)). What the first run exposed was invisible to every static
  check in this repository: Semgrep's `p/spring` ruleset is retired upstream, where a 404 loads zero
  rules and exits 7, and the ZAP job could not write its report into a directory owned by the
  runner. Both are fixed, and [`CI_EXECUTION_REPORT.md`](../../CI_EXECUTION_REPORT.md) records the
  runs that prove it.
- Static analysis and a penetration scan have now run (Semgrep over `p/java`, gating at `ERROR`
  severity; OWASP ZAP baseline scan against the running image, non-gating with the report uploaded;
  OSSF Scorecard for supply-chain posture). Semgrep loaded **60 rules and found 0 `ERROR`-severity
  findings**; ZAP completed with **one** alert — its root answers `401` to an unauthenticated
  spider, which is correct behaviour reported as a finding, and the reason ZAP's output is not
  evidence that the application passes it. Both stay advisory deliberately: ZAP because nobody has
  triaged that alert, Scorecard because it reports on the repository rather than on the code and
  starts red on any new repository. An authenticated ZAP scan is
  [`docs/roadmap.md §5`](../roadmap.md).
- One provider. Multi-provider routing is deliberately not implemented, and the webhook's pinned
  provider constant is a documented decision rather than an oversight — a provider cannot come from
  the request body, because a holder of one PSP's secret could then file events under another's
  name. The schema already keys every provider table on `provider`, so what is missing is identity
  rather than structure; [`docs/roadmap.md §2`](../roadmap.md) scopes it, including the part
  usually underrated, which is that two real PSPs' payload vocabularies do not line up.
- **Two integration test classes still start their own database.** `WebhookWithoutDatabaseIT`
  creates a second database and a non-owning role so it can revoke `CONNECT` and produce a real
  outage — sharing would be wrong. `FlywayMigrationIT` migrates a database and runs deliberately
  broken migrations against it. The other seven now share `SharedPostgres`; the earlier decision to
  keep nine was measured against a different shape of the suite and is no longer the right call.
  Every converted class either truncates in `@BeforeEach` or asserts only on uniquely-keyed rows, so
  sharing does not couple them.
- **`RequestIdFilter` sanitisation was untested until this pass.** Three tests now pin it. One
  finding changed a comment rather than code: the filter's own character set is *not* what stops
  log forging via a newline — the container's header parser rejects such a request before any
  application code runs. The filter's character set is the barrier for printable characters and for
  length, and the comment now says so rather than claiming the whole defence.
- **The five untested endpoints are now tested** (`E2E-API-07` … `E2E-API-11`): `GET
  /ledger/transactions/{id}`, `POST /ledger/transactions/{id}/reverse`, `GET
  /reconciliation/batches/{id}`, `POST /reconciliation/cases/{id}/write-off` and `GET
  /provider/events/failed`. Each asserts the status *and* the state left behind, because a status
  code on its own passes just as well against a handler that wrote the wrong row.
- **The ordering the suite relied on is now controlled, and controlling it found three more
  defects.** `EventOrderingIT` (rows `C-ORD-01` … `C-ORD-04`) enumerates every ordering of a
  lifecycle rather than sampling one, and forces the ordering in SQL via `next_attempt_at` so a
  permutation is reproduced exactly with no sleep and no polling — then drains the inbox with eight
  workers released on a barrier. It found three more defects in the same family as defect 21:
  a capture that arrived before its own authorisation died on an `IllegalStateTransitionException`
  instead of applying (§B5 says apply and flag); a settlement that arrived before its own capture
  spent all eight retries being early and then died holding money the provider had really paid; and
  the settlement path's unconditional `SET provider_status = 'SETTLED'` — the same regression as
  defect 21 in a second place — dragged a refunded payment back to `SETTLED`. All three are fixed
  and pinned. **What remains open is not the same property but a neighbouring one: the randomised
  leg is seeded from the clock by default, so it explores different orderings on different runs,
  and a bug it finds may not reproduce without the printed seed.** That is a deliberate trade — a
  randomised test that cannot be replayed is a flaky build, and a flaky build is worse than a
  narrower one.

---

## 8. Anti-goals for the test suite

Stated so the suite does not quietly grow into theatre:

- **No assertion on log text.** Tests assert database state and HTTP responses, because logs are
  not a contract.
- **No mocked repositories in integration tests.** A mocked `LedgerRepository` proves nothing
  about balancing, locking, or constraints; those tests use a real database.
- **No `Thread.sleep`-based concurrency tests.** Coordination uses latches and
  `SKIP LOCKED` claiming, with generous timeouts, so failures are real and not flaky.
- **No test that only asserts a `500`.** A failure test asserts *what state was left behind*,
  which is the part that matters.
- **No coverage percentage target.** Line coverage is reported; it is not a gate. A financial
  invariant that is hard to test deserves a hard test, not a percentage.