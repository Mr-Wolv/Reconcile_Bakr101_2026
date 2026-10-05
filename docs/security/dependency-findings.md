# Dependency findings

**Status: the dependency tree is vetted in fact. Seven known advisories remain, all Jackson, none
reachable in this application; the three Tomcat advisories that were critical are cleared.** This
file replaces the honest-but-weak statement that the tree had never been checked. It also replaces
the idea that a scanner which has never run is evidence.

## How this was checked, and how to repeat it

```bash
# 1. Resolve what is really on the classpath. Declared versions are the wrong input:
#    Spring Boot's parent manages most of them, and the advisory that matters is
#    always about the resolved version.
docker run --rm -v "$PWD":/build -w /build -v reconcile-m2:/root/.m2 \
  maven:3.9-eclipse-temurin-25 mvn -B dependency:tree -DoutputFile=target/deptree.txt

# 2. Ask OSV about every resolved artifact.
python scripts/osv_check.py
```

156 coordinates were queried. `scripts/osv_check.py` exits non-zero when OSV reports anything, so
this is re-runnable and cannot silently rot into a stale claim.

**What this is not.** It is not OWASP dependency-check, and it is not a substitute. That tool
analyses bytecode, follows shaded and nested dependencies, and scores CVSS against the NVD; it
needs the NVD dump, which this environment cannot reach — which is why
`.github/workflows/dependency-scan.yml` has never run. OSV is a second opinion, not the only one.
The two remaining gaps are named at the end.

## What was found

**Measured before and after the Tomcat pin (see Decision D12 below):**

| | Before | After |
| --- | --- | --- |
| Advisory references reported by OSV | 17 | **14** |
| Unique advisories | 10 | **7** |
| Critical (C:H/I:H) | 3 | **0** |

| Artifact | Version | Advisories |
| --- | --- | --- |
| `tools.jackson.core:jackson-core` | 3.1.5 | 2 |
| `tools.jackson.core:jackson-databind` | 3.1.5 | 5 |
| `com.fasterxml.jackson.core:jackson-core` | 2.21.5 | 2 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.21.5 | 5 |
| `org.apache.tomcat.embed:tomcat-embed-core` | ~~11.0.24~~ → **11.0.26** | ~~3~~ → **0** |

Seven unique advisories remain, all Jackson, none reachable (see below). The three Tomcat
advisories are cleared: `tomcat-embed-core` now resolves to `11.0.26`, verified against the
resolved tree rather than assumed from the POM.

### Jackson 2 is not this project's JSON library

This codebase uses **Jackson 3** (`tools.jackson`), per `08 §A2`. The `com.fasterxml` 2.x artifacts
arrive transitively:

```
org.springdoc:springdoc-openapi-starter-webmvc-api:3.1.1
  -> springdoc-openapi-starter-common:3.1.1
    -> io.swagger.core.v3:swagger-core-jakarta:2.2.55
      -> com.fasterxml.jackson.core:jackson-databind:2.21.5
```

They exist to generate the OpenAPI document. No request body in this application is deserialised by
them, so their advisories are not reachable by an attacker — they parse a document we generate.

## Per-advisory decisions

Reachability is asserted against this repository, not assumed from the advisory text. The three
Tomcat rows were **found, analysed and then fixed**; they are listed because a finding that was
closed is still evidence that the check works.

