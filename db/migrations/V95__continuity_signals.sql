-- =============================================================================
-- V95 · Continuity signals: going quiet, still reachable, "do you know where",
--       a chain of key holders, and what term cover is against expenses.
-- Refs: docs/27-continuity-signals.md, docs/05 §6, docs/20, docs/22
--
-- Five small things, each a narrowing of what already exists rather than a new
-- way in:
--
--   inactivity_checks            An owner can say "if I go quiet for N days,
--                                start the request my trusted contacts could
--                                have made". Off unless the owner turns it on.
--                                It raises an ordinary emergency request, so the
--                                owner's waiting period, the veto and V25's
--                                "silence since the request" all still apply.
--   continuity_links             One-tap "I'm here" and "Yes, I can still be
--                                reached" links. Only a hash is stored; each is
--                                single-use and expires. Nobody but the two
--                                definer functions below can read or spend one.
--   trusted_contact_confirmations  The dated tick a trusted contact leaves.
--   key_holder_asks              "Do you know where the SBI locker key is?",
--                                Yes / Not sure. The "thing" is the record's
--                                title, which was already plaintext; the sealed
--                                location and key holder never enter the question.
--   access_chain_confirmations   A tick per position in the sealed chain of key
--                                holders (key_holder, key_holder_2, key_holder_3).
--                                The names stay sealed; only "position 2 was
--                                confirmed on this date" is plaintext.
--   protection_inputs            Annual expenses and who depends on them, entered
--                                by a person for themselves. Read by nobody else.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Presence. One definition of "this person has been here since", used by the
-- unlock rule and by the inactivity sweep, so the two can never disagree.
-- A session used, or an "I'm here" tapped, after the moment in question.
-- -----------------------------------------------------------------------------
create table inactivity_checks (
  id                  uuid primary key default gen_random_uuid(),
  household_id        uuid not null references households(id) on delete cascade,
  member_id           uuid not null references members(id) on delete cascade,
  user_id             uuid not null references users(id) on delete cascade,
  -- Off unless the owner turns it on. A default of "on" would be an unlock
  -- nobody chose.
  enabled             boolean not null default false,
  -- Conservative: two months at the least, a year at the most. The reminders
  -- and the request come after this, not at it.
  period_days         int not null default 90 check (period_days between 60 and 365),
  enabled_at          timestamptz,
  last_check_in_at    timestamptz,
  -- The timeline, each stamped when it happened. A stamp older than the
  -- person's last presence belongs to a finished cycle and counts for nothing.
  first_reminder_at   timestamptz,
  second_reminder_at  timestamptz,
  raised_at           timestamptz,
  version             int not null default 1,
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  unique (household_id, member_id),
  constraint enabled_has_a_start check (not enabled or enabled_at is not null)
);
create trigger inactivity_checks_touch before insert or update on inactivity_checks
  for each row execute function app.touch_row();

comment on table inactivity_checks is
  'An owner''s "if I go quiet" setting. Raises ordinary emergency requests after two '
  'unanswered check-ins; the waiting period and veto still apply (docs/27 §1).';

alter table inactivity_checks enable row level security;

-- Only the person it is about. A trusted contact learns of it when it matters:
-- when a request is raised, which tells them.
create policy inactivity_checks_own on inactivity_checks for all
  using (app.guest_share_id() is null
         and user_id = app.current_user_id()
         and member_id = any(app.current_member_ids(household_id)))
  with check (app.guest_share_id() is null
              and user_id = app.current_user_id()
              and member_id = any(app.current_member_ids(household_id)));

create or replace function app.member_present_since(p_member_id uuid, p_since timestamptz)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (
           select 1 from members m
           join user_sessions s on s.user_id = m.user_id
           where m.id = p_member_id and s.last_used_at > p_since)
      or exists (
           select 1 from inactivity_checks c
           where c.member_id = p_member_id and c.last_check_in_at > p_since)
$$;

