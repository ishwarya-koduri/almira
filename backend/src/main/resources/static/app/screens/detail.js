/* Investment detail — everything about one holding, including who can see it.

   A panel from the right on a tablet or desktop, wider on a desktop, with the
   list it came from still beside it (X-53). The value and a small line of its
   history come first; then four sections, one at a time: Details, Papers,
   Family (who owns it, who can see it, who receives it) and Reminders. */

import { api } from "../api.js";
import {
  el, mount, sheet, field, moneyInput, select, categoryIcon, withBusy,
  toast, rupees, formatDate, segmented, areaTrend, onDemand, when, avatar,
} from "../ui.js";
import { confirmItsYou } from "../step-up.js";
import { state, findType } from "../state.js";
import { reload } from "../app.js";
import { whereWhoCard } from "../where.js";
import { sealedNoteCard } from "../sealed-notes.js";
import { t } from "../i18n.js";

const SECTIONS = ["details", "papers", "family", "reminders"];

export async function openDetail(id, onChanged, { section = "details" } = {}) {
  const body = el("div.stack-3", {}, el("div.skeleton", { style: { height: "200px" } }));
  const modal = sheet({ title: t("detail.title"), body, wide: true });
  let current = SECTIONS.includes(section) ? section : "details";
  const changed = () => (onChanged ? onChanged() : reload());

  let record;
  try {
    record = await api.investment(state.household.id, id);
  } catch (error) {
    mount(body, el("div.banner", {}, error.message));
    return;
  }

  const type = findType(record.typeId);
  const schema = type?.schema || { common: {}, fields: [] };

  const draw = () => {
    const sections = {
      details: () => [detailsCard(), returnsCard(), actionsRow()],
      papers: () => [papersCard(), whereWhoCard("investment", id), sealedNoteCard("investment", id)],
      family: () => [ownershipCard(), nomineeCard()],
      reminders: () => [remindersCard()],
    };
    const host = el("div.stack-3", { role: "tabpanel", "aria-label": t(`detail.section.${current}`) },
      ...sections[current]());
    mount(body, el("div.stack-3", {},
      el("div.row", {},
        categoryIcon(record.categoryCode, record.color),
        el("div.grow", { style: { minWidth: 0 } },
          el("h3", { style: { margin: 0, overflowWrap: "anywhere" } }, record.title),
          el("div.caption.muted", {}, [record.typeLabel, record.institutionName].filter(Boolean).join(" · ")),
        ),
      ),

      el("div.card.card-tight.stack-2", {},
        el("div.row-between.wrap", { style: { alignItems: "flex-start" } },
          el("div", { style: { minWidth: 0 } },
            el("div.overline", {}, t("detail.value")),
            el("div.detail-value", {}, record.valueFormatted || t("detail.notKnown")),
            el("div.caption.muted", {}, valueExplanation(record)),
            priceFedDetail(record),
            inBaseCurrency(record),
          ),
          el("button.btn.btn-sm", { type: "button", onclick: () => updateValue() }, t("detail.updateValue")),
        ),
        historyLine(),
      ),

      el("div.detail-sections", {},
        segmented(SECTIONS.map((name) => ({ value: name, label: t(`detail.section.${name}`) })), current,
          (value) => { current = value; draw(); })),
      host,
    ));
  };

  /* --- value history (X-53) ------------------------------------------------ */

  function historyLine() {
    const host = el("div.detail-history", {});
    (async () => {
      try {
        const history = await api.valuations(state.household.id, id);
        const points = [...history]
          .sort((a, b) => String(a.asOfDate).localeCompare(String(b.asOfDate)))
          .map((v) => ({ label: formatDate(v.asOfDate), value: Number(v.value), formatted: rupees(v.value) }));
        if (points.length < 2) {
          mount(host, el("p.caption.muted", { style: { margin: 0 } }, t("detail.history.soon")));
          return;
        }
        const summary = t("detail.history.summary", {
          count: points.length, start: points[0].formatted, from: points[0].label,
          end: points[points.length - 1].formatted,
        });
        mount(host, onDemand(t("block.showChart"), () => areaTrend(points, { summary, height: 64, fromZero: false })));
      } catch { /* the history is a courtesy; the value above already says what is known */ }
    })();
    return host;
  }

  /* --- details ------------------------------------------------------------- */

  function detailsCard() {
    return el("div.card.card-tight.stack-2", {},
      el("div.overline", {}, t("detail.section.details")),
      record.investedAmount && row("Amount invested", inOwnCurrency(record.investedAmount, record.currency)),
      record.quantity && row("Quantity", `${record.quantity} ${record.unit || ""}`.trim()),
      record.startDate && row(schema.common?.start_date?.label || "Started", formatDate(record.startDate)),
      record.maturityDate && row(schema.common?.maturity_date?.label || "Matures", formatDate(record.maturityDate)),
      ...attributeRows(record, schema),
      row("Added", formatDate(record.createdAt)),
    );
  }

  function actionsRow() {
    return el("div.stack-2", {},
      el("div.row.wrap", { style: { gap: "8px" } },
        el("button.btn", { type: "button", onclick: () => duplicate() }, "Duplicate"),
        record.maturityDate && el("button.btn", { type: "button", onclick: () => renew() },
          "Renew this"),
        el("button.btn.btn-danger", { type: "button", onclick: () => archive() }, "Move to trash"),
      ),
      record.rolledFromId && el("p.caption.muted", {},
        "This renewed an earlier record, which is kept as history."),
    );
  }

  /* --- family: who owns it, who can see it -------------------------------- */

  function ownershipCard() {
    // Ownership and visibility sit together: they are the two questions this
    // product exists to answer clearly.
    return el("div.card.card-tight.stack-2", {},
      el("div.overline", {}, "Ownership & privacy"),
      el("div.stack-2", {}, ...record.owners.map((owner) => el("div.row", {},
        avatar(owner.memberId, owner.name),
        el("span.grow", {}, owner.name || t("detail.someone")),
        Number(owner.sharePct) !== 100 && el("span.caption.muted", {}, `${owner.sharePct}%`)))),
      row("Who can see it", visibilityText(record)),
      el("div.row", {},
        el("button.btn.btn-sm", { type: "button", onclick: () => changeVisibility() }, "Change who can see this"),
      ),
    );
  }

  /* --- papers -------------------------------------------------------------- */

  function papersCard() {
    const host = el("div.card.card-tight.stack-2", {},
      el("div.overline", {}, t("detail.papers.title")),
      el("div.skeleton", { style: { height: "40px" } }));
    const input = el("input", {
      type: "file", accept: "image/*,application/pdf", hidden: true, "aria-label": t("detail.papers.attach"),
    });
    const attach = el("button.btn.btn-sm", { type: "button", onclick: () => input.click() }, t("detail.papers.attach"));
    input.addEventListener("change", () => {
      const file = input.files?.[0];
      if (!file) return;
      withBusy(attach, async () => {
        try {
          await api.attachDocument(state.household.id, "investment", id, file);
          toast(t("detail.papers.added"));
          await drawPapers();
          await changed();
        } catch (error) { toast(error.message, { tone: "error" }); }
        input.value = "";
      });
    });

    const open = async (doc, button) => withBusy(button, async () => {
      try {
        // Opening a paper is a step-up action (docs/05 §4): a two-minute, single-use ticket.
        if (!(await confirmItsYou(t("detail.papers.whyConfirm")))) return;
        const ticket = await api.documentAccess(state.household.id, doc.id);
        window.open(api.documentDownloadUrl(ticket.token), "_blank", "noopener");
      } catch (error) { toast(error.message, { tone: "error" }); }
    });

    async function drawPapers() {
      let docs;
      try {
        docs = await api.documentsFor(state.household.id, "investment", id);
      } catch {
        mount(host, el("div.overline", {}, t("detail.papers.title")),
          el("p.caption.muted", {}, t("app.somethingWrong")));
        return;
      }
      mount(host,
        el("div.overline", {}, t("detail.papers.title")),
        docs.length
          ? el("div.list", {}, ...docs.map((doc) => {
              const button = el("button.btn.btn-sm", { type: "button" }, t("detail.papers.open"));
              button.onclick = () => open(doc, button);
              return el("div.list-row", { style: { cursor: "default" } },
                el("div.grow", { style: { minWidth: 0 } },
                  el("div.title", {}, doc.fileName),
                  el("div.meta", {}, [doc.docType.replace(/_/g, " "), doc.expiresOn && (doc.expired
                    ? t("detail.papers.expired", { date: formatDate(doc.expiresOn) })
                    : t("detail.papers.expires", { date: formatDate(doc.expiresOn) }))].filter(Boolean).join(" · "))),
                button);
            }))
          : el("p.caption.muted", { style: { margin: 0 } }, t("detail.papers.none")),
        el("div.row", {}, attach, input),
      );
    }
    drawPapers();
    return host;
  }

  /* --- reminders ----------------------------------------------------------- */

  function remindersCard() {
    const list = el("div.stack-2", {}, el("div.skeleton", { style: { height: "40px" } }));
    (async () => {
      try {
        const reminders = (await api.reminders(state.household.id)).filter((r) => r.investmentId === id);
        mount(list, reminders.length
          ? el("div.list", {}, ...reminders.map((r) => el("div.list-row", { style: { cursor: "default" } },
              el("div.grow", {}, el("div.title", {}, r.title), el("div.meta", {}, when(r.effectiveDate))),
              r.amountFormatted && el("div.amount", {}, r.amountFormatted))))
          : el("p.caption.muted", { style: { margin: 0 } }, t("detail.reminders.none")));
      } catch {
        mount(list, el("p.caption.muted", { style: { margin: 0 } }, t("app.somethingWrong")));
      }
    })();
    return el("div.card.card-tight.stack-2", {},
      el("div.overline", {}, t("detail.section.reminders")),
      record.maturityDate && row(schema.common?.maturity_date?.label || "Matures", when(record.maturityDate)),
      row(t("detail.lastConfirmed"), record.lastVerifiedAt ? formatDate(record.lastVerifiedAt) : t("detail.never")),
      list,
    );
  }

  /* --- returns ------------------------------------------------------------- */

  function returnsCard() {
    const host = el("div.card.card-tight.stack-2", {},
      el("div.overline", {}, "Return"),
      el("div.skeleton", { style: { height: "40px" } }),
    );

    (async () => {
      try {
        const performance = await api.investmentReturns(state.household.id, id);
        // Every figure here is allowed to be missing, and a missing one is
        // explained rather than shown as a zero — a fabricated return is worse
        // than no return (docs/01 §8).
        const figures = [
          ["Gain, realised", performance.realizedGainFormatted],
          ["Gain, on paper", performance.unrealizedGainFormatted],
          ["Absolute", performance.absoluteReturn !== null && performance.absoluteReturn !== undefined
            ? `${performance.absoluteReturn}%` : null],
          ["CAGR", performance.cagr !== null && performance.cagr !== undefined
            ? `${performance.cagr}%` : null],
          ["XIRR", performance.xirr !== null && performance.xirr !== undefined
            ? `${performance.xirr}%` : null],
        ].filter(([, value]) => value !== null && value !== undefined);

        mount(host,
          el("div.overline", {}, "Return"),
          ...(figures.length
            ? figures.map(([label, value]) => row(label, value))
            : []),
          performance.note && el("p.caption.muted", {}, performance.note),
        );
      } catch {
        mount(host, el("div.overline", {}, "Return"),
          el("p.caption.muted", {}, "We couldn't work this out just now."));
      }
    })();

    return host;
  }

  /* --- nominees ------------------------------------------------------------ */

  function nomineeCard() {
    return el("div.card.card-tight.stack-2", {},
      el("div.overline", {}, "Nominees"),
      record.nominees.length
        ? el("div.stack-2", {}, ...record.nominees.map((nominee) => row(
            nominee.name, `${nominee.relationship || "nominee"} · ${nominee.sharePct}%`)))
        : el("p.caption.muted", {},
            "Nobody recorded. A nominee is who the institution pays — not who inherits it."),
      el("div.row", {},
        el("button.btn.btn-sm", { type: "button", onclick: () => editNominees() },
          record.nominees.length ? "Change nominees" : "Add a nominee"),
      ),
    );
  }

  function editNominees() {
    const rows = [];
    const host = el("div.stack-2", {});
    const error = el("div.help.error", { style: { minHeight: "1.15rem" } });

    const addRow = (existing) => {
      const who = select({
        options: [
          { value: "", label: "Someone outside the household" },
          ...state.members.map((m) => ({ value: m.id, label: m.displayName })),
        ],
        value: existing?.memberId || "",
        "aria-label": "Nominee",
      });
      const name = el("input.input", {
        type: "text", placeholder: "Their name", value: existing?.name || "",
        "aria-label": "Nominee name",
      });
      const share = el("input.input", {
        type: "number", min: "1", max: "100", value: String(existing?.sharePct ?? 100),
        "aria-label": "Share", style: { width: "88px" },
      });
      const entry = { who, name, share };
      rows.push(entry);
      const node = el("div.card.card-tight.stack-2", {},
        el("div.row", {}, who, share, el("span.caption.muted", {}, "%")),
        name,
        el("button.btn.btn-sm.btn-danger", {
          type: "button",
          onclick: () => { node.remove(); rows.splice(rows.indexOf(entry), 1); },
        }, "Remove"),
      );
      // A member and a written name are alternatives, not both.
      who.addEventListener("change", () => { name.hidden = Boolean(who.value); });
      name.hidden = Boolean(who.value);
      host.append(node);
    };

    (record.nominees.length ? record.nominees : [null]).forEach(addRow);

    const save = el("button.btn.btn-primary.grow", { type: "button" }, "Save nominees");
    save.onclick = () => withBusy(save, async () => {
      error.textContent = "";
      try {
        await api.setNominees(state.household.id, id, {
          nominees: rows.map((entry) => ({
            memberId: entry.who.value || null,
            name: entry.who.value ? null : entry.name.value.trim(),
            sharePct: Number(entry.share.value),
          })).filter((nominee) => nominee.memberId || nominee.name),
        });
        nomineeModal.close();
        toast("Nominees saved.");
        record = await api.investment(state.household.id, id);
        draw();
        await (onChanged ? onChanged() : reload());
      } catch (apiError) {
        error.textContent = apiError.message;
      }
    });

    const nomineeModal = sheet({
      title: "Who should receive this?",
      body: el("div.stack-3", {},
        el("p.caption.muted", {},
          "A nominee receives the money from the institution. Who inherits it is " +
          "decided by a will — Almira records both so a mismatch can be spotted."),
        host,
        el("button.btn.btn-sm", { type: "button", onclick: () => addRow(null) }, "＋ Add another"),
        error,
      ),
      footer: [save],
    });
  }

  /* --- duplicate and renew -------------------------------------------------- */

  function duplicate() {
    const button = el("button.btn.btn-primary.grow", { type: "button" }, "Make a copy");
    const title = el("input.input", { type: "text", value: `${record.title} (copy)`, "aria-label": "Name" });
    button.onclick = () => withBusy(button, async () => {
      const created = await api.duplicate(state.household.id, id, { title: title.value.trim() || null });
      duplicateModal.close();
      modal.close();
      toast("Copied. The history stays with the original.");
      await (onChanged ? onChanged() : reload());
      if (created.investment) openDetail(created.id, onChanged);
    });

    const duplicateModal = sheet({
      title: "Duplicate",
      body: el("div.stack-3", {},
        el("p.caption.muted", {},
          "Same shape — type, institution, owners, nominees. The valuations and " +
          "transactions stay with the original, because they happened to it."),
        field({ label: "Name", control: title }),
      ),
      footer: [button],
    });
  }

  function renew() {
    const amount = moneyInput({ placeholder: "0" });
    if (record.value) {
      amount.input.value = String(record.value);
      amount.input.dispatchEvent(new Event("input"));
    }
    const maturity = el("input.input", { type: "date", "aria-label": "New maturity date" });
    const button = el("button.btn.btn-primary.grow", { type: "button" }, "Renew it");

    button.onclick = () => withBusy(button, async () => {
      await api.rollover(state.household.id, id, {
        investedAmount: amount.value(),
        maturityDate: maturity.value || null,
      });
      renewModal.close();
      modal.close();
      toast("Renewed. The old record is kept, marked matured.");
      await (onChanged ? onChanged() : reload());
    });

    const renewModal = sheet({
      title: `Renew ${record.title}`,
      body: el("div.stack-3", {},
        el("p.caption.muted", {},
          `The old record is kept and marked matured, and the new one starts where ` +
          `it ended${record.maturityDate ? ` — ${formatDate(record.maturityDate)}` : ""}. ` +
          `Anything it funds carries across.`),
        field({ label: "Amount", control: amount, help: "Principal plus whatever it earned." }),
        field({ label: "New maturity date", control: maturity,
          help: "We don't guess this one — the old date has already passed." }),
      ),
      footer: [button],
    });
  }

  function row(label, value) {
    if (value === null || value === undefined || value === "") return null;
    return el("div.row-between", { style: { fontSize: "var(--text-sm)" } },
      el("span.muted", {}, label), el("span", { style: { textAlign: "right" } }, value));
  }

  function attributeRows(record, schema) {
    return Object.entries(record.attributes || {}).map(([key, value]) => {
      const def = (schema.fields || []).find((f) => f.key === key);
      const label = def?.label || key.replace(/_/g, " ");
      let shown = value;
      if (def?.dataType === "money") shown = rupees(value);
      else if (def?.dataType === "bool") shown = value ? "Yes" : "No";
      else if (def?.dataType === "select") {
        shown = def.options?.find((o) => o.value === value)?.label || value;
      } else if (def?.unit) shown = `${value} ${def.unit}`;
      return row(label, String(shown));
    });
  }

  function updateValue() {
    const amount = moneyInput({ placeholder: "0" });
    const save = el("button.btn.btn-primary", { type: "button" }, "Save value");
    const valueField = field({
      label: "What is it worth today?", control: amount, required: true,
      help: "A snapshot. We keep the history so you can see the trend later.",
    });
    const inner = sheet({
      title: "Update value",
      body: el("div.stack-3", {}, valueField),
      footer: [save],
    });
    save.onclick = () => withBusy(save, async () => {
      const value = amount.value();
      if (value === null) { valueField.setError("Enter an amount"); return; }
      try {
        record = await api.addValuation(state.household.id, id, { value });
        inner.close(); draw(); toast("Value updated.");
      } catch (error) { valueField.setError(error.message); }
    });
  }

  function changeVisibility() {
    const choice = select({
      options: [
        { value: "private", label: "Private — only the owner" },
        { value: "household", label: `Shared with ${state.household.name}` },
        { value: "scoped", label: "Shared with specific people" },
      ],
      value: record.visibility,
    });
    const picker = el("div.row.wrap", { style: { gap: "8px" }, hidden: record.visibility !== "scoped" },
      ...state.members.filter((m) => !m.isMe).map((m) => el("button.chip", {
        type: "button", "data-member": m.id,
        "aria-pressed": String(record.visibleToMemberIds.includes(m.id)),
        onclick: (event) => {
          const chip = event.currentTarget;
          chip.setAttribute("aria-pressed", chip.getAttribute("aria-pressed") === "true" ? "false" : "true");
        },
      }, m.displayName)),
    );
    choice.addEventListener("change", () => { picker.hidden = choice.value !== "scoped"; });

    const save = el("button.btn.btn-primary", { type: "button" }, "Save");
    const inner = sheet({
      title: "Who can see this?",
      body: el("div.stack-3", {},
        field({ label: "Visibility", control: choice,
          help: "Private is genuinely private — no role in the household can override it." }),
        picker,
      ),
      footer: [save],
    });

    save.onclick = () => withBusy(save, async () => {
      const visibleToMemberIds = [...picker.querySelectorAll('[aria-pressed="true"]')]
        .map((chip) => chip.dataset.member);
      try {
        record = await api.setVisibility(state.household.id, id, {
          visibility: choice.value, visibleToMemberIds,
        });
        inner.close(); draw(); toast("Updated.");
        await reload();
      } catch (error) { toast(error.message, { tone: "error" }); }
    });
  }

  async function archive() {
    try {
      await api.archive(state.household.id, id);
      modal.close();
      toast("Moved to trash.", {
        action: "Undo",
        onAction: async () => {
          await api.restore(state.household.id, id);
          toast("Restored.");
          await reload();
        },
      });
      await reload();
    } catch (error) { toast(error.message, { tone: "error" }); }
  }

  draw();
}

