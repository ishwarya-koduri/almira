/* =============================================================================
   For my family (docs/03 §8, docs/10 Phase 3).

   The screen someone opens twice: once while setting it up, and once — by
   somebody else — on the worst day of their life. So it leads with what exists
   and where it is, keeps the claim steps one tap away, and puts the print
   button where a person who has never used the app can find it.

   Everything here is filtered by what the reader may see. A trusted contact
   under an open emergency window sees the continuity records and the will; a
   member sees their own and whatever the household shares. Same screen, same
   code, different rows — because the filtering happens in the database.
   ============================================================================= */

import { api, downloadAuthenticated } from "../api.js";
import {
  el, mount, sheet, field, textInput, select, skeletonRows, empty, withBusy, toast, formatDate,
} from "../ui.js";
import { state } from "../state.js";
import { t } from "../i18n.js";

export async function continuityScreen(host) {
  mount(host, skeletonRows(4));

  const [handbook, mismatches, estate, contacts, trusted, requests] = await Promise.all([
    api.handbook(state.household.id),
    api.mismatches(state.household.id).catch(() => []),
    api.estateDocuments(state.household.id).catch(() => []),
    api.contacts(state.household.id).catch(() => []),
    api.trustedContacts(state.household.id).catch(() => []),
    api.emergencyRequests(state.household.id).catch(() => []),
  ]);

  mount(host, el("div.stack", {},
    el("div.row-between.wrap", {},
      el("h2", {}, t("continuity.title")),
      printButton(),
    ),
    el("p.muted", {}, t("continuity.intro")),

    mismatches.length > 0 && mismatchCard(mismatches),
    handbookCard(handbook, host),
    estateCard(estate, host),
    contactsCard(contacts, host),
    emergencyCard(trusted, requests, host),
  ));
}

function printButton() {
  const button = el("button.btn.btn-primary.btn-sm", { type: "button" }, t("continuity.print"));
  button.onclick = () => withBusy(button, async () => {
    try {
      await downloadAuthenticated(
        api.handbookPdfUrl(state.household.id), "almira-family-handbook.pdf",
      );
    } catch (error) {
      toast(error.message, { tone: "error" });
    }
  });
  return button;
}

/* -----------------------------------------------------------------------------
   Nominee ≠ heir. The one thing on this screen that is genuinely urgent.
   ----------------------------------------------------------------------------- */

function mismatchCard(mismatches) {
  return el("div.card.stack-2", { style: { borderColor: "var(--caution)" } },
    el("h3", {}, t("estate.mismatch.title")),
    ...mismatches.map((mismatch) => el("div.stack-2", {},
      el("b", {}, mismatch.title),
      el("div.muted", {},
        `${t("continuity.nominee")}: ${mismatch.nominees.join(", ")} · ` +
        `${t("estate.beneficiaries")}: ${mismatch.heirs.join(", ")}`),
      el("p.caption.muted", {}, mismatch.explanation),
    )),
  );
}

/* -----------------------------------------------------------------------------
   What there is
   ----------------------------------------------------------------------------- */

function handbookCard(handbook, host) {
  if (handbook.entries.length === 0) {
    return el("div.card", {}, empty({
      title: t("continuity.title"),
      body: "Nothing is marked for the family summary yet. Anything you record is included " +
        "by default — you can leave individual things out.",
    }));
  }

  return el("div.card.stack-3", {},
    el("div.row-between.wrap", {},
      el("h3", {}, `${t("continuity.included")} · ${handbook.entries.length}`),
      el("b", {}, handbook.totalIncludedFormatted),
    ),
    handbook.excludedCount > 0 && el("p.caption.muted", {}, handbook.note),

    el("div.list", {}, ...handbook.entries.map((entry) => el("div.list-row", {
      role: "button", tabIndex: 0,
      onclick: () => openTransmission(entry, host),
      onkeydown: (event) => { if (event.key === "Enter") openTransmission(entry, host); },
    },
      el("div", {},
        el("div.title", {}, entry.title),
        el("div.meta", {},
          [
            entry.institutionName,
            entry.reference,
            entry.whereItIsKept && `${t("continuity.keptAt")} ${entry.whereItIsKept}`,
            entry.nominees.length ? `${t("continuity.nominee")}: ${entry.nominees.join(", ")}`
              : t("continuity.noNominee"),
          ].filter(Boolean).join(" · "),
        ),
      ),
      el("div.amount", {}, el("b", {}, entry.valueFormatted || "—")),
    ))),

    handbook.debts.length > 0 && el("div.stack-2", {},
      el("span.overline", {}, t("home.owed")),
      ...handbook.debts.map((debt) => el("div.row-between", {},
        el("span", {}, `${debt.title}${debt.lender ? ` — ${debt.lender}` : ""}`),
        el("span.muted", {}, debt.outstandingFormatted || "—"),
      )),
    ),
  );
}

