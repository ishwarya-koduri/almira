-- =============================================================================
-- V34 · The two seeded plaintext "where" fields are gone too.
-- Refs: docs/20-where-and-who.md §1, docs/known-issues.md 17
--
-- V33 retired the "where it is kept" columns. Two seeded type fields (V6) ask
-- the same question in plain text, into investments.attributes, where the
-- server can read, search, copy into a duplicate or a template, and print it:
--
--   business_equity · agreement_location  "Where the agreement is"
--   crypto          · wallet_hint         "Where the keys are"
--
-- The sealed "Where the original is" card (original_location, key_holder)
-- records both on any holding. This migration removes the fields, the same way
-- V33 removed storage_location.
--
-- IT NEVER DESTROYS A VALUE THAT SOMEONE WROTE. If any holding or template —
-- including one in the trash — carries a non-empty value under either key, the
-- migration refuses, says how many, and changes nothing.
-- =============================================================================

do $$
declare
  holdings_agreement  bigint;
  holdings_wallet     bigint;
  trashed             bigint;
  templates_agreement bigint;
  templates_wallet    bigint;
begin
  -- A count taken under row-level security sees only some rows (V33).
  if row_security_active('public.investments')
     or row_security_active('public.investment_templates') then
    raise exception using
      message = 'V34 must run as the table owner, without row-level security.',
      detail  = 'Row-level security is active for this role, so a count of plaintext '
             || '"where" values could miss rows it cannot see. Run migrations as the owner role.';
  end if;

  select count(*) filter (where length(coalesce(attributes ->> 'agreement_location', '')) > 0),
         count(*) filter (where length(coalesce(attributes ->> 'wallet_hint', '')) > 0),
         count(*) filter (where deleted_at is not null
                            and (length(coalesce(attributes ->> 'agreement_location', '')) > 0
                              or length(coalesce(attributes ->> 'wallet_hint', '')) > 0))
    into holdings_agreement, holdings_wallet, trashed
    from investments;
  select count(*) filter (where length(coalesce(attributes ->> 'agreement_location', '')) > 0),
         count(*) filter (where length(coalesce(attributes ->> 'wallet_hint', '')) > 0)
    into templates_agreement, templates_wallet
    from investment_templates;

  if holdings_agreement + holdings_wallet + templates_agreement + templates_wallet > 0 then
    raise exception using
      message = format(
        'V34 refused: %s value(s) still hold a plaintext "where" sentence. Nothing was changed. '
        'Holdings with "Where the agreement is" (agreement_location): %s. '
        'Holdings with "Where the keys are" (wallet_hint): %s. Of those holdings, in the trash: %s. '
        'Templates with agreement_location: %s. Templates with wallet_hint: %s.',
        holdings_agreement + holdings_wallet + templates_agreement + templates_wallet,
        holdings_agreement, holdings_wallet, trashed, templates_agreement, templates_wallet),
      hint = 'Seal them first, then start this build again. Run a build from before V34 '
          || '(for example commit 47c9e74) against this database. In its web client, '
          || 'unlock with the passphrase, open each holding, retype the sentence into '
          || '"Where the original is" (or "Key or papers with"), save, then edit the '
          || 'holding and empty the old field. Restore trashed holdings from Settings > '
          || 'Trash first; a template has no sealed card, so decide deliberately and clear '
          || 'it by hand. docs/20-where-and-who.md §1 has the steps and the queries that '
          || 'list the rows.';
  end if;
end
$$;

-- What is left can only be absent, null or ''; removing it destroys nothing.
update investments
   set attributes = attributes - 'agreement_location' - 'wallet_hint'
 where attributes ?| array['agreement_location', 'wallet_hint'];
update investment_templates
   set attributes = attributes - 'agreement_location' - 'wallet_hint'
 where attributes ?| array['agreement_location', 'wallet_hint'];

-- The types stop asking. Only types that have a fields array can match, so
-- jsonb_set never adds one where there was none; the order of the rest is kept.
update investment_types
   set field_schema = jsonb_set(
         field_schema,
         '{fields}',
         coalesce((select jsonb_agg(f order by n)
                     from jsonb_array_elements(field_schema -> 'fields') with ordinality as e(f, n)
                    where f ->> 'key' not in ('agreement_location', 'wallet_hint')), '[]'::jsonb),
         false)
 where jsonb_path_exists(field_schema,
         '$.fields[*] ? (@.key == "agreement_location" || @.key == "wallet_hint")');

delete from custom_fields where key in ('agreement_location', 'wallet_hint');

-- And they cannot come back under the same names.
alter table investment_types add constraint type_does_not_ask_for_plaintext_where
  check (not jsonb_path_exists(field_schema,
           '$.fields[*] ? (@.key == "agreement_location" || @.key == "wallet_hint")')
         and not (coalesce(field_schema -> 'common', '{}'::jsonb) ?| array['agreement_location', 'wallet_hint']));

alter table custom_fields add constraint custom_field_is_not_plaintext_where
  check (key not in ('agreement_location', 'wallet_hint'));

alter table investments add constraint no_plaintext_where_in_attributes
  check (not (attributes ?| array['agreement_location', 'wallet_hint']));

alter table investment_templates add constraint no_plaintext_where_in_template_attributes
  check (not (attributes ?| array['agreement_location', 'wallet_hint']));
