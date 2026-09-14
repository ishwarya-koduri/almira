#!/usr/bin/env bash
# =============================================================================
# verify.sh runs the backend suite only against the scratch Postgres and Redis
# it started itself — never against whatever else holds their ports.
#
#   ./dev-personal/tests/verify-suite-needs-its-own-services.sh [path/to/verify.sh]
#
# Runs verify.sh inside a throwaway copy of the repository layout, with docker,
# curl, java, unzip and sleep replaced by stubs on PATH and every gradlew, up.sh
# and check-spec replaced by recorders. Nothing real is started, downloaded,
# built or removed; the stub docker only writes down what it was asked. The
# case is the one the port collision produces: `docker run` fails to publish.
# The test reads whether the backend suite was run anyway.
#
# Creates only a temporary directory, which it removes on exit.
# =============================================================================
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
VERIFY="${1:-$HERE/../move/verify.sh}"
FAILED=0

run_case() { # name, docker-run exit code, expect suite ran (yes/no)
  local name="$1" run_exit="$2" expect="$3"
  local root; root="$(mktemp -d)"
  trap 'rm -rf "$root"' RETURN
  mkdir -p "$root/repo/dev-personal/move" "$root/repo/backend/gradle/wrapper" \
           "$root/repo/app/gradle/wrapper" "$root/repo/scripts" "$root/bin" "$root/home"
  cp "$VERIFY" "$root/repo/dev-personal/move/verify.sh"
  touch "$root/repo/backend/gradle/wrapper/gradle-wrapper.jar" "$root/repo/app/gradle/wrapper/gradle-wrapper.jar"
  echo 'pass' > "$root/repo/scripts/check-spec.py"
  printf '#!/usr/bin/env bash\nexit 0\n' > "$root/repo/dev-personal/up.sh"
  printf '#!/usr/bin/env bash\necho "$ALMIRA_TEST_DB_URL $*" >> "%s/suite.log"\nexit 0\n' "$root" > "$root/repo/backend/gradlew"
  printf '#!/usr/bin/env bash\nexit 0\n' > "$root/repo/app/gradlew"

  cat > "$root/bin/docker" <<STUB
#!/usr/bin/env bash
echo "\$*" >> "$root/docker.log"
case "\$1" in
  info) exit 0 ;;
  run) exit $run_exit ;;
  ps) exit 0 ;;
  exec)
    case "\$*" in
      *redis-cli*) [ $run_exit = 0 ] && echo PONG ;;
      *pg_isready*) exit $run_exit ;;
    esac
    exit 0 ;;
esac
exit 0
STUB
  cat > "$root/bin/curl" <<'STUB'
#!/usr/bin/env bash
# Preflight asks whether the app port is already serving: say no. Later health: say enforced.
case "$*" in *"--max-time 3"*) exit 22 ;; esac
echo '{"rlsEnforced":true}'
STUB
  printf '#!/usr/bin/env bash\necho %s >&2\n' "'openjdk version \"21.0.1\"'" > "$root/bin/java"
  printf '#!/usr/bin/env bash\nexit 0\n' > "$root/bin/unzip"
  printf '#!/usr/bin/env bash\nexit 0\n' > "$root/bin/sleep"
  chmod +x "$root/bin/"* "$root/repo/dev-personal/up.sh" "$root/repo/backend/gradlew" "$root/repo/app/gradlew"

  PATH="$root/bin:$PATH" ALMIRA_PERSONAL_HOME="$root/home" ALMIRA_PERSONAL_PORT=1 \
    bash "$root/repo/dev-personal/move/verify.sh" --no-android > "$root/out" 2>&1
  local status=$?

  local ran=no
  [ -s "$root/suite.log" ] && ran=yes
  if [ "$ran" = "$expect" ]; then
    echo "ok    $name (exit $status, suite ran: $ran)"
  else
    echo "FAIL  $name — suite ran: $ran, expected $expect (exit $status)"
    grep -E 'FAIL|ok ' "$root/out" | sed 's/^/        /' | head -20
    FAILED=1
  fi
}

run_case "scratch containers could not start: the suite is not run" 1 no
run_case "scratch containers started and answer: the suite runs" 0 yes

exit $FAILED
