# Reconcile — Payment & Settlement Reconciliation Engine

> A financial backend where **correctness, auditability, idempotency, reconciliation, and failure
> handling are the product**, not the packaging.

Reconcile models a simplified payment platform that processes payments internally and periodically
receives settlement records from an external payment service provider. Its central question is not
"can we create a payment?" but:

> **Does what our system says happened agree with what the external provider says actually happened?**

---

## ⚠️ What this is, and what it is not

This is a **systems-engineering simulation of a fintech backend**, built as a portfolio project.

It does **not** process real money, move real funds, integrate with any real payment provider or
bank, store cardholder data, or perform KYC/AML. It makes **no claim** of PCI DSS compliance,
regulatory compliance, or production readiness, and no such claim may appear anywhere in this
repository.

What it does demonstrate is real engineering: double-entry accounting with enforced invariants,
idempotent command handling, signed webhook ingestion with replay protection, deterministic
reconciliation against an external system, and an audit trail that reconstructs every state change
from the database alone. Those are patterns used in fintech. Demonstrating them is not the same as
being a regulated institution, and the repository is careful about the difference.

---

## Status

| | |
| --- | --- |
| **Current state** | **1.0.0. Specification complete, Definition of Done satisfied box for box, money path, webhook ingestion, reconciliation and operations all implemented and verified against a real database.** |
| Release | [`CHANGELOG.md`](CHANGELOG.md) · [`RELEASE.md`](RELEASE.md) — what a tag means, and how to cut one |
| Definition of Done | [`docs/spec/08 §7`](docs/spec/08-test-matrix-and-dod.md) — every box ticked, each naming the test that proves it. [§7a](docs/spec/08-test-matrix-and-dod.md) lists what it does **not** cover |
| Specification | [`docs/spec/`](docs/spec/00-overview.md) — 9 documents, the contract the code is written against |
| Decisions | [`docs/adr/`](docs/adr/README.md) — 5 architecture decision records |
| Code | 72 main sources (12,232 lines); 38 test sources (13,056 lines) |
| Migration | `V1__baseline.sql` + `V2__financial_invariants_and_terminal_events.sql` apply cleanly with `ON_ERROR_STOP=1`, declaring 23 tables, 36 indexes, 10 triggers and 4 functions in total. Verified against a fresh database — it holds exactly 10 user triggers |
| Tests | **354 discovered · 353 executed and passed · 1 skipped · 0 failed or errored** — 150 unit (`mvn test`) and 204 integration against real PostgreSQL (`mvn verify`). Recomputed from the Maven reports by [`scripts/inventory.py`](scripts/inventory.py) and **checked** against this sentence, so it cannot silently go stale |
| Build | Green, in Docker on Java 25. `./scripts/probe.sh` additionally passes 33 HTTP assertions against the running image |

The one skipped test is deliberate: it rewrites the golden fixture and is opt-in
(`-Dreconcile.regenerate-golden=true`). A golden test that can update its own expectation is a test
that has stopped testing, so regenerating is a decision a person makes and reviews as a diff.

Note the distinction, because a build summary usually blurs it: **354 is the number of tests
discovered, and 353 is the number that passed.** The skipped test was found and deliberately not
run, so "354 tests pass" would be false. `inventory.py` now derives both numbers from the reports
and fails the build if this sentence disagrees with either — as well as if the two do not add up.

**Every count in this file is verified, not remembered.** `python scripts/inventory.py --check`
recomputes the figures above from the tree and from the Maven reports, parses them back out of
this file, and exits nonzero on any disagreement. `scripts/verify.sh` runs it. That is the fix for
drift, and it is worth stating because the alternative — updating the numbers by hand — is exactly
what produced the wrong ones this repository shipped with: a reader could not tell which figures
had been checked and which were merely believed.

### What is implemented and proven

Every bullet below is exercised by a test in this repository that you can run:

- **The ledger.** Posting with deterministic account locking (`SELECT … FOR UPDATE … ORDER BY code`,
  ADR-0002), reversal by exact mirroring, and the L7 balance verifier. `LedgerInvariantsIT` proves
  against a real PostgreSQL 18 that L1–L11 are properties of the *database*: raw SQL is refused by
  the deferred balance trigger, the immutability triggers, the partial unique double-post index,
  the no-negative-assets trigger and `ux_payout_line_payment`.
- **The payment lifecycle.** Create → authorize → capture → settle → refund. Each command is one
  database transaction covering the state change, its ledger posting, its state-history row and its
  audit event. The fee split is frozen at creation and `gross == net + fee` exactly.
- **Payouts.** The caller names payments; every amount is read from the ledger. One-payout-per-
  payment is enforced by a unique index rather than by Java, so a retry under a different
  idempotency key still cannot pay a merchant twice.
- **The worked example, end to end.** `PaymentLifecycleIT` runs capture → settlement → payout and
  asserts the ledger unwinds to `PLATFORM_CASH = 300` and `PLATFORM_FEE_REVENUE = 300`, with
  `PSP_CLEARING` and `MERCHANT_PAYABLE` at zero — and re-asserts L7 after **every** test in the class.
- **The API surface.** Payments, ledger reads and the reversal command, with RFC 9457
  `problem+json`, a two-role bearer-token model, constant-time token comparison, keyset pagination,
  and a correlation id echoed on every response and written onto every audit row.
- **Idempotency, proven from the outside.** `IdempotencyHttpIT` speaks HTTP to the running
  application: the same key and body twice returns a **byte-identical** response with the same
  `Location` and one payment row; the same key with a different body is `409
  IDEMPOTENCY_KEY_CONFLICT` and creates nothing; **32 simultaneous** retries of one key produce one
  payment and 32 identical bodies with no `5xx`; and a business refusal (`409
  PAYMENT_INVALID_STATE`) is stored and replayed verbatim while the payment stays untouched.
  `IdempotencyFailureIT` covers the row most systems get wrong — an injected
  `DataAccessResourceFailureException` releases the key, and the retry posts the ledger exactly
  once — and the slow path (`409 REQUEST_IN_PROGRESS` with `Retry-After: 1`) that spec 03 §A5
  requires the suite to assert.
- **Schema/JPA agreement.** `ddl-auto: validate` proves the seven read-model entities still agree
  with `V1__baseline.sql`. It has already caught three real mismatches (`CHAR(3)` vs `varchar(3)`,
  `SMALLINT` vs `integer`, and an illegal `@GeneratedValue` on a non-identifier).
