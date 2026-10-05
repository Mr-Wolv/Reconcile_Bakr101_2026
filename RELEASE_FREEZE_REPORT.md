# Release freeze — Reconcile v1

**HEAD:** `f969294c21affab114cbb3266b000c0640fe9bda` (unchanged; nothing committed)
**Inherits:** [`FINAL_RELEASE_GATE_REPORT.md`](FINAL_RELEASE_GATE_REPORT.md) — `READY WITH NON-BLOCKING FINDINGS`
**Purpose:** close what can be closed locally, preserve what cannot, and stop.

Historical evidence — [`AUDIT_REPORT.md`](AUDIT_REPORT.md),
[`REVALIDATION_REPORT.md`](REVALIDATION_REPORT.md), [`FINAL_RELEASE_GATE_REPORT.md`](FINAL_RELEASE_GATE_REPORT.md) —
was **not modified**; all three are md5-verified unchanged. Corrections are recorded here.

---

## A. Final certification

# READY WITH NON-BLOCKING FINDINGS

`READY` was considered and rejected on one specific ground, not as caution: **hosted CI execution is
outside the repository's verifiable boundary from this environment.** §7 requires `READY` only if
every remaining limitation is eliminated *or demonstrably outside that boundary*. It is outside, but
"outside" is a judgement about the environment rather than a property of the artifact, and one
material claim — that every push is gated — rests on logic that has never actually executed. I will
not upgrade a verdict on the grounds that I ran out of things to check.

Equally, I did not downgrade it because that one check is unreachable. Everything else is green
with evidence.

---

## B. Release-gate table

| Gate | Result | Evidence | Remaining limitation |
| --- | --- | --- | --- |
| Java 25 compilation + full build | **PASS** (exit 0) | `mvn -B clean verify`, `BUILD SUCCESS` | none |
| Unit tests | **PASS** | 150 run, 0 failures, 0 errors, 0 skipped | none |
| Integration tests | **PASS** | 204 run, 0 failures, 0 errors | none |
| Skipped-test accounting | **PASS** — 1 skipped | `ReconciliationGoldenIT::regenerateTheGoldenFile`, opt-in via `-Dreconcile.regenerate-golden=true` | none; the count is machine-checked |
| Event ordering | **PASS — 4/4, 90.4 s** | `EventOrderingIT` seed `freeze-2026-10-06`: 24 orderings of the settlement lifecycle, 6 webhook-only, 6 refund-vs-settlement, 64 events across 16 threads | the randomised soak leg remains schedule-driven |
| Migrations on a fresh database | **PASS** (exit 0 each) | V1 then V2 with `ON_ERROR_STOP=1`; **10 user triggers present**, matching the declared count | V2 was amended while unreleased — a deployment note, not a defect |
| Inventory / count checks | **PASS** (exit 0) | 13 assertions: source counts, 4 test quantities, arithmetic, spec/ADR counts, 4 migration figures | none; every figure now derived from the tree or the reports |
| Dependency-summary parser tests | **PASS** (exit 0) | 12 tests | scanner itself needs NVD/OSV network access |
| Workflow static checks | **PASS** (exit 0) | 4 workflows parse; every `run:` block valid bash | static only |
| SQL invariant probe | **PASS, proven non-vacuous** | V1-only → exit 3; V1+V2 → exit 0 | none |
| Independent raw SQL M1–M8 | **PASS** — 12 assertions | each refusal attributed to a named database rule | none |
| `scripts/probe.sh` | **PASS — 33/33** | against the built image, isolated ports | none |
| `audit-probe/vv-remediation.mjs` | **PASS — 19/19** | against the built image | none |
| Persisted-state audit | **PASS** — 15 assertions | database inspected after all attacks | none |
| Concurrency + outage | **PASS** — 6 assertions | 24-way idempotent replay, 16-way capture, DB outage + replay | sampled, not exhaustive (see C) |
| R-02 runtime attack | **PASS** | provider-controlled control characters; batch completed, JSONB valid | none |
| Enum ↔ constraint convergence | **PASS** | 12 outcomes ↔ 12 allowed values ↔ 12 severity mappings | none |
| Static CI checks | **PASS — 9/9** | triggers, SHA pinning, failure propagation, advisory jobs, secret scoping, permissions, gate wiring | static only |
| **CI execution** | **NOT EXECUTED — ENVIRONMENT LIMITATION** | no GitHub-hosted runner reachable | see C |

**19 of 19 executable gates returned exit 0**, each status captured at the point of execution.

---

## C. Remaining non-blocking limitations

Only genuine ones. No hypothetical risks dressed as findings.

1. **Hosted CI execution is unverified.** CI *logic* is statically verified — 9/9 checks covering
   triggers on `push`/`pull_request`, all actions SHA-pinned, no `|| true`, no `set +e`, the build's
   exit status propagating, the health-wait ending in `exit 1`, all four required gates invoked by
   `verify.yml`, the single secret (`NVD_API_KEY`) confined to the one scheduled workflow that
   documents needing it, and read-only permissions throughout. The three `continue-on-error: true`
   jobs are `score`, `zap`, and `soak` (the last additionally gated on `event_name == 'schedule'`) —
   all advisory by design and all documented as such. **No workflow has ever been executed by a
   runner.** Nothing in this report should be read as "CI is green."

2. **`dependency-scan.yml` cannot complete without `NVD_API_KEY`.** A cold NVD sync is ~250k
   requests; on the anonymous endpoint that is roughly seventeen days. Documented in the workflow
   itself and in `docs/roadmap.md`, not concealed. Two cheaper independent scanners (`osv`,
   `deps`) do gate on every push.

