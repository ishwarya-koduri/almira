/* =============================================================================
   Heir mode (X-40, docs/03 §8.3).

   Opened by the person holding an open emergency window, usually on a phone,
   usually on a bad day. So it is its own quiet place, not the family plan with
   fewer things on it:

     · one task per screen, with why it matters and the steps, and nothing else;
     · "You can stop here" on every screen — the place is kept, and coming back
       says where they were;
     · a task can be handed to up to five relatives, each by their own link;
     · no totals and no amounts anywhere, no navigation to the rest of the app,
       and none of the app's notices — no reminders, no "still true?", nothing
       about the person that was written for a different day.

   It is laid out for 375px first and checked at 200% text; every control is a
   whole-width button of at least 44px.
   ============================================================================= */

import { api, ApiError } from "../api.js";
import { el, mount, sheet, field, textInput, withBusy, toast, notice, skeletonRows, formatDate } from "../ui.js";
import { state } from "../state.js";
import { t } from "../i18n.js";

export async function heirScreen(host, requestId) {
  mount(host, skeletonRows(3));
  const household = state.household.id;

  const requests = await api.emergencyRequests(household).catch(() => []);
  const request = requests.find((candidate) => candidate.id === requestId);
  if (!request || !request.requestedByMe || request.status !== "open") {
    mount(host, closedScreen(request));
    return;
  }

  let plan;
  let viewing = null;

  function show(next, { fresh = false } = {}) {
    plan = next;
    if (plan.paused && !fresh) { mount(host, welcomeBack(plan, resume)); return; }
    const task = plan.tasks.find((candidate) => candidate.id === (viewing || plan.nextTaskId));
    if (!task) { mount(host, allDone(plan, () => { viewing = plan.tasks[0]?.id; show(plan); })); return; }
    mount(host, taskScreen(plan, task, actions));
    host.querySelector("h1")?.focus();
  }

  async function resume() {
    show(await api.resumeHeirPlan(household, requestId), { fresh: true });
  }

  const actions = {
    done: async (task) => { viewing = null; show(await api.setHeirTask(household, requestId, task.id, "done")); },
    later: async (task) => { viewing = null; show(await api.setHeirTask(household, requestId, task.id, "later")); },
    reopen: async (task) => { viewing = task.id; show(await api.setHeirTask(household, requestId, task.id, "todo")); },
    stop: async () => { mount(host, stoppedScreen(await api.pauseHeirPlan(household, requestId), resume)); },
    open: (task) => { viewing = task.id; show(plan); },
    all: () => openAllTasks(plan, (task) => actions.open(task)),
    share: (task) => {
      viewing = task.id;
      openHelpers(plan, task, requestId, (next) => { plan = next; }, () => show(plan, { fresh: true }));
    },
  };

  try {
    plan = await api.heirPlan(household, requestId);
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 404) throw error;
    mount(host, situationScreen(request, async (situation) => {
      show(await api.startHeirPlan(household, requestId, situation), { fresh: true });
    }));
    return;
  }
  // Every return re-reads what the window shows now, keeping what was done.
  plan = await api.startHeirPlan(household, requestId, plan.situation).catch(() => plan);
  show(plan);
}

/* --- the screens ------------------------------------------------------------- */

function frame(...children) {
  return el("div.heir", {}, ...children);
}

function closedScreen(request) {
  return frame(
    el("h1.heir-title", { tabIndex: -1 }, t("heir.closed.title")),
    el("p", {}, request ? request.explanation : t("heir.closed.body")),
    el("a.btn.btn-block", { href: "#/continuity" }, t("heir.leave")),
  );
}

function situationScreen(request, choose) {
  const button = (situation, label) => {
    const node = el("button.btn.btn-block.heir-choice", { type: "button" }, label);
    node.onclick = () => withBusy(node, () => choose(situation));
    return node;
  };
  return frame(
    el("p.caption", {}, t("heir.takeYourTime")),
    el("h1.heir-title", { tabIndex: -1 }, t("heir.situation.question", { name: request.subjectName })),
    el("p.muted", {}, t("heir.situation.help")),
    el("div.stack-3", {},
      button("passed_away", t("heir.situation.passedAway", { name: request.subjectName })),
      button("cannot_manage", t("heir.situation.cannotManage", { name: request.subjectName })),
    ),
    notice(t("heir.situation.change")),
  );
}

