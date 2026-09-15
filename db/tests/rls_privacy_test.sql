-- =============================================================================
-- Row-Level Security assertion suite.
--
-- Runs as `almira_app` (the non-owner runtime role), so it exercises the real
-- security boundary. Every assertion raises on failure, so the script exits
-- non-zero the moment a leak is possible.
--
--   psql -U almira_app -d almira -v ON_ERROR_STOP=1 -f db/tests/rls_privacy_test.sql
--
-- Scenario (docs/05 §3):
--   Ishwarya  owner   -- has a Private FD she tells nobody about
--   Ravi      ADMIN   -- deliberately the most privileged role, to prove that
--                        role grants capability, never sight
--   Aarav     managed -- a child, no login of his own
--   Outsider          -- a real user in a different household
-- =============================================================================

\set ON_ERROR_STOP on
begin;

create temporary table t (k text primary key, v uuid) on commit drop;

-- ---------------------------------------------------------------- fixtures --
insert into users (phone, full_name) values ('+919000000001', 'Ishwarya') returning id \gset ish_
insert into users (phone, full_name) values ('+919000000002', 'Ravi')     returning id \gset ravi_
insert into users (phone, full_name) values ('+919000000009', 'Outsider') returning id \gset out_

select set_config('app.user_id', :'ish_id', false);
select household_id, member_id from app.bootstrap_household('Koduri', 'private', 'Ishwarya') \gset hh_

insert into members (household_id, user_id, display_name, relationship)
  values (:'hh_household_id', :'ravi_id', 'Ravi', 'spouse') returning id \gset ravim_
insert into members (household_id, display_name, relationship, date_of_birth)
  values (:'hh_household_id', 'Aarav', 'child', date '2015-04-02') returning id \gset aaravm_

-- Ravi is an ADMIN. If any read below leaks to him, the model is broken.
insert into household_memberships (household_id, user_id, role, status)
  values (:'hh_household_id', :'ravi_id', 'admin', 'active');

-- Outsider gets their own household, to prove cross-tenant isolation too.
select set_config('app.user_id', :'out_id', false);
select household_id from app.bootstrap_household('Strangers', 'household', 'Outsider') \gset oh_

select set_config('app.user_id', :'ish_id', false);

-- Helper: create an investment with a single 100% owner, in one transaction so
-- the deferred share-sum constraint sees a complete ownership set.
-- NOTE the client-generated id. `INSERT ... RETURNING` cannot be used here:
-- RETURNING is subject to the SELECT policy, and a brand-new Private record has
-- no ownership rows yet, so by the rules it is readable by nobody -- not even
-- its author, for those few milliseconds. Generating the UUID up front sidesteps
-- that entirely, and is what the service layer does too (it also gives offline
-- capture a stable id and a natural idempotency key -- docs/05 §10).
create or replace function pg_temp.mk(
    p_hh uuid, p_type text, p_title text, p_amount numeric,
    p_visibility text, p_owner uuid) returns uuid
  language plpgsql as $$
declare v_id uuid := gen_random_uuid();
begin
  insert into investments (id, household_id, type_id, title, invested_amount, visibility, created_by)
    select v_id, p_hh, it.id, p_title, p_amount, p_visibility, app.current_user_id()
      from investment_types it where it.code = p_type and it.household_id is null;
  insert into investment_ownerships (investment_id, member_id, share_pct)
    values (v_id, p_owner, 100);
  return v_id;
end $$;

select pg_temp.mk(:'hh_household_id', 'fd',            'SBI FD (secret)',   500000, 'private',   :'hh_member_id') as id \gset i_private_
select pg_temp.mk(:'hh_household_id', 'gold_physical', 'Family gold',       100000, 'household', :'hh_member_id') as id \gset i_shared_
select pg_temp.mk(:'hh_household_id', 'fd',            'Scoped FD',         200000, 'scoped',    :'hh_member_id') as id \gset i_scoped_

select set_config('app.user_id', :'ravi_id', false);
select pg_temp.mk(:'hh_household_id', 'mf_sip', 'Ravi ELSS SIP', 300000, 'private', :'ravim_id') as id \gset i_ravi_

-- A jointly owned flat that Ishwarya marks Private. Ravi is a co-owner.
select set_config('app.user_id', :'ish_id', false);
select gen_random_uuid() as id \gset i_joint_
insert into investments (id, household_id, type_id, title, invested_amount, visibility, created_by)
  select :'i_joint_id', :'hh_household_id', it.id, 'Flat, Kakinada', 4000000, 'private', :'ish_id'
    from investment_types it where it.code = 'property' and it.household_id is null;
insert into investment_ownerships (investment_id, member_id, share_pct) values
  (:'i_joint_id', :'hh_member_id', 50),
  (:'i_joint_id', :'ravim_id',     50);

-- Scope the scoped FD to Ravi only.
insert into record_visibility_grants (household_id, record_type, record_id, member_id, created_by)
  values (:'hh_household_id', 'investment', :'i_scoped_id', :'ravim_id', :'ish_id');

insert into t values
  ('hh', :'hh_household_id'), ('ish', :'ish_id'), ('ravi', :'ravi_id'), ('out', :'out_id'),
  ('i_private', :'i_private_id'), ('i_shared', :'i_shared_id'), ('i_scoped', :'i_scoped_id'),
  ('i_ravi', :'i_ravi_id'), ('i_joint', :'i_joint_id'),
  ('m_ish', :'hh_member_id'), ('m_ravi', :'ravim_id'), ('m_aarav', :'aaravm_id');

-- ----------------------------------------------------------------- asserts --
create or replace function pg_temp.assert(p_ok boolean, p_what text) returns void
  language plpgsql as $$
begin
  if p_ok is not true then
    raise exception 'PRIVACY ASSERTION FAILED: %', p_what using errcode = 'raise_exception';
  end if;
  raise notice '  ok  %', p_what;
end $$;

create or replace function pg_temp.as_user(p_key text) returns void
  language plpgsql as $$
declare v uuid;
begin
  select t.v into v from t where t.k = p_key;
  perform set_config('app.user_id', v::text, false);
end $$;

create or replace function pg_temp.sees(p_key text) returns boolean
  language plpgsql as $$
declare v uuid;
begin
  select t.v into v from t where t.k = p_key;
  return exists (select 1 from investments where id = v);
end $$;

do $$ begin raise notice '--- Ishwarya (owner) ---'; end $$;
select pg_temp.as_user('ish');
select pg_temp.assert(     pg_temp.sees('i_private'), 'owner sees her own private FD');
select pg_temp.assert(     pg_temp.sees('i_shared'),  'owner sees the household-shared gold');
select pg_temp.assert(     pg_temp.sees('i_scoped'),  'owner sees the record she scoped');
select pg_temp.assert(     pg_temp.sees('i_joint'),   'owner sees the joint flat');
select pg_temp.assert(not  pg_temp.sees('i_ravi'),    'owner CANNOT see Ravi''s private SIP');
select pg_temp.assert((select count(*) = 4 from investments), 'owner sees exactly 4 of 5 records');

do $$ begin raise notice '--- Ravi (ADMIN role) ---'; end $$;
select pg_temp.as_user('ravi');
select pg_temp.assert(not  pg_temp.sees('i_private'),
       'ADMIN CANNOT see another member''s private FD -- role never grants sight');
select pg_temp.assert(     pg_temp.sees('i_shared'),  'admin sees household-shared gold');
select pg_temp.assert(     pg_temp.sees('i_scoped'),  'admin sees the record scoped to him');
select pg_temp.assert(     pg_temp.sees('i_ravi'),    'admin sees his own private SIP');
select pg_temp.assert(     pg_temp.sees('i_joint'),
       'co-owner sees the joint flat DESPITE it being marked private');

do $$ begin raise notice '--- totals must not leak ---'; end $$;
-- HOUSEHOLD lens = every owner row of every record the viewer may see.
-- Ravi:      gold 100,000 + scoped 200,000 + his SIP 300,000 + flat 4,000,000
--            (both halves, because he can see the whole record) = 4,600,000.
-- Ishwarya:  the same, minus the scoped one he keeps, plus her private FD:
--            500,000 + 100,000 + 200,000 + 4,000,000               = 4,800,000.
-- The 500,000 gap IS the privacy model. It must never appear in Ravi's number.
select pg_temp.assert(
  (select coalesce(sum(attributed_value),0) from investment_owner_value
     where household_id = (select v from t where k='hh')) = 4600000,
  'a private record contributes zero to another member''s household total');

-- MEMBER lens = only what is attributed to that one person by their share.
-- Ravi: his SIP 300,000 + his half of the flat 2,000,000 = 2,300,000.
-- This is also the joint-holding check: the flat counts 4,000,000 once at
-- household level and 2,000,000 to each owner -- never 4,000,000 twice.
select pg_temp.assert(
  (select coalesce(sum(attributed_value),0) from investment_owner_value
     where member_id = (select v from t where k='m_ravi')) = 2300000,
  'joint holdings split by share and are never double-counted');

select pg_temp.as_user('ish');
select pg_temp.assert(
  (select coalesce(sum(attributed_value),0) from investment_owner_value
     where household_id = (select v from t where k='hh')) = 4800000,
  'the owner still sees her own true total, private records included');

do $$ begin raise notice '--- child rows must not leak either ---'; end $$;
select pg_temp.as_user('ravi');
select pg_temp.assert(
  not exists (select 1 from investment_ownerships
              where investment_id = (select v from t where k='i_private')),
  'ownership rows of an invisible record are invisible (no side-channel)');
select pg_temp.assert(
  not exists (select 1 from valuations
              where investment_id = (select v from t where k='i_private')),
  'valuations of an invisible record are invisible');
select pg_temp.assert(
  not exists (select 1 from investment_value
              where investment_id = (select v from t where k='i_private')),
  'the value VIEW is not a side-channel (security_invoker works)');

do $$ begin raise notice '--- writes obey the same predicate ---'; end $$;
do $$
declare n int;
begin
  update investments set title = 'hijacked'
    where id = (select v from t where k='i_private');
  get diagnostics n = row_count;
  perform pg_temp.assert(n = 0, 'an admin cannot UPDATE a record they cannot read');

  delete from investments where id = (select v from t where k='i_private');
  get diagnostics n = row_count;
  perform pg_temp.assert(n = 0, 'an admin cannot DELETE a record they cannot read');
end $$;

do $$ begin raise notice '--- a published-price label is the feed''s alone (V66) ---'; end $$;
select pg_temp.as_user('ish');
do $$
declare blocked boolean := false;
begin
  -- The owner of the holding, on her own holding: allowed to value it, never
  -- allowed to say the value came from AMFI or an exchange.
  begin
    insert into valuations (investment_id, as_of_date, value, source, price_source, unit_price, instrument)
      values ((select v from t where k='i_private'), date '2026-09-11', 1, 'price_feed', 'amfi', 1, '122639');
  exception when insufficient_privilege then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'the application role cannot write a price-fed valuation, even as the owner');
end $$;
select pg_temp.as_user('ravi');

do $$ begin raise notice '--- privilege escalation must be impossible ---'; end $$;
do $$
declare n int; blocked boolean := false;
begin
  -- Ravi is an admin. If he could grant HIMSELF visibility on Ishwarya's
  -- private FD, the entire privacy model would be one INSERT away from useless.
  begin
    insert into record_visibility_grants (household_id, record_type, record_id, member_id)
      values ((select v from t where k='hh'), 'investment',
              (select v from t where k='i_private'), (select v from t where k='m_ravi'));
  exception when insufficient_privilege or others then
    blocked := true;
  end;
  perform pg_temp.assert(blocked,
    'an admin cannot grant THEMSELVES visibility into a private record');

  -- Nor may he attach himself as an owner to acquire the same access.
  begin
    insert into investment_ownerships (investment_id, member_id, share_pct)
      values ((select v from t where k='i_private'), (select v from t where k='m_ravi'), 50);
  exception when others then
    blocked := true;
  end;
  perform pg_temp.assert(blocked,
    'an admin cannot attach themselves as owner of a private record');

  perform pg_temp.assert(not pg_temp.sees('i_private'),
    'after both attempts, the private FD is still invisible to the admin');
end $$;

do $$ begin raise notice '--- only a HOLDER may share a record (V8) ---'; end $$;
do $$
declare blocked boolean;
begin
  -- Ravi can READ the household gold, and he is an admin. Neither fact makes it
  -- his to share. Sharing what is not yours is not a capability anyone has.
  blocked := false;
  begin
    insert into record_visibility_grants (household_id, record_type, record_id, member_id)
      values ((select v from t where k='hh'), 'investment',
              (select v from t where k='i_shared'), (select v from t where k='m_aarav'));
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked,
    'an admin cannot share a household record they do not hold');

  -- The joint flat, though, IS his -- he is a co-owner, so he may share it.
  perform pg_temp.as_user('ravi');
  insert into record_visibility_grants (household_id, record_type, record_id, member_id)
    values ((select v from t where k='hh'), 'investment',
            (select v from t where k='i_joint'), (select v from t where k='m_aarav'));
  perform pg_temp.assert(true, 'a co-owner CAN share a record they hold');

  -- And a type nobody has written a holder rule for fails loudly rather than
  -- defaulting open -- the point of routing every type through one function.
  -- 'template' is deliberately not a grantable type: a template is private to
  -- its maker or shared with the household, with nothing in between. Should it
  -- ever gain scoped sharing, this assertion is meant to fail and be pointed at
  -- some other unhandled name.
  blocked := false;
  begin
    perform app.can_grant_visibility('template', gen_random_uuid());
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked,
    'a grantable type with no holder rule raises instead of defaulting open');
