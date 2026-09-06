-- =============================================================================
-- V21 · A guest session cannot write. In the database, not only in the request.
--
-- V20 clamped every READ policy to the records a link names, and the
-- application opens a guest transaction as read-only. Both are true, and both
-- were insufficient on their own: a guest session borrows the sharer's
-- identity, so the WRITE policies — which never heard of guest scope — would
-- have let it edit the very record it was allowed to see. The SQL privacy suite
-- caught it by setting the scope directly, without the application's read-only
-- transaction, which is exactly the shape of the bug that survives a refactor.
--
-- The fix is placed where it cannot be forgotten: the two capability functions
-- every write policy already routes through, plus the handful of policies that
-- do not. A new table that follows the existing pattern inherits this.
-- =============================================================================

create or replace function app.can_write_household(p_household_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select app.guest_share_id() is null
     and exists (
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
  select app.guest_share_id() is null
     and exists (
    select 1 from household_memberships hm
    where hm.household_id = p_household_id
      and hm.user_id = app.current_user_id()
      and hm.status = 'active'
      and hm.role in ('owner','admin')
  )
$$;

-- The policies that check ownership directly rather than capability.
alter policy estate_roles_write on estate_roles
  using (app.guest_share_id() is null and app.owns_estate_document(estate_document_id))
  with check (app.guest_share_id() is null and app.owns_estate_document(estate_document_id));

alter policy estate_beneficiaries_write on estate_beneficiaries
  using (app.guest_share_id() is null and app.owns_estate_document(estate_document_id))
  with check (app.guest_share_id() is null and app.owns_estate_document(estate_document_id));

alter policy guest_shares_update on guest_shares
  using (app.guest_share_id() is null and created_by = app.current_user_id())
  with check (app.guest_share_id() is null and created_by = app.current_user_id());

alter policy guest_shares_delete on guest_shares
  using (app.guest_share_id() is null and created_by = app.current_user_id());

alter policy guest_share_items_all on guest_share_items
  using (app.guest_share_id() is null
         and exists (select 1 from guest_shares s where s.id = share_id))
  with check (app.guest_share_id() is null
              and exists (select 1 from guest_shares s where s.id = share_id));

alter policy emergency_contacts_write on emergency_contacts
  using (app.guest_share_id() is null
         and member_id = any(app.current_member_ids(household_id)))
  with check (app.guest_share_id() is null
              and member_id = any(app.current_member_ids(household_id))
              and app.can_write_household(household_id));

alter policy emergency_requests_insert on emergency_requests
  with check (app.guest_share_id() is null
              and app.is_household_member(household_id)
              and requested_by = app.current_user_id()
              and exists (
                select 1 from emergency_contacts c
                where c.household_id = emergency_requests.household_id
                  and c.member_id = emergency_requests.subject_member_id
                  and c.trusted_member_id = any(app.current_member_ids(household_id))));

alter policy emergency_requests_update on emergency_requests
  using (app.guest_share_id() is null
         and app.is_household_member(household_id)
         and (requested_by = app.current_user_id()
           or subject_member_id = any(app.current_member_ids(household_id))))
  with check (app.guest_share_id() is null and app.is_household_member(household_id));

-- Investment ownership rows are written through a policy that checks the
-- investment rather than the household, so it needs saying here too.
alter policy inv_own_write on investment_ownerships
  using (app.guest_share_id() is null and app.can_modify_investment(investment_id))
  with check (app.guest_share_id() is null and app.can_modify_investment(investment_id));
