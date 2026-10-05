# 06 — API Contract

Base path `/api/v1`. All request and response bodies are `application/json; charset=utf-8`.
A machine-readable OpenAPI 3.1 document is generated from these contracts and committed at
`docs/openapi/reconcile-v1.yaml`; CI fails if the committed document drifts from the annotated
controllers.

Money is **always** `{"amountMinor": <long>, "currency": "<ISO-4217>"}`. Never a decimal, never
a float, in any direction.

---

## 1. Endpoint inventory

### Payments

| Method | Path | Auth | Idempotency |
| --- | --- | --- | --- |
| `POST` | `/api/v1/payments` | operator | **required** |
| `GET` | `/api/v1/payments/{id}` | operator | — |
| `GET` | `/api/v1/payments` | operator | — |
| `GET` | `/api/v1/payments/{id}/timeline` | operator | — |
| `POST` | `/api/v1/payments/{id}/authorize` | operator | optional |
| `POST` | `/api/v1/payments/{id}/capture` | operator | optional |
| `POST` | `/api/v1/payments/{id}/refund` | operator | optional |
| `POST` | `/api/v1/payments/{id}/fail` | admin | optional |

`POST /api/v1/payments` → `201 Created`, `Location: /api/v1/payments/{id}`

```json
{ "id": "pay_01K4XQ8Z…", "merchantReference": "MERCH-77", "state": "CREATED",
  "amount": {"amountMinor": 10000, "currency": "EGP"},
  "platformFee": {"amountMinor": 300, "currency": "EGP"},
  "netAmount": {"amountMinor": 9700, "currency": "EGP"},
  "provider": "SIMULATED_PSP", "providerTransactionId": null,
  "createdAt": "2026-10-04T09:00:00Z", "version": 0 }
```

`GET /api/v1/payments` accepts `state`, `merchantReference`, `providerTransactionId`, `cursor` and
`limit` (default 50, max 200). Keyset pagination on `(created_at, id)` — offset pagination on a
financial audit query is a bug that gets worse as the table grows.

`GET /api/v1/payments/{id}/timeline` merges the four sources that touch a payment — its state
history, the postings made against it, the provider events that mentioned it, and the
reconciliation results that judged it — into one chronologically ordered list of uniform entries,
each carrying the id of the row it describes and, where the underlying source has one, the
correlation id. The four are ordered by `(occurred_at, kind, reference)`; the tiebreak is not
cosmetic, because a capture writes a state-history row, a posting and an audit event inside one
transaction and so genuinely shares a timestamp. The endpoint has no pagination: it is bounded by
one payment's own history.

`createdFrom` and `createdTo` are **not implemented**. An earlier draft of this spec listed them; the
controller has no such parameters, so a caller sending them gets them silently ignored. Either they
are added or this line stays; what must not happen is a client integrating against a filter the
service does not apply.

### Ledger

| Method | Path | Auth |
| --- | --- | --- |
| `GET` | `/api/v1/accounts` | operator |
| `GET` | `/api/v1/accounts/{code}` | operator |
| `GET` | `/api/v1/accounts/{code}/entries` | operator |
| `GET` | `/api/v1/ledger/transactions/{id}` | operator |
| `POST` | `/api/v1/ledger/transactions/{id}/reverse` | **admin** |

`GET /api/v1/accounts/{code}` returns the projected balance **and** the recomputed balance:

```json
{ "code": "PSP_CLEARING", "name": "Receivable from the PSP", "accountType": "ASSET",
  "currency": "EGP", "balance": {"amountMinor": 0, "currency": "EGP"},
  "entryCount": 2, "lastEntryAt": "2026-10-04T09:10:00Z", "verified": true }
```

`verified` is the result of the L7 recomputation. Surfacing it on the primary balance endpoint
means an operator can see an invariant breach from the UI without knowing to run a report.

`POST /api/v1/ledger/transactions/{id}/reverse` is admin-only and requires
`{ "reason": "..." }`. It never edits the original; it creates a `REVERSAL`.

### Provider simulator

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| `POST` | `/api/v1/provider/payments` | operator | Registers a payment with the simulated PSP |
| `POST` | `/api/v1/provider/webhooks` | **signature** | The inbound webhook endpoint |
| `GET` | `/api/v1/provider/settlements` | operator | The PSP's own view of settlements |
| `GET` | `/api/v1/provider/events` | operator | Canonical events |
| `GET` | `/api/v1/provider/events/failed` | operator | The poison-message backlog |
| `POST` | `/api/v1/provider/sync` | admin | Decision D5 — pull settlements for a window |

The simulator's behaviour is controllable, which is the whole point of it:

