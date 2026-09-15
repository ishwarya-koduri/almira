#!/usr/bin/env bash
# =============================================================================
# scripts/dormancy-repair.sh refuses before it connects to anything unless the
# environment has turned the repair on (ALMIRA_OPS_DORMANCY_REPAIR=enabled).
#
#   ./scripts/tests/dormancy-repair-refuses-unless-enabled.sh
#
# `psql` on PATH is a stub that records being called, so a refusal is proven by
# the database never having been asked. No server, no container.
# =============================================================================
set -uo pipefail
. "$(dirname "$0")/lib.sh"

echo "${BOLD}dormancy-repair.sh: nothing is asked of the database unless the repair is enabled${OFF}"

mkdir -p "$WORK/bin"
cat > "$WORK/bin/psql" <<STUB
#!/usr/bin/env bash
echo "psql \$*" >> "$WORK/psql.calls"
cat > /dev/null
echo "outcome | waiting"
STUB
chmod +x "$WORK/bin/psql"

run() { # run <env value or "unset"> <args…>
  local value="$1"; shift
  : > "$WORK/psql.calls"
  if [ "$value" = unset ]; then
    env -u ALMIRA_OPS_DORMANCY_REPAIR PATH="$WORK/bin:$PATH" \
      bash "$REPO/scripts/dormancy-repair.sh" --url postgres://x@127.0.0.1/none --operator tester "$@" > "$WORK/out" 2>&1
  else
    ALMIRA_OPS_DORMANCY_REPAIR="$value" PATH="$WORK/bin:$PATH" \
      bash "$REPO/scripts/dormancy-repair.sh" --url postgres://x@127.0.0.1/none --operator tester "$@" > "$WORK/out" 2>&1
  fi
  STATUS=$?
}
untouched() { [ ! -s "$WORK/psql.calls" ]; }

for value in unset "" yes true; do
  run "$value" carry-out 00000000-0000-0000-0000-000000000001
  check "ALMIRA_OPS_DORMANCY_REPAIR=${value:-<empty>}: refused (exit $STATUS)" bash -c "[ $STATUS = 3 ] && grep -q 'Refusing' '$WORK/out'"
  check "ALMIRA_OPS_DORMANCY_REPAIR=${value:-<empty>}: the database was never asked" untouched
done

run enabled carry-out 00000000-0000-0000-0000-000000000001
check "enabled: it asks the database (exit $STATUS)" bash -c "[ $STATUS = 0 ] && grep -q 'psql' '$WORK/psql.calls'"

finish
