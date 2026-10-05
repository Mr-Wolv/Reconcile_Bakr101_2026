# Independent V&V / QA / Engineering Audit

**Audit date:** 2026-10-05  
**Subject:** shared working tree at commit f969294c21affab114cbb3266b000c0640fe9bda, branch main  
**State:** dirty worktree; 25 status entries before this report. Findings apply to that working tree, including staged and unstaged changes, not a clean release commit.  
**Change policy:** no application, test, configuration, or documentation source was changed during the audit. This report is the only file added by the audit.

## Executive Verdict

### Validation — Did we build the right thing?

**Verdict: PARTIALLY.**

The declared v1 problem—a single-tenant payment and settlement reconciliation prototype with a double-entry ledger—is coherent and appropriately scoped as a portfolio project. The repository explicitly excludes real payment processing, cardholder data, PCI certification, KYC/AML, and production readiness. Those exclusions are clear.

The effective settlement contract is incomplete and internally inconsistent. Settlement record currency and aggregate line totals are not fully constrained, and there is no documented operator-visible outcome for every inconsistency. The payload type says record and line totals are both checked and that a mismatch must be surfaced; the implementation accepts a net/fee mismatch, and reconciliation omits record-level settlement totals. A valid signed settlement can also settle an EGP payment while posting USD ledger entries. These are failures in the definition and realization of the core reconciliation problem.

### Verification — Did we build the thing right?

**Verdict: NO.**

A clean Maven build and the full automated suite passed, and local image smoke testing passed. Adversarial runtime testing nevertheless falsified key claims: cross-currency settlements were accepted and reconciled as matched, a zero-entry posted ledger header bypassed the database balance trigger, chunked webhooks bypassed the promised size-rejection/audit behavior, and non-retryable events were retried to the eight-attempt limit. Other reproducible issues affect capture validation, replay suppression, and case-event audit history.

### Final certification

**NOT READY.** The four high findings below concern money denomination, settlement accuracy, ledger integrity, and unauthenticated request resource limits. They must be resolved and re-audited before delivery as a financial correctness demonstration. The passing suite does not override these observed counterexamples.

## Evidence labels

| Label | Meaning in this report |
| --- | --- |
| **CLAIMED** | Stated in project artifacts, not independently established by that statement. |
| **IMPLEMENTED** | Present in source, configuration, or schema. |
| **TESTED** | Covered by an executed automated test or script; the assertion itself may still be weak. |
| **OBSERVED** | Seen in the isolated running image or database during this audit. |
| **PROVEN** | A specific falsifying experiment or implementation path establishes the result within the stated scope. |
| **UNKNOWN / NOT VERIFIED** | Evidence was unavailable or the scenario was not executed. |

## Repository Coverage

### Structural map

The pre-report inventory contained **165 files** outside .git and the pre-existing ignored target directory.

| Area | Count | Purpose and audit result |
| --- | ---: | --- |
| Root files | 10 | [README](README.md), [release](RELEASE.md), [security](SECURITY.md), [changelog](CHANGELOG.md), [Maven](pom.xml), [Dockerfile](Dockerfile), [Compose](docker-compose.yml), [license](LICENSE), and Git attributes/ignore. Reviewed; Docker image and Compose stack used dynamically. |
| .freebuff | 1 | Desktop project marker. Reviewed; no application behavior. |
| .vscode | 1 | Editor settings. Reviewed; no runtime behavior. |
| .github | 8 | Four Actions workflows, Dependabot configuration, and Java-upgrade tool hooks. Workflows statically checked; hooks reviewed but not invoked. |
| audit-probe | 8 | Exploratory JavaScript and SQL probes. All reviewed; [async-batch.mjs](audit-probe/async-batch.mjs), [recon-crash.mjs](audit-probe/recon-crash.mjs), [vv-api.mjs](audit-probe/vv-api.mjs), [vv-new-endpoints.mjs](audit-probe/vv-new-endpoints.mjs), and [vv-post-remediation.sql](audit-probe/vv-post-remediation.sql) executed. Three remaining SQL probes were reviewed only. |
| docs | 20 | Requirements/specifications, ADRs, API contract, security and dependency notes, roadmap. Reviewed and cross-checked against schema, code, and runtime. |
| scripts | 6 | Build, workflow, dependency, suite, and smoke helpers. Five executed; [verify.sh](scripts/verify.sh) was reviewed but not run in the shared tree because it starts by deleting target. |
| src | 111 | 69 main Java files, 37 test Java files, one application YAML, one production migration, two test migration SQL files, and one golden TSV. All main Java sources compiled; all 36 test classes ran and produced reports; migration applied in clean and runtime databases. |

There is no frontend, cloud deployment manifest, Kubernetes configuration, or real PSP adapter. The runtime boundary is a Spring Boot application plus PostgreSQL in Docker Compose. The repository has no configured Git remote. The audit did not inspect Git object internals or generated build files as source. No files were tracked under target; the existing ignored directory was preserved.

Of the 165 inventoried files, 39 are documentation, metadata, static data, or configuration rather than standalone executable files; [Dockerfile](Dockerfile) and [Compose configuration](docker-compose.yml) were consumed by the build/runtime. Ten repository helper/probe files were directly invoked (five scripts and five audit probes). Java application sources were compiled; 36 test classes ran; production/test migration SQL was exercised through PostgreSQL. Three audit SQL probes and the tool-recording hooks were reviewed but not run.

### Execution coverage

