-- =============================================================================
-- V11 · Reminders, notifications, and the household's clock.
-- Refs: docs/04 §9, docs/10 Epic 1.6
--
-- The point of a registry is that it tells you things before you need them. An
-- FD that matures unnoticed and rolls over at 3% is exactly the kind of quiet
-- loss this product exists to prevent.
-- =============================================================================

-- A household has a clock. "Due today" has to mean today where the family
-- lives, not where the server runs — otherwise a reminder fires on the wrong
-- side of midnight for half the year, which is the sort of bug people notice
-- and never trust again (docs/07 §1).
alter table households
  add column time_zone text not null default 'Asia/Kolkata';

comment on column households.time_zone is
  'IANA zone. All "is it due?" comparisons are made in this zone, not the server''s.';

create table reminders (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references households(id) on delete cascade,
  investment_id uuid references investments(id) on delete cascade,
  liability_id  uuid references liabilities(id) on delete cascade,
  kind          text not null
                  check (kind in ('maturity','premium_due','renewal','sip','emi',
                                  'review','verify','custom')),
  title         text not null,
  due_date      date not null,
  -- How far ahead to speak up. A maturity you learn about on the day is not
  -- much use; a premium is.
  lead_days     int  not null default 7 check (lead_days between 0 and 365),
  recurrence    text not null default 'none'
                  check (recurrence in ('none','monthly','quarterly','half_yearly','yearly')),
  amount        numeric(18,4),
  status        text not null default 'pending'
                  check (status in ('pending','notified','done','snoozed','cancelled')),
  snoozed_until date,
  note          text,
  -- Reminders created FROM a record are kept in step with it automatically.
  -- A hand-made one is never overwritten by that process.
  auto_generated boolean not null default false,
  last_notified_at timestamptz,
  deleted_at    timestamptz,
  version       int not null default 1,
  created_by    uuid references users(id),
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  constraint reminder_points_at_one_thing
    check (num_nonnulls(investment_id, liability_id) <= 1)
);
create index on reminders (household_id, due_date) where deleted_at is null;
create index on reminders (status, due_date) where deleted_at is null;
create index on reminders (investment_id);
create index on reminders (liability_id);
-- One auto-generated reminder per record per kind: regenerating must update
-- rather than accumulate a new row every time a record is saved.
create unique index reminders_one_auto_per_record_kind
  on reminders (coalesce(investment_id, liability_id), kind)
  where auto_generated and deleted_at is null;
create trigger reminders_touch before insert or update on reminders
  for each row execute function app.touch_row();

create table notifications (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null references users(id) on delete cascade,
  household_id uuid references households(id) on delete cascade,
  reminder_id  uuid references reminders(id) on delete set null,
  channel      text not null default 'in_app' check (channel in ('in_app','push','email','sms')),
  template     text not null,
  payload      jsonb not null default '{}',
  status       text not null default 'queued'
                 check (status in ('queued','sent','failed','read')),
  sent_at      timestamptz,
  read_at      timestamptz,
  created_at   timestamptz not null default now()
);
create index on notifications (user_id, created_at desc);
create index on notifications (status) where status = 'queued';

-- =============================================================================
-- Row-level security.
--
-- A reminder inherits the privacy of what it points at: a nudge about a private
-- policy would otherwise announce the policy. Reminders attached to nothing are
-- household-wide by nature — someone typed them for everyone.
-- =============================================================================

alter table reminders enable row level security;

create policy reminders_read on reminders for select
  using (
    app.is_household_member(household_id)
    and (
      (investment_id is null and liability_id is null)
      or (investment_id is not null
          and exists (select 1 from investments i where i.id = investment_id))
      or (liability_id is not null
          and exists (select 1 from liabilities l where l.id = liability_id))
    )
  );

create policy reminders_insert on reminders for insert
  with check (app.can_write_household(household_id));

create policy reminders_update on reminders for update
  using (app.can_write_household(household_id)
         and ( (investment_id is null and liability_id is null)
            or (investment_id is not null
                and exists (select 1 from investments i where i.id = investment_id))
            or (liability_id is not null
                and exists (select 1 from liabilities l where l.id = liability_id)) ))
  with check (app.can_write_household(household_id));

create policy reminders_delete on reminders for delete
  using (app.can_write_household(household_id));

alter table notifications enable row level security;
-- A notification belongs to one person. Nobody else reads it, whatever
-- their role.
create policy notifications_own on notifications for select
  using (user_id = app.current_user_id());
create policy notifications_mark_read on notifications for update
  using (user_id = app.current_user_id())
  with check (user_id = app.current_user_id());
create policy notifications_insert on notifications for insert
  with check (household_id is null or app.is_household_member(household_id));
