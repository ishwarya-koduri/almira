-- =============================================================================
-- V147 · A dormancy nobody takes on goes to an operator after 90 days; a member
--        may change their own login while dormant; an operator repair records
--        the evidence seen, is designed for two operators, and its wait starts
--        when the notice is actually sent.
-- Refs: docs/05 §12.7, V120, V135, V137, lifecycle/Dormancy.kt,
--       scripts/dormancy-repair.sh
--
-- The owner's answers (2026-09-15):
--
--   (3) "After 90 days with nobody accepting, route to operator repair rather
--       than leaving it claimable forever. 'Dormant forever' should be a
--       decision, never something reached by drift." Once the offer has been
--       open to everyone for dormancy_settings.repair_after (90 days) with
--       nobody taking it on, the sweep stamps routed_to_repair_at: from then it
--       can no longer be taken on in the app (the successor included), the
--       household is told, the operator is alerted, and only a documented
--       repair can end it — or the owner coming back, as before.
--
--       "Freezing edits to OTHER people's date of birth or login is right.
--       Freezing a member's edit of their OWN login is not." V135's trigger
--       refused any change of members.user_id while dormant; a change to your
--       own row's login is now allowed. Another person's login, anyone's date of
--       birth and removal stay frozen.
--
--   (5) Evidence: "a death certificate or equivalent where the trigger is a
--       death; otherwise a written request from someone identifiable as a
--       member or legal representative. Record that evidence was seen and by
--       whom — do not store the document." So a request names the kind of
--       evidence, which must match what made the household dormant, and who saw
--       it and when; evidence_reference stays a pointer to the ticket that
--       records the sighting, never the document.
--
--       Approvers: "design for two. Allow a single-operator mode that logs the
--       reason." A second operator, not the one who asked, approves; or the
--       operator carrying it out gives a reason for acting alone, which is stored
--       and audited.
--
--       The clock: "start the clock when the notification actually sends, not
--       when the request is filed." notified_before_at is now the moment the
--       first before-notice outside the app was sent by the worker (status
--       'sent'), and act_after is that plus the wait. Until one is sent, nothing
--       can be carried out.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- (3) Routing to an operator.
-- -----------------------------------------------------------------------------
alter table dormancy_settings
  add column repair_after interval not null default interval '90 days'
    check (repair_after >= interval '30 days' and repair_after <= interval '365 days');

alter table household_dormancies
  add column routed_to_repair_at timestamptz;

alter table household_dormancies add constraint dormancy_routed_after_it_was_open
  check (routed_to_repair_at is null or opened_to_others_at is not null);

comment on column household_dormancies.routed_to_repair_at is
  'When nobody had taken the household on for dormancy_settings.repair_after after it was open to everyone: from then only an operator repair (or the owner returning) ends it (V147).';

-- The successor is not asked first once it is an operator's.
create or replace function app.dormancy_asks_first(p_dormancy_id uuid)
  returns uuid language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select m.user_id
    from household_dormancies d
    join members m on m.id = d.successor_member_id and m.deleted_at is null
   where d.id = p_dormancy_id and d.ended_at is null
     and d.routed_to_repair_at is null
     and d.successor_declined_at is null
     and d.successor_until is not null and now() < d.successor_until
     and m.user_id is distinct from d.owner_user_id
     and app.may_take_on_household(d.household_id, m.user_id)
$$;

