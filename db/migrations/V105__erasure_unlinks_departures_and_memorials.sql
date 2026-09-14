-- =============================================================================
-- V105 · A departure or memorial that names someone no longer stops their erasure.
-- Refs: V40, V41, lifecycle/AccountPurge.kt, docs/05 §8
--
-- household_departures.started_by and destination_household_id, and
-- member_memorials.marked_by and reversed_by, are `on delete set null`.
-- PostgreSQL carries that out as an UPDATE on the row, and the BEFORE UPDATE
-- triggers that keep these rows from being rewritten fired for it. So an admin
-- who had asked someone to leave or marked someone as passed away, or a person
-- who had left and taken their records into a household of their own, could
-- never be erased: the purge deleted the user (or that household), the trigger
-- raised, the whole purge rolled back, and the sweep retried it every hour.
--
-- The triggers now let exactly that through: with no user on the connection
-- (only the owner-connection sweeps run that way), an update whose only change
-- is one of those references going from a value to null. Anything else is
-- checked as before, and nobody signed in can clear a reference this way.
-- =============================================================================

create or replace function app.departure_changes_are_limited() returns trigger
  language plpgsql as $$
declare
  v_actor uuid := app.current_user_id();
begin
  -- The person or household it named was erased: the foreign key unlinks it.
  if v_actor is null
     and ((old.started_by is not null and new.started_by is null)
       or (old.destination_household_id is not null and new.destination_household_id is null))
     and (new.started_by is null or new.started_by = old.started_by)
     and (new.destination_household_id is null or new.destination_household_id = old.destination_household_id)
     and to_jsonb(new) - 'started_by' - 'destination_household_id'
       = to_jsonb(old) - 'started_by' - 'destination_household_id' then
    return new;
  end if;
  if new.household_id <> old.household_id or new.member_id <> old.member_id
     or new.user_id <> old.user_id or new.started_by is distinct from old.started_by
     or new.started_by_admin <> old.started_by_admin
     or new.requested_at <> old.requested_at or new.effective_at <> old.effective_at then
    raise exception 'a departure''s date and people cannot be changed' using errcode = 'check_violation';
  end if;
  if old.cancelled_at is not null or old.completed_at is not null then
    raise exception 'that departure has already ended' using errcode = 'check_violation';
  end if;
  if v_actor is not null then
    if new.completed_at is not null or new.destination_household_id is distinct from old.destination_household_id then
      raise exception 'only the lifecycle sweep completes a departure' using errcode = 'insufficient_privilege';
    end if;
    if new.private_records <> old.private_records and v_actor <> old.user_id then
      raise exception 'only the person leaving decides what happens to their records'
        using errcode = 'insufficient_privilege';
    end if;
    if new.cancelled_at is not null and old.started_by_admin and v_actor = old.user_id then
      raise exception 'an admin asked for this departure; an admin can withdraw it'
        using errcode = 'insufficient_privilege';
    end if;
  end if;
  return new;
end $$;

create or replace function app.memorial_changes_are_limited() returns trigger
  language plpgsql as $$
begin
  -- The person who marked or reversed it was erased: the foreign key unlinks them.
  if app.current_user_id() is null
     and ((old.marked_by is not null and new.marked_by is null)
       or (old.reversed_by is not null and new.reversed_by is null))
     and (new.marked_by is null or new.marked_by = old.marked_by)
     and (new.reversed_by is null or new.reversed_by = old.reversed_by)
     and to_jsonb(new) - 'marked_by' - 'reversed_by'
       = to_jsonb(old) - 'marked_by' - 'reversed_by' then
    return new;
  end if;
  if new.household_id <> old.household_id or new.member_id <> old.member_id
     or new.user_id is distinct from old.user_id or new.basis <> old.basis
     or new.marked_at <> old.marked_at or new.marked_by is distinct from old.marked_by
     or new.note is distinct from old.note then
    raise exception 'a memorial can only be reversed, not rewritten' using errcode = 'check_violation';
  end if;
  if old.reversed_at is not null then
    raise exception 'a reversed memorial stays reversed; mark again if needed'
      using errcode = 'check_violation';
  end if;
  return new;
end $$;
