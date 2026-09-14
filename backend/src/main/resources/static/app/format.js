/* =============================================================================
   Money and dates, as plain functions (D-11).

   No DOM and no imports, so scripts/check-format.js can run every rule here
   under jsc without a browser. ui.js re-exports these and wraps them in
   elements; screens should reach for those wrappers.

   The server still sends the canonical figure for anything it computed
   (valueFormatted, netWorthFormatted), so a total never disagrees with itself
   across surfaces. What this adds is the short form for a tight list — ₹42 L
   beside the full ₹42,00,000 — and "in 4 days" beside the date it means.
   ============================================================================= */

const LAKH = 100000;
const CRORE = 10000000;

export function groupIndian(value) {
  const n = Math.trunc(Math.abs(Number(value) || 0)).toString();
  const sign = Number(value) < 0 ? "-" : "";
  if (n.length <= 3) return sign + n;
  const last3 = n.slice(-3);
  const rest = n.slice(0, -3);
  const pairs = [];
  for (let i = rest.length; i > 0; i -= 2) pairs.unshift(rest.slice(Math.max(0, i - 2), i));
  return `${sign}${pairs.join(",")},${last3}`;
}

export const rupees = (value) =>
  value === null || value === undefined ? "—" : `₹${groupIndian(value)}`;

/**
 * ₹42 L, ₹1.25 Cr, and the full figure below a lakh — where the short form
 * saves nothing. Truncated rather than rounded: a list should never show more
 * than is there, whether it is owned or owed.
 */
export function compactRupees(value) {
  if (value === null || value === undefined || value === "") return null;
  const number = Number(value);
  if (!Number.isFinite(number)) return null;
  const sign = number < 0 ? "-" : "";
  const whole = Math.abs(number);
  if (whole < LAKH) return `${sign}₹${groupIndian(whole)}`;
  const [unit, label] = whole >= CRORE ? [CRORE, "Cr"] : [LAKH, "L"];
  // Multiply before dividing: 12.345 * 100 is 1234.4999… in floating point.
  const scaled = Math.trunc((whole * 100) / unit) / 100;
  // 42.00 → "42", 4.50 → "4.5": no trailing zeros for a reader to parse.
  const text = scaled.toFixed(2).replace(/\.?0+$/, "");
  return `${sign}₹${text} ${label}`;
}

/**
 * Whole days between two calendar dates, ignoring the time of day and the
 * time zone offset — "tomorrow" should not turn into "today" at 6pm.
 */
export function daysBetween(iso, now = new Date()) {
  if (!iso) return null;
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(iso));
  const target = match
    ? Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]))
    : Date.UTC(new Date(iso).getFullYear(), new Date(iso).getMonth(), new Date(iso).getDate());
  const today = Date.UTC(now.getFullYear(), now.getMonth(), now.getDate());
  return Math.round((target - today) / 86400000);
}

/** The i18n key and count for a relative date; the caller translates. */
export function relativeKey(iso, now = new Date()) {
  const days = daysBetween(iso, now);
  if (days === null || Number.isNaN(days)) return null;
  if (days === 0) return { key: "when.today", count: 0 };
  if (days === 1) return { key: "when.tomorrow", count: 1 };
  if (days === -1) return { key: "when.yesterday", count: 1 };
  if (days > 0) return { key: "when.inDays", count: days };
  return { key: "when.daysAgo", count: Math.abs(days) };
}

/**
 * Rows a breakdown should show (X-71): a category holding nothing is noise
 * ("Insurance ₹0 · 0%"), not information.
 */
export function withoutZeroRows(rows) {
  return (rows || []).filter((row) => Number(row.value) !== 0);
}

/* -----------------------------------------------------------------------------
   An amount in words, in the reader's language (X-06).

   The server writes the English line ("Thirty Three Lakh Fifty Thousand") and
   the English client shows that one, so a PDF and a phone agree. Telugu and
   Hindi are written here, from the same number, on the same Indian scale:
   crore, lakh, thousand, hundred. The figures beside them stay ₹33,50,000 in
   every language (docs/14 rule 1). The Telugu and Hindi forms are drafts that
   a native speaker has not yet reviewed (docs/14).
   ----------------------------------------------------------------------------- */

const EN_ONES = ["", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
  "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen"];
