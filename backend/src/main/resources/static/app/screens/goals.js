/* =============================================================================
   Goals (docs/03 §7).

   A goal turns a pile of holdings into an answer to the question people
   actually ask: will there be enough, and when. So the screen leads with the
   ring and the shortfall, then says what is funding it — and, where a goal has
   no funding at all, says that instead of drawing an empty ring and leaving the
   reader to work out why.

   On-track is a neutral observation, never a verdict, and never advice.
   ============================================================================= */

import { api } from "../api.js";
import { el, mount, sheet, field, textInput, moneyInput, select, skeletonRows, empty, withBusy, toast, formatDate, ring, icon } from "../ui.js";
import { state } from "../state.js";
import { t } from "../i18n.js";
import { GOAL_STARTERS } from "../starters.js";

export async function goalsScreen(host) {
  mount(host, skeletonRows(3));
  const goals = await api.goals(state.household.id);

  if (goals.length === 0) {
    mount(host, el("div.stack", {},
      empty({
        title: t("goals.empty.title"),
        body: t("goals.empty.body"),
        action: el("button.btn.btn-primary", { type: "button", onclick: () => newGoal(host) }, t("goals.set")),
      }),
      goalStarters(host),
    ));
    return;
  }

  mount(host, el("div.stack", {},
    el("div.row-between.wrap", {},
      el("h2", {}, t("nav.goals")),
      el("button.btn.btn-primary.btn-sm", { type: "button", onclick: () => newGoal(host) }, icon("plus"), t("goals.new")),
    ),
    goalStarters(host),
    ...goals.map((goal) => goalCard(goal, host)),
    el("p.caption.muted", {}, goals[0].disclaimer),
  ));
}

function goalCard(goal, host) {
  const percent = Number(goal.progress.percentComplete || 0);
  return el("div.card.stack-2", {},
    el("div.row-between.wrap", { style: { alignItems: "flex-start", gap: "16px" } },
      el("div.stack-2", {},
        el("div.row", { style: { gap: "8px", alignItems: "baseline" } },
          el("h3", {}, goal.name),
          goal.memberName && el("span.caption.muted", {}, t("goals.for", { name: goal.memberName })),
        ),
        el("div.muted", {},
          t("goals.fundedOf", { funded: goal.fundedFormatted, target: goal.targetAmountFormatted }),
          goal.targetDate ? ` · ${t("goals.by", { date: formatDate(goal.targetDate) })}` : "",
        ),
      ),
      // Teal, like every meter: gold is the hero's hairline and nothing else (D-01).
      ring(Math.round(Number(percent) || 0), { label: t("goals.percentFunded", { percent: Math.round(Number(percent) || 0) }) }),
    ),

    goal.progress.note && el("p.caption.muted", {}, goal.progress.note),

    Number(goal.funded) > 0
      && goal.progress.onTrack !== null && goal.progress.onTrack !== undefined
      && el("span.chip.chip-static", { style: { alignSelf: "flex-start" } },
          goal.progress.onTrack ? t("goals.onTrack") : t("goals.behind")),

    goal.fundedBy.length > 0
      ? el("div.stack-2", {},
          el("span.overline", {}, t("goals.fundedBy")),
          ...goal.fundedBy.map((source) => el("div.row-between", {},
            el("span", {}, source.title),
            el("span.muted", {},
              `${source.allocationPct}% · ${source.contributionFormatted || ""}`),
          )),
        )
      : el("p.caption.muted", {}, t("goals.nothingPointed")),

    el("div.row.wrap", { style: { gap: "8px" } },
      el("button.btn.btn-sm", { type: "button", onclick: () => attachHolding(goal, host) },
        t("goals.pointHolding")),
    ),
  );
}


/**
 * Ready-made goals (X-31): "Aarav's degree · 2039". One tap fills the name, the
 * date and whose it is; the amount is left for the family, because what a
 * degree will cost them is theirs to say, not ours to suggest.
 */
function goalStarters(host) {
  const child = state.members.find((m) => m.isMinor);
  const year = new Date().getFullYear();
  return el("div.stack-2", { "data-goal-starters": "true" },
    el("span.overline", {}, t("goalStarter.heading")),
    el("div.row.wrap", { style: { gap: "8px" } },
      ...GOAL_STARTERS.map((starter) => {
        const forChild = starter.code === "education" || starter.code === "wedding";
        const name = forChild && child
          ? t(`goalStarter.${starter.code}.named`, { name: child.displayName })
          : t(`goalStarter.${starter.code}`);
        const targetYear = year + starter.yearsAhead;
        return el("button.chip", {
          type: "button",
          onclick: () => newGoal(host, {
            name, targetDate: `${targetYear}-06-01`, memberId: forChild && child ? child.id : "",
          }),
        }, `${name} · ${targetYear}`);
      }),
    ),
  );
}

