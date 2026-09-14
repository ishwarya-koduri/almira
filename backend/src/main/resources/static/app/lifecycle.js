/* =============================================================================
   The end of things, and the changes in between (docs/05 §12): closing an
   account, downloading everything, leaving a household, a successor, a memorial,
   and a child who turns eighteen.

   Every one of these is slow on purpose — thirty days, seven days, a week after a
   memorial — and every screen says so in plain words before anything is asked.
   What goes and what stays are shown side by side, and nothing is ever shown
   about someone else that the roster does not already say.
   ============================================================================= */

import { api, downloadAuthenticated } from "./api.js";
import { el, mount, sheet, field, textInput, select, toast, withBusy, formatDate } from "./ui.js";
import { state } from "./state.js";
import { reload } from "./app.js";
import { t } from "./i18n.js";

/* -----------------------------------------------------------------------------
   Confirming it's you. Runs the action; if the server asks for a step-up, sends
   a code, asks for it, and runs the action again. One step-up lasts five minutes
   on this device, so "download, then close" asks once.
   ----------------------------------------------------------------------------- */

export async function withStepUp(action) {
  try {
    return await action();
  } catch (error) {
    if (error.code !== "step_up_required") throw error;
  }
  await confirmIdentity();
  return action();
}

function confirmIdentity() {
  return new Promise((resolve, reject) => {
    api.stepUpRequest().then((challenge) => {
      const code = textInput({
        class: "otp-input", inputMode: "numeric", autocomplete: "one-time-code",
        maxLength: 6, placeholder: "······", "aria-label": t("lifecycle.code"),
      });
      const codeField = field({
        label: challenge.channel === "email" ? t("lifecycle.codeEmailed") : t("lifecycle.codeTexted"),
        control: code, required: true,
      });
      const confirm = el("button.btn.btn-primary", { type: "button" }, t("lifecycle.confirm"));
      let settled = false;
      const modal = sheet({
        title: t("lifecycle.confirmItsYou"),
        body: el("div.stack-3", {},
          el("p.lifecycle-text", {}, t("lifecycle.confirmWhy")),
          codeField,
          challenge.developmentCode && notice(`Development mode: the code is ${challenge.developmentCode}.`),
        ),
        footer: [confirm],
        onClose: () => { if (!settled) reject(new Error(t("lifecycle.notConfirmed"))); },
      });
      confirm.onclick = () => withBusy(confirm, async () => {
        try {
          await api.stepUpVerify({ code: code.value.trim(), requestId: challenge.requestId });
          settled = true;
          modal.close();
          resolve();
        } catch (error) { codeField.setError(error.message); }
      });
      code.focus();
    }).catch(reject);
  });
}

/* -----------------------------------------------------------------------------
   Pieces
   ----------------------------------------------------------------------------- */

/** Muted text with an info mark — a notice, not a coloured box. */
export function notice(...children) {
  return el("p.lifecycle-notice", {},
    el("span.lifecycle-mark", { "aria-hidden": "true" }, "i"),
    el("span", {}, ...children));
}

/** Two columns that become one on a phone, each a heading and a plain list. */
function twoColumns(leftTitle, left, rightTitle, right) {
  // Which household a line is in is worth saying only when there is more than one.
  const several = new Set([...left, ...right].map((line) => line.householdId).filter(Boolean)).size > 1;
  const column = (title, lines) => el("section.lifecycle-column", {},
    el("h5", {}, title),
    lines.length === 0
      ? el("p.lifecycle-text.muted", {}, t("lifecycle.nothing"))
      : el("ul.lifecycle-lines", {}, ...lines.map((line) => el("li", {},
          el("span.lifecycle-line-title", {}, line.title),
          several && line.householdName && line.kind !== "household" && el("span.lifecycle-line-where", {}, line.householdName),
          line.detail && el("span.lifecycle-line-detail", {}, line.detail),
        ))),
  );
  return el("div.lifecycle-columns", {}, column(leftTitle, left), column(rightTitle, right));
}

