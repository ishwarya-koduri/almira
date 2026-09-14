-- =============================================================================
-- V41 · People change households: leaving one, carrying one on, growing up in one.
-- Refs: docs/05 §3.5 and §8, docs/05 §12, docs/04 §12, docs/07 §1
--
--   Leaving       — anyone can leave, and an admin can ask someone to. Nothing
--                   moves for seven days. What is solely yours goes with you into
--                   a household of your own (or is erased after you have your
--                   copy); what you hold jointly is flagged for you to decide,
--                   and the decision is visible only to the people who can
--                   already see that record.
--
--   Succession    — the owner names who carries the household on. That person
--                   becomes an admin only when it is genuinely needed: the owner
--                   has been marked as passed away, or their emergency window has
--                   opened for that person. It is a capability and nothing else:
--                   the owner's private records still open only as they chose,
--                   through the continuity marks and the emergency window.
--
--   Coming of age — the month a managed child turns eighteen, the household is
--                   told once, so an invitation to their own login can follow.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Leaving a household
-- -----------------------------------------------------------------------------
create table household_departures (
  id                       uuid primary key default gen_random_uuid(),
  household_id             uuid not null references households(id) on delete cascade,
  member_id                uuid not null references members(id) on delete cascade,
  user_id                  uuid not null references users(id) on delete cascade,
  started_by               uuid references users(id) on delete set null,
  -- An admin asked, rather than the person themselves. An admin-started
  -- departure can be withdrawn by an admin; the person can still choose what
  -- happens to what is theirs, but cannot keep themselves in a household that
  -- has asked them to go.
  started_by_admin         boolean not null,
  -- What happens to what is solely theirs: taken into a household of their own,
  -- or erased once they have downloaded their copy.
  private_records          text not null default 'take'
                             check (private_records in ('take', 'export_and_erase')),
  requested_at             timestamptz not null default now(),
  effective_at             timestamptz not null,
  cancelled_at             timestamptz,
  completed_at             timestamptz,
  -- Where their records went, when they took them.
  destination_household_id uuid references households(id) on delete set null,
  created_at               timestamptz not null default now(),
  constraint departure_waits_seven_days check (effective_at >= requested_at + interval '7 days'),
  constraint departure_ends_once check (cancelled_at is null or completed_at is null)
);
create unique index household_departures_one_pending
  on household_departures (member_id) where cancelled_at is null and completed_at is null;
create index on household_departures (effective_at) where cancelled_at is null and completed_at is null;

create table departure_joint_decisions (
  departure_id uuid not null references household_departures(id) on delete cascade,
  record_type  text not null check (record_type in ('investment', 'liability', 'account')),
  record_id    uuid not null,
  -- stays          — your share passes to the others who hold it.
  -- take_my_share  — the same, and a copy of your share goes with you.
  decision     text not null check (decision in ('stays', 'take_my_share')),
  decided_at   timestamptz not null default now(),
  primary key (departure_id, record_type, record_id)
);

alter table household_departures enable row level security;

-- The fact of a departure is household business: the roster is about to change.
-- What goes with the person is not in this row, and is never shown to anyone
-- but them.
create policy household_departures_read on household_departures for select
  using (app.is_household_member(household_id) or user_id = app.current_user_id());

create policy household_departures_insert on household_departures for insert
  with check (
    app.guest_share_id() is null
    and started_by = app.current_user_id()
    and exists (select 1 from members m
                where m.id = member_id and m.household_id = household_departures.household_id
                  and m.user_id = household_departures.user_id and m.deleted_at is null)
    and ( (not started_by_admin and user_id = app.current_user_id()
           and app.is_household_member(household_id))
       or (started_by_admin and user_id <> app.current_user_id()
           and app.can_administer_household(household_id)) )
  );

create policy household_departures_update on household_departures for update
  using (app.guest_share_id() is null
         and (user_id = app.current_user_id()
           or (started_by_admin and app.can_administer_household(household_id))))
  with check (user_id = app.current_user_id()
           or (started_by_admin and app.can_administer_household(household_id)));

-- Who may change what. The person leaving chooses what happens to their own
-- records; only whoever started it can withdraw it; only the sweep completes it.
create or replace function app.departure_changes_are_limited() returns trigger
  language plpgsql as $$
