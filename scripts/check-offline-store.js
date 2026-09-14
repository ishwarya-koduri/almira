/* =============================================================================
   What the offline copy keeps, and for how long, without a browser (P-21).

       /System/Library/Frameworks/JavaScriptCore.framework/Versions/A/Helpers/jsc \
         -m scripts/check-offline-store.js

   The encryption, the separate key and the deletion need WebCrypto and
   IndexedDB, so they are checked in a browser (scripts/browser-checks). This is
   the allowlist and the clock, which are where a quiet mistake would leak.
   ============================================================================= */

import {
  offlineCopy, isStale, lastUpdatedKey, MAX_AGE_MS, FORMAT,
} from "../backend/src/main/resources/static/app/offline-store.js";

const log = typeof print === "function" ? print : console.log;
let failures = 0;
function expect(label, actual, wanted) {
  const a = JSON.stringify(actual);
  const w = JSON.stringify(wanted);
  if (a === w) log(`  ok   ${label}`);
  else { failures += 1; log(`  FAIL ${label}\n         got    ${a}\n         wanted ${w}`); }
}

const handbook = {
  householdName: "Koduri", preparedFor: "Ishwarya", totalIncludedFormatted: "₹42,00,000",
  disclaimer: "Informational.", excludedCount: 2,
  entries: [{
    investmentId: "0f0e", title: "SBI FD", institutionName: "SBI", reference: "FD-1234", nominees: ["Aarav", 7],
    valueFormatted: "₹3,00,000", value: 300000, howToClaim: "Visit the branch.",
    whereItIsKept: "Locker 12, Jubilee Hills", notes: "private note",
    sealed: [{ field: "where", ciphertext: "AAAA" }],
    contacts: [{ name: "Ramesh Rao", kind: "ca", phone: "98765 43210", id: "c1", notes: "secret" }],
  }],
  debts: [{ title: "Home loan", lender: "HDFC", outstandingFormatted: "₹20,00,000", note: "restructured", securedAgainst: "Flat" }],
  instruments: [{ estateDocumentId: "e1", title: "Will", kind: "will", executors: ["Sita"], location: "Almirah", sealed: [{}] }],
  contacts: [{ name: "Ramesh Rao", role: "CA", phone: "98765 43210" }],
};
const contacts = [{ id: "c1", name: "Ramesh Rao", kind: "ca", phone: "98765 43210", address: "12 Road", notes: "n", links: [1, 2], visibility: "household" }];
const trusted = [{ id: "t1", memberId: "m1", trustedMemberId: "m2", trustedMemberName: "Sita", memberName: "Ishwarya", theyTrustMe: false, waitDays: 7, note: "n" }];

log("\nWhat is kept");
const now = Date.UTC(2026, 8, 14, 10, 0);
const copy = offlineCopy({ handbook, contacts, trusted, householdId: "h1", now });
const text = JSON.stringify(copy);
expect("the format and the time it was kept", [copy.format, copy.savedAt, copy.householdId], [FORMAT, now, "h1"]);
expect("a holding keeps what the page shows",
  copy.handbook.entries[0],
  { title: "SBI FD", institutionName: "SBI", reference: "FD-1234", valueFormatted: "₹3,00,000", howToClaim: "Visit the branch.",
    nominees: ["Aarav"], contacts: [{ name: "Ramesh Rao", kind: "ca", phone: "98765 43210" }] });
expect("no sealed value, in any form", /sealed|ciphertext|AAAA/.test(text), false);
expect("never where something is kept", /Locker|Almirah|whereItIsKept|location/.test(text), false);
expect("no notes", /private note|secret|restructured|"note"/.test(text), false);
expect("no ids", /0f0e|"c1"|"e1"|"t1"|"m1"|"m2"|investmentId|estateDocumentId/.test(text), false);
expect("no address", /12 Road|address/.test(text), false);
expect("a will by title and executors", copy.handbook.instruments, [{ title: "Will", kind: "will", executors: ["Sita"] }]);
expect("a trusted person by name", copy.trusted, [{ memberName: "Ishwarya", trustedMemberName: "Sita", theyTrustMe: false, waitDays: 7 }]);
expect("nothing given is an empty copy, not an error",
  offlineCopy({ householdId: "h1", now }).handbook, { entries: [], debts: [], instruments: [], contacts: [] });

log("\nHow long");
expect("a fresh copy is shown", isStale(copy, now + 60_000), false);
expect("a day-old copy is shown", isStale(copy, now + 86_400_000), false);
expect("past 30 days it is not", isStale(copy, now + MAX_AGE_MS + 1), true);
expect("a copy from the future is not trusted", isStale(copy, now - 60 * 60 * 1000), true);
expect("an older format is not read", isStale({ ...copy, format: FORMAT + 1 }, now), true);
expect("nothing is stale", isStale(null, now), true);

log("\nLast updated");
expect("just now", lastUpdatedKey(now, now + 20_000), { key: "offline.updated.justNow", count: 0 });
expect("a minute", lastUpdatedKey(now, now + 60_000), { key: "offline.updated.minute", count: 1 });
expect("minutes", lastUpdatedKey(now, now + 59 * 60_000), { key: "offline.updated.minutes", count: 59 });
expect("2 hours ago", lastUpdatedKey(now, now + 2 * 3_600_000 + 5), { key: "offline.updated.hours", count: 2 });
expect("a day", lastUpdatedKey(now, now + 30 * 3_600_000), { key: "offline.updated.day", count: 1 });
expect("days", lastUpdatedKey(now, now + 9 * 86_400_000), { key: "offline.updated.days", count: 9 });

log(failures === 0 ? "\nAll offline copy checks passed.\n" : `\n${failures} offline copy checks failed.\n`);
if (typeof quit === "function") quit(failures === 0 ? 0 : 1);
else if (typeof process !== "undefined") process.exit(failures === 0 ? 0 : 1);
