# ADR-0001 — Build a modular monolith, not microservices

**Status:** Accepted · 2026-10-04

## Context

The conventional portfolio fintech project is a microservices showcase: many services, a message
bus, Kubernetes manifests, service discovery. That shape is also the reason such projects tend to
say very little about financial correctness — once the system is distributed, the interesting
question is no longer "is this money correct?" but "how many brokers is it going to take to keep
five services agreeing about it?"

Reconcile's purpose is the opposite. It exists to make a claim about **financial data
correctness**: double-entry invariants, idempotency, immutable history, reconciliation against an
external system, and recovery from failure. Those claims are only credible if they are tested,
and they are much harder to test convincingly when the system is spread across processes.

A sibling project already covers the distributed-systems ground (Kafka, orchestration, partial
failure). Covering it again here would dilute this project's distinct value, not strengthen it.

## Decision

**One Spring Boot application, modular internally, exposed as a REST API plus background workers.**
No message broker, no service discovery, no orchestration, no container-per-service topology.

Modules: `shared`, `ledger`, `payment`, `idempotency`, `provider`, `settlement`, `reconciliation`,
`audit`, `api`. The dependency rule is enforced by an ArchUnit test
(`C-ARCH-01`): modules depend inward only, `shared` depends on nothing, and the domain packages
contain no Spring or JPA imports.

## Consequences

**Positive**

- A ledger posting, its balance updates, its idempotency record, and its audit events commit in
  **one local transaction**. The correctness argument is "one transaction", not "an eventual
  consistency window with a reconciliation story we hope holds".
- Invariants are testable against one real PostgreSQL instance without distributed-test
  infrastructure, which is what makes the concurrency and failure tests affordable to write well.
- The failure modes in the [failure matrix](../spec/07-failure-matrix.md) are the *interesting*
  ones — duplicate webhooks, lost webhooks, partial settlements, worker crashes — rather than
  timeouts between services.
- One deployable artifact, one Docker image, one migration history. The build is reproducible and
  the CI path is short enough to actually run on every push.

**Negative, and accepted**

- No demonstration of distributed transaction handling, service-to-service resilience, or
  orchestration. That is a deliberate gap, not an oversight.
- Vertical scaling only; the reconciliation batch and the event worker compete for the same
  resources.

## Revisit when

A genuine boundary appears that needs independent scaling or independent deployment — most
plausibly the **reconciliation engine**, which is CPU-bound, batch-oriented, back-pressurable,
and reads data that is already durable. Extracting it would not require restructuring anything
else, which is precisely the point of the modular layout.

Splitting components merely because "fintech means microservices" is not a trigger.