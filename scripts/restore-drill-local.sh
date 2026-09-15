#!/usr/bin/env bash
# =============================================================================
# A backup-and-restore drill that needs nothing but Docker: two THROWAWAY
# Postgres containers, a dump from one restored into the other, and proof that
# the copy has the same rows and the same privacy.
#
#   ./scripts/restore-drill-local.sh
#   ./scripts/restore-drill-local.sh --name-prefix ws-ops-drill --keep
#   ./scripts/restore-drill-local.sh --from /srv/backups/almira-<timestamp>
#
# In order, stopping at the first thing that is wrong:
#
#   1. SOURCE  a new container with page checksums, the two roles, every
#              migration in version order, and deploy/restore/drill/local-seed.sql
#              written through row-level security as the runtime role.
#   2. BACKUP  pg_dump -Fc, the same format scripts/backup.sh takes.
#   3. RESTORE into a second new container: checksums on, the runtime role
#              created first, pg_restore --exit-on-error.
#   4. VERIFY  a. every table has exactly the rows it had in the source;
#              b. every table has the same RLS switch and the same policies,
#                 compared as text, so a policy lost or altered is named;
#              c. a person still sees only what they should, on the copy:
#                 Vikram (an admin) does not see Asha's private FD;
#              d. db/tests/rls_privacy_test.sql passes against the copy, as
#                 the runtime role (it rolls itself back);
#              e. the zero-knowledge sweep (deploy/restore/sweep.sql).
#
# --from BACKUP drills a backup taken by scripts/backup.sh instead of a fresh
# dump: no source container. Before any container is started it checks where
# the backup's documents are (the same rule as restore.sh: a manifest that says
# they are NOT in the backup needs ALMIRA_BACKUP_DOCUMENTS=external and the
# drill says so; the flag with a backup that holds them is refused), then that
# database.dump matches the manifest's sha256. Step 4a compares against the
# manifest's row counts; 4b, needing a source, and 4c, needing the local seed,
# are skipped and say so; 4d and 4e run as above.
#
# This proves the DATABASE half of docs/17 §6 on any machine. It does not take
# the documents volume or start the application; scripts/backup.sh and
# scripts/restore.sh do that against a real compose stack, and
# deploy/restore/drill/ reads everything back through the API.
#
# Both containers are removed at the end, pass or fail, unless --keep. It never
# touches a container it did not start: the names are the prefix plus -source
# and -restore, and a prefix of an existing stack is refused.
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

RED=$'\033[31m'; GREEN=$'\033[32m'; BOLD=$'\033[1m'; DIM=$'\033[2m'; OFF=$'\033[0m'
die() { echo "${RED}${BOLD}Stopped:${OFF}${RED} $*${OFF}" >&2; exit 1; }
step() { echo; echo "${BOLD}$*${OFF}"; }
ok() { echo "  ${GREEN}ok${OFF}   $*"; }

PREFIX="almira-restore-drill" KEEP=0 IMAGE="postgres:16-alpine" FROM=""
while [ $# -gt 0 ]; do
  case "$1" in
    --from) FROM="$2"; shift 2;;
    --name-prefix) PREFIX="$2"; shift 2;;
    --keep) KEEP=1; shift;;
    --image) IMAGE="$2"; shift 2;;
    -h|--help) sed -n '2,46p' "$0"; exit 0;;
    *) die "unknown option: $1";;
  esac
done
case "$PREFIX" in
  almira|almira-db|almira-redis|almira-personal*|almira-prod*) die "'$PREFIX' is the name of a real stack. Choose a throwaway prefix.";;
esac
SRC="$PREFIX-source" DST="$PREFIX-restore"

# --- a backup from backup.sh: where its documents are, before any container ---
# (docs/known-issues.md, "A guard runs before the action it guards")
if [ -n "$FROM" ]; then
  [ -f "$FROM/manifest.json" ] && [ -f "$FROM/database.dump" ] || die "$FROM is not a backup from scripts/backup.sh (no manifest.json or database.dump)."
  DOCS_DECISION=$(python3 scripts/lib/backup_documents.py drill "$FROM/manifest.json" 2>&1) || die "$DOCS_DECISION"
  eval "$DOCS_DECISION"
  python3 - "$FROM" <<'PY' || die "database.dump does not match its manifest. Do not restore it."
import hashlib, json, os, sys
d = sys.argv[1]
want = json.load(open(os.path.join(d, "manifest.json")))["files"]["database.dump"]["sha256"]
h = hashlib.sha256()
with open(os.path.join(d, "database.dump"), "rb") as f:
    for chunk in iter(lambda: f.read(1 << 20), b""): h.update(chunk)
sys.exit(0 if h.hexdigest() == want else 1)
PY
fi
for name in "$SRC" "$DST"; do
  docker inspect "$name" >/dev/null 2>&1 && die "a container called $name already exists. Remove it, or choose another --name-prefix."
done