- **Signed webhook ingestion.** `ProviderWebhookIT` speaks HTTP to a running application: the
  signature covers the **raw bytes** (spec 03 §B1), so a payload whose re-serialisation differs is
  still accepted, and a one-byte change is refused with `401` and a `REJECTED_SIGNATURE` delivery
  row and no business effect. Three deliveries of one event produce **three delivery rows, one
  event row, one state change and one ledger posting** — the reason `provider_event_deliveries` and
  `provider_events` are separate tables. The handler persists and returns `202` and never applies an
  effect inline; `InboundEventWorker` claims rows with `FOR UPDATE SKIP LOCKED`, retries with
  full-jitter backoff for up to 8 attempts, reclaims rows stranded in `PROCESSING` by a killed
  worker, and surfaces what exhausted its attempts on `/api/v1/provider/events/failed`.
- **Settlement ingestion, push and pull.** A `settlement.paid` event and a `provider/sync` pull run
  the **same** ingestion path — `ProviderSyncService` builds the same payload and calls the same
  `ProviderEventProcessor.apply`. If they diverged, a settlement would reconcile differently
  depending on how it arrived. `ProviderSimulatorIT` proves it: pulling the same window twice posts
  `SETTLEMENT_RECEIVED` exactly once, a webhook arriving *after* the pull changes nothing, a
  provider that never answers is `503` with nothing ingested, and a malformed body is `502`.
- **Reconciliation.** `ReconciliationService` derives the expected side from the **ledger** (by
  account code, not by line position) so the two sides of every comparison are genuinely
  independent. `ReconciliationGoldenIT` runs **50 subjects covering all nine reachable outcomes**
  and compares the result set byte for byte against a committed file, twice — determinism is a
  property of the product, so it is asserted rather than assumed. Results are append-only, so a
  case is planned before the result row and its id is part of the `INSERT`; a re-run reuses the
  existing case and raises `occurrence_count` instead of opening a second one.
- **Concurrency, against real threads and real locks.** `ConcurrencyIT` runs 32 threads posting
  32 transactions and asserts the balances, the entry count and L7 afterwards; 16 threads capturing
  one payment yield exactly one capture and fifteen `409`s; two concurrent payouts of the same
  payment yield one payout. `LedgerContentionIT` holds a row lock from a second connection with
  `lock_timeout` set low and proves the retry policy is **bounded at three attempts**, fails
  loudly, and cannot double-post.
- **Operations.** `/api/v1/health` is unauthenticated and returns `503` with the failing check named
  when the schema is wrong, when L7 verification drifts, or when a batch has been `RUNNING` past
  its lease. `OperationsApiIT` corrupts a balance through raw SQL with the guard trigger disabled —
  what a bad restore looks like — and asserts health says so rather than reporting `200`.
  `/api/v1/audit/events` and `/api/v1/audit/entities/{type}/{id}` page the trail by keyset, and
  `/api/v1/metrics/summary` counts the live system.

### Known defects found by running the built image — all now fixed

These were present with a fully green suite, and were found only by building the Docker image and
making real HTTP calls against it. The root cause of the first one is worth recording, because
nothing about it was visible from the tests that existed.

| # | Defect | Root cause | State |
| --- | --- | --- | --- |
| 1 | **Every authenticated request returned `401`.** The REST surface was unreachable | `UsernamePasswordAuthenticationToken`'s three-argument constructor already marks the token authenticated, so the redundant `setAuthenticated(true)` threw `IllegalArgumentException`. `ExceptionTranslationFilter` caught it and converted it to a `401`. `SecurityConfigIT` passed throughout, because it tested `authoritiesFor()` in isolation and never issued a request | **Fixed.** `ApiHttpIT` now issues real requests over a socket and would catch it |
| 2 | **The container reported `unhealthy` forever** | Two independent causes: the `HEALTHCHECK` probed `/api/v1/health`, which does not exist; and `eclipse-temurin:25-jre` ships neither `wget` nor `curl`, so the probe could not have succeeded against any URL | **Fixed** — probes `/actuator/health`, with `curl` installed in the runtime stage |
| 3 | **An unknown path returned `500`, not `404`** | The catch-all `@ExceptionHandler(Exception.class)` swallowed `NoResourceFoundException`, so a mistyped URL looked like a server fault | **Fixed** — `NoResourceFoundException` is handled explicitly |
| 4 | **Boot auto-configured a working `user` account** on every start, and logged its generated password. Nothing revoked it and nobody chose it | `spring-boot-security`'s `UserDetailsServiceAutoConfiguration` has no bean to make it back off, and this build authenticates with bearer tokens | **Fixed** — excluded by fully-qualified name. Boot 4 moved the class out of `spring-boot-autoconfigure`, so the name is `org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration`; confirmed against the jar, and the running image no longer logs a generated password |
| 5 | **An illegal payment transition returned `500`, not `409`** | `IllegalStateTransitionException`'s own javadoc promised the API layer would map it to `PAYMENT_INVALID_STATE`. No such mapping existed — it extended `RuntimeException`, so the advice's catch-all answered `500`. A refusal working as designed was being reported as a server fault, which escalates alerts and invites a pointless retry | **Fixed** — it now extends `DomainException` and carries the code, so a type that can escape the contract no longer exists |
| 6 | **Every refusal was served as `application/json`** | `@RestControllerAdvice` returns a POJO, and the default converter labels it `application/json`. The media type is part of the contract in `docs/spec/06 §3` | **Fixed** — `application/problem+json` on every advice path, and on the filter-chain refusals that already set it |
| 7 | **`@EnableScheduling` was on the application class with nothing scheduled.** `IdempotencyService.purgeExpired()` existed, was correct, and was called by no one, so the 24-hour retention window in spec 03 §A6 was a promise written only in the schema while the table every mutating endpoint writes to grew without bound | The retention sweeper had simply never been written; a correct method with no caller is invisible to every test that calls the method directly | **Fixed** — [IdempotencyRetentionSweeper](src/main/java/com/reconcile/service/IdempotencyRetentionSweeper.java) on a `fixedDelay` schedule with the same value as its initial delay. `IdempotencyRetentionSweeperIT` asserts both that expired rows are reclaimed in bounded batches and that a trigger is *actually registered* — the half that would have passed before the fix |
| 8 | **An absent configuration section arrived as `null` and blew up in an unrelated component.** Adding a convenience constructor to the `Idempotency` record left Spring Boot's value-object binder with two candidate constructors, and it responded by skipping that section entirely — silently, with no error — so `properties.idempotency()` was `null` and the sweeper's constructor NPE'd at startup | The top-level `ReconcileProperties` record had no defaulting compact constructor, contradicting its own javadoc claim that "a half-configured bean cannot be observed". The overload was a convenience nobody needed; the ambiguity hazard was permanent | **Fixed** — the overload is gone, the top-level record defaults every section, and `ReconcilePropertiesTest` asserts both the defaults and that each section declares exactly **one** constructor, so the next convenience overload cannot silently reintroduce it |

