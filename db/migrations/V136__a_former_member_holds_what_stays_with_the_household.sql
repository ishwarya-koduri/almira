-- =============================================================================
-- V136 · A former member: what an erased person held for the household stays
--        with the household, under nobody's name.
-- Refs: docs/05 §12.1, §12.7, docs/04 §12, V120, V135,
--       lifecycle/AccountPurge.kt, docs/known-issues.md ("A dormant household
--       with nobody who may take it on waits for good" — the erasure half)
--
-- The owner's answer (D8, 2026-09-15): "The departed person's own personal
-- data is erased on schedule — their right doesn't wait on absent relatives.
-- The household's records belong to the other members and survive. If the
-- data model can't express that split, that's the real fix."
--
-- V120 held the whole closure while the household was dormant: the account,
-- the sessions, the private records, all of it waited on someone else taking
-- the household on. The split the model lacked was a holder that is not a
-- person. Records name their holders through `members` rows, and a member row
-- without a login already means "a person with no account". What it could not
-- say is "nobody: someone who was here and has been erased". Reusing a managed
-- member row for that would let an invitation claim it (V7), and whoever
-- accepted would become the holder of what the erased person held.
--
-- So a member row can now be a former member:
--
--   * `former_since` is set by the purge (the owner connection, no signed-in
--     user) when it keeps what the person held for the household. The row's
--     name becomes "Former member" and everything else about the person on it
--     — date of birth, date of death, relationship, notes, avatar — is cleared.
--   * It never gets a login again, is never un-marked, and nobody writes those
--     columns but the purge: a trigger refuses it whatever the grants become.
--   * What it holds is read by the rules it always was (docs/05 §3.1): a
--     household-shared or scoped record is seen by whom it was shared with; a
--     joint record by its other holders. Records private to the person alone
--     are not kept — the purge erases them — so there is nothing of theirs
--     that anyone could come to see.
-- =============================================================================

alter table members add column former_since timestamptz;

comment on column members.former_since is
  'Set when the person this row was is erased and what they held for the household stays (docs/05 §12.7). '
  'The row then carries no personal data, never gets a login, and is never un-marked.';

create or replace function app.former_member_stays_former() returns trigger
  language plpgsql security definer
  set search_path = public, app, pg_temp as $$
begin
  if old.former_since is not null then
    if new.former_since is distinct from old.former_since then
      raise exception 'a former member stays a former member' using errcode = 'check_violation';
    end if;
    if new.user_id is not null then
      raise exception 'a former member cannot be claimed by a login' using errcode = 'check_violation';
    end if;
    if new.date_of_birth is not null or new.died_on is not null then
      raise exception 'a former member carries no personal dates' using errcode = 'check_violation';
    end if;
  elsif new.former_since is not null then
    -- Only the purge marks a row, and only as it erases the person.
    if app.current_user_id() is not null then
      raise exception 'only the erasure makes a former member' using errcode = 'insufficient_privilege';
    end if;
  end if;
  return new;
end $$;

create trigger members_former_stays_former before update on members
  for each row execute function app.former_member_stays_former();

-- However a row came to be written, a former member has no login.
alter table members add constraint members_former_has_no_login
  check (former_since is null or user_id is null);
