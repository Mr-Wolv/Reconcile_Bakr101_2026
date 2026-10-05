#!/usr/bin/env bash
#
# Probe the running container over HTTP, exactly as a reader of the README would.
#
# `mvn verify` proves the code against a real database it starts itself. This proves the *image*:
# the built jar, the composed PostgreSQL 18, the migration, the security chain, the authz contract
# and the ledger, with nothing mocked and nothing in-process. Defects 12, 16, 18 and 19 in the
# README were found this way and were invisible to the test suite; 19 only surfaced on a *second*
# run, which is why repeatability is asserted here rather than assumed.
#
#   docker compose up -d --build app
#   ./scripts/probe.sh
#
# Every assertion prints PASS or FAIL and the exit status is non-zero if anything failed, so it can
# be wired into CI as a smoke test of the built artefact.
#
# Safe to run repeatedly. Every run creates its own payment with its own provider transaction and
# its own idempotency keys, and every balance assertion is a *delta* measured across this run - the
# accounts are global and cumulative, so an absolute reading would fail the second time through.
#
# Note on portability: nothing here passes a path *into* `docker exec`. Git Bash rewrites an
# argument that looks like /tmp into a Windows path before Docker ever sees it, and a body written
# to a container-side file therefore lands somewhere else entirely. Bodies go in on stdin, and
# status and body come back on one stream split on the host.

set -uo pipefail

COMPOSE="${COMPOSE:-docker compose}"
OPERATOR_TOKEN="${RECONCILE_OPERATOR_TOKEN:-dev-operator-token}"
ADMIN_TOKEN="${RECONCILE_ADMIN_TOKEN:-dev-admin-token}"
BASE="${PROBE_BASE:-http://localhost:8080}"
# A run-unique suffix, so a second run collides with neither the first run's idempotency keys nor
# its merchant references.
RUN_ID="${PROBE_RUN_ID:-$(date -u +%s)-$$}"

# Resolved once, and by *output* rather than by exit status. Two environments, opposite answers:
# Git Bash has `python` and no usable `python3`, a Linux CI runner the reverse - and on Windows
# `command -v python3` succeeds anyway, because the Microsoft Store shim is on PATH. That shim
# prints nothing and still exits 0, so both `command -v` and `&&` accept it and every JSON parse
# downstream then reads an empty string. Checking that the interpreter produced the sentinel it was
# asked for is the only test that distinguishes "installed" from "a shortcut to nothing".
if [ -n "${PYTHON:-}" ]; then
  candidates="$PYTHON python3 python"
else
  candidates="python3 python"
fi

PYTHON=""
for candidate in $candidates; do
  if [ "$("$candidate" -c 'print("reconcile-probe")' 2>/dev/null)" = "reconcile-probe" ]; then
    PYTHON="$candidate"
    break
  fi
done
if [ -z "$PYTHON" ]; then
  echo "no working python interpreter found (tried: $candidates); set PYTHON=/path/to/python" >&2
  exit 2
fi
GROSS=10000
APP_ID="$($COMPOSE ps -q app)"

if [ -z "$APP_ID" ]; then
  echo "no app container found - run: $COMPOSE up -d --build app" >&2
  exit 2
fi

failures=0
LAST_STATUS=""
LAST_BODY=""

# One request; status and body both come back, so no check needs a second call to a mutating route.
#   call <method> <path> <token> [body] [idempotency-key]
call() {
  local method="$1" path="$2" token="$3" body="${4:-}" idem="${5:-}"
  local args=(-s -w '\n<<STATUS>>%{http_code}' -X "$method" -H 'Content-Type: application/json')
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$idem" ] && args+=(-H "Idempotency-Key: $idem")
  [ -n "$body" ] && args+=(--data-binary "$body")
  local raw
  raw="$(docker exec "$APP_ID" curl "${args[@]}" "$BASE$path" 2>/dev/null)"
  LAST_STATUS="$(printf '%s' "$raw" | sed -n 's/.*<<STATUS>>//p' | tr -d '\r')"
  LAST_BODY="$(printf '%s' "$raw" | sed '/^<<STATUS>>/,$d' | tr -d '\r')"
}

pass() { printf 'PASS  %-56s %s\n' "$1" "$2"; }
fail() {
  printf 'FAIL  %-56s %s\n' "$1" "$2"
  printf '      %s\n' "$(printf '%s' "$LAST_BODY" | head -c 240)"
  failures=$((failures + 1))
}

