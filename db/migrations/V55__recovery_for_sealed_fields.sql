-- =============================================================================
-- V55 · Recovery for sealed fields, without the server learning anything.
-- Refs: docs/12 §10, docs/16, docs/20 §1.2, docs/22 §4
--
-- Until now a forgotten passphrase destroyed every sealed field, and the family
-- could never read "where the will is" unless someone had handed them the
-- passphrase. Both are the same missing thing: a second way to reach the
-- content key that is not the passphrase.
--
-- The scheme stays zero-knowledge. The content key never leaves a client. What
-- lands here is up to two more wrapped copies of it, each under a key derived
-- on the device from a secret the server never receives:
--
--   · a recovery key — 168 random bits, printed as a code on a sheet;
--   · recovery shares — a different 168-bit secret, split 2-of-3 on the device
--     with Shamir's scheme over GF(256), each share printed for one person.
--
-- The server stores the wrap, a verifier, the salt and a key id. It never
-- stores the code, a share, or anything derived from either that could open
-- the wrap (docs/12 §10).
--
-- Two tables, because two different people need two different things:
--
--   e2e_recovery_wraps  the wrapped copy itself. Readable by its owner, and by
--                       the person holding an open emergency window on them —
--                       the heir with the sheet needs the wrap to use it.
--   e2e_recovery_slots  who holds what, and when it was last practised. Plain
--                       text on purpose: "Amma holds a recovery share" is the
--                       sentence an heir reads when a sealed line will not
--                       open. Readable by anyone in the household who can see
--                       a value that person sealed, and never by a guest.
--
-- No grant here: R__grants sets default privileges, so both tables are
-- reachable by almira_app and every row still passes the policies below.
-- =============================================================================

-- An id for the content key, so a copy made from one key can never be left
-- standing silently after a client replaces the key with another. It is
-- HMAC-SHA256(contentKey, "almira content key id v1"), first 16 bytes: a PRF
-- output, which says nothing about a 256-bit random key. Null on rows written
-- before this migration, and by a client that does not send it.
alter table e2e_keys add column content_key_id text
  check (content_key_id is null or length(content_key_id) = 22);

create table e2e_recovery_wraps (
  id             uuid primary key default gen_random_uuid(),
  household_id   uuid not null,
  user_id        uuid not null,
  kind           text not null check (kind in ('recovery_key', 'recovery_shares')),
  -- HKDF over a high-entropy secret, not PBKDF2: stretching exists to slow the
  -- guessing of something a person chose, and nobody chose these.
  kdf            text not null default 'HKDF-SHA256' check (kdf = 'HKDF-SHA256'),
  kdf_salt       text not null,
  wrap_algorithm text not null default 'AES-GCM-256' check (wrap_algorithm = 'AES-GCM-256'),
  -- The same envelope as everywhere else (docs/12 §3); the server checks shape.
  wrapped_key    text not null check (length(wrapped_key) >= 44),
  verifier       text not null check (length(verifier) >= 44),
  content_key_id text not null check (length(content_key_id) = 22),
  -- Filled by app.ciphertext_digests(), below.
  wrapped_key_sha256 bytea not null,
  verifier_sha256    bytea not null,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  unique (household_id, user_id, kind),
  -- A copy of a key cannot outlive the key it copies.
  foreign key (household_id, user_id) references e2e_keys (household_id, user_id) on delete cascade
);
create trigger e2e_recovery_wraps_touch before insert or update on e2e_recovery_wraps
  for each row execute function app.touch_row();

create table e2e_recovery_slots (
  wrap_id       uuid primary key references e2e_recovery_wraps(id) on delete cascade,
  household_id  uuid not null references households(id) on delete cascade,
  user_id       uuid not null references users(id) on delete cascade,
  kind          text not null check (kind in ('recovery_key', 'recovery_shares')),
  threshold     smallint,
  share_count   smallint,
  -- Roles or relationships, as the family says them: "Amma", "our lawyer".
  -- Not sealed, and the interface says so, so it must never say where anything is.
  holders       text[] not null default '{}',
  practiced_at  timestamptz,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  constraint recovery_shape check (
    (kind = 'recovery_key'    and threshold is null and share_count is null and cardinality(holders) <= 1)
    or (kind = 'recovery_shares' and threshold = 2 and share_count = 3 and cardinality(holders) <= 3)),
  constraint recovery_holders_are_short check (
    length(array_to_string(holders, '')) <= 180
    and array_position(holders, '') is null)
);
create index on e2e_recovery_slots (household_id, user_id);
create trigger e2e_recovery_slots_touch before update on e2e_recovery_slots
  for each row execute function app.touch_row();

