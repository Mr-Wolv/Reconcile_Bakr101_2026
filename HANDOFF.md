# Reconcile v1 — project handoff

**Project:** Reconcile v1
**Status:** Release frozen
**Certification:** `READY WITH NON-BLOCKING FINDINGS`
**Blocking defects:** 0

A bounded payment and settlement **reconciliation prototype**. It demonstrates that the engineering
mechanisms behind money movement can be made correct and provable — not that it processes money.

---

## What it is

A double-entry ledger, a payment lifecycle, provider webhook ingestion with exactly-once semantics,
and a reconciliation engine that compares an independently derived expected view against the
provider's actual view and classifies every difference. Spring Boot 4 on Java 25, PostgreSQL 18, a
modular monolith with no message broker — because at this scale the boundaries matter and a network
failure mode would only add ways to lose money.

## What it demonstrates

| Mechanism | Where it is enforced |
| --- | --- |
| Double-entry invariants: ≥2 entries, debits = credits, no currency mixing, no negative assets | **The database**, not the application — via constraint triggers and foreign keys |
| Append-only ledger history; a business fact cannot post twice | **The database** — immutable triggers and unique constraints |
| Settlement records validated against invariants before they may move value | Application (`SettlementRecordValidator`, S1–S9), with the verdict stored durably |
| Exactly-once writes under concurrent identical requests | Idempotency records + unique constraints |
| Signed webhooks where the event id is inside the signed material | HMAC over `timestamp · eventId · rawBody` |
| Bounded request processing — an oversize undeclared body is refused **and recorded** | `BoundedRequestWrapper`; the `TOO_LARGE` delivery row is durable evidence |
| Terminal states: a permanently unusable event is `DEAD`, not retried forever | Explicit `DEAD` status, outside the claim query |
| Reconciliation classification with severity-ranked, actionable outcomes | `ReconciliationClassifier` |
| Idempotent, resumable background workers with full jitter | `InboundEventWorker`, `ReconciliationWorker` |

## Final verification evidence

Measured, not asserted. Every figure below is recomputed from the tree or the Maven reports by
`scripts/inventory.py`, which fails the build if the documentation disagrees with reality.

```
354 tests discovered · 353 executed and passed · 1 skipped · 0 failed
  150 unit (`mvn test`)
  204 integration against real PostgreSQL 18.6 (`mvn verify`)
```

- **CI executes the same gate and is green.** `verify` and `security` pass on hosted runners at
  `d1e9af8`, reproducing the counts above on a machine that is not a developer's; the nightly `soak`
  job exercises the randomised ordering leg on demand as well as on its schedule.
- **19 of 19 release gates exit 0** — build, both suites, four static gates, migration application,
  SQL invariant probe, and every live probe.
- **Financial invariants M1–M8 are proven with no application in the path**, each refusal attributed
  to a named database rule. The probe is proven non-vacuous: it **fails on the pre-fix schema**.
- **M9–M15 proven at the interfaces tested**: 24-way concurrent idempotent replay → one payment;
  16-way concurrent capture → one 200, fifteen 409s, exactly one ledger posting; database stopped
  mid-write → replay after recovery → exactly one payment; ledger still balances throughout.
- **Event ordering** enumerated exhaustively (`EventOrderingIT`, 4 legs): 24 orderings of the
  settlement lifecycle, 6 webhook-only, 6 refund-versus-settlement races.
- **Every gate has been shown to fail** when its input is broken — that is the property that makes a
  gate worth having.

## Known evidence boundaries

Stated plainly, because the absence of these is not a claim that they are satisfied.

1. **Hosted CI execution is verified, and it is green.** `verify` and `security` both pass on `main`
   at `d1e9af8`: unit 150/150, integration 204 with one deliberate skip, both suites asserted from
   the XML reports rather than from the log, the documented inventory reconciled against the tree,
   and the four security jobs green. The execution was worth more than the nine static checks that
   preceded it — it found a Semgrep ruleset retired upstream and a ZAP job that could not write its
   report, neither of which any offline check can see ([`CI_EXECUTION_REPORT.md`](CI_EXECUTION_REPORT.md)).
   What remains unexecuted is bounded and trigger-explainable: `release.yml` (needs a `v*` tag) and
   `dependency-scan.yml` (needs `NVD_API_KEY`).
