#!/usr/bin/env bash
# =============================================================================
# Phase 2 end-to-end test, over real HTTP against a running backend.
#
# The Kotlin suite proves each piece; this proves they compose — that a
# renewal moves the totals the way the goal, the tax pack and the export all
# agree it should, and that every derived surface (returns, goals, tax,
# reports, exports) hides exactly what the records it derives from hide.
# Derived data leaks as readily as originals; this is where that is checked
# through the whole stack.
#
#   ./scripts/e2e-phase2.sh [base-url]
# =============================================================================
set -uo pipefail
BASE="${1:-http://localhost:8080}"
PASS=0; FAIL=0
GREEN=$'\033[32m'; RED=$'\033[31m'; DIM=$'\033[2m'; BOLD=$'\033[1m'; OFF=$'\033[0m'

# Both gates before anything is written: a local address, and a server that says
# it is in development. This suite creates users, households and holdings — the
# way that goes wrong is a mistyped URL at one in the morning, not an attacker.
# shellcheck source=scripts/lib/require-development-server.sh
. "$(dirname "$0")/lib/require-development-server.sh"
require_development_server "$BASE" \
  "This suite signs up users and writes holdings, goals, imports and templates."

jq_() { python3 -c "
import sys,json
d=json.load(sys.stdin)
try: print(eval('d'+'''$1'''))
except Exception: print('')
" 2>/dev/null; }

money() { python3 -c "
import sys, json, decimal
d = json.load(sys.stdin, parse_float=decimal.Decimal, parse_int=decimal.Decimal)
try:
    print(decimal.Decimal(str(eval('d' + r'''$1'''))).quantize(decimal.Decimal('0.01')))
except Exception:
    print('')
" 2>/dev/null; }

count() { python3 -c "
import sys, json
d = json.load(sys.stdin)
print(len(d) if isinstance(d, list) else -1)
" 2>/dev/null; }

ok()   { PASS=$((PASS+1)); echo "  ${GREEN}ok${OFF}   $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  ${RED}FAIL${OFF} $1"; [ -n "${2:-}" ] && echo "       ${DIM}got: $2${OFF}"; }
is()   { [ "$2" = "$3" ] && ok "$1" || bad "$1" "expected '$3', got '$2'"; }
isnt() { [ "$2" != "$3" ] && ok "$1" || bad "$1" "should not be '$3'"; }
has()  { case "$2" in *"$3"*) ok "$1";; *) bad "$1" "'$2' does not contain '$3'";; esac; }
hasnt() { case "$2" in *"$3"*) bad "$1" "'$2' contains '$3'";; *) ok "$1";; esac; }
section() { echo; echo "${BOLD}$1${OFF}"; }

login() {
  local phone="$1" requested code
  requested=$(curl -s -X POST "$BASE/api/v1/auth/otp/request" -H 'Content-Type: application/json' \
        -d "{\"phone\":\"$phone\"}")
  # The per-network sign-in cap counts a laptop's suites as one attacker, which
  # is correct and reads like a broken build. Say which it is.
  case "$requested" in
    *rate_limited*)
      echo "${RED}Sign-in is rate limited for this network.${OFF}" >&2
      echo "  Development raises the cap: run through ./scripts/dev.sh, or set" >&2
      echo "  ALMIRA_OTP_MAX_PER_IP_PER_HOUR=500 on the server." >&2
      exit 2;;
  esac
  code=$(echo "$requested" | jq_ "['developmentCode']")
  curl -s -X POST "$BASE/api/v1/auth/otp/verify" -H 'Content-Type: application/json' \
    -d "{\"phone\":\"$phone\",\"code\":\"$code\"}" | jq_ "['accessToken']"
}
api() {
  local token="$1" method="$2" path="$3" body="${4:-}"
  if [ -n "$body" ]; then
    curl -s -X "$method" "$BASE$path" -H "Authorization: Bearer $token" \
      -H 'Content-Type: application/json' -d "$body"
  else
    curl -s -X "$method" "$BASE$path" -H "Authorization: Bearer $token"
  fi
}
status() {
  local token="$1" method="$2" path="$3" body="${4:-}"
  if [ -n "$body" ]; then
    curl -s -o /dev/null -w '%{http_code}' -X "$method" "$BASE$path" \
      -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -d "$body"
  else
    curl -s -o /dev/null -w '%{http_code}' -X "$method" "$BASE$path" -H "Authorization: Bearer $token"
  fi
}
type_id() { # type_id <token> <hid> <code>
  api "$1" GET "/api/v1/households/$2/taxonomy" | python3 -c "
import sys, json
for category in json.load(sys.stdin):
    for t in category['types']:
        if t['code'] == '$3':
            print(t['id']); raise SystemExit
"
}

