/* Home — the whole picture, calmly. docs/03 §2.

   The headline is true net worth: assets less what is owed, with both shown
   beside it, because a figure called "net worth" that quietly ignores a home
   loan is worse than no figure at all. Under it, the line of how that has moved
   (P-15); then what is waiting — To review, in one card (X-51) — and what is
   due, before any breakdown.

   Coming back to Home draws the last known view at once and refreshes it
   quietly (X-38): placeholders only when there is nothing to show yet. */

import { api } from "../api.js";
import {
  el, mount, segmented, skeletonRows, empty, money, when, notice, withoutZeroRows,
  areaTrend, donut, onDemand, updatedNote,
} from "../ui.js";
import { state, update } from "../state.js";
import { openCapture } from "./capture.js";
import { openDetail } from "./detail.js";
import { t, language } from "../i18n.js";
import { loadReview, reviewCard } from "../review.js";
import { trendPoints } from "../glance.js";

export async function homeScreen(host) {
  const hid = state.household.id;
  const scope = state.scopeMember ? "member" : state.scope;
  const member = state.scopeMember;
  const paths = {
    dashboard: api.dashboardPath(hid, scope, member),
    trend: api.trendPath(hid, scope, member),
    review: api.reviewPath(hid),
  };

  const last = {
    data: api.peek(paths.dashboard),
    trend: api.peek(paths.trend) || null,
    inbox: api.peek(paths.review) || null,
  };
  let status = host.querySelector("[data-home-status]");
  const draw = (view) => { status = render(host, view); host.dataset.home = paths.dashboard; };

  // After a change (a nominee saved, a question answered) every kept view is
  // gone, but the one on screen is still this scope's: leave it up while the
  // new one comes, rather than flashing placeholders over it.
  if (last.data) draw(last);
  else if (host.dataset.home !== paths.dashboard) mount(host, el("div.stack", {}, el("div.skeleton", { style: { height: "180px", borderRadius: "24px" } }), skeletonRows(3)));

  // Beside the dashboard, never instead of it: a failed trend or inbox is no
  // line and no card, not a failed Home.
  let fresh;
  try {
    const [data, trend, inbox] = await Promise.all([
      api.dashboard(hid, scope, member),
      api.netWorthTrend(hid, scope, member).catch(() => null),
      loadReview(hid),
    ]);
    fresh = { data, trend, inbox };
  } catch (error) {
    if (!last.data) throw error;
    if (host.isConnected && status) status.textContent = t("block.notRefreshed");
    return;
  }

  // The person may have moved on while this was fetched.
  if (!host.isConnected) return;
  const shown = Boolean(last.data) || host.dataset.home === paths.dashboard;
  if (!last.data || JSON.stringify(last) !== JSON.stringify(fresh)) draw(fresh);
  if (shown && status) mount(status, updatedNote());
}

function render(host, { data, trend, inbox }) {
  const scopes = [
    { value: "me", label: t("home.scope.me") },
    { value: "household", label: state.household.name },
    ...state.members.filter((m) => !m.isMe).map((m) => ({ value: `member:${m.id}`, label: m.displayName })),
  ];
  const current = state.scopeMember ? `member:${state.scopeMember}` : state.scope;
  const upcoming = data.upcoming.slice(0, 4);
  const status = el("div", { "data-home-status": "" });
  const refresh = () => homeScreen(host);

  mount(host, el("div.stack", {},

    el("div.row-between.wrap", {},
      segmented(scopes, current, (value) => {
        if (value.startsWith("member:")) update({ scope: "member", scopeMember: value.slice(7) });
        else update({ scope: value, scopeMember: null });
        homeScreen(host);
      }),
      status,
    ),

    // The hero: brass for the figure, gold only for the hairline under it.
    // Assets and liabilities sit beside it as sub-figures: a single number
    // people are asked to trust should show its own arithmetic. On a phone that
    // arithmetic waits behind a tap, so what is waiting is still on the first
    // screen (X-55).
    hero(data, trend),

    // What is waiting, then what is due, before any breakdown: what needs doing
    // comes before what there is.
    reviewCard(state.household.id, inbox, { onChanged: refresh }),

    upcoming.length > 0 && el("div.card", {},
      el("div.section-title", {}, el("h4", {}, t("home.upcoming")), el("span.caption", {}, t("home.next90"))),
      el("div.list", {}, ...upcoming.map((item) =>
        el("button.list-row", {
          type: "button",
          // An EMI points at a liability, not a holding, so only maturities open
          // a holding's detail panel.
          onclick: () => { if (item.kind === "maturity") openDetail(item.investmentId, refresh); },
          style: item.kind === "emi" ? { cursor: "default" } : null,
        },
          el(`span.pill${item.kind === "emi" ? ".pill-caution" : ""}`, {},
            item.kind === "maturity" ? t("review.kind.maturity") : t("home.emiDue")),
          el("div.grow", {},
            el("div.title", {}, item.title),
            el("div.meta", {}, when(item.date)),
          ),
          el("div.amount", {}, el("b", {}, money(null, item.value))),
        ))),
    ),

    data.holdingCount === 0
      ? el("div.card", {}, empty({
          title: t("home.empty.title"),
          body: t("home.empty.body"),
          action: el("button.btn.btn-primary", { onclick: () => openCapture() }, t("home.empty.action")),
        }))
      : el("div.grid.grid-2", {},
          allocationCard(data.byCategory),
          data.byLiabilityKind.length > 0
            ? breakdownCard(t("home.owedBreakdown"), data.byLiabilityKind)
            : breakdownCard(t("home.whoseItIs"), data.byMember),
        ),

    data.byLiabilityKind.length > 0 && el("div.grid.grid-2", {},
      breakdownCard(t("home.whoseItIs"), data.byMember),
      el("div.card", {},
        el("div.section-title", {}, el("h4", {}, t("home.ownedOwed"))),
        el("div.alloc", {},
          barRow(t("home.assets"), data.totalAssets, data.totalAssetsFormatted,
                 data.totalAssets, "var(--accent)"),
          barRow(t("home.owed"), data.totalLiabilities, data.totalLiabilitiesFormatted,
                 data.totalAssets, "var(--caution)"),
        ),
        el("p.caption", { style: { marginTop: "16px", marginBottom: 0 } },
          t("home.whatsLeft", { amount: data.netWorthFormatted })),
      ),
    ),
  ));
  return status;
}

