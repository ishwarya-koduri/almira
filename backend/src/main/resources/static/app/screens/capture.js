/* =============================================================================
   Capture — the hero (docs/03 §3).

   Getting data in is the hardest problem in this category; a registry nobody
   fills is a registry nobody keeps. So: the type picker comes first and
   reshapes the form, at most a handful of essentials are visible, everything
   else waits behind "More details", and validation happens on blur rather than
   per keystroke.

   The form is generated from the type's field_schema — the same definition the
   server validates against — so the form can never ask for something the API
   will reject, and adding an asset type is a data change, not a UI change.
   ============================================================================= */

import { api } from "../api.js";
import {
  el, mount, sheet, field, textInput, moneyInput, select, categoryDot,
  withBusy, toast, rupees, icon,
} from "../ui.js";
import { state, myMember, findType, startingVisibility, helpingWhom } from "../state.js";
import { t, categoryName } from "../i18n.js";
import { reload } from "../app.js";
import { openImport } from "./import.js";
import { openStatementImport } from "./statement-import.js";
import { openPhotoReader } from "./photo-reader.js";
import { canReadPhotos } from "../ocr.js";
import { reportCaptureAbandoned } from "../measurement.js";
import { attachDraft, isOffline, queueCapture } from "../draft-ui.js";
import { helpMark } from "../glossary.js";
import { holdingImpliesReminder, offerRemindersOutsideTheApp } from "../message-consent.js";

export function openCapture(onSaved) {
  chooseHowToAdd(onSaved);
}

/* -----------------------------------------------------------------------------
   Step 0 — how would you like to add it?

   Five input modes converge on the same form (docs/03 §3): type it in words,
   start from something you've saved before, pick a type, read a document, or
   bring a spreadsheet. Whatever the route, the form is the last step and
   nothing is saved until someone has looked at it — a parse is a proposal.
   ----------------------------------------------------------------------------- */