-- What the runtime may ask: whether the person a request is about has been
-- here since it was made, for a request the caller can already see. The inner
-- function is not granted to the runtime role (R__grants), so presence is never
-- a question anyone in the product can put about anyone.
create or replace function app.emergency_subject_present(p_request_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (
    select 1 from emergency_requests r
    where r.id = p_request_id
      and app.guest_share_id() is null
      and (r.requested_by = app.current_user_id()
        or r.subject_member_id = any(app.current_member_ids(r.household_id)))
      and app.member_present_since(r.subject_member_id, r.requested_at))
$$;

-- An emergency request now says how it began. 'inactivity' rows are raised by
-- the sweep on the owner's own instruction, in the trusted contact's name.
alter table emergency_requests
  add column raised_by text not null default 'request'
    check (raised_by in ('request', 'inactivity'));

-- V25's rule, with an "I'm here" counting as presence exactly as a sign-in does.
create or replace function app.emergency_reveals(p_household_id uuid, p_in_continuity boolean)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select coalesce(p_in_continuity, false) and exists (
    select 1
    from emergency_requests r
    where r.household_id = p_household_id
      and r.requested_by = app.current_user_id()
      and r.vetoed_at is null
      and r.revoked_at is null
      and now() >= r.unlock_at
      and now() <  r.access_expires_at
      and not app.member_present_since(r.subject_member_id, r.requested_at)
  )
$$;

create or replace function app.has_open_emergency_window(p_household_id uuid, p_subject_member_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (
    select 1
    from emergency_requests r
    where r.household_id = p_household_id
      and r.subject_member_id = p_subject_member_id
      and r.requested_by = app.current_user_id()
      and r.vetoed_at is null
      and r.revoked_at is null
      and now() >= r.unlock_at
      and now() <  r.access_expires_at
      and not app.member_present_since(r.subject_member_id, r.requested_at)
  )
$$;

-- -----------------------------------------------------------------------------
-- Trusted contacts, still reachable (yearly)
-- -----------------------------------------------------------------------------
create table trusted_contact_confirmations (
  id                    uuid primary key default gen_random_uuid(),
  household_id          uuid not null references households(id) on delete cascade,
  emergency_contact_id  uuid not null references emergency_contacts(id) on delete cascade,
  confirmed_by          uuid not null references users(id) on delete cascade,
  confirmed_at          timestamptz not null default now(),
  via                   text not null check (via in ('app', 'link'))
);
create index on trusted_contact_confirmations (emergency_contact_id, confirmed_at desc);

-- When they were last asked, so a year is counted from the question as well as
-- the answer. Written by the sweep and by the owner's "Ask now".
create table trusted_contact_asks (
  emergency_contact_id  uuid primary key references emergency_contacts(id) on delete cascade,
  household_id          uuid not null references households(id) on delete cascade,
  last_asked_at         timestamptz not null default now()
);

alter table trusted_contact_confirmations enable row level security;
alter table trusted_contact_asks enable row level security;

-- Readable by whoever can read the contact row itself (V20): the owner who
-- named them, and the person named. The subquery runs under that row's policy.
create policy trusted_contact_confirmations_read on trusted_contact_confirmations for select
  using (app.guest_share_id() is null
         and exists (select 1 from emergency_contacts c where c.id = emergency_contact_id));

-- Only the person named can say they are reachable, and only for themselves.
create policy trusted_contact_confirmations_insert on trusted_contact_confirmations for insert
  with check (app.guest_share_id() is null
              and confirmed_by = app.current_user_id()
              and via = 'app'
              and exists (select 1 from emergency_contacts c
                          where c.id = emergency_contact_id
                            and c.household_id = trusted_contact_confirmations.household_id
                            and c.trusted_member_id = any(app.current_member_ids(c.household_id))));

create policy trusted_contact_asks_read on trusted_contact_asks for select
  using (app.guest_share_id() is null
         and exists (select 1 from emergency_contacts c where c.id = emergency_contact_id));

-- The owner who named them may record that they asked.
create policy trusted_contact_asks_write on trusted_contact_asks for all
  using (app.guest_share_id() is null
         and exists (select 1 from emergency_contacts c
                     where c.id = emergency_contact_id
                       and c.member_id = any(app.current_member_ids(c.household_id))))
  with check (app.guest_share_id() is null
              and exists (select 1 from emergency_contacts c
                          where c.id = emergency_contact_id
                            and c.household_id = trusted_contact_asks.household_id
                            and c.member_id = any(app.current_member_ids(c.household_id))));

-- -----------------------------------------------------------------------------
-- One-tap links
-- -----------------------------------------------------------------------------
create table continuity_links (
  id                    uuid primary key default gen_random_uuid(),
  purpose               text not null check (purpose in ('check_in', 'reachable')),
  -- Only the hash. The link itself exists in one message and nowhere else.
  token_hash            text not null unique,
  household_id          uuid not null references households(id) on delete cascade,
  -- Whose tap it is: the owner for a check-in, the trusted contact otherwise.
  user_id               uuid not null references users(id) on delete cascade,
  inactivity_check_id   uuid references inactivity_checks(id) on delete cascade,
  emergency_contact_id  uuid references emergency_contacts(id) on delete cascade,
  created_at            timestamptz not null default now(),
  expires_at            timestamptz not null,
  used_at               timestamptz,
  constraint link_expires_after_it_is_made check (expires_at > created_at),
  constraint check_in_names_its_check
    check ((purpose = 'check_in') = (inactivity_check_id is not null)),
  constraint reachable_names_its_contact
    check ((purpose = 'reachable') = (emergency_contact_id is not null))
);

comment on table continuity_links is
  'Single-use, expiring "I''m here" and "still reachable" links. Hash only. The runtime role '
  'can neither read nor write it; app.redeem_continuity_link spends one (docs/27 §2).';

alter table continuity_links enable row level security;
-- No policy at all: row-level security denies every row to the runtime role.

-- The only door. Keyed by the hash alone, it spends the link and records what
-- the tap means, and returns the purpose — or null for a link that is unknown,
-- used or expired, which the caller answers identically.
create or replace function app.redeem_continuity_link(p_token_hash text)
  returns text language plpgsql security definer
  set search_path = public, app, pg_temp as $$
declare
  l continuity_links%rowtype;
  v_member uuid;
begin
  update continuity_links
     set used_at = now()
   where token_hash = p_token_hash and used_at is null and expires_at > now()
  returning * into l;
  if not found then
    return null;
  end if;

  if l.purpose = 'check_in' then
    update inactivity_checks
       set last_check_in_at = now()
     where id = l.inactivity_check_id and user_id = l.user_id
    returning member_id into v_member;
    if v_member is null then
      return null;
    end if;
    -- "I'm here" is the owner's own word, so a request the sweep raised in
    -- their name is stopped, exactly as if they had pressed Stop.
    update emergency_requests
       set vetoed_at = now(), vetoed_by = l.user_id
     where subject_member_id = v_member and raised_by = 'inactivity'
       and vetoed_at is null and revoked_at is null and now() < access_expires_at;
    insert into activity_log (household_id, actor_user_id, action, entity_type, entity_id, diff)
    values (l.household_id, l.user_id, 'continuity.check_in', 'inactivity_check',
            l.inactivity_check_id, '{"via":"link"}'::jsonb);
  else
    if not exists (
      select 1 from emergency_contacts c
      join members m on m.id = c.trusted_member_id and m.deleted_at is null
      where c.id = l.emergency_contact_id and m.user_id = l.user_id) then
      return null;
    end if;
    insert into trusted_contact_confirmations (household_id, emergency_contact_id, confirmed_by, via)
    values (l.household_id, l.emergency_contact_id, l.user_id, 'link');
    insert into activity_log (household_id, actor_user_id, action, entity_type, entity_id, diff)
    values (l.household_id, l.user_id, 'continuity.reachable', 'emergency_contact',
            l.emergency_contact_id, '{"via":"link"}'::jsonb);
  end if;
  return l.purpose;
end $$;

-- The owner's "Ask now": issues a reachability link for a contact they named.
-- A definer function so the runtime role never holds a read on the table.
create or replace function app.issue_reachability_link(p_emergency_contact_id uuid, p_token_hash text,
                                                        p_expires_at timestamptz)
  returns uuid language plpgsql security definer
  set search_path = public, app, pg_temp as $$
declare
  v_household uuid;
  v_user uuid;
  v_id uuid;
begin
  select c.household_id, m.user_id into v_household, v_user
    from emergency_contacts c
    join members m on m.id = c.trusted_member_id and m.deleted_at is null
   where c.id = p_emergency_contact_id
     and app.guest_share_id() is null
     and c.member_id = any(app.current_member_ids(c.household_id));
  if v_household is null or v_user is null then
    return null;
  end if;
  insert into continuity_links (purpose, token_hash, household_id, user_id, emergency_contact_id, expires_at)
  values ('reachable', p_token_hash, v_household, v_user, p_emergency_contact_id, p_expires_at)
  returning id into v_id;
  return v_id;
end $$;

-- -----------------------------------------------------------------------------
-- Ask the key holder
-- -----------------------------------------------------------------------------
create table key_holder_asks (
  id               uuid primary key default gen_random_uuid(),
  household_id     uuid not null references households(id) on delete cascade,
  record_type      text not null
                     check (record_type in ('investment','liability','account','document','estate_document')),
  record_id        uuid not null,
  -- The record's title when asked. Never typed by anyone, so it cannot carry a
  -- sealed sentence by mistake.
  thing            text not null check (length(btrim(thing)) between 1 and 200),
  asked_by         uuid not null references users(id) on delete cascade,
  asked_member_id  uuid not null references members(id) on delete cascade,
  answer           text check (answer in ('yes', 'not_sure')),
  answered_at      timestamptz,
  created_at       timestamptz not null default now(),
  constraint answered_together check ((answer is null) = (answered_at is null))
);
create unique index key_holder_asks_one_open
  on key_holder_asks (record_type, record_id, asked_member_id, asked_by) where answer is null;
create index on key_holder_asks (household_id, asked_member_id);

alter table key_holder_asks enable row level security;

-- The person who asked, and the person asked. Nobody else learns who was asked.
create policy key_holder_asks_read on key_holder_asks for select
  using (app.guest_share_id() is null
         and app.is_household_member(household_id)
         and (asked_by = app.current_user_id()
           or asked_member_id = any(app.current_member_ids(household_id))));

-- About a record the asker can see, to someone else in the household.
create policy key_holder_asks_insert on key_holder_asks for insert
  with check (app.guest_share_id() is null
              and app.is_household_member(household_id)
              and asked_by = app.current_user_id()
              and not (asked_member_id = any(app.current_member_ids(household_id)))
              and exists (select 1 from members m
                          where m.id = asked_member_id and m.household_id = key_holder_asks.household_id
                            and m.deleted_at is null and m.user_id is not null)
              and app.linked_record_visible(record_type, record_id));

-- Only the person asked answers. Which columns they may change is limited by
-- the column grant below, so an answer cannot rewrite the question.
create policy key_holder_asks_answer on key_holder_asks for update
  using (app.guest_share_id() is null
         and asked_member_id = any(app.current_member_ids(household_id)))
  with check (asked_member_id = any(app.current_member_ids(household_id)));

create policy key_holder_asks_withdraw on key_holder_asks for delete
  using (app.guest_share_id() is null and asked_by = app.current_user_id());

-- -----------------------------------------------------------------------------
-- The access chain: first, second, third — sealed names, plaintext ticks
-- -----------------------------------------------------------------------------
create table access_chain_confirmations (
  household_id  uuid not null references households(id) on delete cascade,
  record_type   text not null
                  check (record_type in ('investment','liability','account','document','estate_document')),
  record_id     uuid not null,
  position      smallint not null check (position between 1 and 3),
  confirmed_by  uuid not null references users(id) on delete cascade,
  confirmed_at  timestamptz not null default now(),
  primary key (record_type, record_id, position)
);

create or replace function app.chain_field_key(p_position int)
  returns text language sql immutable as $$
  select case p_position when 1 then 'key_holder' when 2 then 'key_holder_2' when 3 then 'key_holder_3' end
$$;

alter table access_chain_confirmations enable row level security;

-- Exactly as visible as the sealed value it ticks (V22/V28).
create policy access_chain_confirmations_read on access_chain_confirmations for select
  using (app.is_household_member(household_id)
         and app.linked_record_visible(record_type, record_id));

-- Only the person who sealed that position's name vouches for it: nobody else
-- can know who it names.
create policy access_chain_confirmations_write on access_chain_confirmations for all
  using (app.guest_share_id() is null
         and exists (select 1 from sealed_values sv
                     where sv.record_type = access_chain_confirmations.record_type
                       and sv.record_id = access_chain_confirmations.record_id
                       and sv.household_id = access_chain_confirmations.household_id
                       and sv.field_key = app.chain_field_key(position)
                       and sv.sealed_by = app.current_user_id()))
  with check (app.guest_share_id() is null
              and confirmed_by = app.current_user_id()
              and exists (select 1 from sealed_values sv
                          where sv.record_type = access_chain_confirmations.record_type
                            and sv.record_id = access_chain_confirmations.record_id
                            and sv.household_id = access_chain_confirmations.household_id
                            and sv.field_key = app.chain_field_key(position)
                            and sv.sealed_by = app.current_user_id()));

-- A tick is about the name it was given for. Change or remove the name and the
-- tick goes with it; a confirmation of somebody who is no longer written there
-- would be a false comfort.
create or replace function app.chain_tick_follows_its_name() returns trigger
  language plpgsql security definer
  set search_path = public, app, pg_temp as $$
begin
  -- A passphrase change rewraps the content key and rewrites no value (docs/12
  -- §6), so a changed ciphertext is a name written again.
  if old.field_key in ('key_holder', 'key_holder_2', 'key_holder_3')
     and (tg_op = 'DELETE' or new.ciphertext is distinct from old.ciphertext) then
    delete from access_chain_confirmations
     where record_type = old.record_type and record_id = old.record_id
       and position = case old.field_key when 'key_holder' then 1 when 'key_holder_2' then 2 else 3 end;
  end if;
  return null;
end $$;

create trigger sealed_values_chain_tick
  after update or delete on sealed_values
  for each row execute function app.chain_tick_follows_its_name();

-- -----------------------------------------------------------------------------
-- Protection adequacy inputs — a person's own, for their own view
-- -----------------------------------------------------------------------------
create table protection_inputs (
  household_id          uuid not null references households(id) on delete cascade,
  user_id               uuid not null references users(id) on delete cascade,
  annual_expenses       numeric(16,2) check (annual_expenses is null
                                             or (annual_expenses > 0 and annual_expenses < 1000000000000)),
  dependant_member_ids  uuid[] not null default '{}'
                          check (cardinality(dependant_member_ids) <= 30),
  version               int not null default 1,
  created_at            timestamptz not null default now(),
  updated_at            timestamptz not null default now(),
  primary key (household_id, user_id)
);
create trigger protection_inputs_touch before insert or update on protection_inputs
  for each row execute function app.touch_row();

alter table protection_inputs enable row level security;

create policy protection_inputs_own on protection_inputs for all
  using (app.guest_share_id() is null
         and user_id = app.current_user_id()
         and app.is_household_member(household_id))
  with check (app.guest_share_id() is null
              and user_id = app.current_user_id()
              and app.is_household_member(household_id));

-- -----------------------------------------------------------------------------
-- Grants. R__grants gives new tables everything by default and runs after
-- every versioned migration, so the narrowing is repeated there; this block is
-- for a database migrated without the repeatable pass.
-- -----------------------------------------------------------------------------
do $$
begin
  if exists (select 1 from pg_roles where rolname = 'almira_app') then
    execute 'grant select, insert, update, delete on inactivity_checks to almira_app';
    execute 'grant select, insert on trusted_contact_confirmations to almira_app';
    execute 'revoke update, delete on trusted_contact_confirmations from almira_app';
    execute 'grant select, insert, update, delete on trusted_contact_asks to almira_app';
    execute 'revoke all on continuity_links from almira_app';
    execute 'grant select, insert, delete on key_holder_asks to almira_app';
    execute 'revoke update on key_holder_asks from almira_app';
    execute 'grant update (answer, answered_at) on key_holder_asks to almira_app';
    execute 'grant select, insert, update, delete on access_chain_confirmations to almira_app';
    execute 'grant select, insert, update, delete on protection_inputs to almira_app';
    execute 'grant execute on function app.redeem_continuity_link(text) to almira_app';
    execute 'grant execute on function app.issue_reachability_link(uuid, text, timestamptz) to almira_app';
    execute 'revoke execute on function app.member_present_since(uuid, timestamptz) from almira_app';
  end if;
end $$;

-- A function is executable by PUBLIC unless that is taken away.
revoke execute on function app.member_present_since(uuid, timestamptz) from public;
