-- =============================================================================
-- V3 · Accounts, investments, ownership, valuations, and the visibility axis.
-- Refs: docs/04-data-model.md §4 §8, docs/01 §4-5, docs/07 §1
-- =============================================================================

-- -----------------------------------------------------------------------------
-- accounts — "which bank funds which SIP" (docs/01 §4 Linkage).
-- Numbers are stored MASKED by default; the full number is opt-in, envelope-
-- encrypted, and needs re-auth to view (docs/05 §5).
-- -----------------------------------------------------------------------------
create table accounts (
  id             uuid primary key default gen_random_uuid(),
  household_id   uuid not null references households(id) on delete cascade,
  institution_id uuid references institutions(id),
  account_kind   text not null
                   check (account_kind in ('savings','current','demat','folio',
                                           'wallet','locker','other')),
  label          text not null,
  number_masked  text,                          -- e.g. "XXXX3417"
  number_enc     bytea,                         -- envelope-encrypted full number
  ifsc           text,
  notes          text,
  visibility     text not null default 'private'
                   check (visibility in ('private','household','scoped')),
  deleted_at     timestamptz,
  version        int not null default 1,
  created_by     uuid references users(id),
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now()
);
create index on accounts (household_id) where deleted_at is null;
create index on accounts (institution_id);
create trigger accounts_touch before insert or update on accounts
  for each row execute function app.touch_row();

-- Joint accounts: several holders, no single "owner" column (docs/04 §4).
create table account_holders (
  id          uuid primary key default gen_random_uuid(),
  account_id  uuid not null references accounts(id) on delete cascade,
  member_id   uuid not null references members(id) on delete cascade,
  holder_type text not null default 'primary'
                check (holder_type in ('primary','joint')),
  unique (account_id, member_id)
);
create index on account_holders (member_id);

-- -----------------------------------------------------------------------------
-- investments — the core record. Everything countable lives here, including
-- the Universal type, so "can it track my ___?" is always yes (docs/01 §5).
-- -----------------------------------------------------------------------------
create table investments (
  id                uuid primary key default gen_random_uuid(),
  household_id      uuid not null references households(id) on delete cascade,
  type_id           uuid not null references investment_types(id),
  account_id        uuid references accounts(id) on delete set null,
  institution_id    uuid references institutions(id) on delete set null,
  title             text not null,
  status            text not null default 'active'
                      check (status in ('active','matured','closed','draft','archived')),
  invested_amount   numeric(18,4),               -- money is NEVER a float
  currency          text not null default 'INR',
  quantity          numeric(18,4),               -- grams, units, shares
  unit              text,
  cost_basis_method text not null default 'fifo'
                      check (cost_basis_method in ('fifo','average','manual')),
  start_date        date,
  maturity_date     date,
  storage_location  text,                        -- "home locker", "SBI locker Kakinada"
  -- Type-specific + custom field values, validated against
  -- investment_types.field_schema and custom_fields.
  attributes        jsonb not null default '{}',
  notes             text,
  -- Privacy now, disclosure on the defined event (docs/05 §3.4): a Private
  -- record with is_in_continuity=true stays hidden while the owner is around
  -- and surfaces only when emergency access unlocks.
  is_in_continuity  boolean not null default true,
  visibility        text not null default 'private'
                      check (visibility in ('private','household','scoped')),
  last_verified_at  timestamptz,
  deleted_at        timestamptz,
  version           int not null default 1,
  created_by        uuid references users(id),
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  constraint investment_amounts_are_not_negative
    check (invested_amount is null or invested_amount >= 0),
  constraint investment_quantity_is_not_negative
    check (quantity is null or quantity >= 0),
  constraint investment_dates_are_ordered
    check (maturity_date is null or start_date is null or maturity_date >= start_date)
);
create index on investments (household_id, status) where deleted_at is null;
create index on investments (household_id, visibility) where deleted_at is null;
create index on investments (type_id);
create index on investments (account_id);
create index on investments (institution_id);
create index on investments (maturity_date) where status = 'active' and deleted_at is null;
create index on investments (household_id, last_verified_at);
create index investments_attributes_gin on investments using gin (attributes);
create index investments_title_trgm on investments using gin (title gin_trgm_ops);
create trigger investments_touch before insert or update on investments
  for each row execute function app.touch_row();

