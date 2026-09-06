/* Home — the whole picture, calmly. docs/03 §2.

   Phase 0 reports Total Assets rather than true net worth, because liabilities
   arrive in Phase 1. The label says so plainly: a figure called "net worth"
   that quietly ignores a home loan is worse than no figure at all. */

import { api } from "../api.js";
import { el, mount, segmented, categoryDot, skeletonRows, empty, rupees, relativeDays } from "../ui.js";
import { state, update } from "../state.js";
import { openCapture } from "./capture.js";
import { openDetail } from "./detail.js";
import { navigate } from "../app.js";
import { t } from "../i18n.js";

export async function homeScreen(host) {
  mount(host, el("div.stack", {}, el("div.skeleton", { style: { height: "180px", borderRadius: "24px" } }), skeletonRows(3)));

  const scopes = [
    { value: "me", label: t("home.scope.me") },
    { value: "household", label: state.household.name },
    ...state.members.filter((m) => !m.isMe).map((m) => ({ value: `member:${m.id}`, label: m.displayName })),
  ];

  const current = state.scopeMember ? `member:${state.scopeMember}` : state.scope;

  const load = async () => {
    const scope = state.scopeMember ? "member" : state.scope;
    return api.dashboard(state.household.id, scope, state.scopeMember);
  };

  const data = await load();
  const upcoming = data.upcoming.slice(0, 4);

  mount(host, el("div.stack", {},

    el("div.row.wrap", {},
      segmented(scopes, current, (value) => {
        if (value.startsWith("member:")) update({ scope: "member", scopeMember: value.slice(7) });
        else update({ scope: value, scopeMember: null });
        homeScreen(host);
      }),
    ),

    // The hero. Gold appears exactly once per screen, and this is it.
    //
    // The headline is TRUE net worth. Assets and liabilities sit beside it as
    // sub-figures rather than being hidden: a single number people are asked to
    // trust should show its own arithmetic.
    el("div.hero", {},
      el("div.row-between.wrap", { style: { alignItems: "flex-start", gap: "24px" } },
        el("div", {},
          el("div.overline", {}, t("home.netWorth")),
          el("div.hero-amount", {}, data.netWorthFormatted),
          el("div.hero-words", {}, data.netWorthInWords),
        ),
        el("div.hero-side", {},
          el("div.hero-side-row", {},
            el("span.muted", {}, t("home.assets")), el("b", {}, data.totalAssetsFormatted)),
          el("div.hero-side-row", {},
            el("span.muted", {}, t("home.owed")),
            el("b", { style: { color: "var(--caution)" } },
              Number(data.totalLiabilities) > 0 ? `− ${data.totalLiabilitiesFormatted}` : "—")),
          el("div", { style: { height: "1px", background: "var(--hairline)", margin: "4px 0" } }),
          el("div.hero-side-row", {},
            el("span.muted", {}, "Holdings"), el("b", {}, String(data.holdingCount))),
          el("div.hero-side-row", {},
            el("span.muted", {}, "Loans"), el("b", {}, String(data.liabilityCount))),
          data.valueConfidence.unknown > 0 && el("div.hero-side-row", {},
            el("span.muted", {}, "No value yet"), el("b", {}, String(data.valueConfidence.unknown))),
        ),
      ),
      el("p.caption.faint", { style: { marginTop: "20px", marginBottom: 0 } }, data.disclaimer),
    ),

    data.holdingCount === 0
      ? el("div.card", {}, empty({
          title: t("home.empty.title"),
          body: t("home.empty.body"),
          action: el("button.btn.btn-primary", { onclick: () => openCapture() }, t("home.empty.action")),
        }))
      : el("div.grid.grid-2", {},
          breakdownCard(t("home.whereItSits"), data.byCategory, true),
          data.byLiabilityKind.length > 0
            ? breakdownCard("What's owed", data.byLiabilityKind, false)
            : breakdownCard(t("home.whoseItIs"), data.byMember, false),
        ),

    data.byLiabilityKind.length > 0 && el("div.grid.grid-2", {},
      breakdownCard(t("home.whoseItIs"), data.byMember, false),
      el("div.card", {},
        el("div.section-title", {}, el("h4", {}, "Owned against owed")),
        el("div.alloc", {},
          barRow("Assets", data.totalAssets, data.totalAssetsFormatted,
                 data.totalAssets, "var(--positive)"),
          barRow("Owed", data.totalLiabilities, data.totalLiabilitiesFormatted,
                 data.totalAssets, "var(--caution)"),
        ),
        el("p.caption.muted", { style: { marginTop: "16px", marginBottom: 0 } },
          `What's left is yours: ${data.netWorthFormatted}.`),
      ),
    ),

    upcoming.length > 0 && el("div.card", {},
      el("div.section-title", {}, el("h4", {}, "Coming up"), el("span.caption.muted", {}, "Next 90 days")),
      el("div.list", {}, ...upcoming.map((item) =>
        el("button.list-row", {
          type: "button",
          // An EMI points at a liability, not a holding, so only maturities open
          // a holding's detail sheet.
          onclick: () => { if (item.kind === "maturity") openDetail(item.investmentId); },
          style: item.kind === "emi" ? { cursor: "default" } : null,
        },
          el(`span.pill${item.kind === "emi" ? ".pill-caution" : ""}`, {},
            item.kind === "maturity" ? "Matures" : "EMI due"),
          el("div.grow", {},
            el("div.title", {}, item.title),
            el("div.meta", {}, relativeDays(item.date)),
          ),
          el("div.amount", {}, el("b", {}, rupees(item.value))),
        ))),
    ),

    data.attention.length > 0 && el("div.stack-3", {},
      el("h4", {}, t("home.attention")),
      ...data.attention.map((item) => el("div.banner", {},
        el("div.grow", {},
          el("b", {}, item.label),
          el("div.caption", {}, `${item.count} ${item.count === 1 ? "holding" : "holdings"}`),
        ),
        el("button.btn.btn-sm", { type: "button", onclick: () => navigate("investments") }, "Review"),
      )),
    ),
  ));
}

function barRow(label, value, formatted, scale, colour) {
  const pct = Number(scale) > 0 ? Math.max(2, (Number(value) / Number(scale)) * 100) : 0;
  return el("div.alloc-row", {},
    el("span.dot", { style: { background: colour }, "aria-hidden": "true" }),
    el("div", {},
      el("div.alloc-label", {}, label),
      el("div.alloc-bar", {},
        el("i", { style: { width: `${Math.min(100, pct)}%`, background: colour } })),
    ),
    el("div.alloc-value", {}, formatted),
  );
}

function breakdownCard(title, rows, useCategoryColour) {
  return el("div.card", {},
    el("div.section-title", {}, el("h4", {}, title)),
    rows.length === 0
      ? el("p.caption.muted", {}, "Nothing to show yet.")
      : el("div.alloc", {}, ...rows.slice(0, 8).map((row) => el("div.alloc-row", {},
          categoryDot(useCategoryColour ? row.key : null, row.color || "var(--accent)"),
          el("div", {},
            el("div.alloc-label", {}, row.label),
            el("div.alloc-bar", {},
              el("i", {
                style: {
                  width: `${Math.max(2, Number(row.percentage))}%`,
                  background: row.color || "var(--accent)",
                },
              })),
          ),
          el("div.alloc-value", {},
            el("div", {}, row.valueFormatted),
            el("div.caption.faint", {}, `${row.percentage}%`),
          ),
        ))),
  );
}
