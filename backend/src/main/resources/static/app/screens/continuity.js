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
  datedSteps, notice, segmented, moneyInput, chipRow,
} from "../ui.js";
import { guidedFlow, choiceList } from "../guided.js";
import { confirmItsYou } from "../step-up.js";
import { state } from "../state.js";
import { t } from "../i18n.js";
import { whereWhoCard, FIELD } from "../where.js";
import { lockMark, sealedLineText, openTheirs } from "../recovery.js";
import { e2e, openSealedValueAs } from "../e2e.js";
import { loadReadiness, readinessCard } from "../readiness.js";
import { openDetail } from "./detail.js";
import { navigate } from "../app.js";

export async function continuityScreen(host) {
  mount(host, skeletonRows(4));

  const [handbook, mismatches, estate, contacts, trusted, requests, readiness, sweep] = await Promise.all([
    api.handbook(state.household.id),
    api.mismatches(state.household.id).catch(() => []),
    api.estateDocuments(state.household.id).catch(() => []),
    api.contacts(state.household.id).catch(() => []),
    api.trustedContacts(state.household.id).catch(() => []),
    api.emergencyRequests(state.household.id).catch(() => []),
    loadReadiness(state.household.id),
    api.lostMoney(state.household.id).catch(() => null),
  ]);

  // Holding an open window on someone is the one thing that outranks printing:
  // heir mode takes the screen's single primary action (X-40).
  const heirWindow = requests.find((request) => request.requestedByMe && request.status === "open");

  mount(host, el("div.stack", {},
    el("div.row-between.wrap", {},
      el("h2", {}, t("continuity.title")),
      heirWindow
        ? el("button.btn.btn-primary.btn-sm", { type: "button", onclick: () => navigate(`heir/${heirWindow.id}`) },
            t("heir.open", { name: heirWindow.subjectName }))
        : printButton(),
    ),
    el("p.muted", {}, t("continuity.intro")),
    printCard(Boolean(heirWindow)),

    readiness && readinessCard(readiness, fixers(estate, host)),
    mismatches.length > 0 && mismatchCard(mismatches),
    theirSealedCard(handbook, requests, host),
    handbookCard(handbook, host),
    estateCard(estate, host, handbook),
    contactsCard(contacts, host),
    emergencyCard(trusted, requests, host),
    sweep && lostMoneyCard(sweep, host),
  ));
}

/* -----------------------------------------------------------------------------
   Ready to hand over (docs/22): where each named gap is fixed. The card is
   drawn again when that place closes, so a fix shows as a shorter list.
   ----------------------------------------------------------------------------- */

function fixers(estate, host) {
  const redraw = () => continuityScreen(host);
  return {
    record(recordType, recordId) {
      if (recordType === "investment") {
        // The holding's own sheet has the nominee card, its documents and the
        // where-and-who card: every record gap is fixed in one place.
        openDetail(recordId, redraw);
        whenLastSheetCloses(redraw);
      } else if (recordType === "estate_document") {
        const document = estate.find((item) => item.id === recordId);
        if (document) openWhere(document, host, redraw);
      }
    },
    trusted(reason) {
      // Nobody else can sign in: the fix starts with an invitation.
      if (reason === "nobody_to_name") navigate("family");
      else nameTrusted(host);
    },
  };
}

/** openDetail keeps its sheet to itself; notice when the newest one is gone. */
function whenLastSheetCloses(callback) {
  const scrims = document.querySelectorAll(".scrim");
  const opened = scrims[scrims.length - 1];
  if (!opened) return;
  const observer = new MutationObserver(() => {
    if (!opened.isConnected) { observer.disconnect(); callback(); }
  });
  observer.observe(document.body, { childList: true });
}

/**
 * Three printed things, each for a different drawer (P-28, X-61). The handbook
 * is the screen's primary action unless heir mode has taken it; these two sit
 * beside it as ordinary buttons.
 */
