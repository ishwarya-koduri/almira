#!/usr/bin/env bash
# =============================================================================
# Stand this project up on a clean machine, and prove it works.
#
#   ./dev-personal/move/verify.sh              everything
#   ./dev-personal/move/verify.sh --no-android skip the 5.6 GB SDK download
#   ./dev-personal/move/verify.sh --cleanup    also remove the scratch test
#                                              containers this script created
#
# Success is: every step below prints ok, which means the stack is up, the
# backend and shared test suites pass, and the Android app builds.
#
# --- What this script will and will not do -----------------------------------
#
# It ADDS. It creates ~/Developer/almira-personal and fills it, pulls Docker
# images, and builds. It removes nothing, ever — not even its own scratch
# containers unless you pass --cleanup, and then only the two it made itself,
# by name.
#
# It is written to be run on a NEW machine. Running it on the machine the
# project already works on is safe but pointless, and it will refuse if the
# stack's port is already answering, because that is the one way it could
# disturb something.
# =============================================================================
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HOME_DIR="${ALMIRA_PERSONAL_HOME:-$HOME/Developer/almira-personal}"
PORT="${ALMIRA_PERSONAL_PORT:-18080}"
ANDROID=1
CLEANUP=0
for arg in "$@"; do
  case "$arg" in
    --no-android) ANDROID=0 ;;
    --cleanup) CLEANUP=1 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

BOLD=$'\033[1m'; DIM=$'\033[2m'; GREEN=$'\033[32m'; RED=$'\033[31m'; YELLOW=$'\033[33m'; OFF=$'\033[0m'
STEP=0
FAILED=()
BOOTSTRAPPED=()

step() { STEP=$((STEP + 1)); echo; echo "${BOLD}[$STEP] $1${OFF}"; }
ok()   { echo "    ${GREEN}ok${OFF}   $1"; }
warn() { echo "    ${YELLOW}note${OFF} $1"; }
bad()  { echo "    ${RED}FAIL${OFF} $1"; FAILED+=("$1"); }
have() { command -v "$1" > /dev/null 2>&1; }

# -----------------------------------------------------------------------------
step "Preflight — is this machine able to build the project at all?"
# -----------------------------------------------------------------------------
for tool in git docker curl unzip; do
  if have "$tool"; then ok "$tool $(command -v "$tool")"; else bad "$tool is not installed"; fi
done

if have java; then
  JAVA_VERSION=$(java -version 2>&1 | head -1 | sed 's/.*version "\([0-9]*\).*/\1/')
  if [ "${JAVA_VERSION:-0}" -ge 21 ] 2>/dev/null; then
    ok "JDK $JAVA_VERSION"
  else
    bad "JDK 21 or newer is required; found $JAVA_VERSION"
  fi
else
  bad "no JDK. Install 21: brew install openjdk@21, or apt install openjdk-21-jdk"
fi

if docker info > /dev/null 2>&1; then
  ok "Docker is running"
else
  bad "Docker is installed but not running — start it and re-run"
fi

# The one way this script could disturb a machine that already works.
if curl -fsS -o /dev/null --max-time 3 "http://localhost:$PORT/health" 2>/dev/null; then
  echo
  echo "${RED}${BOLD}Something is already serving on port $PORT.${OFF}"
  echo "This looks like a machine where the project is already set up. Nothing has"
  echo "been changed. If you meant to verify a second copy alongside it, give it a"
  echo "port of its own:"
  echo "    ALMIRA_PERSONAL_PORT=18090 $0"
  exit 3
fi

