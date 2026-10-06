# CI execution — Reconcile v1

**Tree:** `main` at `d1e9af8`.
**First execution:** the runs of 2026-10-05/06, after four commits that exist only because a runner
finally ran the workflows.
**Purpose:** record what hosted execution found, what was changed because of it, and the soak
decision it forced.

Historical evidence is not rewritten. Where this report contradicts a statement in
[`RELEASE_FREEZE_REPORT.md`](RELEASE_FREEZE_REPORT.md) or
[`FINAL_RELEASE_GATE_REPORT.md`](FINAL_RELEASE_GATE_REPORT.md) — both of which say, correctly for
their date, that no workflow had ever run — the runs below are the correction.

---

## A. What ran, and what the runner measured

Nothing here is a local build. Every figure is a line from a job log on `ubuntu-latest`.

| Job | Result | Measurement |
| --- | --- | --- |
| `verify` — full gate | **PASS** | `unit: 150 run, 0 failures` · `integration: 204 run, 0 failures, 1 skipped` · `Both suites ran and passed.` · `inventory: every documented figure matches the tree` |
| `verify` — `probe` | **PASS** | image built with compose, health wait, `scripts/probe.sh` 33/33 |
| `verify` — `soak` | **PASS** | seed `soak-2026-10-06`, `EventOrderingIT` 4 tests, 0 failures, `BUILD SUCCESS` |
| `security` — OSV | **PASS** | `7 advisories are recorded in docs/security/osv-baseline.json; 0 are new.` plus the report parser's 12 fixtures |
| `security` — Semgrep | **PASS** | `scanned with 60 rule(s) loaded` · `0 ERROR-severity finding(s)` |
| `security` — dependency review | **PASS** on pull requests | preflight `dependency review is available (HTTP 200)`, then `dependency-review-action` at `fail-on-severity: high` |
| `security` — Scorecard | advisory | `continue-on-error` by decision; it reports on the repository, not the code |
| `security` — ZAP | advisory, evidence produced | scan exit 2 (a WARN alert), `zap recorded 1 alert(s) over 1 site(s)`, report uploaded as an artifact |

