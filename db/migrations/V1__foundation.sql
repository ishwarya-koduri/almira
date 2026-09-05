-- =============================================================================
-- V1 · Foundation: extensions, helper schema, identity, tenancy.
-- Refs: docs/04-data-model.md §2, docs/05-security-and-privacy.md §2
-- =============================================================================

create extension if not exists pgcrypto;
create extension if not exists citext;
create extension if not exists pg_trgm;

-- `app` holds request-context helpers and the security-definer functions that
-- back Row-Level Security. Keeping them out of `public` means an RLS policy can
-- never be shadowed by a user-created object.
create schema if not exists app;

-- -----------------------------------------------------------------------------
-- Request context
-- -----------------------------------------------------------------------------
-- The backend sets `app.user_id` as a transaction-local GUC on every request
-- (see RlsConnectionInterceptor). Unauthenticated / system transactions leave it
-- unset, which makes every RLS predicate evaluate to NULL -> deny.
create or replace function app.current_user_id() returns uuid
  language sql stable
  as $$ select nullif(current_setting('app.user_id', true), '')::uuid $$;

comment on function app.current_user_id() is
  'Authenticated user for this transaction, or NULL. Set via set_config(''app.user_id'', ..., true).';

-- -----------------------------------------------------------------------------
-- Shared triggers
-- -----------------------------------------------------------------------------
-- Maintains updated_at and the optimistic-concurrency `version` counter.
-- Writers use `WHERE id = ? AND version = ?`; a stale write affects 0 rows and
-- the service layer turns that into a 409 with the current state.
create or replace function app.touch_row() returns trigger
  language plpgsql as $$
begin
  new.updated_at := now();
  if tg_op = 'UPDATE' then
    new.version := old.version + 1;
  end if;
  return new;
end $$;

-- Age helper. Deliberately NOT a generated column: minority depends on
-- current_date, which is not immutable, so it must be evaluated at read time.
create or replace function app.is_minor(dob date) returns boolean
  language sql stable
  as $$ select dob is not null and dob > (current_date - interval '18 years') $$;

-- -----------------------------------------------------------------------------
-- users — one row per real person who can sign in.
-- No bank credentials, ever (docs/05 §5). Phone is the primary identifier
-- because login is phone-OTP (docs/09 §9).
-- -----------------------------------------------------------------------------
create table users (
  id                uuid primary key default gen_random_uuid(),
  phone             text unique,                   -- E.164, e.g. +919876543210
  email             citext unique,
  full_name         text,
  auth_provider     text not null default 'otp'
                      check (auth_provider in ('otp','google','apple','email')),
  locale            text not null default 'en-IN',
  currency_pref     text not null default 'INR',
  -- Per-user default visibility for newly captured records. Wins over the
  -- household default (docs/05 §3.2).
  default_visibility text not null default 'private'
                      check (default_visibility in ('private','household')),
  mfa_enabled       boolean not null default false,
  mfa_secret_enc    bytea,                          -- envelope-encrypted, never plaintext
  status            text not null default 'active'
                      check (status in ('active','suspended','deleted')),
  last_login_at     timestamptz,
  deleted_at        timestamptz,
  version           int not null default 1,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  constraint users_need_an_identifier check (phone is not null or email is not null)
);
create trigger users_touch before insert or update on users
  for each row execute function app.touch_row();

-- -----------------------------------------------------------------------------
-- Sessions & rotating refresh tokens (docs/05 §2, docs/09 §9.2)
-- Tokens are stored hashed; a leaked dump yields nothing usable.
-- -----------------------------------------------------------------------------
create table user_sessions (
  id            uuid primary key default gen_random_uuid(),
  user_id       uuid not null references users(id) on delete cascade,
  device_name   text,
  user_agent    text,
  ip            inet,
  created_at    timestamptz not null default now(),
  last_used_at  timestamptz not null default now(),
  expires_at    timestamptz not null,
  revoked_at    timestamptz,
  revoked_reason text
);
create index on user_sessions (user_id) where revoked_at is null;
create index on user_sessions (expires_at);

create table refresh_tokens (
  id           uuid primary key default gen_random_uuid(),
  session_id   uuid not null references user_sessions(id) on delete cascade,
  token_hash   text not null unique,           -- sha256 of the opaque token
  issued_at    timestamptz not null default now(),
  expires_at   timestamptz not null,
  -- Rotation: the token that replaced this one. Presenting a token that
  -- already has a successor is *reuse* -> the whole session is revoked.
  rotated_to   uuid references refresh_tokens(id),
  used_at      timestamptz,
  revoked_at   timestamptz
);
create index on refresh_tokens (session_id);
create index on refresh_tokens (expires_at);