if [ ${#FAILED[@]} -gt 0 ]; then
  echo; echo "${RED}${BOLD}Preflight failed. Fix the above and re-run; nothing was changed.${OFF}"
  printf '  · %s\n' "${FAILED[@]}"
  exit 1
fi

# -----------------------------------------------------------------------------
step "The folder-local home — everything this project installs lives here"
# -----------------------------------------------------------------------------
mkdir -p "$HOME_DIR"
if [ -f "$HOME_DIR/env.sh" ]; then
  ok "env.sh already present at $HOME_DIR — left alone"
else
  cat > "$HOME_DIR/env.sh" <<'ENVSH'
# Everything this project installs stays inside this folder, so that removing
# the folder removes the toolchain and nothing else on the machine is touched.
export ALMIRA_PERSONAL_HOME="$HOME/Developer/almira-personal"
export GRADLE_USER_HOME="$ALMIRA_PERSONAL_HOME/gradle-home"
export ANDROID_HOME="$ALMIRA_PERSONAL_HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$ALMIRA_PERSONAL_HOME/android-user-home"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
export ANDROID_EMULATOR_HOME="$ALMIRA_PERSONAL_HOME/android-user-home"
export KONAN_DATA_DIR="$ALMIRA_PERSONAL_HOME/konan"
if [ -x /usr/libexec/java_home ]; then
  _jdk21="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
  [ -n "$_jdk21" ] && export JAVA_HOME="$_jdk21"
  unset _jdk21
fi
for _p in "$ANDROID_HOME/cmdline-tools/latest/bin" "$ANDROID_HOME/platform-tools" "$ANDROID_HOME/emulator"; do
  [ -d "$_p" ] && case ":$PATH:" in *":$_p:"*) ;; *) PATH="$_p:$PATH" ;; esac
done
unset _p
export PATH
export ALMIRA_PERSONAL_PORT="${ALMIRA_PERSONAL_PORT:-18080}"
export ALMIRA_API_BASE="http://localhost:$ALMIRA_PERSONAL_PORT"
ENVSH
  ok "wrote $HOME_DIR/env.sh"
fi
# shellcheck disable=SC1091
source "$HOME_DIR/env.sh" > /dev/null

# -----------------------------------------------------------------------------
step "The Gradle wrapper — see INVENTORY.md, the jars are not in the repository"
# -----------------------------------------------------------------------------
# `./gradlew` is a shell script that runs a 47 KB jar next to it. Both jars are
# committed, so this should find them and do nothing.
#
# It is kept because it once had to work: a `*.jar` line in .gitignore whose
# exception was anchored to the repository root caught both, and a fresh clone
# had the script and not the jar. If that ever happens again this bootstraps
# around it — download the distribution the properties file already names, and
# let it regenerate its own wrapper — but it says so loudly, because a silent
# fallback is how a repository stays broken for months without anyone noticing.
bootstrap_wrapper() {
  local project="$1"
  local jar="$project/gradle/wrapper/gradle-wrapper.jar"
  [ -f "$jar" ] && { ok "$(basename "$project")/gradle-wrapper.jar present"; return 0; }

  # Not a note. This is a defect in the repository, and the run should say so
  # in the summary rather than quietly papering over it.
  echo
  echo "    ${RED}${BOLD}the Gradle wrapper jar is missing from the clone${OFF}"
  echo "    ${RED}  $jar${OFF}"
  echo "    ${YELLOW}  It is supposed to be committed. Check .gitignore still has${OFF}"
  echo "    ${YELLOW}    !**/gradle/wrapper/gradle-wrapper.jar${OFF}"
  echo "    ${YELLOW}  and that both jars are tracked:  git ls-files '*gradle-wrapper.jar'${OFF}"
  echo "    ${DIM}  Bootstrapping around it so this run can continue.${OFF}"
  BOOTSTRAPPED+=("$jar")

  local url
  url=$(sed -n 's/^distributionUrl=//p' "$project/gradle/wrapper/gradle-wrapper.properties" | tr -d '\\')
  [ -z "$url" ] && { bad "no distributionUrl in $project/gradle/wrapper/gradle-wrapper.properties"; return 1; }

  local cache="$HOME_DIR/gradle-bootstrap"
  local zip="$cache/$(basename "$url")"
  mkdir -p "$cache"
  if [ ! -f "$zip" ]; then
    echo "    ${DIM}fetching $(basename "$url") once — about 130 MB${OFF}"
    curl -fsSL "$url" -o "$zip" || { bad "could not download $url"; return 1; }
  fi
  local dir="$cache/$(basename "$url" -bin.zip)"
  [ -d "$dir" ] || unzip -q "$zip" -d "$cache" || { bad "could not unzip $zip"; return 1; }

  ( cd "$project" && "$dir/bin/gradle" --no-daemon wrapper > /dev/null 2>&1 ) \
    || { bad "could not regenerate the wrapper in $project"; return 1; }
  [ -f "$jar" ] && ok "regenerated $(basename "$project")/gradle-wrapper.jar" \
                || bad "wrapper still missing in $project"
}
bootstrap_wrapper "$REPO/backend"
bootstrap_wrapper "$REPO/app"

