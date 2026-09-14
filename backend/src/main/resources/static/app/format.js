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
