/* =============================================================================
   "Want a reminder by email or SMS when this is due?" (docs/23 "Asked when it
   helps").

   Consent to messages is opt-in (V125). Nobody is sent a reminder outside the
   app until they say yes, so the question is asked where it means something:
   right after they save something that has a date we would remind them about —
   a maturity, a premium, a renewal, a SIP, an EMI. Asked once. "Not now" keeps
   it away for ninety days (the server remembers, on every device), and a no
   given on Your data rights is an answer, so it is never asked after that.

   Nothing is pre-ticked. "Yes" stays disabled until a channel is chosen, and it
   records exactly the channels ticked, where it was asked, and the notice in
   force. The same sheet is what "Give" opens on Your data rights.

   Never why a save fails: every error here is swallowed after the record is
   already saved, and the in-app reminder exists whatever the answer.
   ============================================================================= */

import { api } from "./api.js";
import { el, sheet, toast, withBusy } from "./ui.js";
import { t } from "./i18n.js";

/** Asked once per page load at most, whatever the server says: one question, not one per save. */
let askedThisVisit = false;

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
 * After a save that made a reminder: ask, if the server says this person has not
 * answered and has not said "Not now" lately. Resolves once the sheet is shown or
 * there is nothing to ask; never throws.
 */
export async function offerRemindersOutsideTheApp() {
  if (askedThisVisit) return;
  let ask;
  try {
    ask = await api.messagesAsk();
  } catch {
    return; // An older server, or offline: no question is better than a broken one.
  }
  if (!ask?.ask || !Array.isArray(ask.channels) || !ask.channels.length) return;
  askedThisVisit = true;
  openMessagesConsent({ channels: ask.channels, askedIn: "in_context" });
}

/**
 * The question itself. [channels] are the ones this server offers; [askedIn] is
 * `in_context` or `settings`; [onAnswered] runs after a yes or a "Not now".
 */
export function openMessagesConsent({ channels, askedIn, onAnswered } = {}) {
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
    title: t("messagesConsent.title"),
    body: el("div.stack-3", { "data-messages-consent": "" },
      el("p", { style: { margin: 0 } }, t("messagesConsent.body")),
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
      try { await api.messagesNotNow(); } catch { /* asked again next visit at worst */ }
    }
    modal.close();
    onAnswered?.(false);
  });

  return modal;
}
