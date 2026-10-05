# Architecture Decision Records

Short, dated records of decisions that were genuinely arguable — the kind where a reasonable
engineer would have chosen differently, and where the *reason* is more useful than the outcome.

Format is deliberately lightweight: context, decision, consequences (including the costs that are
accepted), and the condition under which the decision should be revisited. No templates, no status
workflow ceremony. A decision that is obvious does not need a record.

| ADR | Decision | Revisit when |
| --- | --- | --- |
| [0001](0001-modular-monolith.md) | Modular monolith, not microservices | The reconciliation engine needs independent scaling or deployment |
| [0002](0002-ledger-posting-locking.md) | Pessimistic row locks in deterministic order for ledger posting | A hot account becomes a measured throughput bottleneck |
| [0003](0003-durable-inbox.md) | Persist inbound provider events before applying any effect | Never — this is the correctness foundation of the ingestion path |
| [0004](0004-database-enforced-invariants.md) | Financial invariants enforced in the database as well as Java | Never; the database layer is the point |
| [0005](0005-ledger-as-reconciliation-source.md) | Reconcile against the ledger, not the payment row | Never; the alternative is a system that cannot detect its own bugs |

## What is deliberately *not* recorded

Decisions with a single defensible answer — using an integer for money, using keyset pagination,
using RFC 9457 problem responses, choosing PostgreSQL — belong in the specification
([`../spec`](../spec/00-overview.md)), not in an ADR. An ADR that records every choice is a
changelog, and a changelog nobody reads is worse than no changelog.