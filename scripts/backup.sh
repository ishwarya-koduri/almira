#!/usr/bin/env bash
# =============================================================================
# Takes one backup of a running Almira compose stack: the database, the
# documents, and a manifest that lets a restore prove it restored correctly.
#
#   ./scripts/backup.sh --project almira-prod --env-file .env.production --out /srv/backups
#
# Writes <out>/almira-<UTC timestamp>/ containing:
#
#   database.dump   pg_dump custom format
#   documents.tgz   the documents volume (encrypted at rest by the application)
#   manifest.json   sha256 of both files, row counts per table AS THEY ARE IN
#                   THE DUMP, the migration version, and the server settings
#
# WHAT IS NOT IN IT, ON PURPOSE: the rows of the two tables that hold what is
# still waiting to be sent — outbound_message_bodies (rendered messages) and
# sign_in_code_email_bodies (queued sign-in emails, from which a live code can be
# derived). A backup holding them would be a credential store (owner's decision,
# 2026-09-15). The tables are in the dump, empty. A message still queued when the
# backup was taken comes back without its body and is recorded failed, once, as
# body_not_restored instead of being sent (docs/13 "After a restore"). The
# manifest lists both tables under excluded_table_data.
#
# DOCUMENTS IN OBJECT STORAGE (ALMIRA_STORAGE_PROVIDER=s3 in the env file) are
# not in it either, and this script will not pretend otherwise: it refuses to
# run unless ALMIRA_BACKUP_DOCUMENTS=external is set for the command, and then
# says in its output and in manifest.json that the documents are NOT in this
# backup and which bucket they are in (no credential). With the filesystem
# provider, ALMIRA_BACKUP_DOCUMENTS=external is a contradiction and refused.
# (Owner's decision, 2026-09-15; scripts/lib/backup_documents.py.)
#
# And ALMIRA_KMS_MASTER_KEY. Account numbers and
# document contents are unreadable without it, so a backup that contained the
# key would be a backup that contained the data in the clear. Keep the key
# somewhere that is not this host and not this backup (docs/17 §4). A backup
# without anywhere to get the key from is ciphertext.
#
# Order matters, and it is database FIRST, then documents. Documents are only
# ever added or soft-deleted, so every document row in the dump has its file in
# a tarball taken afterwards. The reverse order can capture a row whose file
# is not in the tarball.
#
# Everything this checks is checked BEFORE the dump is written: the database is
# up, where the documents are and that this was acknowledged, the documents
# volume exists, and the bodies table is where the exclusion
# expects it. A backup refused after pg_dump used to leave a dump behind with no
# documents and no manifest (docs/known-issues.md, "A guard runs before the
# action it guards").
#
# --project is required and there is no default. A backup script that guesses
# which stack it is looking at is how a development database gets backed up
# and a production one does not.
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

RED=$'\033[31m'; GREEN=$'\033[32m'; BOLD=$'\033[1m'; DIM=$'\033[2m'; OFF=$'\033[0m'
die() { echo "${RED}$*${OFF}" >&2; exit 1; }

PROJECT="" ENV_FILE="" OUT="" OVERRIDE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --project)  PROJECT="$2"; shift 2;;
    --env-file) ENV_FILE="$2"; shift 2;;
    --out)      OUT="$2"; shift 2;;
    --compose-override) OVERRIDE="$2"; shift 2;;
    -h|--help)  sed -n '2,49p' "$0"; exit 0;;
    *) die "unknown option: $1";;
  esac
done
[ -n "$PROJECT" ]  || die "--project is required (e.g. almira-prod)."
[ -n "$ENV_FILE" ] || die "--env-file is required."
[ -n "$OUT" ]      || die "--out is required."
[ -f "$ENV_FILE" ] || die "No such env file: $ENV_FILE"
case "$PROJECT" in
  almira|almira-personal) die "'$PROJECT' is a development stack's project name. This script is for a deployment.";;
esac

