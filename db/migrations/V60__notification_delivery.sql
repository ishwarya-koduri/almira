-- =============================================================================
-- V60 · Notifications that can reach a person, and are careful when they do.
-- Refs: docs/13 "Who a message is for" and "Pacing", docs/21 §6,
--       docs/known-issues.md 13, docs/providers/push.md
--
-- Until now the notification outbox knew what to say and not where to say it:
-- every channel was sent `recipientHint = null` (known-issues 13), there was no
-- place for a phone's push token, and nothing stopped a person being told five
-- things at 06:00. Four things arrive together, because each is small and none
-- is useful alone:
--
--   user_devices              one row per installed app, holding its push token.
--   notification_preferences  which channels a person wants, their quiet hours,
--                             and "ask me later" for Still true? reminders.
--   outbound_messages         a row can now wait: `not_before`, and why.
--   members.died_on           so a remembrance day can be kept quiet.
--
-- The functions V32 and V35 wrote messages through are NOT redefined here: what
-- is paced, and whom it reaches, is decided by the worker at send time
-- (provider/DeliveryPacing.kt), so this migration composes with any later
-- migration that changes who may be written to.
--
-- Nothing existing is rewritten. No preference row means the defaults below.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Devices. A person can have two phones; a token rotates; an install
-- re-registers on launch. So the key is the installation, chosen by the app,
-- and a re-launch updates the same row (docs/providers/push.md).
-- -----------------------------------------------------------------------------
create table user_devices (
  id               uuid primary key default gen_random_uuid(),
  user_id          uuid not null references users(id) on delete cascade,
  installation_id  text not null check (installation_id ~ '^[A-Za-z0-9._:-]{8,128}$'),
  platform         text not null check (platform in ('ios', 'android')),
  -- The token is an address, not a secret: sending to it needs our provider
  -- credentials. It is still never returned by the API or logged.
  token            text not null check (length(token) between 16 and 4096),
  -- An APNs development token is refused by the production gateway and the
  -- other way round, so iOS says which it is.
  environment      text check (environment in ('development', 'production')),
  app_version      text check (app_version is null or length(app_version) <= 64),
  last_seen_at     timestamptz not null default now(),
  created_at       timestamptz not null default now(),
  constraint user_devices_ios_says_environment check (platform <> 'ios' or environment is not null)
);
create unique index user_devices_one_row_per_installation on user_devices (user_id, installation_id);
create index on user_devices (user_id);

alter table user_devices enable row level security;

-- Your own devices, and nobody else's. Nobody in the household, an admin
-- included, needs to know which phones a person owns. A guest link acts for
-- the person who shared it and must not be able to redirect their messages.
create policy user_devices_read on user_devices for select
  using (user_id = app.current_user_id());
create policy user_devices_insert on user_devices for insert
  with check (app.guest_share_id() is null and user_id = app.current_user_id());
create policy user_devices_update on user_devices for update
  using (app.guest_share_id() is null and user_id = app.current_user_id())
  with check (user_id = app.current_user_id());
create policy user_devices_delete on user_devices for delete
  using (app.guest_share_id() is null and user_id = app.current_user_id());

comment on table user_devices is
  'One row per installed app: its push token. Read and written only by the person it belongs to; '
  'the notification outbox reads it on the owner connection (docs/13).';

-- -----------------------------------------------------------------------------
-- Preferences. One row per person, written the first time they change
-- anything; until then the defaults below apply, and the worker uses the same
-- defaults for a person with no row (DeliveryPacing.DEFAULTS).
-- -----------------------------------------------------------------------------
create table notification_preferences (
  user_id                  uuid primary key references users(id) on delete cascade,
  sms_enabled              boolean not null default true,
  email_enabled            boolean not null default true,
  push_enabled             boolean not null default true,
  -- Local time in the household's own zone. A window may cross midnight
  -- (21:00 to 08:00 does); an empty window is not a window.
  quiet_from               time not null default '21:00',
  quiet_until              time not null default '08:00',
  -- "Ask me later" on a Still true? reminder: no digest before this date.
  still_true_paused_until  date,
  updated_at               timestamptz not null default now(),
  constraint notification_preferences_quiet_is_a_window check (quiet_from <> quiet_until)
);

alter table notification_preferences enable row level security;

create policy notification_preferences_read on notification_preferences for select
  using (user_id = app.current_user_id());
create policy notification_preferences_insert on notification_preferences for insert
  with check (app.guest_share_id() is null and user_id = app.current_user_id());
create policy notification_preferences_update on notification_preferences for update
  using (app.guest_share_id() is null and user_id = app.current_user_id())
  with check (user_id = app.current_user_id());
-- No delete policy: switching everything back is an update, and a missing row
-- only ever means the defaults.

comment on table notification_preferences is
  'Which channels a person wants, their quiet hours, and a pause on Still true? reminders. '
  'Own row only (docs/13 "Pacing").';

-- -----------------------------------------------------------------------------
-- A queued message can wait. The worker sets `not_before` when a message would
-- land inside someone's quiet hours or would be their second non-essential
-- message of the day, and does not claim it until then.
-- -----------------------------------------------------------------------------
alter table outbound_messages
  add column not_before     timestamptz,
  add column deferred_for   text check (deferred_for in ('quiet_hours', 'daily_limit'));

drop index if exists outbound_messages_queued;
create index outbound_messages_queued
  on outbound_messages (coalesce(not_before, created_at))
  where status = 'queued' and idempotency_key is not null;

-- For "has this person already been sent something today?".
create index outbound_messages_started_per_user
  on outbound_messages (user_id, send_started_at)
  where channel <> 'in_app' and send_started_at is not null;

-- -----------------------------------------------------------------------------
-- Remembrance days. A member's date of birth has existed since V1; the date a
-- member died is new, optional, and recorded only when the family chooses to.
-- -----------------------------------------------------------------------------
alter table members
  add column died_on date,
  add constraint members_died_after_born
    check (died_on is null or date_of_birth is null or died_on >= date_of_birth);

-- Whether [p_day] is the birthday or the death anniversary of anyone recorded
-- in this household. Booleans only, definer rights: the Still true? sweep asks
-- it on the owner connection, and it must not become a way to read dates.
-- A 29 February date is kept on 28 February in a year without one.
create or replace function app.is_remembrance_day(p_household_id uuid, p_day date)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (
    select 1
    from members m
    cross join lateral (values (m.date_of_birth), (m.died_on)) as d(day)
    where m.household_id = p_household_id
      and m.deleted_at is null
      and d.day is not null
      and extract(month from d.day) = extract(month from p_day)
      and ( extract(day from d.day) = extract(day from p_day)
         or ( extract(month from d.day) = 2 and extract(day from d.day) = 29
              and extract(day from p_day) = 28
              and extract(day from (date_trunc('year', p_day) + interval '1 month' * 2 - interval '1 day')) = 28 ) )
  )
$$;

-- Table privileges come from R__grants, which re-runs after every migration and
-- grants the runtime role select/insert/update/delete on every table in public;
-- row-level security above decides which rows. Functions in schema app are
-- executable by almira_app by default (V27).
