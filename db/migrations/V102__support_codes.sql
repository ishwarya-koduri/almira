-- =============================================================================
-- V102 · A support code: help without showing anyone the family's record.
-- Refs: docs/28-plans-and-support.md §4, docs/05 §3, V101 (the ops schema)
--
-- Almost everything a family writes here is private, some of it sealed so that
-- not even the server can read it. Support cannot be allowed to look, and
-- should not have to. So a person who needs help makes a code, and the code
-- carries only diagnostics: the app's version and platform, the screen they
-- were on, the last error codes they saw, and a few on/off switches. Never an
-- amount, a name, a title, a document, or who they are.
--
--   · The person makes it, sees exactly what it holds before it exists, and can
--     take it back at any time. It dies on its own after 24 hours.
--   · Only a hash of the code is stored, so the table does not hold live codes.
--   · There is no operator role in the application. Support reads a code as the
--     schema owner, through ops.lookup_support_code, which refuses an expired or
--     revoked code, needs a name and a reason, writes the audit entry, and
--     counts the look on the row — which the person can see.
--   · The runtime role can only ever touch its own caller's codes, and cannot
--     delete one: a taken-back code stays, revoked, as the record that it was.
-- =============================================================================

create table support_codes (
  id               uuid primary key default gen_random_uuid(),
  user_id          uuid not null references users(id) on delete cascade,
  -- sha256 of the normalised code (upper case, no separators), hex.
  code_hash        text not null unique check (code_hash ~ '^[0-9a-f]{64}$'),
  -- What the code shares. Validated by the service against a closed list of
  -- keys and shapes; bounded here so a bug there cannot store a document.
  diagnostics      jsonb not null check (pg_column_size(diagnostics) <= 4096),
  created_at       timestamptz not null default now(),
  expires_at       timestamptz not null,
  revoked_at       timestamptz,
  lookups          int not null default 0,
  last_looked_up_at timestamptz,
  check (expires_at > created_at and expires_at <= created_at + interval '24 hours')
);
create index on support_codes (user_id, created_at desc);

comment on table support_codes is
  'Diagnostics a person chose to share with support, behind a 24-hour code. '
  'No amounts, names, titles or documents. Looked up only through ops.lookup_support_code (V102).';

alter table support_codes enable row level security;

-- Your own codes, and nobody else's. A guest session borrows the sharer's
-- identity (V21), so it is shut out explicitly.
create policy support_codes_read on support_codes for select
  using (user_id = app.current_user_id() and app.guest_share_id() is null);

create policy support_codes_insert on support_codes for insert
  with check (user_id = app.current_user_id() and app.guest_share_id() is null);

-- Revoking is the only change a person makes. The columns that record a
-- lookup are the operator's; the grant below leaves the app no way to write them.
create policy support_codes_revoke on support_codes for update
  using (user_id = app.current_user_id() and app.guest_share_id() is null)
  with check (user_id = app.current_user_id() and app.guest_share_id() is null);

-- No delete policy.

-- And whatever the grants become (R__grants re-grants every column when it
-- re-runs): a code's contents, owner and lifetime never change, a revocation
-- is never undone, and only a caller with no signed-in identity — the operator
-- function, run as the owner — moves the lookup count.
create or replace function app.support_codes_guard() returns trigger
  language plpgsql as $$
begin
  if new.user_id is distinct from old.user_id or new.code_hash is distinct from old.code_hash
     or new.diagnostics is distinct from old.diagnostics or new.created_at is distinct from old.created_at
     or new.expires_at is distinct from old.expires_at then
    raise exception 'a support code cannot be changed, only taken back';
  end if;
  if old.revoked_at is not null and new.revoked_at is distinct from old.revoked_at then
    raise exception 'a support code that was taken back stays taken back';
  end if;
  if (new.lookups is distinct from old.lookups or new.last_looked_up_at is distinct from old.last_looked_up_at)
     and app.current_user_id() is not null then
    raise exception 'only support records a lookup';
  end if;
  return new;
end $$;

create trigger support_codes_guard before update on support_codes
  for each row execute function app.support_codes_guard();

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'almira_app') then
    execute 'revoke all on support_codes from almira_app';
    execute 'grant select, insert on support_codes to almira_app';
    execute 'grant update (revoked_at) on support_codes to almira_app';
  end if;
end $$;

-- -----------------------------------------------------------------------------
-- The support lookup. Owner only; see V101 for the ops schema.
-- -----------------------------------------------------------------------------
create or replace function ops.lookup_support_code(p_code text, p_operator text, p_reason text)
  returns table (diagnostics jsonb, created_at timestamptz, expires_at timestamptz)
  language plpgsql
  set search_path = public, app, pg_temp as $$
declare
  v_hash text;
  v_row support_codes%rowtype;
begin
  if p_operator is null or length(trim(p_operator)) = 0 then
    raise exception 'say who is looking (p_operator)';
  end if;
  if p_reason is null or length(trim(p_reason)) < 5 then
    raise exception 'say why, in a few words (p_reason) — it goes in the audit log';
  end if;

  v_hash := encode(sha256(convert_to(upper(regexp_replace(coalesce(p_code, ''), '[^A-Za-z0-9]', '', 'g')), 'UTF8')), 'hex');

  select * into v_row from support_codes s where s.code_hash = v_hash;

  -- Every attempt is recorded, including one that finds nothing: guessing is
  -- the thing to notice.
  insert into activity_log (household_id, actor_user_id, action, entity_type, entity_id, diff)
  values (null, null, 'support_code.lookup', 'support_code', v_row.id,
          jsonb_build_object('operator', trim(p_operator), 'reason', left(trim(p_reason), 200),
                             'found', v_row.id is not null
                                      and v_row.revoked_at is null and v_row.expires_at > now()));

  -- A notice and no rows, not an exception: an exception would roll back the
  -- audit entry above along with everything else.
  if v_row.id is null or v_row.revoked_at is not null or v_row.expires_at <= now() then
    raise notice 'no live support code matches — it may have expired or been taken back';
    return;
  end if;

  update support_codes s set lookups = s.lookups + 1, last_looked_up_at = now() where s.id = v_row.id;

  return query select v_row.diagnostics, v_row.created_at, v_row.expires_at;
end $$;

revoke all on function ops.lookup_support_code(text, text, text) from public;