function chooseHowToAdd(onSaved) {
  const quick = textInput({
    placeholder: t("capture.quick.example"),
    "aria-label": t("capture.quick.label"),
    autocomplete: "off",
  });
  const chipHost = el("div.stack-2", {});
  // Never wraps (X-70): on a phone the label becomes an arrow, and the name
  // stays for a screen reader.
  const parseButton = el("button.btn.btn-primary.btn-collapse", { type: "button", "aria-label": t("capture.quick.read") },
    el("span.btn-label", { "aria-hidden": "true" }, t("capture.quick.read")), icon("arrow", "icon.btn-icon"));
  let parsed = null;
  // Closed without going on to a next step is an abandonment at step 1
  // (docs/what-we-measure.md); every way forward sets this first.
  let movedOn = false;
  const advance = () => { movedOn = true; modal.close(); };

  const showParse = (result) => {
    parsed = result;
    const type = result.fields.find((f) => f.key === "typeId");
    const amount = result.fields.find((f) => f.key === "investedAmount");
    const unclear = result.notUnderstood || [];
    mount(chipHost,
      // The sentence as typed, with the words each chip came from underlined —
      // so "Matures · 5 Mar 2028" can be checked against what was actually said.
      result.input && el("p.parse-echo", {}, ...echoSentence(result)),
      el("div.row.wrap", { style: { gap: "8px" } },
        ...result.fields.map((f) => el("span.chip.chip-static", { title: f.sourceText },
          el("span.caption.muted", {}, `${chipLabel(f)} · `), f.display)),
        // Words nothing was made of are shown as what they are. They used to
        // become part of the name, which is how "7.1% matures nominee Aarav"
        // got saved as the name of a deposit.
        ...unclear.map((span) => el("span.chip.chip-static.chip-unclear", {},
          el("span.caption", {}, `${t("capture.parse.unclear")} · `), `“${span.text}”`)),
      ),
      amount?.hint && el("p.caption.muted", {}, `${amount.display} — ${amount.hint}`),
      unclear.length > 0 && el("p.caption.muted", {}, t("capture.parse.unclearHelp")),
      result.note && el("p.caption.muted", {}, result.note),
      type
        ? el("button.btn.btn-primary", {
            type: "button",
            onclick: () => {
              advance();
              const found = findType(type.value);
              if (found) captureForm(found, onSaved, prefillFrom(result));
            },
          }, t("capture.parse.check"))
        : el("p.caption.muted", {}, t("capture.parse.pickType")),
    );
  };

  parseButton.onclick = () => withBusy(parseButton, async () => {
    const text = quick.value.trim();
    if (!text) { quick.focus(); return; }
    showParse(await api.parseText(state.household.id, text));
  });
  quick.addEventListener("keydown", (event) => {
    if (event.key === "Enter") { event.preventDefault(); parseButton.click(); }
  });

  // A PDF or a photo, read into the same chips and the same form.
  const useDocument = (result) => {
    const type = result.fields.find((f) => f.key === "typeId");
    if (type && findType(type.value)) {
      advance();
      captureForm(findType(type.value), onSaved, prefillFrom(result));
    } else {
      showParse({ ...result, unparsed: "" });
    }
  };

  const documentInput = el("input", {
    type: "file", accept: "application/pdf,image/*", hidden: true,
    onchange: async () => {
      const file = documentInput.files?.[0];
      if (!file) return;
      // The same file chosen twice should still arrive.
      documentInput.value = "";
      // A photo is read on this device first (P-13); the server cannot read one.
      if (file.type.startsWith("image/") && canReadPhotos()) {
        openPhotoReader(file, { onFound: useDocument, onFallback: () => quick.focus() });
        return;
      }
      const result = await api.parseDocument(state.household.id, file);
      toast(result.note || t("capture.document.saved"));
      useDocument(result);
    },
  });

  const templateHost = el("div.stack-2", {});
  loadTemplates(templateHost, onSaved, advance);

  const modal = sheet({
    title: t("app.addSomething"),
    onClose: () => { if (!movedOn) reportCaptureAbandoned(1); },
    body: el("div.stack-3", {},
      field({
        label: t("capture.quick.field"),
        control: el("div.row", {}, quick, parseButton),
        help: t("capture.quick.help"),
      }),
      chipHost,
      templateHost,
      el("div.stack-2", {},
        el("span.overline", {}, t("capture.or")),
        el("div.row.wrap", { style: { gap: "8px" } },
          el("button.btn", {
            type: "button",
            onclick: () => { advance(); pickType((type) => captureForm(type, onSaved)); },
          }, t("capture.pickType")),
          el("button.btn", { type: "button", onclick: () => documentInput.click() },
            t("capture.readDocument")),
          el("button.btn", {
            type: "button",
            onclick: () => { advance(); openImport(onSaved); },
          }, t("import.title")),
          el("button.btn", {
            type: "button",
            onclick: () => { advance(); openStatementImport(onSaved); },
          }, t("statement.button")),
          documentInput,
        ),
      ),
    ),
  });
}

/**
 * Chips in, form fields out. Only what the parser was confident enough to name:
 * words it did not understand are never carried into the name.
 */
function prefillFrom(parsed) {
  const prefill = { attributes: {} };
  for (const parsedField of parsed.fields || []) {
    const key = parsedField.key;
    if (key === "typeId") continue;
    if (key === "nomineeName") prefill.nominee = { ...prefill.nominee, name: parsedField.value };
    else if (key === "nomineeRelationship") prefill.nominee = { ...prefill.nominee, relationship: parsedField.value };
    else if (key.startsWith("attributes.")) prefill.attributes[key.slice(11)] = parsedField.value;
    else prefill[key] = parsedField.value;
  }
  if (prefill.nominee && !prefill.nominee.name) delete prefill.nominee;
  return prefill;
}

/** A server label in the reader's language where there is one; the server's English otherwise. */
function chipLabel(parsedField) {
  const key = `capture.field.${parsedField.key}`;
  const translated = t(key);
  return translated === key ? parsedField.label : translated;
}

/** The typed sentence as text nodes, with each chip's source words underlined. */
function echoSentence(result) {
  const marks = [
    ...result.fields.filter((f) => Number.isInteger(f.start) && Number.isInteger(f.end))
      .map((f) => ({ start: f.start, end: f.end, label: chipLabel(f), unclear: false })),
    ...(result.notUnderstood || [])
      .map((span) => ({ start: span.start, end: span.end, label: t("capture.parse.unclear"), unclear: true })),
  ].sort((a, b) => a.start - b.start);

  const text = result.input;
  const parts = [];
  let at = 0;
  for (const mark of marks) {
    if (mark.start < at || mark.end > text.length) continue;  // overlapping or stale: leave as plain text
    if (mark.start > at) parts.push(text.slice(at, mark.start));
    parts.push(el(mark.unclear ? "span.parse-unclear" : "u", { title: mark.label }, text.slice(mark.start, mark.end)));
    at = mark.end;
  }
  if (at < text.length) parts.push(text.slice(at));
  return parts;
}

