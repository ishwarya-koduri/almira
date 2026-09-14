/* =============================================================================
   Tax (docs/01 §9, docs/03 §11, docs/tax/capital-gains.md).

   Informational, never advice. Every figure here is a summary of what the
   household has already recorded, arranged the way a return asks for it — a
   starting point for a person or their CA, not a filing. The disclaimer travels
   with the numbers rather than sitting in a footer nobody reads.

   Nothing is projected and nothing is optimised: no "invest ₹40,000 more to
   save tax". Where a meter is derived from a declared figure rather than from
   recorded transactions, it says which.

   One primary action on this screen: "Share with my CA". The PDF and the
   Schedule 112A file are there too, as ordinary buttons, because a person
   filing their own return needs them as much as a CA does.

   The server's sentences (the basis of each line, the notes, the warnings)
   arrive in English, as docs/14 says; the words this screen adds go through t().
   ============================================================================= */

import { api, downloadAuthenticated } from "../api.js";
import {
  el, mount, select, skeletonRows, segmented, sheet, field, textInput, toast, withBusy, groupIndian,
} from "../ui.js";
import { state } from "../state.js";
import { t } from "../i18n.js";

/** 1 April to 31 March. In September 2026 the current year is 2026-27. */
function financialYears(count = 4) {
  const today = new Date();
  const startYear = today.getMonth() >= 3 ? today.getFullYear() : today.getFullYear() - 1;
  return Array.from({ length: count }, (_, index) => {
    const year = startYear - index;
    return { value: `${year}-${String((year + 1) % 100).padStart(2, "0")}`, label: `FY ${year}-${String((year + 1) % 100).padStart(2, "0")}` };
  });
}

/** A notice is muted text beside an info mark, not a coloured box (docs/02). */
function notice(text) {
  return el("p.caption.muted", { role: "note", style: { display: "flex", gap: "8px", margin: "0" } },
    el("span", { "aria-hidden": "true" }, "ⓘ"),
    el("span", {}, text),
  );
}

export async function taxScreen(host, { fy = null, member = null } = {}) {
  mount(host, skeletonRows(4));
  const years = financialYears();
  const year = fy || years[0].value;
  const pack = await api.taxPack(state.household.id, year, member);
  const schedule = pack.capitalGainsSchedule;
  const refresh = () => taxScreen(host, { fy: year, member });

  const memberOptions = [
    { value: "", label: t("tax.everyone") },
    ...state.members.map((m) => ({ value: m.id, label: m.displayName })),
  ];
  const memberSelect = select({ options: memberOptions, value: member || "", "aria-label": t("tax.whose") });
  memberSelect.style.maxWidth = "100%";
  memberSelect.addEventListener("change", () =>
    taxScreen(host, { fy: year, member: memberSelect.value || null }));

  mount(host, el("div.stack", {},
    el("div.row-between.wrap", {},
      el("h2", {}, t("nav.tax")),
      // Four years do not fit a phone; the years scroll inside their own strip
      // so the page never scrolls sideways.
      el("div.row.wrap", { style: { gap: "8px", maxWidth: "100%", minWidth: "0" } },
        el("div", { style: { overflowX: "auto", maxWidth: "100%" } },
          segmented(years, year, (value) => taxScreen(host, { fy: value, member }))),
        memberSelect,
      ),
    ),

    notice(pack.disclaimer),
    actions(year, member),

    el("div.stack-2", {},
      el("span.overline", {}, t("tax.deductions")),
      ...pack.deductions.map(meter),
    ),

    gainsCard(pack.capitalGains, schedule),
    schedule && schedule.grandfathering.length > 0 && grandfatheringCard(schedule, refresh),
    interestCard(pack.interestIncome),
  ));
}

// --- the files, and the one primary action ------------------------------------

function actions(year, member) {
  const query = new URLSearchParams({ fy: year, ...(member ? { member } : {}) });

  const pdf = el("button.btn", { type: "button" }, t("tax.pdf"));
  pdf.onclick = () => withBusy(pdf, async () => {
    try {
      await downloadAuthenticated(api.taxPackPdfUrl(state.household.id, query), `almira-tax-pack-${year}.pdf`);
    } catch (error) { toast(error.message, { tone: "error" }); }
  });

  const csv = el("button.btn", { type: "button" }, t("tax.schedule112a"));
  csv.onclick = () => withBusy(csv, async () => {
    try {
      await downloadAuthenticated(api.schedule112aUrl(state.household.id, query), `almira-schedule-112a-${year}.csv`);
    } catch (error) { toast(error.message, { tone: "error" }); }
  });

  const share = el("button.btn.btn-primary", { type: "button" }, t("tax.shareWithCa"));
  share.onclick = () => shareWithCa(year, member);

  return el("div.row.wrap", { style: { gap: "8px" } }, share, pdf, csv);
}

