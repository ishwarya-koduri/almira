/* =============================================================================
   The web client's money and date rules, without a browser (D-11, X-71).

       /System/Library/Frameworks/JavaScriptCore.framework/Versions/A/Helpers/jsc \
         -m scripts/check-format.js
       # or: node scripts/check-format.js  (as an ES module)
   ============================================================================= */

import {
  groupIndian, rupees, compactRupees, daysBetween, relativeKey, withoutZeroRows, amountInWords,
} from "../backend/src/main/resources/static/app/format.js";

const log = typeof print === "function" ? print : console.log;
let failures = 0;
function expect(label, actual, wanted) {
  const a = JSON.stringify(actual);
  const w = JSON.stringify(wanted);
  if (a === w) log(`  ok   ${label}`);
  else { failures += 1; log(`  FAIL ${label}\n         got    ${a}\n         wanted ${w}`); }
}

// Indian grouping: the last three digits, then pairs.
expect("a lakh groups as 1,00,000", groupIndian(100000), "1,00,000");
expect("forty-two lakh", rupees(4200000), "₹42,00,000");
expect("a crore and a quarter", rupees(12500000), "₹1,25,00,000");
expect("under a thousand is left alone", rupees(999), "₹999");
expect("a negative figure keeps its sign", groupIndian(-150000), "-1,50,000");
expect("no value is a dash, not ₹0", rupees(null), "—");

// The short form for a tight list.
expect("₹42 L, with no trailing zeros", compactRupees(4200000), "₹42 L");
expect("₹4.5 L", compactRupees(450000), "₹4.5 L");
expect("₹1.25 Cr", compactRupees(12500000), "₹1.25 Cr");
expect("truncated, never rounded up past what is there", compactRupees(4299999), "₹42.99 L");
expect("₹1.13 L, not the ₹1.12 L that dividing first gives", compactRupees(113000), "₹1.13 L");
expect("below a lakh the full figure is already short", compactRupees(85000), "₹85,000");
expect("a string from JSON is read as a number", compactRupees("2500000"), "₹25 L");
expect("owed amounts keep their sign", compactRupees(-4200000), "-₹42 L");
expect("no value has no short form", compactRupees(null), null);
expect("nonsense has no short form", compactRupees("abc"), null);

// Relative dates count calendar days, not 24-hour spans.
const evening = new Date(2026, 8, 14, 21, 30);
expect("tomorrow is 1 even late in the evening", daysBetween("2026-09-15", evening), 1);
expect("today", relativeKey("2026-09-14", evening), { key: "when.today", count: 0 });
expect("tomorrow", relativeKey("2026-09-15", evening), { key: "when.tomorrow", count: 1 });
expect("yesterday", relativeKey("2026-09-13", evening), { key: "when.yesterday", count: 1 });
expect("in 4 days", relativeKey("2026-09-18", evening), { key: "when.inDays", count: 4 });
expect("12 days ago", relativeKey("2026-09-02", evening), { key: "when.daysAgo", count: 12 });
expect("a timestamp is read by its date", relativeKey("2026-09-18T23:59:00Z", evening), { key: "when.inDays", count: 4 });
expect("no date, no phrase", relativeKey(null, evening), null);

// Noise rows.
expect("a category holding nothing is dropped",
  withoutZeroRows([{ key: "gold", value: 100 }, { key: "insurance", value: 0 }, { key: "cash", value: "0.00" }]),
  [{ key: "gold", value: 100 }]);
expect("no rows is no rows", withoutZeroRows(undefined), []);

// An amount in words (X-06). English matches IndianNumbersTest on the server, word for word.
for (const [value, words] of [
  [0, "Zero"], [7, "Seven"], [15, "Fifteen"], [40, "Forty"], [99, "Ninety Nine"], [100, "One Hundred"],
  [1000, "One Thousand"], [5050000, "Fifty Lakh Fifty Thousand"],
  [1493750, "Fourteen Lakh Ninety Three Thousand Seven Hundred Fifty"], [10000000, "One Crore"],
  [17687500, "One Crore Seventy Six Lakh Eighty Seven Thousand Five Hundred"],
]) expect(`${value} in English words, as the server writes it`, amountInWords(value, "en"), words);
expect("a hundred crore or more is counted in figures, as the server does", amountInWords(1500000000, "en"), "150 Crore");
expect("paise are dropped, not rounded", amountInWords(99.99, "en"), "Ninety Nine");
expect("the tax total rounds first, as its server line does", amountInWords(99.5, "en", { rupees: true, round: true }), "Rupees One Hundred");
expect("a loss keeps its sign", amountInWords(-1000, "en"), "Minus One Thousand");

// Telugu: a count of one is ఒక before a scale; the scale words take their joining form before more.
expect("Telugu: thirty-three lakh fifty thousand", amountInWords(3350000, "te"), "ముప్పై మూడు లక్షల యాభై వేలు");
expect("Telugu: 1,76,875", amountInWords(176875, "te"), "ఒక లక్ష డెబ్బై ఆరు వేల ఎనిమిది వందల డెబ్బై ఐదు");
expect("Telugu: a hundred alone is వంద", amountInWords(100, "te"), "వంద");
expect("Telugu: a hundred and fifty is నూట యాభై", amountInWords(150, "te"), "నూట యాభై");
expect("Telugu: twenty-one lakh", amountInWords(2100000, "te"), "ఇరవై ఒక లక్షలు");
expect("Telugu: one crore", amountInWords(10000000, "te"), "ఒక కోటి");
expect("Telugu: two crore five lakh", amountInWords(20500000, "te"), "రెండు కోట్ల ఐదు లక్షలు");
expect("Telugu: rupees, and zero", amountInWords(0, "te", { rupees: true }), "సున్నా రూపాయలు");

// Hindi: a word of its own for every number under a hundred, and no plural on the scale.
expect("Hindi: thirty-three lakh fifty thousand", amountInWords(3350000, "hi"), "तैंतीस लाख पचास हज़ार");
expect("Hindi: 1,76,875", amountInWords(176875, "hi"), "एक लाख छिहत्तर हज़ार आठ सौ पचहत्तर");
expect("Hindi: a hundred and fifty crore is in words too", amountInWords(1500000000, "hi"), "एक सौ पचास करोड़");
expect("Hindi: minus, with rupees", amountInWords(-99, "hi", { rupees: true }), "माइनस निन्यानवे रुपये");
expect("an unknown language falls back to English", amountInWords(12, "fr"), "Twelve");
expect("no value, no words", amountInWords(null, "te"), "");

if (failures > 0) {
  log(`\n${failures} failing`);
  throw new Error(`${failures} failing`);
}
log("\nall passing");
