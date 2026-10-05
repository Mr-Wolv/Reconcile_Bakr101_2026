#!/usr/bin/env python3
"""Compute this repository's inventory, and check the documents that quote it.

Why this exists: the README and the test matrix quote exact counts — main sources, test sources,
lines of code, migrations, test executions. Those counts were maintained by hand, and they were
wrong (D-01). A stale number in a handoff document is worse than no number, because a reader has
no way to tell which figures were checked and which were merely believed.

Two ways to stop a number going stale, and only one of them works:

  * Update the number by hand every time a file is added. This fails, because nothing forces it to
    happen and the failure is silent.
  * Make the number checkable, so going stale is a build failure rather than a false claim.

So this is the second one. ``--print`` reports the inventory. ``--check`` parses the figures back
out of the documents, recomputes them, and exits nonzero on any disagreement. It is part of
``scripts/verify.sh``, which means the documents are verified by the same run that verifies the
code.

Test counts are read from the Surefire and Failsafe reports rather than hard-coded, because they
depend on what the suite actually ran — which is the only thing worth quoting. When the reports are
absent, the test-count claims are skipped and said to be skipped, rather than quietly passing.

Usage::

    python scripts/inventory.py            # print the inventory
    python scripts/inventory.py --check    # fail if a document disagrees with it
"""

from __future__ import annotations

import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _count(pattern: str) -> int:
    import fnmatch
    return sum(1 for path in glob.glob(os.path.join(ROOT, pattern), recursive=True))


def _lines_of(files: list[str]) -> int:
    total = 0
    for pattern in files:
        for path in glob.glob(os.path.join(ROOT, pattern), recursive=True):
            try:
                with open(path, encoding="utf-8", errors="replace") as handle:
                    total += sum(1 for _ in handle)
            except OSError:
                continue
    return total


def _top_level_declarations(migration: str) -> dict:
    """Count the object declarations in a migration file.

    Deliberately a count of statements at the start of a line, not of every occurrence: a trigger
    body contains `CREATE` in prose and in code, and counting those would produce a number nobody
    could check by hand.
    """
    path = os.path.join(ROOT, migration)
    if not os.path.exists(path):
        return {}
    counts = {"tables": 0, "indexes": 0, "triggers": 0, "functions": 0}
    # The qualifier words matter and were missing. `CREATE CONSTRAINT TRIGGER` is how PostgreSQL
    # spells a deferred constraint trigger, and V1 uses it for tg_txn_balances; `CREATE UNIQUE
    # INDEX` is how a unique index is spelled. This pattern matched neither, so it under-counted
    # triggers by one in V1 and by one in V2 - and the README, which quotes this tool's output,
    # inherited the error and called it fact. The count was wrong in a way no reader could check,
    # which is the only kind of wrong this script exists to prevent.
    #
    # Verified against the database rather than trusted: a fresh database built from both
    # migrations contains exactly 10 user triggers, matching V1's 9 plus V2's 1.
    pattern = re.compile(
        r"^CREATE\s+(?:OR\s+REPLACE\s+)?(?:UNIQUE\s+)?(?:CONSTRAINT\s+)?"
        r"(TABLE|INDEX|TRIGGER|FUNCTION)\b",
        re.IGNORECASE | re.MULTILINE)
    # An explicit map, because "INDEX".lower() + "s" is "indexs" and a number nobody can check by
    # hand is exactly what this script exists to prevent.
    plurals = {"TABLE": "tables", "INDEX": "indexes", "TRIGGER": "triggers",
               "FUNCTION": "functions"}
    with open(path, encoding="utf-8", errors="replace") as handle:
        for kind in pattern.findall(handle.read()):
            counts[plurals[kind.upper()]] += 1
    return counts


