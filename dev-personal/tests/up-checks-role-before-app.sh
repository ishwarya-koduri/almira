#!/usr/bin/env bash
# =============================================================================
# up.sh refuses a runtime role that bypasses row-level security BEFORE the
# application is started — not after it is already serving.
#
#   ./dev-personal/tests/up-checks-role-before-app.sh [path/to/up.sh]
#
# Runs up.sh with `docker` and `curl` replaced by stubs on PATH, so nothing real
# is built, started, stopped or contacted. The docker stub records every call
# and answers the role query as the case says; the test then reads the record
# for whether the application was ever started.
#
# Creates only a temporary directory, which it removes on exit; it touches no
# container, volume, image or network.
# =============================================================================
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
UP="${1:-$HERE/../up.sh}"
FAILED=0

run_case() { # name, bypass answer ("true"/"false"/""), app user, expect app started (yes/no)
  local name="$1" answer="$2" user="$3" expect="$4"
  local stubs; stubs="$(mktemp -d)"
  local log="$stubs/docker.log"
  cat > "$stubs/docker" <<STUB
#!/usr/bin/env bash
echo "\$*" >> "$log"
case "\$*" in
  *"exec -T db psql"*) echo "$answer" ;;
esac
exit 0
STUB
  cat > "$stubs/curl" <<'STUB'
#!/usr/bin/env bash
echo '{"status":"UP","rlsEnforced":true}'
STUB
  chmod +x "$stubs/docker" "$stubs/curl"
  : > "$log"

  PATH="$stubs:$PATH" ALMIRA_DB_APP_USER="$user" bash "$UP" > "$stubs/out" 2>&1
  local status=$?

  local started=no
  # `up -d app`, or a bare `up -d` that starts every service including the app.
  if grep -Eq ' up -d( --wait)?( app)?$' "$log"; then started=yes; fi

  if [ "$started" = "$expect" ]; then
    echo "ok    $name (exit $status, app started: $started)"
  else
    echo "FAIL  $name — app started: $started, expected $expect (exit $status)"
    sed 's/^/        docker /' "$log"
    FAILED=1
  fi
  rm -rf "$stubs"
}

run_case "a role that bypasses RLS is refused before the app starts" "true" "almira_app" no
run_case "a role that does not exist is refused before the app starts" "missing" "almira_ghost" no
run_case "the owner as the runtime role is refused before the app starts" "false" "almira" no
run_case "an ordinary runtime role starts the app" "false" "almira_app" yes

exit $FAILED