function printCard(handbookToo) {
  const kit = el("button.btn.btn-sm", { type: "button" }, t("kit.print"));
  kit.onclick = () => withBusy(kit, async () => {
    try {
      await downloadAuthenticated(api.emergencyKitPdfUrl(state.household.id), "almira-emergency-kit.pdf");
    } catch (error) {
      toast(error.message, { tone: "error" });
    }
  });
  const envelope = el("button.btn.btn-sm", { type: "button" }, t("envelope.print"));
  envelope.onclick = () => withBusy(envelope, async () => {
    if (!(await confirmItsYou(t("envelope.stepUp")))) return;
    try {
      await downloadAuthenticated(api.envelopePdfUrl(state.household.id), "almira-family-handbook.pdf", { method: "POST" });
      toast(t("envelope.printed"));
    } catch (error) {
      toast(error.message, { tone: "error" });
    }
  });
  return el("div.card.stack-2", { "data-print": "" },
    el("h3", {}, t("print.title")),
    el("div.print-choices", {},
      el("div.stack-2", {}, kit, el("p.caption", {}, t("kit.explain"))),
      el("div.stack-2", {}, envelope, el("p.caption", {}, t("envelope.explain"))),
      handbookToo && el("div.stack-2", {}, printButton({ primary: false }), el("p.caption", {}, t("continuity.printExplain"))),
    ),
  );
}

function printButton({ primary = true } = {}) {
  const button = el(`button.btn.btn-sm${primary ? ".btn-primary" : ""}`, { type: "button" }, t("continuity.print"));
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
  // Urgent, but not a debt: a heavier edge rather than rust (D-01).
  return el("div.card.card-emphasis.stack-2", {},
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
            entry.nominees.length ? `${t("continuity.nominee")}: ${entry.nominees.join(", ")}`
              : t("continuity.noNominee"),
          ].filter(Boolean).join(" · "),
        ),
        ...sealedLines(entry.sealed),
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

/**
 * A sealed line is never blank (docs/20 §1.2): it says it is sealed, by whom,
 * and who holds a way to open it. The words themselves stay sealed.
 */
function sealedLabel(fieldKey) {
  if (fieldKey === FIELD.originalLocation) return t("where.location");
  if (fieldKey === FIELD.keyHolder) return t("where.keyHolder");
  return t("note.title");
}

function sealedLines(lines) {
  return (lines || []).map((line) => el("div.sealed-line", { "data-sealed-line": line.fieldKey },
    lockMark(true),
    el("span", {}, `${sealedLabel(line.fieldKey)}: ${sealedLineText(line)}`),
  ));
}

/* -----------------------------------------------------------------------------
   Open what someone sealed, with their recovery sheet or shares (docs/12 §10.5)

   Offered only to the person holding an open emergency window on them, and only
   for someone who made a recovery copy. The key opened lives in this session's
   memory beside the reader's own and is dropped by Lock or a reload.
   ----------------------------------------------------------------------------- */

function theirSealedCard(handbook, requests, host) {
  const open = new Set(requests
    .filter((request) => request.requestedByMe && request.status === "open")
    .map((request) => request.subjectMemberId));
  const people = new Map();
  for (const line of [...handbook.entries, ...(handbook.instruments || [])].flatMap((item) => item.sealed || [])) {
    if (line.sealedByMe || !line.sealedByMemberId || !open.has(line.sealedByMemberId)) continue;
    if (!line.hasRecoveryKey && !line.hasRecoveryShares) continue;
    people.set(line.sealedByMemberId, {
      memberId: line.sealedByMemberId, name: line.sealedByName,
      hasRecoveryKey: line.hasRecoveryKey, hasRecoveryShares: line.hasRecoveryShares, access: line,
    });
  }
  if (people.size === 0) return null;

  return el("div.card.stack-3", { "data-their-sealed": "true" },
    el("h3", {}, t("recovery.theirs.cardTitle")),
    ...[...people.values()].map((person) => el("div.stack-2", {},
      el("p", {}, sealedLineText(person.access)),
      e2e.hasKeyFor(person.memberId)
        ? el("div.row.wrap", { style: { gap: "8px" } },
            el("button.btn.btn-primary", { type: "button", onclick: () => showTheirs(person, handbook) },
              t("recovery.theirs.show", { name: person.name })),
            el("button.btn.btn-ghost", { type: "button", onclick: () => { e2e.lock(); continuityScreen(host); } },
              t("security.lock")))
        : el("div.row", {},
            el("button.btn.btn-primary", {
              type: "button",
              onclick: () => openTheirs(person, async () => { await continuityScreen(host); showTheirs(person, handbook); }),
            }, t("recovery.theirs.open", { name: person.name }))),
    )),
  );
}

