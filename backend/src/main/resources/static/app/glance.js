/* =============================================================================
   The rules behind Home, Holdings and Household that can be reasoned about on
   their own: no DOM, no requests, no language. scripts/check-glance.js holds
   them to what the screens promise.
   ============================================================================= */

/**
 * The month-end points from the month this household began recording (P-15).
 * TrendService never guesses between snapshots, and the months before anything
 * was recorded are not history, so the line starts there.
 */
export function trendPoints(trend) {
  if (!trend || !Array.isArray(trend.points)) return [];
  const began = trend.historyBeginsAt ? String(trend.historyBeginsAt).slice(0, 7) : null;
  return trend.points.filter((point) => !began || String(point.date).slice(0, 7) >= began);
}

/**
 * The words above the readiness ring (X-33): movement since last month when
 * there is a month to compare with, otherwise how many things are done. Never a
 * lone percentage. A message key and its parameters.
 */
export function movementLine(readiness) {
  const checks = readiness?.checks || [];
  const done = checks.reduce((sum, check) => sum + check.done, 0);
  const applicable = checks.reduce((sum, check) => sum + check.applicable, 0);
  const movement = readiness?.movement;
  if (movement && movement.saferCount > 0) {
    return { key: movement.saferCount === 1 ? "ready.movement.one" : "ready.movement.many", params: { count: movement.saferCount } };
  }
  if (movement && movement.fewerDone > 0) {
    return { key: "ready.movement.fewer", params: { count: movement.fewerDone } };
  }
  if (movement) return { key: "ready.movement.same", params: { done, applicable } };
  return { key: "ready.movement.none", params: { done, applicable } };
}

/**
 * The first thing waiting on each record, for a list row's one needs-doing line
 * (X-54). The inbox arrives in the order to look at it, so the first one wins.
 */
export function firstNeedByRecord(inbox) {
  const needs = new Map();
  for (const item of inbox?.items || []) {
    if (!needs.has(item.recordId)) needs.set(item.recordId, item);
  }
  return needs;
}

export const MEMBER_TONES = 6;

/**
 * 1–6 for a member id, the same for every viewer and every roster order, so the
 * same person is the same colour everywhere (X-54, X-56).
 */
export function memberToneIndex(memberId) {
  let hash = 0;
  for (const ch of String(memberId || "")) hash = (hash * 31 + ch.charCodeAt(0)) >>> 0;
  return (hash % MEMBER_TONES) + 1;
}

/** "Ravi Koduri" → "RK"; one word, one letter. Whole characters, so Telugu and Devanagari are not split. */
export function initials(name) {
  const words = String(name || "").trim().split(/\s+/).filter(Boolean);
  const first = (word) => {
    if (typeof Intl !== "undefined" && Intl.Segmenter) {
      for (const { segment } of new Intl.Segmenter(undefined, { granularity: "grapheme" }).segment(word)) return segment;
      return "";
    }
    return Array.from(word)[0] || "";
  };
  return words.slice(0, 2).map(first).join("").toLocaleUpperCase() || "?";
}

/**
 * A role in plain words (X-56): "Can add and edit", not "editor"; "No sign-in"
 * for someone kept by others. A message key.
 */
export function roleKey(member) {
  if (member.isManaged || !member.role) return "family.role.managed";
  return `family.role.${member.role}`;
}
