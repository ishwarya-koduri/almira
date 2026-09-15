-- =============================================================================
-- V142 · A yes to messages from before V125 is asked again, not inherited.
-- Refs: V125 app.messages_consent_given, consent_events.channels,
--       privacy/DataRights.kt, docs/23 "Asked when it helps"
--
-- V125 read a `given` event with no channels — a tap on "Give" beside
-- "Reminders by email or text", from before anyone chose channels — as a yes to
-- email and SMS. The owner ruled inherited consent out: the choices changed, so
-- that tap is not a valid affirmative consent to anything sent outside the app
-- that is not essential. From here:
--
--  * **It sends nothing.** app.messages_consent_given is false for a latest
--    `messages` event that names no channels, on every channel. Queueing and
--    the worker's re-check (`no_consent`) both ask it, so a reminder already
--    queued on the strength of such a yes is not sent either.
--
--  * **History is kept.** Nothing here deletes or rewrites an event. The old
--    yes stays in consent_events and on the person's timeline, as what it was.
--
--  * **The person is asked again**, at the next useful moment and on Your data
--    rights, with words that say why (DataRightsService). Their answer is a new
--    event, marked `asked_again`: a yes names exactly the channels ticked; a no
--    is a withdrawal. "Not now" is kept like any other (V141).
--
--  * **No new yes without channels.** Every `given` event for messages written
--    from here names its channels, so "no channels" can only ever mean "from
--    before V125". NOT VALID: the rows it describes are exactly the old ones,
--    which stay.
-- =============================================================================

alter table consent_events add column asked_again boolean not null default false;

alter table consent_events add constraint consent_events_messages_yes_names_channels check (
  purpose <> 'messages' or action <> 'given' or channels is not null) not valid;

alter table consent_events add constraint consent_events_asked_again_is_about_messages check (
  not asked_again or purpose = 'messages');

-- True only when the person's latest `messages` event is `given` and names this
-- channel. No event, a withdrawal, a yes for other channels, or a yes from before
-- channels were chosen (no channels at all) is false.
create or replace function app.messages_consent_given(p_user_id uuid, p_channel text)
  returns boolean language sql stable security definer
  set search_path = public, pg_temp as $$
  select coalesce((
    select e.action = 'given'
           and e.channels is not null
           and p_channel = any(e.channels)
      from consent_events e
     where e.user_id = p_user_id and e.purpose = 'messages'
     order by e.seq desc
     limit 1
  ), false)
$$;

-- create or replace keeps V125's privileges; said again so this file stands alone.
revoke execute on function app.messages_consent_given(uuid, text) from public;
do $$
begin
  if exists (select 1 from pg_roles where rolname = 'almira_app') then
    execute 'revoke execute on function app.messages_consent_given(uuid, text) from almira_app';
  end if;
end $$;