async function loadTemplates(host, onSaved, closeParent) {
  try {
    const templates = await api.templates(state.household.id);
    if (!templates.length) return;
    mount(host,
      el("span.overline", {}, t("capture.templates")),
      el("div.row.wrap", { style: { gap: "8px" } },
        ...templates.slice(0, 8).map((template) => el("button.chip", {
          type: "button",
          onclick: () => { closeParent(); useTemplate(template, onSaved); },
        }, template.name)),
      ),
    );
  } catch { /* templates are a convenience; never block capture on them */ }
}

/**
 * A template supplies the shape; this asks only for what is different this
 * time, which is the whole saving.
 */
function useTemplate(template, onSaved) {
  const title = textInput({ value: template.title || template.name });
  const amount = moneyInput({ placeholder: "0" });
  if (template.investedAmount) {
    amount.input.value = String(template.investedAmount);
    amount.input.dispatchEvent(new Event("input"));
  }
  const startDate = textInput({ type: "date" });
  const owner = select({
    options: state.members.map((m) => ({ value: m.id, label: m.isMe ? t("common.me", { name: m.displayName }) : m.displayName })),
    value: myMember()?.id,
  });
  const save = el("button.btn.btn-primary.grow", { type: "button" }, t("app.save"));
  const error = el("div.help.error", { role: "alert", style: { minHeight: "1.15rem" } });

  save.onclick = () => withBusy(save, async () => {
    error.textContent = "";
    try {
      const created = await api.applyTemplate(state.household.id, template.id, {
        title: title.value.trim() || null,
        investedAmount: amount.value(),
        startDate: startDate.value || null,
        owners: [{ memberId: owner.value, sharePct: 100 }],
      });
      modal.close();
      toast(created.visibleToYou ? t("common.saved") : t("capture.savedPrivate"));
      await (onSaved ? onSaved() : reload());
    } catch (apiError) {
      // A template can be missing something its type requires — an FD without
      // its interest rate. Say which field, and offer the full form.
      const fields = apiError.details?.fields;
      error.textContent = fields
        ? t("capture.template.missing", { fields: Object.values(fields).join(". ") })
        : apiError.message;
    }
  });

  const modal = sheet({
    title: template.name,
    body: el("div.stack-3", {},
      el("p.caption.muted", {},
        template.institutionName
          ? t("capture.template.typeAt", { type: template.typeLabel, institution: template.institutionName })
          : t("capture.template.type", { type: template.typeLabel })),
      field({ label: t("capture.field.title"), control: title }),
      field({ label: t("capture.field.investedAmount"), control: amount }),
      field({ label: t("common.date"), control: startDate }),
      field({ label: t("capture.whose"), control: owner }),
      error,
    ),
    footer: [save],
  });
}

/* -----------------------------------------------------------------------------
   Step 1 — the type picker. A warm grid, searchable once it gets long.
   ----------------------------------------------------------------------------- */

function pickType(onPick) {
  let picked = false;
  const search = textInput({ type: "search", placeholder: t("capture.searchTypes.placeholder"), "aria-label": t("capture.searchTypes.label") });
  const grid = el("div.stack", {});

  const draw = (query = "") => {
    const q = query.toLowerCase();
    const groups = state.taxonomy
      .map((category) => ({
        ...category,
        types: category.types.filter((type) =>
          !q || type.label.toLowerCase().includes(q) || category.categoryLabel.toLowerCase().includes(q)
            || categoryName(category.categoryCode, category.categoryLabel).toLowerCase().includes(q)),
      }))
      .filter((category) => category.types.length > 0);

    mount(grid, ...(groups.length === 0
      ? [el("p.muted", {}, t("capture.searchTypes.none"))]
      : groups.map((category) => el("div.stack-2", {},
          el("div.overline", { role: "heading", "aria-level": "4" }, categoryName(category.categoryCode, category.categoryLabel)),
          el("div.row.wrap", { style: { gap: "8px" } },
            ...category.types.map((type) => el("button.chip", {
              type: "button",
              onclick: () => { picked = true; modal.close(); onPick(type); },
            }, categoryDot(category.categoryCode, category.color), type.label)),
          ),
        ))));
  };

  search.addEventListener("input", () => draw(search.value.trim()));
  draw();

  const modal = sheet({
    title: t("capture.whatAreYouAdding"),
    onClose: () => { if (!picked) reportCaptureAbandoned(2); },
    body: el("div.stack-3", {}, search, grid),
  });
}

