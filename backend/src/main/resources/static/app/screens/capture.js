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
  withBusy, toast, rupees,
} from "../ui.js";
import { state, myMember, findType } from "../state.js";
import { reload } from "../app.js";
import { openImport } from "./import.js";

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
    placeholder: "1L gold 6.3g at ICICI 3 Aug",
    "aria-label": "Describe what you're adding",
    autocomplete: "off",
  });
  const chipHost = el("div.stack-2", {});
  const parseButton = el("button.btn.btn-primary", { type: "button" }, "Read it");
  let parsed = null;

  const showParse = (result) => {
    parsed = result;
    const type = result.fields.find((f) => f.key === "typeId");
    mount(chipHost,
      el("div.row.wrap", { style: { gap: "8px" } },
        ...result.fields.map((f) => el("span.chip", {},
          el("span.caption.muted", {}, `${f.label}: `), f.display)),
      ),
      result.unparsed && el("p.caption.muted", {},
        `We couldn't place “${result.unparsed}” — it'll become the name.`),
      result.note && el("p.caption.muted", {}, result.note),
      type
        ? el("button.btn.btn-primary", {
            type: "button",
            onclick: () => {
              modal.close();
              const found = findType(type.value);
              if (found) captureForm(found, onSaved, prefillFrom(result));
            },
          }, "Check the details")
        : el("p.caption.muted", {}, "Pick a type below and we'll carry the rest across."),
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

  const documentInput = el("input", {
    type: "file", accept: "application/pdf,image/*", hidden: true,
    onchange: async () => {
      const file = documentInput.files?.[0];
      if (!file) return;
      const result = await api.parseDocument(state.household.id, file);
      toast(result.note || "Saved the document.");
      const type = result.fields.find((f) => f.key === "typeId");
      if (type && findType(type.value)) {
        modal.close();
        captureForm(findType(type.value), onSaved, prefillFrom(result));
      } else {
        showParse({ ...result, unparsed: "" });
      }
    },
  });

  const templateHost = el("div.stack-2", {});
  loadTemplates(templateHost, onSaved, () => modal.close());

  const modal = sheet({
    title: "Add something",
    body: el("div.stack-3", {},
      field({
        label: "Say it in your own words",
        control: el("div.row", {}, quick, parseButton),
        help: "Lakhs and crores are fine — “2.5Cr flat”, “50k SIP”.",
      }),
      chipHost,
      templateHost,
      el("div.stack-2", {},
        el("span.overline", {}, "Or"),
        el("div.row.wrap", { style: { gap: "8px" } },
          el("button.btn", {
            type: "button",
            onclick: () => { modal.close(); pickType((type) => captureForm(type, onSaved)); },
          }, "Pick a type"),
          el("button.btn", { type: "button", onclick: () => documentInput.click() },
            "Read a document"),
          el("button.btn", {
            type: "button",
            onclick: () => { modal.close(); openImport(onSaved); },
          }, "Import a spreadsheet"),
          documentInput,
        ),
      ),
    ),
  });
}

/** Chips in, form fields out. Only what the parser was confident enough to name. */
function prefillFrom(parsed) {
  const prefill = { attributes: {} };
  for (const parsedField of parsed.fields || []) {
    const key = parsedField.key;
    if (key === "typeId") continue;
    if (key.startsWith("attributes.")) prefill.attributes[key.slice(11)] = parsedField.value;
    else prefill[key] = parsedField.value;
  }
  if (!prefill.title && parsed.unparsed) prefill.title = parsed.unparsed;
  return prefill;
}

async function loadTemplates(host, onSaved, closeParent) {
  try {
    const templates = await api.templates(state.household.id);
    if (!templates.length) return;
    mount(host,
      el("span.overline", {}, "Saved shapes"),
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
  const title = textInput({ value: template.title || template.name, "aria-label": "Name" });
  const amount = moneyInput({ placeholder: "0" });
  if (template.investedAmount) {
    amount.input.value = String(template.investedAmount);
    amount.input.dispatchEvent(new Event("input"));
  }
  const startDate = textInput({ type: "date" });
  const owner = select({
    options: state.members.map((m) => ({ value: m.id, label: m.isMe ? `${m.displayName} (me)` : m.displayName })),
    value: myMember()?.id, "aria-label": "Owner",
  });
  const save = el("button.btn.btn-primary.grow", { type: "button" }, "Save");
  const error = el("div.help.error", { style: { minHeight: "1.15rem" } });

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
      toast(created.visibleToYou ? "Saved." : "Saved. It's private to its owner.");
      await (onSaved ? onSaved() : reload());
    } catch (apiError) {
      // A template can be missing something its type requires — an FD without
      // its interest rate. Say which field, and offer the full form.
      const fields = apiError.details?.fields;
      error.textContent = fields
        ? `${Object.values(fields).join(". ")}. Open the full form to fill it in.`
        : apiError.message;
    }
  });

  const modal = sheet({
    title: template.name,
    body: el("div.stack-3", {},
      el("p.caption.muted", {},
        `${template.typeLabel}${template.institutionName ? ` at ${template.institutionName}` : ""}.`),
      field({ label: "Name", control: title }),
      field({ label: "Amount", control: amount }),
      field({ label: "Date", control: startDate }),
      field({ label: "Whose is it?", control: owner }),
      error,
    ),
    footer: [save],
  });
}

