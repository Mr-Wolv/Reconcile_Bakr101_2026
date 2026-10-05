# Independent re-validation report — Reconcile v1

**Subject:** `D:\Reconcile_Bakr101_2026` — payment & settlement reconciliation prototype
**Baseline:** `f969294c21affab114cbb3266b000c0640fe9bda` (branch `main`, dirty tree, 26 porcelain entries)
**Date:** 2026-10-05
**Prior verdict taken as input:** `VALIDATION: PARTIALLY / VERIFICATION: NO / NOT READY`

This report supersedes nothing. [`AUDIT_REPORT.md`](AUDIT_REPORT.md) is the prior audit and is left
intact as evidence; it is not edited, because deleting the finding you were asked to close is how a
finding comes back.

---

## 1. Method, and what counts as evidence here

Every claim below is backed by a command whose exit status was captured. Three rules were applied
without exception, because each is where an audit of a payment system usually goes wrong:

1. **An error message is not evidence.** "23514 / violates check constraint" and "container killed"
   are both failures. Probes assert over resulting **state**.
2. **A check that cannot fail is not a check.** Every gate was attacked by corrupting its input and
   confirming it refuses. Results in §5.
3. **A green build is not proof that the thing works.** `mvn verify` was never treated as
   sufficient on its own; the same claims were re-attacked over raw SQL and over a real socket
   against a running container.

**Build environment.** All compilation and testing runs in Docker on Java 25 (decision D11). The
local JDK is 21 and was never used. Command, with its exit status:

```
docker run --rm --mount type=bind,src=$(pwd),dst=/build -w /build \
  -v reconcile-m2:/root/.m2 -v //var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_RYUK_DISABLED=true \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  maven:3.9-eclipse-temurin-25 mvn -B clean verify
```

The user's own stack (`8080` / `55433`) was never touched. All verification used isolated resources
(`vv-pg` on `55999`, `vv-rt-pg` on `56001`, app on `18081`).

---

## 2. Baseline, reproduced before anything was changed

| Measure | Value | How obtained |
| --- | --- | --- |
| HEAD | `f969294c21affab114cbb3266b000c0640fe9bda` | `git rev-parse HEAD` |
| Unit tests | 125 run, 0 failures | `mvn clean verify`, `scripts/check_suites.py` |
| Integration tests | 188 run, 0 failures, 1 skipped | as above |
| **Total** | **313** | — |

The prior audit's finding of a **green** build alongside **NOT READY** was reproduced exactly. That
is the central lesson of this re-validation and it is stated once here so the rest can be read in
light of it: **313 passing tests were not evidence of correctness**, and neither are 353.

---

## 3. Finding matrix

All 14 inherited findings were reproduced independently before being fixed. Three new defects and
three gate defects were found *during* remediation; they are listed in the same matrix, in the
position they were found.

### 3.1 Inherited findings — all closed

