-- =============================================================================
-- V75 · The 31 January 2018 value, and a tax pack shared for one person.
-- Refs: docs/tax/capital-gains.md, docs/01 §9, docs/05 §7, V13, V20, V21
--
-- Two small additions the CA-grade capital-gains statement needs.
--
-- 1. investment_fmv_2018 — for listed shares and equity fund units bought on or
--    before 31 January 2018, the cost of a long-term sale is the higher of what
--    was paid and the lower of that day's fair market value and the sale value
--    (section 55(2)(ac)). There is no offline source for that price, so the
--    owner enters it, once per holding: it is a property of the security, the
--    same for every lot of it. It is kept per unit AS THE SHARE STOOD THAT DAY;
--    later splits are applied when the statement is worked out, from the
--    recorded split transactions, exactly as lot costs are.
--
--    Keyed to the holding rather than to a lot, because lots are rebuilt from
--    transactions on every change (V13) and would lose anything attached.
--
-- 2. guest_shares.scope_member_id — a tax pack is a return, and a return has
--    one taxpayer. A link "for my CA" can now name whose pack it is. Null keeps
--    the household view every existing link already has.
-- =============================================================================

create table investment_fmv_2018 (
  investment_id  uuid primary key references investments(id) on delete cascade,
  -- Per unit, in rupees. Zero is allowed (a suspended scrip), negative is not.
  fmv_per_unit   numeric(18,4) not null check (fmv_per_unit >= 0),
  -- Where the figure came from ("BSE high on 31 Jan 2018"), for the CA.
  source_note    text check (source_note is null or length(source_note) <= 200),
  recorded_by    uuid references users(id),
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  version        int not null default 1
);
create trigger investment_fmv_2018_touch before insert or update on investment_fmv_2018
  for each row execute function app.touch_row();

comment on table investment_fmv_2018 is
  'Owner-entered fair market value per unit on 31 January 2018, for section 55(2)(ac) '
  'grandfathering. Informational; read through the investment''s own visibility.';

-- Cascades from the investment, exactly like tax lots and valuations: a value
-- for a holding you cannot see does not exist for you. Writes route through
-- app.can_modify_investment, which V21 already closes to guest sessions.
alter table investment_fmv_2018 enable row level security;
create policy fmv_2018_read on investment_fmv_2018 for select
  using (exists (select 1 from investments i where i.id = investment_id));
create policy fmv_2018_write on investment_fmv_2018 for all
  using (app.can_modify_investment(investment_id))
  with check (app.can_modify_investment(investment_id));

alter table guest_shares
  add column scope_member_id uuid references members(id) on delete cascade;

comment on column guest_shares.scope_member_id is
  'For a tax_pack share: whose pack. Null is the household view.';

-- R__grants sets default privileges for new tables; stated here as well, so the
-- table's reach is readable in the file that creates it.
do $$
begin
  if exists (select 1 from pg_roles where rolname = 'almira_app') then
    execute 'grant select, insert, update, delete on investment_fmv_2018 to almira_app';
  end if;
end $$;
