#!/usr/bin/env python3
"""Tests for dependency_check_summary.py.

F-11 was a parser reading ``report["report"]["vulnerableSoftware"]`` when Dependency-Check actually
writes ``report["report"]["dependencies"][*]["packages"][*]["vulnerabilities"][*]``. It printed
"0 vulnerable dependencies" on a report full of them, exited 0, and — this is the part that made it
survive — had no test of its own, because a parser exercised only by a scheduled CI job cannot be
exercised at all.

So the fixtures here are the test. Each one is a complete report document with a known expected
verdict, and the assertions are on the exit status rather than on printed text, because the exit
status is what a gate is made of.

Run::

    python scripts/test_dependency_check_summary.py        # exits nonzero on any failure

The whole file is the suite: unittest's exit status is what the caller reads, and adding a test
framework to a repository that has none for its Python tooling would cost more than it buys.
"""

from __future__ import annotations

import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import dependency_check_summary as summary  # noqa: E402


def _finding(name="CVE-2026-0001", score=8.8, software="libfoo", version="1.2.3"):
    """One vulnerability in the shape Dependency-Check writes."""
    return {
        "name": name,
        "source": "NVD",
        "severity": "HIGH",
        "cvssv3BaseScore": score,
        "description": "a made-up finding used to prove the parser can see one",
        "vulnerableSoftware": [
            {
                "vulnerableSoftwareId": f"cpe:2.3:a:com.example:{software}:{version}:*:*:*:*:*:*:*",
                "software": {
                    "id": f"cpe:2.3:a:com.example:{software}:{version}:*:*:*:*:*:*:*",
                    "name": software,
                    "version": version,
                },
            }
        ],
    }


def _report(findings, *, shape="dependencies"):
    """A whole report document, in either shape the parser claims to support."""
    if shape == "dependencies":
        # One dependency per finding, which is how a real scan reports them: the finding is nested
        # under the package, which is nested under the file that pulled it in.
        return {
            "report": {
                "reportSchema": "1.1",
                "analysisType": "json-generator",
                "dependencies": [
                    {
                        "fileName": f"{finding['vulnerableSoftware'][0]['software']['name']}-1.2.3.jar",
                        "packages": [{"vulnerabilities": [finding]}],
                    }
                    for finding in findings
                ],
            }
        }
    return {"report": {"vulnerableSoftware": findings}}


