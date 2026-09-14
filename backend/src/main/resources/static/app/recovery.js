/* =============================================================================
   Who else can open this? (docs/12 §10)

   A forgotten passphrase used to be the end of everything sealed, and a family
   could never read "where the will is". This is the other way in, made on this
   device and never seen by the server:

     · a recovery sheet — one code, printed, kept with the will;
     · recovery shares — three codes, one each for three people, any two open it;
     · a practice unlock — type the code, see it open, change nothing.

   The order of making a copy matters and is kept: the codes are made here and
   shown first, and only when the person says they have printed or written them
   down is the copy saved. A copy on the server whose paper was never kept would
   be a second way in that nobody can use.

   Codes live in this module's closures for as long as a sheet is open, and are
   dropped when it closes. They are never put in a URL, localStorage, a toast,
   or a request.
   ============================================================================= */

import { api } from "./api.js";
import { el, mount, sheet, field, textInput, withBusy, toast, segmented } from "./ui.js";
import { state } from "./state.js";
import { t, localDate } from "./i18n.js";
import {
  e2e, RECOVERY_KEY, RECOVERY_SHARES, prepareRecovery, saveRecovery, practiseRecovery,
  recoverPassphrase, openWithTheirRecovery,
} from "./e2e.js";

/* -----------------------------------------------------------------------------
   Small marks
   ----------------------------------------------------------------------------- */

const LOCK_SVG =
  '<svg viewBox="0 0 16 16" width="16" height="16" focusable="false">' +
  '<path class="shackle" d="M5 7V5a3 3 0 0 1 6 0v2" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>' +
  '<rect x="3.25" y="7" width="9.5" height="7" rx="1.5" fill="currentColor"/></svg>';

/** A lock. `closed` animates the shackle down, which is the whole of the "it just sealed" moment. */
export function lockMark(closed = true) {
  return el("span.lock-mark", { html: LOCK_SVG, "aria-hidden": "true", "data-closed": String(closed) });
}

const TICK_SVG =
  '<svg viewBox="0 0 24 24" width="28" height="28" focusable="false">' +
  '<circle cx="12" cy="12" r="11" fill="currentColor"/>' +
  '<path d="M7 12.5l3.2 3.2L17 9" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/></svg>';

const tick = () => el("span.tick-mark", { html: TICK_SVG, "aria-hidden": "true" });

/**
 * "Sealed on this device. Almira can't read it." beside a field that is about
 * to be sealed — the moment anxiety peaks is the moment to say it. The lock
 * closes once there is something in the field to seal.
 */
export function sealReassurance(controls, messageKey = "seal.reassurance") {
  const mark = lockMark(false);
  const sync = () => {
    mark.dataset.closed = String(controls.some((control) => control.value && control.value.length > 0));
  };
  controls.forEach((control) => control.addEventListener("input", sync));
  sync();
  return el("p.seal-note", { "data-seal-reassurance": "true" }, mark, el("span", {}, t(messageKey)));
}

/* -----------------------------------------------------------------------------
   Who could open a sealed line, in the reader's language
   ----------------------------------------------------------------------------- */

function joinNames(names) {
  if (names.length <= 1) return names.join("");
  return `${names.slice(0, -1).join(", ")} ${t("seal.and")} ${names[names.length - 1]}`;
}

/**
 * Built from the fields, so it can be translated; the server's English
 * `sentence` says the same thing and is used when a field is missing.
 */
export function sealedLineText(access) {
  if (!access) return t("where.theirs");
  if (typeof access.sealedByMe !== "boolean") return access.sentence || t("where.theirs");
  const name = access.sealedByName || t("seal.someoneWhoLeft");
  const head = access.sealedByMe ? t("seal.byMe") : t("seal.by", { name });
  const ways = [];
  if (access.hasRecoveryKey) {
    if (access.recoveryKeyHolder) ways.push(t("seal.sheetWith", { holder: access.recoveryKeyHolder }));
    else ways.push(access.sealedByMe ? t("seal.sheetMine") : t("seal.sheetTheirs", { name }));
  }
  if (access.hasRecoveryShares) {
    const holders = access.shareHolders || [];
    if (holders.length === 0) ways.push(t("seal.shares"));
    else if (holders.length === 1) ways.push(t("seal.shareWithOne", { holder: holders[0] }));
    else ways.push(t("seal.sharesWith", { holders: joinNames(holders) }));
  }
  if (ways.length) return [head, ways.join(` · ${t("seal.or")} `)].join(" · ");
  return [head, access.sealedByMe ? t("seal.onlyMine") : t("seal.onlyTheirs", { name })].join(" · ");
}

