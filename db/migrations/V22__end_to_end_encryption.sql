-- =============================================================================
-- V22 · Zero-knowledge mode: fields the server cannot read.
-- Refs: docs/05 §4 ("optional zero-knowledge mode"), docs/12
--
-- Everything else in this product is encrypted *by* the server with a key the
-- server can reach — which protects a stolen database and nothing else. This is
-- the other kind: the key is derived in the browser from a passphrase the
-- server never receives, and what lands here is ciphertext the server has no
-- means to open. A subpoena, a rogue operator and a full backup all yield the
-- same thing: bytes.
--
-- The honest trade-offs, which the UI states plainly rather than burying:
--   · no server-side search, OCR or sorting on a sealed field;
--   · no recovery. Forgetting the passphrase destroys the data, and that is
--     what "the server cannot read it" means when it is true.
--
-- The scheme itself is documented in docs/12 so the native app can implement
-- exactly the same one — a format only one client understands is a format that
-- loses data the first time someone switches devices.
-- =============================================================================

-- The content key, wrapped by a key derived from the passphrase. One per person
-- per household: a household can hold sealed fields from several members, each
-- unreadable to the others until shared deliberately (a later phase; for now a
-- sealed field is private to the person who sealed it).
create table e2e_keys (
  id             uuid primary key default gen_random_uuid(),
  household_id   uuid not null references households(id) on delete cascade,
  user_id        uuid not null references users(id) on delete cascade,
  -- Everything needed to derive the same wrapping key again, and nothing that
  -- helps anyone who does not have the passphrase.
  kdf            text not null default 'PBKDF2-SHA256',
  kdf_salt       text not null,
  iterations     int  not null check (iterations >= 100000),
  wrap_algorithm text not null default 'AES-GCM-256',
  wrapped_key    text not null,
  -- Bumped when the passphrase changes, so a client can tell that ciphertext
  -- written under an older key needs rewrapping.
  key_version    int  not null default 1,
  -- A short constant encrypted under the content key. Lets a client tell a
  -- wrong passphrase from a corrupt payload without trying to decrypt records.
  verifier       text not null,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  unique (household_id, user_id)
);
create trigger e2e_keys_touch before insert or update on e2e_keys
  for each row execute function app.touch_row();

-- One sealed field. The server stores it, serves it back, and cannot read it.
create table sealed_values (
  id           uuid primary key default gen_random_uuid(),
  household_id uuid not null references households(id) on delete cascade,
  record_type  text not null
                 check (record_type in ('investment','liability','account','member','estate_document')),
  record_id    uuid not null,
  field_key    text not null,
  -- version(1) | keyVersion(4) | iv(12) | ciphertext+tag, base64url. The client
  -- builds it; the server checks only that it is well-formed base64 of a
  -- plausible length, because anything more would require understanding it.
  ciphertext   text not null,
  algorithm    text not null default 'AES-GCM-256',
  key_version  int  not null default 1,
  sealed_by    uuid not null references users(id),
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  unique (record_type, record_id, field_key),
  constraint ciphertext_is_not_trivially_short check (length(ciphertext) >= 24)
);
create index on sealed_values (household_id);
create index on sealed_values (record_type, record_id);
create trigger sealed_values_touch before insert or update on sealed_values
  for each row execute function app.touch_row();

-- -----------------------------------------------------------------------------
-- Visibility. A sealed value is unreadable either way, but *which* records have
-- sealed fields, and how long they are, is itself information — so the rows
-- follow the visibility of the record they belong to, exactly like documents.
-- -----------------------------------------------------------------------------
alter table e2e_keys enable row level security;

create policy e2e_keys_read on e2e_keys for select
  using (user_id = app.current_user_id());

create policy e2e_keys_write on e2e_keys for all
  using (user_id = app.current_user_id() and app.guest_share_id() is null)
  with check (user_id = app.current_user_id()
              and app.is_household_member(household_id)
              and app.guest_share_id() is null);

alter table sealed_values enable row level security;

create policy sealed_values_read on sealed_values for select
  using (app.is_household_member(household_id)
         and app.linked_record_visible(record_type, record_id)
         and app.guest_scope_allows(record_type, record_id));

create policy sealed_values_write on sealed_values for all
  using (app.guest_share_id() is null
         and sealed_by = app.current_user_id()
         and app.linked_record_visible(record_type, record_id))
  with check (app.guest_share_id() is null
              and sealed_by = app.current_user_id()
              and app.can_write_household(household_id)
              and app.linked_record_visible(record_type, record_id));

-- The estate instruments can carry a sealed field too — a will's location is
-- exactly the sort of line someone wants unreadable to everyone but themselves.
create or replace function app.linked_record_visible(p_entity_type text, p_entity_id uuid)
  returns boolean language sql stable
  set search_path = public, app, pg_temp as $$
  select case p_entity_type
    when 'investment'      then exists (select 1 from investments      i where i.id = p_entity_id)
    when 'liability'       then exists (select 1 from liabilities      l where l.id = p_entity_id)
    when 'account'         then exists (select 1 from accounts         a where a.id = p_entity_id)
    when 'member'          then exists (select 1 from members          m where m.id = p_entity_id)
    when 'estate_document' then exists (select 1 from estate_documents e where e.id = p_entity_id)
    else false
  end
$$;
