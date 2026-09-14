-- =============================================================================
-- V85 · The first session: a readiness check before any data, and the shelves
-- after it (docs/03 §1, X-30, P-10, X-32, X-80).
--
-- Two small tables, both about one person and nothing about their money.
--
--   readiness_check_answers  eight answers to "How ready is your family?",
--                            per user, before a household may even exist.
--                            Yes / partly / no / not sure, and nothing typed.
--
--   first_session_progress   per user, per household: who the setup is for
--                            (themselves, or someone they are helping), the
--                            shelves they chose to skip, and when they saw the
--                            welcome a second family member gets. What is
--                            already on a shelf is never stored here: it is
--                            counted from the records the person can see, so
--                            it cannot disagree with them.
--
-- Both are the owner's own rows under row-level security. A household admin
-- cannot read another person's answers: role grants capability, never sight.
-- =============================================================================

create table readiness_check_answers (
  user_id     uuid        primary key references users(id) on delete cascade,
  -- {"will": "no", "nominees": "partly", ...}. Codes and answers are checked in
  -- the service against the one list of questions; the constraint here keeps
  -- anything but a flat object of short strings out.
  answers     jsonb       not null check (jsonb_typeof(answers) = 'object'),
  answered_at timestamptz not null default now(),
  version     int         not null default 1,
  constraint readiness_answers_are_small check (pg_column_size(answers) < 2048)
);

comment on table readiness_check_answers is
  'Answers to the eight-question readiness check (X-30). One row per person; no amounts, no names, no free text.';

alter table readiness_check_answers enable row level security;

create policy readiness_answers_own_select on readiness_check_answers
  for select using (user_id = app.current_user_id());
create policy readiness_answers_own_insert on readiness_check_answers
  for insert with check (user_id = app.current_user_id());
create policy readiness_answers_own_update on readiness_check_answers
  for update using (user_id = app.current_user_id())
  with check (user_id = app.current_user_id());
create policy readiness_answers_own_delete on readiness_check_answers
  for delete using (user_id = app.current_user_id());

create table first_session_progress (
  user_id           uuid        not null references users(id) on delete cascade,
  household_id      uuid        not null references households(id) on delete cascade,
  -- "me", or "someone": an adult child setting things up for a parent (X-32).
  setting_up_for    text        not null default 'me' check (setting_up_for in ('me', 'someone')),
  -- The member row the setup is about, when it is for someone. The household's
  -- own member row, so their name is the roster's and changes with it.
  someone_member_id uuid        references members(id) on delete set null,
  skipped_shelves   text[]      not null default '{}'
                      check (cardinality(skipped_shelves) <= 15),
  welcome_seen_at   timestamptz,
  updated_at        timestamptz not null default now(),
  primary key (user_id, household_id),
  constraint someone_needs_setting_up_for_someone
    check (someone_member_id is null or setting_up_for = 'someone')
);

comment on table first_session_progress is
  'Per person, per household: who the first session is for, shelves skipped, welcome seen. Never what is on a shelf.';

alter table first_session_progress enable row level security;

-- Your own row, in a household you are still an active member of.
create policy first_session_own_select on first_session_progress
  for select using (user_id = app.current_user_id() and app.is_household_member(household_id));
create policy first_session_own_insert on first_session_progress
  for insert with check (user_id = app.current_user_id() and app.is_household_member(household_id));
create policy first_session_own_update on first_session_progress
  for update using (user_id = app.current_user_id() and app.is_household_member(household_id))
  with check (user_id = app.current_user_id() and app.is_household_member(household_id));
create policy first_session_own_delete on first_session_progress
  for delete using (user_id = app.current_user_id());

-- The member named must belong to the same household. A policy cannot see
-- another table's rows without recursing, so this is a trigger, run as the
-- owner, that answers only yes or no.
create or replace function app.check_first_session_member() returns trigger
  language plpgsql security definer
  set search_path = public, app, pg_temp as $$
begin
  -- BEFORE triggers run ahead of the policy's WITH CHECK, so a stranger must
  -- not learn from this which members a household has. Outside the household
  -- the answer is the same refusal whatever was named.
  if not app.is_household_member(new.household_id) then
    raise exception 'first_session_member_not_in_household' using errcode = 'foreign_key_violation';
  end if;
  if new.someone_member_id is not null and not exists (
       select 1 from members m
       where m.id = new.someone_member_id
         and m.household_id = new.household_id
         and m.deleted_at is null) then
    raise exception 'first_session_member_not_in_household' using errcode = 'foreign_key_violation';
  end if;
  new.updated_at := now();
  return new;
end $$;

create trigger first_session_progress_member before insert or update on first_session_progress
  for each row execute function app.check_first_session_member();

-- R__grants sets default privileges for new tables; stated here as well, so the
-- tables' reach is readable in the file that creates them.
do $$
begin
  if exists (select 1 from pg_roles where rolname = 'almira_app') then
    execute 'grant select, insert, update, delete on readiness_check_answers to almira_app';
    execute 'grant select, insert, update, delete on first_session_progress to almira_app';
  end if;
end $$;
