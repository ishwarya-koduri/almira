/* =============================================================================
   Your data rights (docs/23 "Your data rights").

   Five plain cards, each one tap: See · Correct · Erase · Nominate · Complain.
   Below them, what you have agreed to, purpose by purpose, with withdrawing
   exactly as easy as giving; who can act for you; your requests; and the
   consent history as a dated timeline.

   Every reply names the grievance contact and the date we reply by, because the
   moment someone needs it is the moment they are least inclined to look for it.

   Reached from Settings, not from the navigation: it is somewhere you go on
   purpose, not somewhere you live.
   ============================================================================= */

import { api } from "../api.js";
import { el, mount, sheet, field, textInput, withBusy, toast } from "../ui.js";
import { state } from "../state.js";
import { hasRoute, navigate } from "../app.js";
import { t, localDate } from "../i18n.js";
import { openPrivacyNotice } from "../privacy.js";
import { confirmItsYou } from "../step-up.js";
import { openClosure } from "../lifecycle.js";
import { openMessagesConsent, channelNames } from "../message-consent.js";

export async function rightsScreen(host) {
  const [overview, history, trusted] = await Promise.all([
    api.privacy(),
    api.consentHistory().catch(() => []),
    state.household ? api.trustedContacts(state.household.id).catch(() => []) : [],
  ]);
  const redraw = () => rightsScreen(host);

  mount(host, el("div.stack", { "data-rights": "" },
    el("div", {},
      el("h1", {}, t("rights.title")),
      el("p.muted", { style: { margin: 0 } }, t("rights.intro")),
    ),
    el("nav.rights-grid", { "aria-label": t("rights.title") },
      card("see", () => openSee()),
      card("correct", () => openRequest("correction", overview.grievance, redraw)),
      card("erase", () => openErase()),
      card("nominate", () => openNominate(overview.nominees, redraw)),
      card("complain", () => openRequest("grievance", overview.grievance, redraw)),
    ),
    grievanceLine(overview.grievance),
    consentCard(overview, redraw),
    whoCanActCard(overview.nominees, trusted, redraw),
    overview.requests.length ? requestsCard(overview.requests, redraw) : null,
    historyCard(history),
  ));
}

function card(key, onclick) {
  return el("button.rights-card", { type: "button", onclick },
    el("span.rights-card-title", {}, t(`rights.card.${key}`)),
    el("span.caption", {}, t(`rights.card.${key}.body`)),
  );
}

/** A notice is muted text with an information mark, never a coloured box. */
function note(...children) {
  return el("p.notice-line", {},
    el("span.notice-mark", { "aria-hidden": "true" }, "ⓘ"),
    el("span", {}, ...children),
  );
}

function grievanceLine(grievance) {
  if (!grievance.configured) {
    return note(t("rights.grievance.unnamed", { days: grievance.respondWithinDays }));
  }
  return note(t("rights.grievance.named", {
    name: grievance.name, email: grievance.email, days: grievance.respondWithinDays,
  }));
}

/* --- what you have agreed to ------------------------------------------------ */

function consentCard(overview, redraw) {
  const accepted = overview.acceptedNoticeVersion === overview.noticeVersion;
  const accept = el("button.btn.btn-sm", { type: "button" }, t("rights.notice.accept"));
  accept.onclick = () => withBusy(accept, async () => {
    try { await api.acceptNotice(overview.noticeVersion); toast(t("rights.notice.accepted")); redraw(); }
    catch (error) { toast(error.message, { tone: "error" }); }
  });

  return el("section.card.stack-3", {},
    el("h3", {}, t("rights.consent.title")),
    el("div.list", {},
      ...overview.consents.map((consent) => consentRow(consent, redraw)),
    ),
    el("div.row-between.wrap", {},
      el("div", {},
        el("div", {}, t("rights.notice.version", { date: localDate(overview.noticeVersion) })),
        el("div.caption", {},
          accepted ? t("rights.notice.acceptedOn", { date: localDate(overview.acceptedAt) })
                   : t("rights.notice.notAccepted"),
          overview.noticeLegallyReviewed ? "" : ` · ${t("rights.notice.draft")}`),
      ),
      el("div.row", {},
        el("button.btn.btn-ghost.btn-sm", { type: "button", onclick: () => openPrivacyNotice() },
          t("privacy.open")),
        accepted ? null : accept,
      ),
    ),
  );
}

