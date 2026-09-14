-- =============================================================================
-- V91 · A long form, asked one question at a time, remembers where you were.
-- Refs: docs/03 §8.4, catch-up plan X-58
--
-- Naming an emergency contact, recording a will, and writing down where an
-- original is kept were each one long sheet. They are guided flows now: one
-- question per screen, a progress line, and "Take your time". A person who
-- stops half-way — because the doorbell rang, or because it was too much for
-- today — comes back to the question they were on.
--
-- So the place in the flow is saved at every step, per person. The answers are
-- saved with it only where they are ordinary fields the server would store
-- anyway when the flow finishes. Where and who is sealed on the device
-- (docs/20): its words are sealed and saved at each step through the sealed
-- path, and this table keeps only the step number — the constraint below makes
-- that a property of the table, not a habit of the client.
-- =============================================================================

create table guided_flow_drafts (
  id           uuid primary key default gen_random_uuid(),
  household_id uuid not null references households(id) on delete cascade,
  user_id      uuid not null references users(id) on delete cascade,
  flow         text not null check (flow in ('emergency_setup','estate_document','where_and_who')),
  -- Which record a per-record flow is about, as "<type>:<uuid>"; empty otherwise.
  subject_key  text not null default ''
                 check (subject_key ~ '^([a-z_]{1,40}:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})?$'),
  step         int not null default 0 check (step between 0 and 50),
  answers      jsonb not null default '{}'::jsonb,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  version      int not null default 1,
  unique (household_id, user_id, flow, subject_key),
  constraint draft_answers_are_an_object check (jsonb_typeof(answers) = 'object'),
  constraint draft_answers_are_small check (length(answers::text) <= 4000),
  -- Sealed words are never kept here, even for a moment.
  constraint where_and_who_keeps_no_words check (flow <> 'where_and_who' or answers = '{}'::jsonb)
);
create trigger guided_flow_drafts_touch before update on guided_flow_drafts
  for each row execute function app.touch_row();

-- Your own place in your own flow. Nobody else's business, an admin's included,
-- and never reachable from a guest link.
alter table guided_flow_drafts enable row level security;

create policy guided_flow_drafts_own on guided_flow_drafts for all
  using (app.guest_share_id() is null
         and user_id = app.current_user_id()
         and app.is_household_member(household_id))
  with check (app.guest_share_id() is null
              and user_id = app.current_user_id()
              and app.is_household_member(household_id));
