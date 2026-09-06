/* =============================================================================
   Tax (docs/01 §9, docs/03 §11).

   Informational, never advice. Every figure here is a summary of what the
   household has already recorded, arranged the way a return asks for it — a
   starting point for a person or their CA, not a filing. The disclaimer travels
   with the numbers rather than sitting in a footer nobody reads.

   Nothing is projected and nothing is optimised: no "invest ₹40,000 more to
   save tax". Where a meter is derived from a declared figure rather than from
   recorded transactions, it says which.
   ============================================================================= */

import { api } from "../api.js";
import { el, mount, select, skeletonRows, segmented } from "../ui.js";
import { state } from "../state.js";

/** 1 April to 31 March. In September 2026 the current year is 2026-27. */
function financialYears(count = 4) {
  const today = new Date();
  const startYear = today.getMonth() >= 3 ? today.getFullYear() : today.getFullYear() - 1;
  return Array.from({ length: count }, (_, index) => {
    const year = startYear - index;
    return { value: `${year}-${String((year + 1) % 100).padStart(2, "0")}`, label: `FY ${year}-${String((year + 1) % 100).padStart(2, "0")}` };
  });
}

export async function taxScreen(host, { fy = null, member = null } = {}) {
  mount(host, skeletonRows(4));
  const years = financialYears();
  const year = fy || years[0].value;
  const pack = await api.taxPack(state.household.id, year, member);

  const memberOptions = [
    { value: "", label: "Everyone I can see" },
    ...state.members.map((m) => ({ value: m.id, label: m.displayName })),
  ];
  const memberSelect = select({ options: memberOptions, value: member || "", "aria-label": "Whose tax" });
  memberSelect.addEventListener("change", () =>
    taxScreen(host, { fy: year, member: memberSelect.value || null }));

  mount(host, el("div.stack", {},
    el("div.row-between.wrap", {},
      el("h2", {}, "Tax"),
      el("div.row.wrap", { style: { gap: "8px" } },
        segmented(years, year, (value) => taxScreen(host, { fy: value, member })),
        memberSelect,
      ),
    ),

    el("div.banner", {}, pack.disclaimer),

    el("div.stack-2", {},
      el("span.overline", {}, "Deductions"),
      ...pack.deductions.map(meter),
    ),

    gainsCard(pack.capitalGains),
    interestCard(pack.interestIncome),
  ));
}

function meter(deduction) {
  const percent = Math.max(0, Math.min(100, Number(deduction.percentUsed || 0)));
  return el("div.card.stack-2", {},
    el("div.row-between.wrap", {},
      el("div", {},
        el("b", {}, deduction.label),
        el("div.caption.muted", {}, deduction.description),
      ),
      el("div", {}, `${deduction.usedFormatted} of ${deduction.limitFormatted}`),
    ),
    el("div.meter", { role: "img", "aria-label": `${percent}% of the limit used` },
      el("div.meter-fill", { style: { width: `${percent}%` } })),
    el("span.caption.muted", {}, `${deduction.remainingFormatted} of the limit unused.`),
    deduction.note && el("span.caption.muted", {}, deduction.note),
    deduction.sources.length > 0 && el("details", {},
      el("summary.caption", {}, `From ${deduction.sources.length} ${deduction.sources.length === 1 ? "record" : "records"}`),
      el("div.stack-2", { style: { paddingTop: "8px" } },
        ...deduction.sources.map((source) => el("div.row-between", {},
          el("span", {}, source.title),
          el("span.muted", {}, source.amountFormatted, " ",
            el("span.caption", {}, `(${source.basis})`)),
        )),
      ),
    ),
  );
}

function gainsCard(gains) {
  return el("div.card.stack-2", {},
    el("div.row-between.wrap", {},
      el("h3", {}, "Capital gains"),
      el("b", {}, gains.netRealizedFormatted),
    ),
    gains.realized.length === 0
      ? el("p.caption.muted", {}, "Nothing sold in this year, so there is nothing realised to report.")
      : el("div.stack-2", {}, ...gains.realized.map((bucket) => el("div.row-between", {},
          el("span", {}, bucket.label),
          el("span.muted", {},
            `${bucket.gainFormatted} · ${bucket.disposals} ${bucket.disposals === 1 ? "sale" : "sales"}`),
        ))),

    gains.unrealized.length > 0 && el("details", {},
      el("summary.caption", {}, "If you sold today"),
      el("div.stack-2", { style: { paddingTop: "8px" } },
        el("p.caption.muted", {},
          "For planning only. Nothing here has been sold, and this is not a projection of value."),
        ...gains.unrealized.slice(0, 20).map((position) => el("div.row-between", {},
          el("span", {}, position.title),
          el("span.muted", {}, `${position.unrealizedGainFormatted} · ${position.termIfSoldToday}`),
        )),
      ),
    ),
  );
}

function interestCard(interest) {
  return el("div.card.stack-2", {},
    el("div.row-between.wrap", {},
      el("h3", {}, "Interest income"),
      el("b", {}, interest.totalFormatted),
    ),
    interest.bySource.length === 0
      ? el("p.caption.muted", {}, "No interest recorded for this year.")
      : el("div.stack-2", {}, ...interest.bySource.map((source) => el("div.row-between", {},
          el("span", {}, source.title),
          el("span.muted", {}, source.amountFormatted),
        ))),
  );
}