/**
 * Something held abroad shows both amounts side by side: its own, which is the
 * true one, and the household's currency beside it with the rate and the date
 * that rate is from (docs/07 §1). With no rate the second figure is not
 * guessed — the server says what is missing, and that sentence is shown.
 */
function inBaseCurrency(record) {
  const base = state.household.baseCurrency || "INR";
  if (!record.currency || record.currency === base || record.value === null || record.value === undefined) {
    return null;
  }
  const host = el("div.stack-2", { "aria-live": "polite" });
  (async () => {
    try {
      const converted = await api.convert(state.household.id, record.value, record.currency, base);
      if (converted.convertedAmount === null || converted.convertedAmount === undefined) {
        mount(host, el("p.caption.muted", {}, converted.note || t("money.notConverted")));
        return;
      }
      mount(host,
        el("div", { style: { fontFamily: "var(--font-display)", fontSize: "var(--text-lg)" } },
          `≈ ${base === "INR" ? rupees(converted.convertedAmount) : `${base} ${converted.convertedAmount}`}`),
        el("div.caption.muted", {}, t("money.rateLine", {
          from: record.currency,
          rate: Number(converted.rate).toLocaleString("en-IN", { maximumFractionDigits: 4 }),
          to: base,
          date: formatDate(converted.rateAsOf),
          source: rateSourceLabel(converted.rateSource),
        })),
      );
    } catch {
      mount(host, el("p.caption.muted", {}, t("money.rateUnavailable")));
    }
  })();
  return host;
}