-- Nobody may take it on the ordinary way once it is an operator's, so a
-- repair request is not refused for "someone may take it on themselves".
create or replace function app.dormancy_has_someone_to_take_it_on(p_dormancy_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (
    select 1 from household_dormancies d
      join household_memberships hm on hm.household_id = d.household_id and hm.status = 'active'
     where d.id = p_dormancy_id and d.ended_at is null
       and d.routed_to_repair_at is null
       and hm.user_id is distinct from d.owner_user_id
       and app.may_take_on_household(d.household_id, hm.user_id))
$$;

-- V135's accept, refused before anything is written once it is an operator's.
create or replace function app.accept_household_ownership(p_household_id uuid)
  returns void language plpgsql security definer
  set search_path = public, app, pg_temp as $$
declare
  v_user uuid := app.current_user_id();
  v_first uuid;
  d record;
begin
  if v_user is null or app.guest_share_id() is not null then
    raise exception 'authentication required' using errcode = 'insufficient_privilege';
  end if;
  if not app.is_household_member(p_household_id) then
    raise exception 'dormancy_not_found' using errcode = 'no_data_found';
  end if;
  select * into d from household_dormancies hd
   where hd.household_id = p_household_id and hd.ended_at is null
   for update;
  if not found then
    raise exception 'household_not_dormant' using errcode = 'no_data_found';
  end if;
  if d.routed_to_repair_at is not null then
    raise exception 'dormancy_routed_to_repair' using errcode = 'insufficient_privilege';
  end if;
  if d.owner_user_id = v_user
     or app.is_memorialised_in(p_household_id)
     or not exists (select 1 from household_memberships hm
                     where hm.household_id = p_household_id and hm.user_id = v_user
                       and hm.status = 'active' and hm.role in ('admin', 'editor', 'viewer'))
     or not exists (select 1 from members m
                     where m.household_id = p_household_id and m.user_id = v_user
                       and m.deleted_at is null and not app.is_minor(m.date_of_birth)) then
    raise exception 'ownership_not_eligible' using errcode = 'insufficient_privilege';
  end if;
  if not app.may_take_on_household(p_household_id, v_user) then
    raise exception 'ownership_not_while_leaving' using errcode = 'insufficient_privilege';
  end if;
  if now() < d.accept_from then
    raise exception 'dormancy_not_yet' using errcode = 'insufficient_privilege';
  end if;
  v_first := app.dormancy_asks_first(d.id);
  if v_first is not null and v_first <> v_user then
    raise exception 'dormancy_successor_first' using errcode = 'insufficient_privilege';
  end if;

  insert into activity_log (household_id, actor_user_id, action, entity_type, entity_id, diff)
    values (p_household_id, v_user, 'household.ownership.accept', 'household', p_household_id,
            jsonb_build_object('dormancyId', d.id, 'reason', d.reason,
                               'asSuccessor', v_first is not null));
  update household_memberships set role = 'owner'
   where household_id = p_household_id and user_id = v_user and status = 'active';
end $$;

-- The same for declining: nobody is asked once it is an operator's.
create or replace function app.decline_household_ownership(p_household_id uuid)
  returns uuid language plpgsql security definer
  set search_path = public, app, pg_temp as $$
declare
  v_user uuid := app.current_user_id();
  d record;
begin
  if v_user is null or app.guest_share_id() is not null then
    raise exception 'authentication required' using errcode = 'insufficient_privilege';
  end if;
  if not app.is_household_member(p_household_id) then
    raise exception 'dormancy_not_found' using errcode = 'no_data_found';
  end if;
  select * into d from household_dormancies hd
   where hd.household_id = p_household_id and hd.ended_at is null
   for update;
  if not found then
    raise exception 'household_not_dormant' using errcode = 'no_data_found';
  end if;
  if app.dormancy_asks_first(d.id) is distinct from v_user then
    raise exception 'dormancy_not_asked' using errcode = 'insufficient_privilege';
  end if;
  update household_dormancies
     set successor_declined_at = now(), opened_to_others_at = coalesce(opened_to_others_at, now())
   where id = d.id;
  insert into activity_log (household_id, actor_user_id, action, entity_type, entity_id, diff)
    values (p_household_id, v_user, 'household.ownership.decline', 'household', p_household_id,
            jsonb_build_object('dormancyId', d.id));
  return d.id;
end $$;

-- Whether this household is an operator's now, for the member's view. Only for
-- a household the caller belongs to.
create or replace function app.dormancy_routed_to_repair(p_household_id uuid)
  returns timestamptz language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select d.routed_to_repair_at
    from household_dormancies d
   where d.household_id = p_household_id and d.ended_at is null
     and app.is_household_member(p_household_id)
$$;

-- The sweep: stamps every dormancy that has been open to everyone for
-- repair_after with nobody taking it on, and returns them to be told and alerted.
-- Run on the owner connection; nobody else may.
create or replace function app.route_stale_dormancies_to_repair()
  returns table (dormancy_id uuid, household_id uuid, dormant_since timestamptz, open_since timestamptz)
  language sql volatile
  set search_path = public, app, pg_temp as $$
  update household_dormancies d
     set routed_to_repair_at = now()
   where d.ended_at is null and d.routed_to_repair_at is null
     and d.opened_to_others_at is not null
     and d.opened_to_others_at <= now() - (select s.repair_after from dormancy_settings s where s.id)
  returning d.id, d.household_id, d.started_at, d.opened_to_others_at
$$;
revoke all on function app.route_stale_dormancies_to_repair() from public;

-- -----------------------------------------------------------------------------
-- (3) Your own login is not frozen.
-- -----------------------------------------------------------------------------
create or replace function app.members_frozen_while_dormant() returns trigger
  language plpgsql security definer
  set search_path = public, app, pg_temp as $$
begin
  if app.current_user_id() is null then
    return new;
  end if;
  if (-- Another person's login; your own row's login is yours to change (V147).
      (new.user_id is distinct from old.user_id and old.user_id is distinct from app.current_user_id())
      or new.date_of_birth is distinct from old.date_of_birth
      or new.deleted_at is distinct from old.deleted_at)
     and exists (select 1 from household_dormancies d
                  where d.household_id = old.household_id and d.ended_at is null) then
    raise exception 'household_dormant' using errcode = 'insufficient_privilege';
  end if;
  return new;
end $$;

-- -----------------------------------------------------------------------------
-- The two new notices are essential, and a memorial does not stop them.
-- -----------------------------------------------------------------------------
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
    'emergency.named',
    'lifecycle.closure.requested',
    'lifecycle.closure.cancelled',
    'lifecycle.memorial.marked',
    'lifecycle.successor.claimed',
    'lifecycle.departure.asked',
    'lifecycle.departure.completed.you',
    'lifecycle.memorial.reversed',
    'lifecycle.successor.named',
    'lifecycle.household.dormant',
    'lifecycle.household.dormant.you',
    'lifecycle.household.running_again',
    'lifecycle.household.ownership_accepted',
    'lifecycle.household.asked_first',
    'lifecycle.household.repair_requested',
    'lifecycle.household.repair_done',
    'lifecycle.coming_of_age.you',
    -- Nobody took your household on in the app, so only an operator can now (V147):
    -- the right to take it on ends for everyone told.
    'lifecycle.household.routed_to_repair'
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
    'lifecycle.household.repair_requested',
    'lifecycle.household.repair_done',
    -- The household is handed to operators (V147): a false memorial must hear of it.
    'lifecycle.household.routed_to_repair',
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

-- -----------------------------------------------------------------------------
-- (5) The request's evidence, approvers and clock.
-- -----------------------------------------------------------------------------
alter table dormancy_repair_requests
  -- What was seen. Never the document: Almira is not a custodian of death certificates.
  add column evidence_kind text
    check (evidence_kind in ('death_certificate_or_equivalent',
                             'written_request_from_member',
                             'written_request_from_legal_representative')),
  add column evidence_seen_by text check (evidence_seen_by is null or length(btrim(evidence_seen_by)) between 1 and 80),
  add column evidence_seen_at timestamptz,
  -- The wait, counted from when the before-notice was actually sent.
  add column wait_period interval check (wait_period is null or wait_period >= interval '7 days'),
  add column before_notice_queued_at timestamptz,
  -- A second operator, not the one who asked; or one operator's stated reason for acting alone.
  add column approved_by_operator text check (approved_by_operator is null or length(btrim(approved_by_operator)) between 1 and 80),
  add column approved_at timestamptz,
  add column single_operator_reason text
    check (single_operator_reason is null or length(btrim(single_operator_reason)) between 10 and 1000);

-- New requests carry all of it; a request from before V147 is left as it was.
alter table dormancy_repair_requests add constraint repair_records_the_evidence_seen
  check (evidence_kind is not null and evidence_seen_by is not null and evidence_seen_at is not null
         and wait_period is not null and before_notice_queued_at is not null) not valid;
alter table dormancy_repair_requests add constraint repair_approved_by_someone_else
  check (approved_by_operator is null or approved_by_operator <> requested_by_operator);
alter table dormancy_repair_requests add constraint repair_approval_has_a_time
  check ((approved_by_operator is null) = (approved_at is null));

-- The wait no longer starts at filing: act_after is unknown until a notice is sent.
alter table dormancy_repair_requests alter column act_after drop not null;
alter table dormancy_repair_requests drop constraint repair_waits_a_week;
alter table dormancy_repair_requests add constraint repair_waits_after_it_is_told
  check (act_after is null or (notified_before_at is not null and act_after >= notified_before_at + interval '7 days'));

comment on column dormancy_repair_requests.notified_before_at is
  'When the first before-notice outside the app was actually sent (the worker recorded it sent). The wait starts here (V147).';
comment on column dormancy_repair_requests.evidence_reference is
  'Where the record of having seen the evidence is kept (a ticket). Never the evidence itself (V147).';

-- When the first before-notice outside the app was sent, or null.
create or replace function app.dormancy_repair_notice_sent_at(p_request_id uuid)
  returns timestamptz language sql stable
  set search_path = public, app, pg_temp as $$
  select min(o.finished_at)
    from outbound_messages o
   where o.template = 'lifecycle.household.repair_requested'
     and o.idempotency_key like 'lifecycle.household.repair_requested:' || p_request_id::text || ':%'
     and o.channel <> 'in_app' and o.status = 'sent'
$$;
revoke all on function app.dormancy_repair_notice_sent_at(uuid) from public;

-- Starts the clock when a before-notice has gone; idempotent. Returns act_after, or null.
create or replace function ops.dormancy_repair_start_clock(p_request_id uuid)
  returns timestamptz language plpgsql
  set search_path = public, app, pg_temp as $$
declare
  v_sent timestamptz;
  v_after timestamptz;
begin
  select act_after into v_after from dormancy_repair_requests where id = p_request_id;
  if v_after is not null then
    return v_after;
  end if;
  v_sent := app.dormancy_repair_notice_sent_at(p_request_id);
  if v_sent is null then
    return null;
  end if;
  update dormancy_repair_requests
     set notified_before_at = v_sent,
         act_after = v_sent + coalesce(wait_period, interval '14 days')
   where id = p_request_id and act_after is null
  returning act_after into v_after;
  return v_after;
end $$;

drop function ops.request_dormancy_repair(uuid, uuid, text, text, text, text, text, interval);

create or replace function ops.request_dormancy_repair(
    p_household_id uuid, p_member_id uuid, p_operator text, p_reason text,
    p_requester_name text, p_requester_relationship text,
    p_evidence_kind text, p_evidence_seen_by text, p_evidence_reference text,
    p_wait interval default interval '14 days')
  returns uuid language plpgsql
  set search_path = public, app, pg_temp as $$
declare
  d record;
  m record;
  v_id uuid;
  v_household text;
  v_told int;
  v_days int;
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
  if p_evidence_kind is null or p_evidence_kind not in ('death_certificate_or_equivalent',
       'written_request_from_member', 'written_request_from_legal_representative') then
    raise exception 'name the evidence seen: death_certificate_or_equivalent, written_request_from_member '
                    'or written_request_from_legal_representative';
  end if;
  if p_evidence_seen_by is null or length(trim(p_evidence_seen_by)) = 0 then
    raise exception 'record who saw the evidence (p_evidence_seen_by). Do not store the document itself';
  end if;
  if p_evidence_reference is null or length(trim(p_evidence_reference)) < 3 then
    raise exception 'record where the note of having seen it is kept (p_evidence_reference), never the document';
  end if;
  if p_wait is null or p_wait < interval '7 days' then
    raise exception 'the household is given at least seven days before anything is done';
  end if;

  select * into d from household_dormancies
   where household_id = p_household_id and ended_at is null for update;
  if not found then
    raise exception 'household % is not dormant', p_household_id;
  end if;
  -- The evidence must fit what made it dormant: a death, or anything else.
  if d.reason = 'owner_passed_away' and p_evidence_kind <> 'death_certificate_or_equivalent' then
    raise exception 'this household is dormant because its owner passed away: the evidence is a death certificate or equivalent';
  end if;
  if d.reason <> 'owner_passed_away' and p_evidence_kind = 'death_certificate_or_equivalent' then
    raise exception 'this household is dormant because its owner is closing their account or leaving, not a death: '
                    'the evidence is a written request from a member or a legal representative';
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
                                        evidence_kind, evidence_seen_by, evidence_seen_at,
                                        wait_period, before_notice_queued_at, act_after)
  values (p_household_id, d.id, p_member_id, trim(p_reason), trim(p_requester_name),
          trim(p_requester_relationship), trim(p_evidence_reference), trim(p_operator),
          p_evidence_kind, trim(p_evidence_seen_by), now(),
          p_wait, now(), null)
  returning id into v_id;

  v_days := greatest(7, extract(day from p_wait)::int);
  select name into v_household from households where id = p_household_id;
  v_told := app.tell_household_of_repair(
    v_id, 'lifecycle.household.repair_requested',
    'Almira has been asked to make someone the owner of ' || v_household,
    'Nobody in ' || v_household || ' can take it on, so ' || trim(p_requester_name) || ' (' ||
      trim(p_requester_relationship) || ') has asked Almira to make ' || m.display_name ||
      ' its owner. Nothing will be done for at least ' || v_days || ' days after this message reaches you. ' ||
      'If this is wrong, reply to this message or contact Almira support before then. Private records stay private.');

  insert into activity_log (household_id, actor_user_id, action, entity_type, entity_id, diff)
  values (p_household_id, null, 'ops.dormancy_repair.request', 'household', p_household_id,
          jsonb_build_object('requestId', v_id, 'dormancyId', d.id, 'memberId', p_member_id,
                             'operator', trim(p_operator), 'reason', left(trim(p_reason), 200),
                             'evidenceKind', p_evidence_kind, 'evidenceSeenBy', trim(p_evidence_seen_by),
                             'wait', p_wait, 'told', v_told, 'clock', 'starts when a notice is sent'));
  return v_id;
