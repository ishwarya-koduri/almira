#!/usr/bin/env bash
# =============================================================================
# Reads a support code a person has shared (docs/28 §4).
#
#   ./scripts/support-code.sh --env-file .env.production --operator "support-1" \
#       ABCDE-FGHJK "import screen fails, ticket 1042"
#
# Prints the diagnostics the code carries — app version, screen, the last error
# codes, switches — and nothing else: never who the person is, never a record.
# Refuses an expired or taken-back code. Every attempt, found or not, is in the
# audit log with your name and the reason, and the person sees that the code
# was opened.
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=lib/owner-psql.sh
. scripts/lib/owner-psql.sh

[ "${#ARGS[@]}" -eq 2 ] || { sed -n '2,13p' "$0"; exit 2; }

# Passed as psql variables and quoted by psql (:'name'), never spliced into SQL.
psql "$OWNER_URL" -v ON_ERROR_STOP=1 -q -x \
  -v code="${ARGS[0]}" -v operator="$OPERATOR" -v reason="${ARGS[1]}" <<'SQL'
select jsonb_pretty(diagnostics) as diagnostics, created_at, expires_at
  from ops.lookup_support_code(:'code', :'operator', :'reason');
SQL
