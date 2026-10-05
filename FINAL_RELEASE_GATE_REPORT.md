# Final independent release-gate audit — Reconcile v1

**Subject:** `D:\Reconcile_Bakr101_2026`
**HEAD:** `f969294c21affab114cbb3266b000c0640fe9bda` (branch `main`, unchanged by this pass)
**Hypothesis under test:** the previous pass's `READY WITH NON-BLOCKING FINDINGS`
**Date:** 2026-10-05/06

This audit treated that verdict as unproven. It is neither endorsed nor denied by assumption; each
claim below was re-derived from the repository, the migrations, the reports and a running container.

**State preservation (§1).** HEAD unchanged, nothing committed, nothing stashed or reset.
`git status --porcelain` is byte-identical to the snapshot taken before this pass began.
`AUDIT_REPORT.md` and `REVALIDATION_REPORT.md` are **unmodified** (md5 verified before and after).
Where the prior report contains a factual error, it is corrected *here* rather than edited there —
rewriting a historical record is falsification, not correction.

---

## A. Executive verdict

# READY WITH NON-BLOCKING FINDINGS

The previous verdict survives adversarial review. It did not survive it unchallenged: this pass
**found and fixed two further defects** (G-04 and S-01 below), one of which was a gate that
reported the same number whether or not a unit test had failed.

Three claims in the prior report did not survive either. One of them was the mandatory §6
discrepancy, and it was real.

---

## B. Finding matrix

| ID | Previous severity | Current status | Evidence | Regression protection | Remaining risk |
| --- | --- | --- | --- | --- | --- |
| F-01 | High | **CLOSED** — reopened only to confirm, not reopened as a defect | Probe over real HTTP: cross-currency settlement → payment stays `CAPTURED`; batch `COMPLETED`; result `SETTLEMENT_RECORD_INVALID`. Persisted: 0 payments settled by an invalid record, 0 ledger rows sourced from one | `SettlementRecordValidatorTest`, `ReconciliationClassifierTest`, probe | None at the tested boundary |
| F-02 | High | **CLOSED** | Totals-vs-lines record settles nothing; probe + persisted-state audit | as above | None at the tested boundary |
| F-03 | High | **CLOSED** | My own raw SQL: `ledger transaction m1 is POSTED with 0 entries; at least 2 are required`, nothing persisted. Repo probe **fails on V1-only, passes on V1+V2** | `LedgerInvariantsIT`, SQL probe (proven non-vacuous) | None |
| F-04 | Medium | **CLOSED** | Chunked body over limit → 413 over a raw socket; **1 `TOO_LARGE` delivery row persisted** | `ProviderWebhookIT`, probe | None |
| F-05 | Medium | **CLOSED** | 409, payment stays `AUTHORIZED`; **0 CAPTURED payments lacking a capture posting** | probe, persisted audit | None |
| F-06 | Medium | **CLOSED** | Valid signature replayed under a new id → 401; provider's own retry → 202 | `WebhookSignatureTest`, probe | None |
| F-07 | Medium | **CLOSED** | `status=DEAD, attempts=1`; **0 DEAD events with attempts>1; 0 FAILED** | `ProviderWebhookIT`, probe, persisted audit | None |
| F-08 | Medium | **CLOSED** | `ReconciliationIT.recurrenceRecordsTheCasesActualStatus` green; case-event history written from real `from_status` | `ReconciliationIT` | None |
| F-09 | Medium | **CLOSED** | `check_workflows.py` refuses an unparseable `run:` block (exit 1), green on restore | attack evidence below | None |
| F-10 | Low | **CLOSED** | Float scan reads all 72 main sources; claim machine-checked by `inventory.py` | `FloatingPointScanTest` | None |
| F-11 | Medium | **CLOSED, and strengthened this pass** | Parser blocks a real HIGH finding (exit 1, `BLOCKING CVE-2024-0001 cvss=8.1`); honours a justified suppression (exit 0); **now refuses an unrecognised document** (exit 2) | 12 fixture tests | See S-01 |
| F-12 | Low | **CLOSED** | Both live probes green; 7 archived with stated reasons | probe run evidence | None |
| D-01 | Medium | **CLOSED, with a defect found inside the fix this pass** | `inventory.py --check` refuses 7 distinct corrupted figures, arithmetic self-check included | attack evidence below | G-04 was inside this gate; now fixed |
| O-01 | Low | **CLOSED** | Hostile ids refused; writes confined to `hooks/` | behavioural verification | None |
| R-01 | High | **CLOSED and generalised** | Enum (12) ↔ CHECK constraint (12) ↔ severity map converge exactly. Removing `AMBIGUOUS_MATCH` from V2 makes the suite **fail**; restoring turns it green | `theNewOutcomeSatisfiesTheDatabaseConstraint` + **new** `everyOutcomeValueIsAcceptedByTheSchema` | None; recurrence now blocked generally, not just for one value |
| R-02 | High | **CLOSED** | Runtime: API refuses a hostile reference (400); a hostile **provider** reference → batch `COMPLETED`, `REFERENCE_MISMATCH` persisted, JSONB parses, string stored as data, L7 `PASS` | `ReconciliationIT` + runtime attack | Provider string reaches `differences`; now serialised by a real serialiser |
| G-01 | Medium | **CLOSED** | `inventory.py` reads document **text**, not filenames; 7 mutations refused | attack evidence | None |
| G-02 | Medium | **CLOSED** | `inventory.py` now invoked by `verify.yml` | `gate_audit.sh` | None |
| G-03 | Low | **CLOSED** | Parser tests invoked by `verify.sh` **and** `verify.yml` | `gate_audit.sh` | None |
| **G-04** | **Medium (NEW)** | **FIXED this pass** | `inventory.py` reported `passed=353` when 352 passed. Its formula **omitted unit failures and integration skips**, so it returned the same number whether or not a unit test had failed — a gate structurally blind to a broken unit suite | Injected a unit failure: `passed` moved 352 → 351. Old formula wrong in 3 of 4 scenarios | None; formula now derives all four quantities and the gate checks them |
| **S-01** | **Low (NEW)** | **FIXED this pass** | `dependency_check_summary.py` refused unreadable files but read `{}` — a document it did not understand — as "0 vulnerabilities", exit 0. Same failure *class* as F-11, one level up | 12th fixture test; `{}`, `{"status":"ok"}`, `[1,2,3]` all → exit 2 | None |

