-- =============================================================================
-- V33 · The plaintext "where it is kept" columns are gone.
-- Refs: docs/20-where-and-who.md §1, docs/known-issues.md 17
--
-- Where the original is, and who holds the key, are recorded sealed
-- (sealed_values, field keys `original_location` and `key_holder`, V22/V28).
-- Three older columns held the same sentence in plain text, where the server
-- could read, search and print it:
--
--   investments.storage_location           (V3)
--   investment_templates.storage_location  (V16)
--   estate_documents.location              (V18)
--
-- and eight seeded types asked for it (field_schema.common.storage_location,
-- V6). This migration removes all of it, so "no plaintext fallback" is true of
-- the database and not only of the web client.
--
-- IT NEVER DESTROYS A NOTE THAT SOMEONE WROTE. The server cannot seal a note
-- for anyone, because it has no key. So if any of those columns, or any
-- `attributes.storage_location`, holds a non-empty value — including in the
-- trash — the migration refuses, says how many, and changes nothing. Seal and
-- clear them first (see the message below), then start the server again.
--
-- Dropped rather than renamed and revoked:
--   · after the refusal check there is nothing in the columns to keep;
--   · a column-level REVOKE does not bind the table owner, and the owner pool
--     is what migrations, sweeps and restores run as;
--   · `select i.*` (the investment, template and estate queries) would fail
--     for the app role on a revoked column, so every read would have to change
--     anyway, and a renamed column is still in every dump and restore.
-- =============================================================================

do $$
declare
  investments_n   bigint;
  trashed_n       bigint;
  templates_n     bigint;
  estate_n        bigint;
  estate_trash_n  bigint;
  attributes_n    bigint;
  tattributes_n   bigint;
begin
  -- A count taken under row-level security sees only some rows, and "zero"
  -- would then drop everyone else's notes. Refuse rather than trust it.
  if row_security_active('public.investments')
     or row_security_active('public.investment_templates')
     or row_security_active('public.estate_documents') then
    raise exception using
      message = 'V33 must run as the table owner, without row-level security.',
      detail  = 'Row-level security is active for this role, so a count of plaintext '
             || 'locations could miss rows it cannot see. Run migrations as the owner role.';
  end if;

  select count(*) filter (where deleted_at is null),
         count(*) filter (where deleted_at is not null)
    into investments_n, trashed_n
    from investments where length(storage_location) > 0;
  select count(*) into templates_n
    from investment_templates where length(storage_location) > 0;
  select count(*) filter (where deleted_at is null),
         count(*) filter (where deleted_at is not null)
    into estate_n, estate_trash_n
    from estate_documents where length(location) > 0;
  select count(*) into attributes_n
    from investments
   where attributes ? 'storage_location'
     and length(coalesce(attributes ->> 'storage_location', '')) > 0;
  select count(*) into tattributes_n
    from investment_templates
   where attributes ? 'storage_location'
     and length(coalesce(attributes ->> 'storage_location', '')) > 0;

  if investments_n + trashed_n + templates_n + estate_n + estate_trash_n
     + attributes_n + tattributes_n > 0 then
    raise exception using
      message = format(
        'V33 refused: %s record(s) still hold a plaintext location. Nothing was changed. '
        'Holdings: %s (of which in the trash: %s). Wills and paperwork: %s (deleted: %s). '
        'Templates: %s. Holdings with attributes.storage_location: %s. '
        'Templates with attributes.storage_location: %s.',
        investments_n + trashed_n + templates_n + estate_n + estate_trash_n
          + attributes_n + tattributes_n,
        investments_n + trashed_n, trashed_n, estate_n + estate_trash_n, estate_trash_n,
        templates_n, attributes_n, tattributes_n),
      hint = 'Seal them first, then start this build again. Run a build from before V33 '
          || '(for example commit 02a198d) against this database. In its web client, '
          || 'unlock with the passphrase, open each record (a holding''s detail screen, '
          || 'or "Where the original is" on a will under For my family) and press '
          || '"Seal it, and clear the unsealed note". Restore trashed holdings from '
          || 'Settings > Trash first. A note the button does not offer to move, a '
          || 'template, a deleted will or an attributes value has no button: decide '
          || 'deliberately, then either retype it in the sealed card and set the column '
          || 'to an empty string, or clear it by hand. docs/20-where-and-who.md §1 has '
          || 'the steps and the queries that list the rows.';
  end if;
end
$$;

-- -----------------------------------------------------------------------------
-- What is left can only be NULL, '' or an empty attribute; removing it destroys
-- nothing anyone wrote.
-- -----------------------------------------------------------------------------

alter table investments          drop column storage_location;
alter table investment_templates drop column storage_location;
alter table estate_documents     drop column location;

update investments          set attributes = attributes - 'storage_location'
 where attributes ? 'storage_location';
update investment_templates set attributes = attributes - 'storage_location'
 where attributes ? 'storage_location';

-- The seeded types (and any custom or promoted type) stop asking for it.
update investment_types
   set field_schema = jsonb_set(
         field_schema #- '{common,storage_location}',
         '{fields}',
         coalesce((select jsonb_agg(f order by n)
                     from jsonb_array_elements(coalesce(field_schema -> 'fields', '[]'::jsonb))
                          with ordinality as e(f, n)
                    where f ->> 'key' is distinct from 'storage_location'), '[]'::jsonb))
 where field_schema -> 'common' ? 'storage_location'
    or jsonb_path_exists(field_schema, '$.fields[*] ? (@.key == "storage_location")');

delete from custom_fields where key = 'storage_location';

-- -----------------------------------------------------------------------------
-- And it cannot come back under the same name. The service refuses first, with
-- a sentence; these are the backstop, like every other rule in this schema.
-- -----------------------------------------------------------------------------

alter table investment_types add constraint type_does_not_ask_for_plaintext_location
  check (not (field_schema -> 'common' ? 'storage_location')
         and not jsonb_path_exists(field_schema, '$.fields[*] ? (@.key == "storage_location")'));

alter table custom_fields add constraint custom_field_is_not_plaintext_location
  check (key <> 'storage_location');

alter table investments add constraint no_plaintext_location_in_attributes
  check (not (attributes ? 'storage_location'));

alter table investment_templates add constraint no_plaintext_location_in_template_attributes
  check (not (attributes ? 'storage_location'));
