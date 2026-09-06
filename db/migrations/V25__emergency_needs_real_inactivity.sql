-- =============================================================================
-- V25 · The unlock waits for silence, not only for the clock.
-- Refs: docs/05 §6 ("time-delayed inactivity unlock"), docs/10 Phase 3
--
-- V20 built the delay and the veto: someone named by the owner asks, and nothing
-- opens for a fortnight the owner chose. That is most of the safeguard, and it
-- was missing the other half.
--
-- The veto protects an owner who *notices*. The point of an inactivity unlock is
-- the owner who cannot — and its mirror image is the owner who is perfectly
-- fine and simply did not see the notification while travelling. So the window
-- now opens only if the person it concerns has genuinely not used Almira since
-- the request was made. Signing in is the clearest possible statement that
-- someone is reachable, and it stops the clock without them having to
-- understand what a veto is.
--
-- Deliberately not "active in the last N days": the question is not whether they
-- are a frequent user, it is whether they have been present *since somebody
-- asked about them*.
-- =============================================================================

create or replace function app.emergency_reveals(p_household_id uuid, p_in_continuity boolean)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select coalesce(p_in_continuity, false) and exists (
    select 1
    from emergency_requests r
    join members m on m.id = r.subject_member_id
    where r.household_id = p_household_id
      and r.requested_by = app.current_user_id()
      and r.vetoed_at is null
      and r.revoked_at is null
      and now() >= r.unlock_at
      and now() <  r.access_expires_at
      -- Silence since the request. A session used after it is the person
      -- saying, in the plainest way available, that they are here.
      and not exists (
        select 1 from user_sessions s
        where s.user_id = m.user_id
          and s.last_used_at > r.requested_at
      )
  )
$$;
