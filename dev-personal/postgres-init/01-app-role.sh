#!/bin/sh
# Creates the non-owner runtime role on first start of a fresh data volume.
#
# WHY TWO ROLES: PostgreSQL lets a table's owner bypass its own row-level
# security. Almira's entire privacy model — one family member's records being
# unreadable to another, including an admin — is those policies. Serving traffic
# as the owner would switch all of it off while everything still looked healthy.
#
# A shell script rather than plain .sql because the password arrives as an
# environment variable, so it is not written into a file that gets committed.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
do \$\$
begin
  if not exists (select 1 from pg_roles where rolname = '$ALMIRA_DB_APP_USER') then
    create role $ALMIRA_DB_APP_USER login password '$ALMIRA_DB_APP_PASSWORD';
  end if;
end
\$\$;

alter role $ALMIRA_DB_APP_USER nosuperuser nocreatedb nocreaterole noinherit nobypassrls;
grant connect on database $POSTGRES_DB to $ALMIRA_DB_APP_USER;
grant usage on schema public to $ALMIRA_DB_APP_USER;
SQL

echo "almira-personal: runtime role $ALMIRA_DB_APP_USER created (non-owner, nobypassrls)"
