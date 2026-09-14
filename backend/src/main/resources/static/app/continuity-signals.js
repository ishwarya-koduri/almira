/* =============================================================================
   Continuity signals (docs/27): if you go quiet, still reachable, do they know
   where it is, and term cover against what the household spends.

   Each is a card on For my family and none of them is loud. Going quiet is a
   timeline of three calm steps with one "I'm here"; a trusted contact's tick is
   a date; a question is Yes or Not sure; protection is a sentence first and a
   soft ring second, never red and never a verdict.

   Every card is optional: an older server has no endpoint, and a failed load is
   simply no card.
   ============================================================================= */

import { api, ApiError } from "./api.js";
import {
  el, mount, sheet, field, select, moneyInput, withBusy, toast, notice, ring, money, formatDate,
} from "./ui.js";
import { state } from "./state.js";
import { t } from "./i18n.js";
import { withStepUp } from "./lifecycle.js";

/** Loads what the cards need, each on its own. Never throws. */
export async function loadSignals(householdId) {
  const [inactivity, asks, protection] = await Promise.all([
    api.inactivity(householdId).catch(() => null),
    api.keyHolderAsks(householdId).catch(() => null),
    api.protection(householdId).catch(() => null),
  ]);
  return { inactivity, asks, protection };
}

const TICK =
  '<svg viewBox="0 0 24 24" width="18" height="18" focusable="false">' +
  '<path d="M5 12.5l4.5 4.5L19 7.5" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/></svg>';

const tickMark = () => el("span.signal-tick", { html: TICK, "aria-hidden": "true" });

/* -----------------------------------------------------------------------------
   If you go quiet
   ----------------------------------------------------------------------------- */

function periodWords(days) {
  const key = `quiet.period.${days}`;
  const text = t(key);
  return text === key ? `${days} days` : text.toLowerCase();
}

/** Pure: the sentence for one step, in the reader's language. */
export function stepSentence(step, view) {
  // With nobody named yet there is no wait to quote, so the sentence does not invent one.
  const code = step.code === "request" && view.shortestWaitDays == null ? "requestNoWait" : step.code;
  return t(`quiet.step.${code}`, {
    period: periodWords(view.periodDays),
    wait: view.shortestWaitDays,
  });
}

export function goingQuietCard(view, redraw) {
  if (!view) return null;
  const hid = state.household.id;
  const nobody = view.contactsWhoCanAsk.length === 0;

  const imHere = el("button.btn", { type: "button" }, t("quiet.imHere"));
  imHere.onclick = () => withBusy(imHere, async () => {
    await api.imHere(hid);
    toast(t("quiet.thanks"));
    await redraw();
  });

  const timeline = el("ol.quiet-timeline", { "aria-label": t("quiet.title") },
    ...view.steps.map((step) => el("li.quiet-step", { "data-done": String(step.done) },
      el("span.quiet-mark", { "aria-hidden": "true" }, step.done ? tickMark() : null),
      el("div.stack-2", {},
        el("span.overline", {}, t("quiet.day", { day: step.day })),
        el("span", {}, stepSentence(step, view)),
        view.enabled && step.at && el("span.caption", {}, formatDate(step.at)),
      ),
    )),
  );

  const stageLine = view.stage === "raised" ? t("quiet.stage.raised")
    : (view.stage === "reminded_once" || view.stage === "reminded_twice") ? t("quiet.stage.reminded")
      : null;

  return el("div.card.stack-3", { "data-going-quiet": view.stage },
    el("div.row-between.wrap", {},
      el("h3", {}, t("quiet.title")),
      el("span.pill", { class: view.enabled ? "pill-accent" : "" }, view.enabled ? t("quiet.on") : t("quiet.off")),
    ),
    el("p.caption", { style: { marginTop: 0 } }, t("quiet.intro")),
    stageLine && el("p", { role: "status" }, stageLine),
    timeline,
    view.enabled && view.lastPresenceAt && el("p.caption", {}, t("quiet.lastSeen", { date: formatDate(view.lastPresenceAt) })),
    nobody && notice(t("quiet.nobody")),
    el("div.row.wrap", { style: { gap: "8px" } },
      view.enabled && imHere,
      !nobody && el("button.btn.btn-ghost", { type: "button", onclick: () => quietSheet(view, redraw) },
        view.enabled ? t("quiet.change") : t("quiet.turnOn")),
      view.enabled && turnOffButton(redraw),
    ),
  );
}

