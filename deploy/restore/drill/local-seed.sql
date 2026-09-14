-- =============================================================================
-- Seed for scripts/restore-drill-local.sh: a small household whose privacy a
-- bad restore could break. Runs as the RUNTIME role (almira_app), through the
-- same policies the application uses, and COMMITS — unlike the RLS suite.
--
--   Asha    owner, with a private FD nobody else may see
--   Vikram  admin, with a private SIP
--   Diya    a child, no login, who owns a small FD
--   a household-shared gold holding, a private home loan, and one measurement
--   count, so the aggregate table is in the dump too
--
-- Names and numbers are invented.
-- =============================================================================
\set ON_ERROR_STOP on
begin;

insert into users (phone, full_name) values ('+919000000101', 'Asha')   returning id \gset asha_
insert into users (phone, full_name) values ('+919000000102', 'Vikram') returning id \gset vik_

select set_config('app.user_id', :'asha_id', true);
select household_id, member_id from app.bootstrap_household('Drill household', 'private', 'Asha') \gset hh_

insert into members (household_id, user_id, display_name, relationship)
  values (:'hh_household_id', :'vik_id', 'Vikram', 'spouse') returning id \gset vikm_
insert into members (household_id, display_name, relationship, date_of_birth)
  values (:'hh_household_id', 'Diya', 'child', date '2016-06-01') returning id \gset diyam_
insert into household_memberships (household_id, user_id, role, status)
  values (:'hh_household_id', :'vik_id', 'admin', 'active');

create or replace function pg_temp.holding(
    p_hh uuid, p_type text, p_title text, p_amount numeric, p_visibility text, p_owner uuid) returns uuid
  language plpgsql as $$
declare v_id uuid := gen_random_uuid();
begin
  insert into investments (id, household_id, type_id, title, invested_amount, visibility, created_by)
    select v_id, p_hh, it.id, p_title, p_amount, p_visibility, app.current_user_id()
      from investment_types it where it.code = p_type and it.household_id is null;
  insert into investment_ownerships (investment_id, member_id, share_pct) values (v_id, p_owner, 100);
  return v_id;
end $$;

select pg_temp.holding(:'hh_household_id', 'fd', 'Asha private FD', 500000, 'private', :'hh_member_id');
select pg_temp.holding(:'hh_household_id', 'gold_physical', 'Family gold', 180000, 'household', :'hh_member_id');
select pg_temp.holding(:'hh_household_id', 'fd', 'Diya FD', 25000, 'household', :'diyam_id');

insert into liabilities (household_id, kind, title, outstanding, visibility, created_by)
  values (:'hh_household_id', 'home', 'Home loan', 2400000, 'private', :'asha_id');

select app.count_product_event('household_created');

select set_config('app.user_id', :'vik_id', true);
select pg_temp.holding(:'hh_household_id', 'mf_sip', 'Vikram private SIP', 300000, 'private', :'vikm_id');

commit;
