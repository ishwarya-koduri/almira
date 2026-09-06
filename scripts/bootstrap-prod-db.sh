#!/usr/bin/env bash
# =============================================================================
# Creates the non-owner runtime role on a fresh production database.
#
# Run ONCE per database, as the owner, before the application starts:
#
#   ./scripts/bootstrap-prod-db.sh --env-file .env.production          # compose stack
#   ./scripts/bootstrap-prod-db.sh --url "postgres://owner:pw@host/almira"   # managed DB
#
# It is idempotent: every statement is guarded, so running it again after a
# password rotation simply updates the password.
#
# WHY THIS EXISTS: PostgreSQL lets a table's owner bypass its own row-level
# security. Almira's whole privacy model — one member's private records being
# unreadable to another, including an admin — is those policies. Serving traffic
# as the owner would make every one of them decorative, and nothing would look
# wrong. So the schema is owned by one role and served by another, and this is
# what creates the second one.
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

GREEN=$'\033[32m'; RED=$'\033[31m'; BOLD=$'\033[1m'; DIM=$'\033[2m'; OFF=$'\033[0m'

ENV_FILE=""
ADMIN_URL=""
while [ $# -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="$2"; shift 2;;
    --url)      ADMIN_URL="$2"; shift 2;;
    -h|--help)  sed -n '2,20p' "$0"; exit 0;;
    *) echo "unknown option: $1" >&2; exit 2;;
  esac
done

if [ -n "$ENV_FILE" ]; then
  [ -f "$ENV_FILE" ] || { echo "${RED}No such env file: $ENV_FILE${OFF}" >&2; exit 1; }
  # shellcheck disable=SC1090
  set -a; . "$ENV_FILE"; set +a
fi

: "${ALMIRA_DB_APP_USER:?set ALMIRA_DB_APP_USER (in the env file or the environment)}"
: "${ALMIRA_DB_APP_PASSWORD:?set ALMIRA_DB_APP_PASSWORD}"

if [ -z "$ADMIN_URL" ]; then
  : "${ALMIRA_DB_NAME:?set ALMIRA_DB_NAME, or pass --url}"
  : "${ALMIRA_DB_OWNER_USER:?set ALMIRA_DB_OWNER_USER}"
  : "${ALMIRA_DB_OWNER_PASSWORD:?set ALMIRA_DB_OWNER_PASSWORD}"
  HOST="${ALMIRA_DB_HOST:-127.0.0.1}"
  PORT="${ALMIRA_DB_PORT:-5432}"
  ADMIN_URL="postgres://${ALMIRA_DB_OWNER_USER}:${ALMIRA_DB_OWNER_PASSWORD}@${HOST}:${PORT}/${ALMIRA_DB_NAME}"
fi

command -v psql >/dev/null 2>&1 || {
  echo "${RED}psql is not on PATH.${OFF}" >&2
  echo "  On the compose stack you can run it inside the database container instead:" >&2
  echo "  ${DIM}docker compose -f deploy/docker-compose.prod.yml --env-file .env.production run --rm db-bootstrap${OFF}" >&2
  exit 1
}

echo "${BOLD}Creating the runtime role…${OFF}"
psql "$ADMIN_URL" -v ON_ERROR_STOP=1 -q \
  -v app_user="$ALMIRA_DB_APP_USER" \
  -v app_password="$ALMIRA_DB_APP_PASSWORD" \
  -f deploy/bootstrap-db.sql

echo
echo "${GREEN}Done.${OFF} Start the application, then confirm the role it actually connects as:"
echo "  ${DIM}curl -s https://your-host/health${OFF}"
echo "  It must report ${BOLD}\"dbRole\":\"$ALMIRA_DB_APP_USER\"${OFF} and ${BOLD}\"rlsEnforced\":true${OFF}."
echo "  If it names the owner instead, stop: row-level security is being bypassed."
