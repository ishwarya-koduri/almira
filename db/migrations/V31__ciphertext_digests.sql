-- =============================================================================
-- V31 · A stored digest of every zero-knowledge ciphertext.
-- Refs: docs/17 §6, docs/12 §9
--
-- The last of three restore protections, and deliberately last:
--
--   1. page checksums (initdb)      — a flipped bit on disk is refused, not served;
--   2. the structural sweep         — truncation and header damage after a restore;
--   3. THIS                         — a flipped bit INSIDE a well-formed envelope,
--                                     which parses perfectly and will not open.
--
-- The server cannot tell a damaged ciphertext from a good one by reading it —
-- reading it is the thing it cannot do — so it writes down what the bytes were,
-- and a restore compares (deploy/restore/digest-check.sql).
--
-- WHAT THIS IS NOT: an integrity control against an attacker. Anyone who can
-- change a ciphertext through SQL fires the trigger and gets a matching digest
-- for free; anyone with raw write access can rewrite both columns. It detects
-- accidental damage between a write and a restore — storage, the backup file,
-- the copy — and nothing else. docs/12 §9 explains why verifying it on write
-- would be tautological.
--
-- WHY A TRIGGER, not application code: every path that writes a ciphertext
-- gets it, including ones written next year, and the frozen v1 API is untouched.
-- The digest is over the stored TEXT, exactly as it sits in the column.
--
-- RESTORES MUST BE WHOLE. pg_dump creates triggers after it loads data, so a
-- full restore keeps the digests from the moment of the write, which is what
-- makes the comparison mean anything. A data-only restore into a schema that
-- already has the trigger would recompute every digest from the restored — and
-- possibly damaged — bytes, and the check would pass on anything. scripts/restore.sh
-- only does whole restores into an empty database.
-- =============================================================================

create function app.ciphertext_digests() returns trigger
  language plpgsql as $$
begin
  if tg_table_name = 'sealed_values' then
    new.ciphertext_sha256 := sha256(convert_to(new.ciphertext, 'UTF8'));
  elsif tg_table_name = 'e2e_keys' then
    new.wrapped_key_sha256 := sha256(convert_to(new.wrapped_key, 'UTF8'));
    new.verifier_sha256    := sha256(convert_to(new.verifier, 'UTF8'));
  end if;
  return new;
end $$;

alter table sealed_values add column ciphertext_sha256 bytea;
alter table e2e_keys add column wrapped_key_sha256 bytea,
                     add column verifier_sha256    bytea;

-- Backfill without firing the touch trigger: recording a digest is not an edit,
-- and bumping updated_at on every sealed value would tell each owner that all
-- their fields changed today.
alter table sealed_values disable trigger sealed_values_touch;
update sealed_values set ciphertext_sha256 = sha256(convert_to(ciphertext, 'UTF8'));
alter table sealed_values enable trigger sealed_values_touch;

alter table e2e_keys disable trigger e2e_keys_touch;
update e2e_keys set wrapped_key_sha256 = sha256(convert_to(wrapped_key, 'UTF8')),
                    verifier_sha256    = sha256(convert_to(verifier, 'UTF8'));
alter table e2e_keys enable trigger e2e_keys_touch;

alter table sealed_values alter column ciphertext_sha256 set not null;
alter table e2e_keys alter column wrapped_key_sha256 set not null,
                     alter column verifier_sha256    set not null;

create trigger sealed_values_digest before insert or update of ciphertext on sealed_values
  for each row execute function app.ciphertext_digests();
create trigger e2e_keys_digest before insert or update of wrapped_key, verifier on e2e_keys
  for each row execute function app.ciphertext_digests();