class DependencyCheckSummaryTest(unittest.TestCase):

    def run_summary(self, report, *args):
        """Runs the CLI against a temporary report file and returns (exit_code, stdout)."""
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as fh:
            json.dump(report, fh)
            path = fh.name
        try:
            buffer = io.StringIO()
            with redirect_stdout(buffer):
                code = summary.main(["dependency_check_summary.py", path, *args])
            return code, buffer.getvalue()
        finally:
            os.unlink(path)

    # ------------------------------------------------------------------ the defect

    def test_a_high_severity_finding_is_detected_and_fails_the_gate(self):
        """The exact shape that produced F-11: a real high finding must not read as zero."""
        code, output = self.run_summary(_report([_finding()]))

        self.assertEqual(code, 1, "a CVSS 8.8 finding must fail the gate")
        self.assertIn("CVE-2026-0001", output)
        self.assertIn("1 blocking", output)
        self.assertNotIn("0 vulnerable dependencies", output)

    def test_the_documented_schema_is_the_one_parsed(self):
        """Guards the specific key path F-11 got wrong, by name."""
        report = _report([_finding()])
        body = report["report"]

        self.assertIn("dependencies", body)
        self.assertNotIn(
            "vulnerableSoftware", body,
            "the fixture must exercise the shape the old parser failed to read",
        )
        self.assertEqual(len(list(summary._iter_schema(report))), 1)

    # ------------------------------------------------------------------ thresholds

    def test_a_clean_report_passes(self):
        code, output = self.run_summary(_report([]))

        self.assertEqual(code, 0)
        self.assertIn("0 vulnerabilities in the report", output)

    def test_a_finding_below_the_threshold_does_not_fail_the_gate(self):
        code, output = self.run_summary(_report([_finding(score=4.0)]))

        self.assertEqual(code, 0, "CVSS 4.0 is below the 7.0 gate")
        self.assertIn("1 vulnerabilities in the report", output)
        self.assertIn("0 blocking", output)

    def test_the_threshold_is_configurable(self):
        report = _report([_finding(score=5.0)])

        self.assertEqual(self.run_summary(report, "4.0")[0], 1)
        self.assertEqual(self.run_summary(report, "9.0")[0], 0)

    # ------------------------------------------------------------------ both shapes

    def test_the_flat_legacy_shape_is_also_parsed(self):
        """Accepting both is deliberate; reading neither is the bug being fixed."""
        code, output = self.run_summary(
            _report([_finding()], shape="vulnerableSoftware")
        )

        self.assertEqual(code, 1)
        self.assertIn("CVE-2026-0001", output)

    def test_scores_fall_back_to_cvss_v2(self):
        finding = _finding()
        finding.pop("cvssv3BaseScore")
        finding["cvssv2BaseScore"] = 9.1

        code, _ = self.run_summary(_report([finding]))

        self.assertEqual(code, 1)

    # ------------------------------------------------------------------ failure modes

    def test_a_missing_report_is_a_failure_not_a_pass(self):
        with redirect_stdout(io.StringIO()):
            code = summary.main(["dependency_check_summary.py", "/nonexistent/report.json"])

        self.assertEqual(
            code, 1,
            "a scan that produced no report must never be indistinguishable from a clean scan",
        )

    def test_unparseable_json_is_a_failure_not_a_pass(self):
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as fh:
            fh.write("{ this is not json")
            path = fh.name
        try:
            with redirect_stdout(io.StringIO()):
                code = summary.main(["dependency_check_summary.py", path])
        finally:
            os.unlink(path)

        self.assertEqual(code, 1)

    def test_a_suppressed_finding_needs_a_justification_to_be_suppressed(self):
        """An allowlist entry without a reason is a mute button, not a decision."""
        finding = _finding()
        report = _report([finding])

        with tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False, encoding="utf-8") as fh:
            fh.write(
                '<suppressions><suppression>'
                "<justification></justification>"
                "<cpe>cpe:2.3:a:com.example:libfoo:1.2.3:*:*:*:*:*:*:*</cpe>"
                "</suppression></suppressions>"
            )
            bare = fh.name
        with tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False, encoding="utf-8") as fh:
            fh.write(
                '<suppressions><suppression>'
                "<justification>false positive; no reachable code path</justification>"
                "<cpe>cpe:2.3:a:com.example:libfoo:1.2.3:*:*:*:*:*:*:*</cpe>"
                "</suppression></suppressions>"
            )
            justified = fh.name
        try:
            self.assertEqual(
                self.run_summary(report, "7.0", bare)[0], 1,
                "an unjustified suppression must not hide a finding",
            )
            self.assertEqual(
                self.run_summary(report, "7.0", justified)[0], 0,
                "a justified, matching suppression is the mechanism working",
            )
        finally:
            os.unlink(bare)
            os.unlink(justified)

    def test_a_missing_allowlist_is_not_fatal(self):
        """Suppressions are optional; their absence must not manufacture a failure."""
        code, _ = self.run_summary(_report([_finding()]), "7.0", "/nonexistent/suppressions.xml")

        self.assertEqual(code, 1, "the finding is still blocking; only the allowlist is missing")

    def test_a_document_that_is_not_a_report_is_refused_rather_than_read_as_clean(self):
        """Readable JSON that is not a scan report must not become "0 vulnerabilities".

        Found by auditing the F-11 fix rather than by it. `{}` is valid JSON, so it sailed past
        the "can I read this file" check and arrived at the summary as a clean scan. This is the
        F-11 failure one level up: F-11 reported zero on a report full of findings; this reported
        zero on a report it had not understood.
        """
        for label, document in (
            ("an empty object", {}),
            ("an unrelated object", {"status": "ok", "results": 12}),
            ("a list, not a report", [1, 2, 3]),
        ):
            with self.subTest(document=label):
                code, out = self.run_summary(document, "7.0")

                self.assertNotEqual(
                    code, 0,
                    f"{label} must not be reported as a clean scan")
                self.assertIn("not a Dependency-Check report", out)


if __name__ == "__main__":
    unittest.main(verbosity=2)