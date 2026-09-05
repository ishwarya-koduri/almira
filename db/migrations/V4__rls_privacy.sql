-- =============================================================================
-- V4 · Row-Level Security — the authoritative gate of the privacy model.
--
-- docs/05-security-and-privacy.md §3.6 requires enforcement at three layers:
--   1. Postgres RLS      <- THIS FILE. The layer a service bug cannot bypass.
--   2. Service layer     <- re-checks the same predicate, applies masking.
--   3. UI                <- never asks for what the API will not return.
--
-- THE PREDICATE. A member may read a household record iff:
--     they own/hold it                                  (ownership wins always)
--  OR visibility = 'household'
--  OR visibility = 'scoped' AND a matching grant row exists
--  OR emergency access is unlocked AND is_in_continuity  (added in Phase 3)
--
-- Note what is deliberately absent: role. No role — not owner, not admin —
-- appears anywhere in a read predicate. Being the household creator lets you
-- MANAGE the household, not read everyone's private holdings (docs/05 §3).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Predicate helpers.
--
-- These are SECURITY DEFINER on purpose. A policy on `investments` that queried
-- `household_memberships` directly would trip that table's own policy and
-- recurse. Running the lookup as the owner breaks the cycle. Each function is
-- STABLE, takes only the current user from the transaction GUC, and returns a
-- boolean — it cannot be coaxed into returning data.
-- -----------------------------------------------------------------------------

