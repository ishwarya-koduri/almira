-- =============================================================================
-- V90 · Heir mode: one task at a time, for the person holding an open window.
-- Refs: docs/03 §8.3, docs/05 §6, catch-up plan X-40
--
-- When an emergency window opens, the person who asked is usually reading on a
-- phone, in a hospital corridor or the week after a funeral. The family plan
-- screen shows them everything at once. Heir mode turns what they may now see
-- into a short list of tasks, one per screen, that they can put down and pick
-- up again, and hand a task to a brother or an aunt.
--
-- Three rules shape the tables:
--
--   1. A plan lives only as long as the window. Every read policy asks
--      app.has_open_emergency_window (V40), so a vetoed, withdrawn, expired or
--      reachable-again window closes the plan too — with no worker to forget.
--   2. A task names a record by id, never by copying its title. The words are
--      joined at read time under row-level security, so when the window shuts,
--      what the task was about goes with it.
--   3. A relative helping with a task has no account. Their link is a guest
--      link (V20) of its own scope, `heir_help`, naming exactly the records of
--      the tasks handed to them; inside that session the ordinary guest clamp
--      narrows every read, and the policies below narrow the tasks to theirs.
--      At most five at a time.
-- =============================================================================

alter table guest_shares drop constraint guest_shares_scope_check;
alter table guest_shares add constraint guest_shares_scope_check
  check (scope in ('tax_pack','handbook','records','heir_help'));

-- Sharing a task is something the person holding the window does, whatever
-- their role: a viewer who is somebody's trusted contact is exactly who ends up
-- doing this. So a helper's link may be made by a member with an open window in
-- this household, as well as by anyone who could make a link before.
alter policy guest_shares_insert on guest_shares
  with check (created_by = app.current_user_id()
              and ( app.can_write_household(household_id)
                 or ( scope = 'heir_help'
                      and app.guest_share_id() is null
                      and app.is_household_member(household_id)
                      and app.emergency_reveals(household_id, true) ) ));

create table heir_plans (
  id                   uuid primary key default gen_random_uuid(),
  household_id         uuid not null references households(id) on delete cascade,
  emergency_request_id uuid not null unique references emergency_requests(id) on delete cascade,
  subject_member_id    uuid not null references members(id) on delete cascade,
  -- Which list of tasks. Asked once, gently, and changeable.
  situation            text not null check (situation in ('passed_away','cannot_manage')),
  created_by           uuid not null references users(id),
  -- "You can stop here." Set when they put it down; cleared when they return.
  paused_at            timestamptz,
  created_at           timestamptz not null default now(),
  updated_at           timestamptz not null default now(),
  version              int not null default 1
);
create trigger heir_plans_touch before update on heir_plans
  for each row execute function app.touch_row();

create table heir_helpers (
  id           uuid primary key default gen_random_uuid(),
  plan_id      uuid not null references heir_plans(id) on delete cascade,
  household_id uuid not null references households(id) on delete cascade,
  name         text not null,
  relationship text,
  -- The link they were sent. Withdrawing the helper withdraws the link.
  share_id     uuid not null unique references guest_shares(id) on delete cascade,
  created_at   timestamptz not null default now(),
  removed_at   timestamptz,
  constraint heir_helper_name_length check (length(btrim(name)) between 1 and 80),
  constraint heir_helper_relationship_length check (relationship is null or length(relationship) <= 40)
);
create index on heir_helpers (plan_id) where removed_at is null;

create table heir_tasks (
  id           uuid primary key default gen_random_uuid(),
  plan_id      uuid not null references heir_plans(id) on delete cascade,
  household_id uuid not null references households(id) on delete cascade,
  task_key     text not null check (task_key ~ '^[a-z_]{1,40}$'),
  record_type  text check (record_type in ('investment','liability','estate_document')),
  record_id    uuid,
  sort         int not null,
  -- "later" is not a failure: it goes to the back of the list.
  status       text not null default 'todo' check (status in ('todo','done','later')),
  done_at      timestamptz,
  helper_id    uuid references heir_helpers(id) on delete set null,
  updated_at   timestamptz not null default now(),
  version      int not null default 1,
  constraint heir_task_names_a_whole_record check ((record_type is null) = (record_id is null))
);
create unique index heir_tasks_once_per_thing
  on heir_tasks (plan_id, task_key, coalesce(record_id, '00000000-0000-0000-0000-000000000000'::uuid));
create trigger heir_tasks_touch before update on heir_tasks
  for each row execute function app.touch_row();

