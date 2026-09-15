#!/usr/bin/env bash
# =============================================================================
# scripts/restore.sh and scripts/restore-row.sh check BEFORE they write.
#
#   ./scripts/tests/restore-checks-before-writing.sh
#
# Against throwaway Postgres containers and volumes (see lib.sh), with backups
# taken by the real scripts/backup.sh. pg_dump, pg_restore, psql and tar are
# real; only `docker compose` is shimmed.
#
# restore.sh
#   1. A documents volume that is not empty refuses before anything is written
#      to the target: no pg_restore, no table, no runtime role created.
#   2. An empty one restores: tables, rows and documents arrive.
#
# restore-row.sh
#   3. A row whose BACKUP copy is damaged too — a digest that does not match,
#      or bytes that break the sweep's rules — is refused, and the live row
#      keeps exactly the bytes it had.
#   4. A row whose backup copy is intact is put back.
#   5. A row that is not in the live database is refused, and not inserted.
# =============================================================================
set -uo pipefail
. "$(dirname "$0")/lib.sh"

echo "${BOLD}restore.sh and restore-row.sh: checked before writing${OFF}"

envelope() { python3 -c 'import base64,os; print(base64.urlsafe_b64encode(bytes([1,0,0,0,1]) + os.urandom(40)).decode().rstrip("="))'; }
GOOD_ID=11111111-1111-1111-1111-111111111111
DAMAGED_ID=22222222-2222-2222-2222-222222222222
GOOD_CIPHERTEXT=$(envelope)
DAMAGED_CIPHERTEXT=$(envelope)
HH=33333333-3333-3333-3333-333333333333

# --- the source, and two backups of it ---------------------------------------
start_pg source
SOURCE="$PG"
write_env_file
owner_sql <<SQL >/dev/null
create table flyway_schema_history (installed_rank int, version text, success boolean);
insert into flyway_schema_history values (1, '108', true);
create table outbound_message_bodies (message_id int primary key, body text);
create table sealed_values (
  id uuid primary key, household_id uuid, record_type text, record_id uuid, field_key text,
  ciphertext text, key_version int, ciphertext_sha256 bytea);
create table e2e_keys (
  id uuid primary key, household_id uuid, user_id uuid, kdf_salt text, wrapped_key text, verifier text,
  iterations int, key_version int, wrapped_key_sha256 bytea, verifier_sha256 bytea);
create table e2e_recovery_wraps (
  id uuid primary key, household_id uuid, user_id uuid, kind text, kdf_salt text, wrapped_key text,
  verifier text, wrapped_key_sha256 bytea, verifier_sha256 bytea);
insert into sealed_values values
  ('$GOOD_ID', '$HH', 'account', '$HH', 'number', '$GOOD_CIPHERTEXT', 1, sha256(convert_to('$GOOD_CIPHERTEXT', 'UTF8')));
SQL
make_volume source-documents
SOURCE_VOLUME="$VOLUME"
"$REAL_DOCKER" run --rm -v "$SOURCE_VOLUME":/d alpine:latest sh -c 'mkdir -p /d/h && echo scan > /d/h/doc.bin'
use_compose_shim
export DOCS_VOLUME_NAME="$SOURCE_VOLUME"

take_backup() { # → sets BACKUP
  local out="$WORK/backups-$1"; mkdir -p "$out"
  bash "$REPO/scripts/backup.sh" --project ws-script-test --env-file "$ENV_FILE" --out "$out" > "$WORK/backup.out" 2>&1 \
    || { cat "$WORK/backup.out"; echo "${RED}backup.sh failed while setting up${OFF}"; exit 2; }
  BACKUP=$(ls -d "$out"/almira-*)
}
take_backup clean
CLEAN_BACKUP="$BACKUP"

# A second backup whose copy of DAMAGED_ID is damaged: its digest was written
# for other bytes.
owner_sql "insert into sealed_values values ('$DAMAGED_ID', '$HH', 'account', '$HH', 'number', '$DAMAGED_CIPHERTEXT', 1, sha256(convert_to('not these bytes', 'UTF8')))"
take_backup damaged
DAMAGED_BACKUP="$BACKUP"

# --- restore.sh ----------------------------------------------------------------
start_pg target
use_compose_shim
make_volume target-documents
TARGET_VOLUME="$VOLUME"
export DOCS_VOLUME_NAME="$TARGET_VOLUME"
"$REAL_DOCKER" run --rm -v "$TARGET_VOLUME":/d alpine:latest sh -c 'echo left-over > /d/stray'

restore() {
  bash "$REPO/scripts/restore.sh" --project ws-script-test-drill --env-file "$ENV_FILE" --from "$1" > "$WORK/restore.out" 2>&1
  STATUS=$?
}

