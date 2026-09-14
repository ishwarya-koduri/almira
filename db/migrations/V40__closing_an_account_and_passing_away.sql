-- =============================================================================
-- V40 · The end of an account: closing it, and a person who has died.
-- Refs: docs/05 §8 ("erase — hard purge with audit"), docs/05 §12, docs/04 §12
--
-- Two different endings, and they are kept apart on purpose.
--
--   Closing an account  — the person asks, sees what goes and what stays, and
--                          then nothing happens for thirty days. They can change
--                          their mind by signing in and saying so. After that a
--                          scheduled sweep erases them (docs/05 §12). The row
--                          here is the promise and the clock; it outlives the
--                          account so the purge itself can be accounted for.
--
--   Passing away        — someone else says it: an admin of the household, or
--                          the person's trusted contact once an emergency window
--                          has genuinely opened. Nothing is erased. The account
--                          becomes read-only in that household, carries a quiet
--                          label, and stops being sent messages. The one person
--                          who can undo it is the person it names, by signing in
--                          — which is also the plainest possible correction.
--
-- Read-only is enforced where every write already routes through: the two
-- capability functions. A memorialised account keeps sight of its own records
-- (sight is never a capability) and loses the ability to change anything.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Closing an account
-- -----------------------------------------------------------------------------
create table account_closures (
  id               uuid primary key default gen_random_uuid(),
  -- Set null by the purge itself, when the user row goes. The closure row stays:
  -- it is the record that a closure was asked for, and when it was carried out.
  user_id          uuid references users(id) on delete set null,
  requested_at     timestamptz not null default now(),
  -- Thirty days is the promise made on the screen. The check makes it a fact the
  -- application cannot shorten by accident or on purpose.
  purge_after      timestamptz not null,
  cancelled_at     timestamptz,
  purged_at        timestamptz,
  created_at       timestamptz not null default now(),
  constraint closure_waits_thirty_days check (purge_after >= requested_at + interval '30 days'),
  constraint closure_ends_once check (cancelled_at is null or purged_at is null)
);
create unique index account_closures_one_pending
  on account_closures (user_id) where cancelled_at is null and purged_at is null;
create index on account_closures (purge_after) where cancelled_at is null and purged_at is null;

comment on table account_closures is
  'A request to close an account. Pending until purge_after, cancellable until then, '
  'then carried out by the lifecycle sweep (docs/05 §12).';

alter table account_closures enable row level security;

-- Your own closure, and nobody else's. An admin has no business knowing that a
-- member has asked to close their account until the household is told.
create policy account_closures_read on account_closures for select
  using (user_id = app.current_user_id());

create policy account_closures_insert on account_closures for insert
  with check (app.guest_share_id() is null and user_id = app.current_user_id());

create policy account_closures_update on account_closures for update
  using (app.guest_share_id() is null and user_id = app.current_user_id())
  with check (user_id = app.current_user_id());

-- A request may only be cancelled by the person in it. The clock cannot be
-- moved, and only the sweep (which runs with no user) records a purge.
create or replace function app.closure_changes_are_limited() returns trigger
  language plpgsql as $$
begin
  if new.user_id is distinct from old.user_id and new.user_id is not null then
    raise exception 'a closure belongs to one account' using errcode = 'check_violation';
  end if;
  if new.requested_at <> old.requested_at or new.purge_after <> old.purge_after then
    raise exception 'a closure''s waiting period cannot be changed' using errcode = 'check_violation';
  end if;
  if new.purged_at is distinct from old.purged_at and app.current_user_id() is not null then
    raise exception 'only the lifecycle sweep records a purge' using errcode = 'insufficient_privilege';
  end if;
  if old.cancelled_at is not null and new.cancelled_at is distinct from old.cancelled_at then
    raise exception 'a cancelled closure stays cancelled' using errcode = 'check_violation';
  end if;
  return new;
end $$;

create trigger account_closures_limited before update on account_closures
  for each row execute function app.closure_changes_are_limited();