const EN_TENS = ["", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"];

// Hindi has a word of its own for every number below a hundred.
const HI_UNDER_100 = [
  "", "एक", "दो", "तीन", "चार", "पाँच", "छह", "सात", "आठ", "नौ",
  "दस", "ग्यारह", "बारह", "तेरह", "चौदह", "पंद्रह", "सोलह", "सत्रह", "अठारह", "उन्नीस",
  "बीस", "इक्कीस", "बाईस", "तेईस", "चौबीस", "पच्चीस", "छब्बीस", "सत्ताईस", "अट्ठाईस", "उनतीस",
  "तीस", "इकतीस", "बत्तीस", "तैंतीस", "चौंतीस", "पैंतीस", "छत्तीस", "सैंतीस", "अड़तीस", "उनतालीस",
  "चालीस", "इकतालीस", "बयालीस", "तैंतालीस", "चवालीस", "पैंतालीस", "छियालीस", "सैंतालीस", "अड़तालीस", "उनचास",
  "पचास", "इक्यावन", "बावन", "तिरेपन", "चौवन", "पचपन", "छप्पन", "सत्तावन", "अट्ठावन", "उनसठ",
  "साठ", "इकसठ", "बासठ", "तिरेसठ", "चौंसठ", "पैंसठ", "छियासठ", "सड़सठ", "अड़सठ", "उनहत्तर",
  "सत्तर", "इकहत्तर", "बहत्तर", "तिहत्तर", "चौहत्तर", "पचहत्तर", "छिहत्तर", "सतहत्तर", "अठहत्तर", "उन्यासी",
  "अस्सी", "इक्यासी", "बयासी", "तिरासी", "चौरासी", "पचासी", "छियासी", "सत्तासी", "अट्ठासी", "नवासी",
  "नब्बे", "इक्यानवे", "बानवे", "तिरानवे", "चौरानवे", "पंचानवे", "छियानवे", "सत्तानवे", "अट्ठानवे", "निन्यानवे",
];

const TE_ONES = ["", "ఒకటి", "రెండు", "మూడు", "నాలుగు", "ఐదు", "ఆరు", "ఏడు", "ఎనిమిది", "తొమ్మిది",
  "పది", "పదకొండు", "పన్నెండు", "పదమూడు", "పద్నాలుగు", "పదిహేను", "పదహారు", "పదిహేడు", "పద్దెనిమిది", "పందొమ్మిది"];
const TE_TENS = ["", "", "ఇరవై", "ముప్పై", "నలభై", "యాభై", "అరవై", "డెబ్బై", "ఎనభై", "తొంభై"];

const SCALES = [[CRORE, "crore"], [LAKH, "lakh"], [1000, "thousand"], [100, "hundred"]];

/** Splits a whole number into [count, scale] parts, largest first, and the rest below a hundred. */
function indianParts(whole) {
  let remaining = whole;
  const parts = [];
  for (const [divisor, scale] of SCALES) {
    const count = Math.floor(remaining / divisor);
    if (count > 0) { parts.push([count, scale]); remaining %= divisor; }
  }
  return { parts, rest: remaining };
}

function englishWords(whole) {
  const under100 = (n) => (n < 20 ? EN_ONES[n] : EN_TENS[Math.floor(n / 10)] + (n % 10 ? ` ${EN_ONES[n % 10]}` : ""));
  const name = { crore: "Crore", lakh: "Lakh", thousand: "Thousand", hundred: "Hundred" };
  const { parts, rest } = indianParts(whole);
  // As IndianNumbers.words does: a count of a hundred crore or more is written in figures.
  const words = parts.map(([count, scale]) => `${count < 100 ? under100(count) : count} ${name[scale]}`);
  if (rest > 0) words.push(under100(rest));
  return words.join(" ");
}

function hindiWords(whole) {
  const name = { crore: "करोड़", lakh: "लाख", thousand: "हज़ार", hundred: "सौ" };
  const { parts, rest } = indianParts(whole);
  const words = parts.map(([count, scale]) => `${count < 100 ? HI_UNDER_100[count] : hindiWords(count)} ${name[scale]}`);
  if (rest > 0) words.push(HI_UNDER_100[rest]);
  return words.join(" ");
}

function teluguUnder100(n) {
  return n < 20 ? TE_ONES[n] : TE_TENS[Math.floor(n / 10)] + (n % 10 ? ` ${TE_ONES[n % 10]}` : "");
}

/**
 * Telugu scale words change shape: వంద on its own but నూట before more, రెండు
 * వందలు at the end but రెండు వందల before more, and a count of one before a
 * scale is ఒక, not ఒకటి.
 */
function teluguWords(whole) {
  const { parts, rest } = indianParts(whole);
  const words = [];
  parts.forEach(([count, scale], index) => {
    const last = index === parts.length - 1 && rest === 0;
    const counted = (count < 100 ? teluguUnder100(count) : teluguWords(count)).replace(/ఒకటి$/, "ఒక");
    if (scale === "hundred") {
      if (count === 1) words.push(last ? "వంద" : "నూట");
      else words.push(`${counted} ${last ? "వందలు" : "వందల"}`);
    } else if (scale === "thousand") {
      if (count === 1) words.push("వెయ్యి");
      else words.push(`${counted} ${last ? "వేలు" : "వేల"}`);
    } else if (scale === "lakh") {
      words.push(count === 1 ? "ఒక లక్ష" : `${counted} ${last ? "లక్షలు" : "లక్షల"}`);
    } else {
      words.push(count === 1 ? "ఒక కోటి" : `${counted} ${last ? "కోట్లు" : "కోట్ల"}`);
    }
  });
  if (rest > 0) words.push(teluguUnder100(rest));
  return words.join(" ");
}

/**
 * 3350000 → "Thirty Three Lakh Fifty Thousand", "ముప్పై మూడు లక్షల యాభై వేలు",
 * "तैंतीस लाख पचास हज़ार".
 *
 * Paise are dropped, as the server drops them: the words are there to catch a
 * missing zero, not to be a cheque. `round` rounds to the nearest rupee first,
 * for the figures the server rounds (the capital-gains total); `rupees` adds
 * the currency the way each language says it.
 */
export function amountInWords(value, language = "en", { rupees: withRupees = false, round = false } = {}) {
  if (value === null || value === undefined || value === "") return "";
  const number = Number(value);
  if (!Number.isFinite(number)) return "";
  const whole = round ? Math.round(Math.abs(number)) : Math.trunc(Math.abs(number));
  const negative = number < 0 && whole > 0;
  const zero = { en: "Zero", te: "సున్నా", hi: "शून्य" };
  const minus = { en: "Minus ", te: "మైనస్ ", hi: "माइनस " };
  const lang = zero[language] ? language : "en";
  const body = whole === 0 ? zero[lang]
    : lang === "te" ? teluguWords(whole)
      : lang === "hi" ? hindiWords(whole) : englishWords(whole);
  const signed = `${negative ? minus[lang] : ""}${body}`;
  if (!withRupees) return signed;
  return lang === "te" ? `${signed} రూపాయలు` : lang === "hi" ? `${signed} रुपये` : `Rupees ${signed}`;
}
