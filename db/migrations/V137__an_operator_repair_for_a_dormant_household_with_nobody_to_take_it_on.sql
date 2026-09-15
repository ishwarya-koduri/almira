-- =============================================================================
-- V137 · An operator repair for a dormant household nobody may take on.
-- Refs: docs/05 §12.7, docs/28 §3 (the ops schema), V101, V102 (support codes),
--       scripts/dormancy-repair.sh, docs/known-issues.md ("A dormant household
--       with nobody who may take it on waits for good" — closed by this)
--
-- The owner's answer (D8b, 2026-09-15): a household with no eligible member
-- gets an operator path — logged, only on a documented request (a record with a
-- reason, who is asking and how they are related, and a reference to the
-- evidence), telling the household at its recorded addresses BEFORE anything is
-- done, with a waiting period, and AFTER. Never in the family app.
--
-- As with support codes, there is no operator role in the application and no
-- endpoint: the functions are in the ops schema, executable only by the schema
-- owner, run from psql by scripts/dormancy-repair.sh, which refuses unless
-- ALMIRA_OPS_DORMANCY_REPAIR=enabled is set for that environment.
--
--   1. ops.request_dormancy_repair writes the request, audits it and queues the
--      "before" notice to everyone in the household with a login (and to the
--      person whose going made it dormant, if they still have an account). It
--      refuses when anyone in the household may take it on the ordinary way.
--   2. Nothing happens for the waiting period: at least seven days, fourteen
--      unless the operator asks for longer (a check constraint).
--   3. ops.carry_out_dormancy_repair makes the named member owner — only with a
--      request, after its wait, after the before-notice, while the household is
--      still dormant with nobody else to take it on — and queues the "after"
--      notice. Every attempt, refused or not, is in the audit log with its
--      outcome; a refusal is a result, not an exception, so the audit line is
--      not rolled back with it.
--
-- Both notices are essential: they are about the household being taken, so
-- they are not under consent to messages and a memorial does not stop them.
-- =============================================================================

create table dormancy_repair_requests (
  id                      uuid primary key default gen_random_uuid(),
  household_id            uuid not null references households(id) on delete cascade,
  dormancy_id             uuid not null references household_dormancies(id) on delete cascade,
  -- The person the request asks to be made owner: a member with a login.
  member_id               uuid not null references members(id) on delete cascade,
  reason                  text not null check (length(btrim(reason)) between 10 and 2000),
  requester_name          text not null check (length(btrim(requester_name)) between 1 and 120),
  requester_relationship  text not null check (length(btrim(requester_relationship)) between 1 and 60),
  -- Where the evidence is kept (a ticket, a case file). Never the evidence.
  evidence_reference      text not null check (length(btrim(evidence_reference)) between 3 and 200),
  requested_by_operator   text not null check (length(btrim(requested_by_operator)) between 1 and 80),
  created_at              timestamptz not null default now(),
  act_after               timestamptz not null,
  notified_before_at      timestamptz,
  carried_out_at          timestamptz,
  carried_out_by_operator text,
  notified_after_at       timestamptz,
  withdrawn_at            timestamptz,
  withdrawn_reason        text,
  constraint repair_waits_a_week check (act_after >= created_at + interval '7 days'),
  constraint repair_is_told_before check (carried_out_at is null or notified_before_at is not null),
  constraint repair_carried_out_or_withdrawn check (carried_out_at is null or withdrawn_at is null)
);
create unique index dormancy_repair_requests_one_open
  on dormancy_repair_requests (dormancy_id) where carried_out_at is null and withdrawn_at is null;

comment on table dormancy_repair_requests is
  'A documented request for an operator to make a member owner of a dormant household nobody may take on '
  '(docs/05 §12.7). Written only by ops.* functions as the schema owner.';

alter table dormancy_repair_requests enable row level security;

-- The household sees that a request exists and what it asks: it was told, and
-- a change to who runs it is not made behind its back. Nobody writes it through
-- a policy; the runtime role holds no write privilege (below and R__grants).
create policy dormancy_repair_requests_read on dormancy_repair_requests for select
  using (app.is_household_member(household_id));

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'almira_app') then
    execute 'revoke insert, update, delete, truncate on dormancy_repair_requests from almira_app';
  end if;
end $$;

