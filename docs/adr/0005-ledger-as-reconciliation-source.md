# ADR-0005 — Reconcile against the ledger, not against the payment row

**Status:** Accepted · 2026-10-04

## Context

The obvious way to reconcile is to compare two like-for-like records:

```
compare  payments.amount / payments.currency / payments.state
against  provider_transactions.gross / .currency / .status
```

It is simple, it is readable, and it has a failure mode that makes the whole system worthless.

Consider the bug where a capture posts the wrong amount. Two things happen: the ledger records the
wrong value, **and** the payment row is updated from that same wrong value. The reconciliation
query reads the payment row, the provider says 100.00, the payment row says 97.00… except it
does not, because both sides of the bug wrote the same wrong number to both tables. The
reconciliation passes. The discrepancy is never detected. It is found by an auditor, months later,
by accident.

The bug does not have to be exotic. A rounding change, a currency mix-up, a fee policy applied at
the wrong moment, a retry that re-derived the amount from a mutable source — all of them write to
both places, because the payment row and the ledger entry are written by the same command.

## Decision

**The internal side of every comparison is derived from the ledger.** The expected provider view is
computed from the capture transaction's entries, read **by account code**:

| Expected field | Derived from |
| --- | --- |
| `grossAmount` | Σ entries on `PSP_CLEARING` in the capture transaction |
| `feeAmount` | Σ entries on `PLATFORM_FEE_REVENUE` |
| `netAmount` | Σ entries on `MERCHANT_PAYABLE` |
| `currency` | the ledger transaction's currency column |
| `status` | payment state, but only for presence (`SETTLED` iff a settlement record line covers it) |
| `merchantReference` | `payments.merchant_reference` — the one field with no ledger equivalent |

The consequences are worth stating:

- The comparison is between two **independent sources**: our ledger, and an external provider we do
  not control. That is what makes agreement meaningful.
- A bug that corrupts the payment row alone now *produces* a discrepancy instead of hiding one.
- The fee split becomes a ledger concern. That is where it belongs: the split is a fact about
  money, not about the payment's status.
- Reconciliation cannot be fooled by a bug that touched one table.

## Consequences

**Positive**

- Reconciliation genuinely detects corruption. The test that proves this is
  `E2E-DEMO-02`, and the sharper version is a definition-of-done item: corrupting
  `payments.amount` must make reconciliation **fail**, not pass. A project whose reconciliation
  cannot fail is theatre.
- The ledger becomes unambiguously the source of truth for money, which is the claim the project
  wants to make anyway. This ADR is where that claim is earned rather than asserted.
- Comparing gross / fee / net as three separate legs means a provider fee discrepancy is reported
  as `AMOUNT_MISMATCH` with `component = FEE`, instead of being lost inside a net-amount
  difference.

**Negative, and accepted**

- More expensive: a join over `ledger_entries` per subject instead of a single column read. Mitigated
  by `ix_entries_txn`, and irrelevant at the scale of a settlement batch.
- The expected view is a query rather than a field, so it is slightly harder to reason about in a
  debugger.
- `merchantReference` is the single place where the comparison is not ledger-derived. That is
  honest — reference data has no ledger representation — and it is called out rather than hidden.

## What payouts do not change here

A `MERCHANT_PAYOUT` posting does not alter the expected provider view, because a payout moves
money the provider has already paid us — it changes no captured, settled, or net figure the
provider ever reported. Including it in the comparison would invent a disagreement that does not
exist. Payout is verified by the ledger, not by the provider.

## Related decisions

Identity matching is equally conservative: exact equality on `(provider,
external_transaction_id)`, with **no** amount-based or fuzzy fallback.

Fuzzy matching would raise the match rate and lower the correctness of every match it produces. A
reconciliation system that guesses is worse than one that says `AMBIGUOUS_MATCH` and opens a case,
because the guess is invisible and the failure is not.