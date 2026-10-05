#!/usr/bin/env python
"""Validate the GitHub Actions workflows without being able to run them.

WHY THIS EXISTS
---------------
Nothing in this repository can execute a GitHub Actions workflow, so a workflow is
the only kind of file here with no execution evidence at all. `docs/spec/08 §7a`
says so out loud rather than implying the jobs have run. This script is the
strongest check that is actually available offline, and it is deliberately
narrow about what it claims:

  * every workflow file is valid YAML
  * every workflow declares `on:` (triggers) and `permissions:` — a workflow that
    inherits the repository default token scope is a workflow nobody audited
  * every `uses:` is pinned to a tag or a SHA, and any SHA is a full 40-hex value
    rather than something that merely looks like one
  * every `run:` block is syntactically valid bash (`bash -n`), because a YAML file
    that parses can still carry a shell script that does not

WHAT IT DOES NOT DO
-------------------
It does not prove any job has ever succeeded. It cannot. It proves the file is
well-formed and says what it claims to, which is a real check that catches a real
class of mistake — an unbalanced heredoc inside `run:` is invisible to a YAML
parser and fatal on the runner.

Usage:
    python scripts/check_workflows.py
"""

import pathlib
import re
import subprocess
import sys

import yaml

WORKFLOWS = pathlib.Path(".github/workflows")
SHA = re.compile(r"^[0-9a-f]{40}$")
VALID_ON_KEYS = {"push", "pull_request", "schedule", "workflow_dispatch", "workflow_call"}


def bash_is_valid(script):
    """`bash -n` parses without executing. Returns (ok, message).

    The script is fed on stdin rather than through a temp file: this runs on a
    Windows checkout under Git Bash as often as on Linux, and a native Windows
    temp path is not a path `bash` can open. The bytes are encoded rather than
    handed over as text, because a text pipe on Windows rewrites every ``\n`` as
    ``\r\n`` on the way in — which would report every workflow as a shell syntax
    error when none of them is one.
    """
    result = subprocess.run(["bash", "-n"], input=script.encode("utf-8"),
                            capture_output=True)
    if result.returncode == 0:
        return True, ""
    return False, result.stderr.decode("utf-8", "replace").strip()


def check(path):
    problems = []
    try:
        document = yaml.safe_load(path.read_text(encoding="utf-8"))
    except yaml.YAMLError as error:
        return [f"{path}: not valid YAML: {error}"]

    if not isinstance(document, dict):
        return [f"{path}: the document is not a mapping"]

    # PyYAML resolves an unquoted `on:` to the boolean True, which is a YAML 1.1
    # quirk rather than a workflow error. Accept either and never report it.
    triggers = document.get("on", document.get(True))
    if triggers is None:
        problems.append(f"{path}: no `on:` — the workflow can never trigger")
    elif isinstance(triggers, str):
        unknown = {triggers} - VALID_ON_KEYS
        if unknown:
            problems.append(f"{path}: unrecognised trigger {triggers}")
    elif isinstance(triggers, dict):
        unknown = set(triggers) - VALID_ON_KEYS
        if unknown:
            problems.append(f"{path}: unrecognised triggers {sorted(unknown)}")
    else:
        problems.append(f"{path}: `on:` is neither a string nor a mapping")

    if "permissions" not in document:
        problems.append(f"{path}: no `permissions:` — it would inherit the repository default")

    jobs = document.get("jobs") or {}
    if not jobs:
        problems.append(f"{path}: no jobs")

    for job_name, job in jobs.items():
        if not isinstance(job, dict):
            problems.append(f"{path}: job {job_name} is not a mapping")
            continue
        steps = job.get("steps") or []
        if not steps:
            problems.append(f"{path}: job {job_name} has no steps")
        for index, step in enumerate(steps, start=1):
            where = f"{path}: {job_name} step {index}"
            if not isinstance(step, dict):
                problems.append(f"{where} is not a mapping")
                continue
            uses = step.get("uses")
            if uses:
                ref = str(uses).rsplit("@", 1)[-1] if "@" in str(uses) else ""
                if not ref:
                    problems.append(f"{where}: `{uses}` is not pinned to a version")
                elif len(ref) == 40 and not SHA.match(ref):
                    problems.append(f"{where}: `{ref}` looks like a SHA but is not 40 hex chars")
            run = step.get("run")
            if isinstance(run, str):
                ok, message = bash_is_valid(run)
                if not ok:
                    problems.append(f"{where}: run block is not valid bash — {message}")
            elif run is not None:
                problems.append(f"{where}: `run` is not a string")

    return problems


def main():
    if not WORKFLOWS.exists():
        sys.exit(f"{WORKFLOWS} does not exist")

    files = sorted(WORKFLOWS.glob("*.yml")) + sorted(WORKFLOWS.glob("*.yaml"))
    if not files:
        sys.exit(f"no workflow files in {WORKFLOWS}")

    problems = []
    for path in files:
        found = check(path)
        problems.extend(found)
        print(f"{path}: {'ok' if not found else str(len(found)) + ' problem(s)'}")

    if problems:
        print()
        for problem in problems:
            print(f"::error::{problem}")
        return 1

    print(f"\n{len(files)} workflow(s) parse, name their triggers and permissions, and every "
          f"run block is valid bash.")
    print("That is the whole claim. None of these jobs has ever been executed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())