const day = (iso) => formatDate(iso);

/** Like ui.js segmented, but it keeps its own pressed state: these sheets are not redrawn per tap. */
function toggle(options, value, onChange) {
  const node = el("div.segmented", { role: "group" });
  options.forEach((option) => {
    node.append(el("button", {
      type: "button",
      "aria-pressed": option.value === value,
      onclick: (event) => {
        node.querySelectorAll("button").forEach((b) => b.setAttribute("aria-pressed", String(b === event.currentTarget)));
        onChange(option.value);
      },
    }, option.label));
  });
  return node;
}

async function downloadEverything(button) {
  await withBusy(button, async () => {
    try {
      // A download cannot read the server's error code, so the step-up is asked
      // for up front rather than on refusal.
      const { elevated } = await api.stepUpStatus();
      if (!elevated) await confirmIdentity();
      await downloadAuthenticated("/api/v1/me/export", "almira-everything.zip");
      toast(t("lifecycle.downloaded"));
    } catch (error) {
      toast(error.message, { tone: "error" });
    }
  });
}

/* -----------------------------------------------------------------------------
   Settings: your account — download everything, and closing it
   ----------------------------------------------------------------------------- */

export async function accountCard(host, redrawScreen) {
  const closure = await api.get("/api/v1/me/closure").catch(() => ({ pending: false }));
  const download = el("button.btn", { type: "button" }, t("lifecycle.downloadEverything"));
  download.onclick = () => downloadEverything(download);

  const body = el("div.stack-3", {},
    el("p.lifecycle-text", {}, t("lifecycle.downloadExplain")),
    el("div.row.wrap", {}, download),
  );

  if (closure.pending) {
    const keep = el("button.btn.btn-primary", { type: "button" }, t("lifecycle.keepAccount"));
    keep.onclick = () => withBusy(keep, async () => {
      await api.post("/api/v1/me/closure/cancel");
      toast(t("lifecycle.kept"));
      await redrawScreen();
    });
    body.append(
      notice(t("lifecycle.closingOn", { date: day(closure.closesAfter) })),
      el("div.row.wrap", {}, keep),
    );
  } else {
    const close = el("button.btn.btn-ghost", { type: "button" }, t("lifecycle.closeAccount"));
    close.onclick = () => openClosure(redrawScreen);
    body.append(el("div.row.wrap", {}, close));
  }

  return el("div.card.stack-3", {}, el("h4", {}, t("lifecycle.yourAccount")), body);
}

function openClosure(redrawScreen) {
  const content = el("div.stack-3", {});
  const footer = el("div.row.wrap", {});
  const modal = sheet({ title: t("lifecycle.closeAccount"), body: content, footer: [footer] });

  // 1 · Your copy first.
  const first = () => {
    const download = el("button.btn", { type: "button" }, t("lifecycle.downloadEverything"));
    download.onclick = () => downloadEverything(download);
    const handbook = el("button.btn", { type: "button" }, t("lifecycle.handbook"));
    handbook.onclick = () => withBusy(handbook, async () => {
      try {
        await downloadAuthenticated(api.handbookPdfUrl(state.household.id), "almira-family-handbook.pdf");
      } catch (error) { toast(error.message, { tone: "error" }); }
    });
    const next = el("button.btn.btn-primary", { type: "button" }, t("lifecycle.continue"));
    next.onclick = () => withBusy(next, second);
    mount(content,
      el("p.lifecycle-text", {}, t("lifecycle.beforeYouGo")),
      el("div.row.wrap", {}, download, handbook),
    );
    mount(footer, next);
  };

  // 2 · What goes and what stays.
  const second = async () => {
    const preview = await api.get("/api/v1/me/closure/preview");
    const next = el("button.btn.btn-primary", { type: "button", disabled: preview.blockers.length > 0 },
      t("lifecycle.continue"));
    next.onclick = third;
    mount(content,
      twoColumns(
        t("lifecycle.erasedAfter", { days: preview.waitDays }), preview.erased,
        t("lifecycle.staysWithHousehold"), preview.stays,
      ),
      ...preview.blockers.map((blocker) => notice(blocker.message)),
      notice(preview.retention),
    );
    mount(footer, next);
  };

  // 3 · In plain words, and confirmed.
  const third = () => {
    const date = new Date(Date.now() + 30 * 86400000).toISOString();
    const confirm = el("button.btn.btn-danger", { type: "button" }, t("lifecycle.closeOn", { date: day(date) }));
    confirm.onclick = () => withBusy(confirm, async () => {
      try {
        await withStepUp(() => api.post("/api/v1/me/closure"));
        modal.close();
        toast(t("lifecycle.closureRequested"));
        await redrawScreen();
      } catch (error) { toast(error.message, { tone: "error" }); }
    });
    mount(content,
      el("p.lifecycle-text", {}, t("lifecycle.closeConfirm", { date: day(date) })),
      el("p.lifecycle-text", {}, t("lifecycle.closeUndo")),
    );
    mount(footer, confirm);
  };

  first();
}

