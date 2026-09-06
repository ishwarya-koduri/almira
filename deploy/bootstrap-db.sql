-- =============================================================================
-- Creates the non-owner runtime role on a database that already exists.
--
-- WHY: PostgreSQL lets a table's owner bypass its own row-level security. If
-- Almira connected as the owner, every policy protecting one family member's
-- records from another would be decorative. So the schema is owned by one role
-- and served by another that cannot opt out.
--
-- Idempotent — run it as often as you like. Run it as the OWNER of the
-- database, before the application starts for the first time:
--
--   psql "$ALMIRA_ADMIN_URL" -v ON_ERROR_STOP=1 \
--        -v app_user=almira_app -v app_password='…' -f deploy/bootstrap-db.sql
--
-- Or, on the compose stack:  ./scripts/bootstrap-prod-db.sh
--
-- After this, /health must report "dbRole":"almira_app" and "rlsEnforced":true.
-- If it says the owner's name, stop and fix it before anybody signs in.
-- =============================================================================

\set ON_ERROR_STOP on

-- The role. Password is passed in rather than written here, so this file can be
-- committed and read by anybody.
select format('create role %I login password %L', :'app_user', :'app_password')
where not exists (select 1 from pg_roles where rolname = :'app_user')
\gexec

-- Keep the password in step when it is rotated.
select format('alter role %I login password %L', :'app_user', :'app_password')
\gexec

-- It must never be able to create schemas, own objects, or bypass policies.
select format('alter role %I nosuperuser nocreatedb nocreaterole noinherit nobypassrls', :'app_user')
\gexec

select format('grant connect on database %I to %I', current_database(), :'app_user')
\gexec
select format('grant usage on schema public to %I', :'app_user')
\gexec

-- The table-level grants themselves live in db/migrations/R__grants.sql, which
-- Flyway runs last on every deploy — so a table added next year is covered
-- without anybody remembering this file exists.
\echo ''
\echo 'Runtime role ready. Flyway will grant table privileges on first start.'
\echo 'Then check: curl -s https://your-host/health'
\echo 'It must report "dbRole":"almira_app" and "rlsEnforced":true.'
