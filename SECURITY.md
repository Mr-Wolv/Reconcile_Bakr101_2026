# Security policy

## Scope

This repository is a portfolio piece: a payment and settlement reconciliation engine, built to
demonstrate that correctness, auditability and failure handling are the product rather than an
afterthought. It is **not a production payment system**, and this document says so plainly rather
than implying guarantees nobody has verified.

## Reporting a vulnerability

Open a **private security advisory** on this repository rather than a public issue. Do not open a
public issue, a pull request or a discussion for anything security-related.

Please include: what you found, how to reproduce it, and the impact you believe it has. A proof of
concept is worth more than a theoretical argument.

Expect acknowledgement within a few days. Fixes for confirmed issues are prioritised by blast radius
— anything that moves money or crosses an authorisation boundary first.

## What is real here, and what is not

**The authentication is a demonstration, not an identity system.** Two static bearer tokens, no user
store, no rotation, no OAuth, no MFA. `SecurityConfig`'s own javadoc calls it "not a production
authentication design". Tokens are compared by digest rather than by `equals`, so a rejection leaks
no timing information about how much was guessed — that part is deliberate — but the token *model*
is a placeholder.

Anyone who deploys this as-is is deploying a known, documented weakness. Do not.

**The shipped secrets are public and are not secrets.** `dev-operator-token`, `dev-admin-token` and
`dev-webhook-secret` are committed to this repository, in `application.yml` and
`docker-compose.yml`. They exist so the stack starts with no setup, and they are treated as
configuration rather than as credentials.

What stops them reaching a real environment is `assertNoDefaultSecretsInProduction`: the application
**refuses to start** when a default token or webhook secret is configured outside the `dev`/`test`
profile. It fails closed — any profile name it does not recognise counts as production. `SecurityConfigIT`
asserts all three branches of that guard.

If you run this anywhere real, set every one of them:

```
RECONCILE_OPERATOR_TOKEN
RECONCILE_ADMIN_TOKEN
RECONCILE_PSP_WEBHOOK_SECRET
```

**Money handling is integer-only and enforced by the database.** Amounts are `BIGINT` minor units
with a currency; there is no floating point anywhere in the money path. The ledger is append-only,
its invariants (L1–L10) are enforced by triggers rather than by application code alone, and
`LedgerInvariantsIT` corrupts balances deliberately to prove the triggers fire.

This is the part of the system that was built to be trusted with money. It is also the part most
worth attacking, and has had the least adversarial testing — see below.

## What has *not* been done

Stated explicitly, because the absence of a vulnerability report is not evidence of absence.

- **No penetration test.** Nobody has tried to break this on purpose.
- **No SAST.** There is no static analysis of the application code in CI.
- **No security review of the schema or the reconciliation logic.** The reconciliation engine's
  correctness properties are asserted by 45 golden subjects and unit tests; whether they can be
  subverted by crafted provider input has not been examined.
- **The dependency tree has now been checked against OSV, and the answer is in
  [`docs/security/dependency-findings.md`](docs/security/dependency-findings.md).** It started at 10
  known advisories; the three critical Tomcat ones are **fixed** by pinning `tomcat.version` to
  `11.0.26` (Decision D12), because Spring Boot 4.1.1 — the latest stable release — manages a Tomcat
  inside the affected range. **Seven Jackson advisories remain**, none reachable: four need
  polymorphic typing or `Path`/`Duration` DTO fields the codebase does not contain, and three are
  DoS issues bounded by the request-body limits already enforced. Those mitigations are
  configuration rather than code, so they can rot silently.
- **OWASP dependency-check has still never produced a report here.** It runs weekly in
  `.github/workflows/dependency-scan.yml` and fails at CVSS ≥ 7, but it needs the NVD dump, which
  this environment cannot reach. So shaded and nested dependencies remain unexamined; OSV is a
  second opinion, not the whole answer.
- **The webhook signature scheme** is HMAC with a replay window. It has not been reviewed for
  timing attacks, and the timestamp window is trusted from the client's clock.

## Known-by-design exposures

These are deliberate. They are listed so an auditor does not have to rediscover them:

| Exposure | Why |
| --- | --- |
| `GET /api/v1/health` is unauthenticated | A load balancer cannot present a credential. Returns invariant check *names*, not secrets. |
| `/actuator/health` and `/actuator/info` are unauthenticated | Same reason. `/actuator/env`, `/metrics`, `/beans` and `/configprops` are all refused — `/actuator/env` in particular would dump every configured secret. |
| The OpenAPI document requires a bearer token | It is a complete map of every route, parameter and failure code. It is authenticated like the API it describes. |
| Ports bind to loopback only | Docker's default publishes on every interface. `RECONCILE_DB_BIND` and `RECONCILE_APP_BIND` widen it deliberately. |
| The provider simulator is stateful and in-process | It is a test fixture standing in for an external system, and it is not a security boundary. |

## If you are evaluating this as a dependency

The honest summary: **the reconciliation and ledger logic is the substance and is built to be
audited; the authentication is a placeholder and the security tooling is configured but unproven.**
Judge it on the first, and read the second as a documented gap rather than an oversight.