end $$;

do $$ begin raise notice '--- revoking a scoped grant takes effect at once ---'; end $$;
select pg_temp.as_user('ish');
select pg_temp.as_user('ish');
delete from record_visibility_grants
  where record_id = (select v from t where k='i_scoped');
select pg_temp.as_user('ravi');
select pg_temp.assert(not pg_temp.sees('i_scoped'),
       'a revoked scoped grant ends access immediately, mid-session');

do $$ begin raise notice '--- cross-household isolation ---'; end $$;
select pg_temp.as_user('out');
select pg_temp.assert((select count(*) = 0 from investments),
       'a user in another household sees nothing at all');
select pg_temp.assert((select count(*) = 0 from members
                       where household_id = (select v from t where k='hh')),
       'a user in another household cannot even enumerate the roster');

do $$ begin raise notice '--- an unauthenticated connection sees nothing ---'; end $$;
select set_config('app.user_id', '', false);
select pg_temp.assert((select count(*) = 0 from investments),
       'no app.user_id set -> every predicate denies');
select pg_temp.assert((select count(*) = 0 from members),
       'no app.user_id set -> roster denied');

do $$ begin raise notice '--- share sums are a database invariant ---'; end $$;
select pg_temp.as_user('ish');
do $$
declare v_ok boolean := false;
begin
  begin
    -- The gold is already 100% Ishwarya's. Adding Ravi at 40% makes it 140%.
    insert into investment_ownerships (investment_id, member_id, share_pct)
      values ((select v from t where k='i_shared'), (select v from t where k='m_ravi'), 40);
    -- The share-sum trigger is DEFERRED, so it has not fired yet -- deferred
    -- triggers queue until COMMIT. Forcing them IMMEDIATE runs the check right
    -- here, which is what lets a single test transaction assert on it.
    set constraints all immediate;
  exception when others then
    v_ok := true;
  end;
  perform pg_temp.assert(v_ok, 'ownership shares totalling 140% are rejected');
end $$;

-- ...and the same constraint accepts a legitimate 60/40 re-split.
do $$
declare v_total numeric;
begin
  update investment_ownerships set share_pct = 60
    where investment_id = (select v from t where k='i_shared');
  insert into investment_ownerships (investment_id, member_id, share_pct)
    values ((select v from t where k='i_shared'), (select v from t where k='m_ravi'), 40);
  set constraints all immediate;
  select sum(share_pct) into v_total from investment_ownerships
    where investment_id = (select v from t where k='i_shared');
  perform pg_temp.assert(v_total = 100, 'a legitimate 60/40 re-split is accepted');
end $$;

-- ---------------------------------------------------------------- debts ----
do $$ begin raise notice '--- debts are exactly as private as assets ---'; end $$;

create or replace function pg_temp.mk_debt(
    p_hh uuid, p_kind text, p_title text, p_amount numeric,
    p_visibility text, p_holder uuid) returns uuid
  language plpgsql as $$
declare v_id uuid := gen_random_uuid();
begin
  insert into liabilities (id, household_id, kind, title, outstanding, visibility, created_by)
    values (v_id, p_hh, p_kind, p_title, p_amount, p_visibility, app.current_user_id());
  insert into liability_holders (liability_id, member_id, responsibility_pct)
    values (v_id, p_holder, 100);
  return v_id;
end $$;

select pg_temp.as_user('ish');
select pg_temp.mk_debt((select v from t where k='hh'), 'personal', 'Her personal loan',
                       300000, 'private', (select v from t where k='m_ish')) as id \gset d_hers_
select pg_temp.mk_debt((select v from t where k='hh'), 'car', 'Household car loan',
                       600000, 'household', (select v from t where k='m_ish')) as id \gset d_shared_

select pg_temp.as_user('ravi');
select pg_temp.mk_debt((select v from t where k='hh'), 'credit_card', 'His card',
                       50000, 'private', (select v from t where k='m_ravi')) as id \gset d_his_

insert into t values ('d_hers', :'d_hers_id'), ('d_shared', :'d_shared_id'), ('d_his', :'d_his_id');

create or replace function pg_temp.sees_debt(p_key text) returns boolean
  language plpgsql as $$
declare v uuid;
begin
  select t.v into v from t where t.k = p_key;
  return exists (select 1 from liabilities where id = v);
end $$;

select pg_temp.assert(not pg_temp.sees_debt('d_hers'),
       'an admin CANNOT see another member''s private debt');
select pg_temp.assert(pg_temp.sees_debt('d_shared'), 'the admin sees the shared car loan');
select pg_temp.assert(pg_temp.sees_debt('d_his'),    'the admin sees his own private card');

-- The subtractive leak: a debt nobody can see must not shrink anyone else's
-- net worth. Ravi's visible debt is the shared 600,000 plus his own 50,000.
select pg_temp.assert(
  (select coalesce(sum(attributed_outstanding),0) from liability_holder_value
     where household_id = (select v from t where k='hh')) = 650000,
  'a private debt contributes zero to another member''s total (no subtractive leak)');

select pg_temp.as_user('ish');
-- Hers: her own 300,000 plus the shared 600,000. His 50,000 is invisible to her.
select pg_temp.assert(
  (select coalesce(sum(attributed_outstanding),0) from liability_holder_value
     where household_id = (select v from t where k='hh')) = 900000,
  'the owner still sees her own true debt total');

select pg_temp.assert(
  not exists (select 1 from liability_holders
              where liability_id = (select v from t where k='d_his')),
  'holder rows of an invisible debt are invisible too');

do $$ begin raise notice '--- an encumbrance must not betray a hidden debt ---'; end $$;
insert into asset_liability_links (liability_id, investment_id)
  values ((select v from t where k='d_hers'), (select v from t where k='i_shared'));

select pg_temp.assert(
  (select count(*) from asset_liability_links
    where investment_id = (select v from t where k='i_shared')) = 1,
  'the owner sees the loan secured against her shared gold');

select pg_temp.as_user('ravi');
select pg_temp.assert(
  (select count(*) from asset_liability_links
    where investment_id = (select v from t where k='i_shared')) = 0,
  'the admin sees the gold but not that a private loan is secured against it');

do $$ begin raise notice '--- responsibility sums are a database invariant ---'; end $$;
select pg_temp.as_user('ish');
do $$
declare v_ok boolean := false;
begin
  begin
    insert into liability_holders (liability_id, member_id, responsibility_pct)
      values ((select v from t where k='d_shared'), (select v from t where k='m_ravi'), 40);
    set constraints all immediate;
  exception when others then v_ok := true;
  end;
  perform pg_temp.assert(v_ok, 'responsibility totalling 140% is rejected');
end $$;

-- ------------------------------------------------------------ templates ----
do $$ begin raise notice '--- a saved form is as private as what it was saved from ---'; end $$;

select pg_temp.as_user('ish');
insert into investment_templates (id, household_id, name, type_id, title, visibility,
                                  source_visibility, created_by)
  values (gen_random_uuid(), (select v from t where k='hh'), 'Her FD preset',
          (select id from investment_types where code = 'fd' limit 1),
          'ICICI FD', 'private', null, app.current_user_id())
  returning id as id \gset tpl_hers_
insert into investment_templates (id, household_id, name, type_id, title, visibility,
                                  source_visibility, created_by)
  values (gen_random_uuid(), (select v from t where k='hh'), 'Household FD preset',
          (select id from investment_types where code = 'fd' limit 1),
          'Shared FD', 'household', null, app.current_user_id())
  returning id as id \gset tpl_shared_
insert into t values ('tpl_hers', :'tpl_hers_id'), ('tpl_shared', :'tpl_shared_id');

select pg_temp.as_user('ravi');
select pg_temp.assert(
  not exists (select 1 from investment_templates where id = (select v from t where k='tpl_hers')),
  'an admin cannot see another member''s private template');
select pg_temp.assert(
  exists (select 1 from investment_templates where id = (select v from t where k='tpl_shared')),
  'a shared template is visible to the household');

-- Shared means usable, not editable. Otherwise a preset someone relies on could
-- be rewritten under them by anyone who can read it.
do $$
declare n int;
begin
  update investment_templates set name = 'Renamed by someone else'
    where id = (select v from t where k='tpl_shared');
  get diagnostics n = row_count;
  perform pg_temp.assert(n = 0, 'a shared template stays its maker''s to change');
end $$;

-- A template made from a private record carries that record's details, so it
-- can never be shared more widely than the record it came from.
select pg_temp.as_user('ish');
do $$
declare v_ok boolean := false;
begin
  begin
    insert into investment_templates (household_id, name, type_id, visibility,
                                      source_visibility, created_by)
      values ((select v from t where k='hh'), 'Leaky preset',
              (select id from investment_types where code = 'fd' limit 1),
              'household', 'private', app.current_user_id());
  exception when others then v_ok := true;
  end;
  perform pg_temp.assert(v_ok,
    'a template saved from a private record cannot be shared with the household');
end $$;

-- --------------------------------------------------------------- guests ----
do $$ begin raise notice '--- a guest link reaches its slice and nothing else ---'; end $$;

select pg_temp.as_user('ish');
select gen_random_uuid() as id \gset share_
insert into guest_shares (id, household_id, label, scope, token_hash, expires_at, created_by)
  values (:'share_id', (select v from t where k='hh'), 'Slice for the CA', 'records',
          'not-a-real-hash', now() + interval '7 days', app.current_user_id());
insert into t values ('share', :'share_id');

-- The link names exactly one holding: the shared gold.
insert into guest_share_items (share_id, record_type, record_id)
  values (:'share_id', 'investment', (select v from t where k='i_shared'));

-- Becoming the guest: same identity, clamped.
select set_config('app.guest_share_id', :'share_id', false);

select pg_temp.assert(
  (select count(*) from investments) = 1,
  'inside a guest session only the linked record is visible');
select pg_temp.assert(
  pg_temp.sees('i_shared') and not pg_temp.sees('i_joint'),
  'the guest sees the linked holding and not the sharer''s others');
select pg_temp.assert(
  (select count(*) from liabilities) = 0,
  'a link to a holding reveals no debts at all');

-- And it is not a way to write, either.
do $$
declare blocked boolean := false;
begin
  begin
    update investments set title = 'Changed by a guest'
      where id = (select v from t where k='i_shared');
    if not found then blocked := true; end if;
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'a guest cannot change what it can see');
end $$;

select set_config('app.guest_share_id', '', false);
select pg_temp.assert(
  (select count(*) from investments) > 1,
  'leaving the guest scope restores the sharer''s own view');

-- ------------------------------------------------------------ emergency ----
do $$ begin raise notice '--- emergency access reveals only continuity records ---'; end $$;

select pg_temp.as_user('ish');
-- One private holding is marked for the family; the other deliberately is not.
update investments set is_in_continuity = true
  where id = (select v from t where k='i_private');
-- The id is chosen first rather than returned, for the same reason the API
-- takes a client-supplied one: a private record has no ownership rows for the
-- instant between these two statements, so RETURNING would be refused by the
-- read policy on the row that was just written.
select gen_random_uuid() as id \gset i_excluded_
insert into investments (id, household_id, type_id, title, invested_amount, visibility,
                         is_in_continuity, created_by)
  values (:'i_excluded_id', (select v from t where k='hh'),
          (select id from investment_types where code = 'gold_physical' limit 1),
          'Not for the family', 250000, 'private', false, app.current_user_id());
insert into investment_ownerships (investment_id, member_id, share_pct)
  values (:'i_excluded_id', (select v from t where k='m_ish'), 100);
insert into t values ('i_excluded', :'i_excluded_id');

insert into emergency_contacts (household_id, member_id, trusted_member_id, wait_days, created_by)
  values ((select v from t where k='hh'), (select v from t where k='m_ish'),
          (select v from t where k='m_ravi'), 14, app.current_user_id());

select pg_temp.as_user('ravi');
select pg_temp.assert(not pg_temp.sees('i_private'),
  'before any request, a private record stays private');

select gen_random_uuid() as id \gset request_
insert into emergency_requests (id, household_id, subject_member_id, requested_by, unlock_at,
                                access_expires_at)
  values (:'request_id', (select v from t where k='hh'), (select v from t where k='m_ish'),
          app.current_user_id(), now() + interval '14 days', now() + interval '44 days');
insert into t values ('request', :'request_id');

select pg_temp.assert(not pg_temp.sees('i_private'),
  'while the request is waiting, nothing has opened');

-- The wait elapses. Ageing the request rather than only the unlock, because the
-- database will not let an unlock precede its own request.
update emergency_requests
   set requested_at = now() - interval '20 days', unlock_at = now() - interval '6 days'
 where id = :'request_id';

select pg_temp.assert(pg_temp.sees('i_private'),
  'once the window opens, a continuity-marked private record is visible');

-- ...and shuts again the moment the person turns out to be reachable. Signing
-- in is the plainest statement that somebody is here, and it stops the clock
-- without them having to understand what a veto is.
insert into user_sessions (user_id, expires_at, last_used_at)
  values ((select v from t where k='ish'), now() + interval '1 day', now());
select pg_temp.assert(not pg_temp.sees('i_private'),
  'using Almira after the request keeps the window shut');
delete from user_sessions where user_id = (select v from t where k='ish');
select pg_temp.assert(pg_temp.sees('i_private'),
  'and the window is open again once that activity is behind the request');
select pg_temp.assert(not pg_temp.sees('i_excluded'),
  'a record left out of continuity stays invisible even under emergency access');