/** "How your family claims this" — the Transmission Assistant, per holding. */
async function openTransmission(entry, host) {
  const body = el("div.stack-3", {}, skeletonRows(3));
  const modal = sheet({ title: t("continuity.howToClaim"), body });

  let guide;
  try {
    guide = await api.transmission(state.household.id, entry.investmentId);
  } catch (error) {
    mount(body, el("div.banner", {}, error.message));
    return;
  }

  mount(body, el("div.stack-3", {},
    el("div", {},
      el("h3", {}, guide.title),
      el("div.caption.muted", {},
        [guide.typeLabel, guide.institutionName].filter(Boolean).join(" · ")),
    ),
    el("div.banner", {}, guide.summary),

    el("div.stack-2", {},
      el("span.overline", {}, t("continuity.steps")),
      ...guide.steps.map((step, index) => el("div.stack-2", {},
        el("b", {}, `${index + 1}. ${step.step}`),
        el("p.caption.muted", {}, step.detail),
      )),
    ),

    el("div.stack-2", {},
      el("span.overline", {}, t("continuity.documents")),
      ...guide.documents.map((document) => el("div.row-between", {},
        el("span", {}, document.name),
        el("span.caption", {
          style: { color: document.ready ? "var(--positive, var(--ink-muted))" : "var(--ink-muted)" },
        }, document.ready ? t("continuity.ready") : t("continuity.notReady")),
      )),
    ),

    guide.contacts.length > 0 && el("div.stack-2", {},
      el("span.overline", {}, t("continuity.call")),
      ...guide.contacts.map((contact) => el("div.row-between", {},
        el("span", {}, `${contact.name} (${contact.kind})`),
        el("span.muted", {}, contact.phone || ""),
      )),
    ),

    el("p.caption.muted", {}, guide.contactHint),
    guide.typicalDays && el("p.caption.muted", {},
      `Usually takes about ${guide.typicalDays} days once everything is in.`),
    el("p.caption.muted", {}, guide.disclaimer),
  ));
}

/* -----------------------------------------------------------------------------
   Wills, powers of attorney, and the people who hold them
   ----------------------------------------------------------------------------- */

function estateCard(documents, host) {
  return el("div.card.stack-3", {},
    el("div.row-between.wrap", {},
      el("h3", {}, t("estate.title")),
      el("button.btn.btn-sm", { type: "button", onclick: () => newEstateDocument(host) },
        t("estate.add")),
    ),
    documents.length === 0
      ? el("p.caption.muted", {}, t("estate.empty"))
      : el("div.stack-2", {}, ...documents.map((document) => el("div.stack-2", {},
          el("div.row-between", {},
            el("b", {}, document.title),
            el("span.caption.muted", {}, document.kind),
          ),
          document.location && el("div.caption.muted", {},
            `${t("estate.location")}: ${document.location}`),
          document.roles.length > 0 && el("div.caption.muted", {},
            `${t("estate.executor")}: ${document.roles.map((r) => r.name).join(", ")}`),
          document.beneficiaries.length > 0 && el("div.caption.muted", {},
            `${t("estate.beneficiaries")}: ${document.beneficiaries.map((b) =>
              `${b.name}${b.investmentTitle ? ` (${b.investmentTitle})` : ""}`).join(", ")}`),
        ))),
  );
}

