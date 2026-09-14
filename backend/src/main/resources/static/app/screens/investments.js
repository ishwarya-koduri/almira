/* The list. Searchable, filterable, and honest about what it does not know. */

import { api } from "../api.js";
import { el, mount, categoryIcon, chipRow, skeletonRows, empty, money, formatDate, textInput, icon } from "../ui.js";
import { state } from "../state.js";
import { openCapture } from "./capture.js";
import { openDetail } from "./detail.js";
import { t } from "../i18n.js";

let filters = { q: "", category: null };

export async function investmentsScreen(host) {
  const search = textInput({
    type: "search", placeholder: "Search your holdings…", value: filters.q,
    "aria-label": "Search holdings",
  });

  const results = el("div", {}, skeletonRows(4));

  const load = async () => {
    mount(results, skeletonRows(4));
    const params = new URLSearchParams();
    if (filters.q) params.set("q", filters.q);
    if (filters.category) params.set("category", filters.category);
    const rows = await api.investments(state.household.id, params.toString());
    mount(results, renderRows(rows));
  };

  // Debounced so a search does not fire a request per keystroke.
  let timer;
  search.addEventListener("input", () => {
    clearTimeout(timer);
    filters.q = search.value.trim();
    timer = setTimeout(load, 250);
  });

  // One row that scrolls sideways, not fourteen chips wrapping into two (D-09).
  const categoryChips = chipRow("Filter by kind",
    el("button.chip", {
      type: "button", "aria-pressed": filters.category === null,
      onclick: () => { filters.category = null; drawChips(); load(); },
    }, "Everything"),
    ...state.taxonomy.map((category) => el("button.chip", {
      type: "button", "aria-pressed": filters.category === category.categoryCode,
      onclick: () => {
        filters.category = filters.category === category.categoryCode ? null : category.categoryCode;
        drawChips(); load();
      },
    }, categoryIcon(category.categoryCode, category.color), category.categoryLabel)),
  );

  function drawChips() {
    [...categoryChips.children].forEach((chip, index) => {
      const value = index === 0 ? null : state.taxonomy[index - 1].categoryCode;
      chip.setAttribute("aria-pressed", String(filters.category === value));
    });
  }

  mount(host, el("div.stack", {},
    el("div.row-between.wrap", {},
      el("h1", {}, "Investments"),
      el("button.btn.btn-primary", { type: "button", onclick: () => openCapture(load) }, icon("plus"), "Add"),
    ),
    search,
    categoryChips,
    results,
  ));

  await load();
}

function renderRows(rows) {
  if (rows.length === 0) {
    return el("div.card", {}, empty({
      title: "Nothing here yet",
      body: filters.q || filters.category
        ? "No holdings match that. Try clearing the filters."
        : "Add your first holding — it takes about twenty seconds.",
      action: el("button.btn.btn-primary", { onclick: () => openCapture() }, icon("plus"), "Add something"),
    }));
  }

  return el("div.card.card-tight", {},
    el("div.list", {}, ...rows.map((row) => el("button.list-row", {
      type: "button", onclick: () => openDetail(row.id),
    },
      categoryIcon(row.categoryCode, row.color),
      el("div.grow", { style: { minWidth: 0 } },
        el("div.title", {}, row.title),
        el("div.meta", {}, [
          row.typeLabel,
          row.institutionName,
          row.owners.map((o) => o.name).filter(Boolean).join(" & "),
        ].filter(Boolean).join(" · ")),
      ),
      // D-11: ₹42 L in a phone's list, the full figure where there is room.
      // X-71: no amount is an invitation, not a dash.
      el("div.amount", {},
        row.valueFormatted
          ? el("b", {}, money(row.valueFormatted, row.value))
          : el("span.link-quiet", {}, t("block.addAmount")),
        row.valueFormatted && el("div.meta", {}, valueNote(row)),
      ),
      visibilityPill(row.visibility),
    ))),
  );
}

/**
 * Says how the number is known rather than implying a market value we never
 * measured. "At cost" is honest; a confident figure would not be.
 */
function valueNote(row) {
  switch (row.valueBasis) {
    case "valued": return `valued ${formatDate(row.valuedOn)}`;
    case "at_cost": return "at cost";
    case "custom_field": return "your figure";
    default: return "no value yet";
  }
}

function visibilityPill(visibility) {
  if (visibility === "household") return el("span.pill", {}, "Shared");
  if (visibility === "scoped") return el("span.pill.pill-accent", {}, "Scoped");
  return el("span.pill", { title: "Only you can see this" }, "Private");
}
