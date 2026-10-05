# Reconcile v1 — Specification Overview

**Project:** Reconcile — Payment & Settlement Reconciliation Engine
**Status:** v1 specification, pre-implementation. These documents are the contract the
implementation is written against; if code and spec disagree, one of them is a bug.
**Last revised:** 2026-10-04

---

## 1. What this system is

Reconcile is a **financial backend whose correctness properties are the product**. It models a
simplified payment platform that processes payments internally and periodically receives
settlement records from an external payment service provider (PSP).

Its central question is not "can we create a payment?" but:

> Does what our system says happened agree with what the external provider says actually happened?

The system is engineered around five properties, in priority order:

1. **Correctness** — double-entry invariants that cannot be violated, money as integers.
2. **Auditability** — every state change is reconstructable from the database alone.
3. **Idempotency** — retries, duplicates, and replays never double-post.
4. **Reconciliation** — external disagreement is detected, classified, and turned into an
   operational case with a resolution trail.
5. **Failure handling** — crashes, timeouts, duplicates, delays, and partial external data are
   designed-for paths, not exceptions.

## 2. Explicit non-goals

This is a **systems-engineering simulation of a fintech backend**. It is not a payments company.
The README repeats this; it is load-bearing for honesty and must not be softened later.

Not implemented in v1, by decision:

| Excluded | Why |
| --- | --- |
| Real card / bank / PSP integration | No external credentials, and a mock would be indistinguishable from a real one in a portfolio. |
| Cardholder data | Nothing resembling a PAN, CVV, or track data is stored or logged, ever. |
| Merchant onboarding, KYC and balance recovery | A payout is a ledger posting over already-`SETTLED` payments, not a bank transfer. Recovering money from a merchant after a post-payout refund is **not** modelled, and the system refuses that refund rather than pretending to handle it. |
| FX / multi-currency settlement | A ledger transaction is single-currency. No rate engine, no cross-currency posting. |
| KYC, AML, PCI certification, licensing | Explicitly out of scope. **No compliance claims anywhere in the repo.** |
| Fraud ML, credit scoring, loan underwriting, crypto | Out of scope. |
| Partial capture, partial refund, disputes, chargebacks | Candidate v1.5 / v2 (see §10). |
| Microservices, Kafka, Kubernetes | EventFlow already proves that ground. Reconcile proves financial correctness — see [ADR-0001](../adr/0001-modular-monolith.md). |

## 3. Architecture

One Spring Boot application, modular internally, with two deployment roles sharing the codebase.

```
                    ┌───────────────────────────────────────────────┐
   client ────────▶ │  REST API (payments, ledger, reconciliation,    │
                    │            provider simulator, ops)             │
                    └───────────────┬───────────────────────────────┘
                                    │
        ┌───────────────────────────▼────────────────────────────┐
        │  application services                                     │
        │  PaymentService · LedgerService · ProviderIngestionService │
        │  ReconciliationService · CaseService                     │
        └───────────────────────────┬────────────────────────────┘
                                    │  one DB transaction per command
        ┌───────────────────────────▼────────────────────────────┐
        │  domain (pure Java, no Spring, no JPA)                  │
        │  Money · PaymentStateMachine · LedgerTransaction         │
        │  ReconciliationClassifier · WebhookSignature             │
        └───────────────────────────┬────────────────────────────┘
                                    │
                    ┌───────────────▼───────────────┐
                    │  PostgreSQL 18                │
                    └───────────────────────────────┘

   background workers (same JVM, separate @Scheduled executors)
        · InboundEventWorker    — durable provider inbox
        · ReconciliationWorker — resumable batch processing
        · LedgerVerifier       — recomputes balances from entries
```

Design rules that follow from "correctness is the product":

- **The domain layer has no Spring and no JPA.** It is plain Java, unit-testable in milliseconds,
  and it owns every invariant. Persistence cannot bypass it. The surrounding application layer
  (`service/`, `api/`, `config/`, `security/`, `persistence/`) is a Spring application on
  purpose — the idempotent reservation, the webhook signature verification, the durable-inbox
  worker, the reconciliation batch scheduler and the JDBC choke point all live there, because they
  are integration and orchestration concerns and not pure invariants.
- **The ledger is the source of truth for money.** `payments` is operational state. Reconciliation
  derives its *expected* provider view from the ledger, never from the payment row, so a bug in
  the payment row cannot hide a bug in the money.