function newGoal(host, prefill = null) {
  const name = textInput({ placeholder: t("goals.nameExample"), value: prefill?.name || "" });
  const target = moneyInput({ placeholder: "0" });
  const date = textInput({ type: "date", value: prefill?.targetDate || "" });
  const member = select({
    options: [
      { value: "", label: t("goals.household") },
      ...state.members.map((m) => ({ value: m.id, label: m.displayName })),
    ],
    value: prefill?.memberId || "",
  });
  const visibility = select({
    options: [
      { value: "private", label: t("goals.visibility.private") },
      { value: "household", label: t("common.sharedWith", { name: state.household.name }) },
    ],
    value: state.user?.defaultVisibility || "private",
  });
  const error = el("div.help.error", { role: "alert", style: { minHeight: "1.15rem" } });
  const save = el("button.btn.btn-primary.grow", { type: "button" }, t("goals.setTheGoal"));

  save.onclick = () => withBusy(save, async () => {
    error.textContent = "";
    if (!name.value.trim()) { error.textContent = t("goals.nameMissing"); return; }
    if (!target.value()) { error.textContent = t("goals.amountMissing"); return; }
    try {
      await api.createGoal(state.household.id, {
        name: name.value.trim(),
        targetAmount: target.value(),
        targetDate: date.value || null,
        memberId: member.value || null,
        visibility: visibility.value,
      });
      modal.close();
      toast(t("goals.saved"));
      await goalsScreen(host);
    } catch (apiError) {
      error.textContent = apiError.message;
    }
  });

  const modal = sheet({
    title: t("goals.new"),
    body: el("div.stack-3", {},
      field({ label: t("goals.whatFor"), control: name, required: true }),
      field({ label: t("goals.howMuch"), control: target, required: true }),
      field({ label: t("goals.byWhen"), control: date, help: t("goals.byWhenHelp") }),
      field({ label: t("goals.whose"), control: member }),
      field({ label: t("goals.whoCanSee"), control: visibility, help: t("goals.whoCanSeeHelp") }),
      error,
    ),
    footer: [save],
  });
}

function attachHolding(goal, host) {
  const list = el("div.stack-2", {}, skeletonRows(2));
  const error = el("div.help.error", { role: "alert", style: { minHeight: "1.15rem" } });

  (async () => {
    const holdings = await api.unallocated(state.household.id);
    if (holdings.length === 0) {
      mount(list, el("p.caption.muted", {}, t("goals.allAllocated")));
      return;
    }
    mount(list, ...holdings.map((holding) => {
      const share = textInput({
        type: "number", min: "1", max: String(holding.unallocatedPct ?? 100),
        value: String(holding.unallocatedPct ?? 100), "aria-label": t("goals.shareOf", { title: holding.title }),
        style: { width: "84px" },
      });
      const add = el("button.btn.btn-sm", { type: "button", "aria-label": t("goals.addHolding", { title: holding.title }) }, t("family.addButton"));
      add.onclick = () => withBusy(add, async () => {
        error.textContent = "";
        try {
          await api.mapToGoal(state.household.id, goal.id, {
            investmentId: holding.investmentId ?? holding.id,
            allocationPct: Number(share.value),
          });
          modal.close();
          toast(t("goals.nowFunds", { holding: holding.title, goal: goal.name }));
          await goalsScreen(host);
        } catch (apiError) {
          error.textContent = apiError.message;
        }
      });
      return el("div.row-between", {},
        el("div", {},
          el("div", {}, holding.title),
          el("span.caption.muted", {},
            `${holding.valueFormatted || t("goals.noValue")}${
              holding.unallocatedPct !== undefined && holding.unallocatedPct < 100
                ? ` · ${t("goals.unallocated", { percent: holding.unallocatedPct })}` : ""}`),
        ),
        el("div.row", { style: { gap: "8px" } }, share, el("span.caption.muted", {}, "%"), add),
      );
    }));
  })();

  const modal = sheet({
    title: t("goals.whatFunds", { name: goal.name }),
    body: el("div.stack-3", {},
      el("p.caption.muted", {}, t("goals.shareExplain")),
      list, error,
    ),
  });
}
