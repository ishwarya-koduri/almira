#!/usr/bin/env bash
# =============================================================================
# One command: stop the isolated stack and delete every trace of it — the
# containers, the network, and the three named volumes with all their data.
#
#   ./dev-personal/down.sh
#
# Scoped by project name, so it can only ever remove things this stack created.
# The office containers and volumes on this machine are in other projects and
# are untouched; the list printed afterwards proves it.
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")"

BOLD=$'\033[1m'; DIM=$'\033[2m'; GREEN=$'\033[32m'; OFF=$'\033[0m'

echo "${BOLD}Removing the almira-personal stack and its data…${OFF}"
docker compose -p almira-personal -f docker-compose.personal.yml down -v --remove-orphans

echo
echo "${BOLD}What is left of it:${OFF}"
left=$( { docker ps -a --filter "name=almira-personal" --format '  container {{.Names}}'
          docker volume ls --filter "name=almira-personal" --format '  volume    {{.Name}}'
          docker network ls --filter "name=almira-personal" --format '  network   {{.Name}}'; } | grep . || true )
if [ -z "$left" ]; then echo "  ${GREEN}nothing${OFF}"; else echo "$left"; fi

echo
echo "${BOLD}Still running, untouched:${OFF}"
docker ps --format '  {{.Names}}' | grep -v almira-personal | sed 's/^/  /' || echo "  (nothing else was running)"
echo
echo "${DIM}The built image almira-personal:dev remains. Remove it with:${OFF}"
echo "${DIM}  docker image rm almira-personal:dev${OFF}"
