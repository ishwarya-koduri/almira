#!/usr/bin/env bash
# =============================================================================
# Restores a backup taken by scripts/backup.sh into an EMPTY stack, and then
# proves it restored correctly before anybody is let near it.
#
#   ./scripts/restore.sh --project almira-prod --env-file .env.production --from /srv/backups/almira-…
#   ./scripts/restore.sh --project almira-prod --env-file .env.production --from … --verify-only
#
# In order, stopping at the first thing that is wrong:
#
#   0. Where the documents are. A backup whose manifest says they are NOT in
#      it (documents in S3) restores only with ALMIRA_BACKUP_DOCUMENTS=external
#      set, only into a target that uses object storage, and says so; the flag
#      with a backup that holds its documents is refused as a contradiction. A
#      manifest from before the field is treated per the target env file's
#      provider (scripts/lib/backup_documents.py). Checked from the files alone,
#      before anything is started or written — --verify-only included.
#      For data-only testing only, ALMIRA_RESTORE_DOCUMENTS_ABSENT set to exactly
#      every-document-will-be-a-broken-link (with the acknowledgement) lets such
#      a backup into a filesystem target; the database is restored, no document
#      is, and the backup's manifest.json is stamped with a documents_absent
#      entry. The override is refused in every other case.
#   1. The backup files match the sha256 in their manifest.
#   2. The target is EMPTY and protected: the database has no table and has
#      page checksums on, and the documents volume is empty. It refuses a
#      database with any table in it — restoring over live data is not a
#      restore, it is a merge nobody designed — and one without checksums,
#      because the application would refuse to start on it anyway and finding
#      that out after a two-hour restore is worse (docs/17 §3). All of this is
#      checked before anything is written to the target: the documents volume
#      used to be checked only after pg_restore, which left a restored database
#      beside a volume it refused.
#   3. The runtime role is created (the same bootstrap as a fresh install), so
#      the privileges in the dump have a role to land on.
#   4. pg_restore, stopping on the first error.
#   5. The documents, extracted into the volume checked in step 2 — or, when
#      they are external, not: the script says they must already be in the bucket.
#   6. VERIFY:
#        a. row counts per table equal the counts in the dump (straight after
#           pg_restore only — skipped by --verify-only, since a running
#           application adds rows as soon as anybody signs in);
#        b. the structural sweep of every zero-knowledge ciphertext
#           (deploy/restore/sweep.sql), which names each defective row;
#        c. the stored digest of every ciphertext, where the schema has one
#           (deploy/restore/digest-check.sql).
#      --verify-only runs b and c against a stack that is already restored.
#
# The application is NOT started. Start it yourself once this is green, then
# check /health/ready and run scripts/smoke-prod.sh. The KMS key must be the
# one that was in use when the backup was taken; this script cannot check that,
# and the first sign of a wrong key is every account number failing to reveal.
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

RED=$'\033[31m'; GREEN=$'\033[32m'; BOLD=$'\033[1m'; DIM=$'\033[2m'; OFF=$'\033[0m'
die() { echo "${RED}${BOLD}Stopped:${OFF}${RED} $*${OFF}" >&2; exit 1; }
step() { echo; echo "${BOLD}$*${OFF}"; }
ok() { echo "  ${GREEN}ok${OFF}   $*"; }

PROJECT="" ENV_FILE="" FROM="" OVERRIDE="" VERIFY_ONLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --project)  PROJECT="$2"; shift 2;;
    --env-file) ENV_FILE="$2"; shift 2;;
    --from)     FROM="$2"; shift 2;;
    --compose-override) OVERRIDE="$2"; shift 2;;
    --verify-only) VERIFY_ONLY=1; shift;;
    -h|--help)  sed -n '2,42p' "$0"; exit 0;;
    *) die "unknown option: $1";;
  esac
done
[ -n "$PROJECT" ]  || die "--project is required."
[ -n "$ENV_FILE" ] || die "--env-file is required."
[ -n "$FROM" ]     || die "--from is required."
[ -f "$ENV_FILE" ] || die "No such env file: $ENV_FILE"
[ -f "$FROM/manifest.json" ] || die "$FROM has no manifest.json — not a backup from scripts/backup.sh."
case "$PROJECT" in
  almira|almira-personal) die "'$PROJECT' is a development stack's project name.";;
esac

