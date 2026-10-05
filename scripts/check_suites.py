#!/usr/bin/env python3
"""Assert that both test suites actually ran, and that both passed.

Why this exists: Maven exiting 0 is not sufficient evidence that the tests ran. A suite that
never started produces the same green build as one that passed, and the failure mode is silent.
So this reads the reports themselves and requires a non-zero test count from each suite.

It parses the XML rather than grepping it. An earlier version matched `failures="N"` attributes,
but ``failsafe-summary.xml`` carries ``<failures>N</failures>`` *elements* - so that version
could never match and would have reported success on a completely red suite. A check that cannot
fail is worse than no check.

Exit status is 0 only when both suites ran and neither reported a failure or an error.
"""

import glob
import os
import sys
import xml.etree.ElementTree as ET

SUREFIRE_REPORTS = "target/surefire-reports/TEST-*.xml"
FAILSAFE_SUMMARY = "target/failsafe-reports/failsafe-summary.xml"


def _counts(completed, failures, errors, skipped):
    return {"completed": completed, "failures": failures, "errors": errors, "skipped": skipped}


def unit_suite():
    """Sum the per-class surefire reports. Each is a <testsuite> with attributes."""
    files = sorted(glob.glob(SUREFIRE_REPORTS))
    if not files:
        print(f"::error::no surefire reports at {SUREFIRE_REPORTS} - the unit suite did not run")
        return None

    completed = failures = errors = skipped = 0
    for path in files:
        root = ET.parse(path).getroot()
        completed += int(root.get("tests", 0))
        failures += int(root.get("failures", 0))
        errors += int(root.get("errors", 0))
        skipped += int(root.get("skipped", 0))
    return _counts(completed, failures, errors, skipped)


def integration_suite():
    """Read the failsafe summary, whose counters are child elements rather than attributes."""
    if not os.path.exists(FAILSAFE_SUMMARY):
        print(f"::error::{FAILSAFE_SUMMARY} is missing - the integration suite did not run")
        return None

    values = {}
    for child in ET.parse(FAILSAFE_SUMMARY).getroot():
        text = (child.text or "").strip()
        if text.isdigit():
            values[child.tag.lower()] = int(text)

    return _counts(
        completed=values.get("completed", 0),
        failures=values.get("failures", 0),
        errors=values.get("errors", 0),
        skipped=values.get("skipped", 0),
    )


def main():
    bad = False
    for name, summary in (("unit", unit_suite()), ("integration", integration_suite())):
        if summary is None:
            bad = True
            continue

        print(
            f"{name}: {summary['completed']} run, {summary['failures']} failures, "
            f"{summary['errors']} errors, {summary['skipped']} skipped"
        )

        if summary["completed"] == 0:
            print(f"::error::the {name} suite reported 0 tests - it did not run")
            bad = True
        if summary["failures"] or summary["errors"]:
            print(f"::error::the {name} suite reported failures")
            bad = True

    if bad:
        return 1
    print("Both suites ran and passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())