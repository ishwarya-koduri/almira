/* Investment detail — everything about one holding, including who can see it. */

import { api } from "../api.js";
import {
  el, mount, sheet, field, moneyInput, select, categoryDot, withBusy,
  toast, rupees, formatDate,
} from "../ui.js";
import { state, findType } from "../state.js";
import { reload } from "../app.js";

export async function openDetail(id) {
  const body = el("div.stack-3", {}, el("div.skeleton", { style: { height: "200px" } }));
  const modal = sheet({ title: "Holding", body });

  let record;
  try {
    record = await api.investment(state.household.id, id);
  } catch (error) {
    mount(body, el("div.banner", {}, error.message));
    return;
  }

  const type = findType(record.typeId);
  const schema = type?.schema || { common: {}, fields: [] };

  const draw = () => mount(body, el("div.stack-3", {},

    el("div.row", {},
      categoryDot(record.categoryCode, record.color),
      el("div.grow", {},
        el("h3", {}, record.title),
        el("div.caption.muted", {}, [record.typeLabel, record.institutionName].filter(Boolean).join(" · ")),
      ),
    ),

    el("div.card.card-tight", {},
      el("div.row-between", {},
        el("div", {},
          el("div.overline", {}, "Value"),
          el("div", { style: { fontFamily: "var(--font-display)", fontSize: "var(--text-h2)" } },
            record.valueFormatted || "Not known yet"),
          el("div.caption.muted", {}, valueExplanation(record)),
        ),
        el("button.btn.btn-sm", { type: "button", onclick: () => updateValue() }, "Update value"),
      ),
    ),

    // Ownership and visibility sit together: they are the two questions this
    // product exists to answer clearly.
    el("div.card.card-tight.stack-2", {},
      el("div.overline", {}, "Ownership & privacy"),
      row("Owned by", record.owners.map((o) =>
        `${o.name}${Number(o.sharePct) === 100 ? "" : ` (${o.sharePct}%)`}`).join(" & ")),
      row("Who can see it", visibilityText(record)),
      el("div.row", {},
        el("button.btn.btn-sm", { type: "button", onclick: () => changeVisibility() }, "Change who can see this"),
      ),
    ),

    el("div.card.card-tight.stack-2", {},
      el("div.overline", {}, "Details"),
      record.investedAmount && row("Amount invested", rupees(record.investedAmount)),
      record.quantity && row("Quantity", `${record.quantity} ${record.unit || ""}`.trim()),
      record.startDate && row(schema.common?.start_date?.label || "Started", formatDate(record.startDate)),
      record.maturityDate && row(schema.common?.maturity_date?.label || "Matures", formatDate(record.maturityDate)),
      record.storageLocation && row(schema.common?.storage_location?.label || "Kept at", record.storageLocation),
      ...attributeRows(record, schema),
      row("Added", formatDate(record.createdAt)),
      row("Last confirmed", record.lastVerifiedAt ? formatDate(record.lastVerifiedAt) : "Never"),
    ),

    el("div.row", {},
      el("button.btn.btn-danger", { type: "button", onclick: () => archive() }, "Move to trash"),
    ),
  ));

  function row(label, value) {
    if (!value) return null;
    return el("div.row-between", { style: { fontSize: "var(--text-sm)" } },
      el("span.muted", {}, label), el("span", { style: { textAlign: "right" } }, value));
  }

  function attributeRows(record, schema) {
    return Object.entries(record.attributes || {}).map(([key, value]) => {
      const def = (schema.fields || []).find((f) => f.key === key);
      const label = def?.label || key.replace(/_/g, " ");
      let shown = value;
      if (def?.dataType === "money") shown = rupees(value);
      else if (def?.dataType === "bool") shown = value ? "Yes" : "No";
      else if (def?.dataType === "select") {
        shown = def.options?.find((o) => o.value === value)?.label || value;
      } else if (def?.unit) shown = `${value} ${def.unit}`;
      return row(label, String(shown));
    });
  }

  function updateValue() {
    const amount = moneyInput({ placeholder: "0" });
    const save = el("button.btn.btn-primary", { type: "button" }, "Save value");
    const valueField = field({
      label: "What is it worth today?", control: amount, required: true,
      help: "A snapshot. We keep the history so you can see the trend later.",
    });
    const inner = sheet({
      title: "Update value",
      body: el("div.stack-3", {}, valueField),
      footer: [save],
    });
    save.onclick = () => withBusy(save, async () => {
      const value = amount.value();
      if (value === null) { valueField.setError("Enter an amount"); return; }
      try {
        record = await api.addValuation(state.household.id, id, { value });
        inner.close(); draw(); toast("Value updated.");
      } catch (error) { valueField.setError(error.message); }
    });
  }

  function changeVisibility() {
    const choice = select({
      options: [
        { value: "private", label: "Private — only the owner" },
        { value: "household", label: `Shared with ${state.household.name}` },
        { value: "scoped", label: "Shared with specific people" },
      ],
      value: record.visibility,
    });
    const picker = el("div.row.wrap", { style: { gap: "8px" }, hidden: record.visibility !== "scoped" },
      ...state.members.filter((m) => !m.isMe).map((m) => el("button.chip", {
        type: "button", "data-member": m.id,
        "aria-pressed": String(record.visibleToMemberIds.includes(m.id)),
        onclick: (event) => {
          const chip = event.currentTarget;
          chip.setAttribute("aria-pressed", chip.getAttribute("aria-pressed") === "true" ? "false" : "true");
        },
      }, m.displayName)),
    );
    choice.addEventListener("change", () => { picker.hidden = choice.value !== "scoped"; });

    const save = el("button.btn.btn-primary", { type: "button" }, "Save");
    const inner = sheet({
      title: "Who can see this?",
      body: el("div.stack-3", {},
        field({ label: "Visibility", control: choice,
          help: "Private is genuinely private — no role in the household can override it." }),
        picker,
      ),
      footer: [save],
    });

    save.onclick = () => withBusy(save, async () => {
      const visibleToMemberIds = [...picker.querySelectorAll('[aria-pressed="true"]')]
        .map((chip) => chip.dataset.member);
      try {
        record = await api.setVisibility(state.household.id, id, {
          visibility: choice.value, visibleToMemberIds,
        });
        inner.close(); draw(); toast("Updated.");
        await reload();
      } catch (error) { toast(error.message, { tone: "error" }); }
    });
  }

  async function archive() {
    try {
      await api.archive(state.household.id, id);
      modal.close();
      toast("Moved to trash.", {
        action: "Undo",
        onAction: async () => {
          await api.restore(state.household.id, id);
          toast("Restored.");
          await reload();
        },
      });
      await reload();
    } catch (error) { toast(error.message, { tone: "error" }); }
  }

  draw();
}

function valueExplanation(record) {
  switch (record.valueBasis) {
    case "valued": return `Your snapshot from ${formatDate(record.valuedOn)}`;
    case "at_cost": return "What you paid — add a value to see what it's worth today";
    case "custom_field": return "From a field you added";
    default: return "Add a value to include this in your totals";
  }
}

function visibilityText(record) {
  if (record.visibility === "household") return `Everyone in ${state.household.name}`;
  if (record.visibility === "scoped") {
    const names = state.members
      .filter((m) => record.visibleToMemberIds.includes(m.id))
      .map((m) => m.displayName);
    return names.length ? `The owner and ${names.join(", ")}` : "The owner only";
  }
  return "Only the owner — not even a household admin";
}
