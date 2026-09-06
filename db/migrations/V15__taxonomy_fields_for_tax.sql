-- =============================================================================
-- V15 · Fields the tax layer needs and the seeded taxonomy was missing.
--
-- Found by building the deduction meters: health insurance had no premium
-- FREQUENCY, so a ₹2,000 monthly premium read as ₹2,000 a year; and NPS had a
-- "claimed under 80CCD(1B)" flag with no amount beside it, so the meter had
-- nothing to count.
--
-- V6 is the initial seed and is left alone — an applied migration is history.
-- Later changes to reference data are their own migrations, which is also why
-- db/taxonomy.py only ever generates V6.
-- =============================================================================

-- Appends a field to a type's schema, unless it is already there. Idempotent, so
-- re-running against a partially-updated database is safe.
create or replace function app.add_type_field(p_type_code text, p_field jsonb)
  returns void language plpgsql as $$
begin
  update investment_types
     set field_schema = jsonb_set(
           field_schema, '{fields}',
           coalesce(field_schema -> 'fields', '[]'::jsonb) || p_field
         ),
         schema_version = schema_version + 1
   where code = p_type_code
     and household_id is null
     and not exists (
       select 1 from jsonb_array_elements(coalesce(field_schema -> 'fields', '[]'::jsonb)) f
       where f ->> 'key' = p_field ->> 'key'
     );
end $$;

-- A health premium is quoted monthly as often as yearly, and the difference is
-- a factor of twelve on someone's 80D meter.
select app.add_type_field('insurance_health', jsonb_build_object(
  'key', 'premium_frequency',
  'label', 'Premium frequency',
  'dataType', 'select',
  'group', 'more',
  'sort', 35,
  'required', false,
  'options', jsonb_build_array(
    jsonb_build_object('value', 'monthly',     'label', 'Monthly'),
    jsonb_build_object('value', 'quarterly',   'label', 'Quarterly'),
    jsonb_build_object('value', 'half_yearly', 'label', 'Half-yearly'),
    jsonb_build_object('value', 'yearly',      'label', 'Yearly'),
    jsonb_build_object('value', 'single',      'label', 'Single premium')
  ),
  'help', 'So we count the right amount towards 80D.'
));

-- NPS had the 80CCD(1B) flag but no amount to put against it.
select app.add_type_field('nps', jsonb_build_object(
  'key', 'yearly_contribution',
  'label', 'Yearly contribution',
  'dataType', 'money',
  'group', 'more',
  'sort', 35,
  'required', false,
  'help', 'What goes in each year — used for the 80CCD(1B) meter.'
));

-- EPF likewise: the employee share is what counts towards 80C, and there was
-- nowhere to say what it comes to over a year.
select app.add_type_field('epf', jsonb_build_object(
  'key', 'yearly_contribution',
  'label', 'Yearly employee contribution',
  'dataType', 'money',
  'group', 'more',
  'sort', 55,
  'required', false,
  'help', 'Your own share over a year — the employer''s share is not deductible.'
));