-- -----------------------------------------------------------------------------
-- investment_ownerships — who owns it, and how much of it.
-- Joint holdings without double counting: totals multiply by share_pct.
-- An owner ALWAYS sees a record they own, whatever its visibility (docs/05 §3.1).
-- -----------------------------------------------------------------------------
create table investment_ownerships (
  id            uuid primary key default gen_random_uuid(),
  investment_id uuid not null references investments(id) on delete cascade,
  member_id     uuid not null references members(id) on delete cascade,
  holder_type   text not null default 'primary'
                  check (holder_type in ('primary','joint','guardian')),
  share_pct     numeric(5,2) not null check (share_pct > 0 and share_pct <= 100),
  unique (investment_id, member_id)
);
create index on investment_ownerships (member_id);

-- Shares must total exactly 100%. Enforced as a DEFERRED constraint trigger so a
-- transaction can rewrite the whole ownership set, and checked at COMMIT.
create or replace function app.assert_ownership_shares_total_100() returns trigger
  language plpgsql as $$
declare
  v_investment_id uuid := coalesce(new.investment_id, old.investment_id);
  v_total numeric(8,2);
  v_exists boolean;
begin
  -- If the investment itself is gone (cascade delete), there is nothing to check.
  select exists (select 1 from investments where id = v_investment_id) into v_exists;
  if not v_exists then return null; end if;

  select coalesce(sum(share_pct), 0) into v_total
    from investment_ownerships where investment_id = v_investment_id;

  if v_total <> 100 then
    raise exception
      'ownership shares for investment % total %%%, must total 100%%',
      v_investment_id, v_total
      using errcode = 'check_violation';
  end if;
  return null;
end $$;

create constraint trigger investment_ownerships_total_100
  after insert or update or delete on investment_ownerships
  deferrable initially deferred
  for each row execute function app.assert_ownership_shares_total_100();

-- -----------------------------------------------------------------------------
-- investment_nominees — nominee != heir (docs/01 §10). A nominee may be a
-- household member or a plain name; shares split across several.
-- -----------------------------------------------------------------------------
create table investment_nominees (
  id            uuid primary key default gen_random_uuid(),
  investment_id uuid not null references investments(id) on delete cascade,
  member_id     uuid references members(id) on delete set null,
  nominee_name  text,
  relationship  text,
  share_pct     numeric(5,2) not null default 100 check (share_pct > 0 and share_pct <= 100),
  created_at    timestamptz not null default now(),
  constraint nominee_needs_an_identity check (member_id is not null or nominee_name is not null)
);
create index on investment_nominees (investment_id);
create index on investment_nominees (member_id);

-- -----------------------------------------------------------------------------
-- valuations — point-in-time snapshots. Current value = latest snapshot.
-- Without one, a record is shown "at cost", never a fabricated number.
-- -----------------------------------------------------------------------------
create table valuations (
  id            uuid primary key default gen_random_uuid(),
  investment_id uuid not null references investments(id) on delete cascade,
  as_of_date    date not null default current_date,
  value         numeric(18,4) not null check (value >= 0),
  quantity      numeric(18,4),
  source        text not null default 'manual'
                  check (source in ('manual','import','quote_api')),
  note          text,
  created_by    uuid references users(id),
  created_at    timestamptz not null default now(),
  unique (investment_id, as_of_date)
);
create index on valuations (investment_id, as_of_date desc);

-- -----------------------------------------------------------------------------
-- record_visibility_grants — the "scoped" list: exactly who else may see this.
-- Present only when a record's visibility = 'scoped' (docs/04 §8).
-- -----------------------------------------------------------------------------
create table record_visibility_grants (
  id           uuid primary key default gen_random_uuid(),
  household_id uuid not null references households(id) on delete cascade,
  record_type  text not null
                 check (record_type in ('investment','liability','account','goal','document')),
  record_id    uuid not null,
  member_id    uuid not null references members(id) on delete cascade,
  created_by   uuid references users(id),
  created_at   timestamptz not null default now(),
  unique (record_type, record_id, member_id)
);
create index on record_visibility_grants (record_type, record_id);
create index on record_visibility_grants (member_id);

-- -----------------------------------------------------------------------------
-- investment_tags
-- -----------------------------------------------------------------------------
create table investment_tags (
  investment_id uuid not null references investments(id) on delete cascade,
  tag_id        uuid not null references tags(id) on delete cascade,
  primary key (investment_id, tag_id)
);
