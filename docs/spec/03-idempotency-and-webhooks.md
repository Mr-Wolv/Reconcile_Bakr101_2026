# 03 — Idempotency and Webhooks

Two separate mechanisms that solve the same shape of problem — *the same intent arriving more
than once* — from opposite directions: client-initiated commands, and provider-initiated events.

---

# Part A — Idempotency

## A1. Protocol

Header on every mutating endpoint:

```http
Idempotency-Key: 9f1c8e3a-4c2b-4e1a-9f7d-2b6c8e1a4f30
```

Rules:

| Endpoint | Key |
| --- | --- |
| `POST /api/v1/payments` | **required** (`400 IDEMPOTENCY_KEY_REQUIRED`) |
| `POST /api/v1/payments/{id}/authorize\|capture\|refund\|fail` | optional, honoured |
| `POST /api/v1/reconciliation/batches`, `POST /api/v1/provider/sync` | optional, honoured |
| `POST /api/v1/reconciliation/cases/{id}/resolve` | optional, honoured |

A key is scoped to an **endpoint**, not global: `authorize` and `capture` are separate namespaces.
`POST /api/v1/payments` uses the literal template `POST /api/v1/payments` (not a concrete id), so
the same key is never accidentally reused across different payments.

## A2. Fingerprinting

```
fingerprint = SHA-256( canonicalJson {
    "method":        "POST",
    "pathTemplate":  "/api/v1/payments/{id}/capture",
    "bodyHash":      SHA-256(rawRequestBodyBytes)
} )
```

The fingerprint uses the **raw** request bytes, before deserialisation. Two requests that differ
only in key order or whitespace still hash differently — accepted, because a client that
re-serialises differently on retry has a real ambiguity, and silently treating them as the same
request would be worse than a conflict.

## A3. Behaviour matrix

| Situation | Response |
| --- | --- |
| First use of a key | Execute, store response, return it (`201`/`200`) |
| Same key, same endpoint, **same fingerprint**, prior response `COMPLETED` | Return the stored response verbatim, including status code and body. Header `Idempotency-Replayed: true`. Audit `IDEMPOTENCY_KEY_REPLAYED`. |
| Same key, same endpoint, **different fingerprint** | `409 IDEMPOTENCY_KEY_CONFLICT`. The stored record is untouched; no side effect occurs. Audit `IDEMPOTENCY_KEY_CONFLICT`. |
| Same key, prior response `IN_PROGRESS` | `409 REQUEST_IN_PROGRESS` with `Retry-After: 1` |
| Same key, prior response was a **business** failure (4xx) | Stored and replayed verbatim. A `409 PAYMENT_INVALID_STATE` replays as the same `409` on retry — deterministic behaviour. |
| Same key, prior response was an **infrastructure** failure (5xx, timeout) | Record is **released** (deleted) so the client can retry cleanly. A 500 must not be cached as a permanent answer. |

The distinction between the last two rows is the one most systems get wrong. Caching a 5xx
turns a transient database blip into a permanently failed payment.

## A4. Storage and execution

Table `idempotency_records`:

| Column | Notes |
| --- | --- |
| `id` | ULID `idem_…` |
| `endpoint` | `varchar(128)`, the method and path template (`POST /api/v1/payments/{id}/capture`). The method is part of the namespace, not only of the fingerprint: uniqueness is `(endpoint, idem_key)`, and folding the verb into the fingerprint alone would make two verbs on one template collide into `IDEMPOTENCY_KEY_CONFLICT` instead of being the independent namespaces they are |
| `idem_key` | `varchar(255)` |
| `fingerprint` | `char(64)` |
| `status` | `IN_PROGRESS` \| `COMPLETED` |
| `response_status` | nullable until completed |
| `response_body` | `jsonb`, nullable until completed |
| `resource_type`, `resource_id` | e.g. `PAYMENT`, `pay_…` |
| `created_at`, `completed_at`, `expires_at` | `expires_at` = created + 24 h |

Unique: `(endpoint, idem_key)`.

Execution order — reservation, work, and recording in **one transaction**:

```
BEGIN
  INSERT INTO idempotency_records (endpoint, idem_key, fingerprint, status)   -- status IN_PROGRESS
     VALUES (:endpoint, :key, :fingerprint, 'IN_PROGRESS')
     ON CONFLICT (endpoint, idem_key) DO NOTHING;
  -- 0 rows inserted ⇒ we lost the race; handle per A5
  ... execute the command (payment insert, ledger posting, audit) ...
  UPDATE idempotency_records
     SET status = 'COMPLETED', response_status = :status, response_body = :body, ...
   WHERE id = :reservation_id;
COMMIT
```

