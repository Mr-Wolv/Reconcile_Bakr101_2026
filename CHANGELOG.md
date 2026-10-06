# Changelog

All notable changes to Reconcile are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project does not claim
[Semantic Versioning](https://semver.org/) compatibility it has not demonstrated.

Numbers in this file are measured by a build, never estimated. The suite that produced them is
`./scripts/verify.sh`, and the HTTP smoke test is `bash scripts/probe.sh`.

---

## [1.0.0] — 2026-10-04

First stable release. The money path — payments, the ledger, idempotency, signed webhook
ingestion, reconciliation and operations — is implemented and verified against a real
PostgreSQL 18.6.

### What this release claims

- Double-entry ledger whose invariants L1–L11 are enforced by the database, not only by Java.
- Payment lifecycle with one database transaction per command, covering the state change, its
  ledger posting, its state-history row and its audit event.
- Idempotent command handling, including 32-way concurrency, with a documented 24-hour
  retention window.
- Signed webhook ingestion with replay protection, freshness bounds, a body-size limit and a
  durable inbox: three deliveries of one event produce one effect.
- Deterministic reconciliation against an external provider, deriving our side from the ledger
  rather than from the payment row, with eleven outcomes and a committed golden fixture.
- An audit trail that reconstructs one payment's entire story from the database alone.

### What this release does not claim

It processes no real money, integrates no real payment provider, stores no cardholder data, and
performs no KYC/AML. It makes no claim of PCI DSS, regulatory compliance, or production
readiness, and none may be added anywhere in this repository.

### Fixed in this release

Twenty-two defects were found by writing the tests and by running the built image, and are
documented with root causes in the README —
defect 1 under *Known defects found by running the built image*, the rest under
[*Defects found by writing the reconciliation and operations tests*](README.md#defects-found-by-writing-the-reconciliation-and-operations-tests).
Those worth naming:

| # | Defect | Consequence had it shipped |
| --- | --- | --- |
| 1 | A posting function reachable from no command path | An entire ledger path, correct in isolation and dead in practice, behind a green suite |
| 9 | Lock-ordering inconsistency under contention | Deadlock retries that would have been indistinguishable from a lost payment |
| 11 | Contention retried without a bound | Silent failure presented as eventual success |
| 18 | The provider pull sent no credential | Every sync answered `401` and surfaced as `503 PROVIDER_UNAVAILABLE` — "the provider is down" for a provider that was refusing us |
| 19 | The simulator reissued persisted transaction ids | A restart handed out an id already in use |
| 20 | A key past its retention window was still replayed | The 24-hour guarantee was a property of the sweeper's schedule rather than of the data |
| 21 | A late event overwrote the provider-side status after a settlement | A correctly settled payment reported `STATUS_MISMATCH` — a false discrepancy manufactured by our own ingestion order, in the direction reconciliation exists to detect |
| 22 | Three further ordering defects: a capture that overtook its own authorisation, a settlement that overtook its own capture, and a settlement that dragged a refund backwards | A capture lost permanently; a settlement lost permanently after burning the retry budget on being *early*; and defect 21 again, in a second place |

Defect 20 was found in this release's final pass, by a test for a behaviour the specification
required and no test asserted. It is the clearest argument in the repository for the rule the
project is built on: **a claim without a test is a comment.**

Defect 21 was found by `scripts/probe.sh` and not by the suite, which is the stronger argument of
the two. Every test delivers events in order on a clean database; the defect needed the worker to
apply a settlement before the event that preceded it. Nothing was wrong with the tests — the
ordering they relied on was never a guarantee, and the one harness that did not control it found the
gap on its first run.

**Defect 22 is the answer to that.** `EventOrderingIT` gives the suite control of the ordering: it
enumerates every ordering of a lifecycle rather than sampling one, forces it in SQL through
`next_attempt_at` so a permutation reproduces exactly, and drains the inbox with eight workers
released on a barrier. Its first run found **three more** defects in the same family, all of them
invisible to an in-order suite. The lesson generalises past this repository: a test that controls the
condition it depends on finds a different class of bug than a test that inherits it.

### Added in this release

- `E2E-WEB-04` and `E2E-WEB-04b` — the 413 oversize-body refusal, including the ordering claim
  that size is decided before the signature. The code path existed and had no test.
- `E2E-API-03` — an unknown field on a payment request is `400 MALFORMED_REQUEST`, not silently
  dropped. The configuration was correct and unasserted.
- `C-IDEM-06` — a key replayed after the retention window is treated as new. Found defect 20.
- `C-RECON-14` — `DUPLICATE_PROVIDER_RECORD` and `AMBIGUOUS_MATCH`, produced by dropping the
  unique keys that normally forbid them, because two unreachable classifier branches are
  indistinguishable from two deleted ones.
- `C-RECON-15` — the two sides of reconciliation are independent: corrupting the payment row does
  not move the outcome, corrupting the provider's row does.
- `I-MIG-02`, `E2E-MIG-01`, `E2E-MIG-01b` — migrations are idempotent on re-run, a failing
  migration is atomic, and the application refuses to start against a schema it cannot migrate.
- `I-WEB-08` — a webhook delivered while the database is unreachable is a server fault that writes
  nothing, and the same event is accepted once it returns.
- `U-MONEY-06` — every main source is scanned for floating-point types, with the scan's own
  non-vacuity proven.
- `E2E-SEC-01`, in full — every one of the seven admin-only routes is walked with an operator token
  and must be refused. Added by the final coverage audit: `SecurityConfigIT` proved the route table
  as configuration and one HTTP test exercised one of its seven entries, so six authorisation rules
  were in force but unexercised. A route added later without a matching `requestMatchers` line is
  exactly the regression this now catches.
- Three tests for `RequestIdFilter` sanitisation, added by the same audit. One of them corrects a
  comment rather than code: the filter's character set is *not* what stops log forging via a
  newline — the container's header parser rejects such a request before any application code runs.
  The filter's character set is the barrier for printable characters and for length, and the class
  comment now says which layer stops what.
- `C-ORD-01` … `C-ORD-04` — event ordering, controlled rather than inherited. All 24 orderings of a
  four-event lifecycle, all 6 of a webhook-only lifecycle, all 6 of a refund racing its own
  settlement, and a 64-event burst delivered from sixteen threads and drained by eight workers.
  Each ordering is forced in the database rather than hoped for, so a failure names the ordering
  that produced it. Found defect 22, all three parts.
- `E2E-API-07` … `E2E-API-11` — the five endpoints that had service coverage and no HTTP coverage:
  the ledger transaction view, the admin reversal, the batch view, case write-off and the failed-event
  backlog. Each asserts the status *and* the state left behind, because a status code alone passes
  just as well against a handler that wrote the wrong row.
- `.github/workflows/security.yml` — a scheduled OSV dependency scan (gated on a triaged baseline so
  it fails on a *new* advisory, not on the seven already decided), GitHub's dependency review on
  every pull request, OSSF Scorecard for supply-chain posture, Semgrep SAST at `ERROR` severity, and
  a non-gating OWASP ZAP baseline scan whose report is uploaded.
- `docs/roadmap.md` — the deferred work written down as planning rather than as an apology: the NVD
  scan with the arithmetic that blocks it, multi-provider with the payload-mapper cost people
  underrate, refund modelling on reconciliation's expected side (a real limitation found while
  writing the ordering tests), SHA-pinned actions, and an authenticated ZAP scan.
- A deterministic ordering seed. CI passes the commit SHA, so one commit always explores the same
  orderings and each new commit explores new ones; a nightly soak rotates it. Every run prints the
  orderings it explored, so a green run is a record rather than a silence — measured: the same seed
  produces the same digest twice (`e1cb2d615dbe`) and a different seed does not (`715d5c0fecfc`).
  `EventOrderingIT` now hashes the seed rather than parsing it, because `Long.getLong` would reject
  a git SHA as malformed and fall back to the clock — silently reintroducing the non-determinism.
- `deferred_attempts` on `provider_events`, and `ProviderEventProcessor.BlockedEventException`: "not
  applicable yet" is now a third outcome, distinct from both failure kinds, so an event that
  overtook its own capture is re-queued without spending a retry attempt.

### Changed in this release

- `V1__baseline.sql` gained `provider_events.deferred_attempts`, which changes its Flyway checksum.
  Editing a shipped baseline migration is only legitimate before 1.0.0 exists — and it is the right
  call here, because a second migration file for a column that has never been released would be
  history invented to match a mistake. **The consequence is real and is worth stating plainly:** any
  database migrated from the old checksum now refuses to start with `Migration checksum mismatch`,
  which is Flyway working correctly. Recreate it (`docker compose down -v && docker compose up -d`)
  rather than reaching for `flyway repair`, which would teach the database to accept a migration
  that no longer matches the file.
- `RequestIdFilter`'s comment now states the real division of labour between the container's header
  parser and its own sanitiser, rather than claiming the sanitiser stops log forging outright. No
  behavioural change; the first comment written to describe a defence that had not been tested.
- Seven integration test classes moved from their own Testcontainers PostgreSQL to the shared one.
  `WebhookWithoutDatabaseIT` and `FlywayMigrationIT` keep theirs, for reasons in §7a. Each converted
  class truncates in `@BeforeEach` or asserts only on uniquely-keyed rows, so none of them can see
  another's residue.

### Known limitations

These are deliberate and documented rather than fixed:

- **One provider.** The webhook pins `SIMULATED_PSP`. Accepting the provider from the request
  would let a holder of one provider's secret file events under another provider's name. The schema
  already keys every provider table on `provider`, so this is a seam rather than a rewrite —
  [`docs/roadmap.md §2`](docs/roadmap.md) scopes the part that is not small, which is that two real
  PSPs' payload vocabularies do not line up.
- **The dependency scan is scheduled and gated on a triaged baseline**, and now has a second
  keyless database behind it: `scripts/osv_check.py` queries OSV for the resolved tree, and
  `actions/dependency-review-action` adds the GitHub Advisory Database on every pull request — results
  in [`docs/security/dependency-findings.md`](docs/security/dependency-findings.md). One finding
  applied (CVE-2026-65182, Tomcat, fixed by pinning 11.0.26); the seven that remain are recorded as
  unreachable by configuration and are listed in
  [`docs/security/osv-baseline.json`](docs/security/osv-baseline.json), which is what the CI gate
  compares against so a *new* advisory is what fails. The third opinion — OWASP dependency-check, the
  only one that reads bytecode and so the only one that catches a shaded artifact with an innocent
  coordinate — is **blocked on an `NVD_API_KEY`**, which is issued against a person and cannot be
  created by the repository. Its workflow was wrong until this release: it claimed a key made the run
  "minutes" and allowed 45 of them, which a cold sync of NVD API 2.0 could never satisfy; the
  timeout is now 300 minutes and the NVD store is cached. Scoping is in
  [`docs/roadmap.md §1`](docs/roadmap.md).
- **GitHub Actions has never executed.** Every workflow parses; none has ever run. The runner,
  concurrency groups, artifact upload, and the Semgrep, Scorecard and ZAP steps are all unexercised,
  and actions are referenced by tag rather than by commit SHA because an invented SHA is a workflow
  that fails at its first step. Treat the first run of each as a measurement.
- **SAST and a penetration scan are configured and have never run.** Semgrep over `p/java` gates
  on `ERROR` severity; GitHub dependency review gates on high; Scorecard and ZAP baseline scanning
  are non-gating and upload their reports. The first ZAP run will report findings nobody has
  triaged, which is why it does not gate — a decision about sequencing, not evidence that the
  application passes it.
- **Retention depends on a scheduled sweeper for storage**, though not for correctness: a key's
  replay window is enforced by the reservation path (defect 20), and the sweeper only reclaims
  rows.