/* -----------------------------------------------------------------------------
   Step-up: making, replacing or removing a copy asks who you are first
   ----------------------------------------------------------------------------- */

function confirmIdentity() {
  return new Promise((resolve, reject) => {
    api.stepUpRequest().then((challenge) => {
      const code = textInput({
        class: "otp-input", inputMode: "numeric", autocomplete: "one-time-code",
        maxLength: 6, placeholder: "······", "aria-label": t("recovery.stepUp.code"),
      });
      const codeField = field({
        label: challenge.channel === "email" ? t("recovery.stepUp.emailed") : t("recovery.stepUp.texted"),
        control: code, required: true, help: t("recovery.stepUp.why"),
      });
      const confirm = el("button.btn.btn-primary.grow", { type: "button" }, t("recovery.stepUp.confirm"));
      let done = false;
      const inner = sheet({
        title: t("recovery.stepUp.title"),
        onClose: () => { if (!done) reject(new Error(t("recovery.stepUp.cancelled"))); },
        body: el("div.stack-3", {},
          codeField,
          challenge.developmentCode && el("div.banner.banner-accent", {},
            el("div", {}, t("stepUp.developmentCode", { code: challenge.developmentCode })),
          ),
        ),
        footer: [confirm],
      });
      confirm.onclick = () => withBusy(confirm, async () => {
        try {
          await api.stepUpVerify({ code: code.value.trim(), requestId: challenge.requestId });
          done = true;
          inner.close();
          resolve();
        } catch (error) { codeField.setError(error.message); }
      });
      code.focus();
    }, reject);
  });
}

async function withStepUp(action) {
  try {
    return await action();
  } catch (error) {
    if (error.code !== "step_up_required") throw error;
    await confirmIdentity();
    return action();
  }
}

/* -----------------------------------------------------------------------------
   Typing codes
   ----------------------------------------------------------------------------- */

function codeField(label) {
  const input = textInput({
    autocomplete: "off", autocapitalize: "characters", spellcheck: false,
    class: "code-input", placeholder: "XXXXX-XXXXX-XXXXX-XXXXX-XXXXX-XXXXX-XXXXX-XXXXX", "aria-label": label,
  });
  return { input, node: field({ label, control: input }) };
}

/** One input for a sheet, two for shares. Returns the node and a reader. */
function codeInputs(kind) {
  const fields = kind === RECOVERY_KEY
    ? [codeField(t("recovery.code.sheet"))]
    : [codeField(t("recovery.code.shareTyped", { n: 1 })), codeField(t("recovery.code.shareTyped", { n: 2 }))];
  return {
    node: el("div.stack-2", {}, ...fields.map((f) => f.node)),
    values: () => fields.map((f) => f.input.value),
    clear: () => fields.forEach((f) => { f.input.value = ""; }),
  };
}

function kindChooser(initial, onChange) {
  let current = initial;
  const host = el("div", {});
  const draw = () => mount(host, segmented([
    { value: RECOVERY_KEY, label: t("recovery.sheet") },
    { value: RECOVERY_SHARES, label: t("recovery.shares") },
  ], current, (value) => { current = value; draw(); onChange(value); }));
  draw();
  return host;
}

/* -----------------------------------------------------------------------------
   The printed sheet
   ----------------------------------------------------------------------------- */

/**
 * Printed from a node that exists only while the print dialog is up. Plain on
 * purpose: this is read by someone who may never have opened the app.
 */
function printCodes({ kind, codes, holders }) {
  const me = state.members?.find((member) => member.isMe);
  const whose = me?.displayName || "";
  const pages = codes.map((code, index) => el("section.print-page", {},
    el("h1", {}, kind === RECOVERY_KEY ? t("recovery.print.sheetTitle") : t("recovery.print.shareTitle", { n: index + 1 })),
    el("p", {}, t("recovery.print.whose", { name: whose, household: state.household?.name || "" })),
    el("p.print-code", {}, code),
    holders[index] && el("p", {}, t("recovery.print.keptBy", { holder: holders[index] })),
    el("p", {}, kind === RECOVERY_KEY ? t("recovery.print.sheetWhat", { name: whose }) : t("recovery.print.shareWhat", { name: whose })),
    el("p", {}, t("recovery.print.how")),
    el("p", {}, t("recovery.print.made", { date: localDate(new Date().toISOString()) })),
  ));
  const host = el("div.print-sheet", {}, ...pages);
  document.body.append(host);
  const cleanup = () => { host.remove(); window.removeEventListener("afterprint", cleanup); };
  window.addEventListener("afterprint", cleanup);
  window.print();
  // Browsers that do not fire afterprint still get the node removed.
  setTimeout(cleanup, 60_000);
}

