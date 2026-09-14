#!/usr/bin/env bash
# =============================================================================
# Sets the plan a household is on (docs/27 §3). There is no payment gateway, so
# this is the only way a plan changes.
#
#   ./scripts/household-plan.sh --env-file .env.production --operator "ops-1" \
#       <household-id> <plan> <paid-through YYYY-MM-DD | none> [grace-days] [note]
#
# `none` means nothing ends. grace-days defaults to almira.plans.grace-days.
# After paid-through plus the grace period the household is read-only — never
# locked: viewing, the handbook, Download everything and closing an account
# still work. Renewing is running this again with a later date; it takes effect
# on the next request. Audited as plan.set, readable by the household's admins.
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=lib/owner-psql.sh
. scripts/lib/owner-psql.sh

[ "${#ARGS[@]}" -ge 3 ] && [ "${#ARGS[@]}" -le 5 ] || { sed -n '2,15p' "$0"; exit 2; }

PAID="${ARGS[2]}"; [ "$PAID" = "none" ] && PAID=""
GRACE="${ARGS[3]:-}"
NOTE="${ARGS[4]:-}"

psql "$OWNER_URL" -v ON_ERROR_STOP=1 -q \
  -v hid="${ARGS[0]}" -v plan="${ARGS[1]}" -v paid="$PAID" -v grace="$GRACE" \
  -v operator="$OPERATOR" -v note="$NOTE" <<'SQL'
select ops.set_household_plan(:'hid'::uuid, :'plan', nullif(:'paid', '')::date,
                              nullif(:'grace', '')::int, :'operator', nullif(:'note', ''));
select plan_code, paid_through, grace_days, set_by, updated_at
  from household_plans where household_id = :'hid'::uuid;
SQL