declare
  v_actor uuid := app.current_user_id();
begin
  if new.household_id <> old.household_id or new.member_id <> old.member_id
     or new.user_id <> old.user_id or new.started_by is distinct from old.started_by
     or new.started_by_admin <> old.started_by_admin
     or new.requested_at <> old.requested_at or new.effective_at <> old.effective_at then
    raise exception 'a departure''s date and people cannot be changed' using errcode = 'check_violation';
  end if;
  if old.cancelled_at is not null or old.completed_at is not null then
    raise exception 'that departure has already ended' using errcode = 'check_violation';
  end if;
  if v_actor is not null then
    if new.completed_at is not null or new.destination_household_id is distinct from old.destination_household_id then
      raise exception 'only the lifecycle sweep completes a departure' using errcode = 'insufficient_privilege';
    end if;
    if new.private_records <> old.private_records and v_actor <> old.user_id then
      raise exception 'only the person leaving decides what happens to their records'
        using errcode = 'insufficient_privilege';
    end if;
    if new.cancelled_at is not null and old.started_by_admin and v_actor = old.user_id then
      raise exception 'an admin asked for this departure; an admin can withdraw it'
        using errcode = 'insufficient_privilege';
    end if;
  end if;
  return new;
end $$;

create trigger household_departures_limited before update on household_departures
  for each row execute function app.departure_changes_are_limited();

alter table departure_joint_decisions enable row level security;

-- The person leaving decides, and only about records they can see. Anyone else
-- who can see the record (a co-owner) can see the decision; an admin who cannot
-- see the record learns nothing, not even that it exists.
create policy departure_joint_decisions_read on departure_joint_decisions for select
  using (exists (select 1 from household_departures d where d.id = departure_id)
         and (exists (select 1 from household_departures d
                      where d.id = departure_id and d.user_id = app.current_user_id())
              or app.linked_record_visible(record_type, record_id)));

create policy departure_joint_decisions_write on departure_joint_decisions for all
  using (app.guest_share_id() is null
         and exists (select 1 from household_departures d
                     where d.id = departure_id and d.user_id = app.current_user_id()
                       and d.cancelled_at is null and d.completed_at is null))
  with check (app.guest_share_id() is null
         and app.linked_record_visible(record_type, record_id)
         and exists (select 1 from household_departures d
                     where d.id = departure_id and d.user_id = app.current_user_id()
                       and d.cancelled_at is null and d.completed_at is null
                       -- A decision about a record is a holder's to make. Being
                       -- able to see a household-shared holding is not holding it.
                       and d.member_id = any(app.record_holder_member_ids(record_type, record_id))));

-- -----------------------------------------------------------------------------
-- The admin removal dead-end.
--
-- Removing a managed member (no login of their own) is refused while records
-- name them, and an admin cannot reassign what they cannot see. These tell the
-- admin how many are in the way and who can move them, without showing a single
-- title. Only for managed members: a person with a login leaves through a
-- departure, and their private count is nobody else's business.
-- -----------------------------------------------------------------------------
create or replace function app.managed_member_holdings(p_member_id uuid)
  returns table (visible_count int, hidden_count int)
  language plpgsql stable security definer
  set search_path = public, app, pg_temp as $$
declare
  v_household uuid;
  v_user uuid;
begin
  select m.household_id, m.user_id into v_household, v_user
    from members m where m.id = p_member_id and m.deleted_at is null;
  if v_household is null or v_user is not null
     or not app.can_administer_household(v_household) then
    return query select 0, 0;
    return;
  end if;
  return query
    with named as (
      select app.can_read_record(i.household_id, i.visibility, 'investment', i.id,
                                 app.owns_investment(i.id)) as readable
        from investment_ownerships o join investments i on i.id = o.investment_id
       where o.member_id = p_member_id and i.deleted_at is null
      union all
      select app.can_read_record(l.household_id, l.visibility, 'liability', l.id,
                                 app.owes_liability(l.id))
        from liability_holders h join liabilities l on l.id = h.liability_id
       where h.member_id = p_member_id and l.deleted_at is null
      union all
      select app.can_read_record(a.household_id, a.visibility, 'account', a.id,
                                 app.holds_account(a.id))
        from account_holders h join accounts a on a.id = h.account_id
       where h.member_id = p_member_id and a.deleted_at is null
    )
    select (count(*) filter (where readable))::int,
           (count(*) filter (where not readable))::int
      from named;