No prior finding is reopened: every one was re-attacked and the underlying defect is absent.

---

## C. Evidence matrix

`CLAIMED` → `IMPLEMENTED` → `TESTED` → `OBSERVED` → `PROVEN`. I do not advance a row past what the
evidence supports. "TESTED" alone is explicitly **not** treated as proven.

| Req | Implementation | Automated test | Adversarial probe | Runtime evidence | Database evidence | Status |
| --- | --- | --- | --- | --- | --- | --- |
| **M1** ≥2 entries per posted txn | `fn_txn_header_balances`, deferred constraint trigger | `LedgerInvariantsIT` | my raw SQL + repo probe | — | refused by name; 0 survivors | **PROVEN** |
| **M2** debits = credits | same trigger | `LedgerInvariantsIT` | my raw SQL | — | `does not balance: debits=10000 credits=9999` | **PROVEN** |
| **M3** entry ccy = txn ccy | `fk_entry_txn_currency` | `LedgerInvariantsIT` | my raw SQL | — | FK named; 0 survivors | **PROVEN** |
| **M4** asset ≥ 0 (liability may go < 0) | `fn_asset_non_negative` | `LedgerInvariantsIT` | **my raw SQL** (not in repo probe) | — | refused by name; control confirmed a liability went to −500 | **PROVEN** |
| **M5** history immutable | `tg_*_immutable` | `LedgerInvariantsIT` | my raw SQL | — | both UPDATE and DELETE refused by name | **PROVEN** |
| **M6** no double posting | `ux_ledger_source` | `LedgerInvariantsIT` | my raw SQL | — | constraint named; still 1 posting | **PROVEN** |
| **M7** no `account_code` drift | `fk_entry_account_code` | `LedgerInvariantsIT` | my raw SQL | — | constraint named; 0 drifted entries | **PROVEN** |
| **M8** projection = entries | L7 verifier query | — | my raw SQL (inject drift → 5 rows detected; recompute → 0) | `ledger: PASS` after 24 replays, 16 captures, DB outage | drift rows = 0 on the live DB | **PROVEN** |
| **M9** invalid settlement moves no money | `SettlementRecordValidator` S1–S9 | `SettlementRecordValidatorTest` | `vv-remediation.mjs` | payment stays `CAPTURED` | 0 payments settled by an invalid record; 0 ledger rows | **PROVEN** |
| **M10** invalid cannot read as settled | `validity` + `ck_settlement_*` | `ReconciliationIT` | my raw SQL + probe | outcome is `SETTLEMENT_RECORD_INVALID`, never `MATCHED` | 0 `MATCHED` results for invalid-covered transactions | **PROVEN** |
| **M11** wrong-ccy capture rejected, no state change | `PaymentService.capture` | `PaymentLifecycleIT` | probe | 409, state stays `AUTHORIZED` | 0 CAPTURED payments without a capture posting | **PROVEN** |
| **M12** concurrent capture = exactly one | state machine + row lock | `ConcurrencyIT` | runtime | 16 racing → 1×200, 15×409 | exactly 1 posting, 3 entries | **PROVEN at 16-way** |
| **M13** concurrent identical writes = one | unique constraint | `ConcurrencyIT` | runtime | 24 racing → 1 id | 1 payment row, 1 idempotency record | **PROVEN at 24-way** |
| **M14** replay after ambiguous outage is idempotent | as M13 | — | runtime | write returned `000`; replay after recovery → 1 payment | rows = 1 | **PROVEN** |
| **M15** no money without a ledger | single transaction | — | runtime | DB stopped → write did not complete | no phantom row | **PROVEN** |
| **CI gates every push** | `verify.yml` | — | static inspection only | **none** | — | **NOT EXECUTED — ENVIRONMENT LIMITATION** |