/* -----------------------------------------------------------------------------
   Step 2 — the form, generated from the type's schema
   ----------------------------------------------------------------------------- */

export function captureForm(type, onSaved, prefill = null) {
  const schema = type.schema || { common: {}, fields: [] };
  const controls = new Map();   // key -> { field, read }
  const customFields = [];

  const essentials = el("div.stack-3", {});
  const more = el("div.stack-3", {});

  /* --- columns relabelled by the type, and its own attributes, by `sort` ---- */
  // One list, ordered by the schema's `sort`, exactly as the native app's
  // toFormFields does (known-issues 1): a Fixed Deposit's interest rate (30)
  // sits between Principal (20) and Opened on (40), as on the receipt, instead
  // of below every column. Array sort is stable, so on equal numbers columns
  // come first, in the schema's own order — the app's tie-break too.
  // There is no location column any more (V33, docs/20 §1). Where the original
  // is gets recorded sealed, on the saved record; this form posts in plain
  // text, so it never asks, and the server refuses the old field if sent.
  const columnOrder = ["currency", "invested_amount", "quantity", "start_date", "maturity_date"];
  const knownColumns = new Set(columnOrder);
  const ordered = [
    ...Object.entries(schema.common || {})
      .filter(([key]) => knownColumns.has(key))
      .map(([key, def]) => ({ sort: def.sort ?? 100, key, def, column: true })),
    ...(schema.fields || []).map((def) => ({ sort: def.sort ?? 100, key: `attr:${def.key}`, def, column: false })),
  ].sort((a, b) => a.sort - b.sort);
  for (const { key, def, column } of ordered) {
    const control = column ? buildColumnControl(key, def) : buildAttributeControl(def);
    controls.set(key, control);
    (def.group === "essential" ? essentials : more).append(control.field);
  }

  // A type that asks for its currency (something held abroad) is entered in
  // that currency: the ₹ in front of the amount changes with the choice, so a
  // dollar balance is never typed against a rupee sign.
  const currencyControl = controls.get("currency");
  const amountControl = controls.get("invested_amount");
  if (currencyControl && amountControl) {
    currencyControl.onChange((code) => amountControl.setCurrency(code));
  }

  // Every holding can have an original somewhere, so every form says where
  // that gets recorded — sealed, on the saved record (docs/20 §1).
  essentials.append(el("p.caption.muted", { "data-sealed-pointer": "original_location" },
    t("where.captureNote")));

  /* --- title, institution, ownership, visibility ---------------------------- */
  const titleInput = textInput({ placeholder: titlePlaceholder(type) });
  const titleField = field({
    label: t("common.whatToCallIt"), control: titleInput, required: true,
    help: t("capture.titleHelp"),
  });

  const institutionSelect = select({
    options: [{ value: "", label: t("common.noInstitution") }],
  });
  loadInstitutions(institutionSelect);

  // Setting things up for someone (X-32): their records are theirs by default.
  const startingOwner = prefill?.ownerId || helpingWhom()?.id || myMember()?.id;
  const ownerSelect = select({
    options: state.members.map((m) => ({ value: m.id, label: m.isMe ? t("common.me", { name: m.displayName }) : m.displayName })),
    value: startingOwner,
  });

  const defaultVisibility = startingVisibility(startingOwner);
  const visibilitySelect = select({
    options: [
      { value: "private", label: t("capture.visibility.private") },
      { value: "household", label: t("common.sharedWith", { name: state.household.name }) },
      { value: "scoped", label: t("common.sharedWithSome") },
    ],
    value: defaultVisibility,
  });

  const scopedPicker = el("div.stack-2", { hidden: true },
    el("span.caption.muted", { id: `scoped-${type.code}` }, t("capture.chooseWho")),
    el("div.row.wrap", { role: "group", "aria-labelledby": `scoped-${type.code}`, style: { gap: "8px" } },
      ...state.members.filter((m) => !m.isMe).map((m) => el("button.chip", {
        type: "button", "aria-pressed": "false", "data-member": m.id,
        onclick: (event) => {
          const chip = event.currentTarget;
          chip.setAttribute("aria-pressed", chip.getAttribute("aria-pressed") === "true" ? "false" : "true");
        },
      }, m.displayName)),
    ),
  );
  visibilitySelect.addEventListener("change", () => {
    scopedPicker.hidden = visibilitySelect.value !== "scoped";
  });

  /* --- custom fields — the "record anything" escape hatch -------------------- */
  const customHost = el("div.stack-3", {});
  const addCustom = el("button.btn.btn-sm", { type: "button", onclick: () => addCustomField() },
    t("capture.custom.add"));

  function addCustomField() {
    const label = textInput({ placeholder: t("capture.custom.namePlaceholder"), "aria-label": t("capture.custom.name") });
    const kind = select({
      options: [
        { value: "text", label: t("capture.custom.type.text") }, { value: "money", label: t("capture.field.investedAmount") },
        { value: "number", label: t("capture.custom.type.number") }, { value: "date", label: t("common.date") },
        { value: "percent", label: t("capture.custom.type.percent") }, { value: "bool", label: t("capture.custom.type.bool") },
      ],
      "aria-label": t("capture.custom.type"),
    });
    const value = textInput({ placeholder: t("capture.custom.valuePlaceholder"), "aria-label": t("capture.custom.value") });
    const counts = el("label.row", { style: { fontSize: "var(--text-caption)" } },
      el("input", { type: "checkbox" }), ` ${t("capture.custom.counts")}`);

    const row = el("div.card.card-tight.stack-2", {},
      el("div.row", {}, label, kind),
      el("div.row", {}, value,
        el("button.btn.btn-sm.btn-danger", {
          type: "button",
          onclick: () => { row.remove(); customFields.splice(customFields.indexOf(entry), 1); },
        }, t("app.remove"))),
      counts,
    );
    const entry = { label, kind, value, counts, row };
    customFields.push(entry);
    customHost.append(row);
    label.focus();
  }

  /* --- assembly ------------------------------------------------------------- */
  // The sheet puts its footer outside the <form>, so the submit button is
  // associated by id. Without this the button renders, looks primary, and does
  // absolutely nothing -- while Enter in a text field still works, which makes
  // it look intermittent rather than broken.
  const formId = `capture-${type.code}`;
  const save = el("button.btn.btn-primary.grow", { type: "submit", form: formId }, t("app.save"));
  const saveAnother = el("button.btn", { type: "button" }, t("capture.saveAnother"));
  const formError = el("div.help.error", { role: "alert", style: { minHeight: "1.15rem" } });

  const form = el(`form#${formId}.stack-3`, { onsubmit: (event) => { event.preventDefault(); submit(save, false); } },
    titleField,
    essentials,
    field({ label: t("capture.whereHeld"), control: institutionSelect, help: t("capture.whereHeldHelp") }),
    field({ label: t("capture.whose"), control: ownerSelect }),
    field({ label: [t("common.whoCanSee"), helpMark("private")], control: visibilitySelect,
      help: helpingWhom()
        ? t("capture.visibility.helping", { name: helpingWhom().displayName })
        : t("capture.visibility.help") }),
    scopedPicker,
    (more.children.length > 0 || true) && el("details.more", {},
      el("summary", {}, t("common.moreDetails")),
      el("div.stack-3", { style: { paddingTop: "16px" } },
        ...(more.children.length ? [more] : [el("p.caption.muted", {}, t("capture.nothingElse"))]),
        el("div.stack-2", {},
          el("span.overline", {}, t("capture.custom.title")),
          customHost,
          addCustom,
        ),
      ),
    ),
    formError,
  );

  // Whatever a parse or a template proposed arrives here as ordinary field
  // values, so it can be edited or deleted like anything typed by hand.
  if (prefill) {
    if (prefill.title) titleInput.value = prefill.title;
    if (prefill.institutionId) {
      institutionSelect.dataset.pending = prefill.institutionId;
    }
    const columns = {
      investedAmount: "invested_amount", quantity: "quantity",
      startDate: "start_date", maturityDate: "maturity_date",
    };
    for (const [key, column] of Object.entries(columns)) {
      if (prefill[key] !== undefined) controls.get(column)?.set?.(prefill[key]);
    }
    for (const [key, value] of Object.entries(prefill.attributes || {})) {
      // A bond calls its rate a coupon; the parser only knows "rate".
      const target = key === "interest_rate" && !controls.has("attr:interest_rate") ? "attr:coupon_rate" : `attr:${key}`;
      controls.get(target)?.set?.(value);
    }
  }

  // Each field is kept on this device as it is typed (X-83), so a dropped
  // connection or a closed tab does not lose it. drafts.js decides which fields
  // may be kept: identifiers and anything sealed never are. A form opened
  // without a proposal picks up where the last one on this type stopped.
  const draftFields = new Map([
    ["title", { field: titleField, read: () => titleInput.value.trim() || null, set: (v) => { titleInput.value = v; } }],
    ...[...controls].filter(([, control]) => control.set),
    ["owner", { field: ownerSelect.closest?.(".field") || ownerSelect, read: () => ownerSelect.value, set: (v) => { ownerSelect.value = v; } }],
  ]);
  const draft = attachDraft(`capture:${type.code}`, draftFields, () => ({
    typeCode: type.code, title: titleInput.value.trim() || undefined, householdId: state.household.id,
  }));
  form.insertBefore(draft.status, formError);
  if (!prefill) draft.restore();

  // A nominee has no field on this form — it is recorded on the saved holding.
  // So a nominee read from the sentence is said out loud here, can be dropped,
  // and is saved straight after the holding is.
  let pendingNominee = prefill?.nominee || null;
  const nomineeNote = pendingNominee && el("div.row-between.nominee-pending", {},
    el("p.caption.muted", {}, t("capture.nominee.pending", {
      name: pendingNominee.relationship
        ? `${pendingNominee.name} (${pendingNominee.relationship})` : pendingNominee.name,
    })),
    el("button.btn.btn-sm", {
      type: "button",
      onclick: () => { pendingNominee = null; nomineeNote.remove(); },
    }, t("capture.nominee.drop")),
  );
  if (nomineeNote) form.insertBefore(nomineeNote, formError);

  let saved = false;
  const modal = sheet({
    title: helpingWhom()
      ? t("capture.titleFor", { type: type.label.toLowerCase(), name: helpingWhom().displayName })
      : t("capture.addType", { type: type.label.toLowerCase() }),
    onClose: () => { if (!saved) reportCaptureAbandoned(3); },
    body: form,
    footer: [save, saveAnother],
  });

  saveAnother.onclick = () => submit(saveAnother, true);

  async function submit(button, andAnother) {
    formError.textContent = "";
    titleField.setError("");
    controls.forEach((control) => control.field.setError?.(""));

    if (!titleInput.value.trim()) {
      titleField.setError(t("common.giveItAName")); titleInput.focus(); return;
    }

    const body = {
      typeId: type.id,
      title: titleInput.value.trim(),
      visibility: visibilitySelect.value,
      owners: [{ memberId: ownerSelect.value, sharePct: 100 }],
      attributes: {},
    };
    if (institutionSelect.value) body.institutionId = institutionSelect.value;

    for (const [key, control] of controls) {
      const value = control.read();
      if (value === null || value === undefined || value === "") continue;
      if (key.startsWith("attr:")) body.attributes[key.slice(5)] = value;
      else body[camel(key)] = value;
    }

    if (visibilitySelect.value === "scoped") {
      body.visibleToMemberIds = [...scopedPicker.querySelectorAll('[aria-pressed="true"]')]
        .map((chip) => chip.dataset.member);
      if (body.visibleToMemberIds.length === 0) {
        formError.textContent = t("capture.chooseAtLeastOne"); return;
      }
    }

    const custom = customFields
      .filter((entry) => entry.label.value.trim())
      .map((entry) => ({
        key: slug(entry.label.value),
        label: entry.label.value.trim(),
        dataType: entry.kind.value,
        countsTowardValue: entry.counts.querySelector("input").checked && entry.kind.value === "money",
      }));
    if (custom.length) {
      body.customFields = custom;
      customFields.forEach((entry, index) => {
        const value = entry.value.value.trim();
        if (value) body.attributes[custom[index]?.key ?? slug(entry.label.value)] = value;
      });
    }

    await withBusy(button, async () => {
      try {
        let created;
        try {
          created = await api.capture(state.household.id, body);
        } catch (error) {
          // No network: kept on this phone and sent when it is back (X-83).
          if (!isOffline(error) || !queueCapture(state.household.id, body, `capture:${type.code}`)) throw error;
          saved = true;
          draft.discard();
          modal.close();
          toast(t("draft.queuedOne", { name: body.title }));
          if (andAnother) openCapture(onSaved);
          return;
        }
        saved = true;
        draft.discard();
        if (pendingNominee && created.visibleToYou) await saveNominee(created.id, pendingNominee);
        modal.close();
        // A record saved as Private for someone else is a legitimate outcome,
        // and the API tells us when the creator cannot read it back. Saying so
        // is far better than appearing to have lost it.
        toast(created.visibleToYou
          ? t("capture.savedWhat", { what: created.investment.valueFormatted || body.title })
          : t("capture.savedPrivateList"));
        await (onSaved ? onSaved() : reload());
        if (andAnother) openCapture(onSaved);
        // It has a date we'll remind about: the moment to ask whether that may be
        // by email, SMS or push too (V125), about this holding (V141). Never
        // pre-ticked; asked once per holding.
        else if (holdingImpliesReminder(body) && created.visibleToYou) {
          offerRemindersOutsideTheApp({ contextType: "investment", contextId: created.id });
        }
      } catch (error) {
        const fields = error.details?.fields;
        if (fields) {
          for (const [key, message] of Object.entries(fields)) {
            const control = controls.get(`attr:${key}`) || controls.get(key);
            if (control) control.field.setError(message);
            else formError.textContent = message;
          }
          const firstInvalid = form.querySelector('[data-invalid="true"] input, [data-invalid="true"] select');
          firstInvalid?.focus();
        } else {
          formError.textContent = error.message;
        }
      }
    });
  }

  titleInput.focus();
}

