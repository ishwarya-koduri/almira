#!/usr/bin/env bash
# =============================================================================
# scripts/backup.sh checks what it needs BEFORE it writes a dump, and the dump
# it writes holds no queued message bodies.
#
#   ./scripts/tests/backup-checks-before-dumping.sh
#
# Against a throwaway Postgres container and volume (see lib.sh). pg_dump,
# pg_restore and tar are the real ones; only `docker compose` is shimmed.
#
#   1. A documents volume that does not exist refuses before pg_dump runs: no
#      backup directory, no dump file, no pg_dump call.
#   2. A bodies table that is not where the exclusion expects it (renamed, or a
#      second one) refuses before pg_dump runs — an exclusion that silently
#      matches nothing would put every queued body in the backup.
#   3. A normal backup: the dump has the bodies TABLE but none of its DATA, the
#      other tables' data is there, and the manifest says what was left out and
#      that the documents are in it.
#   4. Documents in S3 (ALMIRA_STORAGE_PROVIDER=s3 in the env file) without
#      ALMIRA_BACKUP_DOCUMENTS=external refuses before anything: no directory,
#      no pg_dump, not even the database asked whether it is up.
#   5. ALMIRA_BACKUP_DOCUMENTS=external with the filesystem provider is a
#      contradiction, refused the same way; so is any other value of it.
#   6. Documents in S3, acknowledged: the backup is taken without documents.tgz,
#      and both its output and manifest.json say the documents are NOT in it and
#      name the bucket — and no credential from the env file appears in either.
# =============================================================================
set -uo pipefail
. "$(dirname "$0")/lib.sh"

echo "${BOLD}backup.sh: checked before dumping, and no queued bodies in the dump${OFF}"
start_pg backup
write_env_file
use_compose_shim

owner_sql <<'SQL' >/dev/null
create table flyway_schema_history (installed_rank int, version text, success boolean);
insert into flyway_schema_history values (1, '108', true);
create table households (id int primary key, name text);
insert into households values (1, 'Kapoor'), (2, 'Reddy');
create table outbound_messages (id int primary key, status text);
insert into outbound_messages values (1, 'queued');
create table outbound_message_bodies (message_id int primary key, body text, address text);
insert into outbound_message_bodies values (1, 'SECRET-BODY-your code is 481516', '+919000000001');
SQL

backup() { # → $STATUS, output in $WORK/out, backups under $OUT
  OUT="$WORK/out-$1"; mkdir -p "$OUT"
  bash "$REPO/scripts/backup.sh" --project ws-script-test --env-file "$ENV_FILE" --out "$OUT" > "$WORK/out" 2>&1
  STATUS=$?
}
nothing_written() { [ -z "$(ls -A "$OUT")" ]; }

# 1 · The documents volume is missing.
: > "$CALLS"
DOCS_VOLUME_NAME="$PREFIX-no-such-volume" backup missing-volume
check "a missing documents volume refuses (exit $STATUS)" \
  bash -c "[ $STATUS != 0 ] && grep -q 'Documents volume $PREFIX-no-such-volume does not exist' '$WORK/out'"
check "and nothing was written: no backup directory, no dump" nothing_written
check "and pg_dump never ran" not_called pg_dump

# 2 · The bodies table is not where the exclusion expects it.
make_volume documents
"$REAL_DOCKER" run --rm -v "$VOLUME":/d alpine:latest sh -c 'mkdir -p /d/h1 && echo ciphertext > /d/h1/doc.bin'
export DOCS_VOLUME_NAME="$VOLUME"
owner_sql "alter table outbound_message_bodies rename to outbound_message_bodies_v2"
: > "$CALLS"
backup renamed-bodies
check "a bodies table under another name refuses (exit $STATUS)" \
  bash -c "[ $STATUS != 0 ] && grep -q 'outbound_message_bodies_v2' '$WORK/out'"
check "and nothing was written" nothing_written
check "and pg_dump never ran" not_called pg_dump
owner_sql "alter table outbound_message_bodies_v2 rename to outbound_message_bodies"

# 3 · A normal backup.
: > "$CALLS"
backup normal
DEST=$(ls -d "$OUT"/almira-* 2>/dev/null | head -1)
check "a normal backup succeeds (exit $STATUS)" bash -c "[ $STATUS = 0 ] && [ -f '$DEST/database.dump' ] && [ -f '$DEST/documents.tgz' ]"
"$REAL_DOCKER" exec -i "$PG" pg_restore -l < "$DEST/database.dump" > "$WORK/toc" 2>/dev/null
"$REAL_DOCKER" exec -i "$PG" pg_restore --data-only -f - < "$DEST/database.dump" > "$WORK/data" 2>/dev/null
check "the dump has the bodies table itself, so a restore has somewhere to queue" \
  grep -Eq 'TABLE public outbound_message_bodies ' "$WORK/toc"
