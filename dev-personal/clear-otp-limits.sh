#!/usr/bin/env bash
# =============================================================================
# Clear the OTP throttle counters in the personal dev stack.
#
#   ./dev-personal/clear-otp-limits.sh [phone]
#
# Five codes an hour per phone and a separate cap per source address is right
# for production and far too few for a testing loop, so this exists. With a
# phone argument it clears that number's counter and the address counters;
# with none, every otp:rate:* key.
#
# It refuses to succeed quietly. That is the whole point of the file: the
# obvious one-liner
#
#     docker exec almira-personal-redis redis-cli --scan --pattern 'otp:rate:*' \
#       | xargs -r redis-cli del
#
# looks like it works and does nothing at all, because this Redis runs with
# requirepass and an unauthenticated client answers NOAUTH on stderr while the
# pipeline exits zero. That cost real time: a whole session's worth of "I
# cleared the counters" was untrue, and the throttle was only ever expiring on
# its own. So every step here is checked and every failure is loud.
#
# The password is read from the running container's own command line, which is
# where compose already resolved ${ALMIRA_REDIS_PASSWORD}. Nothing is duplicated
# and nothing is hardcoded: change it in the compose file and this follows.
# =============================================================================
set -euo pipefail

CONTAINER=almira-personal-redis
RED=$'\033[31m'; GREEN=$'\033[32m'; BOLD=$'\033[1m'; DIM=$'\033[2m'; OFF=$'\033[0m'
die() { echo "${RED}${BOLD}$1${OFF}" >&2; exit 1; }

docker inspect "$CONTAINER" > /dev/null 2>&1 \
  || die "$CONTAINER is not running. Start the stack with ./dev-personal/up.sh"

PASSWORD=$(docker inspect "$CONTAINER" --format '{{json .Config.Cmd}}' \
  | python3 -c 'import json,sys; c=json.load(sys.stdin) or []; i=c.index("--requirepass"); print(c[i+1])' \
  2>/dev/null) \
  || die "could not read --requirepass off $CONTAINER's command line.
If the compose file no longer passes it there, teach this script where it moved
rather than falling back to an unauthenticated client — that is the failure this
file exists to prevent."

redis() { docker exec "$CONTAINER" redis-cli -a "$PASSWORD" --no-auth-warning "$@"; }

# Authenticate before anything else, so a wrong password is reported here and
# not mistaken for "there was nothing to clear".
PONG=$(redis ping 2>&1 || true)
[ "$PONG" = "PONG" ] || die "Redis did not accept the password taken from the container: $PONG"

if [ $# -ge 1 ]; then
  PHONE="$1"
  case "$PHONE" in +*) ;; *) PHONE="+91$PHONE" ;; esac
  PATTERNS=("otp:rate:phone:$PHONE" "otp:rate:ip:*")
  echo "${BOLD}Clearing the throttle for $PHONE and every source address…${OFF}"
else
  PATTERNS=("otp:rate:*")
  echo "${BOLD}Clearing every OTP throttle counter…${OFF}"
fi

KEYS=()
for pattern in "${PATTERNS[@]}"; do
  while IFS= read -r key; do
    [ -n "$key" ] && KEYS+=("$key")
  done < <(redis --scan --pattern "$pattern" | tr -d '\r')
done

if [ ${#KEYS[@]} -eq 0 ]; then
  echo "  ${DIM}nothing was throttled${OFF}"
  exit 0
fi

printf '  %s\n' "${KEYS[@]}"
DELETED=$(redis del "${KEYS[@]}" | tr -d '\r')
[ "$DELETED" = "${#KEYS[@]}" ] \
  || die "asked Redis to delete ${#KEYS[@]} keys and it deleted $DELETED. Nothing here should be racing this."

# And confirm, rather than trusting the count we were just given.
LEFT=$(redis --scan --pattern 'otp:rate:*' | tr -d '\r' | grep -c . || true)
if [ $# -ge 1 ]; then
  echo "  ${GREEN}deleted $DELETED; $LEFT other throttle key(s) remain${OFF}"
else
  [ "$LEFT" = "0" ] || die "cleared $DELETED keys but $LEFT otp:rate:* keys are still there."
  echo "  ${GREEN}deleted $DELETED; none remain${OFF}"
fi
