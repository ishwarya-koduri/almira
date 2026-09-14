-- =============================================================================
-- V80 · Readiness over time: one small row a day, per person, per household.
-- Refs: docs/22 §1, "Movement", catch-up plan X-33
--
-- "Ready to hand over" was a verdict with no movement: a number, rounded down,
-- and no way to see that last month's work counted. Movement needs a memory,
-- and this is the smallest one that will do.
--
-- What a row holds, and what it deliberately does not:
--   · counts only — how many things were done and how many applied, per check,
--     and the score those made. No titles, no record ids, no amounts. Readiness
--     is computed through the viewer's own sight, so even counts are that
--     viewer's; the row is theirs alone, and no one else can read it.
--   · one row per day. A second read on the same day replaces the first, so a
--     page that is opened thirty times writes one row, and the history is a
--     calendar rather than a click log.
--
-- Rows older than 400 days are removed when a new one is written; a year is
-- enough to say "than last month" and "than last year", and nothing here is
-- worth keeping longer.
-- =============================================================================

create table readiness_snapshots (
  household_id  uuid not null references households(id) on delete cascade,
  user_id       uuid not null references users(id) on delete cascade,
  taken_on      date not null,
  -- Null when the server had no honest score that day (docs/22 §1).
  score         int check (score is null or score between 0 and 100),
  -- {"nominee": [done, applicable], "document": [...], ...}
  checks        jsonb not null check (jsonb_typeof(checks) = 'object'),
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  primary key (household_id, user_id, taken_on)
);

comment on table readiness_snapshots is
  'One row a day of a person''s handover-readiness counts, for "N things safer than last month". '
  'Counts only; readable and writable by that person alone.';

-- A person's own rows, in a household they still belong to. Leaving a household
-- ends sight of its history with everything else; being deleted removes it.
alter table readiness_snapshots enable row level security;

create policy readiness_snapshots_own on readiness_snapshots for all
  using (user_id = app.current_user_id() and app.is_household_member(household_id))
  with check (user_id = app.current_user_id() and app.is_household_member(household_id));

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'almira_app') then
    execute 'grant select, insert, update, delete on readiness_snapshots to almira_app';
  end if;
end $$;
