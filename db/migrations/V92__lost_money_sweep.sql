-- =============================================================================
-- V92 · The lost-money sweep: did anyone look, and what did they find?
-- Refs: docs/03 §8.5, catch-up plan P-25
--
-- Three public portals find money a family has forgotten: RBI's UDGAM for
-- deposits a bank moved to the DEA Fund, the IEPF Authority for shares and
-- dividends a company transferred, and the EPFO passbook for an old employer's
-- provident fund. Almira links to each and never touches them — no scraping,
-- no automated search, no credentials. What it keeps is the answer a person
-- brings back: that they checked, on which date, and whether something turned
-- up. Something found becomes an ordinary record, with the steps to claim it.
-- =============================================================================

create table lost_money_checks (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references households(id) on delete cascade,
  -- Whose name was searched. A portal search is about one person's PAN or UAN.
  member_id     uuid not null references members(id) on delete cascade,
  portal        text not null check (portal in ('udgam','iepf','epfo')),
  -- checked: looked, and still going through it · found · nothing
  status        text not null check (status in ('checked','found','nothing')),
  checked_on    date not null,
  -- The record made from what was found, once there is one.
  investment_id uuid references investments(id) on delete set null,
  created_by    uuid not null references users(id),
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  version       int not null default 1,
  unique (household_id, member_id, portal)
);
create trigger lost_money_checks_touch before update on lost_money_checks
  for each row execute function app.touch_row();

-- The person who looked, and the person it is about. That a search for Ravi's
-- name found an unclaimed deposit is Ravi's business before it is the
-- household's; the record made from it has its own visibility.
alter table lost_money_checks enable row level security;

create policy lost_money_checks_read on lost_money_checks for select
  using (app.guest_share_id() is null
         and app.is_household_member(household_id)
         and (created_by = app.current_user_id()
              or member_id = any(app.current_member_ids(household_id))));

create policy lost_money_checks_insert on lost_money_checks for insert
  with check (app.guest_share_id() is null
              and created_by = app.current_user_id()
              and app.can_write_household(household_id)
              and exists (select 1 from members m
                          where m.id = member_id and m.household_id = lost_money_checks.household_id));

create policy lost_money_checks_update on lost_money_checks for update
  using (app.guest_share_id() is null
         and app.can_write_household(household_id)
         and (created_by = app.current_user_id()
              or member_id = any(app.current_member_ids(household_id))))
  with check (app.guest_share_id() is null
              and app.can_write_household(household_id)
              and exists (select 1 from members m
                          where m.id = member_id and m.household_id = lost_money_checks.household_id));

create policy lost_money_checks_delete on lost_money_checks for delete
  using (app.guest_share_id() is null and created_by = app.current_user_id());
