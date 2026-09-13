/* =============================================================================
   What the "seal it, and clear the unsealed note" button may do (docs/20 §1).

   Kept apart from where.js, with no imports, so scripts/check-where-legacy.js
   can assert it without a browser.

   Clearing the unsealed note is only safe when this person can still read the
   location afterwards:

     · empty      — nothing sealed yet: seal the note, and clear it only once
                    the seal has been stored;
     · open       — they sealed a location themselves, later: that is their
                    current word, so the note can go;
     · theirs     — a co-owner sealed it, and only they can open it;
     · unreadable — sealed, but it does not open on this device;
     · locked     — nothing can be sealed or checked.

   In the last three, clearing would delete the only copy this person (and the
   family, through the handbook) can read, and seal nothing in its place. So the
   button is not offered at all.
   ============================================================================= */

export function legacyMoveAction(slotState) {
  switch (slotState) {
    case "empty": return "seal-then-clear";
    case "open": return "clear";
    default: return null;
  }
}