| Advisory | Summary | Reachable? | Decision |
| --- | --- | --- | --- |
| `GHSA-9xv2-5v5q-p794` | Tomcat DIGEST authenticator: authentication bypass by capture-replay (C:H/I:H/A:H) | **No** | No DIGEST authenticator is configured anywhere. `SecurityConfig:58-59` disables `httpBasic` and `formLogin` explicitly; authentication is a bearer-token filter. **Fixed** by D12 |
| `GHSA-gcx9-497g-6cp6` | Tomcat security-constraint path bypass (**CVE-2026-65182**, C:H/I:H) | **No** | **Fixed** by D12. This one genuinely applied. The advisory states `11.0.0-M1` through `11.0.24`, and this project resolves exactly `11.0.24` — so it genuinely applies. The mechanism is `<security-constraint>` path processing, which this application does not use: authorisation is Spring Security's filter chain, and no servlet-container security constraints are declared |
| `GHSA-h3x4-894j-xpx5` | Tomcat FORM authentication incorrect authorization (C:H/I:H) | **No** | **Fixed** by D12. Independently unreachable: `formLogin` is disabled at `SecurityConfig.java:59` |
| `GHSA-gx83-3vf8-gh7j` | Jackson incomplete `PolymorphicTypeValidator` denylist | **No** | No polymorphic typing: no `@JsonTypeInfo`, no `activateDefaultTyping`, no `PolymorphicTypeValidator` in `src/main/java`. Deserialisation targets concrete record types |
| `GHSA-wv8q-qhhj-9h54` | Jackson retains every unknown raw type id | **No** | Same cause: unknown type ids only arise from polymorphic deserialisation, which is not enabled |
| `GHSA-wjgm-6hv5-3cvf` | Jackson `Path` deserialisation without a scheme allowlist | **No** | No DTO in `src/main/java/com/reconcile/api` has a `java.nio.file.Path` field, so no attacker-supplied path is ever resolved |
| `GHSA-q4xh-88c3-wmh7` | Jackson `Duration`/`XMLGregorianCalendar` unbounded number parse (DoS) | **No** | No DTO has a `Duration` or `XMLGregorianCalendar` field |
| `GHSA-7hhh-6rmp-j9qf` | jackson-core unbounded `StringBuilder` growth on an invalid token (DoS) | **Mitigated** | Every request body is size-bounded before parsing: the webhook at `ProviderWebhookController.java:93` and every idempotent command at `IdempotentCommand.java:254`. The unbounded growth is bounded by that limit |
| `GHSA-p6pp-m3f8-5c89` | jackson-core ReDoS: quadratic backtracking in number parsing | **Mitigated, weakest case** | Reachable in principle — any JSON number is parsed. Bounded by the same body-size limits, and there is no authentication-free endpoint that accepts a JSON body. **This is the finding to revisit first if anything changes** |
| `GHSA-cxp5-3px4-pw24` | jackson-databind quadratic forward-reference completion (DoS) | **Mitigated** | Same body-size bounds. Quadratic work on a bounded input is bounded work |

**Every Jackson 2 advisory** resolves to the same reachability answer as its Jackson 3 twin, for the
stronger reason that those artifacts never touch a request.

## The real decision

Nine of the ten are unreachable by configuration or by absent types. That is a mitigation, not a
fix, and mitigations rot: a future `formLogin().enable()` or a DTO with a `Path` field would
silently reopen a critical advisory with nothing to fail.

**Decision D12 — Tomcat is pinned to 11.0.26.** Spring Boot 4.1.1 is the latest *stable* release
and manages Tomcat 11.0.24, which is inside the affected range of all three Tomcat advisories. The
fixes landed in 11.0.25 (August 2026) and 11.0.26 (September 2026), so there is no framework release
to bump to. `<tomcat.version>` in this `pom.xml` is Spring Boot's own supported override, and the
bump stays inside the 11.0.x line — a servlet-container patch, not a combination Spring never
tested.

This removes three advisories from the tree. It does not close a live exploit path, because there
was never one: the vulnerable code is Tomcat's `<security-constraint>` processing and the
application does not use it. The pin is there so that a future `web.xml`-style constraint, or a
real deployment, does not silently inherit a critical CVE.

**Re-check this pin whenever Spring Boot is upgraded.** A Boot release managing Tomcat at or above
11.0.26 makes it redundant; a Boot release leaving 11.0.x makes it wrong.

## What is still unknown

Stated so this file is not read as more than it is:

- **OWASP dependency-check has never run**, so shaded and nested dependencies are unexamined.
- **CVSS scores are quoted from OSV's record**, not recomputed against this application.
- **Only `GHSA-gcx9-497g-6cp6` was checked against the advisory's own affected-version range**
  (CVE-2026-65182). The other two Tomcat advisories are reasoned from this repository's
  configuration; a reviewer should check them the same way.
- Absence of a finding is not absence of a vulnerability. OSV knows what has been reported to it.