/** Everything this person sealed on what the reader can see, opened on this device. */
async function showTheirs(person, handbook) {
  const body = el("div.stack-3", {}, skeletonRows(3));
  sheet({ title: t("recovery.theirs.shownTitle", { name: person.name }), body });
  const index = await api.whereAndWho(state.household.id);
  const rows = [];
  for (const record of index.records) {
    const lines = [];
    for (const [slot, fieldKey, label] of [["originalLocation", FIELD.originalLocation, "where.location"], ["keyHolder", FIELD.keyHolder, "where.keyHolder"]]) {
      const value = record[slot];
      if (!value || value.access?.sealedByMemberId !== person.memberId) continue;
      let text;
      try {
        text = await openSealedValueAs(person.memberId, state.household.id, record.recordType, record.recordId, fieldKey, value.ciphertext);
      } catch {
        text = t("where.unreadable");
      }
      lines.push(el("div.row-between", { style: { fontSize: "var(--text-sm)", alignItems: "flex-start" } },
        el("span.muted", {}, t(label)), el("span", { style: { textAlign: "right", whiteSpace: "pre-wrap" } }, text)));
    }
    if (lines.length) {
      rows.push(el("div.card.card-tight.stack-2", {},
        el("div.row-between", {}, el("b", {}, record.title), el("span.muted.text-sm", {}, t(`where.type.${record.recordType}`))),
        ...lines));
    }
  }
  // Sealed notes and anything else they sealed, on the records the handbook names.
  const items = [
    ...handbook.entries.map((entry) => ({ recordType: "investment", recordId: entry.investmentId, title: entry.title, sealed: entry.sealed })),
    ...(handbook.instruments || []).map((item) => ({ recordType: "estate_document", recordId: item.estateDocumentId, title: item.title, sealed: item.sealed })),
  ];
  for (const item of items) {
    const keys = (item.sealed || [])
      .filter((line) => line.sealedByMemberId === person.memberId && !Object.values(FIELD).includes(line.fieldKey))
      .map((line) => line.fieldKey);
    if (!item.recordId || keys.length === 0) continue;
    const values = await api.sealedValues(state.household.id, item.recordType, item.recordId).catch(() => []);
    const lines = [];
    for (const value of values.filter((candidate) => keys.includes(candidate.fieldKey))) {
      let text;
      try {
        text = await openSealedValueAs(person.memberId, state.household.id, item.recordType, item.recordId, value.fieldKey, value.ciphertext);
      } catch {
        text = t("where.unreadable");
      }
      lines.push(el("div.stack-2", { style: { fontSize: "var(--text-sm)" } },
        el("span.muted", {}, sealedLabel(value.fieldKey)), el("span", { style: { whiteSpace: "pre-wrap" } }, text)));
    }
    if (lines.length) {
      rows.push(el("div.card.card-tight.stack-2", {},
        el("div.row-between", {}, el("b", {}, item.title), el("span.muted.text-sm", {}, t(`where.type.${item.recordType}`))),
        ...lines));
    }
  }

  mount(body,
    el("p.seal-note", {}, lockMark(true), el("span", {}, t("recovery.theirs.local"))),
    rows.length ? rows : el("p.muted", {}, t("recovery.theirs.nothing")),
  );
}

function estateCard(documents, host, handbook) {
  const sealedByDocument = new Map((handbook?.instruments || [])
    .filter((instrument) => instrument.estateDocumentId)
    .map((instrument) => [instrument.estateDocumentId, instrument.sealed || []]));
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
          el("div.row", {},
            el("button.btn.btn-sm", {
              type: "button",
              onclick: () => openWhere(document, host),
            }, t("where.cardTitle")),
          ),
          ...sealedLines(sealedByDocument.get(document.id)),
          document.roles.length > 0 && el("div.caption.muted", {},
            `${t("estate.executor")}: ${document.roles.map((r) => r.name).join(", ")}`),
          document.beneficiaries.length > 0 && el("div.caption.muted", {},
            `${t("estate.beneficiaries")}: ${document.beneficiaries.map((b) =>
              `${b.name}${b.investmentTitle ? ` (${b.investmentTitle})` : ""}`).join(", ")}`),
        ))),
  );
}

/**
 * A will's location is the line the family needs most and the line a hostile
 * relative wants most, so it is recorded sealed, and only sealed (docs/20).
 */
function openWhere(document, host, onClose) {
  sheet({
    title: document.title,
    onClose,
    body: whereWhoCard("estate_document", document.id),
  });
}