Because the reservation and the work share one transaction, a crash rolls back both: the key is
free again and no side effect exists. The `IN_PROGRESS` state is therefore only ever visible to a
*concurrent* transaction, never after a crash — which is exactly what it is for.

## A5. The concurrent-retry race

Two identical requests arrive at the same instant:

1. Both attempt the `INSERT`. One succeeds and takes a row lock; the other **blocks** on the
   unique index.
2. The first commits, releasing the lock.
3. The second's insert returns 0 rows affected. It re-reads the record: `COMPLETED`.
4. The second replays the stored response.

No `IN_PROGRESS` response is emitted in this path, because under `READ COMMITTED` the blocking
insert only unblocks after the winner commits, and the follow-up `SELECT` starts a fresh
statement snapshot.

The `409 REQUEST_IN_PROGRESS` branch exists for the residual case where the winner's
transaction is still open at the moment the loser gives up waiting (lock timeout, or a
transaction that spans more than one HTTP hop). It is correct and it is rare — and the test
suite asserts both the fast path (identical response) and the slow path (`Retry-After`), so the
behaviour is evidence rather than assumption.

## A6. Retention

Records are deleted after 24 hours by a scheduled sweeper, in batches of 1,000, oldest first.
This is a documented trade-off: a key presented after 24 hours is treated as new and will
execute again. That is the same behaviour Stripe documents, and it is stated in the API
documentation rather than hidden.

## A7. Evidence

| Test | Assertion |
| --- | --- |
| `I-IDEM-01` | Same key + same body twice → one payment row, identical response body, `Idempotency-Replayed: true`. |
| `I-IDEM-02` | Same key + different body → `409`, and **no** second payment exists. |
| `I-IDEM-03` | 32 threads, same key, same body → exactly one payment, 32 identical response bodies. |
| `I-IDEM-04` | Business failure (capture a `CREATED` payment) → `409`; retry with the same key → identical `409`, no state change. |
| `I-IDEM-05` | Simulated `DataAccessResourceFailureException` → record released; retry succeeds and posts exactly one ledger transaction. |

---

# Part B — Signed Webhooks and the Durable Inbox

## B1. Signature scheme

```http
POST /api/v1/provider/webhooks
X-Provider-Event-Id: evt_01K4…
X-Provider-Timestamp: 1759600000
X-Provider-Signature: 5f2c…(64 hex chars)
Content-Type: application/json
```

```
signature = lowercase_hex( HMAC_SHA256( providerSecret,
                       timestamp + "." + eventId + "." + rawBody ) )
```

- The signature covers the **raw request bytes**. The controller reads `byte[]`, verifies, and
  only then deserialises. Any design that deserialises first and re-serialises for verification
  is broken by definition, and there is a test that proves verification uses the raw bytes
  (a request whose body is valid JSON but re-serialises differently still verifies).
- The signature covers the **event id** (F-06). This is not decoration: `X-Provider-Event-Id` is
  the key `provider_events` deduplicates on, so authenticating the body while trusting an unsigned
  header leaves the identity unauthenticated. A captured valid request replayed under a fresh id
  within the freshness window verified and enqueued a second event. The event id is now part of the
  signed material, so that replay produces `401 WEBHOOK_REJECTED_SIGNATURE` and no second event.
- The `.` separators are required, not formatting. Without them an event id of `a` with a body
  beginning `b` produces the same MAC input as an event id of `a.b` with an empty body. Keyed MACs
  need an unambiguous field encoding; `WebhookSignatureTest` asserts the two do not collide.
- **Documented replay semantics.** Inside the ±300 s window a byte-identical redelivery is
  accepted and deduplicated — that is a provider retrying its own delivery, and refusing it would
  break every honest provider. Outside the window every request is refused regardless of
  signature. What is no longer possible is replaying an authentic request under a <i>different</i>
  event id, because the id is signed.
- Comparison is constant-time (`MessageDigest.isEqual` on decoded bytes).
- The timestamp is epoch **seconds**. Accepted within a **±300 s** window; outside it →
  `401 WEBHOOK_TIMESTAMP_OUT_OF_WINDOW`, and the delivery is recorded as rejected.
- Secrets come from the environment (`RECONCILE_PSP_WEBHOOK_SECRET`). They are never stored in
  the database, never logged, and never committed. The application refuses to start with a
  default secret outside the `dev` profile.

## B2. Verification order

