/* =============================================================================
   The Reports completeness card's number, without a browser (known-issues 19).

       /System/Library/Frameworks/JavaScriptCore.framework/Versions/A/Helpers/jsc \
         -m scripts/check-completeness.js
       # or: node scripts/check-completeness.js  (as an ES module)
   ============================================================================= */

import { completenessPercent } from "../backend/src/main/resources/static/app/completeness.js";

const log = typeof print === "function" ? print : console.log;
let failures = 0;
function expect(label, actual, wanted) {
  const a = JSON.stringify(actual);
  const w = JSON.stringify(wanted);
  if (a === w) log(`  ok   ${label}`);
  else { failures += 1; log(`  FAIL ${label}\n         got    ${a}\n         wanted ${w}`); }
}

// The shapes CompletenessService answers with.
expect("nothing recorded: no number, not 0% and not 100%",
  completenessPercent({ score: 0, scoreEarned: false, scoreExplanation: "Nothing is recorded that you can see yet, so there is nothing to score." }),
  null);
expect("an earned score is shown as the server rounded it",
  completenessPercent({ score: 99, scoreEarned: true }), "99%");
expect("everything done is 100%",
  completenessPercent({ score: 100, scoreEarned: true }), "100%");
expect("an earned 0 is still a number",
  completenessPercent({ score: 0, scoreEarned: true }), "0%");
expect("a server from before scoreEarned is shown as it was",
  completenessPercent({ score: 72 }), "72%");
expect("no score at all is no number",
  completenessPercent({}), null);

if (failures > 0) {
  log(`\n${failures} failing`);
  throw new Error(`${failures} failing`);
}
log("\nall passing");