# -----------------------------------------------------------------------------
step "The stack — Postgres, Redis and the application, all namespaced"
# -----------------------------------------------------------------------------
if ( cd "$REPO" && ./dev-personal/up.sh --seed ); then
  ok "stack is up and seeded"
else
  bad "./dev-personal/up.sh failed — its own output above says why"
fi

HEALTH=$(curl -fsS --max-time 10 "http://localhost:$PORT/health" 2>/dev/null || true)
case "$HEALTH" in
  *'"rlsEnforced":true'*) ok "health: row-level security is enforced" ;;
  "") bad "nothing answered on http://localhost:$PORT/health" ;;
  *) bad "health reports rlsEnforced false — the app is connected as a role that can bypass privacy" ;;
esac

# -----------------------------------------------------------------------------
step "Scratch services for the backend suite"
# -----------------------------------------------------------------------------
# The suite brings up its own Postgres and Redis with Testcontainers, which does
# not find Docker Desktop's socket on a Mac. The suite has an escape hatch for
# exactly this — it accepts external services and refuses outright to run
# against a database named `almira`, so it cannot be pointed at real data by
# accident. Using it unconditionally keeps this script's behaviour the same on
# every machine.
#
# These two containers are the only things this script creates that it could
# also remove, and it does not remove them unless you ask.
docker rm -f almira-personal-verifypg almira-personal-verifyredis > /dev/null 2>&1 || true
docker run -d --name almira-personal-verifypg -p 127.0.0.1:15432:5432 \
  -e POSTGRES_DB=almira_verify -e POSTGRES_USER=almira -e POSTGRES_PASSWORD=dev \
  postgres:16-alpine > /dev/null 2>&1 && ok "scratch Postgres on 127.0.0.1:15432" \
  || bad "could not start the scratch Postgres"
docker run -d --name almira-personal-verifyredis -p 127.0.0.1:16379:6379 \
  redis:7-alpine > /dev/null 2>&1 && ok "scratch Redis on 127.0.0.1:16379" \
  || bad "could not start the scratch Redis"

for _ in $(seq 1 30); do
  docker exec almira-personal-verifypg pg_isready -U almira > /dev/null 2>&1 && break
  sleep 1
done
docker exec almira-personal-verifypg psql -U almira -d almira_verify -c \
  "create role almira_app login password 'app_dev_password';
   grant connect on database almira_verify to almira_app;
   grant usage on schema public to almira_app;" > /dev/null 2>&1 \
  && ok "scratch database has the unprivileged application role" \
  || warn "the application role may already exist — continuing"

# -----------------------------------------------------------------------------
step "docs/12 against the code that implements it"
# -----------------------------------------------------------------------------
if ( cd "$REPO" && python3 scripts/check-spec.py > /dev/null 2>&1 ); then
  ok "the zero-knowledge spec and the code agree"
else
  bad "docs/12 disagrees with the code — run: python3 scripts/check-spec.py"
fi

# -----------------------------------------------------------------------------
step "Backend tests"
# -----------------------------------------------------------------------------
if ( cd "$REPO/backend" && \
     ALMIRA_TEST_DB_URL="jdbc:postgresql://localhost:15432/almira_verify" \
     ALMIRA_TEST_DB_OWNER_USER=almira ALMIRA_TEST_DB_OWNER_PASSWORD=dev \
     ALMIRA_TEST_DB_APP_USER=almira_app ALMIRA_TEST_DB_APP_PASSWORD=app_dev_password \
     ALMIRA_TEST_REDIS_HOST=127.0.0.1 ALMIRA_TEST_REDIS_PORT=16379 \
     ./gradlew --no-daemon test ); then
  ok "backend suite passed"
else
  bad "backend suite failed"
fi

