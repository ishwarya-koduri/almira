#!/usr/bin/env bash
# =============================================================================
# One command: build the image, start the isolated stack, wait until it is
# genuinely healthy, and say what is running.
#
#   ./dev-personal/up.sh [--seed]
#
#   --seed   also fill it with a demo household (development stacks only; the
#            seeding script checks that for itself and refuses otherwise)
#
# Touches nothing outside the almira-personal Docker project. The office
# containers on this machine — bhrigu-postgres, bhrigu-kafka and the rest — are
# in a different project on a different network and are never referenced here.
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")"

COMPOSE=(docker compose -p almira-personal -f docker-compose.personal.yml)
PORT="${ALMIRA_PERSONAL_PORT:-18080}"
BASE="http://localhost:$PORT"

GREEN=$'\033[32m'; RED=$'\033[31m'; DIM=$'\033[2m'; BOLD=$'\033[1m'; OFF=$'\033[0m'
say() { echo "${BOLD}$1${OFF}"; }

say "Building the application image…"
echo "${DIM}  First run pulls the JDK image and the whole dependency graph — a few minutes.${OFF}"
"${COMPOSE[@]}" build app

say "Starting Postgres, Redis and the application…"
"${COMPOSE[@]}" up -d

say "Waiting for it to come up…"
for _ in $(seq 1 90); do
  if curl -fsS "$BASE/health" >/dev/null 2>&1; then break; fi
  sleep 2
done

HEALTH=$(curl -fsS "$BASE/health" 2>/dev/null || true)
if [ -z "$HEALTH" ]; then
  echo "${RED}The application did not answer on $BASE.${OFF}" >&2
  echo "  ${DIM}Logs: ${COMPOSE[*]} logs --tail=60 app${OFF}" >&2
  exit 1
fi

echo "  $HEALTH"
case "$HEALTH" in
  *'"rlsEnforced":true'*) echo "  ${GREEN}row-level security is enforced against the runtime role${OFF}" ;;
  *) echo "${RED}  rlsEnforced is not true — the app is connected as a role that can bypass every privacy policy.${OFF}" >&2
     exit 1 ;;
esac

if [ "${1:-}" = "--seed" ]; then
  say "Seeding a demo household…"
  ( cd .. && ./scripts/demo-data.sh "$BASE" )
fi

echo
say "Ready."
echo "  Web app    $BASE"
echo "  API docs   $BASE/docs"
echo "  Health     $BASE/health"
echo "  ${DIM}Logs:  docker compose -p almira-personal -f dev-personal/docker-compose.personal.yml logs -f app${OFF}"
echo "  ${DIM}Down:  ./dev-personal/down.sh          (deletes the data)${OFF}"
