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

export async function homeScreen(host) {
  mount(host, el("div.stack", {}, el("div.skeleton", { style: { height: "180px", borderRadius: "24px" } }), skeletonRows(3)));

  const scopes = [
    { value: "me", label: "Me" },
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
    el("div.hero", {},
      el("div.row-between.wrap", { style: { alignItems: "flex-start", gap: "24px" } },
        el("div", {},
          el("div.overline", {}, "Total assets"),
          el("div.hero-amount", {}, data.totalAssetsFormatted),
          el("div.hero-words", {}, data.totalAssetsInWords),
        ),
        el("div.hero-side", {},
          el("div.hero-side-row", {}, el("span.muted", {}, "Holdings"), el("b", {}, String(data.holdingCount))),
          el("div.hero-side-row", {}, el("span.muted", {}, "Valued"), el("b", {}, String(data.valueConfidence.valued))),
          el("div.hero-side-row", {}, el("span.muted", {}, "At cost"), el("b", {}, String(data.valueConfidence.atCost))),
          data.valueConfidence.unknown > 0 && el("div.hero-side-row", {},
            el("span.muted", {}, "No value yet"), el("b", {}, String(data.valueConfidence.unknown))),
        ),
      ),
      el("p.caption.faint", { style: { marginTop: "20px", marginBottom: 0 } }, data.disclaimer),
    ),

    data.holdingCount === 0
      ? el("div.card", {}, empty({
          title: "Nothing recorded yet",
          body: "Add your first holding — a fixed deposit or some gold takes about twenty seconds.",
          action: el("button.btn.btn-primary", { onclick: () => openCapture() }, "＋ Add your first thing"),
        }))
      : el("div.grid.grid-2", {},
          breakdownCard("Where it sits", data.byCategory, true),
          breakdownCard("Whose it is", data.byMember, false),
        ),

    upcoming.length > 0 && el("div.card", {},
      el("div.section-title", {}, el("h4", {}, "Coming up"), el("span.caption.muted", {}, "Next 90 days")),
      el("div.list", {}, ...upcoming.map((item) =>
        el("button.list-row", { type: "button", onclick: () => openDetail(item.investmentId) },
          el("span.pill", {}, item.kind === "maturity" ? "Matures" : item.kind),
          el("div.grow", {},
            el("div.title", {}, item.title),
            el("div.meta", {}, relativeDays(item.date)),
          ),
          el("div.amount", {}, el("b", {}, rupees(item.value))),
        ))),
    ),

    data.attention.length > 0 && el("div.stack-3", {},
      el("h4", {}, "Worth a look"),
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
