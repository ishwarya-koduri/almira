-- =============================================================================
-- V13 · Transactions, tax lots, and disposals.
-- Refs: docs/01 §8 §9, docs/04 §4, docs/10 Epics 2.2 and 2.3
--
-- Everything in Phase 2 that is arithmetic rather than record-keeping rests on
-- this: XIRR needs dated cash flows, and capital gains need to know which
-- specific units were sold and what they cost. A "total invested" column can
-- answer neither.
-- =============================================================================

create table transactions (
  id             uuid primary key default gen_random_uuid(),
  investment_id  uuid not null references investments(id) on delete cascade,
  txn_type       text not null
                   check (txn_type in ('buy','sell','contribution','withdrawal',
                                       'interest','dividend','fee','split','bonus')),
  -- Money that moved. Always positive; direction comes from txn_type, because a
  -- sign convention that lives in the data is one every reader has to remember.
  amount         numeric(18,4) check (amount is null or amount >= 0),
  quantity       numeric(18,6) check (quantity is null or quantity >= 0),
  price          numeric(18,6) check (price is null or price >= 0),
  txn_date       date not null,
  from_account_id uuid references accounts(id) on delete set null,
  -- For a split or bonus: the ratio, as "2:1" or "1:1".
  ratio          text,
  notes          text,
  created_by     uuid references users(id),
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  version        int not null default 1,

  -- A quantity-bearing transaction without a quantity cannot be replayed into
  -- lots, and would silently produce wrong capital gains later.
  constraint quantity_txns_need_a_quantity check (
    txn_type not in ('buy','sell') or quantity is not null
  ),
  constraint ratio_only_for_corporate_actions check (
    ratio is null or txn_type in ('split','bonus')
  )
);
create index on transactions (investment_id, txn_date, id);
create index on transactions (from_account_id);
create trigger transactions_touch before insert or update on transactions
  for each row execute function app.touch_row();

comment on table transactions is
  'Dated cash and unit movements. The source of truth for returns and tax lots — '
  'both of which are DERIVED from this table and can always be rebuilt from it.';

-- -----------------------------------------------------------------------------
-- Tax lots: which units were bought, when, and at what cost.
--
-- India taxes a disposal by how long THOSE units were held, so "average cost"
-- alone cannot answer the question — you need the acquisition date of the
-- specific units sold. Lots are rebuilt from transactions rather than mutated
-- in place, so they can never drift from the movements they describe.
-- -----------------------------------------------------------------------------
create table tax_lots (
  id            uuid primary key default gen_random_uuid(),
  investment_id uuid not null references investments(id) on delete cascade,
  acquired_on   date not null,
  quantity      numeric(18,6) not null check (quantity > 0),
  unit_cost     numeric(18,6) not null check (unit_cost >= 0),
  remaining_qty numeric(18,6) not null check (remaining_qty >= 0),
  -- The buy that opened it, for tracing a lot back to its movement.
  source_txn_id uuid references transactions(id) on delete cascade,
  sequence      int not null,
  created_at    timestamptz not null default now(),
  constraint remaining_cannot_exceed_quantity check (remaining_qty <= quantity)
);
create index on tax_lots (investment_id, acquired_on, sequence);

-- -----------------------------------------------------------------------------
-- Disposals: which lots a sale consumed.
--
-- Realized gain is recorded rather than recomputed at read time, because the
-- classification depends on the rules in force on the date of sale. A summary
-- printed for a past financial year must not change because this year's rules
-- differ (docs/01 §9).
-- -----------------------------------------------------------------------------
create table tax_lot_disposals (
  id             uuid primary key default gen_random_uuid(),
  investment_id  uuid not null references investments(id) on delete cascade,
  tax_lot_id     uuid references tax_lots(id) on delete cascade,
  sell_txn_id    uuid not null references transactions(id) on delete cascade,
  quantity       numeric(18,6) not null check (quantity > 0),
  cost_basis     numeric(18,4) not null check (cost_basis >= 0),
  proceeds       numeric(18,4) not null check (proceeds >= 0),
  acquired_on    date not null,
  disposed_on    date not null,
  holding_days   int not null,
  -- 'short' or 'long', by the rule for this asset class on the disposal date.
  gain_term      text not null check (gain_term in ('short','long')),
  gain           numeric(18,4) not null,
  created_at     timestamptz not null default now()
);
create index on tax_lot_disposals (investment_id, disposed_on);
create index on tax_lot_disposals (sell_txn_id);

-- =============================================================================
-- Row-level security — all three cascade from the investment, exactly like
-- valuations. A transaction on a record you cannot see does not exist for you,
-- and neither do the gains derived from it.
-- =============================================================================

alter table transactions enable row level security;
create policy transactions_read on transactions for select
  using (exists (select 1 from investments i where i.id = investment_id));
create policy transactions_write on transactions for all
  using (app.can_modify_investment(investment_id))
  with check (app.can_modify_investment(investment_id));

alter table tax_lots enable row level security;
create policy tax_lots_read on tax_lots for select
  using (exists (select 1 from investments i where i.id = investment_id));
create policy tax_lots_write on tax_lots for all
  using (app.can_modify_investment(investment_id))
  with check (app.can_modify_investment(investment_id));

alter table tax_lot_disposals enable row level security;
create policy disposals_read on tax_lot_disposals for select
  using (exists (select 1 from investments i where i.id = investment_id));
create policy disposals_write on tax_lot_disposals for all
  using (app.can_modify_investment(investment_id))
  with check (app.can_modify_investment(investment_id));

-- -----------------------------------------------------------------------------
-- Cash flows for XIRR, in one place so every consumer agrees on the signs.
--
-- Negative is money leaving the household, positive is money arriving. Getting
-- this backwards does not fail — it returns a plausible, wrong rate — so the
-- convention lives here rather than in each caller.
-- -----------------------------------------------------------------------------
create view investment_cash_flows with (security_invoker = true) as
select
  t.investment_id,
  t.txn_date as flow_date,
  t.txn_type,
  case t.txn_type
    when 'buy'          then -coalesce(t.amount, 0)
    when 'contribution' then -coalesce(t.amount, 0)
    when 'fee'          then -coalesce(t.amount, 0)
    when 'sell'         then  coalesce(t.amount, 0)
    when 'withdrawal'   then  coalesce(t.amount, 0)
    when 'interest'     then  coalesce(t.amount, 0)
    when 'dividend'     then  coalesce(t.amount, 0)
    else 0                     -- splits and bonuses move units, not money
  end as flow_amount
from transactions t
where t.txn_type in ('buy','contribution','fee','sell','withdrawal','interest','dividend');

comment on view investment_cash_flows is
  'Signed, dated cash flows. Negative = money out. The single definition of sign '
  'convention, so no two callers can disagree about it.';
