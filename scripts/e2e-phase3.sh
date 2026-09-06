#!/usr/bin/env bash
# =============================================================================
# Phase 3 end-to-end test, over real HTTP against a running backend.
#
# Phase 3 is where people other than the family get to see something: a guest
# link, an emergency contact, an advisor. Each is meant to be a NARROWING of the
# privacy model rather than a hole in it, and that is a claim best checked from
# outside the process — through real HTTP, with real tokens, against a server
# that has already forgotten how any of it was built.
#
# It also checks the two things that are only true if the whole stack agrees:
# that a nominee who disagrees with a will is flagged, and that a sealed field
# is unreadable to everything except the client that sealed it.
#
#   ./scripts/e2e-phase3.sh [base-url]
# =============================================================================
set -uo pipefail
BASE="${1:-http://localhost:8080}"
PASS=0; FAIL=0
GREEN=$'\033[32m'; RED=$'\033[31m'; DIM=$'\033[2m'; BOLD=$'\033[1m'; OFF=$'\033[0m'

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

titles() { python3 -c "
import sys, json
d = json.load(sys.stdin)
print('|'.join(sorted(i['title'] for i in d)) if isinstance(d, list) else '<error>')
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
P_ISH="9$(printf '%09d' $(( (STAMP % 900000000) + 600000 )))"
P_RAVI="8$(printf '%09d' $(( (STAMP % 900000000) + 700000 )))"
P_CA="7$(printf '%09d' $(( (STAMP % 900000000) + 800000 )))"

section "A household, a spouse, and a CA"
ISH=$(login "$P_ISH");   isnt "Ishwarya signs in" "$ISH" ""
RAVI=$(login "$P_RAVI"); isnt "Ravi signs in" "$RAVI" ""
CA=$(login "$P_CA");     isnt "The CA signs in" "$CA" ""

HH=$(api "$ISH" POST /api/v1/households \
  '{"name":"Koduri","mode":"family","defaultVisibility":"private","displayName":"Ishwarya"}')
HID=$(echo "$HH" | jq_ "['id']")
ISH_MEM=$(echo "$HH" | jq_ "['myMemberId']")

RAVI_MEM=$(api "$ISH" POST "/api/v1/households/$HID/members" \
  '{"displayName":"Ravi","relationship":"spouse"}' | jq_ "['id']")
INV=$(api "$ISH" POST "/api/v1/households/$HID/invitations" \
  "{\"memberId\":\"$RAVI_MEM\",\"phone\":\"$P_RAVI\",\"role\":\"admin\"}")
api "$RAVI" POST /api/v1/invitations/accept "{\"token\":\"$(echo "$INV" | jq_ "['token']")\"}" >/dev/null

CA_MEM=$(api "$ISH" POST "/api/v1/households/$HID/members" \
  '{"displayName":"Ramesh (CA)","relationship":"other"}' | jq_ "['id']")
CA_INV=$(api "$ISH" POST "/api/v1/households/$HID/invitations" \
  "{\"memberId\":\"$CA_MEM\",\"phone\":\"$P_CA\",\"role\":\"advisor\"}")
ACCEPTED=$(api "$CA" POST /api/v1/invitations/accept "{\"token\":\"$(echo "$CA_INV" | jq_ "['token']")\"}")
is "the CA joins as an advisor" "$(echo "$ACCEPTED" | jq_ "['role']")" "advisor"

TYPE_TERM=$(type_id "$ISH" "$HID" insurance_term)
TYPE_GOLD=$(type_id "$ISH" "$HID" gold_physical)

POLICY=$(api "$ISH" POST "/api/v1/households/$HID/investments" \
  "{\"typeId\":\"$TYPE_TERM\",\"title\":\"LIC term cover\",\"investedAmount\":2000000,
    \"attributes\":{\"policy_no\":\"5567123456\",\"sum_assured\":2000000,\"premium_amount\":18400},
    \"visibility\":\"household\"}")
POLICY_ID=$(echo "$POLICY" | jq_ "['id']")
GOLD=$(api "$ISH" POST "/api/v1/households/$HID/investments" \
  "{\"typeId\":\"$TYPE_GOLD\",\"title\":\"Her private gold\",\"investedAmount\":500000,
    \"visibility\":\"private\"}")
GOLD_ID=$(echo "$GOLD" | jq_ "['id']")

section "Contacts and the paperwork"
AGENT=$(api "$ISH" POST "/api/v1/households/$HID/contacts" \
  '{"kind":"agent","name":"Suresh","phone":"9876500000","visibility":"household"}')
AGENT_ID=$(echo "$AGENT" | jq_ "['id']")
LINKED=$(api "$ISH" POST "/api/v1/households/$HID/contacts/$AGENT_ID/links" \
  "{\"entityType\":\"investment\",\"entityId\":\"$POLICY_ID\",\"role\":\"Sold the policy\"}")
is "a contact links to the record they handle" \
   "$(echo "$LINKED" | python3 -c "
import sys, json
print(json.load(sys.stdin)['links'][0]['entityTitle'])")" "LIC term cover"
is "and a link to a record you cannot see is refused" \
   "$(status "$RAVI" POST "/api/v1/households/$HID/contacts/$AGENT_ID/links" \
      "{\"entityType\":\"investment\",\"entityId\":\"$GOLD_ID\"}")" "404"

section "Nominee is not heir"
api "$ISH" PUT "/api/v1/households/$HID/investments/$POLICY_ID/nominees" \
  '{"nominees":[{"name":"Mohan (brother)","sharePct":100}]}' >/dev/null
api "$ISH" POST "/api/v1/households/$HID/estate/documents" \
  "{\"memberId\":\"$ISH_MEM\",\"kind\":\"will\",\"title\":\"Ishwarya's will\",
    \"location\":\"Home locker, second shelf\",\"visibility\":\"household\",
    \"beneficiaries\":[{\"investmentId\":\"$POLICY_ID\",\"memberId\":\"$RAVI_MEM\",\"sharePct\":100}]}" >/dev/null

MISMATCH=$(api "$ISH" GET "/api/v1/households/$HID/estate/mismatches")
is "the policy that pays a nominee the will doesn't name is flagged" \
   "$(echo "$MISMATCH" | count)" "1"
has "and the flag explains the difference without picking a side" \
    "$(echo "$MISMATCH" | jq_ "[0]['explanation']")" "A nominee receives the money"

section "How the family claims it"
GUIDE=$(api "$ISH" GET "/api/v1/households/$HID/continuity/transmission/$POLICY_ID")
has "an insurance claim guide names the form" "$(echo "$GUIDE" | jq_ "['steps'][0]['detail']")" "3783"
is  "and a guide for a holding you cannot see is a 404" \
    "$(status "$RAVI" GET "/api/v1/households/$HID/continuity/transmission/$GOLD_ID")" "404"

HANDBOOK=$(api "$ISH" GET "/api/v1/households/$HID/continuity/handbook")
is "the handbook holds everything marked for the family" \
   "$(echo "$HANDBOOK" | python3 -c "
import sys, json
print(len(json.load(sys.stdin)['entries']))")" "2"
is "the reference number that makes it findable is there" \
   "$(echo "$HANDBOOK" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(next(e['reference'] for e in d['entries'] if e['title'] == 'LIC term cover'))")" "5567123456"