# --- where the documents are: read from the env file, before anything else ---
# Runs before the database is even asked whether it is up, so a refusal here
# has touched nothing (docs/known-issues.md, "A guard runs before the action it
# guards").
DOCS_DECISION=$(python3 scripts/lib/backup_documents.py backup "$ENV_FILE" 2>&1) || die "$DOCS_DECISION"
eval "$DOCS_DECISION"

dc() {
  docker compose -p "$PROJECT" -f deploy/docker-compose.prod.yml ${OVERRIDE:+-f "$OVERRIDE"} \
    --env-file "$ENV_FILE" "$@"
}

dc exec -T db pg_isready -q || die "The database in project '$PROJECT' is not running or not ready."

sql() { dc exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAq -v ON_ERROR_STOP=1' <<<"$1"; }

# --- checked before anything is written --------------------------------------
if [ "$DOCS_EXTERNAL" = 0 ]; then
  DOCS_VOLUME=$(dc config --format json | python3 -c 'import json,sys; print(json.load(sys.stdin)["volumes"]["documents"]["name"])')
  docker volume inspect "$DOCS_VOLUME" >/dev/null 2>&1 || die "Documents volume $DOCS_VOLUME does not exist."
fi

# The tables whose rows are left out. --exclude-table-data matches by name and
# says nothing when it matches no table, so a renamed or third bodies table
# would be dumped in full without a word. Refuse unless every table that looks
# like one is one of these. (An older database has fewer, or none: only the ones
# that exist are left out.)
BODIES_KNOWN="public.outbound_message_bodies public.sign_in_code_email_bodies"
BODIES_FOUND=$(sql "select coalesce(string_agg(n.nspname || '.' || c.relname, ' ' order by n.nspname, c.relname), '')
                      from pg_class c join pg_namespace n on n.oid = c.relnamespace
                     where c.relkind in ('r', 'p') and c.relname ilike '%bod%'
                       and n.nspname not in ('pg_catalog', 'information_schema')")
BODIES_TABLES=""
for t in $BODIES_FOUND; do
  case " $BODIES_KNOWN " in
    *" $t "*) BODIES_TABLES="${BODIES_TABLES:+$BODIES_TABLES }$t";;
    *) die "Refusing to back up: queued bodies are expected only in $BODIES_KNOWN, but this database also has $t. Its rows would be dumped in full. Update BODIES_KNOWN in this script to match the schema.";;
  esac
done

STAMP=$(date -u +%Y%m%dT%H%M%SZ)
DEST="$OUT/almira-$STAMP"
[ -e "$DEST" ] && die "$DEST already exists."

# --- written from here on -----------------------------------------------------
mkdir -p "$DEST"
chmod 700 "$DEST"

echo "${BOLD}Backing up project $PROJECT → $DEST${OFF}"
if [ "$DOCS_EXTERNAL" = 1 ]; then
  echo "${RED}${BOLD}  DOCUMENTS ARE NOT IN THIS BACKUP.${OFF}${RED} They live in $DOCS_WHERE.${OFF}"
  echo "  ${DIM}(acknowledged with ALMIRA_BACKUP_DOCUMENTS=external; the manifest says the same)${OFF}"
fi

echo "  database… ${DIM}(without the rows of ${BODIES_TABLES:-no bodies tables})${OFF}"
EXCLUDES=()
for t in $BODIES_TABLES; do EXCLUDES+=("--exclude-table-data=$t"); done
dc exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc "$@"' sh ${EXCLUDES[@]+"${EXCLUDES[@]}"} > "$DEST/database.dump"

MIGRATION=$(sql "select version from flyway_schema_history where success and version is not null order by installed_rank desc limit 1")
CHECKSUMS=$(sql "show data_checksums")
SERVER=$(sql "show server_version")

