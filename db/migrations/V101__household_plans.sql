-- =============================================================================
-- V101 · A household's plan, and what happens when it ends.
-- Refs: docs/27-plans-and-support.md §1–§3, docs/05 §3
--
-- There is no price and no payment gateway yet (docs/27 §1). What there is, is
-- the shape both will plug into, and one promise that has to hold before either
-- exists: when a plan ends, the household becomes read-only — it is never
-- locked. Viewing, the family handbook, "Download everything" and closing an
-- account always work. Your family's record is never held hostage.
--
-- 1. household_plans — one row per household, at most. No row means the
--    configured default plan with nothing ending (almira.plans), which is every
--    household today. The plan *definitions* — names, what a price would be —
--    are configuration, not rows: they change with a decision, not with data.
--
--    paid_through is a date, not a status. Whether a household is active, in
--    its grace period or read-only is worked out from it on every request, so
--    nothing has to run at midnight for a plan to end, and nothing that fails to
--    run can end one early.
--
-- 2. Nobody in the app can write it. There is no operator role in the product
--    and no gateway to call back, so the runtime role reads it (members only)
--    and cannot insert, change or delete a row: RLS has no write policy, and the
--    grant is revoked as well. It is set by an operator, as the schema owner,
--    through ops.set_household_plan, which also writes the audit entry.
--
-- 3. The ops schema. Functions for operators, run as the owner from psql
--    (scripts/household-plan.sh). almira_app has no USAGE on it, and R__grants
--    never grants any, so the running application cannot reach them even if it
--    is compromised.
-- =============================================================================

create table household_plans (
  household_id  uuid primary key references households(id) on delete cascade,
  -- A key of almira.plans.definitions. Not a foreign key: definitions are config.
  plan_code     text not null check (plan_code ~ '^[a-z][a-z0-9_]{1,39}$'),
  -- The last day that is paid for. Null: nothing ends.
  paid_through  date,
  -- Days of ordinary use after paid_through. Null: almira.plans.grace-days.
  grace_days    int check (grace_days is null or grace_days between 0 and 365),
  -- Who set it and why, for the next operator. Never shown to the household.
  set_by        text not null check (length(set_by) between 1 and 100),
  note          text check (note is null or length(note) <= 500),
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);
create trigger household_plans_touch before insert or update on household_plans
  for each row execute function app.touch_row();

comment on table household_plans is
  'A household''s plan. Set by an operator (ops.set_household_plan); read by members. '
  'A lapsed plan makes the household read-only, never locked (V101).';

alter table household_plans enable row level security;

-- Every member may know the plan their family is on: it decides whether they
-- can change anything. Nobody else may know it exists.
create policy household_plans_read on household_plans for select
  using (app.is_household_member(household_id));

-- No insert, update or delete policy: denied to the runtime role by construction.

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'almira_app') then
    execute 'revoke insert, update, delete, truncate on household_plans from almira_app';
  end if;
end $$;

-- -----------------------------------------------------------------------------
-- Operator functions.
-- -----------------------------------------------------------------------------
create schema if not exists ops;
revoke all on schema ops from public;

comment on schema ops is
  'Operator functions, run as the schema owner from psql. Never granted to almira_app.';

create or replace function ops.set_household_plan(
    p_household_id uuid, p_plan_code text, p_paid_through date, p_grace_days int,
    p_operator text, p_note text default null)
  returns void language plpgsql
  set search_path = public, app, pg_temp as $$
begin
  if not exists (select 1 from households where id = p_household_id) then
    raise exception 'no household %', p_household_id;
  end if;
  if p_operator is null or length(trim(p_operator)) = 0 then
    raise exception 'say who is setting this plan (p_operator)';
  end if;

  insert into household_plans (household_id, plan_code, paid_through, grace_days, set_by, note)
  values (p_household_id, p_plan_code, p_paid_through, p_grace_days, trim(p_operator), p_note)
  on conflict (household_id) do update set
    plan_code = excluded.plan_code, paid_through = excluded.paid_through,
    grace_days = excluded.grace_days, set_by = excluded.set_by, note = excluded.note;

  -- The household's admins can read this entry (audit_read): a change to what
  -- they may do is not made behind their backs. The note stays out of it.
  insert into activity_log (household_id, actor_user_id, action, entity_type, entity_id, diff)
  values (p_household_id, null, 'plan.set', 'household', p_household_id,
          jsonb_build_object('plan', p_plan_code, 'paidThrough', p_paid_through,
                             'graceDays', p_grace_days, 'operator', trim(p_operator)));
end $$;

revoke all on function ops.set_household_plan(uuid, text, date, int, text, text) from public;
