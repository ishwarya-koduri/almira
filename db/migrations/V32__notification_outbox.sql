-- =============================================================================
-- V32 · Notifications go out from an outbox, not from the request that caused them.
-- Refs: docs/13 "Interactive and background", docs/known-issues.md 21
--
-- Until now a reminder, a still-true nudge or an emergency-access notice called
-- every channel's provider inline — inside the request or the sweep — and wrote
-- the outcome afterwards. A provider that hung held the request for the whole
-- retry budget (about 31 seconds for SMS at the defaults), and a crash between
-- "the provider accepted it" and "we wrote that down" left nothing to stop the
-- next attempt sending it again.
--
-- Now the request writes a `queued` row per channel, with a unique idempotency
-- key per logical message, and a background worker sends it
-- (provider/NotificationOutbox.kt). The in-app row is still written `sent`
-- straight away: it is the database, not a provider.
--
-- Nothing existing is rewritten. Earlier rows have no idempotency key, and the
-- worker only ever claims rows that have one, so no historical row is sent.
-- =============================================================================

alter table outbound_messages
  -- One per logical message per channel. The worker passes it to the adapter,
  -- and a live adapter passes it to a provider that honours one.
  add column idempotency_key text,
  -- The worker's lease: which run claimed the row, and until when. A row whose
  -- lease has run out was claimed by a worker that stopped.
  add column claim_token     uuid,
  add column claimed_until   timestamptz,
  -- Set, and committed, BEFORE the provider is called. A queued row that has it
  -- is a row whose send may have happened: a crash sits between the call and
  -- the record. What the worker does with such a row depends on the channel
  -- (at-most-once or at-least-once — see docs/13).
  add column send_started_at timestamptz,
  add column finished_at     timestamptz;

create unique index outbound_messages_idempotency_key
  on outbound_messages (idempotency_key) where idempotency_key is not null;

create index outbound_messages_queued
  on outbound_messages (created_at) where status = 'queued' and idempotency_key is not null;

-- A queued row has made no attempt yet. The old constraint required at least
-- one, which was true only while every row was written after its send.
alter table outbound_messages drop constraint outbound_messages_attempts_check;
alter table outbound_messages add constraint outbound_messages_attempts_check check (attempts >= 0);

-- -----------------------------------------------------------------------------
-- The body, only while it is on its way.
--
-- outbound_messages has never held a body — it is readable by the person the
-- message was for, and it is the least protected thing written here — and it
-- still does not. The worker needs the words to send, so they wait in their
-- own table, which the runtime role cannot read at all (row-level security on,
-- no policy), and are deleted when the message reaches `sent`, `failed` or
-- `skipped`.
-- -----------------------------------------------------------------------------
create table outbound_message_bodies (
  message_id uuid primary key references outbound_messages(id) on delete cascade,
  body       text not null
);

alter table outbound_message_bodies enable row level security;
-- Deliberately no policy: nothing but the owner connection (the worker) and the
-- definer function below touches it.

-- Writes a queued message and its body, or nothing if the key already exists.
-- Returns the new row's id, or null when it was a duplicate. Definer rights for
-- the same reason as app.record_outbound_message: it is called from requests
-- and from sweeps, and neither has a user a write policy could name.
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
