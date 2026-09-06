/* =============================================================================
   Reports — completeness, concentration, liquidity and export (docs/10 Epic 2.5).

   The completeness card is the one that has to be handled carefully. It is a
   measure of whether these records would survive being read by someone who did
   not create them — not a rating of the person keeping them. So it leads with
   one next step rather than five failures, and an empty household is greeted
   with an invitation instead of a score of nought.
   ============================================================================= */

import { api, downloadAuthenticated } from "../api.js";
import { el, mount, skeletonRows, withBusy, toast } from "../ui.js";
import { state } from "../state.js";
import { openDetail } from "./detail.js";

export async function reportsScreen(host) {
  mount(host, skeletonRows(4));
  const [completeness, insights] = await Promise.all([
    api.completeness(state.household.id),
    api.insights(state.household.id),
  ]);

  mount(host, el("div.stack", {},
    el("h2", {}, "Reports"),
    completenessCard(completeness, host),
    insightsCard(insights),
    exportCard(),
  ));
}

function completenessCard(report, host) {
  return el("div.card.stack-2", {},
    el("div.row-between.wrap", { style: { alignItems: "baseline" } },
      el("h3", {}, "How complete this is"),
      el("div.hero-amount", { style: { fontSize: "var(--text-h2)" } }, `${report.score}%`),
    ),
    el("p.muted", {}, report.scoreLabel),
    report.nextStep && el("div.banner", {}, report.nextStep),

    ...report.checks.map((check) => el("div.row-between.wrap", {},
      el("div", {},
        el("span", {}, check.label),
        el("span.caption.muted", {}, ` — ${check.done} done, ${check.outstanding} to go`),
      ),
      check.outstanding > 0 && el("button.btn.btn-sm", {
        type: "button",
        onclick: () => openDetail(check.investmentIds[0], () => reportsScreen(host)),
      }, "Fix the first"),
    )),

    el("p.caption.muted", {}, report.note),
  );
}

function insightsCard(insights) {
  if (insights.concentration.length === 0) {
    return el("div.card.stack-2", {},
      el("h3", {}, "Where the money is"),
      ...insights.observations.map((line) => el("p.muted", {}, line)),
    );
  }

  return el("div.card.stack-2", {},
    el("div.row-between.wrap", {},
      el("h3", {}, "Where the money is"),
      el("b", {}, insights.totalAssetsFormatted),
    ),

    el("div.stack-2", {},
      el("span.overline", {}, "Bunched"),
      ...insights.concentration.map((item) => el("div.row-between", {},
        el("span", {}, `${labelFor(item.kind)}: ${item.label}`),
        el("span.muted", {}, `${item.percentage}% · ${item.valueFormatted}`),
      )),
    ),

    el("div.stack-2", {},
      el("span.overline", {}, "How quickly you could reach it"),
      ...insights.liquidity.map((bucket) => el("div.stack-2", {},
        el("div.row-between", {},
          el("span", {}, bucket.label),
          el("span.muted", {}, `${bucket.percentage}% · ${bucket.valueFormatted}`),
        ),
        el("div.meter", { role: "img", "aria-label": `${bucket.percentage}%` },
          el("div.meter-fill", { style: { width: `${Math.min(100, Number(bucket.percentage))}%` } })),
        el("span.caption.muted", {}, bucket.description),
      )),
    ),

    insights.observations.length > 0 && el("div.stack-2", {},
      el("span.overline", {}, "Worth noticing"),
      ...insights.observations.map((line) => el("p.caption.muted", {}, line)),
    ),

    el("p.caption.muted", {}, insights.disclaimer),
  );
}

const labelFor = (kind) => ({
  holding: "Largest holding", institution: "Largest institution", category: "Largest kind",
}[kind] || kind);

function exportCard() {
  const buttons = ["csv", "xlsx", "pdf"].map((format) => {
    const button = el("button.btn", { type: "button" }, format.toUpperCase());
    button.onclick = () => withBusy(button, async () => {
      try {
        await downloadAuthenticated(
          api.exportUrl(state.household.id, format), `almira-holdings.${format}`);
      } catch (error) {
        toast(error.message, { tone: "error" });
      }
    });
    return button;
  });

  return el("div.card.stack-2", {},
    el("h3", {}, "Take your data with you"),
    el("p.caption.muted", {},
      "Everything you can see, in a file you own. The spreadsheet formats carry " +
      "raw numbers so you can add them up yourself."),
    el("div.row.wrap", { style: { gap: "8px" } }, ...buttons),
  );
}
