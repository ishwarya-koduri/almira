/* =============================================================================
   "Want a reminder by email or SMS when this is due?" (docs/23 "Asked when it
   helps").

   Consent to messages is opt-in (V125). Nobody is sent a reminder outside the
   app until they say yes, so the question is asked where it means something:
   right after they save something that has a date we would remind them about —
   a maturity, a premium, a renewal, a SIP, an EMI — and on Home, beside the
   Still true? questions, before the first digest would go outside the app.

   Asked about the thing in front of the person (V141). "Not now" keeps the
   question away from that same holding, loan or digest for ninety days (the
   server remembers, on every device); a different holding's first due date may
   ask again. A no given on Your data rights is an answer, so it is never asked
   after that.

   Asked again, and said why (V142): someone whose yes is from before channels
   could be chosen gets the same question with a line saying the choices
   changed. Their old yes sends nothing until they answer.

   Nothing is pre-ticked. "Yes" stays disabled until a channel is chosen, and it
   records exactly the channels ticked, where it was asked, and the notice in
   force. The same sheet is what "Give" opens on Your data rights.

   Never why a save fails: every error here is swallowed after the record is
   already saved, and the in-app reminder exists whatever the answer.
   ============================================================================= */

import { api } from "./api.js";
import { el, sheet, toast, withBusy } from "./ui.js";
import { t } from "./i18n.js";

/** The Still true? digest as an ask's context (V141). */
export const DIGEST_CONTEXT = Object.freeze({ contextType: "still_true_digest" });

/**
 * The contexts already asked about on this page load: one question per thing,
 * not one per save. A different holding is a different question.
 */
const askedThisVisit = new Set();
const contextKey = (context) => `${context?.contextType || "any"}:${context?.contextId || ""}`;

/** "email, SMS" — the channels a yes covers, in the reader's language. */
export function channelNames(channels) {
  return channels.map((channel) => t(`messagesConsent.short.${channel}`)).join(", ");
}

/** Whether a capture body (a holding) carries a date a reminder is made for (ReminderService.syncForInvestment). */
export function holdingImpliesReminder(body) {
  if (!body || typeof body !== "object") return false;
  const attributes = body.attributes || {};
  return Boolean(body.maturityDate || attributes.premium_due_date || attributes.renewal_date || attributes.sip_day);
}

/** Whether a liability body carries an EMI day (ReminderService.syncForLiability). */
export function liabilityImpliesReminder(body) {
  return Boolean(body && body.emiDay);
}

/**
 * After a save that made a reminder: ask about [context] — `{ contextType:
 * "investment" | "liability", contextId }` — if the server says to. Resolves once
 * the sheet is shown or there is nothing to ask; never throws.
 */
export async function offerRemindersOutsideTheApp(context) {
  const key = contextKey(context);
  if (askedThisVisit.has(key)) return;
  let ask;
  try {
    ask = await api.messagesAsk(context);
  } catch {
    return; // An older server, offline, or a record we cannot see: no question is better than a broken one.
  }
  if (!ask?.ask || !Array.isArray(ask.channels) || !ask.channels.length) return;
  askedThisVisit.add(key);
  openMessagesConsent({ channels: ask.channels, askedIn: "in_context", askingAgain: Boolean(ask.askingAgain), context });
}

/**
 * Whether Home should ask about the digest: the server's answer for the digest
 * context when it says to ask, otherwise null. Never throws.
 */
export async function loadDigestAsk() {
  try {
    const ask = await api.messagesAsk(DIGEST_CONTEXT);
    return ask?.ask && Array.isArray(ask.channels) && ask.channels.length ? ask : null;
  } catch {
    return null;
  }
}

/**
 * The calm ask on Home's To review card, before the first Still true? digest
 * would go outside the app. Two quiet buttons: "Choose how" opens the same sheet,
 * nothing ticked; "Not now" keeps it from the digest for ninety days, and from
 * nothing else. [ask] is the server's answer (loadDigestAsk).
 */
