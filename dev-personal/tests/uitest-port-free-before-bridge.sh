#!/usr/bin/env bash
# =============================================================================
# claim-port.sh succeeds only when the port is actually free afterwards.
#
#   ./dev-personal/tests/uitest-port-free-before-bridge.sh [path/to/claim-port.sh]
#
# Uses listeners it starts itself, on ports the OS picks, and kills only them:
# one that exits on SIGTERM (the port is freed; claiming succeeds) and one that
# ignores it (the port stays held; claiming must fail rather than let run.sh
# start a bridge that loses the bind).
# =============================================================================
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CLAIM="${1:-$HERE/../uitest/claim-port.sh}"
FAILED=0

listen() { # ignore_term (1/0) -> prints "pid port"
  python3 - "$1" <<'PY' &
import signal, socket, sys, os, time
if sys.argv[1] == "1":
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
s = socket.socket(); s.bind(("127.0.0.1", 0)); s.listen()
print(os.getpid(), s.getsockname()[1], flush=True)
time.sleep(60)
PY
}

check() { # name, ignore_term, expected exit
  local name="$1" out; out="$(mktemp)"
  listen "$2" > "$out"
  local pid port
  for _ in 1 2 3 4 5 6 7 8 9 10; do read -r pid port < "$out" && [ -n "${port:-}" ] && break; sleep 0.2; done
  bash "$CLAIM" "$port" > /dev/null 2>&1
  local status=$?
  kill -9 "$pid" 2>/dev/null || true
  { wait "$pid"; } 2>/dev/null || true
  rm -f "$out"
  if [ "$status" = "$3" ]; then echo "ok    $name (exit $status)"; else echo "FAIL  $name — exit $status, expected $3"; FAILED=1; fi
}

check "a holder that stops frees the port, and claiming succeeds" 0 0
check "a holder that ignores SIGTERM keeps the port, and claiming fails" 1 1

exit $FAILED