-- ------------------------------------------------------ recovery copies ----
-- V55: a wrapped copy of the content key is readable by its owner and by the
-- person holding an open emergency window on her. Nobody else, and nobody can
-- replace it but her. Who holds a share is readable by members who can see a
-- value she sealed. The values below are shaped like envelopes and mean nothing.
do $$ begin raise notice '--- recovery copies follow the owner and the open window ---'; end $$;

select pg_temp.as_user('ish');
insert into e2e_keys (household_id, user_id, kdf_salt, iterations, wrapped_key, verifier, content_key_id)
  values ((select v from t where k='hh'), (select v from t where k='ish'),
          'AAAAAAAAAAAAAAAAAAAAAA', 600000, repeat('A', 88), repeat('A', 60), 'AAAAAAAAAAAAAAAAAAAAAA');
select gen_random_uuid() as id \gset wrap_
insert into e2e_recovery_wraps (id, household_id, user_id, kind, kdf_salt, wrapped_key, verifier, content_key_id)
  values (:'wrap_id', (select v from t where k='hh'), (select v from t where k='ish'), 'recovery_shares',
          'AAAAAAAAAAAAAAAAAAAAAA', repeat('A', 88), repeat('A', 60), 'AAAAAAAAAAAAAAAAAAAAAA');
insert into e2e_recovery_slots (wrap_id, household_id, user_id, kind, threshold, share_count, holders)
  values (:'wrap_id', (select v from t where k='hh'), (select v from t where k='ish'), 'recovery_shares',
          2, 3, array['Amma', 'Ravi']);
insert into t values ('wrap', :'wrap_id');

select pg_temp.assert((select count(*) from e2e_recovery_wraps) = 1
                      and (select count(*) from e2e_recovery_slots) = 1,
  'the owner sees her own recovery copy and who holds it');

select pg_temp.as_user('ravi');
select pg_temp.assert((select count(*) from e2e_recovery_wraps
                        where user_id = (select v from t where k='ish')) = 1,
  'the person holding an open window on her can read her recovery copy');

do $$
declare n int;
begin
  update e2e_recovery_wraps set wrapped_key = repeat('B', 88)
    where id = (select v from t where k='wrap');
  get diagnostics n = row_count;
  perform pg_temp.assert(n = 0, 'an open window reads a recovery copy; it cannot swap it for a dud');
  update e2e_recovery_slots set holders = array['Someone else']
    where wrap_id = (select v from t where k='wrap');
  get diagnostics n = row_count;
  perform pg_temp.assert(n = 0, 'nor change who holds the shares');
end $$;

do $$
declare blocked boolean := false;
begin
  begin
    insert into e2e_recovery_wraps (household_id, user_id, kind, kdf_salt, wrapped_key, verifier, content_key_id)
      values ((select v from t where k='hh'), (select v from t where k='ish'), 'recovery_key',
              'AAAAAAAAAAAAAAAAAAAAAA', repeat('A', 88), repeat('A', 60), 'AAAAAAAAAAAAAAAAAAAAAA');
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'nobody can make a recovery copy in someone else''s name');
end $$;

select pg_temp.as_user('out');
select pg_temp.assert((select count(*) from e2e_recovery_wraps) = 0
                      and (select count(*) from e2e_recovery_slots) = 0,
  'someone in another household sees no recovery copy and no holder');
select pg_temp.as_user('ravi');

-- An unlock is a read, and only a read. Someone acting for a family that
-- cannot answer must not be able to change what they find.
do $$
declare n int;
begin
  update investments set title = 'Edited under emergency access'
    where id = (select v from t where k='i_private');
  get diagnostics n = row_count;
  perform pg_temp.assert(n = 0, 'emergency access reveals; it does not permit writing');
end $$;

select pg_temp.as_user('out');
select pg_temp.assert(not pg_temp.sees('i_private'),
  'someone outside the household gains nothing from another person''s unlock');

select pg_temp.as_user('ish');
update emergency_requests set vetoed_at = now() where id = :'request_id';
select pg_temp.as_user('ravi');
select pg_temp.assert(not pg_temp.sees('i_private'),
  'a veto closes the window immediately, mid-session');

select pg_temp.assert((select count(*) from e2e_recovery_wraps
                        where user_id = (select v from t where k='ish')) = 0,
  'a veto closes the recovery copy along with the records');
select pg_temp.assert((select count(*) from e2e_recovery_slots
                        where user_id = (select v from t where k='ish')) = 0,
  'and a member who can see nothing she sealed does not learn who holds her shares');

-- She seals a value on the household's shared gold, which Ravi can see.
select pg_temp.as_user('ish');
insert into sealed_values (household_id, record_type, record_id, field_key, ciphertext, sealed_by)
  values ((select v from t where k='hh'), 'investment', (select v from t where k='i_shared'),
          'original_location', repeat('A', 60), app.current_user_id());
select pg_temp.as_user('ravi');
select pg_temp.assert((select count(*) from e2e_recovery_slots
                        where user_id = (select v from t where k='ish')) = 1,
  'a member who can see something she sealed is told who holds a recovery share');
select pg_temp.assert((select count(*) from e2e_recovery_wraps
                        where user_id = (select v from t where k='ish')) = 0,
  'and is still not given the copy itself');

select set_config('app.guest_share_id', (select v from t where k='share')::text, false);
select pg_temp.as_user('ish');
select pg_temp.assert((select count(*) from e2e_recovery_wraps) = 0
                      and (select count(*) from e2e_recovery_slots) = 0,
  'a guest link reaches no recovery copy and no holder, even the sharer''s own');
select set_config('app.guest_share_id', '', false);

-- ------------------------------------------------------------- closing ----
do $$ begin raise notice '--- closing an account is the account holder''s business alone (V40) ---'; end $$;

select pg_temp.as_user('ish');
insert into account_closures (user_id, purge_after)
  values (app.current_user_id(), now() + interval '30 days');
select pg_temp.assert((select count(*) from account_closures) = 1,
  'a person can ask to close their own account');

do $$
declare refused boolean := false;
begin
  begin
    insert into account_closures (user_id, purge_after)
      values (app.current_user_id(), now() + interval '1 day');
  exception when others then refused := true;
  end;
  perform pg_temp.assert(refused, 'the thirty days cannot be shortened, and one closure waits at a time');
end $$;

do $$
declare refused boolean := false;
begin
  begin
    update account_closures set purge_after = purge_after - interval '29 days';
  exception when others then refused := true;
  end;
  perform pg_temp.assert(refused, 'a waiting closure''s date cannot be moved');
end $$;

do $$
declare refused boolean := false;
begin
  begin
    update account_closures set purged_at = now();
  exception when others then refused := true;
  end;
  perform pg_temp.assert(refused, 'only the sweep, with no user, records an erasure');
end $$;

select pg_temp.as_user('ravi');
select pg_temp.assert((select count(*) from account_closures) = 0,
  'an admin cannot see that someone has asked to close their account');
do $$
declare refused boolean := false;
begin
  begin
    insert into account_closures (user_id, purge_after)
      values ((select v from t where k='ish'), now() + interval '30 days');
  exception when others then refused := true;
  end;
  perform pg_temp.assert(refused, 'nobody can ask to close someone else''s account');
end $$;

select pg_temp.as_user('ish');
update account_closures set cancelled_at = now() where cancelled_at is null;
select pg_temp.assert((select count(*) from account_closures where cancelled_at is not null) = 1,
  'the person can keep their account by saying so');

-- ---------------------------------------------------------- passing away ----
do $$ begin raise notice '--- a memorial makes an account read-only, and only its subject undoes it (V40) ---'; end $$;

select pg_temp.as_user('ravi');
do $$
declare refused boolean := false;
begin
  begin
    insert into member_memorials (household_id, member_id, user_id, marked_by, basis)
      values ((select v from t where k='hh'), (select v from t where k='m_ish'),
              (select v from t where k='ish'), app.current_user_id(), 'trusted_contact');
  exception when others then refused := true;
  end;
  perform pg_temp.assert(refused,
    'a trusted contact whose window has not opened (here: vetoed) cannot mark anyone');
end $$;

select pg_temp.as_user('ish');
do $$
declare refused boolean := false;
begin
  begin
    insert into member_memorials (household_id, member_id, user_id, marked_by, basis)
      values ((select v from t where k='hh'), (select v from t where k='m_ish'),
              app.current_user_id(), app.current_user_id(), 'admin');
  exception when others then refused := true;
  end;
  perform pg_temp.assert(refused, 'nobody can mark themselves as passed away');
end $$;

insert into member_memorials (household_id, member_id, user_id, marked_by, basis)
  values ((select v from t where k='hh'), (select v from t where k='m_ravi'),
          (select v from t where k='ravi'), app.current_user_id(), 'admin');

select pg_temp.as_user('ravi');
select pg_temp.assert(not app.can_write_household((select v from t where k='hh'))
                      and not app.can_administer_household((select v from t where k='hh')),
  'a memorialised account loses every capability in that household, admin included');
select pg_temp.assert(pg_temp.sees('i_ravi') and pg_temp.sees('i_joint'),
  'and keeps sight of what it could see, so the person can sign in and see the label');
do $$
declare n int;
begin
  update investments set title = 'Changed after a memorial' where id = (select v from t where k='i_ravi');
  get diagnostics n = row_count;
  perform pg_temp.assert(n = 0, 'a memorialised account cannot change even its own records');
end $$;
select pg_temp.assert(
  app.record_in_app_message((select v from t where k='hh'), (select v from t where k='ravi'),
                            'reminder.due', 'A reminder', 'rls-test-stopped') is null,
  'messages to a memorialised person are not written');
select pg_temp.assert(
  app.record_in_app_message((select v from t where k='hh'), (select v from t where k='ravi'),
                            'lifecycle.memorial.marked', 'Marked', 'rls-test-told') is not null,
  'except the one that tells them they were marked');

select pg_temp.as_user('out');
select pg_temp.assert((select count(*) from member_memorials) = 0,
  'a memorial is not visible outside the household');

select pg_temp.as_user('ish');
do $$
declare n int;
begin
  update member_memorials set reversed_at = now(), reversed_by = app.current_user_id();
  get diagnostics n = row_count;
  perform pg_temp.assert(n = 0, 'the admin who made a memorial cannot quietly take it back');
end $$;

select pg_temp.as_user('ravi');
update member_memorials set reversed_at = now(), reversed_by = app.current_user_id()
 where reversed_at is null;
select pg_temp.assert(app.can_administer_household((select v from t where k='hh')),
  'the person it named takes it away, and every capability returns');

-- --------------------------------------------------------------- leaving ----
do $$ begin raise notice '--- leaving a household: seven days, and decisions only about what you hold (V41) ---'; end $$;

select pg_temp.as_user('ravi');
do $$
declare refused boolean := false;
begin
  begin
    insert into household_departures (household_id, member_id, user_id, started_by, started_by_admin, effective_at)
      values ((select v from t where k='hh'), (select v from t where k='m_ravi'), app.current_user_id(),
              app.current_user_id(), false, now() + interval '1 day');
  exception when others then refused := true;
  end;
  perform pg_temp.assert(refused, 'a departure cannot take effect sooner than seven days');
end $$;

select gen_random_uuid() as id \gset departure_
insert into household_departures (id, household_id, member_id, user_id, started_by, started_by_admin, effective_at)
  values (:'departure_id', (select v from t where k='hh'), (select v from t where k='m_ravi'),
          app.current_user_id(), app.current_user_id(), false, now() + interval '7 days');
insert into t values ('departure', :'departure_id');

insert into departure_joint_decisions (departure_id, record_type, record_id, decision)
  values (:'departure_id', 'investment', (select v from t where k='i_joint'), 'take_my_share');

do $$
declare refused boolean := false;
begin
  begin
    insert into departure_joint_decisions (departure_id, record_type, record_id, decision)
      values ((select v from t where k='departure'), 'investment', (select v from t where k='i_scoped'), 'stays');
  exception when others then refused := true;
  end;
  perform pg_temp.assert(refused, 'seeing a holding shared with you is not holding it: no decision about it');
end $$;

do $$
declare refused boolean := false;
begin
  begin
    insert into departure_joint_decisions (departure_id, record_type, record_id, decision)
      values ((select v from t where k='departure'), 'investment', (select v from t where k='i_private'), 'stays');
  exception when others then refused := true;
  end;
  perform pg_temp.assert(refused, 'and no decision about a record the leaver cannot see');
end $$;

do $$
declare refused boolean := false;
begin
  begin
    update household_departures set completed_at = now() where id = (select v from t where k='departure');
  exception when others then refused := true;
  end;
  perform pg_temp.assert(refused, 'only the sweep completes a departure');
end $$;

select pg_temp.as_user('ish');
select pg_temp.assert((select count(*) from household_departures) = 1,
  'the household can see that someone is leaving');
select pg_temp.assert((select count(*) from departure_joint_decisions) = 1,
  'a co-holder sees the decision about the record they hold together');
do $$
declare n int;
begin
  update household_departures set cancelled_at = now() where id = (select v from t where k='departure');
  get diagnostics n = row_count;
  perform pg_temp.assert(n = 0, 'an admin cannot cancel a departure someone chose for themselves');
end $$;

select pg_temp.as_user('out');
select pg_temp.assert((select count(*) from household_departures) = 0
                      and (select count(*) from departure_joint_decisions) = 0,
  'nothing about a departure reaches outside the household');

select pg_temp.as_user('ravi');
update household_departures set cancelled_at = now() where id = :'departure_id';

