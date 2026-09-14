-- =============================================================================
-- V50 · A second factor: an authenticator app, recovery codes and passkeys.
-- Refs: docs/05 §2, docs/15 §5, docs/16 ID-6 to ID-9
--
-- Sign-in was one one-time code. Whoever receives the texts for a number
-- received the account — including a stranger a disconnected number was
-- reassigned to. An account with a second factor now needs it after the code
-- (auth/SecondFactorService.kt).
--
-- Every table here is about one person and nobody else, so every policy is the
-- same sentence: a row is reachable only by the user it belongs to. During the
-- second step of a sign-in there is no session yet; the service binds the
-- person the first factor proved to that one transaction (set_config is_local),
-- exactly as the request filter does for a verified token, and nothing wider.
--
-- users.mfa_enabled and users.mfa_secret_enc (V1) were declared and never
-- written. `users` has no row-level security, because sign-in reads it before
-- anyone is known, which makes it the wrong home for a secret. They are dropped
-- rather than left looking like the place a second factor lives
-- (docs/19 "POSSIBLE GAPS").
-- =============================================================================

alter table users drop column mfa_enabled;
alter table users drop column mfa_secret_enc;

-- -----------------------------------------------------------------------------
-- An authenticator app (RFC 6238). One per person.
-- -----------------------------------------------------------------------------
create table user_totp_factors (
  user_id        uuid primary key references users(id) on delete cascade,
  -- Envelope-encrypted under a data key of its own, wrapped by the KEK
  -- (crypto/UserSecretCipher.kt). Never plaintext, never logged.
  secret_enc     bytea not null,
  kek_id         text not null,
  -- Null while the person has scanned the code but not yet typed one back.
  -- An unconfirmed secret is not a factor: it is never asked for at sign-in.
  confirmed_at   timestamptz,
  -- The RFC 6238 time step of the last code accepted. A code is accepted only
  -- for a later step, so one seen over a shoulder cannot be used again.
  last_used_step bigint,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now()
);
create trigger user_totp_factors_touch before update on user_totp_factors
  for each row execute function app.touch_row();

-- -----------------------------------------------------------------------------
-- Recovery codes: for a lost phone. Each works once.
-- -----------------------------------------------------------------------------
create table user_recovery_codes (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null references users(id) on delete cascade,
  -- PBKDF2-HMAC-SHA256 over the code with this row's salt. The code itself is
  -- shown once, when it is made, and stored nowhere.
  salt       bytea not null,
  code_hash  bytea not null,
  created_at timestamptz not null default now(),
  used_at    timestamptz
);
create index on user_recovery_codes (user_id) where used_at is null;

-- -----------------------------------------------------------------------------
-- Passkeys (WebAuthn). Many per person: one per phone or laptop.
-- -----------------------------------------------------------------------------
create table user_passkeys (
  id               uuid primary key default gen_random_uuid(),
  user_id          uuid not null references users(id) on delete cascade,
  credential_id    bytea not null unique,
  -- COSE public key. Public by nature; nothing here signs anything.
  public_key_cose  bytea not null,
  signature_count  bigint not null default 0 check (signature_count >= 0),
  name             text not null check (length(name) between 1 and 60),
  created_at       timestamptz not null default now(),
  last_used_at     timestamptz
);
create index on user_passkeys (user_id);

-- -----------------------------------------------------------------------------
-- Row-level security: the person, and only the person.
-- -----------------------------------------------------------------------------
alter table user_totp_factors   enable row level security;
alter table user_recovery_codes enable row level security;
alter table user_passkeys       enable row level security;

create policy user_totp_factors_own on user_totp_factors for all
  using (user_id = app.current_user_id())
  with check (user_id = app.current_user_id());

create policy user_recovery_codes_own on user_recovery_codes for all
  using (user_id = app.current_user_id())
  with check (user_id = app.current_user_id());

create policy user_passkeys_own on user_passkeys for all
  using (user_id = app.current_user_id())
  with check (user_id = app.current_user_id());

-- Grants: R__grants gives almira_app select/insert/update/delete on every table
-- in public, re-run because this migration changes nothing it names. Explicit
-- here as well, so the grant does not depend on a repeatable migration's
-- checksum having moved.
do $$
begin
  if exists (select 1 from pg_roles where rolname = 'almira_app') then
    execute 'grant select, insert, update, delete on user_totp_factors, user_recovery_codes, user_passkeys to almira_app';
  end if;
end $$;
