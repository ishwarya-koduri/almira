#!/usr/bin/env bash
# =============================================================================
# The smallest test that proves a deployment is really working: two people in
# one household see different net worth, and both are right.
#
#   ./scripts/smoke-prod.sh https://almira.example.com
#
# Everything else in this product rests on that one property. If it holds
# through a real deployment — TLS, reverse proxy, connection pool, the runtime
# database role — then row-level security is enforced end to end, and a
# misconfiguration that would have exposed one family member's records to
# another would have shown up here as two identical totals.
#
# IT WRITES DATA. It signs in two throwaway users, creates a household called
# "Smoke test <timestamp>", and leaves it there — deleting a user is not
# something the API does, and it should not be. Run it against a staging
# deployment, or accept that a real one gains one obviously-named household.
# The last line prints the SQL to remove it.
#
# SIGN-IN CODES. A production server does not echo one-time codes. If the OTP
# provider is `log`, read them from the application log; otherwise they arrive
# by SMS. Either way you can pass them in:
#
#   SMOKE_CODE_A=123456 SMOKE_CODE_B=654321 ./scripts/smoke-prod.sh https://…
#
# and if they are not set, the script asks for each one as it needs it.
# =============================================================================
set -uo pipefail

BASE="${1:-}"
[ -n "$BASE" ] || { sed -n '2,28p' "$0"; exit 2; }
BASE="${BASE%/}"

PASS=0; FAIL=0
GREEN=$'\033[32m'; RED=$'\033[31m'; DIM=$'\033[2m'; BOLD=$'\033[1m'; OFF=$'\033[0m'

j() { python3 -c "
import sys, json
try: print(eval('json.load(sys.stdin)' + '''$1'''))
except Exception: print('')
" 2>/dev/null; }

money() { python3 -c "
import sys, json, decimal
d = json.load(sys.stdin, parse_float=decimal.Decimal, parse_int=decimal.Decimal)
try: print(decimal.Decimal(str(eval('d' + r'''$1'''))).quantize(decimal.Decimal('0.01')))
except Exception: print('')
" 2>/dev/null; }

ok()   { PASS=$((PASS+1)); echo "  ${GREEN}ok${OFF}   $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  ${RED}FAIL${OFF} $1"; [ -n "${2:-}" ] && echo "       ${DIM}$2${OFF}"; }
is()   { [ "$2" = "$3" ] && ok "$1" || bad "$1" "expected '$3', got '$2'"; }
isnt() { [ "$2" != "$3" ] && ok "$1" || bad "$1" "should not be '$3'"; }
section() { echo; echo "${BOLD}$1${OFF}"; }

api() {
  local token="$1" method="$2" path="$3" body="${4:-}"
  if [ -n "$body" ]; then
    curl -sS -X "$method" "$BASE$path" -H "Authorization: Bearer $token" \
      -H 'Content-Type: application/json' -d "$body"
  else
    curl -sS -X "$method" "$BASE$path" -H "Authorization: Bearer $token"
  fi
}

login() { # login <phone> <code-var-name> <label>
  local phone="$1" var="$2" label="$3" requested code
  requested=$(curl -sS -X POST "$BASE/api/v1/auth/otp/request" \
    -H 'Content-Type: application/json' -d "{\"phone\":\"$phone\"}")

  case "$requested" in
    *rate_limited*) echo "${RED}Sign-in is rate limited for this network.${OFF}" >&2; exit 2;;
  esac

  # A development server hands the code back; a real one does not.
  code=$(printf '%s' "$requested" | j "['developmentCode']")
  if [ -z "$code" ]; then
    code="$(eval "printf '%s' \"\${$var:-}\"")"
  fi
  if [ -z "$code" ]; then
    printf '  code sent to %s (%s) — enter it: ' "$phone" "$label" >&2
    read -r code < /dev/tty
  fi

  curl -sS -X POST "$BASE/api/v1/auth/otp/verify" -H 'Content-Type: application/json' \
    -d "{\"phone\":\"$phone\",\"code\":\"$code\"}" | j "['accessToken']"
}

echo "${BOLD}Almira smoke test — $BASE${OFF}"

# --- before anything is written ----------------------------------------------
section "The deployment itself"
HEALTH=$(curl -sS "$BASE/health") || { echo "${RED}No answer from $BASE${OFF}"; exit 1; }
is "the service is up"            "$(printf '%s' "$HEALTH" | j "['status']")"      "ok"
is "the database is reachable"    "$(printf '%s' "$HEALTH" | j "['database']")"    "up"
is "row-level security is enforced against the runtime role" \
   "$(printf '%s' "$HEALTH" | j "['rlsEnforced']")" "True"
ROLE=$(printf '%s' "$HEALTH" | j "['dbRole']")
isnt "and that role is not the schema owner" "$ROLE" "almira"
echo "       ${DIM}connected as: $ROLE${OFF}"