Order matters, and it is not arbitrary — cheapest and most decisive first, so that a flood of
bad traffic does not reach the database.

```
1. Body ≤ 256 KiB                                   → else 413 WEBHOOK_TOO_LARGE (recorded)
2. Headers present and well-formed                 → else 400
3. Timestamp parseable and within the window       → else 401 (recorded as rejected)
4. HMAC over (timestamp, event id, body) matches   → else 401 (recorded as rejected)
5. Event id not already delivered and canonicalised → else 200, duplicate=true (recorded)
6. Transaction: persist delivery row, then event row, then audit
7. Return 202 Accepted
```

Note step 3 before step 4. Checking freshness first bounds replay of a captured request even if
an attacker holds a key. Checking the signature first on a stale request would be the more
expensive order and would tell an attacker only that their signature was bad.

**Step 1 is a bound on reading, not on a header (F-04).** The original wording — "Content-Length
present and ≤ 256 KiB" — described an implementation that checked a header and then called
`readAllBytes`. A chunked request has no `Content-Length`, so nothing bounded what was read: a
300 KB chunked body was received in full, allocated in full, and only then refused. On an
unauthenticated endpoint that is the difference between a size limit and a suggestion.

The bound is now applied while the body is consumed. The request wrapper reads at most
`256 KiB + 1` bytes and never asks the underlying stream for more, so an undeclared body of any
size costs this process one byte of it. The extra byte is what distinguishes "exactly at the
limit" (accepted) from "over the limit" (refused); a wrapper that stopped on the limit could not
tell them apart. The remaining bytes are not drained — the connection is closed instead, because
consuming them to be polite to the next request on the socket is the cost the bound exists to
avoid.

Either way the refusal is **recorded** as a `TOO_LARGE` delivery row. A `413` with no row is the
symptom this replaces: an unauthenticated endpoint being asked to do too much, silently.

## B3. Two tables, because deduplication and audit need different shapes

**`provider_event_deliveries`** — append-only. One row per HTTP delivery attempt, including
duplicates and rejects.

| Column | Notes |
| --- | --- |
| `id` | ULID `ped_…` |
| `event_id` | From the header |
| `received_at` | |
| `outcome` | `ACCEPTED` \| `DUPLICATE` \| `REJECTED_SIGNATURE` \| `REJECTED_TIMESTAMP` \| `REJECTED_MALFORMED` \| `TOO_LARGE` |
| `http_status` | The status we returned |
| `signature_valid` | `boolean` |
| `reason` | Nullable detail |
| `request_id` | |

**`provider_events`** — the deduplicated canonical event, carrying processing state. Unique on
`(provider, event_id)`.

| Column | Notes |
| --- | --- |
| `id` | ULID `pev_…` |
| `event_id`, `provider` | Unique together |
| `event_type` | `payment.created` \| `payment.captured` \| `payment.settled` \| `payment.refunded` \| `payment.failed` \| `settlement.paid` |
| `provider_occurred_at` | Provider's timestamp, original offset preserved in `provider_occurred_at_offset` |
| `payload` | `jsonb`, verbatim |
| `payload_hash` | `char(64)` |
| `status` | `PENDING` \| `PROCESSING` \| `PROCESSED` \| `NO_EFFECT` \| `FAILED` \| `DEAD` |
| `attempts`, `next_attempt_at`, `locked_at`, `locked_by`, `last_error`, `processed_at` | Worker bookkeeping |

Rationale (decision **D4**): deduplication requires a uniqueness constraint that *rejects*
duplicates; auditability requires that those same duplicates be *recorded*. One table cannot do
both, and the demo scenario "the audit trail shows three deliveries, one effect" depends on
having both.

## B4. Processing is asynchronous by design

The HTTP handler does not apply business effects. It persists and returns `202`.

```
HTTP → verify → persist delivery + event → 202
                                        ↓ (worker, seconds later)
                    claim PENDING event → apply effect → PROCESSED / NO_EFFECT / FAILED
```

The reason is failure semantics. If business effects ran inside the request, a database
connection lost mid-processing would leave the provider retrying an operation whose outcome we
also do not know — uncertain delivery in both directions. Persisting first makes the event
durable *before* any effect, so the provider's retry is harmless and our processing is resumable.

Worker claiming:

```sql
WITH claimed AS (
  SELECT id FROM provider_events
   WHERE status IN ('PENDING','FAILED')
     AND next_attempt_at <= now()
     AND attempts < :max_attempts
   ORDER BY next_attempt_at
   LIMIT :batch
   FOR UPDATE SKIP LOCKED          -- a second worker never blocks or double-claims
)
UPDATE provider_events e
   SET status = 'PROCESSING', locked_at = now(), locked_by = :worker_id, attempts = e.attempts + 1
  FROM claimed WHERE e.id = claimed.id
RETURNING e.*;
```

