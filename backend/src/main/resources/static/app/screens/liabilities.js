/* What's owed. The debt half of the balance sheet — same shape as the asset
   list, same privacy rules, and deliberately never alarmist about it
   (docs/02 §10: factual and calm, whatever the number says). */

import { api } from "../api.js";
import {
  el, mount, sheet, field, textInput, moneyInput, select, skeletonRows, empty,
  withBusy, toast, rupees, formatDate,
} from "../ui.js";
import { state, myMember } from "../state.js";
import { reload } from "../app.js";

const KINDS = [
  { value: "home", label: "Home loan" },
  { value: "car", label: "Car loan" },
  { value: "personal", label: "Personal loan" },
  { value: "education", label: "Education loan" },
  { value: "gold", label: "Gold loan" },
  { value: "credit_card", label: "Credit card" },
  { value: "lap", label: "Loan against property" },
  { value: "las", label: "Loan against securities" },
  { value: "loan_against_insurance", label: "Loan against insurance" },
  { value: "family", label: "Money borrowed from family" },
  { value: "other", label: "Something else" },
];
const KIND_LABEL = Object.fromEntries(KINDS.map((k) => [k.value, k.label]));

export async function liabilitiesScreen(host) {
  mount(host, el("div.stack", {}, skeletonRows(3)));

  const [rows, dashboard] = await Promise.all([
    api.liabilities(state.household.id),
    api.dashboard(state.household.id, "household"),
  ]);

  mount(host, el("div.stack", {},
    el("div.row-between.wrap", {},
      el("h1", {}, "What's owed"),
      el("button.btn.btn-primary", { type: "button", onclick: () => openForm() }, "＋ Add a loan"),
    ),

    rows.length > 0 && el("div.card", {},
      el("div.row-between.wrap", { style: { alignItems: "baseline" } },
        el("div", {},
          el("div.overline", {}, "Total owed"),
          el("div", {
            style: {
              fontFamily: "var(--font-display)", fontSize: "var(--text-h1)",
              color: "var(--caution)",
            },
          }, dashboard.totalLiabilitiesFormatted),
        ),
        el("div.caption.muted", { style: { textAlign: "right", maxWidth: "34ch" } },
          `Against ${dashboard.totalAssetsFormatted} of assets. ` +
          `That leaves ${dashboard.netWorthFormatted}.`),
      ),
    ),

    rows.length === 0
      ? el("div.card", {}, empty({
          title: "Nothing owed",
          body: "If you have a home loan, a car loan or a card balance, adding it here " +
                "makes your net worth the real one.",
          action: el("button.btn.btn-primary", { onclick: () => openForm() }, "＋ Add a loan"),
        }))
      : el("div.card.card-tight", {},
          el("div.list", {}, ...rows.map((row) => el("button.list-row", {
            type: "button", onclick: () => openDetail(row.id),
          },
            el("span.dot", { style: { background: "var(--caution)" }, "aria-hidden": "true" }),
            el("div.grow", { style: { minWidth: 0 } },
              el("div.title", {}, row.title),
              el("div.meta", {}, [
                KIND_LABEL[row.kind] || row.kind,
                row.lenderName,
                row.holders.map((h) => h.name).filter(Boolean).join(" & "),
                row.securedBy.length ? `secured by ${row.securedBy[0].title}` : null,
              ].filter(Boolean).join(" · ")),
            ),
            el("div.amount", {},
              el("b", { style: { color: "var(--caution)" } }, row.outstandingFormatted),
              row.emiAmount && el("div.meta", {},
                `EMI ${rupees(row.emiAmount)}${row.emiDay ? ` on the ${ordinal(row.emiDay)}` : ""}`),
            ),
            el("span.pill", {}, visibilityWord(row.visibility)),
          ))),
        ),
  ));

  // ---------------------------------------------------------------- form ---

  function openForm() {
    const title = textInput({ placeholder: "HDFC home loan", "aria-label": "Name" });
    const kind = select({ options: KINDS, value: "home", "aria-label": "Kind" });
    const outstanding = moneyInput({ placeholder: "0" });
    const emiAmount = moneyInput({ placeholder: "0" });
    const emiDay = textInput({ type: "number", min: 1, max: 31, placeholder: "5" });
    const rate = textInput({ inputMode: "decimal", placeholder: "8.5" });
    const principal = moneyInput({ placeholder: "0" });

    const titleField = field({ label: "What's it called?", control: title, required: true });
    const outstandingField = field({
      label: "How much is still owed?", control: outstanding, required: true,
      help: "Today's balance, not the original amount.",
    });

    const securedBy = select({
      options: [{ value: "", label: "Not secured against anything" }],
      "aria-label": "Secured against",
    });
    api.investments(state.household.id).then((holdings) => {
      holdings.forEach((h) => securedBy.append(el("option", { value: h.id }, h.title)));
    }).catch(() => { /* optional */ });

    const visibility = select({
      options: [
        { value: "private", label: "Private — only whoever owes it" },
        { value: "household", label: `Shared with ${state.household.name}` },
      ],
      value: state.user?.defaultVisibility || state.household.defaultVisibility,
      "aria-label": "Who can see this",
    });

    const save = el("button.btn.btn-primary.grow", { type: "button" }, "Save");
    const modal = sheet({
      title: "Add a loan",
      body: el("div.stack-3", {},
        titleField,
        field({ label: "What kind?", control: kind }),
        outstandingField,
        field({ label: "EMI amount", control: emiAmount, help: "Optional — we'll show it in what's coming up." }),
        field({ label: "EMI day of the month", control: emiDay,
          help: "A loan due on the 31st still falls due in February — we handle that." }),
        el("details.more", {},
          el("summary", {}, "More details"),
          el("div.stack-3", { style: { paddingTop: "16px" } },
            field({ label: "Original amount borrowed", control: principal }),
            field({ label: "Interest rate", control: rate, help: "% per year" }),
            field({ label: "Secured against", control: securedBy,
              help: "The asset the lender can claim. Shows as “encumbered” on that holding." }),
          ),
        ),
        field({ label: "Who can see this?", control: visibility,
          help: "Private means only whoever owes it — not even a household admin." }),
      ),
      footer: [save],
    });

    save.onclick = () => withBusy(save, async () => {
      titleField.setError(""); outstandingField.setError("");
      if (!title.value.trim()) { titleField.setError("Give it a name"); return; }
      if (outstanding.value() === null) { outstandingField.setError("How much is still owed?"); return; }

      const body = {
        title: title.value.trim(),
        kind: kind.value,
        outstanding: outstanding.value(),
        visibility: visibility.value,
        holders: [{ memberId: myMember()?.id, responsibilityPct: 100 }],
      };
      if (emiAmount.value() !== null) body.emiAmount = emiAmount.value();
      if (emiDay.value) body.emiDay = Number(emiDay.value);
      if (principal.value() !== null) body.principal = principal.value();
      if (rate.value.trim()) body.interestRate = Number(rate.value);
      if (securedBy.value) body.securedByInvestmentId = securedBy.value;

      try {
        await api.createLiability(state.household.id, body);
        modal.close();
        toast("Saved.");
        await reload();
      } catch (error) {
        titleField.setError(error.message);
      }
    });
    title.focus();
  }

  // -------------------------------------------------------------- detail ---

  async function openDetail(id) {
    const body = el("div.stack-3", {}, skeletonRows(2));
    const modal = sheet({ title: "Loan", body });
    const row = await api.liability(state.household.id, id);

    const draw = () => mount(body, el("div.stack-3", {},
      el("div", {},
        el("h3", {}, row.title),
        el("div.caption.muted", {},
          [KIND_LABEL[row.kind] || row.kind, row.lenderName].filter(Boolean).join(" · ")),
      ),

      el("div.card.card-tight", {},
        el("div.row-between", {},
          el("div", {},
            el("div.overline", {}, "Still owed"),
            el("div", {
              style: {
                fontFamily: "var(--font-display)", fontSize: "var(--text-h2)",
                color: "var(--caution)",
              },
            }, row.outstandingFormatted),
            row.balanceAsOf && el("div.caption.muted", {}, `As of ${formatDate(row.balanceAsOf)}`),
          ),
          el("button.btn.btn-sm", { type: "button", onclick: () => recordPayment() },
            "Update balance"),
        ),
      ),

      el("div.card.card-tight.stack-2", {},
        el("div.overline", {}, "Who owes it"),
        ...row.holders.map((h) => detailRow(
          h.name,
          Number(h.responsibilityPct) === 100 ? "All of it" : `${h.responsibilityPct}%`,
        )),
        detailRow("Who can see it", visibilityText(row)),
      ),

      row.securedBy.length > 0 && el("div.card.card-tight.stack-2", {},
        el("div.overline", {}, "Secured against"),
        ...row.securedBy.map((a) => detailRow(a.title, "encumbered")),
      ),

      el("div.card.card-tight.stack-2", {},
        el("div.overline", {}, "Details"),
        row.principal && detailRow("Originally borrowed", rupees(row.principal)),
        row.interestRate && detailRow("Interest rate", `${row.interestRate}% p.a.`),
        row.emiAmount && detailRow("EMI", rupees(row.emiAmount)),
        row.emiDay && detailRow("Due on", `the ${ordinal(row.emiDay)} of each month`),
        row.startDate && detailRow("Started", formatDate(row.startDate)),
        row.endDate && detailRow("Ends", formatDate(row.endDate)),
      ),

      el("div.row", {},
        el("button.btn.btn-danger", {
          type: "button",
          onclick: async () => {
            await api.deleteLiability(state.household.id, id);
            modal.close();
            toast("Removed.");
            await reload();
          },
        }, "Remove"),
      ),
    ));

    function recordPayment() {
      const amount = moneyInput({ placeholder: "0" });
      const amountField = field({
        label: "What's owed now?", control: amount, required: true,
        help: "We keep each figure you record, so the trend is real.",
      });
      const save = el("button.btn.btn-primary", { type: "button" }, "Save");
      const inner = sheet({
        title: "Update balance", body: el("div.stack-3", {}, amountField), footer: [save],
      });
      save.onclick = () => withBusy(save, async () => {
        if (amount.value() === null) { amountField.setError("Enter an amount"); return; }
        try {
          Object.assign(row, await api.recordBalance(state.household.id, id, {
            outstanding: amount.value(),
          }));
          inner.close(); draw(); toast("Updated.");
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

function visibilityWord(visibility) {
  return visibility === "household" ? "Shared" : visibility === "scoped" ? "Scoped" : "Private";
}

function visibilityText(row) {
  if (row.visibility === "household") return `Everyone in ${state.household.name}`;
  if (row.visibility === "scoped") return "Specific people";
  return "Only whoever owes it";
}

function ordinal(n) {
  const suffix = ["th", "st", "nd", "rd"][(n % 100 - 20) % 10] || ["th", "st", "nd", "rd"][n % 100] || "th";
  return `${n}${suffix}`;
}
