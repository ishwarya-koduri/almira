-- =============================================================================
-- V7 · Accepting an invitation.
--
-- Same bootstrap problem as creating a household: joining requires inserting a
-- household_memberships row, but that table's INSERT policy demands you already
-- be an admin of the household you are trying to join. Rather than weaken the
-- policy, acceptance goes through one SECURITY DEFINER function.
--
-- It also carries the merge rule from docs/07 §1: when the invitation names a
-- managed member (a child, a parent whose assets someone else has been
-- recording), the accepting user CLAIMS that row rather than creating a second
-- one. One real person is represented once per household -- otherwise their
-- holdings would silently split across two identities.
-- =============================================================================

-- The OUT columns are prefixed. Unprefixed names (household_id, member_id,
-- role) would shadow the identically-named columns of household_memberships in
-- the INSERT below, and PL/pgSQL rejects the ambiguity at runtime rather than
-- at creation -- so it surfaces as a failed acceptance, not a failed migration.
create or replace function app.accept_invitation(p_token_hash text)
  returns table (out_household_id uuid, out_member_id uuid, out_role text)
  language plpgsql security definer
  set search_path = public, app, pg_temp as $$
declare
  v_user   uuid := app.current_user_id();
  v_member uuid;
  inv      record;
begin
  if v_user is null then
    raise exception 'authentication required' using errcode = 'insufficient_privilege';
  end if;

  select * into inv from invitations where token_hash = p_token_hash;
  if not found then
    raise exception 'invitation_not_found' using errcode = 'no_data_found';
  end if;
  if inv.accepted_at is not null then
    raise exception 'invitation_used' using errcode = 'invalid_parameter_value';
  end if;
  if inv.revoked_at is not null then
    raise exception 'invitation_revoked' using errcode = 'invalid_parameter_value';
  end if;
  if inv.expires_at < now() then
    raise exception 'invitation_expired' using errcode = 'invalid_parameter_value';
  end if;

  -- Accepting twice is not an error; it is a user tapping the link again.
  if exists (select 1 from household_memberships hm
             where hm.household_id = inv.household_id
               and hm.user_id = v_user and hm.status = 'active') then
    select m.id into v_member from members m
      where m.household_id = inv.household_id and m.user_id = v_user
        and m.deleted_at is null
      limit 1;
    update invitations set accepted_at = now() where id = inv.id;
    return query select inv.household_id, v_member, inv.role;
    return;
  end if;

  -- Claim the managed member row, if the invitation named an unclaimed one.
  if inv.member_id is not null then
    update members set user_id = v_user
      where id = inv.member_id and user_id is null and deleted_at is null
      returning id into v_member;
  end if;

  if v_member is null then
    insert into members (household_id, user_id, display_name, relationship)
      select inv.household_id, v_user, coalesce(nullif(u.full_name, ''), 'Member'), 'other'
      from users u where u.id = v_user
      returning id into v_member;
  end if;

  insert into household_memberships (household_id, user_id, role, status)
    values (inv.household_id, v_user, inv.role, 'active')
    on conflict (household_id, user_id)
      do update set status = 'active', role = excluded.role;

  update invitations set accepted_at = now() where id = inv.id;

  insert into activity_log (household_id, actor_user_id, action, entity_type, entity_id)
    values (inv.household_id, v_user, 'invitation.accept', 'member', v_member);

  return query select inv.household_id, v_member, inv.role;
end $$;