- **Background work only ever reads state that is already durable.**

## 4. Module layout

```
reconcile/
├── shared/          Money, CurrencyCode, Ulid, DomainException, ProblemCode, RequestContext
├── ledger/          accounts, transactions, entries, balances, posting, reversal, verifier
├── payment/         payment, state machine, commands, state history
├── provider/        inbound webhook ingestion, durable inbox, PSP simulator (test scope),
│                     and the provider-side payload/transaction/settlement shapes
├── reconciliation/  batches, matching, classification, results, cases
├── api/             REST controllers, DTOs, problem+json mapping, security config
├── service/         application services: payments, payouts, idempotency, provider
│                     ingestion, reconciliation, ops — the layer that composes the domain
│                     and persistence into commands
├── config/          wiring: clock, OpenAPI, properties
├── persistence/     JPA entities, the one wired repository, and the JDBC choke point (`Sql`,
│                     `LedgerWriter`)
├── security/        bearer-token authentication filter and RFC 9457 problem writer
├── simulator/       the simulated PSP used by tests and by the probe
└── audit/           append-only audit events (written by the service layer, not a separate
                     module boundary)
```

Dependency rule: modules may be depended upon in the direction
`api → {application services} → domain`, and outward-in only. `ledger` must not depend on
`payment`; the payment service asks the ledger to post a named transaction type. This is enforced
by an ArchUnit test (`shared` may depend on nothing).

## 5. Technology stack

| Component | Version | Notes |
| --- | --- | --- |
| Java | 25 (LTS) | Build runs in Docker; local JDK is 21, see §9. |
| Spring Boot | 4.1.1 | Released 2026-08-20. |
| Spring Security | via Boot BOM | Bearer-token auth; see [06-api-contract](06-api-contract.md). |
| Spring Data JPA / Hibernate | via Boot BOM | JPA for reads; native SQL for locking and aggregate projections. |
| PostgreSQL | 18.6 | Released 2026-08-13. Features used: `JSONB`, partial unique indexes, `FOR UPDATE SKIP LOCKED`, `generated always as identity`. |
| Flyway | Boot-managed | **Requires the separate `org.flywaydb:flyway-database-postgresql` artifact** — PostgreSQL support is not in core since Flyway 10. |
| Docker / Compose | — | App image is the reproducible build artifact. |
| Testcontainers | Boot-managed | PostgreSQL 18.6, per test class where isolation matters, shared container per module. |
| JUnit 5 / Mockito / AssertJ | Boot-managed | |
| ArchUnit | Boot-managed | Architecture rules as tests. |
| GitHub Actions | — | Full verification path, see [08](08-test-matrix-and-dod.md). |

No Kafka in v1 — deliberately. EventFlow owns that technology; adding it here would dilute the
story this project is meant to tell.

## 6. Identifier and naming conventions

- All primary keys are **ULID**s rendered in Crockford base32, prefixed by entity type, so that
  keys sort by creation time (good index locality) and are self-describing in logs:
  `pay_01K4…`, `ltx_…`, `len_…`, `acct_…`, `pev_…`, `psp_txn_…`, `srec_…`, `rbat_…`,
  `rcs_…`, `rres_…`, `aud_…`, `idem_…`.
- Externally supplied references (`Idempotency-Key`, provider transaction ids) are stored
  verbatim, never reinterpreted.
- Human-facing codes (`RC-104`) are **not** primary keys. Where the README shows a short case
  code, that is cosmetic display formatting of the ULID.
- Timestamps are `TIMESTAMPTZ` in UTC. The application never stores local time. Provider-supplied
  timestamps are stored both in their original offset column and normalised to UTC.

## 7. Transaction boundaries

These five boundaries are the ones the implementation must honour.

