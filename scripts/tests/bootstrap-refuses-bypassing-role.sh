#!/usr/bin/env bash
# =============================================================================
# scripts/bootstrap-prod-db.sh (deploy/bootstrap-db.sql) refuses a runtime role
# that can bypass row-level security, and refuses to run with the owner as the
# runtime role BEFORE its ALTER ROLE statements change the owner's password.
#
#   ./scripts/tests/bootstrap-refuses-bypassing-role.sh
#
# Against a throwaway Postgres container (see lib.sh). psql is not needed on
# the host: a `psql` on PATH runs the real one inside the container.
# =============================================================================
set -uo pipefail
. "$(dirname "$0")/lib.sh"

echo "${BOLD}bootstrap-prod-db.sh: the runtime role is checked before anybody is told to start${OFF}"
start_pg bootstrap

mkdir -p "$WORK/bin"
cat > "$WORK/bin/psql" <<SHIM
#!/usr/bin/env bash
args=() file=""
while [ \$# -gt 0 ]; do
  case "\$1" in
    -f) file="\$2"; args+=(-f -); shift 2;;
    *) args+=("\$1"); shift;;
  esac
done
exec "$REAL_DOCKER" exec -i "$PG" psql "\${args[@]}" < "\${file:-/dev/stdin}"
SHIM
chmod +x "$WORK/bin/psql"

bootstrap() { # bootstrap <app user> → output in $WORK/out, exit status in $STATUS
  PATH="$WORK/bin:$PATH" ALMIRA_DB_APP_USER="$1" ALMIRA_DB_APP_PASSWORD=app_test_password \
    bash "$REPO/scripts/bootstrap-prod-db.sh" --url "postgres://almira:test@127.0.0.1:5432/almira" > "$WORK/out" 2>&1
  STATUS=$?
}

# 1 · An ordinary role: created, checked, Done.
bootstrap almira_app
check "a plain runtime role is created and reported done (exit $STATUS)" \
  bash -c "[ $STATUS = 0 ] && grep -q 'Done' '$WORK/out'"
check "and it is nosuperuser nobypassrls nocreaterole" \
  bash -c "[ \"\$(docker exec $PG psql -U almira -d almira -tAc \"select rolsuper or rolbypassrls or rolcreaterole from pg_roles where rolname='almira_app'\")\" = f ]"

# 2 · A role that is a member of a BYPASSRLS role: the ALTER ROLE cannot fix
# that, so the check must refuse, and Done must not be printed.
owner_sql "create role almira_leaky nologin bypassrls; create role almira_member login; grant almira_leaky to almira_member;"
bootstrap almira_member
check "a runtime role that can SET ROLE to a BYPASSRLS role fails (exit $STATUS)" bash -c "[ $STATUS != 0 ]"
check "the failure names the role it can become and the attribute" \
  grep -q 'almira_member is a member of almira_leaky, which has BYPASSRLS' "$WORK/out"
check "and the operator is not told it is done" bash -c "! grep -q 'Done' '$WORK/out'"

# 3 · A runtime role that owns a table under RLS.
owner_sql "create role almira_tableowner login; create table owned_by_app (id int); alter table owned_by_app enable row level security; alter table owned_by_app owner to almira_tableowner;"
bootstrap almira_tableowner
check "a runtime role that owns a table under RLS fails (exit $STATUS)" \
  bash -c "[ $STATUS != 0 ] && grep -q 'owns tables under row-level security' '$WORK/out' && ! grep -q Done '$WORK/out'"

# 4 · The owner as the runtime role: refused BEFORE the ALTER ROLE statements,
# the first of which sets the role's password — here, the owner's. The action
# that must not happen is that change: the owner's stored password hash is the
# same afterwards. (Read from pg_authid: loopback connections in this image are
# trusted, so signing in would not show it.)
owner_password() { owner_sql "select rolpassword from pg_authid where rolname = 'almira'"; }
OWNER_PASSWORD_BEFORE=$(owner_password)
bootstrap almira
check "the owner as the runtime role fails (exit $STATUS)" \
  bash -c "[ $STATUS != 0 ] && grep -q 'is the role running this bootstrap' '$WORK/out'"
check "and the owner's password was not changed" bash -c "[ '$OWNER_PASSWORD_BEFORE' = '$(owner_password)' ]"

finish