end $$;

-- The people who recorded the records an admin cannot see, so they can be told.
-- User ids only, and only people still active in the household; the caller
-- already knows every member of it.
create or replace function app.recorders_of_hidden_holdings(p_member_id uuid)
  returns setof uuid
  language plpgsql stable security definer
  set search_path = public, app, pg_temp as $$
declare
  v_household uuid;
  v_user uuid;
begin
  select m.household_id, m.user_id into v_household, v_user
    from members m where m.id = p_member_id and m.deleted_at is null;
  if v_household is null or v_user is not null
     or not app.can_administer_household(v_household) then
    return;
  end if;
  return query
    with hidden as (
      select i.created_by
        from investment_ownerships o join investments i on i.id = o.investment_id
       where o.member_id = p_member_id and i.deleted_at is null
         and not app.can_read_record(i.household_id, i.visibility, 'investment', i.id,
                                     app.owns_investment(i.id))
      union
      select l.created_by
        from liability_holders h join liabilities l on l.id = h.liability_id
       where h.member_id = p_member_id and l.deleted_at is null
         and not app.can_read_record(l.household_id, l.visibility, 'liability', l.id,
                                     app.owes_liability(l.id))
      union
      select a.created_by
        from account_holders h join accounts a on a.id = h.account_id
       where h.member_id = p_member_id and a.deleted_at is null
         and not app.can_read_record(a.household_id, a.visibility, 'account', a.id,
                                     app.holds_account(a.id))
    )
    select distinct hidden.created_by
      from hidden
      join household_memberships hm
        on hm.household_id = v_household and hm.user_id = hidden.created_by and hm.status = 'active'
     where hidden.created_by is not null;
end $$;

-- -----------------------------------------------------------------------------
-- Succession
-- -----------------------------------------------------------------------------
create table household_successors (
  household_id        uuid primary key references households(id) on delete cascade,
  -- The owner who named them. If ownership changes hands, the new owner names
  -- their own.
  named_by            uuid not null references users(id) on delete cascade,
  successor_member_id uuid not null references members(id) on delete cascade,
  named_at            timestamptz not null default now(),
  claimed_at          timestamptz,
  claimed_basis       text check (claimed_basis in ('passed_away', 'emergency_access')),
  constraint claim_says_why check ((claimed_at is null) = (claimed_basis is null))
);