end $$;

-- A second operator approves. Not the one who asked.
create or replace function ops.approve_dormancy_repair(p_request_id uuid, p_operator text)
  returns void language plpgsql
  set search_path = public, app, pg_temp as $$
declare
  q record;
begin
  if p_operator is null or length(trim(p_operator)) = 0 then
    raise exception 'say who is approving this (p_operator) — it goes in the audit log';
  end if;
  select * into q from dormancy_repair_requests where id = p_request_id for update;
  if not found or q.withdrawn_at is not null or q.carried_out_at is not null then
    raise exception 'no open repair request %', p_request_id;
  end if;
  if trim(p_operator) = q.requested_by_operator then
    raise exception 'the second operator is someone other than the one who asked (%). '
                    'Acting alone is carry-out with a reason instead', q.requested_by_operator;
  end if;
  update dormancy_repair_requests set approved_by_operator = trim(p_operator), approved_at = now()
   where id = p_request_id and approved_at is null;
  insert into activity_log (household_id, actor_user_id, action, entity_type, entity_id, diff)
  values (q.household_id, null, 'ops.dormancy_repair.approve', 'dormancy_repair_request', p_request_id,
          jsonb_build_object('operator', trim(p_operator)));
end $$;

drop function ops.carry_out_dormancy_repair(uuid, text);