function consentRow(consent, redraw) {
  const status = consent.given === true
    ? [t("rights.consent.givenOn", { date: localDate(consent.changedAt) }),
       consent.channels?.length ? t("rights.consent.channels", { channels: channelNames(consent.channels) }) : null,
      ].filter(Boolean).join(" · ")
    : consent.given === false
      ? t("rights.consent.withdrawnOn", { date: localDate(consent.changedAt) })
      : t("rights.consent.notAsked");

  // Giving and withdrawing are the same control, the same size, in the same
  // place: withdrawal as easy as consent (Rule 3).
  let action;
  if (consent.required) {
    action = el("button.btn.btn-sm", { type: "button", onclick: () => openErase() },
      t("rights.consent.withdrawByClosing"));
  } else {
    const giving = consent.given !== true;
    action = el("button.btn.btn-sm", { type: "button" },
      giving ? t("rights.consent.give") : t("rights.consent.withdraw"));
    action.onclick = () => withBusy(action, async () => {
      try {
        // A yes to messages chooses its channels, none ticked for you (V125): the
        // same sheet that asks when a reminder is first made.
        if (giving && consent.purpose === "messages") {
          const ask = await api.messagesAsk();
          openMessagesConsent({ channels: ask.channels, askedIn: "settings", onAnswered: (yes) => yes && redraw() });
          return;
        }
        await api.changeConsent(consent.purpose, giving);
        toast(giving ? t("rights.consent.gaveToast") : t("rights.consent.withdrewToast"));
        redraw();
      } catch (error) { toast(error.message, { tone: "error" }); }
    });
  }
  // Records is given by using the service; say so rather than show "not asked".
  const shownStatus = consent.required && consent.given == null ? t("rights.consent.byUsing") : status;

  return el("div.list-row", { style: { cursor: "default" } },
    el("div.grow", {},
      el("div.title", {}, t(`rights.purpose.${consent.purpose}`)),
      el("div.meta", {}, t(`rights.purpose.${consent.purpose}.body`)),
      el("div.caption", {}, shownStatus),
    ),
    action,
  );
}

/* --- who can act for you ---------------------------------------------------- */

function whoCanActCard(nominees, trusted, redraw) {
  const mine = trusted.filter((contact) => !contact.theyTrustMe);
  return el("section.card.stack-3", {},
    el("h3", {}, t("rights.who.title")),
    el("p.caption", { style: { margin: 0 } }, t("rights.who.difference")),
    el("div.rights-pair", {},
      el("div.stack-2", {},
        el("span.overline", {}, t("rights.who.emergency")),
        mine.length
          ? el("div.stack-2", {}, ...mine.map((contact) => el("div", {}, contact.trustedMemberName)))
          : el("div.caption", {}, t("rights.who.noEmergency")),
        hasRoute("continuity") && el("button.btn.btn-ghost.btn-sm", {
          type: "button", onclick: () => navigate("continuity"),
        }, t("rights.who.manageEmergency")),
      ),
      el("div.stack-2", {},
        el("span.overline", {}, t("rights.who.nominees")),
        nominees.length
          ? el("div.stack-2", {}, ...nominees.map((nominee) => el("div", {},
            nominee.fullName, nominee.relationship ? el("span.caption", {}, ` · ${nominee.relationship}`) : null)))
          : el("div.caption", {}, t("rights.who.noNominees")),
        el("button.btn.btn-ghost.btn-sm", {
          type: "button", onclick: () => openNominate(nominees, redraw),
        }, t("rights.card.nominate")),
      ),
    ),
  );
}

/* --- requests and history --------------------------------------------------- */

