#!/usr/bin/env bash
# =============================================================================
# Phase 0 end-to-end test, over real HTTP against a running backend.
#
# The database-level suite (db/tests/rls_privacy_test.sql) proves the policies
# hold in SQL. This proves the same guarantees survive the whole stack --
# controllers, services, connection pooling, JSON. A privacy model that is
# correct in the database and leaky in an endpoint is still a leak.
#
#   ./scripts/e2e-phase0.sh [base-url]
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
  "This suite signs up users, creates households and writes holdings, debts and documents."

j() { python3 -c "import sys,json;d=json.load(sys.stdin);print(eval('d'+'$1'))" 2>/dev/null; }
jq_() { python3 -c "
import sys,json
d=json.load(sys.stdin)
try: print(eval('d'+'''$1'''))
except Exception: print('')
" 2>/dev/null; }

# Money must be compared as a decimal. json.load() turns 5050000.00 into the
# float 5050000.0, so comparing its printed form against the API's own decimal
# rendering fails for reasons that have nothing to do with correctness.
money() { python3 -c "
import sys, json, decimal
d = json.load(sys.stdin, parse_float=decimal.Decimal, parse_int=decimal.Decimal)
try:
    v = eval('d' + r'''$1''')
    print(decimal.Decimal(str(v)).quantize(decimal.Decimal('0.01')))
except Exception:
    print('')
" 2>/dev/null; }

minus() { python3 -c "
import decimal, sys
print(decimal.Decimal('$1') - decimal.Decimal('$2'))
" 2>/dev/null; }

count() { python3 -c "
import sys, json
d = json.load(sys.stdin)
print(len(d) if isinstance(d, list) else -1)
" 2>/dev/null; }

titles() { python3 -c "
import sys, json
d = json.load(sys.stdin)
print('|'.join(sorted(i['title'] for i in d)) if isinstance(d, list) else '<error>')
" 2>/dev/null; }

ok()   { PASS=$((PASS+1)); echo "  ${GREEN}ok${OFF}   $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  ${RED}FAIL${OFF} $1"; [ -n "${2:-}" ] && echo "       ${DIM}got: $2${OFF}"; }
is()   { [ "$2" = "$3" ] && ok "$1" || bad "$1" "expected '$3', got '$2'"; }
isnt() { [ "$2" != "$3" ] && ok "$1" || bad "$1" "should not be '$3'"; }
section() { echo; echo "${BOLD}$1${OFF}"; }

# --- auth helpers ------------------------------------------------------------
login() { # login <phone> -> access token
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
api() { # api <token> <method> <path> [body]
  local token="$1" method="$2" path="$3" body="${4:-}"
  if [ -n "$body" ]; then
    curl -s -X "$method" "$BASE$path" -H "Authorization: Bearer $token" \
      -H 'Content-Type: application/json' -d "$body"
  else
    curl -s -X "$method" "$BASE$path" -H "Authorization: Bearer $token"
  fi
}
status() { # status <token> <method> <path> [body]
  local token="$1" method="$2" path="$3" body="${4:-}"
  if [ -n "$body" ]; then
    curl -s -o /dev/null -w '%{http_code}' -X "$method" "$BASE$path" \
      -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -d "$body"
  else
    curl -s -o /dev/null -w '%{http_code}' -X "$method" "$BASE$path" -H "Authorization: Bearer $token"
  fi
}

STAMP=$(date +%s)
P_ISH="9$(printf '%09d' $(( (STAMP % 900000000) + 100000 )))"
P_RAVI="8$(printf '%09d' $(( (STAMP % 900000000) + 200000 )))"
P_OUT="7$(printf '%09d' $(( (STAMP % 900000000) + 300000 )))"

section "Sign-in"
ISH=$(login "$P_ISH");   isnt "Ishwarya signs in with a phone OTP" "$ISH" ""
RAVI=$(login "$P_RAVI"); isnt "Ravi signs in with a phone OTP" "$RAVI" ""
OUT=$(login "$P_OUT");   isnt "An unrelated user signs in" "$OUT" ""

section "Household and members"
HH=$(api "$ISH" POST /api/v1/households '{"name":"Koduri","mode":"family","defaultVisibility":"private","displayName":"Ishwarya"}')
HID=$(echo "$HH" | jq_ "['id']")
isnt "household created" "$HID" ""
is   "default visibility is private" "$(echo "$HH" | jq_ "['defaultVisibility']")" "private"
is   "creator is the owner" "$(echo "$HH" | jq_ "['myRole']")" "owner"
ISH_MEM=$(echo "$HH" | jq_ "['myMemberId']")

AARAV=$(api "$ISH" POST "/api/v1/households/$HID/members" '{"displayName":"Aarav","relationship":"child","dateOfBirth":"2015-04-02"}')
is "a child is flagged as a minor" "$(echo "$AARAV" | jq_ "['isMinor']")" "True"
is "a child has no login of their own" "$(echo "$AARAV" | jq_ "['isManaged']")" "True"

RAVI_PLACEHOLDER=$(api "$ISH" POST "/api/v1/households/$HID/members" '{"displayName":"Ravi","relationship":"spouse"}')
RAVI_MEM=$(echo "$RAVI_PLACEHOLDER" | jq_ "['id']")

section "Invitation claims the existing member (merge, not duplicate)"
INV=$(api "$ISH" POST "/api/v1/households/$HID/invitations" "{\"memberId\":\"$RAVI_MEM\",\"phone\":\"$P_RAVI\",\"role\":\"admin\"}")
TOKEN=$(echo "$INV" | jq_ "['token']")
isnt "invitation issued" "$TOKEN" ""
ACC=$(api "$RAVI" POST /api/v1/invitations/accept "{\"token\":\"$TOKEN\"}")
is "Ravi joins as admin" "$(echo "$ACC" | jq_ "['role']")" "admin"
is "Ravi claims the existing member row, not a new one" "$(echo "$ACC" | jq_ "['memberId']")" "$RAVI_MEM"
MEMBERS=$(api "$ISH" GET "/api/v1/households/$HID/members")
is "the household still has exactly 3 people" "$(echo "$MEMBERS" | count)" "3"

section "Taxonomy"
TAX=$(api "$ISH" GET "/api/v1/households/$HID/taxonomy")
CATS=$(echo "$TAX" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)))')
is "12 categories are seeded" "$CATS" "12"
TYPE_FD=$(echo "$TAX" | python3 -c '
import sys,json
for c in json.load(sys.stdin):
    for t in c["types"]:
        if t["code"]=="fd": print(t["id"]); break')
TYPE_GOLD=$(echo "$TAX" | python3 -c '
import sys,json
for c in json.load(sys.stdin):
    for t in c["types"]:
        if t["code"]=="gold_physical": print(t["id"]); break')
TYPE_UNIV=$(echo "$TAX" | python3 -c '
import sys,json
for c in json.load(sys.stdin):
    for t in c["types"]:
        if t["code"]=="universal": print(t["id"]); break')
TYPE_MF=$(echo "$TAX" | python3 -c '
import sys,json
for c in json.load(sys.stdin):
    for t in c["types"]:
        if t["code"]=="mf_sip": print(t["id"]); break')
isnt "the FD template exists" "$TYPE_FD" ""
ESS=$(echo "$TAX" | python3 -c '
import sys,json
for c in json.load(sys.stdin):
    for t in c["types"]:
        if t["code"]=="fd":
            s=t["schema"]
            n=len([k for k,v in s["common"].items() if v.get("group")=="essential"])
            n+=len([f for f in s["fields"] if f.get("group")=="essential"])
            print(n)')
[ "$ESS" -le 6 ] && ok "the FD form shows $ESS essentials, the rest behind More details" \
                 || bad "FD essentials should be <= 6" "$ESS"

section "Capture"
FD=$(api "$ISH" POST "/api/v1/households/$HID/investments" "{
  \"typeId\":\"$TYPE_FD\",\"title\":\"SBI FD (retirement buffer)\",
  \"investedAmount\":500000,\"maturityDate\":\"2029-03-31\",
  \"visibility\":\"private\",
  \"attributes\":{\"interest_rate\":\"7.1\",\"payout\":\"cumulative\",\"receipt_no\":\"FD-88213\"}}")
FD_ID=$(echo "$FD" | jq_ "['id']")
isnt "a private FD is captured" "$FD_ID" ""
is   "it is visible to its owner" "$(echo "$FD" | jq_ "['visibleToYou']")" "True"
is   "value basis is at_cost with no valuation yet" "$(echo "$FD" | jq_ "['investment']['valueBasis']")" "at_cost"

GOLD=$(api "$ISH" POST "/api/v1/households/$HID/investments" "{
  \"typeId\":\"$TYPE_GOLD\",\"title\":\"Wedding coins\",
  \"investedAmount\":100000,\"quantity\":6.3,\"unit\":\"g\",
  \"visibility\":\"household\",
  \"attributes\":{\"purity\":\"22k\",\"making_charges\":\"4500\"},
  \"storageLocation\":\"Home locker\"}")
GOLD_ID=$(echo "$GOLD" | jq_ "['id']")
isnt "household-shared gold is captured" "$GOLD_ID" ""

SCOPED=$(api "$ISH" POST "/api/v1/households/$HID/investments" "{
  \"typeId\":\"$TYPE_FD\",\"title\":\"HDFC FD (shared with Ravi)\",
  \"investedAmount\":200000,\"maturityDate\":\"2027-01-15\",
  \"visibility\":\"scoped\",\"visibleToMemberIds\":[\"$RAVI_MEM\"],
  \"attributes\":{\"interest_rate\":\"7.4\"}}")
SCOPED_ID=$(echo "$SCOPED" | jq_ "['id']")
isnt "a scoped FD is captured" "$SCOPED_ID" ""

JOINT=$(api "$ISH" POST "/api/v1/households/$HID/investments" "{
  \"typeId\":\"$TYPE_UNIV\",\"title\":\"Flat, Kakinada\",
  \"investedAmount\":4000000,\"visibility\":\"private\",
  \"owners\":[{\"memberId\":\"$ISH_MEM\",\"sharePct\":50},{\"memberId\":\"$RAVI_MEM\",\"sharePct\":50}],
  \"attributes\":{\"what_it_is\":\"Two-bedroom flat\"}}")
JOINT_ID=$(echo "$JOINT" | jq_ "['id']")
isnt "a jointly owned record is captured" "$JOINT_ID" ""

RAVI_MF=$(api "$RAVI" POST "/api/v1/households/$HID/investments" "{
  \"typeId\":\"$TYPE_MF\",\"title\":\"Parag Parikh Flexi Cap\",
  \"investedAmount\":300000,\"visibility\":\"private\",
  \"attributes\":{\"scheme_name\":\"PPFAS Flexi Cap\",\"sip_amount\":\"25000\",\"sip_day\":\"5\"}}")
RAVI_MF_ID=$(echo "$RAVI_MF" | jq_ "['id']")
isnt "Ravi captures his own private SIP" "$RAVI_MF_ID" ""

section "Record anything: the universal type with a custom money field"
CUSTOM=$(api "$ISH" POST "/api/v1/households/$HID/investments" "{
  \"typeId\":\"$TYPE_UNIV\",\"title\":\"Stake in Meera's bakery\",
  \"visibility\":\"household\",
  \"customFields\":[
    {\"key\":\"stake_value\",\"label\":\"What the stake is worth\",\"dataType\":\"money\",\"countsTowardValue\":true},
    {\"key\":\"handshake_only\",\"label\":\"Only a handshake\",\"dataType\":\"bool\"}],
  \"attributes\":{\"what_it_is\":\"20% of a bakery\",\"stake_value\":\"250000\",\"handshake_only\":true}}")
CUSTOM_ID=$(echo "$CUSTOM" | jq_ "['id']")
isnt "an asset the app never predefined is captured" "$CUSTOM_ID" ""
is "its value comes from the custom money field" \
   "$(echo "$CUSTOM" | jq_ "['investment']['valueBasis']")" "custom_field"
is "and that value is counted" \
   "$(echo "$CUSTOM" | money "['investment']['value']")" "250000.00"

section "Type-aware validation"
BAD=$(api "$ISH" POST "/api/v1/households/$HID/investments" "{
  \"typeId\":\"$TYPE_GOLD\",\"title\":\"Bad purity\",\"investedAmount\":1000,
  \"attributes\":{\"purity\":\"platinum\"}}")
is "an invalid choice is refused" "$(echo "$BAD" | jq_ "['error']['code']")" "attributes_invalid"
TYPO=$(api "$ISH" POST "/api/v1/households/$HID/investments" "{
  \"typeId\":\"$TYPE_GOLD\",\"title\":\"Typo\",\"investedAmount\":1000,
  \"attributes\":{\"purtiy\":\"22k\"}}")
is "a misspelled field is refused rather than silently stored" \
   "$(echo "$TYPO" | jq_ "['error']['code']")" "attribute_unknown"
SHARES=$(api "$ISH" POST "/api/v1/households/$HID/investments" "{
  \"typeId\":\"$TYPE_UNIV\",\"title\":\"Bad shares\",\"investedAmount\":1000,
  \"owners\":[{\"memberId\":\"$ISH_MEM\",\"sharePct\":60},{\"memberId\":\"$RAVI_MEM\",\"sharePct\":30}]}")
is "ownership shares that don't total 100% are refused" \
   "$(echo "$SHARES" | jq_ "['error']['code']")" "shares_must_total_100"

section "Privacy through the API"
RAVI_LIST=$(api "$RAVI" GET "/api/v1/households/$HID/investments")
RAVI_TITLES=$(echo "$RAVI_LIST" | titles)
case "$RAVI_TITLES" in
  *"SBI FD (retirement buffer)"*) bad "an admin must not see another member's private FD" "$RAVI_TITLES";;
  *) ok "an admin does NOT see another member's private FD";;
esac
case "$RAVI_TITLES" in
  *"Wedding coins"*) ok "the admin does see household-shared gold";;
  *) bad "household-shared gold should be visible" "$RAVI_TITLES";;
esac
case "$RAVI_TITLES" in
  *"HDFC FD (shared with Ravi)"*) ok "the admin sees the record scoped to him";;
  *) bad "scoped record should be visible" "$RAVI_TITLES";;
esac
case "$RAVI_TITLES" in
  *"Flat, Kakinada"*) ok "a co-owner sees a joint record marked private";;
  *) bad "co-owner should see the joint record" "$RAVI_TITLES";;
esac

is "fetching another member's private record returns 404, not 403" \
   "$(status "$RAVI" GET "/api/v1/households/$HID/investments/$FD_ID")" "404"
is "editing it is refused too" \
   "$(status "$RAVI" PATCH "/api/v1/households/$HID/investments/$FD_ID" '{"version":1,"title":"hijacked"}')" "404"
is "deleting it is refused too" \
   "$(status "$RAVI" DELETE "/api/v1/households/$HID/investments/$FD_ID")" "404"
is "an unrelated user cannot reach the household at all" \
   "$(status "$OUT" GET "/api/v1/households/$HID/investments")" "404"

section "Totals must not leak"
ISH_DASH=$(api "$ISH"  GET "/api/v1/households/$HID/dashboard?scope=household")
RAVI_DASH=$(api "$RAVI" GET "/api/v1/households/$HID/dashboard?scope=household")
ISH_TOTAL=$(echo "$ISH_DASH"  | money "['totalAssets']")
RAVI_TOTAL=$(echo "$RAVI_DASH" | money "['totalAssets']")
# Ishwarya: FD 500,000 + gold 100,000 + scoped 200,000 + flat 4,000,000
#           + bakery 250,000 + Ravi's SIP? no (private to him)      = 5,050,000
# Ravi:     gold 100,000 + scoped 200,000 + flat 4,000,000
#           + bakery 250,000 + his SIP 300,000                      = 4,850,000
is "the owner's total includes her private FD" "$ISH_TOTAL" "5050000.00"
is "the admin's total excludes it entirely"    "$RAVI_TOTAL" "4850000.00"
# No debts recorded yet, so net worth and total assets are the same figure —
# which is itself worth asserting: the subtraction must be a no-op at zero.
is "net worth equals total assets when nothing is owed" \
   "$(echo "$ISH_DASH" | money "['netWorth']")" "$ISH_TOTAL"
is "amount-in-words is rendered server-side" \
   "$(echo "$ISH_DASH" | jq_ "['netWorthInWords']")" "Fifty Lakh Fifty Thousand"
is "Indian grouping is applied" \
   "$(echo "$ISH_DASH" | jq_ "['totalAssetsFormatted']")" "₹50,50,000"

ME_DASH=$(api "$RAVI" GET "/api/v1/households/$HID/dashboard?scope=me")
# Ravi's own share: his SIP 300,000 + half the flat 2,000,000 = 2,300,000
is "the member lens splits a joint holding by share" \
   "$(echo "$ME_DASH" | money "['totalAssets']")" "2300000.00"

section "Revoking a scoped grant"
api "$ISH" PATCH "/api/v1/households/$HID/investments/$SCOPED_ID/visibility" \
   '{"visibility":"private"}' > /dev/null
is "the scoped record disappears for Ravi at once" \
   "$(status "$RAVI" GET "/api/v1/households/$HID/investments/$SCOPED_ID")" "404"
RAVI_TOTAL2=$(api "$RAVI" GET "/api/v1/households/$HID/dashboard?scope=household" | money "['totalAssets']")
is "and its amount leaves his total"  "$RAVI_TOTAL2" "4650000.00"

section "Concurrency and idempotency"
V=$(api "$ISH" GET "/api/v1/households/$HID/investments/$GOLD_ID" | jq_ "['version']")
api "$ISH" PATCH "/api/v1/households/$HID/investments/$GOLD_ID" "{\"version\":$V,\"title\":\"Wedding coins (2018)\"}" >/dev/null
is "a second write with the stale version is rejected" \
   "$(status "$ISH" PATCH "/api/v1/households/$HID/investments/$GOLD_ID" "{\"version\":$V,\"title\":\"clobber\"}")" "409"

OFFLINE_ID=$(python3 -c 'import uuid;print(uuid.uuid4())')
BODY="{\"id\":\"$OFFLINE_ID\",\"typeId\":\"$TYPE_UNIV\",\"title\":\"Captured offline\",\"investedAmount\":1000,\"visibility\":\"household\"}"
api "$ISH" POST "/api/v1/households/$HID/investments" "$BODY" >/dev/null
RETRY=$(api "$ISH" POST "/api/v1/households/$HID/investments" "$BODY")
is "re-sending an offline capture returns the same record, not a duplicate" \
   "$(echo "$RETRY" | jq_ "['id']")" "$OFFLINE_ID"
COUNT=$(api "$ISH" GET "/api/v1/households/$HID/investments?q=Captured%20offline" | count)
is "only one copy exists" "$COUNT" "1"

section "Valuation and trash"
api "$ISH" POST "/api/v1/households/$HID/investments/$GOLD_ID/valuations" \
   '{"value":143000,"note":"Jeweller quote"}' >/dev/null
G=$(api "$ISH" GET "/api/v1/households/$HID/investments/$GOLD_ID")
is "a valuation replaces the at-cost basis" "$(echo "$G" | jq_ "['valueBasis']")" "valued"
is "and the current value follows it" "$(echo "$G" | money "['value']")" "143000.00"

api "$ISH" DELETE "/api/v1/households/$HID/investments/$OFFLINE_ID" >/dev/null
is "a deleted record leaves the list" \
   "$(api "$ISH" GET "/api/v1/households/$HID/investments?q=Captured%20offline" | count)" "0"
is "and lands in the trash" \
   "$(api "$ISH" GET "/api/v1/households/$HID/trash" | count)" "1"
api "$ISH" POST "/api/v1/households/$HID/trash/investments/$OFFLINE_ID/restore" >/dev/null
is "restore brings it back" \
   "$(api "$ISH" GET "/api/v1/households/$HID/investments?q=Captured%20offline" | count)" "1"

section "Liabilities and true net worth"
LOAN=$(api "$ISH" POST "/api/v1/households/$HID/liabilities" "{
  \"title\":\"HDFC home loan\",\"kind\":\"home\",\"outstanding\":4000000,
  \"emiAmount\":22000,\"emiDay\":5,\"visibility\":\"household\",
  \"securedByInvestmentId\":\"$JOINT_ID\",
  \"holders\":[{\"memberId\":\"$ISH_MEM\",\"responsibilityPct\":50},
              {\"memberId\":\"$RAVI_MEM\",\"responsibilityPct\":50}]}")
LOAN_ID=$(echo "$LOAN" | jq_ "['id']")
isnt "a home loan is recorded" "$LOAN_ID" ""
is   "outstanding renders with Indian grouping" \
     "$(echo "$LOAN" | jq_ "['outstandingFormatted']")" "₹40,00,000"

# Asserted as a relationship, not a constant: earlier sections revalue holdings,
# and a hard-coded total would be a test that breaks whenever anything above it
# changes — without ever telling you whether the subtraction is right.
D=$(api "$ISH" GET "/api/v1/households/$HID/dashboard?scope=household")
A=$(echo "$D" | money "['totalAssets']"); L=$(echo "$D" | money "['totalLiabilities']")
is "the whole loan is counted, once" "$L" "4000000.00"
is "net worth is exactly assets minus what is owed" \
   "$(echo "$D" | money "['netWorth']")" "$(minus "$A" "$L")"

is "the asset it secures shows as encumbered" \
   "$(api "$ISH" GET "/api/v1/households/$HID/investments/$JOINT_ID" | money "['encumbrance']")" \
   "4000000.00"
is "and its net equity is what's actually yours" \
   "$(api "$ISH" GET "/api/v1/households/$HID/investments/$JOINT_ID" | money "['netEquity']")" \
   "0.00"

PRIVATE_DEBT=$(api "$ISH" POST "/api/v1/households/$HID/liabilities" \
  '{"title":"Private personal loan","kind":"personal","outstanding":300000,"visibility":"private"}')
isnt "a private debt is recorded" "$(echo "$PRIVATE_DEBT" | jq_ "['id']")" ""

DI=$(api "$ISH"  GET "/api/v1/households/$HID/dashboard?scope=household")
DR=$(api "$RAVI" GET "/api/v1/households/$HID/dashboard?scope=household")
is "the owner's debts include her private loan" \
   "$(echo "$DI" | money "['totalLiabilities']")" "4300000.00"
is "the admin's debts do NOT — her 3,00,000 is invisible" \
   "$(echo "$DR" | money "['totalLiabilities']")" "4000000.00"
is "so her net worth is her own assets minus her own debts" \
   "$(echo "$DI" | money "['netWorth']")" \
   "$(minus "$(echo "$DI" | money "['totalAssets']")" "$(echo "$DI" | money "['totalLiabilities']")")"
is "and a private debt never shrinks another member's net worth" \
   "$(echo "$DR" | money "['netWorth']")" \
   "$(minus "$(echo "$DR" | money "['totalAssets']")" "4000000.00")"

section "Nominees"
N=$(api "$ISH" PUT "/api/v1/households/$HID/investments/$GOLD_ID/nominees" \
  "{\"nominees\":[{\"memberId\":\"$RAVI_MEM\",\"sharePct\":60},
                 {\"name\":\"Aarav Koduri\",\"relationship\":\"son\",\"sharePct\":40}]}")
is "nominees can be a member or a plain name" \
   "$(echo "$N" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["nominees"]))')" "2"
is "nominee shares that don't total 100% are refused" \
   "$(api "$ISH" PUT "/api/v1/households/$HID/investments/$GOLD_ID/nominees" \
      "{\"nominees\":[{\"memberId\":\"$RAVI_MEM\",\"sharePct\":70}]}" | jq_ "['error']['code']")" \
   "nominee_shares_must_total_100"

echo
if [ "$FAIL" -eq 0 ]; then
  echo "${GREEN}${BOLD}All $PASS checks passed.${OFF}"
else
  echo "${RED}${BOLD}$FAIL of $((PASS+FAIL)) checks failed.${OFF}"
fi
exit $(( FAIL > 0 ? 1 : 0 ))
