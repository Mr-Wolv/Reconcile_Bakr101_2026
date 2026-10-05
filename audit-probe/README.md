# `audit-probe` — adversarial probes, and what happened to the old ones

Every file here is a **probe**, not a test. Probes are not wired into CI, they are not run by
`scripts/verify.sh`, and nothing in the Maven suite depends on them. They exist so that a claim
about the running system can be attacked over its real interface — HTTP or SQL — by something that
does not share code or assumptions with the thing under test.

A probe that cannot fail is useless. A probe that **reliably fails for the wrong reason** is worse
than useless: it produces output a reviewer has to re-derive by hand, and if its failures are mixed
with real ones, its passes cannot be trusted either.

## Live

| File | What it attacks | How it reports |
| --- | --- | --- |
| [`vv-ledger-invariants.sql`](vv-ledger-invariants.sql) | L1/L2/L3/L4/L6/L10 — whether the **database** refuses illegal ledger writes, against raw SQL with no application in the path | `psql`; asserts over resulting **state**, not captured error text; nonzero exit if any attack survived or any legal shape was refused |
| [`vv-remediation.mjs`](vv-remediation.mjs) | F-01, F-02, F-04, F-05, F-06, F-07 — the remediated findings, over a real socket including a raw chunked request | `node`; prints PASS/FAIL per assertion; `process.exit(1)` on any failure |

Both are **valid and useful**, and both are backed by equivalent regression tests in the Maven
suite. The tests are the gate; the probes are the independent confirmation that the gate is not
merely agreeing with itself.

## What `vv-remediation.mjs` actually earned its keep for

It was written to re-confirm six fixes. It found **two new defects that the entire Maven suite
could not see**, both introduced while fixing F-01/F-02:

1. **`ReconciliationOutcome.SETTLEMENT_RECORD_INVALID` violated a `CHECK` constraint.** The enum
   grew; the schema did not. Every batch containing the new verdict was refused at `INSERT` and
   marked `FAILED` with `processed_subjects = 0` — the engine stopped reconciling precisely when it
   found something. Unit tests exercise the pure classifier and so never touch the constraint.
2. **A merchant reference containing a newline killed every batch in its window.** `differencesJson()`
   hand-built JSONB; the escaper handled backslash and quote but not control characters, which JSON
   forbids. Provider-controlled text, provider-triggered denial of service.

Both are now fixed, both have regression tests that fail against the old code, and the probe is
green at 19/19. The general lesson is recorded in `README.md`: **a fix that is correct in the Java
and wrong against the schema it persists into is invisible to every test that stops at the enum.**

### Getting this probe to run at all was itself the work

It is worth recording, because it is the exact failure mode this directory exists to remove. The
first version of the file asserted against a contract that does not exist, in four separate ways:

| It did | Reality |
| --- | --- |
| created the payment first, then read `providerTransactionId` off it | the create response never carries that field; the **simulator** assigns it and the payment is created against it |
| posted the registration to `/api/v1/provider/simulator/payments` | the controller is mapped at `/api/v1/provider` |
| read results from `GET /batches/{id}.results` | `GET /batches/{id}` has no `results` member; results are at `GET /batches/{id}/results` |
| "drained" the inbox by polling until the backlog endpoint answered `200` | that is true within milliseconds of startup, so it drained nothing and every later assertion was a race |

Every one of these produced a confident-looking failure and none of them was about the product.
The rule is the one at the bottom of this file, applied to the new file as well as the archived
ones: **a probe must assert the corrected contract or assert nothing.**

## Archived

These are kept for history and are **not evidence**. They are not run by anything. Do not cite
their output.

| File | Why it was archived |
| --- | --- |
| `archive/vv-api.mjs` | Expected account entries as a bare array where the contract specifies a page object, and expected a plural audit entity type `payments` where the contract specifies singular `payment`. Eight reported failures, all the probe's fault. |
| `archive/vv-new-endpoints.mjs` | Seeded data through a `POST /ledger/transactions` route that does not exist, and requested `limit=500` while separately asserting that a limit above 200 is rejected — contradicting its own constraint. |
| `archive/recon-crash.mjs` | Asserted a reconciliation batch would fail under a condition the system now handles. The assertion was stale; the "no scheduler" comment in it had already been overtaken. A probe asserting a failure that no longer happens teaches a reviewer to distrust the product. |
| `archive/vv-post-remediation.sql` | Ran with psql error-stop disabled, so expected constraint violations and unexpected SQL failures could coexist with exit 0 and the output needed human interpretation. Its replacement is `vv-ledger-invariants.sql`, which derives its verdict from observed state. |
| `archive/async-batch.mjs` | Superseded: its scenario is covered end-to-end by `vv-remediation.mjs` and by the integration suite. |
| `archive/vv-l2-currency.sql` | Narrow single-invariant probe. Its coverage is inside `vv-ledger-invariants.sql` section A4, and keeping a second copy of a currency check is a second thing to keep true. |
| `archive/vv-l8-l11.sql` | Same: superseded by `vv-ledger-invariants.sql`, which now also covers the L3/L10 cases this one missed. |

### What this archive is and is not

It is a record of what was tried and why the attempt was not sound. It is **not** evidence that
any claim held, and running any of it now should be expected to produce failures for reasons
unrelated to the product.

The rule that produced this outcome, applied to the live files too: **a probe must either assert
the corrected contract or assert nothing.** "It fails, but the failure is the probe's fault" is not
a category anything should be filed under for long.