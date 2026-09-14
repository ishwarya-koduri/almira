/* =============================================================================
   A session that ends without a reload drops the keys held in memory, without
   a browser (docs/12 §10.5).

       /System/Library/Frameworks/JavaScriptCore.framework/Versions/A/Helpers/jsc \
         -m scripts/check-session-end.js

   The server ending a session (sign out everywhere, a refused refresh, a 401)
   calls auth.clear() and mounts the sign-in screen in the same page. The next
   person to sign in there must not find the last one's content key, or a key
   opened with someone else's recovery sheet, still unlocked. The keys need
   WebCrypto to exist, so what is asserted is that e2e.lock() is reached.
   ============================================================================= */

import "./lib/jsc-page-globals.js";
import { auth } from "../backend/src/main/resources/static/app/api.js";
import { e2e } from "../backend/src/main/resources/static/app/e2e.js";
import { sessionBelongsTo } from "../backend/src/main/resources/static/app/session-end.js";

const log = typeof print === "function" ? print : console.log;
let failures = 0;
function expect(label, actual, wanted) {
  const a = JSON.stringify(actual);
  const w = JSON.stringify(wanted);
  if (a === w) log(`  ok   ${label}`);
  else { failures += 1; log(`  FAIL ${label}\n         got    ${a}\n         wanted ${w}`); }
}

const realLock = e2e.lock;
let locks = 0;
e2e.lock = () => { locks += 1; realLock(); };

log("auth.clear() — the path a refused refresh and a 401 take");
localStorage.setItem("almira.refresh", "a-refresh-token");
locks = 0;
await auth.clear();
expect("the keys held in memory are dropped", locks, 1);
expect("and the session is gone", auth.isSignedIn, false);

log("\nThe person the session belongs to");
locks = 0;
sessionBelongsTo("member-a");
const afterFirst = locks;
sessionBelongsTo("member-a");
expect("the same person again keeps what they unlocked", locks, afterFirst);
sessionBelongsTo("member-b");
expect("a different person drops it", locks, afterFirst + 1);
auth.clear();
locks = 0;
sessionBelongsTo("member-b");
expect("after sign-out, even the same person starts locked", locks, 1);

e2e.lock = realLock;
if (failures) { log(`\n${failures} failed`); throw new Error(`${failures} check(s) failed`); }
log("\nall passed");