function turnOffButton(redraw) {
  const off = el("button.btn.btn-danger", { type: "button" }, t("quiet.turnOff"));
  off.onclick = () => withBusy(off, async () => {
    // Turning it off never needs a step-up: it only ever closes a way in.
    const current = await api.inactivity(state.household.id);
    await api.setInactivity(state.household.id, { enabled: false, periodDays: current.periodDays });
    toast(t("quiet.saved"));
    await redraw();
  });
  return off;
}

function quietSheet(view, redraw) {
  let period = view.periodDays;
  const choices = el("div", {});
  // Four choices do not fit a segmented control on a phone, and none of them
  // may hide off the edge: chips that wrap.
  const draw = () => mount(choices, el("div.row.wrap", { role: "group", "aria-label": t("quiet.period"), style: { gap: "8px" } },
    ...view.periodChoices.map((days) => el("button.chip", {
      type: "button",
      "aria-pressed": days === period,
      onclick: () => { period = days; draw(); },
    }, t(`quiet.period.${days}`)))));
  draw();
  const error = el("div.help.error", { style: { minHeight: "1.15rem" } });
  const save = el("button.btn.btn-primary.grow", { type: "button" }, t("app.save"));
  save.onclick = () => withBusy(save, async () => {
    error.textContent = "";
    try {
      await withStepUp(() => api.setInactivity(state.household.id, { enabled: true, periodDays: period }));
      modal.close();
      toast(t("quiet.saved"));
      await redraw();
    } catch (problem) {
      error.textContent = problem.message;
    }
  });
  const modal = sheet({
    title: t("quiet.title"),
    body: el("div.stack-3", {},
      el("p.caption", {}, t("quiet.intro")),
      el("div.stack-2", {}, el("span", {}, t("quiet.period")), choices),
      notice(t("quiet.confirmFirst")),
      error,
    ),
    footer: [save],
  });
}

/* -----------------------------------------------------------------------------
   Still reachable — a line under each trusted contact
   ----------------------------------------------------------------------------- */

const YEAR_MS = 365 * 24 * 60 * 60 * 1000;

/** Pure: whether a confirmation still counts. */
export function isCurrent(confirmedAt, now = Date.now()) {
  return Boolean(confirmedAt) && now - new Date(confirmedAt).getTime() < YEAR_MS;
}

/** For the owner: a dated tick, or "not confirmed yet" and Ask now. */
export function reachableLine(contact, redraw) {
  const current = isCurrent(contact.reachableConfirmedAt);
  const ask = el("button.btn.btn-ghost.btn-sm", { type: "button" }, t("reach.askNow"));
  ask.onclick = () => withBusy(ask, async () => {
    try {
      await api.askReachable(state.household.id, contact.id);
      toast(t("reach.askedToast"));
      await redraw();
    } catch (problem) {
      toast(problem.message, { tone: "error" });
    }
  });
  return el("div.row-between.wrap", { "data-reachable": String(current) },
    el("span.row.caption", { style: { gap: "6px" } },
      current && tickMark(),
      current
        ? t("reach.confirmed", { date: formatDate(contact.reachableConfirmedAt) })
        : contact.reachabilityAskedAt
          ? `${t("reach.notYet")} · ${t("reach.asked", { date: formatDate(contact.reachabilityAskedAt) })}`
          : t("reach.notYet")),
    !current && ask,
  );
}

/** For the person named: one tap, once a year. */
export function confirmReachableButton(contact, redraw) {
  if (isCurrent(contact.reachableConfirmedAt)) {
    return el("span.row.caption", { style: { gap: "6px" } },
      tickMark(), t("reach.confirmed", { date: formatDate(contact.reachableConfirmedAt) }));
  }
  const button = el("button.btn.btn-sm", { type: "button" }, t("reach.confirm"));
  button.onclick = () => withBusy(button, async () => {
    await api.confirmReachable(state.household.id, contact.id);
    toast(t("reach.thanks"));
    await redraw();
  });
  return button;
}

/* -----------------------------------------------------------------------------
   Do they know where it is?
   ----------------------------------------------------------------------------- */

const answerWord = (answer) => (answer === "yes" ? t("ask.yes") : t("ask.notSure"));

