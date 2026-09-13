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
# WHAT IS NOT IN IT, ON PURPOSE: ALMIRA_KMS_MASTER_KEY. Account numbers and
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
    -h|--help)  sed -n '2,30p' "$0"; exit 0;;
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

dc() {
  docker compose -p "$PROJECT" -f deploy/docker-compose.prod.yml ${OVERRIDE:+-f "$OVERRIDE"} \
    --env-file "$ENV_FILE" "$@"
}

dc exec -T db pg_isready -q || die "The database in project '$PROJECT' is not running or not ready."

STAMP=$(date -u +%Y%m%dT%H%M%SZ)
DEST="$OUT/almira-$STAMP"
[ -e "$DEST" ] && die "$DEST already exists."
mkdir -p "$DEST"
chmod 700 "$DEST"

echo "${BOLD}Backing up project $PROJECT → $DEST${OFF}"

echo "  database…"
dc exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$DEST/database.dump"

sql() { dc exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAq -v ON_ERROR_STOP=1' <<<"$1"; }
MIGRATION=$(sql "select version from flyway_schema_history where success and version is not null order by installed_rank desc limit 1")
CHECKSUMS=$(sql "show data_checksums")
SERVER=$(sql "show server_version")

echo "  documents…"
DOCS_VOLUME=$(dc config --format json | python3 -c 'import json,sys; print(json.load(sys.stdin)["volumes"]["documents"]["name"])')
docker volume inspect "$DOCS_VOLUME" >/dev/null 2>&1 || die "Documents volume $DOCS_VOLUME does not exist."
# Read-only mount: a backup has no business being able to change what it copies.
docker run --rm -v "$DOCS_VOLUME":/src:ro -v "$(cd "$DEST" && pwd)":/out alpine:latest \
  tar czf /out/documents.tgz -C /src .

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

python3 - "$DEST" "$PROJECT" "$MIGRATION" "$CHECKSUMS" "$SERVER" "$(sed -n 's/^ALMIRA_IMAGE_TAG=//p' "$ENV_FILE")" <<'PY'
import hashlib, json, os, sys, datetime
dest, project, migration, checksums, server, tag = sys.argv[1:7]
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
        "database.dump": {"sha256": sha("database.dump"), "bytes": os.path.getsize(os.path.join(dest, "database.dump"))},
        "documents.tgz": {"sha256": sha("documents.tgz"), "bytes": os.path.getsize(os.path.join(dest, "documents.tgz"))},
    },
    "row_counts": counts,
    "not_included": "ALMIRA_KMS_MASTER_KEY — kept separately, never with a backup (docs/17 §4)",
}
json.dump(manifest, open(os.path.join(dest, "manifest.json"), "w"), indent=2, sort_keys=True)
PY

ROWS=$(python3 -c 'import json,sys; m=json.load(open(sys.argv[1])); print(sum(m["row_counts"].values()), len(m["row_counts"]))' "$DEST/manifest.json")
echo
echo "${GREEN}Backup written.${OFF} ${DIM}$(du -sh "$DEST" | cut -f1)  ·  rows/tables in dump: $ROWS  ·  migration $MIGRATION  ·  checksums $CHECKSUMS${OFF}"
echo "  $DEST"
echo
echo "  ${BOLD}It is not a backup until it has been restored.${OFF} Prove it:"
echo "  ${DIM}./scripts/restore.sh --project <an EMPTY stack> --env-file … --from $DEST${OFF}"
echo "  And copy it off this host. The KMS key goes somewhere else again."