STAMP=$(date +%s)
P_ISH="9$(printf '%09d' $(( (STAMP % 900000000) + 400000 )))"
P_RAVI="8$(printf '%09d' $(( (STAMP % 900000000) + 500000 )))"

section "A household with one shared holding and one private one"
ISH=$(login "$P_ISH");   isnt "Ishwarya signs in" "$ISH" ""
RAVI=$(login "$P_RAVI"); isnt "Ravi signs in" "$RAVI" ""

HH=$(api "$ISH" POST /api/v1/households \
  '{"name":"Koduri","mode":"family","defaultVisibility":"private","displayName":"Ishwarya"}')
HID=$(echo "$HH" | jq_ "['id']")
RAVI_MEM=$(api "$ISH" POST "/api/v1/households/$HID/members" \
  '{"displayName":"Ravi","relationship":"spouse"}' | jq_ "['id']")
INV=$(api "$ISH" POST "/api/v1/households/$HID/invitations" \
  "{\"memberId\":\"$RAVI_MEM\",\"phone\":\"$P_RAVI\",\"role\":\"admin\"}")
api "$RAVI" POST /api/v1/invitations/accept "{\"token\":\"$(echo "$INV" | jq_ "['token']")\"}" >/dev/null
is "Ravi is an admin of the household" \
   "$(api "$RAVI" GET "/api/v1/households/$HID" | jq_ "['myRole']")" "admin"

TYPE_FD=$(type_id "$ISH" "$HID" fd)
TYPE_GOLD=$(type_id "$ISH" "$HID" gold_physical)

FD=$(api "$ISH" POST "/api/v1/households/$HID/investments" \
  "{\"typeId\":\"$TYPE_FD\",\"title\":\"ICICI FD\",\"investedAmount\":400000,
    \"startDate\":\"2025-04-01\",\"maturityDate\":\"2026-04-01\",
    \"attributes\":{\"interest_rate\":7.1},\"visibility\":\"household\"}")
FD_ID=$(echo "$FD" | jq_ "['id']")
isnt "a shared FD is captured" "$FD_ID" ""

GOLD=$(api "$ISH" POST "/api/v1/households/$HID/investments" \
  "{\"typeId\":\"$TYPE_GOLD\",\"title\":\"Wedding gold\",\"investedAmount\":600000,
    \"visibility\":\"private\"}")
GOLD_ID=$(echo "$GOLD" | jq_ "['id']")

section "Quick add proposes, and saves nothing"
PARSE=$(api "$ISH" POST "/api/v1/households/$HID/capture/parse-text" \
  '{"text":"1L gold 6.3g at ICICI 3 Aug"}')
has "the amount is read in Indian shorthand" \
    "$(echo "$PARSE" | python3 -c "
import sys, json
print('|'.join(f['display'] for f in json.load(sys.stdin)['fields']))")" "₹1,00,000"
has "the type is matched from the household's own taxonomy" \
    "$(echo "$PARSE" | python3 -c "
import sys, json
print('|'.join(f['display'] for f in json.load(sys.stdin)['fields']))")" "Physical Gold"
is  "nothing was saved by parsing" \
    "$(api "$ISH" GET "/api/v1/households/$HID/investments" | count)" "2"

section "Templates"
TPL=$(api "$ISH" POST "/api/v1/households/$HID/templates" \
  "{\"name\":\"Quarterly FD\",\"typeId\":\"$TYPE_FD\",\"title\":\"ICICI FD\",
    \"investedAmount\":200000,\"attributes\":{\"interest_rate\":7.1},
    \"visibility\":\"household\"}")
TPL_ID=$(echo "$TPL" | jq_ "['id']")
APPLIED=$(api "$ISH" POST "/api/v1/households/$HID/templates/$TPL_ID/apply" \
  '{"investedAmount":250000,"startDate":"2026-04-01","visibility":"household"}')
is "applying a template saves a record with what was different this time" \
   "$(echo "$APPLIED" | money "['investment']['investedAmount']")" "250000.00"
is "a template made from nothing private may be shared" \
   "$(echo "$TPL" | jq_ "['visibility']")" "household"
