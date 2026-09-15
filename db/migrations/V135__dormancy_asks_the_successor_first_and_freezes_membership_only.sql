-- =============================================================================
-- V135 · A dormant household asks its named successor first, and freezes
--        membership only.
-- Refs: docs/05 §12.7, docs/04 §12, V120, V41,
--       docs/known-issues.md ("The last owner of a household with records must
--       be taken on explicitly, even with a successor named" — closed by this),
--       lifecycle/Dormancy.kt
--
-- The owner's answers (2026-09-15):
--
--   (D6) "Acceptance is the whole point. Order it, don't automate it: ask the
--        named successor first, give them a longer window, then open it to
--        others." A named successor is still never made owner by the sweep.
--        When the household goes dormant and the successor the departed owner
--        named is eligible (an adult with a login and an active membership as
--        admin, editor or viewer; not memorialised; not leaving or closing their
--        account), only they may take it on until `successor_until`: the
--        general wait (`accept_from`: a week after a memorial, at once otherwise)
--        plus the successor's window, `dormancy_settings.successor_window`
--        (14 days unless configured; the application writes
--        almira.lifecycle.dormancy.successor-window here at startup). They may
--        accept (step-up, audited) or decline (audited). On a decline, at the
--        end of the window, or as soon as they stop being eligible, the offer is
--        open to every other eligible member, who are told then.
--
--   (D7) "Keep the frozen week after a memorial, but freeze MEMBERSHIP only."
--        V120 made can_administer_household false for everyone while dormant.
--        Now only who is in the household, and with what role, is frozen:
--        inviting (and accepting an invitation), removing or adding a person,
--        role changes, asking someone to leave, marking someone as passed away
--        through the admin door, a member's date of birth or login, and naming
--        a successor (is_household_owner stays false: the only owner is the one
--        who went). Everything else an existing admin could do — renaming,
--        connections, editing a member's name — stays open, as do reads, the
--        handbook, heir mode and Download everything, which never asked.
--        The owner whose going made it dormant administers nothing meanwhile.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- The successor's window, one row.
-- -----------------------------------------------------------------------------
create table dormancy_settings (
  id                boolean primary key default true check (id),
  successor_window  interval not null default interval '14 days'
                      check (successor_window >= interval '1 day' and successor_window <= interval '90 days'),
  updated_at        timestamptz not null default now()
);
insert into dormancy_settings (id) values (true);

comment on table dormancy_settings is
  'How long a named successor alone is asked to take a dormant household on (docs/05 §12.7). '
  'Written by the application at startup from almira.lifecycle.dormancy.successor-window, on the owner connection.';

-- Nobody reads or writes it through a policy: the definer functions below read
-- it, and the owner connection writes it. R__grants takes it away from the
-- runtime role.
alter table dormancy_settings enable row level security;

-- -----------------------------------------------------------------------------
-- Who is asked first.
-- -----------------------------------------------------------------------------
alter table household_dormancies
  add column successor_member_id   uuid references members(id) on delete set null,
  add column successor_until       timestamptz,
  add column successor_declined_at timestamptz,
  -- When the offer was opened to everyone eligible, and they were told. Set when
  -- the dormancy opens with nobody to ask first, on a decline, or by the sweep
  -- once the window has passed or the successor is no longer eligible.
  add column opened_to_others_at   timestamptz;

alter table household_dormancies add constraint dormancy_successor_window_follows_the_wait
  check (successor_until is null or successor_until >= accept_from);
alter table household_dormancies add constraint dormancy_decline_needs_a_successor_window
  check (successor_declined_at is null or successor_until is not null);

-- Existing dormancies had no successor to ask: they are open to others already.
update household_dormancies set opened_to_others_at = started_at where opened_to_others_at is null;

-- -----------------------------------------------------------------------------
-- May this person take this household on? V120's rule, in one place, about a
-- named person — so the successor's eligibility and the accept function cannot
-- disagree. The owner's going is excluded by the caller.
-- -----------------------------------------------------------------------------
create or replace function app.may_take_on_household(p_household_id uuid, p_user_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select p_user_id is not null
     and exists (select 1 from household_memberships hm
                  where hm.household_id = p_household_id and hm.user_id = p_user_id
                    and hm.status = 'active' and hm.role in ('admin', 'editor', 'viewer'))
     and exists (select 1 from members m
                  where m.household_id = p_household_id and m.user_id = p_user_id
                    and m.deleted_at is null and not app.is_minor(m.date_of_birth))
     and not exists (select 1 from member_memorials mm
                      where mm.household_id = p_household_id and mm.user_id = p_user_id
                        and mm.reversed_at is null)
     and not exists (select 1 from account_closures c
                      where c.user_id = p_user_id and c.cancelled_at is null and c.purged_at is null)
     and not exists (select 1 from household_departures hd
                      where hd.household_id = p_household_id and hd.user_id = p_user_id
                        and hd.cancelled_at is null and hd.completed_at is null)
$$;

-- The successor's user, while they are still the one asked: named, not declined,
-- inside their window, and still eligible. Null otherwise.
create or replace function app.dormancy_asks_first(p_dormancy_id uuid)
  returns uuid language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select m.user_id
    from household_dormancies d
    join members m on m.id = d.successor_member_id and m.deleted_at is null
   where d.id = p_dormancy_id and d.ended_at is null
     and d.successor_declined_at is null
     and d.successor_until is not null and now() < d.successor_until
     and m.user_id is distinct from d.owner_user_id
     and app.may_take_on_household(d.household_id, m.user_id)
$$;

-- -----------------------------------------------------------------------------
-- Opening one: V120's function, and the successor it asks first.
-- -----------------------------------------------------------------------------
create or replace function app.open_household_dormancy(
    p_household_id uuid, p_user_id uuid, p_reason text,
    p_closure_id uuid, p_departure_id uuid, p_memorial_id uuid, p_accept_from timestamptz)
  returns uuid language plpgsql security definer
  set search_path = public, app, pg_temp as $$
declare
  v_id uuid;
  v_member uuid;
  v_accept_from timestamptz := coalesce(p_accept_from, now());
  v_successor uuid;
  v_successor_user uuid;
  v_until timestamptz;
begin
  if not app.going_leaves_household_ownerless(p_household_id, p_user_id) then
    return null;
  end if;
  select m.id into v_member from members m
   where m.household_id = p_household_id and m.user_id = p_user_id and m.deleted_at is null
   limit 1;

  -- The successor this owner named, if they may take it on.
  select s.successor_member_id, m.user_id into v_successor, v_successor_user
    from household_successors s
    join members m on m.id = s.successor_member_id and m.deleted_at is null
   where s.household_id = p_household_id and s.named_by = p_user_id and s.claimed_at is null;
  if v_successor is not null and v_successor_user is distinct from p_user_id
     and app.may_take_on_household(p_household_id, v_successor_user) then
    select v_accept_from + ds.successor_window into v_until from dormancy_settings ds where ds.id;
    v_until := coalesce(v_until, v_accept_from + interval '14 days');
  else
    v_successor := null;
  end if;

  insert into household_dormancies (household_id, reason, owner_user_id, owner_member_id,
                                    closure_id, departure_id, memorial_id, accept_from,
                                    successor_member_id, successor_until, opened_to_others_at)
  values (p_household_id, p_reason, p_user_id, v_member,
          p_closure_id, p_departure_id, p_memorial_id, v_accept_from,
          v_successor, v_until, case when v_successor is null then now() end)
  on conflict (household_id) where ended_at is null do nothing
  returning id into v_id;
  if v_id is not null then
    insert into activity_log (household_id, actor_user_id, action, entity_type, entity_id, diff)
      values (p_household_id, null, 'household.dormancy.open', 'household', p_household_id,
              jsonb_build_object('dormancyId', v_id, 'reason', p_reason,
                                 'successorAskedFirst', v_successor is not null));
  end if;
  return v_id;
end $$;

-- -----------------------------------------------------------------------------
-- Taking it on: V120's function, with the order. Nobody but the successor while
-- the successor is asked first.
-- -----------------------------------------------------------------------------
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

-- -----------------------------------------------------------------------------
-- Declining. Only the successor, only while they are the one asked. Returns the
-- dormancy's id so the service can tell the others; the offer is open to them
-- from now.
-- -----------------------------------------------------------------------------
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

-- What the caller may know about the order: whether someone is asked first,
-- until when, and whether that is them. Never who, unless it is them — the
-- successor is the owner's and the successor's business (V41).
create or replace function app.dormancy_order_for_me(p_household_id uuid)
  returns table (asked_first_until timestamptz, you_are_asked_first boolean, declined boolean)
  language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select case when app.dormancy_asks_first(d.id) is not null then d.successor_until end,
         app.dormancy_asks_first(d.id) = app.current_user_id(),
         d.successor_declined_at is not null
    from household_dormancies d
   where d.household_id = p_household_id and d.ended_at is null
     and app.is_household_member(p_household_id)
$$;

-- -----------------------------------------------------------------------------
-- (D7) Administering is open again to the admins who remain; only the owner
-- whose going made it dormant administers nothing meanwhile. V40's definition
-- with that one clause.
-- -----------------------------------------------------------------------------
create or replace function app.can_administer_household(p_household_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select app.guest_share_id() is null
     and not app.is_memorialised_in(p_household_id)
     and not exists (select 1 from household_dormancies d
                      where d.household_id = p_household_id and d.ended_at is null
                        and d.owner_user_id = app.current_user_id())
     and exists (
    select 1 from household_memberships hm
    where hm.household_id = p_household_id
      and hm.user_id = app.current_user_id()
      and hm.status = 'active'
      and hm.role in ('owner','admin')
  )
$$;

-- Who is in the household, and as what, is frozen while it is dormant.
-- app.household_is_dormant answers only for a household the caller belongs to,
-- which every admin door below already requires.
drop policy memberships_write on household_memberships;
create policy memberships_write on household_memberships for insert
  with check (app.can_administer_household(household_id) and not app.household_is_dormant(household_id));

drop policy memberships_update on household_memberships;
create policy memberships_update on household_memberships for update
  using (app.can_administer_household(household_id) and not app.household_is_dormant(household_id))
  with check (app.can_administer_household(household_id) and not app.household_is_dormant(household_id));

drop policy memberships_delete on household_memberships;
create policy memberships_delete on household_memberships for delete
  using ((app.can_administer_household(household_id) and not app.household_is_dormant(household_id))
         or user_id = app.current_user_id());

drop policy members_insert on members;
create policy members_insert on members for insert
  with check (app.can_administer_household(household_id) and not app.household_is_dormant(household_id));

drop policy members_delete on members;
create policy members_delete on members for delete
  using (app.can_administer_household(household_id) and not app.household_is_dormant(household_id));

drop policy household_departures_insert on household_departures;
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
           and app.can_administer_household(household_id)
           and not app.household_is_dormant(household_id)) )
  );

