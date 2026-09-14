/* =============================================================================
   What a session holds only in memory, and the one place that lets it go.

   The zero-knowledge keys (e2e.js) live in module state, not on disk, so a
   reload drops them. A session can end without a reload, though: the server
   refuses the refresh after "sign out everywhere", a revoked or expired
   session, or a 401 from /me. The sign-in screen then mounts in the same page,
   and the next person to sign in on that tab must not inherit the last one's
   content key, or a key they opened with someone's recovery sheet.

   No imports, so api.js can call it without importing e2e.js (which imports
   api.js), and scripts/check-session-end.js can load it without a browser.
   ============================================================================= */

const holders = new Set();
let owner = null;

/** [drop] is called whenever the session ends or changes hands. */
export function onSessionEnd(drop) {
  holders.add(drop);
}

/** The session ended: every holder lets go, and the page belongs to nobody. */
export function endSession() {
  owner = null;
  for (const drop of holders) {
    try { drop(); } catch { /* the others still let go */ }
  }
}

/** Whose session this is. A different person drops what the last one held. */
export function sessionBelongsTo(userId) {
  const next = userId || null;
  if (next !== owner) endSession();
  owner = next;
}
