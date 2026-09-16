#!/usr/bin/env bash
# =============================================================================
# The operator repair for a dormant household nobody in it may take on
# (docs/05 §12.7, V137). Never in the family app.
#
#   ./scripts/dormancy-repair.sh --env-file .env.production --operator "support-1" list
#   ./scripts/dormancy-repair.sh … request <household-id> <member-id> "<reason>" \
#       "<requester name>" "<relationship>" <evidence kind> "<evidence seen by>" \
#       "<where the note of seeing it is kept>" [wait, e.g. "21 days"]
#   ./scripts/dormancy-repair.sh … notice-given <request-id> <post|phone|in_person> \
#       "<what was done>" ["<when, e.g. 2026-09-10 11:00+05:30>"]
#   ./scripts/dormancy-repair.sh … approve <request-id>          (a second operator)
#   ./scripts/dormancy-repair.sh … carry-out <request-id> [--alone "<why one operator acts alone>"]
#   ./scripts/dormancy-repair.sh … withdraw <request-id> "<reason>"
#
# Evidence kind (owner's decision, 2026-09-15, V147): death_certificate_or_equivalent
# when the household is dormant because its owner passed away; otherwise
# written_request_from_member or written_request_from_legal_representative. Record
# that it was seen and by whom — never store the document, and never put it in the
# reference: Almira is not a custodian of death certificates.
#
# Refuses unless ALMIRA_OPS_DORMANCY_REPAIR=enabled in that environment: it is
# off unless someone turns it on for the case in hand. `request` records the
# documented request and queues the notice to the household, in the app and at the
# addresses on their accounts; the wait (seven days at least, fourteen by default)
# starts only when that notice is actually sent. `carry-out` does it only after the
# wait, and only when a second operator — not the one who asked — has approved, or
# with --alone and a reason, which is stored and audited. It tells them again.
#
# When the household has no address a notice can reach, `notice-given` records
# that one was given by post, phone or in person — what was done, and when — and
# the wait runs from the moment it was given (owner's decision, 2026-09-16, V148).
# The bar is then higher, not lower: two operators, and --alone is refused.
# Every step, and every refused attempt, is in the audit log with your name.
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

usage() { sed -n '2,38p' "$0"; exit 2; }
[ "${#ARGS[@]}" -ge 1 ] || usage

# Passed as psql variables and quoted by psql (:'name'), never spliced into SQL.
case "${ARGS[0]}" in
  list)
    psql "$OWNER_URL" -v ON_ERROR_STOP=1 -q <<'SQL'
select * from ops.dormant_households_nobody_may_take_on();
SQL
    ;;
  request)
    [ "${#ARGS[@]}" -ge 9 ] || usage
    psql "$OWNER_URL" -v ON_ERROR_STOP=1 -q -x \
      -v household="${ARGS[1]}" -v member="${ARGS[2]}" -v operator="$OPERATOR" -v reason="${ARGS[3]}" \
      -v requester="${ARGS[4]}" -v relationship="${ARGS[5]}" -v kind="${ARGS[6]}" -v seen_by="${ARGS[7]}" \
      -v evidence="${ARGS[8]}" -v wait="${ARGS[9]:-14 days}" <<'SQL'
select ops.request_dormancy_repair(:'household'::uuid, :'member'::uuid, :'operator', :'reason',
                                   :'requester', :'relationship', :'kind', :'seen_by', :'evidence',
                                   :'wait'::interval) as request_id;
SQL
    echo "The wait starts when the notice to the household is actually sent; 'list' shows whether it has."
    ;;
  notice-given)
    [ "${#ARGS[@]}" -ge 4 ] || usage
    psql "$OWNER_URL" -v ON_ERROR_STOP=1 -q -x \
      -v request="${ARGS[1]}" -v operator="$OPERATOR" -v method="${ARGS[2]}" -v what="${ARGS[3]}" \
      -v given="${ARGS[4]:-}" <<'SQL'
select ops.record_dormancy_repair_notice_given(
         :'request'::uuid, :'operator', :'method', :'what',
         coalesce(nullif(:'given', '')::timestamptz, now())) as act_after;
SQL
    echo "Two operators are required for this request: --alone will be refused."
    ;;
  approve)
    [ "${#ARGS[@]}" -eq 2 ] || usage
    psql "$OWNER_URL" -v ON_ERROR_STOP=1 -q -v request="${ARGS[1]}" -v operator="$OPERATOR" <<'SQL'
select ops.approve_dormancy_repair(:'request'::uuid, :'operator');
SQL
    ;;
  carry-out)
    if [ "${#ARGS[@]}" -eq 2 ]; then
      ALONE=""
    elif [ "${#ARGS[@]}" -eq 4 ] && [ "${ARGS[2]}" = "--alone" ]; then
      ALONE="${ARGS[3]}"
    else
      usage
    fi
    psql "$OWNER_URL" -v ON_ERROR_STOP=1 -q -x -v request="${ARGS[1]}" -v operator="$OPERATOR" -v alone="$ALONE" <<'SQL'
select ops.carry_out_dormancy_repair(:'request'::uuid, :'operator', nullif(:'alone', '')) as outcome;
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
