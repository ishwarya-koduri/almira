-- =============================================================================
-- V10 · Liabilities, and what an asset is really worth once they are counted.
-- Refs: docs/01 §6, docs/04 §5, docs/10 Epic 1.4
--
-- "Total Assets" is the number every other app shows. True net worth —
-- assets minus what is owed — is the one people actually live by, and it is the
-- reason a home loan has to be a first-class record rather than a note.
-- =============================================================================

create table liabilities (
  id             uuid primary key default gen_random_uuid(),
  household_id   uuid not null references households(id) on delete cascade,
  institution_id uuid references institutions(id) on delete set null,   -- the lender
  kind           text not null
                   check (kind in ('home','car','personal','education','gold','credit_card',
                                   'lap','las','loan_against_insurance','family','other')),
  title          text not null,
  principal      numeric(18,4),
  outstanding    numeric(18,4) not null default 0,
  interest_rate  numeric(6,3),
  emi_amount     numeric(18,4),
  emi_day        int check (emi_day between 1 and 31),
  start_date     date,
  end_date       date,
  status         text not null default 'active' check (status in ('active','closed')),
  attributes     jsonb not null default '{}',
  notes          text,
  visibility     text not null default 'private'
                   check (visibility in ('private','household','scoped')),
  deleted_at     timestamptz,
  version        int not null default 1,
  created_by     uuid references users(id),
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  constraint liability_amounts_are_not_negative
    check (outstanding >= 0 and (principal is null or principal >= 0)),
  constraint liability_dates_are_ordered
    check (end_date is null or start_date is null or end_date >= start_date)
);
create index on liabilities (household_id, status) where deleted_at is null;
create index on liabilities (household_id, visibility) where deleted_at is null;
create index on liabilities (institution_id);
create index on liabilities (emi_day) where status = 'active' and deleted_at is null;
create trigger liabilities_touch before insert or update on liabilities
  for each row execute function app.touch_row();

-- -----------------------------------------------------------------------------
-- Who is on the hook, and for how much.
--
-- The mirror of investment_ownerships: a jointly held loan is split by
-- responsibility so that one household's ₹40L home loan is ₹20L against each
-- spouse's own net worth and ₹40L against the household's — counted once.
-- -----------------------------------------------------------------------------
create table liability_holders (
  id                 uuid primary key default gen_random_uuid(),
  liability_id       uuid not null references liabilities(id) on delete cascade,
  member_id          uuid not null references members(id) on delete cascade,
  holder_type        text not null default 'primary'
                       check (holder_type in ('primary','joint','guarantor')),
  responsibility_pct numeric(5,2) not null check (responsibility_pct > 0 and responsibility_pct <= 100),
  unique (liability_id, member_id)
);
create index on liability_holders (member_id);

create or replace function app.assert_responsibility_totals_100() returns trigger
  language plpgsql as $$
declare
  v_liability_id uuid := coalesce(new.liability_id, old.liability_id);
  v_total numeric(8,2);
begin
  if not exists (select 1 from liabilities where id = v_liability_id) then return null; end if;

  select coalesce(sum(responsibility_pct), 0) into v_total
    from liability_holders where liability_id = v_liability_id;

  if v_total <> 100 then
    raise exception
      'responsibility for liability % totals %%%, must total 100%%', v_liability_id, v_total
      using errcode = 'check_violation';
  end if;
  return null;
end $$;

create constraint trigger liability_holders_total_100
  after insert or update or delete on liability_holders
  deferrable initially deferred
  for each row execute function app.assert_responsibility_totals_100();

-- -----------------------------------------------------------------------------
-- Balance history, so the net-worth trend is drawn from what was recorded
-- rather than inferred. liabilities.outstanding is the current figure; this is
-- how it got there.
-- -----------------------------------------------------------------------------
create table liability_balances (
  id           uuid primary key default gen_random_uuid(),
  liability_id uuid not null references liabilities(id) on delete cascade,
  as_of_date   date not null default current_date,
  outstanding  numeric(18,4) not null check (outstanding >= 0),
  note         text,
  source       text not null default 'manual' check (source in ('manual','import','statement')),
  created_by   uuid references users(id),
  created_at   timestamptz not null default now(),
  unique (liability_id, as_of_date)
);
create index on liability_balances (liability_id, as_of_date desc);

-- -----------------------------------------------------------------------------
-- What a loan is secured against. An asset with a loan against it is
-- "encumbered": you own the flat, but not all of its value is yours yet.
-- -----------------------------------------------------------------------------
create table asset_liability_links (
  id            uuid primary key default gen_random_uuid(),
  liability_id  uuid not null references liabilities(id) on delete cascade,
  investment_id uuid not null references investments(id) on delete cascade,
  note          text,
  created_at    timestamptz not null default now(),
  unique (liability_id, investment_id)
);
create index on asset_liability_links (investment_id);

-- =============================================================================
-- Row-level security — the same predicate as assets, stated the same way.
-- A private debt is exactly as private as a private asset.
-- =============================================================================