/**
 * Saves a nominee the sentence named. Someone in the household is linked by
 * name; anyone else is recorded as written. A failure here must not lose the
 * holding that was just saved, so it says what to do instead of throwing.
 */
async function saveNominee(investmentId, nominee) {
  const member = state.members.find((m) =>
    m.displayName?.trim().toLowerCase() === nominee.name.trim().toLowerCase());
  try {
    await api.setNominees(state.household.id, investmentId, {
      nominees: [{
        memberId: member?.id || null,
        name: member ? null : nominee.name,
        relationship: nominee.relationship || null,
        sharePct: 100,
      }],
    });
  } catch {
    toast(t("capture.nominee.failed"));
  }
}

/* -----------------------------------------------------------------------------
   Control builders
   ----------------------------------------------------------------------------- */

function buildColumnControl(key, def) {
  if (key === "currency") {
    const input = select({
      options: [{ value: "", label: "—" }, ...FOREIGN_CURRENCIES.map((code) => ({ value: code, label: code }))],
      "aria-label": def.label,
    });
    const wrapper = field({ label: def.label, required: def.required, control: input, help: def.help });
    return {
      field: wrapper,
      read: () => input.value || null,
      set: (value) => { input.value = String(value).toUpperCase(); input.dispatchEvent(new Event("change")); },
      onChange: (listener) => input.addEventListener("change", () => listener(input.value || null)),
    };
  }

  if (key === "invested_amount") {
    const control = moneyInput({ placeholder: "0" });
    const wrapper = field({ label: def.label, required: def.required, control, help: def.help });
    let currency = null;
    // The helper reassures rather than validates: seeing "≈ ₹15,873/g" is how
    // you catch a missing zero before it is saved (docs/02 §7).
    control.input.addEventListener("blur", () => {
      const value = control.value();
      wrapper.setError("");
      if (def.required && value === null) wrapper.setError(t("capture.isNeeded", { label: def.label }));
      else if (value !== null) wrapper.querySelector(".help").textContent = inCurrency(value, currency);
    });
    return {
      field: wrapper, read: () => control.value(),
      set: (value) => { control.input.value = String(value); control.input.dispatchEvent(new Event("input")); },
      setCurrency: (code) => {
        currency = code && code !== "INR" ? code : null;
        const sign = control.querySelector(".rupee");
        if (sign) sign.textContent = currency || "₹";
      },
    };
  }

  if (key === "quantity") {
    const input = textInput({ inputMode: "decimal", placeholder: "0" });
    const control = def.unit
      ? el("div.unit-wrap", {}, input, el("span.unit", {}, def.unit))
      : input;
    const wrapper = field({ label: def.label, required: def.required, control, help: def.help });
    return {
      field: wrapper, read: () => (input.value.trim() ? Number(input.value) : null),
      set: (value) => { input.value = String(value); },
    };
  }

  if (key.endsWith("_date")) {
    const input = textInput({ type: "date" });
    const wrapper = field({ label: def.label, required: def.required, control: input, help: def.help });
    return { field: wrapper, read: () => input.value || null, set: (value) => { input.value = value; } };
  }

  // The schema's help for a plain text column reads as an example ("Home locker,
  // bank locker, with a relative…"), so it belongs in the placeholder. Showing
  // it in both places says the same thing twice and wastes the line that an
  // error message needs.
  const input = textInput({ placeholder: def.help || "" });
  const wrapper = field({ label: def.label, required: def.required, control: input });
  return {
    field: wrapper, read: () => input.value.trim() || null,
    set: (value) => { input.value = String(value); },
  };
}

