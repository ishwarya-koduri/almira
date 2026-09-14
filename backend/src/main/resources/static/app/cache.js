/* =============================================================================
   The last known view (X-38).

   Coming back to a tab should show what was there a moment ago, at once, and
   refresh it quietly — not a second of grey placeholders on every visit. So the
   answers to a short list of reads are kept here and a screen draws from them
   first.

   What keeps this safe, stated plainly:

   · **Memory only.** Nothing is written to localStorage, sessionStorage,
     IndexedDB or the service worker's cache; a reload or a closed tab starts
     empty. A family's balance sheet does not sit on a shared laptop's disk
     (sw.js makes the same promise for the network layer).
   · **Per person.** Every entry belongs to the signed-in user it was fetched
     for. When a different person signs in, or anyone signs out, it is all
     dropped before anything can be drawn from it.
   · **Never sealed plaintext.** Only the paths below are kept, and none of them
     carries a sealed value: those are decrypted in the browser from the e2e
     endpoints, which are not on the list, and the result is never stored here.
     Account numbers are revealed by a POST, and a POST is never kept.
   · **Any change clears it.** A successful write of any kind forgets every
     entry, so a screen never redraws an answer from before that change.
   ============================================================================= */

/** Reads whose last answer may be shown while a fresh one is fetched. */
const KEEP = [
  /^\/api\/v1\/households\/[\w-]+\/dashboard\?/,
  /^\/api\/v1\/households\/[\w-]+\/review$/,
  /^\/api\/v1\/households\/[\w-]+\/reports\/net-worth-trend\?/,
  /^\/api\/v1\/households\/[\w-]+\/investments(\?[^/]*)?$/,
  /^\/api\/v1\/households\/[\w-]+\/investments\/[\w-]+\/valuations$/,
  /^\/api\/v1\/households\/[\w-]+\/continuity\/readiness$/,
  /^\/api\/v1\/households\/[\w-]+\/still-true$/,
];

const entries = new Map();   // path -> { data, at }
let owner = null;

export function cacheable(path) {
  return KEEP.some((pattern) => pattern.test(path));
}

/**
 * Whose entries these are. A different person drops everything first, so one
 * person's figures can never be drawn for another on the same browser.
 */
export function setOwner(userId) {
  if (userId !== owner) forget();
  owner = userId || null;
}

export function remember(path, data) {
  if (!owner || !cacheable(path)) return;
  entries.set(path, { data, at: Date.now() });
}

/** The last answer for this path, or undefined. */
export function peek(path) {
  if (!owner) return undefined;
  return entries.get(path)?.data;
}

/** Everything, now: after a write, and on sign-out. */
export function forget() {
  entries.clear();
}

/** On sign-out: forget, and belong to nobody. */
export function reset() {
  forget();
  owner = null;
}
