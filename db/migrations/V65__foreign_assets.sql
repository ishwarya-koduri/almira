-- =============================================================================
-- V65 · Something held abroad, in the currency it is held in.
-- Refs: docs/01 §4, docs/07 §1 "Valuation & money", V23 (exchange rates)
--
-- V23 made money in more than one currency possible: a holding keeps its own
-- currency and the rupee figure beside it is a rendering with a dated rate.
-- But nothing in the taxonomy said "this is abroad", so a US brokerage account
-- or an NRE-style deposit in Dubai was filed as "Anything Else" in rupees —
-- which is exactly the conversion-at-a-guess V23 exists to prevent.
--
-- A category of its own rather than a type under Alternatives: people look for
-- "abroad" first and the kind of asset second, and the handover list treats a
-- foreign account differently from art or crypto (docs/22 §3).
--
-- Also: investments.currency is now held to a three-letter code. It was free
-- text, and the API was the only thing between a typo and a total that could
-- never find a rate for it.
-- =============================================================================

insert into asset_categories (code, label, icon, color, sort) values
  ('foreign', 'Held Abroad', 'globe', '#4F6D7A', 115)
on conflict (code) do nothing;

insert into investment_types (category_id, code, label, icon, sort, field_schema)
select c.id, 'foreign_asset', 'Held abroad', 'globe', 10, jsonb_build_object(
  'common', jsonb_build_object(
    'invested_amount', jsonb_build_object(
      'label', 'Amount, in its own currency', 'group', 'essential', 'sort', 30, 'required', true,
      'help', 'What it is worth or what you put in, in the currency it is held in.'),
    'currency', jsonb_build_object(
      'label', 'Currency', 'group', 'essential', 'sort', 25, 'required', true,
      'help', 'Kept in this currency. The rupee figure beside it uses a dated rate.'),
    'start_date', jsonb_build_object('label', 'Opened or bought on', 'group', 'more', 'sort', 60, 'required', false)
  ),
  'fields', jsonb_build_array(
    jsonb_build_object('key', 'country', 'label', 'Country', 'dataType', 'text',
      'group', 'essential', 'sort', 10, 'required', true, 'placeholder', 'United States, UAE…'),
    jsonb_build_object('key', 'holding_kind', 'label', 'What kind', 'dataType', 'select',
      'group', 'essential', 'sort', 20, 'required', false,
      'options', jsonb_build_array(
        jsonb_build_object('value', 'bank_account', 'label', 'Bank account'),
        jsonb_build_object('value', 'deposit', 'label', 'Fixed deposit'),
        jsonb_build_object('value', 'shares', 'label', 'Shares or ETFs'),
        jsonb_build_object('value', 'fund', 'label', 'Fund'),
        jsonb_build_object('value', 'stock_awards', 'label', 'RSUs or ESOPs'),
        jsonb_build_object('value', 'retirement', 'label', 'Retirement account'),
        jsonb_build_object('value', 'property', 'label', 'Property'),
        jsonb_build_object('value', 'other', 'label', 'Something else'))),
    jsonb_build_object('key', 'account_hint', 'label', 'Account ending', 'dataType', 'text',
      'group', 'more', 'sort', 70, 'required', false,
      'help', 'The last four digits are enough for your family to find it.'),
    jsonb_build_object('key', 'beneficiary_named', 'label', 'Beneficiary named with the institution', 'dataType', 'bool',
      'group', 'more', 'sort', 80, 'required', false,
      'help', 'Abroad this is often called a beneficiary or a transfer-on-death designation.'),
    jsonb_build_object('key', 'in_schedule_fa', 'label', 'Declared in Schedule FA', 'dataType', 'bool',
      'group', 'more', 'sort', 90, 'required', false,
      'help', 'Residents list foreign assets in Schedule FA of the income tax return.')
  )
)
from asset_categories c
where c.code = 'foreign'
  and not exists (select 1 from investment_types t where t.code = 'foreign_asset' and t.household_id is null);

-- Existing rows are checked, not assumed: a lower-case code is fixed, and
-- anything else stops the migration with the rows to look at, because
-- guessing what "dollars" meant is not a migration's job.
update investments set currency = upper(currency) where currency ~ '^[a-z]{3}$';
update investment_templates set currency = upper(currency) where currency ~ '^[a-z]{3}$';

do $$
declare bad int;
begin
  select count(*) into bad from investments where currency !~ '^[A-Z]{3}$';
  if bad > 0 then
    raise exception 'V65: % investment(s) have a currency that is not a three-letter code. '
      'Find them with: select id, currency from investments where currency !~ ''^[A-Z]{3}$''; '
      'set each to its ISO 4217 code, then re-run.', bad;
  end if;
end $$;

alter table investments add constraint investment_currency_is_a_code
  check (currency ~ '^[A-Z]{3}$');