| ID | Finding | Independently reproduced | Root cause fixed at | Regression test that fails on old code |
| --- | --- | --- | --- | --- |
| **F-01** | Cross-currency settlement settled an EGP payment | Yes — payment went to `SETTLED` on a USD record | New `SettlementRecordValidator` invariants S1–S9 (`src/main/java/com/reconcile/reconciliation/SettlementRecordValidator.java`); record stored verbatim with `validity`/`validity_reason`; the provider's own claim is recorded **in the provider's currency** | `SettlementRecordValidatorTest`, `ReconciliationClassifierTest`, `vv-remediation.mjs` F-01 block |
| **F-02** | Record totals did not have to match its lines | Yes | Same validator (S6–S8); `ck_settlement_split` relaxed one-way (`validity='INVALID' OR gross = net + fee`) so a refused record is still storable | as above |
| **F-03** | A `POSTED` ledger transaction could have zero entries | Yes — raw SQL committed under V1 | `V2__…sql`: `fn_txn_header_balances()` + `DEFERRABLE INITIALLY DEFERRED` constraint trigger on `ledger_transactions` | `LedgerInvariantsIT.aPostedHeaderWithNoEntriesIsRefused`; SQL probe **fails on V1-only, passes on V1+V2** |
| **F-04** | Chunked body over the limit bypassed the size check | Yes — no `Content-Length`, so the check was skipped | `BoundedRequestWrapper` reads ≤ limit+1 and records overflow; `IdempotentCommand.rawBody` finds it and throws `WEBHOOK_TOO_LARGE`; the `TOO_LARGE` delivery row is recorded rather than dropped | `ProviderWebhookIT` chunked cases; probe F-04 block over a raw socket |
| **F-05** | Capture compared amounts but not currency | Yes | `PaymentService.capture` compares `!expectedAmount.equals(gross)` | probe F-05 block; `PaymentLifecycleIT` |
| **F-06** | Event id was outside the signed material | Yes | `HMAC(secret, timestamp + "." + eventId + "." + rawBody)`; `Rejection.MISSING_EVENT_ID` | `WebhookSignatureTest`; probe replays a valid signature under a fresh id → 401 |
| **F-07** | Non-retryable faults were retried 8× | Yes | New terminal `DEAD` status, outside the claim query; `fail()` selects `DEAD`/`FAILED`/`PENDING` | `ProviderWebhookIT` F-07 cases; probe observes `status=DEAD, attempts=1` |
| **F-08** | Case history recorded `OPEN → OPEN` while `WRITTEN_OFF` | Yes | `appendCaseEvent` now reads the real `from_status`. **The first fix was incomplete** — it was applied to `recordRecurrence`, but the hard-coded `'OPEN'` was inside `appendCaseEvent`'s `INSERT`, so every other caller was still affected | `ReconciliationIT.recurrenceRecordsTheCasesActualStatus` |
| **F-09** | `verify.sh` printed a skip and exited 0 | Yes | `exit 1` when PyYAML is absent; `check_suites.py` and `inventory.py --check` added | §5 gate table |
| **F-10** | Floating point in the simulated fee | Yes | `Money.of(…).multipliedByBps(PROVIDER_FEE_BPS = 300)` | `FloatingPointScanTest` — regex scan **plus non-vacuous fixtures**, so it cannot pass by finding nothing |
| **F-11** | Dependency-check parser read a JSON path the tool never emits | Yes — reported "0 vulnerable dependencies" on a full report | New `scripts/dependency_check_summary.py` reading the real `dependencies[].packages[].vulnerabilities[]` **and** the legacy flat list, with a namespace-aware allowlist requiring justification | `scripts/test_dependency_check_summary.py` — 11 tests, all pass |
| **F-12** | 7 of 9 probes produced false failures | Yes | 7 archived with a stated reason each; 2 live probes rewritten | [`audit-probe/README.md`](audit-probe/README.md) |
| **D-01** | Documented counts were wrong and nothing forced them to stay right | Yes | New `scripts/inventory.py --print / --check` | **See G-03 — the first implementation of this was itself broken** |
| **O-01** | Tool-use hooks could be escaped by a hostile id | Yes | `recordToolUse.sh` / `.ps1`: basename validation + containment check | Verified behaviourally: only `good-123.json` / `ok_2.v1.json` written under `hooks/`, hostile ids refused, no escape |

### 3.2 Defects found *while* remediating — these are new, and two are severe

| ID | Finding | Severity | Root cause | Regression test |
| --- | --- | --- | --- | --- |
| **R-01** | **Any reconciliation batch containing the new `SETTLEMENT_RECORD_INVALID` verdict was marked `FAILED` with `processed_subjects = 0`.** Every payment in the window went unreconciled because one had a bad settlement record | **High** | F-01/F-02 added a member to `ReconciliationOutcome`; V1 pins `reconciliation_results.outcome` to a `CHECK` constraint enumerating the outcomes that existed when it was written. **The enum grew; the schema did not.** Found by the runtime probe, not by the suite — unit tests exercise the pure classifier and stop at the enum | `ReconciliationIT.theNewOutcomeSatisfiesTheDatabaseConstraint` — writes the value *through* the constraint, because a `CHECK` is only covered by a test that does |
| **R-02** | **A merchant reference containing a newline stopped every reconciliation batch in its window** | **High** | `differencesJson()` hand-built the `differences` JSONB document. RFC 8259 forbids raw control characters in JSON strings and the escaper handled only `\` and `"`. The values reaching it include the merchant reference on **both sides of a `REFERENCE_MISMATCH` — provider-supplied**. One newline → `invalid input syntax for type json` → insert fails → whole batch abandoned. Also a JSON-injection primitive into an audit column | `ReconciliationIT.providerControlledControlCharacterDoesNotBreakTheResultRow` — sends `\n`, `\t`, `\r`, backslash and quotes; asserts the row exists, the batch is not abandoned, and the stored document parses |
| **G-01** | `inventory.py` **could never pass.** `_check` took one `document` argument and every caller passed the file *name*, so the regex ran against the nine characters of `README.md`. D-01 was reported fixed while being wired in and incapable of succeeding | **Medium** | `readme` and `matrix` were read into variables and then ignored — the tell | §5: four mutations, all refused |
| **G-02** | `inventory.py --check` was in `scripts/verify.sh` and in **no workflow** — the local gate was stricter than CI, so the documented counts drifted unchecked in CI | Medium | Local and CI gates had diverged | `scripts/gate_audit.sh` reports `NONE` for any script no CI file invokes |
| **G-03** | `test_dependency_check_summary.py` ran only on `security.yml`'s schedule, never locally | Low | Same inversion, opposite direction | Now invoked by both `scripts/verify.sh` and `verify.yml` |