- **Build:** isolated Docker Java 25 environment, mvn -B clean verify — passed.
- **Unit suite:** 125 tests, 0 failures, 0 errors.
- **Integration suite:** 188 tests, 0 failures, 0 errors, 1 skipped golden-file rewrite test.
- **Total:** 313 test executions, 312 passed and 1 skipped. Surefire produced 13 XML reports; Failsafe produced 23 test-class XML reports plus its summary.
- **Image/runtime:** Docker image built and started with an isolated Compose project on ports 18080 and 55434. Health and [probe.sh](scripts/probe.sh) passed. The user’s existing app/database listeners were left untouched.
- **Scripts:** [check_workflows.py](scripts/check_workflows.py), [pin_actions.py](scripts/pin_actions.py) with --check, [osv_check.py](scripts/osv_check.py), [check_suites.py](scripts/check_suites.py), and [probe.sh](scripts/probe.sh) ran. OSV examined 156 resolved dependencies: 14 advisory references represented seven unique baseline advisories and zero new advisories.
- **Exploratory runtime probes:** [vv-api.mjs](audit-probe/vv-api.mjs) reported 65 passes and 2 invalid expectations; [vv-new-endpoints.mjs](audit-probe/vv-new-endpoints.mjs) reported 31 passes and 6 invalid expectations. [async-batch.mjs](audit-probe/async-batch.mjs), [recon-crash.mjs](audit-probe/recon-crash.mjs), and [vv-post-remediation.sql](audit-probe/vv-post-remediation.sql) ran. Their results and limitations are separated below.
- **Raw database experiments:** executed only against isolated PostgreSQL. They included malformed settlement aggregates, a zero-entry ledger header, and ledger/account checks.
- **Runtime scenarios observed:** payment registration/create/authorize/capture/settle/payout; duplicate and concurrent idempotency; concurrent capture; signed webhook acceptance and replay; role matrix; reconciliation; asynchronous batch completion; wrong-currency capture expectation; changed webhook event-ID replay; non-retryable event processing; settlement aggregate mismatch; cross-currency settlement; terminal-case recurrence; chunked oversized webhook; direct SQL ledger invariant checks.

### Not verified

- GitHub Actions were not run by a hosted runner. The checkout has no remote configured; workflow status, permissions as enforced by GitHub, and uploaded artifacts remain **UNKNOWN**.
- The scheduled OWASP Dependency-Check/NVD job, Semgrep, Scorecard, and ZAP jobs were not run. The local OSV check is narrower evidence.
- [verify.sh](scripts/verify.sh) was not executed directly because it runs rm -rf target against the shared checkout. The clean Maven gate and its checks were run in an isolated copy instead.
- A full process/container kill-and-restart experiment was not run. The integration suite exercises simulated worker recovery and database failure cases; that is **TESTED**, not a host-level restart observation.
- The Java-upgrade hook scripts and three unexecuted SQL probes were not run.
- Production deployment, real-provider behavior, cloud availability, disaster recovery, external backup restore, and regulatory compliance were not demonstrated.

## Effective Specification and Traceability

The effective contract was assembled from the [README](README.md), nine numbered specification documents, five ADRs, [OpenAPI](docs/openapi/reconcile-v1.yaml), migration, service code, tests, workflows, and scripts. The primary rules are: represent amounts in minor-unit integers with an explicit supported currency; make each ledger posting balanced, single-currency, append-only, and unique by business source; enforce payment state transitions; persist verified webhooks before asynchronous effect processing; reconcile ledger-derived payment views against provider records; retain cases and audit history; and run as a local portfolio prototype.

The table summarizes every major requirement area and evidence chain. PASS means the named paths were exercised; it does not imply universal correctness where an adversarial row disproves the broader claim.

