/* =============================================================================
   Your plan (docs/27 §1–§2).

   Before there is a price, this says so in those words, says the one price
   will be for the whole family, and lists what Almira will never do. When a
   plan has ended and its grace period is over, the household is read-only —
   never locked — and the shell says that once, quietly, at the top of every
   screen: everything can still be seen, the handbook opened, everything
   downloaded and an account closed. Nothing here can take a payment.
   ============================================================================= */

import { api } from "./api.js";
import { el, notice, money, formatDate } from "./ui.js";
import { state } from "./state.js";
import { t } from "./i18n.js";

/** The plan of the current household, or null when it could not be read. Never blocks the shell. */
export async function loadPlan(householdId) {
  try {
    return await api.get(`/api/v1/households/${householdId}/plan`);
  } catch {
    return null;
  }
}

/** The one line the shell shows while a household is read-only, or nothing. */
export function readOnlyNotice(plan) {
  if (!plan?.readOnly) return null;
  return notice(el("span", {},
    t("plan.readOnlyNotice"), " ",
    el("a", { href: "#/settings" }, t("plan.seeWhatWorks"))),
  { role: "note" });
}

const NEVER = ["plan.never.sell", "plan.never.advice", "plan.never.money"];
const ALWAYS = ["plan.always.see", "plan.always.handbook", "plan.always.download", "plan.always.close", "plan.always.emergency"];

function priceLine(price) {
  if (!price?.decided) return el("p.caption", { style: { margin: 0 } }, t("plan.noPrice"));
  return el("p", { style: { margin: 0 } },
    money(null, price.amountInr), " ", t(price.period === "month" ? "plan.perMonth" : "plan.perYear"));
}

function stateLine(plan) {
  if (plan.state === "grace") {
    return notice(t("plan.grace", { date: formatDate(plan.readOnlyFrom) }));
  }
  if (plan.state === "read_only") {
    return notice(t("plan.readOnly"));
  }
  if (plan.paidThrough) {
    return el("p.caption", { style: { margin: 0 } }, t("plan.paidThrough", { date: formatDate(plan.paidThrough) }));
  }
  return null;
}

export async function planCard() {
  const plan = state.household ? await api.get(`/api/v1/households/${state.household.id}/plan`) : null;
  const catalogue = await api.get("/api/v1/plans");
  if (plan) state.plan = plan;
  const name = plan?.planName || catalogue.plans[0]?.name || t("plan.title");
  const price = plan?.price || catalogue.plans[0]?.price;

  return el("div.card.stack-3", {},
    el("h4", {}, t("plan.title")),
    el("p.plan-name", {}, name),
    priceLine(price),
    el("p.caption", { style: { margin: 0 } }, t("plan.onePrice")),
    plan && stateLine(plan),
    el("hr.plan-rule", { "aria-hidden": "true" }),
    // The server sends these lists too, in English, for any client; here they
    // go through t() like every other sentence. Same meaning, line for line.
    el("div.stack-2", {},
      el("span.overline", {}, t("plan.neverTitle")),
      el("ul.promise", {}, ...NEVER.map((key) => el("li", {}, t(key)))),
    ),
    el("div.stack-2", {},
      el("span.overline", {}, t("plan.alwaysTitle")),
      el("ul.promise", {}, ...ALWAYS.map((key) => el("li", {}, t(key)))),
    ),
    el("p", { style: { margin: 0, fontFamily: "var(--font-display)" } }, t("plan.promise")),
  );
}