is "and a spouse's copy holds only what they may see" \
   "$(api "$RAVI" GET "/api/v1/households/$HID/continuity/handbook" | python3 -c "
import sys, json
print('|'.join(e['title'] for e in json.load(sys.stdin)['entries']))")" "LIC term cover"
is "the printed handbook is a PDF" \
   "$(curl -s "$BASE/api/v1/households/$HID/continuity/handbook.pdf" \
      -H "Authorization: Bearer $ISH" | head -c 4)" "%PDF"

section "A guest link reaches its slice and nothing else"
SHARE=$(api "$ISH" POST "/api/v1/households/$HID/shares" \
  '{"label":"Handbook for Meera","scope":"handbook","expiresInDays":7}')
SHARE_ID=$(echo "$SHARE" | jq_ "['id']")
URL=$(echo "$SHARE" | jq_ "['url']")
TOKEN="${URL##*/}"
isnt "a link is created" "$TOKEN" ""

OPENED=$(curl -s "$BASE/api/v1/share/$TOKEN")
is "it opens with no sign-in at all" "$(echo "$OPENED" | jq_ "['label']")" "Handbook for Meera"
is "it carries the slice it was made from" \
   "$(echo "$OPENED" | python3 -c "
import sys, json
print(len(json.load(sys.stdin)['handbook']['entries']))")" "2"
# The handbook deliberately includes what is private to its owner — privacy is
# for life, continuity is for after — so the link says so before it is sent.
has "the link says plainly that something private is inside" \
    "$(echo "$SHARE" | jq_ "['scopeNote']")" "private to you"