| Command | Transaction contents |
| --- | --- |
| Create payment | reserve idempotency key → insert payment → insert initial state-history row → audit |
| Authorize / fail | lock payment → validate transition → update payment + append state history → audit |
| Capture / refund | lock payment → validate transition → **lock accounts (deterministic order) → post ledger transaction** → update balances → insert payment↔ledger link → update payment + state history → audit |
| Execute payout | lock the payments named in the payout → require every one `SETTLED` and not already in a payout → lock accounts → post one `MERCHANT_PAYOUT` for the record total → insert payout lines and links → audit |
| Ingest webhook delivery | insert `provider_event_deliveries` row → insert canonical `provider_events` row if new → audit → **commit and return 202**. No business effect. |
| Process inbound event | lock event → apply business effect (payment/ledger transition, if any) → mark event `PROCESSED` / `NO_EFFECT` → audit |
| Ingest settlement record | insert settlement record + lines → lock payment rows → transition covered payments to `SETTLED` → lock accounts → post `SETTLEMENT_RECEIVED` → audit |
| Reconciliation batch | **transaction 1:** create batch, snapshot subject keys. **transaction 2 (per subject):** claim subject, classify, insert result, upsert case, audit. |

Note the split in reconciliation: the batch header commits on its own so a crash leaves a
resumable batch, and each subject commits independently so one bad subject cannot roll back 10,000
good ones.

## 8. Concurrency strategy

**Decision: pessimistic row locking (`SELECT … FOR UPDATE`) for ledger posting.**

- Accounts are locked in a **deterministic order** (ascending `account_code`) inside the posting
  transaction, which makes deadlock between two multi-account postings structurally impossible
  rather than merely unlikely.
- Isolation level stays the PostgreSQL default `READ COMMITTED`. It is sufficient *because* we
  take explicit row locks; we do not depend on snapshot serialisability, and we avoid the
  serialization-failure retry storms that `SERIALIZABLE` would create under posting contention.
- Optimistic version columns were rejected: two transactions posting to overlapping accounts
  would force caller-side retry loops and could write retry policy into every posting path.
  Rationale in [ADR-0002](../adr/0002-ledger-posting-locking.md).
- Payment rows are locked (`FOR UPDATE`) for every state transition, so two concurrent `capture`
  calls cannot both observe `AUTHORIZED`.
- Background workers claim rows with `FOR UPDATE SKIP LOCKED`, so a second worker instance never
  double-processes and never blocks behind the first.
- The unique constraint `(source_type, source_id, transaction_type)` on `ledger_transactions` is
  the **last line of defence** against double-posting. Even if every layer above it is bypassed by
  a bug, the database refuses the second posting.

## 9. Build and toolchain constraint (resolved)

The local JDK on this machine is **Temurin 21.0.11**; the project targets **Java 25**.

- **Decision D11 (confirmed): the build runs only in Docker.** `maven:3.9-eclipse-temurin-25` is
  the builder stage, in both `Dockerfile` and CI. No local JDK is expected to compile this project.
- `maven.compiler.release=25`; CI must run in a container or a Java 25 runner, not on the 21 JDK.
- Local `mvn` on JDK 21 will fail with `release version 25 not supported`. That is expected and
  correct: the local JDK is not the build environment.
- The Maven Wrapper is committed, so `docker compose run --rm build` is fully reproducible.
- Do not silently downgrade to Java 21 to make a local build pass — a version pin exists for a
  reason and the spec pins it deliberately.

## 10. Phasing

**v1 (this spec):** payments lifecycle, double-entry ledger, idempotency, signed webhooks with a
durable inbox, settlement ingestion, reconciliation with cases, audit trail, ops read models,
Docker, CI, OpenAPI.

**v1.5 candidates, in preference order:**

1. **Merchant balance recovery** — the one gap payouts expose: a refund of an already-paid-out
   payment is currently refused. Supporting it means a merchant receivable account and a funded
   float, so it is genuinely v1.5 work rather than a small extension.
2. **Settlement fees** — gross / processing fee / platform fee / net on both sides of the
   reconciliation, activating the `PSP_FEE_EXPENSE` account and adding a `SETTLEMENT_FEE` posting.
3. **Partial capture / partial refund** — requires amounts on the state machine and a new
   posting type per tranche.

**v2 candidates:** disputes/chargebacks (`SETTLED → DISPUTED → UNDER_REVIEW → WON/LOST`) with
corresponding reversals; outbox-based internal event publication.

## 11. Decisions taken in this spec (flagged for review)

Each of these resolves an ambiguity in the original brief. They are reversible, but they are
decisions, and the reviewer should confirm them.

