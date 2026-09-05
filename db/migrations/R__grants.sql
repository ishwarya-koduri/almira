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

  -- Anything a later migration creates inherits these defaults automatically.
  execute 'alter default privileges in schema public '
          'grant select, insert, update, delete on tables to almira_app';
  execute 'alter default privileges in schema public '
          'grant usage, select on sequences to almira_app';
  execute 'alter default privileges in schema app '
          'grant execute on functions to almira_app';
end $$;