| ID | Requirement / rule | Specification → implementation | Test | Runtime evidence | Result |
| --- | --- | --- | --- | --- | --- |
| R-01 | Minor-unit money, supported currencies, currency-safe arithmetic | [01-money-and-ledger](docs/spec/01-money-and-ledger.md); [Money](src/main/java/com/reconcile/shared/Money.java), [CurrencyCode](src/main/java/com/reconcile/shared/CurrencyCode.java) | Money and fee-policy unit tests; full suite | EGP/USD API and ledger flows | **PASS in core Money path**; simulator floating literal is a scan gap (F-10). |
| R-02 | Ledger transactions balance, contain at least two entries, and use one currency | L1/L2 in [01](docs/spec/01-money-and-ledger.md), [migration trigger](src/main/resources/db/migration/V1__baseline.sql#L229), [LedgerWriter](src/main/java/com/reconcile/persistence/jdbc/LedgerWriter.java) | Ledger invariant/integration tests; SQL negative controls | Invalid entry postings rejected; empty transaction header committed and returned POSTED with zero entries | **FAIL** (F-03). |
| R-03 | Ledger is immutable and reversals are separate, unique transactions | L4–L6 in [01](docs/spec/01-money-and-ledger.md); migration immutability/reversal constraints | Raw-JDBC ledger tests and SQL probe | Mutation/reversal controls passed in isolated DB | **PASS for tested paths**. |
| R-04 | Capture expected amount equals authorized amount, including denomination | T3 in [02-payments](docs/spec/02-payments.md); [PaymentService.capture](src/main/java/com/reconcile/service/PaymentService.java#L187) | Payment lifecycle/API tests | Expected USD 200 against an EGP 200 authorization returned 200 and captured the EGP payment | **FAIL** (F-05). |
| R-05 | Payment state graph rejects illegal transitions and serializes concurrent capture | [02](docs/spec/02-payments.md), [PaymentStateMachine](src/main/java/com/reconcile/payment/PaymentStateMachine.java), transactional [PaymentService](src/main/java/com/reconcile/service/PaymentService.java) | Exhaustive state-pair test; lifecycle and concurrency integration tests | 16 concurrent capture calls produced one successful transition; duplicate/idempotency probes passed | **PASS for exercised paths**. |
| R-06 | Same idempotency key/request has one business effect under retry/concurrency | [03-idempotency-and-webhooks](docs/spec/03-idempotency-and-webhooks.md); [IdempotencyService](src/main/java/com/reconcile/service/IdempotencyService.java) | Unit and HTTP failure/concurrency tests | 24 concurrent same-key calls returned one payment | **PASS for same-key scenarios exercised**. |
| R-07 | Webhook verifies timestamp and raw-body HMAC before acceptance | [03 §B2](docs/spec/03-idempotency-and-webhooks.md); [WebhookSignature](src/main/java/com/reconcile/provider/WebhookSignature.java), [controller](src/main/java/com/reconcile/api/ProviderWebhookController.java) | Signature and webhook integration tests | Normal, duplicate, and stale-signature requests exercised | **PASS for body/timestamp authentication**. |
| R-08 | Webhook replay is deduplicated | [03 §B5](docs/spec/03-idempotency-and-webhooks.md), event ID persistence | Same-ID integration test | Re-sent a valid signed body and timestamp with a new unsigned event-ID header; it was accepted as a new event | **FAIL for replay suppression by event identity** (F-06). |
| R-09 | Non-retryable inbound events stop immediately and remain visible | I-WEB-10 in [08-test-matrix](docs/spec/08-test-matrix-and-dod.md); [InboundEventWorker](src/main/java/com/reconcile/service/InboundEventWorker.java#L169) | Integration suite includes webhook failures | Signed unknown event reached FAILED at attempts=8 instead of attempts=1 | **FAIL** (F-07). |
| R-10 | Settlement record and line totals are checked; discrepancies are surfaced | [ProviderEventPayload](src/main/java/com/reconcile/provider/ProviderEventPayload.java), [04-reconciliation](docs/spec/04-reconciliation.md), [processor](src/main/java/com/reconcile/service/ProviderEventProcessor.java#L485) | Normal settlement and duplicate tests; no aggregate-net adversarial test found | Record net/fee disagreed with line-derived net/fee; event was accepted and targeted reconciliation returned MATCHED with no differences | **FAIL** (F-02). |
| R-11 | Settlement currency agrees with every linked payment/provider transaction | Money/currency rules in [01](docs/spec/01-money-and-ledger.md) and settlement model in [04](docs/spec/04-reconciliation.md); [settlement application](src/main/java/com/reconcile/service/ProviderEventProcessor.java#L347) | No test for cross-currency settlement identified | Three valid signed USD settlement records settled EGP payments and posted USD ledger entries; a batch returned MATCHED for all three | **FAIL** (F-01). |
| R-12 | Settlement duplicates and push/pull overlap do not double-post | [03-idempotency-and-webhooks](docs/spec/03-idempotency-and-webhooks.md), settlement unique key and locked payment transition | Provider webhook and sync integration tests | [probe.sh](scripts/probe.sh) and duplicate webhook/pull cases passed | **PASS for duplicate business record paths**. |
| R-13 | Reconciliation derives expected money from ledger and compares provider facts | [04-reconciliation](docs/spec/04-reconciliation.md); [ReconciliationService](src/main/java/com/reconcile/service/ReconciliationService.java), classifier | Golden and reconciliation integration tests | Normal flow matched; wrong record totals and wrong settlement currency still matched because only provider-transaction fields were compared | **PARTIAL**; core result path exists but omits settlement-level truth (F-01/F-02). |
| R-14 | Case transitions and event history reflect current, terminal status | Case state machine in [04-reconciliation](docs/spec/04-reconciliation.md); [ReconciliationService](src/main/java/com/reconcile/service/ReconciliationService.java#L378) | Case lifecycle integration tests | Re-detection after WRITTEN_OFF preserved terminal status but appended OPEN→OPEN history | **FAIL in audit trail** (F-08). |
| R-15 | Role matrix separates operator/admin actions | [06-api-contract](docs/spec/06-api-contract.md); [SecurityConfig](src/main/java/com/reconcile/security/SecurityConfig.java) | Security API integration tests | [probe.sh](scripts/probe.sh) role matrix passed | **PASS for documented static-token local roles**. |
| R-16 | Input validation, API shapes, status codes, OpenAPI match | [OpenAPI](docs/openapi/reconcile-v1.yaml), controllers, exception advice | Contract integration tests | Smoke script passed; exploratory scripts exposed their own stale assertions; wrong expected currency was accepted | **PARTIAL**; see F-05 and F-12. |
| R-17 | Database migration and ledger projection checks operate on PostgreSQL | [V1 migration](src/main/resources/db/migration/V1__baseline.sql), [HealthService](src/main/java/com/reconcile/service/HealthService.java) | Flyway and operations integration tests | Clean Postgres 18.6 startup and image health passed; header-only transaction escaped L1 | **PARTIAL**; F-03 invalidates a broad DB-invariant claim. |
| R-18 | Provider timeout, event ordering, duplicate delivery, and worker recovery are handled | [07-failure-matrix](docs/spec/07-failure-matrix.md), [InboundEventWorker](src/main/java/com/reconcile/service/InboundEventWorker.java) | Integration suite, including simulated failures | Async batch completed; duplicate delivery paths passed; no actual container kill/restart run | **PARTIAL**; non-retryable behavior fails and process restart remains unverified. |
| R-19 | Local CI gates test both suites and security/build claims | [verify workflow](.github/workflows/verify.yml), [security workflow](.github/workflows/security.yml), [workflow checker](scripts/check_workflows.py) | Workflow parser, SHA check, suite checker, OSV scanner run locally | Static checks passed; no GitHub runner execution | **PARTIAL / hosted behavior UNKNOWN**; F-09 and F-11. |
| R-20 | Prototype scope and portfolio claims remain honest | [README](README.md), [RELEASE](RELEASE.md), [SECURITY](SECURITY.md) | Static claim review | No production, PCI, cloud-deployment claim found; source/test counts are stale | **Mostly truthful with documentation drift** (D-01). |

## Critical Findings

No BLOCKER or CRITICAL finding was assigned. Four HIGH findings prevent certification.

### F-01 — Settlement currency can differ from linked payment and ledger

- **Severity / category:** HIGH — DATA INTEGRITY, FINANCIAL CORRECTNESS, VERIFICATION
- **Location:** [ProviderEventProcessor.java](src/main/java/com/reconcile/service/ProviderEventProcessor.java#L347), [settlement schema](src/main/resources/db/migration/V1__baseline.sql#L445)
- **Problem:** No validation requires settlement record currency to equal the currency of each linked payment/provider transaction. The settlement posting is created in the record currency.
- **Expected:** Reject or classify a currency-inconsistent settlement as an explicit reconciliation discrepancy; never move value between currency buckets based on an inconsistent record.
- **Actual / evidence:** Against the isolated image, I captured three EGP 100.00 payments. I sent valid fresh HMAC-signed USD settlement records, each with one line referencing an EGP provider transaction and gross USD 100.00. The first attempt correctly failed because USD clearing was empty. After creating a legitimate USD capture to fund USD clearing, the retries processed: each EGP payment became SETTLED, each settlement record said USD, and each SETTLEMENT_RECEIVED transaction posted Dr PLATFORM_CASH / Cr PSP_CLEARING in USD. USD cash rose by 30,000 minor units and USD clearing fell by 30,000; the EGP clearing legs were not settled. A reconciliation batch marked all three targeted EGP payment/provider pairs MATCHED with delta 0 and no differences.
- **Impact:** A validly signed but inconsistent provider record can misstate denomination, consume another currency’s clearing balance, and cause reconciliation to certify the payment as matched.
- **Root cause:** Settlement currency is trusted for the posting; reconciliation reads payment capture currency and provider transaction currency, but does not compare either with settlement-record currency.
- **Recommended fix:** Make currency equality a required invariant at ingestion and in the database relationship where practical. Preserve the invalid provider record as a visible mismatch/case without posting cash or marking the payment settled. Add the cross-currency case to API, integration, and reconciliation tests.
- **Classification / confidence:** Validation and verification; **PROVEN** by signed runtime reproduction and completed reconciliation.

### F-02 — Settlement record net/fee totals can disagree with lines and still reconcile as matched

- **Severity / category:** HIGH — VALIDATION, DATA INTEGRITY, VERIFICATION
- **Location:** [ProviderEventProcessor.java](src/main/java/com/reconcile/service/ProviderEventProcessor.java#L488), [ReconciliationService.java](src/main/java/com/reconcile/service/ReconciliationService.java#L430), [ProviderEventPayload.java](src/main/java/com/reconcile/provider/ProviderEventPayload.java)
- **Problem:** The processor computes aggregate lineGross and lineNet but only compares lineGross with record gross. Record-level fee/net are constrained to gross=net+fee, but are not compared with aggregate line net/fee. Reconciliation reads payments, capture entries, and provider_transactions; it does not compare settlement_records aggregate values to their settlement lines.
- **Expected:** Every provider record/line inconsistency has a deterministic persisted outcome and operator-visible case/result. The payload contract says both record and per-line totals are checked and disagreements must be surfaced.
- **Actual / evidence:** A valid signed settlement had record gross 10,000, fee 400, net 9,600 and one line gross 10,000, net 9,700. The record satisfied its own split constraint, but the line implied fee 300. Ingestion accepted it and settled the payment. Reconciliation reported MATCHED with an empty differences array.
- **Impact:** A provider settlement statement can be wrong while the system certifies the individual transaction as reconciled. Settlement-level cash/fee differences are absent from the case population.
- **Root cause:** Aggregate line net is calculated but unused; no aggregate settlement comparison or outcome/case representation exists.
- **Recommended fix:** Validate gross, net, fee, line count, and line currency together. Model record-vs-line discrepancies as durable reconciliation results/cases rather than dropping the event into a retry/dead-letter queue or silently accepting it. Add adversarial aggregate tests.
- **Classification / confidence:** Validation and verification; **PROVEN** by signed runtime ingestion and reconciliation.

### F-03 — A zero-entry ledger transaction commits as POSTED and bypasses L1

- **Severity / category:** HIGH — DATA INTEGRITY, VERIFICATION
- **Location:** [V1__baseline.sql](src/main/resources/db/migration/V1__baseline.sql#L229)
- **Problem:** The deferred L1 constraint trigger fires only AFTER INSERT ON ledger_entries. Inserting a ledger_transactions header with no entries triggers no balance check.
- **Expected:** Every committed POSTED transaction must have at least two entries and balanced debits/credits. The ADR and schema claim the database enforces this against raw SQL and future writers.
- **Actual / evidence:** A direct transaction against the isolated PostgreSQL database inserted and committed a PAYMENT_CAPTURE header with no ledger_entries. The ledger API returned state POSTED and entries []. The account verification endpoint remained verified because its projection check is driven by account balances/entries and does not enumerate empty transaction headers.
- **Impact:** Financial history can contain a transaction that claims to be posted while representing no money movement. The asserted database defense does not cover all database writers.
- **Root cause:** Constraint trigger at [V1__baseline.sql](src/main/resources/db/migration/V1__baseline.sql#L261) is attached to entry insertion only; no header-side deferred trigger or posting-only database procedure enforces presence of entries.
- **Recommended fix:** Enforce nonempty/balanced ledger transaction at commit for both header and entries, using a deferred header/entry check or controlled posting routine and restricted table privileges. Add a direct SQL test for a zero-entry header.
- **Classification / confidence:** Verification; **PROVEN** by committed raw SQL and API readback.

### F-04 — Chunked oversized webhooks exceed the body limit and skip the required rejection record

- **Severity / category:** HIGH — SECURITY, RELIABILITY, OPERATIONS
- **Location:** [ProviderWebhookController.java](src/main/java/com/reconcile/api/ProviderWebhookController.java#L92), [RequestIdFilter.java](src/main/java/com/reconcile/api/RequestIdFilter.java#L89), [IdempotentCommand.java](src/main/java/com/reconcile/api/IdempotentCommand.java#L240)
- **Problem:** The 256 KiB check uses Content-Length. Chunked requests have unknown length. The raw-body helper calls readAllBytes before deciding the cache was truncated, which reads/allocates the complete body instead of stopping at the limit.
- **Expected:** F-21 specifies 413 TOO_LARGE with a durable rejection delivery record for an oversized webhook.
- **Actual / evidence:** A raw chunked HTTP/1.1 request with a 300,123-byte body and no Content-Length received 400 MALFORMED_REQUEST. A database query found no provider_event_deliveries row for that event. The source path reads the whole stream before detecting that the bounded cache filled.
- **Impact:** The documented request-size bound is bypassed for chunked transport; repeated large unauthenticated requests can consume memory and request-processing capacity, while rejection attempts lack the required audit evidence.
- **Root cause:** Header-only size enforcement and unbounded readAllBytes on an unknown-length request.
- **Recommended fix:** Enforce a byte-counting input limit while consuming the stream, stop at limit+1, return the specified 413, and persist the rejection outcome. Test Content-Length and chunked over-limit requests, including substantially larger bodies.
- **Classification / confidence:** Verification and security; **PROVEN** for response/audit mismatch; unbounded read is **PROVEN by source**, resource exhaustion beyond the tested body size is **LIKELY**, not induced to OOM.

## Medium and Low Findings

### F-05 — Capture’s expected amount comparison ignores currency

- **Severity / category:** MEDIUM — API, FINANCIAL CORRECTNESS, VERIFICATION
- **Location:** [PaymentService.java](src/main/java/com/reconcile/service/PaymentService.java#L187)
- **Problem:** The guard compares only amountMinor even though its API contract says expected Money must equal the authorized amount.
- **Expected:** Reject an expected amount when either the minor amount or currency differs from the authorized payment.
- **Actual / evidence:** A request capturing EGP 200 with expectedAmount USD 200 returned HTTP 200 and state CAPTURED. Internal posting remained EGP.
- **Impact:** Callers can receive acceptance for a capture expectation with the wrong denomination; the API guard does not enforce its declared contract.
- **Root cause:** PaymentService compares the scalar amountMinor and does not compare Money.currency.
- **Fix:** Compare the complete Money value and return CAPTURE_AMOUNT_MISMATCH on either amount or currency mismatch. Add currency mismatch API tests.
- **Classification / confidence:** Verification; **PROVEN**.

### F-06 — Event identity is not covered by the webhook signature

- **Severity / category:** MEDIUM — SECURITY, RELIABILITY
- **Location:** [WebhookSignature.java](src/main/java/com/reconcile/provider/WebhookSignature.java), [ProviderWebhookController.java](src/main/java/com/reconcile/api/ProviderWebhookController.java#L116)
- **Problem:** HMAC covers timestamp plus raw body; X-Provider-Event-Id is used for deduplication but is unsigned.
- **Expected:** An authenticated provider event identity or equivalent replay key prevents a captured valid request from being enqueued again under a different identifier.
- **Actual / evidence:** Replaying the same fresh body, timestamp, and valid signature with a changed event-ID header returned accepted=true and inserted a distinct event. Same-ID duplicate delivery returned duplicate as expected.
- **Impact:** A captured request can bypass event-ID dedupe within the five-minute freshness window and amplify inbox/worker/audit load. The tested business operation remained protected by payment/settlement idempotency; duplicate financial movement was not observed.
- **Root cause:** The signature helper authenticates timestamp and body only; deduplication trusts a separate unsigned header.
- **Fix:** Bind provider event identity into authenticated data or enforce a second replay key over authenticated content; retain business-level idempotency.
- **Classification / confidence:** Verification/security; **PROVEN** for inbox dedupe bypass; financial duplication is **NOT OBSERVED**.

### F-07 — Non-retryable events are retried through the retry budget

- **Severity / category:** MEDIUM — RELIABILITY, VERIFICATION
- **Location:** [InboundEventWorker.java](src/main/java/com/reconcile/service/InboundEventWorker.java#L169), [V1__baseline.sql](src/main/resources/db/migration/V1__baseline.sql#L414)
- **Problem:** fail() marks non-retryable errors FAILED with next_attempt_at=now, while claim selects both PENDING and FAILED. FAILED is therefore immediately claimable until attempts reaches eight.
- **Expected:** A NonRetryableEventException becomes terminal after its first processing attempt, as I-WEB-10 specifies.
- **Actual / evidence:** A signed unknown event that should fail on attempt 1 reached FAILED at attempts=8.
- **Impact:** Permanent poison events consume worker capacity repeatedly and contradict I-WEB-10 and the retry contract.
- **Root cause:** FAILED is used both for terminal failures and claimable retry work; claim has no terminal/retryable discriminator.
- **Fix:** Keep terminal failures out of the claim query, or distinguish permanent and retryable status transitions. Assert attempts=1 after a non-retryable failure and verify no later claim.
- **Classification / confidence:** Verification; **PROVEN** dynamically and statically.

### F-08 — Case recurrence writes false OPEN→OPEN history after terminal disposition

- **Severity / category:** MEDIUM — DATA INTEGRITY, AUDITABILITY
- **Location:** [ReconciliationService.java](src/main/java/com/reconcile/service/ReconciliationService.java#L378)
- **Problem:** Re-detection always writes from_status OPEN and to_status OPEN without reading the case’s current state.
- **Expected:** Case history reflects the actual transition or recurrence event and never records a state the case did not occupy.
- **Actual / evidence:** After a discrepancy case was WRITTEN_OFF, another batch re-detected it. The case remained WRITTEN_OFF and occurrence_count increased, but the latest case_events row said OPEN→OPEN.
- **Impact:** The immutable investigation timeline describes a state the case did not occupy, undermining audit review.
- **Root cause:** recordRecurrence calls appendCaseEvent with a hard-coded OPEN source state.
- **Fix:** Record the actual current status in recurrence events or model recurrence as an event that is not a state transition.
- **Classification / confidence:** Verification; **PROVEN** dynamically and by source.

### F-09 — Local verify script can exit successfully after skipping required gates

- **Severity / category:** LOW — CI/CD, OPERATIONS
- **Location:** [verify.sh](scripts/verify.sh#L51)
- **Problem:** If Python with PyYAML is missing, the script prints an error/skip message but continues. It also does not run [check_suites.py](scripts/check_suites.py) despite its usage text describing a suite check. The hosted [verify workflow](.github/workflows/verify.yml) does run a suite-count check.
- **Expected:** The local full-verification command exits nonzero when required checks are skipped and confirms both suites executed.
- **Actual / evidence:** The no-PyYAML branch only echoes diagnostics and continues to Maven; the script contains no check_suites.py invocation.
- **Impact:** A local caller that trusts only exit status can treat a skipped workflow check or zero-test Maven result as a clean full gate.
- **Root cause:** The skip branch is non-failing shell output, and the suite-count checker is only invoked by CI workflow steps.
- **Fix:** Exit nonzero when the workflow check cannot run, and invoke the suite report checker after Maven completes.
- **Classification / confidence:** Verification of script logic; **PROVEN statically**. The script itself was not invoked because it deletes shared target output.

### F-10 — Floating-point source scan misses decimal literals; a simulator fee uses one

- **Severity / category:** LOW — TESTING, PORTFOLIO CLAIM
- **Location:** [FloatingPointScanTest.java](src/test/java/com/reconcile/architecture/FloatingPointScanTest.java), [SimulatedProviderService.java](src/main/java/com/reconcile/simulator/SimulatedProviderService.java#L172), [test matrix](docs/spec/08-test-matrix-and-dod.md#L276)
- **Problem:** The detector scans floating-point type names, but the simulator computes its fee with grossAmountMinor * 0.03d. The “no floating-point representation anywhere in main sources” claim is false even though the regex test passes.
- **Expected:** The source scan detects any disallowed floating arithmetic in main sources or the project narrows its claim to production Money paths.
- **Actual / evidence:** The regex checks double/float type names after stripping comments and strings; the simulator’s decimal literal is not matched.
- **Impact:** Simulator fee behavior is not represented using the claimed integer/decimal-only rule. No monetary misrounding was proven in tested values; treat that risk as unproven.
- **Root cause:** The detector recognizes type names but not numeric suffixes/literals or floating arithmetic expressions.
- **Fix:** Use the same exact fee policy as the payment service and extend the scan to detect decimal/exponent literals and floating arithmetic.
- **Classification / confidence:** Verification/test quality; **PROVEN** that the claim and scan are incomplete; monetary loss **UNKNOWN**.

### F-11 — Dependency-Check post-scan summary reads the wrong JSON shape

- **Severity / category:** LOW — CI/CD, TESTING
- **Location:** [dependency-scan.yml](.github/workflows/dependency-scan.yml#L95)
- **Problem:** The custom report step reads report.vulnerableSoftware, while Dependency-Check report examples expose dependencies with nested vulnerabilities. The summary can print zero despite findings. The workflow also passes failBuildOnCVSS=7 to the Maven goal, which remains the intended build-failure gate; the parser flaw alone does not establish a false-green scanner gate. Dependency-Check documents failBuildOnCVSS as its build threshold, and its maintainer’s example reads dependencies[].vulnerabilities ([report mapping example](https://gist.github.com/jeremylong/ec5d496f64fc5bba6d47b2524a40ce3f), [Maven goal documentation](https://jeremylong.github.io/DependencyCheck/dependency-check-maven/check-mojo.html)).
- **Expected:** The report summary reads the generated report schema and reports high findings consistently with the Maven goal.
- **Actual / evidence:** The script reads report.vulnerableSoftware; the maintainer’s mapping example reads dependencies[].vulnerabilities. The workflow also passes failBuildOnCVSS=7 to the Maven goal, which remains the intended build-failure gate; the parser flaw alone does not establish a false-green scanner gate. Dependency-Check documents failBuildOnCVSS as its build threshold ([report mapping example](https://gist.github.com/jeremylong/ec5d496f64fc5bba6d47b2524a40ce3f), [Maven goal documentation](https://jeremylong.github.io/DependencyCheck/dependency-check-maven/check-mojo.html)).
- **Impact:** The human-readable artifact summary is unreliable; actual hosted behavior remains unverified.
- **Root cause:** The inline Python assumes a property path that does not match the report structure.
- **Fix:** Parse the generated report’s actual schema and test the parser with a fixture that contains a high-severity vulnerability.
- **Classification / confidence:** Static verification; **LIKELY** reporting defect; hosted build-gate behavior **UNKNOWN**.

### F-12 — Two exploratory API probes contain invalid or stale expectations

- **Severity / category:** LOW — TESTING, DOCUMENTATION
- **Location:** [vv-api.mjs](audit-probe/vv-api.mjs), [vv-new-endpoints.mjs](audit-probe/vv-new-endpoints.mjs)
- **Problem:** The first expects account entries as a bare array instead of the documented page object and expects plural audit entity type payments despite the contract specifying singular payment. The second seeds through a nonexistent ledger transaction POST route and requests limit=500 while later asserting that a limit above 200 is rejected.
- **Expected:** A probe’s setup and assertions agree with the OpenAPI/spec and do not contradict their own boundary checks.
- **Evidence:** These probes reported 8 failures, all on expectations contradicted by OpenAPI/spec or by their own constraints. The underlying product behavior tested by valid assertions remained separate from those false failures.
- **Impact:** Probe output is noisy and can mislead reviewers; these files are not part of CI and do not invalidate the Maven suite.
- **Root cause:** Probe assumptions were not kept synchronized with the API contract and pagination limit.
- **Fix:** Align probes with OpenAPI and the real API, then fail fast on invalid setup assumptions.
- **Classification / confidence:** Verification / testing; **PROVEN**.

### D-01 — Documentation inventory and workflow comment have drifted

- **Severity / category:** LOW — DOCUMENTATION
- **Location:** [README.md](README.md#L40), [test matrix](docs/spec/08-test-matrix-and-dod.md), [security workflow](.github/workflows/security.yml)
- **Problem:** [README](README.md) and test matrix report 68 main sources and 35 test sources; the current tree contains 69 and 37. The security workflow comment says actions are referenced by tag, but the four workflows contain six distinct SHA-pinned references, all of which passed [pin_actions.py](scripts/pin_actions.py) with --check.
- **Expected:** Inventory and workflow comments describe the current tree.
- **Actual / evidence:** Counted source files and inspected each workflow reference; the values/comments differ.
- **Impact:** Documentation is less reliable as inventory and handoff evidence. No runtime defect was demonstrated by this drift.
- **Root cause:** Counts and prose were maintained manually and not refreshed after repository changes.
- **Fix:** Regenerate counts or remove volatile totals; update the stale action-pinning comment.
- **Classification / confidence:** Verification / documentation consistency; **PROVEN**.

### O-01 — Java-upgrade recording hooks trust session ID as a path segment

- **Severity / category:** OBSERVATION — SECURITY, TOOLING
- **Location:** [recordToolUse.sh](.github/modernize/java-upgrade/hooks/scripts/recordToolUse.sh), [PowerShell hook](.github/modernize/java-upgrade/hooks/scripts/recordToolUse.ps1)
- **Problem:** Both scripts interpolate session_id into a filename without validating it as a safe basename. The scripts are not referenced by the four checked workflows or other searched project configuration and were not invoked.
- **Expected:** A hook only writes within its designated output directory even if input fields are malformed.
- **Actual / evidence:** The raw session ID is concatenated into the output path. No project configuration reference was found; hook execution was not tested.
- **Impact:** If an external caller supplies untrusted session IDs and invokes these hooks, path traversal could write JSONL outside the hooks directory using the caller’s privileges. Active reachability is **NOT VERIFIED**.
- **Root cause:** The scripts trust the tool payload’s session ID as a path component.
- **Fix:** Validate session IDs against a restricted character set and check the resolved path remains inside the hooks directory.
- **Classification / confidence:** Verification / security, static conditional review; **SUSPECTED**, not a demonstrated project execution path.

## Validation Findings

1. **Settlement validity lacks complete domain invariants.** The effective rules need to define how record currency, per-transaction currency, record gross/net/fee totals, line gross/net totals, and computed line fees relate. Current specs say discrepancies should be surfaced but do not define a durable result/case for every record-level mismatch.
2. **The settlement exception path is under-specified.** Gross mismatch becomes a non-retryable event failure; net/fee mismatch is accepted. Neither behavior consistently creates the reconciliation evidence the product says it exists to produce.
3. **The declared L1 database guarantee is incomplete.** A transaction header can exist in POSTED state without any entries. The rule must apply to transaction creation, not only to entry insertions.
4. **Replay identity is not a verified input.** The contract uses a header as a dedupe key but authenticates only timestamp and body. The spec should describe this boundary explicitly and require authenticated identity or content-level replay protection.
5. **No multi-tenant or merchant-user product is specified.** The project behaves as an operator-run single system. Tenant isolation is not a v1 requirement and must not be implied by portfolio claims.

## Verification Findings

The clean build and broad automated suite are meaningful evidence for compilation, standard API paths, PostgreSQL integration, tested concurrency, state transitions, duplicate settlement delivery, and ordinary ledger posting. They do not establish broader claims where adversarial counterexamples exist.

The most important implementation failures are F-01 through F-08. In addition, the database L1 trigger, webhook request-size filter, retry query, case event writer, and capture amount check each contradict expected behavior at the exact location described in their findings. OpenAPI generation/contract tests passed, but that does not prove every documented financial invariant.

## Security Findings

- **F-04:** chunked unauthenticated webhook bodies are read beyond the stated limit and do not get the specified durable refusal record.
- **F-06:** valid fresh webhook signatures can be replayed under new unsigned IDs, creating additional inbox work.
- **O-01:** optional Java-upgrade hooks have a conditional path traversal write risk if untrusted session IDs reach them; no execution wiring was found.
- **Authentication scope:** local role checks passed. The application uses two static bearer tokens and a shared webhook secret; defaults are present for the dev Compose profile. Project docs explicitly state this is not a production identity design. This audit found no committed private key or recognizable third-party API credential in the scanned source.
- **Tenant isolation:** not applicable to the declared single-tenant operator prototype; there is no merchant-user/tenant authorization model to certify.
- **Scanners:** local OSV baseline check passed with no new advisory. GitHub hosted security workflows, OWASP Dependency-Check, Semgrep, Scorecard, and ZAP remain **NOT VERIFIED**.

## Data Integrity Findings

The core Java Money and LedgerPostingCommand paths use explicit currency and enforce balanced postings; normal payment, settlement, payout, reversal, duplicate-post, and concurrency tests passed. Database constraints also reject mixed entry currency, incorrect account-code references, unbalanced multi-entry postings, and immutable-row mutation in exercised cases.

The exceptions are material:

- Settlement record currency can disagree with linked payment currency and drive entries into a different currency (F-01).
- Settlement aggregate net/fee mismatch can be accepted without a reconciliation case (F-02).
- A POSTED zero-entry header bypasses the database’s L1 trigger (F-03).
- A capture expectation with the wrong currency is accepted (F-05).
- The simulator contains floating arithmetic that its source scan misses (F-10); no amount error was demonstrated.

The post-remediation SQL probe is not a reliable automated gate: it leaves psql error-stop disabled, so expected constraint violations and unexpected SQL failures can coexist with process exit 0. Its printed output requires human interpretation. It did not include the zero-entry-header case that this audit found.

## Reliability / Failure Findings

- Duplicate same-ID webhooks and same-settlement business effects were covered by tests and runtime probes.
- Concurrent create/capture paths were exercised successfully in the local image and integration suite.
- Asynchronous reconciliation returned 202/RUNNING, then COMPLETED; the probe’s old “no scheduler” comment is stale.
- recon-crash.mjs’s expected batch failure was not reproduced: current reconciliation completed and returned a MISSING_INTERNAL outcome. That is a stale probe hypothesis, not a product failure.
- Database failure and retry behavior are represented in integration tests, but full host process/container restart was not independently run.
- Permanent event failures are retried until attempts=8 (F-07), despite the immediate-failure requirement.
- Oversized chunked webhook failure differs from its documented 413-and-record recovery behavior (F-04).
- A settlement can be blocked by the no-negative-asset constraint until other currency activity funds a same-currency account; once funded, the wrong-currency settlement described in F-01 processes. The constraint prevents a negative asset but does not enforce settlement denomination.

## Test Quality Assessment

**Trust: moderate for the paths the suite names; insufficient for broad financial certification.**

Strengths:
- Full clean Maven verify passed on Java 25 with PostgreSQL 18.6.
- 36 test classes produced reports across unit, integration, API, security, persistence, concurrency, reconciliation, and architecture packages.
- Tests use real PostgreSQL/Testcontainers for integration and include concurrent operations, duplicate processing, migration checks, and direct SQL invariants.
- The workflow suite-count checker ran and confirmed both suites executed.

Limits:
- No test covers settlement record currency against linked payment currency or aggregate record net/fee against line totals.
- The ledger constraint test misses a header with zero entries.
- The source floating-point scan checks type names, not decimal literals.
- Two exploratory API probes have invalid expectations and are not CI gates.
- The SQL invariant probe relies on manual interpretation and exits 0 on SQL errors.
- No full container restart or hosted CI execution was performed.
- A passing test is evidence only for its assertion; runtime experiments provide concrete counterexamples despite a green suite.

## Documentation Consistency

| Artifact | Status | Finding |
| --- | --- | --- |
| [README scope/disclaimers](README.md) | TRUE | Explicitly limits scope and disclaims production, PCI, and regulatory compliance. |
| [README source/test inventory](README.md#L40) | OUTDATED | Reports 68 main and 35 test sources; current tree has 69 and 37. |
| Spec 01 / ADR 0004 L1 database claim | PARTIALLY TRUE | Multi-entry balance checks work; a zero-entry header bypasses the entry-only trigger. |
| ProviderEventPayload settlement comment | FALSE in implementation | Says both record and line totals are checked and surfaced; only line gross is compared, and no reconciliation result covers line net/fee. |
| Failure matrix F-21 | FALSE for chunked transport | Requires 413 plus a delivery row; chunked body returned 400 without a delivery row. |
| Spec 08 floating-point claim | FALSE | Scan passes while SimulatedProviderService contains a double literal. |
| [verify.sh usage/comments](scripts/verify.sh) | PARTIALLY TRUE | It can skip workflow parsing and lacks the suite-count check described by its usage text. |
| Security workflow comments | OUTDATED | Claims action tags; current references are SHA-pinned. |
| OpenAPI and ordinary endpoint shapes | TESTED | Contract test and runtime smoke passed; exploratory probe failures were probe defects. |

## Claim / CV Readiness Audit

| Candidate claim | Classification | Evidence boundary |
| --- | --- | --- |
| “Built a Java 25 / Spring Boot / PostgreSQL payment and settlement reconciliation prototype.” | SAFE TO CLAIM | Local code, clean build, migration, image, and smoke run establish this bounded prototype claim. |
| “Implemented a double-entry ledger with immutable entries and database constraints.” | SAFE WITH QUALIFIER | Tests and SQL probes establish many constraints; disclose that a zero-entry header currently bypasses L1. |
| “The clean full suite passed: 313 executions, 1 skipped.” | SAFE TO CLAIM | Accurate for the audited dirty working tree and isolated run; do not present as hosted CI or a release tag. |
| “The local Docker image passed its smoke test.” | SAFE TO CLAIM | Evidence is the isolated local image and [probe.sh](scripts/probe.sh), not a cloud deployment. |
| “Supports idempotency and concurrent capture.” | SAFE WITH QUALIFIER | Same-key and concurrent capture cases passed; this does not mean every provider replay is deduplicated. |
| “Exactly-once, race-free, zero-data-loss, or fully reconciled financial processing.” | DO NOT CLAIM | Settlement, replay, and database counterexamples disprove the broad wording. |
| “Production-ready, PCI compliant, secure for real payments, highly available, or cloud deployed.” | DO NOT CLAIM | The project disclaims these claims and this audit did not establish them. |

## Architecture Assessment

- **Boundaries:** The modular monolith is a sensible boundary for the declared scope. Domain types, API, service, persistence, provider, and reconciliation packages are separated and architecture tests passed.
- **Complexity:** The project avoids unjustified microservices, Kafka, and Kubernetes. Durable inbox processing and row-locked ledger posting fit the actual failure and concurrency model.
- **Coupling:** The important hidden coupling is settlement currency/aggregate data versus payment ledger denomination. Reconciliation treats provider transaction rows as complete provider truth and does not validate the settlement record that contains them.
- **Responsibilities:** Payment transitions and ledger postings mostly sit in transactional services. Provider ingestion verifies and persists before asynchronous processing. Settlement validation is too weak before those service operations; reconciliation should receive a durable mismatch instead of a partial or normalized view.
- **Transactions:** Payment capture/state/audit and settlement state/ledger posting use the same PostgreSQL transaction path. External provider ingestion is durable inbox based, so delivery is at least once and effects rely on database idempotency. This is a reasonable architecture, but its settlement invariants are incomplete.
- **Operability:** Local Docker build, health check, metrics/health endpoints, and failure tests are present. Hosted workflows, production credentials, restore/backup, and operating procedures were not demonstrated.

## Final Certification

**NOT READY.**

The bounded portfolio prototype has a strong local build and integration-test baseline. The evidence is not sufficient to certify its financial correctness or stated invariant coverage: a cross-currency settlement was applied and marked MATCHED; a settlement aggregate mismatch was also marked MATCHED; a database-committed empty ledger transaction escaped L1; and chunked oversized webhooks bypassed the specified refusal/audit path. Resolve F-01 through F-04, add adversarial regression tests at each boundary, then rerun the full audit and hosted release gates.
