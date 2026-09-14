-- =============================================================================
-- V81 · "What Ravi sees": whether another member would see a record you see.
-- Refs: docs/05 §3, docs/03 §5, catch-up plan X-56
--
-- The Family screen previews Home through another member's eyes. The preview
-- is built from ONE side only: records the viewer can already read by ordinary
-- sight. For each, this answers a single yes or no — would that member read it
-- too? — so the preview can never carry the other member's private records to
-- the viewer (they were never read), nor the viewer's private records into the
-- preview (the answer for those is no).
--
-- Why a function. The read predicate for another person needs their ownership
-- and their visibility grants. Ownerships are readable through the record, but
-- grants on a liability are readable only by the member they name (V4
-- grants_read), so the question cannot be asked through the viewer's RLS alone.
-- Asking it as the owner is safe only because of the guard below: the function
-- answers false for any record the CALLER cannot read, so it cannot be used to
-- probe what someone else holds. What it can say is already on the record's
-- "Who can see it" line for anyone who reads the record.
--
-- The rule it mirrors is app.can_read_record (V23) for the other member's user:
-- an active membership; an advisor sees explicit grants only; everyone else
-- sees what they hold, what is shared with the household, and what is scoped
-- to them. An emergency unlock is not previewed: that is sight handed over for
-- a while, not the everyday view. A member with no sign-in sees nothing.
-- =============================================================================

create or replace function app.member_would_see(
    p_member_id   uuid,
    p_record_type text,
    p_record_id   uuid
) returns boolean
  language plpgsql stable security definer
  set search_path = public, app, pg_temp as $$
declare
  v_household  uuid;
  v_user       uuid;
  v_role       text;
  v_record_hh  uuid;
  v_visibility text;
  v_holds      boolean;
  v_granted    boolean;
begin
  select m.household_id, m.user_id into v_household, v_user
    from members m where m.id = p_member_id and m.deleted_at is null;
  -- The caller must be in that household, and never as a guest link.
  if v_household is null or not app.is_household_member(v_household)
     or app.guest_share_id() is not null then
    return false;
  end if;
  if v_user is null then
    return false;            -- no sign-in, no sight
  end if;

  select hm.role into v_role from household_memberships hm
   where hm.household_id = v_household and hm.user_id = v_user and hm.status = 'active';
  if v_role is null then
    return false;
  end if;

  case p_record_type
    when 'investment' then
      select i.household_id, i.visibility into v_record_hh, v_visibility
        from investments i where i.id = p_record_id and i.deleted_at is null;
      -- The guard: only a record the caller reads by ordinary sight.
      if v_record_hh is distinct from v_household
         or not app.can_read_record(v_record_hh, v_visibility, 'investment', p_record_id,
                                    app.owns_investment(p_record_id)) then
        return false;
      end if;
      select exists (select 1 from investment_ownerships o join members m on m.id = o.member_id
                      where o.investment_id = p_record_id and m.user_id = v_user and m.deleted_at is null)
        into v_holds;
    when 'liability' then
      select l.household_id, l.visibility into v_record_hh, v_visibility
        from liabilities l where l.id = p_record_id and l.deleted_at is null;
      if v_record_hh is distinct from v_household
         or not app.can_read_record(v_record_hh, v_visibility, 'liability', p_record_id,
                                    app.owes_liability(p_record_id)) then
        return false;
      end if;
      select exists (select 1 from liability_holders h join members m on m.id = h.member_id
                      where h.liability_id = p_record_id and m.user_id = v_user and m.deleted_at is null)
        into v_holds;
    else
      return false;
  end case;

  select exists (select 1 from record_visibility_grants g join members m on m.id = g.member_id
                  where g.record_type = p_record_type and g.record_id = p_record_id
                    and m.user_id = v_user and m.deleted_at is null)
    into v_granted;

  if v_role = 'advisor' then
    return v_granted;
  end if;
  return v_holds or v_visibility = 'household' or (v_visibility = 'scoped' and v_granted);
end $$;

comment on function app.member_would_see(uuid, text, uuid) is
  'Would this member read this record by ordinary sight? False for any record the caller cannot read.';

revoke all on function app.member_would_see(uuid, text, uuid) from public;
do $$
begin
  if exists (select 1 from pg_roles where rolname = 'almira_app') then
    execute 'grant execute on function app.member_would_see(uuid, text, uuid) to almira_app';
  end if;
end $$;