function hero(data, trend) {
  const node = el("div.hero", {},
    el("div.row-between.wrap", { style: { alignItems: "flex-start", gap: "24px" } },
      el("div", { style: { minWidth: 0 } },
        el("div.overline", {}, t("home.netWorth")),
        el("div.hero-amount", {}, data.netWorthFormatted),
        el("div.hero-words", {}, data.netWorthInWords),
      ),
      el("div.hero-side", { id: "hero-breakdown" },
        el("div.hero-side-row", {},
          el("span.muted", {}, t("home.assets")), el("b", {}, data.totalAssetsFormatted)),
        // X-71: nothing owed is no row, rather than a lone dash.
        Number(data.totalLiabilities) > 0 && el("div.hero-side-row", {},
          el("span.muted", {}, t("home.owed")),
          el("b.owed", {}, `− ${data.totalLiabilitiesFormatted}`)),
        el("div.hero-rule", { "aria-hidden": "true" }),
        el("div.hero-side-row", {},
          el("span.muted", {}, t("home.holdings")), el("b", {}, String(data.holdingCount))),
        data.liabilityCount > 0 && el("div.hero-side-row", {},
          el("span.muted", {}, t("home.loans")), el("b", {}, String(data.liabilityCount))),
        data.valueConfidence.unknown > 0 && el("div.hero-side-row", {},
          el("span.muted", {}, t("home.noValueYet")), el("b", {}, String(data.valueConfidence.unknown))),
      ),
    ),
  );
  const toggle = el("button.btn.btn-ghost.btn-sm.hero-toggle", {
    type: "button", "aria-expanded": "false", "aria-controls": "hero-breakdown",
    onclick: () => {
      const open = node.dataset.open !== "true";
      node.dataset.open = String(open);
      toggle.setAttribute("aria-expanded", String(open));
    },
  }, t("block.breakdown"));
  node.append(toggle);
  const line = trendBlock(trend);
  if (line) node.append(el("div", { style: { marginTop: "16px" } }, line));
  node.append(el("div", { style: { marginTop: "16px" } }, notice(data.disclaimer)));
  return node;
}

/** A slim line under the figure (P-15, docs/25 §7): today's point emphasised, no red or green. */
function trendBlock(trend) {
  const points = trendPoints(trend);
  if (points.length < 2) {
    return trend ? el("p.caption.muted", { style: { margin: 0 } }, t("home.trend.soon")) : null;
  }
  const locale = { en: "en-IN", te: "te-IN", hi: "hi-IN" }[language.code] || "en-IN";
  const month = (iso) => new Date(iso).toLocaleDateString(locale, { month: "short", year: "numeric" });
  const first = points[0];
  const latest = points[points.length - 1];
  const summary = t("home.trend.summary", {
    from: month(first.date), start: first.netWorthFormatted, end: latest.netWorthFormatted,
  });
  return onDemand(t("block.showChart"), () => el("div", {},
    areaTrend(points.map((point) => ({ label: month(point.date), value: point.netWorth })), { summary, height: 96, fromZero: false }),
    el("p.caption.muted", { style: { margin: "4px 0 0" } }, summary),
  ));
}

/** Where it sits: one labelled donut in the category colours, labels beside it (P-15). */
function allocationCard(rows) {
  const shown = withoutZeroRows(rows).slice(0, 8);
  const parts = shown.map((row) => `${row.label} ${row.percentage}%`).join(", ");
  return el("div.card", {},
    el("div.section-title", {}, el("h4", {}, t("home.whereItSits"))),
    shown.length === 0
      ? el("p.caption", {}, t("home.nothingToShow"))
      : onDemand(t("block.showChart"), () => donut(shown.map((row) => ({
          label: row.label,
          value: Number(row.value),
          color: row.color || `var(--cat-${row.key}, var(--ink-muted))`,
          display: `${row.valueFormatted} · ${row.percentage}%`,
        })), { summary: t("home.allocation.summary", { parts }) })),
  );
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

function breakdownCard(title, rows) {
  // X-71: "Insurance ₹0 · 0%" is noise, not information.
  const shown = withoutZeroRows(rows);
  return el("div.card", {},
    el("div.section-title", {}, el("h4", {}, title)),
    shown.length === 0
      ? el("p.caption", {}, t("home.nothingToShow"))
      : el("div.alloc", {}, ...shown.slice(0, 8).map((row) => el("div.alloc-row", {},
          el("span.dot", { style: { background: row.color || "var(--accent)" }, "aria-hidden": "true" }),
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
            el("div.caption", {}, `${row.percentage}%`),
          ),
        ))),
  );
}