/**
 * Recording a will or other paperwork, one question at a time (X-58). The
 * answers are kept with the step so a person who stops comes back to them;
 * where the original is comes after it is saved, sealed (docs/20).
 */
function newEstateDocument(host) {
  const members = state.members.map((m) => ({
    value: m.id, label: m.isMe ? t("guided.me", { name: m.displayName }) : m.displayName,
  }));
  const kinds = [
    ["will", "estate.kind.will"], ["codicil", "estate.kind.codicil"], ["poa", "estate.kind.poa"],
    ["living_will", "estate.kind.living_will"], ["trust", "estate.kind.trust"],
    ["nomination_letter", "estate.kind.nomination_letter"], ["other", "estate.kind.other"],
  ].map(([value, key]) => ({ value, label: t(key) }));

  guidedFlow({
    flow: "estate_document",
    title: t("estate.add").replace(/^＋\s*/, ""),
    keep: ["kind", "title", "memberId", "executedOn", "visibility"],
    steps: [
      {
        key: "kind",
        question: t("estate.q.kind"),
        help: t("estate.q.kindHelp"),
        build: (answers) => choiceList(kinds, answers.kind || "will", t("estate.q.kind")),
      },
      {
        key: "memberId",
        question: t("estate.q.whose"),
        build: (answers) => choiceList(members, answers.memberId || state.members.find((m) => m.isMe)?.id, t("estate.q.whose")),
      },
      {
        key: "title",
        question: t("estate.q.title"),
        help: t("estate.q.titleHelp"),
        build: (answers) => {
          const input = textInput({ value: answers.title || "", maxLength: 160, "aria-label": t("estate.q.title") });
          return {
            node: input,
            value: () => input.value.trim(),
            error: () => (input.value.trim() ? null : t("estate.q.titleMissing")),
          };
        },
      },
      {
        key: "executedOn",
        question: t("estate.q.signed"),
        help: t("estate.q.signedHelp"),
        build: (answers) => {
          const input = textInput({ type: "date", value: answers.executedOn || "", "aria-label": t("estate.q.signed") });
          return { node: input, value: () => input.value || null };
        },
      },
      {
        key: "visibility",
        question: t("estate.q.visibility"),
        build: (answers) => choiceList([
          { value: "private", label: t("estate.q.private"), help: t("estate.q.privateHelp") },
          { value: "household", label: t("estate.q.household", { household: state.household.name }) },
        ], answers.visibility || "private", t("estate.q.visibility")),
      },
    ],
    finish: {
      label: t("app.save"),
      run: async (answers) => {
        const created = await api.createEstateDocument(state.household.id, {
          memberId: answers.memberId,
          kind: answers.kind,
          title: answers.title,
          executedOn: answers.executedOn || null,
          visibility: answers.visibility,
        });
        toast(t("estate.recorded"));
        await continuityScreen(host);
        // The one line that matters most, asked next and sealed on this device.
        if (created?.id) openWhere({ id: created.id, title: created.title || answers.title }, host);
      },
    },
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
   Emergency access, as a dated picture (X-41)
   ----------------------------------------------------------------------------- */

function emergencyCard(trusted, requests, host) {
  const mine = trusted.filter((contact) => !contact.theyTrustMe);
  const trustsMe = trusted.filter((contact) => contact.theyTrustMe);

  return el("div.card.stack-3", { "data-emergency": "" },
    el("h3", {}, t("emergency.title")),
    el("p.caption", {}, t("emergency.explain")),

    mine.length > 0 && el("div.stack-2", {},
      ...mine.map((contact) => el("div.row-between", {},
        el("span", {}, contact.trustedMemberName),
        el("span.caption", {}, t("emergency.waitDays", { days: contact.waitDays })),
      )),
    ),
    el("div.row", {},
      el("button.btn.btn-sm", { type: "button", onclick: () => nameTrusted(host) },
        `＋ ${t("emergency.trusted")}`)),

    trustsMe.length > 0 && el("div.stack-2", {},
      el("span.overline", {}, t("emergency.trustedBy")),
      ...trustsMe.map((contact) => {
        const ask = el("button.btn.btn-sm", { type: "button" }, t("emergency.request"));
        ask.onclick = () => withBusy(ask, async () => {
          await api.requestEmergencyAccess(state.household.id, { subjectMemberId: contact.memberId });
          toast(t("emergency.asked"));
          await continuityScreen(host);
        });
        return el("div.row-between.wrap", {}, el("span", {}, contact.memberName), ask);
      }),
    ),

    requests.length > 0 && el("div.stack-3", {},
      el("span.overline", {}, t("emergency.requests")),
      ...requests.map((request) => requestRow(request, host)),
    ),
  );
}

function requestRow(request, host) {
  const actions = el("div.row.wrap", { style: { gap: "8px" } });

  if (request.status === "waiting" || request.status === "open") {
    if (!request.requestedByMe) {
      const veto = el("button.btn.btn-sm.btn-danger", { type: "button" }, t("emergency.veto"));
      veto.onclick = () => withBusy(veto, async () => {
        await api.vetoEmergencyAccess(state.household.id, request.id);
        toast(t("emergency.stopped"));
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

  return el("div.stack-2", { "data-request": request.id },
    el("div.row-between.wrap", {},
      el("b", {},
        request.requestedByMe
          ? t("emergency.youAsked", { name: request.subjectName })
          : t("emergency.theyAsked", { who: request.requestedByName, name: request.subjectName })),
      el("span.chip.chip-static", {}, t(`emergency.status.${request.status}`)),
    ),
    request.timeline?.length
      ? datedSteps(request.timeline, { label: t("emergency.timelineLabel") })
      : el("p.caption", {}, request.explanation),
    request.subjectHasBeenActive && notice(request.explanation),
    actions.childElementCount > 0 && actions,
  );
}

/**
 * Naming someone, one question at a time, ending on the picture of what it
 * means — dated as if they asked today — before anything is saved (X-41, X-58).
 */
function nameTrusted(host) {
  const others = state.members.filter((m) => !m.isMe);
  if (others.length === 0) {
    toast(t("emergency.nobodyToName"));
    navigate("family");
    return;
  }

  guidedFlow({
    flow: "emergency_setup",
    title: t("emergency.trusted"),
    keep: ["trustedMemberId", "waitDays"],
    steps: [
      {
        key: "trustedMemberId",
        question: t("emergency.q.who"),
        help: t("emergency.q.whoHelp"),
        build: (answers) => choiceList(
          others.map((m) => ({ value: m.id, label: m.displayName })),
          answers.trustedMemberId || others[0].id,
          t("emergency.q.who"),
        ),
      },
      {
        key: "waitDays",
        question: t("emergency.q.wait"),
        help: t("emergency.q.waitHelp"),
        build: (answers) => choiceList(
          [7, 14, 30, 60].map((days) => ({ value: days, label: t("emergency.waitDays", { days }) })),
          answers.waitDays || 14,
          t("emergency.q.wait"),
        ),
      },
      {
        key: "confirmed",
        question: t("emergency.q.picture"),
        build: async (answers) => {
          const preview = await api.emergencyPreview(state.household.id, answers.trustedMemberId, answers.waitDays);
          const node = el("div.stack-3", {},
            el("p.caption", {}, t("emergency.q.asIfToday", { name: preview.trustedMemberName })),
            datedSteps(preview.steps, { label: t("emergency.timelineLabel") }),
            el("div.stack-2", {},
              el("span.overline", {}, t("emergency.willSee", { name: preview.trustedMemberName })),
              el("ul.plain-list", {}, ...preview.willSee.map((line) => el("li", {}, line))),
            ),
            el("div.stack-2", {},
              el("span.overline", {}, t("emergency.neverSee")),
              el("ul.plain-list", {}, ...preview.neverSee.map((line) => el("li", {}, line))),
            ),
          );
          return { node, value: () => true };
        },
      },
    ],
    finish: {
      label: t("emergency.q.confirm"),
      run: async (answers) => {
        await api.nameTrustedContact(state.household.id, {
          trustedMemberId: answers.trustedMemberId,
          waitDays: Number(answers.waitDays),
        });
        toast(t("emergency.named"));
        await continuityScreen(host);
      },
    },
  });
}

/* -----------------------------------------------------------------------------
   The lost-money sweep (P-25): one card per portal, per person. Links only.
   ----------------------------------------------------------------------------- */

function lostMoneyCard(sweep, host) {
  let memberId = state.members.find((m) => m.isMe)?.id || state.members[0]?.id;
  const portals = el("div.stack-3", {});

  const draw = () => {
    const people = chipRow(t("lostMoney.whose"), ...state.members.map((m) => el("button.chip", {
      type: "button", "aria-pressed": m.id === memberId,
      onclick: () => { memberId = m.id; draw(); },
    }, m.displayName)));
    mount(portals, people, ...sweep.portals.map((portal) => portalCard(portal,
      sweep.checks.find((check) => check.portal === portal.code && check.memberId === memberId), memberId, host)));
  };
  draw();

  return el("div.card.stack-3", { "data-lost-money": "" },
    el("h3", {}, t("lostMoney.title")),
    el("p.caption", {}, t("lostMoney.intro")),
    portals,
    notice(sweep.note),
  );
}

function portalCard(portal, check, memberId, host) {
  const member = state.members.find((m) => m.id === memberId);
  const record = (status) => async () => {
    await api.recordLostMoneyCheck(state.household.id, portal.code, { memberId, status });
    // Redrawn in place: a tap halfway down the page should not jump to the top.
    const y = window.scrollY;
    await continuityScreen(host);
    window.scrollTo(0, y);
  };
  const statusButtons = segmented([
    { value: "checked", label: t("lostMoney.status.checked") },
    { value: "found", label: t("lostMoney.status.found") },
    { value: "nothing", label: t("lostMoney.status.nothing") },
  ], check?.status, (status) => (status === "found" && !check?.investmentId
    ? recordFound(portal, memberId, host)
    : record(status)()));
  statusButtons.setAttribute("aria-label", t("lostMoney.statusLabel", { portal: portal.name }));

  return el("section.portal-card.stack-2", { "data-portal": portal.code },
    el("div.row-between.wrap", {},
      el("h4", {}, portal.name),
      check && el("span.caption", {}, t("lostMoney.checkedOn", {
        status: t(`lostMoney.status.${check.status}`), date: formatDate(check.checkedOn),
      })),
    ),
    el("p.caption", {}, portal.finds),
    el("details", {},
      el("summary.caption", {}, t("lostMoney.howTo", { name: member?.displayName || "" })),
      el("ol.portal-steps", {}, ...portal.howToSearch.map((line) => el("li", {}, line))),
      el("p.caption", {}, `${t("lostMoney.youNeed")} ${portal.youNeed.join(" · ")}`),
    ),
    el("a.btn.btn-sm.portal-link", {
      href: portal.url, target: "_blank", rel: "noopener noreferrer",
    }, t("lostMoney.open", { portal: portal.name })),
    statusButtons,
    check?.investmentId && el("div.stack-2", {},
      el("p.caption", {}, t("lostMoney.becameRecord", { title: check.recordTitle || t("lostMoney.aRecord") })),
      el("details", {},
        el("summary.caption", {}, t("lostMoney.howToClaim")),
        el("ol.portal-steps", {}, ...portal.claim.map((step) => el("li", {}, el("b", {}, step.step), ` — ${step.detail}`))),
      ),
    ),
  );
}

/** Found something: it becomes a record for the family plan, with how to claim it. */
function recordFound(portal, memberId, host) {
  const title = textInput({ maxLength: 160, placeholder: t("lostMoney.found.titlePlaceholder"), "aria-label": t("lostMoney.found.title") });
  const amount = moneyInput({ "aria-label": t("lostMoney.found.amount") });
  const where = textInput({ maxLength: 120, placeholder: t("lostMoney.found.wherePlaceholder"), "aria-label": t("lostMoney.found.where") });
  const error = el("p.help.error", { role: "alert" });
  const save = el("button.btn.btn-primary.grow", { type: "button" }, t("lostMoney.found.save"));
  save.onclick = () => withBusy(save, async () => {
    error.textContent = "";
    if (!title.value.trim()) { error.textContent = t("estate.q.titleMissing"); return; }
    try {
      await api.recordFoundMoney(state.household.id, portal.code, {
        memberId, title: title.value.trim(), amount: amount.value(), whereFound: where.value.trim() || null,
      });
      modal.close();
      toast(t("lostMoney.found.saved"));
      await continuityScreen(host);
    } catch (problem) {
      error.textContent = problem.message;
    }
  });
  const modal = sheet({
    title: t("lostMoney.found.heading", { portal: portal.name }),
    body: el("div.stack-3", {},
      el("p.caption", {}, t("lostMoney.found.explain")),
      field({ label: t("lostMoney.found.title"), control: title, required: true }),
      field({ label: t("lostMoney.found.amount"), control: amount, help: t("lostMoney.found.amountHelp") }),
      field({ label: t("lostMoney.found.where"), control: where }),
      error,
    ),
    footer: [save],
  });
}
