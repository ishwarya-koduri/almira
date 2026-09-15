#!/usr/bin/env bash
# =============================================================================
# scripts/smoke-prod.sh writes nothing — no one-time code requested, nobody
# signed in, no household created — when the deployment's own checks fail.
#
#   ./scripts/tests/smoke-prod-stops-before-writing.sh
#
# Runs the real script with `curl` on PATH answering as each case says and
# recording every request. No server, no container.
# =============================================================================
set -uo pipefail
. "$(dirname "$0")/lib.sh"

echo "${BOLD}smoke-prod.sh: stops before writing when the deployment fails its checks${OFF}"

mkdir -p "$WORK/bin"
cat > "$WORK/bin/curl" <<STUB
#!/usr/bin/env bash
echo "\$*" >> "$CALLS"
url="" method=GET
for a in "\$@"; do case "\$a" in http*) url="\$a";; POST|PATCH|PUT|DELETE) method="\$a";; esac; done
case "\$*" in *"-w %{http_code}"*|*"-w"*) printf 200; exit 0;; esac
case "\$*" in *-sSI*) printf 'HTTP/1.1 200\r\ncontent-type: application/manifest+json\r\n\r\n'; exit 0;; esac
case "\$url" in
  */health) echo "\$HEALTH_BODY";;
  */auth/otp/request) echo '{"error":{"code":"otp_unavailable"}}';;
  *) echo '{}';;
esac
STUB
chmod +x "$WORK/bin/curl"

smoke() { # smoke <health body>
  : > "$CALLS"
  HEALTH_BODY="$1" PATH="$WORK/bin:$PATH" bash "$REPO/scripts/smoke-prod.sh" https://smoke.invalid > "$WORK/out" 2>&1 < /dev/null
  STATUS=$?
}
wrote_nothing() { ! grep -Eq -- '-X (POST|PATCH|PUT|DELETE)' "$CALLS"; }

smoke '{"status":"ok","database":"up","dbRole":"almira_app","rlsEnforced":false,"environment":"production"}'
check "row-level security not enforced: fails (exit $STATUS)" \
  bash -c "[ $STATUS != 0 ] && grep -q 'Stopped before writing anything' '$WORK/out'"
check "  and nothing was written: no code requested, nobody signed in, no household" wrote_nothing

smoke '{"status":"ok","database":"up","dbRole":"almira","rlsEnforced":true,"environment":"production"}'
check "connected as the schema owner: fails (exit $STATUS)" \
  bash -c "[ $STATUS != 0 ] && grep -q 'Stopped before writing anything' '$WORK/out'"
check "  and nothing was written" wrote_nothing

smoke '{"status":"ok","database":"down","dbRole":"almira_app","rlsEnforced":true,"environment":"production"}'
check "database down: fails (exit $STATUS)" \
  bash -c "[ $STATUS != 0 ] && grep -q 'Stopped before writing anything' '$WORK/out'"
check "  and nothing was written" wrote_nothing

# The harness can see a write: a healthy deployment goes on to request a code
# (which this stub answers as unavailable, so the script stops there cleanly).
smoke '{"status":"ok","database":"up","dbRole":"almira_app","rlsEnforced":true,"environment":"production"}'
check "a healthy deployment goes on to the writes (exit $STATUS)" \
  bash -c "[ $STATUS = 0 ] && grep -Eq -- '-X POST .*/auth/otp/request' '$CALLS'"

finish