### Two defects found by auditing the fix for a *previous* defect

Both were introduced while fixing findings F-01 and F-02, both were invisible to a green suite,
and both were found by `audit-probe/vv-remediation.mjs` against a running container. They are
recorded here because the failure mode is the interesting part: **a fix that is correct in the Java
and wrong against the schema it persists into.** Nothing in a unit test can see that, because the
unit test stops at the enum.

| # | Defect | Root cause | State |
| --- | --- | --- | --- |
| 9 | **A reconciliation batch containing an invalid settlement was marked `FAILED` with `processed_subjects = 0`** — every payment in the window went unreconciled | `ReconciliationOutcome.SETTLEMENT_RECORD_INVALID` was added to the Java enum, but V1 pins `reconciliation_results.outcome` to a `CHECK` constraint enumerating the outcomes that existed when it was written. The insert was refused, and `ReconciliationWorker` abandoned the whole batch. **The engine stopped reconciling precisely when it found the thing it was built to find** | **Fixed** — V2 replaces the constraint by looking up V1's name and failing loudly if the shape is not what it expects, and adds a partial index for the new verdict. A `CHECK` constraint is only covered by a test that writes the constrained value *through the constraint*: `ReconciliationIT.theNewOutcomeSatisfiesTheDatabaseConstraint` does exactly that |
| 10 | **A merchant reference containing a newline stopped every reconciliation batch in that window** | `differencesJson()` hand-built the `differences` JSONB document with a `StringBuilder`. RFC 8259 forbids raw control characters inside a JSON string and the escaper handled only backslash and double quote — so the insert failed with `invalid input syntax for type json` and the batch was abandoned. The values reaching it include the merchant reference on both sides of a `REFERENCE_MISMATCH`, which the **provider** supplies: one newline in one reference was a denial of service on reconciliation, and a JSON-injection primitive into an audit column | **Fixed** — a real serialiser is used instead of a re-derivation of the format, so it cannot regress on either. Keys, order and null handling are unchanged. `ReconciliationIT.providerControlledControlCharacterDoesNotBreakTheResultRow` sends a reference carrying `\n`, `\t`, `\r`, a backslash and quotes, and asserts the result row exists, the batch is not abandoned, and the stored document parses |

### Defects found by writing the reconciliation and operations tests

Each of these was in code that a green suite already covered, and each was found by a test that
asserted the *consequence* rather than the method call.