do $$ begin raise notice '--- an admin learns how many records block a removal, never which (V41) ---'; end $$;

-- A private holding recorded by Ishwarya in Aarav's name. By the read rule it is
-- visible to nobody with a login — which is exactly the dead end.
select pg_temp.as_user('ish');
select gen_random_uuid() as id \gset i_aarav_
insert into investments (id, household_id, type_id, title, invested_amount, visibility, created_by)
  select :'i_aarav_id', (select v from t where k='hh'), it.id, 'Aarav''s gold coin', 50000, 'private', :'ish_id'
    from investment_types it where it.code = 'gold_physical' and it.household_id is null;
insert into investment_ownerships (investment_id, member_id, share_pct)
  values (:'i_aarav_id', (select v from t where k='m_aarav'), 100);

select pg_temp.as_user('ravi');
select pg_temp.assert(
  (select hidden_count from app.managed_member_holdings((select v from t where k='m_aarav'))) = 1,
  'the admin is told a record they cannot see stands in the way');
select pg_temp.assert(
  (select array_agg(x) from app.recorders_of_hidden_holdings((select v from t where k='m_aarav')) x)
    = array[(select v from t where k='ish')],
  'and who recorded it, so that person can be asked');
select pg_temp.assert(not exists (select 1 from investments where id = :'i_aarav_id'),
  'without the record itself becoming visible');

select pg_temp.as_user('out');
select pg_temp.assert(
  (select hidden_count from app.managed_member_holdings((select v from t where k='m_aarav'))) = 0
  and not exists (select 1 from app.recorders_of_hidden_holdings((select v from t where k='m_aarav'))),
  'someone outside the household learns nothing from asking');

-- ------------------------------------------------------------ succession ----
do $$ begin raise notice '--- a successor is named by the owner and claims only on the event (V41) ---'; end $$;

select pg_temp.as_user('ravi');
do $$
declare refused boolean := false;
begin
  begin
    insert into household_successors (household_id, named_by, successor_member_id)
      values ((select v from t where k='hh'), app.current_user_id(), (select v from t where k='m_ravi'));
  exception when others then refused := true;
  end;
  perform pg_temp.assert(refused, 'an admin cannot name themselves the successor');
end $$;

select pg_temp.as_user('ish');
insert into household_successors (household_id, named_by, successor_member_id)
  values ((select v from t where k='hh'), app.current_user_id(), (select v from t where k='m_ravi'));

select pg_temp.as_user('ravi');
select pg_temp.assert((select count(*) from household_successors) = 1,
  'the person named can see that they are');
do $$
declare refused boolean := false;
begin
  begin
    perform app.claim_household_succession((select v from t where k='hh'));
  exception when others then refused := true;
  end;
  perform pg_temp.assert(refused, 'a successor cannot claim while the owner is here');
end $$;

-- Marked as passed away by the admin who is also the successor: still not a
-- claim for a week, during which the owner is told and can say it is wrong.
insert into member_memorials (household_id, member_id, user_id, marked_by, basis)
  values ((select v from t where k='hh'), (select v from t where k='m_ish'),
          (select v from t where k='ish'), app.current_user_id(), 'admin');
do $$
declare refused boolean := false;
begin
  begin
    perform app.claim_household_succession((select v from t where k='hh'));
  exception when others then refused := true;
  end;
  perform pg_temp.assert(refused, 'nor in the same week as a memorial that could still be a mistake');
end $$;
select pg_temp.as_user('ish');
update member_memorials set reversed_at = now(), reversed_by = app.current_user_id()
 where reversed_at is null;

select pg_temp.as_user('out');
select pg_temp.assert((select count(*) from household_successors) = 0,
  'a succession plan is not visible outside the household');

-- --------------------------------------------------------- coming of age ----
do $$ begin raise notice '--- a coming-of-age notice is the sweep''s to write (V41) ---'; end $$;

select pg_temp.as_user('ish');
do $$
declare refused boolean := false;
begin
  begin
    insert into coming_of_age_notices (member_id, household_id, turns_adult_on)
      values ((select v from t where k='m_aarav'), (select v from t where k='hh'), current_date);
  exception when others then refused := true;
  end;
  perform pg_temp.assert(refused, 'nobody writes a coming-of-age notice by hand');
end $$;
-- ------------------------------------------------- second factors (V50) --
-- An authenticator secret, a recovery code and a passkey belong to one person.
-- Not to their household's admin, not to a stranger, and not to a transaction
-- that carries no identity at all.
do $$ begin raise notice '--- second factors (V50) ---'; end $$;

select pg_temp.as_user('ish');
insert into user_totp_factors (user_id, secret_enc, kek_id, confirmed_at)
  values (app.current_user_id(), '\x01'::bytea, 'test', now());
insert into user_recovery_codes (user_id, salt, code_hash)
  values (app.current_user_id(), '\x00'::bytea, '\x00'::bytea);
insert into user_passkeys (user_id, credential_id, public_key_cose, name)
  values (app.current_user_id(), '\xaa01'::bytea, '\xbb'::bytea, 'Phone');
select pg_temp.assert((select count(*) from user_totp_factors) = 1
                  and (select count(*) from user_recovery_codes) = 1
                  and (select count(*) from user_passkeys) = 1,
  'a person sees their own authenticator, recovery codes and passkeys');

select pg_temp.as_user('ravi');
select pg_temp.assert((select count(*) from user_totp_factors) = 0,
  'an ADMIN of the same household sees no one else''s authenticator');
select pg_temp.assert((select count(*) from user_recovery_codes) = 0,
  'an ADMIN sees no one else''s recovery codes');
select pg_temp.assert((select count(*) from user_passkeys) = 0,
  'an ADMIN sees no one else''s passkeys');

do $$
declare n int;
begin
  update user_totp_factors set last_used_step = 1;
  get diagnostics n = row_count;
  perform pg_temp.assert(n = 0, 'an ADMIN cannot touch someone else''s authenticator');
  delete from user_passkeys;
  get diagnostics n = row_count;
  perform pg_temp.assert(n = 0, 'an ADMIN cannot remove someone else''s passkey');
  update user_recovery_codes set used_at = null;
  get diagnostics n = row_count;
  perform pg_temp.assert(n = 0, 'an ADMIN cannot revive someone else''s recovery code');
end $$;

do $$
begin
  insert into user_passkeys (user_id, credential_id, public_key_cose, name)
    values ((select v from t where k = 'ish'), '\xcc02'::bytea, '\xdd'::bytea, 'Planted');
  perform pg_temp.assert(false, 'a passkey cannot be planted on someone else''s account');
exception when insufficient_privilege then
  perform pg_temp.assert(true, 'a passkey cannot be planted on someone else''s account');
end $$;

select pg_temp.as_user('out');
select pg_temp.assert((select count(*) from user_totp_factors) + (select count(*) from user_recovery_codes)
                      + (select count(*) from user_passkeys) = 0,
  'a stranger sees no second factor of anyone''s');

select set_config('app.user_id', '', false);
select pg_temp.assert((select count(*) from user_totp_factors) + (select count(*) from user_recovery_codes)
                      + (select count(*) from user_passkeys) = 0,
  'with no identity, no second factor is visible');

-- ------------------------------------------------------------ data rights --
-- V45. Consent, notice acceptances, rights requests and nominations belong to
-- one person each; a child's consent line is seen by whoever sees the child.
do $$ begin raise notice '--- data rights and parental consent (V45) ---'; end $$;

select pg_temp.as_user('ish');
-- A yes to messages names its channels (V125; required for new rows since V142).
insert into consent_events (user_id, purpose, action, notice_version, channels)
  values ((select v from t where k='ish'), 'messages', 'given', app.current_privacy_notice_version(), array['email']);
insert into consent_events (user_id, purpose, action, notice_version)
  values ((select v from t where k='ish'), 'messages', 'withdrawn', app.current_privacy_notice_version());
insert into privacy_notice_acceptances (user_id, notice_version)
  values ((select v from t where k='ish'), app.current_privacy_notice_version());

select pg_temp.assert((select count(*) from consent_events) = 2,
  'a person sees their own consent history');
do $$
declare blocked boolean := false;
begin
  -- Even about oneself: the question takes any user id, so it is asked only
  -- where an email or text is queued (app.enqueue_outbound_message, V125).
  begin
    perform app.messages_consent_given((select v from t where k='ish'), 'email');
  exception when insufficient_privilege then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'the runtime role cannot ask whether anyone has consented to messages (V125)');
end $$;

do $$
declare n int;
begin
  update consent_events set action = 'given';
  get diagnostics n = row_count;
  perform pg_temp.assert(n = 0, 'a consent record cannot be rewritten by the application');
  delete from consent_events;
  get diagnostics n = row_count;
  perform pg_temp.assert(n = 0, 'nor deleted');
end $$;

do $$
declare blocked boolean := false;
begin
  begin
    insert into privacy_notice_versions (version, published_on, summary)
      values ('2099-01-01', date '2099-01-01', 'published by a request');
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'a request cannot publish a privacy notice');
end $$;

insert into data_rights_requests (user_id, kind, details, respond_by)
  values ((select v from t where k='ish'), 'correction', 'My name is spelt wrong in the audit log',
          current_date + 30)
  returning id \gset drr_
insert into t values ('drr', :'drr_id');

insert into data_rights_nominees (user_id, full_name, relationship, contact)
  values ((select v from t where k='ish'), 'Lakshmi Koduri', 'sister', '+919000000077')
  returning id \gset nominee_
insert into t values ('nominee', :'nominee_id');

do $$
declare blocked boolean := false;
begin
  begin
    insert into data_rights_requests (user_id, kind, details, respond_by)
      values ((select v from t where k='ish'), 'grievance', 'No reply', current_date + 120);
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'no request can promise a reply more than 90 days out');

  blocked := false;
  begin
    insert into data_rights_requests (user_id, kind, details, respond_by, status, response)
      values ((select v from t where k='ish'), 'grievance', 'Answer myself', current_date + 5,
              'answered', 'Resolved, says I');
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'a request cannot arrive already answered');

  blocked := false;
  begin
    update data_rights_nominees set full_name = 'Someone else'
     where id = (select v from t where k='nominee');
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'a nominee is revoked, never edited into somebody else');
end $$;

-- Ravi is an admin of the same household. None of it is his.
select pg_temp.as_user('ravi');
select pg_temp.assert((select count(*) from consent_events) = 0,
  'an admin sees nothing of another member''s consent history');
select pg_temp.assert((select count(*) from privacy_notice_acceptances) = 0,
  'nor their notice acceptances');
select pg_temp.assert((select count(*) from data_rights_requests) = 0,
  'nor their rights requests');
select pg_temp.assert((select count(*) from data_rights_nominees) = 0,
  'nor who they nominated');
select pg_temp.assert(not app.withdraw_data_rights_request((select v from t where k='drr')),
  'an admin cannot withdraw another member''s request');

do $$
declare n int; blocked boolean := false;
begin
  update data_rights_nominees set revoked_at = now()
   where id = (select v from t where k='nominee');
  get diagnostics n = row_count;
  perform pg_temp.assert(n = 0, 'an admin cannot revoke another member''s nominee');
  begin
    -- With channels, so it is row-level security that refuses it, not V142's check.
    insert into consent_events (user_id, purpose, action, notice_version, channels)
      values ((select v from t where k='ish'), 'messages', 'given', app.current_privacy_notice_version(), array['email']);
  exception when insufficient_privilege then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'nobody gives consent in someone else''s name');
end $$;

select pg_temp.as_user('ish');
select pg_temp.assert(app.withdraw_data_rights_request((select v from t where k='drr')),
  'the person who asked can withdraw their request');
select pg_temp.assert(not app.withdraw_data_rights_request((select v from t where k='drr')),
  'and only once');
update data_rights_nominees set revoked_at = now() where id = (select v from t where k='nominee');
select pg_temp.assert(
  (select revoked_at is not null from data_rights_nominees where id = (select v from t where k='nominee')),
  'the person who nominated can revoke');

-- A parent's consent for Aarav, a child with no login.
insert into parental_consents (household_id, member_id, given_by, capacity, verification, notice_version)
  values ((select v from t where k='hh'), (select v from t where k='m_aarav'),
          (select v from t where k='ish'), 'parent', 'step_up_code', app.current_privacy_notice_version())
  returning id \gset pc_
insert into t values ('pc', :'pc_id');

do $$
declare blocked boolean := false;
begin
  -- Ravi has a login of his own and is an adult: nobody consents for him.
  begin
    insert into parental_consents (household_id, member_id, given_by, capacity, verification, notice_version)
      values ((select v from t where k='hh'), (select v from t where k='m_ravi'),
              (select v from t where k='ish'), 'parent', 'step_up_code', app.current_privacy_notice_version());
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'parental consent is only for a minor with no login of their own');

  blocked := false;
  begin
    insert into parental_consents (household_id, member_id, given_by, capacity, verification, notice_version)
      values ((select v from t where k='hh'), (select v from t where k='m_aarav'),
              (select v from t where k='ish'), 'lawful_guardian', 'step_up_code', app.current_privacy_notice_version());
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'a child has one live parental consent at a time');
end $$;

select pg_temp.as_user('ravi');
select pg_temp.assert((select count(*) from parental_consents where member_id = (select v from t where k='m_aarav')) = 1,
  'the household sees the consent line on the child''s profile');
select pg_temp.assert(not app.withdraw_parental_consent((select v from t where k='pc')),
  'only the adult who gave consent can withdraw it');

