#!/usr/bin/env bash
# =============================================================================
# The operator repair for a dormant household nobody in it may take on
# (docs/05 §12.7, V137). Never in the family app.
#
#   ./scripts/dormancy-repair.sh --env-file .env.production --operator "support-1" list
#   ./scripts/dormancy-repair.sh … request <household-id> <member-id> "<reason>" \
#       "<requester name>" "<relationship>" "<evidence reference>" [wait, e.g. "21 days"]
#   ./scripts/dormancy-repair.sh … carry-out <request-id>
#   ./scripts/dormancy-repair.sh … withdraw <request-id> "<reason>"
#
# Refuses unless ALMIRA_OPS_DORMANCY_REPAIR=enabled in that environment: it is
# off unless someone turns it on for the case in hand. `request` records the
# documented request and tells the household, in the app and at the addresses on
# their accounts, before anything is done; `carry-out` does it only after the
# wait (seven days at least, fourteen by default), and tells them again. Every
# step, and every refused attempt, is in the audit log with your name.
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=lib/owner-psql.sh
. scripts/lib/owner-psql.sh

if [ "${ALMIRA_OPS_DORMANCY_REPAIR:-}" != "enabled" ]; then
  echo "${RED}Refusing: ALMIRA_OPS_DORMANCY_REPAIR is not 'enabled' for this environment.${OFF}" >&2
  echo "Turn it on only for a documented request, and off again afterwards (docs/05 §12.7)." >&2
  exit 3
fi

usage() { sed -n '2,19p' "$0"; exit 2; }
[ "${#ARGS[@]}" -ge 1 ] || usage

# Passed as psql variables and quoted by psql (:'name'), never spliced into SQL.
case "${ARGS[0]}" in
  list)
    psql "$OWNER_URL" -v ON_ERROR_STOP=1 -q <<'SQL'
select * from ops.dormant_households_nobody_may_take_on();
SQL
    ;;
  request)
    [ "${#ARGS[@]}" -ge 7 ] || usage
    psql "$OWNER_URL" -v ON_ERROR_STOP=1 -q -x \
      -v household="${ARGS[1]}" -v member="${ARGS[2]}" -v operator="$OPERATOR" -v reason="${ARGS[3]}" \
      -v requester="${ARGS[4]}" -v relationship="${ARGS[5]}" -v evidence="${ARGS[6]}" \
      -v wait="${ARGS[7]:-14 days}" <<'SQL'
select ops.request_dormancy_repair(:'household'::uuid, :'member'::uuid, :'operator', :'reason',
                                   :'requester', :'relationship', :'evidence', :'wait'::interval) as request_id;
SQL
    ;;
  carry-out)
    [ "${#ARGS[@]}" -eq 2 ] || usage
    psql "$OWNER_URL" -v ON_ERROR_STOP=1 -q -x -v request="${ARGS[1]}" -v operator="$OPERATOR" <<'SQL'
select ops.carry_out_dormancy_repair(:'request'::uuid, :'operator') as outcome;
SQL
    ;;
  withdraw)
    [ "${#ARGS[@]}" -eq 3 ] || usage
    psql "$OWNER_URL" -v ON_ERROR_STOP=1 -q -v request="${ARGS[1]}" -v operator="$OPERATOR" -v reason="${ARGS[2]}" <<'SQL'
select ops.withdraw_dormancy_repair(:'request'::uuid, :'operator', :'reason');
SQL
    ;;
  *) usage;;
esac