3. **Concurrency evidence is sampled, not exhaustive.** 24-way idempotent replay and 16-way capture
   over HTTP, plus the in-process `ConcurrencyIT`. Event *ordering* is enumerated exhaustively by
   `EventOrderingIT`, which I re-ran and which passed — but the randomised soak leg remains
   schedule-driven and was not exercised beyond the single seed above.

4. **The one skipped test is deliberate and opt-in.** It rewrites a golden fixture. A golden test
   that can update its own expectation has stopped testing, so regenerating is a person's decision.

5. **V2 was amended during remediation.** Correct practice for an unreleased migration. A database
   built from the earlier *draft* of V2 would fail Flyway checksum validation and need
   `flyway repair` or a rebuild. No such database exists outside this working session, and the
   migrations directory contains exactly the two intended files.

6. **`EventOrderingIT`'s randomised leg uses one seed per run.** The nightly soak rotates it. A
   search that fails the build on its first finding is a search that gets switched off.

---

## D. Changes made in this pass

Two files. Nothing else was edited; no architecture touched; no feature added.

| File | Reason |
| --- | --- |
| [scripts/inventory.py](scripts/inventory.py) | **Corrected a wrong figure.** Its declaration regex was `^CREATE (TABLE\|INDEX\|TRIGGER\|FUNCTION)`, which matches neither `CREATE CONSTRAINT TRIGGER` (V1 uses it for `tg_txn_balances`; V2 for `tg_txn_header_balances`) nor `CREATE UNIQUE INDEX`. It therefore under-counted triggers, and the README — which quotes this tool — inherited the error and stated **8** where a fresh database holds **10**. Verified against `pg_trigger` rather than trusted: 10 = V1's 9 + V2's 1. |
| [scripts/inventory.py](scripts/inventory.py) | **Closed two drift risks.** Specification count (9) and ADR count (5) were hand-maintained and correct; they are now derived from the tree and checked. The four migration figures are now checked too — the last unchecked numbers in the README. |
| [README.md](README.md) | Corrected `8 triggers` → `10`, and `33 indexes` → `36`. Both were wrong before this pass. |

**New finding this pass: the README's trigger and index counts were wrong, and the tool that
produced them was the reason.** A hand-maintained number is a liability; a *machine-generated*
number that is also unchecked is worse, because it looks verified. The spec's own figure for the
baseline file (9 triggers) was correct throughout — it is scoped to V1, and the defect was in the
combined total.

All six new checks proven non-vacuous: corrupting the spec count, the ADR count, or any one of the
four migration figures is refused with the correct diagnosis, and the file is restored and
re-verified green.

---

## E. Final handoff statement

**The repository is suitable to be treated as the finished v1 portfolio project.**

> This is a validated and verified engineering prototype within the tested and documented scope;
> hosted CI and exhaustive concurrency remain external/limited evidence boundaries.

That sentence is the claim, and it is exactly as strong as the evidence supports. What backs it:

- **Financial invariants M1–M8 are PROVEN with no application in the path**, each refusal
  attributed to a named database rule, and the probe shown to *fail* on the pre-fix schema — which
  is what distinguishes a probe from a rubber stamp.
- **M9–M15 are PROVEN at the interfaces tested**, including a database stopped mid-write and
  replayed afterwards.
- **Every gate is executable and every gate has been shown to fail when its input is broken.**
  Mutation-applied checks were used throughout, because a probe that silently mutates nothing is
  worse than no probe.
- **Documentation is consistent with the repository**, mechanically — twelve figures derived from
  the tree and the Maven reports, with arithmetic asserted, and every one proven able to fail.
- **No unsupported production or compliance claim exists.** The sweep found five occurrences of
  sensitive terms in current documentation; all five are prohibitions — a "what is *not* on this
  list" section, a "Not implemented in v1, by decision" table, and `README.md:19-20` stating the
  project makes no such claim and that no such claim may appear anywhere in the repo.
- **Remaining uncertainty is bounded and stated**, not argued away.

### A note on this pass, because it is the honest part

My first two attempts at the consolidated release-gate run reported **6 and then 10 failures**.
Every one was a bug in my harness, not in the product: the probe scripts locate the application's
database by the container name `vvprobe-postgres-1` while I had created the compose project as
`frz`; my helper container was started without `POSTGRES_PASSWORD` and exited immediately; and a
stray stack from an earlier attempt still held ports 18082/56002. I have recorded this because it
is the third time in this audit that the failure belonged to the probe — and the response each
time is the same: diagnose it, fix the probe, re-run, and report the corrected result. The
consolidated run reported **0 failures across 19 gates** only after that, and it aborts at setup
now if the helper database is not ready or the ports are not free, so it fails loudly at the cause
instead of six lines later as a mystery.

### Stopping condition

1. No known blocking correctness, integrity, security or reliability defect remains. ✔
2. Every claimed gate has executable evidence, or an explicitly stated environmental limitation
   (hosted CI — the only one). ✔
3. Financial invariants have adversarial evidence. ✔
4. Documentation is consistent with actual repository state, mechanically checked. ✔
5. No unsupported production or compliance claims remain. ✔
6. Remaining uncertainty is clearly bounded. ✔

**Release freeze declared for Reconcile v1.** Do not reopen this project unless a real defect is
discovered through actual engineering use.

---

### CI boundary, stated one final time and without softening

- **CI logic is statically verified.** 9/9 checks, listed in §C.1.
- **CI execution is not verified**, and no claim is made that it is.

These are different things. A green local build, 19 green gates and four parsed workflows are
evidence that the gating *logic* is correct and *wired* — not evidence that a runner has ever run
it. The first hosted run may still surface an environment-specific failure, and that is the one
honest caveat a reader of this document should carry forward.