create or replace function ops.carry_out_dormancy_repair(
    p_request_id uuid, p_operator text, p_single_operator_reason text default null)
  returns text language plpgsql
  set search_path = public, app, pg_temp as $$
declare
  q record;
  d record;
  v_user uuid;
  v_outcome text;
  v_household text;
  v_alone boolean := p_single_operator_reason is not null and length(trim(p_single_operator_reason)) >= 10;
begin
  if p_operator is null or length(trim(p_operator)) = 0 then
    raise exception 'say who is carrying this out (p_operator) — it goes in the audit log';
  end if;

  -- The clock first: it starts only when a before-notice has actually gone.
  perform ops.dormancy_repair_start_clock(p_request_id);

  select * into q from dormancy_repair_requests where id = p_request_id for update;
  if found then
    select * into d from household_dormancies where id = q.dormancy_id for update;
    select m.user_id into v_user from members m where m.id = q.member_id and m.deleted_at is null;
  end if;

  v_outcome := case
    when q.id is null then 'no_request'
    when q.withdrawn_at is not null then 'withdrawn'
    when q.carried_out_at is not null then 'already_done'
    when q.notified_before_at is null or q.act_after is null then 'not_told_before'
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
    when q.approved_at is null and not v_alone then 'needs_second_operator'
    else 'done'
  end;

  insert into activity_log (household_id, actor_user_id, action, entity_type, entity_id, diff)
  values (q.household_id, null, 'ops.dormancy_repair.carry_out', 'dormancy_repair_request', p_request_id,
          jsonb_build_object('operator', trim(p_operator), 'outcome', v_outcome,
                             'approvedBy', q.approved_by_operator,
                             'singleOperatorReason', case when v_outcome = 'done' and q.approved_at is null
                                                          then left(trim(p_single_operator_reason), 500) end));

  if v_outcome <> 'done' then
    raise notice 'not carried out: %', v_outcome;
    return v_outcome;
  end if;

  -- The trigger on household_memberships ends the dormancy as transferred (V120).
  update household_memberships set role = 'owner'
   where household_id = q.household_id and user_id = v_user and status = 'active';
  update dormancy_repair_requests
     set carried_out_at = now(), carried_out_by_operator = trim(p_operator),
         single_operator_reason = case when approved_at is null then trim(p_single_operator_reason) end
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