is "a template made from a private record cannot be shared with the household" \
   "$(api "$ISH" POST "/api/v1/households/$HID/templates" \
      "{\"name\":\"Gold shape\",\"fromInvestmentId\":\"$GOLD_ID\",\"visibility\":\"household\"}" \
      | jq_ "['error']['code']")" "template_would_widen"
APPLIED_ID=$(echo "$APPLIED" | jq_ "['id']")
api "$ISH" DELETE "/api/v1/households/$HID/investments/$APPLIED_ID" >/dev/null

section "Goals"
GOAL=$(api "$ISH" POST "/api/v1/households/$HID/goals" \
  '{"name":"Aarav UG","targetAmount":1600000,"targetDate":"2039-06-01","visibility":"household"}')
GOAL_ID=$(echo "$GOAL" | jq_ "['id']")
api "$ISH" POST "/api/v1/households/$HID/goals/$GOAL_ID/investments" \
  "{\"investmentId\":\"$FD_ID\",\"allocationPct\":100}" >/dev/null
is "the goal is funded by what points at it" \
   "$(api "$ISH" GET "/api/v1/households/$HID/goals/$GOAL_ID" | money "['funded']")" "400000.00"
is "a fully allocated holding drops out of the unallocated list" \
   "$(api "$ISH" GET "/api/v1/households/$HID/goals-unallocated" \
      | python3 -c "
import sys, json
print('|'.join(h['title'] for h in json.load(sys.stdin)))")" "Wedding gold"

section "Renewal keeps the history and counts once"
BEFORE=$(api "$ISH" GET "/api/v1/households/$HID/dashboard?scope=household" | money "['totalAssets']")
is "assets before the renewal" "$BEFORE" "1000000.00"
ROLL=$(api "$ISH" POST "/api/v1/households/$HID/investments/$FD_ID/rollover" \
  '{"investedAmount":428400,"maturityDate":"2027-04-01","visibility":"household"}')
is "the old record is kept, marked matured" \
   "$(echo "$ROLL" | jq_ "['previous']['status']")" "matured"
is "the new record starts where the old one ended" \
   "$(echo "$ROLL" | jq_ "['created']['investment']['startDate']")" "2026-04-01"
is "the new record points back at the one it renewed" \
   "$(echo "$ROLL" | jq_ "['created']['investment']['rolledFromId']")" "$FD_ID"
is "the renewal counts once, not twice" \
   "$(api "$ISH" GET "/api/v1/households/$HID/dashboard?scope=household" | money "['totalAssets']")" \
   "1028400.00"
is "and the goal is funded by the renewal, not by both" \
   "$(api "$ISH" GET "/api/v1/households/$HID/goals/$GOAL_ID" | money "['funded']")" "428400.00"

section "Returns are computed only where the data supports them"
NEW_FD=$(echo "$ROLL" | jq_ "['created']['id']")
PERF=$(api "$ISH" GET "/api/v1/households/$HID/investments/$NEW_FD/returns")
# Nulls are omitted from the JSON, so the check is that no figure is present —
# not that it is the string "None", which would also be true of a missing key.
is "no valuation yet means no invented return" \
   "$(echo "$PERF" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(any(d.get(k) is not None for k in ('xirr', 'cagr', 'absoluteReturn')))")" "False"
isnt "and it says why, rather than showing a blank" "$(echo "$PERF" | jq_ "['note']")" ""
api "$ISH" POST "/api/v1/households/$HID/investments/$NEW_FD/valuations" \
  '{"value":450000,"asOfDate":"2026-09-01"}' >/dev/null
is "with a valuation, the gain on paper is the difference" \
   "$(api "$ISH" GET "/api/v1/households/$HID/investments/$NEW_FD/returns" | money "['unrealizedGain']")" \
   "21600.00"

section "Tax"
PACK=$(api "$ISH" GET "/api/v1/households/$HID/tax/pack?fy=2026-27")
is "the financial year is 1 April to 31 March" "$(echo "$PACK" | jq_ "['financialYear']")" "2026-27"
has "the disclaimer travels with the numbers" "$(echo "$PACK" | jq_ "['disclaimer']")" "not tax advice"
is "the 80C limit is a lakh and a half" \
   "$(echo "$PACK" | money "['deductions'][0]['limit']")" "150000.00"

