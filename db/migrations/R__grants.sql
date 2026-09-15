-- =============================================================================
-- R · Repeatable: privileges for the application runtime role.
--
-- Flyway runs repeatable migrations LAST and re-runs them whenever this file's
-- checksum changes, so a new table added in any future migration automatically
-- picks up the right grants here.
--
-- `almira_app` is deliberately NOT the owner of anything. That is the whole
-- point: PostgreSQL lets a table owner bypass its own RLS policies, so an
-- application connecting as the owner would silently defeat every policy in
-- V4. Running as a plain, granted role means the policies are inescapable.
--
-- Note what is NOT granted: no CREATE, no ownership, no TRUNCATE, and no
-- write access at all to activity_log beyond INSERT (docs/05 §9 append-only).
-- =============================================================================

do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'almira_app') then
    raise notice 'role almira_app is absent; skipping grants '
                 '(expected in a migrate-only context)';
    return;
  end if;

  execute 'grant usage on schema public to almira_app';
  execute 'grant usage on schema app    to almira_app';

  -- Table privileges. DELETE is granted because soft-delete lives in the
  -- service layer while link/child tables (ownerships, grants, tags) are
  -- genuinely removed; RLS still decides which rows are reachable.
  execute 'grant select, insert, update, delete on all tables in schema public to almira_app';
  execute 'grant usage, select on all sequences in schema public to almira_app';
  execute 'grant execute on all functions in schema app to almira_app';

  -- activity_log is append-only for the application.
  execute 'revoke update, delete on activity_log from almira_app';

  -- Reference data is read-only for the application; it is seeded by migrations.
  execute 'revoke insert, update, delete on asset_categories from almira_app';

  -- Continuity signals (V95). A one-tap link is spent only through
  -- app.redeem_continuity_link, so the runtime role holds nothing on the table;
  -- a trusted contact's confirmation is a dated fact and is never rewritten; an
  -- answer to "do you know where" may change the answer and nothing else; and
  -- "has this person been here since" is asked only through a request the
  -- caller can already see (app.emergency_subject_present).
  if to_regclass('public.continuity_links') is not null then
    execute 'revoke all on continuity_links from almira_app';
    execute 'revoke update, delete on trusted_contact_confirmations from almira_app';
    execute 'revoke update on key_holder_asks from almira_app';
    execute 'grant update (answer, answered_at) on key_holder_asks to almira_app';
    execute 'revoke execute on function app.member_present_since(uuid, timestamptz) from almira_app, public';
  end if;

  -- Definer helpers that answer about a person or household the caller names
  -- (V107). Only the sweeps on the owner connection and other definer
  -- functions ask them; to the runtime role they would read birth and death
  -- days, memorials and consent choices in any household past RLS.
  if to_regprocedure('app.is_remembrance_day(uuid, date)') is not null then
    execute 'revoke execute on function app.is_remembrance_day(uuid, date) from almira_app, public';
    execute 'revoke execute on function app.notifications_stopped(uuid, uuid) from almira_app, public';
  end if;
  -- Consent to messages (V125), which replaced V45's messages_consent_withdrawn.
  if to_regprocedure('app.messages_consent_given(uuid, text)') is not null then
    execute 'revoke execute on function app.messages_consent_given(uuid, text) from almira_app, public';
  end if;
  -- A "not now" is kept or moved on, never deleted by the application (V125).
  if to_regclass('public.messages_consent_asks') is not null then
    execute 'revoke delete, truncate on messages_consent_asks from almira_app';
  end if;

  -- Plans and support codes (V101, V102). A household's plan is set only by an
  -- operator as the schema owner, so the runtime role may read it and nothing
  -- else; a support code is made and taken back by its owner, and every other
  -- column (its contents, lifetime and the lookup count) is not the app's to
  -- write. Without this, the blanket grant above would hand those back each
  -- time this file re-runs, leaving only RLS and the trigger in the way.
  if to_regclass('public.household_plans') is not null then
    execute 'revoke insert, update, delete, truncate on household_plans from almira_app';
  end if;
  if to_regclass('public.support_codes') is not null then
    execute 'revoke all on support_codes from almira_app';
    execute 'grant select, insert on support_codes to almira_app';
    execute 'grant update (revoked_at) on support_codes to almira_app';
  end if;

  -- Bytes an erasure still has to delete (V109). The sweep's alone, on the owner
  -- connection; the key of an erased document is nothing the runtime role needs.
  if to_regclass('public.pending_storage_deletions') is not null then
    execute 'revoke all on pending_storage_deletions from almira_app';
  end if;

  -- Queued sign-in emails (V110). Written only through
  -- app.enqueue_sign_in_code_email and read only by the worker on the owner
  -- connection; where a sign-in email goes, and how it ended, is not the
  -- runtime role's to read.
  if to_regclass('public.sign_in_code_emails') is not null then
    execute 'revoke all on sign_in_code_emails from almira_app';
    execute 'revoke all on sign_in_code_email_bodies from almira_app';
  end if;

  -- Dormant households (V120). The household reads its state through RLS; only
  -- the sweep, the triggers and app.accept_household_ownership write it. The
  -- helpers that open and end one, or answer the rule for a person the caller
  -- names, are the sweep's and those functions' alone.
  if to_regclass('public.household_dormancies') is not null then
    execute 'revoke insert, update, delete, truncate on household_dormancies from almira_app';
    execute 'revoke execute on function app.household_holds_records(uuid) from almira_app, public';
    execute 'revoke execute on function app.going_leaves_household_ownerless(uuid, uuid) from almira_app, public';
    execute 'revoke execute on function app.open_household_dormancy(uuid, uuid, text, uuid, uuid, uuid, timestamptz) from almira_app, public';
    execute 'revoke execute on function app.end_household_dormancy(uuid, text, uuid) from almira_app, public';
  end if;

  -- Anything a later migration creates inherits these defaults automatically.
  execute 'alter default privileges in schema public '
          'grant select, insert, update, delete on tables to almira_app';
  execute 'alter default privileges in schema public '
          'grant usage, select on sequences to almira_app';
  execute 'alter default privileges in schema app '
          'grant execute on functions to almira_app';
end $$;