select pg_temp.as_user('out');
select pg_temp.assert((select count(*) from parental_consents) = 0,
  'someone outside the household sees no child''s consent');
do $$
declare blocked boolean := false;
begin
  begin
    insert into parental_consents (household_id, member_id, given_by, capacity, verification, notice_version)
      values ((select v from t where k='hh'), (select v from t where k='m_aarav'),
              (select v from t where k='out'), 'parent', 'step_up_code', app.current_privacy_notice_version());
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'an outsider cannot consent for another household''s child');
end $$;

select pg_temp.as_user('ish');
select pg_temp.assert(app.withdraw_parental_consent((select v from t where k='pc')),
  'the parent who gave consent can withdraw it');
-- ------------------------------------------------- devices and preferences --
do $$ begin raise notice '--- a person''s phones and notification choices are theirs alone (V60) ---'; end $$;

select pg_temp.as_user('ish');
insert into user_devices (user_id, installation_id, platform, token, environment)
  values ((select v from t where k='ish'), 'ish-iphone-0001', 'ios', 'apns0123456789abcdef', 'production');
insert into notification_preferences (user_id, sms_enabled, quiet_from, quiet_until)
  values ((select v from t where k='ish'), false, '22:00', '07:00');
select pg_temp.assert((select count(*) from user_devices) = 1, 'a person sees their own device');
select pg_temp.assert((select count(*) from notification_preferences) = 1, 'a person sees their own preferences');

do $$
declare blocked boolean := false;
begin
  begin
    insert into user_devices (user_id, installation_id, platform, token)
      values ((select v from t where k='ravi'), 'planted-0001', 'android', 'fcm0123456789abcdef');
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'nobody can register a device, and so redirect messages, for someone else');
end $$;

select pg_temp.as_user('ravi');
select pg_temp.assert((select count(*) from user_devices) = 0,
  'an ADMIN of the same household sees none of another member''s devices');
select pg_temp.assert((select count(*) from notification_preferences) = 0,
  'an ADMIN sees none of another member''s notification choices');
do $$
declare n int;
begin
  update notification_preferences set sms_enabled = true;
  get diagnostics n = row_count;
  perform pg_temp.assert(n = 0, 'an ADMIN cannot switch another member''s channels back on');
  delete from user_devices;
  get diagnostics n = row_count;
  perform pg_temp.assert(n = 0, 'an ADMIN cannot remove another member''s device');
end $$;

select pg_temp.as_user('out');
select pg_temp.assert((select count(*) from user_devices) = 0, 'an outsider sees no devices');

select pg_temp.as_user('ish');
select set_config('app.guest_share_id', :'share_id', false);
do $$
declare blocked boolean := false;
begin
  begin
    insert into user_devices (user_id, installation_id, platform, token)
      values ((select v from t where k='ish'), 'guest-planted-0001', 'android', 'fcm0123456789abcdef');
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'a guest link cannot register a device for the person who shared it');
end $$;
select set_config('app.guest_share_id', '', false);

do $$
declare blocked boolean := false;
begin
  begin
    perform app.is_remembrance_day((select v from t where k='hh'), date '2031-04-02');
  exception when insufficient_privilege then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'the runtime role cannot ask which days are birthdays in a household (V107)');
  blocked := false;
  begin
    perform app.notifications_stopped((select v from t where k='out'), null);
  exception when insufficient_privilege then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'nor whether someone is memorialised anywhere');
end $$;
-- ------------------------------------------------ product measurement (V70) --
do $$ begin raise notice '--- measurement counts events, never people (V70) ---'; end $$;
select pg_temp.as_user('ish');
do $$
declare blocked boolean;
begin
  perform pg_temp.assert(
    (select p.prorettype = 'void'::regtype from pg_proc p where p.oid =
       'app.count_product_event(text, smallint, uuid, uuid, uuid[], uuid[], uuid[], uuid[])'::regprocedure),
    'counting says nothing back, so it cannot tell who is a minor or who opted out (V107)');
  perform app.count_product_event('holding_added', 0::smallint, null,
            (select v from t where k='hh'), '{}', array[(select v from t where k='i_private')]);
  perform pg_temp.assert(not exists (select 1 from measurement_daily_counts),
    'the runtime role cannot read the counts it just added to');

  blocked := false;
  begin
    insert into measurement_daily_counts (day, event, count) values (current_date, 'holding_added', 1000);
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'the runtime role cannot write a count directly');

  blocked := false;
  begin
    perform app.count_product_event('net_worth_viewed_by_ish');
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'an event that is not on the list cannot be stored');

  blocked := false;
  begin
    perform app.count_product_event('first_holding_added');
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'a first holding cannot be counted without a holding');

  -- Whether a minor or an opt-out stopped a count is proven over HTTP, reading
  -- the counts as the owner (MeasurementApiTest); here it can only be called.
  insert into measurement_opt_outs (user_id) values ((select v from t where k='ish'));

  perform pg_temp.as_user('ravi');
  perform pg_temp.assert(not exists (select 1 from measurement_opt_outs),
    'one person cannot see another''s opt-out');
  blocked := false;
  begin
    insert into measurement_opt_outs (user_id) values ((select v from t where k='ish'));
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'nor opt someone else out');
end $$;
select pg_temp.as_user('ish');

-- ------------------------------------------------ 31 January 2018 values ----
-- V75. The value an owner enters for section 55(2)(ac) grandfathering is a
-- fact about a holding, and must be exactly as visible as the holding.
do $$ begin raise notice '--- a 31 January 2018 value follows its holding''s visibility ---'; end $$;

select pg_temp.as_user('ish');
insert into investment_fmv_2018 (investment_id, fmv_per_unit)
  values ((select v from t where k='i_private'), 1200);
insert into investment_fmv_2018 (investment_id, fmv_per_unit)
  values ((select v from t where k='i_shared'), 5000);
select pg_temp.assert(
  (select count(*) from investment_fmv_2018) = 2,
  'the owner sees the values she entered');

select pg_temp.as_user('ravi');
select pg_temp.assert(
  not exists (select 1 from investment_fmv_2018
              where investment_id = (select v from t where k='i_private')),
  'ADMIN cannot see the 2018 value on another member''s private holding');
select pg_temp.assert(
  exists (select 1 from investment_fmv_2018
          where investment_id = (select v from t where k='i_shared')),
  'admin sees the 2018 value on a household holding');

do $$
declare blocked boolean := false;
begin
  begin
    insert into investment_fmv_2018 (investment_id, fmv_per_unit)
      values ((select v from t where k='i_private'), 1);
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'admin cannot put a 2018 value on a private holding');
end $$;

do $$
declare n int;
begin
  update investment_fmv_2018 set fmv_per_unit = 1
    where investment_id = (select v from t where k='i_private');
  get diagnostics n = row_count;
  perform pg_temp.assert(n = 0, 'nor change one that is there');
end $$;

select pg_temp.as_user('out');
select pg_temp.assert(
  (select count(*) from investment_fmv_2018) = 0,
  'outside the household there are no 2018 values at all');

-- The guest link from above names only the shared gold.
select pg_temp.as_user('ish');
select set_config('app.guest_share_id', (select v::text from t where k='share'), false);
select pg_temp.assert(
  (select count(*) from investment_fmv_2018) = 1,
  'a guest sees the 2018 value on the linked holding and no other');

do $$
declare blocked boolean := false; n int;
begin
  begin
    update investment_fmv_2018 set fmv_per_unit = 1
      where investment_id = (select v from t where k='i_shared');
    get diagnostics n = row_count;
    if n = 0 then blocked := true; end if;
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'a guest cannot change a 2018 value it can see');
end $$;
select set_config('app.guest_share_id', '', false);

-- ------------------------------------------------ V80 readiness snapshots --
do $$ begin raise notice '--- readiness history is one person''s own ---'; end $$;
select pg_temp.as_user('ish');
insert into readiness_snapshots (household_id, user_id, taken_on, score, checks)
  values ((select v from t where k='hh'), (select v from t where k='ish'), date '2026-01-31', 40,
          '{"nominee": [1, 2]}');
select pg_temp.assert(
  (select count(*) from readiness_snapshots) = 1, 'a person reads their own readiness history');

select pg_temp.as_user('ravi');
select pg_temp.assert(
  (select count(*) from readiness_snapshots) = 0, 'ADMIN cannot read another member''s readiness history');
do $$
declare blocked boolean := false; n int;
begin
  begin
    insert into readiness_snapshots (household_id, user_id, taken_on, score, checks)
      values ((select v from t where k='hh'), (select v from t where k='ish'), date '2026-02-28', 99, '{}');
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'nor write a row in her name');
  update readiness_snapshots set score = 100;
  get diagnostics n = row_count;
  perform pg_temp.assert(n = 0, 'nor change hers');
end $$;

select pg_temp.as_user('out');
select pg_temp.assert(
  (select count(*) from readiness_snapshots) = 0, 'outside the household there is no readiness history');

-- ------------------------------------------------ V81 what a member sees --
do $$ begin raise notice '--- what a member sees: only ever about what you see ---'; end $$;
select pg_temp.as_user('ish');
select pg_temp.assert(
  app.member_would_see((select v from t where k='m_ravi'), 'investment', (select v from t where k='i_shared')),
  'Ravi would see the household gold');
-- The grant on the scoped FD was revoked above; without it, no; with it, yes.
select pg_temp.assert(
  not app.member_would_see((select v from t where k='m_ravi'), 'investment', (select v from t where k='i_scoped')),
  'Ravi would not see a scoped FD that names nobody');
insert into record_visibility_grants (household_id, record_type, record_id, member_id, created_by)
  values ((select v from t where k='hh'), 'investment', (select v from t where k='i_scoped'),
          (select v from t where k='m_ravi'), (select v from t where k='ish'));
select pg_temp.assert(
  app.member_would_see((select v from t where k='m_ravi'), 'investment', (select v from t where k='i_scoped')),
  'Ravi would see the FD scoped to him');
delete from record_visibility_grants where record_id = (select v from t where k='i_scoped');
select pg_temp.assert(
  not app.member_would_see((select v from t where k='m_ravi'), 'investment', (select v from t where k='i_private')),
  'Ravi would not see Ishwarya''s private FD, admin or not');
select pg_temp.assert(
  app.member_would_see((select v from t where k='m_ravi'), 'investment', (select v from t where k='i_joint')),
  'Ravi would see the private flat he co-owns');
select pg_temp.assert(
  not app.member_would_see((select v from t where k='m_aarav'), 'investment', (select v from t where k='i_shared')),
  'a member with no sign-in sees nothing');
select pg_temp.assert(
  not app.member_would_see((select v from t where k='m_ravi'), 'investment', (select v from t where k='i_ravi')),
  'no answer about a record the caller cannot read, even the other member''s own');

select pg_temp.as_user('ravi');
select pg_temp.assert(
  not app.member_would_see((select v from t where k='m_ish'), 'investment', (select v from t where k='i_private')),
  'Ravi cannot learn that Ishwarya holds a private FD by asking about her');

select pg_temp.as_user('out');
select pg_temp.assert(
  not app.member_would_see((select v from t where k='m_ravi'), 'investment', (select v from t where k='i_shared')),
  'outside the household the answer is always no');

-- ------------------------------------------- the first session (V85) ----
-- Readiness answers and first-session progress are one person's own rows. An
-- admin is still only a person: role grants capability, never sight.
do $$ begin raise notice '--- readiness answers and first-session progress are your own ---'; end $$;

select pg_temp.as_user('ish');
insert into readiness_check_answers (user_id, answers)
  values ((select v from t where k='ish'), '{"will": "no"}');
insert into first_session_progress (user_id, household_id, setting_up_for, someone_member_id)
  values ((select v from t where k='ish'), (select v from t where k='hh'), 'someone', (select v from t where k='m_aarav'));
select pg_temp.assert((select count(*) from readiness_check_answers) = 1, 'you see your own readiness answers');
select pg_temp.assert((select count(*) from first_session_progress) = 1, 'and your own first-session row');

select pg_temp.as_user('ravi');
select pg_temp.assert((select count(*) from readiness_check_answers) = 0,
  'an admin cannot read another person''s readiness answers');
select pg_temp.assert((select count(*) from first_session_progress) = 0,
  'nor their first-session row');

do $$
declare blocked boolean := false; n int;
begin
  begin
    insert into readiness_check_answers (user_id, answers)
      values ((select v from t where k='ish'), '{"will": "yes"}');
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'nor answer the check for them');

  update readiness_check_answers set answers = '{"will": "yes"}';
  get diagnostics n = row_count;
  perform pg_temp.assert(n = 0, 'nor change their answers');

  update first_session_progress set skipped_shelves = '{will}';
  get diagnostics n = row_count;
  perform pg_temp.assert(n = 0, 'nor skip a shelf for them');
end $$;

select pg_temp.as_user('out');
do $$
declare blocked boolean := false; own_household uuid;
begin
  begin
    insert into first_session_progress (user_id, household_id)
      values ((select v from t where k='out'), (select v from t where k='hh'));
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'an outsider cannot start a first session in a household they are not in');

  -- In their own household, naming a member of someone else's is refused.
  select m.household_id into own_household from members m
    where m.user_id = (select v from t where k='out') limit 1;
  perform pg_temp.assert(own_household is not null, 'the outsider has a household of their own');
  blocked := false;
  begin
    insert into first_session_progress (user_id, household_id, setting_up_for, someone_member_id)
      values ((select v from t where k='out'), own_household, 'someone', (select v from t where k='m_aarav'));
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'nor set up for a member of another household');
end $$;

