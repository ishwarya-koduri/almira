#!/usr/bin/env bash
# =============================================================================
# Fills a fresh install with one plausible household, so the app can be looked
# at with real shapes in it rather than empty states.
#
#   ./scripts/demo-data.sh [base-url]
#
# Prints the phone number to sign in with. The OTP is shown on screen because
# ALMIRA_OTP_PROVIDER defaults to `log` in development.
# =============================================================================
set -euo pipefail
BASE="${1:-http://localhost:8080}"
BOLD=$'\033[1m'; DIM=$'\033[2m'; OFF=$'\033[0m'

j() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

login() {
  local phone="$1" code
  code=$(curl -s -X POST "$BASE/api/auth/otp/request" -H 'Content-Type: application/json' \
        -d "{\"phone\":\"$phone\"}" | j "['developmentCode']")
  curl -s -X POST "$BASE/api/auth/otp/verify" -H 'Content-Type: application/json' \
    -d "{\"phone\":\"$phone\",\"code\":\"$code\"}" | j "['accessToken']"
}
post() { curl -s -X POST "$BASE$2" -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$3"; }
get()  { curl -s "$BASE$2" -H "Authorization: Bearer $1"; }

STAMP=$(date +%s)
PHONE_A="9$(printf '%09d' $(( STAMP % 900000000 )))"
PHONE_B="8$(printf '%09d' $(( (STAMP + 7) % 900000000 )))"

echo "${BOLD}Creating a household…${OFF}"
A=$(login "$PHONE_A"); B=$(login "$PHONE_B")

HH=$(post "$A" /api/households \
  '{"name":"Koduri","mode":"family","defaultVisibility":"private","displayName":"Ishwarya"}')
HID=$(echo "$HH" | j "['id']"); ISH=$(echo "$HH" | j "['myMemberId']")

RAVI=$(post "$A" "/api/households/$HID/members" '{"displayName":"Ravi","relationship":"spouse"}' | j "['id']")
post "$A" "/api/households/$HID/members" \
  '{"displayName":"Aarav","relationship":"child","dateOfBirth":"2015-04-02"}' >/dev/null
INV=$(post "$A" "/api/households/$HID/invitations" \
  "{\"memberId\":\"$RAVI\",\"phone\":\"$PHONE_B\",\"role\":\"admin\"}" | j "['token']")
post "$B" /api/invitations/accept "{\"token\":\"$INV\"}" >/dev/null

typeid() { get "$A" "/api/households/$HID/taxonomy" | python3 -c "
import sys, json
for c in json.load(sys.stdin):
    for t in c['types']:
        if t['code'] == '$1': print(t['id']); break"; }

inst() { get "$A" "/api/households/$HID/institutions?q=$1" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(d[0]['id'] if d else '')"; }

echo "${BOLD}Adding accounts…${OFF}"
SBI=$(post "$A" "/api/households/$HID/accounts" "{
  \"label\":\"SBI savings — salary\",\"accountKind\":\"savings\",
  \"institutionId\":\"$(inst 'State%20Bank')\",\"number\":\"50100234567890\",
  \"storeFullNumber\":true,\"ifsc\":\"SBIN0001234\",\"visibility\":\"household\"}" | j "['id']")
ZERODHA=$(post "$A" "/api/households/$HID/accounts" "{
  \"label\":\"Zerodha demat\",\"accountKind\":\"demat\",
  \"institutionId\":\"$(inst Zerodha)\",\"number\":\"1208160012345678\",
  \"visibility\":\"household\"}" | j "['id']")

echo "${BOLD}Adding holdings…${OFF}"
add() { post "$A" "/api/households/$HID/investments" "$1" >/dev/null; }

add "{\"typeId\":\"$(typeid fd)\",\"title\":\"SBI FD — 5 years\",\"investedAmount\":500000,
     \"maturityDate\":\"2029-03-31\",\"accountId\":\"$SBI\",\"visibility\":\"household\",
     \"attributes\":{\"interest_rate\":\"7.1\",\"payout\":\"cumulative\",\"receipt_no\":\"FD-88213\"}}"
add "{\"typeId\":\"$(typeid gold_physical)\",\"title\":\"Wedding coins\",\"investedAmount\":100000,
     \"quantity\":6.3,\"unit\":\"g\",\"storageLocation\":\"Home locker\",\"visibility\":\"household\",
     \"attributes\":{\"purity\":\"22k\",\"making_charges\":\"4500\"}}"
add "{\"typeId\":\"$(typeid mf_sip)\",\"title\":\"Parag Parikh Flexi Cap\",\"investedAmount\":420000,
     \"accountId\":\"$ZERODHA\",\"visibility\":\"household\",
     \"attributes\":{\"scheme_name\":\"PPFAS Flexi Cap\",\"sip_amount\":\"25000\",\"sip_day\":\"5\",
                   \"plan\":\"direct\",\"scheme_category\":\"equity\"}}"
add "{\"typeId\":\"$(typeid ppf)\",\"title\":\"PPF — SBI\",\"investedAmount\":850000,
     \"maturityDate\":\"2031-04-01\",\"visibility\":\"household\",
     \"attributes\":{\"account_no\":\"PPF-4471\",\"claimed_80c\":true}}"
add "{\"typeId\":\"$(typeid insurance_term)\",\"title\":\"LIC term cover\",\"visibility\":\"household\",
     \"attributes\":{\"policy_no\":\"5567123\",\"sum_assured\":\"10000000\",\"premium_amount\":\"18400\",
                   \"premium_due_date\":\"2026-11-12\",\"agent_name\":\"Ramesh\"}}"

FLAT=$(post "$A" "/api/households/$HID/investments" "{
  \"typeId\":\"$(typeid property)\",\"title\":\"Flat, Kakinada\",\"investedAmount\":4200000,
  \"visibility\":\"household\",
  \"owners\":[{\"memberId\":\"$ISH\",\"sharePct\":50},{\"memberId\":\"$RAVI\",\"sharePct\":50}],
  \"attributes\":{\"address\":\"12-4-9, Ramaraopeta, Kakinada\",\"property_kind\":\"flat\",\"area\":1450}}" \
  | j "['id']")

# One private holding, so the privacy model is visible in the UI rather than
# only in the tests.
add "{\"typeId\":\"$(typeid savings_buffer)\",\"title\":\"My own buffer\",\"investedAmount\":150000,
     \"visibility\":\"private\",\"attributes\":{\"purpose\":\"Emergency fund\"}}"

echo "${BOLD}Adding what's owed…${OFF}"
post "$A" "/api/households/$HID/liabilities" "{
  \"title\":\"HDFC home loan\",\"kind\":\"home\",\"outstanding\":2850000,\"principal\":3500000,
  \"interestRate\":8.6,\"emiAmount\":28400,\"emiDay\":5,\"visibility\":\"household\",
  \"securedByInvestmentId\":\"$FLAT\",
  \"holders\":[{\"memberId\":\"$ISH\",\"responsibilityPct\":50},
              {\"memberId\":\"$RAVI\",\"responsibilityPct\":50}]}" >/dev/null
post "$A" "/api/households/$HID/liabilities" \
  '{"title":"Card outstanding","kind":"credit_card","outstanding":35000,"emiDay":18,
    "visibility":"household"}' >/dev/null

echo
echo "${BOLD}Done.${OFF} Sign in at $BASE"
echo "  ${BOLD}$PHONE_A${OFF}  ${DIM}Ishwarya — owner${OFF}"
echo "  ${BOLD}$PHONE_B${OFF}  ${DIM}Ravi — admin, and cannot see Ishwarya's private buffer${OFF}"
echo "  ${DIM}The OTP is shown on screen in development.${OFF}"