function buildAttributeControl(def) {
  let control;
  let read;
  let write;

  switch (def.dataType) {
    case "money": {
      const money = moneyInput({ placeholder: "0" });
      control = money;
      read = () => money.value();
      write = (value) => { money.input.value = String(value); money.input.dispatchEvent(new Event("input")); };
      break;
    }
    case "number":
    case "percent": {
      const input = textInput({ inputMode: "decimal", placeholder: def.placeholder || "" });
      control = def.unit ? el("div.unit-wrap", {}, input, el("span.unit", {}, def.unit)) : input;
      read = () => input.value.trim() || null;
      write = (value) => { input.value = String(value); };
      break;
    }
    case "date": {
      const input = textInput({ type: "date" });
      control = input;
      read = () => input.value || null;
      write = (value) => { input.value = String(value); };
      break;
    }
    case "bool": {
      const input = el("input", { type: "checkbox" });
      control = el("label.row", {}, input, el("span.caption.muted", {}, t("common.yes")));
      read = () => (input.checked ? true : null);
      write = (value) => { input.checked = value === true || value === "true"; };
      break;
    }
    case "select": {
      const input = select({
        options: [{ value: "", label: "—" }, ...(def.options || [])],
        "aria-label": def.label,
      });
      control = input;
      read = () => input.value || null;
      write = (value) => { input.value = String(value); };
      break;
    }
    default: {
      const input = textInput({ placeholder: def.placeholder || "" });
      control = input;
      read = () => input.value.trim() || null;
      write = (value) => { input.value = String(value); };
    }
  }

  const wrapper = field({ label: def.label, required: def.required, control, help: def.help });
  return { field: wrapper, read, set: write };
}