check() {
  if [ "$2" = "$LAST_STATUS" ]; then pass "$1" "$LAST_STATUS"; else fail "$1" "expected $2, got $LAST_STATUS"; fi
}

check_contains() {
  if printf '%s' "$LAST_BODY" | grep -q -- "$2"; then
    pass "$1" "contains $2"
  else
    fail "$1" "does not contain $2"
  fi
}

# Not an HTTP check: compares two values the script computed itself.
check_equals() {
  if [ "$3" = "$2" ]; then pass "$1" "$2"; else fail "$1" "expected $2, got $3"; fi
}

json_string() {
  printf '%s' "$LAST_BODY" | sed -E "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"([^\"]*)\".*/\1/"
}

# The stored balance of one ledger account. Balances are global, so this is only ever used to take
# a reading before and after this run's own movement.
balance_of() {
  call GET "/api/v1/accounts/$1" "$OPERATOR_TOKEN"
  printf '%s' "$LAST_BODY" \
    | "$PYTHON" -c 'import json,sys; print(json.load(sys.stdin)["balance"]["amountMinor"])'
}

# `docker compose up -d --build app` returns when the container is *created*, not when the
# application is listening, and those two moments are nowhere near each other: the JVM starts, then
# Flyway migrates, then the connector opens. A probe that begins on that boundary gets a
# connection-refused - which curl reports as status 000, not as an HTTP status - on its first
# twenty-odd assertions, and then fails for a reason that has nothing to do with the image it was
# written to inspect. That is not a flaky test, it is a procedure with a hole in it, and the hole
# belongs to whoever runs the probe, not to this script's callers. So the wait is here.
#
# The distinction this preserves is "not up yet" against "up and broken". The second is a real defect
# that deserves `docker compose logs app`, and it must never be reported as a slow start, so the
# timeout below exits 2 - the same status as a missing container - and says which one it found.
READY_WAIT="${PROBE_READY_WAIT:-90}"
printf 'waiting for %s (up to %ss) ... ' "$BASE" "$READY_WAIT"
waited=0
call GET /api/v1/health ''
while [ "$LAST_STATUS" != "200" ]; do
  if [ "$waited" -ge "$READY_WAIT" ]; then
    printf ' not ready\n'
    echo "the app never became healthy in ${READY_WAIT}s; last status '${LAST_STATUS:-none}'." >&2
    echo "read the cause before retrying:" >&2
    echo "  $COMPOSE logs app" >&2
    exit 2
  fi
  sleep 2
  waited=$((waited + 2))
  printf '.'
  call GET /api/v1/health ''
done
printf ' ready after %ss\n' "$waited"

echo "== health =="
call GET /api/v1/health ''
check "GET /api/v1/health" 200
check_contains "the schema check ran" '"name":"migrations"'
check_contains "the L7 ledger verification ran" '"name":"ledger"'

echo
echo "== the simulated PSP queues a payment =="
call POST /api/v1/provider/payments "$OPERATOR_TOKEN" \
  "{\"merchantReference\":\"MERCH-PROBE-$RUN_ID\",\"grossAmountMinor\":$GROSS,\"currency\":\"EGP\"}"
check "POST /api/v1/provider/payments" 201
external="$(json_string externalTransactionId)"
echo "      externalTransactionId = $external"

before_clearing="$(balance_of PSP_CLEARING)"
before_cash="$(balance_of PLATFORM_CASH)"

echo
echo "== the payment, and idempotency =="
payment_body="{\"merchantReference\":\"MERCH-PROBE-$RUN_ID\",\"amount\":{\"amountMinor\":$GROSS,\"currency\":\"EGP\"},\"provider\":\"SIMULATED_PSP\",\"providerTransactionId\":\"$external\"}"
call POST /api/v1/payments "$OPERATOR_TOKEN" "$payment_body" "probe-create-$RUN_ID"
check "POST /api/v1/payments" 201
payment="$(json_string id)"
echo "      paymentId = $payment"

# A fresh key claiming a provider transaction another payment already owns. This is the defect-16
# path: it used to arrive as a 500 from the unique-violation, and no test covered it because the
# Testcontainers fixtures never collided.
call POST /api/v1/payments "$OPERATOR_TOKEN" "$payment_body" "probe-create-dup-$RUN_ID"
check "the same provider transaction id again is a conflict, not a 500" 409
check_contains "and it says so" 'PROVIDER_TRANSACTION_ALREADY_CLAIMED'

call POST /api/v1/payments "$OPERATOR_TOKEN" "$payment_body" "probe-create-$RUN_ID"
check_contains "the same idempotency key replays the first answer" "$payment"

call POST "/api/v1/payments/$payment/authorize" "$OPERATOR_TOKEN" '{}'
check "POST /api/v1/payments/{id}/authorize" 200
call POST "/api/v1/payments/$payment/capture" "$OPERATOR_TOKEN" '{}' "probe-capture-$RUN_ID"
check "POST /api/v1/payments/{id}/capture" 200

replay="$(docker exec "$APP_ID" curl -s -D - -o /dev/null \
  -X POST -H "Authorization: Bearer $OPERATOR_TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: probe-capture-$RUN_ID" --data-binary '{}' \
  "$BASE/api/v1/payments/$payment/capture" 2>/dev/null | tr -d '\r')"
if printf '%s' "$replay" | grep -qi 'idempotency-replayed: true'; then
  pass "a replayed key replays rather than re-capturing" "true"
else
  printf 'FAIL  %-56s no Idempotency-Replayed header\n' "a replayed key replays rather than re-capturing"
  failures=$((failures + 1))
fi

echo
echo "== every PSP event, delivered as a signed webhook, in order =="
call GET "/api/v1/provider/events?providerTransactionId=$external" "$OPERATOR_TOKEN"
check "GET /api/v1/provider/events" 200
# `-i` is load-bearing: without it docker exec does not attach stdin, `--data-binary @-` sends an
# empty body, and the signature check rejects it as WEBHOOK_REJECTED_SIGNATURE - which looks
# exactly like a broken webhook chain and is not one.
delivered="$(printf '%s' "$LAST_BODY" | "$PYTHON" -c '
import json, subprocess, sys
events = json.load(sys.stdin)
# A real PSP sends these in order; the settlement is last and settles what the capture created.
events.sort(key=lambda e: ["payment.created", "payment.captured", "settlement.paid"]
            .index(e["type"]) if e["type"] in
            ["payment.created", "payment.captured", "settlement.paid"] else 99)
for event in events:
    out = subprocess.run(
        ["docker", "exec", "-i", sys.argv[1], "curl", "-s", "-o", "/dev/null",
         "-w", "%{http_code}", "-X", "POST", "-H", "Content-Type: application/json",
         "-H", "X-Provider-Event-Id: " + event["eventId"],
         "-H", "X-Provider-Timestamp: " + event["signTimestamp"],
         "-H", "X-Provider-Signature: " + event["signature"],
         "--data-binary", "@-", "http://localhost:8080/api/v1/provider/webhooks"],
        input=event["body"].encode(), capture_output=True)
    print("DELIVERED", event["type"], out.stdout.decode().strip())
' "$APP_ID")"
printf '%s\n' "$(printf '%s' "$delivered" | sed 's/^/      /')"
for want in 'payment.created 202' 'payment.captured 202' 'settlement.paid 202'; do
  if printf '%s' "$delivered" | grep -q "DELIVERED $want"; then
    pass "POST /api/v1/provider/webhooks ($want)" "202"
  else
    printf 'FAIL  %-56s not accepted\n' "POST /api/v1/provider/webhooks ($want)"
    failures=$((failures + 1))
  fi
done

# A second delivery of the same event id must be recognised, not applied twice. This is the whole
# point of storing event ids, and it is only observable from outside the JVM.
redelivered="$(printf '%s' "$LAST_BODY" | "$PYTHON" -c '
import json, subprocess, sys
events = json.load(sys.stdin)
event = [e for e in events if e["type"] == "settlement.paid"][0]
out = subprocess.run(
    ["docker", "exec", "-i", sys.argv[1], "curl", "-s", "-o", "/dev/null",
     "-w", "%{http_code}", "-X", "POST", "-H", "Content-Type: application/json",
     "-H", "X-Provider-Event-Id: " + event["eventId"],
     "-H", "X-Provider-Timestamp: " + event["signTimestamp"],
     "-H", "X-Provider-Signature: " + event["signature"],
     "--data-binary", "@-", "http://localhost:8080/api/v1/provider/webhooks"],
    input=event["body"].encode(), capture_output=True)
print("REDELIVERED", out.stdout.decode().strip())
' "$APP_ID")"
if printf '%s' "$redelivered" | grep -q 'REDELIVERED 200'; then
  pass "a duplicate delivery is a success (200, not 202 - spec 06 §3)" "200"
else
  printf 'FAIL  %-56s got %s\n' "and re-delivering an accepted event id is accepted" "$redelivered"
  failures=$((failures + 1))
fi

# The webhook answers 202 the moment the event is durable; the effect is applied by the worker on
# its own schedule. Reading the ledger straight after therefore races the worker - and a probe that
# races is a flaky probe, so wait for the settlement to be visible before measuring anything.
settled=0
for _ in $(seq 1 30); do
  call GET "/api/v1/payments/$payment" "$OPERATOR_TOKEN"
  if printf '%s' "$LAST_BODY" | grep -q '"state":"SETTLED"'; then settled=1; break; fi
  sleep 1
done
check_equals "the settlement is applied, not merely accepted" "1" "$settled"

call GET "/api/v1/payments/$payment/timeline" "$OPERATOR_TOKEN"
check "GET /api/v1/payments/{id}/timeline" 200
check_contains "the timeline records it" 'SETTLED'

after_clearing="$(balance_of PSP_CLEARING)"
after_cash="$(balance_of PLATFORM_CASH)"
check_equals "PSP_CLEARING is back where it started" \
  "$before_clearing" "$after_clearing"
check_equals "PLATFORM_CASH grew by exactly the gross" \
  "$((before_cash + GROSS))" "$after_cash"

call GET /api/v1/accounts/PSP_CLEARING "$OPERATOR_TOKEN"
check_contains "and the stored balance still recomputes (L7)" '"verified":true'

echo
echo "== reconciliation, run inline =="
window_start="$(date -u -d '1 day ago' +%Y-%m-%dT%H:%M:%SZ)"
window_end="$(date -u -d '1 hour' +%Y-%m-%dT%H:%M:%SZ)"
window="\"windowStart\":\"$window_start\",\"windowEnd\":\"$window_end\""
call POST /api/v1/reconciliation/batches "$ADMIN_TOKEN" \
  "{\"provider\":\"SIMULATED_PSP\",$window,\"subjectMode\":\"BOTH\",\"async\":false}"
check "POST /api/v1/reconciliation/batches" 200
check_contains "the batch completed" '"status":"COMPLETED"'
batch="$(json_string id)"

# Asserted on *this* payment's own result rather than on the batch totals, which count every
# payment in the window - including those left behind by earlier probe runs.
call GET "/api/v1/reconciliation/batches/$batch/results" "$OPERATOR_TOKEN"
check "GET /api/v1/reconciliation/batches/{id}/results" 200
outcome="$(printf '%s' "$LAST_BODY" \
  | "$PYTHON" -c '
import json, sys
for r in json.load(sys.stdin):
    if r.get("internalPaymentId") == sys.argv[1]:
        print(r["outcome"], r.get("caseId"))
        break
else:
    print("ABSENT None")
' "$payment")"
check_equals "this payment reconciles clean" "MATCHED None" "$outcome"

echo
echo
echo "== the pull path, which is the answer to a lost webhook =="
call POST /api/v1/provider/sync "$ADMIN_TOKEN" "{$window}"
check "POST /api/v1/provider/sync" 200

# Push and pull must converge on the same records; a second batch after a pull is what shows it.
call POST /api/v1/reconciliation/batches "$ADMIN_TOKEN" \
  "{\"provider\":\"SIMULATED_PSP\",$window,\"subjectMode\":\"BOTH\",\"async\":false}"
check "and reconciliation still runs afterwards" 200
batch="$(json_string id)"
call GET "/api/v1/reconciliation/batches/$batch/results" "$OPERATOR_TOKEN"
outcome="$(printf '%s' "$LAST_BODY" \
  | "$PYTHON" -c '
import json, sys
for r in json.load(sys.stdin):
    if r.get("internalPaymentId") == sys.argv[1]:
        print(r["outcome"], r.get("caseId"))
        break
else:
    print("ABSENT None")
' "$payment")"
check_equals "and the payment still reconciles clean" "MATCHED None" "$outcome"

echo
echo "== authorisation, as documented =="
call GET /api/v1/health ''
check "health needs no token (permitAll)" 200
call GET /api/v1/audit/events ''
check "the audit API does need one" 401
call POST /api/v1/reconciliation/batches "$OPERATOR_TOKEN" \
  "{\"provider\":\"SIMULATED_PSP\",$window}"
check "an operator may not create a batch (admin only)" 403
call POST /api/v1/provider/sync "$OPERATOR_TOKEN" "{$window}"
check "an operator may not sync the provider (admin only)" 403

echo
if [ "$failures" -eq 0 ]; then
  echo "probe passed"
  exit 0
fi
echo "probe failed: $failures check(s)"
exit 1