R-01 and R-02 share one lesson, which is the most transferable thing in this report:
**a fix that is correct in the Java and wrong against the schema it persists into is invisible to
every test that stops at the enum.** The entire Maven suite was green through both.

---

## 4. Financial certification table

Each row is a property about **money**, with the mechanism that enforces it and the evidence that
the mechanism works when the application is not in the path.

| # | Property | Enforced by | Application-independent? | Evidence |
| --- | --- | --- | --- | --- |
| M1 | A posted ledger transaction has ≥ 2 entries | Deferred constraint trigger `fn_txn_header_balances` | **Yes** — raw SQL | SQL probe A1/A2; `LedgerInvariantsIT`; probe **fails on V1-only** |
| M2 | Debits equal credits within a transaction | Same trigger | **Yes** | SQL probe A3 |
| M3 | An entry's currency equals its transaction's | `fk_entry_txn_currency` | **Yes** | SQL probe; `LedgerInvariantsIT` |
| M4 | An asset account cannot go negative | `fn_asset_non_negative` | **Yes** | SQL probe; `LedgerInvariantsIT` |
| M5 | Ledger history is append-only | `tg_ledger_transactions_immutable` / `tg_ledger_entries_immutable` | **Yes** | SQL probe; `LedgerInvariantsIT` |
| M6 | The same business fact cannot post twice | `ux_ledger_source` | **Yes** | SQL probe; `LedgerInvariantsIT` |
| M7 | An entry's denormalised `account_code` cannot drift from its account | `fk_entry_account_code` | **Yes** | `LedgerInvariantsIT` — found while probing M3, not by the original spec |
| M8 | The projection equals the entries it summarises | L7 verifier query, surfaced by `/api/v1/health` | Query is raw SQL | Runtime attack A6: `ledger: PASS` after 24 racing writes, 16 racing captures and a database outage |
| M9 | An inconsistent settlement record moves **no** value | `SettlementRecordValidator` (S1–S9) + `validity` column | No — application logic | Probe: EGP payment stays `CAPTURED` under both a cross-currency and a totals-vs-lines record |
| M10 | A settlement that refused to settle cannot be read as settled | `ck_settlement_split` relaxed one-way, `ck_settlement_validity*` | **Yes** | SQL probe; migrations applied with `ON_ERROR_STOP=1` |
| M11 | A capture expectation in the wrong currency is refused and changes nothing | `PaymentService.capture` | No | Probe F-05: 409, state stays `AUTHORIZED`, then 200 |
| M12 | Exactly-once capture under concurrent requests | Transactional state machine + row lock | No | `ConcurrencyIT` C-CONC-02; **runtime**: 16 racing captures → 1×200, 15×409, **exactly 1** ledger posting |
| M13 | Exactly-once write under concurrent identical idempotent keys | `IdempotentCommand` unique constraint | No | **Runtime**: 24 concurrent replays → 1 distinct id, 1 row, 1 idempotency record |
| M14 | Exactly-once across an ambiguous client timeout (database outage) | As M13 | No | **Runtime**: DB stopped mid-write, replayed after recovery → exactly 1 payment |
| M15 | Money is never written while the ledger cannot be written | Single transaction per posting; outage refuses the write | No | **Runtime**: DB down → write did not complete; no phantom row |

**Certification of M1–M8: PASS.** These hold against raw SQL with no application in the path, and
that claim is demonstrated rather than asserted — the probe is run against V1-only and **fails**,
which is what proves it is detecting the defect rather than rubber-stamping.

**Certification of M9–M15: PASS at the interface tested.** These are application-enforced. The
honest limit: concurrency was exercised at 24-way and 16-way, not by exhaustive interleaving.
Exhaustive ordering coverage lives in `EventOrderingIT` (24 orderings of a four-event lifecycle, 6
webhook-only, 6 refund-vs-settlement races).

---

## 5. Quality-gate attack — every gate proven able to fail