create or replace function app.owes_liability(p_liability_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (
    select 1 from liability_holders h
      join members m on m.id = h.member_id
    where h.liability_id = p_liability_id
      and m.user_id = app.current_user_id()
      and m.deleted_at is null
  )
$$;

create or replace function app.can_modify_liability(p_liability_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (
    select 1 from liabilities l
    where l.id = p_liability_id
      and l.deleted_at is null
      and app.can_write_household(l.household_id)
      and ( app.can_read_record(l.household_id, l.visibility, 'liability', l.id,
                                app.owes_liability(l.id))
         or ( l.created_by = app.current_user_id()
              and not exists (select 1 from liability_holders h
                              where h.liability_id = l.id) ) )
  )
$$;

alter table liabilities enable row level security;

create policy liabilities_read on liabilities for select
  using (app.can_read_record(household_id, visibility, 'liability', id,
                             app.owes_liability(id)));

create policy liabilities_insert on liabilities for insert
  with check (app.can_write_household(household_id));

create policy liabilities_update on liabilities for update
  using (app.can_write_household(household_id)
         and app.can_read_record(household_id, visibility, 'liability', id,
                                 app.owes_liability(id)))
  with check (app.can_write_household(household_id));

create policy liabilities_delete on liabilities for delete
  using (app.can_write_household(household_id)
         and app.can_read_record(household_id, visibility, 'liability', id,
                                 app.owes_liability(id)));

alter table liability_holders enable row level security;
create policy liability_holders_read on liability_holders for select
  using (exists (select 1 from liabilities l where l.id = liability_id));
create policy liability_holders_write on liability_holders for all
  using (app.can_modify_liability(liability_id))
  with check (app.can_modify_liability(liability_id));

alter table liability_balances enable row level security;
create policy liability_balances_read on liability_balances for select
  using (exists (select 1 from liabilities l where l.id = liability_id));
create policy liability_balances_write on liability_balances for all
  using (app.can_modify_liability(liability_id))
  with check (app.can_modify_liability(liability_id));

-- A link is visible only if BOTH ends are. Otherwise the encumbrance on a
-- shared flat would reveal that a private loan exists, and roughly how big.
alter table asset_liability_links enable row level security;
create policy asset_liability_links_read on asset_liability_links for select
  using (exists (select 1 from liabilities l where l.id = liability_id)
         and exists (select 1 from investments i where i.id = investment_id));
create policy asset_liability_links_write on asset_liability_links for all
  using (app.can_modify_liability(liability_id)
         and exists (select 1 from investments i where i.id = investment_id))
  with check (app.can_modify_liability(liability_id)
              and exists (select 1 from investments i where i.id = investment_id));

-- -----------------------------------------------------------------------------
-- Extend the single grant rule to the new type. This is the whole point of V8:
-- adding a grantable record type means adding a branch here, and the function
-- raises for anything it does not know rather than defaulting open.
-- -----------------------------------------------------------------------------
create or replace function app.record_holder_member_ids(p_record_type text, p_record_id uuid)
  returns uuid[] language plpgsql stable security definer
  set search_path = public, app, pg_temp as $$
begin
  case p_record_type
    when 'investment' then
      return (select coalesce(array_agg(o.member_id), '{}')
              from investment_ownerships o where o.investment_id = p_record_id);
    when 'account' then
      return (select coalesce(array_agg(h.member_id), '{}')
              from account_holders h where h.account_id = p_record_id);
    when 'liability' then
      return (select coalesce(array_agg(h.member_id), '{}')
              from liability_holders h where h.liability_id = p_record_id);
    else
      raise exception 'record type % has no holder rule; extend app.record_holder_member_ids',
        p_record_type using errcode = 'feature_not_supported';
  end case;
end $$;

create or replace function app.record_created_by(p_record_type text, p_record_id uuid)
  returns uuid language plpgsql stable security definer
  set search_path = public, app, pg_temp as $$
begin
  case p_record_type
    when 'investment' then
      return (select i.created_by from investments i where i.id = p_record_id);
    when 'account' then
      return (select a.created_by from accounts a where a.id = p_record_id);
    when 'liability' then
      return (select l.created_by from liabilities l where l.id = p_record_id);
    else
      raise exception 'record type % has no creator rule; extend app.record_created_by',
        p_record_type using errcode = 'feature_not_supported';
  end case;
end $$;

-- =============================================================================
-- Derived views. security_invoker again: without it these would be a perfect
-- way to read past every policy above.
-- =============================================================================

create view liability_current with (security_invoker = true) as
select
  l.id            as liability_id,
  l.household_id,
  l.status,
  -- The latest recorded balance wins over the column, so a snapshot taken today
  -- is reflected immediately and the column is never silently stale.
  coalesce(b.outstanding, l.outstanding) as outstanding,
  b.as_of_date    as balance_as_of,
  l.principal,
  l.emi_amount,
  l.emi_day
from liabilities l
left join lateral (
  select lb.outstanding, lb.as_of_date
  from liability_balances lb
  where lb.liability_id = l.id
  order by lb.as_of_date desc, lb.created_at desc
  limit 1
) b on true
where l.deleted_at is null;

/**
 * Per-holder attributed debt — the mirror of investment_owner_value, and the
 * reason a joint loan is not counted twice.
 */
create view liability_holder_value with (security_invoker = true) as
select
  h.liability_id,
  h.member_id,
  h.holder_type,
  h.responsibility_pct,
  lc.household_id,
  lc.status,
  lc.outstanding,
  round(coalesce(lc.outstanding, 0) * h.responsibility_pct / 100.0, 4) as attributed_outstanding
from liability_holders h
join liability_current lc on lc.liability_id = h.liability_id;

comment on view liability_holder_value is
  'Per-holder attributed debt (outstanding x responsibility). Sums here never double-count a joint loan.';
