-- =============================================================================
-- A repair notice given another way starts the clock, and needs two operators.
--
-- Owner's decision (2026-09-16), on a household whose members have no reachable
-- address, where the before-notice can never be sent and the repair could
-- therefore never be carried out:
--
--   "No, that's the wrong failure. It's 'dormant forever by drift' wearing a
--    different hat — a household frozen permanently because an email bounced.
--    Let an operator record that notice was given another way (post, phone, in
--    person), with what was done and when, and start the clock from that. Higher
--    bar than the normal path: two operators, no single-operator mode."
--
-- So the clock has a second way to start. `ops.record_dormancy_repair_notice_given`
-- writes down the method, what was done, when, and who recorded it, and sets
-- `notified_before_at` to the moment the notice was actually given — not to the
-- moment it was recorded, which may be days later. The wait runs from there,
-- exactly as it does for a sent message (V147).
--
-- The higher bar is enforced in two places, not one: `carry_out_dormancy_repair`
-- refuses a single operator on such a request whatever reason they give, and a
-- check constraint refuses to store a single-operator reason on it at all. The
-- in-app notices went out when the request was filed either way; this is only
-- about the copy that could not be sent outside the app.
-- =============================================================================

alter table dormancy_repair_requests
  -- When the notice was actually given, by an operator's own account of it.
  add column notice_given_another_way_at timestamptz,
  add column notice_given_method text
    check (notice_given_method is null or notice_given_method in ('post', 'phone', 'in_person')),
  -- What was done, in enough words to be checked later: an address posted to, a
  -- number called and who answered, where and to whom it was handed.
  add column notice_given_detail text
    check (notice_given_detail is null or length(btrim(notice_given_detail)) between 20 and 2000),
  add column notice_given_recorded_by text
    check (notice_given_recorded_by is null or length(btrim(notice_given_recorded_by)) between 1 and 80),
  add column notice_given_recorded_at timestamptz;

alter table dormancy_repair_requests add constraint repair_notice_another_way_is_whole
  check (num_nulls(notice_given_another_way_at, notice_given_method, notice_given_detail,
                   notice_given_recorded_by, notice_given_recorded_at) in (0, 5));

-- The higher bar, in the table itself: nobody acts alone on one of these.
alter table dormancy_repair_requests add constraint repair_given_another_way_needs_two
  check (notice_given_another_way_at is null or single_operator_reason is null);

comment on column dormancy_repair_requests.notice_given_another_way_at is
  'When a notice that could not be sent was given by post, phone or in person. The wait starts here, and two operators are then required (V148).';

-- -----------------------------------------------------------------------------
-- Recording it.
-- -----------------------------------------------------------------------------
create or replace function ops.record_dormancy_repair_notice_given(
    p_request_id uuid, p_operator text, p_method text, p_what_was_done text,
    p_when timestamptz default now())
  returns timestamptz language plpgsql
  set search_path = public, app, pg_temp as $$
declare
  q record;
  v_after timestamptz;
begin
  if p_operator is null or length(trim(p_operator)) = 0 then
    raise exception 'say who is recording this (p_operator) — it goes in the audit log';
  end if;
  if p_method is null or p_method not in ('post', 'phone', 'in_person') then
    raise exception 'say how the notice was given: post, phone or in_person';
  end if;
  if p_what_was_done is null or length(trim(p_what_was_done)) < 20 then
    raise exception 'say what was done, in enough words to be checked later (at least 20 characters)';
  end if;
  if p_when is null or p_when > now() then
    raise exception 'say when the notice was given; it cannot be in the future';
  end if;

  select * into q from dormancy_repair_requests where id = p_request_id for update;
  if q.id is null then
    raise exception 'no repair request with that id';
  end if;
  if q.withdrawn_at is not null or q.carried_out_at is not null then
    raise exception 'that request is already closed';
  end if;
  -- If the sent notice did go out, that is the record, and its clock stands.
  perform ops.dormancy_repair_start_clock(p_request_id);
  select act_after into v_after from dormancy_repair_requests where id = p_request_id;
  if v_after is not null then
    raise notice 'a before-notice was already sent; the wait already runs to %', v_after;
    return v_after;
  end if;

  update dormancy_repair_requests
     set notice_given_another_way_at = p_when,
         notice_given_method = p_method,
         notice_given_detail = trim(p_what_was_done),
         notice_given_recorded_by = trim(p_operator),
         notice_given_recorded_at = now(),
         notified_before_at = p_when,
         act_after = p_when + coalesce(wait_period, interval '14 days')
   where id = p_request_id
  returning act_after into v_after;

  insert into activity_log (household_id, actor_user_id, action, entity_type, entity_id, diff)
  values (q.household_id, null, 'ops.dormancy_repair.notice_given', 'dormancy_repair_request', p_request_id,
          jsonb_build_object('operator', trim(p_operator), 'method', p_method,
                             'givenAt', p_when, 'actAfter', v_after,
                             'what', left(trim(p_what_was_done), 500)));
  return v_after;
end $$;

revoke all on function ops.record_dormancy_repair_notice_given(uuid, text, text, text, timestamptz) from public;

-- -----------------------------------------------------------------------------
-- Carrying it out: on such a request, a second operator, always.
-- -----------------------------------------------------------------------------
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

  -- The clock first: it starts only when a before-notice has actually gone, or
  -- when an operator has recorded one given another way (V148).
  perform ops.dormancy_repair_start_clock(p_request_id);

  select * into q from dormancy_repair_requests where id = p_request_id for update;
  if found then
    select * into d from household_dormancies where id = q.dormancy_id for update;
    select m.user_id into v_user from members m where m.id = q.member_id and m.deleted_at is null;
  end if;

  -- Owner's decision (V148): a notice given by post, phone or in person is a
  -- higher bar, not a lower one. Nobody carries one of those out alone.
  if q.notice_given_another_way_at is not null then
    v_alone := false;
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
                             'noticeGivenAnotherWay', q.notice_given_method,
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

revoke all on function ops.carry_out_dormancy_repair(uuid, text, text) from public;

-- What an operator lists: say when a notice was given another way, so a request
-- whose clock runs on an operator's account of it is visible as such.
drop function ops.dormant_households_nobody_may_take_on();
create or replace function ops.dormant_households_nobody_may_take_on()
  returns table (household_id uuid, dormant_since timestamptz, reason text, people int,
                 routed_to_repair_at timestamptz, open_request uuid, request_clock_started boolean,
                 notice_given_another_way text)
  language sql stable
  set search_path = public, app, pg_temp as $$
  select d.household_id, d.started_at, d.reason,
         (select count(*)::int from household_memberships hm
           where hm.household_id = d.household_id and hm.status = 'active'),
         d.routed_to_repair_at,
         q.id,
         q.act_after is not null or (q.id is not null and app.dormancy_repair_notice_sent_at(q.id) is not null),
         q.notice_given_method
    from household_dormancies d
    left join dormancy_repair_requests q
      on q.dormancy_id = d.id and q.carried_out_at is null and q.withdrawn_at is null
   where d.ended_at is null and not app.dormancy_has_someone_to_take_it_on(d.id)
   order by d.started_at
$$;

revoke all on function ops.dormant_households_nobody_may_take_on() from public;