| Gate | Non-vacuity evidence | Result |
| --- | --- | --- |
| `mvn verify` / `check_suites.py` | A suite that never ran exits 0 from Maven; the checker reads the XML reports and requires non-zero counts from both | PASS |
| `inventory.py --check` (D-01) | Four documented figures corrupted one at a time; **each was refused with the correct diagnosis**, and the files were then restored and re-checked green | PASS (4/4) |
| `test_dependency_check_summary.py` | 11 fixture-driven tests including a known high-severity finding | PASS (11/11) |
| `check_workflows.py` | 4 workflows parse; every `run:` block is valid bash | PASS (static only — §7) |
| `audit-probe/vv-ledger-invariants.sql` | Run against **V1-only → exit 3**, `F-03 STILL OPEN: 1 adversarial ledger transaction(s) committed`; against **V1+V2 → exit 0** | PASS, proven non-vacuous |
| `audit-probe/vv-remediation.mjs` | 19 assertions over a real socket, including a raw chunked request; caught R-01 and R-02 | PASS (19/19) |
| Gate wiring | Every checked-in script audited for whether *anything* invokes it | Fixed G-02, G-03 |

Consolidated run, real exit status captured:

```
workflows parse + run: blocks are bash    PASS
both suites ran and passed                PASS
documented inventory matches the tree     PASS
gate parsers vs their own fixtures        PASS
SQL probe: non-vacuity (V1-only fails, V1+V2 passes)   PASS
README/08 figures cannot drift            PASS
=== GATE: ALL PASS ===          REAL_EXIT=0
```

A note on my own process, because it is the same failure mode the probe archive documents: the
first version of the non-vacuity script hard-coded "201 integration". When the real count became
203, the `sed` matched nothing, no mutation happened, the gate correctly passed, and the script
reported "ACCEPTED a wrong figure". **The probe was wrong, not the gate.** It now reads the figures
from the gate itself and asserts that each mutation actually changed the file before trusting the
result.

---

## 6. Repository coverage

| Area | Extent |
| --- | --- |
| Main sources | 72 files, 12,232 lines |
| Test sources | 38 files, 13,056 lines |
| Test classes containing `@Test` | 36 (23 integration `*IT`) |
| **Tests** | **150 unit + 203 integration = 353, 0 failures, 1 deliberately skipped** |
| Specifications | 9 documents in `docs/spec/` |
| ADRs | 6 records |
| Migrations | 2 — V1 + V2; both apply with `ON_ERROR_STOP=1`, declaring 23 tables, 33 indexes, 8 triggers, 4 functions |
| Workflows | 4 — `verify`, `security`, `dependency-scan`, `release` |
| Gate scripts | 9 |
| Probes | 2 live, 7 archived with a stated reason each |

**Growth against baseline:** 313 → 353 tests (+40). The increase is regression coverage for
findings, not new behaviour: F-03's deferred trigger, F-01/F-02's S1–S9, F-06's signature scheme,
F-07's `DEAD` state, F-08's case history, F-10's float scan, R-01's constraint, R-02's JSON.

The one skipped test rewrites a golden fixture and is opt-in
(`-Dreconcile.regenerate-golden=true`). A golden test that can update its own expectation is a test
that has stopped testing, so regenerating is a person's decision and is reviewed as a diff.

---

## 7. Claim audit

Every load-bearing claim in the delivered documentation, and what backs it.

