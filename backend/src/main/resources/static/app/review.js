/* "To review" — one inbox, cleared card by card (X-51).

   It replaced "Worth a look", which was a stack of identical brown cards in no
   order, each saying "Review". Now Home shows one count and the first few
   things; "Go through them" opens a single card at a time, with the one or two
   answers that card can take, and a quiet "Not now" that moves on without
   deciding anything.

   The server decides what is waiting and in what order (GET /review): a
   maturity of something you hold, a Still true? question, then a missing
   nominee or scan exactly as readiness names it. The client only offers the
   answers. Nothing is judged here; a card is a fact about a record, never
   advice about an investment (docs/08 §6).

   A failed load is simply no card: Home must not fail with it. */

import { api } from "./api.js";
import { el, mount, sheet, toast, when, empty } from "./ui.js";
import { t, localDate } from "./i18n.js";
import { monthFromToday } from "./still-true.js";
import { openDetail } from "./screens/detail.js";

export { firstNeedByRecord } from "./glance.js";

/** The inbox, or null. Never throws. */
export async function loadReview(householdId) {
  try {
    const inbox = await api.review(householdId);
    return inbox && Array.isArray(inbox.items) ? inbox : null;
  } catch {
    return null;
  }
}

/** A kind the client knows, in the reader's language; otherwise the server's English. Pure. */
export function needText(item) {
  const key = item.kind === "still_true" ? `review.detail.still_true.${item.reason || "period"}` : `review.detail.${item.kind}`;
  const text = t(key);
  return text === key ? item.detail : text;
}

/** The few words for a row: "No nominee", "Matures in 4 days". */
export function needLabel(item) {
  const key = `review.kind.${item.kind}`;
  const text = t(key);
  return text === key ? item.detail : text;
}

/**
 * Home's card: the count, the first three, and a way through all of them. An
 * empty inbox is a calm sentence, not a missing card.
 *
 * @param onChanged called once the flow closes after anything was answered
 */
export function reviewCard(householdId, inbox, { onChanged } = {}) {
  if (!inbox) return null;
  if (inbox.items.length === 0) {
    return el("div.card", { "data-review": "empty" },
      el("div.section-title", {}, el("h4", {}, t("review.title"))),
      el("p", { style: { margin: 0 } }, t("review.empty")));
  }

  const start = (index) => openReview(householdId, inbox.items, { start: index, onChanged });
  return el("div.card", { "data-review": String(inbox.items.length) },
    el("div.section-title", {},
      el("h4", {}, t("review.title")),
      el("span.review-count", { "aria-label": t("review.count", { count: inbox.items.length }) },
        String(inbox.items.length))),
    el("div.list", {}, ...inbox.items.slice(0, 3).map((item, index) => el("button.list-row", {
      type: "button", onclick: () => start(index),
    },
      el("span.pill", {}, needLabel(item)),
      el("div.grow", { style: { minWidth: 0 } },
        el("div.title", {}, item.title),
        item.kind === "maturity" && item.date && el("div.meta", {}, when(item.date))),
    ))),
    el("div.row.wrap", { style: { marginTop: "12px" } },
      el("button.btn", { type: "button", onclick: () => start(0) }, t("review.start")),
      inbox.items.some((item) => item.kind === "still_true") && askLaterButton()),
  );
}

/**
 * "Ask me later" pauses Still true? reminders for a week (docs/21 §6). It pauses
 * the messages, not the questions: they stay here to answer whenever.
 */
function askLaterButton() {
  const button = el("button.btn.btn-ghost", {
    type: "button",
    "data-still-true-ask-later": "",
    onclick: async () => {
      button.disabled = true;
      try {
        const prefs = await api.askLaterAboutStillTrue();
        toast(t("still.askedLater", { date: localDate(prefs.stillTruePausedUntil) }));
        button.remove();
      } catch (error) {
        button.disabled = false;
        toast(error.message, { tone: "error" });
      }
    },
  }, t("still.askLater"));
  return button;
}

