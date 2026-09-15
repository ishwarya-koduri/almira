#!/usr/bin/env bash
# =============================================================================
# scripts/freeze-api-spec.sh replaces the frozen contract only with a spec it
# has fetched completely and checked — a failure leaves the file as it was.
#
#   ./scripts/tests/freeze-api-spec-validates-before-replacing.sh
#
# Runs the real script from a copy of the repository layout in a temporary
# directory (so docs/api/openapi-v1.json in this checkout is never touched),
# with `curl` on PATH answering as each case says. No server, no container.
# =============================================================================
set -uo pipefail
. "$(dirname "$0")/lib.sh"

echo "${BOLD}freeze-api-spec.sh: the frozen file is replaced only by a valid spec${OFF}"

COPY="$WORK/repo"
mkdir -p "$COPY/scripts" "$COPY/docs/api" "$WORK/bin"
cp "$REPO/scripts/freeze-api-spec.sh" "$COPY/scripts/"
FROZEN='{"components":{"schemas":{"Old":{}}},"info":{"title":"Almira API","version":"1"},"openapi":"3.0.1","paths":{"/api/v1/old":{"get":{}}}}'

cat > "$WORK/bin/curl" <<'STUB'
#!/usr/bin/env bash
url="${@: -1}"
case "$url" in
  */health) echo '{"status":"ok"}';;
  */v3/api-docs)
    case "$SPEC_CASE" in
      http-error) echo '<html>500</html>'; exit 22;;
      html)       echo '<html><body>Whitelabel Error Page</body></html>';;
      truncated)  printf '{"openapi":"3.0.1","info":{"title":"Almira API","version":"1"},"paths":{"/api/v1/a":';;
      no-paths)   echo '{"openapi":"3.0.1","info":{"title":"Almira API","version":"1"},"paths":{},"components":{"schemas":{}}}';;
      good)       echo '{"openapi":"3.0.1","info":{"title":"Almira API","version":"1"},"paths":{"/api/v1/new":{"get":{}}},"components":{"schemas":{"New":{}}}}';;
    esac;;
esac
STUB
chmod +x "$WORK/bin/curl"

freeze() { # freeze <case>
  printf '%s\n' "$FROZEN" > "$COPY/docs/api/openapi-v1.json"
  SPEC_CASE="$1" PATH="$WORK/bin:$PATH" bash "$COPY/scripts/freeze-api-spec.sh" > "$WORK/out" 2>&1
  STATUS=$?
}
unchanged() { [ "$(cat "$COPY/docs/api/openapi-v1.json")" = "$FROZEN" ]; }
no_leftovers() { [ "$(ls "$COPY/docs/api")" = "openapi-v1.json" ]; }

for case in http-error html truncated no-paths; do
  freeze "$case"
  check "$case: refused (exit $STATUS)" bash -c "[ $STATUS != 0 ] && grep -q 'is unchanged' '$WORK/out'"
  check "$case: the frozen file is exactly as it was" unchanged
  check "$case: no temporary file is left beside it" no_leftovers
done

freeze good
check "a complete spec is frozen (exit $STATUS)" bash -c "[ $STATUS = 0 ] && grep -q '1 paths, 1 operations, 1 schemas' '$WORK/out'"
check "sorted and indented, with the new path" \
  bash -c "grep -q '\"/api/v1/new\"' '$COPY/docs/api/openapi-v1.json' && head -1 '$COPY/docs/api/openapi-v1.json' | grep -qx '{'"
check "and no temporary file is left beside it" no_leftovers

finish