/* -----------------------------------------------------------------------------
   Making a copy
   ----------------------------------------------------------------------------- */

function makeCopy(kind, onSaved) {
  const count = kind === RECOVERY_KEY ? 1 : 3;
  const holderInputs = Array.from({ length: count }, (_, index) => textInput({
    autocomplete: "off", list: "recovery-holder-suggestions",
    placeholder: t("where.keyHolderPlaceholder"),
    "aria-label": kind === RECOVERY_KEY ? t("recovery.holder.sheet") : t("recovery.holder.share", { n: index + 1 }),
  }));
  const suggestions = el("datalist#recovery-holder-suggestions", {},
    ...t("where.keyHolderSuggestions").split("|").map((role) => el("option", { value: role.trim() })));
  const body = el("div.stack-3", {});
  const error = el("div.help.error", { role: "alert", style: { minHeight: "1.15rem" } });
  const next = el("button.btn.btn-primary.grow", { type: "button" }, t("recovery.make.next"));
  let prepared = null;

  const modal = sheet({
    title: kind === RECOVERY_KEY ? t("recovery.make.sheetTitle") : t("recovery.make.sharesTitle"),
    body,
    footer: [next],
    onClose: () => { prepared = null; },
  });

  // Step one: who will keep it. Plain text the family can read, so a role.
  mount(body,
    el("p.muted", {}, kind === RECOVERY_KEY ? t("recovery.make.sheetIntro") : t("recovery.make.sharesIntro")),
    ...holderInputs.map((input, index) => field({
      label: kind === RECOVERY_KEY ? t("recovery.holder.sheet") : t("recovery.holder.share", { n: index + 1 }),
      control: input,
      help: index === 0 ? t("recovery.holder.help") : "",
    })),
    suggestions,
    error,
  );

  next.onclick = () => withBusy(next, async () => {
    error.textContent = "";
    try {
      prepared = await prepareRecovery(kind);
      showCodes();
    } catch (problem) {
      error.textContent = problem.message;
    }
  });

  // Step two: the codes, printed or copied, and only then saved.
  function showCodes() {
    const holders = holderInputs.map((input) => input.value.trim());
    const kept = el("input", { type: "checkbox", id: "recovery-kept" });
    const save = el("button.btn.btn-primary.grow", { type: "button", disabled: true }, t("recovery.make.save"));
    kept.addEventListener("change", () => { save.disabled = !kept.checked; });
    const print = el("button.btn", { type: "button", onclick: () => printCodes({ kind, codes: prepared.codes, holders }) },
      t("recovery.make.print"));

    mount(body,
      el("p.muted", {}, kind === RECOVERY_KEY ? t("recovery.make.codesSheet") : t("recovery.make.codesShares")),
      ...prepared.codes.map((code, index) => el("div.code-block", {},
        el("span.overline", {}, kind === RECOVERY_KEY
          ? t("recovery.sheet")
          : `${t("recovery.code.share", { n: index + 1 })}${holders[index] ? ` · ${holders[index]}` : ""}`),
        el("p.code-text", {}, code),
      )),
      el("div.row", {}, print),
      el("label.row.check-row", { for: "recovery-kept" }, kept, el("span", {}, t("recovery.make.kept"))),
      el("p.seal-note", {}, lockMark(true), el("span", {}, t("recovery.make.never"))),
      error,
    );
    modal.panel.querySelector(".sheet-foot").replaceChildren(save);
    save.onclick = () => withBusy(save, async () => {
      error.textContent = "";
      try {
        await withStepUp(() => saveRecovery(state.household.id, prepared, holders.filter(Boolean)));
        prepared = null;
        modal.close();
        toast(t("recovery.make.saved"));
        await onSaved?.();
      } catch (problem) {
        error.textContent = problem.message;
      }
    });
  }
}

