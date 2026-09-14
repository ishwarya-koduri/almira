-- =============================================================================
-- V70 · Product measurement: a fixed list of events, counted per day, about
-- nobody (docs/what-we-measure.md).
--
-- The design is the table's shape. A row is (day, event, step, count) and
-- nothing else: no user, no household, no member, no amount, no name, no
-- record id, no device, no address. There is nothing to join a count back to,
-- so there is nothing to leak, subpoena or re-identify beyond the count itself.
--
-- Three rules live HERE rather than only in the service, so no code path can
-- skip them:
--
--   1. The allowlist. An event not in the check constraint cannot be stored.
--   2. The opt-out. A person who has opted out is never counted, looked up by
--      the acting user's id, which is read and never written.
--   3. Minors. An event about a record owned or held by someone under 18, about
--      a member who is under 18, or done by a login that is an under-18 member
--      anywhere, is not counted. The record and member
--      ids are passed in, used for that check, and discarded.
--
-- Plus: a guest link (V20) is never counted, and the runtime role cannot read
-- or write the counts directly at all. It can only call the function, which
-- runs as the owner. Reading the counts is an operator's job, as the owner.
-- =============================================================================

create table measurement_daily_counts (
  day    date     not null,
  event  text     not null check (event in (
           'sign_in_completed',
           'household_created',
           'invite_sent',
           'invite_accepted',
           'first_holding_added',
           'holding_added',
           'liability_added',
           'document_uploaded',
           'estate_contact_added',
           'still_true_confirmed',
           'handbook_printed',
           'capture_abandoned'
         )),
  -- Only capture_abandoned has steps (1 how to add, 2 pick a type, 3 the form).
  step   smallint not null default 0 check (step between 0 and 3),
  count  bigint   not null default 0 check (count >= 0),
  primary key (day, event, step),
  check (step = 0 or event = 'capture_abandoned')
);

comment on table measurement_daily_counts is
  'Aggregate product events per day. Deliberately no user, household, record or amount. See docs/what-we-measure.md.';

-- Row-level security with NO policy: the runtime role sees no rows and can
-- write none, even though R__grants grants table privileges on everything.
-- The owner (migrations, the counting function, an operator) is unaffected.
alter table measurement_daily_counts enable row level security;

-- One row per person who has said no. Their own row only, under RLS.
create table measurement_opt_outs (
  user_id      uuid        primary key references users(id) on delete cascade,
  opted_out_at timestamptz not null default now()
);

comment on table measurement_opt_outs is
  'People who have turned product measurement off (Settings). Read by app.count_product_event.';

alter table measurement_opt_outs enable row level security;

create policy measurement_opt_outs_own_select on measurement_opt_outs
  for select using (user_id = app.current_user_id());
create policy measurement_opt_outs_own_insert on measurement_opt_outs
  for insert with check (user_id = app.current_user_id());
create policy measurement_opt_outs_own_delete on measurement_opt_outs
  for delete using (user_id = app.current_user_id());

-- -----------------------------------------------------------------------------
-- The only way in.
--
-- Returns whether the event was counted, so tests can say why not; callers in
-- the application ignore it. The day is India's, since the product is.
-- -----------------------------------------------------------------------------
create or replace function app.count_product_event(
  p_event          text,
  p_step           smallint default 0,
  p_actor          uuid     default null,
  p_household_id   uuid     default null,
  p_member_ids     uuid[]   default '{}',
  p_investment_ids uuid[]   default '{}',
  p_liability_ids  uuid[]   default '{}',
  p_account_ids    uuid[]   default '{}'
) returns boolean
  language plpgsql security definer
  set search_path = public, app, pg_temp as $$
declare
  v_actor uuid := coalesce(p_actor, app.current_user_id());
  v_event text := p_event;
begin
  -- Derived below, never reported: otherwise it could be counted without a holding.
  if v_event = 'first_holding_added' then
    raise exception 'first_holding_added is counted with holding_added, not on its own';
  end if;

  -- A guest link borrows someone's identity to read; it is not that person using Almira.
  if app.guest_share_id() is not null then
    return false;
  end if;

  if v_actor is not null and exists (select 1 from measurement_opt_outs where user_id = v_actor) then
    return false;
  end if;

  if exists (
       select 1 from members m
       where app.is_minor(m.date_of_birth)
         and (m.id = any(coalesce(p_member_ids, '{}'))
              or (v_actor is not null and m.user_id = v_actor)
              or m.id in (select o.member_id from investment_ownerships o
                          where o.investment_id = any(coalesce(p_investment_ids, '{}')))
              or m.id in (select h.member_id from liability_holders h
                          where h.liability_id = any(coalesce(p_liability_ids, '{}')))
              or m.id in (select a.member_id from account_holders a
                          where a.account_id = any(coalesce(p_account_ids, '{}'))))
     ) then
    return false;
  end if;

  insert into measurement_daily_counts as c (day, event, step, count)
    values ((now() at time zone 'Asia/Kolkata')::date, v_event, coalesce(p_step, 0), 1)
    on conflict (day, event, step) do update set count = c.count + 1;

  -- "First record added" is the same moment as a holding, seen from the
  -- household: counted when this is the only holding the household has ever had
  -- (soft-deleted ones included, so delete-and-add is not a second first).
  if v_event = 'holding_added' and p_household_id is not null
     and (select count(*) from investments where household_id = p_household_id) = 1 then
    insert into measurement_daily_counts as c (day, event, step, count)
      values ((now() at time zone 'Asia/Kolkata')::date, 'first_holding_added', 0, 1)
      on conflict (day, event, step) do update set count = c.count + 1;
  end if;

  return true;
end $$;

comment on function app.count_product_event(text, smallint, uuid, uuid, uuid[], uuid[], uuid[], uuid[]) is
  'Counts one allowlisted product event unless the actor opted out, a minor is involved, or a guest link is in use. Stores no identifier.';
