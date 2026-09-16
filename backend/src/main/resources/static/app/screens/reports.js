/* =============================================================================
   Reports — completeness, concentration, liquidity and export (docs/10 Epic 2.5).

   The completeness card is the one that has to be handled carefully. It is a
   measure of whether these records would survive being read by someone who did
   not create them — not a rating of the person keeping them. So it leads with
   one next step rather than five failures, and an empty household is greeted
   with an invitation instead of a score of nought.
   ============================================================================= */

import { api, downloadAuthenticated } from "../api.js";
import { el, mount, skeletonRows, withBusy, toast, notice } from "../ui.js";
import { state } from "../state.js";
import { openDetail } from "./detail.js";
import { completenessPercent } from "../completeness.js";
import { t } from "../i18n.js";

export async function reportsScreen(host) {
  mount(host, skeletonRows(4));
  const [completeness, insights] = await Promise.all([
    api.completeness(state.household.id),
    api.insights(state.household.id),
  ]);

  mount(host, el("div.stack", {},
    el("h2", {}, t("nav.reports")),
    completenessCard(completeness, host),
    insightsCard(insights),
    exportCard(),
  ));
}

function completenessCard(report, host) {
  const percent = completenessPercent(report);
  return el("div.card.stack-2", {},
    el("div.row-between.wrap", { style: { alignItems: "baseline" } },
      el("h3", {}, t("reports.complete")),
      // A score is progress, not the household's worth: ink, never brass (D-01).
      percent && el("div.score", {}, percent),
    ),
    el("p.muted", {}, report.scoreLabel),
    !percent && report.scoreExplanation && el("p", { "data-no-score": "true" }, report.scoreExplanation),
    report.nextStep && el("p", { style: { fontWeight: 500 } }, report.nextStep),

    ...report.checks.map((check) => el("div.row-between.wrap", {},
      el("div", {},
        el("span", {}, check.label),
        el("span.caption.muted", {}, `: ${t("reports.doneToGo", { done: check.done, outstanding: check.outstanding })}`),
      ),
      check.outstanding > 0 && el("button.btn.btn-sm", {
        type: "button",
        onclick: () => openDetail(check.investmentIds[0], () => reportsScreen(host)),
        "aria-label": `${t("reports.fixFirst")}: ${check.label}`,
      }, t("reports.fixFirst")),
    )),

    notice(report.note),
  );
}

function insightsCard(insights) {
  if (insights.concentration.length === 0) {
    return el("div.card.stack-2", {},
      el("h3", {}, t("reports.whereMoney")),
      ...insights.observations.map((line) => el("p.muted", {}, line)),
    );
  }

  return el("div.card.stack-2", {},
    el("div.row-between.wrap", {},
      el("h3", {}, t("reports.whereMoney")),
      el("b", {}, insights.totalAssetsFormatted),
    ),

    el("div.stack-2", {},
      el("span.overline", {}, t("reports.bunched")),
      ...insights.concentration.map((item) => el("div.row-between", {},
        el("span", {}, `${labelFor(item.kind)}: ${item.label}`),
        el("span.muted", {}, `${item.percentage}% · ${item.valueFormatted}`),
      )),
    ),

    el("div.stack-2", {},
      el("span.overline", {}, t("reports.liquidity")),
      ...insights.liquidity.map((bucket) => el("div.stack-2", {},
        el("div.row-between", {},
          el("span", {}, bucket.label),
          el("span.muted", {}, `${bucket.percentage}% · ${bucket.valueFormatted}`),
        ),
        el("div.meter", { role: "img", "aria-label": `${bucket.label}: ${bucket.percentage}%` },
          el("div.meter-fill", { style: { width: `${Math.min(100, Number(bucket.percentage))}%` } })),
        el("span.caption.muted", {}, bucket.description),
      )),
    ),

    insights.observations.length > 0 && el("div.stack-2", {},
      el("span.overline", {}, t("reports.noticing")),
      ...insights.observations.map((line) => el("p.caption.muted", {}, line)),
    ),

    notice(insights.disclaimer),
  );
}

const labelFor = (kind) => (["holding", "institution", "category"].includes(kind) ? t(`reports.largest.${kind}`) : kind);

function exportCard() {
  const buttons = ["csv", "xlsx", "pdf"].map((format) => {
    const button = el("button.btn", {
      type: "button", "aria-label": t("reports.downloadAs", { format: format.toUpperCase() }),
    }, format.toUpperCase());
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
    el("h3", {}, t("reports.export.title")),
    el("p.caption.muted", {}, t("reports.export.body")),
    el("div.row.wrap", { style: { gap: "8px" } }, ...buttons),
  );
}