/* -----------------------------------------------------------------------------
   Practising
   ----------------------------------------------------------------------------- */

function practise(kind, onDone) {
  const inputs = codeInputs(kind);
  const result = el("div.stack-2", { "aria-live": "polite" });
  const check = el("button.btn.btn-primary.grow", { type: "button" }, t("recovery.practise.check"));
  const modal = sheet({
    title: t("recovery.practise.title"),
    body: el("div.stack-3", {},
      el("p.muted", {}, kind === RECOVERY_KEY ? t("recovery.practise.introSheet") : t("recovery.practise.introShares")),
      inputs.node,
      result,
    ),
    footer: [check],
  });
  check.onclick = () => withBusy(check, async () => {
    mount(result);
    try {
      await practiseRecovery(state.household.id, kind, inputs.values());
      inputs.clear();
      mount(result, el("div.row.practice-ok", {}, tick(), el("b", {}, t("recovery.practise.ok"))));
      check.textContent = t("app.close");
      check.onclick = () => modal.close();
      await onDone?.();
    } catch (problem) {
      mount(result, el("p.help.error", { role: "alert" }, problem.message));
    }
  });
}

/* -----------------------------------------------------------------------------
   Forgot the passphrase
   ----------------------------------------------------------------------------- */

export function forgotPassphrase(onDone) {
  let kind = RECOVERY_KEY;
  const codesHost = el("div", {});
  let inputs = codeInputs(kind);
  mount(codesHost, inputs.node);
  const chooser = kindChooser(kind, (value) => { kind = value; inputs = codeInputs(kind); mount(codesHost, inputs.node); });
  const passphrase = textInput({ type: "password", autocomplete: "new-password", "aria-label": t("recovery.forgot.new") });
  const again = textInput({ type: "password", autocomplete: "new-password", "aria-label": t("recovery.forgot.again") });
  const error = el("div.help.error", { role: "alert", style: { minHeight: "1.15rem" } });
  const go = el("button.btn.btn-primary.grow", { type: "button" }, t("recovery.forgot.go"));

  const modal = sheet({
    title: t("recovery.forgot.title"),
    body: el("div.stack-3", {},
      el("p.muted", {}, t("recovery.forgot.intro")),
      chooser,
      codesHost,
      field({ label: t("recovery.forgot.new"), control: passphrase, help: t("recovery.forgot.newHelp") }),
      field({ label: t("recovery.forgot.again"), control: again }),
      error,
    ),
    footer: [go],
  });

  go.onclick = () => withBusy(go, async () => {
    error.textContent = "";
    if (passphrase.value.length < 12) { error.textContent = t("recovery.forgot.short"); return; }
    if (passphrase.value !== again.value) { error.textContent = t("recovery.forgot.mismatch"); return; }
    try {
      await recoverPassphrase(state.household.id, kind, inputs.values(), passphrase.value);
      inputs.clear();
      modal.close();
      toast(t("recovery.forgot.done"));
      await onDone?.();
    } catch (problem) {
      // A rotation that committed and whose answer was lost looks like this
      // too (docs/12 §6): the sentence says to try the new passphrase first.
      error.textContent = problem.code ? `${problem.message} ${t("recovery.forgot.tryNew")}` : problem.message;
    }
  });
}

/* -----------------------------------------------------------------------------
   The card in Settings
   ----------------------------------------------------------------------------- */

function slotSummary(slot) {
  if (!slot) return t("recovery.none");
  const parts = [t("recovery.made", { date: localDate(slot.createdAt) })];
  if (slot.holders?.length) parts.push(t("recovery.keptBy", { holders: joinNames(slot.holders) }));
  parts.push(slot.practicedAt ? t("recovery.practised", { date: localDate(slot.practicedAt) }) : t("recovery.notPractised"));
  return parts.join(" · ");
}

/** A year since the last practice — or never — is worth a quiet word. */
const practiceDue = (slot) => !slot.practicedAt || (Date.now() - Date.parse(slot.practicedAt)) > 365 * 24 * 3600 * 1000;