# --- 0 · where the documents are: from the files alone, before anything else ---
# (docs/known-issues.md, "A guard runs before the action it guards")
DOCS_DECISION=$(python3 scripts/lib/backup_documents.py restore "$FROM/manifest.json" "$ENV_FILE" 2>&1) \
  || die "$DOCS_DECISION"
eval "$DOCS_DECISION"
if [ "${DOCS_ABSENT:-0}" = 1 ] && [ "$VERIFY_ONLY" = 0 ]; then
  # The stamp is written after pg_restore; a manifest it cannot write to is found out now.
  [ -w "$FROM/manifest.json" ] && [ -w "$FROM" ] \
    || die "ALMIRA_RESTORE_DOCUMENTS_ABSENT is set, but $FROM/manifest.json cannot be written, so the restore could not be stamped 'documents absent'. Nothing has been written."
fi

dc() {
  docker compose -p "$PROJECT" -f deploy/docker-compose.prod.yml ${OVERRIDE:+-f "$OVERRIDE"} \
    --env-file "$ENV_FILE" "$@"
}
sql() { dc exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAq -v ON_ERROR_STOP=1' <<<"$1"; }
psql_file() { dc exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -X -q -v ON_ERROR_STOP=1 -f -' < "$1"; }

echo "${BOLD}Restore into project $PROJECT from $FROM${OFF}"
if [ "${DOCS_ABSENT:-0}" = 1 ]; then
  echo "${RED}${BOLD}  DATA-ONLY TEST RESTORE: DOCUMENTS ABSENT.${OFF}${RED} $DOCS_WHERE, and this target has no bucket: every document will be a broken link.${OFF}"
  echo "  ${DIM}(ALMIRA_RESTORE_DOCUMENTS_ABSENT=every-document-will-be-a-broken-link; the backup's manifest.json is stamped)${OFF}"
elif [ "$DOCS_EXTERNAL" = 1 ]; then
  echo "${RED}${BOLD}  DOCUMENTS ARE NOT RESTORED BY THIS SCRIPT:${OFF}${RED} $DOCS_WHERE.${OFF}"
  echo "  ${DIM}(acknowledged with ALMIRA_BACKUP_DOCUMENTS=external)${OFF}"
fi

if [ "$VERIFY_ONLY" = 0 ]; then
  step "1 · The backup is the backup that was taken"
  python3 - "$FROM" <<'PY' || die "a backup file does not match its manifest. Do not restore it."
import hashlib, json, os, sys
d = sys.argv[1]
m = json.load(open(os.path.join(d, "manifest.json")))
bad = False
for name, want in m["files"].items():
    h = hashlib.sha256()
    with open(os.path.join(d, name), "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""): h.update(chunk)
    if h.hexdigest() != want["sha256"]:
        print(f"  {name}: sha256 {h.hexdigest()} but the manifest says {want['sha256']}")
        bad = True
    else:
        print(f"  ok   {name} matches its manifest")
sys.exit(1 if bad else 0)
PY

  step "2 · The target is empty, and protected"
  dc up -d --wait db redis >/dev/null
  TABLES=$(sql "select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where c.relkind in ('r','p') and n.nspname not in ('pg_catalog','information_schema')
                  and n.nspname not like 'pg_toast%'")
  [ "$TABLES" = 0 ] || die "the target database already has $TABLES table(s). This restores into an EMPTY database only."
  ok "no tables in the target database"
  [ "$(sql "show data_checksums")" = on ] || die "the target database has data_checksums off. Recreate it with POSTGRES_INITDB_ARGS=--data-checksums (docs/17 §3)."
  ok "page checksums are on"
  DOCS_VOLUME=$(dc config --format json | python3 -c 'import json,sys; print(json.load(sys.stdin)["volumes"]["documents"]["name"])')
  # Inspected, not created: a volume that does not exist yet is empty, and
  # nothing is made on the target until every check here has passed.
  if docker volume inspect "$DOCS_VOLUME" >/dev/null 2>&1; then
    EXISTING=$(docker run --rm -v "$DOCS_VOLUME":/dst:ro alpine:latest sh -c 'find /dst -mindepth 1 | wc -l' | tr -d '[:space:]')
    [ "$EXISTING" = 0 ] || die "the documents volume $DOCS_VOLUME is not empty ($EXISTING entries). Nothing has been written to the target."
    ok "the documents volume $DOCS_VOLUME is empty"
  else
    ok "the documents volume $DOCS_VOLUME does not exist yet"
  fi

  step "3 · The runtime role"
  dc --profile bootstrap run --rm -T db-bootstrap >/dev/null
  ok "created (idempotent)"

  step "4 · The database"
  dc exec -T db sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --exit-on-error' < "$FROM/database.dump" \
    || die "pg_restore failed. The target is now partly restored; recreate its volume before trying again."
  ok "pg_restore finished without error"

  step "5 · The documents"
  if [ "${DOCS_ABSENT:-0}" = 1 ]; then
    python3 scripts/lib/backup_documents.py stamp-absent "$FROM/manifest.json" "$PROJECT" "$ENV_FILE" \
      || die "the database is restored, but the manifest could not be stamped 'documents absent'. Treat this target as data-only."
    echo "  ${RED}absent${OFF}: no document restored, none reachable. $FROM/manifest.json now records this restore under documents_absent."
  elif [ "$DOCS_EXTERNAL" = 1 ]; then
    echo "  ${RED}not restored${OFF}: $DOCS_WHERE. Every document must already be in that bucket."
  else
    docker volume create "$DOCS_VOLUME" >/dev/null
    docker run --rm -v "$DOCS_VOLUME":/dst -v "$(cd "$FROM" && pwd)":/in:ro alpine:latest \
      sh -c 'tar xzf /in/documents.tgz -C /dst && chown -R 10001:10001 /dst'
    ok "extracted into $DOCS_VOLUME"
  fi
fi

step "6a · Every table has the rows the dump had"
if [ "$VERIFY_ONLY" = 1 ]; then
  # Counts mean something only before the application has written anything: a
  # single sign-in adds sessions and audit rows. So they are checked straight
  # after pg_restore, and not on a stack that has been running since.
  echo "  ${DIM}skipped with --verify-only: counts are checked once, straight after pg_restore.${OFF}"
else
COUNT_SQL=$(python3 -c '
import json, sys
tables = sorted(json.load(open(sys.argv[1]))["row_counts"])
print(" union all ".join(f"select {t!r}, count(*) from {t}" for t in tables) or "select 1 where false")
' "$FROM/manifest.json")
dc exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAq -F "|" -v ON_ERROR_STOP=1' <<<"$COUNT_SQL" \
  | python3 -c '
import json, sys
want = json.load(open(sys.argv[1]))["row_counts"]
have = {t: int(n) for t, n in (line.rstrip("\n").split("|") for line in sys.stdin if line.strip())}
diff = [(t, want[t], have.get(t)) for t in sorted(want) if have.get(t) != want[t]]
for t, w, h in diff:
    print(f"  {t}: the dump has {w} rows, the database has {h}")
if not diff:
    print(f"  ok   {len(want)} tables, {sum(want.values())} rows, all equal to the dump")
sys.exit(1 if diff else 0)
'  "$FROM/manifest.json" || die "row counts differ from the backup (listed above)."
fi

step "6b · Structural sweep of zero-knowledge ciphertext"
psql_file deploy/restore/sweep.sql || die "the sweep found defective ciphertext (listed above)."

if [ -n "$(sql "select 1 from information_schema.columns where table_name = 'sealed_values' and column_name = 'ciphertext_sha256'")" ]; then
  step "6c · Stored digests"
  psql_file deploy/restore/digest-check.sql || die "stored digests do not match (listed above)."
else
  step "6c · Stored digests"
  echo "  ${DIM}skipped: this schema predates the stored digest.${OFF}"
fi

echo
echo "${GREEN}${BOLD}Restore verified.${OFF}"
if [ "${DOCS_ABSENT:-0}" = 1 ]; then
  echo "  ${RED}${BOLD}Data-only test restore: documents absent.${OFF}${RED} Every document is a broken link. Do not run the service on this.${OFF}"
elif [ "$DOCS_EXTERNAL" = 1 ]; then
  echo "  ${RED}${BOLD}Documents were not part of it:${OFF}${RED} $DOCS_WHERE.${OFF}"
fi
echo "  Next: start the application with the KMS key from when the backup was taken,"
echo "  ${DIM}docker compose -p $PROJECT -f deploy/docker-compose.prod.yml${OVERRIDE:+ -f $OVERRIDE} --env-file $ENV_FILE up -d app${OFF}"
echo "  then: curl -fsS http://127.0.0.1:<port>/health/ready  and  ./scripts/smoke-prod.sh"
