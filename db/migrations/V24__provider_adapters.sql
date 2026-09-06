-- =============================================================================
-- V24 · The seams where outside services plug in.
-- Refs: docs/01 §11, docs/05 §8, docs/10 Phase 4, docs/13
--
-- Five things this product will eventually want from someone else: SMS that
-- actually arrives, email and push, DigiLocker, the Account Aggregator network,
-- and WhatsApp capture. Every one of them needs an account, a registration, or
-- a regulator's approval, and none of that can be done from a laptop.
--
-- So each is built as an adapter behind an interface with a **sandbox
-- implementation that really works** — it records what it would have sent, or
-- returns a fixture shaped exactly like the real payload — and the switch to
-- live is a credential and a property, not a rewrite. The tests run against the
-- sandbox, which means the wiring is proven now and only the transport is
-- unproven later.
--
-- What is stored here is the *connection*, never a provider password: an access
-- token is short-lived and encrypted, and a refresh token is only ever held
-- where the household can revoke it.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- First, a fix the new tables exposed.
--
-- app.touch_row() bumps `version` on every UPDATE, which assumed every table
-- carrying the trigger has that column. The tables added since — templates,
-- sealed values, provider connections — mostly do not, and the trigger only
-- fails on UPDATE, so an INSERT looked fine and the failure waited for the
-- second write. Re-sealing a zero-knowledge field would have hit it.
--
-- Guarded rather than dropped: optimistic concurrency is worth keeping where it
-- exists, and a table without a version column should simply not get one.
-- -----------------------------------------------------------------------------
create or replace function app.touch_row() returns trigger
  language plpgsql as $$
begin
  new.updated_at := now();
  if tg_op = 'UPDATE' and to_jsonb(new) ? 'version' then
    new.version := (to_jsonb(old) ->> 'version')::int + 1;
  end if;
  return new;
end $$;

create table provider_connections (
  id             uuid primary key default gen_random_uuid(),
  household_id   uuid not null references households(id) on delete cascade,
  provider       text not null
                   check (provider in ('digilocker','account_aggregator','whatsapp')),
  -- sandbox | live. Recorded per connection, so a household that connected in
  -- sandbox never has its fixtures mistaken for real data later.
  mode           text not null default 'sandbox' check (mode in ('sandbox','live')),
  status         text not null default 'pending'
                   check (status in ('pending','active','expired','revoked','failed')),
  -- The provider's own handle for this link: a consent id, a session id, a
  -- WhatsApp number. Never a credential.
  external_ref   text,
  -- Encrypted with the household DEK, like every other secret here (V9).
  access_token_enc bytea,
  expires_at     timestamptz,
  scope          text,
  last_synced_at timestamptz,
  detail         jsonb not null default '{}',
  created_by     uuid references users(id),
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  unique (household_id, provider)
);
create trigger provider_connections_touch before insert or update on provider_connections
  for each row execute function app.touch_row();

alter table provider_connections enable row level security;

create policy provider_connections_read on provider_connections for select
  using (app.is_household_member(household_id));

create policy provider_connections_write on provider_connections for all
  using (app.can_administer_household(household_id))
  with check (app.can_administer_household(household_id));

-- -----------------------------------------------------------------------------
-- What was sent, or would have been.
--
-- Notifications are a logging stand-in until a provider exists, which is the
-- right call — but "we logged it" is unverifiable. Recording every outbound
-- message means the in-app list works today, the tests can assert delivery, and
-- swapping in a real channel changes where a row goes, not whether it exists.
-- -----------------------------------------------------------------------------
create table outbound_messages (
  id           uuid primary key default gen_random_uuid(),
  household_id uuid references households(id) on delete cascade,
  user_id      uuid references users(id) on delete cascade,
  channel      text not null check (channel in ('in_app','sms','email','push','whatsapp')),
  provider     text not null,
  template     text not null,
  -- Deliberately not the body. A notification can carry an amount or a policy
  -- name, and this table is the least protected thing we write (docs/05 §5).
  title        text,
  recipient_hint text,
  status       text not null default 'queued'
                 check (status in ('queued','sent','failed','skipped')),
  failure      text,
  created_at   timestamptz not null default now()
);
create index on outbound_messages (user_id, created_at desc);
create index on outbound_messages (channel, created_at desc);

alter table outbound_messages enable row level security;

-- Your own messages, and nobody else's — the log of what someone was told is as
-- personal as what it was about.
create policy outbound_messages_read on outbound_messages for select
  using (user_id = app.current_user_id());

-- The recorder runs from a background worker as often as from a request, and a
-- worker has no signed-in user to satisfy a policy with. Definer rights, one
-- narrow function, and no read path: writing a row here can never be turned
-- into reading one.
create or replace function app.record_outbound_message(
    p_household_id uuid, p_user_id uuid, p_channel text, p_provider text,
    p_template text, p_title text, p_recipient_hint text, p_status text, p_failure text)
  returns void language sql security definer
  set search_path = public, app, pg_temp as $$
  insert into outbound_messages (household_id, user_id, channel, provider, template,
                                 title, recipient_hint, status, failure)
  values (p_household_id, p_user_id, p_channel, p_provider, p_template,
          p_title, p_recipient_hint, p_status, p_failure);
$$;