-- -----------------------------------------------------------------------------
-- Whether anyone in the household may take it on the ordinary way.
-- -----------------------------------------------------------------------------
create or replace function app.dormancy_has_someone_to_take_it_on(p_dormancy_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (
    select 1 from household_dormancies d
      join household_memberships hm on hm.household_id = d.household_id and hm.status = 'active'
     where d.id = p_dormancy_id and d.ended_at is null
       and hm.user_id is distinct from d.owner_user_id
       and app.may_take_on_household(d.household_id, hm.user_id))
$$;

-- The two notices are essential, and a memorial does not stop them: V125's and
-- V103's lists, each with one line more.
create or replace function app.message_is_essential(p_template text)
  returns boolean language sql immutable
  set search_path = public, app, pg_temp as $$
  select coalesce(p_template in (
    'otp_email',
    'auth.new_sign_in',
    'auth.phone_changed',
    'auth.authenticator_added',
    'auth.authenticator_removed',
    'auth.passkey_added',
    'auth.passkey_removed',
    'auth.recovery_codes_replaced',
    'auth.recovery_code_used',
    'emergency.check_in',
    'emergency.requested',
    'emergency.raised',
    'emergency.vetoed',
    'lifecycle.closure.requested',
    'lifecycle.closure.cancelled',
    'lifecycle.memorial.marked',
    'lifecycle.successor.claimed',
    'lifecycle.departure.asked',
    -- An operator is asked to make someone owner of your household, and did (V137).
    'lifecycle.household.repair_requested',
    'lifecycle.household.repair_done'
  ), false)
$$;

create or replace function app.never_stopped_by_memorial(p_template text)
  returns boolean language sql immutable
  set search_path = public, app, pg_temp as $$
  select p_template in (
    'lifecycle.memorial.marked',
    'emergency.requested',
    'emergency.check_in',
    'lifecycle.successor.claimed',
    'lifecycle.departure.asked',
    -- An operator is asked to hand their household on (V137): a false memorial
    -- must still hear of it.
    'lifecycle.household.repair_requested',
    'lifecycle.household.repair_done',
    'auth.new_sign_in',
    'auth.phone_changed',
    'auth.authenticator_added',
    'auth.authenticator_removed',
    'auth.passkey_added',
    'auth.passkey_removed',
    'auth.recovery_codes_replaced',
    'auth.recovery_code_used'
  )
$$;

-- Everyone in the household with a login, and the person whose going made it
-- dormant if they still have an account: in the app, and by email and SMS at
-- the addresses on their accounts (the worker skips a channel with no provider).
create or replace function app.tell_household_of_repair(
    p_request_id uuid, p_template text, p_title text, p_body text)
  returns int language plpgsql security definer
  set search_path = public, app, pg_temp as $$
declare
  r record;
  v_told int := 0;
  v_key text;
  v_channel text;
begin
  for r in
    select distinct u.id as user_id, q.household_id
      from dormancy_repair_requests q
      join household_dormancies d on d.id = q.dormancy_id
      join users u on u.id in (select hm.user_id from household_memberships hm
                                where hm.household_id = q.household_id and hm.status = 'active')
                   or u.id = d.owner_user_id
     where q.id = p_request_id
  loop
    v_key := p_template || ':' || p_request_id || ':' || r.user_id;
    perform app.record_in_app_message(r.household_id, r.user_id, p_template, p_title, v_key || ':in_app');
    foreach v_channel in array array['email', 'sms'] loop
      perform app.enqueue_outbound_message(r.household_id, r.user_id, v_channel, p_template,
                                           p_title, p_body, v_key || ':' || v_channel);
    end loop;
    v_told := v_told + 1;
  end loop;
  return v_told;
end $$;

-- -----------------------------------------------------------------------------
-- 1. The request.
-- -----------------------------------------------------------------------------
create or replace function ops.request_dormancy_repair(
    p_household_id uuid, p_member_id uuid, p_operator text, p_reason text,
    p_requester_name text, p_requester_relationship text, p_evidence_reference text,
    p_wait interval default interval '14 days')
  returns uuid language plpgsql
  set search_path = public, app, pg_temp as $$
declare
  d record;
  m record;
  v_id uuid;
  v_household text;
  v_told int;
begin
  if p_operator is null or length(trim(p_operator)) = 0 then
    raise exception 'say who is asking for this (p_operator) — it goes in the audit log';
  end if;
  if p_reason is null or length(trim(p_reason)) < 10 then
    raise exception 'say why, in a sentence (p_reason)';
  end if;
  if p_requester_name is null or length(trim(p_requester_name)) = 0
     or p_requester_relationship is null or length(trim(p_requester_relationship)) = 0 then
    raise exception 'record who asked and how they are related to the household';
  end if;
  if p_evidence_reference is null or length(trim(p_evidence_reference)) < 3 then
    raise exception 'record where the evidence is kept (p_evidence_reference)';
  end if;
  if p_wait is null or p_wait < interval '7 days' then
    raise exception 'the household is given at least seven days before anything is done';
  end if;

  select * into d from household_dormancies
   where household_id = p_household_id and ended_at is null for update;
  if not found then
    raise exception 'household % is not dormant', p_household_id;
  end if;
  if app.dormancy_has_someone_to_take_it_on(d.id) then
    raise exception 'someone in this household may take it on themselves; this is not for an operator';
  end if;
  select mb.*, hm.status as membership_status into m
    from members mb
    left join household_memberships hm on hm.household_id = mb.household_id and hm.user_id = mb.user_id
   where mb.id = p_member_id and mb.household_id = p_household_id and mb.deleted_at is null;
  if not found or m.user_id is null or m.membership_status is distinct from 'active'
     or m.user_id is not distinct from d.owner_user_id
     or app.is_minor(m.date_of_birth)
     or exists (select 1 from member_memorials mm
                 where mm.household_id = p_household_id and mm.user_id = m.user_id and mm.reversed_at is null) then
    raise exception 'that member cannot be made owner: they need their own login, an active membership, '
                    'to be an adult, and not to be marked as passed away';
  end if;

  insert into dormancy_repair_requests (household_id, dormancy_id, member_id, reason, requester_name,
                                        requester_relationship, evidence_reference, requested_by_operator,
                                        act_after)
  values (p_household_id, d.id, p_member_id, trim(p_reason), trim(p_requester_name),
          trim(p_requester_relationship), trim(p_evidence_reference), trim(p_operator), now() + p_wait)
  returning id into v_id;

  select name into v_household from households where id = p_household_id;
  v_told := app.tell_household_of_repair(
    v_id, 'lifecycle.household.repair_requested',
    'Almira has been asked to make someone the owner of ' || v_household,
    'Nobody in ' || v_household || ' can take it on, so ' || trim(p_requester_name) || ' (' ||
      trim(p_requester_relationship) || ') has asked Almira to make ' || m.display_name ||
      ' its owner. Nothing will be done before ' || to_char((now() + p_wait) at time zone 'Asia/Kolkata', 'FMDD FMMonth YYYY') ||
      '. If this is wrong, reply to this message or contact Almira support before then. Private records stay private.');
  update dormancy_repair_requests set notified_before_at = now() where id = v_id;

  insert into activity_log (household_id, actor_user_id, action, entity_type, entity_id, diff)
  values (p_household_id, null, 'ops.dormancy_repair.request', 'household', p_household_id,
          jsonb_build_object('requestId', v_id, 'dormancyId', d.id, 'memberId', p_member_id,
                             'operator', trim(p_operator), 'reason', left(trim(p_reason), 200),
                             'actAfter', now() + p_wait, 'told', v_told));
  return v_id;
end $$;

-- -----------------------------------------------------------------------------
-- 2. Withdrawing one.
-- -----------------------------------------------------------------------------
create or replace function ops.withdraw_dormancy_repair(p_request_id uuid, p_operator text, p_reason text)
  returns void language plpgsql
  set search_path = public, app, pg_temp as $$
declare
  v_household uuid;
begin
  if p_operator is null or length(trim(p_operator)) = 0 or p_reason is null or length(trim(p_reason)) < 5 then
    raise exception 'say who is withdrawing it and why';
  end if;
  update dormancy_repair_requests set withdrawn_at = now(), withdrawn_reason = trim(p_reason)
   where id = p_request_id and carried_out_at is null and withdrawn_at is null
  returning household_id into v_household;
  if v_household is null then
    raise exception 'no open repair request %', p_request_id;
  end if;
  insert into activity_log (household_id, actor_user_id, action, entity_type, entity_id, diff)
  values (v_household, null, 'ops.dormancy_repair.withdraw', 'household', v_household,
          jsonb_build_object('requestId', p_request_id, 'operator', trim(p_operator),
                             'reason', left(trim(p_reason), 200)));
end $$;

-- -----------------------------------------------------------------------------
-- 3. Carrying one out. Returns the outcome; 'done' is the only one that changed
-- anything. Every refusal is decided before the role change and is audited.
-- -----------------------------------------------------------------------------
create or replace function ops.carry_out_dormancy_repair(p_request_id uuid, p_operator text)
  returns text language plpgsql
  set search_path = public, app, pg_temp as $$
declare
  q record;
  d record;
  v_user uuid;
  v_outcome text;
  v_household text;
begin
  if p_operator is null or length(trim(p_operator)) = 0 then
    raise exception 'say who is carrying this out (p_operator) — it goes in the audit log';
  end if;

  select * into q from dormancy_repair_requests where id = p_request_id for update;
  if found then
    select * into d from household_dormancies where id = q.dormancy_id for update;
    select m.user_id into v_user from members m where m.id = q.member_id and m.deleted_at is null;
  end if;

  v_outcome := case
    when q.id is null then 'no_request'
    when q.withdrawn_at is not null then 'withdrawn'
    when q.carried_out_at is not null then 'already_done'
    when q.notified_before_at is null then 'not_told_before'
    when now() < q.act_after then 'waiting'
    when d.ended_at is not null then 'dormancy_ended'
    when app.dormancy_has_someone_to_take_it_on(d.id) then 'someone_may_take_it_on'
    when v_user is null
      or not exists (select 1 from household_memberships hm
                      where hm.household_id = q.household_id and hm.user_id = v_user and hm.status = 'active')
      or exists (select 1 from member_memorials mm
                  where mm.household_id = q.household_id and mm.user_id = v_user and mm.reversed_at is null)
      or exists (select 1 from members m where m.id = q.member_id and app.is_minor(m.date_of_birth))
      then 'member_not_eligible'
    else 'done'
  end;

  insert into activity_log (household_id, actor_user_id, action, entity_type, entity_id, diff)
  values (q.household_id, null, 'ops.dormancy_repair.carry_out', 'dormancy_repair_request', p_request_id,
          jsonb_build_object('operator', trim(p_operator), 'outcome', v_outcome));

  if v_outcome <> 'done' then
    raise notice 'not carried out: %', v_outcome;
    return v_outcome;
  end if;

  -- The trigger on household_memberships ends the dormancy as transferred (V120).
  update household_memberships set role = 'owner'
   where household_id = q.household_id and user_id = v_user and status = 'active';
  update dormancy_repair_requests
     set carried_out_at = now(), carried_out_by_operator = trim(p_operator)
   where id = q.id;

  select name into v_household from households where id = q.household_id;
  perform app.tell_household_of_repair(
    q.id, 'lifecycle.household.repair_done',
    (select display_name from members where id = q.member_id) || ' now runs ' || v_household,
    'Almira made ' || (select display_name from members where id = q.member_id) || ' the owner of ' ||
      v_household || ', as it said it would. Private records stay private.');
  update dormancy_repair_requests set notified_after_at = now() where id = q.id;
  return v_outcome;
end $$;

-- -----------------------------------------------------------------------------
-- What an operator can list: dormant households nobody may take on. Counts and
-- dates only — never a name or a record.
-- -----------------------------------------------------------------------------
create or replace function ops.dormant_households_nobody_may_take_on()
  returns table (household_id uuid, dormant_since timestamptz, reason text, people int, open_request uuid)
  language sql stable
  set search_path = public, app, pg_temp as $$
  select d.household_id, d.started_at, d.reason,
         (select count(*)::int from household_memberships hm
           where hm.household_id = d.household_id and hm.status = 'active'),
         (select q.id from dormancy_repair_requests q
           where q.dormancy_id = d.id and q.carried_out_at is null and q.withdrawn_at is null)
    from household_dormancies d
   where d.ended_at is null and not app.dormancy_has_someone_to_take_it_on(d.id)
   order by d.started_at
$$;

revoke all on function ops.request_dormancy_repair(uuid, uuid, text, text, text, text, text, interval) from public;
revoke all on function ops.withdraw_dormancy_repair(uuid, text, text) from public;
revoke all on function ops.carry_out_dormancy_repair(uuid, text) from public;
revoke all on function ops.dormant_households_nobody_may_take_on() from public;