The single qualifier in that table is the concurrency row: 16-way and 24-way are **sampled**, not
exhaustive. Exhaustive ordering is enumerated by `EventOrderingIT`, which I did not re-run this
pass and therefore mark UNKNOWN rather than TESTED.

---

## D. Claim audit

### D.1 The mandatory §6 discrepancy — RESOLVED

The prior report's claim-audit row asserted the README claimed `"351/353 tests pass"`. **The README
never said that, and 351 was wrong.** Two independent defects, not one:

1. **The prior report's figure was stale and semantically confused.** `351` was a build-11-era
   numerator (150 + 201). It was also labelled "tests pass" against a build with a skipped test.
2. **The tool producing the number was wrong** — this is the finding that mattered.

`inventory.py` computed `passed` as
`unit.tests − unit.errors − unit.skipped + integ.completed − integ.failures − integ.errors`,
**omitting `unit.failures` and `integration.skipped`**. It therefore reported the *discovered total*
under the name *passed* (353 when 352 passed), and returned **the same number whether or not a unit
test had failed**. Demonstrated before fixing: wrong in 3 of 4 synthetic scenarios; `passed` 352 → 351
when a unit failure was injected.

Authoritative semantics, derived from the Maven XML (`failsafe-summary.xml` uses **elements**, not
attributes — the trap `check_suites.py` itself documents):

| Quantity | Before this pass | Now |
| --- | --- | --- |
| discovered (`tests` attribute) | 353 | **354** |
| executed (discovered − skipped) | 352 | **353** |
| **passed** | 353 *(wrong)* | **353** |
| skipped | 1 | **1** |
| failed / errored | 0 | **0** |

`ReconciliationGoldenIT::regenerateTheGoldenFile` is the one skipped test — deliberate, opt-in via
`-Dreconcile.regenerate-golden=true`.

**Resolution:** `inventory.py` now derives all four quantities correctly, checks the README against
each, and asserts the arithmetic (`354 = 353 + 1`, `353 = 353 + 0 + 0`). The README states the
distinction explicitly. `REVALIDATION_REPORT.md` is left unmodified; this section supersedes its row.

### D.2 Forbidden-claim sweep

151 distinct numbers and the full forbidden vocabulary were swept across every `.md`, `.yml`, `.sh`,
`.py`, `.mjs` and `.sql`. Adjudication of every hit:

- **`AUDIT_REPORT.md:374-375`** — a "DO NOT CLAIM" table listing production-ready, PCI compliant,
  zero-data-loss, exhaustive concurrency. The **opposite** of an overclaim.
- **`RELEASE.md:13`** — "It does **not** mean the software is production-ready, regulated, audited,
  or safe to handle real [money]". A disclaimer.
- **`README.md:178`, `CHANGELOG.md:52`** — "guarantee" describing a defect that was *fixed*. Historic.
- **`README.md:108`, `verify.yml:146`** — scoped properties ("exactly once" for one event class;
  "the exhaustive legs of `EventOrderingIT`"). **Both scoped; neither absolute.**