Retries: exponential backoff `base 2s, factor 2, cap 5 min, full jitter`, max **8 attempts**.

**Three terminal shapes, not two (F-07).** The claim query above selects
`status IN ('PENDING','FAILED')`, so what `FAILED` means has to be exact:

| State | Meaning | Claimed again? |
| --- | --- | --- |
| `PENDING` | Waiting for a scheduled attempt, or deferred because a precondition has not arrived | yes, when `next_attempt_at` passes |
| `FAILED` | A <i>retryable</i> fault; the budget is not yet exhausted | yes, after backoff, up to 8 attempts |
| `DEAD` | Retrying cannot change the answer | **never** |

`DEAD` is terminal by construction, not by convention: it is outside the claim query, and it is
still returned by `GET /api/v1/provider/events/failed`, so "stop retrying" never means "stop
showing". A permanently broken event — an unknown event type, a stored payload that no longer
parses, a settlement whose own totals contradict its lines — reaches `DEAD` on attempt 1.

The previous behaviour wrote `FAILED` with `next_attempt_at = now()` for both kinds of failure,
which the claim query immediately picked up again: a poison event consumed eight attempts and five
minutes of backoff to arrive at an answer it had already reached, and pushed a genuinely fixable
backlog behind it while it did. `I-WEB-10` now asserts `attempts == 1` and `status = DEAD` after
twelve worker passes.

After the last retryable attempt, `status = FAILED`, `WEBHOOK_FAILED` is audited, and the same
endpoint exposes the backlog. Stale `PROCESSING` rows whose `locked_at` is older than 5 minutes
are reclaimed by the same sweep (crash recovery — see the failure matrix).

## B5. Out-of-order and stale events

Duplicate suppression handles *repetition*. It does not handle *disagreement*, and a PSP will
happily deliver `payment.captured` after `payment.refunded`.

Every event carries `provider_occurred_at`. Before applying an effect, the handler compares it
against the payment's current state using the same transition table as the API. If the requested
transition is not legal from the current state, the event is **not** discarded silently:

| Condition | Outcome |
| --- | --- |
| Transition is legal | Apply it. `PROCESSED`. |
| Transition not legal **and** the payment has already reached a state at least as far along | `NO_EFFECT`. Audit `WEBHOOK_NO_EFFECT` with both states. **This is the duplicate-and-reordered case.** |
| Transition not legal **and** the payment is behind | Apply anyway, and audit the anomaly. Reconciliation, not the event handler, is what surfaces it later as a case. |

The third row is deliberate. Refusing to apply an out-of-order event would leave our internal
state permanently behind the provider's, and the resulting case would then have no honest
explanation. Applying it and flagging it produces a state that agrees with the provider, plus a
case an operator can actually investigate. Choosing to lie in either direction is worse than
recording what happened.

## B6. Evidence

| Test | Assertion |
| --- | --- |
| `I-WEB-01` | Valid signature → `202`, one delivery row, one event row. |
| `I-WEB-02` | Same event id delivered 3× → 3 delivery rows (`ACCEPTED`, `DUPLICATE`, `DUPLICATE`), **one** event row, **one** payment transition, one ledger posting. |
| `I-WEB-03` | Invalid signature → `401`, delivery row `REJECTED_SIGNATURE`, no event row, no business effect. |
| `I-WEB-04` | Timestamp 10 minutes old → `401 REJECTED_TIMESTAMP`. Timestamp in window → `202`. |
| `I-WEB-05` | Valid JSON whose re-serialisation differs from the raw body → still `202` (proves raw-byte signing). |
| `I-WEB-06` | `payment.captured` delivered **after** `payment.refunded` → payment stays `REFUNDED`, event `NO_EFFECT`, audit records both states. |
| `I-WEB-07` | Worker killed between claim and effect; row left `PROCESSING`; after the 5-minute reclaim sweep the event is processed exactly once. |
| `I-WEB-08` | Handler is invoked while the database is unreachable → `500`, no partial write, event absent; redelivery succeeds. |
| `I-WEB-09` | Event references a payment that does not exist yet → `FAILED` with backoff, then succeeds once the payment appears. |
| `I-WEB-10` | Non-retryable error fails the event immediately (attempts == 1), leaves `status = DEAD`, is never re-claimed, and surfaces on `/provider/events/failed`. |