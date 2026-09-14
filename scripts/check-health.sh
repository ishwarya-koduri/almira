#!/usr/bin/env bash
# =============================================================================
# One outside look at a running Almira, for cron or an uptime monitor.
#
#   ./scripts/check-health.sh --base https://almira.example.in
#   ./scripts/check-health.sh --base http://127.0.0.1:8080 --alert-cmd './notify.sh'
#
# Checks, in order, and prints ONE line either way:
#
#   1. /health/live   answers 200            — the process is serving HTTP
#   2. /health/ready  answers 200            — database as the runtime role,
#                                              row-level security in force, Redis
#   3. /health        says rlsEnforced:true  — and an environment that is not
#                                              `development` (unless --allow-development)
#
# Exit 0 when all hold, 1 otherwise. With --alert-cmd, a failure also runs that
# command with the one-line reason as its only argument — your own mail, SMS or
# chat hook. Nothing is sent anywhere by this script on its own.
#
# It reads only the three health endpoints, which carry no household data
# (docs/17 §8), so it needs no credentials.
# =============================================================================
set -uo pipefail

BASE="" ALERT="" ALLOW_DEV=0 TIMEOUT=10
while [ $# -gt 0 ]; do
  case "$1" in
    --base) BASE="${2%/}"; shift 2;;
    --alert-cmd) ALERT="$2"; shift 2;;
    --allow-development) ALLOW_DEV=1; shift;;
    --timeout) TIMEOUT="$2"; shift 2;;
    -h|--help) sed -n '2,24p' "$0"; exit 0;;
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

status() { curl -s -o "$2" -w '%{http_code}' --max-time "$TIMEOUT" "$BASE$1" 2>/dev/null; }
body=$(mktemp); trap 'rm -f "$body"' EXIT

code=$(status /health/live "$body")
[ "$code" = 200 ] || fail "not serving (/health/live answered $code)"

code=$(status /health/ready "$body")
if [ "$code" != 200 ]; then
  fail "not ready (/health/ready answered $code: $(tr -d '\n' < "$body" | head -c 200))"
fi

code=$(status /health "$body")
[ "$code" = 200 ] || fail "/health answered $code"
verdict=$(python3 - "$body" "$ALLOW_DEV" <<'PY'
import json, sys
try:
    b = json.load(open(sys.argv[1]))
except ValueError:
    print("/health is not JSON"); sys.exit(1)
if b.get("rlsEnforced") is not True:
    print(f"row-level security is NOT in force (dbRole={b.get('dbRole')!r})"); sys.exit(1)
if sys.argv[2] != "1" and b.get("environment") == "development":
    print("running with development settings"); sys.exit(1)
PY
) || fail "$verdict"

echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) ok   almira at $BASE: live, ready, row-level security in force"
