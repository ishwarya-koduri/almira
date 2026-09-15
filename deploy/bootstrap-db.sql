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
-- It ends by checking the role it made, and fails if that role can get past
-- row-level security. The application checks the same thing again at every
-- start, before migrating, and refuses to start if it can (RuntimeRoleCheck,
-- docs/17 §3). /health still reports "dbRole" and "rlsEnforced".
-- =============================================================================

\set ON_ERROR_STOP on

-- The runtime role must not be the role running this. Checked before the
-- ALTER ROLE statements below, which would otherwise set the owner's password
-- to the runtime role's and try to strip the owner's own attributes.
select set_config('almira.bootstrap_app_user', :'app_user', false);
do $$
begin
  if current_setting('almira.bootstrap_app_user') = current_user then
    raise exception 'app_user % is the role running this bootstrap (the owner). The runtime role must be a different role.', current_user;
  end if;
end
$$;

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

-- Checked here, before anybody starts the application, and not left to /health
-- afterwards. The ALTER ROLE above cannot undo a membership: a runtime role
-- granted a superuser, a BYPASSRLS role or a table owner can SET ROLE to it.
-- The same rule as RuntimeRoleCheck, which the application runs again itself.
do $$
declare
  app text := current_setting('almira.bootstrap_app_user');
  problems text;
begin
  select string_agg(format('%s %s', case when r.rolname = app then format('%I', app)
                                         else format('%I is a member of %I, which', app, r.rolname) end,
                           concat_ws(', ',
                             case when r.rolsuper then 'is a SUPERUSER' end,
                             case when r.rolbypassrls then 'has BYPASSRLS' end,
                             case when r.rolcreaterole then 'has CREATEROLE' end,
                             case when exists (select 1 from pg_class c where c.relowner = r.oid and c.relrowsecurity)
                                  then 'owns tables under row-level security' end)), '; ')
    into problems
    from pg_roles r
   where pg_has_role(app, r.oid, 'MEMBER')
     and (r.rolsuper or r.rolbypassrls or r.rolcreaterole
          or exists (select 1 from pg_class c where c.relowner = r.oid and c.relrowsecurity));
  if problems is not null then
    raise exception 'the runtime role can bypass row-level security: %. Do not start the application; fix the role first.', problems;
  end if;
end
$$;

\echo ''
\echo 'Runtime role ready, and it cannot bypass row-level security.'
\echo 'Flyway will grant table privileges on first start. The application checks'
\echo 'the role again before migrating and refuses to start if it can bypass.'
