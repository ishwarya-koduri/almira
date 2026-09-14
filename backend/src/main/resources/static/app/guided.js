/* =============================================================================
   Guided flows (X-58, docs/03 §8.4).

   Naming an emergency contact, recording a will, writing down where an original
   is kept: each used to be one long sheet, all at once. Grief-design guidance is
   plain about why that fails — a person doing this has little attention to
   spare — so each is now one question per screen, a progress line, and "Take
   your time".

   The place in the flow is saved at every step (V91). A flow names which of its
   answers the server may keep with that place; a sealed flow keeps none, and
   seals and saves each answer through the sealed path as its step is left, so
   "saved at every step" is true there too without a plain copy anywhere.
   ============================================================================= */

import { api } from "./api.js";
import { el, mount, sheet, withBusy, notice } from "./ui.js";
import { state } from "./state.js";
import { t } from "./i18n.js";

/**
 * @param flow     emergency_setup | estate_document | where_and_who
 * @param subject  "<recordType>:<recordId>" for a per-record flow, else ""
 * @param title    the sheet's title
 * @param keep     answer keys the server may keep with the step
 * @param steps    [{ key, question, help?, build(answers) → { node, value(), error?() },
 *                    skip?(answers), save?(value, answers) }]
 * @param finish   { label, run(answers) } — the last step's button and what it does
 */
export async function guidedFlow({ flow, subject = "", title, keep = [], steps, finish }) {
  const household = state.household.id;
  let answers = {};
  let index = 0;
  let resumed = false;

  const draft = await api.guidedDraft(household, flow, subject).catch(() => null);
  if (draft) {
    answers = { ...draft.answers };
    index = Math.min(draft.step, steps.length - 1);
    resumed = index > 0;
  }

  const body = el("div.guided", {});
  const footer = el("div.guided-foot", {});
  const modal = sheet({ title, body, footer: [footer] });

  const visible = () => steps.filter((step) => !step.skip?.(answers));
  const kept = () => Object.fromEntries(Object.entries(answers).filter(([key, value]) =>
    keep.includes(key) && value !== undefined && value !== null && value !== ""));

  const saveStep = (step) => api.saveGuidedDraft(household, flow, subject, { step, answers: kept() })
    .then(() => true)
    .catch(() => false);

  async function draw() {
    const list = visible();
    // A step skipped by an earlier answer moves the position to the next that shows.
    while (index < steps.length && steps[index].skip?.(answers)) index += 1;
    if (index >= steps.length) index = steps.length - 1;
    const step = steps[index];
    const position = list.indexOf(step) + 1;
    const control = await step.build(answers);
    const error = el("p.help.error", { role: "alert" });
    const savedNote = el("p.caption.guided-saved", { "aria-live": "polite" });

    mount(body,
      el("div.guided-progress", {},
        el("span.caption", {}, t("guided.progress", { n: position, total: list.length })),
        el("div.meter", {
          role: "progressbar", "aria-valuemin": 0, "aria-valuemax": list.length, "aria-valuenow": position,
          "aria-label": t("guided.progress", { n: position, total: list.length }),
        }, el("div.meter-fill", { style: { width: `${Math.round((position / list.length) * 100)}%` } })),
      ),
      resumed && position > 1 && notice(t("guided.welcomeBack")),
      el("h2.guided-question", { tabIndex: -1 }, step.question),
      step.help && el("p.muted", {}, step.help),
      control.node,
      error,
      el("p.caption", {}, t("guided.takeYourTime")),
      savedNote,
    );
    resumed = false;
    body.querySelector(".guided-question")?.focus();

    const last = position === list.length;
    const back = el("button.btn.btn-ghost", {
      type: "button", disabled: position === 1,
      onclick: async () => {
        answers[step.key] = control.value();
        const previous = [...steps.slice(0, index)].reverse().find((candidate) => !candidate.skip?.(answers));
        index = previous ? steps.indexOf(previous) : 0;
        await saveStep(index);
        draw();
      },
    }, t("guided.back"));
    const stop = el("button.btn.btn-ghost", {
      type: "button",
      onclick: async () => {
        answers[step.key] = control.value();
        await saveStep(index);
        modal.close();
      },
    }, t("guided.stopHere"));
    const next = el("button.btn.btn-primary.grow", { type: "button" }, last ? finish.label : t("guided.next"));
    next.onclick = () => withBusy(next, async () => {
      error.textContent = "";
      const problem = control.error?.();
      if (problem) { error.textContent = problem; return; }
      const value = control.value();
      answers[step.key] = value;
      try {
        await step.save?.(value, answers);
        if (last) {
          await finish.run(answers);
          await api.discardGuidedDraft(household, flow, subject).catch(() => undefined);
          modal.close();
          return;
        }
      } catch (problemSaving) {
        error.textContent = problemSaving.message || t("app.somethingWrong");
        return;
      }
      const following = steps.slice(index + 1).find((candidate) => !candidate.skip?.(answers));
      index = following ? steps.indexOf(following) : index;
      const ok = await saveStep(index);
      await draw();
      if (!ok) body.querySelector(".guided-saved").textContent = t("guided.notSaved");
    });

    mount(footer, el("div.row-between.wrap", {}, el("div.row", {}, back, stop), next));
  }

  await draw();
  return modal;
}

/** A choice as large buttons, one per row: easier to hit than a select, and every option in sight. */
export function choiceList(options, value, label) {
  let current = value;
  const node = el("div.choice-list", { role: "radiogroup", "aria-label": label });
  const draw = () => mount(node, ...options.map((option) => el("button.choice", {
    type: "button", role: "radio", "aria-checked": option.value === current,
    onclick: () => {
      current = option.value;
      draw();
      node.querySelector('[aria-checked="true"]')?.focus();
    },
  },
    el("span.choice-label", {}, option.label),
    option.help && el("span.caption", {}, option.help),
  )));
  draw();
  return { node, value: () => current };
}