- No occurrence anywhere of an unqualified production-ready, PCI, real-PSP, zero-data-loss,
  cloud-availability, disaster-recovery or regulatory-compliance claim.

**No unsupported claim of the forbidden kind was found.**

### D.3 Numbers still checked by hand, not mechanically

`inventory.py` verifies source counts, test counts and migration declarations. It does **not**
verify: the "33 HTTP assertions" figure, the "5 ADRs" / "9 specs" counts, or per-ID counts in the
failure matrix. I re-ran the probe and measured **33 PASS, 0 FAIL, exit 0 — twice, cold and warm**,
so the 33 claim is now verified by observation rather than by memory. The spec/ADR counts I did not
re-derive and mark UNKNOWN.

---

## E. Release-gate result

| Gate | Result | Evidence |
| --- | --- | --- |
| Build | **PASS** | `mvn -B clean verify`, `BUILD SUCCESS`, Java 25 in Docker |
| Unit | **PASS** | 150 run, 0 failures, 0 errors, 0 skipped |
| Integration | **PASS** | 204 run, 0 failures, 0 errors, 1 skipped |
| Skipped | **1, deliberate** | `ReconciliationGoldenIT::regenerateTheGoldenFile`, opt-in |
| Live probe (`scripts/probe.sh`) | **PASS — 33/33, twice** | **previously NOT VERIFIED; now executed** |
| Live probe (`vv-remediation.mjs`) | **PASS — 19/19** | against the compose image |
| Raw SQL invariant probe | **PASS, proven non-vacuous** | V1-only → exit 3; V1+V2 → exit 0 |
| Independent raw SQL M1–M8 | **PASS 8/8** | each refusal attributed to a named rule |
| Persisted-state audit | **PASS 14/14** | after all attacks |
| Concurrency / outage | **PASS 6/6** | 24-way, 16-way, outage + replay |
| Migration integrity | **PASS** | fresh DB applies V1+V2 with `ON_ERROR_STOP=1`; 23 tables; Flyway history clean, all `success=true`, non-null checksums |
| Workflow static check | **PASS** | 4 workflows parse; every `run:` block valid bash |
| Gate wiring | **PASS** | every checked-in script invoked by `verify.sh` and/or a workflow |
| **CI execution** | **NOT EXECUTED — ENVIRONMENT LIMITATION** | no GitHub-hosted runner reachable |

### CI boundary — the distinction the brief asks for

**CI logic is statically verified. CI execution is not verified and was not attempted.**

Verified statically: all actions SHA-pinned (0 unpinned); no `|| true` anywhere; the three
`continue-on-error: true` occurrences are all on documented non-gating jobs (`score`, `zap`, and
`soak`, which additionally carries `if: github.event_name == 'schedule'`); no `write-all`
permissions; the single secret (`NVD_API_KEY`) is confined to the scheduled job that documents
needing it; the main `mvn verify` is a single command whose exit status propagates; the health-wait
loop ends in an explicit `exit 1`; `verify.yml` triggers on `push: main` **and** `pull_request`.

Not verified: that any of it runs. `dependency-scan.yml` additionally cannot complete without an
`NVD_API_KEY`, which is documented as a known limitation rather than concealed.

### Migration amendment — stated explicitly

V2 was **created and then amended** during remediation (R-01 added the outcome-constraint
replacement). Amending an **unreleased** migration is correct practice. The consequence is bounded:

- any database built from the **earlier draft** of V2 will fail Flyway checksum validation and needs
  `flyway repair` or a rebuild;
- every database built from the **final** V2 is clean — demonstrated on a fresh database here;
- no environment outside this working session ever applied the draft.

The migrations directory contains exactly the two intended files and nothing else, so no draft can
be applied by accident.

---

## F. Final recommendation

> **"Would you allow this repository to be treated as a finished portfolio engineering project
> whose correctness claims are defensible under adversarial review?"**

**Yes — with the boundary stated, and that boundary is not decorative.**

What actually earned it is not the 354 tests. It is that **every claim in this report was attacked
by mutating the thing it depends on, and the mutation was verified to have applied before the
result was believed.** Concretely: the SQL probe was shown to fail on V1-only; the inventory gate
was shown to refuse seven corrupted figures; `check_suites.py` was shown to refuse an injected
failure, a deleted summary and an empty suite; the workflow checker was shown to refuse a malformed
`run:` block; the convergence test was shown to fail when a value is removed from the constraint and
to pass again when the constraint is restored; and a gate whose formula was wrong was caught *by
auditing the gate rather than trusting it*.