function newEstateDocument(host) {
  const title = textInput({ placeholder: "Ishwarya's will", "aria-label": "Title" });
  const kind = select({
    options: [
      { value: "will", label: "Will" },
      { value: "codicil", label: "Codicil" },
      { value: "poa", label: "Power of attorney" },
      { value: "living_will", label: "Living will" },
      { value: "trust", label: "Trust deed" },
      { value: "nomination_letter", label: "Nomination letter" },
      { value: "other", label: "Something else" },
    ],
    "aria-label": "Kind",
  });
  const member = select({
    options: state.members.map((m) => ({
      value: m.id, label: m.isMe ? `${m.displayName} (me)` : m.displayName,
    })),
    value: state.members.find((m) => m.isMe)?.id,
    "aria-label": "Whose",
  });
  const location = textInput({
    placeholder: "Home locker, second shelf", "aria-label": "Where the original is",
  });
  const executedOn = textInput({ type: "date", "aria-label": "Executed on" });
  const visibility = select({
    options: [
      { value: "private", label: "Private — only me" },
      { value: "household", label: `Shared with ${state.household.name}` },
    ],
    "aria-label": "Who can see this",
  });
  const error = el("div.help.error", { style: { minHeight: "1.15rem" } });
  const save = el("button.btn.btn-primary.grow", { type: "button" }, t("app.save"));

  save.onclick = () => withBusy(save, async () => {
    error.textContent = "";
    if (!title.value.trim()) { error.textContent = "Give it a name."; return; }
    try {
      await api.createEstateDocument(state.household.id, {
        memberId: member.value,
        kind: kind.value,
        title: title.value.trim(),
        location: location.value.trim() || null,
        executedOn: executedOn.value || null,
        visibility: visibility.value,
      });
      modal.close();
      toast("Recorded.");
      await continuityScreen(host);
    } catch (apiError) {
      error.textContent = apiError.message;
    }
  });

  const modal = sheet({
    title: t("estate.add"),
    body: el("div.stack-3", {},
      el("p.caption.muted", {},
        "Almira records that the document exists and where it is. It does not draft it, " +
        "and nothing here is legal advice."),
      field({ label: "What is it?", control: kind }),
      field({ label: "Name", control: title, required: true }),
      field({ label: "Whose is it?", control: member }),
      field({
        label: t("estate.location"), control: location,
        help: "The single most useful line here — a will nobody can find is a will that does not exist.",
      }),
      field({ label: "Signed on", control: executedOn }),
      field({ label: "Who can see it?", control: visibility }),
      error,
    ),
    footer: [save],
  });
}

/* -----------------------------------------------------------------------------
   People who help
   ----------------------------------------------------------------------------- */

function contactsCard(contacts, host) {
  return el("div.card.stack-3", {},
    el("div.row-between.wrap", {},
      el("h3", {}, t("contacts.title")),
      el("button.btn.btn-sm", { type: "button", onclick: () => newContact(host) }, t("contacts.add")),
    ),
    contacts.length === 0
      ? el("p.caption.muted", {}, t("contacts.empty"))
      : el("div.list", {}, ...contacts.map((contact) => el("div.list-row", {},
          el("div", {},
            el("div.title", {}, contact.name),
            el("div.meta", {},
              [contact.kind, contact.organisation, contact.phone].filter(Boolean).join(" · ")),
          ),
          contact.links.length > 0 && el("div.amount", {},
            el("span.caption.muted", {},
              `${contact.links.length} ${contact.links.length === 1 ? "record" : "records"}`)),
        ))),
  );
}

function newContact(host) {
  const name = textInput({ placeholder: "Ramesh Rao", "aria-label": "Name" });
  const kind = select({
    options: [
      { value: "ca", label: "Chartered accountant" },
      { value: "agent", label: "Insurance agent" },
      { value: "lawyer", label: "Lawyer" },
      { value: "banker", label: "Banker" },
      { value: "broker", label: "Broker" },
      { value: "advisor", label: "Advisor" },
      { value: "other", label: "Someone else" },
    ],
    "aria-label": "Kind",
  });
  const organisation = textInput({ placeholder: "Rao & Associates", "aria-label": "Organisation" });
  const phone = textInput({ placeholder: "98765 43210", "aria-label": "Phone" });
  const error = el("div.help.error", { style: { minHeight: "1.15rem" } });
  const save = el("button.btn.btn-primary.grow", { type: "button" }, t("app.save"));

  save.onclick = () => withBusy(save, async () => {
    error.textContent = "";
    if (!name.value.trim()) { error.textContent = "Who is it?"; return; }
    try {
      await api.createContact(state.household.id, {
        name: name.value.trim(),
        kind: kind.value,
        organisation: organisation.value.trim() || null,
        phone: phone.value.trim() || null,
        visibility: "household",
      });
      modal.close();
      toast("Added.");
      await continuityScreen(host);
    } catch (apiError) {
      error.textContent = apiError.message;
    }
  });

  const modal = sheet({
    title: t("contacts.add"),
    body: el("div.stack-3", {},
      field({ label: "Name", control: name, required: true }),
      field({ label: "What do they do?", control: kind }),
      field({ label: "Organisation", control: organisation }),
      field({ label: "Phone", control: phone, help: "The number your family would ring." }),
      error,
    ),
    footer: [save],
  });
}

/* -----------------------------------------------------------------------------
   Emergency access
   ----------------------------------------------------------------------------- */

