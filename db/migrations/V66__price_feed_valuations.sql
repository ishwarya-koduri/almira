-- =============================================================================
-- V66 · Valuations from the day's published prices.
-- Refs: docs/13 §6, docs/07 §1 "Valuation & money", V3 (valuations)
--
-- Until now every value was typed by hand. AMFI publishes every mutual fund's
-- NAV each evening, and NSE and BSE publish every listed share's closing price;
-- a holding that says which fund or share it is and how many units it has can
-- be valued from those without anyone typing a figure.
--
-- Three rules, and this migration is where the database holds them:
--
--   1. A price-fed valuation says where it came from and what it was: the
--      published file (`price_source`), the price per unit, and the scheme or
--      share it matched. The date is the price's own date, never the day the
--      job ran — "as of" is the thing a reader needs.
--
--   2. Only the feed writes one. The application role can no longer insert or
--      update a valuation marked `price_feed`, so a "Valued at NAV" stamp can
--      never be put on a figure someone typed. The job writes on the system
--      connection, which is the owner.
--
--   3. It never replaces what someone entered. That rule lives in the job
--      (PriceFeedValuations), because it is about which rows to write at all:
--      a user's valuation on or after the price date wins, and one before it
--      stays in the history, shown beside the new figure.
-- =============================================================================

alter table valuations drop constraint if exists valuations_source_check;
alter table valuations add constraint valuations_source_check
  check (source in ('manual', 'import', 'quote_api', 'price_feed'));

alter table valuations
  add column price_source text check (price_source in ('amfi', 'nse', 'bse')),
  add column unit_price   numeric(18,6) check (unit_price > 0),
  -- The AMFI scheme code or the ISIN the holding was matched on, so a figure can
  -- be traced back to one line of one file.
  add column instrument   text check (length(instrument) <= 20);

alter table valuations add constraint price_feed_valuation_is_labelled
  check ((source = 'price_feed') = (price_source is not null and unit_price is not null and instrument is not null));

-- Rule 2. Same predicate as before for everything else, so nothing a person
-- could do yesterday is refused today.
drop policy valuations_write on valuations;
create policy valuations_write on valuations for all
  using (app.can_modify_investment(investment_id))
  with check (app.can_modify_investment(investment_id) and source <> 'price_feed');

-- A mutual fund had no field saying which fund it is in a form a file can be
-- matched on: the scheme name is free text. The ISIN is printed on every
-- account statement; the AMFI scheme code is what the NAV file leads with.
-- Either is accepted in the one field.
select app.add_type_field(code, jsonb_build_object(
  'key', 'isin',
  'label', 'ISIN or AMFI scheme code',
  'dataType', 'text',
  'group', 'more',
  'sort', 75,
  'required', false,
  'placeholder', 'INF879O01027 or 122639',
  'help', 'On your account statement. With the units held, the value follows the daily NAV.'
))
from (values ('mf_sip'), ('mf_lumpsum')) as t(code);