-- -----------------------------------------------------------------------------
-- Definer-rights lookups, so the policies below never read each other's tables
-- through row-level security (which would recurse). Booleans only.
-- -----------------------------------------------------------------------------

-- Whether the current guest session is the link of a helper on this plan.
create or replace function app.heir_guest_on_plan(p_plan_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select app.guest_share_id() is not null and exists (
    select 1 from heir_helpers h
    where h.plan_id = p_plan_id and h.share_id = app.guest_share_id() and h.removed_at is null)
$$;

-- Whether the current guest session is this helper's own link.
create or replace function app.heir_guest_is_helper(p_helper_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select app.guest_share_id() is not null and p_helper_id is not null and exists (
    select 1 from heir_helpers h
    where h.id = p_helper_id and h.share_id = app.guest_share_id() and h.removed_at is null)
$$;

-- Whether the caller may work on this plan right now: theirs, and the window
-- it was made under still open.
create or replace function app.heir_plan_is_mine_and_open(p_plan_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (
    select 1 from heir_plans p
    where p.id = p_plan_id
      and p.created_by = app.current_user_id()
      and app.has_open_emergency_window(p.household_id, p.subject_member_id))
$$;

-- Five at a time. Counted with definer rights so the count is the truth.
create or replace function app.heir_helpers_at_most_five() returns trigger
  language plpgsql security definer
  set search_path = public, app, pg_temp as $$
begin
  if new.removed_at is null and (
       select count(*) from heir_helpers h
       where h.plan_id = new.plan_id and h.removed_at is null and h.id <> new.id) >= 5 then
    raise exception 'a plan is shared with at most five people at a time'
      using errcode = 'check_violation', constraint = 'heir_helpers_at_most_five';
  end if;
  return new;
end $$;
create trigger heir_helpers_at_most_five before insert or update on heir_helpers
  for each row execute function app.heir_helpers_at_most_five();

-- -----------------------------------------------------------------------------
-- Policies
-- -----------------------------------------------------------------------------
alter table heir_plans enable row level security;

create policy heir_plans_read on heir_plans for select
  using (created_by = app.current_user_id()
         and app.is_household_member(household_id)
         and app.has_open_emergency_window(household_id, subject_member_id)
         and (app.guest_share_id() is null or app.heir_guest_on_plan(id)));

-- Made only by the person holding the window, about the request that opened it.
create policy heir_plans_insert on heir_plans for insert
  with check (app.guest_share_id() is null
              and created_by = app.current_user_id()
              and app.has_open_emergency_window(household_id, subject_member_id)
              and exists (
                select 1 from emergency_requests r
                where r.id = emergency_request_id
                  and r.household_id = heir_plans.household_id
                  and r.subject_member_id = heir_plans.subject_member_id
                  and r.requested_by = app.current_user_id()
                  and r.vetoed_at is null and r.revoked_at is null
                  and now() >= r.unlock_at and now() < r.access_expires_at));

create policy heir_plans_update on heir_plans for update
  using (app.guest_share_id() is null and created_by = app.current_user_id()
         and app.has_open_emergency_window(household_id, subject_member_id))
  with check (app.guest_share_id() is null and created_by = app.current_user_id()
              and app.has_open_emergency_window(household_id, subject_member_id));

create policy heir_plans_delete on heir_plans for delete
  using (app.guest_share_id() is null and created_by = app.current_user_id());

alter table heir_helpers enable row level security;

create policy heir_helpers_read on heir_helpers for select
  using (app.heir_plan_is_mine_and_open(plan_id)
         and (app.guest_share_id() is null or share_id = app.guest_share_id()));

create policy heir_helpers_write on heir_helpers for all
  using (app.guest_share_id() is null and app.heir_plan_is_mine_and_open(plan_id))
  with check (app.guest_share_id() is null and app.heir_plan_is_mine_and_open(plan_id)
              and exists (select 1 from guest_shares s
                          where s.id = share_id and s.scope = 'heir_help'
                            and s.created_by = app.current_user_id()));

alter table heir_tasks enable row level security;

create policy heir_tasks_read on heir_tasks for select
  using (app.heir_plan_is_mine_and_open(plan_id)
         and (app.guest_share_id() is null or app.heir_guest_is_helper(helper_id)));

create policy heir_tasks_write on heir_tasks for all
  using (app.guest_share_id() is null and app.heir_plan_is_mine_and_open(plan_id))
  with check (app.guest_share_id() is null and app.heir_plan_is_mine_and_open(plan_id)
              and (helper_id is null or exists (
                     select 1 from heir_helpers h
                     where h.id = helper_id and h.plan_id = heir_tasks.plan_id
                       and h.removed_at is null)));
