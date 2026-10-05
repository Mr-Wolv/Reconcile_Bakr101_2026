# Roadmap

What this repository deliberately does not do yet — why, what it would take, and what would make it
worth starting.

**This is not a wish list.** Every entry below names a specific blocker, a scoped piece of work, and
something it would prove that the current baseline cannot. An entry that could not name all three
would not be here: a roadmap of aspirations is a list of things nobody has thought about.

The baseline is the thing that ships. This is the plan for what comes after it, and keeping the two
apart is the point — a repository that only ever describes itself is a snapshot, and a repository
that only ever describes its future is a proposal.

---

## 1. OWASP dependency-check against the NVD

**Status:** workflow written and corrected; blocked on one human action.

### Why it is deferred

It needs an `NVD_API_KEY`, which is issued against a person's email by NIST and is therefore not
something a repository can create for itself.

The scale is what makes it worth deferring rather than working around. A cold sync of NVD API 2.0
is roughly 250k requests: **about seventeen days** on the anonymous endpoint's five-per-thirty-
seconds, and about a day and a half even with a key. Any claim that this scan "takes minutes" is
wrong on a cold cache, and an earlier version of `dependency-scan.yml` made exactly that claim with
a 45-minute timeout — a job that could not have succeeded. Both the timeout (now 300 minutes) and
the NVD store cache have since been fixed, so what remains is the secret and nothing else.

### What is already covered

The gap is smaller than it looks, because two keyless scanners now gate every push and every pull
request:

| Source | Database | Needs | Covers |
| --- | --- | --- | --- |
| `scripts/osv_check.py` | OSV | nothing | the exact resolved tree, on a triaged baseline |
| `actions/dependency-review-action` | GitHub Advisory | nothing | dependencies a pull request *adds* |
| `dependency-scan.yml` | NVD | `NVD_API_KEY` | **bytecode** — shaded and repackaged artifacts |

The first two query a database by coordinate. Only the third reads the class files, so it is the only
one that catches a jar whose coordinate is innocent and whose contents are not. That is a real blind
spot and it is why this stays on the list rather than being deleted.

### What it would take

A free API key, entered once as a repository secret. The workflow, the cache and the failure gate
are already written.

### What it would prove

That the *shipped bytes* are clean, not merely that the declared coordinates are. For a payment
engine, that is the difference between "we know our dependencies" and "we know what we are running".

---

## 2. Multi-provider

**Status:** out of scope for the baseline; the schema is already shaped for it.

### Why it is deferred

It is a second vertical slice, not a fix, and its test value is weaker than it first appears.

The database is *already* multi-provider — `provider_events` is keyed `UNIQUE (provider, event_id)`,
`provider_transactions` on `(provider, provider_transaction_id)`, `payments` carries `provider` with
`provider_transaction_id`. That work is done. What is missing is **identity**: establishing which
provider a request came from.

It cannot come from the request body. A holder of one PSP's webhook secret could file events under
another provider's name, which is precisely why `ProviderWebhookController` pins `SIMULATED_PSP`. The
defensible design is a path parameter — `/api/v1/provider/{provider}/webhooks` — with the signature
secret looked up from a registry by that segment. It is a small change and a good one.

The rest is not small:

- a `provider_registry` (code, signature scheme, secret reference, enabled);
- per-provider secret configuration, replacing the single `reconcile.provider.webhook-secret`;
- **a per-provider payload mapper**, and this is the real cost — Adyen sends
  `eventCode` / `amount.value` / `currency`, this project sends `type` / `gross.amountMinor`. The
  vocabularies do not line up, so the flat `ProviderEventPayload` that is simple *because* there is
  one provider stops being simple;
- the same treatment for the outbound `/provider/sync` pull;
- a second simulator.

### The honest objection

Proving multi-provider against two simulators we wrote ourselves is not proof against a real PSP. It
demonstrates the abstraction and nothing more. That is worth something, and it is worth less than it
looks.

### What would make it worth starting

A second provider with a public sandbox — Adyen's test account or Stripe's test mode — so the mapper
is written against somebody else's documentation rather than against our own assumptions. Without
that, this is refactoring for its own sake.

