-- =============================================================================
-- Carrying out a repair for a request id nobody has says so, rather than failing.
--
-- V147 and V148 read the dormancy row inside `if found`, so a request id that
-- matches nothing left `d` unassigned. The outcome below is one CASE expression
-- and plpgsql binds every record reference in it before the first branch can
-- answer, so `ops.carry_out_dormancy_repair` raised
--
--     ERROR:  record "d" is not assigned yet
--
-- instead of returning the documented `no_request`. It hid because plpgsql
-- caches the expression plan per session: the same call answers correctly once
-- another call in that session has assigned `d`, which is why the suite saw it
-- only now and then. Reproduced on a clean database, fixed by reading the row
-- unconditionally.
--
-- The function is otherwise copied from V148 exactly, guards and all: a function
-- replaced whole loses every line the new text forgets.
-- =============================================================================

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
  -- Not inside an `if found`: a record that is never assigned has no structure,
  -- and the outcome below reads d.ended_at whatever the first branch says, so an
  -- id nobody has raised "record d is not assigned yet" instead of answering
  -- no_request. Selecting on a null dormancy_id assigns d a row of nulls, which
  -- is what the outcome expects.
  select * into d from household_dormancies where id = q.dormancy_id for update;
  select m.user_id into v_user from members m where m.id = q.member_id and m.deleted_at is null;

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
