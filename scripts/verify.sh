#!/usr/bin/env bash
#
# The build runs only in Docker (decision D11): the project targets Java 25 and the local JDK is 21.
#
#   ./scripts/verify.sh                  # everything: unit tests, integration tests, the suite check
#   ./scripts/verify.sh -Dit.test=ApiHttpIT -Dtest=MoneyTest -DfailIfNoTests=false   # a subset
#
# It also runs `scripts/check_workflows.py` before Maven, because the workflows are the one file
# class here that `mvn verify` cannot reach and that no local command has ever exercised, and
# `scripts/check_suites.py` after Maven, because a green exit status is not the same thing as tests
# having run.
#
# Every check here is REQUIRED. A check that cannot run is a failing check, not a skipped one.
#
# Any extra arguments are passed straight to Maven, so a subset of the suite is one flag away.
#
# WHY THIS SCRIPT EXISTS - a defect that shipped with a green build
# ------------------------------------------------------------------
# `mvn verify` on its own is NOT trustworthy in this environment. The repository is bind-mounted
# from a Windows filesystem into the container, and that mount does not update modification times
# reliably. Maven's compiler plugin decides what to recompile by comparing source and class
# timestamps, so a file edited seconds after the last build looks unchanged. The result:
#
#   1. javac is never invoked on the edited file, and
#   2. the build reports BUILD SUCCESS while target/classes still holds the previous version.
#
# Measured, not theorised: introducing a deliberate `ServletRequest` -> `HttpServletRequest` type
# mismatch into IdempotentCommand.java and running `mvn -o test-compile` printed "BUILD SUCCESS"
# with no "Compiling" line at all, and the next test run failed at runtime with
# `java.lang.Error: Unresolved compilation problem` from inside a supposedly compiled class.
#
# Deleting target/ first removes the timestamps from the decision, so javac always sees every
# source file. It costs a full recompile and buys a build that means what it says. CI is not
# affected (a fresh git checkout has correct timestamps), but the local loop is where a green
# result gets believed, so the local loop is where this has to be enforced.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

echo "==> checking the GitHub Actions workflows"
#
# They parse, and nothing here can execute them, so the honest claim is a static one and this is
# what makes it. It catches the failure mode a YAML parser cannot see: a `run:` block whose shell
# does not parse.
#
# The interpreter is *tested*, not merely located. On Windows `python3` resolves to the Microsoft
# Store stub, which exists on PATH and then refuses to run - so `command -v` alone would find a
# python that cannot do anything, and `set -e` would turn that into a build failure that reads as
# a broken workflow.
PYTHON=""
for candidate in python3 python; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c "import yaml" >/dev/null 2>&1; then
    PYTHON="$candidate"
    break
  fi
done
if [ -z "$PYTHON" ]; then
  # F-09. This branch used to print an error, say "this is a skipped check, not a passing one", and
  # carry on to Maven — so a caller trusting only the exit status got 0 from a run in which a
  # required check never executed. A gate that can be bypassed by uninstalling its own dependency
  # is not a gate. `set -e` does not help here, because the failure is a *missing* check rather than
  # a failing one, and only this branch knows that.
  echo "::error::no python with PyYAML was found, so the workflow check cannot run"
  echo "::error::this is a FAILED verification, not a passing one. Install PyYAML and re-run:"
  echo "::error::    python -m pip install pyyaml"
  exit 1
fi
"$PYTHON" scripts/check_workflows.py

echo "==> removing target/ so no stale class can survive"
rm -rf target

echo "==> mvn -B verify $*"
MSYS_NO_PATHCONV=1 docker run --rm \
  --mount "type=bind,src=${REPO_ROOT},dst=/build" -w /build \
  -v reconcile-m2:/root/.m2 -v //var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_RYUK_DISABLED=true \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  maven:3.9-eclipse-temurin-25 mvn -B verify "$@"

echo "==> checking that both suites actually ran"
# F-09, second half. `mvn verify` exits 0 for a build in which no integration test was selected,
# which is exactly what happens when someone passes -Dit.test=... on the command line for a local
# subset. That is legitimate for a subset run and must not be reported as a full gate, so the
# suite check is the thing that says which of the two happened.
#
# check_suites.py reads the Surefire and Failsafe reports themselves and fails when a suite is
# missing, empty, or contains a failure — none of which Maven's exit status covers.
"$PYTHON" scripts/check_suites.py

echo "==> checking the documented inventory against the tree"
# D-01. The README and the test matrix quote exact counts. They were wrong, and hand-maintained
# counts stay wrong because nothing forces anyone to update them. inventory.py recomputes the
# figures, parses them back out of the documents, and fails on any disagreement — so a stale claim
# becomes a build failure rather than a false statement in a handoff document.
"$PYTHON" scripts/inventory.py --check

echo "==> exercising the gate parsers against their own fixtures"
# F-11 follow-on. dependency_check_summary.py replaced an inline heredoc that read a JSON path
# Dependency-Check never emits, so it reported "0 vulnerable dependencies" on a full report and
# exited 0. Replacing an inline parser with a checked-in one is only better than the original if
# the checked-in one is tested somewhere it can actually run — here and on every push — rather than
# only inside a scheduled job that will not fire for days.
"$PYTHON" scripts/test_dependency_check_summary.py

echo "==> verification complete: workflows parsed, both suites executed and checked, documents in sync, parsers tested"