WORK=$(mktemp -d)
cleanup() {
  if [ "$KEEP" = 0 ]; then
    docker rm -f "$SRC" "$DST" >/dev/null 2>&1 || true
  else
    echo "${DIM}kept: $SRC $DST${OFF}"
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT

start() {
  docker run -d --name "$1" \
    -e POSTGRES_USER=almira -e POSTGRES_PASSWORD=drill -e POSTGRES_DB=almira \
    -e POSTGRES_INITDB_ARGS=--data-checksums \
    -v "$PWD/infra/postgres-init:/docker-entrypoint-initdb.d:ro" \
    "$IMAGE" >/dev/null
  for _ in $(seq 1 60); do
    # The init scripts restart the server once; wait for the one after that.
    if docker exec "$1" pg_isready -q -U almira -d almira 2>/dev/null \
       && docker exec "$1" psql -U almira -d almira -tAc "select 1 from pg_roles where rolname='almira_app'" 2>/dev/null | grep -q 1; then
      sleep 1; return 0
    fi
    sleep 1
  done
  die "$1 did not become ready."
}
owner() { docker exec -i "$1" psql -U almira -d almira -X -q -tA -v ON_ERROR_STOP=1 "${@:2}"; }
app()   { docker exec -i -e PGPASSWORD=app_dev_password "$1" psql -h 127.0.0.1 -U almira_app -d almira -X -q -tA -v ON_ERROR_STOP=1 "${@:2}"; }

COUNTS_SQL=$(cat <<'SQL'
select format('select %L, count(*) from %I.%I', n.nspname || '.' || c.relname, n.nspname, c.relname)
from pg_class c join pg_namespace n on n.oid = c.relnamespace
where c.relkind in ('r', 'p') and n.nspname in ('public', 'app')
order by 1
SQL
)
counts() { owner "$1" <<<"$COUNTS_SQL" | while read -r q; do owner "$1" -F '|' <<<"$q;"; done; }

POLICY_SQL=$(cat <<'SQL'
select c.relname || '|rls=' || c.relrowsecurity || '|force=' || c.relforcerowsecurity
from pg_class c join pg_namespace n on n.oid = c.relnamespace
where c.relkind in ('r', 'p') and n.nspname = 'public'
union all
select tablename || '|' || policyname || '|' || cmd || '|' || array_to_string(roles, ',') || '|'
       || coalesce(qual, '') || '|' || coalesce(with_check, '')
from pg_policies where schemaname = 'public'
order by 1
SQL
)

if [ -n "$FROM" ]; then
echo "${BOLD}Restore drill: $FROM → $DST${OFF}"
if [ "$DOCS_EXTERNAL" = 1 ]; then
  echo "${RED}${BOLD}  DOCUMENTS ARE NOT IN THIS BACKUP:${OFF}${RED} $DOCS_WHERE.${OFF}"
  echo "  ${DIM}(acknowledged with ALMIRA_BACKUP_DOCUMENTS=external; this drill restores no documents either way)${OFF}"
else
  echo "  ${DIM}documents: $DOCS_WHERE — this drill restores the database only; restore.sh restores the documents${OFF}"
fi

step "1 · Source"
ok "the backup's database.dump matches its manifest (no source container)"
cp "$FROM/database.dump" "$WORK/database.dump"
python3 -c '
import json, sys
for t, n in sorted(json.load(open(sys.argv[1]))["row_counts"].items()):
    print(f"{t}|{n}")
' "$FROM/manifest.json" | sort > "$WORK/source.counts"

step "2 · Backup"
ok "taken by scripts/backup.sh at $(python3 -c 'import json,sys; m=json.load(open(sys.argv[1])); print(m["created_at"], "migration", m["migration_version"])' "$FROM/manifest.json")"
else
echo "${BOLD}Restore drill: $SRC → $DST${OFF}"

step "1 · Source"
start "$SRC"
ok "started with page checksums ($(owner "$SRC" -c 'show data_checksums'))"
ordered=$(ls db/migrations/V*.sql | sed 's/.*\/V\([0-9]*\)__/\1 &/' | sort -n | cut -d' ' -f2)
for file in $ordered db/migrations/R__grants.sql; do
  owner "$SRC" < "$file" >/dev/null 2>"$WORK/err" || { cat "$WORK/err" >&2; die "migration $(basename "$file") failed on the source."; }
done
ok "$(echo "$ordered" | wc -w | tr -d ' ') migrations and the grants applied"
app "$SRC" < deploy/restore/drill/local-seed.sql >/dev/null 2>"$WORK/err" || { cat "$WORK/err" >&2; die "the seed failed."; }
ok "seeded through row-level security as almira_app"

step "2 · Backup"
docker exec "$SRC" pg_dump -U almira -d almira -Fc > "$WORK/database.dump"
counts "$SRC" | sort > "$WORK/source.counts"
owner "$SRC" <<<"$POLICY_SQL" > "$WORK/source.policies"
ok "pg_dump: $(du -h "$WORK/database.dump" | cut -f1), $(wc -l < "$WORK/source.counts" | tr -d ' ') tables, $(awk -F'|' '{s+=$2} END {print s}' "$WORK/source.counts") rows"
fi

step "3 · Restore"
start "$DST"
[ "$(owner "$DST" -c 'show data_checksums')" = on ] || die "the restore target has page checksums off."
[ "$(owner "$DST" -c "select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace where c.relkind in ('r','p') and n.nspname not in ('pg_catalog','information_schema') and n.nspname not like 'pg_toast%'")" = 0 ] \
  || die "the restore target is not empty."
ok "target is empty, checksums on, runtime role present"
docker exec -i "$DST" pg_restore -U almira -d almira --exit-on-error < "$WORK/database.dump" 2>"$WORK/err" \
  || { cat "$WORK/err" >&2; die "pg_restore failed."; }
ok "pg_restore finished without error"

step "4a · Every table has the rows it had"
if [ -n "$FROM" ]; then
  # Exactly the tables the manifest counted, in whichever schema: the tables
  # whose rows it left out on purpose (excluded_table_data) are not among them.
  python3 -c '
import json, sys
for t in sorted(json.load(open(sys.argv[1]))["row_counts"]):
    print(f"select {t!r}, count(*) from {t};")
' "$FROM/manifest.json" | owner "$DST" -F '|' | sort > "$WORK/restore.counts"
else
  counts "$DST" | sort > "$WORK/restore.counts"
fi
if ! diff -u "$WORK/source.counts" "$WORK/restore.counts" > "$WORK/counts.diff"; then
  sed 's/^/  /' "$WORK/counts.diff"; die "row counts differ (source -, restore +)."
fi
ok "$(wc -l < "$WORK/restore.counts" | tr -d ' ') tables, $(awk -F'|' '{s+=$2} END {print s}' "$WORK/restore.counts") rows, all equal"

step "4b · Row-level security came back as it was"
owner "$DST" <<<"$POLICY_SQL" > "$WORK/restore.policies"
if [ -n "$FROM" ]; then
  echo "  ${DIM}no source to compare with: $(grep -c 'rls=true' "$WORK/restore.policies") tables with RLS on and $(grep -vc '|rls=' "$WORK/restore.policies") policies on the copy; 4d checks they hold${OFF}"
elif ! diff -u "$WORK/source.policies" "$WORK/restore.policies" > "$WORK/policies.diff"; then
  sed 's/^/  /' "$WORK/policies.diff" | head -40; die "row-level security differs (source -, restore +)."
else
  ok "$(grep -c 'rls=true' "$WORK/restore.policies") tables with RLS on, $(grep -vc '|rls=' "$WORK/restore.policies") policies, identical"
fi

step "4c · A person still sees only their own"
if [ -n "$FROM" ]; then
  echo "  ${DIM}skipped: it reads the local seed's people, who are not in this backup${OFF}"
else
seen_by() {
  app "$DST" <<SQL
begin;
select set_config('app.user_id', (select id::text from users where phone = '$1'), true);
select count(*) from investments where title like '%private%';
rollback;
SQL
}
asha=$(seen_by '+919000000101' | grep -E '^[0-9]+$' | tail -1)
vikram=$(seen_by '+919000000102' | grep -E '^[0-9]+$' | tail -1)
[ "$asha" = 1 ] && [ "$vikram" = 1 ] \
  || die "on the copy, Asha sees $asha private holding(s) and Vikram sees $vikram; each should see exactly their own one."
ok "Asha sees her private FD and not Vikram's SIP; Vikram, an admin, sees only his own"
fi
[ "$(app "$DST" -c 'select count(*) from measurement_daily_counts')" = 0 ] \
  || die "the runtime role can read the measurement counts on the copy."
ok "the runtime role still cannot read the measurement counts"

step "4d · The privacy suite, against the copy"
app "$DST" < db/tests/rls_privacy_test.sql > "$WORK/rls.out" 2>&1 \
  || { grep -E "FAILED|ERROR" "$WORK/rls.out" | head -5 >&2; die "db/tests/rls_privacy_test.sql failed on the restored database."; }
grep -q "ALL PRIVACY ASSERTIONS PASSED" "$WORK/rls.out" || die "the privacy suite did not reach its end."
ok "$(grep -c '  ok  ' "$WORK/rls.out") assertions passed"

step "4e · Structural sweep of zero-knowledge ciphertext"
owner "$DST" < deploy/restore/sweep.sql > "$WORK/sweep.out" 2>&1 || { cat "$WORK/sweep.out" >&2; die "the sweep found defective ciphertext."; }
ok "no defective sealed value"

echo
echo "${GREEN}${BOLD}Restore drill passed${OFF} $(date -u +%Y-%m-%dT%H:%MZ): rows, row-level security and the privacy suite all hold on the restored copy."