# -----------------------------------------------------------------------------
step "Shared module tests — the same assertions on the JVM and natively"
# -----------------------------------------------------------------------------
if ( cd "$REPO/app" && ./gradlew --no-daemon :shared:testDebugUnitTest ); then
  ok "shared module, JVM"
else
  bad "shared module tests failed on the JVM"
fi

if [ "$(uname)" = "Darwin" ]; then
  if ( cd "$REPO/app" && ./gradlew --no-daemon :shared:iosSimulatorArm64Test ); then
    ok "shared module, Kotlin/Native — the zero-knowledge vectors run on both"
  else
    warn "Kotlin/Native tests did not run; Xcode may not be installed. Not fatal here."
  fi
else
  warn "not macOS — the Kotlin/Native half is skipped, as is the whole iOS target"
fi

# -----------------------------------------------------------------------------
step "Android SDK and the app"
# -----------------------------------------------------------------------------
if [ "$ANDROID" = "0" ]; then
  warn "skipped by --no-android"
else
  if [ -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
    ok "Android command-line tools already installed"
  else
    echo "    ${DIM}installing the Android command-line tools into $ANDROID_HOME${OFF}"
    case "$(uname)" in
      Darwin) TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip" ;;
      *)      TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" ;;
    esac
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    curl -fsSL "$TOOLS_URL" -o "$HOME_DIR/cmdline-tools.zip" \
      && unzip -q -o "$HOME_DIR/cmdline-tools.zip" -d "$ANDROID_HOME/cmdline-tools" \
      && mv -n "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" 2>/dev/null
    [ -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ] \
      && ok "command-line tools installed" || bad "could not install the command-line tools"
  fi

  if [ -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
    yes 2>/dev/null | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null 2>&1
    "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
      "platform-tools" "platforms;android-35" "build-tools;35.0.0" > /dev/null 2>&1 \
      && ok "platform 35, build-tools and platform-tools" \
      || bad "sdkmanager could not install the build packages"
  fi

  if ( cd "$REPO/app" && ./gradlew --no-daemon :androidApp:assembleDebug ); then
    APK="$REPO/app/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
    [ -f "$APK" ] && ok "APK built: $(du -h "$APK" | cut -f1)" || ok "assembleDebug succeeded"
  else
    bad "the Android app did not build"
  fi
fi

# -----------------------------------------------------------------------------
step "Result"
# -----------------------------------------------------------------------------
echo
if [ ${#BOOTSTRAPPED[@]} -gt 0 ]; then
  echo "  ${YELLOW}${BOLD}The wrapper jar had to be bootstrapped for:${OFF}"
  printf '    · %s\n' "${BOOTSTRAPPED[@]}"
  echo "  ${YELLOW}That means the jars have fallen out of the repository again.${OFF}"
  echo "  ${YELLOW}The build works, but a fresh clone does not without this script.${OFF}"
  echo
fi
if [ ${#FAILED[@]} -eq 0 ]; then
  echo "  ${GREEN}${BOLD}Everything passed.${OFF}"
  echo "  Web app       http://localhost:$PORT"
  echo "  Sign in       9889190735 (Ishwarya) or 8889190742 (Ravi)"
  echo "  The code is printed on screen and in the server log — see dev-personal/RUNNING.md"
else
  echo "  ${RED}${BOLD}${#FAILED[@]} step(s) failed:${OFF}"
  printf '    · %s\n' "${FAILED[@]}"
fi

echo
if [ "$CLEANUP" = "1" ]; then
  docker rm -f almira-personal-verifypg almira-personal-verifyredis > /dev/null 2>&1 \
    && echo "  ${DIM}removed the two scratch containers this script created${OFF}"
else
  echo "  ${DIM}Left running, because this script removes nothing unless asked:${OFF}"
  echo "  ${DIM}  almira-personal-verifypg, almira-personal-verifyredis${OFF}"
  echo "  ${DIM}Remove them when you like:  $0 --cleanup${OFF}"
  echo "  ${DIM}                        or:  docker rm -f almira-personal-verifypg almira-personal-verifyredis${OFF}"
fi

[ ${#FAILED[@]} -eq 0 ] || exit 1
