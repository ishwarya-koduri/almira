/* What's owed. The debt half of the balance sheet — same shape as the asset
   list, same privacy rules, and deliberately never alarmist about it
   (docs/02 §10: factual and calm, whatever the number says). */

import { api } from "../api.js";
import {
  el, mount, sheet, field, textInput, moneyInput, select, skeletonRows, empty,
  withBusy, toast, rupees, formatDate,
} from "../ui.js";
import { state, myMember, takePendingForm, startingVisibility } from "../state.js";
import { reload } from "../app.js";
import { whereWhoCard } from "../where.js";
import { sealedNoteCard } from "../sealed-notes.js";
import { t } from "../i18n.js";
import { liabilityImpliesReminder, offerRemindersOutsideTheApp } from "../message-consent.js";

// Labels are looked up when drawn, so a change of language reaches them.
const KIND_CODES = ["home", "car", "personal", "education", "gold", "credit_card", "lap", "las",
  "loan_against_insurance", "family", "other"];
const kinds = () => KIND_CODES.map((value) => ({ value, label: t(`liabilities.kind.${value}`) }));
const kindLabel = (code) => (KIND_CODES.includes(code) ? t(`liabilities.kind.${code}`) : code);

export async function liabilitiesScreen(host) {
  mount(host, el("div.stack", {}, skeletonRows(3)));

  const [rows, dashboard] = await Promise.all([
    api.liabilities(state.household.id),
    api.dashboard(state.household.id, "household"),
  ]);

  mount(host, el("div.stack", {},
    el("div.row-between.wrap", {},
      el("h1", {}, t("home.owedBreakdown")),
      el("button.btn.btn-primary", { type: "button", onclick: () => openForm() }, t("liabilities.add")),
    ),

    rows.length > 0 && el("div.card", {},
      el("div.row-between.wrap", { style: { alignItems: "baseline" } },
        el("div", {},
          el("div.overline", {}, t("liabilities.total")),
          el("div", {
            style: {
              fontFamily: "var(--font-display)", fontSize: "var(--text-h1)",
              color: "var(--caution)",
            },
          }, dashboard.totalLiabilitiesFormatted),
        ),
        el("div.caption.muted", { style: { textAlign: "right", maxWidth: "34ch" } },
          t("liabilities.against", { assets: dashboard.totalAssetsFormatted, net: dashboard.netWorthFormatted })),
      ),
    ),

    rows.length === 0
      ? el("div.card", {}, empty({
          title: t("liabilities.empty.title"),
          body: t("liabilities.empty.body"),
          action: el("button.btn.btn-primary", { type: "button", onclick: () => openForm() }, t("liabilities.add")),
        }))
      : el("div.card.card-tight", {},
          el("div.list", {}, ...rows.map((row) => el("button.list-row", {
            type: "button", onclick: () => openDetail(row.id),
          },
            el("span.dot", { style: { background: "var(--caution)" }, "aria-hidden": "true" }),
            el("div.grow", { style: { minWidth: 0 } },
              el("div.title", {}, row.title),
              el("div.meta", {}, [
                kindLabel(row.kind),
                row.lenderName,
                row.holders.map((h) => h.name).filter(Boolean).join(" & "),
                row.securedBy.length ? t("liabilities.securedBy", { title: row.securedBy[0].title }) : null,
              ].filter(Boolean).join(" · ")),
            ),
            el("div.amount", {},
              el("b", { style: { color: "var(--caution)" } }, row.outstandingFormatted),
              row.emiAmount && el("div.meta", {},
                row.emiDay
                  ? t("liabilities.emiOn", { amount: rupees(row.emiAmount), day: dayOfMonth(row.emiDay) })
                  : t("liabilities.emi", { amount: rupees(row.emiAmount) })),
            ),
            el("span.pill", {}, visibilityWord(row.visibility)),
          ))),
        ),
  ));

  // A starter on the first session's shelves asked for this form (X-31).
  const pending = takePendingForm("liabilities");
  if (pending) openForm(pending);

  // ---------------------------------------------------------------- form ---

  function openForm(prefill = null) {
    const holderId = prefill?.memberId || myMember()?.id;
    const title = textInput({ placeholder: t("liabilities.nameExample"), value: prefill?.title || "" });
    const kind = select({ options: kinds(), value: prefill?.liabilityKind || "home" });
    const outstanding = moneyInput({ placeholder: "0" });
    const emiAmount = moneyInput({ placeholder: "0" });
    const emiDay = textInput({ type: "number", min: 1, max: 31, placeholder: "5" });
    const rate = textInput({ inputMode: "decimal", placeholder: "8.5" });
    const principal = moneyInput({ placeholder: "0" });

    const titleField = field({ label: t("liabilities.whatCalled"), control: title, required: true });
    const outstandingField = field({
      label: t("liabilities.howMuchOwed"), control: outstanding, required: true,
      help: t("liabilities.howMuchOwedHelp"),
    });

    const securedBy = select({
      options: [{ value: "", label: t("liabilities.notSecured") }],
    });
    api.investments(state.household.id).then((holdings) => {
      holdings.forEach((h) => securedBy.append(el("option", { value: h.id }, h.title)));
    }).catch(() => { /* optional */ });

    const visibility = select({
      options: [
        { value: "private", label: t("liabilities.visibility.private") },
        { value: "household", label: t("common.sharedWith", { name: state.household.name }) },
      ],
      value: startingVisibility(holderId),
    });

    const save = el("button.btn.btn-primary.grow", { type: "button" }, t("app.save"));
    const modal = sheet({
      title: t("liabilities.addTitle"),
      body: el("div.stack-3", {},
        titleField,
        field({ label: t("common.whatKind"), control: kind }),
        outstandingField,
        field({ label: t("liabilities.emiAmount"), control: emiAmount, help: t("liabilities.emiAmountHelp") }),
        field({ label: t("liabilities.emiDay"), control: emiDay, help: t("liabilities.emiDayHelp") }),
        el("details.more", {},
          el("summary", {}, t("common.moreDetails")),
          el("div.stack-3", { style: { paddingTop: "16px" } },
            field({ label: t("liabilities.principal"), control: principal }),
            field({ label: t("liabilities.rate"), control: rate, help: t("liabilities.rateHelp") }),
            field({ label: t("liabilities.securedAgainst"), control: securedBy, help: t("liabilities.securedHelp") }),
          ),
        ),
        field({ label: t("common.whoCanSee"), control: visibility, help: t("liabilities.visibility.help") }),
      ),
      footer: [save],
    });

    save.onclick = () => withBusy(save, async () => {
      titleField.setError(""); outstandingField.setError("");
      if (!title.value.trim()) { titleField.setError(t("common.giveItAName")); return; }
      if (outstanding.value() === null) { outstandingField.setError(t("liabilities.howMuchOwed")); return; }

      const body = {
        title: title.value.trim(),
        kind: kind.value,
        outstanding: outstanding.value(),
        visibility: visibility.value,
        holders: [{ memberId: holderId, responsibilityPct: 100 }],
      };
      if (emiAmount.value() !== null) body.emiAmount = emiAmount.value();
      if (emiDay.value) body.emiDay = Number(emiDay.value);
      if (principal.value() !== null) body.principal = principal.value();
      if (rate.value.trim()) body.interestRate = Number(rate.value);
      if (securedBy.value) body.securedByInvestmentId = securedBy.value;

      try {
        const created = await api.createLiability(state.household.id, body);
        modal.close();
        toast(t("common.saved"));
        await reload();
        // An EMI day makes a monthly reminder: ask whether it may come outside the app
        // too (V125), about this loan (V141).
        if (liabilityImpliesReminder(body) && created?.id) {
          offerRemindersOutsideTheApp({ contextType: "liability", contextId: created.id });
        }
      } catch (error) {
        titleField.setError(error.message);
      }
    });
    title.focus();
  }

  // -------------------------------------------------------------- detail ---

  async function openDetail(id) {
    const body = el("div.stack-3", {}, skeletonRows(2));
    const modal = sheet({ title: t("liabilities.detailTitle"), body });
    const row = await api.liability(state.household.id, id);

    const draw = () => mount(body, el("div.stack-3", {},
      el("div", {},
        el("h3", {}, row.title),
        el("div.caption.muted", {},
          [kindLabel(row.kind), row.lenderName].filter(Boolean).join(" · ")),
      ),

      el("div.card.card-tight", {},
        el("div.row-between", {},
          el("div", {},
            el("div.overline", {}, t("liabilities.stillOwed")),
            el("div", {
              style: {
                fontFamily: "var(--font-display)", fontSize: "var(--text-h2)",
                color: "var(--caution)",
              },
            }, row.outstandingFormatted),
            row.balanceAsOf && el("div.caption.muted", {}, t("liabilities.asOf", { date: formatDate(row.balanceAsOf) })),
          ),
          el("button.btn.btn-sm", { type: "button", onclick: () => recordPayment() },
            t("liabilities.updateBalance")),
        ),
      ),

      el("div.card.card-tight.stack-2", {},
        el("div.overline", {}, t("liabilities.whoOwes")),
        ...row.holders.map((h) => detailRow(
          h.name,
          Number(h.responsibilityPct) === 100 ? t("liabilities.allOfIt") : `${h.responsibilityPct}%`,
        )),
        detailRow(t("common.whoCanSeeIt"), visibilityText(row)),
      ),

      row.securedBy.length > 0 && el("div.card.card-tight.stack-2", {},
        el("div.overline", {}, t("liabilities.securedAgainst")),
        ...row.securedBy.map((a) => detailRow(a.title, t("liabilities.encumbered"))),
      ),

      el("div.card.card-tight.stack-2", {},
        el("div.overline", {}, t("detail.section.details")),
        row.principal && detailRow(t("liabilities.originally"), rupees(row.principal)),
        row.interestRate && detailRow(t("liabilities.rate"), t("liabilities.ratePerYear", { rate: row.interestRate })),
        row.emiAmount && detailRow(t("liabilities.emiShort"), rupees(row.emiAmount)),
        row.emiDay && detailRow(t("liabilities.dueOn"), t("liabilities.eachMonth", { day: dayOfMonth(row.emiDay) })),
        row.startDate && detailRow(t("detail.started"), formatDate(row.startDate)),
        row.endDate && detailRow(t("liabilities.ends"), formatDate(row.endDate)),
      ),

      whereWhoCard("liability", id),
      sealedNoteCard("liability", id),

      el("div.row", {},
        el("button.btn.btn-danger", {
          type: "button",
          onclick: async () => {
            await api.deleteLiability(state.household.id, id);
            modal.close();
            toast(t("liabilities.removed"));
            await reload();
          },
        }, t("app.remove")),
      ),
    ));

    function recordPayment() {
      const amount = moneyInput({ placeholder: "0" });
      const amountField = field({
        label: t("liabilities.owedNow"), control: amount, required: true,
        help: t("liabilities.owedNowHelp"),
      });
      const save = el("button.btn.btn-primary", { type: "button" }, t("app.save"));
      const inner = sheet({
        title: t("liabilities.updateBalance"), body: el("div.stack-3", {}, amountField), footer: [save],
      });
      save.onclick = () => withBusy(save, async () => {
        if (amount.value() === null) { amountField.setError(t("common.enterAmount")); return; }
        try {
          Object.assign(row, await api.recordBalance(state.household.id, id, {
            outstanding: amount.value(),
          }));
          inner.close(); draw(); toast(t("common.updated"));
          await reload();
        } catch (error) { amountField.setError(error.message); }
      });
    }

    draw();
  }
}

function detailRow(label, value) {
  if (!value) return null;
  return el("div.row-between", { style: { fontSize: "var(--text-sm)" } },
    el("span.muted", {}, label), el("span", { style: { textAlign: "right" } }, value));
}

/** Who can see it, in the same words as a holding's row (X-54): never "Scoped". */
function visibilityWord(visibility) {
  return t(visibility === "household" ? "privacy.pill.household"
    : visibility === "scoped" ? "privacy.pill.scoped" : "privacy.pill.private");
}

function visibilityText(row) {
  if (row.visibility === "household") return t("common.everyoneIn", { name: state.household.name });
  if (row.visibility === "scoped") return t("common.specificPeople");
  return t("liabilities.visibility.owerOnly");
}

/** "the 5th" in English; each language says a day of the month its own way. */
function dayOfMonth(n) {
  return t("liabilities.day", { n, ordinal: ordinal(n) });
}

function ordinal(n) {
  const suffix = ["th", "st", "nd", "rd"][(n % 100 - 20) % 10] || ["th", "st", "nd", "rd"][n % 100] || "th";
  return `${n}${suffix}`;
}
