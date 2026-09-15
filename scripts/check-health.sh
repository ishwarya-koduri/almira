#!/usr/bin/env bash
# =============================================================================
# One outside look at a running Almira, for cron or an uptime monitor.
#
#   ./scripts/check-health.sh --base https://almira.example.in
#   ./scripts/check-health.sh --base http://127.0.0.1:8080 --alert-cmd './notify.sh'
#   ./scripts/check-health.sh --base https://almira.example.in --ops-token-file /etc/almira/ops-token
#
# Checks, in order, and prints ONE line either way:
#
#   1. /health/live   answers 200            — the process is serving HTTP
#   2. /health/ready  answers 200            — database as the runtime role,
#                                              row-level security in force, Redis
#   3. /health        says rlsEnforced:true  — and an environment that is not
#                                              `development` (unless --allow-development)
#   4. with --ops-token-file, /health's operator view says no sign-in email to
#      an allowlisted address went undelivered in the last hour. The file holds
#      ALMIRA_OPS_HEALTH_TOKEN; it is sent as a header read from a private
#      temporary file, never on the command line. A view that is missing (a
#      wrong token, or the server has none set) fails too, so a broken check
#      is not a quiet one. The answer is a count and a time — never an address.
#
# Exit 0 when all hold, 1 otherwise. With --alert-cmd, a failure also runs that
# command with the one-line reason as its only argument — your own mail, SMS or
# chat hook. Nothing is sent anywhere by this script on its own.
#
# It reads only the three health endpoints, which carry no household data
# (docs/17 §8), so it needs no credentials but the optional operator token.
# =============================================================================
set -uo pipefail

BASE="" ALERT="" ALLOW_DEV=0 TIMEOUT=10 OPS_TOKEN_FILE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --base) BASE="${2%/}"; shift 2;;
    --alert-cmd) ALERT="$2"; shift 2;;
    --allow-development) ALLOW_DEV=1; shift;;
    --timeout) TIMEOUT="$2"; shift 2;;
    --ops-token-file) OPS_TOKEN_FILE="$2"; shift 2;;
    -h|--help) sed -n '2,31p' "$0"; exit 0;;
    *) echo "unknown option: $1" >&2; exit 2;;
  esac
done
[ -n "$BASE" ] || { echo "--base is required" >&2; exit 2; }

fail() {
  local reason="almira at $BASE: $*"
  echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) FAIL $reason"
  [ -n "$ALERT" ] && $ALERT "$reason"
  exit 1
}

status() { curl -s -o "$2" -w '%{http_code}' --max-time "$TIMEOUT" "${@:3}" "$BASE$1" 2>/dev/null; }
body=$(mktemp); headers=$(umask 077; mktemp); trap 'rm -f "$body" "$headers"' EXIT

OPS=()
if [ -n "$OPS_TOKEN_FILE" ]; then
  [ -r "$OPS_TOKEN_FILE" ] || { echo "--ops-token-file: cannot read $OPS_TOKEN_FILE" >&2; exit 2; }
  token=$(tr -d '[:space:]' < "$OPS_TOKEN_FILE")
  [ -n "$token" ] || { echo "--ops-token-file: $OPS_TOKEN_FILE is empty" >&2; exit 2; }
  printf 'X-Almira-Ops-Token: %s\n' "$token" > "$headers"
  unset token
  OPS=(-H "@$headers")
fi

code=$(status /health/live "$body")
[ "$code" = 200 ] || fail "not serving (/health/live answered $code)"

code=$(status /health/ready "$body")
if [ "$code" != 200 ]; then
  fail "not ready (/health/ready answered $code: $(tr -d '\n' < "$body" | head -c 200))"
fi

code=$(status /health "$body" ${OPS[@]+"${OPS[@]}"})
[ "$code" = 200 ] || fail "/health answered $code"
verdict=$(python3 - "$body" "$ALLOW_DEV" "${#OPS[@]}" <<'PY'
import json, sys
try:
    b = json.load(open(sys.argv[1]))
except ValueError:
    print("/health is not JSON"); sys.exit(1)
if b.get("rlsEnforced") is not True:
    print(f"row-level security is NOT in force (dbRole={b.get('dbRole')!r})"); sys.exit(1)
if sys.argv[2] != "1" and b.get("environment") == "development":
    print("running with development settings"); sys.exit(1)
if sys.argv[3] != "0":
    v = b.get("signInEmailNotDelivered")
    if not isinstance(v, dict):
        print("the operator view of /health is missing: the token does not match ALMIRA_OPS_HEALTH_TOKEN, "
              "or the server has none set"); sys.exit(1)
    if int(v.get("lastHour", 0)) > 0:
        print(f"{v['lastHour']} sign-in email(s) to an allowlisted address not delivered in the last hour "
              f"(newest {v.get('newest') or '?'}); the tester was shown 'sent'. "
              "Look for SIGN-IN EMAIL NOT DELIVERED in the logs (docs/17 §8)"); sys.exit(1)
    # A restore's lost messages: raised once (MESSAGES LOST IN A RESTORE), so this fails for
    # the hour after that alert, not for as long as the loss is shown (V145).
    lost = b.get("messagesLostInRestore")
    if isinstance(lost, dict) and lost.get("alertedAt"):
        import datetime
        at = datetime.datetime.fromisoformat(lost["alertedAt"].replace("Z", "+00:00"))
        if datetime.datetime.now(datetime.timezone.utc) - at <= datetime.timedelta(hours=1):
            print(f"{lost.get('alertedCount')} queued message(s) lost their body in the restore of "
                  f"{lost.get('restoredAt')} ({lost.get('recordedBy')}) and will never be sent. "
                  "Look for MESSAGES LOST IN A RESTORE in the logs (docs/13 \"After a restore\")"); sys.exit(1)
PY
) || fail "$verdict"

extra=""
[ "${#OPS[@]}" = 0 ] || extra=", no undelivered sign-in email"
echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) ok   almira at $BASE: live, ready, row-level security in force$extra"