if [ "$DOCS_EXTERNAL" = 0 ]; then
  echo "  documents…"
  # Read-only mount: a backup has no business being able to change what it copies.
  docker run --rm -v "$DOCS_VOLUME":/src:ro -v "$(cd "$DEST" && pwd)":/out alpine:latest \
    tar czf /out/documents.tgz -C /src .
else
  echo "  documents… ${BOLD}not taken${OFF}: they are in $DOCS_WHERE"
fi

echo "  manifest…"
# Row counts are read from the DUMP, not from the live database: a count taken a
# second before or after pg_dump's snapshot would disagree with it for no reason,
# and a restore check that cries wolf is a check people learn to ignore.
dc exec -T db pg_restore --data-only -f - < "$DEST/database.dump" | python3 -c '
import json, re, sys
counts, table = {}, None
for line in sys.stdin:
    if table is None:
        m = re.match(r"COPY ([\w.\"]+) .*FROM stdin;", line)
        if m:
            table = m.group(1).replace("\"", "")
            counts[table] = 0
    elif line == "\\.\n":
        table = None
    else:
        counts[table] += 1
json.dump(counts, open(sys.argv[1], "w"), indent=1, sort_keys=True)
' "$DEST/.counts.json"

python3 - "$DEST" "$PROJECT" "$MIGRATION" "$CHECKSUMS" "$SERVER" "$(sed -n 's/^ALMIRA_IMAGE_TAG=//p' "$ENV_FILE")" "$BODIES_TABLES" \
  "$(python3 scripts/lib/backup_documents.py manifest-block "$ENV_FILE")" <<'PY'
import hashlib, json, os, sys, datetime
dest, project, migration, checksums, server, tag, bodies, documents = sys.argv[1:9]
documents = json.loads(documents)
def sha(name):
    h = hashlib.sha256()
    with open(os.path.join(dest, name), "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""): h.update(chunk)
    return h.hexdigest()
counts = json.load(open(os.path.join(dest, ".counts.json")))
os.remove(os.path.join(dest, ".counts.json"))
manifest = {
    "format": 1,
    "created_at": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
    "project": project,
    "migration_version": migration,
    "server_version": server,
    "image_tag": tag,
    "data_checksums": checksums,
    "files": {
        name: {"sha256": sha(name), "bytes": os.path.getsize(os.path.join(dest, name))}
        for name in ("database.dump", "documents.tgz") if name == "database.dump" or documents["in_this_backup"]
    },
    # Where the documents are. in_this_backup false: NOT in this backup; the
    # bucket is named, never a credential (scripts/lib/backup_documents.py).
    "documents": documents,
    "row_counts": counts,
    # Rows deliberately not in the dump; the tables themselves are. Not counted
    # above, so a restore's row-count check does not expect them.
    "excluded_table_data": sorted(bodies.split()),
    "not_included": "ALMIRA_KMS_MASTER_KEY — kept separately, never with a backup (docs/17 §4)",
}
json.dump(manifest, open(os.path.join(dest, "manifest.json"), "w"), indent=2, sort_keys=True)
PY

ROWS=$(python3 -c 'import json,sys; m=json.load(open(sys.argv[1])); print(sum(m["row_counts"].values()), len(m["row_counts"]))' "$DEST/manifest.json")
echo
echo "${GREEN}Backup written.${OFF} ${DIM}$(du -sh "$DEST" | cut -f1)  ·  rows/tables in dump: $ROWS  ·  migration $MIGRATION  ·  checksums $CHECKSUMS${OFF}"
echo "  $DEST"
if [ "$DOCS_EXTERNAL" = 1 ]; then
  echo "  ${RED}${BOLD}Documents are NOT in this backup.${OFF}${RED} They live in $DOCS_WHERE.${OFF}"
fi
echo
echo "  ${BOLD}It is not a backup until it has been restored.${OFF} Prove it:"
echo "  ${DIM}./scripts/restore.sh --project <an EMPTY stack> --env-file … --from $DEST${OFF}"
echo "  And copy it off this host. The KMS key goes somewhere else again."