export function digestAsk(ask, { onAnswered } = {}) {
  if (!ask) return null;
  const block = el("div.notice-line", { "data-digest-ask": "", style: { marginTop: "12px", alignItems: "flex-start" } });
  const done = (yes) => { block.remove(); onAnswered?.(yes); };
  const choose = el("button.btn.btn-sm", { type: "button" }, t("messagesConsent.digest.choose"));
  choose.onclick = () => openMessagesConsent({
    channels: ask.channels, askedIn: "in_context", askingAgain: Boolean(ask.askingAgain),
    context: DIGEST_CONTEXT, digest: true, onAnswered: done,
  });
  const notNow = el("button.btn.btn-ghost.btn-sm", { type: "button" }, t("messagesConsent.notNow"));
  notNow.onclick = () => withBusy(notNow, async () => {
    try { await api.messagesNotNow(DIGEST_CONTEXT); } catch { /* asked again next visit at worst */ }
    done(false);
  });
  block.append(
    el("span.notice-mark", { "aria-hidden": "true" }, "ⓘ"),
    el("div.stack-2", { style: { minWidth: 0 } },
      el("span", {}, ask.askingAgain ? t("messagesConsent.askingAgain") : t("messagesConsent.digest.prompt")),
      el("div.row.wrap", {}, choose, notNow)),
  );
  return block;
}

/**
 * The question itself. [channels] are the ones this server offers; [askedIn] is
 * `in_context` or `settings`; [askingAgain] says the choices changed (V142);
 * [context] is what "Not now" is kept for (V141); [digest] words it for the Still
 * true? digest; [onAnswered] runs after a yes or a "Not now".
 */
export function openMessagesConsent({ channels, askedIn, askingAgain = false, context, digest = false, onAnswered } = {}) {
  const chosen = new Set();
  const yes = el("button.btn.btn-primary", { type: "button", disabled: true }, t("messagesConsent.yes"));
  const notNow = el("button.btn.btn-ghost", { type: "button" },
    askedIn === "settings" ? t("messagesConsent.cancel") : t("messagesConsent.notNow"));
  const error = el("div.help.error", { role: "alert", style: { minHeight: "1.15rem" } });

  const boxes = channels.map((channel) => {
    const id = `messages-consent-${channel}`;
    // Never pre-ticked (Rule 3): an unticked box is not a yes.
    const box = el("input", { type: "checkbox", id });
    box.onchange = () => {
      if (box.checked) chosen.add(channel); else chosen.delete(channel);
      yes.disabled = chosen.size === 0;
    };
    return el("label.consent-declaration", { for: id }, box, el("span", {}, t(`messagesConsent.channel.${channel}`)));
  });

  const modal = sheet({
    title: digest ? t("messagesConsent.digest.title") : t("messagesConsent.title"),
    body: el("div.stack-3", { "data-messages-consent": "" },
      askingAgain && el("p", { style: { margin: 0 }, "data-asking-again": "" }, t("messagesConsent.askingAgain")),
      el("p", { style: { margin: 0 } }, digest ? t("messagesConsent.digest.body") : t("messagesConsent.body")),
      el("fieldset.stack-2", { style: { border: 0, margin: 0, padding: 0 } },
        el("legend.caption", {}, t("messagesConsent.choose")),
        ...boxes),
      el("p.notice-line", {},
        el("span.notice-mark", { "aria-hidden": "true" }, "ⓘ"),
        el("span", {}, t("messagesConsent.promise"))),
      error,
    ),
    footer: [notNow, yes],
  });

  yes.onclick = () => withBusy(yes, async () => {
    if (!chosen.size) return;
    try {
      await api.changeConsent("messages", true, {
        channels: channels.filter((channel) => chosen.has(channel)),
        askedIn,
      });
      modal.close();
      toast(t("messagesConsent.yesToast"));
      onAnswered?.(true);
    } catch (failure) {
      error.textContent = failure.message;
    }
  });

  notNow.onclick = () => withBusy(notNow, async () => {
    // From Settings, closing is only closing: it was not a question we asked.
    if (askedIn !== "settings") {
      try { await api.messagesNotNow(context); } catch { /* asked again next visit at worst */ }
    }
    modal.close();
    onAnswered?.(false);
  });

  return modal;
}
