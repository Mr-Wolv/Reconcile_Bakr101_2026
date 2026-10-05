# Releasing Reconcile

This file defines what a release **is** for this repository, and the order the steps are performed
in. It is written for a person, because every step that touches the outside world — committing,
tagging, pushing, publishing — is a decision a person makes and a bot should not make alone.

## What a release means here

`v1.0.0` means: the whole suite is green on the exact tree that carries the tag, the HTTP smoke
test passes against the image built from that tree, and every claim in the
[README](README.md) and the [changelog](CHANGELOG.md) is backed by a test in that tree.

It does **not** mean the software is production-ready, regulated, audited, or safe to handle real
money. See the README's opening section: this is a systems-engineering simulation, and no such
claim may be added anywhere in this repository.

## Before tagging

Run these in order. Each one has caught something the previous one could not.

```bash
# 1. The full gate. This deletes target/ first, because mvn verify alone can report BUILD SUCCESS
#    on source that does not compile — see the README's defect table. Expect roughly 7–8 minutes
#    (measured 7:07-7:29 across runs; it was 13:17 before the integration tests shared one
#    database). It also checks the workflow definitions, which no other command here reaches.
./scripts/verify.sh

# 2. Both suites must have run, not just passed. A build that silently skipped integration tests
#    reports success; this checks the reports rather than the log.
python scripts/check_suites.py

# 3. The image, not the jar: build it, start it, and walk the HTTP surface.
docker compose up -d --build app
bash scripts/probe.sh        # 33 assertions, re-runnable against a dirty database.
                            # Waits for the container to become healthy first, so the two
                            # lines above are the whole step and can be run back to back.

# 4. The dependency tree, against OSV. Fails only on an advisory that is not in
#    docs/security/osv-baseline.json, so this is a check and not a re-reading of decisions
#    already made. verify.sh deletes target/ first, so the resolved tree is written here rather
#    than assumed to survive from step 1.
MSYS_NO_PATHCONV=1 docker run --rm --mount "type=bind,src=$PWD,dst=/build" -w /build \
  -v reconcile-m2:/root/.m2 maven:3.9-eclipse-temurin-25 \
  mvn -B -ntp dependency:tree -DoutputFile=target/deptree.txt
python scripts/osv_check.py
```

All four must exit `0`. If the probe fails, `docker compose logs app` before changing anything —
the failure is usually a real defect, and the probe exists because three previous ones were.

## Then

```bash
# 5. Confirm the working tree is exactly what you intend to release.
git status --short
git diff --stat

# 6. Commit, tag, push. All three are the operator's call and none of them is automated here.
git commit -m "..."
git tag -a v1.0.0 -m "Reconcile 1.0.0"
git push origin main --follow-tags
```

Pushing the tag triggers [`.github/workflows/release.yml`](.github/workflows/release.yml), which
re-runs the full gate against the tagged tree. **This has never executed** — see the known
limitations in the README — so treat the first run as unproven rather than as a formality.

## Publishing an image

Deliberately not automated. The release workflow builds and verifies the image; pushing it to a
registry creates an artifact that outlives this repository, so it is a human decision:

```bash
docker build -t reconcile:1.0.0 .
docker buildx build --platform linux/amd64,linux/arm64 -t <registry>/reconcile:1.0.0 --push .
```

## After tagging

- If the tagged commit's suite is red, move the tag. Do not leave a red tag reachable — a version
  number is a promise that a specific tree passed.
- Update the measured counts in the README from the CI run, not from this file. This file
  deliberately contains no counts, so it cannot go stale against a build.

## Version numbering

`MAJOR.MINOR.PATCH`, with `MAJOR` meaning the ledger's invariants changed, `MINOR` meaning the API
contract in [`docs/openapi/reconcile-v1.yaml`](docs/openapi/reconcile-v1.yaml) changed, and
`PATCH` meaning neither.

There is no automated compatibility check between a tag and the previous OpenAPI document. That is
a known gap: a `PATCH` that quietly breaks a response field would be caught by the contract test
only if the committed document were updated in the same commit, which is a review question rather
than a machine one.