/* -----------------------------------------------------------------------------
   Step 1 — the type picker. A warm grid, searchable once it gets long.
   ----------------------------------------------------------------------------- */

function pickType(onPick) {
  const search = textInput({ type: "search", placeholder: "Search types…", "aria-label": "Search types" });
  const grid = el("div.stack", {});

  const draw = (query = "") => {
    const q = query.toLowerCase();
    const groups = state.taxonomy
      .map((category) => ({
        ...category,
        types: category.types.filter((type) =>
          !q || type.label.toLowerCase().includes(q) || category.categoryLabel.toLowerCase().includes(q)),
      }))
      .filter((category) => category.types.length > 0);

    mount(grid, ...(groups.length === 0
      ? [el("p.muted", {}, "Nothing matches. Try a different word — or pick “Anything Else”.")]
      : groups.map((category) => el("div.stack-2", {},
          el("div.overline", {}, category.categoryLabel),
          el("div.row.wrap", { style: { gap: "8px" } },
            ...category.types.map((type) => el("button.chip", {
              type: "button",
              onclick: () => { modal.close(); onPick(type); },
            }, categoryDot(category.categoryCode, category.color), type.label)),
          ),
        ))));
  };

  search.addEventListener("input", () => draw(search.value.trim()));
  draw();

  const modal = sheet({
    title: "What are you adding?",
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

  /* --- first-class columns, relabelled by the type ------------------------- */
  const columnOrder = [
    "invested_amount", "quantity", "start_date", "maturity_date", "storage_location",
  ];
  for (const key of columnOrder) {
    const def = schema.common?.[key];
    if (!def) continue;
    const control = buildColumnControl(key, def);
    controls.set(key, control);
    (def.group === "essential" ? essentials : more).append(control.field);
  }

  /* --- type-specific attributes -------------------------------------------- */
  for (const def of schema.fields || []) {
    const control = buildAttributeControl(def);
    controls.set(`attr:${def.key}`, control);
    (def.group === "essential" ? essentials : more).append(control.field);
  }

  /* --- title, institution, ownership, visibility ---------------------------- */
  const titleInput = textInput({ placeholder: titlePlaceholder(type), "aria-label": "Name" });
  const titleField = field({
    label: "What should we call it?", control: titleInput, required: true,
    help: "Something you'll recognise in a list a year from now.",
  });

  const institutionSelect = select({
    options: [{ value: "", label: "Not linked to an institution" }],
    "aria-label": "Institution",
  });
  loadInstitutions(institutionSelect);

  const ownerSelect = select({
    options: state.members.map((m) => ({ value: m.id, label: m.isMe ? `${m.displayName} (me)` : m.displayName })),
    value: myMember()?.id,
    "aria-label": "Owner",
  });

  const defaultVisibility = state.user?.defaultVisibility || state.household.defaultVisibility;
  const visibilitySelect = select({
    options: [
      { value: "private", label: "Private — only the owner can see it" },
      { value: "household", label: `Shared with ${state.household.name}` },
      { value: "scoped", label: "Shared with specific people" },
    ],
    value: defaultVisibility,
    "aria-label": "Who can see this",
  });

  const scopedPicker = el("div.stack-2", { hidden: true },
    el("span.caption.muted", {}, "Choose who can see it"),
    el("div.row.wrap", { style: { gap: "8px" } },
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
    "＋ Add your own field");

  function addCustomField() {
    const label = textInput({ placeholder: "What is it called?", "aria-label": "Field name" });
    const kind = select({
      options: [
        { value: "text", label: "Text" }, { value: "money", label: "Amount" },
        { value: "number", label: "Number" }, { value: "date", label: "Date" },
        { value: "percent", label: "Percent" }, { value: "bool", label: "Yes / no" },
      ],
      "aria-label": "Field type",
    });
    const value = textInput({ placeholder: "Value", "aria-label": "Field value" });
    const counts = el("label.row", { style: { fontSize: "var(--text-caption)" } },
      el("input", { type: "checkbox" }), " Count this amount in totals");

    const row = el("div.card.card-tight.stack-2", {},
      el("div.row", {}, label, kind),
      el("div.row", {}, value,
        el("button.btn.btn-sm.btn-danger", {
          type: "button",
          onclick: () => { row.remove(); customFields.splice(customFields.indexOf(entry), 1); },
        }, "Remove")),
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
  const save = el("button.btn.btn-primary.grow", { type: "submit", form: formId }, "Save");
  const saveAnother = el("button.btn", { type: "button" }, "Save & add another");
  const formError = el("div.help.error", { style: { minHeight: "1.15rem" } });

  const form = el(`form#${formId}.stack-3`, { onsubmit: (event) => { event.preventDefault(); submit(save, false); } },
    titleField,
    essentials,
    field({ label: "Where is it held?", control: institutionSelect,
      help: "Which bank, fund house or broker — so you know what funds what." }),
    field({ label: "Whose is it?", control: ownerSelect }),
    field({ label: "Who can see this?", control: visibilitySelect,
      help: "Private means only the owner. Not even a household admin." }),
    scopedPicker,
    (more.children.length > 0 || true) && el("details.more", {},
      el("summary", {}, "More details"),
      el("div.stack-3", { style: { paddingTop: "16px" } },
        ...(more.children.length ? [more] : [el("p.caption.muted", {}, "Nothing else for this type.")]),
        el("div.stack-2", {},
          el("span.overline", {}, "Your own fields"),
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
      controls.get(`attr:${key}`)?.set?.(value);
    }
  }

  const modal = sheet({
    title: `Add ${type.label.toLowerCase()}`,
    body: form,
    footer: [save, saveAnother],
  });

  saveAnother.onclick = () => submit(saveAnother, true);

  async function submit(button, andAnother) {
    formError.textContent = "";
    titleField.setError("");
    controls.forEach((control) => control.field.setError?.(""));

    if (!titleInput.value.trim()) {
      titleField.setError("Give it a name"); titleInput.focus(); return;
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
        formError.textContent = "Choose at least one person to share it with."; return;
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
        const created = await api.capture(state.household.id, body);
        modal.close();
        // A record saved as Private for someone else is a legitimate outcome,
        // and the API tells us when the creator cannot read it back. Saying so
        // is far better than appearing to have lost it.
        toast(created.visibleToYou
          ? `Saved — ${created.investment.valueFormatted || body.title}`
          : "Saved. It's private to its owner, so it won't appear in your list.");
        await (onSaved ? onSaved() : reload());
        if (andAnother) openCapture(onSaved);
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

/* -----------------------------------------------------------------------------
   Control builders
   ----------------------------------------------------------------------------- */

function buildColumnControl(key, def) {
  if (key === "invested_amount") {
    const control = moneyInput({ placeholder: "0" });
    const wrapper = field({ label: def.label, required: def.required, control, help: def.help });
    // The helper reassures rather than validates: seeing "≈ ₹15,873/g" is how
    // you catch a missing zero before it is saved (docs/02 §7).
    control.input.addEventListener("blur", () => {
      const value = control.value();
      wrapper.setError("");
      if (def.required && value === null) wrapper.setError(`${def.label} is needed`);
      else if (value !== null) wrapper.querySelector(".help").textContent = `${rupees(value)}`;
    });
    return {
      field: wrapper, read: () => control.value(),
      set: (value) => { control.input.value = String(value); control.input.dispatchEvent(new Event("input")); },
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
      control = el("label.row", {}, input, el("span.caption.muted", {}, "Yes"));
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
    fd: "SBI FD — 5 years",
    gold_physical: "Wedding coins",
    mf_sip: "Parag Parikh Flexi Cap",
    stock_listed: "Infosys",
    insurance_term: "LIC term cover",
    property: "Flat, Kakinada",
    universal: "A stake in Meera's bakery",
  };
  return examples[type.code] || type.label;
}

const camel = (snake) => snake.replace(/_([a-z])/g, (_, c) => c.toUpperCase());

const slug = (label) => label.toLowerCase().replace(/[^a-z0-9]+/g, "_")
  .replace(/^_+|_+$/g, "").slice(0, 40) || "field";