section "Import: preview, then commit"
CSV=$'Name,Amount,Opened\nAxis FD,100000,01/04/2025\nBroken,not a number,01/04/2025\n'
REQ="{\"typeId\":\"$TYPE_FD\",\"mapping\":{\"title\":\"Name\",\"investedAmount\":\"Amount\",\"startDate\":\"Opened\"},\"visibility\":\"household\",\"dryRun\":true}"
TMP=$(mktemp -t almira-import).csv
printf '%s' "$CSV" > "$TMP"
DRY=$(curl -s -X POST "$BASE/api/v1/households/$HID/import" -H "Authorization: Bearer $ISH" \
  -F "file=@$TMP;type=text/csv" -F "request=$REQ")
is "a dry run saves nothing but says what would happen" "$(echo "$DRY" | jq_ "['wouldImport']")" "2"
is "and reports nothing as imported, because nothing was" "$(echo "$DRY" | jq_ "['imported']")" "0"
has "an unreadable cell is named before anything is written" \
    "$(echo "$DRY" | python3 -c "
import sys, json
print(' '.join(r.get('message') or '' for r in json.load(sys.stdin)['rows']))")" "not a number"
RUN=$(curl -s -X POST "$BASE/api/v1/households/$HID/import" -H "Authorization: Bearer $ISH" \
  -F "file=@$TMP;type=text/csv" -F "request=${REQ/true/false}")
is "the import lands what the preview promised" "$(echo "$RUN" | jq_ "['imported']")" "2"
AGAIN=$(curl -s -X POST "$BASE/api/v1/households/$HID/import" -H "Authorization: Bearer $ISH" \
  -F "file=@$TMP;type=text/csv" -F "request=${REQ/true/false}")
is "importing the same file again adds nothing" "$(echo "$AGAIN" | jq_ "['duplicates']")" "2"
rm -f "$TMP"

section "Reports"
COMP=$(api "$ISH" GET "/api/v1/households/$HID/reports/completeness")
isnt "completeness names the next thing to do" "$(echo "$COMP" | jq_ "['nextStep']")" ""
has "and says what it is measuring" "$(echo "$COMP" | jq_ "['note']")" "usable"
INS=$(api "$ISH" GET "/api/v1/households/$HID/reports/insights")
has "insights carry the not-advice note" "$(echo "$INS" | jq_ "['disclaimer']")" "not financial advice"
isnt "and report where the money is bunched" "$(echo "$INS" | jq_ "['concentration'][0]['label']")" ""

section "Everything derived obeys the same privacy rules"
is "Ravi cannot see the private gold" \
   "$(status "$RAVI" GET "/api/v1/households/$HID/investments/$GOLD_ID")" "404"
RAVI_TOTAL=$(api "$RAVI" GET "/api/v1/households/$HID/dashboard?scope=household" | money "['totalAssets']")
ISH_TOTAL=$(api "$ISH" GET "/api/v1/households/$HID/dashboard?scope=household" | money "['totalAssets']")
is "the private gold contributes nothing to Ravi's total" \
   "$(python3 -c "import decimal;print(decimal.Decimal('$ISH_TOTAL') - decimal.Decimal('$RAVI_TOTAL'))")" \
   "600000.00"
is "nor to his completeness score's record count" \
   "$(python3 -c "
import decimal
print(decimal.Decimal('$(api "$ISH" GET "/api/v1/households/$HID/reports/completeness" | jq_ "['recordCount']")')
      - decimal.Decimal('$(api "$RAVI" GET "/api/v1/households/$HID/reports/completeness" | jq_ "['recordCount']")'))")" \
   "1"
hasnt "nor to his export" \
   "$(curl -s "$BASE/api/v1/households/$HID/reports/export?format=csv" -H "Authorization: Bearer $RAVI")" \
   "Wedding gold"
has "while his own export still holds what he may see" \
   "$(curl -s "$BASE/api/v1/households/$HID/reports/export?format=csv" -H "Authorization: Bearer $RAVI")" \
   "Axis FD"
is "a PDF export is a PDF" \
   "$(curl -s "$BASE/api/v1/households/$HID/reports/export?format=pdf" -H "Authorization: Bearer $RAVI" \
     | head -c 4)" "%PDF"
is "an unknown export format is refused by name" \
   "$(api "$RAVI" GET "/api/v1/households/$HID/reports/export?format=doc" | jq_ "['error']['code']")" \
   "format_unsupported"

echo
if [ "$FAIL" -eq 0 ]; then
  echo "${GREEN}${BOLD}All $PASS checks passed.${OFF}"
else
  echo "${RED}${BOLD}$FAIL of $((PASS+FAIL)) checks failed.${OFF}"
fi
exit $(( FAIL > 0 ? 1 : 0 ))
