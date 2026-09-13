-- =============================================================================
-- Stored-digest check: run after the structural sweep passes (docs/17 §6).
--
--   psql "$OWNER_URL" -v ON_ERROR_STOP=1 -f deploy/restore/digest-check.sql
--
-- Compares every zero-knowledge ciphertext against the SHA-256 recorded when it
-- was written (V31). Catches the one thing the sweep cannot: a changed byte
-- inside a well-formed envelope. Meaningful only after a WHOLE restore — see the
-- note in V31 about data-only restores.
--
-- Names each mismatching row; prints no ciphertext; reads only.
-- Exits non-zero when any digest disagrees.
-- =============================================================================
\set ON_ERROR_STOP on
\pset footer off

create temp view digest_mismatches as
  select 'sealed_values' as tbl, id, 'ciphertext' as col,
         format('household %s · %s %s · field %s', household_id, record_type, record_id, field_key) as locator
    from sealed_values
   where ciphertext_sha256 is distinct from sha256(convert_to(ciphertext, 'UTF8'))
  union all
  select 'e2e_keys', id, 'wrapped_key', format('household %s · user %s', household_id, user_id)
    from e2e_keys
   where wrapped_key_sha256 is distinct from sha256(convert_to(wrapped_key, 'UTF8'))
  union all
  select 'e2e_keys', id, 'verifier', format('household %s · user %s', household_id, user_id)
    from e2e_keys
   where verifier_sha256 is distinct from sha256(convert_to(verifier, 'UTF8'));

\echo 'Stored-digest check of zero-knowledge ciphertext (docs/17 §6)'
select (select count(*) from sealed_values) + 2 * (select count(*) from e2e_keys) as values_checked,
       (select count(*) from digest_mismatches) as mismatch_count,
       (select count(*) from digest_mismatches) > 0 as has_mismatches
\gset
\echo '  values checked: ' :values_checked

\if :has_mismatches
  \echo ''
  \echo '  CHANGED SINCE WRITTEN — each is well-formed and will not open:'
  select tbl as "table", id, col as "column", locator from digest_mismatches order by tbl, id, col;
  \echo ''
  \echo '  Do not put this restore into service. docs/17 §6.'
  select set_config('digest.mismatches', :'mismatch_count', false) \g /dev/null
  do $$ begin
    raise exception 'digest check: % value(s) changed since written', current_setting('digest.mismatches');
  end $$;
\else
  \echo '  0 mismatches.'
\endif
