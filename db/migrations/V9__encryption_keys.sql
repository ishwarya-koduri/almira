-- =============================================================================
-- V9 · Envelope encryption keys (docs/05 §4).
--
-- The database stores only WRAPPED data-encryption keys. The key that unwraps
-- them — the KEK — lives in a KMS and never touches this database, so a dump of
-- Postgres yields ciphertext and a wrapped key that cannot be opened with
-- anything the dump contains.
--
-- One DEK per household, so a compromise is bounded to one family's data rather
-- than every record in the system, and so a household can be re-keyed or erased
-- independently (docs/05 §8).
-- =============================================================================

create table encryption_keys (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references households(id) on delete cascade,
  key_version   int  not null default 1,
  -- The DEK, encrypted by the KEK. Never a plaintext key, in any environment.
  wrapped_dek   bytea not null,
  -- Which KEK wrapped it, so rotating the KEK does not orphan existing data.
  kek_id        text not null,
  -- Set when a newer version supersedes this one. Retired keys are KEPT:
  -- deleting them would make every record encrypted under them unreadable.
  retired_at    timestamptz,
  created_at    timestamptz not null default now(),
  unique (household_id, key_version)
);
create index on encryption_keys (household_id) where retired_at is null;

comment on table encryption_keys is
  'Wrapped per-household data keys. The KEK lives in a KMS and is never stored here.';

alter table encryption_keys enable row level security;

-- Readable by the household it belongs to. A wrapped key is useless without the
-- KEK, but there is still no reason for one household to see another's.
create policy encryption_keys_read on encryption_keys for select
  using (app.is_household_member(household_id));

-- No INSERT, UPDATE or DELETE policy exists, so the application role cannot
-- write this table directly. Provisioning goes through the function below,
-- which is the only path — a member cannot overwrite their household's key with
-- one of their own and make everyone else's records unreadable.

-- -----------------------------------------------------------------------------
-- Provision the household's data key, exactly once.
--
-- Returns the ACTIVE key whether this call created it or lost a race to create
-- it. Two concurrent first-writes would otherwise each generate a DEK, and
-- whichever lost would have encrypted data under a key nobody kept.
-- -----------------------------------------------------------------------------
create or replace function app.provision_household_dek(
    p_household_id uuid,
    p_wrapped_dek  bytea,
    p_kek_id       text
) returns table (out_key_version int, out_wrapped_dek bytea, out_kek_id text)
  language plpgsql security definer
  set search_path = public, app, pg_temp as $$
declare
  existing encryption_keys%rowtype;
begin
  if not app.is_household_member(p_household_id) then
    raise exception 'not a member of that household' using errcode = 'insufficient_privilege';
  end if;

  select * into existing from encryption_keys
   where household_id = p_household_id and retired_at is null
   order by key_version desc limit 1;

  if found then
    return query select existing.key_version, existing.wrapped_dek, existing.kek_id;
    return;
  end if;

  begin
    insert into encryption_keys (household_id, key_version, wrapped_dek, kek_id)
      values (p_household_id, 1, p_wrapped_dek, p_kek_id);
  exception when unique_violation then
    -- Lost the race; the winner's key is the one that counts.
    select * into existing from encryption_keys
     where household_id = p_household_id and retired_at is null
     order by key_version desc limit 1;
    return query select existing.key_version, existing.wrapped_dek, existing.kek_id;
    return;
  end;

  return query select 1, p_wrapped_dek, p_kek_id;
end $$;