/* -----------------------------------------------------------------------------
   Family: a memorial, a departure, a successor, coming of age
   ----------------------------------------------------------------------------- */

/** Everything the family screen needs to know, fetched once. Each part fails on its own. */
export async function loadFamilyLifecycle() {
  const hid = state.household.id;
  const safe = (promise, fallback) => promise.catch(() => fallback);
  const [departures, successor, comingOfAge, requests] = await Promise.all([
    safe(api.get(`/api/v1/households/${hid}/departures`), []),
    safe(api.get(`/api/v1/households/${hid}/successor`), { named: false }),
    safe(api.get(`/api/v1/households/${hid}/coming-of-age`), []),
    safe(api.emergencyRequests(hid), []),
  ]);
  return { departures, successor, comingOfAge, requests };
}

/** Shown to someone whose account here carries the memorial label. */
export function memorialNotice(me) {
  const here = el("button.btn.btn-primary", { type: "button" }, t("lifecycle.imHere"));
  here.onclick = () => withBusy(here, async () => {
    await api.del(`/api/v1/households/${state.household.id}/members/${me.id}/memorial`);
    toast(t("lifecycle.welcomeBack"));
    await reload();
  });
  return el("div.card.stack-3", {},
    notice(t("lifecycle.memorialNotice")),
    el("div.row.wrap", {}, here),
  );
}

/** The quiet label and the actions for one person on the roster. */
export function memberLifecycle(member, context) {
  const hid = state.household.id;
  const canManage = ["owner", "admin"].includes(state.household.myRole) && !state.household.readOnly;
  const windowOpen = context.requests.some((r) =>
    r.requestedByMe && r.status === "open" && r.subjectMemberId === member.id);
  const notice18 = context.comingOfAge.find((n) => n.memberId === member.id);
  const leaving = context.departures.find((d) => d.memberId === member.id && d.status === "pending");

  const meta = [
    member.passedAway && t("lifecycle.inMemory"),
    notice18 && !notice18.hasLogin && t("lifecycle.turns18", { date: formatDate(notice18.turnsAdultOn) }),
    leaving && t("lifecycle.leavesOn", { date: formatDate(leaving.effectiveAt) }),
  ].filter(Boolean);

  const actions = [];
  if (!member.isMe && !member.passedAway && (canManage || windowOpen)) {
    actions.push(el("button.btn.btn-sm.btn-ghost", {
      type: "button", onclick: () => markPassedAway(member),
    }, t("lifecycle.markPassedAway")));
  }
  if (!member.isMe && canManage && !member.isManaged && member.role !== "owner" && !leaving && !member.passedAway) {
    actions.push(el("button.btn.btn-sm.btn-ghost", {
      type: "button", onclick: (event) => askToLeave(member, event.currentTarget),
    }, t("lifecycle.askToLeave")));
  }
  if (leaving && leaving.canCancel && !leaving.isMe) {
    const withdraw = el("button.btn.btn-sm.btn-ghost", { type: "button" }, t("lifecycle.withdraw"));
    withdraw.onclick = () => withBusy(withdraw, async () => {
      await api.post(`/api/v1/households/${hid}/departures/${leaving.id}/cancel`);
      await reload();
    });
    actions.push(withdraw);
  }
  return { meta, actions };
}

