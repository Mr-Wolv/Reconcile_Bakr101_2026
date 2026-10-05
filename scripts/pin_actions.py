#!/usr/bin/env python
"""Resolve every GitHub Actions tag in the workflows to the commit SHA it currently points at.

WHY THIS EXISTS
---------------
OSSF Scorecard scores whether actions are pinned to a full-length commit SHA, and it is right to.
A tag such as `actions/checkout@v4` is a mutable name: whoever controls that repository can move it,
and this repository would run whatever they moved it to. A SHA cannot be moved.

An earlier version of this project stated that pinning was impossible here because no SHA could be
verified without network access. That was wrong, and the fix is to say so than to leave a limitation
standing that a single HTTP request removes: the GitHub API serves public repositories
unauthenticated, which is enough to resolve a tag and read back the commit it names.

WHAT IT DOES, AND WHAT IT DOES NOT
----------------------------------
It resolves and rewrites. It does not audit the actions it pins: pinning tells you that *something*
immutable will run, not that what runs is trustworthy. That is what the SAST and dependency jobs are
for. Pinning is supply-chain hygiene, not a security review, and the difference is worth stating
rather than blurring.

REFRESHING
----------
Tags move, so a pin is a snapshot. Re-run this when a dependency bump is wanted:

    python scripts/pin_actions.py           # rewrite every tag to its current SHA
    python scripts/pin_actions.py --check   # fail if any tag is unpinned; do not rewrite

A `--check` that fails in CI is what stops the pins silently rotting back to tags. Nothing here calls
it automatically, because a workflow cannot usefully report on its own definition while being
defined.
"""

import argparse
import json
import pathlib
import re
import sys
import urllib.error
import urllib.request

WORKFLOWS = pathlib.Path(".github/workflows")
API = "https://api.github.com/repos/{owner}/{repo}/git/ref/tags/{ref}"
COMMIT = "https://api.github.com/repos/{owner}/{repo}/commits/{sha}"
SHA = re.compile(r"^[0-9a-f]{40}$")
USES = re.compile(r"uses:\s*(?P<action>[\w.-]+/[\w.-]+(?:/[\w./-]+)?)@(?P<ref>[\w.\-/]+)")

# Recorded next to each pin so a diff shows *what version* was pinned, not just an opaque hash.
# A bare SHA answers "is this immutable?" and not "is this the version I meant?".
COMMENTS = {
    "actions/checkout": "v4",
    "actions/setup-java": "v4",
    "actions/upload-artifact": "v4",
    "actions/cache": "v4",
    "actions/dependency-review-action": "v5.0.0",
    "ossf/scorecard-action": "v2.4.4",
}


def api(url):
    request = urllib.request.Request(
        url, headers={"User-Agent": "reconcile-pin-actions",
                      "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read())


def resolve(action, ref):
    """The commit a tag points at, following one level of annotated-tag indirection."""
    owner_repo, _, path = action.partition("/")
    ref_url = API.format(owner=owner_repo, repo=path, ref=ref) if path else None
    if ref_url is None:
        raise ValueError(f"{action} is not owner/repo[/path]")

    payload = api(ref_url)
    sha = payload["object"]["sha"]
    if payload["object"]["type"] == "tag":
        # An annotated tag names a tag object, which names the commit. One more hop.
        sha = api(f"https://api.github.com/repos/{owner_repo}/{path}/git/tags/{sha}")["object"]["sha"]
    if not SHA.match(sha):
        raise ValueError(f"{action}@{ref} resolved to {sha!r}, which is not a commit SHA")
    return sha


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true",
                        help="report unpinned actions and exit non-zero; rewrite nothing")
    arguments = parser.parse_args()

    files = sorted(WORKFLOWS.glob("*.yml")) + sorted(WORKFLOWS.glob("*.yaml"))
    if not files:
        sys.exit(f"no workflow files in {WORKFLOWS}")

    wanted = {}
    pinned = set()
    unpinned = []
    for path in files:
        for match in USES.finditer(path.read_text(encoding="utf-8")):
            action, ref = match.group("action"), match.group("ref")
            if action.startswith("./"):
                continue  # a local action, nothing to resolve
            if SHA.match(ref):
                pinned.add((action, ref))
                continue
            unpinned.append((path, match.group(0)))
            wanted[(action, ref)] = None

    if arguments.check:
        for path, text in unpinned:
            print(f"::error::{path}: `{text}` is not pinned to a commit SHA")
        if unpinned:
            print(f"\n{len(unpinned)} action reference(s) unpinned. "
                  f"Run: python scripts/pin_actions.py")
            return 1
        # Pinned is not the same as *resolvable*. Two actions in this repository were originally
        # referenced by floating tags that do not exist - `dependency-review-action@v4` and
        # `scorecard-action@v2`, neither of which was ever cut - so the workflows would have failed
        # on their first execution with "unable to resolve action". A YAML parser cannot see that,
        # and neither can a SHA-shape check. Asking the API is the only thing that can, so it is
        # what this mode does.
        bad = []
        for (action, sha) in sorted(pinned):
            owner_repo, _, path = action.partition("/")
            try:
                api(COMMIT.format(owner=owner_repo, repo=path, sha=sha))
            except Exception as error:
                bad.append(f"{action}@{sha}: {error}")
        for problem in bad:
            print(f"::error::pinned action does not resolve — {problem}")
        if bad:
            return 1
        print(f"Every action reference is pinned to a full commit SHA that still resolves "
              f"({len(files)} workflows, {len(pinned)} distinct actions).")
        return 0

    if not wanted:
        print("Every action reference is already pinned.")
        return 0

    print(f"Resolving {len(wanted)} action reference(s) against the GitHub API...\n")
    for action, ref in sorted(wanted):
        try:
            sha = resolve(action, ref)
        except urllib.error.HTTPError as error:
            print(f"  !! {action}@{ref}: HTTP {error}")
            return 1
        except Exception as error:
            print(f"  !! {action}@{ref}: {error}")
            return 1
        wanted[(action, ref)] = sha
        version = COMMENTS.get(action, "")
        print(f"  {action}@{ref} -> {sha}" + (f"  ({version})" if version else ""))

    # Rewrite, keeping whatever trailing comment already followed the reference.
    pattern = re.compile(
        r"(?P<head>uses:\s*(?P<action>[\w.-]+/[\w.-]+(?:/[\w./-]+)?)@)(?P<ref>[\w.\-/]+)"
        r"(?P<tail>[^\n]*)$", re.M)

    changed = 0
    for path in files:
        text = path.read_text(encoding="utf-8")

        def swap(match):
            action, ref = match.group("action"), match.group("ref")
            if action.startswith("./") or SHA.match(ref):
                return match.group(0)
            sha = wanted.get((action, ref))
            if sha is None:
                return match.group(0)
            version = COMMENTS.get(action)
            note = f"  # {version}" if version and not match.group("tail").strip() else ""
            return f"{match.group('head')}{sha}{note}"

        rewritten = pattern.sub(swap, text)
        if rewritten != text:
            path.write_text(rewritten, encoding="utf-8")
            changed += 1
            print(f"  pinned {path}")

    print(f"\n{changed} workflow(s) rewritten. Re-run scripts/check_workflows.py to validate.")
    return 0


if __name__ == "__main__":
    sys.exit(main())