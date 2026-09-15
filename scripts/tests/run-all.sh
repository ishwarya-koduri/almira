#!/usr/bin/env bash
# =============================================================================
# Runs every operator-script test in this directory, one after another, and
# says which failed. Needs Docker for the ones that start throwaway containers.
#
#   ./scripts/tests/run-all.sh
#   SCRIPT_TEST_PREFIX=my-prefix ./scripts/tests/run-all.sh
# =============================================================================
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
FAILED=()
for test in "$HERE"/*.sh; do
  case "$(basename "$test")" in lib.sh|run-all.sh) continue;; esac
  bash "$test" || FAILED+=("$(basename "$test")")
  echo
done
if [ ${#FAILED[@]} -eq 0 ]; then
  echo "all script tests passed"
else
  echo "failed: ${FAILED[*]}"; exit 1
fi