create or replace function app.is_household_member(p_household_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (
    select 1 from household_memberships hm
    where hm.household_id = p_household_id
      and hm.user_id = app.current_user_id()
      and hm.status = 'active'
  )
$$;

-- Capability check. Used only on WRITE policies, never on reads.
create or replace function app.can_write_household(p_household_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (
    select 1 from household_memberships hm
    where hm.household_id = p_household_id
      and hm.user_id = app.current_user_id()
      and hm.status = 'active'
      and hm.role in ('owner','admin','editor')
  )
$$;

create or replace function app.can_administer_household(p_household_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (
    select 1 from household_memberships hm
    where hm.household_id = p_household_id
      and hm.user_id = app.current_user_id()
      and hm.status = 'active'
      and hm.role in ('owner','admin')
  )
$$;

-- The member rows that ARE the current user, inside one household.
create or replace function app.current_member_ids(p_household_id uuid)
  returns uuid[] language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select coalesce(array_agg(m.id), '{}')
  from members m
  where m.household_id = p_household_id
    and m.user_id = app.current_user_id()
    and m.deleted_at is null
$$;

-- Ownership. This is what makes "you cannot hide a joint asset from your
-- co-owner" true at the database level (docs/05 §3.1).
create or replace function app.owns_investment(p_investment_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (
    select 1 from investment_ownerships o
      join members m on m.id = o.member_id
    where o.investment_id = p_investment_id
      and m.user_id = app.current_user_id()
      and m.deleted_at is null
  )
$$;

create or replace function app.holds_account(p_account_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (
    select 1 from account_holders h
      join members m on m.id = h.member_id
    where h.account_id = p_account_id
      and m.user_id = app.current_user_id()
      and m.deleted_at is null
  )
$$;

create or replace function app.has_visibility_grant(p_record_type text, p_record_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (
    select 1 from record_visibility_grants g
      join members m on m.id = g.member_id
    where g.record_type = p_record_type
      and g.record_id   = p_record_id
      and m.user_id     = app.current_user_id()
      and m.deleted_at is null
  )
$$;

-- The whole model in one expression, so every table states it identically.
create or replace function app.can_read_record(
    p_household_id uuid,
    p_visibility   text,
    p_record_type  text,
    p_record_id    uuid,
    p_is_owner     boolean
) returns boolean language sql stable
  set search_path = public, app, pg_temp as $$
  select app.is_household_member(p_household_id)
     and ( p_is_owner
        or p_visibility = 'household'
        or (p_visibility = 'scoped'
            and app.has_visibility_grant(p_record_type, p_record_id)) )
$$;

-- ---------------------------------------------------------------------------
-- Write authority over a record and its children.
--
-- There is a genuine ordering problem underneath these two functions. Creating
-- a Private investment takes two statements: the record, then its ownership
-- rows. In between, the record has no owners, so the read predicate correctly
-- says "nobody may see this" -- including the person creating it. A naive
-- child-table policy that asks "can I read the parent?" therefore makes it
-- impossible to ever attach the first owner.
--
-- The fix is the `not exists (... ownerships)` clause: write access is granted
-- while a record has no owners yet. That window is not exploitable, because the
-- DEFERRED share-sum constraint in V3 guarantees every COMMITTED investment has
-- owners totalling 100%. An ownerless row therefore only ever exists inside the
-- uncommitted transaction that is creating it, which no other session can see.
--
-- Note what this does NOT do: it never lets someone attach themselves to an
-- existing record they cannot read. That would be privilege escalation --
-- grant yourself ownership, then read the thing you just made yourself an
-- owner of.
-- ---------------------------------------------------------------------------
create or replace function app.can_modify_investment(p_investment_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (
    select 1 from investments i
    where i.id = p_investment_id
      and i.deleted_at is null
      and app.can_write_household(i.household_id)
      and ( app.can_read_record(i.household_id, i.visibility, 'investment', i.id,
                                app.owns_investment(i.id))
         or not exists (select 1 from investment_ownerships o
                        where o.investment_id = i.id) )
  )
$$;

create or replace function app.can_modify_account(p_account_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (
    select 1 from accounts a
    where a.id = p_account_id
      and a.deleted_at is null
      and app.can_write_household(a.household_id)
      and ( app.can_read_record(a.household_id, a.visibility, 'account', a.id,
                                app.holds_account(a.id))
         or not exists (select 1 from account_holders h
                        where h.account_id = a.id) )
  )
$$;

-- -----------------------------------------------------------------------------
-- households
-- -----------------------------------------------------------------------------
alter table households enable row level security;

create policy households_read on households for select
  using (app.is_household_member(id));

create policy households_update on households for update
  using (app.can_administer_household(id))
  with check (app.can_administer_household(id));

-- INSERT is intentionally absent: households are created only through
-- app.bootstrap_household(), which is atomic (household + member + membership).

-- -----------------------------------------------------------------------------
-- members — the roster. Every member can see WHO is in the household.
-- They still cannot see what those people own; that is the investments policy.
-- (docs/03 §5: "you never see another member's private count or values".)
-- -----------------------------------------------------------------------------
alter table members enable row level security;

create policy members_read on members for select
  using (app.is_household_member(household_id));

create policy members_insert on members for insert
  with check (app.can_administer_household(household_id));

create policy members_update on members for update
  using (app.can_administer_household(household_id)
         or user_id = app.current_user_id())      -- you may always edit yourself
  with check (app.can_administer_household(household_id)
         or user_id = app.current_user_id());

create policy members_delete on members for delete
  using (app.can_administer_household(household_id));

-- -----------------------------------------------------------------------------
-- household_memberships
-- -----------------------------------------------------------------------------
alter table household_memberships enable row level security;

create policy memberships_read on household_memberships for select
  using (app.is_household_member(household_id) or user_id = app.current_user_id());

create policy memberships_write on household_memberships for insert
  with check (app.can_administer_household(household_id));

create policy memberships_update on household_memberships for update
  using (app.can_administer_household(household_id))
  with check (app.can_administer_household(household_id));

create policy memberships_delete on household_memberships for delete
  using (app.can_administer_household(household_id) or user_id = app.current_user_id());

-- -----------------------------------------------------------------------------
-- Reference tables: global rows (household_id NULL) are readable by everyone;
-- a household's custom types/institutions stay inside that household.
-- -----------------------------------------------------------------------------
alter table asset_categories enable row level security;
create policy categories_read on asset_categories for select using (true);

alter table investment_types enable row level security;
create policy types_read on investment_types for select
  using (household_id is null or app.is_household_member(household_id));
create policy types_insert on investment_types for insert
  with check (household_id is not null and app.can_write_household(household_id));
create policy types_update on investment_types for update
  using (household_id is not null and app.can_write_household(household_id))
  with check (household_id is not null and app.can_write_household(household_id));

alter table institutions enable row level security;
create policy institutions_read on institutions for select
  using (household_id is null or app.is_household_member(household_id));
create policy institutions_insert on institutions for insert
  with check (household_id is not null and app.can_write_household(household_id));
create policy institutions_update on institutions for update
  using (household_id is not null and app.can_write_household(household_id))
  with check (household_id is not null and app.can_write_household(household_id));

alter table tags enable row level security;
create policy tags_all on tags for all
  using (app.is_household_member(household_id))
  with check (app.can_write_household(household_id));

alter table custom_fields enable row level security;
create policy custom_fields_read on custom_fields for select
  using (app.is_household_member(household_id));
create policy custom_fields_write on custom_fields for insert
  with check (app.can_write_household(household_id));
create policy custom_fields_update on custom_fields for update
  using (app.can_write_household(household_id))
  with check (app.can_write_household(household_id));
create policy custom_fields_delete on custom_fields for delete
  using (app.can_write_household(household_id));

-- -----------------------------------------------------------------------------
-- accounts
-- -----------------------------------------------------------------------------
alter table accounts enable row level security;

create policy accounts_read on accounts for select
  using (app.can_read_record(household_id, visibility, 'account', id,
                             app.holds_account(id)));

create policy accounts_insert on accounts for insert
  with check (app.can_write_household(household_id));

create policy accounts_update on accounts for update
  using (app.can_write_household(household_id)
         and app.can_read_record(household_id, visibility, 'account', id,
                                 app.holds_account(id)))
  with check (app.can_write_household(household_id));

create policy accounts_delete on accounts for delete
  using (app.can_write_household(household_id)
         and app.can_read_record(household_id, visibility, 'account', id,
                                 app.holds_account(id)));

-- Holder rows follow their account: the subquery is itself RLS-filtered.
alter table account_holders enable row level security;
create policy account_holders_read on account_holders for select
  using (exists (select 1 from accounts a where a.id = account_id));
create policy account_holders_write on account_holders for all
  using (app.can_modify_account(account_id))
  with check (app.can_modify_account(account_id));

-- -----------------------------------------------------------------------------
-- investments — the record that matters most.
-- -----------------------------------------------------------------------------
alter table investments enable row level security;

create policy investments_read on investments for select
  using (app.can_read_record(household_id, visibility, 'investment', id,
                             app.owns_investment(id)));

create policy investments_insert on investments for insert
  with check (app.can_write_household(household_id));

-- An editor may edit shared records and their own, but a record they cannot
-- READ is not editable either — the read predicate is part of the USING clause.
create policy investments_update on investments for update
  using (app.can_write_household(household_id)
         and app.can_read_record(household_id, visibility, 'investment', id,
                                 app.owns_investment(id)))
  with check (app.can_write_household(household_id));

create policy investments_delete on investments for delete
  using (app.can_write_household(household_id)
         and app.can_read_record(household_id, visibility, 'investment', id,
                                 app.owns_investment(id)));

-- -----------------------------------------------------------------------------
-- Investment children: readability cascades from the parent row, because the
-- `exists (select ... from investments)` subquery obeys the policy above.
-- This is why there is no side-channel: ownership, nominees, valuations and
-- tags are all invisible for an investment you cannot see.
-- -----------------------------------------------------------------------------
alter table investment_ownerships enable row level security;
create policy inv_own_read on investment_ownerships for select
  using (exists (select 1 from investments i where i.id = investment_id));
create policy inv_own_write on investment_ownerships for all
  using (app.can_modify_investment(investment_id))
  with check (app.can_modify_investment(investment_id));

alter table investment_nominees enable row level security;
create policy inv_nom_read on investment_nominees for select
  using (exists (select 1 from investments i where i.id = investment_id));
create policy inv_nom_write on investment_nominees for all
  using (app.can_modify_investment(investment_id))
  with check (app.can_modify_investment(investment_id));

alter table valuations enable row level security;
create policy valuations_read on valuations for select
  using (exists (select 1 from investments i where i.id = investment_id));
create policy valuations_write on valuations for all
  using (app.can_modify_investment(investment_id))
  with check (app.can_modify_investment(investment_id));

alter table investment_tags enable row level security;
create policy inv_tags_read on investment_tags for select
  using (exists (select 1 from investments i where i.id = investment_id));
create policy inv_tags_write on investment_tags for all
  using (app.can_modify_investment(investment_id))
  with check (app.can_modify_investment(investment_id));

-- -----------------------------------------------------------------------------
-- record_visibility_grants — you may see a grant if you are in the household
-- and either it names you, or you can already read the record it points at.
-- -----------------------------------------------------------------------------
alter table record_visibility_grants enable row level security;

create policy grants_read on record_visibility_grants for select
  using (app.is_household_member(household_id)
         and ( member_id = any (app.current_member_ids(household_id))
            or (record_type = 'investment'
                and exists (select 1 from investments i where i.id = record_id))
            or (record_type = 'account'
                and exists (select 1 from accounts a where a.id = record_id)) ));

-- SECURITY: this policy must check authority over the TARGET RECORD, not just
-- over the household. Checking only `can_write_household` would let any editor
-- insert a grant naming themselves against a colleague's Private investment and
-- then read it -- privilege escalation straight through the privacy model.
-- can_modify_investment/account refuse exactly that, because an existing record
-- you cannot read is not a record you may modify.
create policy grants_write on record_visibility_grants for all
  using ( app.can_write_household(household_id) and
          case record_type
            when 'investment' then app.can_modify_investment(record_id)
            when 'account'    then app.can_modify_account(record_id)
            else false            -- liability/goal/document arrive in later phases
          end )
  with check ( app.can_write_household(household_id) and
          case record_type
            when 'investment' then app.can_modify_investment(record_id)
            when 'account'    then app.can_modify_account(record_id)
            else false
          end );

-- -----------------------------------------------------------------------------
-- activity_log — append-only. No UPDATE or DELETE policy exists, so those are
-- denied for the application role by construction (docs/05 §9).
-- -----------------------------------------------------------------------------
alter table activity_log enable row level security;

create policy audit_read on activity_log for select
  using (actor_user_id = app.current_user_id()
         or (household_id is not null and app.can_administer_household(household_id)));

create policy audit_append on activity_log for insert
  with check (app.is_household_member(household_id) or household_id is null);

-- -----------------------------------------------------------------------------
-- Atomic household bootstrap.
--
-- Creating a household is a chicken-and-egg problem for RLS: you cannot insert
-- the membership that would authorise the insert. Rather than punching a hole
-- in the INSERT policies, this is the single, auditable SECURITY DEFINER entry
-- point. It refuses to run without an authenticated user.
-- -----------------------------------------------------------------------------
create or replace function app.bootstrap_household(
    p_name               text,
    p_default_visibility text,
    p_display_name       text
) returns table (household_id uuid, member_id uuid)
  language plpgsql security definer
  set search_path = public, app, pg_temp as $$
declare
  v_user uuid := app.current_user_id();
  v_household uuid;
  v_member uuid;
begin
  if v_user is null then
    raise exception 'bootstrap_household requires an authenticated user'
      using errcode = 'insufficient_privilege';
  end if;
  if p_default_visibility not in ('private','household') then
    raise exception 'invalid default_visibility: %', p_default_visibility
      using errcode = 'check_violation';
  end if;

  insert into households (name, default_visibility, created_by)
    values (p_name, p_default_visibility, v_user)
    returning id into v_household;

  insert into members (household_id, user_id, display_name, relationship)
    values (v_household, v_user, p_display_name, 'self')
    returning id into v_member;

  insert into household_memberships (household_id, user_id, role, status)
    values (v_household, v_user, 'owner', 'active');

  insert into activity_log (household_id, actor_user_id, action, entity_type, entity_id)
    values (v_household, v_user, 'household.create', 'household', v_household);

  return query select v_household, v_member;
end $$;