function progress(plan, task) {
  const position = plan.tasks.indexOf(task) + 1;
  return el("div.guided-progress", {},
    el("span.caption", {}, t("heir.progress", { n: position, total: plan.taskCount, done: plan.doneCount })),
    el("div.meter", {
      role: "progressbar", "aria-valuemin": 0, "aria-valuemax": plan.taskCount, "aria-valuenow": plan.doneCount,
      "aria-label": t("heir.doneOf", { done: plan.doneCount, total: plan.taskCount }),
    }, el("div.meter-fill", { style: { width: `${plan.taskCount ? Math.round((plan.doneCount / plan.taskCount) * 100) : 0}%` } })),
  );
}

function taskScreen(plan, task, actions) {
  const busy = (label, run, spec = "button.btn.btn-block") => {
    const node = el(spec, { type: "button" }, label);
    node.onclick = () => withBusy(node, () => run(task));
    return node;
  };
  const done = task.status === "done";
  return frame(
    progress(plan, task),
    el("h1.heir-title", { tabIndex: -1 }, task.title),
    el("p.heir-why", {}, task.why),
    task.whereToStart && el("p.caption", {}, t("heir.startWith", { where: task.whereToStart })),
    task.steps.length > 0 && el("ol.heir-steps", {}, ...task.steps.map((step) => el("li", {},
      el("b", {}, step.step), step.detail && el("span.muted", {}, step.detail)))),
    task.helperName && notice(t("heir.helping", { name: task.helperName })),
    done && notice(t("heir.doneOn", { date: formatDate(task.doneAt) })),
    el("div.heir-actions", {},
      done
        ? busy(t("heir.reopen"), actions.reopen, "button.btn.btn-block")
        : busy(t("heir.done"), actions.done, "button.btn.btn-primary.btn-block"),
      !done && busy(t("heir.later"), actions.later),
      busy(t("heir.share"), actions.share),
    ),
    el("div.heir-foot", {},
      el("button.btn.btn-ghost.btn-block", { type: "button", onclick: () => actions.all() }, t("heir.seeAll")),
      busy(t("heir.stopHere"), actions.stop, "button.btn.btn-ghost.btn-block"),
      el("p.caption", {}, t("heir.stopHereHelp")),
    ),
  );
}

function welcomeBack(plan, resume) {
  const next = plan.tasks.find((task) => task.id === plan.nextTaskId);
  const carryOn = el("button.btn.btn-primary.btn-block", { type: "button" }, t("heir.carryOn"));
  carryOn.onclick = () => withBusy(carryOn, resume);
  return frame(
    el("h1.heir-title", { tabIndex: -1 }, t("heir.welcomeBack")),
    el("p", {}, t("heir.doneOf", { done: plan.doneCount, total: plan.taskCount })),
    next && el("p.muted", {}, t("heir.nextIs", { title: next.title })),
    carryOn,
    el("a.btn.btn-ghost.btn-block", { href: "#/continuity" }, t("heir.notNow")),
  );
}

function stoppedScreen(plan, resume) {
  const back = el("button.btn.btn-block", { type: "button" }, t("heir.carryOn"));
  back.onclick = () => withBusy(back, resume);
  return frame(
    el("h1.heir-title", { tabIndex: -1 }, t("heir.stopped.title")),
    el("p", {}, t("heir.stopped.body")),
    el("p.muted", {}, t("heir.doneOf", { done: plan.doneCount, total: plan.taskCount })),
    back,
    el("a.btn.btn-ghost.btn-block", { href: "#/continuity" }, t("heir.leave")),
  );
}

function allDone(plan, review) {
  return frame(
    el("h1.heir-title", { tabIndex: -1 }, t("heir.allDone.title")),
    el("p", {}, t("heir.allDone.body")),
    el("button.btn.btn-block", { type: "button", onclick: review }, t("heir.seeAll")),
    el("a.btn.btn-ghost.btn-block", { href: "#/continuity" }, t("heir.leave")),
    notice(t("heir.windowCloses", { date: formatDate(plan.windowClosesAt) })),
  );
}

/* --- the whole list, and sharing a task ---------------------------------------- */