export function questionsCard(asks, redraw) {
  if (!asks) return null;
  const hid = state.household.id;

  const forMe = asks.forMe.map((ask) => {
    const question = el("p", { style: { margin: 0 } }, t("ask.question", { thing: ask.thing }));
    if (ask.answer) {
      return el("div.stack-2", { "data-question": ask.id },
        question,
        el("span.caption", {}, t("ask.youSaid", { answer: answerWord(ask.answer), date: formatDate(ask.answeredAt) })),
      );
    }
    const reply = (answer) => async () => {
      await api.answerKeyHolder(hid, ask.id, answer);
      await redraw();
    };
    const yes = el("button.btn.btn-sm", { type: "button" }, t("ask.yes"));
    const unsure = el("button.btn.btn-sm", { type: "button" }, t("ask.notSure"));
    yes.onclick = () => withBusy(yes, reply("yes"));
    unsure.onclick = () => withBusy(unsure, reply("not_sure"));
    return el("div.stack-2", { "data-question": ask.id },
      question,
      el("span.caption", {}, t("ask.from", { name: ask.askedByName || "" })),
      el("div.row", { style: { gap: "8px" } }, yes, unsure),
    );
  });

  const mine = asks.asked.map((ask) => {
    const withdraw = el("button.btn.btn-ghost.btn-sm", { type: "button" }, t("ask.withdraw"));
    withdraw.onclick = () => withBusy(withdraw, async () => {
      await api.withdrawKeyHolderAsk(hid, ask.id);
      await redraw();
    });
    return el("div.list-row", { style: { alignItems: "flex-start" } },
      el("div.grow", {},
        el("div.title", {}, ask.askedName),
        el("div.meta", {}, t("ask.question", { thing: ask.thing })),
      ),
      ask.answer
        ? el("span.row.caption", { style: { gap: "6px" } }, ask.answer === "yes" && tickMark(),
            `${answerWord(ask.answer)} · ${formatDate(ask.answeredAt)}`)
        : el("div.row", { style: { gap: "4px" } }, el("span.caption", {}, t("ask.waiting")), withdraw),
    );
  });

  return el("div.card.stack-3", { "data-questions": "true" },
    el("div.row-between.wrap", {},
      el("h3", {}, t("ask.title")),
      el("button.btn.btn-sm", { type: "button", onclick: () => askSheet(redraw) }, t("ask.button")),
    ),
    el("p.caption", { style: { marginTop: 0 } }, t("ask.intro")),
    forMe.length > 0 && el("div.stack-3", {}, el("span.overline", {}, t("ask.forYou")), ...forMe),
    mine.length > 0 && el("div.stack-2", {}, el("span.overline", {}, t("ask.yours")), el("div.list", {}, ...mine)),
  );
}

async function askSheet(redraw) {
  const hid = state.household.id;
  const people = (state.members || []).filter((m) => !m.isMe && !m.isManaged && !m.passedAway);
  // Titles only: the index's sealed slots are not opened here, and the
  // question never carries them (docs/27 §4).
  const index = await api.whereAndWho(hid).catch(() => ({ records: [] }));
  const records = index.records || [];

  const error = el("div.help.error", { style: { minHeight: "1.15rem" } });
  if (people.length === 0 || records.length === 0) {
    sheet({
      title: t("ask.title"),
      body: el("div.stack-3", {}, notice(people.length === 0 ? t("ask.nobody") : t("ask.nothing"))),
    });
    return;
  }

  const record = select({
    options: records.map((r) => ({ value: `${r.recordType}|${r.recordId}`, label: r.title })),
    "aria-label": t("ask.record"),
  });
  const person = select({
    options: people.map((m) => ({ value: m.id, label: m.displayName })),
    "aria-label": t("ask.person"),
  });
  const save = el("button.btn.btn-primary.grow", { type: "button" }, t("ask.button"));
  save.onclick = () => withBusy(save, async () => {
    error.textContent = "";
    const [recordType, recordId] = record.value.split("|");
    try {
      await api.askKeyHolder(hid, { recordType, recordId, askedMemberId: person.value });
      modal.close();
      toast(t("ask.sent"));
      await redraw();
    } catch (problem) {
      error.textContent = problem.message;
    }
  });
  const modal = sheet({
    title: t("ask.title"),
    body: el("div.stack-3", {},
      field({ label: t("ask.record"), control: record }),
      field({ label: t("ask.person"), control: person }),
      notice(t("ask.privacy")),
      error,
    ),
    footer: [save],
  });
}

/* -----------------------------------------------------------------------------
   Term cover and what the household spends — information, never advice
   ----------------------------------------------------------------------------- */

/** Pure: "4.1×" for a multiple the server rounded down. */
export function multipleLabel(multiple) {
  if (multiple === null || multiple === undefined) return "";
  return `${Number(multiple).toString()}×`;
}

