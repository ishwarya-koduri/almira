#!/usr/bin/env bash
# =============================================================================
# scripts/check-health.sh --ops-token-file: a sign-in email that an allowlisted
# address did not get fails the check and runs the alert command, with a count
# and never an address; the token never appears on a command line.
#
#   ./scripts/tests/check-health-operator-view.sh
#
# Runs the real script with `curl` on PATH answering as each case says and
# recording every call, and a recording alert command. No server, no container.
#
#   1. Without --ops-token-file: unchanged, and no token header is sent.
#   2. With it and nothing undelivered: ok, and the header carried the token
#      from a file, not an argument.
#   3. With it and two undelivered: fails, and the alert command is run with
#      the count, and nothing that looks like an address.
#   4. With it and no operator view in the answer (a wrong token, or none set
#      on the server): fails rather than passing quietly.
# =============================================================================
set -uo pipefail
. "$(dirname "$0")/lib.sh"

echo "${BOLD}check-health.sh: the operator's view of undelivered sign-in emails${OFF}"

TOKEN="ops-token-$(python3 -c 'import secrets; print(secrets.token_hex(16))')"
printf '%s\n' "$TOKEN" > "$WORK/token"
ALERTS="$WORK/alerts"
printf '#!/usr/bin/env bash\necho "$*" >> "%s"\n' "$ALERTS" > "$WORK/alert.sh"
chmod +x "$WORK/alert.sh"

mkdir -p "$WORK/bin"
cat > "$WORK/bin/curl" <<STUB
#!/usr/bin/env bash
echo "\$*" >> "$CALLS"
out="" url="" header=""
while [ \$# -gt 0 ]; do
  case "\$1" in
    -o) out="\$2"; shift 2;;
    -H) header="\$2"; shift 2;;
    -w|--max-time) shift 2;;
    http*) url="\$1"; shift;;
    *) shift;;
  esac
done
case "\$header" in @*) sed 's/^/HEADER-FILE: /' "\${header#@}" >> "$CALLS";; esac
case "\$url" in
  */health/live) echo '{"status":"alive"}' > "\$out";;
  */health/ready) echo '{"status":"ready"}' > "\$out";;
  */health) echo "\$HEALTH_BODY" > "\$out";;
esac
printf 200
STUB
chmod +x "$WORK/bin/curl"

run() { # run <health body> [extra args…]
  : > "$CALLS"; : > "$ALERTS"
  HEALTH_BODY="$1" PATH="$WORK/bin:$PATH" bash "$REPO/scripts/check-health.sh" --base https://health.invalid \
    --alert-cmd "$WORK/alert.sh" "${@:2}" > "$WORK/out" 2>&1
  STATUS=$?
}
BASE_BODY='"status":"ok","database":"up","dbRole":"almira_app","rlsEnforced":true,"environment":"production"'
token_not_in_arguments() { ! grep -v '^HEADER-FILE: ' "$CALLS" | grep -q "$TOKEN"; }

run "{$BASE_BODY}"
check "1 · without --ops-token-file it passes as before (exit $STATUS)" bash -c "[ $STATUS = 0 ] && ! grep -q 'sign-in' '$WORK/out'"
check "    and sends no token" bash -c "! grep -q 'X-Almira-Ops-Token' '$CALLS'"

run "{$BASE_BODY,\"signInEmailNotDelivered\":{\"lastHour\":0,\"newest\":\"\"}}" --ops-token-file "$WORK/token"
check "2 · nothing undelivered: ok (exit $STATUS)" bash -c "[ $STATUS = 0 ] && grep -q 'no undelivered sign-in email' '$WORK/out'"
check "    the token went in a header, read from a file" grep -q "HEADER-FILE: X-Almira-Ops-Token: $TOKEN" "$CALLS"
check "    and is on no command line" token_not_in_arguments
check "    and no alert was run" bash -c "[ ! -s '$ALERTS' ]"

run "{$BASE_BODY,\"signInEmailNotDelivered\":{\"lastHour\":2,\"newest\":\"2026-09-15T04:00:00Z\"}}" --ops-token-file "$WORK/token"
check "3 · two undelivered: fails (exit $STATUS)" bash -c "[ $STATUS = 1 ] && grep -q '2 sign-in email(s) to an allowlisted address not delivered' '$WORK/out'"
check "    and the alert command is run once, with the count" bash -c "[ \"\$(wc -l < '$ALERTS' | tr -d ' ')\" = 1 ] && grep -q 'SIGN-IN EMAIL NOT DELIVERED' '$ALERTS'"
check "    naming no address" bash -c "! grep -q '@' '$ALERTS' '$WORK/out'"
check "    and the token is on no command line" token_not_in_arguments

run "{$BASE_BODY}" --ops-token-file "$WORK/token"
check "4 · the operator view missing fails, not passes (exit $STATUS)" \
  bash -c "[ $STATUS = 1 ] && grep -q 'operator view of /health is missing' '$WORK/out' && [ -s '$ALERTS' ]"

finish