is  "a tax-pack link, by contrast, carries only what the pack is built from" \
    "$(api "$ISH" POST "/api/v1/households/$HID/shares" \
       '{"label":"Tax pack","scope":"tax_pack","expiresInDays":7}' | jq_ "['scopeNote']")" \
    "1 record. Nothing private to you is included."
is "the view is recorded, so sharing is not into the dark" \
   "$(api "$ISH" GET "/api/v1/households/$HID/shares/$SHARE_ID/views" | count)" "1"
is "someone else's link is not mine to see" \
   "$(api "$RAVI" GET "/api/v1/households/$HID/shares" | count)" "0"

api "$ISH" DELETE "/api/v1/households/$HID/shares/$SHARE_ID" >/dev/null
is "withdrawing it stops the next open" \
   "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/v1/share/$TOKEN")" "404"
is "and a link that never existed answers identically" \
   "$(curl -s "$BASE/api/v1/share/definitely-not-a-token" | jq_ "['error']['message']")" \
   "$(curl -s "$BASE/api/v1/share/$TOKEN" | jq_ "['error']['message']")"

section "The advisor sees what they were given"
is "a household holding is not the advisor's business" \
   "$(api "$CA" GET "/api/v1/households/$HID/investments" | count)" "0"
api "$ISH" PATCH "/api/v1/households/$HID/investments/$POLICY_ID/visibility" \
  "{\"visibility\":\"scoped\",\"visibleToMemberIds\":[\"$CA_MEM\"]}" >/dev/null
is "what is shared explicitly, they can see" \
   "$(api "$CA" GET "/api/v1/households/$HID/investments" | titles)" "LIC term cover"
# The version it actually has, so a refusal cannot be mistaken for a race.
POLICY_VERSION=$(api "$CA" GET "/api/v1/households/$HID/investments/$POLICY_ID" | jq_ "['version']")
is "and they cannot change it" \
   "$(status "$CA" PATCH "/api/v1/households/$HID/investments/$POLICY_ID" \
      "{\"version\":$POLICY_VERSION,\"title\":\"Renamed by the CA\"}")" "403"
api "$ISH" PATCH "/api/v1/households/$HID/investments/$POLICY_ID/visibility" \
  '{"visibility":"household"}' >/dev/null
is "moving it back to the family ends the engagement" \
   "$(api "$CA" GET "/api/v1/households/$HID/investments" | count)" "0"

section "Emergency access: a delay, a veto, and only what was marked"
is "someone who was never named cannot ask" \
   "$(status "$RAVI" POST "/api/v1/households/$HID/emergency/requests" \
      "{\"subjectMemberId\":\"$ISH_MEM\"}")" "403"

api "$ISH" POST "/api/v1/households/$HID/emergency/contacts" \
  "{\"trustedMemberId\":\"$RAVI_MEM\",\"waitDays\":14}" >/dev/null
is "being someone's emergency contact is not a secret from them" \
   "$(api "$RAVI" GET "/api/v1/households/$HID/emergency/contacts" | python3 -c "
import sys, json
print(json.load(sys.stdin)[0]['theyTrustMe'])")" "True"

REQUEST=$(api "$RAVI" POST "/api/v1/households/$HID/emergency/requests" \
  "{\"subjectMemberId\":\"$ISH_MEM\",\"reason\":\"Hospital\"}")
REQUEST_ID=$(echo "$REQUEST" | jq_ "['id']")
is "a request waits rather than opening" "$(echo "$REQUEST" | jq_ "['status']")" "waiting"
is "and nothing has opened while it waits" \
   "$(api "$RAVI" GET "/api/v1/households/$HID/investments" | titles)" "LIC term cover"
is "the person it concerns is told, and can stop it" \
   "$(api "$ISH" GET "/api/v1/households/$HID/emergency/requests" | count)" "1"

VETOED=$(api "$ISH" POST "/api/v1/households/$HID/emergency/requests/$REQUEST_ID/veto")
is "a veto closes it" "$(echo "$VETOED" | jq_ "['status']")" "vetoed"
has "and says nothing was opened" "$(echo "$VETOED" | jq_ "['explanation']")" "Nothing was opened"