check "but no TABLE DATA for it" bash -c "! grep -q 'TABLE DATA public outbound_message_bodies ' '$WORK/toc'"
check "and no body text anywhere in the dump's data" bash -c "! grep -q 'SECRET-BODY' '$WORK/data' && ! LC_ALL=C grep -aq 'SECRET-BODY' '$DEST/database.dump'"
check "while every other table's data is there" grep -q 'TABLE DATA public households ' "$WORK/toc"
check "the manifest names what was left out, and counts no rows for it" python3 -c '
import json, sys
m = json.load(open(sys.argv[1]))
assert m["excluded_table_data"] == ["public.outbound_message_bodies"], m.get("excluded_table_data")
assert "public.outbound_message_bodies" not in m["row_counts"], m["row_counts"]
assert m["row_counts"]["public.households"] == 2, m["row_counts"]
' "$DEST/manifest.json"
check "and says the documents are in it" python3 -c '
import json, sys
m = json.load(open(sys.argv[1]))
assert m["documents"] == {"in_this_backup": True, "provider": "filesystem", "file": "documents.tgz"}, m.get("documents")
assert set(m["files"]) == {"database.dump", "documents.tgz"}, m["files"]
' "$DEST/manifest.json"

# 4 · Documents in S3, not acknowledged.
S3_ENV="$WORK/env-s3"
cp "$ENV_FILE" "$S3_ENV"
cat >> "$S3_ENV" <<'ENV'
ALMIRA_STORAGE_PROVIDER=s3
ALMIRA_S3_BUCKET=almira-docs-test
ALMIRA_S3_REGION=ap-south-1
ALMIRA_S3_ENDPOINT=https://AKIASHOULDNOTAPPEAR:endpoint-password@s3.example.test:9000/?X-Amz-Signature=abc
ALMIRA_S3_ACCESS_KEY_ID=AKIASHOULDNOTAPPEAR
ALMIRA_S3_SECRET_ACCESS_KEY=secret-should-not-appear
ENV
backup_with() { # backup_with <label> <env file> — as backup(), with another env file
  OUT="$WORK/out-$1"; mkdir -p "$OUT"
  bash "$REPO/scripts/backup.sh" --project ws-script-test --env-file "$2" --out "$OUT" > "$WORK/out" 2>&1
  STATUS=$?
}
: > "$CALLS"
unset ALMIRA_BACKUP_DOCUMENTS
backup_with s3-unacknowledged "$S3_ENV"
check "4 · documents in S3 without the acknowledgement refuses (exit $STATUS)" \
  bash -c "[ $STATUS != 0 ] && grep -q 'keeps its documents in object storage' '$WORK/out' && grep -q 'ALMIRA_BACKUP_DOCUMENTS=external' '$WORK/out'"
check "    and nothing was written" nothing_written
check "    and pg_dump never ran" not_called pg_dump
check "    and the database was not even asked (the check is before everything)" not_called pg_isready

# 5 · The acknowledgement with the filesystem provider, and a wrong value.
: > "$CALLS"
ALMIRA_BACKUP_DOCUMENTS=external backup filesystem-acknowledged
check "5 · the acknowledgement with filesystem documents is a contradiction, refused (exit $STATUS)" \
  bash -c "[ $STATUS != 0 ] && grep -q 'keeps them on the filesystem' '$WORK/out'"
check "    and nothing was written" nothing_written
check "    and pg_dump never ran" not_called pg_dump
: > "$CALLS"
ALMIRA_BACKUP_DOCUMENTS=yes backup_with s3-wrong-value "$S3_ENV"
check "    ALMIRA_BACKUP_DOCUMENTS=yes is not the acknowledgement (exit $STATUS)" \
  bash -c "[ $STATUS != 0 ] && grep -q \"The only value it takes is 'external'\" '$WORK/out'"
check "    and nothing was written, no pg_dump" bash -c "[ -z \"\$(ls -A '$OUT')\" ] && ! grep -q pg_dump '$CALLS'"

# 6 · Documents in S3, acknowledged.
: > "$CALLS"
DOCS_VOLUME_NAME="$PREFIX-no-volume-with-s3" ALMIRA_BACKUP_DOCUMENTS=external backup_with s3-acknowledged "$S3_ENV"
DEST=$(ls -d "$OUT"/almira-* 2>/dev/null | head -1)
check "6 · acknowledged, the backup is taken (exit $STATUS)" bash -c "[ $STATUS = 0 ] && [ -f '$DEST/database.dump' ] && [ -f '$DEST/manifest.json' ]"
[ "$STATUS" = 0 ] || sed 's/^/        /' "$WORK/out"
check "    without documents.tgz, and no tar of any volume" bash -c "[ ! -e '$DEST/documents.tgz' ] && ! grep -q 'tar czf' '$CALLS'"
check "    its output says the documents are NOT in it, and names the bucket" \
  bash -c "grep -q 'DOCUMENTS ARE NOT IN THIS BACKUP' '$WORK/out' && grep -q \"bucket 'almira-docs-test'\" '$WORK/out'"
check "    so does manifest.json" python3 -c '
import json, sys
m = json.load(open(sys.argv[1]))
d = m["documents"]
assert d["in_this_backup"] is False and d["provider"] == "s3", d
assert d["bucket"] == "almira-docs-test" and d["prefix"] == "documents/" and d["region"] == "ap-south-1", d
assert d["endpoint"] == "https://s3.example.test:9000", d
assert "NOT in this backup" in d["note"] and "almira-docs-test" in d["note"], d
assert set(m["files"]) == {"database.dump"}, m["files"]
' "$DEST/manifest.json"
check "    and no credential from the env file is in the output or the manifest" \
  bash -c "! grep -Eq 'AKIASHOULDNOTAPPEAR|secret-should-not-appear|endpoint-password|X-Amz-Signature' '$WORK/out' '$DEST/manifest.json'"

finish