create or replace function app.is_household_owner(p_household_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select app.guest_share_id() is null
     and not app.is_memorialised_in(p_household_id)
     and exists (
    select 1 from household_memberships hm
    where hm.household_id = p_household_id
      and hm.user_id = app.current_user_id()
      and hm.status = 'active'
      and hm.role = 'owner'
  )
$$;

alter table household_successors enable row level security;

-- The owner, and the person named. It is a responsibility they should know they
-- hold; it is not a notice for the whole household.
create policy household_successors_read on household_successors for select
  using (app.is_household_member(household_id)
         and (named_by = app.current_user_id()
           or successor_member_id = any(app.current_member_ids(household_id))));

create policy household_successors_write on household_successors for all
  using (named_by = app.current_user_id() and app.is_household_owner(household_id))
  with check (named_by = app.current_user_id() and app.is_household_owner(household_id)
              and claimed_at is null
              and exists (select 1 from members m
                          join household_memberships hm
                            on hm.household_id = m.household_id and hm.user_id = m.user_id
                           and hm.status = 'active'
                          where m.id = successor_member_id
                            and m.household_id = household_successors.household_id
                            and m.deleted_at is null
                            and m.user_id <> app.current_user_id()
                            -- An advisor is a colleague of the family, not
                            -- someone to carry the household on.
                            and hm.role <> 'advisor'));

-- Claiming. A definer function, because the successor is by definition not yet
-- allowed to change anyone's role — this is the single audited way they become
-- so, and it refuses unless the named event has really happened.
create or replace function app.claim_household_succession(p_household_id uuid)
  returns text
  language plpgsql security definer
  set search_path = public, app, pg_temp as $$
declare
  v_user uuid := app.current_user_id();
  s record;
  v_owner_member uuid;
  v_basis text;
  v_role text;
begin
  if v_user is null or app.guest_share_id() is not null then
    raise exception 'authentication required' using errcode = 'insufficient_privilege';
  end if;

  select * into s from household_successors hs
    where hs.household_id = p_household_id
      and hs.successor_member_id = any(app.current_member_ids(p_household_id));
  if not found then
    raise exception 'succession_not_named' using errcode = 'no_data_found';
  end if;
  if s.claimed_at is not null then
    raise exception 'succession_already_claimed' using errcode = 'invalid_parameter_value';
  end if;
  -- Still here, still able to act, and the person who named them still the
  -- owner. A successor named by someone who has since handed the household on
  -- is a leftover, not a plan.
  if app.is_memorialised_in(p_household_id)
     or not exists (select 1 from household_memberships hm
                    where hm.household_id = p_household_id and hm.user_id = v_user
                      and hm.status = 'active' and hm.role <> 'advisor') then
    raise exception 'succession_not_named' using errcode = 'no_data_found';
  end if;
  if not exists (select 1 from household_memberships hm
                 where hm.household_id = p_household_id and hm.user_id = s.named_by
                   and hm.status = 'active' and hm.role = 'owner') then
    raise exception 'succession_not_yet' using errcode = 'insufficient_privilege';
  end if;

  select m.id into v_owner_member from members m
    where m.household_id = p_household_id and m.user_id = s.named_by and m.deleted_at is null
    limit 1;

  -- A week after the memorial, not the moment it is made. The memorial is
  -- reversible by the person it names, and they are told when it is made; an
  -- ownership that changed hands in the same minute would make a false memorial
  -- a way to take a household rather than a mistake to correct.
  if exists (select 1 from member_memorials mm
             where mm.household_id = p_household_id and mm.user_id = s.named_by
               and mm.reversed_at is null
               and mm.marked_at <= now() - interval '7 days') then
    v_basis := 'passed_away';
    v_role := 'owner';
  elsif v_owner_member is not null and app.has_open_emergency_window(p_household_id, v_owner_member) then
    v_basis := 'emergency_access';
    v_role := 'admin';
  else
    raise exception 'succession_not_yet' using errcode = 'insufficient_privilege';
  end if;

  -- Never a demotion: an admin who becomes successor on an emergency window
  -- stays an admin, and an owner stays an owner.
  update household_memberships
     set role = case when role = 'owner' then 'owner'
                     when v_role = 'owner' then 'owner'
                     else 'admin' end
   where household_id = p_household_id and user_id = v_user and status = 'active';

  update household_successors
     set claimed_at = now(), claimed_basis = v_basis
   where household_id = p_household_id;

  insert into activity_log (household_id, actor_user_id, action, entity_type, entity_id, diff)
    values (p_household_id, v_user, 'household.succession.claim', 'household', p_household_id,
            jsonb_build_object('basis', v_basis, 'role', v_role));

  return v_basis;
end $$;

-- -----------------------------------------------------------------------------
-- Coming of age
-- -----------------------------------------------------------------------------
create table coming_of_age_notices (
  member_id       uuid primary key references members(id) on delete cascade,
  household_id    uuid not null references households(id) on delete cascade,
  turns_adult_on  date not null,
  noticed_at      timestamptz not null default now(),
  -- When the young adult, signed in as themselves, has seen what is theirs.
  welcomed_at     timestamptz
);

alter table coming_of_age_notices enable row level security;

create policy coming_of_age_notices_read on coming_of_age_notices for select
  using (app.is_household_member(household_id));

-- Written by the sweep (no user, so no policy is needed for it). The only change
-- anyone makes is the young adult saying they have seen it.
create policy coming_of_age_notices_update on coming_of_age_notices for update
  using (app.guest_share_id() is null and member_id = any(app.current_member_ids(household_id)))
  with check (member_id = any(app.current_member_ids(household_id)));