```json
POST /api/v1/provider/payments
{ "merchantReference": "MERCH-77", "grossAmountMinor": 10000, "currency": "EGP",
  "behaviour": ["DUPLICATE_WEBHOOK", "DELAYED_WEBHOOK", "WRONG_AMOUNT",
                "PARTIAL_SETTLEMENT", "OMIT_FROM_SETTLEMENT", "DUPLICATE_SETTLEMENT",
                "TIMEOUT", "OUT_OF_ORDER"] }
```

Note the amount is a flat `grossAmountMinor` plus a separate `currency`, not the `amount` object
every other endpoint in this spec uses — the request is the *provider's* shape, not ours. The
eight behaviours are exactly `SimulatedProviderService.Behaviour`; anything else is a `400`.

Each behaviour maps to one of the failure-matrix rows, so every designed-for failure is
reproducible with one HTTP call and therefore testable end to end.

`POST /api/v1/provider/sync` — `{ "windowStart": "…", "windowEnd": "…" }` → fetches settlement
records from the simulator and runs the same ingestion path as the webhook. Its existence is the
answer to "what if the provider's webhook never arrives?", which in practice it always does.

### Payouts

| Method | Path | Auth |
| --- | --- | --- |
| `POST` | `/api/v1/payouts` | **admin** |
| `GET` | `/api/v1/payouts/{id}` | operator |
| `GET` | `/api/v1/payouts` | operator |

```http
POST /api/v1/payouts
Idempotency-Key: 5b0d…
Content-Type: application/json

{ "merchantReference": "MERCH-77",
  "paymentIds": ["pay_01K4XQ8Z…", "pay_01K4XQ9A…"],
  "idempotencyKey": "5b0d…" }
```

→ `201 Created` with the record and the `MERCHANT_PAYOUT` posting id.

The caller names the payments; the service reads each one's **net amount from the ledger**, not
from the request. A caller cannot pay out an arbitrary figure. Rules, all enforced before
anything is written:

| Rule | Failure |
| --- | --- |
| Every named payment exists and is `SETTLED` | `422 PAYOUT_PAYMENT_NOT_SETTLED` |
| No named payment appears in an earlier payout (L11) | `409 PAYOUT_PAYMENT_ALREADY_PAID` |
| Every named payment belongs to `merchantReference` | `422 PAYOUT_MERCHANT_MISMATCH` |
| Every named payment shares one currency | `422 PAYOUT_CURRENCY_MISMATCH` |
| `PLATFORM_CASH` covers the total | `409 PAYOUT_INSUFFICIENT_CASH` |

The whole thing is one transaction: lock the payments, lock the accounts in the standard order,
post one `MERCHANT_PAYOUT`, insert the lines, set `paid_out_at`, audit. It is also
idempotency-key aware, so a retry after a timeout cannot pay the merchant twice — and even
without that key, L11 makes the second attempt impossible.

### Reconciliation

| Method | Path | Auth |
| --- | --- | --- |
| `POST` | `/api/v1/reconciliation/batches` | admin |
| `GET` | `/api/v1/reconciliation/batches` | operator |
| `GET` | `/api/v1/reconciliation/batches/{id}` | operator |
| `GET` | `/api/v1/reconciliation/batches/{id}/results` | operator |
| `GET` | `/api/v1/reconciliation/cases` | operator |
| `GET` | `/api/v1/reconciliation/cases/{id}` | operator |
| `POST` | `/api/v1/reconciliation/cases/{id}/investigate` | operator |
| `POST` | `/api/v1/reconciliation/cases/{id}/resolve` | admin |
| `POST` | `/api/v1/reconciliation/cases/{id}/write-off` | **admin** |

```http
POST /api/v1/reconciliation/batches
{ "provider": "SIMULATED_PSP", "windowStart": "2026-10-01T00:00:00Z",
  "windowEnd": "2026-10-05T00:00:00Z", "subjectMode": "BOTH", "async": true }
```

→ `202 Accepted` with the batch id. `async: false` runs it inline and returns `200` with the
completed summary — used by the CI scenario and by small demo batches.

```http
POST /api/v1/reconciliation/cases/{id}/resolve
{ "resolutionNote": "PSP applied a 3.00 processing fee that our fee policy did not model; internal fee configuration was stale.",
  "adjustmentLedgerTransactionId": "ltx_01K4Y…" }
```

The note minimum length is 20 characters and is enforced by
`reconciliation_cases.ck_case_resolution` as well as by bean validation — the database is the
last line of defence here too.

### Operations

| Method | Path | Auth |
| --- | --- | --- |
| `GET` | `/api/v1/audit/events` | operator |
| `GET` | `/api/v1/audit/entities/{type}/{id}` | operator |
| `GET` | `/api/v1/health` | permitAll |
| `GET` | `/api/v1/metrics/summary` | operator |