function markPassedAway(member) {
  const note = textInput({ placeholder: t("lifecycle.notePlaceholder"), "aria-label": t("lifecycle.note") });
  const confirm = el("button.btn.btn-danger", { type: "button" }, t("lifecycle.markPassedAway"));
  const modal = sheet({
    title: t("lifecycle.markTitle", { name: member.displayName }),
    body: el("div.stack-3", {},
      el("p.lifecycle-text", {}, t("lifecycle.markExplain", { name: member.displayName })),
      field({ label: t("lifecycle.note"), control: note, help: t("lifecycle.noteHelp") }),
      notice(t("lifecycle.markNext")),
    ),
    footer: [confirm],
  });
  confirm.onclick = () => withBusy(confirm, async () => {
    try {
      await withStepUp(() => api.post(
        `/api/v1/households/${state.household.id}/members/${member.id}/memorial`,
        { note: note.value.trim() || null },
      ));
      modal.close();
      await reload();
    } catch (error) { toast(error.message, { tone: "error" }); }
  });
}

async function askToLeave(member, button) {
  const confirm = el("button.btn.btn-danger", { type: "button" }, t("lifecycle.askToLeave"));
  const modal = sheet({
    title: t("lifecycle.askTitle", { name: member.displayName }),
    body: el("div.stack-3", {},
      el("p.lifecycle-text", {}, t("lifecycle.askExplain", { name: member.displayName })),
    ),
    footer: [confirm],
  });
  confirm.onclick = () => withBusy(confirm, async () => {
    try {
      await withStepUp(() => api.post(`/api/v1/households/${state.household.id}/departures`, { memberId: member.id }));
      modal.close();
      await reload();
    } catch (error) { toast(error.message, { tone: "error" }); }
  });
  button?.blur();
}

/** Leaving this household: your own card at the foot of the Family screen. */
export function leaveCard(context) {
  const mine = context.departures.find((d) => d.isMe && d.status === "pending");
  if (mine) {
    const stay = el("button.btn.btn-primary", { type: "button" }, t("lifecycle.stay"));
    stay.onclick = () => withBusy(stay, async () => {
      try {
        await api.post(`/api/v1/households/${state.household.id}/departures/${mine.id}/cancel`);
        toast(t("lifecycle.staying"));
        await reload();
      } catch (error) { toast(error.message, { tone: "error" }); }
    });
    const choices = el("button.btn", { type: "button", onclick: () => openLeave(mine) }, t("lifecycle.yourChoices"));
    return el("div.card.stack-3", {},
      el("h4", {}, t("lifecycle.leaving")),
      notice(t(mine.startedByAdmin ? "lifecycle.askedToLeaveOn" : "lifecycle.youLeaveOn", {
        date: formatDate(mine.effectiveAt), household: state.household.name,
      })),
      el("div.row.wrap", {}, mine.canCancel && stay, choices),
    );
  }
  return el("div.card.stack-3", {},
    el("h4", {}, t("lifecycle.leaveHousehold")),
    el("p.lifecycle-text", {}, t("lifecycle.leaveExplain")),
    el("div.row.wrap", {},
      el("button.btn.btn-ghost", { type: "button", onclick: () => openLeave(null) }, t("lifecycle.leaveHousehold"))),
  );
}

