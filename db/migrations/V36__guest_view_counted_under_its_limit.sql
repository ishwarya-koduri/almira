-- =============================================================================
-- V36 · A guest view is counted only while the link still allows it.
-- Refs: V20, docs/known-issues.md ("A guard runs before the action it guards")
--
-- ShareService.open read the link, checked expiry, revocation and the view
-- limit on that copy, and then called app.record_guest_view, which counted the
-- view unconditionally. Two opens of a single-view link could both pass the
-- check before either counted, and both were served; a revocation committed
-- between the read and the count did not stop an open already past the check.
--
-- The count now carries the check. The update comes first and only matches a
-- link that is unrevoked, unexpired and under its limit; its row lock queues a
-- concurrent open, which re-reads the row and finds the limit reached. When it
-- matches nothing, nothing is recorded and the function returns null — which
-- ShareService answers exactly as it answers an expired link.
-- =============================================================================

create or replace function app.record_guest_view(
    p_share_id uuid, p_ip_hash text, p_user_agent text)
  returns int language plpgsql security definer
  set search_path = public, app, pg_temp as $$
declare v_count int;
begin
  update guest_shares set view_count = view_count + 1
    where id = p_share_id
      and revoked_at is null
      and expires_at > now()
      and (max_views is null or view_count < max_views)
    returning view_count into v_count;
  if v_count is null then
    return null;
  end if;
  insert into guest_share_views (share_id, ip_hash, user_agent)
    values (p_share_id, p_ip_hash, p_user_agent);
  return v_count;
end $$;
