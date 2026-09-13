/* "Ready to hand over" — one number, and every gap in it named (docs/22).

   A card at the top of For my family, not a screen of its own: the point is a
   to-do list beside the things it is about. The number is only ever the
   server's. When the server says there is no number (nothing recorded, all of
   it left out, nothing a check applies to), the card says why in words and
   shows no figure at all — a 0% or a 100% there would be invented.

   Gaps come grouped by record, in the server's order: the viewer's own gap
   first, then each record's missing things. Each row opens the place where that
   thing is fixed, and the card is fetched again when that place is closed.

   Records left out of the family summary are a to-do of their own: leaving the
   records with gaps out is the one way to move the score without fixing
   anything, so they are named (with a way to open each) and the server keeps
   the score below 100 while any remain (docs/22 §2).

   An older server has no endpoint. A failed load is simply no card; the family
   screen must not fail with it. */

import { api } from "./api.js";
import { el } from "./ui.js";
import { t } from "./i18n.js";

/** The readiness, or null. Never throws. */
export async function loadReadiness(householdId) {
  try {
    const result = await api.readiness(householdId);
    return result && Array.isArray(result.checks) && Array.isArray(result.gaps) ? result : null;
  } catch {
    return null;
  }
}

/** The sentence for a missing score, in the reader's language. Pure, so it can be reasoned about. */
export function noScoreKey(readiness) {
  if (readiness.recordCount === 0 && readiness.leftOutCount === 0) return "ready.null.nothing";
  if (readiness.recordCount === 0) return "ready.null.leftOut";
  return "ready.null.nothingApplies";
}

/** Consecutive gaps on the same record become one to-do row. */
export function groupGaps(gaps) {
  const groups = [];
  for (const gap of gaps) {
    const key = gap.recordId ? `${gap.recordType}:${gap.recordId}` : `you:${gap.check}`;
    const last = groups[groups.length - 1];
    if (last && last.key === key) last.gaps.push(gap);
    else groups.push({ key, recordType: gap.recordType || null, recordId: gap.recordId || null, title: gap.title || null, gaps: [gap] });
  }
  return groups;
}

/** A translated reason, or the server's English sentence for one this client does not know. */
function reasonText(gap) {
  const key = `ready.reason.${gap.reason}`;
  const text = t(key);
  return text === key ? gap.fix : text;
}

/** One to-do: these records are left out of the family summary — on purpose? Each opens the record. */
function leftOutTodo(records, open) {
  return el("div.stack-2", { "data-left-out": "true" },
    el("div.stack-2", {},
      el("span", {}, t("ready.leftOut.title", { count: records.length })),
      el("span.caption.muted", {}, t("ready.leftOut.body")),
    ),
    el("div.list", {}, ...records.map((record) => {
      const go = () => open.record(record.recordType, record.recordId);
      return el("div.list-row", {
        role: "button", tabIndex: 0,
        "aria-label": `${record.title}. ${t("ready.leftOut.row")}`,
        style: { alignItems: "flex-start" },
        onclick: go,
        onkeydown: (event) => { if (event.key === "Enter") go(); },
      },
        el("span.pill", {}, t(`where.type.${record.recordType}`)),
        el("div.grow", {},
          el("div.title", {}, record.title),
          el("div.meta", {}, t("ready.leftOut.row")),
        ),
      );
    })),
  );
}

/**
 * @param readiness    the server's answer
 * @param open         { record(recordType, recordId), trusted(reason) } — where each gap is fixed
 */
export function readinessCard(readiness, open) {
  const hasScore = typeof readiness.score === "number";

  const headline = hasScore
    ? el("div.row", { style: { alignItems: "baseline", gap: "8px" } },
        el("span", { style: { fontSize: "2rem", fontWeight: 600, fontVariantNumeric: "tabular-nums" } },
          `${readiness.score}%`),
        el("span.caption.muted", {}, readiness.complete ? t("ready.complete") : t("ready.howCounted")))
    : el("p", { "data-no-score": "true" }, t(noScoreKey(readiness)));

  const checks = el("div.stack-2", {},
    ...readiness.checks.map((check) => el("div.stack-2", {},
      el("div.row-between", {},
        el("span", {}, t(`ready.check.${check.code}`)),
        el("span.caption.muted", { style: { whiteSpace: "nowrap" } },
          check.applicable === 0
            ? t("ready.notApplicable")
            : t("ready.of", { done: check.done, applicable: check.applicable })),
      ),
      check.applicable > 0 && el("div.meter", {
        role: "img", "aria-label": `${check.percent}%`,
      }, el("div.meter-fill", { style: { width: `${check.percent}%` } })),
    )),
  );

  const groups = groupGaps(readiness.gaps);
  // An older server sends no list; the count alone is then only a caveat.
  const leftOut = Array.isArray(readiness.leftOut) ? readiness.leftOut : [];
  const todoCount = readiness.gaps.length + (leftOut.length > 0 ? 1 : 0);
  const todo = todoCount === 0 ? null : el("div.stack-2", {},
    el("span.overline", {}, t("ready.todo", { count: todoCount })),
    groups.length > 0 && el("div.list", {}, ...groups.map((group) => {
      const go = () => (group.recordId
        ? open.record(group.recordType, group.recordId)
        : open.trusted(group.gaps[0].reason));
      const label = [group.title || t("ready.you"), ...group.gaps.map(reasonText)].join(". ");
      return el("div.list-row", {
        role: "button", tabIndex: 0, "aria-label": label, style: { alignItems: "flex-start" },
        onclick: go,
        onkeydown: (event) => { if (event.key === "Enter") go(); },
      },
        el("span.pill", {}, group.recordType ? t(`where.type.${group.recordType}`) : t("ready.you")),
        el("div.grow", {},
          group.title && el("div.title", {}, group.title),
          ...group.gaps.map((gap) => el("div.meta", {}, reasonText(gap))),
        ),
      );
    })),
    leftOut.length > 0 && leftOutTodo(leftOut, open),
  );

  const caveats = el("details", {},
    el("summary.caption", {}, t("ready.caveats")),
    // Server sentences stay English (Doc 14).
    ...readiness.caveats.map((caveat) => el("p.caption.muted", {}, caveat)),
  );

  return el("div.card.stack-3", { "data-readiness": "true" },
    el("h3", {}, t("ready.title")),
    el("p.caption.muted", { style: { marginTop: 0 } }, t("ready.intro")),
    headline,
    checks,
    todo,
    caveats,
  );
}
