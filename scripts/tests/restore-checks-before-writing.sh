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
# Where the documents are (owner's decision, 2026-09-15), each refused before
# anything is started or written — no `compose up`, no pg_restore, no table, no
# runtime role, no container:
#   D1. restore.sh: a backup whose manifest says its documents are NOT in it,
#       without ALMIRA_BACKUP_DOCUMENTS=external.
#   D2. restore.sh: the same, acknowledged, into a target on the filesystem
#       provider, where every document would be missing.
#   D3. restore.sh: the acknowledgement with a backup that holds its documents.
#   D4. restore.sh: a manifest from before the field, into a target on S3,
#       without the acknowledgement (treated per the target's provider).
#   D5. restore-drill-local.sh --from: D1 and D3, before any container starts.
#   D6. drill.py verify --manifest: D1, before the server is asked anything.
#   D7. restore.sh: a backup with external documents, acknowledged, into an S3
#       target restores the database, says the documents are not restored, and
#       extracts nothing; a pre-field manifest into a filesystem target passes
#       step 0 as before.
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

# A backup of the same source taken as an S3 deployment, acknowledged; and a
# copy of the clean one whose manifest predates the documents field.
S3_ENV="$WORK/env-s3"
cp "$ENV_FILE" "$S3_ENV"
printf 'ALMIRA_STORAGE_PROVIDER=s3\nALMIRA_S3_BUCKET=almira-docs-test\nALMIRA_S3_REGION=ap-south-1\n' >> "$S3_ENV"
mkdir -p "$WORK/backups-s3"
ALMIRA_BACKUP_DOCUMENTS=external bash "$REPO/scripts/backup.sh" --project ws-script-test --env-file "$S3_ENV" --out "$WORK/backups-s3" > "$WORK/backup.out" 2>&1 \
  || { cat "$WORK/backup.out"; echo "${RED}backup.sh (s3) failed while setting up${OFF}"; exit 2; }
S3_BACKUP=$(ls -d "$WORK/backups-s3"/almira-*)
OLD_BACKUP="$WORK/backup-before-the-field"
cp -R "$CLEAN_BACKUP" "$OLD_BACKUP"
python3 -c '
import json, sys
p = sys.argv[1]; m = json.load(open(p)); del m["documents"]; json.dump(m, open(p, "w"))
' "$OLD_BACKUP/manifest.json"


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

restore_env() { # restore_env <env file> <backup>
  bash "$REPO/scripts/restore.sh" --project ws-script-test-drill --env-file "$1" --from "$2" > "$WORK/restore.out" 2>&1
  STATUS=$?
}
untouched() { # the target: no compose up, no pg_restore, no table, no runtime role
  not_called " up -d" && not_called pg_restore \
    && [ "$("$REAL_DOCKER" exec "$PG" psql -U almira -d almira -tAc "select count(*) from pg_tables where schemaname='public'")" = 0 ] \
    && [ -z "$("$REAL_DOCKER" exec "$PG" psql -U almira -d almira -tAc "select 1 from pg_roles where rolname='almira_app'")" ]
}

unset ALMIRA_BACKUP_DOCUMENTS
: > "$CALLS"
restore_env "$S3_ENV" "$S3_BACKUP"
check "D1 · external documents without the acknowledgement refuse (exit $STATUS)" \
  bash -c "[ $STATUS != 0 ] && grep -q 'the manifest says the documents are NOT in this backup' '$WORK/restore.out' && grep -q 'ALMIRA_BACKUP_DOCUMENTS=external' '$WORK/restore.out'"
check "     and the target is untouched" untouched

: > "$CALLS"
ALMIRA_BACKUP_DOCUMENTS=external restore_env "$ENV_FILE" "$S3_BACKUP"
check "D2 · external documents into a filesystem target refuse, acknowledged or not (exit $STATUS)" \
  bash -c "[ $STATUS != 0 ] && grep -q 'every document would be missing' '$WORK/restore.out'"
check "     and the target is untouched" untouched

: > "$CALLS"
ALMIRA_BACKUP_DOCUMENTS=external restore_env "$ENV_FILE" "$CLEAN_BACKUP"
check "D3 · the acknowledgement with a backup that holds its documents refuses (exit $STATUS)" \
  bash -c "[ $STATUS != 0 ] && grep -q 'the manifest says the documents are in this backup' '$WORK/restore.out'"
check "     and the target is untouched" untouched

