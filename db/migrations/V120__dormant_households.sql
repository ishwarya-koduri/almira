-- =============================================================================
-- V120 · A household whose last owner goes is dormant until someone takes it on.
-- Refs: docs/05 §12.7, docs/04 §12, docs/known-issues.md ("A household can be left
--       with no owner when its successor or admin is gone by purge time"),
--       lifecycle/AccountPurge.kt, lifecycle/DepartureCompletion.kt,
--       lifecycle/Dormancy.kt
--
-- Owner's decision (2026-09-15): never purge the last owner while the household
-- holds records. Move it to dormant, tell the remaining members, and require an
-- explicit transfer before any purge. A family's records must not become
-- unreachable because of an account lifecycle rule.
--
-- Before this, a closure or a departure whose thirty or seven days were up
-- handed the owner role on by itself — to the named successor, an admin, or the
-- longest-standing member — and when none of those was left by then, the purge
-- went ahead and the household carried on with nobody able to run it.
--
-- Now, when the person going is the only owner able to act, other people are
-- still in the household, and it holds records:
--
--   * the sweep does not carry that part out. It opens a dormancy (here) and
--     leaves the person's membership and what they hold in that household
--     exactly as it was;
--   * nobody can do what needs an owner or admin — the capability functions
--     say no while it is dormant — and reads are untouched, because reads never
--     ask them;
--   * an adult member with a login accepts the household
--     (app.accept_household_ownership), or the person comes back: cancels their
--     closure or departure, or says "I'm here" to a memorial;
--   * only then does the next sweep carry out the rest.
--
-- A household with nobody eligible stays dormant. It is never erased for that.
--
-- Opening a dormancy for a closure or a departure is done by the sweep on the
-- owner connection. For a memorial it is done here, by a trigger, because the
-- memorial is written by a request and the rule must not depend on a service
-- remembering to ask. Ending one is always done here, by triggers, whichever
-- way the owner comes back or the household is taken on.
-- =============================================================================

create table household_dormancies (
  id               uuid primary key default gen_random_uuid(),
  household_id     uuid not null references households(id) on delete cascade,
  -- What left it without an owner. Shown to the household as a plain reason;
  -- nothing about what the person held.
  reason           text not null
                     check (reason in ('owner_closing_account', 'owner_leaving', 'owner_passed_away')),
  -- The owner whose going made it dormant. Set null only if they are erased,
  -- which cannot happen while this is open (the purge waits for it).
  owner_user_id    uuid references users(id) on delete set null,
  owner_member_id  uuid references members(id) on delete set null,
  -- The one event that caused it, so that event being withdrawn ends it.
  closure_id       uuid references account_closures(id) on delete set null,
  departure_id     uuid references household_departures(id) on delete set null,
  memorial_id      uuid references member_memorials(id) on delete set null,
  started_at       timestamptz not null default now(),
  -- The earliest anyone may accept. A week after a memorial, as a succession
  -- claim waits (V41): a false memorial must be correctable before it hands a
  -- household over. At once otherwise — the thirty or seven days have passed.
  accept_from      timestamptz not null default now(),
  ended_at         timestamptz,
  -- transferred: someone took it on. owner_returned: the event was withdrawn.
  ended_reason     text check (ended_reason in ('transferred', 'owner_returned')),
  transferred_to   uuid references users(id) on delete set null,
  constraint dormancy_ends_with_a_reason check ((ended_at is null) = (ended_reason is null)),
  -- Each reason names its own cause and no other. The cause may be unlinked
  -- (set null) by an erasure once it has ended; the reason stays.
  constraint dormancy_names_its_cause check (
    (reason = 'owner_closing_account' and departure_id is null and memorial_id is null)
    or (reason = 'owner_leaving' and closure_id is null and memorial_id is null)
    or (reason = 'owner_passed_away' and closure_id is null and departure_id is null)
  )
);
create unique index household_dormancies_one_open
  on household_dormancies (household_id) where ended_at is null;
create index on household_dormancies (closure_id) where ended_at is null;
create index on household_dormancies (departure_id) where ended_at is null;
create index on household_dormancies (memorial_id) where ended_at is null;

comment on table household_dormancies is
  'A household left without an owner able to act while it holds records. Nothing that needs an '
  'owner or admin can be done, and the departed owner''s part is not carried out, until an adult '
  'member accepts it or the owner comes back (docs/05 §12.7).';

alter table household_dormancies enable row level security;

-- The household sees that it is dormant, since when and why: it is how members
-- know why nobody can invite or remove anyone, and what they can do about it.
-- Nobody writes it through a policy: the sweep, the triggers and the accept
-- function below are the only writers, and R__grants takes the table's write
-- privileges away from the runtime role.
create policy household_dormancies_read on household_dormancies for select
  using (app.is_household_member(household_id));

-- -----------------------------------------------------------------------------
-- The rule, asked in one place.
-- -----------------------------------------------------------------------------

-- Anything a family would lose. Soft-deleted records count: they are in Trash
-- and can be restored.
create or replace function app.household_holds_records(p_household_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (select 1 from investments where household_id = p_household_id)
      or exists (select 1 from liabilities where household_id = p_household_id)
      or exists (select 1 from accounts where household_id = p_household_id)
      or exists (select 1 from goals where household_id = p_household_id)
      or exists (select 1 from estate_documents where household_id = p_household_id)
      or exists (select 1 from contacts where household_id = p_household_id)
      or exists (select 1 from documents where household_id = p_household_id)
$$;

-- The same yes or no for the closure preview, asked by the household's owner
-- about their own household and nobody else's. It can tell an owner that some
-- record exists that they cannot see — never what, whose or how many — as
-- app.managed_member_holdings already tells an admin a count (V41).
create or replace function app.household_holds_records_for_its_owner(p_household_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (select 1 from household_memberships hm
                  where hm.household_id = p_household_id and hm.user_id = app.current_user_id()
                    and hm.status = 'active' and hm.role = 'owner')
     and app.guest_share_id() is null
     and app.household_holds_records(p_household_id)
$$;

-- Whether this person going would leave the household with no owner able to
-- act, people still in it, and records in it. False for a household nobody else
-- belongs to: the closure preview says that household is erased with its only
-- person, at their request, and nobody else could reach it.
create or replace function app.going_leaves_household_ownerless(p_household_id uuid, p_user_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (select 1 from household_memberships hm
                  where hm.household_id = p_household_id and hm.user_id = p_user_id
                    and hm.status = 'active' and hm.role = 'owner')
     and not exists (select 1 from household_memberships hm
                      where hm.household_id = p_household_id and hm.user_id <> p_user_id
                        and hm.status = 'active' and hm.role = 'owner'
                        and not exists (select 1 from member_memorials mm
                                         where mm.household_id = hm.household_id
                                           and mm.user_id = hm.user_id and mm.reversed_at is null))
     and exists (select 1 from household_memberships hm
                  where hm.household_id = p_household_id and hm.user_id <> p_user_id
                    and hm.status = 'active')
     and app.household_holds_records(p_household_id)
$$;

-- Whether a household the CALLER belongs to is dormant. Answers false for any
-- other household, so it says nothing about households the caller cannot see
-- (V107).
create or replace function app.household_is_dormant(p_household_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (select 1 from household_dormancies d
                  where d.household_id = p_household_id and d.ended_at is null)
     and exists (select 1 from household_memberships hm
                  where hm.household_id = p_household_id
                    and hm.user_id = app.current_user_id() and hm.status = 'active')
$$;

-- -----------------------------------------------------------------------------
-- While dormant, nobody administers it.
--
-- V40's definitions with one clause more. can_write_household is unchanged:
-- an editor keeps adding what they hold, and a viewer still reads. Reads never
-- consult these functions, so sight is exactly what it was.
-- -----------------------------------------------------------------------------
create or replace function app.can_administer_household(p_household_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select app.guest_share_id() is null
     and not app.is_memorialised_in(p_household_id)
     and not app.household_is_dormant(p_household_id)
     and exists (
    select 1 from household_memberships hm
    where hm.household_id = p_household_id
      and hm.user_id = app.current_user_id()
      and hm.status = 'active'
      and hm.role in ('owner','admin')
  )
$$;

-- V41's definition with the same clause: a successor is named by an owner who
-- runs the household, and a dormant household has none.
create or replace function app.is_household_owner(p_household_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select app.guest_share_id() is null
     and not app.is_memorialised_in(p_household_id)
     and not app.household_is_dormant(p_household_id)
     and exists (
    select 1 from household_memberships hm
    where hm.household_id = p_household_id
      and hm.user_id = app.current_user_id()
      and hm.status = 'active'
      and hm.role = 'owner'
  )
$$;

-- -----------------------------------------------------------------------------
-- Opening one. The sweep calls this for a closure or a departure; the memorial
-- trigger calls it for a memorial. Returns the dormancy's id when it opened one
-- now, and null when the household is not left ownerless or is already dormant
-- — so a sweep that runs every hour tells the household once.
-- -----------------------------------------------------------------------------
create or replace function app.open_household_dormancy(
    p_household_id uuid, p_user_id uuid, p_reason text,
    p_closure_id uuid, p_departure_id uuid, p_memorial_id uuid, p_accept_from timestamptz)
  returns uuid language plpgsql security definer
  set search_path = public, app, pg_temp as $$
declare
  v_id uuid;
  v_member uuid;
begin
  if not app.going_leaves_household_ownerless(p_household_id, p_user_id) then
    return null;
  end if;
  select m.id into v_member from members m
   where m.household_id = p_household_id and m.user_id = p_user_id and m.deleted_at is null
   limit 1;
  insert into household_dormancies (household_id, reason, owner_user_id, owner_member_id,
                                    closure_id, departure_id, memorial_id, accept_from)
  values (p_household_id, p_reason, p_user_id, v_member,
          p_closure_id, p_departure_id, p_memorial_id, coalesce(p_accept_from, now()))
  on conflict (household_id) where ended_at is null do nothing
  returning id into v_id;
  if v_id is not null then
    insert into activity_log (household_id, actor_user_id, action, entity_type, entity_id, diff)
      values (p_household_id, null, 'household.dormancy.open', 'household', p_household_id,
              jsonb_build_object('dormancyId', v_id, 'reason', p_reason));
  end if;
  return v_id;
end $$;

-- Ending one, with a line in the log. Internal to the triggers and the accept
-- function; nothing else ends a dormancy.
create or replace function app.end_household_dormancy(p_dormancy_id uuid, p_reason text, p_to uuid)
  returns void language plpgsql security definer
  set search_path = public, app, pg_temp as $$
declare
  v_household uuid;
begin
  update household_dormancies
     set ended_at = now(), ended_reason = p_reason, transferred_to = p_to
   where id = p_dormancy_id and ended_at is null
  returning household_id into v_household;
  if v_household is not null then
    insert into activity_log (household_id, actor_user_id, action, entity_type, entity_id, diff)
      values (v_household, app.current_user_id(), 'household.dormancy.end', 'household', v_household,
              jsonb_build_object('dormancyId', p_dormancy_id, 'endedReason', p_reason));
  end if;
end $$;

-- -----------------------------------------------------------------------------
-- A memorial on the last owner able to act.
-- -----------------------------------------------------------------------------
create or replace function app.memorial_opens_dormancy() returns trigger
  language plpgsql security definer
  set search_path = public, app, pg_temp as $$
begin
  if new.user_id is not null and new.reversed_at is null then
    -- From now, not from marked_at: the week is counted from when the household
    -- learns of it, whatever date the row carries.
    perform app.open_household_dormancy(new.household_id, new.user_id, 'owner_passed_away',
                                        null, null, new.id, greatest(new.marked_at, now()) + interval '7 days');
  end if;
  return new;
end $$;

create trigger member_memorials_open_dormancy after insert on member_memorials
  for each row execute function app.memorial_opens_dormancy();

-- -----------------------------------------------------------------------------
-- The owner comes back: the closure, departure or memorial that caused it is
-- withdrawn. Nothing was carried out while it was dormant, so nothing needs
-- undoing but the state itself.
-- -----------------------------------------------------------------------------
create or replace function app.withdrawal_ends_dormancy() returns trigger
  language plpgsql security definer
  set search_path = public, app, pg_temp as $$
declare
  d record;
  v_withdrawn boolean;
begin
  -- Each table says "withdrawn" in its own column; a record has only its own.
  if tg_table_name = 'member_memorials' then
    v_withdrawn := old.reversed_at is null and new.reversed_at is not null;
  else
    v_withdrawn := old.cancelled_at is null and new.cancelled_at is not null;
  end if;
  if not v_withdrawn then
    return new;
  end if;
  for d in
    select hd.id from household_dormancies hd
     where hd.ended_at is null
       and case tg_table_name
             when 'account_closures' then hd.closure_id = new.id
             when 'household_departures' then hd.departure_id = new.id
             else hd.memorial_id = new.id
           end
  loop
    perform app.end_household_dormancy(d.id, 'owner_returned', null);
  end loop;
  return new;
end $$;

create trigger account_closures_end_dormancy after update on account_closures
  for each row execute function app.withdrawal_ends_dormancy();
create trigger household_departures_end_dormancy after update on household_departures
  for each row execute function app.withdrawal_ends_dormancy();
create trigger member_memorials_end_dormancy after update on member_memorials
  for each row execute function app.withdrawal_ends_dormancy();

-- -----------------------------------------------------------------------------
-- Someone able to act becomes an owner: by accepting (below), or by claiming a
-- succession after a memorial (V41). Either way the household has an owner
-- again, and the dormancy ends as transferred.
-- -----------------------------------------------------------------------------
create or replace function app.new_owner_ends_dormancy() returns trigger
  language plpgsql security definer
  set search_path = public, app, pg_temp as $$
declare
  d record;
begin
  if new.role = 'owner' and new.status = 'active'
     and (old.role <> 'owner' or old.status <> 'active')
     and not exists (select 1 from member_memorials mm
                      where mm.household_id = new.household_id and mm.user_id = new.user_id
                        and mm.reversed_at is null) then
    for d in select hd.id from household_dormancies hd
              where hd.household_id = new.household_id and hd.ended_at is null
                and hd.owner_user_id is distinct from new.user_id
    loop
      perform app.end_household_dormancy(d.id, 'transferred', new.user_id);
    end loop;
  end if;
  return new;
end $$;

create trigger household_memberships_end_dormancy after update on household_memberships
  for each row execute function app.new_owner_ends_dormancy();

-- -----------------------------------------------------------------------------
-- Taking it on. A definer function, because a member of a dormant household is
-- by definition not allowed to change anyone's role; this is the single audited
-- way they become the owner, and it refuses anyone who should not.
--
-- Who may: an adult with a login and an active membership, as admin, editor or
-- viewer — not an advisor (a colleague, not family), not a restricted member,
-- not someone memorialised, and not someone who is themselves closing their
-- account or leaving this household, which would only make it dormant again.
-- The step-up is the service's, before this is called.
--
-- What it changes: the caller's role, and nothing else. Role is capability,
-- never sight (docs/05 §3): the departed owner's private records stay private,
-- and the next sweep carries out their closure or departure as docs/05 §12 says.
-- -----------------------------------------------------------------------------
create or replace function app.accept_household_ownership(p_household_id uuid)
  returns void language plpgsql security definer
  set search_path = public, app, pg_temp as $$
declare
  v_user uuid := app.current_user_id();
  d record;
begin
  if v_user is null or app.guest_share_id() is not null then
    raise exception 'authentication required' using errcode = 'insufficient_privilege';
  end if;
  -- Not a member: the household does not exist, as far as the caller knows.
  if not app.is_household_member(p_household_id) then
    raise exception 'dormancy_not_found' using errcode = 'no_data_found';
  end if;
  select * into d from household_dormancies hd
   where hd.household_id = p_household_id and hd.ended_at is null
   for update;
  if not found then
    raise exception 'household_not_dormant' using errcode = 'no_data_found';
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
  if exists (select 1 from account_closures c
              where c.user_id = v_user and c.cancelled_at is null and c.purged_at is null)
     or exists (select 1 from household_departures hd
                 where hd.household_id = p_household_id and hd.user_id = v_user
                   and hd.cancelled_at is null and hd.completed_at is null) then
    raise exception 'ownership_not_while_leaving' using errcode = 'insufficient_privilege';
  end if;
  if now() < d.accept_from then
    raise exception 'dormancy_not_yet' using errcode = 'insufficient_privilege';
  end if;

  insert into activity_log (household_id, actor_user_id, action, entity_type, entity_id, diff)
    values (p_household_id, v_user, 'household.ownership.accept', 'household', p_household_id,
            jsonb_build_object('dormancyId', d.id, 'reason', d.reason));
  -- The trigger on household_memberships ends the dormancy as transferred.
  update household_memberships set role = 'owner'
   where household_id = p_household_id and user_id = v_user and status = 'active';
end $$;
