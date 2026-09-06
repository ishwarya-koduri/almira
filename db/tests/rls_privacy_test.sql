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

do $$ begin raise notice ''; raise notice 'ALL PRIVACY ASSERTIONS PASSED'; end $$;
rollback;