: > "$CALLS"
restore_env "$S3_ENV" "$OLD_BACKUP"
check "D4 · a manifest from before the field, into an S3 target, needs the acknowledgement (exit $STATUS)" \
  bash -c "[ $STATUS != 0 ] && grep -q 'does not say where the documents are, and the target keeps them in object storage' '$WORK/restore.out'"
check "     and the target is untouched" untouched

drill_from() { # drill_from <backup>
  bash "$REPO/scripts/restore-drill-local.sh" --name-prefix "$PREFIX-drill" --from "$1" > "$WORK/drill.out" 2>&1
  STATUS=$?
}
no_drill_container() { ! "$REAL_DOCKER" inspect "$PREFIX-drill-restore" >/dev/null 2>&1 && not_called "run -d --name $PREFIX-drill"; }
: > "$CALLS"
drill_from "$S3_BACKUP"
check "D5 · restore-drill-local.sh --from external documents without the acknowledgement refuses (exit $STATUS)" \
  bash -c "[ $STATUS != 0 ] && grep -q 'NOT in this backup' '$WORK/drill.out'"
check "     before any container was started" no_drill_container
: > "$CALLS"
ALMIRA_BACKUP_DOCUMENTS=external drill_from "$CLEAN_BACKUP"
check "     and the acknowledgement with documents in the backup refuses too (exit $STATUS)" \
  bash -c "[ $STATUS != 0 ] && grep -q 'documents are in this backup' '$WORK/drill.out'"
check "     before any container was started" no_drill_container

# drill.py, against a server that records every request it is sent.
STUB_PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')
python3 -c '
import http.server, sys
class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def handle_one_request(self):
        open(sys.argv[2], "a").write("request\n")
        super().handle_one_request()
    def do_GET(self): self.send_response(500); self.end_headers()
    do_POST = do_GET
http.server.HTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
' "$STUB_PORT" "$WORK/stub.requests" &
STUB_PID=$!
sleep 1
python3 "$REPO/deploy/restore/drill/drill.py" verify --base "http://127.0.0.1:$STUB_PORT" \
  --compose-args "-p ws-script-test --env-file $S3_ENV" --state "$WORK/no-such-state.json" --manifest "$S3_BACKUP/manifest.json" > "$WORK/drillpy.out" 2>&1
STATUS=$?
kill "$STUB_PID" 2>/dev/null; wait "$STUB_PID" 2>/dev/null
check "D6 · drill.py verify --manifest with external documents, unacknowledged, stops (exit $STATUS)" \
  bash -c "[ $STATUS = 2 ] && grep -q 'Stopped before verifying' '$WORK/drillpy.out'"
check "     before the server was asked anything" bash -c "[ ! -s '$WORK/stub.requests' ]"

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

: > "$CALLS"
bash "$REPO/scripts/restore.sh" --project ws-script-test-drill --env-file "$ENV_FILE" --from "$OLD_BACKUP" --verify-only > "$WORK/restore.out" 2>&1
STATUS=$?
check "D7 · a manifest from before the field, into a filesystem target, passes step 0 as before (exit $STATUS)" \
  bash -c "[ $STATUS = 0 ] && grep -q 'Restore verified' '$WORK/restore.out' && ! grep -q 'NOT RESTORED' '$WORK/restore.out'"

start_pg target-s3
use_compose_shim
make_volume target-s3-documents
S3_TARGET_VOLUME="$VOLUME"
export DOCS_VOLUME_NAME="$S3_TARGET_VOLUME"
: > "$CALLS"
ALMIRA_BACKUP_DOCUMENTS=external restore_env "$S3_ENV" "$S3_BACKUP"
check "     external documents, acknowledged, into an S3 target restore (exit $STATUS)" \
  bash -c "[ $STATUS = 0 ] && grep -q 'Restore verified' '$WORK/restore.out'"
[ "$STATUS" = 0 ] || sed 's/^/        /' "$WORK/restore.out"
check "     with the rows" bash -c "[ \"\$(docker exec $PG psql -U almira -d almira -tAc 'select count(*) from sealed_values')\" = 1 ]"
check "     saying, before and after, that the documents are not restored" \
  bash -c "grep -q 'DOCUMENTS ARE NOT RESTORED BY THIS SCRIPT' '$WORK/restore.out' && grep -q 'Documents were not part of it' '$WORK/restore.out'"
check "     and extracting nothing" bash -c "! grep -q 'tar xzf' '$CALLS' && [ -z \"\$(docker run --rm -v $S3_TARGET_VOLUME:/d alpine:latest ls -A /d)\" ]"

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