| # | Decision | Rationale | Where |
| --- | --- | --- | --- |
| D1 | A ledger transaction is inserted already `POSTED`; there is no `PENDING` state. | Everything (entries + balances + link + audit) lands in one DB transaction, so a transaction can never be observed half-posted. Costs two-phase posting semantics; we do not need them. | [01](01-money-and-ledger.md) |
| D2 | Refund of an **unsettled** payment is a `REVERSAL` of its capture; refund of a **settled** payment is a distinct `PAYMENT_REFUND_SETTLED` posting. | Exercises the reversal machinery in a real business flow rather than only as an operator tool, and is accounting-correct because the contra account differs once cash has moved. | [01](01-money-and-ledger.md) §6 |
| D3 | Reconciliation reasons extend the original eight with `REFERENCE_MISMATCH` and `SETTLEMENT_DATE_MISMATCH`. | A differing merchant reference or settlement date is a genuine discrepancy; classifying it as something else would be hiding data. They are lower precedence than amount/status. | [04](04-reconciliation.md) §4 |
| D4 | Webhook deliveries and canonical events are **two tables**: `provider_event_deliveries` (append-only, every delivery including rejects and duplicates) and `provider_events` (deduplicated, carries processing state). | Deduplication needs a uniqueness constraint; the audit trail needs to record the duplicates that constraint throws away. One table cannot do both. | [03](03-idempotency-and-webhooks.md) §4 |
| D5 | Settlement data arrives by **both** webhook push and an operator-invoked pull (`POST /api/v1/provider/sync`). | Webhooks are lost in practice; a reconciliation system that trusts only the push path is not credible. | [06](06-api-contract.md) |
| D6 | v1 fee model is a single platform fee at capture (`bps` of gross); PSP fees land in v1.5. | Keeps `gross = net + fee` exact and the reconciliation comparison set small. | [01](01-money-and-ledger.md) §3 |
| D7 | Expected provider view is derived from the **ledger**, not from `payments`. | Prevents an operational-state bug from masking a money bug. | [04](04-reconciliation.md) §3 |
| D8 | Two static bearer tokens from environment variables, no user store. | Auth is not the subject of this project; the code path is real and the limitation is documented. | [06](06-api-contract.md) §6 |
| D9 | A **payout is a multi-line record over individual payments' net amounts**, posting one `MERCHANT_PAYOUT` per record — not a per-merchant aggregate over a shared account. | A payout file listing the payments it settles needs no per-merchant sub-ledgers, mirrors the `settlement_records` shape already in the schema, and `UNIQUE (payment_id)` on the lines makes paying the same payment twice structurally impossible. | [01](01-money-and-ledger.md) §3 |
| D10 | A refund of a payment whose net has already been paid out is **refused** (`409 PAYMENT_ALREADY_PAID_OUT`), not funded. | Funding it needs 100.00 when the platform holds 97.00, and would drive `PLATFORM_CASH` negative. The honest v1 answer is a loud refusal plus an operator adjustment, rather than a balance the ledger should never be allowed to hold. | [01](01-money-and-ledger.md) §3 |
| D11 | The build runs **only** in Docker on a Java 25 builder; the local JDK 21 is never used to compile. | Confirmed with the owner. The version pin is a deliberate claim, so the build must not quietly succeed on an older JDK. | §9 |

## 12. Document map

| Document | Contents |
| --- | --- |
| [00-overview](00-overview.md) | This document. |
| [01-money-and-ledger](01-money-and-ledger.md) | `Money`, chart of accounts, ledger invariants L1–L11, posting, reversal, balances, verification. |
| [02-payments](02-payments.md) | Exact state machine, transition table, command contracts, side effects. |
| [03-idempotency-and-webhooks](03-idempotency-and-webhooks.md) | Idempotency protocol and fingerprinting, HMAC scheme, durable inbox, retry/backoff. |
| [04-reconciliation](04-reconciliation.md) | Scope selection, three-stage algorithm, classification precedence, case lifecycle. |
| [05-database-schema](05-database-schema.md) | Full baseline DDL, indexes, immutability triggers. |
| [06-api-contract](06-api-contract.md) | Every endpoint, error model, security, OpenAPI. |
| [07-failure-matrix](07-failure-matrix.md) | Every designed-for failure, its detection, behaviour, and evidence. |
| [08-test-matrix-and-dod](08-test-matrix-and-dod.md) | Test inventory with stable IDs, demo scenarios, Definition of Done. |
| [../adr](../adr/README.md) | Architecture decision records. |