And it earned it honestly: **this pass found two further defects.** G-04 is the one I would point at
in a review — a checked-in gate that printed `passed=353` while 352 passed, and that would have
printed `passed=353` again with a unit test failing, because the formula omitted the term that
matters. S-01 is the same shape one layer up: a parser that could not tell a clean scan from a
report it had not read. Both are fixed, both now have regression tests, and both were found by
auditing the previous pass's own remediation rather than by re-running its tests.

**Where the claim stops.** The honest limits, all of which I would state in any handoff:

- **CI has never executed here.** Statically verified only. This is the largest single gap and it is
  an environment limit, not a property of the repository.
- **Concurrency is sampled, not exhaustive** — 16-way and 24-way over HTTP, plus the in-process
  suite. `EventOrderingIT`'s exhaustive ordering legs I did not re-run and therefore mark UNKNOWN.
- **`scripts/probe.sh` was executed this pass** (33/33, twice), which closes the one gap the previous
  report left open. The spec/ADR counts in the README remain hand-maintained and UNKNOWN.
- **V2 was amended while unreleased**, with the deployment consequence stated above.

**Certification: `READY WITH NON-BLOCKING FINDINGS`.** All blocking-class findings are closed with
replayable evidence chains; M1–M8 are proven with no application in the path; M9–M15 are proven at
the interfaces tested; and the two defects this pass found were closed at root cause with the
repository restored and every gate re-verified green afterwards.

---

### Appendix — changes made during this pass

Five files edited. Nothing else; nothing committed; both historical reports byte-identical.

| File | Change |
| --- | --- |
| [scripts/inventory.py](scripts/inventory.py) | **G-04:** `passed` derived correctly (all four quantities); README checked against discovered / executed-and-passed / skipped; arithmetic self-check added |
| [scripts/dependency_check_summary.py](scripts/dependency_check_summary.py) | **S-01:** refuses a document that is readable JSON but not a Dependency-Check report (exit 2) |
| [scripts/test_dependency_check_summary.py](scripts/test_dependency_check_summary.py) | regression test for S-01 (now 12) |
| [src/test/java/com/reconcile/service/ReconciliationIT.java](src/test/java/com/reconcile/service/ReconciliationIT.java) | `everyOutcomeValueIsAcceptedByTheSchema` — generalises R-01's protection |
| [README.md](README.md) | §6: count semantics stated precisely and made machine-checked |

`V2__financial_invariants_and_terminal_events.sql` and `verify.yml` were mutated **only** by the
non-vacuity attacks and are byte-identical to their pre-attack state (V2 md5
`aba371d6bef1e423c916bc35ee91d468` confirmed both sides).

### Appendix — probes of this audit that were wrong, and how it was found

Recorded because a report that only lists successes is not an audit.

| Probe | Its error | How it was caught |
| --- | --- | --- |
| Raw-SQL M1–M8, first run | gave both entries `line_no = 1`; `ux_entry_txn_line` fired first, so M2/M5/M6/M7 were refused **for the wrong rule** and the fixture never persisted | captured the database's own message beside each assertion; noticed M5's fixture was absent |
| Raw-SQL M8, restore | zeroed a projection that legitimately had entries — genuine drift, correctly reported | the "residual drift" line was read rather than assumed |
| `check_suites.py` attack | ran while `mvn clean` had deleted `target/`, so it "passed" because reports were **missing** | that is a different test; re-run after the build |
| Inventory non-vacuity | hard-coded "201 integration"; the real count was 203, so the `sed` mutated nothing and the script blamed the gate | added a mutation-applied check; figures now read from the gate |
| Dependency-summary fixture | used `cvssv3.baseScore`; the real key is `cvssv3BaseScore` | re-read the parser before reporting a defect |
| Enum-convergence parser | matched `NAME(`, the shape of an enum *with* a constructor, and found 0 constants | it then "proved" 12 constraint values were unknown to the enum; a parser finding zero of its target is broken, not informative |

In every case the **product** was correct and the **probe** was wrong. That is the expected ratio,
and it is the reason the mutated-input check exists.