def _test_counts() -> dict | None:
    """Executed test counts from the Maven reports, or None when they have not been produced."""
    unit_files = glob.glob(os.path.join(ROOT, "target/surefire-reports/TEST-*.xml"))
    if not unit_files:
        return None

    def _sum(files):
        completed = failures = errors = skipped = 0
        for path in files:
            root = ET.parse(path).getroot()
            completed += int(root.get("tests", 0))
            failures += int(root.get("failures", 0))
            errors += int(root.get("errors", 0))
            skipped += int(root.get("skipped", 0))
        return completed, failures, errors, skipped

    unit = _sum(unit_files)
    integration = (0, 0, 0, 0)
    summary = os.path.join(ROOT, "target/failsafe-reports/failsafe-summary.xml")
    if os.path.exists(summary):
        values = {}
        for child in ET.parse(summary).getroot():
            text = (child.text or "").strip()
            if text.isdigit():
                values[child.tag.lower()] = int(text)
        integration = (
            values.get("completed", 0),
            values.get("failures", 0),
            values.get("errors", 0),
            values.get("skipped", 0),
        )

    # Four distinct quantities, named so a reader cannot confuse them. This matters because they
    # are not equal: a skipped test is DISCOVERED but neither EXECUTED nor PASSED, and "353 tests
    # pass" is a false claim about a build in which one test was skipped.
    #
    # The `passed` figure below used to be
    #     unit.tests - unit.errors - unit.skipped
    #   + integration.completed - integration.failures - integration.errors
    # which omitted unit FAILURES and integration SKIPS. It therefore reported the discovered total
    # under the name "passed" (353 when 352 passed), and - the part that made it a gate defect rather
    # than a cosmetic one - it returned the SAME number whether or not a unit test had failed, so
    # the figure was structurally incapable of noticing a broken unit suite.
    def passed(t):
        return t[0] - t[1] - t[2] - t[3]

    def executed(t):
        return t[0] - t[3]

    return {
        "unit": unit,
        "integration": integration,
        "discovered": unit[0] + integration[0],
        "executed": executed(unit) + executed(integration),
        "passed": passed(unit) + passed(integration),
        "skipped": unit[3] + integration[3],
        "failed": unit[1] + integration[1],
        "errored": unit[2] + integration[2],
    }


def inventory() -> dict:
    migrations = sorted(
        os.path.basename(p)
        for p in glob.glob(os.path.join(ROOT, "src/main/resources/db/migration/*.sql"))
    )
    declarations = {}
    for migration in migrations:
        found = _top_level_declarations(
            os.path.join("src/main/resources/db/migration", migration)
        )
        declarations = {k: declarations.get(k, 0) + v for k, v in found.items()}

    return {
        "main_sources": _count("src/main/java/**/*.java"),
        "test_sources": _count("src/test/java/**/*.java"),
        "main_lines": _lines_of(["src/main/java/**/*.java"]),
        "test_lines": _lines_of(["src/test/java/**/*.java"]),
        "migrations": len(migrations),
        "migration_files": migrations,
        "declarations": declarations,
        "tests": _test_counts(),
    }


def _read(relative: str) -> str:
    with open(os.path.join(ROOT, relative), encoding="utf-8", errors="replace") as handle:
        return handle.read()


def _check(label: str, name: str, text: str, expected, pattern: str, failures: list) -> None:
    """Assert that `text` quotes `expected` in a line matching `pattern`.

    `name` and `text` are separate parameters on purpose. An earlier version took a single
    `document` argument and every caller passed the file *name*, so the regex ran against the
    nine characters of "README.md" and could never match: the check was wired into
    scripts/verify.sh and was incapable of passing, which is worse than absent because it reads
    as a gate. It failed loudly, so nothing was certified on a false pass - but the documented
    inventory was never actually verified until this was fixed.
    """
    if expected is None:
        print(f"SKIP  {label}: nothing to verify")
        return
    match = re.search(pattern, text, re.IGNORECASE | re.MULTILINE)
    if not match:
        failures.append(
            f"{label}: no line matching {pattern!r} in {name}. The document must state the "
            f"figure ({expected}) so this check can verify it."
        )
        return
    stated = int(match.group(1).replace(",", ""))
    if stated != expected:
        failures.append(
            f"{label}: {name} says {stated}, the tree has {expected}"
        )
        return
    print(f"ok    {label}: {stated}")


def _doc_counts() -> dict:
    """Counts of the documents the README quotes: specifications and ADRs.

    Added in the release-freeze pass. These two figures were hand-maintained and were correct when
    written, which is precisely why they were a risk: nothing forced them to stay right, and D-01
    showed what that costs. The ADR directory also holds a README, which is an index and not a
    decision record, so counting files rather than records would over-count by one.
    """
    specs = sorted(f for f in os.listdir(os.path.join(ROOT, "docs/spec"))
                   if f.endswith(".md") and os.path.isfile(os.path.join(ROOT, "docs/spec", f)))
    adr_dir = os.path.join(ROOT, "docs/adr")
    adrs = sorted(f for f in os.listdir(adr_dir)
                  if f.endswith(".md") and f.lower() != "readme.md"
                  and os.path.isfile(os.path.join(adr_dir, f)))
    return {"specifications": len(specs), "adrs": len(adrs)}