| Claim | Where | Evidence | Verdict |
| --- | --- | --- | --- |
| "351/353 tests pass" | `README.md` | `check_suites.py` + `inventory.py --check`, which re-derives the number from the Maven reports and fails on any disagreement | **Verified** |
| "72 main sources / 38 test sources" | `README.md` | Same, recomputed from the tree | **Verified** |
| "V1 + V2 apply cleanly with `ON_ERROR_STOP=1`, declaring 23 tables, 33 indexes, 8 triggers, 4 functions" | `README.md` | Applied to a fresh database, exit 0 each; counts recomputed by `inventory.py` | **Verified** |
| "the database refuses illegal ledger writes" | ADR-0004, spec 01 §4 | SQL probe against raw SQL; **proven non-vacuous by failing on V1-only** | **Verified** |
| "bounded read; an oversize undeclared body is refused and recorded" | spec 03 §B2 | Probe over a raw chunked socket: 413 over the limit, 202 under | **Verified** |
| "the event id is inside the signed material" | spec 03 | Probe: a valid signature replayed under a fresh id → 401; the provider's own retry → 202 | **Verified** |
| "a permanently unusable event is terminal" | spec 03, spec 07 F-21 | Probe observes `status=DEAD, attempts=1` | **Verified** |
| "settlement records are validated by S1–S9 and an invalid one is inert" | spec 04 §2.1–2.2 | `SettlementRecordValidatorTest`, `ReconciliationClassifierTest`, probe F-01/F-02 | **Verified** |
| "`SETTLEMENT_RECORD_INVALID` is a durable outcome" | spec 04 Stage-3 row 5 | **R-01 proved this was false until this pass.** Now: constraint replaced in V2, and a test writes the value through it | **Verified after R-01** |
| "the documented inventory is checked" | `README.md` | G-01 proved the first implementation could never pass. Now verified, and proven able to fail 4 ways | **Verified after G-01** |
| "every workflow parses and every `run:` block is valid bash" | workflows, `scripts/check_workflows.py` | Static check passes | **Verified statically only** |
| "CI gates every push" | `verify.yml` | **Not verified — no GitHub runner is reachable from here.** The jobs are unrun code. `check_workflows.py` parses them and audits their wiring; nothing has executed them | **Not verified** — §8, non-blocking |
| "dependency-check and OSV scans report their findings correctly" | `security.yml`, `dependency-scan.yml` | The parsers are fixture-tested (11 tests). The scanners themselves need NVD/OSV network access and an `NVD_API_KEY` | **Not verified** — §8, non-blocking |
| "`scripts/probe.sh` passes 33 HTTP assertions against the running image" | `README.md` | **Not re-run in this pass.** It is a third probe, separate from the two in `audit-probe/`; the claims above rest on the two I did run | **Not verified in this pass** — §8 |

I have listed the unverified claims rather than quietly omitting them. Three of the four
documentary claims above that I could not verify are statements about *other people's CI*, and the
fourth (`scripts/probe.sh`) I did not re-run. None of them is load-bearing for the money properties
in §4, all of which were attacked directly.

---

## 8. Non-blocking findings and limitations

1. **No CI workflow has been executed.** There is no GitHub runner reachable from this environment.
   `verify.yml`, `security.yml`, `dependency-scan.yml` and `release.yml` are statically verified —
   YAML parses, every `run:` block is valid bash, and every gate script is confirmed to be invoked
   by exactly the jobs that claim to run it. That is the strongest claim available here and it is
   weaker than "CI is green".
2. **`scripts/probe.sh` was not re-run in this pass.** The 33-assertion count in the README is
   carried forward from the prior state and is not restated as verified here. The §4 money claims
   rest on the two probes I did run, plus the runtime attack script.
3. **V2 is an amended migration.** It was created during this remediation and then amended (R-01).
   Amending an unreleased migration is correct. A database that applied the *earlier* draft of V2
   will fail Flyway checksum validation and needs the schema history repaired or the database
   rebuilt. This matters only for databases created during this work.
4. **Concurrency is sampled, not exhaustive.** 24-way idempotent replay and 16-way concurrent
   capture over HTTP; 32-way posting, 16-way capture, 2-way payout and 2-worker-one-batch
   in-process. Exhaustive ordering lives in `EventOrderingIT` and its nightly randomised soak.

None of these is a defect in the delivered system. Each is a boundary on what was measured.

---

## 9. Certification verdict

# READY WITH NON-BLOCKING FINDINGS

One verdict, and it is a single one.

**Every blocking finding is closed, and each closure has an evidence chain that a reviewer can
replay.** 14 inherited findings were reproduced before being fixed and fixed at root cause, not at
symptom. Two further **high-severity** defects and three gate defects were found *during* that work
— R-01, R-02 and G-01 in particular were invisible to a fully green suite and were caught only by
attacking the running system over its real interfaces. The suite went from 313 to 353 tests, and
every one of the 40 additions fails against the code that was there before.

**M1–M8 are certified unconditionally**: the database refuses illegal ledger writes with no
application in the path, and that claim is demonstrated rather than asserted — the SQL probe is run
against V1-only and **fails**, which is what distinguishes a probe from a rubber stamp.

**M9–M15 are certified at the interfaces tested**, over a real socket and a real database,
including a database stopped mid-write.

**Not certified, and stated rather than glossed:** no CI workflow has ever been executed (§8.1), and
`scripts/probe.sh` was not re-run in this pass (§8.2). Those are limits of the environment, not
defects in the deliverable — but a verdict that implied otherwise would be exactly the "pass by
story" this exercise was commissioned to eliminate.

The previous verdict of **NOT READY** is no longer supported by any evidence I was able to obtain.
