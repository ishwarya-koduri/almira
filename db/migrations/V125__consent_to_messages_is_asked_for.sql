-- =============================================================================
-- V125 · Consent to messages is asked for, never assumed.
-- Refs: V45 consent_events, V103 app.never_stopped_by_memorial,
--       V107/V108 app.enqueue_outbound_message, provider/MessageTemplates.kt,
--       docs/23 "What you agree to", docs/13 "Pacing",
--       docs/known-issues.md "Consent to messages is assumed until someone
--       withdraws it" (closed by this migration)
--
-- The owner's decision: "Message consent: opt-in, not opt-out. Ask at the
-- moment the first reminder would be useful. Do not inherit consent from people
-- who were never asked."
--
-- Until now a person with no `messages` event at all was treated as having
-- agreed, and only a withdrawal stopped an email or a text. From here:
--
--  * **No event is no.** An email, SMS, WhatsApp message or push that is not an
--    essential account or security notice is queued only for someone whose
--    latest `messages` event is `given`, and only on a channel that event names.
--    Nobody is migrated into consent: this file records nothing for anyone.
--    The in-app copy is written as before, whatever the answer.
--
--  * **Which messages are essential is one list, here.** app.message_is_essential
--    answers it for the queueing below, for the worker's re-check before a send,
--    and for pacing's "already told today?" count, so the question "may this go
--    without consent?" and "may this skip quiet hours?" can never disagree.
--    MessageTemplates.ESSENTIAL_TEMPLATES is the same list for the wording, and
--    EssentialMessagesTest fails if the two differ. A template on neither list
--    is not essential: a new kind of message needs consent until someone decides
--    otherwise, in both places, in review.
--
--  * **What a yes covers is recorded with it.** A `given` event names the
--    channels the person ticked and where they were asked (Settings, or at the
--    moment a reminder would first help). A `given` event from before this
--    migration names no channels; it was a tap on "Give" beside "Reminders by
--    email or text", so it covers email and SMS and nothing added since.
--
--  * **"Not now" is remembered, so it is not asked again for 90 days.**
--    messages_consent_asks keeps only when the person last said it. It is not a
--    consent record — saying "not now" is not a decision to keep — and it never
--    makes a message go.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- What a yes covers
-- -----------------------------------------------------------------------------
alter table consent_events add column channels text[];
alter table consent_events add column asked_in text;

alter table consent_events add constraint consent_events_channels_valid check (
  channels is null
  or (purpose = 'messages' and action = 'given'
      and cardinality(channels) between 1 and 4
      and channels <@ array['email', 'sms', 'whatsapp', 'push']::text[]));

alter table consent_events add constraint consent_events_asked_in_valid check (
  asked_in is null or asked_in in ('settings', 'in_context'));

-- -----------------------------------------------------------------------------
-- The essential notices: the one list
-- -----------------------------------------------------------------------------
-- A person needs these to protect their account, or to stop something being
-- done to them or in their name, so they are not under consent to messages and
-- are not held by quiet hours or the daily limit. Everything else is.
create or replace function app.message_is_essential(p_template text)
  returns boolean language sql immutable
  set search_path = public, app, pg_temp as $$
  select coalesce(p_template in (
    -- A sign-in code by email (auth/EmailOtpSender.kt). Sent at sign-in, not
    -- through the outbox, and listed so the classification is complete.
    'otp_email',
    -- A takeover of the account itself (auth/AccountNotices.kt).
    'auth.new_sign_in',
    'auth.phone_changed',
    'auth.authenticator_added',
    'auth.authenticator_removed',
    'auth.passkey_added',
    'auth.passkey_removed',
    'auth.recovery_codes_replaced',
    'auth.recovery_code_used',
    -- Emergency access: the question before it begins, the request against
    -- you (your chance to veto), a request raised in your name, and the veto.
    'emergency.check_in',
    'emergency.requested',
    'emergency.raised',
    'emergency.vetoed',
    -- Your account or your place in a household is being ended or taken.
    'lifecycle.closure.requested',
    'lifecycle.closure.cancelled',
    'lifecycle.memorial.marked',
    'lifecycle.successor.claimed',
    'lifecycle.departure.asked'
  ), false)
$$;

-- -----------------------------------------------------------------------------
-- Consent, asked the right way round
-- -----------------------------------------------------------------------------
-- True only when the person's latest `messages` event is `given` and covers
-- this channel. No event, a withdrawal, or a yes for other channels is false.
-- A definer function about any user id: not the runtime role's to ask (R__grants).
create or replace function app.messages_consent_given(p_user_id uuid, p_channel text)
  returns boolean language sql stable security definer
  set search_path = public, pg_temp as $$
  select coalesce((
    select e.action = 'given'
           and p_channel = any(coalesce(e.channels, array['email', 'sms']::text[]))
      from consent_events e
     where e.user_id = p_user_id and e.purpose = 'messages'
     order by e.seq desc
     limit 1
  ), false)
$$;

revoke execute on function app.messages_consent_given(uuid, text) from public;
do $$
begin
  if exists (select 1 from pg_roles where rolname = 'almira_app') then
    execute 'revoke execute on function app.messages_consent_given(uuid, text) from almira_app';
  end if;
end $$;

create or replace function app.enqueue_outbound_message(
    p_household_id uuid, p_user_id uuid, p_channel text,
    p_template text, p_title text, p_body text, p_idempotency_key text,
    p_address text default null)
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
  if p_address is not null and not (p_channel = 'sms' and p_template = 'auth.phone_changed') then
    raise exception 'only a phone-change notice by SMS is sent to a number other than the account''s';
  end if;
  if not app.never_stopped_by_memorial(p_template)
     and app.notifications_stopped(p_user_id, p_household_id) then
    return null;
  end if;
  -- Before anything is written: a message nobody agreed to is never queued,
  -- not queued and then skipped.
  if not app.message_is_essential(p_template)
     and not app.messages_consent_given(p_user_id, p_channel) then
    return null;
  end if;
  insert into outbound_messages (household_id, user_id, channel, provider, template,
                                 title, status, attempts, idempotency_key)
  values (p_household_id, p_user_id, p_channel, 'unknown', p_template,
          p_title, 'queued', 0, p_idempotency_key)
  on conflict (idempotency_key) where idempotency_key is not null do nothing
  returning id into v_id;
  if v_id is not null then
    insert into outbound_message_bodies (message_id, body, address)
    values (v_id, coalesce(p_body, ''), p_address);
  end if;
  return v_id;
end $$;

-- The old question had the default the wrong way round; nothing asks it now.
drop function app.messages_consent_withdrawn(uuid);

-- -----------------------------------------------------------------------------
-- "Not now"
-- -----------------------------------------------------------------------------
create table messages_consent_asks (
  user_id    uuid primary key references users(id) on delete cascade,
  not_now_at timestamptz not null default now()
);

alter table messages_consent_asks enable row level security;

-- Your own, and only yours. No delete policy: the row goes with the account.
create policy messages_consent_asks_read on messages_consent_asks for select
  using (user_id = app.current_user_id());

create policy messages_consent_asks_insert on messages_consent_asks for insert
  with check (user_id = app.current_user_id());

create policy messages_consent_asks_update on messages_consent_asks for update
  using (user_id = app.current_user_id())
  with check (user_id = app.current_user_id());