function openAllTasks(plan, open) {
  const modal = sheet({
    title: t("heir.allTasks"),
    body: el("ol.heir-list", {}, ...plan.tasks.map((task) => el("li", {},
      el("button.heir-list-row", {
        type: "button",
        onclick: () => { modal.close(); open(task); },
      },
        el("span", {}, task.title),
        el("span.caption", {},
          [t(`heir.status.${task.status}`), task.helperName].filter(Boolean).join(" · ")),
      )))),
  });
}

function openHelpers(plan, task, requestId, onChange, onClose) {
  const household = state.household.id;
  const body = el("div.stack-3", {});
  const modal = sheet({ title: t("heir.share"), body, onClose });

  const draw = (current, issued) => {
    const assign = (helperId) => async () => {
      const next = await api.assignHeirTask(household, requestId, task.id, helperId);
      onChange(next);
      draw(next);
      toast(helperId ? t("heir.handed") : t("heir.takenBack"));
    };
    const currentTask = current.tasks.find((candidate) => candidate.id === task.id) || task;
    const name = textInput({ maxLength: 80, "aria-label": t("heir.helper.name"), autocomplete: "off" });
    const relationship = textInput({ maxLength: 40, "aria-label": t("heir.helper.relationship"), autocomplete: "off" });
    const add = el("button.btn.btn-block", { type: "button" }, t("heir.helper.add"));
    const error = el("p.help.error", { role: "alert" });
    add.onclick = () => withBusy(add, async () => {
      error.textContent = "";
      try {
        const helper = await api.addHeirHelper(household, requestId, {
          name: name.value.trim(), relationship: relationship.value.trim() || null,
        });
        const next = await api.assignHeirTask(household, requestId, task.id, helper.id);
        onChange(next);
        draw(next, helper);
      } catch (problem) {
        error.textContent = problem.message;
      }
    });

    mount(body,
      el("p.muted", {}, t("heir.helper.explain", { title: currentTask.title })),
      issued && linkCard(issued),
      current.helpers.length > 0 && el("div.stack-2", {},
        ...current.helpers.map((helper) => {
          const handed = currentTask.helperId === helper.id;
          const remove = el("button.btn.btn-danger.btn-sm", { type: "button" }, t("app.remove"));
          remove.onclick = () => withBusy(remove, async () => {
            const next = await api.removeHeirHelper(household, requestId, helper.id);
            onChange(next);
            draw(next);
          });
          return el("div.heir-helper", {},
            el("div", {},
              el("b", {}, helper.name),
              el("div.caption", {}, [helper.relationship, t(helper.taskCount === 1 ? "heir.helper.oneTask" : "heir.helper.tasks", { count: helper.taskCount })].filter(Boolean).join(" · ")),
            ),
            el("div.row.wrap", {},
              el("button.btn.btn-sm", { type: "button", "aria-pressed": handed, onclick: assign(handed ? null : helper.id) },
                handed ? t("heir.helper.takeBack") : t("heir.helper.handTo", { name: helper.name })),
              remove,
            ),
          );
        })),
      current.helpers.length < current.maxHelpers
        ? el("div.stack-2", {},
            el("span.overline", {}, t("heir.helper.someoneNew")),
            field({ label: t("heir.helper.name"), control: name, required: true }),
            field({ label: t("heir.helper.relationship"), control: relationship }),
            error,
            add,
          )
        : notice(t("heir.helper.full", { max: current.maxHelpers })),
      notice(t("heir.helper.privacy")),
    );
  };
  draw(plan);
  return modal;
}

/** The link exists here once. Copy it or send it; Almira does not send it for them. */
function linkCard(helper) {
  const copy = el("button.btn.btn-primary.btn-block", { type: "button" }, t("heir.helper.copy"));
  copy.onclick = async () => {
    try {
      if (navigator.share) await navigator.share({ title: t("heir.helper.shareTitle"), url: helper.url });
      else { await navigator.clipboard.writeText(helper.url); toast(t("heir.helper.copied")); }
    } catch { /* closed the share sheet */ }
  };
  return el("div.card.card-tight.stack-2", {},
    el("b", {}, t("heir.helper.linkFor", { name: helper.name })),
    el("p.caption.heir-link", {}, helper.url),
    copy,
    el("p.caption", {}, t("heir.helper.once")),
  );
}
