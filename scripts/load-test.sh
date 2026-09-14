#!/usr/bin/env bash
# =============================================================================
# A small, honest load test: many signed-in people reading their balance sheet
# at once, with some of them adding holdings, against a DEVELOPMENT server you
# started yourself on a throwaway database.
#
#   ./scripts/load-test.sh --base http://127.0.0.1:18480
#   ./scripts/load-test.sh --base http://127.0.0.1:18480 --users 20 --requests 2000 --concurrency 25
#
# Setup (not timed): each of --users people signs in with a one-time code the
# development server echoes, creates a household and adds three holdings.
# Then --requests calls at --concurrency, spread over the same people:
#
#   45%  GET  /households/{id}/dashboard     the home screen's figures
#   25%  GET  /households/{id}/investments   the list
#   15%  GET  /me
#   10%  POST /households/{id}/investments   a new FD
#    5%  GET  /households/{id}/still-true
#
# Reports throughput, p50/p95/p99/max latency and every non-2xx by status, and
# exits 1 when anything failed or p95 is over --p95-ms (default 800).
#
# REFUSES to run against anything but loopback, anything that is not in
# development mode, and any port a container named almira-personal* publishes.
# It signs people up and writes records; it is for a server that exists to be
# thrown away. Sign-in limits per network apply, so start that server with
# ALMIRA_OTP_MAX_PER_IP_PER_HOUR above --users.
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

RED=$'\033[31m'; GREEN=$'\033[32m'; BOLD=$'\033[1m'; DIM=$'\033[2m'; OFF=$'\033[0m'
die() { echo "${RED}${BOLD}Stopped:${OFF}${RED} $*${OFF}" >&2; exit 1; }

BASE="" USERS=10 REQUESTS=1000 CONCURRENCY=10 P95_MS=800
while [ $# -gt 0 ]; do
  case "$1" in
    --base) BASE="${2%/}"; shift 2;;
    --users) USERS="$2"; shift 2;;
    --requests) REQUESTS="$2"; shift 2;;
    --concurrency) CONCURRENCY="$2"; shift 2;;
    --p95-ms) P95_MS="$2"; shift 2;;
    -h|--help) sed -n '2,30p' "$0"; exit 0;;
    *) die "unknown option: $1";;
  esac
done
[ -n "$BASE" ] || die "--base is required, e.g. http://127.0.0.1:18480"
for n in "$USERS" "$REQUESTS" "$CONCURRENCY" "$P95_MS"; do
  [[ "$n" =~ ^[1-9][0-9]*$ ]] || die "counts must be positive whole numbers (got '$n')."
done

HOSTPORT=$(python3 -c 'import sys,urllib.parse as u; p=u.urlparse(sys.argv[1]); print(p.hostname or "", p.port or (443 if p.scheme=="https" else 80))' "$BASE")
HOST=${HOSTPORT% *} PORT=${HOSTPORT#* }
case "$HOST" in
  127.0.0.1|localhost|::1) ;;
  *) die "$BASE is not on this machine. This writes data; it runs against loopback only.";;
esac
if command -v docker >/dev/null 2>&1; then
  for name in $(docker ps --format '{{.Names}}' 2>/dev/null | grep '^almira-personal' || true); do
    docker port "$name" 2>/dev/null | grep -q ":$PORT\$" && die "port $PORT belongs to $name, a personal stack. Start a throwaway server instead."
  done
fi
ENV=$(curl -fsS --max-time 5 "$BASE/health" | python3 -c 'import json,sys; b=json.load(sys.stdin); print(b.get("environment",""), b.get("rlsEnforced"))') \
  || die "no Almira at $BASE/health."
[ "${ENV% *}" = development ] || die "$BASE is not a development server (environment '${ENV% *}')."
[ "${ENV#* }" = True ] || die "$BASE is not enforcing row-level security. Fix that before measuring anything."

WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
echo "${BOLD}Load test against $BASE${OFF}  ${DIM}$USERS people · $REQUESTS requests · $CONCURRENCY at a time${OFF}"

# --- setup -------------------------------------------------------------------
json() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
post() { curl -fsS --max-time 20 -H 'Content-Type: application/json' ${3:+-H "Authorization: Bearer $3"} -d "$2" "$BASE$1"; }
getj() { curl -fsS --max-time 20 -H "Authorization: Bearer $2" "$BASE$1"; }

RUN=$(( (RANDOM << 15 | RANDOM) % 900000 + 100000 ))
: > "$WORK/people"
printf "  signing in and seeding"
for i in $(seq 1 "$USERS"); do
  phone="+9169${RUN}$(printf '%02d' $((i % 100)))"
  code=$(post /api/v1/auth/otp/request "{\"phone\":\"$phone\"}" | json 'd.get("developmentCode","")') \
    || die "the one-time-code request failed for person $i (raise ALMIRA_OTP_MAX_PER_IP_PER_HOUR?)."
  [ -n "$code" ] || die "the server did not echo a development code."
  token=$(post /api/v1/auth/otp/verify "{\"phone\":\"$phone\",\"code\":\"$code\"}" | json 'd["accessToken"]')
  hid=$(post /api/v1/households "{\"name\":\"Load $i\",\"defaultVisibility\":\"private\",\"displayName\":\"Person $i\"}" "$token" | json 'd["id"]')
  fd=$(getj "/api/v1/households/$hid/taxonomy" "$token" | json 'next(t["id"] for c in d for t in c["types"] if t["code"]=="fd")')
  for k in 1 2 3; do
    post "/api/v1/households/$hid/investments" \
      "{\"typeId\":\"$fd\",\"title\":\"FD $k\",\"investedAmount\":$((k * 125000)),\"attributes\":{\"interest_rate\":\"7.1\"}}" "$token" >/dev/null
  done
  echo "$token $hid $fd" >> "$WORK/people"
  printf "."
