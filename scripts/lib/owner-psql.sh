# shellcheck shell=bash
# =============================================================================
# The owner connection for operator scripts (docs/28 §3).
#
# Sourced, not run. Reads --env-file / --url the way bootstrap-prod-db.sh does,
# sets OWNER_URL and OPERATOR, and leaves the remaining arguments in ARGS.
#
# The owner role is the strong authentication here: there is no operator role
# in the application and no operator endpoint, so reaching ops.* needs the
# schema owner's credentials, which only whoever runs the database holds.
# =============================================================================

RED=$'\033[31m'; BOLD=$'\033[1m'; DIM=$'\033[2m'; OFF=$'\033[0m'

ENV_FILE=""
OWNER_URL=""
OPERATOR="${ALMIRA_OPERATOR:-}"
ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="$2"; shift 2;;
    --url)      OWNER_URL="$2"; shift 2;;
    --operator) OPERATOR="$2"; shift 2;;
    *) ARGS+=("$1"); shift;;
  esac
done

if [ -n "$ENV_FILE" ]; then
  [ -f "$ENV_FILE" ] || { echo "${RED}No such env file: $ENV_FILE${OFF}" >&2; exit 1; }
  # shellcheck disable=SC1090
  set -a; . "$ENV_FILE"; set +a
fi

if [ -z "$OWNER_URL" ]; then
  : "${ALMIRA_DB_NAME:?set ALMIRA_DB_NAME, or pass --url}"
  : "${ALMIRA_DB_OWNER_USER:?set ALMIRA_DB_OWNER_USER}"
  : "${ALMIRA_DB_OWNER_PASSWORD:?set ALMIRA_DB_OWNER_PASSWORD}"
  OWNER_URL="postgres://${ALMIRA_DB_OWNER_USER}:${ALMIRA_DB_OWNER_PASSWORD}@${ALMIRA_DB_HOST:-127.0.0.1}:${ALMIRA_DB_PORT:-5432}/${ALMIRA_DB_NAME}"
fi

[ -n "$OPERATOR" ] || { echo "${RED}Say who you are: --operator <name> (or ALMIRA_OPERATOR). It goes in the audit log.${OFF}" >&2; exit 2; }

command -v psql >/dev/null 2>&1 || { echo "${RED}psql is not on PATH.${OFF}" >&2; exit 1; }