function requestsCard(requests, redraw) {
  return el("section.card.stack-3", {},
    el("h3", {}, t("rights.requests.title")),
    el("div.list", {}, ...requests.map((request) => {
      const withdraw = request.status === "open" &&
        el("button.btn.btn-ghost.btn-sm", { type: "button" }, t("rights.requests.withdraw"));
      if (withdraw) {
        withdraw.onclick = () => withBusy(withdraw, async () => {
          try { await api.withdrawRightsRequest(request.id); redraw(); }
          catch (error) { toast(error.message, { tone: "error" }); }
        });
      }
      return el("div.list-row", { style: { cursor: "default" } },
        el("div.grow", {},
          el("div.title", {}, t(`rights.requests.kind.${request.kind}`)),
          el("div.meta", {}, request.details),
          el("div.caption", {},
            request.status === "open"
              ? t("rights.requests.replyBy", { date: localDate(request.respondBy) })
              : t(`rights.requests.status.${request.status}`)),
          request.response && el("p", { style: { margin: "var(--space-2) 0 0" } }, request.response),
        ),
        withdraw,
      );
    })),
  );
}

function historyCard(history) {
  return el("section.card.stack-3", {},
    el("h3", {}, t("rights.history.title")),
    history.length
      ? el("ol.timeline", {}, ...history.map((entry) => el("li", {},
        el("time.caption", { datetime: entry.at }, localDate(entry.at)),
        el("span", {}, historyText(entry)),
      )))
      : el("p.caption", { style: { margin: 0 } }, t("rights.history.empty")),
  );
}

function historyText(entry) {
  if (entry.kind === "notice") return t("rights.history.notice", { date: localDate(entry.noticeVersion) });
  if (entry.kind === "parental_consent") {
    return t(`rights.history.parental.${entry.action}`, { name: entry.subject || "" });
  }
  if (entry.action === "given" && entry.channels?.length) {
    return t("rights.history.consent.givenChannels", {
      purpose: t(`rights.purpose.${entry.purpose}`), channels: channelNames(entry.channels),
    });
  }
  return t(`rights.history.consent.${entry.action}`, { purpose: t(`rights.purpose.${entry.purpose}`) });
}

/* --- the five cards --------------------------------------------------------- */

async function openSee() {
  const body = el("div.stack-3", {}, el("p.caption", {}, t("rights.see.loading")));
  sheet({ title: t("rights.card.see"), body });
  try {
    const summary = await api.accessSummary();
    const held = summary.held.filter((item) => item.count > 0);
    const shared = summary.sharedWith;
    mount(body,
      el("p.caption", { style: { margin: 0 } }, t("rights.see.intro")),
      el("section.stack-2", {},
        el("h4", {}, t("rights.see.held")),
        el("div.caption", {}, t("rights.see.identity", {
          what: summary.identity.map((key) => t(`rights.see.identity.${key}`)).join(", "),
        })),
        el("ul.plain-list", {}, ...held.map((item) => el("li.row-between", {},
          el("span", {}, t(`rights.see.category.${item.category}`)),
          el("span.num", {}, String(item.count))))),
      ),
      el("section.stack-2", {},
        el("h4", {}, t("rights.see.why")),
        el("ul.plain-list", {}, ...summary.purposes.map((purpose) => el("li", {},
          el("div", {}, purpose.description),
          el("div.caption", {}, t(`rights.see.basis.${purpose.basis}`))))),
      ),
      el("section.stack-2", {},
        el("h4", {}, t("rights.see.shared")),
        el("ul.plain-list", {},
          ...shared.householdMembers.map((person) => el("li", {},
            t("rights.see.member", { name: person.displayName, household: person.householdName, role: person.role }))),
          ...shared.emergencyContacts.map((name) => el("li", {}, t("rights.see.emergency", { name }))),
          shared.liveGuestLinks ? el("li", {}, t("rights.see.links", { count: shared.liveGuestLinks })) : null,
          shared.nominees ? el("li", {}, t("rights.see.nominees", { count: shared.nominees })) : null,
          ...shared.messageChannels.map((channel) => el("li", {}, t("rights.see.channel", { channel }))),
        ),
        !shared.householdMembers.length && !shared.emergencyContacts.length
          ? el("p.caption", { style: { margin: 0 } }, t("rights.see.nobody")) : null,
      ),
      grievanceLine(summary.grievance),
    );
  } catch (error) {
    mount(body, el("p.caption", {}, error.message));
  }
}

