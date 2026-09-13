/* =============================================================================
   The "seal it, and clear the unsealed note" rule (docs/20 §1), without a browser.

   The button used to seal only when the sealed slot was empty and then clear
   the plain note regardless. On a shared record a co-owner had sealed, or a
   value that would not open, that sealed nothing and deleted the only copy the
   person could read. The rule now lives in where-legacy.js; this asserts it.

       /System/Library/Frameworks/JavaScriptCore.framework/Versions/A/Helpers/jsc \
         -m scripts/check-where-legacy.js
       # or: node scripts/check-where-legacy.js  (as an ES module)
   ============================================================================= */

import { legacyMoveAction } from "../backend/src/main/resources/static/app/where-legacy.js";

const log = typeof print === "function" ? print : console.log;
let failures = 0;
function expect(label, actual, wanted) {
  if (actual === wanted) log(`  ok   ${label}`);
  else { failures += 1; log(`  FAIL ${label} (got ${actual}, wanted ${wanted})`); }
}

expect("nothing sealed yet: seal the note, then clear it", legacyMoveAction("empty"), "seal-then-clear");
expect("sealed by me and open: the note can go", legacyMoveAction("open"), "clear");
expect("sealed by a co-owner: keep the note, offer nothing", legacyMoveAction("theirs"), null);
expect("sealed but will not open: keep the note, offer nothing", legacyMoveAction("unreadable"), null);
expect("locked: offer nothing", legacyMoveAction("locked"), null);
expect("an unknown state: offer nothing", legacyMoveAction(undefined), null);

if (failures > 0) throw new Error(`${failures} check(s) failed`);
log("where-legacy: all checks pass");
