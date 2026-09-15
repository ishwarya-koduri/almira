#!/usr/bin/env bash
# =============================================================================
# Stops whatever is listening on a port, and succeeds only once it is free.
#
#   ./dev-personal/uitest/claim-port.sh 18099
#
# run.sh used to kill the holder, sleep a second and start the bridge without
# looking again. A holder that outlived the kill (another user's process, one
# ignoring SIGTERM, one slow to exit) kept the port; the new bridge lost the bind
# and died into its log; and the tests ran against the old server, whose /health
# answers "ok" just the same. The check that the port is free now comes before
# the bridge is started.
# =============================================================================
set -uo pipefail
PORT="$1"

holders() { lsof -ti "tcp:$PORT" -sTCP:LISTEN 2>/dev/null | tr '\n' ' ' | sed 's/ *$//' || true; }

HELD="$(holders)"
if [ -n "$HELD" ]; then
  echo "  port $PORT was held by pid(s) $HELD — stopping them"
  # shellcheck disable=SC2086
  kill $HELD 2>/dev/null || true
fi

for _ in 1 2 3 4 5 6 7 8 9 10; do
  [ -z "$(holders)" ] && exit 0
  sleep 0.5
done

echo "port $PORT is still held by pid(s) $(holders) after asking them to stop — stop it by hand, then re-run" >&2
exit 1
