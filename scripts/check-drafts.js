/* =============================================================================
   Drafts and the offline queue keep their promises, without a browser (X-83).

       /System/Library/Frameworks/JavaScriptCore.framework/Versions/A/Helpers/jsc \
         -m scripts/check-drafts.js
       # or: node scripts/check-drafts.js  (as an ES module)
   ============================================================================= */

import { createDrafts, isDraftable, clearAllDrafts } from "../backend/src/main/resources/static/app/drafts.js";

const log = typeof print === "function" ? print : console.log;
let failures = 0;
function expect(label, actual, wanted) {
  const a = JSON.stringify(actual);
  const w = JSON.stringify(wanted);
  if (a === w) log(`  ok   ${label}`);
  else { failures += 1; log(`  FAIL ${label}\n         got    ${a}\n         wanted ${w}`); }
}

/** localStorage, as far as drafts.js uses it. */
function memoryStorage() {
  const map = new Map();
  return {
    getItem: (key) => (map.has(key) ? map.get(key) : null),
    setItem: (key, value) => map.set(key, String(value)),
    removeItem: (key) => map.delete(key),
    key: (i) => [...map.keys()][i] ?? null,
    get length() { return map.size; },
    dump: () => [...map.values()].join("\n"),
    keys: () => [...map.keys()],
  };
}

let clock = 1000;
const now = () => (clock += 1);

/* --- what a draft will hold ------------------------------------------------ */

expect("a name is kept", isDraftable("title"), true);
expect("an amount is kept", isDraftable("invested_amount"), true);
expect("a quantity is kept (it merely contains the letters of UAN)", isDraftable("quantity"), true);
expect("an interest rate is kept", isDraftable("attr:interest_rate"), true);
for (const key of [
  "original_location", "key_holder", "attr:policy_no", "attr:folio_no", "attr:account_no", "attr:pran",
  "attr:uan", "attr:agent_phone", "attr:address", "notes", "sealed:original_location", "passphrase",
  "attr:agreement_location", "attr:certificate_no", "attr:custody", "member_id",
]) {
  expect(`never kept: ${key}`, isDraftable(key), false);
}

/* --- saving, reading back, per person -------------------------------------- */

const storage = memoryStorage();
const ish = createDrafts({ storage, userId: "ish", now });
expect("a safe field is saved", ish.save("capture:fd", "title", "HDFC FD", { typeCode: "fd", title: "HDFC FD" }), true);
ish.save("capture:fd", "invested_amount", "200000");
expect("a sealed field is refused, not saved", ish.save("capture:fd", "original_location", "steel almirah"), false);
expect("an identifier is refused", ish.save("capture:fd", "attr:account_no", "001234567"), false);
expect("nothing refused reached storage", /almirah|001234567/.test(storage.dump()), false);
expect("the draft reads back", ish.get("capture:fd").fields, { title: "HDFC FD", invested_amount: "200000" });
expect("its label travels with it", ish.list()[0].meta, { typeCode: "fd", title: "HDFC FD" });

const ravi = createDrafts({ storage, userId: "ravi", now });
expect("another person on the same phone sees none of it", ravi.list(), []);

ish.save("capture:fd", "title", "");
ish.save("capture:fd", "invested_amount", "");
expect("a form emptied by hand is no draft", ish.get("capture:fd"), null);

ish.save("capture:gold", "title", "Bangles");
ish.discard("capture:gold");
expect("a discarded draft is gone", ish.list(), []);

const nobody = createDrafts({ storage, userId: null, now });
expect("signed out, nothing is saved", nobody.save("capture:fd", "title", "x"), false);

/* --- the offline queue ------------------------------------------------------ */

const body = { id: "0000", typeId: "t", title: "SBI FD", investedAmount: 100000, visibility: "private", owners: [], attributes: { interest_rate: "7" } };
expect("a safe save is queued", ish.queue("capture", "h1", body, "SBI FD"), true);
expect("a save carrying an identifier is refused, not queued",
  ish.queue("capture", "h1", { ...body, attributes: { policy_no: "123" } }, "LIC"), false);
ish.queue("capture", "h1", { ...body, id: "0001", title: "Second" }, "Second");
expect("two are waiting", ish.pending().length, 2);

let calls = 0;
const offline = await ish.flush(async () => { calls += 1; throw new TypeError("Failed to fetch"); });
expect("still offline: nothing is lost", [offline.sent.length, ish.pending().length, calls], [0, 2, 1]);

const outcomes = ["sent", "refused"];
const online = await ish.flush(async () => outcomes.shift());
expect("back online: sent and refused are told apart", [online.sent.map((i) => i.label), online.refused.map((i) => i.label)], [["SBI FD"], ["Second"]]);
expect("and the queue is empty", ish.pending(), []);

/* --- a refused save comes back as the draft of its form -------------------- */

const rd = { id: "0002", typeId: "t", title: "Post office RD", investedAmount: 5000, maturityDate: "2030-01-01",
  visibility: "private", owners: [{ memberId: "m1", sharePct: 100 }], attributes: { interest_rate: "6.7" } };
ish.queue("capture", "h1", rd, "Post office RD", "capture:rd");
ish.save("capture:rd", "invested_amount", "6000", { typeCode: "rd", householdId: "h1" });
const refusedOnce = await ish.flush(async () => "refused");
expect("a refused save says it was put back", refusedOnce.refused.map((i) => i.returnedAsDraft), [true]);
expect("what was typed is back in the form's draft, and what was typed since wins",
  ish.get("capture:rd")?.fields,
  { invested_amount: "6000", title: "Post office RD", maturity_date: "2030-01-01", "attr:interest_rate": "6.7", owner: "m1" });
expect("the draft names its type and household for the resume card",
  [ish.get("capture:rd")?.meta?.typeCode, ish.get("capture:rd")?.meta?.householdId], ["rd", "h1"]);
expect("and it is no longer queued", ish.pending(), []);
ish.discard("capture:rd");

/* --- sign-out --------------------------------------------------------------- */

ish.save("capture:fd", "title", "Kept until sign-out");
ish.queue("capture", "h1", body, "Queued");
storage.setItem("almira.language", "te");
clearAllDrafts(storage);
expect("sign-out clears every draft and queued save on the device", storage.keys(), ["almira.language"]);

if (failures > 0) {
  log(`\n${failures} failing`);
  throw new Error(`${failures} failing`);
}
log("\nall passing");