| # | Defect | Root cause | State |
| --- | --- | --- | --- |
| 9 | **Settling a payment moved its state but never moved the money.** `Postings.settlementReceived` existed, was correct, and was called from nowhere — so every settled payment left its value stranded in `PSP_CLEARING` forever, and `PLATFORM_CASH` was permanently zero | The settlement ingestion path was written to settle *payments* and the ledger posting was left as a separate concern nobody connected. Nothing in the type system or the schema connects "this payment is settled" to "this money arrived" | **Fixed** — `ProviderEventProcessor.applySettlement` posts one `SETTLEMENT_RECEIVED` per settlement record, at the gross **we** captured (not the provider's figure, which would normalise a discrepancy away instead of reporting it), and only when the delivery actually settled something. `LedgerRulesIT` (`I-LED-02`) asserts clearing returns to zero, cash arrives, and a second delivery posts nothing more |
| 10 | **A duplicate settlement delivery posted a second time.** Delivering the same settlement twice left a permanently `FAILED` event in the poison-message backlog | `markSettled` returned early when the payment was already settled, but the caller counted *settlements attempted* rather than *settlements performed*, so it posted again and the unique constraint refused it | **Fixed** — `markSettled` returns whether it performed the transition, and the posting is driven off that answer |
| 11 | **A PostgreSQL lock timeout was never retried and surfaced as an unexplained `500`.** The ledger's retry loop catches `DeadlockLoserDataAccessException` and `CannotAcquireLockException`, but `lock_timeout` arrives as `UncategorizedSQLException` because Spring's vendor table has no entry for SQLSTATE `55P03` | The retry policy assumed the driver's exception mapping covered every contention failure. It does not | **Fixed** — [`Sql`](src/main/java/com/reconcile/persistence/jdbc/Sql.java) re-types `55P03` as `CannotAcquireLockException` at the single choke point every raw statement goes through. `LedgerContentionIT` (`C-CONC-03`) asserts exactly two retries, then a loud failure, and that the retry lands the money once |
| 12 | **`/api/v1/health` returned `500` for a perfectly healthy system** | `MAX(installed_rank)` returns an `Integer` and the row mapper cast it to `String` | **Fixed** — `OperationsApiIT` asserts `200` with every check named |
| 13 | **Audit keyset paging walked the trail backwards.** The page query ordered `DESC` and the cursor compared `>`, so "the next page" was computed from the wrong end | The cursor comparison was written without reference to the ordering beside it | **Fixed** — strictly `<` for a descending page, with the reason in the code |
| 14 | **`PAYOUT_INSUFFICIENT_CASH` was documented as a `409` and was unreachable.** An oversized payout hit the L8 trigger, which refused it as a constraint violation — a `500`, so a client could not tell "you cannot pay this" from "the system is broken" | The service relied on the database to enforce a business rule, and never translated the refusal into the code the contract promises | **Fixed** — `PayoutService.requireSufficientCash` pre-checks and returns the documented `409`. The trigger stays as the last line of defence, because only it sees the balance at the moment the money would actually move |
| 15 | **Re-running a reconciliation opened a *second* case for a discrepancy an operator had already resolved** | Case reuse was filtered on `status IN ('OPEN','INVESTIGATING')`, so a resolved case stopped absorbing re-detections and each nightly batch manufactured a fresh one | **Fixed** — one case per `(payment, provider transaction, reason)` whatever its status; recurrence is recorded in `occurrence_count` and the case events. This is `E2E-DEMO-02`'s central claim |
| 16 | **A payment claiming a provider transaction id that was already taken returned `500`.** The unique index that stops two payments sharing one reconciliation identity was doing its job; the caller was told the system had broken | The raw `DuplicateKeyException` propagated to the catch-all advice. The same shape as defect 14: a business rule enforced by the database with no translation at the edge | **Fixed** — `409 PROVIDER_TRANSACTION_ALREADY_CLAIMED`, added to the code enum and to the contract's error table in the same edit. Found by a probe against the running image, not by a test |
| 17 | **`docker compose up` could never start.** The PostgreSQL 18 image moved its data directory, and the volume was mounted at the old path, so the server exited on start-up talking about `pg_upgrade` | The compose file was written against PostgreSQL 16 conventions and never re-checked after the version bump | **Fixed** — the volume is mounted at `/var/lib/postgresql`, and the running stack is verified below |
| 18 | **`POST /api/v1/provider/sync` could never succeed.** The pull sent no credential at all, and the shipped default for `reconcile.provider.sync-base-url` points at this application’s own simulator — which authenticates — so every pull answered `401` and surfaced as `503 PROVIDER_UNAVAILABLE`: the caller was told the provider was down when it was refusing us, and retrying changed nothing | The test’s provider stub was a JDK `HttpServer` that accepted anonymous reads, so the one component whose entire job is an outbound HTTP call had never been asked for a credential. Nine passing sync tests, none of which could have caught it | **Fixed** — `reconcile.provider.sync-token` is presented as a bearer credential, defaulting to the operator token so the simulator works unaided while a real deployment supplies the PSP’s own. The stub now **rejects unauthenticated pulls**, so every sync test in the class proves the header is sent |
| 19 | **The provider simulator reissued transaction ids it had already given away, and the symptom was a `409` against an id the caller had only just been handed.** The id counter lived in an `AtomicLong` that started at zero on every process start, so restarting the application and running the same demonstration twice produced `psp_0000000000006` twice | A real provider's transaction ids are unique forever; the simulator's were unique only within one process lifetime. Nothing exercised it because the whole suite runs in a single JVM, and the failure needs a restart to appear — so it surfaced as a domain error manufactured by a fixture, which reads as a real defect rather than a broken one | **Fixed** — the counter is seeded at construction from the highest id already persisted across all three id spaces, so a restarted simulator continues rather than repeats. `ProviderSimulatorIT.restartedSimulatorDoesNotReissuePersistedIds` builds a second instance against the same database — a restart, in everything but the process — and asserts the new id is greater and unclaimed |
| 20 | **A key presented after its retention window was still replayed — the 24-hour guarantee was a property of the sweeper running on schedule rather than of the data.** `IdempotencyService.reserve` used `ON CONFLICT DO NOTHING` and never looked at `expires_at`, so the record answered every retry until `purgeExpired` deleted it. With the sweeper disabled, failing, or simply not yet due, a client got the original response indefinitely, and the table grew without bound | Spec §A6 states the behaviour plainly — “a key presented after 24 hours is treated as new and will execute again” — and the sweeper implemented the *mechanism*. Nothing asserted the *behaviour*, so the gap between the two was invisible behind a fully green suite. Filtering in the follow-up `SELECT` could not have closed it: the `INSERT` conflicts first and never reaches it | **Fixed** — the expiry test moved into the conflict clause, which updates an existing row only when it is past `expires_at`, making the window true atomically with the takeover and closing the race two concurrent requests would otherwise have. `C-IDEM-06` asserts it end to end: after expiry the key produces a *second payment*, a `Location` that differs, and `Idempotency-Replayed: false` |
| 21 | **An event processed after a settlement dragged the provider-side status backwards.** `upsertProviderTransaction` wrote `provider_status = EXCLUDED.provider_status` unconditionally. The inbound worker applies a claimed batch in whatever order it processes it, so a `payment.created` emitted *before* the settlement could be applied *after* it and overwrote `SETTLED` with `AUTHORIZED` | The payment was correctly `SETTLED` and the next reconciliation batch reported `STATUS_MISMATCH` against it — a false discrepancy manufactured by our own ingestion order, in the exact direction reconciliation exists to detect. Found by `scripts/probe.sh` and not by the suite, because every test delivers events in order; the payment-side state machine *did* refuse to resurrect a state (`I-WEB-06`), and this was the one write that did not honour that rule | **Fixed** — the conflict clause now keeps the stored status when the incoming one is earlier on the lifecycle, using the same ranks as `PaymentState.isAtLeastAsFarAs`, so a genuinely later event (`REFUNDED`, `FAILED`) still wins. `C-WEB-11` delivers the settlement first and the earlier `payment.created` second, and asserts the row stays `SETTLED` |
| 22 | **Three more ordering defects, all in the same family, found by a test that controls the order instead of trusting it.** `EventOrderingIT` enumerates every ordering of a lifecycle and forces it in SQL via `next_attempt_at`, then drains the inbox with eight workers released on a barrier. It found: **(a)** a `payment.captured` arriving before its own `payment.authorized` threw `IllegalStateTransitionException` and died, stranding the capture and leaving the payment at `CREATED` forever — spec 03 §B5 says apply-and-flag; **(b)** a `settlement.paid` arriving before its own capture spent all eight retries and five minutes of backoff being *early*, then failed, holding money the provider had really paid; **(c)** the settlement path's unconditional `SET provider_status = 'SETTLED'` — defect 21 again, in a second place — dragged a refunded payment back to `SETTLED` | Every one is invisible to a suite that delivers events in order, and all three cost the same thing: money our side believes wrongly, or money we refuse. (a) lost a capture permanently. (b) lost a settlement permanently, after burning the retry budget reserved for genuine faults. (c) manufactured a `STATUS_MISMATCH` against a payment whose two sides agreed perfectly | **Fixed** — (a) a capture applied out of order now walks through `AUTHORIZED` first, which moves no money and so reproduces exactly the state the capture would have produced; (b) "not applicable yet" is a third outcome, distinct from both failure kinds: the event is rolled back and re-queued **without spending an attempt**, budgeted by a new `deferred_attempts` column so an event that stays blocked still surfaces on `/provider/events/failed`; (c) the settlement's provider-row write uses the same monotonic ranks as defect 21. `C-ORD-01` … `C-ORD-04` pin all of it, exhaustively for the three small lifecycles and randomised-with-a-seed for the 64-event burst |

### Four defects found by an independent audit, all fixed

A reviewer who had never seen this repository took it apart against the running image — real HTTP
calls, real SQL, a clean `target/` build — and found four more. They are recorded here in the same
form as the rest because they are the same kind of thing: not a hard bug, but a claim that was
stronger than the code, found by removing a precondition every test had been quietly satisfying.

| # | Defect | Root cause | State |
| --- | --- | --- | --- |
| 23 | **One payment the provider named but that never captured made reconciliation return `500` and killed the whole batch** — leaving it `RUNNING` with partial results and no reason recorded, permanently | `expectedView` required a capture posting and threw when there was none. Reached without misbehaviour: `upsertProviderTransaction` writes the provider row *before* the state decision, so a `payment.captured` webhook against a `FAILED` payment leaves exactly that shape | **Fixed** — a payment with no capture posting contributes no expected view, so the subject classifies `MISSING_INTERNAL` and opens a case. Spec 04 §4 now says what "internal" means. `AUDIT-05`–`AUDIT-07` |
| 24 | **`POST /api/v1/reconciliation/batches` with its documented default did nothing at all** — `202`, then no runner, forever; after the lease `/health` turned `503` and would have taken the service out of rotation | `async` defaults to true, but `runBatch` was called from exactly one place, the `async: false` branch. There was no scheduler, and `reconcile.reconciliation.poll-interval` was bound into configuration and read by nothing | **Fixed** — `ReconciliationWorker.poll` claims `RUNNING` batches on that very setting; the default is now 2s because it is how long an operator waits, not a report cadence. `AUDIT-08`, `AUDIT-09` |
| 25 | **A batch that could not complete was orphaned in `RUNNING`**, indistinguishable from a slow one, with `status = 'FAILED'` and `failure_reason` written nowhere in the codebase | `runBatch` had no try/catch at all. The one loop in the system that processes many independent transactions was the one place that most needed a failure boundary | **Fixed** — a failure marks the batch `FAILED`, records the reason, audits it, and is never retried. `AUDIT-10`, `AUDIT-11` |
| 26 | **Deleting a balance projection row made money disappear silently and health said `200`** — 500.00 EGP committed to the immutable ledger with no balance, and the L7 verifier could not see it | Two independent causes. `account_balances` has no delete guard, unlike the seven append-only tables; and the L7 query is driven `FROM account_balances`, so a *missing* row is structurally invisible — it can only detect a row that disagrees. `LedgerWriter` also never checked the UPDATE's row count | **Fixed** — the update count is checked and rolls the posting back rather than losing it, and L7 now asks both questions: does each row agree, and does every account with entries *have* a row. `AUDIT-12`–`AUDIT-15` |

Three of the four were invisible to a green suite for one shared reason, and it is worth naming
because it is the most transferable thing here: **every fixture satisfied the precondition the
defect lived behind.** Every reconciliation subject had a capture posting. Every batch was created
with `async: false`. Every balance row existed. `ReconciliationGoldenIT` could not express defect 23
because it had no way to seed a payment that never captured — so it gained five subjects that can.

Four of these deserve a second reading. Defect 9 is the reason this project exists as a portfolio
piece: an entire posting function, correct in isolation, unreachable in practice, and a green test
suite that never asked where the money went. Defect 11 is the reason the contention test asserts a
*retry count* rather than merely “it eventually worked”. Defect 18 is the reason the probe exists: a
stub that is more permissive than the thing it stands in for hides the defect it exists to find.
Defect 19 is the reason the probe is asserted to be *repeatable*: a fixture that only behaves on a
clean database manufactures false failures, and a test that cannot be run twice is not yet a test.
Defect 20 is the reason the specified-but-untested behaviours were tested at all: a documented
guarantee nobody had asserted was a guarantee the scheduler’s health, not the system’s.

Defect 21 is the reason the probe is run against an already-populated database rather than a fresh
one. Its ordering is not a property of the system under test — it is a property of *how the worker
happens to schedule a batch* — so a suite that delivers events in order, on a clean database, every
time, will never see it. The probe delivers three events in one run, lets the worker take them in
whatever order it wins, and then reconciles. That is the only place the ordering falls out, and it
fell out of a test that had been passing.

### Two settlement gaps, and why they were not the same problem

A settlement is the provider telling us about a transaction. Two things can go wrong with that, and
they needed opposite answers.

**Case A - the capture webhook is lost, the settlement arrives, and we hold the payment.** The row
was left absent and the next batch reported `MISSING_ON_PROVIDER` against a transaction the provider
had just settled. A false discrepancy manufactured by our own ingestion gap, and the worst kind:
it survives a case being resolved and returns on the next run. `applySettlement` now writes the
`provider_transactions` row from facts we already hold - the line's gross and net, and *our*
payment's `captured_at` and merchant reference. `captured_at` is not reconstructed; it is the same
instant `PSP_CLEARING` was credited.

**Case B - the settlement covers a transaction we have no payment for at all.** The line was stored
with a null `provider_transaction_ref`, and the transaction stayed outside every reconciliation
window forever: the subject population is built from `payments` and `provider_transactions`, so a
settlement-only transaction was in neither. No batch would ever produce a subject, result or case
for it. `ProviderSyncService` was not a way out - it feeds pulled settlements through the identical
ingestion path, so it inherited the identical gap.

The fix refuses to invent the missing fact. These rows are written with a **NULL `captured_at`**,
because no capture time was ever given to us, and they are scoped by the *settlement* window
instead - a window we genuinely have. The row says "the provider reported this" and nothing more;
it does not claim to know when it happened. `C-RECON-12` asserts both halves: such a transaction is
reported as `MISSING_INTERNAL`, and is *not* reported when its settlement falls outside the window.

**What did not happen: the golden fixture did not move.** Before writing the code I checked whether
widening the subject population could disturb it, because a fixture that regenerates itself proves
nothing. Its `MISSING_INTERNAL` subjects are built from direct `provider_transactions` rows, never
from settlements, so none of the 50 could be affected - and the run confirmed it: 50 subjects, 0
failures. My earlier prediction that this "would move the golden fixture" was wrong, and the
reasoning behind it was worth checking rather than trusting.

**The bug the new test caught, in the fix itself.** The settlement-date predicate was first written
as `settlement_date < windowEnd::date`, the natural half-open habit for a window of instants. But
`settlement_date` is a *calendar day*: a window ending at "now + 1 hour" ends on today's date, so
the comparison evaluates to `today < today` and silently dropped every settlement dated today. The
comparison is inclusive on both ends now. It is the only reason that code path had ever been
executed, which is the argument for writing the test before trusting the query.

### The defect in the build itself

| Defect | Why it matters | State |
| --- | --- | --- |
| **`mvn verify` could report `BUILD SUCCESS` on source that does not compile.** The repository is bind-mounted from Windows into the build container, and that mount does not update modification times. Maven's compiler plugin decides what to recompile from timestamps, so a file edited seconds after the last build looks unchanged; javac is never invoked, and `target/classes` keeps the previous version | Measured, not suspected: a deliberate `ServletRequest` → `HttpServletRequest` type mismatch compiled to `BUILD SUCCESS` with no `Compiling` line at all, and the next test run failed inside a supposedly compiled class with `java.lang.Error: Unresolved compilation problem`. This is the same shape as defect 1 above — a green suite over broken code — and it would have made every result in this README untrustworthy | **Fixed** — [`scripts/verify.sh`](scripts/verify.sh) deletes `target/` before building, so javac always sees every source. CI was never affected (a fresh checkout has correct timestamps) and uses `mvn verify` directly |

### What is specified but not yet built

Named explicitly rather than left to be inferred from silence:

| Area | State |
| --- | --- |
| Six of the seven JPA entities have no repository | `PaymentEntity` is the only one wired. The other six are validated mappings awaiting their read queries; every read in the system goes through `Sql` today, which is honest but not idiomatic |
| ~~Generated OpenAPI document~~ | **Built.** `springdoc` generates it from the running application and `OpenApiContractIT` (`E2E-API-01`) diffs it against the committed [`docs/openapi/reconcile-v1.yaml`](docs/openapi/reconcile-v1.yaml) (30 routes). The check is proven to fail: renaming one route in the committed file turns the suite red. Regeneration is opt-in, `-Dreconcile.regenerate-openapi=true`. The document is behind a bearer token like the API it describes |
| `C-PROV-*` against a real HTTP mock | The provider side of `ProviderSimulatorIT` is a JDK `HttpServer`, not WireMock, so the real client is exercised — timeouts, status handling, parsing, authentication — without a new dependency. A stub framework would test the framework. The stub authenticates on purpose: it did not at first, which is how defect 18 survived nine green tests |
| Second provider | **Partly built.** The provider code is now defined once, in [Providers](src/main/java/com/reconcile/provider/Providers.java), and every main source references it — four scattered `"SIMULATED_PSP"` literals are gone, and `ProvidersTest` fails if a fifth appears. An unknown provider is now *refused* (`400 VALIDATION_FAILED`) rather than stored, because a payment under a provider no batch filters on never reconciles and never says so. Still to do: a second provider, and moving the set out of code into configuration |
| JPA read models for reconciliation | Results, cases and batches are read through `Sql`. Correct, and typed at the edge, but it is the part most likely to benefit from repository objects |

### Build and test

Two entry points, and they prove different things.

| | Proves | Needs |
| --- | --- | --- |
| `./scripts/verify.sh` | The **code**: 297 tests against a PostgreSQL 18 that Testcontainers starts and tears down, on Java 25 — plus the workflow definitions, which no other command here can reach | Docker only |
| `./scripts/probe.sh` | The **image**: the built jar, the composed stack, the migration, the security chain, the ledger and the settlement — nothing mocked, nothing in-process | `docker compose up -d --build app` first |

The probe exists because the test suite and the running artefact disagree, repeatedly and
in both directions. Defects 12, 16 and 18 were all invisible to `mvn verify` and obvious the
moment a real HTTP request was made against the running container. Defect 19 surfaced only on a
*second* run of the probe, against an application that had been restarted — which is why it is
asserted to be repeatable rather than assumed to be. Run both:

```bash
docker compose up -d --build app
./scripts/probe.sh          # exits non-zero on the first failing assertion
```

A third check earns its place because it covers a file nothing else here touches:

```bash
python scripts/check_workflows.py   # every workflow parses; every `run` block is valid bash
python scripts/osv_check.py         # OSV against the resolved tree; fails only on a new advisory
```

The randomised ordering test takes a seed so a failure is replayable: `-Dreconcile.ordering.seed=<any
string>`. CI passes the commit SHA, so one commit always explores the same orderings and each new
commit explores new ones; a nightly soak rotates the seed on a schedule. Every run prints the orderings
it explored, so even a green run is a record.

It is safe to run repeatedly — each run creates its own payment with its own provider transaction
and idempotency keys, and every balance assertion is a *delta* measured across that run, because
the ledger accounts are global and cumulative and an absolute reading would fail the second time
through.

The build runs **only in Docker**, on Java 25. The local JDK is never used to compile — a pinned
version is a claim, and a build that quietly succeeded on an older JDK would make it untrue.

```bash
# use this one: it deletes target/ first, which is what makes the result trustworthy
./scripts/verify.sh

# a subset of the suite, while iterating. -Dsurefire.failIfNoSpecifiedTests=false is required and
# -DfailIfNoTests=false is NOT a substitute for it: surefire fails a run whose pattern matches
# nothing, and matching nothing is the normal outcome when the unit name matches no test.
./scripts/verify.sh -Dit.test='IdempotencyHttpIT' -Dtest=MoneyTest \
  -Dsurefire.failIfNoSpecifiedTests=false

# the compose entry point
docker compose run --rm build

# CI runs Maven directly; a fresh checkout has correct timestamps, so no clean is needed
MSYS_NO_PATHCONV=1 docker run --rm \
  --mount type=bind,src="$PWD",dst=/build -w /build \
  -v reconcile-m2:/root/.m2 -v //var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_RYUK_DISABLED=true \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  maven:3.9-eclipse-temurin-25 mvn -B verify
```

**Use `scripts/verify.sh`, not a bare `mvn verify`, when building from a Windows checkout.** The
reason is the build defect recorded above: the bind mount's stale timestamps let Maven skip
recompiling a changed file, so a build can succeed over code that does not compile. The script
removes `target/` first. It costs a full recompile and it is the difference between a green build
and a truthful one.

`TESTCONTAINERS_HOST_OVERRIDE` is not optional on Docker Desktop. Testcontainers otherwise guesses
the Docker host as `172.17.0.1`, and that address does not carry the ephemeral host ports it
allocates — the database is up and healthy while the integration tests fail with
`Connection refused`. This was measured rather than assumed: an explicitly fixed published port
works on `172.17.0.1` and an ephemeral one does not, while both are reachable via
`host.docker.internal`. `docker-compose.yml` sets it for the same reason.

Ryuk (Testcontainers' orphan reaper) publishes a port the build container cannot reach on bridge
networking, so it is disabled **for the containerised build only**. CI runs on the host, where Ryuk
works and is deliberately left enabled.

---

## Security posture of a repository that is public

Written for the reader who clones this, and for anyone auditing it. Stated plainly, including the
parts that are not production-grade.

**There is no real authentication here, and the code says so.** Two static bearer tokens, no user
store, no rotation, no OAuth. `SecurityConfig`'s own javadoc calls it "not a production
authentication design". What survives a real deployment is the authorisation matrix, the audit actor
model and the role rules; a real one puts OIDC in front and maps claims onto the same two roles.

**The shipped defaults are public the moment this repository is.** `dev-operator-token`,
`dev-admin-token` and `dev-webhook-secret` are in `application.yml` and in `docker-compose.yml`.
They are not secrets and are not treated as secrets - they exist so the stack starts with no setup.
What stops them reaching a real environment is `assertNoDefaultSecretsInProduction`, which refuses
to start when a default token or webhook secret is configured outside the `dev`/`test` profile, and
is asserted by `SecurityConfigIT`. It is deliberately strict: any profile name it does not recognise
counts as production, so a typo fails closed.

**Both published ports are bound to loopback.** Docker's default publishes on every interface. On a
laptop on a shared network that would expose a database whose password is `reconcile` and an API
guarded by tokens that are, by then, in everybody's checkout. `RECONCILE_DB_BIND` and
`RECONCILE_APP_BIND` widen it deliberately; nothing does so accidentally.

**What is exposed, and what is not.** `/api/v1/health` and `/actuator/health` are `permitAll` on
purpose - a load balancer cannot authenticate. `/v3/api-docs` is behind a bearer token, like the API
it describes: it is a complete map of every route, parameter and failure code. Everything else,
including `/actuator/metrics`, `/actuator/env` and `/actuator/configprops`, is refused by
`.anyRequest().denyAll()`. That last one matters most - `/actuator/env` would dump every configured
secret, and it answers `401`/`403`.

**Mechanics worth naming.** Tokens are compared by digest, not by `equals`, so a rejection carries
no information about how much was guessed. Every query is parameterised; there is no SQL
string-building anywhere, in main code or tests. A rejected webhook records an outcome and a reason,
never the signature or the token.

**What has not been done.** Static analysis and a penetration scan are now *configured* —
[`.github/workflows/security.yml`](.github/workflows/security.yml) runs Semgrep over `p/spring` on
every push, GitHub's dependency review on every pull request, and scheduled OSSF Scorecard and OWASP
ZAP baseline scans against the running image — and **none has ever run**, because nothing in this
repository can execute a GitHub Actions workflow. The same is true of every other workflow here: they
parse, and no runner has ever executed them. Treat the first run of each as a measurement, not as a
gate that is expected to be green.

The dependency scan *does* run somewhere real: the OSV check now fires on every push to `main` and
twice a week, gated on [`docs/security/osv-baseline.json`](docs/security/osv-baseline.json) so it
fails on a *new* advisory rather than on the seven already triaged. That check has run against the
resolved tree — 156 resolved artifacts, 10 advisories found, the three critical Tomcat ones fixed by
pinning Tomcat to `11.0.26`, the seven remaining Jackson ones unreachable, every one with its
reasoning in [`docs/security/dependency-findings.md`](docs/security/dependency-findings.md).

The third opinion — OWASP dependency-check against the NVD, the only one of the three that reads
bytecode rather than trusting a coordinate — is blocked on an API key this repository cannot create
for itself. Its workflow is written and corrected; the key is not available.

**What comes next is written down, not implied.** [`docs/roadmap.md`](docs/roadmap.md) scopes the
deferred work — the NVD scan, multi-provider, refund modelling on reconciliation's expected side,
SHA-pinned actions, an authenticated ZAP scan — each with its blocker, its cost, and what it would
prove. Three of the five are blocked on something outside the code: a key, a sandbox account, a
network call.

## The five properties

**1. Double-entry ledger.** Money is never edited and never edited *around*. Balances are derived
from entries; corrections are new reversing transactions. Invariant `Σ debits = Σ credits` is
enforced by the Java validator *and* by a deferred database trigger, so no unbalanced transaction
can commit even if written by raw SQL.

A full cycle — **capture → settlement → payout** — unwinds the ledger to exactly two non-zero
accounts: what the platform earned and what it holds. Eleven numbered invariants back that claim,
including that a payment can be paid out at most once and cannot be refunded after it has been.
*Implemented and tested; see `PaymentLifecycleIT`.*

**2. Money as integers.** `Money = (long amountMinor, ISO-4217 currency)`. No `double` and no
`float` anywhere, and no arithmetic on stored amounts in `BigDecimal`: the one place it appears is
as an integer-rounding helper when computing a fee from basis points and when rendering minor units
for a human. A ledger transaction cannot mix currencies. `ClassifierArithmeticTest` reads the
comparison path's source and fails on `double`, `float` or `BigDecimal`, because a javadoc promise
about this decays the first time somebody adds a convenience comparison. *Implemented and tested.*

**3. Idempotency as a first-class feature.** `Idempotency-Key` on every mutating command, required
on `POST /payments` and `POST /payouts`, honoured where optional. Same key + same request replays the
stored response **byte for byte**, same `Location`, `Idempotency-Replayed: true`; same key +
**different** request is `409 IDEMPOTENCY_KEY_CONFLICT` and creates nothing.

The reservation, the command and the stored response share **one transaction** — so a crash rolls
back all three and the key is free again — and the reservation is an `INSERT … ON CONFLICT DO
NOTHING` against a unique index, which is what makes 32 simultaneous retries produce one payment
without a lock. A 4xx is stored and replayed; a 5xx is not stored at all, because the rollback
already released the key. Expired records are reclaimed by a scheduled sweeper in batches of 1,000,
oldest first, and a key presented after the 24-hour window is treated as new — a documented
trade-off, stated in the spec rather than hidden. *Implemented and tested over HTTP; see
`IdempotencyHttpIT`, `IdempotencyFailureIT`, `IdempotencyRetentionSweeperIT` and
`IdempotencyFingerprintTest`.*

**4. Reconciliation, not reconciliation-as-a-feature.** Internal records are compared against
simulated PSP settlement data and classified — deterministically, with documented precedence — into
`MATCHED`, `AMOUNT_MISMATCH`, `STATUS_MISMATCH`, `MISSING_ON_PROVIDER`, `MISSING_INTERNAL`,
`DUPLICATE_PROVIDER_RECORD`, `AMBIGUOUS_MATCH`, and more. Every non-match becomes an operational
case with an audit trail and a resolution. The expected side is derived from the **ledger**, never
from the payment row (ADR-0005): a reconciliation that read both sides from one table would pass
every assertion here while being incapable of detecting the bug it exists to detect. A batch freezes
its subject population at creation, so a resumed batch compares exactly the same set, and it never
posts — corrections are separate, case-attached actions, because silently fixing money during
reconciliation would destroy the audit trail. *Implemented and tested; see `ReconciliationIT`,
`ReconciliationGoldenIT` and `EndToEndScenarioIT`.*

**5. Failure handling as a designed-for path.** Duplicated webhooks, delayed webhooks,
out-of-order webhooks, lost webhooks, provider timeouts, double-capture races, double payouts,
concurrent ledger postings, and worker crashes are each specified with an intended behaviour and a
test that proves it. See the [failure matrix](docs/spec/07-failure-matrix.md) — 63 numbered
failures, plus 8 documented limitations. **Implemented and tested for the rows named above**; the
provider-side rows use a JDK `HttpServer` rather than WireMock, and the eight documented limitations
remain true.

---

## Architecture

One Spring Boot application, modular internally — deliberately **not** a microservices showcase.
The interesting question in a financial backend is "is this money correct?", and that is hardest to
demonstrate convincingly when the system is spread across processes.

```
  API  →  application services  →  pure domain (no Spring, no JPA)  →  PostgreSQL 18
                                    ↑
             double-entry ledger · Money · state machines · reconciliation classifier
```

- The **domain layer** has no framework dependencies: every invariant is unit-testable in
  milliseconds and cannot be bypassed by persistence. An ArchUnit rule fails the build if a domain
  class imports Spring or JPA.
- **One database transaction per command**: state change, ledger posting, idempotency record, and
  audit events commit together or not at all. The ledger posting *joins* the caller's transaction
  rather than opening its own — a posting that committed independently would leave a payment marked
  `CAPTURED` with no money moved, which no query in this schema would ever find.
- The **ledger is the source of truth for money**; reconciliation derives its expectations from the
  ledger, never from the payment row, so a bug in one cannot hide a bug in the other.
- **Financial invariants live in the database** as well as in Java — immutability triggers, a
  deferred balance check, and unique indexes that make double-posting impossible.
- Background workers claim work with `FOR UPDATE SKIP LOCKED`, so a restart resumes a crashed job
  exactly where it stopped: `InboundEventWorker` for provider events, `ReconciliationWorker` for
  batches, `IdempotencyRetentionSweeper` for expired records.

**Stack:** Java 25 · Spring Boot 4.1.1 · Spring Security · Spring Data JPA · Spring JDBC ·
PostgreSQL 18.6 · Flyway · Testcontainers 2.0.5 · JUnit 5 · Mockito · AssertJ · ArchUnit · Docker ·
GitHub Actions.

No Kafka. The distributed-systems story belongs to a different project; adding a broker here would
dilute this one's.

---

## The four demonstrations

All four run over real HTTP against real PostgreSQL with the simulated provider in the loop, in
[`EndToEndScenarioIT`](src/test/java/com/reconcile/service/EndToEndScenarioIT.java). No service is
called directly, because a demonstration that skips the API demonstrates something else.

| # | Scenario | Proves |
| --- | --- | --- |
| 1 | Create → capture → PSP settles → reconcile → **`MATCHED`** → payout | The happy path posts a balanced, immutable ledger story and unwinds to exactly `PLATFORM_CASH = 3.00` and `PLATFORM_FEE_REVENUE = 3.00`, with clearing and the payable both zero |
| 2 | PSP reports the wrong amount → **`AMOUNT_MISMATCH`** → case → resolve | `delta_minor = 300` recorded, the case opens, a short note is a `400` and a bogus adjustment a `422`, a real note resolves it — and **re-running the reconciliation does not open a second case** |
| 3 | One webhook delivered **three times** | 3 delivery rows, 1 event, 1 state change, 1 ledger posting, and 3 visible deliveries in the audit trail |
| 4 | Reconciliation worker **interrupted mid-batch**, restarts | Resumability with no duplicate work and no double posting; the batch totals still agree with the rows |

---

## Specification

Read in order; each document assumes the previous one.

| Document | Contents |
| --- | --- |
| [00 — Overview](docs/spec/00-overview.md) | Scope, non-goals, architecture, transaction boundaries, concurrency strategy, phasing, **and the 8 decisions taken in this spec (flagged for review)** |
| [01 — Money and the ledger](docs/spec/01-money-and-ledger.md) | `Money`, chart of accounts, invariants L1–L11, posting, reversal, balance projection |
| [02 — Payments](docs/spec/02-payments.md) | Exact state machine, full transition table, what is rejected and why |
| [03 — Idempotency and webhooks](docs/spec/03-idempotency-and-webhooks.md) | Idempotency protocol and races; HMAC scheme; durable inbox; out-of-order events |
| [04 — Reconciliation](docs/spec/04-reconciliation.md) | Three-stage algorithm, classification precedence, case lifecycle, crash recovery |
| [05 — Database schema](docs/spec/05-database-schema.md) | Complete PostgreSQL DDL, indexes, immutability triggers, invariant-to-mechanism map |
| [06 — API contract](docs/spec/06-api-contract.md) | Every endpoint, RFC 9457 error model, security model, OpenAPI |
| [07 — Failure matrix](docs/spec/07-failure-matrix.md) | 63 designed-for failures, each with its behaviour and its test — plus 8 known limitations |
| [08 — Test matrix and DoD](docs/spec/08-test-matrix-and-dod.md) | Test inventory with stable IDs, CI stages, Definition of Done, and test anti-goals |

Architecture rationale lives separately in [`docs/adr/`](docs/adr/README.md).

---

## License

See [LICENSE](LICENSE).