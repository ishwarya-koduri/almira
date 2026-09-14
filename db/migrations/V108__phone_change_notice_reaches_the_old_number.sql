-- =============================================================================
-- V108 · "The phone number on your account was changed" is also texted to the
-- number it was changed from.
-- Refs: V32 outbound_message_bodies, V107 app.enqueue_outbound_message,
--       docs/known-issues.md 64
--
-- The outbox worker looks an SMS recipient up when it sends: the account's
-- phone. After a change that is the NEW number, so the only text saying the
-- number changed went to the handset that just proved the change — in a
-- takeover, the attacker's. The owner's own number heard nothing.
--
-- A queued message may now carry the address it goes to, fixed when it is
-- queued. It waits beside the body in outbound_message_bodies, which the runtime
-- role cannot read, and is deleted with the body once the message is finished.
-- Only this one notice, on SMS, may be addressed: anything wider would let the
-- runtime role text any number it liked.
-- =============================================================================

alter table outbound_message_bodies add column address text;

drop function app.enqueue_outbound_message(uuid, uuid, text, text, text, text, text);

create function app.enqueue_outbound_message(
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
  if (p_template like 'reminder.%' or p_template = 'still_true.digest')
     and app.messages_consent_withdrawn(p_user_id) then
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

revoke all on function app.enqueue_outbound_message(uuid, uuid, text, text, text, text, text, text) from public;
do $$
begin
  if exists (select 1 from pg_roles where rolname = 'almira_app') then
    execute 'grant execute on function app.enqueue_outbound_message(uuid, uuid, text, text, text, text, text, text) to almira_app';
  end if;
end $$;
