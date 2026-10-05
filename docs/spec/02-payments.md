# 02 — Payments

A payment is the operational object. It carries lifecycle state; the **ledger** carries the
money. This document defines the lifecycle exactly, because "explicit, enforced, tested,
documented" is the standard — not the number of states.

---

## 1. States

```
                    ┌──────────┐
                    │ CREATED  │
                    └────┬─────┘
             ┌────────────┴────────────┐
             ▼                         ▼
      ┌────────────┐            ┌──────────┐
      │ AUTHORIZED │            │  FAILED  │  (terminal)
      └─────┬──────┘            └──────────┘
   ┌────────┴────────┐
   ▼                 ▼
┌───────────┐   ┌──────────┐
│ CAPTURED  │   │  FAILED  │  (terminal)
└─────┬─────┘   └──────────┘
      │
      ▼
┌──────────┐
│ SETTLED  │
└─────┬─────┘
      ▼
┌───────────┐
│ REFUNDED  │  (terminal)
└───────────┘
```

| State | Meaning | Money position |
| --- | --- | --- |
| `CREATED` | Accepted, not yet authorised. | No ledger effect. |
| `AUTHORIZED` | Funds committed, not yet taken. | No ledger effect. |
| `CAPTURED` | Money taken from the customer, owed to the merchant. | `PAYMENT_CAPTURE` posted. |
| `SETTLED` | The PSP has paid us; money is in `PLATFORM_CASH`. | `SETTLEMENT_RECEIVED` posted (on the settlement record, not the payment). |
| `FAILED` | Terminal failure; no money moved. | No ledger effect. |
| `REFUNDED` | Terminal; money returned. | `REVERSAL` (unsettled) or `PAYMENT_REFUND_SETTLED` (settled). |

`FAILED` and `REFUNDED` are terminal: no outbound transition exists from either.

## 2. Transition table

This table is the contract. Anything not in it is rejected.

| # | From | To | Trigger | Guards | Ledger effect | Audit action |
| --- | --- | --- | --- | --- | --- | --- |
| T1 | `CREATED` | `AUTHORIZED` | `POST /payments/{id}/authorize` | payment not expired | none | `PAYMENT_AUTHORIZED` |
| T2 | `CREATED` | `FAILED` | webhook `payment.failed` **or** operator `POST /payments/{id}/fail` | `failure_code` present | none | `PAYMENT_FAILED` |
| T3 | `AUTHORIZED` | `CAPTURED` | `POST /payments/{id}/capture` | capture amount == authorised amount (full capture only in v1) | `PAYMENT_CAPTURE` | `PAYMENT_CAPTURED` |
| T4 | `AUTHORIZED` | `FAILED` | webhook `payment.failed` **or** operator `POST /payments/{id}/fail` | `failure_code` present | none | `PAYMENT_FAILED` |
| T5 | `CAPTURED` | `SETTLED` | settlement ingestion covering this payment's provider transaction | the covering settlement record is valid and its lines reference this payment | none on the payment (the settlement record posts its own `SETTLEMENT_RECEIVED`) | `PAYMENT_SETTLED` |
| T6 | `CAPTURED` | `REFUNDED` | `POST /payments/{id}/refund` | full amount; capture posting not already reversed | `REVERSAL` of the capture transaction | `PAYMENT_REFUNDED` |
| T7 | `SETTLED` | `REFUNDED` | `POST /payments/{id}/refund` | full amount; **the payment's net must not appear in any payout line** | `PAYMENT_REFUND_SETTLED` | `PAYMENT_REFUNDED` |

Explicitly rejected, with examples an interviewer will ask about:

| Attempted | Result |
| --- | --- |
| `CREATED → SETTLED` | `409 PAYMENT_INVALID_STATE` |
| `CREATED → CAPTURED` | `409 PAYMENT_INVALID_STATE` |
| `REFUNDED → CAPTURED` | `409 PAYMENT_INVALID_STATE` |
| `REFUNDED → *` (anything) | `409 PAYMENT_INVALID_STATE` |
| `FAILED → *` | `409 PAYMENT_INVALID_STATE` |
| `CAPTURED → CAPTURED` (double capture) | `409 PAYMENT_INVALID_STATE` |
| `CAPTURED → AUTHORIZED` (un-capture) | `409 PAYMENT_INVALID_STATE` — there is no un-capture; money is not edited |
| Capture with a partial amount | `422 CAPTURE_AMOUNT_MISMATCH` (full capture only in v1) |
| Refund of a `CREATED`/`AUTHORIZED`/`FAILED` payment | `409 PAYMENT_INVALID_STATE` — nothing to refund, so there is no ledger reversal to write |
| Refund of a `SETTLED` payment already included in a payout | `409 PAYMENT_ALREADY_PAID_OUT` — funding it would drive `PLATFORM_CASH` negative (decision D10) |

The state machine lives in a pure class (`PaymentStateMachine`) with a single static transition
table and no Spring, so the rules are unit-testable by enumerating the matrix. A test asserts
that **every** pair in the 6×6 matrix either appears in the table or is rejected — the test
cannot be extended without deciding what the new edge means.

## 3. Concurrency of transitions

Every command that changes state executes inside a transaction that begins with:

```sql
SELECT * FROM payments WHERE id = :id FOR UPDATE;
```

The state read, the validation, the ledger posting, and the update all happen under that lock.
Two concurrent `capture` calls therefore serialise: the second sees `CAPTURED` and is rejected
with `409`. Without the lock, both would observe `AUTHORIZED` and both would post — a real
double-capture that the ledger's unique constraint (L10) would catch, but only *after* the
damage, and with a 409 instead of a clean state-machine rejection.

