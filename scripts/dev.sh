#!/usr/bin/env bash
# Brings up everything needed to run Almira locally, in order, and waits for
# each piece before starting the next.
#
#   ./scripts/dev.sh          start infrastructure and the backend
#   ./scripts/dev.sh test     start infrastructure and run all three test suites
set -euo pipefail
cd "$(dirname "$0")/.."

GREEN=$'\033[32m'; DIM=$'\033[2m'; OFF=$'\033[0m'
say() { echo "${GREEN}▸${OFF} $1"; }

if ! docker info >/dev/null 2>&1; then
  echo "Docker isn't running. Start Docker Desktop and try again." >&2
  exit 1
fi

say "Starting Postgres (55432) and Redis (56379)…"
docker compose -f infra/docker-compose.yml up -d >/dev/null

printf "  waiting for health"
for _ in $(seq 1 45); do
  db=$(docker inspect -f '{{.State.Health.Status}}' almira-db 2>/dev/null || echo starting)
  rd=$(docker inspect -f '{{.State.Health.Status}}' almira-redis 2>/dev/null || echo starting)
  [ "$db" = healthy ] && [ "$rd" = healthy ] && break
  printf "."; sleep 2
done
echo " ${GREEN}ready${OFF}"

# Older volumes predate the test database; create it if it is missing so
# `dev.sh test` works without a full `down -v`.
docker exec almira-db psql -U almira -d postgres -tAc \
  "select 1 from pg_database where datname='almira_test'" 2>/dev/null | grep -q 1 || {
  say "Creating the almira_test database…"
  docker exec almira-db psql -U almira -d postgres -q \
    -c "create database almira_test owner almira;" \
    -c "grant connect on database almira_test to almira_app;" >/dev/null
}

export ALMIRA_TEST_DB_URL="jdbc:postgresql://localhost:55432/almira_test"
export ALMIRA_TEST_REDIS_HOST=localhost
export ALMIRA_TEST_REDIS_PORT=56379

if [ "${1:-}" = "test" ]; then
  say "Unit and integration tests…"
  (cd backend && ./gradlew test --console=plain -q)

  say "Row-level security assertions (SQL)…"
  docker cp db/tests/rls_privacy_test.sql almira-db:/tmp/rls.sql >/dev/null
  docker exec -e PGPASSWORD=app_dev_password almira-db \
    psql -h 127.0.0.1 -U almira_app -d almira -v ON_ERROR_STOP=1 -q -f /tmp/rls.sql 2>&1 \
    | grep -E "ok |PASSED" | sed 's/^psql:[^ ]* NOTICE:  //'

  echo
  say "The end-to-end suite needs a running server:"
  echo "  ${DIM}./scripts/dev.sh   # in one terminal${OFF}"
  echo "  ${DIM}./scripts/e2e-phase0.sh${OFF}"
  exit 0
fi

say "Starting the backend on http://localhost:8080 …"
echo "  ${DIM}web client  http://localhost:8080${OFF}"
echo "  ${DIM}API docs    http://localhost:8080/docs${OFF}"
echo "  ${DIM}health      http://localhost:8080/health${OFF}"
echo
cd backend && exec ./gradlew bootRun --console=plain