/* -----------------------------------------------------------------------------
   Helpers
   ----------------------------------------------------------------------------- */

async function loadInstitutions(selectNode) {
  try {
    const institutions = await api.institutions(state.household.id);
    institutions.forEach((institution) => {
      selectNode.append(el("option", { value: institution.id }, institution.name));
    });
    // A prefilled institution can only be selected once the options exist.
    if (selectNode.dataset.pending) {
      selectNode.value = selectNode.dataset.pending;
      delete selectNode.dataset.pending;
    }
  } catch { /* the field stays optional; capture must never dead-end */ }
}

function titlePlaceholder(type) {
  const examples = {
    fd: "capture.example.fd",
    gold_physical: "capture.example.gold_physical",
    mf_sip: "capture.example.mf_sip",
    stock_listed: "capture.example.stock_listed",
    insurance_term: "capture.example.insurance_term",
    property: "capture.example.property",
    universal: "capture.example.universal",
  };
  return examples[type.code] ? t(examples[type.code]) : type.label;
}

/**
 * The currencies an Indian family most often holds abroad, most common first.
 * Any three-letter code is accepted by the server; these are the ones offered.
 */
const FOREIGN_CURRENCIES = [
  "USD", "AED", "GBP", "EUR", "SGD", "CAD", "AUD", "SAR", "QAR", "KWD", "OMR", "BHD",
  "CHF", "JPY", "HKD", "MYR", "NZD", "THB",
];

/** Rupees in Indian grouping; anything else with its code, grouped the way its statement is. */
function inCurrency(value, code) {
  if (!code) return rupees(value);
  return `${code} ${Number(value).toLocaleString("en-US", { maximumFractionDigits: 2 })}`;
}

const camel = (snake) => snake.replace(/_([a-z])/g, (_, c) => c.toUpperCase());

const slug = (label) => label.toLowerCase().replace(/[^a-z0-9]+/g, "_")
  .replace(/^_+|_+$/g, "").slice(0, 40) || "field";