drop policy member_memorials_insert on member_memorials;
create policy member_memorials_insert on member_memorials for insert
  with check (
    app.guest_share_id() is null
    and marked_by = app.current_user_id()
    and not (member_id = any(app.current_member_ids(household_id)))
    and exists (select 1 from members m
                where m.id = member_id and m.household_id = member_memorials.household_id
                  and m.deleted_at is null
                  and m.user_id is not distinct from member_memorials.user_id)
    and ( (basis = 'admin' and app.can_administer_household(household_id)
           and not app.household_is_dormant(household_id))
       or (basis = 'trusted_contact' and app.has_open_emergency_window(household_id, member_id)) )
  );

-- A member's row: the name is not membership, but who signs in as it, whether
-- it is removed, and the date of birth that decides who may take the household
-- on, are. Asked of a request (a signed-in caller) only; the sweep, with no
-- user, carries out what was decided before.
create or replace function app.members_frozen_while_dormant() returns trigger
  language plpgsql security definer
  set search_path = public, app, pg_temp as $$
begin
  if app.current_user_id() is null then
    return new;
  end if;
  if (new.user_id is distinct from old.user_id
      or new.date_of_birth is distinct from old.date_of_birth
      or new.deleted_at is distinct from old.deleted_at)
     and exists (select 1 from household_dormancies d
                  where d.household_id = old.household_id and d.ended_at is null) then
    raise exception 'household_dormant' using errcode = 'insufficient_privilege';
  end if;
  return new;