-- ------------------------------------------------------------- heir mode ----
-- V90. A plan belongs to the person holding an open window, lives only as long
-- as that window, and a helper's link reaches only the tasks handed to them.
do $$ begin raise notice '--- heir mode lives inside an open window, and a helper sees only their tasks ---'; end $$;

select pg_temp.as_user('ish');
delete from user_sessions where user_id = (select v from t where k='ish');

select pg_temp.as_user('ravi');
select gen_random_uuid() as id \gset heirreq_
insert into emergency_requests (id, household_id, subject_member_id, requested_by, requested_at,
                                unlock_at, access_expires_at)
  values (:'heirreq_id', (select v from t where k='hh'), (select v from t where k='m_ish'),
          app.current_user_id(), now() - interval '20 days', now() - interval '6 days',
          now() + interval '20 days');
insert into t values ('heirreq', :'heirreq_id');

do $$
declare blocked boolean := false;
begin
  begin
    insert into heir_plans (household_id, emergency_request_id, subject_member_id, situation, created_by)
      values ((select v from t where k='hh'), (select v from t where k='request'),
              (select v from t where k='m_ish'), 'passed_away', app.current_user_id());
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'a plan cannot hang off a request that was stopped, even while another is open');
end $$;

select gen_random_uuid() as id \gset heirplan_
insert into heir_plans (id, household_id, emergency_request_id, subject_member_id, situation, created_by)
  values (:'heirplan_id', (select v from t where k='hh'), :'heirreq_id', (select v from t where k='m_ish'),
          'passed_away', app.current_user_id());
insert into t values ('heirplan', :'heirplan_id');
select pg_temp.assert((select count(*) from heir_plans) = 1,
  'the person holding the open window sees the plan they made');

insert into heir_tasks (plan_id, household_id, task_key, sort)
  values (:'heirplan_id', (select v from t where k='hh'), 'certificates', 0);
insert into heir_tasks (plan_id, household_id, task_key, record_type, record_id, sort)
  values (:'heirplan_id', (select v from t where k='hh'), 'claim', 'investment', (select v from t where k='i_private'), 1);

select gen_random_uuid() as id \gset helpshare_
insert into guest_shares (id, household_id, label, scope, token_hash, expires_at, created_by)
  values (:'helpshare_id', (select v from t where k='hh'), 'Helping: Meera', 'heir_help',
          md5(random()::text), now() + interval '10 days', app.current_user_id());
insert into guest_share_items (share_id, record_type, record_id)
  values (:'helpshare_id', 'investment', (select v from t where k='i_private'));
select gen_random_uuid() as id \gset helper_
insert into heir_helpers (id, plan_id, household_id, name, share_id)
  values (:'helper_id', :'heirplan_id', (select v from t where k='hh'), 'Meera', :'helpshare_id');
update heir_tasks set helper_id = :'helper_id' where task_key = 'claim' and plan_id = :'heirplan_id';
insert into t values ('helpshare', :'helpshare_id');

select pg_temp.as_user('ish');
select pg_temp.assert((select count(*) from heir_plans) = 0 and (select count(*) from heir_tasks) = 0
                      and (select count(*) from heir_helpers) = 0,
  'the person it is about does not see the plan made about them');
select pg_temp.as_user('out');
select pg_temp.assert((select count(*) from heir_plans) = 0 and (select count(*) from heir_tasks) = 0,
  'nor does anyone outside the household');

-- The helper's link, as ShareService opens it: the sharer's identity, clamped to the link.
select pg_temp.as_user('ravi');
select set_config('app.guest_share_id', :'helpshare_id', false);
select pg_temp.assert((select count(*) from heir_tasks) = 1
                      and (select task_key from heir_tasks) = 'claim',
  'a helper sees the task handed to them and no other');
select pg_temp.assert((select count(*) from heir_helpers) = 1 and (select count(*) from heir_plans) = 1,
  'and only their own place on the plan');
select pg_temp.assert(pg_temp.sees('i_private') and (select count(*) from investments) = 1,
  'and only the record their task names');
do $$
declare n int;
begin
  update heir_tasks set status = 'done';
  get diagnostics n = row_count;
  perform pg_temp.assert(n = 0, 'a helper reads; a helper does not tick things off');
end $$;

select set_config('app.guest_share_id', (select v::text from t where k='share'), false);
select pg_temp.assert((select count(*) from heir_tasks) = 0,
  'any other guest link reaches no task at all');
select set_config('app.guest_share_id', '', false);

do $$
declare blocked boolean := false; s uuid;
begin
  for i in 1..5 loop
    s := gen_random_uuid();
    begin
      insert into guest_shares (id, household_id, label, scope, token_hash, expires_at, created_by)
        values (s, (select v from t where k='hh'), 'Helping ' || i, 'heir_help', md5(random()::text),
                now() + interval '10 days', app.current_user_id());
      insert into heir_helpers (plan_id, household_id, name, share_id)
        values ((select v from t where k='heirplan'), (select v from t where k='hh'), 'Helper ' || i, s);
    exception when others then blocked := true;
    end;
  end loop;
  perform pg_temp.assert(blocked and (select count(*) from heir_helpers
                                       where plan_id = (select v from t where k='heirplan')) = 5,
    'a plan is shared with five people at most');
end $$;

select pg_temp.as_user('ish');
update emergency_requests set vetoed_at = now() where id = :'heirreq_id';
select pg_temp.as_user('ravi');
select pg_temp.assert((select count(*) from heir_plans) = 0 and (select count(*) from heir_tasks) = 0
                      and (select count(*) from heir_helpers) = 0,
  'a veto closes the plan, its tasks and its helpers, mid-session');
select set_config('app.guest_share_id', :'helpshare_id', false);
select pg_temp.assert((select count(*) from heir_tasks) = 0,
  'and the helper''s link reaches nothing');
select set_config('app.guest_share_id', '', false);

-- ------------------------------------------------------- guided flow drafts ----
do $$ begin raise notice '--- a place in a guided flow is its person''s own (V91) ---'; end $$;

select pg_temp.as_user('ish');
insert into guided_flow_drafts (household_id, user_id, flow, step, answers)
  values ((select v from t where k='hh'), app.current_user_id(), 'estate_document', 2, '{"kind":"will"}');
select pg_temp.assert((select count(*) from guided_flow_drafts) = 1, 'the owner sees her own place in a flow');
select pg_temp.as_user('ravi');
select pg_temp.assert((select count(*) from guided_flow_drafts) = 0, 'ADMIN cannot see another member''s draft');
do $$
declare blocked boolean := false;
begin
  begin
    insert into guided_flow_drafts (household_id, user_id, flow, step)
      values ((select v from t where k='hh'), (select v from t where k='ish'), 'emergency_setup', 1);
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'nor write a draft in her name');
end $$;
do $$
declare blocked boolean := false;
begin
  begin
    insert into guided_flow_drafts (household_id, user_id, flow, subject_key, step, answers)
      values ((select v from t where k='hh'), app.current_user_id(), 'where_and_who',
              'investment:' || gen_random_uuid(), 1, '{"originalLocation":"second shelf"}');
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'a where-and-who draft cannot hold the words, only the step');
end $$;

-- -------------------------------------------------------- lost-money checks ----
do $$ begin raise notice '--- a lost-money check is for who looked and who it is about (V92) ---'; end $$;

select pg_temp.as_user('ish');
insert into lost_money_checks (household_id, member_id, portal, status, checked_on, created_by)
  values ((select v from t where k='hh'), (select v from t where k='m_ravi'), 'iepf', 'nothing', current_date,
          app.current_user_id());
insert into lost_money_checks (household_id, member_id, portal, status, checked_on, created_by)
  values ((select v from t where k='hh'), (select v from t where k='m_aarav'), 'udgam', 'found', current_date,
          app.current_user_id());
select pg_temp.assert((select count(*) from lost_money_checks) = 2, 'the person who looked sees both checks');
select pg_temp.as_user('ravi');
select pg_temp.assert((select count(*) from lost_money_checks) = 1,
  'ADMIN sees the check about himself and not the one about the child');
select pg_temp.as_user('out');
select pg_temp.assert((select count(*) from lost_money_checks) = 0, 'outside the household there are none');
select pg_temp.as_user('ish');
select set_config('app.guest_share_id', (select v::text from t where k='share'), false);
select pg_temp.assert((select count(*) from lost_money_checks) = 0, 'and a guest link reaches none');
select set_config('app.guest_share_id', '', false);

-- --------------------------------------------------------- handbook editions ----
do $$ begin raise notice '--- an envelope edition is its maker''s (V93) ---'; end $$;

select pg_temp.as_user('ish');
insert into handbook_editions (household_id, created_by, edition, link_expires_at)
  values ((select v from t where k='hh'), app.current_user_id(), 1, now() + interval '365 days');
select pg_temp.assert((select count(*) from handbook_editions) = 1, 'the owner sees the edition she printed');
select pg_temp.as_user('ravi');
select pg_temp.assert((select count(*) from handbook_editions) = 0, 'ADMIN does not learn that she printed one');
select pg_temp.as_user('ish');

-- ------------------------------------------------- continuity signals ----
-- V95. Going quiet is the owner's own setting; a one-tap link is spent only by
-- its definer function; a trusted contact's tick is theirs and never rewritten;
-- a "do you know where" question is seen by the two people in it; a chain tick
-- is given only by the person who sealed that name; protection inputs are a
-- person's own.
do $$ begin raise notice '--- continuity signals follow the people in them (V95) ---'; end $$;

select pg_temp.as_user('ish');
insert into inactivity_checks (household_id, member_id, user_id, enabled, period_days, enabled_at)
  values ((select v from t where k='hh'), (select v from t where k='m_ish'), (select v from t where k='ish'),
          true, 90, now());
select pg_temp.assert((select count(*) from inactivity_checks) = 1,
  'the owner sees her own going-quiet setting');

select pg_temp.as_user('ravi');
select pg_temp.assert((select count(*) from inactivity_checks) = 0,
  'another member, even her trusted contact, does not see it');

do $$
declare blocked boolean := false; n int;
begin
  begin
    insert into inactivity_checks (household_id, member_id, user_id, enabled, period_days, enabled_at)
      values ((select v from t where k='hh'), (select v from t where k='m_ish'), (select v from t where k='ish'),
              true, 60, now());
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'nobody can turn going-quiet on in someone else''s name');
  update inactivity_checks set enabled = false;
  get diagnostics n = row_count;
  perform pg_temp.assert(n = 0, 'nor turn hers off');
end $$;

do $$
declare blocked boolean := false;
begin
  begin
    perform 1 from continuity_links;
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'the runtime role cannot read a one-tap link');
  blocked := false;
  begin
    insert into continuity_links (purpose, token_hash, household_id, user_id, emergency_contact_id, expires_at)
      values ('reachable', 'x', (select v from t where k='hh'), (select v from t where k='ravi'),
              (select id from emergency_contacts limit 1), now() + interval '1 day');
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'nor make one');
  blocked := false;
  begin
    perform app.member_present_since((select v from t where k='m_ish'), now() - interval '1 year');
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'whether someone has been here is not a question the runtime role can ask about anyone');
end $$;
select pg_temp.assert(app.redeem_continuity_link('no such link') is null,
  'an unknown link spends nothing and says nothing');

-- Ravi is Ishwarya's named contact (above).
select pg_temp.as_user('ish');
do $$
declare blocked boolean := false;
begin
  begin
    insert into trusted_contact_confirmations (household_id, emergency_contact_id, confirmed_by, via)
      values ((select v from t where k='hh'),
              (select id from emergency_contacts where member_id = (select v from t where k='m_ish')
                 and trusted_member_id = (select v from t where k='m_ravi')),
              app.current_user_id(), 'app');
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'the owner cannot say her contact is reachable for them');
end $$;

select pg_temp.as_user('ravi');
insert into trusted_contact_confirmations (household_id, emergency_contact_id, confirmed_by, via)
  values ((select v from t where k='hh'),
          (select id from emergency_contacts where member_id = (select v from t where k='m_ish')
             and trusted_member_id = (select v from t where k='m_ravi')),
          app.current_user_id(), 'app');
do $$
declare blocked boolean := false;
begin
  begin
    update trusted_contact_confirmations set confirmed_at = now() + interval '1 year';
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'a reachability tick is a dated fact and is never rewritten');
  blocked := false;
  begin
    insert into trusted_contact_confirmations (household_id, emergency_contact_id, confirmed_by, via)
      values ((select v from t where k='hh'),
              (select id from emergency_contacts where member_id = (select v from t where k='m_ish')
                 and trusted_member_id = (select v from t where k='m_ravi')),
              app.current_user_id(), 'link');
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'a "by link" tick is only ever written by the link itself');
end $$;

select pg_temp.as_user('ish');
select pg_temp.assert((select count(*) from trusted_contact_confirmations) = 1,
  'the owner sees the dated tick her contact left');
select pg_temp.as_user('out');
select pg_temp.assert((select count(*) from trusted_contact_confirmations) = 0
                      and (select count(*) from trusted_contact_asks) = 0,
  'outside the household there is no tick and no ask');

-- "Do you know where…?"
select pg_temp.as_user('ish');
insert into key_holder_asks (household_id, record_type, record_id, thing, asked_by, asked_member_id)
  values ((select v from t where k='hh'), 'investment', (select v from t where k='i_private'),
          'SBI FD (secret)', app.current_user_id(), (select v from t where k='m_ravi'));

