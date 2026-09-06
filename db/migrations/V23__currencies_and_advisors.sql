-- =============================================================================
-- V23 · Two Phase-4 pieces that need no provider: money in more than one
--       currency, and an advisor who is a colleague rather than a guest.
-- Refs: docs/01 §4, docs/07 §1 "Valuation & money", docs/10 Phase 4
--
-- Multi-currency follows the rule the rest of the value model already uses:
-- **store what is true, convert only to display**. A holding in dirhams is
-- worth what it is worth in dirhams; the rupee figure beside it is a rendering,
-- made with a rate that has a date and a source attached — and where no rate
-- exists, nothing is invented and the total says how much it is missing.
-- =============================================================================

create table exchange_rates (
  id             uuid primary key default gen_random_uuid(),
  base_currency  text not null check (length(base_currency) = 3),
  quote_currency text not null check (length(quote_currency) = 3),
  -- 1 unit of base = `rate` units of quote. Eight decimals: enough for JPY and
  -- for currencies quoted in thousandths, and never a float.
  rate           numeric(18,8) not null check (rate > 0),
  as_of          date not null,
  -- Where it came from, so a figure can always be traced: 'manual', 'seed', or
  -- the name of whatever provider is plugged in later.
  source         text not null default 'manual',
  -- The household that recorded it, or NULL for a rate everyone can use.
  household_id   uuid references households(id) on delete cascade,
  created_by     uuid references users(id),
  created_at     timestamptz not null default now(),
  unique (base_currency, quote_currency, as_of, source, household_id),
  constraint rate_is_between_two_different_currencies
    check (base_currency <> quote_currency)
);
create index on exchange_rates (base_currency, quote_currency, as_of desc);

alter table exchange_rates enable row level security;

-- Shared rates are reference data; a household's own rate is its own business.
create policy exchange_rates_read on exchange_rates for select
  using (household_id is null or app.is_household_member(household_id));

create policy exchange_rates_write on exchange_rates for all
  using (household_id is not null and app.can_write_household(household_id))
  with check (household_id is not null and app.can_write_household(household_id)
              and created_by = app.current_user_id());

-- A small seed so the feature works out of the box for the currencies an Indian
-- household most often holds. Deliberately dated and marked 'seed': they are a
-- starting point to be corrected, not a live quote, and every screen says so.
insert into exchange_rates (base_currency, quote_currency, rate, as_of, source) values
  ('USD', 'INR', 88.50000000, date '2026-09-01', 'seed'),
  ('AED', 'INR', 24.10000000, date '2026-09-01', 'seed'),
  ('GBP', 'INR', 118.20000000, date '2026-09-01', 'seed'),
  ('EUR', 'INR', 97.40000000, date '2026-09-01', 'seed'),
  ('SGD', 'INR', 68.90000000, date '2026-09-01', 'seed'),
  ('AUD', 'INR', 58.30000000, date '2026-09-01', 'seed'),
  ('CAD', 'INR', 64.10000000, date '2026-09-01', 'seed'),
  ('JPY', 'INR', 0.59000000, date '2026-09-01', 'seed'),
  ('CHF', 'INR', 109.70000000, date '2026-09-01', 'seed'),
  ('SAR', 'INR', 23.60000000, date '2026-09-01', 'seed'),
  ('QAR', 'INR', 24.30000000, date '2026-09-01', 'seed'),
  ('KWD', 'INR', 288.40000000, date '2026-09-01', 'seed'),
  ('MYR', 'INR', 20.90000000, date '2026-09-01', 'seed'),
  ('HKD', 'INR', 11.30000000, date '2026-09-01', 'seed');

-- =============================================================================
-- The advisor: an ongoing scoped role.
--
-- A CA who works with a household all year should not need a fresh guest link
-- every week — but neither should they get what an editor gets. An advisor is a
-- real member with a login, and sees **only what has been explicitly granted**:
-- household visibility does not reach them, and nothing they are not pointed at
-- exists as far as they are concerned.
--
-- Writes were already closed to them: `can_write_household` lists the roles that
-- may write, and 'advisor' is not one.
-- =============================================================================

alter table household_memberships drop constraint if exists household_memberships_role_check;
alter table household_memberships add constraint household_memberships_role_check
  check (role in ('owner','admin','editor','viewer','restricted','advisor'));

-- The invitation carries the role, so it has to know the word too.
alter table invitations drop constraint if exists invitations_role_check;
alter table invitations add constraint invitations_role_check
  check (role in ('owner','admin','editor','viewer','restricted','advisor'));

create or replace function app.is_advisor(p_household_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (
    select 1 from household_memberships hm
    where hm.household_id = p_household_id
      and hm.user_id = app.current_user_id()
      and hm.status = 'active'
      and hm.role = 'advisor'
  )
$$;

-- The body changes; the signature does not, so every policy that calls it keeps
-- working and inherits the new branch.
create or replace function app.can_read_record(
    p_household_id uuid,
    p_visibility   text,
    p_record_type  text,
    p_record_id    uuid,
    p_is_owner     boolean
) returns boolean language sql stable
  set search_path = public, app, pg_temp as $$
  select app.is_household_member(p_household_id)
     and case
       when app.is_advisor(p_household_id)
         -- Explicit grants only. Not ownership — an advisor holds nothing — and
         -- not household visibility, which is for the family.
         then app.has_visibility_grant(p_record_type, p_record_id)
       else ( p_is_owner
           or p_visibility = 'household'
           or (p_visibility = 'scoped'
               and app.has_visibility_grant(p_record_type, p_record_id)) )
     end
$$;
