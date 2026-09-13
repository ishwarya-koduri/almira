#!/usr/bin/env bash
# =============================================================================
# Puts ONE zero-knowledge row back exactly as it is in a backup — the repair for
# a row the structural sweep or the digest check named.
#
#   ./scripts/restore-row.sh --project almira-prod --env-file .env.production \
#       --from /srv/backups/almira-… --table sealed_values --id <uuid>
#
# Only sealed_values and e2e_keys, and only a row that still exists in the live
# database: this repairs damaged bytes, it does not resurrect deleted records.
# It copies the backup's ciphertext columns (and their digests, where the schema
# has them) onto the live row and changes nothing else.
#
# It runs with session_replication_role = replica, so the touch and digest
# triggers do not fire: putting back what was there is not an edit, and must not
# stamp updated_at or recompute a digest from bytes the check has just rejected.
# That setting needs the owner role, which is what this connects as.
#
# Afterwards it re-runs the sweep and the digest check. Read what they say.
# If the backup's copy of the row is also damaged, this has not helped, and the
# honest next step is an older backup or telling the owner the field is lost.
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

RED=$'\033[31m'; GREEN=$'\033[32m'; BOLD=$'\033[1m'; OFF=$'\033[0m'
die() { echo "${RED}${BOLD}Stopped:${OFF}${RED} $*${OFF}" >&2; exit 1; }

PROJECT="" ENV_FILE="" FROM="" OVERRIDE="" TABLE="" ID=""
while [ $# -gt 0 ]; do
  case "$1" in
    --project)  PROJECT="$2"; shift 2;;
    --env-file) ENV_FILE="$2"; shift 2;;
    --from)     FROM="$2"; shift 2;;
    --compose-override) OVERRIDE="$2"; shift 2;;
    --table)    TABLE="$2"; shift 2;;
    --id)       ID="$2"; shift 2;;
    -h|--help)  sed -n '2,22p' "$0"; exit 0;;
    *) die "unknown option: $1";;
  esac
done
for v in PROJECT ENV_FILE FROM TABLE ID; do [ -n "${!v}" ] || die "--$(echo "$v" | tr A-Z_ a-z-) is required."; done
case "$PROJECT" in almira|almira-personal) die "'$PROJECT' is a development stack's project name.";; esac
case "$TABLE" in
  sealed_values) COLUMNS="ciphertext key_version ciphertext_sha256";;
  e2e_keys)      COLUMNS="kdf_salt iterations wrapped_key verifier key_version wrapped_key_sha256 verifier_sha256";;
  *) die "--table must be sealed_values or e2e_keys.";;
esac
[[ "$ID" =~ ^[0-9a-fA-F-]{36}$ ]] || die "--id must be a uuid."
[ -f "$FROM/database.dump" ] || die "$FROM/database.dump not found."

dc() {
  docker compose -p "$PROJECT" -f deploy/docker-compose.prod.yml ${OVERRIDE:+-f "$OVERRIDE"} \
    --env-file "$ENV_FILE" "$@"
}

# The backup's copy of the table, as COPY data, retargeted at a temporary table.
# Only the columns this schema actually has are copied back.
BACKUP_COPY=$(dc exec -T db pg_restore --data-only --table="$TABLE" -f - < "$FROM/database.dump" \
  | sed -e "s/^COPY public\.$TABLE /COPY pg_temp.backup_row /")
grep -q "^COPY pg_temp.backup_row " <<<"$BACKUP_COPY" || die "the backup has no data for $TABLE."

LIVE_COLUMNS=$(dc exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAq' <<<"
  select string_agg(column_name, ' ') from information_schema.columns
   where table_schema = 'public' and table_name = '$TABLE'")
SET_LIST=""
for c in $COLUMNS; do
  case " $LIVE_COLUMNS " in *" $c "*) SET_LIST="${SET_LIST:+$SET_LIST, }$c = b.$c";; esac
done

dc exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -X -q -v ON_ERROR_STOP=1' <<SQL || die "repair failed; nothing was changed."
begin;
create temp table backup_row (like public.$TABLE) on commit drop;
$BACKUP_COPY
select count(*) as in_backup from backup_row where id = '$ID' \gset
\if :in_backup
\else
  \echo 'That row is not in the backup.'
  select 1/0;
\endif
set local session_replication_role = replica;
update public.$TABLE t set $SET_LIST from backup_row b where t.id = b.id and t.id = '$ID';
select count(*) as repaired from public.$TABLE where id = '$ID' \gset
\if :repaired
  \echo 'Row $ID in $TABLE now carries the backup''s bytes.'
\else
  \echo 'That row does not exist in the live database; nothing to repair.'
  select 1/0;
\endif
commit;
SQL

echo
echo "${BOLD}Re-checking…${OFF}"
exec ./scripts/restore.sh --project "$PROJECT" --env-file "$ENV_FILE" ${OVERRIDE:+--compose-override "$OVERRIDE"} --from "$FROM" --verify-only