done
echo " ${GREEN}done${OFF}"

# --- the run -----------------------------------------------------------------
python3 - "$WORK/people" "$REQUESTS" > "$WORK/plan" <<'PY'
import random, sys
people = [line.split() for line in open(sys.argv[1])]
mix = [("dash", 45), ("list", 25), ("me", 15), ("add", 10), ("still", 5)]
kinds = [k for k, w in mix for _ in range(w)]
rng = random.Random(7)
for n in range(int(sys.argv[2])):
    token, hid, fd = rng.choice(people)
    print(rng.choice(kinds), token, hid, fd, n)
PY

cat > "$WORK/one.sh" <<'ONE'
#!/usr/bin/env bash
base="$1"; kind="$2"; token="$3"; hid="$4"; fd="$5"; n="$6"
fmt='%{http_code} %{time_total}\n'
auth=(-H "Authorization: Bearer $token")
case "$kind" in
  dash)  out=$(curl -s -o /dev/null -w "$fmt" --max-time 30 "${auth[@]}" "$base/api/v1/households/$hid/dashboard") ;;
  list)  out=$(curl -s -o /dev/null -w "$fmt" --max-time 30 "${auth[@]}" "$base/api/v1/households/$hid/investments") ;;
  me)    out=$(curl -s -o /dev/null -w "$fmt" --max-time 30 "${auth[@]}" "$base/api/v1/me") ;;
  still) out=$(curl -s -o /dev/null -w "$fmt" --max-time 30 "${auth[@]}" "$base/api/v1/households/$hid/still-true") ;;
  add)   out=$(curl -s -o /dev/null -w "$fmt" --max-time 30 "${auth[@]}" -H 'Content-Type: application/json' \
           -d "{\"typeId\":\"$fd\",\"title\":\"Load FD $n\",\"investedAmount\":50000,\"attributes\":{\"interest_rate\":\"7.1\"}}" \
           "$base/api/v1/households/$hid/investments") ;;
esac
echo "$kind $out"
ONE
chmod +x "$WORK/one.sh"

START=$(python3 -c 'import time; print(time.time())')
xargs -P "$CONCURRENCY" -L 1 "$WORK/one.sh" "$BASE" < "$WORK/plan" > "$WORK/results"
END=$(python3 -c 'import time; print(time.time())')

python3 - "$WORK/results" "$START" "$END" "$P95_MS" "$GREEN" "$RED" "$BOLD" "$OFF" <<'PY'
import sys, collections
path, start, end, budget = sys.argv[1], float(sys.argv[2]), float(sys.argv[3]), float(sys.argv[4])
GREEN, RED, BOLD, OFF = sys.argv[5:9]
rows = [line.split() for line in open(path) if line.strip()]
def pct(values, p):
    values = sorted(values)
    return values[min(len(values) - 1, int(round(p / 100 * (len(values) - 1))))] if values else 0
by_kind = collections.defaultdict(list)
errors = collections.Counter()
for kind, status, seconds in rows:
    by_kind[kind].append(float(seconds) * 1000)
    if not status.startswith("2"):
        errors[f"{kind} {status}"] += 1
every = [ms for v in by_kind.values() for ms in v]
wall = end - start
print()
print(f"  {'':8} {'n':>6} {'p50':>8} {'p95':>8} {'p99':>8} {'max':>8}   (ms)")
for kind in ["dash", "list", "me", "add", "still"]:
    v = by_kind.get(kind, [])
    if v:
        print(f"  {kind:8} {len(v):>6} {pct(v,50):>8.0f} {pct(v,95):>8.0f} {pct(v,99):>8.0f} {max(v):>8.0f}")
print(f"  {'all':8} {len(every):>6} {pct(every,50):>8.0f} {pct(every,95):>8.0f} {pct(every,99):>8.0f} {max(every):>8.0f}")
print(f"\n  {len(rows)} requests in {wall:.1f}s = {len(rows)/wall:.0f} requests/second")
failed = sum(errors.values())
if errors:
    print(f"  {RED}non-2xx:{OFF} " + ", ".join(f"{k} ×{n}" for k, n in errors.most_common()))
p95 = pct(every, 95)
if failed or p95 > budget:
    print(f"\n{RED}{BOLD}Over budget or failing{OFF}: {failed} failed, p95 {p95:.0f} ms against a budget of {budget:.0f} ms.")
    sys.exit(1)
print(f"\n{GREEN}{BOLD}Within budget{OFF}: no failures, p95 {p95:.0f} ms (budget {budget:.0f} ms).")
PY