: > "$CALLS"
restore "$CLEAN_BACKUP"
check "1 · a documents volume that is not empty refuses (exit $STATUS)" \
  bash -c "[ $STATUS != 0 ] && grep -q 'documents volume $TARGET_VOLUME is not empty' '$WORK/restore.out'"
check "    and pg_restore never ran" not_called pg_restore
check "    and the target has no table" bash -c "[ \"\$(PG=$PG; docker exec $PG psql -U almira -d almira -tAc \"select count(*) from pg_tables where schemaname='public'\")\" = 0 ]"
check "    and no runtime role was created on it" \
  bash -c "[ -z \"\$(docker exec $PG psql -U almira -d almira -tAc \"select 1 from pg_roles where rolname='almira_app'\")\" ]"

"$REAL_DOCKER" run --rm -v "$TARGET_VOLUME":/d alpine:latest sh -c 'rm -f /d/stray'
: > "$CALLS"
restore "$CLEAN_BACKUP"
check "2 · into an empty target it restores (exit $STATUS)" bash -c "[ $STATUS = 0 ] && grep -q 'Restore verified' '$WORK/restore.out'"
check "    with the rows" bash -c "[ \"\$(docker exec $PG psql -U almira -d almira -tAc 'select count(*) from sealed_values')\" = 1 ]"
check "    and the documents" bash -c "docker run --rm -v $TARGET_VOLUME:/d alpine:latest cat /d/h/doc.bin | grep -q scan"
[ "$STATUS" = 0 ] || sed 's/^/        /' "$WORK/restore.out"

# --- restore-row.sh, against the source as the live database --------------------
PG="$SOURCE"
use_compose_shim
live() { owner_sql "select ciphertext from sealed_values where id = '$1'"; }
restore_row() {
  bash "$REPO/scripts/restore-row.sh" --project ws-script-test --env-file "$ENV_FILE" \
    --from "$DAMAGED_BACKUP" --table sealed_values --id "$1" > "$WORK/row.out" 2>&1
  STATUS=$?
}

# Both rows damaged in the live database now: different, well-formed bytes.
LIVE_DAMAGED=$(envelope)
owner_sql "update sealed_values set ciphertext = '$(envelope)' where id = '$GOOD_ID'"
owner_sql "update sealed_values set ciphertext = '$LIVE_DAMAGED' where id = '$DAMAGED_ID'"

restore_row "$DAMAGED_ID"
check "3 · a row whose backup copy is damaged too is refused (exit $STATUS)" \
  bash -c "[ $STATUS != 0 ] && grep -q \"backup's copy of that row is damaged too: ciphertext does not match its stored digest\" '$WORK/row.out'"
check "    and the live row keeps the bytes it had" bash -c "[ '$(live "$DAMAGED_ID")' = '$LIVE_DAMAGED' ]"

# 3b · The same when the backup's copy is structurally damaged (the sweep's
# rules), with a digest that matches its bytes.
PADDED_ID=55555555-5555-5555-5555-555555555555
PADDED="$(envelope)=="
owner_sql "insert into sealed_values values ('$PADDED_ID', '$HH', 'account', '$HH', 'number', '$PADDED', 1, sha256(convert_to('$PADDED', 'UTF8')))"
KEEP_BACKUP="$DAMAGED_BACKUP"
take_backup padded
DAMAGED_BACKUP="$BACKUP"
LIVE_PADDED=$(envelope)
owner_sql "update sealed_values set ciphertext = '$LIVE_PADDED' where id = '$PADDED_ID'"
restore_row "$PADDED_ID"
check "    a backup copy that breaks the sweep's rules is refused too (exit $STATUS)" \
  bash -c "[ $STATUS != 0 ] && grep -q 'damaged too: ciphertext has base64 padding' '$WORK/row.out'"
check "    and that live row keeps its bytes as well" bash -c "[ '$(live "$PADDED_ID")' = '$LIVE_PADDED' ]"
owner_sql "delete from sealed_values where id = '$PADDED_ID'"
DAMAGED_BACKUP="$KEEP_BACKUP"

restore_row "$GOOD_ID"
check "4 · a row whose backup copy is intact is put back" \
  bash -c "grep -q 'now carries the backup' '$WORK/row.out' && [ '$(live "$GOOD_ID")' = '$GOOD_CIPHERTEXT' ]"

GONE_ID=44444444-4444-4444-4444-444444444444
owner_sql "insert into sealed_values values ('$GONE_ID', '$HH', 'account', '$HH', 'number', '$(envelope)', 1, null)"
take_backup gone
DAMAGED_BACKUP="$BACKUP"
owner_sql "delete from sealed_values where id = '$GONE_ID'"
restore_row "$GONE_ID"
check "5 · a row that is not in the live database is refused (exit $STATUS)" \
  bash -c "[ $STATUS != 0 ] && grep -q 'does not exist in the live database' '$WORK/row.out'"
check "    and not inserted" bash -c "[ -z '$(live "$GONE_ID")' ]"

finish