/**
 * "Share with my CA": the existing scoped guest link (docs/05 §7), for one
 * person's pack, for a week unless chosen otherwise. Both links are shown once —
 * the server keeps only a hash of the token.
 */
function shareWithCa(year, member) {
  const people = state.members.map((m) => ({ value: m.id, label: m.displayName }));
  const whose = select({
    options: people,
    value: member || state.household.myMemberId || people[0]?.value,
    "aria-label": t("tax.whose"),
  });
  const label = textInput({ value: t("tax.share.defaultLabel", { year }), "aria-label": t("tax.share.label") });
  const days = select({
    options: [1, 7, 30].map((n) => ({ value: String(n), label: t(n === 1 ? "tax.share.day" : "tax.share.days", { n }) })),
    value: "7",
    "aria-label": t("tax.share.howLong"),
  });
  const error = el("div.help.error", { style: { minHeight: "1.15rem" } });
  const result = el("div.stack-2", {});
  const create = el("button.btn.btn-primary.grow", { type: "button" }, t("tax.share.create"));

  const copyRow = (text, url) => el("div.stack-2", {},
    el("span.caption.muted", {}, text),
    el("code", { style: { wordBreak: "break-all", fontSize: "var(--text-caption)" } }, url),
    el("button.btn", {
      type: "button",
      onclick: async () => {
        try { await navigator.clipboard.writeText(url); toast(t("sharing.copied")); }
        catch { toast(t("tax.share.selectToCopy")); }
      },
    }, t("tax.share.copy")),
  );

  create.onclick = () => withBusy(create, async () => {
    error.textContent = "";
    try {
      const link = await api.createShare(state.household.id, {
        label: label.value.trim() || t("tax.share.defaultLabel", { year }),
        scope: "tax_pack",
        financialYear: year,
        memberId: whose.value || null,
        expiresInDays: Number(days.value),
      });
      mount(result,
        link.scopeNote && notice(link.scopeNote),
        notice(t("tax.share.once")),
        // The PDF link is the one to send: it opens in any browser. The page
        // link has no web page of its own yet (known issues, "A guest link has
        // no page to open").
        link.downloadUrl && copyRow(t("tax.share.pdfLink"), link.downloadUrl),
      );
      create.disabled = true;
    } catch (apiError) {
      error.textContent = apiError.message;
    }
  });

  sheet({
    title: t("tax.shareWithCa"),
    body: el("div.stack-3", {},
      notice(t("tax.share.explain")),
      field({ label: t("tax.whose"), control: whose }),
      field({ label: t("tax.share.label"), control: label }),
      field({ label: t("tax.share.howLong"), control: days }),
      error,
      result,
    ),
    footer: [create],
  });
}

// --- deductions -----------------------------------------------------------------

function meter(deduction) {
  const percent = Math.max(0, Math.min(100, Number(deduction.percentUsed || 0)));
  return el("div.card.stack-2", {},
    el("div.row-between.wrap", {},
      el("div", {},
        el("b", {}, deduction.label),
        el("div.caption.muted", {}, deduction.description),
      ),
      el("div.num", {}, `${deduction.usedFormatted} of ${deduction.limitFormatted}`),
    ),
    el("div.meter", { role: "img", "aria-label": `${percent}% of the limit used` },
      el("div.meter-fill", { style: { width: `${percent}%` } })),
    el("span.caption.muted", {}, `${deduction.remainingFormatted} of the limit unused.`),
    deduction.note && el("span.caption.muted", {}, deduction.note),
    deduction.sources.length > 0 && el("details", {},
      el("summary.caption", {}, `From ${deduction.sources.length} ${deduction.sources.length === 1 ? "record" : "records"}`),
      el("div.stack-2", { style: { paddingTop: "8px" } },
        ...deduction.sources.map((source) => el("div.row-between", {},
          el("span", {}, source.title),
          el("span.muted", {}, source.amountFormatted, " ",
            el("span.caption", {}, `(${source.basis})`)),
        )),
      ),
    ),
  );
}

// --- capital gains ----------------------------------------------------------------

const rate = (percent) => (percent == null ? t("tax.slab") : `${Number(percent)}%`);