select pg_temp.as_user('ravi');
select pg_temp.assert((select count(*) from key_holder_asks) = 1, 'the person asked sees the question');
update key_holder_asks set answer = 'yes', answered_at = now();
select pg_temp.assert((select answer from key_holder_asks limit 1) = 'yes', 'and answers it');
do $$
declare blocked boolean := false;
begin
  begin
    update key_holder_asks set thing = 'something else';
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'an answer cannot rewrite the question');
  blocked := false;
  begin
    insert into key_holder_asks (household_id, record_type, record_id, thing, asked_by, asked_member_id)
      values ((select v from t where k='hh'), 'investment', (select v from t where k='i_private'),
              'guess', app.current_user_id(), (select v from t where k='m_ish'));
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'nobody can ask about a record they cannot see');
end $$;

select pg_temp.as_user('ish');
do $$
declare n int;
begin
  update key_holder_asks set answer = 'not_sure', answered_at = now();
  get diagnostics n = row_count;
  perform pg_temp.assert(n = 0, 'the asker cannot answer for the person asked');
end $$;
select pg_temp.as_user('out');
select pg_temp.assert((select count(*) from key_holder_asks) = 0, 'outside the household nobody learns who was asked');

-- The chain. Ishwarya seals a second key holder on the shared gold and ticks it.
select pg_temp.as_user('ish');
insert into sealed_values (household_id, record_type, record_id, field_key, ciphertext, sealed_by)
  values ((select v from t where k='hh'), 'investment', (select v from t where k='i_shared'),
          'key_holder_2', repeat('A', 60), app.current_user_id());
insert into access_chain_confirmations (household_id, record_type, record_id, position, confirmed_by)
  values ((select v from t where k='hh'), 'investment', (select v from t where k='i_shared'), 2, app.current_user_id());
insert into sealed_values (household_id, record_type, record_id, field_key, ciphertext, sealed_by)
  values ((select v from t where k='hh'), 'investment', (select v from t where k='i_private'),
          'key_holder_2', repeat('A', 60), app.current_user_id());
insert into access_chain_confirmations (household_id, record_type, record_id, position, confirmed_by)
  values ((select v from t where k='hh'), 'investment', (select v from t where k='i_private'), 2, app.current_user_id());

select pg_temp.as_user('ravi');
select pg_temp.assert(
  (select count(*) from access_chain_confirmations where record_id = (select v from t where k='i_shared')) = 1,
  'a chain tick is as visible as the record');
select pg_temp.assert(
  (select count(*) from access_chain_confirmations where record_id = (select v from t where k='i_private')) = 0,
  'and not on a private record he cannot see');
do $$
declare blocked boolean := false; n int;
begin
  begin
    insert into access_chain_confirmations (household_id, record_type, record_id, position, confirmed_by)
      values ((select v from t where k='hh'), 'investment', (select v from t where k='i_shared'), 3, app.current_user_id());
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'nobody ticks a position with no name they sealed');
  delete from access_chain_confirmations where record_id = (select v from t where k='i_shared');
  get diagnostics n = row_count;
  perform pg_temp.assert(n = 0, 'nor removes a tick on a name someone else sealed');
end $$;

select pg_temp.as_user('ish');
update sealed_values set ciphertext = repeat('B', 60)
 where record_id = (select v from t where k='i_shared') and field_key = 'key_holder_2';
select pg_temp.assert(
  (select count(*) from access_chain_confirmations where record_id = (select v from t where k='i_shared')) = 0,
  'writing the name again takes its tick away');

-- Protection inputs.
insert into protection_inputs (household_id, user_id, annual_expenses)
  values ((select v from t where k='hh'), app.current_user_id(), 2400000);
select pg_temp.as_user('ravi');
select pg_temp.assert((select count(*) from protection_inputs) = 0,
  'what someone said the household spends is theirs alone');
do $$
declare blocked boolean := false;
begin
  begin
    insert into protection_inputs (household_id, user_id, annual_expenses)
      values ((select v from t where k='hh'), (select v from t where k='ish'), 1);
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'and nobody writes it for them');
end $$;

select pg_temp.as_user('ish');
select set_config('app.guest_share_id', (select v::text from t where k='share'), false);
select pg_temp.assert((select count(*) from inactivity_checks) = 0
                      and (select count(*) from protection_inputs) = 0
                      and (select count(*) from key_holder_asks) = 0
                      and (select count(*) from trusted_contact_confirmations) = 0,
  'a guest link reaches none of the continuity signals');
select set_config('app.guest_share_id', '', false);

-- ---------------------------------------------------------- household plans --
-- V101. A household's plan decides whether it can be changed, so nobody in the
-- application may set one — not the owner, not an admin — and nobody outside
-- the household may learn it exists. Operators set it as the schema owner.
do $$ begin raise notice '--- a household plan is set by an operator, never through the app ---'; end $$;

select pg_temp.as_user('ish');
do $$
declare blocked boolean;
begin
  blocked := false;
  begin
    insert into household_plans (household_id, plan_code, paid_through, set_by)
      values ((select v from t where k='hh'), 'family', current_date + 3650, 'ish');
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'the owner cannot give her household a plan');

  blocked := false;
  begin
    perform ops.set_household_plan((select v from t where k='hh'), 'family', null, null, 'ish', null);
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'the runtime role cannot reach the operator functions');
end $$;

select pg_temp.as_user('ravi');
do $$
declare blocked boolean; n int;
begin
  -- Refused by the grant, or reaching no row through RLS: either is a refusal.
  blocked := false;
  begin
    update household_plans set paid_through = current_date + 3650
      where household_id = (select v from t where k='hh');
    get diagnostics n = row_count;
    blocked := n = 0;
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'an admin cannot extend a plan');

  blocked := false;
  begin
    delete from household_plans where household_id = (select v from t where k='hh');
    get diagnostics n = row_count;
    blocked := n = 0;
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'nor delete one to lift a lapse');
end $$;

select pg_temp.as_user('out');
select pg_temp.assert(
  not exists (select 1 from household_plans where household_id = (select v from t where k='hh')),
  'outside the household, its plan does not exist');
select pg_temp.as_user('ish');

-- ------------------------------------------------------------ support codes --
-- V102. A support code is its maker's alone: nobody else in the household sees
-- it, a guest session borrowing her identity cannot, the app can take it back
-- but never rewrite it, reopen it or fake a lookup, and nobody can delete it.
do $$ begin raise notice '--- a support code is its maker''s, and only support records a look ---'; end $$;

select pg_temp.as_user('ish');
insert into support_codes (id, user_id, code_hash, diagnostics, expires_at)
  values ('00000000-0000-0000-0000-00000000c0de', (select v from t where k='ish'),
          repeat('a', 64), '{"screen":"home"}', now() + interval '24 hours');
select pg_temp.assert(
  (select count(*) from support_codes where id = '00000000-0000-0000-0000-00000000c0de') = 1,
  'she sees the support code she made');

do $$
declare blocked boolean := false;
begin
  begin
    insert into support_codes (user_id, code_hash, diagnostics, expires_at)
      values ((select v from t where k='ravi'), repeat('b', 64), '{}', now() + interval '1 hour');
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'nobody can make a support code in someone else''s name');

  blocked := false;
  begin
    insert into support_codes (user_id, code_hash, diagnostics, expires_at)
      values ((select v from t where k='ish'), repeat('c', 64), '{}', now() + interval '48 hours');
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'a support code cannot outlive 24 hours');

  blocked := false;
  begin
    update support_codes set lookups = 7, last_looked_up_at = now()
      where id = '00000000-0000-0000-0000-00000000c0de';
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'the app cannot rewrite how often support looked');

  blocked := false;
  begin
    update support_codes set diagnostics = '{"title":"SBI FD"}'
      where id = '00000000-0000-0000-0000-00000000c0de';
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'nor what a code shares');
end $$;

update support_codes set revoked_at = now() where id = '00000000-0000-0000-0000-00000000c0de';
select pg_temp.assert(
  (select revoked_at is not null from support_codes where id = '00000000-0000-0000-0000-00000000c0de'),
  'she can take it back');

do $$
declare blocked boolean := false; n int;
begin
  begin
    update support_codes set revoked_at = null where id = '00000000-0000-0000-0000-00000000c0de';
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'a code taken back stays taken back');

  blocked := false;
  begin
    delete from support_codes where id = '00000000-0000-0000-0000-00000000c0de';
    get diagnostics n = row_count;
    blocked := n = 0;
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'and nobody deletes one');

  blocked := false;
  begin
    perform * from ops.lookup_support_code('AAAAA-BBBBB', 'ish', 'curious to see');
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'the runtime role cannot look a code up');
end $$;

select set_config('app.guest_share_id', (select v::text from t where k='share'), false);
select pg_temp.assert(
  not exists (select 1 from support_codes),
  'a guest session borrowing her identity sees no support codes');
select set_config('app.guest_share_id', '', false);

select pg_temp.as_user('ravi');
select pg_temp.assert(not exists (select 1 from support_codes where user_id = (select v from t where k='ish')),
  'ADMIN cannot see another member''s support codes');
select pg_temp.as_user('ish');

-- ------------------------------------------------------ sign-in email outbox --
-- V110. Every email sign-in request queues a message through one definer
-- function, listed address or not. Where it goes and how it ended are the
-- worker's alone, on the owner connection: the runtime role writes through the
-- function and can read, change or delete nothing, whoever it acts for.
do $$ begin raise notice '--- sign-in email outbox (V110) ---'; end $$;

select app.enqueue_sign_in_code_email('someone.unlisted@example.test', 'login', gen_random_uuid(), 6);

do $$
declare blocked boolean;
begin
  blocked := false;
  begin perform count(*) from sign_in_code_emails;
  exception when insufficient_privilege then blocked := true; end;
  perform pg_temp.assert(blocked, 'the runtime role cannot read how a sign-in email ended');

  blocked := false;
  begin perform address from sign_in_code_email_bodies;
  exception when insufficient_privilege then blocked := true; end;
  perform pg_temp.assert(blocked, 'the runtime role cannot read where a sign-in email goes');

  blocked := false;
  begin update sign_in_code_emails set status = 'sent';
  exception when insufficient_privilege then blocked := true; end;
  perform pg_temp.assert(blocked, 'the runtime role cannot mark a sign-in email sent');

  blocked := false;
  begin update sign_in_code_email_bodies set address = 'attacker@example.test';
  exception when insufficient_privilege then blocked := true; end;
  perform pg_temp.assert(blocked, 'the runtime role cannot redirect a queued sign-in email');

  blocked := false;
  begin delete from sign_in_code_email_bodies;
  exception when insufficient_privilege then blocked := true; end;
  perform pg_temp.assert(blocked, 'the runtime role cannot delete a queued sign-in email');

  blocked := false;
  begin insert into sign_in_code_emails default values;
  exception when insufficient_privilege then blocked := true; end;
  perform pg_temp.assert(blocked, 'the runtime role writes a sign-in email only through the function');

  -- V130: the operator's alert flag says an address was listed; it is the
  -- owner connection's alone, like the rest of the record.
  blocked := false;
  begin perform count(*) from sign_in_code_emails where operator_alert;
  exception when insufficient_privilege then blocked := true; end;
  perform pg_temp.assert(blocked, 'the runtime role cannot count the operator''s sign-in email alerts');

  blocked := false;
  begin update sign_in_code_emails set operator_alert = false;
  exception when insufficient_privilege then blocked := true; end;
  perform pg_temp.assert(blocked, 'the runtime role cannot clear an operator alert');
end $$;

do $$
declare blocked boolean := false;
begin
  -- Only a login code is queued; the table refuses anything else.
  begin
    perform app.enqueue_sign_in_code_email('someone@example.test', 'step_up', gen_random_uuid(), 6);
  exception when check_violation then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'only a sign-in code is queued this way');
end $$;

select pg_temp.as_user('ish');
do $$
declare blocked boolean := false;
begin
  begin perform count(*) from sign_in_code_email_bodies;
  exception when insufficient_privilege then blocked := true; end;
  perform pg_temp.assert(blocked, 'a signed-in person cannot read the sign-in email queue either');
end $$;
-- ------------------------------------------------- consent to messages --
-- V125. A yes names its channels; "not now" is one person's own row, never
-- anyone else's, and never deleted by the application.
do $$ begin raise notice '--- consent to messages is asked for (V125) ---'; end $$;

select pg_temp.as_user('ish');
insert into consent_events (user_id, purpose, action, notice_version, channels, asked_in)
  values ((select v from t where k='ish'), 'messages', 'given', app.current_privacy_notice_version(),
          array['email'], 'in_context');
select pg_temp.assert(
  (select channels = array['email'] and asked_in = 'in_context' from consent_events
    where user_id = (select v from t where k='ish') order by seq desc limit 1),
  'a yes to messages records the channels it covers and where it was asked');