function openRequest(kind, grievance, redraw) {
  const details = el("textarea.textarea", { rows: 5, maxLength: 2000, "aria-label": t(`rights.request.${kind}.label`) });
  const detailsField = field({ label: t(`rights.request.${kind}.label`), control: details, required: true,
    help: t(`rights.request.${kind}.help`) });
  const send = el("button.btn.btn-primary", { type: "button" }, t("rights.request.send"));
  const modal = sheet({
    title: t(`rights.card.${kind === "correction" ? "correct" : "complain"}`),
    body: el("div.stack-3", {}, detailsField, grievanceLine(grievance)),
    footer: [send],
  });
  send.onclick = () => withBusy(send, async () => {
    if (!details.value.trim()) { detailsField.setError(t(`rights.request.${kind}.empty`)); return; }
    try {
      const created = await api.rightsRequest({ kind, details: details.value.trim() });
      modal.close();
      toast(t("rights.request.sent", { date: localDate(created.respondBy) }));
      redraw();
    } catch (error) { detailsField.setError(error.message); }
  });
}

/**
 * Erasing is closing the account, which the lifecycle flow owns (lifecycle.js,
 * /api/v1/me/closure). A closure already under way is shown in Settings, where
 * it can be cancelled; a server without the closure API gets the words instead.
 */
export async function openErase() {
  const closure = await api.get("/api/v1/me/closure").catch(() => null);
  if (closure?.pending) { navigate("settings"); return; }
  if (closure) { openClosure(async () => navigate("settings")); return; }
  sheet({
    title: t("rights.card.erase"),
    body: el("div.stack-3", {},
      el("p", { style: { margin: 0 } }, t("rights.erase.body")),
      note(t("rights.erase.notYet")),
    ),
  });
}

function openNominate(nominees, redraw) {
  const name = textInput({ autocomplete: "off", "aria-label": t("rights.nominate.name") });
  const relationship = textInput({ autocomplete: "off", "aria-label": t("rights.nominate.relationship") });
  const contact = textInput({ autocomplete: "off", "aria-label": t("rights.nominate.contact") });
  const nameField = field({ label: t("rights.nominate.name"), control: name, required: true });
  const contactField = field({ label: t("rights.nominate.contact"), control: contact, required: true,
    help: t("rights.nominate.contactHelp") });
  const save = el("button.btn.btn-primary", { type: "button" }, t("rights.nominate.save"));

  const list = el("div.list", {}, ...nominees.map((nominee) => {
    const remove = el("button.btn.btn-ghost.btn-sm", { type: "button" }, t("rights.nominate.remove"));
    remove.onclick = () => withBusy(remove, async () => {
      try { await api.revokeNominee(nominee.id); modal.close(); redraw(); }
      catch (error) { toast(error.message, { tone: "error" }); }
    });
    return el("div.list-row", { style: { cursor: "default" } },
      el("div.grow", {}, el("div.title", {}, nominee.fullName),
        el("div.meta", {}, [nominee.relationship, nominee.contact].filter(Boolean).join(" · "))),
      remove);
  }));

  const modal = sheet({
    title: t("rights.card.nominate"),
    body: el("div.stack-3", {},
      el("p.caption", { style: { margin: 0 } }, t("rights.who.difference")),
      nominees.length ? list : null,
      nameField,
      field({ label: t("rights.nominate.relationship"), control: relationship }),
      contactField,
    ),
    footer: [save],
  });

  save.onclick = () => withBusy(save, async () => {
    if (!name.value.trim()) { nameField.setError(t("rights.nominate.nameEmpty")); return; }
    if (!contact.value.trim()) { contactField.setError(t("rights.nominate.contactEmpty")); return; }
    try {
      if (!(await confirmItsYou(t("rights.nominate.whyConfirm")))) return;
      await api.nominate({
        fullName: name.value.trim(),
        relationship: relationship.value.trim() || null,
        contact: contact.value.trim(),
      });
      modal.close();
      toast(t("rights.nominate.saved"));
      redraw();
    } catch (error) { contactField.setError(error.message); }
  });
}