function emergencyCard(trusted, requests, host) {
  const mine = trusted.filter((contact) => !contact.theyTrustMe);
  const trustsMe = trusted.filter((contact) => contact.theyTrustMe);

  return el("div.card.stack-3", {},
    el("h3", {}, t("emergency.title")),
    el("p.caption.muted", {},
      "Someone you name can ask to see what's marked for the family. Nothing opens for the " +
      "waiting period, you're told the moment they ask, and you can stop it at any point."),

    mine.length === 0
      ? el("div.row", {},
          el("button.btn.btn-sm", { type: "button", onclick: () => nameTrusted(host) },
            `＋ ${t("emergency.trusted")}`))
      : el("div.stack-2", {},
          ...mine.map((contact) => el("div.row-between", {},
            el("span", {}, contact.trustedMemberName),
            el("span.caption.muted", {}, `${t("emergency.wait")}: ${contact.waitDays} days`),
          )),
          el("div.row", {},
            el("button.btn.btn-sm", { type: "button", onclick: () => nameTrusted(host) },
              `＋ ${t("emergency.trusted")}`)),
        ),

    trustsMe.length > 0 && el("div.stack-2", {},
      el("span.overline", {}, "You are trusted by"),
      ...trustsMe.map((contact) => {
        const ask = el("button.btn.btn-sm", { type: "button" }, t("emergency.request"));
        ask.onclick = () => withBusy(ask, async () => {
          await api.requestEmergencyAccess(state.household.id, { subjectMemberId: contact.memberId });
          toast("Asked. They've been told, and can stop it.");
          await continuityScreen(host);
        });
        return el("div.row-between", {}, el("span", {}, contact.memberName), ask);
      }),
    ),

    requests.length > 0 && el("div.stack-2", {},
      el("span.overline", {}, "Requests"),
      ...requests.map((request) => requestRow(request, host)),
    ),
  );
}

function requestRow(request, host) {
  const actions = el("div.row", { style: { gap: "8px" } });

  if (request.status === "waiting" || request.status === "open") {
    if (!request.requestedByMe) {
      const veto = el("button.btn.btn-sm.btn-danger", { type: "button" }, t("emergency.veto"));
      veto.onclick = () => withBusy(veto, async () => {
        await api.vetoEmergencyAccess(state.household.id, request.id);
        toast("Stopped. Nothing was opened.");
        await continuityScreen(host);
      });
      actions.append(veto);
    } else {
      const withdraw = el("button.btn.btn-sm", { type: "button" }, t("emergency.withdraw"));
      withdraw.onclick = () => withBusy(withdraw, async () => {
        await api.withdrawEmergencyAccess(state.household.id, request.id);
        await continuityScreen(host);
      });
      actions.append(withdraw);
    }
  }

  return el("div.stack-2", {},
    el("div.row-between", {},
      el("span", {},
        request.requestedByMe
          ? `You asked about ${request.subjectName}`
          : `${request.requestedByName} asked about ${request.subjectName}`),
      el("span.chip.chip-static", {}, t(`emergency.status.${request.status}`)),
    ),
    el("p.caption.muted", {}, request.explanation),
    request.status === "waiting" && el("p.caption.muted", {},
      `Opens ${formatDate(request.unlockAt)}.`),
    actions,
  );
}

function nameTrusted(host) {
  const who = select({
    options: state.members.filter((m) => !m.isMe).map((m) => ({ value: m.id, label: m.displayName })),
    "aria-label": "Who",
  });
  const wait = select({
    options: [
      { value: "7", label: "7 days" },
      { value: "14", label: "14 days" },
      { value: "30", label: "30 days" },
      { value: "60", label: "60 days" },
    ],
    value: "14",
    "aria-label": "Waiting period",
  });
  const error = el("div.help.error", { style: { minHeight: "1.15rem" } });
  const save = el("button.btn.btn-primary.grow", { type: "button" }, t("app.save"));

  save.onclick = () => withBusy(save, async () => {
    error.textContent = "";
    try {
      await api.nameTrustedContact(state.household.id, {
        trustedMemberId: who.value,
        waitDays: Number(wait.value),
      });
      modal.close();
      toast("Named. They've been told.");
      await continuityScreen(host);
    } catch (apiError) {
      error.textContent = apiError.message;
    }
  });

  const modal = sheet({
    title: t("emergency.trusted"),
    body: el("div.stack-3", {},
      el("p.caption.muted", {},
        "If you can't be reached, this person can ask to see what's marked for the family. " +
        "They can't see anything today, and you'll be told the moment they ask."),
      field({ label: "Who?", control: who, required: true }),
      field({
        label: t("emergency.wait"), control: wait,
        help: "How long you have to say no. Long enough to notice, short enough to matter.",
      }),
      error,
    ),
    footer: [save],
  });
}
