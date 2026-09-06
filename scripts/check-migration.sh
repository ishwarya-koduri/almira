#!/usr/bin/env bash
# =============================================================================
# Syntax-checks migrations against a THROWAWAY database.
#
#   ./scripts/check-migration.sh                 # every migration, in order
#   ./scripts/check-migration.sh V13__foo.sql    # just up to and including one
#
# Never apply a migration to `almira` or `almira_test` by hand: Flyway would find
# the objects already there with no history row, and the next startup would fail
# with "relation already exists". This rebuilds a scratch database from nothing
# every time, so a green run means the whole chain applies to an empty database —
# which is what production will do.
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."
GREEN=$'\033[32m'; RED=$'\033[31m'; OFF=$'\033[0m'

docker exec almira-db psql -U almira -d postgres -q \
  -c "drop database if exists almira_scratch;" \
  -c "create database almira_scratch owner almira;" \
  -c "grant connect on database almira_scratch to almira_app;" >/dev/null

# Version order, not glob order. A shell glob sorts V10 before V2, so the chain
# would appear broken for a reason that has nothing to do with the migrations —
# and Flyway itself orders by version, so the glob would be testing a sequence
# that never actually happens.
ordered=$(ls db/migrations/V*.sql | sed 's/.*\/V\([0-9]*\)__/\1 &/' | sort -n | cut -d' ' -f2)
ordered="$ordered $(ls db/migrations/R__*.sql 2>/dev/null || true)"

stop_after="${1:-}"
for file in $ordered; do
  name=$(basename "$file")
  printf '  %-44s' "$name"
  if docker exec -i almira-db psql -U almira -d almira_scratch -v ON_ERROR_STOP=1 -q < "$file" 2>/tmp/migration-error; then
    echo "${GREEN}ok${OFF}"
  else
    echo "${RED}failed${OFF}"
    sed 's/^/      /' /tmp/migration-error | head -12
    exit 1
  fi
  [ -n "$stop_after" ] && [ "$name" = "$stop_after" ] && break
done

echo "${GREEN}the whole chain applies to an empty database${OFF}"