section "Zero-knowledge: a field the server cannot read"
KEY=$(python3 -c "
import base64, hashlib, json, os, secrets
salt = os.urandom(16)
key = hashlib.pbkdf2_hmac('sha256', b'a quiet afternoon in kakinada', salt, 200000, 32)
b64 = lambda b: base64.urlsafe_b64encode(b).decode().rstrip('=')
# A wrapped key and a verifier, shaped as docs/12 specifies. The bytes are
# random here because this suite only proves the server stores what it is given.
print(json.dumps({
  'kdfSalt': b64(salt), 'iterations': 200000,
  'wrappedKey': b64(bytes([1,0,0,0,1]) + os.urandom(12) + os.urandom(48)),
  'verifier':   b64(bytes([1,0,0,0,1]) + os.urandom(12) + os.urandom(32)),
}))")
is "a passphrase envelope is accepted" \
   "$(status "$ISH" PUT "/api/v1/households/$HID/e2e/key" "$KEY")" "200"
is "and a stretch too short to be worth doing is not" \
   "$(api "$ISH" PUT "/api/v1/households/$HID/e2e/key" \
      "$(echo "$KEY" | python3 -c "
import json, sys
d = json.load(sys.stdin); d['iterations'] = 1000; print(json.dumps(d))")" \
      | jq_ "['error']['code']")" "kdf_too_weak"

CIPHERTEXT=$(python3 -c "
import base64, os
print(base64.urlsafe_b64encode(bytes([1,0,0,0,1]) + os.urandom(12) + os.urandom(48)).decode().rstrip('='))")
is "a sealed field is stored" \
   "$(status "$ISH" PUT "/api/v1/households/$HID/e2e/values/investment/$GOLD_ID/where_it_is" \
      "{\"ciphertext\":\"$CIPHERTEXT\"}")" "200"
is "plain text posted as ciphertext is refused" \
   "$(api "$ISH" PUT "/api/v1/households/$HID/e2e/values/investment/$GOLD_ID/notes" \
      '{"ciphertext":"Locker 214 at Karur Vysya, which is plainly not ciphertext"}' \
      | jq_ "['error']['code']")" "not_ciphertext"
is "a sealed field on a private holding is invisible to everyone else" \
   "$(api "$RAVI" GET "/api/v1/households/$HID/e2e/values" | count)" "0"
is "and visible to its owner" \
   "$(api "$ISH" GET "/api/v1/households/$HID/e2e/values" | count)" "1"

section "Providers, in sandbox"
PROVIDERS=$(api "$ISH" GET "/api/v1/households/$HID/connect/providers")
is "three providers are described" "$(echo "$PROVIDERS" | count)" "3"
has "and each says what would make it real" \
    "$(echo "$PROVIDERS" | jq_ "[0]['toGoLive'][0]")" "client id"

api "$ISH" POST "/api/v1/households/$HID/connect/aa/consent" >/dev/null
api "$ISH" GET  "/api/v1/households/$HID/connect/aa/consent" >/dev/null
IMPORTED=$(api "$ISH" POST "/api/v1/households/$HID/connect/aa/import")
is "approved holdings arrive as ordinary records" "$(echo "$IMPORTED" | jq_ "['imported']")" "3"
is "at the household's own default visibility, not wider" \
   "$(api "$RAVI" GET "/api/v1/households/$HID/investments" | titles)" "LIC term cover"

section "Multi-currency"
api "$ISH" POST "/api/v1/households/$HID/rates" \
  '{"baseCurrency":"AED","quoteCurrency":"INR","rate":25}' >/dev/null
CONVERTED=$(api "$ISH" GET "/api/v1/households/$HID/rates/convert?amount=1000&from=AED&to=INR")
is "a household's own rate is used" "$(echo "$CONVERTED" | money "['convertedAmount']")" "25000.00"
is "and an unknown pair converts to nothing rather than a guess" \
   "$(api "$ISH" GET "/api/v1/households/$HID/rates/convert?amount=100&from=NOK&to=INR" \
      | jq_ "['convertedAmount']")" ""

echo
if [ "$FAIL" -eq 0 ]; then
  echo "${GREEN}${BOLD}All $PASS checks passed.${OFF}"
else
  echo "${RED}${BOLD}$FAIL of $((PASS+FAIL)) checks failed.${OFF}"
fi
exit $(( FAIL > 0 ? 1 : 0 ))