async function openLeave(pending) {
  const hid = state.household.id;
  const preview = await api.get(`/api/v1/households/${hid}/departures/preview`)
    .catch((error) => { toast(error.message, { tone: "error" }); return null; });
  if (!preview) return;

  let privateRecords = pending?.privateRecords || "take";
  const decisions = new Map(preview.joint.map((j) => [`${j.recordType}:${j.recordId}`, j.decision || "stays"]));

  const choice = (value, title, detail) => el("button.choice", {
    type: "button", "aria-pressed": privateRecords === value,
    onclick: (event) => {
      privateRecords = value;
      event.currentTarget.parentElement.querySelectorAll(".choice")
        .forEach((node) => node.setAttribute("aria-pressed", String(node === event.currentTarget)));
    },
  }, el("span", {}, el("span.ct", {}, title), el("br"), el("span.cd", {}, detail)), el("span.radio"));

  const joint = preview.joint.map((j) => el("div.stack-2", {},
    el("span.lifecycle-line-title", {}, j.title),
    el("span.lifecycle-line-detail", {}, t("lifecycle.heldWith", { names: j.otherHolders.join(", ") })),
    toggle(
      [{ value: "stays", label: t("lifecycle.leaveWithThem") }, { value: "take_my_share", label: t("lifecycle.takeMyPart") }],
      decisions.get(`${j.recordType}:${j.recordId}`),
      (value) => decisions.set(`${j.recordType}:${j.recordId}`, value),
    ),
  ));

  const download = el("button.btn", { type: "button" }, t("lifecycle.downloadEverything"));
  download.onclick = () => downloadEverything(download);
  const go = el("button.btn.btn-danger", { type: "button", disabled: preview.blockers.length > 0 },
    pending ? t("lifecycle.saveChoices") : t("lifecycle.leaveIn", { days: preview.waitDays }));

  const modal = sheet({
    title: t("lifecycle.leaveTitle", { household: preview.householdName }),
    body: el("div.stack-3", {},
      twoColumns(t("lifecycle.goesWithYou"), preview.goesWithYou, t("lifecycle.staysWithHousehold"), preview.staysWithHousehold),
      el("h5", {}, t("lifecycle.whatIsYours")),
      el("div.choices", {},
        choice("take", t("lifecycle.takeThem"), t("lifecycle.takeThemDetail")),
        choice("export_and_erase", t("lifecycle.eraseThem"), t("lifecycle.eraseThemDetail")),
      ),
      el("div.row.wrap", {}, download),
      joint.length > 0 && el("h5", {}, t("lifecycle.heldTogether")),
      ...joint,
      preview.sealedFieldsThatStayBehind > 0 && notice(t("lifecycle.sealedStay", { count: preview.sealedFieldsThatStayBehind })),
      ...preview.blockers.map((blocker) => notice(blocker.message)),
      notice(t("lifecycle.leaveNeutral")),
    ),
    footer: [go],
  });

  go.onclick = () => withBusy(go, async () => {
    try {
      let departure = pending;
      if (!departure) {
        departure = await withStepUp(() => api.post(`/api/v1/households/${hid}/departures`, { privateRecords }));
      }
      await api.patch(`/api/v1/households/${hid}/departures/${departure.id}`, {
        privateRecords,
        decisions: preview.joint.map((j) => ({
          recordType: j.recordType, recordId: j.recordId,
          decision: privateRecords === "take" ? decisions.get(`${j.recordType}:${j.recordId}`) : "stays",
        })),
      });
      modal.close();
      toast(pending ? t("lifecycle.saved") : t("lifecycle.leavingToast", { days: preview.waitDays }));
      await reload();
    } catch (error) { toast(error.message, { tone: "error" }); }
  });
}

