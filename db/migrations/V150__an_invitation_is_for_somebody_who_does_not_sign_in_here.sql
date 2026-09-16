-- =============================================================================
-- An invitation is for somebody who does not sign in to the household yet.
--
-- Found by driving the real app (2026-09-16): an owner typed their own number
-- into "Invite Ravi", got a link, and accepting it handed back the member row
-- they already had, reported the invitation's role as though it were theirs, and
-- spent a single-use link on nothing. Ravi stayed unclaimed while the
-- household's list showed the invitation as used.
--
-- The branch below was written for "a user tapping the link again", but an
-- invitation that has already been accepted is refused three checks earlier
-- (invitation_used). What actually reached it was somebody who is already a
-- member redeeming a DIFFERENT, unused invitation — someone else's. Quietly
-- consuming it is the one outcome that helps nobody: the person it was meant for
-- can no longer use it.
--
-- So it is refused, and refused BEFORE anything is written: the raise rolls the
-- statement back, `accepted_at` stays null, and the link still works for the
-- person it was addressed to. Nothing else in the function changes.
-- =============================================================================

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

  -- Already one of this household's people: there is nothing an invitation can
  -- give them, and spending it would take it from whoever it was for.
  if exists (select 1 from household_memberships hm
             where hm.household_id = inv.household_id
               and hm.user_id = v_user and hm.status = 'active') then
    raise exception 'already_a_member' using errcode = 'invalid_parameter_value';
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
