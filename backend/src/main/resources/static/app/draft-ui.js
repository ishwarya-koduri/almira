/* =============================================================================
   Drafts, where people see them (X-83): the "Saved on this phone" tick beside a
   field, the resume card on Home, and sending what was saved offline once the
   network is back. The rules about what may be kept are in drafts.js.
   ============================================================================= */

import { api, ApiError } from "./api.js";
import { createDrafts } from "./drafts.js";
import { el, icon, toast } from "./ui.js";
import { state, typesFlat } from "./state.js";
import { t } from "./i18n.js";

function storage() {
  try { return window.localStorage; } catch { return null; }
}

/** The signed-in person's drafts; disabled (saves nothing) before /me has loaded. */
export function myDrafts() {
  return createDrafts({ storage: storage(), userId: state.user?.id || null });
}

/** "✓ Saved on this phone", shown beside a field once what was typed is kept. */
export function draftTick() {
  return el("span.draft-tick", { "aria-hidden": "true", hidden: true },
    icon("tick", "draft-tick-mark"), t("draft.saved"));
}

/**
 * Wires one form to its draft. `fields` is a Map of key -> { field, read, set };
 * `meta()` returns { typeCode, title }. Returns { restore(), discard(), status }.
 * Fields a draft may not hold are simply never saved and never ticked.
 */
export function attachDraft(formKey, fields, meta) {
  const drafts = myDrafts();
  // One polite announcement for the whole form, not one per keystroke per field.
  const status = el("p.caption.muted.draft-status", { role: "status", "aria-live": "polite" });
  if (!drafts.enabled) return { restore: () => false, discard: () => {}, status };

  let announced = false;
  // Once saved or discarded, a keystroke still waiting on its debounce must
  // not write the draft back.
  let closed = false;
  const timers = [];
  for (const [key, control] of fields) {
    const tick = draftTick();
    const label = control.field.querySelector(":scope > span");
    (label || control.field).append(tick);
    const keep = () => {
      if (closed) return;
      const value = control.read();
      const kept = drafts.save(formKey, key, Array.isArray(value) ? value.join(",") : value, meta());
      tick.hidden = !(kept && value !== null && value !== undefined && value !== "");
      if (kept && !announced) { status.textContent = t("draft.savedHelp"); announced = true; }
    };
    control.field.addEventListener("change", keep);
    control.field.addEventListener("input", debounce(keep, 400, timers));
  }

  return {
    status,
    /** Puts a kept draft back into the form. True when there was one. */
    restore() {
      const draft = drafts.get(formKey);
      if (!draft) return false;
      for (const [key, value] of Object.entries(draft.fields)) {
        const control = fields.get(key);
        if (!control?.set) continue;
        control.set(value);
        const tick = control.field.querySelector(".draft-tick");
        if (tick) tick.hidden = false;
      }
      status.textContent = t("draft.restored");
      return true;
    },
    discard() {
      closed = true;
      timers.forEach((timer) => clearTimeout(timer.id));
      drafts.discard(formKey);
    },
  };
}

function debounce(fn, wait, timers) {
  const timer = { id: null };
  timers.push(timer);
  return () => { clearTimeout(timer.id); timer.id = setTimeout(fn, wait); };
}

/* -----------------------------------------------------------------------------
   Home: "Finish adding HDFC FD?"
   ----------------------------------------------------------------------------- */

export function resumeCard(onResume) {
  const drafts = myDrafts();
  const draft = drafts.list().find((d) => !d.meta?.householdId || d.meta.householdId === state.household?.id);
  const queued = drafts.pending().length;
  if (!draft && queued === 0) return null;

  const type = draft && typesFlat().find((x) => x.code === draft.meta?.typeCode);
  const name = draft?.meta?.title || draft?.fields?.title || type?.label || t("draft.something");
  const card = el("div.card.stack-2", { "data-resume": "true" },
    draft && el("div.row-between.wrap", {},
      el("div.stack-2", { style: { minWidth: 0 } },
        el("h4", {}, t("draft.resume", { name })),
        el("span.caption", {}, t("draft.resumeHelp")),
      ),
      el("div.row", { style: { gap: "8px" } },
        el("button.btn.btn-ghost.btn-sm", {
          type: "button",
          onclick: () => { drafts.discard(draft.formKey); card.remove(); },
        }, t("draft.discard")),
        type && el("button.btn.btn-sm", { type: "button", onclick: () => onResume(type) }, t("draft.continue")),
      ),
    ),
    queued > 0 && el("p.caption.muted", { style: { margin: 0 } }, t("draft.queued", { count: queued })),
  );
  return card;
}

/* -----------------------------------------------------------------------------
   Offline saves
   ----------------------------------------------------------------------------- */

/** True when a failed save is the network's fault rather than the server's answer. */
export function isOffline(error) {
  if (typeof navigator !== "undefined" && navigator.onLine === false) return true;
  if (error instanceof ApiError) return error.code === "offline";
  return error instanceof TypeError;
}

/** Queues a capture made offline. False when it could not be kept (the form then stays open). */
export function queueCapture(householdId, body) {
  // A client id makes a resend after a lost response a no-op on the server
  // ("already_exists"), not a second copy.
  const withId = { ...body, id: body.id || crypto.randomUUID() };
  return myDrafts().queue("capture", householdId, withId, body.title);
}

let flushing = null;

/** Sends what was saved offline. Safe to call often; one flush at a time. */
export function flushQueued(afterSent) {
  if (flushing) return flushing;
  const drafts = myDrafts();
  if (!drafts.enabled || drafts.pending().length === 0) return Promise.resolve();
  flushing = drafts.flush(async (item) => {
    try {
      await api.capture(item.householdId, item.body);
      return "sent";
    } catch (error) {
      if (isOffline(error)) throw error;
      if (error instanceof ApiError && error.code === "already_exists") return "sent";
      return "refused";
    }
  }).then(async ({ sent, refused }) => {
    if (sent.length) toast(t("draft.sent", { count: sent.length }));
    // Refused by the server (a value it will not take, or a household this
    // person has since left): say which, so it can be added again by hand.
    refused.forEach((item) => toast(t("draft.refused", { name: item.label }), { tone: "error" }));
    if (sent.length && afterSent) await afterSent();
  }).finally(() => { flushing = null; });
  return flushing;
}
