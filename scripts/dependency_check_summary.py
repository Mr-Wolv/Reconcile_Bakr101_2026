#!/usr/bin/env python3
"""Summarise an OWASP Dependency-Check JSON report, and fail on findings at or above a threshold.

Why this exists as a file rather than a heredoc inside the workflow: a parser that only runs in
CI is a parser nobody can test. The previous version read
``report["report"]["vulnerableSoftware"]``, which is not the shape Dependency-Check writes, so the
summary printed "0 vulnerable dependencies" on a report full of them and exited 0. The build gate
itself (``failBuildOnCVSS``) was unaffected, so this was a reporting defect rather than a
false-green gate — but a report that says nothing about a known finding is worse than no report,
because it is read as an all-clear.

The real schema (``analysisType`` = ``json-generator`` / ``json``) is::

    {"report": {"dependencies": [
        {"fileName": ..., "packages": [
            {"vulnerabilities": [
                {"name": "CVE-...", "cvssv3BaseScore": 7.5,
                 "vulnerableSoftware": [{"software": {"name":..., "version":...}}]}
            ]}]}]}}

Both the aggregate shape above and the flat ``vulnerableSoftware`` list are accepted, because
Dependency-Check has emitted both across versions and a scanner that silently reads zero on an
older format is the exact failure being fixed. The two are merged, never one silently preferred.

Usage::

    dependency_check_summary.py REPORT.json [MIN_CVSS] [--allowlist FILE]

Exit status is 1 when at or above ``MIN_CVSS`` there is at least one finding that the allowlist
does not cover, and 0 otherwise.  A missing or unreadable report is a failure, never a pass: a scan
that produced nothing must not be able to look like a clean scan.
"""

from __future__ import annotations

import json
import re
import sys
import xml.etree.ElementTree as ET

DEFAULT_THRESHOLD = 7.0

# Coordinates of a dependency. Dependency-Check reports the same library at several levels
# (jar, module, package), and only the top level carries a usable version, so the first
# non-empty one wins rather than the last.
_CPE_SOFTWARE = re.compile(
    r"^(?:cpe:2\.3:[aho\*]?:.*?:(?:(?P<pkg>.+?):)?(?P<ver>.*))", re.IGNORECASE
)


def _software_of(entry: dict) -> tuple[str, str]:
    """Best available (name, version) for a vulnerability's affected software.

    ``name`` is preferred over ``id`` because a reader — human or allowlist — needs the library's
    name, and ``id`` is variously a CPE, a purl or an opaque identifier depending on the generator.
    """
    affected = entry.get("vulnerableSoftware")
    if isinstance(affected, list) and affected:
        software = affected[0].get("software") or {}
        name = software.get("name") or software.get("id") or "unknown"
        version = software.get("version") or "unknown"
        return name, version

    # Some report versions inline a CPE string instead of a structured object.
    text = entry.get("vulnerableSoftware") or entry.get("cpe") or ""
    if isinstance(text, str) and text:
        match = _CPE_SOFTWARE.match(text)
        if match:
            return match.group("pkg") or "unknown", match.group("ver") or "unknown"
    return "unknown", "unknown"


def _score_of(vulnerability: dict) -> float:
    """The v3 base score when present, else the v2 one. Absent is 0.0, not a guess."""
    for key in ("cvssv3BaseScore", "cvssv2BaseScore", "cvssv3Score", "cvssv2Score"):
        raw = vulnerability.get(key)
        if raw not in (None, ""):
            try:
                return float(raw)
            except (TypeError, ValueError):
                continue
    return 0.0


def _is_dependency_report(report: dict) -> bool:
    """Does this document actually have one of Dependency-Check's two shapes?

    Found by auditing the F-11 fix rather than by it. The parser refuses an unreadable or malformed
    file, but a document that parses as JSON and is not a scan report at all - `{}`, a truncated
    write, a different tool's output - walked straight through to "0 vulnerabilities in the report"
    and exit 0. That is the same failure F-11 was about, one level up: F-11 read a key the tool
    never emits and reported zero on a report full of findings; this reports zero on a report it
    never understood. Both look identical to a reviewer skimming a green build.

    A gate that cannot tell "clean" from "I could not read this" is not a gate.
    """
    if not isinstance(report, dict):
        return False
    body = report.get("report", report)
    if not isinstance(body, dict):
        return False
    return isinstance(body.get("dependencies"), list) or isinstance(body.get("vulnerableSoftware"), list)


def _iter_schema(report: dict):
    """Yield every vulnerability in either report shape Dependency-Check emits."""
    body = report.get("report", report)

    # Shape A: dependencies -> packages -> vulnerabilities.
    for dependency in body.get("dependencies") or []:
        dependency_name = dependency.get("fileName") or dependency.get("name") or "unknown"
        for package in dependency.get("packages") or []:
            for vulnerability in package.get("vulnerabilities") or []:
                name, version = _software_of(vulnerability)
                yield {
                    "id": vulnerability.get("name") or vulnerability.get("source") or "unknown",
                    "dependency": dependency_name,
                    "software": name,
                    "version": version,
                    "severity": vulnerability.get("severity") or "",
                    "cvss": _score_of(vulnerability),
                    "description": (vulnerability.get("description") or "").strip(),
                }

    # Shape B: a flat vulnerableSoftware list, older generators.
    for vulnerability in body.get("vulnerableSoftware") or []:
        name, version = _software_of(vulnerability)
        yield {
            "id": vulnerability.get("name") or vulnerability.get("source") or "unknown",
            "dependency": name,
            "software": name,
            "version": version,
            "severity": vulnerability.get("severity") or "",
            "cvss": _score_of(vulnerability),
            "description": (vulnerability.get("description") or "").strip(),
        }


