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
import { el, mount, sheet, field, textInput, moneyInput, select, skeletonRows, empty, withBusy, toast, formatDate } from "../ui.js";
import { state } from "../state.js";

export async function goalsScreen(host) {
  mount(host, skeletonRows(3));
  const goals = await api.goals(state.household.id);

  if (goals.length === 0) {
    mount(host, empty({
      title: "No goals yet",
      body: "A goal is a name, an amount and a date — “Aarav's degree, 2039”. " +
        "Holdings you already have can fund it.",
      action: el("button.btn.btn-primary", { type: "button", onclick: () => newGoal(host) }, "Set a goal"),
    }));
    return;
  }

  mount(host, el("div.stack", {},
    el("div.row-between.wrap", {},
      el("h2", {}, "Goals"),
      el("button.btn.btn-primary.btn-sm", { type: "button", onclick: () => newGoal(host) }, "＋ New goal"),
    ),
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
          goal.memberName && el("span.caption.muted", {}, `for ${goal.memberName}`),
        ),
        el("div.muted", {},
          `${goal.fundedFormatted} of ${goal.targetAmountFormatted}`,
          goal.targetDate ? ` · by ${formatDate(goal.targetDate)}` : "",
        ),
      ),
      ring(percent),
    ),

    goal.progress.note && el("p.caption.muted", {}, goal.progress.note),

    Number(goal.funded) > 0
      && goal.progress.onTrack !== null && goal.progress.onTrack !== undefined
      && el("span.chip.chip-static", { style: { alignSelf: "flex-start" } },
          goal.progress.onTrack ? "On track" : "Behind where it would need to be"),

    goal.fundedBy.length > 0
      ? el("div.stack-2", {},
          el("span.overline", {}, "Funded by"),
          ...goal.fundedBy.map((source) => el("div.row-between", {},
            el("span", {}, source.title),
            el("span.muted", {},
              `${source.allocationPct}% · ${source.contributionFormatted || ""}`),
          )),
        )
      : el("p.caption.muted", {}, "Nothing is pointed at this goal yet."),

    el("div.row.wrap", { style: { gap: "8px" } },
      el("button.btn.btn-sm", { type: "button", onclick: () => attachHolding(goal, host) },
        "Point a holding at this"),
    ),
  );
}

/** A ring, drawn in one element rather than an SVG — it is a progress bar bent round. */
function ring(percent) {
  const clamped = Math.max(0, Math.min(100, percent));
  return el("div.ring", {
    role: "img",
    "aria-label": `${clamped}% funded`,
    style: {
      background: `conic-gradient(var(--gold) ${clamped * 3.6}deg, var(--hairline) 0deg)`,
    },
  }, el("span.ring-label", {}, `${Math.round(clamped)}%`));
}

function newGoal(host) {
  const name = textInput({ placeholder: "Aarav's degree", "aria-label": "Goal name" });
  const target = moneyInput({ placeholder: "0" });
  const date = textInput({ type: "date", "aria-label": "Target date" });
  const member = select({
    options: [
      { value: "", label: "The household's" },
      ...state.members.map((m) => ({ value: m.id, label: m.displayName })),
    ],
    "aria-label": "Whose goal",
  });
  const visibility = select({
    options: [
      { value: "private", label: "Private — only me" },
      { value: "household", label: `Shared with ${state.household.name}` },
    ],
    value: state.user?.defaultVisibility || "private",
    "aria-label": "Who can see this",
  });
  const error = el("div.help.error", { style: { minHeight: "1.15rem" } });
  const save = el("button.btn.btn-primary.grow", { type: "button" }, "Set the goal");

  save.onclick = () => withBusy(save, async () => {
    error.textContent = "";
    if (!name.value.trim()) { error.textContent = "Give it a name."; return; }
    if (!target.value()) { error.textContent = "How much are you aiming for?"; return; }
    try {
      await api.createGoal(state.household.id, {
        name: name.value.trim(),
        targetAmount: target.value(),
        targetDate: date.value || null,
        memberId: member.value || null,
        visibility: visibility.value,
      });
      modal.close();
      toast("Goal set.");
      await goalsScreen(host);
    } catch (apiError) {
      error.textContent = apiError.message;
    }
  });

  const modal = sheet({
    title: "New goal",
    body: el("div.stack-3", {},
      field({ label: "What is it for?", control: name, required: true }),
      field({ label: "How much?", control: target, required: true }),
      field({ label: "By when?", control: date, help: "Optional — a goal without a date still counts." }),
      field({ label: "Whose goal is it?", control: member }),
      field({ label: "Who can see it?", control: visibility,
        help: "A goal says what someone is saving for and how far short they are." }),
      error,
    ),
    footer: [save],
  });
}

function attachHolding(goal, host) {
  const list = el("div.stack-2", {}, skeletonRows(2));
  const error = el("div.help.error", { style: { minHeight: "1.15rem" } });

  (async () => {
    const holdings = await api.unallocated(state.household.id);
    if (holdings.length === 0) {
      mount(list, el("p.caption.muted", {},
        "Everything you can see is already fully allocated to goals."));
      return;
    }
    mount(list, ...holdings.map((holding) => {
      const share = textInput({
        type: "number", min: "1", max: String(holding.unallocatedPct ?? 100),
        value: String(holding.unallocatedPct ?? 100), "aria-label": "Percent",
        style: { width: "84px" },
      });
      const add = el("button.btn.btn-sm", { type: "button" }, "Add");
      add.onclick = () => withBusy(add, async () => {
        error.textContent = "";
        try {
          await api.mapToGoal(state.household.id, goal.id, {
            investmentId: holding.investmentId ?? holding.id,
            allocationPct: Number(share.value),
          });
          modal.close();
          toast(`${holding.title} now funds ${goal.name}.`);
          await goalsScreen(host);
        } catch (apiError) {
          error.textContent = apiError.message;
        }
      });
      return el("div.row-between", {},
        el("div", {},
          el("div", {}, holding.title),
          el("span.caption.muted", {},
            `${holding.valueFormatted || "no value yet"}${
              holding.unallocatedPct !== undefined && holding.unallocatedPct < 100
                ? ` · ${holding.unallocatedPct}% unallocated` : ""}`),
        ),
        el("div.row", { style: { gap: "8px" } }, share, el("span.caption.muted", {}, "%"), add),
      );
    }));
  })();

  const modal = sheet({
    title: `What funds ${goal.name}?`,
    body: el("div.stack-3", {},
      el("p.caption.muted", {},
        "One holding can fund two goals — say what share of it belongs to this one."),
      list, error,
    ),
  });
}
