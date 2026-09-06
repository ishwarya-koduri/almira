-- Runs once, on first container start (docker-entrypoint-initdb.d).
--
-- WHY TWO ROLES: PostgreSQL lets a table's OWNER bypass its own row-level
-- security policies. If the application connected as the owner, every RLS
-- policy in this database would be decorative. So:
--
--   almira      -- owner. Runs Flyway migrations and seeds reference data.
--   almira_app  -- the application runtime. Not an owner, so RLS is enforced
--                  against it with no way to opt out.
--
-- The same split is reproduced in Testcontainers, so the tests exercise the
-- real security boundary rather than a permissive stand-in.
do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'almira_app') then
    create role almira_app login password 'app_dev_password';
  end if;
end $$;

grant connect on database almira to almira_app;
grant usage on schema public to almira_app;

-- A separate database for the automated test suite.
--
-- Tests create households, sign up users and delete records; pointing them at
-- the development database would quietly destroy whatever you were working on.
-- Flyway migrates this one on test startup exactly as it does the real one.
create database almira_test owner almira;
grant connect on database almira_test to almira_app;

-- A throwaway database for syntax-checking a migration before Flyway owns it.
--
-- Hand-applying a migration to a Flyway-managed database leaves the objects
-- present with no history row, and the next startup fails with "relation already
-- exists". Checking against this one instead keeps that impossible rather than
-- merely discouraged. See scripts/check-migration.sh.
create database almira_scratch owner almira;
grant connect on database almira_scratch to almira_app;
