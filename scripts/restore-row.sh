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
# BEFORE it writes anything it checks the backup's copy of the row: the same
# structural rules as deploy/restore/sweep.sql, and the stored digest where the
# backup has one. A damaged copy is refused and the live row is left as it was
# — it used to be written over first and found damaged by the re-check after,
# which replaced one broken value with another and lost the evidence of which
# bytes were there. It also checks the live row exists before the update.
# If the backup's copy is damaged, the honest next step is an older backup or
# telling the owner the field is lost.
#
# Afterwards it re-runs the sweep and the digest check over the whole database.
# Read what they say.
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
# DEFECTS is a query over backup_row b: why the backup's copy cannot go back,
# or no rows. Structural rules from deploy/restore/sweep.sql; a digest is
# compared only where the backup carries one.
case "$TABLE" in
  sealed_values)
    COLUMNS="ciphertext key_version ciphertext_sha256"
    DEFECTS="select 'ciphertext ' || pg_temp.envelope_defect(b.ciphertext) from backup_row b where b.id = '$ID' and pg_temp.envelope_defect(b.ciphertext) is not null
      union all select 'key_version is ' || b.key_version from backup_row b where b.id = '$ID' and b.key_version < 1"
    DIGESTS="ciphertext:ciphertext_sha256";;
  e2e_keys)
    COLUMNS="kdf_salt iterations wrapped_key verifier key_version wrapped_key_sha256 verifier_sha256"
    DEFECTS="select 'kdf_salt ' || pg_temp.salt_defect(b.kdf_salt) from backup_row b where b.id = '$ID' and pg_temp.salt_defect(b.kdf_salt) is not null
      union all select 'wrapped_key ' || pg_temp.envelope_defect(b.wrapped_key) from backup_row b where b.id = '$ID' and pg_temp.envelope_defect(b.wrapped_key) is not null
      union all select 'verifier ' || pg_temp.envelope_defect(b.verifier) from backup_row b where b.id = '$ID' and pg_temp.envelope_defect(b.verifier) is not null
      union all select 'iterations is ' || b.iterations from backup_row b where b.id = '$ID' and b.iterations < 100000"
    DIGESTS="wrapped_key:wrapped_key_sha256 verifier:verifier_sha256";;
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
for pair in $DIGESTS; do
  value="${pair%%:*}" digest="${pair##*:}"
  case " $LIVE_COLUMNS " in *" $digest "*)
    DEFECTS="$DEFECTS
      union all select '$value does not match its stored digest' from backup_row b
       where b.id = '$ID' and b.$digest is not null and b.$digest <> sha256(convert_to(b.$value, 'UTF8'))";;
  esac
done
# The sweep's own parse functions, so the rule for "defective" is one rule.
SWEEP_FUNCTIONS=$(sed -n '/^create function pg_temp\./,/^end \$\$;$/p' deploy/restore/sweep.sql)
[ -n "$SWEEP_FUNCTIONS" ] || die "could not read the structural rules from deploy/restore/sweep.sql."

dc exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -X -q -v ON_ERROR_STOP=1' <<SQL || die "repair failed; nothing was changed."
begin;
$SWEEP_FUNCTIONS
create temp table backup_row (like public.$TABLE) on commit drop;
$BACKUP_COPY
-- Every check before the update: the row is in the backup, it is in the live
-- database, and the backup's copy is itself intact.
select count(*) as in_backup from backup_row where id = '$ID' \gset
\if :in_backup
\else
  \echo 'That row is not in the backup.'
  select 1/0;
\endif
select count(*) as in_live from public.$TABLE where id = '$ID' \gset
\if :in_live
\else
  \echo 'That row does not exist in the live database; nothing to repair.'
  select 1/0;
\endif
select count(*) > 0 as backup_damaged, coalesce(string_agg(d, '; '), '') as backup_defect_list from ($DEFECTS) as defects(d) \gset
\if :backup_damaged
  \echo 'The backup''s copy of that row is damaged too:' :backup_defect_list
  \echo 'The live row has not been touched. Try an older backup, or tell the owner the field is lost.'
  select 1/0;
\endif
set local session_replication_role = replica;
update public.$TABLE t set $SET_LIST from backup_row b where t.id = b.id and t.id = '$ID';
\echo 'Row $ID in $TABLE now carries the backup''s bytes.'
commit;
SQL

echo
echo "${BOLD}Re-checking…${OFF}"
exec ./scripts/restore.sh --project "$PROJECT" --env-file "$ENV_FILE" ${OVERRIDE:+--compose-override "$OVERRIDE"} --from "$FROM" --verify-only
