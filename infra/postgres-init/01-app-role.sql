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
