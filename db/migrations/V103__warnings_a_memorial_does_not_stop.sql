-- =============================================================================
-- V103 · The warnings a memorial does not stop.
-- Refs: V40 (the stop), V45 (security messages are never suppressed), docs/13
--
-- V40 stopped every message to someone marked as passed away except the one
-- that tells them so. But marking needs no proof from an admin, and the stop
-- then also swallowed the warnings a living person needs in order to say no:
-- an emergency-access request against them, a household being taken over, being
-- asked to leave, a sign-in or a changed factor on their account. Someone who
-- marked them could then act on them without their hearing about it.
--
-- So the exemption is a closed list, checked where every message is written.
-- It holds only warnings about something being done to the person or their
-- account. Reminders, digests, and news such as being named someone's trusted
-- contact still stop.
-- =============================================================================

create or replace function app.never_stopped_by_memorial(p_template text)
  returns boolean language sql immutable
  set search_path = public, app, pg_temp as $$
  select p_template in (
    'lifecycle.memorial.marked',
    -- Someone is asking to see what they marked for the family.
    'emergency.requested',
    'emergency.check_in',
    -- Their household, or their place in it, is being taken or ended.
    'lifecycle.successor.claimed',
    'lifecycle.departure.asked',
    -- A takeover of the account itself (auth/AccountNotices.kt).
    'auth.new_sign_in',
    'auth.phone_changed',
    'auth.authenticator_added',
    'auth.authenticator_removed',
    'auth.passkey_added',
    'auth.passkey_removed',
    'auth.recovery_codes_replaced',
    'auth.recovery_code_used'
  )
$$;

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
  if not app.never_stopped_by_memorial(p_template)
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
  if not app.never_stopped_by_memorial(p_template)
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
