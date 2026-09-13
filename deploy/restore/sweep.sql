-- =============================================================================
-- The structural sweep: run after EVERY restore, before anybody signs in.
--
--   psql "$OWNER_URL" -v ON_ERROR_STOP=1 -f deploy/restore/sweep.sql
--   (or ./scripts/restore.sh, which runs it for you)
--
-- WHY: a zero-knowledge ciphertext is the one kind of data the server cannot
-- tell is broken, because opening it is the thing the server cannot do. A
-- truncated or header-damaged restore goes back into service without a
-- complaint and fails months later, in front of the one person who needed it.
-- docs/17 §6.
--
-- WHAT IT CHECKS — the client's own parse rules (docs/12 §3), which are STRICTER
-- than what the server accepts on write (SealedFieldService accepts standard
-- base64 and 17 bytes). A row that fails here is a row no conforming client can
-- open, whether a restore damaged it or it was written that way.
--
--   sealed_values.ciphertext   base64url, no padding · ≥ 33 bytes decoded ·
--                              version byte 1 · envelope keyVersion ≥ 1
--   sealed_values.key_version  ≥ 1
--   e2e_keys.kdf_salt          base64url · ≥ 16 bytes decoded
--   e2e_keys.wrapped_key       an envelope, as above
--   e2e_keys.verifier          an envelope, as above
--   e2e_keys.iterations        ≥ 100 000
--
-- WHAT IT CANNOT SEE: a flipped bit inside the body of a well-formed envelope.
-- It parses perfectly and will not open. That is what the stored digest is for
-- (deploy/restore/digest-check.sql), and why this comes first: the cheap check
-- that catches the likely damage, then the one that catches the rest.
--
-- It names every defective row by primary key and column. It prints no
-- ciphertext. It reads only; it creates nothing but temporary functions, which
-- vanish with the session.
--
-- Exit status: non-zero when any row is defective (it raises an error after
-- listing them, under ON_ERROR_STOP), so a script stops on it. Zero defects: 0.
-- =============================================================================
\set ON_ERROR_STOP on
\pset footer off

-- Why this row is defective, or null if it is not. Never raises: a value that
-- cannot be decoded is a finding, not a crash that hides the next row.
create function pg_temp.envelope_defect(v text) returns text
language plpgsql immutable as $$
declare
  raw bytea;
  key_version int;
begin
  if v is null then return 'is null'; end if;
  if v ~ '=' then return 'has base64 padding; the format is base64url without it'; end if;
  if v !~ '^[A-Za-z0-9_-]*$' then return 'contains characters outside base64url'; end if;
  if length(v) % 4 = 1 then return 'has a length no base64 encoding can produce'; end if;
  begin
    raw := decode(translate(v, '-_', '+/') || repeat('=', (4 - length(v) % 4) % 4), 'base64');
  exception when others then
    return 'does not decode as base64url';
  end;
  if length(raw) < 33 then
    return format('decodes to %s bytes; the smallest envelope is 33', length(raw));
  end if;
  if get_byte(raw, 0) <> 1 then
    return format('has version byte %s; only 1 exists', get_byte(raw, 0));
  end if;
  -- Big-endian, read as signed exactly as the clients do: a high bit set means
  -- a negative key version, which they refuse.
  key_version := (get_byte(raw, 1) << 24) | (get_byte(raw, 2) << 16)
               | (get_byte(raw, 3) << 8) | get_byte(raw, 4);
  if key_version < 1 then
    return format('has envelope key version %s; it must be at least 1', key_version);
  end if;
  return null;
end $$;

create function pg_temp.salt_defect(v text) returns text
language plpgsql immutable as $$
declare raw bytea;
begin
  if v is null then return 'is null'; end if;
  if v !~ '^[A-Za-z0-9_-]*$' then return 'is not base64url without padding'; end if;
  if length(v) % 4 = 1 then return 'has a length no base64 encoding can produce'; end if;
  begin
    raw := decode(translate(v, '-_', '+/') || repeat('=', (4 - length(v) % 4) % 4), 'base64');
  exception when others then
    return 'does not decode as base64url';
  end;
  if length(raw) < 16 then return format('decodes to %s bytes; a salt is 16', length(raw)); end if;
  return null;
end $$;

create temp view sweep_defects as
  select 'sealed_values' as tbl, s.id, 'ciphertext' as col,
         pg_temp.envelope_defect(s.ciphertext) as defect,
         format('household %s · %s %s · field %s', s.household_id, s.record_type, s.record_id, s.field_key) as locator
    from sealed_values s
  union all
  select 'sealed_values', s.id, 'key_version',
         case when s.key_version < 1 then format('is %s; it must be at least 1', s.key_version) end,
         format('household %s · %s %s · field %s', s.household_id, s.record_type, s.record_id, s.field_key)
    from sealed_values s
  union all
  select 'e2e_keys', k.id, 'kdf_salt', pg_temp.salt_defect(k.kdf_salt),
         format('household %s · user %s', k.household_id, k.user_id)
    from e2e_keys k
  union all
  select 'e2e_keys', k.id, 'wrapped_key', pg_temp.envelope_defect(k.wrapped_key),
         format('household %s · user %s', k.household_id, k.user_id)
    from e2e_keys k
  union all
  select 'e2e_keys', k.id, 'verifier', pg_temp.envelope_defect(k.verifier),
         format('household %s · user %s', k.household_id, k.user_id)
    from e2e_keys k
  union all
  select 'e2e_keys', k.id, 'iterations',
         case when k.iterations < 100000 then format('is %s; the floor is 100000', k.iterations) end,
         format('household %s · user %s', k.household_id, k.user_id)
    from e2e_keys k;

\echo 'Structural sweep of zero-knowledge ciphertext (docs/17 §6)'
select (select count(*) from sealed_values) as sealed_values_checked,
       (select count(*) from e2e_keys)      as e2e_keys_checked
\gset
\echo '  rows checked: ' :sealed_values_checked ' sealed values, ' :e2e_keys_checked ' passphrase envelopes'

select count(*) as defect_count, count(*) > 0 as has_defects
  from sweep_defects where defect is not null
\gset

\if :has_defects
  \echo ''
  \echo '  DEFECTIVE ROWS — each will fail to open for its owner:'
  select tbl as "table", id, col as "column", defect, locator
    from sweep_defects where defect is not null
   order by tbl, id, col;
  \echo ''
  \echo '  Do not put this restore into service. Restore these rows from another'
  \echo '  backup, or tell the owners which fields are lost. docs/17 §6.'
  -- Fail the psql process, so a script stops here.
  select set_config('sweep.defects', :'defect_count', false) \g /dev/null
  do $$ begin
    raise exception 'structural sweep: % defective value(s)', current_setting('sweep.defects');
  end $$;
\else
  \echo '  0 defects.'
\endif