/** "If you can't manage this": for the owner, and for the person they named. */
export function successorCard(context, members) {
  const hid = state.household.id;
  const s = context.successor;
  const isOwner = state.household.myRole === "owner" && !state.household.readOnly;

  if (s.named && s.youAreTheSuccessor) {
    const claim = el("button.btn.btn-primary", { type: "button" }, t("lifecycle.carryOn"));
    claim.onclick = () => withBusy(claim, async () => {
      try {
        await withStepUp(() => api.post(`/api/v1/households/${hid}/successor/claim`));
        await reload();
      } catch (error) { toast(error.message, { tone: "error" }); }
    });
    return el("div.card.stack-3", {},
      el("h4", {}, t("lifecycle.successorTitle")),
      notice(t("lifecycle.youAreNamed", { household: state.household.name })),
      s.explanation && el("p.lifecycle-text.muted", {}, s.explanation),
      s.canClaim && el("div.row.wrap", {}, claim),
    );
  }
  if (!isOwner) return null;

  const candidates = members.filter((m) => !m.isMe && !m.isManaged && !m.passedAway && m.role !== "advisor");
  const picker = select({
    options: [{ value: "", label: t("lifecycle.chooseSomeone") },
      ...candidates.map((m) => ({ value: m.id, label: m.displayName }))],
    value: s.memberId || "",
    "aria-label": t("lifecycle.successorTitle"),
  });
  const save = el("button.btn", { type: "button" }, t("lifecycle.nameThem"));
  save.onclick = () => withBusy(save, async () => {
    try {
      if (picker.value) await api.put(`/api/v1/households/${hid}/successor`, { memberId: picker.value });
      else if (s.named) await api.del(`/api/v1/households/${hid}/successor`);
      toast(t("lifecycle.saved"));
      await reload();
    } catch (error) { toast(error.message, { tone: "error" }); }
  });
  return el("div.card.stack-3", {},
    el("h4", {}, t("lifecycle.successorTitle")),
    el("p.lifecycle-text", {}, t("lifecycle.successorExplain")),
    candidates.length === 0
      ? notice(t("lifecycle.successorNobody"))
      : el("div.row.wrap", {}, picker, save),
    s.named && notice(t("lifecycle.successorNamed", { name: s.memberName })),
  );
}

/** "These are yours now": for the young adult, once, after they sign in. */
export function welcomeCard(context) {
  const mine = context.comingOfAge.find((n) => n.isMe && !n.welcomedAt);
  if (!mine) return null;
  const open = el("button.btn.btn-primary", { type: "button" }, t("lifecycle.seeWhatIsYours"));
  open.onclick = () => withBusy(open, openWelcome);
  return el("div.card.stack-3", {},
    el("h4", {}, t("lifecycle.yoursNow")),
    el("p.lifecycle-text", {}, t("lifecycle.yoursNowExplain")),
    el("div.row.wrap", {}, open),
  );
}

async function openWelcome() {
  const hid = state.household.id;
  const welcome = await api.get(`/api/v1/households/${hid}/coming-of-age/welcome`);
  const choices = new Map(welcome.records.map((r) => [`${r.recordType}:${r.recordId}`, r.visibility === "household" ? "household" : "private"]));
  const done = el("button.btn.btn-primary", { type: "button" }, t("lifecycle.done"));
  const modal = sheet({
    title: t("lifecycle.yoursNow"),
    body: el("div.stack-3", {},
      el("p.lifecycle-text", {}, t("lifecycle.welcomeExplain")),
      welcome.records.length === 0 && el("p.lifecycle-text.muted", {}, t("lifecycle.nothing")),
      ...welcome.records.map((r) => el("div.stack-2", {},
        el("span.lifecycle-line-title", {}, r.title),
        r.otherHolders.length > 0 && el("span.lifecycle-line-detail", {}, t("lifecycle.heldWith", { names: r.otherHolders.join(", ") })),
        toggle(
          [{ value: "household", label: t("lifecycle.visibleToFamily") }, { value: "private", label: t("lifecycle.onlyMe") }],
          choices.get(`${r.recordType}:${r.recordId}`),
          (value) => choices.set(`${r.recordType}:${r.recordId}`, value),
        ),
      )),
    ),
    footer: [done],
  });
  done.onclick = () => withBusy(done, async () => {
    try {
      await api.post(`/api/v1/households/${hid}/coming-of-age/welcome`, {
        choices: welcome.records.map((r) => ({
          recordType: r.recordType, recordId: r.recordId, visibility: choices.get(`${r.recordType}:${r.recordId}`),
        })),
      });
      modal.close();
      await reload();
    } catch (error) { toast(error.message, { tone: "error" }); }
  });
}