-- What an operator can list: dormant households nobody may take on, including
-- those handed over after 90 days. Counts and dates only.
drop function ops.dormant_households_nobody_may_take_on();
create or replace function ops.dormant_households_nobody_may_take_on()
  returns table (household_id uuid, dormant_since timestamptz, reason text, people int,
                 routed_to_repair_at timestamptz, open_request uuid, request_clock_started boolean)
  language sql stable
  set search_path = public, app, pg_temp as $$
  select d.household_id, d.started_at, d.reason,
         (select count(*)::int from household_memberships hm
           where hm.household_id = d.household_id and hm.status = 'active'),
         d.routed_to_repair_at,
         q.id,
         q.act_after is not null or (q.id is not null and app.dormancy_repair_notice_sent_at(q.id) is not null)
    from household_dormancies d
    left join dormancy_repair_requests q
      on q.dormancy_id = d.id and q.carried_out_at is null and q.withdrawn_at is null
   where d.ended_at is null and not app.dormancy_has_someone_to_take_it_on(d.id)
   order by d.started_at
$$;

revoke all on function ops.request_dormancy_repair(uuid, uuid, text, text, text, text, text, text, text, interval) from public;
revoke all on function ops.approve_dormancy_repair(uuid, text) from public;
revoke all on function ops.carry_out_dormancy_repair(uuid, text, text) from public;
revoke all on function ops.dormancy_repair_start_clock(uuid) from public;
revoke all on function ops.dormant_households_nobody_may_take_on() from public;