2. **`dependency-scan.yml` cannot complete without `NVD_API_KEY`** — a cold NVD sync is ~250k
   requests. The OSV and GitHub Advisory scans are keyless and do gate every push.
3. **Concurrency evidence is strong but sampled**, not exhaustive: 24-way and 16-way over HTTP, plus
   the in-process suite. Event *ordering* is exhaustive; interleaving is not.
4. **One test is deliberately skipped** (`ReconciliationGoldenIT`) — it rewrites a golden fixture and
   is opt-in via `-Dreconcile.regenerate-golden=true`.
5. **V2 was amended while unreleased.** A database built from the earlier draft would need
   `flyway repair` or a rebuild. Databases built from the final V2 are clean.

## What this project does **not** claim

No production readiness. No PCI DSS compliance. No real PSP integration, real cardholder data, real
money movement, or regulatory compliance. No disaster-recovery, high-availability, zero-data-loss or
exhaustive-concurrency guarantee. The repository states these exclusions in
[`docs/spec/00-overview.md`](docs/spec/00-overview.md) and
[`RELEASE.md`](RELEASE.md), and this document does not soften them.

## Reproducing the verification

Prerequisites: Docker, Python 3 with PyYAML. The local JDK is deliberately never used — the project
targets Java 25 and the build runs only in Docker.

```bash
# Full local gate: workflow check, build (unit + integration), suite check,
# documented-inventory check, and the gate parsers' own tests.
./scripts/verify.sh

# Against a running image (build it first with: docker compose up -d --build app)
bash scripts/probe.sh                                  # 33 HTTP assertions
node audit-probe/vv-remediation.mjs                    # 19 assertions, findings F-01..F-07
docker exec -i <pg> psql -U reconcile -d reconcile -f - < audit-probe/vv-ledger-invariants.sql
```

Useful individually:

```bash
python scripts/check_suites.py             # both suites actually ran and passed
python scripts/inventory.py --check        # every documented figure matches the tree
python scripts/inventory.py --print        # what the tool currently derives
python scripts/check_workflows.py          # workflows parse; every run: block is valid bash
python scripts/test_dependency_check_summary.py
```

`inventory.py --check` is the one worth understanding: it is why the counts in this document can be
trusted. It derives them from the tree and the Maven reports, parses them back out of the
documentation, and fails on any disagreement — including an arithmetic check that the figures add up.

## Where to read next

| | |
| --- | --- |
| [`README.md`](README.md) | Status, scope, and the defect history — every defect found and how |
| [`CI_EXECUTION_REPORT.md`](CI_EXECUTION_REPORT.md) | The first hosted CI execution: what it found, and why the soak now gates |
| [`docs/spec/`](docs/spec/00-overview.md) | Nine specifications: the contract the code is written against |
| [`docs/adr/`](docs/adr/README.md) | Five architecture decision records, with why |
| [`RELEASE_FREEZE_REPORT.md](RELEASE_FREEZE_REPORT.md) | The freeze decision and the final gate table |
| [`FINAL_RELEASE_GATE_REPORT.md`](FINAL_RELEASE_GATE_REPORT.md) | Full finding matrix and evidence matrix |
| [`AUDIT_REPORT.md`](AUDIT_REPORT.md) | The original adversarial audit (historical — not rewritten) |
| [`REVALIDATION_REPORT.md](REVALIDATION_REPORT.md) | The remediation re-validation (historical) |
| [`audit-probe/`](audit-probe/README.md) | Live probes, and why seven earlier ones were archived |

---

**The project is finished.** It should be treated as complete unless actual engineering use exposes
a genuine defect.