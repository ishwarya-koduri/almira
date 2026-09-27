-- =============================================================================
-- What a gram cost, beside the total it makes.
--
-- Found by the owner using the app on a phone (2026-09-26): they typed 5000 in
-- "Amount paid" thinking it was the rate for a gram, put 4 in the weight, and
-- the almirah recorded five thousand rupees of gold instead of twenty. Nothing
-- refused it and nothing looked wrong, which is the worst kind of wrong: a net
-- worth that is quietly a quarter of the truth.
--
-- The form was ambiguous, not the arithmetic. Most people buying gold think in
-- rupees per gram; most people buying shares think in rupees per share; the
-- field asked for the total and never said so.
--
-- Owner's ruling (2026-09-26): *keep the total as the field of record and store
-- the rate per unit alongside as a convenience. Fill either, the other computes,
-- and show "rate x qty = total" before saving.*
--
-- So `invested_amount` stays exactly what it was, and everything that reads it
-- — returns, tax lots, the dashboard, the export — is untouched. This column is
-- what the person typed, kept so the form can show it again when they come back
-- and so nobody has to divide to find out what they paid per gram.
--
-- It applies to whatever has a quantity and a unit: the fourteen seeded types
-- that do (gold in its four forms, silver, shares, mutual funds, bonds, REITs,
-- ESOPs, ULIPs, IPO lots), and any household type with one.
-- =============================================================================

alter table investments
  add column rate_per_unit numeric(18, 4)
    check (rate_per_unit is null or rate_per_unit > 0);

comment on column investments.rate_per_unit is
  'What one unit cost, as the person typed it. A convenience beside invested_amount, '
  'which stays the field of record: rate x quantity = invested_amount (V152).';

-- A rate says nothing without something to multiply it by.
alter table investments add constraint rate_needs_a_quantity
  check (rate_per_unit is null or quantity is not null);