-- -----------------------------------------------------------------------------
-- The stored digest (V31) covers the copies too. A recovery sheet whose wrap a
-- restore damaged opens nothing, and nobody would know until the day it is
-- needed — which is exactly the failure V31 exists to surface early.
-- -----------------------------------------------------------------------------
create or replace function app.ciphertext_digests() returns trigger
  language plpgsql as $$
begin
  if tg_table_name = 'sealed_values' then
    new.ciphertext_sha256 := sha256(convert_to(new.ciphertext, 'UTF8'));
  elsif tg_table_name in ('e2e_keys', 'e2e_recovery_wraps') then
    new.wrapped_key_sha256 := sha256(convert_to(new.wrapped_key, 'UTF8'));
    new.verifier_sha256    := sha256(convert_to(new.verifier, 'UTF8'));
  end if;
  return new;
end $$;

create trigger e2e_recovery_wraps_digest before insert or update of wrapped_key, verifier on e2e_recovery_wraps
  for each row execute function app.ciphertext_digests();

-- -----------------------------------------------------------------------------
-- Whether the caller holds an open emergency window on this particular person.
--
-- The same conditions as app.emergency_reveals (V25) — asked, not vetoed, not
-- revoked, the wait elapsed, the window not expired, and silence from the
-- subject since the request — narrowed to one subject. A window on Amma is not
-- a way to reach Nanna's recovery copy.
-- -----------------------------------------------------------------------------
create or replace function app.emergency_open_on_user(p_household_id uuid, p_user_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (
    select 1
    from emergency_requests r
    join members m on m.id = r.subject_member_id
    where r.household_id = p_household_id
      and m.user_id = p_user_id
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

alter table e2e_recovery_wraps enable row level security;

create policy e2e_recovery_wraps_read on e2e_recovery_wraps for select
  using (app.guest_share_id() is null
         and app.is_household_member(household_id)
         and (user_id = app.current_user_id()
              or app.emergency_open_on_user(household_id, user_id)));

-- Only the owner writes their own copies. An heir reads one; nobody else may
-- replace it, which would quietly swap the sheet in the drawer for a dud.
create policy e2e_recovery_wraps_write on e2e_recovery_wraps for all
  using (user_id = app.current_user_id() and app.guest_share_id() is null)
  with check (user_id = app.current_user_id()
              and app.is_household_member(household_id)
              and app.guest_share_id() is null);

alter table e2e_recovery_slots enable row level security;

-- The subquery on sealed_values runs under the caller's own row-level
-- security, so "can see a value that person sealed" means exactly that: a
-- member who cannot see any record carrying one of their sealed values does
-- not learn who holds their shares.
create policy e2e_recovery_slots_read on e2e_recovery_slots for select
  using (app.guest_share_id() is null
         and app.is_household_member(household_id)
         and (user_id = app.current_user_id()
              or app.emergency_open_on_user(household_id, user_id)
              or exists (select 1 from sealed_values sv
                         where sv.household_id = e2e_recovery_slots.household_id
                           and sv.sealed_by = e2e_recovery_slots.user_id)));

create policy e2e_recovery_slots_write on e2e_recovery_slots for all
  using (user_id = app.current_user_id() and app.guest_share_id() is null)
  with check (user_id = app.current_user_id()
              and app.guest_share_id() is null
              and exists (select 1 from e2e_recovery_wraps w
                          where w.id = e2e_recovery_slots.wrap_id
                            and w.user_id = app.current_user_id()
                            and w.household_id = e2e_recovery_slots.household_id
                            and w.kind = e2e_recovery_slots.kind));
