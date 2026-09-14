/* The list. Searchable, filterable, and honest about what it does not know.

   Each row reads at a glance (X-54): the category's icon, the title with one
   line of what needs doing, the owners' initials in their own colours, the
   value, and who can see it in plain words. The list draws from the last known
   view first and refreshes quietly (X-38). */

import { api } from "../api.js";
import {
  el, mount, categoryIcon, chipRow, skeletonRows, empty, money, formatDate, textInput, icon, avatarStack,
} from "../ui.js";
import { state } from "../state.js";
import { t, categoryName } from "../i18n.js";
import { openCapture } from "./capture.js";
import { openDetail } from "./detail.js";
import { loadReview, firstNeedByRecord, needLabel } from "../review.js";

let filters = { q: "", category: null };

export async function investmentsScreen(host) {
  const search = textInput({
    type: "search", placeholder: t("investments.search.placeholder"), value: filters.q,
    "aria-label": t("investments.search.label"),
  });

  const results = el("div", {}, skeletonRows(4));

  const load = async () => {
    const params = new URLSearchParams();
    if (filters.q) params.set("q", filters.q);
    if (filters.category) params.set("category", filters.category);
    const query = params.toString();
    const path = `/api/v1/households/${state.household.id}/investments${query ? `?${query}` : ""}`;
    const kept = api.peek(path);
    const keptInbox = api.peek(api.reviewPath(state.household.id));
    if (kept) mount(results, renderRows(kept, firstNeedByRecord(keptInbox), load));
    else mount(results, skeletonRows(4));

    // The needs-doing lines come from To review; without it a row simply says
    // what it is, and the list still draws.
    const [rows, inbox] = await Promise.all([
      api.investments(state.household.id, query),
      loadReview(state.household.id),
    ]);
    if (!results.isConnected) return;
    if (!kept || JSON.stringify(kept) !== JSON.stringify(rows) || JSON.stringify(keptInbox) !== JSON.stringify(inbox)) {
      mount(results, renderRows(rows, firstNeedByRecord(inbox), load));
    }
  };

  // Debounced so a search does not fire a request per keystroke.
  let timer;
  search.addEventListener("input", () => {
    clearTimeout(timer);
    filters.q = search.value.trim();
    timer = setTimeout(load, 250);
  });

  // One row that scrolls sideways, not fourteen chips wrapping into two (D-09).
  const categoryChips = chipRow(t("investments.filter"),
    el("button.chip", {
      type: "button", "aria-pressed": filters.category === null,
      onclick: () => { filters.category = null; drawChips(); load(); },
    }, t("investments.everything")),
    ...state.taxonomy.map((category) => el("button.chip", {
      type: "button", "aria-pressed": filters.category === category.categoryCode,
      onclick: () => {
        filters.category = filters.category === category.categoryCode ? null : category.categoryCode;
        drawChips(); load();
      },
    }, categoryIcon(category.categoryCode, category.color), categoryName(category.categoryCode, category.categoryLabel))),
  );

  function drawChips() {
    [...categoryChips.children].forEach((chip, index) => {
      const value = index === 0 ? null : state.taxonomy[index - 1].categoryCode;
      chip.setAttribute("aria-pressed", String(filters.category === value));
    });
  }

  mount(host, el("div.stack", {},
    el("div.row-between.wrap", {},
      el("h1", {}, t("nav.investments")),
      // The + in the navigation is this screen's one teal action (docs/25 §2).
      el("button.btn", { type: "button", onclick: () => openCapture(load) }, icon("plus"), t("app.add").replace(/^＋\s*/, "")),
    ),
    search,
    categoryChips,
    results,
  ));

  await load();
}

function renderRows(rows, needs, onChanged) {
  if (rows.length === 0) {
    return el("div.card", {}, empty({
      title: t("investments.empty.title"),
      body: filters.q || filters.category
        ? t("investments.empty.filtered")
        : t("investments.empty.body"),
      action: el("button.btn.btn-primary", { type: "button", onclick: () => openCapture() }, icon("plus"), t("app.addSomething")),
    }));
  }

  return el("div.card.card-tight", {},
    el("div.list", {}, ...rows.map((row) => {
      const need = needs.get(row.id);
      const owners = row.owners.map((o) => o.name).filter(Boolean).join(" & ");
      const note = valueNote(row);
      const what = [row.typeLabel, row.institutionName].filter(Boolean).join(" · ");
      return el("button.list-row.holding-row", {
        type: "button", onclick: () => openDetail(row.id, onChanged),
      },
        categoryIcon(row.categoryCode, row.color),
        el("div.grow", { style: { minWidth: 0 } },
          el("div.title", {}, row.title),
          el("div.meta", {},
            what,
            // One thing to do, the most urgent, in ink: it is the reason to open the row.
            need && [what && " · ", el("span.needs", {}, needLabel(need))]),
        ),
        row.owners.length > 0 && avatarStack(row.owners, t("holding.ownedBy", { names: owners })),
        // D-11: ₹42 L in a phone's list, the full figure where there is room.
        // X-71: no amount is an invitation, not a dash.
        el("div.amount", {},
          row.valueFormatted
            ? el("b", {}, money(row.valueFormatted, row.value))
            : el("span.link-quiet", {}, t("block.addAmount")),
          row.valueFormatted && note && el("div.meta", {}, note),
        ),
        visibilityPill(row.visibility),
      );
    })),
  );
}

/**
 * Says how the number is known rather than implying a market value we never
 * measured. "At cost" is honest; a confident figure would not be.
 */
function valueNote(row) {
  switch (row.valueBasis) {
    case "valued": return row.valuationSource === "price_feed"
      ? t(row.priceSource === "amfi" ? "value.navShort" : "value.closeShort", { date: formatDate(row.valuedOn) })
      : t("value.valuedOn", { date: formatDate(row.valuedOn) });
    // "at cost" on every row said nothing (X-54); the detail panel explains it.
    case "at_cost": return null;
    case "custom_field": return t("value.yourFigure");
    default: return null;
  }
}

/** Who can see it, in words a person uses (X-54): never "Scoped" or "FULL". */
function visibilityPill(visibility) {
  if (visibility === "household") return el("span.pill", { title: t("privacy.pill.householdTitle") }, t("privacy.pill.household"));
  if (visibility === "scoped") return el("span.pill", { title: t("privacy.pill.scopedTitle") }, t("privacy.pill.scoped"));
  return el("span.pill", { title: t("privacy.pill.privateTitle") }, t("privacy.pill.private"));
}