`GET /api/v1/audit/events` filters on `action`, `entityType`, `entityId`, `actorType`, `from`,
`to`, `requestId`. `GET /api/v1/audit/entities/{type}/{id}` returns the trail for one entity so an
auditor can pull it without a UI. The `{type}` segment is **case-insensitive** — `payment` and
`PAYMENT` are the same request — because the SCREAMING_SNAKE casing in `audit_events.entity_type`
is a storage detail and should not be part of the public contract. Case-insensitivity is not
aliasing: `payments` is the plural of an entity noun, not another casing of the token, so it is a
`404` like any other unrecognised value. An *unknown* type is a `404`
rather than an empty list, so a typo is distinguishable from an entity that simply has not happened
yet. The known types are exactly those the application writes, and the endpoint test reads them out
of the database rather than out of a copy of the list so the two cannot drift apart.

`/api/v1/health` is unauthenticated and returns `200` only when migrations are at the expected
version, the L7 balance verification last passed, and there is no reconciliation batch stuck in
`RUNNING` beyond its lease. Otherwise `503` with the failing checks named. A health endpoint that
only checks that the process is alive is not a health endpoint.

## 2. Headers

| Header | Direction | Meaning |
| --- | --- | --- |
| `Idempotency-Key` | request | See [03](03-idempotency-and-webhooks.md) |
| `Idempotency-Replayed` | response | `true` when the response was served from a stored record |
| `X-Request-Id` | both | Accepted from the client if present, generated otherwise; echoed; recorded in every audit row |
| `X-Provider-Event-Id`, `X-Provider-Timestamp`, `X-Provider-Signature` | webhook only | See [03](03-idempotency-and-webhooks.md) |
| `Retry-After` | response | On `409 REQUEST_IN_PROGRESS` and `503` |
| `RateLimit-*` | response | **Not implemented.** No rate limiter exists in v1 and no such header is emitted. A caller must not read them, and a deployment that needs them must put a gateway in front. |

## 3. Error model

RFC 9457 `application/problem+json`, with a stable machine-readable `code` so clients branch on
the code and never on prose.

```json
{
  "type": "https://reconcile.local/problems/invalid-state-transition",
  "title": "Invalid payment state transition",
  "status": 409,
  "code": "PAYMENT_INVALID_STATE",
  "detail": "Cannot transition payment pay_01K4XQ8Z… from CREATED to SETTLED",
  "instance": "/api/v1/payments/pay_01K4XQ8Z…/capture",
  "requestId": "req_01K4XRA…",
  "errors": [ { "field": "state", "message": "expected one of [AUTHORIZED]", "rejectedValue": "CREATED" } ]
}
```

| Code | Status | When |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | Bean validation / JSON binding |
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | `POST /payments` without a key |
| `MALFORMED_REQUEST` | 400 | Unparseable JSON, or the raw body exceeds 256 KiB |
| `WEBHOOK_REJECTED_SIGNATURE` | 401 | HMAC mismatch |
| `WEBHOOK_REJECTED_TIMESTAMP` | 401 | Outside the ±300 s window |
| `WEBHOOK_REJECTED_MALFORMED` | 400 | Valid signature, unparseable payload |
| `UNAUTHENTICATED` | 401 | Missing/invalid bearer token |
| `FORBIDDEN` | 403 | Authenticated, wrong role |
| `RESOURCE_NOT_FOUND` | 404 | Unknown id |
| `METHOD_NOT_ALLOWED` | 405 | Wrong verb on a known path |
| `IDEMPOTENCY_KEY_CONFLICT` | 409 | Same key, different fingerprint |
| `REQUEST_IN_PROGRESS` | 409 | Same key, request still executing |
| `PAYMENT_INVALID_STATE` | 409 | Illegal transition |
| `PROVIDER_TRANSACTION_ALREADY_CLAIMED` | 409 | Another payment already carries this provider transaction id (`ux_payments_provider_txn`) |
| `CAPTURE_AMOUNT_MISMATCH` | 409 | `expectedAmount` ≠ authorised amount |
| `PAYMENT_EXPIRED` | 410 | Capture after `expires_at` |
| `PAYMENT_ALREADY_PAID_OUT` | 409 | Refund of a payment included in a payout (decision D10) |
| `PAYOUT_PAYMENT_ALREADY_PAID` | 409 | A named payment is already in a payout (L11) |
| `PAYOUT_PAYMENT_NOT_SETTLED` | 422 | A named payment is not `SETTLED` |
| `PAYOUT_MERCHANT_MISMATCH` | 422 | A named payment belongs to a different merchant |
| `PAYOUT_CURRENCY_MISMATCH` | 422 | The payout mixes currencies |
| `PAYOUT_INSUFFICIENT_CASH` | 409 | `PLATFORM_CASH` cannot cover the total (L8) |
| `LEDGER_ALREADY_POSTED` | 409 | The `(source, type)` unique constraint fired |
| `LEDGER_IMBALANCE` | 422 | Posting command did not balance — should be unreachable |
| `CASE_INVALID_TRANSITION` | 409 | Illegal case status change |
| `CASE_RESOLUTION_INCOMPLETE` | 422 | Missing/too-short note, or a bogus adjustment reference |
| `PROVIDER_BAD_RESPONSE` | 502 | The provider answered with something that is not a settlement report |
| `PROVIDER_UNAVAILABLE` | 503 | PSP call failed; retryable |
| `LEDGER_BALANCE_BREACH` | 503 | L7 verification failed — **the system is not healthy** |
| `INTERNAL_ERROR` | 500 | Unexpected; always logged with `requestId` |