def check() -> int:
    facts = inventory()
    failures: list[str] = []

    readme = _read("README.md")
    _check("main sources", "README.md", readme, facts["main_sources"],
           r"\|\s*Code\s*\|\s*([0-9,]+)\s+main sources", failures)
    _check("test sources", "README.md", readme, facts["test_sources"],
           r"([0-9,]+)\s+test sources", failures)

    matrix = _read("docs/spec/08-test-matrix-and-dod.md")
    _check("sources read by the float scan", "docs/spec/08-test-matrix-and-dod.md", matrix,
           facts["main_sources"], r"reads \*\*all\s+([0-9,]+)\*\*\s+main sources", failures)

    docs = _doc_counts()
    _check("specification documents", "README.md", readme, docs["specifications"],
           r"([0-9,]+)\s+documents, the contract the code is written against", failures)
    _check("architecture decision records", "README.md", readme, docs["adrs"],
           r"([0-9,]+)\s+architecture decision records", failures)

    # The migration declaration figures. These were the last hand-maintained numbers in the README
    # and they were WRONG: the README said 8 triggers while the database holds 10, because the
    # counter this check depends on did not match `CREATE CONSTRAINT TRIGGER`. A figure that is
    # both unchecked and incorrect is the worst of the three kinds, so it is checked now.
    declared = facts["declarations"]
    # One regex, four captures: a single sentence states all four, so checking them separately
    # would mean four regexes that could each match a different sentence.
    m = re.search(r"declaring\s+([0-9,]+)\s+tables,\s+([0-9,]+)\s+indexes,\s+([0-9,]+)\s+triggers"
                  r"\s+and\s+([0-9,]+)\s+functions", readme, re.IGNORECASE)
    if not m:
        failures.append(
            "migration declarations: the README does not state the table/index/trigger/function "
            f"counts the migrations actually declare ({declared})"
        )
    else:
        stated = [int(x.replace(",", "")) for x in m.groups()]
        actual = [declared["tables"], declared["indexes"], declared["triggers"], declared["functions"]]
        if stated == actual:
            print(f"ok    migrations declare: {declared['tables']} tables, {declared['indexes']} "
                  f"indexes, {declared['triggers']} triggers, {declared['functions']} functions")
        else:
            failures.append(
                f"migration declarations: README says {stated}, the migrations contain {actual}")

    tests = facts["tests"]
    if tests is None:
        print("SKIP  test counts: no Maven reports; run this after `mvn verify`")
    else:
        _check("unit tests discovered", "README.md", readme, tests["unit"][0],
               r"([0-9,]+)\s+unit\s+\(`mvn test`\)", failures)
        _check("integration tests discovered", "README.md", readme, tests["integration"][0],
               r"([0-9,]+)\s+integration against real PostgreSQL", failures)
        # The three that used to be unchecked, and one of which used to be wrong. A document may
        # state "353 tests" without saying whether that is discovered, executed or passed; the
        # reports know, and the document is now required to agree with them on each.
        _check("tests discovered", "README.md", readme, tests["discovered"],
               r"([0-9,]+)\s+discovered", failures)
        _check("tests executed and passed", "README.md", readme, tests["passed"],
               r"([0-9,]+)\s+executed and passed", failures)
        _check("tests skipped", "README.md", readme, tests["skipped"],
               r"([0-9,]+)\s+skipped", failures)

        # Arithmetic the document cannot get subtly wrong without saying so.
        if tests["discovered"] != tests["executed"] + tests["skipped"]:
            failures.append(
                f"test counts do not add up: {tests['discovered']} discovered is neither "
                f"{tests['executed']} executed + {tests['skipped']} skipped"
            )
        elif tests["executed"] != tests["passed"] + tests["failed"] + tests["errored"]:
            failures.append(
                f"test counts do not add up: {tests['executed']} executed is neither "
                f"{tests['passed']} passed + {tests['failed']} failed + {tests['errored']} errored"
            )
        else:
            print(f"ok    test arithmetic: {tests['discovered']} discovered = "
                  f"{tests['executed']} executed + {tests['skipped']} skipped; "
                  f"{tests['executed']} executed = {tests['passed']} passed + 0 failed")

    if failures:
        print("\n::error::documented inventory does not match the tree:")
        for failure in failures:
            print(f"  - {failure}")
        return 1

    print("inventory: every documented figure matches the tree")
    return 0


def main(argv: list[str]) -> int:
    facts = inventory()
    if "--check" in argv:
        return check()

    print("main sources      :", facts["main_sources"], f"({facts['main_lines']:,} lines)")
    print("test sources      :", facts["test_sources"], f"({facts['test_lines']:,} lines)")
    print("migrations        :", facts["migrations"], ", ".join(facts["migration_files"]))
    print("migrations declare:", facts["declarations"])
    tests = facts["tests"]
    if tests is None:
        print("tests             : no Maven reports present")
    else:
        print(f"unit              : {tests['unit'][0]} "
              f"(failures={tests['unit'][1]} errors={tests['unit'][2]} skipped={tests['unit'][3]})")
        print(f"integration       : {tests['integration'][0]} "
              f"(failures={tests['integration'][1]} errors={tests['integration'][2]} "
              f"skipped={tests['integration'][3]})")
        print(f"discovered        : {tests['discovered']}")
        print(f"executed          : {tests['executed']}")
        print(f"passed            : {tests['passed']}")
        print(f"skipped           : {tests['skipped']}")
        print(f"failed / errored  : {tests['failed']} / {tests['errored']}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))