Runs: security [`37444180209`](https://github.com/Mr-Wolv/Reconcile_Bakr101_2026/actions/runs/37444180209),
verify [`37444180287`](https://github.com/Mr-Wolv/Reconcile_Bakr101_2026/actions/runs/37444180287),
and the dispatched run [`37444180889`](https://github.com/Mr-Wolv/Reconcile_Bakr101_2026/actions/runs/37444180889).
The five open Dependabot pull requests are green on both workflows.

---

## B. Five defects the first execution found

None of these was visible to the nine static checks that preceded the run, and that is the point: a
parse proves a file is well-formed, never that it works.

1. **The Semgrep ruleset was retired upstream.** The workflow ran `--config p/spring`. Semgrep
   answers 404, writes the problem into `toolExecutionNotifications`, loads **zero rules**, emits a
   SARIF with an empty `results` array and exits 7. Every run since the job was written had been
   red, and the gate it guarded had been reading an empty document.
2. **The severity gate could never fail.** It read `result["level"]`, which Semgrep never writes on
   a finding — severity lives on the rule's `defaultConfiguration.level`. A scan whose rule was
   declared `severity: ERROR` produced 51 findings and the gate exited **0**. This is the "check
   that cannot fail" the repository calls worse than no check, in its own gate. It now resolves each
   result's severity through its rule id, and was re-verified against four cases: shipped config → 0
   findings, the 404 SARIF → exit 1, 51 ERROR findings → exit 1, 51 WARNING findings → exit 0.
3. **The ZAP job failed while finding nothing, and uploaded nothing.** Three defects in one job: the
   scan could not write into a bind mount owned by the runner (`Permission denied:
   '/zap/wrk/zap.yaml'`), the workflow looked for the report in the workspace root while the image
   chdirs into the mounted directory and writes `zap-report/zap-report.json`, and the summary step
   parsed a JSON shape ZAP does not emit (`{"site": [...]}` is a list of sites, not a mapping). The
   exit-code policy was read off the script rather than guessed — 1 is a FAIL alert, 2 a WARN alert,
   3 nothing scanned — because the first policy written here was wrong and would have failed the job
   on this application's normal outcome.
4. **`WebhookWithoutDatabaseIT` failed deterministically**, at the redelivery assertion
   (`expected: 202 but was: 500`), and its single failure is what also made the inventory check
   disagree. Restoring `CONNECT` restores the privilege and not Hikari's pool: the connections
   terminated by the outage were still in the idle set, so the immediate redelivery was handed a
   dead one. The test now waits, bounded, for the application to reach the database before
   redelivering. **Every assertion is unchanged.** The outage assertions and the exactly-once
   assertions are byte-identical; what was removed was an unstated assumption that a pool recovers
   instantaneously, which nothing in the specification claims.
5. **Dependency review was red on every pull request**, because the repository's dependency graph was
   disabled — and no workflow can enable a repository feature. That job failed identically before any
   of these changes. It now probes the endpoint the action itself needs and either runs the real
   review or prints the reason and the one-line fix, so a red job there means findings rather than a
   settings page.

---

## C. The ordering soak: from unreachable to gating

It was `if: github.event_name == 'schedule'` **and** `continue-on-error: true`. The consequences
compounded: it was skipped on every push and pull request, so it had never once been observed
running; it could not be triggered on demand even though the workflow already declared
`workflow_dispatch`; and because it could not fail the run, a real finding would have shown as a red
job inside a green run — the shape of failure nobody reads, which is exactly how ZAP sat unnoticed.

**Changed:** it now runs on a manual dispatch as well as on its schedule, and it marks the run red.

**Why gating is safe here, in two parts.**

- *A failure replays exactly.* The seed is the UTC date, is printed before the run starts
  (`seed: soak-2026-10-06`), and `EventOrderingIT` hashes it. The leg does not sample orderings: it
  writes the ordering under test into `next_attempt_at` and releases its threads on a barrier, with
  no sleep and no polling. The argument that kept it advisory — "a search that breaks the build on
  its first finding is a search that gets switched off" — is an argument about findings nobody can
  reproduce, and this leg does not produce those.
- *It cannot block anyone's merge.* The job runs on the nightly schedule and on a manual dispatch,
  never on a push or a pull request, so gating it only decides whether the nightly run is red.

**Evidence that it is not flaky**, which is the thing to establish before allowing a job to gate:
four seeds, four passes, each `Tests run: 4, Failures: 0, Errors: 0` —

| Seed | Where |
| --- | --- |
| `soak-2026-10-06` | on the runner (run `37444180889`) and locally |
| `soak-2026-10-07`, `soak-2026-10-08`, `soak-2026-10-09` | locally, three fresh seeds, `ALL_SEEDS_PASS` |

**One honest note on the first local attempt.** The soak failed locally before it passed, with
`Connection refused` against a mapped Testcontainers port. That is not a defect in the job: it is the
Docker Desktop host-address problem this repository already documents, and
[`scripts/verify.sh`](scripts/verify.sh) sets `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal` for
exactly it. Linux runners resolve the host themselves, which is why CI's workflows correctly omit the
override. The leg was reproduced to a pass in the supported environment *before* its trigger was
changed, so what changed is the job's reachability and not its code.

---

## D. What still has not executed

Two workflows, both for reasons of trigger or key rather than of code, and both now stated that way
rather than as a general claim about the repository:

| Workflow | Why it has not run |
| --- | --- |
| `release.yml` | It fires on a `v*` tag, and no tag has been pushed |
| `dependency-scan.yml` | It needs `NVD_API_KEY`; a cold NVD sync is ~250k requests and the key is issued against a person |

Every statement in the README, the test matrix, the changelog, the handoff and the workflow headers
that Actions "has never executed" — and the one that said actions were referenced by tag rather than
by commit SHA, which was already false when written — is corrected. Two jobs remain advisory by
decision (`score`, `zap`), down from three.

---

## E. Certification

[`RELEASE_FREEZE_REPORT.md`](RELEASE_FREEZE_REPORT.md) withheld `READY` on one specific ground, not as
caution: that a material claim — every push is gated — rested on logic that had never executed. **That
ground is closed here, and the verdict is unchanged anyway.**

It is unchanged because the limitations that never depended on CI are still limitations: interleaving
is sampled rather than exhaustive, one scanner cannot run without a key the repository cannot create,
and one workflow has no tag to run on. Upgrading the verdict would mean deciding that a sampled
concurrency boundary is outside the artifact's boundary rather than a property of it, and that is a
judgement this pass did not audit. The freeze report set the standard for itself in the same words: it
would not upgrade a verdict on the grounds that it ran out of things to check, and neither will this.

**Certification remains `READY WITH NON-BLOCKING FINDINGS`, with a narrower basis.**

---

## F. Reproducing any of it

```bash
# The full gate, locally, in the same container CI uses.
./scripts/verify.sh                       # deletes target/ first; 7-8 minutes

# The soak leg alone, with the job's own seed derivation.
seed="soak-$(date -u +%Y-%m-%d)"
MSYS_NO_PATHCONV=1 docker run --rm --mount type=bind,src="$PWD",dst=/build -w /build \
  -v reconcile-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_RYUK_DISABLED=true -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  maven:3.9-eclipse-temurin-25 \
  mvn -B verify -Dit.test='EventOrderingIT' -Dtest='none' \
    -Dsurefire.failIfNoSpecifiedTests=false "-Dreconcile.ordering.seed=$seed"

# The same leg on a runner, on demand.
gh workflow run verify.yml --ref main
```

`TESTCONTAINERS_HOST_OVERRIDE` is required on Docker Desktop and unnecessary on the Linux runners;
`scripts/verify.sh` sets it, which is why the local gate is the one to run on Windows or macOS.
