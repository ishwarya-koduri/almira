-- =============================================================================
-- V107 · Definer helpers that answer about anyone are not the runtime role's to ask.
-- Refs: V40 app.notifications_stopped, V45 app.messages_consent_withdrawn,
--       V60 app.is_remembrance_day, V70 app.count_product_event, V95 (the same
--       revoke for app.member_present_since), docs/what-we-measure.md
--
-- Each of these reads with the owner's rights, past row-level security, and
-- answers a yes or no about a user, member or household the caller names. The
-- blanket EXECUTE grant in R__grants handed every one of them to almira_app, so
-- anything able to run SQL as the runtime role could ask, for any household,
-- which days are birthdays or death anniversaries (366 calls give the dates),
-- whether a person is memorialised anywhere, whether they withdrew consent to
-- messages, and — through count_product_event's result — whether a member id
-- belongs to a minor or a user has opted out of measurement.
--
--  * is_remembrance_day and notifications_stopped are asked only by the sweeps
--    on the owner connection and by definer functions, so almira_app loses them.
--  * messages_consent_withdrawn was asked by the notifier on the runtime pool.
--    The question moves into app.enqueue_outbound_message, which already stops
--    a message to someone memorialised: an email or text under consent to
--    messages (a reminder, the "still true?" digest) is not queued once it is
--    withdrawn. The in-app copy is untouched, as before.
--  * count_product_event still takes its inputs, since a sign-in is counted
--    before a session exists, but returns nothing. The runtime role cannot read
--    the counts, so whether one was added is no longer anything it can learn.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Consent to messages, checked where an outside message is written.
-- The templates are MessageConsent's former list: reminders and the digest.
-- -----------------------------------------------------------------------------
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
    insert into outbound_message_bodies (message_id, body) values (v_id, coalesce(p_body, ''));
  end if;
  return v_id;
end $$;

-- -----------------------------------------------------------------------------
-- Measurement: the same rules, no answer.
-- -----------------------------------------------------------------------------
drop function app.count_product_event(text, smallint, uuid, uuid, uuid[], uuid[], uuid[], uuid[]);

create function app.count_product_event(
  p_event          text,
  p_step           smallint default 0,
  p_actor          uuid     default null,
  p_household_id   uuid     default null,
  p_member_ids     uuid[]   default '{}',
  p_investment_ids uuid[]   default '{}',
  p_liability_ids  uuid[]   default '{}',
  p_account_ids    uuid[]   default '{}'
) returns void
  language plpgsql security definer
  set search_path = public, app, pg_temp as $$
declare
  v_actor uuid := coalesce(p_actor, app.current_user_id());
  v_event text := p_event;
begin
  -- Derived below, never reported: otherwise it could be counted without a holding.
  if v_event = 'first_holding_added' then
    raise exception 'first_holding_added is counted with holding_added, not on its own';
  end if;

  -- A guest link borrows someone's identity to read; it is not that person using Almira.
  if app.guest_share_id() is not null then
    return;
  end if;

  if v_actor is not null and exists (select 1 from measurement_opt_outs where user_id = v_actor) then
    return;
  end if;

  if exists (
       select 1 from members m
       where app.is_minor(m.date_of_birth)
         and (m.id = any(coalesce(p_member_ids, '{}'))
              or (v_actor is not null and m.user_id = v_actor)
              or m.id in (select o.member_id from investment_ownerships o
                          where o.investment_id = any(coalesce(p_investment_ids, '{}')))
              or m.id in (select h.member_id from liability_holders h
                          where h.liability_id = any(coalesce(p_liability_ids, '{}')))
              or m.id in (select a.member_id from account_holders a
                          where a.account_id = any(coalesce(p_account_ids, '{}'))))
     ) then
    return;
  end if;

  insert into measurement_daily_counts as c (day, event, step, count)
    values ((now() at time zone 'Asia/Kolkata')::date, v_event, coalesce(p_step, 0), 1)
    on conflict (day, event, step) do update set count = c.count + 1;

  if v_event = 'holding_added' and p_household_id is not null
     and (select count(*) from investments where household_id = p_household_id) = 1 then
    insert into measurement_daily_counts as c (day, event, step, count)
      values ((now() at time zone 'Asia/Kolkata')::date, 'first_holding_added', 0, 1)
      on conflict (day, event, step) do update set count = c.count + 1;
  end if;
end $$;

comment on function app.count_product_event(text, smallint, uuid, uuid, uuid[], uuid[], uuid[], uuid[]) is
  'Counts one allowlisted product event unless the actor opted out, a minor is involved, or a guest link is in use. Stores no identifier and says nothing about whether it counted.';

-- -----------------------------------------------------------------------------
-- Privileges. R__grants repeats the revokes after its blanket grant.
-- -----------------------------------------------------------------------------
do $$
begin
  if exists (select 1 from pg_roles where rolname = 'almira_app') then
    execute 'grant execute on function app.count_product_event(text, smallint, uuid, uuid, uuid[], uuid[], uuid[], uuid[]) to almira_app';
    execute 'revoke execute on function app.is_remembrance_day(uuid, date) from almira_app';
    execute 'revoke execute on function app.notifications_stopped(uuid, uuid) from almira_app';
    execute 'revoke execute on function app.messages_consent_withdrawn(uuid) from almira_app';
  end if;
end $$;

-- A function is executable by PUBLIC unless that is taken away.
revoke execute on function app.is_remembrance_day(uuid, date) from public;
revoke execute on function app.notifications_stopped(uuid, uuid) from public;
revoke execute on function app.messages_consent_withdrawn(uuid) from public;