end $$;

create trigger members_frozen_while_dormant before update on members
  for each row execute function app.members_frozen_while_dormant();

-- Accepting an invitation is joining: V7's function, refused while dormant,
-- before anything is written.
create or replace function app.accept_invitation(p_token_hash text)
  returns table (out_household_id uuid, out_member_id uuid, out_role text)
  language plpgsql security definer
  set search_path = public, app, pg_temp as $$
declare
  v_user   uuid := app.current_user_id();
  v_member uuid;
  inv      record;
begin
  if v_user is null then
    raise exception 'authentication required' using errcode = 'insufficient_privilege';
  end if;

  select * into inv from invitations where token_hash = p_token_hash;
  if not found then
    raise exception 'invitation_not_found' using errcode = 'no_data_found';
  end if;
  if inv.accepted_at is not null then
    raise exception 'invitation_used' using errcode = 'invalid_parameter_value';
  end if;
  if inv.revoked_at is not null then
    raise exception 'invitation_revoked' using errcode = 'invalid_parameter_value';
  end if;
  if inv.expires_at < now() then
    raise exception 'invitation_expired' using errcode = 'invalid_parameter_value';
  end if;

  if exists (select 1 from household_memberships hm
             where hm.household_id = inv.household_id
               and hm.user_id = v_user and hm.status = 'active') then
    select m.id into v_member from members m
      where m.household_id = inv.household_id and m.user_id = v_user
        and m.deleted_at is null
      limit 1;
    update invitations set accepted_at = now() where id = inv.id;
    return query select inv.household_id, v_member, inv.role;
    return;
  end if;

  if exists (select 1 from household_dormancies d
              where d.household_id = inv.household_id and d.ended_at is null) then
    raise exception 'household_dormant' using errcode = 'insufficient_privilege';
  end if;

  if inv.member_id is not null then
    update members set user_id = v_user
      where id = inv.member_id and user_id is null and deleted_at is null
      returning id into v_member;
  end if;

  if v_member is null then
    insert into members (household_id, user_id, display_name, relationship)
      select inv.household_id, v_user, coalesce(nullif(u.full_name, ''), 'Member'), 'other'
      from users u where u.id = v_user
      returning id into v_member;
  end if;

  insert into household_memberships (household_id, user_id, role, status)
    values (inv.household_id, v_user, inv.role, 'active')
    on conflict (household_id, user_id)
      do update set status = 'active', role = excluded.role;

  update invitations set accepted_at = now() where id = inv.id;

  insert into activity_log (household_id, actor_user_id, action, entity_type, entity_id)
    values (inv.household_id, v_user, 'invitation.accept', 'member', v_member);

  return query select inv.household_id, v_member, inv.role;
end $$;