-- -----------------------------------------------------------------------------
-- Passing away
-- -----------------------------------------------------------------------------
create table member_memorials (
  id           uuid primary key default gen_random_uuid(),
  household_id uuid not null references households(id) on delete cascade,
  member_id    uuid not null references members(id) on delete cascade,
  -- The login, when the person had one, captured when the memorial is made.
  -- Managed members (a child, a parent someone else recorded for) have none;
  -- for them the memorial is a label and nothing else.
  user_id      uuid references users(id) on delete cascade,
  marked_by    uuid references users(id) on delete set null,
  -- Which door it came through. Kept, because "an admin said so" and "the person
  -- they trusted said so after the window opened" are different claims.
  basis        text not null check (basis in ('admin', 'trusted_contact')),
  note         text check (note is null or length(note) <= 500),
  marked_at    timestamptz not null default now(),
  reversed_at  timestamptz,
  reversed_by  uuid references users(id) on delete set null
);
create unique index member_memorials_one_active
  on member_memorials (member_id) where reversed_at is null;
create index on member_memorials (user_id) where reversed_at is null;

comment on table member_memorials is
  'A household member marked as having passed away. Read-only account in that household, '
  'no notifications, reversible only by the person named (docs/05 §12).';

-- Whether the caller has an emergency window open on this person right now.
-- The same conditions as app.emergency_reveals, for one subject: past the wait,
-- inside the window, not vetoed or withdrawn, and silence since the request.
create or replace function app.has_open_emergency_window(p_household_id uuid, p_subject_member_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (
    select 1
    from emergency_requests r
    join members m on m.id = r.subject_member_id
    where r.household_id = p_household_id
      and r.subject_member_id = p_subject_member_id
      and r.requested_by = app.current_user_id()
      and r.vetoed_at is null
      and r.revoked_at is null
      and now() >= r.unlock_at
      and now() <  r.access_expires_at
      and not exists (
        select 1 from user_sessions s
        where s.user_id = m.user_id
          and s.last_used_at > r.requested_at
      )
  )
$$;

-- Whether the CALLER is memorialised in this household.
create or replace function app.is_memorialised_in(p_household_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (
    select 1 from member_memorials mm
    where mm.household_id = p_household_id
      and mm.user_id = app.current_user_id()
      and mm.reversed_at is null
  )
$$;

-- Whether messages to this person should stop: they are memorialised in this
-- household, or anywhere when the message belongs to no household. A closure
-- that is waiting does NOT stop anything — the person may yet keep the account,
-- and a reminder about a maturing deposit is exactly what they would want.
create or replace function app.notifications_stopped(p_user_id uuid, p_household_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (
    select 1 from member_memorials mm
    where mm.user_id = p_user_id and mm.reversed_at is null
      and (p_household_id is null or mm.household_id = p_household_id))
$$;

-- -----------------------------------------------------------------------------
-- Stopping messages, where every message is written.
--
-- Both definitions are V35's and V32's with one clause more. Every notifier,
-- every sweep and every request that tells someone something goes through one
-- of these two, so this is the place a stop cannot be forgotten by a caller
-- nobody has written yet.
--
-- One message is let through: the one that tells a person they have been
-- marked as passed away. It is written before the memorial, and the exemption
-- is here as well so the order is not what the safeguard depends on. Someone
-- marked by mistake — or on purpose, by someone who wanted them quiet — must
-- hear about it once.
-- -----------------------------------------------------------------------------
create or replace function app.record_in_app_message(
    p_household_id uuid, p_user_id uuid, p_template text, p_title text, p_idempotency_key text)
  returns uuid language plpgsql security definer
  set search_path = public, app, pg_temp as $$
declare
  v_id uuid;
begin
  if p_idempotency_key is null or length(p_idempotency_key) = 0 then
    raise exception 'an in-app message needs an idempotency key';
  end if;
  if p_template <> 'lifecycle.memorial.marked'
     and app.notifications_stopped(p_user_id, p_household_id) then
    return null;
  end if;
  insert into outbound_messages (household_id, user_id, channel, provider, template,
                                 title, status, attempts, idempotency_key)
  values (p_household_id, p_user_id, 'in_app', 'almira', p_template,
          p_title, 'sent', 1, p_idempotency_key)
  on conflict (idempotency_key) where idempotency_key is not null do nothing
  returning id into v_id;
  return v_id;
end $$;

create or replace function app.enqueue_outbound_message(
    p_household_id uuid, p_user_id uuid, p_channel text,
    p_template text, p_title text, p_body text, p_idempotency_key text)
  returns uuid language plpgsql security definer
  set search_path = public, app, pg_temp as $$
declare
  v_id uuid;
begin
  if p_idempotency_key is null or length(p_idempotency_key) = 0 then
    raise exception 'an outbound message needs an idempotency key';
  end if;
  if p_channel = 'in_app' then
    raise exception 'in-app messages are recorded, not queued';
  end if;
  if p_template <> 'lifecycle.memorial.marked'
     and app.notifications_stopped(p_user_id, p_household_id) then
    return null;
  end if;
  insert into outbound_messages (household_id, user_id, channel, provider, template,
                                 title, status, attempts, idempotency_key)
  values (p_household_id, p_user_id, p_channel, 'unknown', p_template,
          p_title, 'queued', 0, p_idempotency_key)
  on conflict (idempotency_key) where idempotency_key is not null do nothing
  returning id into v_id;
  if v_id is not null then
    insert into outbound_message_bodies (message_id, body) values (v_id, coalesce(p_body, ''));
  end if;
  return v_id;
end $$;

alter table member_memorials enable row level security;

-- The household sees the label: it is how they know why someone has gone quiet,
-- and it is shown on the roster everyone already sees.
create policy member_memorials_read on member_memorials for select
  using (app.is_household_member(household_id));

-- Two doors in, never yourself, and never while a guest. The capability check is
-- the raw one below rather than can_administer_household, so the policy reads
-- the same whichever order these functions are redefined in.
create policy member_memorials_insert on member_memorials for insert
  with check (
    app.guest_share_id() is null
    and marked_by = app.current_user_id()
    and not (member_id = any(app.current_member_ids(household_id)))
    and exists (select 1 from members m
                where m.id = member_id and m.household_id = member_memorials.household_id
                  and m.deleted_at is null
                  and m.user_id is not distinct from member_memorials.user_id)
    and ( (basis = 'admin' and app.can_administer_household(household_id))
       or (basis = 'trusted_contact' and app.has_open_emergency_window(household_id, member_id)) )
  );

-- Only the person it names can take it back.
create policy member_memorials_update on member_memorials for update
  using (app.guest_share_id() is null and user_id = app.current_user_id())
  with check (user_id = app.current_user_id() and reversed_by = app.current_user_id());

create or replace function app.memorial_changes_are_limited() returns trigger
  language plpgsql as $$
begin
  if new.household_id <> old.household_id or new.member_id <> old.member_id
     or new.user_id is distinct from old.user_id or new.basis <> old.basis
     or new.marked_at <> old.marked_at or new.marked_by is distinct from old.marked_by
     or new.note is distinct from old.note then
    raise exception 'a memorial can only be reversed, not rewritten' using errcode = 'check_violation';
  end if;
  if old.reversed_at is not null then
    raise exception 'a reversed memorial stays reversed; mark again if needed'
      using errcode = 'check_violation';
  end if;
  return new;
end $$;

create trigger member_memorials_limited before update on member_memorials
  for each row execute function app.memorial_changes_are_limited();

-- -----------------------------------------------------------------------------
-- Read-only, in the database.
--
-- The latest definitions are V21's, with one clause more: a memorialised account
-- can do nothing in that household. Reads never consult these functions, so the
-- person keeps sight of everything they could see — which is what lets them sign
-- in, see the label, and say it is wrong.
-- -----------------------------------------------------------------------------
create or replace function app.can_write_household(p_household_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select app.guest_share_id() is null
     and not app.is_memorialised_in(p_household_id)
     and exists (
    select 1 from household_memberships hm
    where hm.household_id = p_household_id
      and hm.user_id = app.current_user_id()
      and hm.status = 'active'
      and hm.role in ('owner','admin','editor')
  )
$$;

create or replace function app.can_administer_household(p_household_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select app.guest_share_id() is null
     and not app.is_memorialised_in(p_household_id)
     and exists (
    select 1 from household_memberships hm
    where hm.household_id = p_household_id
      and hm.user_id = app.current_user_id()
      and hm.status = 'active'
      and hm.role in ('owner','admin')
  )
$$;