### The cheap 80/20

The inbound path-parameter change alone, with one registered provider. It replaces "the provider is a
constant" with "the provider comes from the URL and the secret is looked up by it" — a strictly
better security property for perhaps a day of work, extending `E2E-SEC-01`'s seven-route walk to
cover it. It touches authentication, so it wants its own commit.

---

## 3. Modelling refunds on reconciliation's expected side

**Status:** a real, discovered limitation. Not a bug — a deliberate decision with a known cost.

### What was found

While writing the ordering tests, `C-ORD-03` produced a batch that reported `STATUS_MISMATCH` for a
payment both sides agreed was `REFUNDED`. Investigating it found the cause in
`ReconciliationService.expectedView`:

```java
settled ? PaymentState.SETTLED : PaymentState.CAPTURED
```

The expected side is derived from the capture posting, which a reversal cancels — and it never says
`REFUNDED` at all. So every refunded payment reconciles as a discrepancy, always, by construction.

### Why that is currently correct

Spec 04 lists `SETTLED` vs `REFUNDED → STATUS_MISMATCH` as `C-RECON-03`'s documented behaviour, and
the asymmetry is defensible: our side is derived from the ledger, and this engine does not model a
refund on its own side, so it reports the disagreement rather than assuming the provider is wrong. A
reconciliation engine that quietly agreed with itself would be worse.

`C-ORD-03` therefore asserts *order-independence* — that every ordering yields the same verdict —
rather than asserting the batch is clean. That is the property this class actually owns.

### What it would take

Deciding whether a refunded payment is an expected discrepancy or a bug. If it is a gap: a reverse
leg in `expectedView`, a `REFUNDED` expected status, a definition of what a refund does to
`settled_at` and `settlement_record_id`, and a decision about partially-settled-then-refunded.

### What it would prove

That reconciliation can answer "is this right?" about a payment whose money came back, rather than
only "did the two sides agree?" about one whose money stayed.

---

## 4. Pinning GitHub Actions to commit SHAs

**Status:** done.

Every `uses:` in every workflow is pinned to a full commit SHA with the tag in a trailing comment,
so the pin is reviewable and Dependabot can still see what it is upgrading. The previous state of
this document claimed the work was unstarted; that was stale, not aspirational — the workflows
were already pinned and the roadmap was simply not updated.

The original blocker was that no SHA could be verified offline, which turned out to be wrong: the
repository has outbound HTTPS, and the GitHub API serves public repositories unauthenticated.

### What it costs

The pins need periodic refreshes as upstream tags move. That is the intended trade — an unpinned
tag is a remote-code-execution vector chosen by whoever can move it — and Dependabot handles the
refresh rather than a human.

---

## 5. Deepening the security testing

`security.yml` now runs Semgrep, Scorecard, ZAP baseline and the two dependency scanners. Three
things that would go further:

- **An authenticated ZAP scan.** The baseline scan is anonymous, so every finding is really "this
  endpoint requires authentication", which is correct behaviour reported as a finding. An
  authenticated scan with a real operator token is the one that finds something new.
- **OWASP ZAP against a running stack with the operator role**, exercising the authorisation matrix
  rather than just the token check.
- **A deliberate hostile-input suite** — replayed webhooks at volume, malformed `jsonb`, a payment
  referencing itself. Some of this exists; none of it is systematic.

None of these needs a secret or a network that this repository cannot reach.

---

## 6. What is *not* on this list, and why

- **A real payment provider.** The README is explicit that this processes no real money and
  integrates no real PSP, and that claim is worth more than the integration would be. Item 2 is the
  honest prerequisite; this would follow it.
- **PCI DSS, KYC/AML, regulatory compliance.** Same reason, and a portfolio piece that implied any of
  them would be lying.
- **Microservice decomposition.** The boundaries are already right — a modular monolith is the
  correct shape at this scale, and splitting it would add a network failure mode to a system whose
  value is that it has none.

---

## The pattern in this list

Three of these five are blocked on something outside the code — a key, a sandbox account, a network
call. That is worth saying plainly: the remaining distance between this baseline and a more complete
system is not mostly engineering. It is mostly access.