/** Rupees in Indian grouping; money held abroad with its code, as its statement writes it. */
function inOwnCurrency(amount, currency) {
  if (!currency || currency === "INR") return rupees(amount);
  return `${currency} ${Number(amount).toLocaleString("en-US", { maximumFractionDigits: 2 })}`;
}

function rateSourceLabel(source) {
  const plain = String(source || "").replace(" (inverted)", "");
  const key = `money.source.${plain}`;
  const label = t(key);
  return label === key ? plain : label;
}

/**
 * A value from a published price says so, calmly: which price, per unit, and —
 * when someone had entered a value of their own earlier — that it is kept, with
 * its date. The feed never replaces a value entered on or after the price's
 * date (docs/13 §6), so this is the only case there is to explain.
 */
function priceFedDetail(record) {
  if (record.valuationSource !== "price_feed" || !record.unitPrice) return null;
  const host = el("div.stack-2", {},
    el("p.caption.muted.price-stamp", {},
      el("span.info-mark", { "aria-hidden": "true" }, "i"),
      t("value.perUnit", {
        price: `₹${Number(record.unitPrice).toLocaleString("en-IN", { maximumFractionDigits: 4 })}`,
        units: record.quantity,
      })),
  );
  (async () => {
    try {
      const history = await api.valuations(state.household.id, record.id);
      const entered = history.find((v) => v.source !== "price_feed");
      if (entered) {
        host.append(el("p.caption.muted", {}, t("value.enteredKept", {
          value: rupees(entered.value), date: formatDate(entered.asOfDate),
        })));
      }
    } catch { /* the history is a courtesy here; the stamp above already says what the figure is */ }
  })();
  return host;
}

function valueExplanation(record) {
  if (record.valueBasis === "valued" && record.valuationSource === "price_feed") {
    const key = { amfi: "value.atNav", nse: "value.atNseClose", bse: "value.atBseClose" }[record.priceSource];
    if (key) return t(key, { date: formatDate(record.valuedOn) });
  }
  switch (record.valueBasis) {
    case "valued": return `Your snapshot from ${formatDate(record.valuedOn)}`;
    case "at_cost": return "What you paid — add a value to see what it's worth today";
    case "custom_field": return "From a field you added";
    default: return "Add a value to include this in your totals";
  }
}

function visibilityText(record) {
  if (record.visibility === "household") return `Everyone in ${state.household.name}`;
  if (record.visibility === "scoped") {
    const names = state.members
      .filter((m) => record.visibleToMemberIds.includes(m.id))
      .map((m) => m.displayName);
    return names.length ? `The owner and ${names.join(", ")}` : "The owner only";
  }
  return "Only the owner — not even a household admin";
}
