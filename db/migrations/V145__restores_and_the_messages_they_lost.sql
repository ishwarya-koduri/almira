-- =============================================================================
-- V145 · A restore that leaves queued messages without their bodies alerts the
--        operator once, with a count.
-- Refs: docs/13 "After a restore: body_not_restored", docs/17 §6 and §8,
--       provider/RestoredMessageAlerts.kt, scripts/restore.sh
--
-- Owner's decision, 2026-09-15: *alert on body_not_restored, once per restore
-- with a count, not per message. A permanently undeliverable queued message is
-- exactly the silent class.*
--
-- A backup leaves out the rows of outbound_message_bodies and
-- sign_in_code_email_bodies, so a message queued when the backup was taken comes
-- back with no body; both workers record it failed as body_not_restored and send
-- nothing. Until now that was one WARN per message and nothing else.
--
-- restore_events is the "per restore": scripts/restore.sh writes one row once
-- pg_restore has finished. A restore done any other way has no row, so the first
-- body_not_restored the workers find with no restore noticed in the last day
-- opens one, marked `detected`. The workers add to its count; once no more have
-- been found for a minute, one ERROR line `MESSAGES LOST IN A RESTORE` carries
-- the count, and alerted_at stops it from being said again. Anything found after
-- that still adds to not_restored, which the operator's view of /health shows
-- beside alerted_count.
--
-- It holds no message, no address and no person: a time, where the row came
-- from, the backup's name and counts. Like the outbox records it is the owner
-- connection's alone — row-level security on with no policy, and nothing granted
-- to the runtime role (R__grants).
-- =============================================================================

create table restore_events (
  id               uuid primary key default gen_random_uuid(),
  restored_at      timestamptz not null default now(),
  recorded_by      text not null check (recorded_by in ('restore.sh', 'detected')),
  -- The backup's directory name (almira-<UTC timestamp>) when restore.sh wrote the row.
  backup_name      text check (backup_name is null or backup_name ~ '^[A-Za-z0-9._-]{1,120}$'),
  not_restored     integer not null default 0 check (not_restored >= 0),
  first_noticed_at timestamptz,
  last_noticed_at  timestamptz,
  alerted_at       timestamptz,
  alerted_count    integer check (alerted_count is null or alerted_count > 0),
  constraint restore_events_alert_has_a_count check ((alerted_at is null) = (alerted_count is null)),
  constraint restore_events_noticed_in_order check (
    (first_noticed_at is null) = (last_noticed_at is null)
    and (first_noticed_at is null or first_noticed_at <= last_noticed_at)
  )
);

create index restore_events_newest on restore_events (restored_at desc);
create index restore_events_to_alert on restore_events (last_noticed_at)
  where alerted_at is null and not_restored > 0;

alter table restore_events enable row level security;

comment on table restore_events is
  'One row per restore (restore.sh) or per restore the workers detected: how many queued messages it left without a body, and when the operator was alerted, once (V145).';