/**
 * One card at a time. An answered card is cleared; "Not now" moves on and
 * leaves it for another day. The sheet ends with what is left, in words.
 */
export function openReview(householdId, items, { start = 0, onChanged } = {}) {
  const queue = [...items.slice(start), ...items.slice(0, start)];
  const total = queue.length;
  let index = 0;
  let cleared = 0;
  let skipped = 0;
  let changed = false;

  const body = el("div.review-step", { "aria-live": "polite" });
  const modal = sheet({
    title: t("review.title"),
    body,
    onClose: () => { if (changed) onChanged?.(); },
  });

  const next = (answered) => {
    if (answered) { cleared += 1; changed = true; } else { skipped += 1; }
    index += 1;
    draw();
  };

  function draw() {
    if (index >= total) {
      mount(body, cleared === total
        ? empty({ title: t("review.done.title"), body: t("review.empty") })
        : empty({ title: t("review.done.title"), body: t("review.done.skipped", { count: skipped }) }),
      el("button.btn", { type: "button", onclick: () => modal.close() }, t("app.close")));
      return;
    }
    const item = queue[index];
    mount(body,
      el("div.review-progress", { "aria-hidden": "true" }, el("i", { style: { width: `${(index / total) * 100}%` } })),
      el("span.caption.muted", {}, t("review.step", { n: index + 1, total })),
      el("div.review-card", { "data-kind": item.kind },
        el("span.pill", { style: { alignSelf: "flex-start" } }, needLabel(item)),
        el("h3", {}, item.title),
        item.date && el("div.caption.muted", {}, when(item.date)),
        item.valueFormatted && el("div", { style: { fontVariantNumeric: "tabular-nums" } }, item.valueFormatted),
        el("p", { style: { margin: 0 } }, needText(item)),
        el("div.review-actions", {}, ...actions(item)),
      ),
    );
    body.querySelector(".review-actions button")?.focus();
  }

  /** The answers a card can take. Opening a holding counts as answered only when something was saved there. */
  function actions(item) {
    const notNow = el("button.btn.btn-ghost", { type: "button", onclick: () => next(false) }, t("review.notNow"));
    // The panel may save more than once (a nominee, then a value); the card is
    // answered by the first save and moves on once.
    let answered = false;
    const openHolding = (label, section) => item.recordType === "investment" && el("button.btn", {
      type: "button",
      onclick: () => openDetail(item.recordId, async () => {
        if (answered || queue[index] !== item) return;
        answered = true;
        next(true);
      }, { section }),
    }, label);

    switch (item.kind) {
      case "still_true": {
        const yes = el("button.btn", { type: "button" }, t("still.confirm"));
        const later = el("button.btn", { type: "button" }, t("still.snooze"));
        const busy = (on) => { yes.disabled = on; later.disabled = on; };
        yes.onclick = async () => {
          busy(true);
          try {
            const answered = await api.confirmStillTrue(householdId, item.recordType, item.recordId);
            toast(t("still.confirmed", { date: localDate(answered.dueOn) }));
            next(true);
          } catch (error) { busy(false); toast(error.message, { tone: "error" }); }
        };
        later.onclick = async () => {
          busy(true);
          try {
            const snoozed = await api.snoozeStillTrue(householdId, item.recordType, item.recordId, monthFromToday());
            toast(t("still.snoozed", { date: localDate(snoozed.dueOn) }));
            next(true);
          } catch (error) { busy(false); toast(error.message, { tone: "error" }); }
        };
        return [yes, later, notNow];
      }
      case "maturity":
        return [openHolding(t("review.open"), "details"), notNow];
      case "no_nominee":
        return [openHolding(t("review.addNominee"), "family"), notNow];
      case "no_document":
        return [
          openHolding(t("review.addPapers"), "papers"),
          // A will or a trust is kept on Family plan, not in a holding's panel.
          item.recordType !== "investment" && el("a.btn", {
            href: "#/continuity", onclick: () => modal.close(),
          }, t("review.plan")),
          notNow,
        ];
      default:
        return [notNow];
    }
  }

  draw();
  return modal;
}