ENVIRONMENT=$(printf '%s' "$HEALTH" | j "['environment']")
if [ "$ENVIRONMENT" != "production" ]; then
  echo "  ${RED}WARN${OFF} this host reports environment='$ENVIRONMENT'."
  echo "       ${DIM}One-time codes may be echoed in responses and the encryption"
  echo "       checks are relaxed. Set ALMIRA_ENV=production.${OFF}"
fi

section "The web client is being served"
is "the app shell responds"  "$(curl -sS -o /dev/null -w '%{http_code}' "$BASE/")" "200"
is "the manifest is served as a manifest" \
   "$(curl -sSI "$BASE/manifest.webmanifest" | tr -d '\r' | awk 'tolower($1)=="content-type:"{print $2}')" \
   "application/manifest+json"
is "the service worker is served" \
   "$(curl -sS -o /dev/null -w '%{http_code}' "$BASE/sw.js")" "200"

# --- the property everything else rests on -----------------------------------
section "Two members, two different totals"
STAMP=$(date +%s)
PHONE_A="9$(printf '%09d' $(( (STAMP % 900000000) + 100000 )))"
PHONE_B="8$(printf '%09d' $(( (STAMP % 900000000) + 200000 )))"

A=$(login "$PHONE_A" SMOKE_CODE_A "the owner")
isnt "the first person can sign in" "$A" ""
[ -n "$A" ] || { echo "${RED}Cannot continue without a session.${OFF}"; exit 1; }

B=$(login "$PHONE_B" SMOKE_CODE_B "the second member")
isnt "the second person can sign in" "$B" ""
[ -n "$B" ] || { echo "${RED}Cannot continue without a session.${OFF}"; exit 1; }

HH=$(api "$A" POST /api/v1/households \
  "{\"name\":\"Smoke test $STAMP\",\"mode\":\"family\",\"defaultVisibility\":\"private\",\"displayName\":\"Smoke A\"}")
HID=$(printf '%s' "$HH" | j "['id']")
isnt "a household is created" "$HID" ""
[ -n "$HID" ] || { echo "${RED}Cannot continue without a household.${OFF}"; exit 1; }

MEMBER_B=$(api "$A" POST "/api/v1/households/$HID/members" \
  '{"displayName":"Smoke B","relationship":"spouse"}' | j "['id']")
INVITE=$(api "$A" POST "/api/v1/households/$HID/invitations" \
  "{\"memberId\":\"$MEMBER_B\",\"phone\":\"$PHONE_B\",\"role\":\"admin\"}" | j "['token']")
is "the second member joins as an admin" \
   "$(api "$B" POST /api/v1/invitations/accept "{\"token\":\"$INVITE\"}" | j "['role']")" "admin"

TYPE_GOLD=$(api "$A" GET "/api/v1/households/$HID/taxonomy" | python3 -c "
import sys, json
for category in json.load(sys.stdin):
    for t in category['types']:
        if t['code'] == 'gold_physical':
            print(t['id']); raise SystemExit
")

api "$A" POST "/api/v1/households/$HID/investments" \
  "{\"typeId\":\"$TYPE_GOLD\",\"title\":\"Shared gold\",\"investedAmount\":100000,\"visibility\":\"household\"}" >/dev/null
api "$A" POST "/api/v1/households/$HID/investments" \
  "{\"typeId\":\"$TYPE_GOLD\",\"title\":\"Private gold\",\"investedAmount\":900000,\"visibility\":\"private\"}" >/dev/null

TOTAL_A=$(api "$A" GET "/api/v1/households/$HID/dashboard?scope=household" | money "['totalAssets']")
TOTAL_B=$(api "$B" GET "/api/v1/households/$HID/dashboard?scope=household" | money "['totalAssets']")

is "the owner sees both holdings"                  "$TOTAL_A" "1000000.00"
is "the admin sees only the shared one"            "$TOTAL_B" "100000.00"
is "the private holding is not readable by the admin" \
   "$(api "$B" GET "/api/v1/households/$HID/investments" | python3 -c "
import sys, json
print('|'.join(i['title'] for i in json.load(sys.stdin)))")" "Shared gold"

echo
if [ "$FAIL" -eq 0 ]; then
  echo "${GREEN}${BOLD}All $PASS checks passed.${OFF} Privacy holds through this deployment."
else
  echo "${RED}${BOLD}$FAIL of $((PASS+FAIL)) checks failed.${OFF}"
  echo "If the two totals were the same, STOP: the application is reading as a role"
  echo "that bypasses row-level security, and every member can see every record."
fi

echo
echo "${DIM}This left one household behind. To remove it:"
echo "  delete from households where name = 'Smoke test $STAMP';${OFF}"
exit $(( FAIL > 0 ? 1 : 0 ))
