-- =============================================================================
-- V141 · "Not now" is about what was asked, not a timer on the person.
-- Refs: V125 messages_consent_asks, privacy/DataRights.kt (messagesAsk,
--       messagesNotNow), static/app/message-consent.js,
--       docs/23 "Asked when it helps"
--
-- V125 kept one row per person: after "Not now" anywhere, nothing was asked for
-- 90 days. The owner's answer: keep the 90 days, but make the trigger the
-- context. The question is never asked again about the SAME thing sooner than
-- 90 days after "Not now" to it; a DIFFERENT holding whose first due date makes
-- a reminder may ask again, even inside those 90 days. And the Still true?
-- digest is a context of its own: "Not now" beside it keeps it away from the
-- digest for 90 days and from nothing else.
--
-- So a "Not now" is kept per context:
--
--   context_type  'investment' or 'liability' — the holding or loan whose date
--                 made the reminder — with context_id its id; or
--                 'still_true_digest', one per person, with no id.
--
-- messages_consent_asks stays as the person's last "Not now" anywhere. It is
-- what a client that names no context is answered from (the v1 call as V125
-- froze it), and it is moved on by every "Not now", with or without a context.
--
-- Like V125's table, a row here is not a consent record and never makes a
-- message go. Your own rows only; kept or moved on, never deleted by the
-- application (R__grants); gone with the account.
--
-- The context's id is not a foreign key: a holding may be erased while the
-- row stays, and the row says nothing about the holding but that you were
-- asked beside it. It is only ever read back by the person who wrote it, who
-- could see the holding when they did (DataRightsService checks, and answers
-- 404 for a record the caller cannot see).
-- =============================================================================

create table messages_consent_ask_contexts (
  user_id      uuid not null references users(id) on delete cascade,
  context_type text not null
                 check (context_type in ('investment', 'liability', 'still_true_digest')),
  context_id   uuid,
  not_now_at   timestamptz not null default now(),
  constraint messages_consent_ask_contexts_id_matches_type check (
    (context_type = 'still_true_digest') = (context_id is null)),
  -- One row per person per context. The digest has no id, and two digest rows
  -- for one person would be the same context twice.
  constraint messages_consent_ask_contexts_one_per_context
    unique nulls not distinct (user_id, context_type, context_id)
);

alter table messages_consent_ask_contexts enable row level security;

-- Your own, and only yours. No delete policy: the row goes with the account.
create policy messages_consent_ask_contexts_read on messages_consent_ask_contexts for select
  using (user_id = app.current_user_id());

create policy messages_consent_ask_contexts_insert on messages_consent_ask_contexts for insert
  with check (user_id = app.current_user_id());

create policy messages_consent_ask_contexts_update on messages_consent_ask_contexts for update
  using (user_id = app.current_user_id())
  with check (user_id = app.current_user_id());