-- -----------------------------------------------------------------------------
-- households — the tenancy root. Nearly every table carries household_id.
-- -----------------------------------------------------------------------------
create table households (
  id                 uuid primary key default gen_random_uuid(),
  name               text not null,
  base_currency      text not null default 'INR',
  -- The household's culture. A per-user default (users.default_visibility)
  -- overrides it for that user's own captures (docs/05 §3.2).
  default_visibility text not null default 'private'
                       check (default_visibility in ('private','household')),
  plan               text not null default 'free',
  created_by         uuid references users(id),
  deleted_at         timestamptz,
  version            int not null default 1,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now()
);
create index on households (created_by);
create trigger households_touch before insert or update on households
  for each row execute function app.touch_row();

-- -----------------------------------------------------------------------------
-- members — every *person* the household tracks, whether or not they log in.
-- A "managed" member (child, elderly parent) has user_id = NULL.
-- -----------------------------------------------------------------------------
create table members (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references households(id) on delete cascade,
  user_id       uuid references users(id) on delete set null,
  display_name  text not null,
  relationship  text,                            -- self | spouse | child | parent | other
  date_of_birth date,
  avatar_url    text,
  notes         text,
  deleted_at    timestamptz,
  version       int not null default 1,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);
create index on members (household_id) where deleted_at is null;
create unique index members_one_row_per_user_per_household
  on members (household_id, user_id) where user_id is not null and deleted_at is null;
create trigger members_touch before insert or update on members
  for each row execute function app.touch_row();

comment on table members is
  'A person tracked by the household. user_id NULL = managed member (no login). '
  'One real person is represented once per household (docs/07 §1).';

-- -----------------------------------------------------------------------------
-- household_memberships — CAPABILITIES ONLY.
-- Role never grants sight of another member''s private records (docs/05 §3).
-- -----------------------------------------------------------------------------
create table household_memberships (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references households(id) on delete cascade,
  user_id       uuid not null references users(id) on delete cascade,
  role          text not null
                  check (role in ('owner','admin','editor','viewer','restricted')),
  status        text not null default 'active'
                  check (status in ('active','invited','suspended','left')),
  joined_at     timestamptz not null default now(),
  version       int not null default 1,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  unique (household_id, user_id)
);
create index on household_memberships (user_id) where status = 'active';
create index on household_memberships (household_id, role);
create trigger household_memberships_touch before insert or update on household_memberships
  for each row execute function app.touch_row();

comment on table household_memberships is
  'Role = what you may DO. Visibility = what you may SEE. They are separate axes; '
  'no role, not even owner, can read another member''s private records.';

-- -----------------------------------------------------------------------------
-- invitations — bring an adult into the household with their own login.
-- -----------------------------------------------------------------------------
create table invitations (
  id             uuid primary key default gen_random_uuid(),
  household_id   uuid not null references households(id) on delete cascade,
  -- Optional: the managed member this invite will "claim" (merge, not duplicate).
  member_id      uuid references members(id) on delete set null,
  phone          text,
  email          citext,
  role           text not null default 'editor'
                   check (role in ('owner','admin','editor','viewer','restricted')),
  token_hash     text not null unique,
  invited_by     uuid references users(id),
  expires_at     timestamptz not null,
  accepted_at    timestamptz,
  revoked_at     timestamptz,
  created_at     timestamptz not null default now(),
  constraint invitations_need_a_channel check (phone is not null or email is not null)
);
create index on invitations (household_id);

-- -----------------------------------------------------------------------------
-- activity_log — append-only audit trail (docs/05 §9).
-- -----------------------------------------------------------------------------
create table activity_log (
  id             bigserial primary key,
  household_id   uuid references households(id) on delete cascade,
  actor_user_id  uuid references users(id) on delete set null,
  action         text not null,                 -- e.g. investment.create, visibility.change
  entity_type    text,
  entity_id      uuid,
  diff           jsonb,
  ip             inet,
  user_agent     text,
  created_at     timestamptz not null default now()
);
create index on activity_log (household_id, created_at desc);
create index on activity_log (entity_type, entity_id);