Internal exception messages never reach the client. `detail` is written for an operator reading
a log, and it never contains a secret, a token, a card number, or a stack trace.

## 4. Error behaviour that is easy to get wrong

- **`PAYMENT_INVALID_STATE` vs `404`** — a transition on an existing payment returns `409`, never
  `404`. Telling a client "not found" when the resource exists leaks nothing useful and
  confuses retry logic.
- **Never `500` for a rejected business decision.** A ledger imbalance is a `422`; a duplicate
  post is a `409`. A `500` means the system broke, and clients escalate `5xx` differently.
- **The webhook endpoint's rejections are deliberate.** It returns `401` on a bad signature and
  *does not* hide that behind `202`. A provider must be able to tell a delivery problem from a
  configuration problem. Duplicates return `200`, because a duplicate is a success.

## 5. Concurrency and pagination conventions

- **Not implemented: conditional reads.** No `ETag` is emitted and `If-None-Match` is ignored, so
  there is no `304`. This was claimed here before it was built; a `304` a client sees in testing
  and never in production is worse than no `304`.
- Keyset pagination everywhere: `?cursor=<opaque>&limit=<n>`, response
  `{ "items": [...], "nextCursor": "…"|null }`. No `offset`, no `page`.
- List responses are capped at 200 items; a larger request is a `400`, not a silent truncation.
- **An unreadable `cursor` is a `400 VALIDATION_FAILED`**, never a silently ignored parameter. An
  endpoint that discards a query parameter it does not understand returns the *first* page while the
  caller believes it asked for another — a successful answer to a question that was never put.

## 6. Security model

| Surface | Mechanism |
| --- | --- |
| All `/api/v1/**` except health and the webhook endpoint | `Authorization: Bearer <token>` |
| `POST /api/v1/provider/webhooks` | HMAC signature only — the signature **is** the authentication |
| Actuator | **Not on a management port.** `management.server.port` is not set, so `/actuator` is served on the main port alongside the API. `/actuator/health/**` and `/actuator/info` are `permitAll`. Everything else is refused by `.anyRequest().denyAll()`, and the refusal is two different codes for two different situations: an anonymous request is **`401`** (it never proved who it is), while a request carrying a *valid* operator or admin token is **`403`** (we know who it is, and no). A token is therefore necessary and not sufficient. Binding a separate port is deployment work that has not been done |

Two static tokens are configured from the environment (decision **D8**):

```
RECONCILE_OPERATOR_TOKEN   → role OPERATOR
RECONCILE_ADMIN_TOKEN      → role ADMIN  (implies OPERATOR)
```

Role matrix: operator reads everything and runs payment commands and case investigation; admin
does everything operator does, plus batch creation, `provider/sync`, ledger reversal, case
resolution and write-off, and payment failure.

Stated plainly, because pretending otherwise would be the dishonest choice: **this is not a
production authentication design.** There is no user store, no token rotation, no OAuth. A real
deployment would put OIDC in front and map claims to the same two roles — and the authorisation
layer, the audit actor model, and the role matrix are the parts that would survive that swap.

Additional controls:

- Bean Validation on every request body; unknown fields rejected (`FAIL_ON_UNKNOWN_PROPERTIES`),
  because silently ignoring an unrecognised field in a payment request is how money goes missing.
- `Content-Type` must be JSON on write endpoints.
- Structured logging with the correlation ids and **no** request/response bodies on payment
  endpoints.
- Secrets only from the environment; no secret is written to the database or to a log line. The
  app refuses to start outside `dev` if a default secret is still configured.
- CORS disabled — there is no browser client in v1.

## 7. OpenAPI

`docs/openapi/reconcile-v1.yaml`, OpenAPI 3.1, generated from the annotated controllers during
`verify` and committed. CI fails on drift. Every endpoint carries an `operationId`, an example
request and response, and an exhaustive list of the `code` values it can return — so the error
surface is documented rather than discovered.