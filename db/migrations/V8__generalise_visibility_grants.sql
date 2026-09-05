-- =============================================================================
-- V8 · One rule for who may share a record, for every record type.
--
-- WHY: V4 authorised writes to record_visibility_grants on household capability
-- alone, which let any editor insert a grant naming themselves against a
-- colleague's Private record and then read it. That was fixed for investments
-- and accounts by checking authority over the target record — but the fix lived
-- in a CASE inside one policy, so liabilities, goals and documents would each
-- have had to re-earn it as they arrived.
--
-- The rule, stated once: **only a member who holds a record may grant
-- visibility on it.** Not an admin, not an editor, not the household owner —
-- sharing what is not yours is not a capability anyone should have.
--
-- Adding a grantable type now means extending ONE function. Forgetting to
-- raises an exception at the moment someone tries to grant, rather than
-- silently defaulting open (or defaulting shut and looking like a bug
-- elsewhere).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- The holder list for any grantable record.
--
-- Guardians count: investment_ownerships.holder_type includes 'guardian', so a
-- parent managing a minor's account is on the list and can share it. That is
-- deliberate — a child's assets have to be shareable by whoever manages them.
-- -----------------------------------------------------------------------------
create or replace function app.record_holder_member_ids(p_record_type text, p_record_id uuid)
  returns uuid[] language plpgsql stable security definer
  set search_path = public, app, pg_temp as $$
begin
  case p_record_type
    when 'investment' then
      return (select coalesce(array_agg(o.member_id), '{}')
              from investment_ownerships o where o.investment_id = p_record_id);
    when 'account' then
      return (select coalesce(array_agg(h.member_id), '{}')
              from account_holders h where h.account_id = p_record_id);
    else
      -- Loud on purpose. A new grantable type that reaches production without a
      -- branch here fails at the grant, not quietly at the read.
      raise exception 'record type % has no holder rule; extend app.record_holder_member_ids',
        p_record_type using errcode = 'feature_not_supported';
  end case;
end $$;

-- -----------------------------------------------------------------------------
-- Who created the record — used only for the creation window below.
-- -----------------------------------------------------------------------------
create or replace function app.record_created_by(p_record_type text, p_record_id uuid)
  returns uuid language plpgsql stable security definer
  set search_path = public, app, pg_temp as $$
begin
  case p_record_type
    when 'investment' then
      return (select i.created_by from investments i where i.id = p_record_id);
    when 'account' then
      return (select a.created_by from accounts a where a.id = p_record_id);
    else
      raise exception 'record type % has no creator rule; extend app.record_created_by',
        p_record_type using errcode = 'feature_not_supported';
  end case;
end $$;

-- -----------------------------------------------------------------------------
-- The single authorisation rule.
--
-- Two ways to qualify:
--   1. You are on the record's holder list.
--   2. The record has no holders yet AND you created it — the few statements
--      between inserting a record and attaching its first owner. That window is
--      not reachable by anyone else: the deferred share-sum constraint means a
--      COMMITTED investment always has holders, so an ownerless row only exists
--      inside the uncommitted transaction that is creating it. Requiring
--      created_by as well closes it even for record types that lack such a
--      constraint.
-- -----------------------------------------------------------------------------
create or replace function app.can_grant_visibility(p_record_type text, p_record_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select
    exists (
      select 1 from members m
      where m.user_id = app.current_user_id()
        and m.deleted_at is null
        and m.id = any (app.record_holder_member_ids(p_record_type, p_record_id))
    )
    or (
      cardinality(app.record_holder_member_ids(p_record_type, p_record_id)) = 0
      and app.record_created_by(p_record_type, p_record_id) = app.current_user_id()
    )
$$;

-- -----------------------------------------------------------------------------
-- The policy now states the rule once, for every type, present and future.
-- -----------------------------------------------------------------------------
drop policy if exists grants_write on record_visibility_grants;

create policy grants_write on record_visibility_grants for all
  using (app.is_household_member(household_id)
         and app.can_grant_visibility(record_type, record_id))
  with check (app.is_household_member(household_id)
              and app.can_grant_visibility(record_type, record_id));

-- -----------------------------------------------------------------------------
-- Tighten the same creation window on the record tables themselves.
--
-- V4 allowed writes to a holder-less record by any household editor. In
-- practice the deferred constraint made that unreachable for investments, but
-- accounts have no such constraint, so an account committed without holders
-- would have been editable by the whole household. Requiring created_by makes
-- the window belong to one person: whoever is mid-way through creating it.
-- -----------------------------------------------------------------------------
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
         or ( i.created_by = app.current_user_id()
              and not exists (select 1 from investment_ownerships o
                              where o.investment_id = i.id) ) )
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
         or ( a.created_by = app.current_user_id()
              and not exists (select 1 from account_holders h
                              where h.account_id = a.id) ) )
  )
$$;
