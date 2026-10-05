#!/usr/bin/env python
"""Query OSV for known vulnerabilities in this project's resolved dependencies.

WHY THIS EXISTS
---------------
The repository ships `.github/workflows/dependency-scan.yml`, which runs OWASP
dependency-check against the NVD. That workflow has never executed, because this
environment has no route to the advisory databases it depends on. A scanner that has
never run is not evidence.

This script is the other half of that answer: it asks the OSV database — a public,
aggregated source that needs no account and no API key — what it actually knows about
the exact artifacts the build resolves. It is not a replacement for dependency-check.
It is a check that can run today, on the versions that are really on the classpath,
and it is re-runnable so the answer can be refreshed rather than remembered.

WHAT IT DOES NOT DO
-------------------
It does not decide whether a finding matters. It reports what OSV returns and exits
non-zero when there is anything to look at. Whether a finding is exploitable in a
simulation that never moves money is a judgement for a person, recorded in
`docs/security/dependency-findings.md`.

Usage:
    python scripts/osv_check.py                    # query, report, non-zero on findings
    python scripts/osv_check.py --allow-findings   # query, report, always exit 0
    python scripts/osv_check.py --write-baseline docs/security/osv-baseline.json

WHY A BASELINE
--------------
Without one, this scan can only ever be red. The tree has accepted advisories - seven, all
Jackson, none reachable - and a check that reports the same ten findings on every run teaches
nobody to read it. The baseline file holds the advisory ids somebody has actually triaged and
written down in docs/security/dependency-findings.md; the run fails only on ids that are absent
from it. That inverts the default correctly: a known, decided finding does not block anything, and
the first genuinely new advisory stops the build on the run it appears.

The baseline is written by hand, once, with --write-baseline, immediately after a human records
a decision. It is never written by CI.
"""

import argparse
import json
import pathlib
import re
import sys
import urllib.error
import urllib.request

TREE = pathlib.Path("target/deptree.txt")
DEFAULT_BASELINE = pathlib.Path("docs/security/osv-baseline.json")
OSV_BATCH = "https://api.osv.dev/v1/querybatch"
OSV_VULN = "https://api.osv.dev/v1/vulns/"

# groupId:artifactId:jar:version — the shape Maven writes. Matched anywhere in the line
# rather than after a log prefix: `dependency:tree` writes this file without one when
# -DoutputFile is used, and an anchored pattern would silently match nothing.
COORDINATE = re.compile(
    r"([A-Za-z0-9._-]+):([A-Za-z0-9._-]+):(?:jar|pom):([A-Za-z0-9._-]+)"
)


def coordinates():
    if not TREE.exists():
        sys.exit(
            f"{TREE} not found. Generate it first:\n"
            "  docker run --rm -v \"$PWD\":/build -w /build -v reconcile-m2:/root/.m2 \\\n"
            "    maven:3.9-eclipse-temurin-25 mvn -B dependency:tree "
            "-DoutputFile=target/deptree.txt"
        )
    found = {}
    for group, artifact, version in COORDINATE.findall(TREE.read_text(encoding="utf-8")):
        found[f"{group}:{artifact}"] = version
    return sorted(found.items())


def post(url, payload):
    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


def query_osv(packages):
    queries = [
        {"package": {"name": name, "ecosystem": "Maven"}, "version": version}
        for name, version in packages
    ]
    try:
        response = post(OSV_BATCH, {"queries": queries})
    except (urllib.error.URLError, TimeoutError) as error:
        sys.exit(
            f"OSV is unreachable from here ({error}).\n"
            "That is the same limitation that stops dependency-check running, and it is\n"
            "why this script exists: it needs a reachable public API, not the NVD dump.\n"
            "Re-run somewhere with outbound HTTPS."
        )

    if "results" not in response:
        sys.exit(f"OSV answered without a 'results' key: {response}")
    return response["results"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--allow-findings", action="store_true",
                        help="report, but always exit 0")
    parser.add_argument(
        "--baseline",
        help=(
            "Path to a JSON list of advisory ids that have already been triaged and recorded in "
            f"docs/security/dependency-findings.md. Findings in it are reported as known and do "
            f"not fail the run; anything absent fails. Defaults to {DEFAULT_BASELINE} when that "
            f"file exists."
        ),
    )
    parser.add_argument(
        "--write-baseline",
        help=(
            "Write the current findings to this path as the accepted baseline, and exit 0. Used "
            "to record a triage decision, never to silence a finding nobody looked at - the "
            "point of the file is that a name in it means somebody read it."
        ),
    )
    arguments = parser.parse_args()

    packages = coordinates()
    print(f"Resolved dependencies queried: {len(packages)}")
    print(f"Source: {OSV_BATCH}")
    print()

    results = query_osv(packages)
    findings = []
    for (name, version), result in zip(packages, results):
        for vulnerability in result.get("vulns", []):
            findings.append((name, version, vulnerability["id"]))

    if not findings:
        print("OSV reports no known vulnerabilities for any resolved dependency.")
        return 0

    if arguments.write_baseline:
        write_baseline(arguments.write_baseline, findings)
        return 0

    baseline_path = arguments.baseline or (DEFAULT_BASELINE if DEFAULT_BASELINE.exists() else None)
    accepted = load_baseline(baseline_path)

    print(f"OSV reported {len(findings)} advisory reference(s):\n")
    for name, version, identifier in sorted(findings):
        state = "known " if identifier in accepted else "NEW   "
        print(f"  [{state}] {identifier}  {name}:{version}")
        try:
            detail = json.loads(
                urllib.request.urlopen(OSV_VULN + identifier, timeout=30).read().decode("utf-8")
            )
            summary = (detail.get("summary") or detail.get("details") or "")[:300]
            if summary:
                print(f"      {summary.strip()}")
            for severity in detail.get("severity", []) or []:
                print(f"      severity: {severity.get('type')} {severity.get('score')}")
        except (urllib.error.URLError, TimeoutError):
            print("      (advisory detail unavailable)")

    untriaged = sorted({identifier for _, _, identifier in findings} - set(accepted))
    if accepted:
        print(f"\n{len(set(accepted))} advisories are recorded in {baseline_path}; "
              f"{len(untriaged)} are new.")
    if untriaged:
        print("\nThese are not in the accepted baseline. Record each in")
        print("docs/security/dependency-findings.md with a decision - fix, accept with a reason,")
        print("or suppress with a reason - then re-run with --write-baseline. An undecided")
        print("finding is not a decision.")
    if arguments.allow_findings:
        return 0
    return 1 if untriaged or not accepted else 0


def load_baseline(path):
    """Advisory ids somebody has already triaged. An absent file means none."""
    if not path:
        return []
    file = pathlib.Path(path)
    if not file.exists():
        print(f"No baseline at {file}; every finding counts as new.", file=sys.stderr)
        return []
    try:
        return json.loads(file.read_text(encoding="utf-8"))
    except json.JSONDecodeError as broken:
        sys.exit(f"{file} is not valid JSON - refusing to guess which findings were accepted")


def write_baseline(path, findings):
    """Records the triage decision. Only ever called deliberately."""
    ids = sorted({identifier for _, _, identifier in findings})
    file = pathlib.Path(path)
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text(json.dumps(ids, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {len(ids)} accepted advisories to {file}.")
    print("Each one now needs a line in docs/security/dependency-findings.md explaining why it")
    print("is accepted. A baseline entry without a written reason is a silenced finding.")


if __name__ == "__main__":
    sys.exit(main())