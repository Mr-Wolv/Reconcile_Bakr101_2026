# ADR-0003 — Persist inbound provider events before applying any effect

**Status:** Accepted · 2026-10-04

## Context

The tempting webhook handler is three lines:

```java
@PostMapping("/webhook")
public void webhook(@RequestBody ProviderEvent event) {
    paymentService.apply(event);
}
```

It is short, and it is wrong under failure. Consider a database connection lost *after* the payment
row was updated but *before* the transaction committed, at the moment the provider's connection is
also being reset. The provider sees a timeout and retries. We are now unable to say whether the
first delivery applied, the retry applied, or neither did — **uncertain delivery in both
directions**, and no record anywhere that lets us find out.

The same thing happens without a crash: processing inside the request couples provider latency to
our processing latency, so a slow handler looks to the provider like an outage and gets
disconnected, mid-effect.

## Decision

**Split ingestion from processing. The HTTP handler verifies, persists, and returns `202`. A
background worker applies the business effect.**

```
HTTP request ──▶ verify signature ──▶ verify timestamp ──▶ check event id
                                                              │
                                                              ▼
                              insert delivery row (append-only, always)
                              insert canonical event row (unique on provider+event_id)
                              insert audit row
                                                              │
                                                         COMMIT ──▶ 202
                                                              │
                                                    worker, later
                                                              ▼
                              claim event (FOR UPDATE SKIP LOCKED)
                              apply effect ──▶ PROCESSED / NO_EFFECT / FAILED
```

The event becomes durable **before** any business effect, so the provider's retry is always
harmless and our own processing is always resumable.

## Consequences

**Positive**

- At-least-once delivery from the provider, converted into **exactly-once effects** by the
  uniqueness constraint on `(provider, event_id)` plus the ledger's
  `(source_type, source_id, type)` constraint. The effect is exactly-once even though delivery is
  not, and that distinction is the whole point.
- Provider latency no longer depends on our processing latency; a slow handler cannot cause
  provider-side timeouts mid-effect.
- Every delivery attempt — including duplicates and rejected signatures — is recorded in an
  append-only table. The audit trail can prove that a webhook arrived three times and had one
  effect.
- Crash recovery is ordinary: a row stranded in `PROCESSING` is reclaimed by a stale-`locked_at`
  sweep and reprocessed exactly once.

**Negative, and accepted**

- Processing is eventually consistent, so a webhook's effect is visible seconds after the `202`.
  This is stated in the API contract rather than hidden.
- Two tables are needed where one would do, because deduplication requires a constraint that
  *rejects* duplicates while auditability requires that those same duplicates be *recorded*. One
  table cannot satisfy both.
- A worker, lock leases, backoff, and a poison-message backlog are new operational surface.

## The vocabulary, precisely

This pattern is sometimes called a "transactional inbox"; "transactional outbox" is a different
pattern for the opposite direction (publishing our own events reliably). The distinction matters
because conflating them is how people end up building a queue they did not need:

| | Inbound inbox (here) | Transactional outbox |
| --- | --- | --- |
| Direction | provider → us | us → subscribers |
| Solves | lost / duplicated / slow processing | "committed to the DB but the broker never heard" |
| Present in v1 | yes | **no** — the only subscribers are in-process workers |

## Evidence

`I-WEB-02` delivers one event three times and asserts three delivery rows, one event row, one
state change, and one ledger posting. `I-WEB-06` delivers an out-of-order event and asserts it is
recorded as `NO_EFFECT` rather than dropped. `I-WEB-07` strands a claimed event by simulating a
killed worker and asserts the reclaim sweep processes it exactly once. `I-WEB-08` makes the
database unreachable during the handler and asserts nothing was partially written.