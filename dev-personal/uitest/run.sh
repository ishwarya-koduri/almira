#!/usr/bin/env bash
# =============================================================================
# Run the iOS thin-slice UI tests on the simulator.
#
#   ./dev-personal/uitest/run.sh                 every test
#   ./dev-personal/uitest/run.sh test3_capture…  one of them
#
# Four preconditions, each learnt by a run failing without it. They are set here
# rather than written down as instructions, because a precondition somebody has
# to remember is a precondition that gets forgotten.
# =============================================================================
set -euo pipefail

DEVICE="${ALMIRA_SIM_DEVICE:-iPhone 17 Pro}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
BOLD=$'\033[1m'; DIM=$'\033[2m'; OFF=$'\033[0m'

# shellcheck disable=SC1091
source "$HOME/Developer/almira-personal/env.sh" > /dev/null

echo "${BOLD}Booting $DEVICE…${OFF}"
xcrun simctl boot "$DEVICE" 2>/dev/null || true
xcrun simctl bootstatus "$DEVICE" -b > /dev/null 2>&1 || true

# Face ID enrolment. It does not survive the device shutting down, and
# `xcodebuild test` shuts it down when it finishes — so without this a second
# run meets a passcode-only device and the match notification goes nowhere.
echo "${BOLD}Enrolling Face ID…${OFF}"
xcrun simctl spawn "$DEVICE" notifyutil -s com.apple.BiometricKit.enrollmentChanged 1 > /dev/null
xcrun simctl spawn "$DEVICE" notifyutil -p com.apple.BiometricKit.enrollmentChanged > /dev/null

# iOS's first-run keyboard tutorials. They appear as sheets over the whole app,
# are absent from the app's own accessibility tree, and swallow every tap
# underneath. Simulator-local; undone by erasing the device.
for setting in "com.apple.Preferences UIKeyboardDidShowInternationalInfoIntroduction" \
               "com.apple.Preferences DidShowContinuousPathIntroduction" \
               "com.apple.keyboard.preferences DidShowGestureKeyboardIntroduction"; do
  # shellcheck disable=SC2086
  xcrun simctl spawn "$DEVICE" defaults write $setting -bool true > /dev/null 2>&1 || true
done

# The OTP throttle. Five codes an hour per number, and a tighter cap per source
# address — every request here arrives from the same Docker gateway.
"$ROOT/dev-personal/clear-otp-limits.sh"

echo "${BOLD}Starting the bridge…${OFF}"
pkill -f "uitest/bridge.py" 2>/dev/null || true
# Take the port from whatever else is holding it, rather than starting a second
# server that silently loses the bind and then answering from the wrong one.
HOLDER=$(lsof -ti tcp:18099 2>/dev/null || true)
if [ -n "$HOLDER" ]; then
  echo "  ${DIM}port 18099 was held by pid(s) $HOLDER — stopping them${OFF}"
  # shellcheck disable=SC2086
  kill $HOLDER 2>/dev/null || true
  sleep 1
fi
python3 "$HERE/bridge.py" > "$HERE/bridge.log" 2>&1 &
BRIDGE=$!
trap 'kill $BRIDGE 2>/dev/null || true' EXIT
sleep 2
curl -fsS -o /dev/null "http://127.0.0.1:18099/health" \
  || { echo "the bridge did not answer on 18099 — see $HERE/bridge.log" >&2; exit 1; }

# `set -u` treats an empty array expansion as unbound on bash 3, which is the
# bash macOS ships, so give it a value it can always expand.
if [ $# -ge 1 ]; then
  ONLY=(-only-testing:"AlmiraUITests/ThinSliceUITests/$1")
else
  ONLY=()
fi

echo "${BOLD}Running…${OFF}"
cd "$ROOT/app/iosApp"
set +e
xcodebuild test -project iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=$DEVICE" \
  -derivedDataPath "$HOME/Developer/almira-personal/xcode-derived" \
  ${ONLY[@]+"${ONLY[@]}"} > "$HERE/last-run.log" 2>&1
set -e

echo
grep -aE "^/Users.*error:|Test Case .* (passed|failed)|TEST (SUCCEEDED|FAILED)" "$HERE/last-run.log" || true
echo
echo "${DIM}What the app showed, last two screens:${OFF}"
grep -a "|" "$HERE/last-run.log" | grep -av "^ *t =" | tail -2 || true
echo
echo "${DIM}Full log: $HERE/last-run.log${OFF}"