Ordering is deliberate and always the same: **lock payment → lock accounts**. Two commands can
never deadlock against each other because they always take the two lock sets in the same order.

## 4. Payment fields

| Field | Type | Notes |
| --- | --- | --- |
| `id` | ULID `pay_…` | PK |
| `merchant_reference` | `varchar(64)` | Supplied by the caller; compared during reconciliation. |
| `amount` | `Money` | Never null. Stored as `amount_minor bigint` + `currency char(3)`. |
| `platform_fee` | `Money` | Derived at creation from the fee policy, frozen at creation. |
| `net_amount` | `Money` | `amount − platform_fee`, frozen at creation. |
| `state` | `PaymentState` | See §1. |
| `failure_code` | `varchar(32)` | Set only on `FAILED`. |
| `provider` | `varchar(32)` | `SIMULATED_PSP` in v1. |
| `provider_transaction_id` | `varchar(64)` | Assigned when the payment is registered with the provider. |
| `captured_at` | `timestamptz` | Set on T3; the reconciliation scope index key. |
| `settled_at` | `timestamptz` | Set on T5. |
| `paid_out_at` | `timestamptz` | Set when a payout line covering this payment is executed. The T7 guard reads it. |
| `idempotency_key` | `varchar(255)` | The key that created the payment. |
| `created_at` / `updated_at` | `timestamptz` | |
| `version` | `bigint` | JPA `@Version` as a second line of defence behind `FOR UPDATE`. |

The link between a payment and its ledger postings lives in `payment_ledger_transactions`
(`role` ∈ `CAPTURE | REFUND | SETTLEMENT | ADJUSTMENT | REVERSAL`), not in a nullable column on
`payments`. A payment can have several postings over its life; a single
`capture_ledger_transaction_id` column would be a lossy model of that, and T6 needs to find the
capture posting to reverse it.

`platform_fee` and `net_amount` are frozen at creation, never recomputed later. If the fee
policy changes, existing payments are unaffected — which is exactly the class of bug that produces
the `AMOUNT_MISMATCH` in the demo, and the reconciliation case that finds it.

## 5. State history

`payment_state_history` is append-only:

| Column | Notes |
| --- | --- |
| `id` | ULID `psh_…` |
| `payment_id` | FK |
| `from_state`, `to_state` | `from_state` null for the initial row |
| `trigger_type` | `API` \| `WEBHOOK` \| `SETTLEMENT` \| `SYSTEM` |
| `trigger_ref` | Request id, provider event id, or settlement record id |
| `reason` | Human-readable, required for `FAILED` |
| `occurred_at` | UTC |
| `actor_type`, `actor_id` | |

Every transition appends exactly one row. There is no update path. A test asserts
`count(state_history) == transitions + 1` for a driven lifecycle.

## 6. Commands

All four are idempotency-key aware (`POST /payments/{id}/…`). See
[03-idempotency-and-webhooks](03-idempotency-and-webhooks.md).

### Create

```http
POST /api/v1/payments
Idempotency-Key: 9f1c…
Content-Type: application/json

{ "merchantReference": "MERCH-77", "amount": {"amountMinor": 10000, "currency": "EGP"},
  "description": "order 4471" }
```

Validation: currency supported; `amountMinor` > 0; `merchantReference` matches
`^[A-Za-z0-9_-]{1,64}$`; description ≤ 280 chars. **No idempotency key → `400 IDEMPOTENCY_KEY_REQUIRED`** —
this endpoint always requires one, because payment creation is the one operation where a silent
duplicate is unacceptable.

Registration with the PSP is asynchronous in v1: the payment is created with no
`provider_transaction_id`, and the webhook `payment.created` supplies it. This is realistic (you
do not get a provider id for free) and gives the first natural exercise of the out-of-order and
duplicate webhook paths.

### Authorize / capture / refund / fail

```http
POST /api/v1/payments/{id}/authorize
POST /api/v1/payments/{id}/capture     { "expectedAmount": {"amountMinor": 10000, "currency": "EGP"} }
POST /api/v1/payments/{id}/refund      { "reason": "customer request" }
POST /api/v1/payments/{id}/fail        { "failureCode": "INSUFFICIENT_FUNDS" }
```

`capture` carries `expectedAmount` and the service rejects with
`409 CAPTURE_AMOUNT_MISMATCH` if it does not equal the authorised amount. In v1 that can only
happen through a caller bug, and the check exists because in a real system that is exactly how a
partial capture is accidentally performed.

## 7. Reads

`GET /api/v1/payments/{id}` returns the payment plus, when present:

- `captureLedgerTransactionId` (derived from the `CAPTURE` link)
- `settlementRecordId` (if settled)
- `reconciliationSummary` — latest result reason, open case id

`GET /api/v1/payments/{id}/timeline` is the investigative endpoint: it merges the state history,
the ledger transactions for this payment, the provider events that mention it, and the
reconciliation results, ordered by time, each with its correlation id. It exists so that
investigating a discrepancy is a single HTTP call rather than four ad-hoc SQL queries — and it is
the endpoint the README's troubleshooting walkthrough screenshots.

## 8. Expiry

A `CREATED` or `AUTHORIZED` payment that is never captured must eventually be released. v1 keeps
this deliberately simple: there is an `expires_at` column and an expired payment is rejected at
capture with `410 PAYMENT_EXPIRED`. **A sweeper that auto-fails expired payments is not in v1**;
it is listed in the failure matrix as a known, documented limitation rather than silently ignored.