export async function recoveryCard(host, redraw) {
  const status = await api.recovery(state.household.id);
  const slots = Object.fromEntries((status.slots || []).map((slot) => [slot.kind, slot]));

  const row = (kind) => {
    const slot = slots[kind];
    const actions = el("div.row.wrap", { style: { gap: "8px" } });
    if (!slot) {
      actions.append(el("button.btn.btn-sm", {
        type: "button",
        disabled: !e2e.isUnlocked,
        onclick: () => makeCopy(kind, redraw),
      }, kind === RECOVERY_KEY ? t("recovery.makeSheet") : t("recovery.makeShares")));
    } else {
      actions.append(
        el("button.btn.btn-sm", { type: "button", onclick: () => practise(kind, redraw) }, t("recovery.practise.button")),
        el("button.btn.btn-sm.btn-ghost", {
          type: "button", disabled: !e2e.isUnlocked, onclick: () => makeCopy(kind, redraw),
        }, t("recovery.replace")),
        el("button.btn.btn-sm.btn-ghost", {
          type: "button",
          onclick: (event) => withBusy(event.currentTarget, async () => {
            if (!window.confirm(kind === RECOVERY_KEY ? t("recovery.removeSheetConfirm") : t("recovery.removeSharesConfirm"))) return;
            try {
              await withStepUp(() => api.removeRecovery(state.household.id, kind));
              toast(t("recovery.removed"));
              await redraw();
            } catch (problem) { toast(problem.message, { tone: "error" }); }
          }),
        }, t("recovery.remove")),
      );
    }
    return el("div.stack-2.recovery-row", { "data-recovery-kind": kind },
      el("div.row-between.wrap", {},
        el("b", {}, kind === RECOVERY_KEY ? t("recovery.sheet") : t("recovery.shares")),
        slot && practiceDue(slot) && el("span.notice", {}, el("span.info-mark", { "aria-hidden": "true" }, "i"),
          t("recovery.practiseDue")),
      ),
      el("p.muted.text-sm", {}, kind === RECOVERY_KEY ? t("recovery.sheetWhat") : t("recovery.sharesWhat")),
      el("p.text-sm", {}, slotSummary(slot)),
      slot && slot.current === false && el("p.notice", {},
        el("span.info-mark", { "aria-hidden": "true" }, "i"), t("recovery.stale")),
      actions,
    );
  };

  return el("div.card.stack-3", { "data-recovery-card": "true" },
    el("h4", {}, t("recovery.title")),
    el("p.muted", {}, t("recovery.intro")),
    !e2e.isUnlocked && el("p.notice", {}, el("span.info-mark", { "aria-hidden": "true" }, "i"), t("recovery.unlockToMake")),
    row(RECOVERY_KEY),
    row(RECOVERY_SHARES),
    el("details", {},
      el("summary.caption", {}, t("recovery.caveats")),
      el("div.stack-2", { style: { paddingTop: "8px" } },
        ...(status.caveats || []).map((caveat) => el("p.muted.text-sm", {}, caveat))),
    ),
  );
}

/* -----------------------------------------------------------------------------
   For the family: open what someone sealed, with their sheet or shares
   ----------------------------------------------------------------------------- */

/**
 * Only reachable while an emergency window is open on that person: the server
 * answers 404 otherwise, and this says so in words rather than as an error.
 */
export function openTheirs(person, onOpened) {
  let kind = person.hasRecoveryKey ? RECOVERY_KEY : RECOVERY_SHARES;
  const codesHost = el("div", {});
  let inputs = codeInputs(kind);
  mount(codesHost, inputs.node);
  const chooser = person.hasRecoveryKey && person.hasRecoveryShares
    ? kindChooser(kind, (value) => { kind = value; inputs = codeInputs(kind); mount(codesHost, inputs.node); })
    : null;
  const error = el("div.help.error", { role: "alert", style: { minHeight: "1.15rem" } });
  const go = el("button.btn.btn-primary.grow", { type: "button" }, t("recovery.theirs.go"));
  const modal = sheet({
    title: t("recovery.theirs.title", { name: person.name }),
    body: el("div.stack-3", {},
      el("p.muted", {}, t("recovery.theirs.intro", { name: person.name })),
      chooser,
      codesHost,
      el("p.seal-note", {}, lockMark(true), el("span", {}, t("recovery.theirs.local"))),
      error,
    ),
    footer: [go],
  });
  go.onclick = () => withBusy(go, async () => {
    error.textContent = "";
    try {
      await openWithTheirRecovery(state.household.id, person.memberId, kind, inputs.values());
      inputs.clear();
      modal.close();
      await onOpened?.();
    } catch (problem) {
      error.textContent = problem.status === 404 ? t("recovery.theirs.noWindow", { name: person.name }) : problem.message;
    }
  });
}
