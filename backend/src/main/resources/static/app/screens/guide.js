/* =============================================================================
   The guide: what to do next, and what the words mean (P-23, X-35, X-42,
   docs/03 §1.6).

   Four parts, in the order someone needs them:

     1. Checklists that tick themselves. Nothing here is ticked by hand: the
        server asks the records this person can see whether each thing is
        there, so a tick is a fact and disappears if the record does.
     2. The readiness check's three gaps, and a way to answer it again.
     3. Short explainers for the handful of ideas the rest of the app assumes —
        a nominee is not an heir; sealed means we cannot read it.
     4. Every hard word, with its one paragraph.

   Nothing here is advice, and the page says so once, quietly.
   ============================================================================= */

import { api } from "../api.js";
import { el, mount, sheet, skeletonRows, notice, icon, segmented, withBusy, toast } from "../ui.js";
import { state } from "../state.js";
import { t } from "../i18n.js";
import { glossaryList, term } from "../glossary.js";

const QUESTIONS = ["will", "nominees", "papers", "second_person", "one_list", "insurance", "loans", "locker"];
const ANSWERS = ["yes", "partly", "no", "not_sure"];

/** Where each checklist item is done. */
const WHERE = {
  readiness_check: null,
  first_record: "#/shelves",
  second_person: "#/family",
  a_goal: "#/goals",
  nominee: "#/investments",
  where_papers: "#/where",
  a_contact: "#/continuity",
  trusted_person: "#/continuity",
  will: "#/continuity",
};

/** Explainers: a line icon, a title and a few sentences; the glossary term they lean on. */
const EXPLAINERS = [
  { code: "nominee_heir", icon: "insurance", term: "nominee" },
  { code: "sealed", icon: "almirah", term: "sealed" },
  { code: "private", icon: "you", term: "private" },
  { code: "handbook", icon: "reports", term: "will" },
];

function checklistCard(list, retake) {
  return el("section.card.stack-3", { "data-checklist": list.code, "aria-label": t(`guide.list.${list.code}`) },
    el("div.row-between.wrap", {},
      el("h3", {}, t(`guide.list.${list.code}`)),
      el("span.caption", {}, t("guide.list.progress", { done: list.done, total: list.total })),
    ),
    el("ul.check-list", {},
      ...list.items.map((item) => {
        const href = WHERE[item.code];
        return el("li.check-item", { "data-done": String(item.done) },
          el("span.check-box", { "aria-hidden": "true" }, item.done ? icon("tick") : null),
          el("div.grow.stack-2", {},
            el("span", {},
              el("span.sr-only", {}, item.done ? t("guide.item.done") : t("guide.item.notYet")),
              t(`guide.item.${item.code}`)),
            el("span.caption", {}, t(`guide.item.${item.code}.why`)),
          ),
          !item.done && (item.code === "readiness_check"
            ? el("button.btn.btn-sm", { type: "button", onclick: retake }, t("guide.item.doIt"))
            : href && el("a.btn.btn-sm", { href }, t("guide.item.doIt"))),
        );
      })),
  );
}

function checkCard(check, retake) {
  if (!check?.answered) {
    return el("section.card.stack-2", {},
      el("h3", {}, t("check.title")),
      el("p.caption", { style: { margin: 0 } }, t("check.intro")),
      el("div.row", {}, el("button.btn.btn-sm", { type: "button", onclick: retake }, t("check.start"))),
    );
  }
  return el("section.card.stack-3", { "data-check": "true" },
    el("div.row-between.wrap", {},
      el("h3", {}, t("guide.check.title")),
      el("button.btn.btn-ghost.btn-sm", { type: "button", onclick: retake }, t("guide.check.again")),
    ),
    check.gaps.length === 0
      ? el("p", { style: { margin: 0 } }, t("check.result.none"))
      : el("ol.gap-list.stack-2", {}, ...check.gaps.map((gap) => el("li", {},
          el("div", {}, el("b", {}, t(`check.gap.${gap.question}`))),
          el("div.caption", {}, t(`check.gap.${gap.question}.do`))))),
  );
}

/** Answering again, in one sheet: eight rows of four choices. */
function retakeSheet(previous, onSaved) {
  const answers = { ...(previous?.answers || {}) };
  const rows = QUESTIONS.map((code) => {
    const host = el("div.stack-2", {});
    const draw = () => {
      const choices = segmented(ANSWERS.map((a) => ({ value: a, label: t(`check.a.${a}`) })), answers[code], (value) => {
        answers[code] = value; draw();
        host.querySelector('[aria-pressed="true"]')?.focus();
      });
      choices.setAttribute("aria-labelledby", `q-${code}`);
      mount(host, el("span", { id: `q-${code}` }, t(`check.q.${code}`)), choices);
    };
    draw();
    return host;
  });
  const save = el("button.btn.btn-primary.grow", { type: "button" }, t("app.save"));
  const error = el("div.help.error", { role: "alert" });
  const modal = sheet({
    title: t("check.title"),
    body: el("div.stack-3", {}, ...rows, error),
    footer: [save],
  });
  save.onclick = () => withBusy(save, async () => {
    if (Object.keys(answers).length === 0) { error.textContent = t("guide.check.answerOne"); return; }
    try {
      await api.answerReadinessCheck(answers);
      modal.close();
      toast(t("guide.check.saved"));
      await onSaved();
    } catch (apiError) { error.textContent = apiError.message; }
  });
}

export async function guideScreen(host) {
  mount(host, skeletonRows(4));
  const [lists, check] = await Promise.all([
    api.checklists(state.household.id).catch(() => []),
    api.readinessCheck().catch(() => null),
  ]);
  const redraw = () => guideScreen(host);
  const retake = () => retakeSheet(check, redraw);

  mount(host, el("div.stack", {},
    el("div.stack-2", {},
      el("h1", {}, t("guide.title")),
      el("p.muted", { style: { margin: 0 } }, t("guide.intro")),
    ),
    ...lists.map((list) => checklistCard(list, retake)),
    checkCard(check, retake),
    el("section.stack-3", { "aria-labelledby": "guide-explainers" },
      el("h2#guide-explainers", {}, t("guide.explainers")),
      el("div.grid.grid-2", {},
        ...EXPLAINERS.map((explainer) => el("article.card.stack-2.explainer", {},
          el("div.row", { style: { gap: "12px", alignItems: "center" } },
            icon(explainer.icon, "explainer-icon"),
            el("h3", {}, t(`guide.explain.${explainer.code}.title`)),
          ),
          el("p", { style: { margin: 0 } }, t(`guide.explain.${explainer.code}.body`)),
          el("span.caption", {}, `${t("guide.explain.word")} `, term(explainer.term)),
        ))),
    ),
    el("section.card.stack-3", { "aria-labelledby": "guide-words" },
      el("h2#guide-words", {}, t("guide.words")),
      glossaryList(),
    ),
    notice(t("guide.notAdvice")),
  ));
}