export function protectionCard(view, redraw) {
  if (!view) return null;
  const gauge = typeof view.gaugePercent === "number" ? ring(view.gaugePercent, {
    size: "lg", label: t("protect.gauge", { multiple: multipleLabel(view.multiple) }),
  }) : null;
  // The ring's own label is a percentage; here it is the multiple.
  gauge?.querySelector(".ring-label")?.replaceChildren(multipleLabel(view.multiple));

  const [firstCaveat, ...otherCaveats] = view.caveats || [];
  return el("div.card.stack-3", { "data-protection": view.status },
    el("h3", {}, t("protect.title")),
    // Words first: the server's sentence, then the figures under it.
    el("p.protection-headline", { style: { margin: 0 } }, view.headline),
    el("div.row.wrap", { style: { gap: "16px", alignItems: "center" } },
      gauge,
      el("div.stack-2", {},
        el("span.money-figure", {}, money(view.termCoverFormatted, view.termCover)),
        el("span.caption", {}, view.termCoverInWords),
        view.annualExpensesFormatted && el("span.caption", {},
          `${t("protect.expenses")}: ${view.annualExpensesFormatted}`),
      ),
    ),
    ...(view.details || []).map((line) => el("p.caption", { style: { margin: 0 } }, line)),
    view.policies.length > 0 && el("details", {},
      el("summary.caption", {}, `${t("protect.policies")} · ${view.policies.length}`),
      el("div.list", {}, ...view.policies.map((policy) => el("div.list-row", {},
        el("div.grow", {},
          el("div.title", {}, policy.title),
          el("div.meta", {}, [policy.policyholders.join(", "),
            policy.coverUntil && t("protect.until", { date: formatDate(policy.coverUntil) })].filter(Boolean).join(" · ")),
        ),
        el("div.amount", {}, money(policy.sumAssuredFormatted, policy.sumAssured)),
      ))),
    ),
    firstCaveat && notice(firstCaveat),
    otherCaveats.length > 0 && el("details", {},
      el("summary.caption", {}, t("protect.more")),
      ...otherCaveats.map((caveat) => el("p.caption", {}, caveat)),
    ),
    el("div.row", {},
      el("button.btn.btn-sm", { type: "button", onclick: () => protectionSheet(view, redraw) },
        view.annualExpenses ? t("protect.update") : t("protect.add"))),
  );
}

function protectionSheet(view, redraw) {
  const expenses = moneyInput({ "aria-label": t("protect.expenses") });
  if (view.annualExpenses) {
    expenses.input.value = Math.round(Number(view.annualExpenses)).toString();
    expenses.input.dispatchEvent(new Event("input"));
  }
  const chosen = new Set((view.dependants || []).map((d) => d.memberId));
  const people = (state.members || []).filter((m) => !m.isMe && !m.passedAway);
  const boxes = people.map((member) => {
    const box = el("input", { type: "checkbox", checked: chosen.has(member.id), value: member.id });
    return { member, box, row: el("label.check-row", {}, box, el("span.grow", {}, el("span.check-label", {}, member.displayName))) };
  });
  const error = el("div.help.error", { style: { minHeight: "1.15rem" } });
  const save = el("button.btn.btn-primary.grow", { type: "button" }, t("app.save"));
  save.onclick = () => withBusy(save, async () => {
    error.textContent = "";
    try {
      await api.setProtectionInputs(state.household.id, {
        annualExpenses: expenses.value(),
        dependantMemberIds: boxes.filter((b) => b.box.checked).map((b) => b.member.id),
      });
      modal.close();
      await redraw();
    } catch (problem) {
      error.textContent = problem instanceof ApiError ? problem.message : t("app.somethingWrong");
    }
  });
  const modal = sheet({
    title: t("protect.title"),
    body: el("div.stack-3", {},
      field({ label: t("protect.expenses"), control: expenses, help: t("protect.expensesHelp") }),
      people.length > 0 && el("fieldset.stack-2", { style: { border: 0, padding: 0, margin: 0 } },
        el("legend", {}, t("protect.dependants")),
        ...boxes.map((b) => b.row)),
      error,
    ),
    footer: [save],
  });
}

/* -----------------------------------------------------------------------------
   The one-tap page: #/here/<token>. Nobody needs to be signed in.
   ----------------------------------------------------------------------------- */

export function hereScreen(token) {
  const result = el("div.stack-3", { "aria-live": "polite" });
  const confirm = el("button.btn.btn-primary", { type: "button" }, t("here.button"));
  confirm.onclick = () => withBusy(confirm, async () => {
    try {
      const done = await api.redeemContinuityLink(token);
      mount(result,
        el("p.row", { style: { gap: "8px", margin: 0 } }, tickMark(), el("span", {}, done.message)));
      confirm.remove();
    } catch (problem) {
      mount(result, notice(problem.message));
      confirm.remove();
    }
  });
  return el("main.narrow", {},
    el("div.card.stack-3", { "data-here": "true" },
      el("h2", {}, t("here.title")),
      el("p", {}, t("here.body")),
      el("div.row", {}, confirm),
      result,
      el("a.btn.btn-ghost", { href: "/" }, t("here.open")),
    ),
  );
}