function gainsCard(gains, schedule) {
  const buckets = schedule?.buckets || [];
  const lines = schedule?.lines || [];

  return el("div.card.stack-2", {},
    el("div.row-between.wrap", {},
      el("h3", {}, t("tax.capitalGains")),
      el("b.num", { style: { fontFamily: "var(--font-display)", fontSize: "var(--text-h4)" } },
        gains.netRealizedFormatted),
    ),
    lines.length > 0 && el("span.caption.muted", {}, schedule.netGainInWords),

    lines.length === 0
      ? el("p.caption.muted", {}, t("tax.nothingSold"))
      : el("div.stack-2", {}, ...buckets.map((bucket) => el("div", {},
          el("div.row-between.wrap", {},
            el("span", {}, bucket.label),
            el("span.num", {}, bucket.taxableGainFormatted),
          ),
          el("span.caption.muted", {},
            bucket.periodLabel,
            Number(bucket.exemptionApplied) > 0
              ? ` · ${t("tax.exemptionUsed", { amount: `₹${groupIndian(Math.round(Number(bucket.exemptionApplied)))}` })}`
              : "",
            bucket.taxAtRateFormatted ? ` · ${t("tax.atRate", { amount: bucket.taxAtRateFormatted })}` : ""),
        ))),

    lines.length > 0 && el("p.caption.muted", {}, t("tax.atRateExplain")),
    ...(schedule?.warnings || []).map(notice),

    lines.length > 0 && el("details", {},
      el("summary.caption", {}, t("tax.lotByLot", { n: lines.length })),
      el("div.stack-3", { style: { paddingTop: "8px" } },
        ...lines.map((line) => el("div.stack-2", {},
          el("div.row-between.wrap", {},
            el("b", {}, line.title),
            el("span.num", {}, line.gainFormatted),
          ),
          el("span.caption.muted", {},
            `${line.acquiredOn} → ${line.transferredOn} · ${line.section} · ${rate(line.ratePercent)}`),
          el("span.caption.muted", {}, line.basis),
          ...line.notes.map((text) => el("span.caption.muted", {}, text)),
        )),
      ),
    ),

    gains.unrealized.length > 0 && el("details", {},
      el("summary.caption", {}, "If you sold today"),
      el("div.stack-2", { style: { paddingTop: "8px" } },
        el("p.caption.muted", {},
          "For planning only. Nothing here has been sold, and this is not a projection of value."),
        ...gains.unrealized.slice(0, 20).map((position) => el("div.row-between", {},
          el("span", {}, position.title),
          el("span.muted", {}, `${position.unrealizedGainFormatted} · ${position.termIfSoldToday}`),
        )),
      ),
    ),
  );
}

/**
 * The 31 January 2018 value, one holding at a time. There is no offline price
 * source, so the owner enters what the exchange quoted that day — once, and it
 * corrects every year's statement, not just this one.
 */
function grandfatheringCard(schedule, refresh) {
  return el("div.card.stack-3", {},
    el("h3", {}, t("tax.fmv.title")),
    el("p.caption.muted", {}, t("tax.fmv.explain")),
    ...schedule.grandfathering.map((entry) => {
      const value = textInput({
        inputMode: "decimal", autocomplete: "off",
        value: entry.fmvPerUnit != null ? String(Number(entry.fmvPerUnit)) : "",
      });
      const note = textInput({
        value: entry.sourceNote || "", maxLength: 200, placeholder: t("tax.fmv.notePlaceholder"),
      });
      const error = el("div.help.error", {});
      const save = el("button.btn", { type: "button" }, t("app.save"));
      save.onclick = () => withBusy(save, async () => {
        error.textContent = "";
        const raw = value.value.replace(/,/g, "").trim();
        if (!/^\d+(\.\d{1,4})?$/.test(raw)) {
          error.textContent = t("tax.fmv.invalid");
          return;
        }
        try {
          await api.setFmv2018(state.household.id, entry.investmentId, {
            fmvPerUnit: Number(raw), sourceNote: note.value.trim() || null,
          });
          toast(t("tax.fmv.saved"));
          await refresh();
        } catch (apiError) { error.textContent = apiError.message; }
      });

      return el("div.stack-2", {},
        el("div.row-between.wrap", {},
          el("b", {}, entry.title),
          el("span.caption.muted", {}, entry.isin || t("tax.fmv.noIsin")),
        ),
        field({ label: t("tax.fmv.perUnitLabel"), control: value }),
        field({ label: t("tax.fmv.note"), control: note }),
        error,
        el("div.row", {}, save),
      );
    }),
  );
}

function interestCard(interest) {
  return el("div.card.stack-2", {},
    el("div.row-between.wrap", {},
      el("h3", {}, "Interest income"),
      el("b.num", {}, interest.totalFormatted),
    ),
    interest.bySource.length === 0
      ? el("p.caption.muted", {}, "No interest recorded for this year.")
      : el("div.stack-2", {}, ...interest.bySource.map((source) => el("div.row-between", {},
          el("span", {}, source.title),
          el("span.muted", {}, source.amountFormatted),
        ))),
  );
}