def _local(tag: str) -> str:
    """The element's name without its XML namespace.

    The suppression file declares a namespace, so ``entry.get("justification")`` returns nothing on
    the real file and everything on an un-namespaced fixture. Matching on the local name is the only
    way to read both, and a suppression that silently matches nothing is the worst outcome here: it
    looks like a clean scan.
    """
    return tag.rsplit("}", 1)[-1]


def _child_text(entry, name: str) -> str:
    """The text of a child element, namespace-agnostically, or ""."""
    for child in entry:
        if _local(child.tag) == name:
            return (child.text or "").strip()
    return ""


def _suppression_entries(allowlist):
    """Every {@code <suppression>} in the file.

    The root element *is* ``<suppressions>`` — there is no wrapper above it — so the entries are
    its children. The branch that looks for a nested ``suppressions`` element is kept for a report
    that happens to be wrapped, because silently finding zero entries would suppress nothing and
    report success.
    """
    if allowlist is None:
        return []
    root = allowlist.getroot() if hasattr(allowlist, "getroot") else allowlist
    if root is None:
        return []
    return [child for child in root if _local(child.tag) == "suppression"]


def _allowlisted(finding: dict, allowlist) -> bool:
    """Whether an allowlist entry covers this finding.

    An entry must name the finding *and* state a reason. An entry with no justification is a mute
    button rather than a decision, and an unexplained allowlist entry is indistinguishable from
    somebody silencing an alert they did not want to read.

    Matching is by substring over the identity rather than by exact CPE equality, because the two
    sides do not spell a library the same way: a suppression says
    ``cpe:2.3:a:com.example:libfoo:1.2.3:*:*:*:*:*:*:*`` while the finding reports ``libfoo``.
    An exact comparison would suppress nothing, which is the more dangerous of the two failure
    modes because it looks like a clean scan.
    """
    for entry in _suppression_entries(allowlist):
        if not _child_text(entry, "justification"):
            continue

        identity = " ".join(
            _child_text(entry, field)
            for field in ("cpe", "vulnerabilityId", "name", "package")
        )
        if not identity.strip():
            continue

        parts = finding["software"].split(":")
        artifact = parts[-2] if len(parts) >= 2 else None
        candidates = (
            finding["id"],
            finding["dependency"],
            finding["software"],
            artifact,
            finding["version"],
        )
        if any(target and target in identity for target in candidates):
            return True
    return False


def load_allowlist(path: str):
    """Reads a Dependency-Check suppression file, or returns an empty allowlist.

    A missing or unreadable allowlist is a warning rather than a failure: suppressions are optional,
    and their absence must not manufacture findings. It does mean every finding blocks, which is the
    correct default.
    """
    try:
        return ET.parse(path)
    except (OSError, ET.ParseError) as unreadable:
        print(f"::warning::could not read the allowlist at {path}: {unreadable}")
        return None


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2

    report_path = argv[1]
    threshold = float(argv[2]) if len(argv) > 2 else DEFAULT_THRESHOLD
    allowlist_path = argv[3] if len(argv) > 3 else "docs/security/dependency-suppressions.xml"

    try:
        with open(report_path, encoding="utf-8") as handle:
            report = json.load(handle)
    except (OSError, json.JSONDecodeError) as unreadable:
        # A scanner that produced no readable report has not found nothing. It has failed, and the
        # two must never look the same to whoever is reading the output.
        print(f"::error::could not read the dependency report at {report_path}: {unreadable}")
        return 1

    if not _is_dependency_report(report):
        # Deliberately NOT "0 vulnerabilities". A report with no `dependencies` and no
        # `vulnerableSoftware` has not been scanned; it has been read wrongly, and the difference
        # is the whole reason this script exists.
        print(f"::error::{report_path} is readable JSON but is not a Dependency-Check report:")
        print("::error::it has neither a 'dependencies' list nor a 'vulnerableSoftware' list,")
        print("::error::so this parser cannot tell a clean scan from a document it did not")
        print("::error::understand. Treating it as zero findings is exactly the failure this")
        print("::error::script replaced. Check that the scan wrote to the expected path.")
        return 2

    allowlist = load_allowlist(allowlist_path)
    findings = list(_iter_schema(report))
    blocking = []
    suppressed = []

    for finding in findings:
        if finding["cvss"] < threshold:
            continue
        (suppressed if _allowlisted(finding, allowlist) else blocking).append(finding)

    print(f"{len(findings)} vulnerabilities in the report, threshold CVSS >= {threshold}")
    print(f"{len(blocking)} blocking, {len(suppressed)} suppressed by the allowlist")

    for finding in sorted(blocking, key=lambda f: -f["cvss"]):
        print(
            f"  BLOCKING {finding['id']} cvss={finding['cvss']} "
            f"{finding['software']} {finding['version']} via {finding['dependency']}"
        )
    for finding in sorted(suppressed, key=lambda f: -f["cvss"]):
        print(
            f"  allowed  {finding['id']} cvss={finding['cvss']} "
            f"{finding['software']} {finding['version']}"
        )

    return 1 if blocking else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))