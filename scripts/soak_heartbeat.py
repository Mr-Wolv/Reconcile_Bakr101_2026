#!/usr/bin/env python
"""Fail unless the ordering soak has run successfully within the last 48 hours.

WHY THIS EXISTS
---------------
A GitHub Actions schedule is a request, not a guarantee: under load GitHub can
delay or silently drop a scheduled run, and nothing tells the repository when
that has happened. The ordering soak runs only on the `verify` workflow's
nightly schedule and on its manual dispatch, so if that schedule ever stops
firing, the one leg a push cannot reproduce silently stops being exercised.
A nightly job that never runs looks exactly like a nightly job that works —
this check is the difference. It runs after the soak's own schedule and fails
the run when no successful soak-triggered run of `verify.yml` exists in the
last 48 hours.

WHAT COUNTS AS A SOAK RUN
-------------------------
The soak job's `if:` admits exactly two events of `verify.yml` — `schedule`
and `workflow_dispatch` — and the job gates, so a run of `verify.yml` with
either event and conclusion `success` is a run in which the soak executed and
passed. Push and pull-request runs skip the soak by design and are excluded.
The event is filtered on the API query *and* re-checked on each run, so the
script stays correct even if the API's event filter were ever ignored.

TOLERANCE
---------
48 hours, not 24: one dropped or heavily delayed nightly is absorbed (the
previous night's success is then about 25 hours old). Two consecutive dropped
nights are not, and that is the failure this check exists to raise. The check
also fails closed when it cannot ask the question at all — an API error is a
failed heartbeat, not a passed one.

Usage:
    GITHUB_TOKEN        a token with `actions: read` (the default GITHUB_TOKEN)
    GITHUB_REPOSITORY   owner/repo
    GITHUB_API_URL      API base URL; defaults to https://api.github.com

Exit 0 when a successful soak ran within the window; 1 otherwise.
"""

import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

WINDOW = timedelta(hours=48)
# The soak job's `if:` admits exactly these two events of verify.yml, and the
# job gates — so a successful run with either event ran the soak and passed it.
SOAK_EVENTS = ("schedule", "workflow_dispatch")
WORKFLOW = "verify.yml"


def api_get(url, token):
    """GET a JSON document from the API, or fail with why it could not be read."""
    request = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "Authorization": "Bearer " + token,
        "User-Agent": "soak-heartbeat",
    })
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        sys.exit(f"::error::GitHub API answered {error.code} while listing runs — the "
                 f"heartbeat cannot check the soak, so it fails rather than assumes.")
    except urllib.error.URLError as error:
        sys.exit(f"::error::GitHub API unreachable ({error.reason}) — the heartbeat "
                 f"cannot check the soak, so it fails rather than assumes.")


def _next_page(payload, _previous_url):
    """The next page URL from a GitHub API Link header, or None if there is none."""
    links = payload.get("Link", "")
    if not links:
        return None
    for section in links.split(","):
        if 'rel="next"' not in section:
            continue
        match = __import__("re").search(r"<([^>]+)>", section)
        if match:
            return match.group(1)
    return None


def latest_successful_soak_run(api, repository, token):
    """The newest successful soak-triggered run of verify.yml, or None."""
    latest = None
    for event in SOAK_EVENTS:
        url = (f"{api}/repos/{repository}/actions/runs"
               f"?workflow_id={WORKFLOW}&event={event}&per_page=100")
        while url:
            payload = api_get(url, token)
            # Runs are returned under ``workflow_runs``, newest first; the
            # first match on this page is the newest success for this event
            # *on this page* — but a later page may hold an even newer one,
            # so every page is scanned.
            for run in payload.get("workflow_runs", []):
                if (run.get("event") in SOAK_EVENTS
                        and run.get("conclusion") == "success"):
                    if latest is None or run["created_at"] > latest["created_at"]:
                        latest = run
            url = _next_page(payload, url)
    return latest


def main():
    api = os.environ.get("GITHUB_API_URL", "https://api.github.com").rstrip("/")
    repository = os.environ.get("GITHUB_REPOSITORY")
    token = os.environ.get("GITHUB_TOKEN")
    if not repository or not token:
        sys.exit("::error::GITHUB_REPOSITORY and GITHUB_TOKEN are required to check "
                 "the soak heartbeat.")

    run = latest_successful_soak_run(api, repository, token)
    now = datetime.now(timezone.utc)
    if run is None:
        sys.exit(f"::error::no run of {WORKFLOW} on the nightly schedule or a manual "
                 f"dispatch has ever succeeded — the ordering soak has never run. "
                 f"Check https://github.com/{repository}/actions/workflows/{WORKFLOW}.")

    # GitHub stamps times as ISO 8601 UTC ("...Z"); Python before 3.11 does not
    # parse the "Z" suffix, so it is replaced with the explicit offset.
    created = datetime.fromisoformat(run["created_at"].replace("Z", "+00:00"))
    age = now - created
    if age > WINDOW:
        sys.exit(f"::error::the ordering soak last ran successfully {age} ago "
                 f"({run['html_url']}), beyond the 48-hour window — the nightly "
                 f"schedule has stopped firing. See "
                 f"https://github.com/{repository}/actions/workflows/{WORKFLOW}.")

    print(f"soak heartbeat: the ordering soak ran successfully {age} ago "
          f"({run['html_url']}) — within the 48-hour window.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
