-- =============================================================================
-- V35 · The in-app message has the logical message's key too.
-- Refs: docs/13 "Interactive and background", V32
--
-- V32 gave every queued channel row an idempotency key, so a reminder swept
-- twice (a crash before its status commits, a second server) queues one text,
-- one email and one push. The in-app row was still written without one, so the
-- same double sweep listed the reminder twice in /me/messages.
--
-- Now the in-app row carries `<logical key>:in_app`, under the same unique
-- index, and a second write of the same logical message writes nothing. The
-- worker never claims it: it only claims rows that are `queued`, and this one is
-- written `sent`.
--
-- Nothing existing is rewritten. Earlier in-app rows keep a null key.
-- =============================================================================

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
  insert into outbound_messages (household_id, user_id, channel, provider, template,
                                 title, status, attempts, idempotency_key)
  values (p_household_id, p_user_id, 'in_app', 'almira', p_template,
          p_title, 'sent', 1, p_idempotency_key)
  on conflict (idempotency_key) where idempotency_key is not null do nothing
  returning id into v_id;
  return v_id;
end $$;

-- No grant here: R__grants sets default privileges so every function created in
-- schema app is executable by almira_app (V27).
