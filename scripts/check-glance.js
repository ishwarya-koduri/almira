/* =============================================================================
   Home, Holdings and Household's rules without a browser: the trend's start,
   readiness movement in words, one needs-doing line per row, member colours
   and initials (static/app/glance.js), and the last known view (cache.js).

       /System/Library/Frameworks/JavaScriptCore.framework/Versions/A/Helpers/jsc \
         -m scripts/check-glance.js
       # or: node scripts/check-glance.js  (as an ES module)
   ============================================================================= */

import {
  trendPoints, movementLine, firstNeedByRecord, memberToneIndex, initials, roleKey,
} from "../backend/src/main/resources/static/app/glance.js";
import {
  cacheable, setOwner, remember, peek, forget, reset,
} from "../backend/src/main/resources/static/app/cache.js";

const log = typeof print === "function" ? print : console.log;
let failures = 0;
function expect(label, actual, wanted) {
  const a = JSON.stringify(actual);
  const w = JSON.stringify(wanted);
  if (a === w) log(`  ok   ${label}`);
  else { failures += 1; log(`  FAIL ${label}\n         got    ${a}\n         wanted ${w}`); }
}

/* --- the trend starts when recording did (P-15) ---------------------------- */

const trend = {
  historyBeginsAt: "2026-06-12",
  points: ["2026-04-30", "2026-05-31", "2026-06-30", "2026-07-31", "2026-08-31"]
    .map((date, i) => ({ date, netWorth: i * 100, netWorthFormatted: `₹${i * 100}` })),
};
expect("months before anything was recorded are not drawn",
  trendPoints(trend).map((p) => p.date), ["2026-06-30", "2026-07-31", "2026-08-31"]);
expect("with no start date every point is drawn", trendPoints({ points: trend.points }).length, 5);
expect("no trend is no points", trendPoints(null), []);

/* --- readiness says what moved, never a lone number (X-33) ----------------- */

const checks = [
  { code: "nominee", done: 2, applicable: 3 }, { code: "document", done: 1, applicable: 3 },
  { code: "location", done: 0, applicable: 1 }, { code: "trusted_contact", done: 1, applicable: 1 },
];
expect("three things safer", movementLine({ checks, movement: { saferCount: 3, fewerDone: 0 } }),
  { key: "ready.movement.many", params: { count: 3 } });
expect("one thing safer is singular", movementLine({ checks, movement: { saferCount: 1, fewerDone: 0 } }).key,
  "ready.movement.one");
expect("fewer done is said plainly, not celebrated", movementLine({ checks, movement: { saferCount: 0, fewerDone: 2 } }),
  { key: "ready.movement.fewer", params: { count: 2 } });
expect("no change says how many are done", movementLine({ checks, movement: { saferCount: 0, fewerDone: 0 } }),
  { key: "ready.movement.same", params: { done: 4, applicable: 8 } });
expect("with no month to compare, how many are done", movementLine({ checks }),
  { key: "ready.movement.none", params: { done: 4, applicable: 8 } });

/* --- one needs-doing line per row, the most urgent (X-54) ------------------- */

const needs = firstNeedByRecord({ items: [
  { recordId: "a", kind: "maturity" }, { recordId: "b", kind: "still_true" },
  { recordId: "a", kind: "no_nominee" }, { recordId: "b", kind: "no_document" },
] });
expect("the first item for a record wins", [needs.get("a").kind, needs.get("b").kind], ["maturity", "still_true"]);
expect("no inbox is no needs", firstNeedByRecord(null).size, 0);

/* --- people (X-54, X-56) ----------------------------------------------------- */

const id = "5b0e2a9c-1f77-4d35-9f0a-3c2d1e0b9a88";
expect("a member's colour is stable", memberToneIndex(id), memberToneIndex(String(id)));
expect("and within the six tones", [1, 2, 3, 4, 5, 6].includes(memberToneIndex(id)), true);
const spread = new Set(Array.from({ length: 60 }, (_, i) => memberToneIndex(`member-${i}`)));
expect("different people spread across the tones", spread.size, 6);
expect("two words, two initials", initials("ravi koduri"), "RK");
expect("one word, one initial", initials("Ishwarya"), "I");
expect("extra spaces are not initials", initials("  Aarav   Kumar  Koduri "), "AK");
expect("no name is a question mark, not blank", initials(""), "?");
expect("a Telugu name keeps its whole first letter", initials("శ్రీ రాము").length >= 2, true);
expect("a role in plain words", roleKey({ role: "editor", isManaged: false }), "family.role.editor");
expect("someone kept by others", roleKey({ role: null, isManaged: true }), "family.role.managed");

/* --- the last known view (X-38) ---------------------------------------------- */

const dash = "/api/v1/households/h1/dashboard?scope=household";
expect("the dashboard is kept", cacheable(dash), true);
expect("the holdings list is kept, with a search", cacheable("/api/v1/households/h1/investments?q=sbi"), true);
expect("sealed values are never kept", cacheable("/api/v1/households/h1/e2e/values?recordType=investment&recordId=x"), false);
expect("where and who is never kept", cacheable("/api/v1/households/h1/where-and-who"), false);
expect("an account (and its number) is never kept", cacheable("/api/v1/households/h1/accounts/a1"), false);
expect("a member preview is never kept", cacheable("/api/v1/households/h1/members/m1/preview"), false);

reset();
remember(dash, { netWorth: 1 });
expect("nothing is kept before anyone owns it", peek(dash), undefined);
setOwner("user-1");
remember(dash, { netWorth: 1 });
expect("the owner reads their own last view", peek(dash), { netWorth: 1 });
setOwner("user-1");
expect("the same person keeps it", peek(dash), { netWorth: 1 });
setOwner("user-2");
expect("someone else signing in drops it before it can be drawn", peek(dash), undefined);
remember(dash, { netWorth: 2 });
forget();
expect("a write forgets every view", peek(dash), undefined);
remember(dash, { netWorth: 3 });
reset();
expect("signing out forgets and belongs to nobody", peek(dash), undefined);
remember(dash, { netWorth: 4 });
expect("and keeps nothing until someone signs in", peek(dash), undefined);

if (failures > 0) {
  log(`\n${failures} failing`);
  throw new Error(`${failures} failing`);
}
log("\nall passing");
