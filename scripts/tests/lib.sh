# shellcheck shell=bash
# =============================================================================
# Shared by the operator-script tests in this directory. Sourced, not run.
#
# Each test runs a real script from scripts/ against THROWAWAY things it made
# itself — a Postgres container, a Docker volume, a temporary directory — and
# removes them on exit, pass or fail. Names are $SCRIPT_TEST_PREFIX plus a
# suffix (default prefix: almira-scripttest-<pid>); a prefix that is the name
# of a real stack is refused, and an existing container of that name is never
# reused or removed.
#
# The scripts under test drive a compose stack (`docker compose -p … exec -T db
# …`). Rather than build the production stack, `use_compose_shim` puts a
# `docker` on PATH that answers the handful of compose subcommands those
# scripts use from the throwaway container, records every call in
# $CALLS, and hands everything else to the real docker. So `pg_dump`,
# `pg_restore` and `psql` are real, against a real database; only the compose
# wrapper around them is not.
# =============================================================================

RED=$'\033[31m'; GREEN=$'\033[32m'; BOLD=$'\033[1m'; DIM=$'\033[2m'; OFF=$'\033[0m'
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PREFIX="${SCRIPT_TEST_PREFIX:-almira-scripttest-$$}"
case "$PREFIX" in
  almira|almira-db|almira-redis|almira-personal*|almira-prod*)
    echo "${RED}SCRIPT_TEST_PREFIX '$PREFIX' is the name of a real stack.${OFF}" >&2; exit 2;;
esac
REAL_DOCKER="$(command -v docker)"
WORK="$(mktemp -d)"
CALLS="$WORK/docker.calls"
: > "$CALLS"
FAILED=0
MADE_CONTAINERS=()
MADE_VOLUMES=()

cleanup() {
  for c in "${MADE_CONTAINERS[@]+"${MADE_CONTAINERS[@]}"}"; do "$REAL_DOCKER" rm -f "$c" >/dev/null 2>&1 || true; done
  for v in "${MADE_VOLUMES[@]+"${MADE_VOLUMES[@]}"}"; do "$REAL_DOCKER" volume rm -f "$v" >/dev/null 2>&1 || true; done
  rm -rf "$WORK"
}
trap cleanup EXIT

pass() { echo "  ${GREEN}ok${OFF}    $*"; }
fail() { echo "  ${RED}FAIL${OFF}  $*"; FAILED=1; }
check() { # check <description> <command…> — passes when the command succeeds
  local what="$1"; shift
  if "$@"; then pass "$what"; else fail "$what"; fi
}
finish() {
  if [ "$FAILED" = 0 ]; then echo "${GREEN}${BOLD}passed${OFF}"; else echo "${RED}${BOLD}FAILED${OFF}"; fi
  exit "$FAILED"
}

# A new Postgres container with page checksums, owner almira / password test,
# database almira. Waits until it answers.
start_pg() { # start_pg <suffix> → sets PG to the container name
  PG="$PREFIX-$1"
  if "$REAL_DOCKER" inspect "$PG" >/dev/null 2>&1; then
    echo "${RED}a container called $PG already exists; choose another SCRIPT_TEST_PREFIX.${OFF}" >&2; exit 2
  fi
  "$REAL_DOCKER" run -d --name "$PG" \
    -e POSTGRES_USER=almira -e POSTGRES_PASSWORD=test -e POSTGRES_DB=almira \
    -e POSTGRES_INITDB_ARGS=--data-checksums postgres:16-alpine >/dev/null
  MADE_CONTAINERS+=("$PG")
  for _ in $(seq 1 60); do
    # The entrypoint restarts the server once after init; wait for a real query.
    if "$REAL_DOCKER" exec "$PG" psql -U almira -d almira -tAc 'select 1' 2>/dev/null | grep -q 1; then
      sleep 2
      "$REAL_DOCKER" exec "$PG" psql -U almira -d almira -tAc 'select 1' 2>/dev/null | grep -q 1 && return 0
    fi
    sleep 1
  done
  echo "${RED}$PG did not become ready.${OFF}" >&2; exit 2
}

# SQL as the owner in the throwaway container; statements on stdin or as $1.
owner_sql() {
  if [ $# -gt 0 ]; then "$REAL_DOCKER" exec -i "$PG" psql -U almira -d almira -X -q -tA -v ON_ERROR_STOP=1 -c "$1"
  else "$REAL_DOCKER" exec -i "$PG" psql -U almira -d almira -X -q -tA -v ON_ERROR_STOP=1; fi
}

make_volume() { # make_volume <suffix> → sets VOLUME
  VOLUME="$PREFIX-$1"
  "$REAL_DOCKER" volume create "$VOLUME" >/dev/null
  MADE_VOLUMES+=("$VOLUME")
}

# An env file for the scripts: the names they read, pointing at nothing real.
write_env_file() { # → sets ENV_FILE
  ENV_FILE="$WORK/env"
  cat > "$ENV_FILE" <<ENV
ALMIRA_DB_NAME=almira
ALMIRA_DB_OWNER_USER=almira
ALMIRA_DB_OWNER_PASSWORD=test
ALMIRA_DB_APP_USER=almira_app
ALMIRA_DB_APP_PASSWORD=app_test_password
ALMIRA_IMAGE_TAG=script-test
ENV
}

# Puts the compose shim first on PATH. DOCS_VOLUME_NAME is what
# `compose config` reports as the documents volume.
use_compose_shim() {
  mkdir -p "$WORK/bin"
  cat > "$WORK/bin/docker" <<SHIM
#!/usr/bin/env bash
echo "\$*" >> "$CALLS"
if [ "\$1" = compose ]; then
  shift
  # Drop the options the scripts pass before the subcommand.
  while [ \$# -gt 0 ]; do
    case "\$1" in
      -p|-f|--env-file|--profile) shift 2;;
      *) break;;
    esac
  done
  case "\$1" in
    exec)
      shift; [ "\$1" = -T ] && shift; shift   # -T and the service name
      exec "$REAL_DOCKER" exec -i "$PG" "\$@";;
    config)
      printf '{"volumes":{"documents":{"name":"%s"}}}\n' "\${DOCS_VOLUME_NAME:-}"; exit 0;;
    up) exit 0;;
    run)
      # Only db-bootstrap is ever run: the same psql the compose service runs.
      exec "$REAL_DOCKER" exec -i "$PG" psql -U almira -d almira -v ON_ERROR_STOP=1 \
        -v app_user=almira_app -v app_password=app_test_password -f - < "$REPO/deploy/bootstrap-db.sql";;
    *) echo "compose shim: unexpected subcommand \$*" >&2; exit 97;;
  esac
fi
exec "$REAL_DOCKER" "\$@"
SHIM
  chmod +x "$WORK/bin/docker"
  export PATH="$WORK/bin:$PATH"
}

called() { grep -q -- "$1" "$CALLS"; }
not_called() { ! grep -q -- "$1" "$CALLS"; }