do $$
declare blocked boolean;
begin
  blocked := false;
  begin
    insert into consent_events (user_id, purpose, action, notice_version, channels)
      values ((select v from t where k='ish'), 'messages', 'given', app.current_privacy_notice_version(),
              array['carrier_pigeon']);
  exception when check_violation then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'a yes names only channels that exist');

  blocked := false;
  begin
    insert into consent_events (user_id, purpose, action, notice_version, channels)
      values ((select v from t where k='ish'), 'messages', 'withdrawn', app.current_privacy_notice_version(),
              array['email']);
  exception when check_violation then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'a withdrawal names no channels: it withdraws them all');

  -- Which messages are essential is about no one, so the runtime role may ask it.
  perform pg_temp.assert(app.message_is_essential('auth.new_sign_in')
                         and not app.message_is_essential('reminder.maturity'),
    'a sign-in notice is essential and a reminder is not');
  -- V140: a notice that changes your own rights or obligations is essential;
  -- someone else joining or leaving is household news, under consent.
  perform pg_temp.assert(app.message_is_essential('emergency.named')
                         and app.message_is_essential('lifecycle.departure.completed.you')
                         and app.message_is_essential('lifecycle.memorial.reversed')
                         and app.message_is_essential('lifecycle.successor.named'),
    'being named, leaving, a memorial reversed and a successor named are essential (V140)');
  -- V143: the dormancy notices change the recipient's access, so they are essential too.
  perform pg_temp.assert(app.message_is_essential('lifecycle.household.dormant')
                         and app.message_is_essential('lifecycle.household.dormant.you')
                         and app.message_is_essential('lifecycle.household.running_again')
                         and app.message_is_essential('lifecycle.household.ownership_accepted'),
    'a household left with nobody running it, or running again, is essential (V143)');
  perform pg_temp.assert(not app.message_is_essential('lifecycle.departure.completed')
                         and not app.message_is_essential('lifecycle.departure.started')
                         and not app.message_is_essential('lifecycle.coming_of_age.welcomed'),
    'someone else joining or leaving is not essential (V140)');
end $$;

insert into messages_consent_asks (user_id) values ((select v from t where k='ish'));
select pg_temp.assert((select count(*) from messages_consent_asks) = 1,
  'a person sees their own "not now"');
update messages_consent_asks set not_now_at = now() where user_id = (select v from t where k='ish');

do $$
declare blocked boolean := false;
begin
  begin
    delete from messages_consent_asks;
  exception when insufficient_privilege then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'a "not now" is not deleted by the application');
end $$;

select pg_temp.as_user('ravi');
select pg_temp.assert((select count(*) from messages_consent_asks) = 0,
  'an admin sees nothing of another member''s "not now"');
do $$
declare n int; blocked boolean := false;
begin
  update messages_consent_asks set not_now_at = now() - interval '1 year'
   where user_id = (select v from t where k='ish');
  get diagnostics n = row_count;
  perform pg_temp.assert(n = 0, 'nor moves it, to make them be asked again');
  begin
    insert into messages_consent_asks (user_id) values ((select v from t where k='ish'));
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'nobody says "not now" in someone else''s name');
end $$;
select pg_temp.as_user('ish');

-- V141. "Not now" beside one holding, or beside the Still true? digest, is kept
-- per context: your own rows, never anyone else's, never deleted by the app.
do $$ begin raise notice '--- "not now" is kept per holding and for the digest (V141) ---'; end $$;

insert into messages_consent_ask_contexts (user_id, context_type, context_id)
  values ((select v from t where k='ish'), 'investment', gen_random_uuid()),
         ((select v from t where k='ish'), 'investment', gen_random_uuid()),
         ((select v from t where k='ish'), 'still_true_digest', null);
select pg_temp.assert((select count(*) from messages_consent_ask_contexts) = 3,
  'a person sees their own "not now" for each holding and the digest');

do $$
declare blocked boolean;
begin
  blocked := false;
  begin
    insert into messages_consent_ask_contexts (user_id, context_type, context_id)
      values ((select v from t where k='ish'), 'still_true_digest', null);
  exception when unique_violation then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'the digest is one context per person');

  blocked := false;
  begin
    insert into messages_consent_ask_contexts (user_id, context_type, context_id)
      values ((select v from t where k='ish'), 'investment', null);
  exception when check_violation then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'a holding''s "not now" names the holding');

  blocked := false;
  begin
    insert into messages_consent_ask_contexts (user_id, context_type, context_id)
      values ((select v from t where k='ish'), 'a_banner', gen_random_uuid());
  exception when check_violation then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'only the contexts that exist');

  blocked := false;
  begin
    delete from messages_consent_ask_contexts;
  exception when insufficient_privilege then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'a "not now" beside a holding is not deleted by the application');
end $$;

select pg_temp.as_user('ravi');
select pg_temp.assert((select count(*) from messages_consent_ask_contexts) = 0,
  'an admin sees nothing of another member''s "not now" beside a holding');
do $$
declare n int; blocked boolean := false;
begin
  update messages_consent_ask_contexts set not_now_at = now() - interval '1 year'
   where user_id = (select v from t where k='ish');
  get diagnostics n = row_count;
  perform pg_temp.assert(n = 0, 'nor moves it on');
  begin
    insert into messages_consent_ask_contexts (user_id, context_type, context_id)
      values ((select v from t where k='ish'), 'liability', gen_random_uuid());
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'nobody says "not now" beside a holding in someone else''s name');
end $$;
select pg_temp.as_user('ish');

-- V142. A yes names its channels from here, and a yes from before channels were
-- chosen is not consent to anything.
do $$ begin raise notice '--- a yes from before channels is asked again (V142) ---'; end $$;
do $$
declare blocked boolean := false;
begin
  begin
    insert into consent_events (user_id, purpose, action, notice_version)
      values ((select v from t where k='ish'), 'messages', 'given', app.current_privacy_notice_version());
  exception when check_violation then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'no new yes to messages is written without channels (V142)');

  blocked := false;
  begin
    insert into consent_events (user_id, purpose, action, notice_version, asked_again)
      values ((select v from t where k='ish'), 'records', 'given', app.current_privacy_notice_version(), true);
  exception when check_violation then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'only an answer about messages is marked as asked again');
end $$;

-- -------------------------------------------------------- dormant households --
-- V120. A household whose last owner goes while it holds records is dormant:
-- nobody administers it, sight is unchanged, nobody writes the state but the
-- database itself, and nobody takes it on who should not. A household of its
-- own, so the lifecycle state of Koduri above is not disturbed.
do $$ begin raise notice '--- a dormant household: no one runs it, no one sees more, and no one takes it who should not ---'; end $$;

insert into users (phone, full_name) values ('+919000000021', 'Lakshmi') returning id \gset dh_owner_
insert into users (phone, full_name) values ('+919000000022', 'Kiran')   returning id \gset dh_admin_
insert into users (phone, full_name) values ('+919000000023', 'Chintu')  returning id \gset dh_teen_
insert into users (phone, full_name) values ('+919000000024', 'The CA')  returning id \gset dh_adv_

select set_config('app.user_id', :'dh_owner_id', false);
select household_id, member_id from app.bootstrap_household('Dormant', 'private', 'Lakshmi') \gset dh_
insert into members (household_id, user_id, display_name) values (:'dh_household_id', :'dh_admin_id', 'Kiran')
  returning id \gset dh_adminm_
insert into members (household_id, user_id, display_name, date_of_birth)
  values (:'dh_household_id', :'dh_teen_id', 'Chintu', current_date - interval '15 years');
insert into members (household_id, user_id, display_name) values (:'dh_household_id', :'dh_adv_id', 'The CA');
insert into household_memberships (household_id, user_id, role, status) values
  (:'dh_household_id', :'dh_admin_id', 'admin', 'active'),
  (:'dh_household_id', :'dh_teen_id', 'viewer', 'active'),
  (:'dh_household_id', :'dh_adv_id', 'advisor', 'active');
select pg_temp.mk(:'dh_household_id', 'fd', 'Lakshmi''s FD (private)', 700000, 'private', :'dh_member_id') as id \gset dh_fd_
insert into t values ('dh', :'dh_household_id'), ('dh_owner', :'dh_owner_id'), ('dh_admin', :'dh_admin_id'),
  ('dh_teen', :'dh_teen_id'), ('dh_adv', :'dh_adv_id'), ('dh_fd', :'dh_fd_id'), ('dh_m_owner', :'dh_member_id');

select pg_temp.assert(app.household_holds_records_for_its_owner((select v from t where k='dh')),
  'the owner can ask whether her own household holds records (for the closure preview)');
select pg_temp.as_user('dh_admin');
select pg_temp.assert(not app.household_holds_records_for_its_owner((select v from t where k='dh')),
  'an admin cannot ask it');
select pg_temp.as_user('out');
select pg_temp.assert(not app.household_holds_records_for_its_owner((select v from t where k='dh')),
  'nor can anyone outside');

select pg_temp.as_user('dh_admin');
do $$
declare blocked boolean;
begin
  blocked := false;
  begin
    insert into household_dormancies (household_id, reason, owner_user_id, memorial_id)
      values ((select v from t where k='dh'), 'owner_passed_away', (select v from t where k='dh_owner'), gen_random_uuid());
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'nobody makes a household dormant by writing the row');

  blocked := false;
  begin
    perform app.open_household_dormancy((select v from t where k='dh'), (select v from t where k='dh_owner'),
                                        'owner_leaving', null, gen_random_uuid(), null, null);
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'nor through the sweep''s function');

  blocked := false;
  begin
    perform app.going_leaves_household_ownerless((select v from t where k='dh'), (select v from t where k='dh_owner'));
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'the rule about a named person is not the runtime role''s to ask');

  blocked := false;
  begin
    perform app.household_holds_records((select v from t where k='hh'));
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'nor whether any household holds records');

  blocked := false;
  begin
    perform app.accept_household_ownership((select v from t where k='dh'));
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'a household that is not dormant cannot be taken on');
end $$;
select pg_temp.assert(app.can_administer_household((select v from t where k='dh')),
  'before: the admin administers');

-- The admin marks the owner as passed away: the trigger makes it dormant.
insert into member_memorials (household_id, member_id, user_id, marked_by, basis)
  values ((select v from t where k='dh'), (select v from t where k='dh_m_owner'),
          (select v from t where k='dh_owner'), app.current_user_id(), 'admin');

select pg_temp.assert((select count(*) from household_dormancies
                        where household_id = (select v from t where k='dh') and ended_at is null
                          and reason = 'owner_passed_away' and accept_from > now() + interval '6 days') = 1,
  'a memorial on the last owner makes the household dormant, for a week before anyone may take it on');
select pg_temp.assert(app.household_is_dormant((select v from t where k='dh')),
  'the household can see it is dormant');
select pg_temp.assert(not app.can_administer_household((select v from t where k='dh'))
                      and app.can_write_household((select v from t where k='dh')),
  'while dormant the admin administers nothing, and can still write what is theirs');
select pg_temp.assert(not pg_temp.sees('dh_fd'),
  'and dormancy opens none of the owner''s private records');

do $$
declare blocked boolean; n int;
begin
  blocked := false;
  begin
    update household_memberships set role = 'owner'
     where household_id = (select v from t where k='dh') and user_id = app.current_user_id();
    get diagnostics n = row_count;
    blocked := n = 0;
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'the admin cannot make themselves owner around the accept function');

  blocked := false;
  begin
    update household_dormancies set ended_at = now(), ended_reason = 'transferred';
    get diagnostics n = row_count;
    blocked := n = 0;
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'nor end the dormancy by writing it');

  blocked := false;
  begin
    delete from household_dormancies;
    get diagnostics n = row_count;
    blocked := n = 0;
  exception when others then blocked := true;
  end;
  perform pg_temp.assert(blocked, 'nor delete it');

  blocked := false;
  begin
    perform app.accept_household_ownership((select v from t where k='dh'));
  exception when others then blocked := sqlerrm = 'dormancy_not_yet';
  end;
  perform pg_temp.assert(blocked, 'nor take it on in the week a memorial can still be corrected');
end $$;

select pg_temp.as_user('dh_teen');
do $$
declare blocked boolean := false;
begin
  begin
    perform app.accept_household_ownership((select v from t where k='dh'));
  exception when others then blocked := sqlerrm = 'ownership_not_eligible';
  end;
  perform pg_temp.assert(blocked, 'a minor cannot take a household on');
end $$;

select pg_temp.as_user('dh_adv');
do $$
declare blocked boolean := false;
begin
  begin
    perform app.accept_household_ownership((select v from t where k='dh'));
  exception when others then blocked := sqlerrm = 'ownership_not_eligible';
  end;
  perform pg_temp.assert(blocked, 'nor can an advisor');
end $$;
select pg_temp.assert(not pg_temp.sees('dh_fd'), 'the advisor sees none of the owner''s private records either');

select pg_temp.as_user('out');
select pg_temp.assert((select count(*) from household_dormancies where household_id = (select v from t where k='dh')) = 0
                      and not app.household_is_dormant((select v from t where k='dh')),
  'outside the household nobody learns that it is dormant');
do $$
declare blocked boolean := false;
begin
  begin
    perform app.accept_household_ownership((select v from t where k='dh'));
  exception when others then blocked := sqlerrm = 'dormancy_not_found';
  end;
  perform pg_temp.assert(blocked, 'and nobody outside can take it on, or learn there is anything to take');
end $$;

-- "I'm here": the owner comes back, and the dormancy ends with the memorial.
select pg_temp.as_user('dh_owner');
update member_memorials set reversed_at = now(), reversed_by = app.current_user_id()
 where household_id = (select v from t where k='dh') and reversed_at is null;
select pg_temp.as_user('dh_admin');
select pg_temp.assert((select ended_reason from household_dormancies
                        where household_id = (select v from t where k='dh')) = 'owner_returned'
                      and not app.household_is_dormant((select v from t where k='dh'))
                      and app.can_administer_household((select v from t where k='dh')),
  'taking the label away ends the dormancy, and the admin administers again');

select pg_temp.as_user('ish');

do $$ begin raise notice ''; raise notice 'ALL PRIVACY ASSERTIONS PASSED'; end $$;
rollback;
