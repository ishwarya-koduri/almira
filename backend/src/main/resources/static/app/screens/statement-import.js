/* =============================================================================
   Import a consolidated account statement (P-11).

   Three steps, each one saying where the file is:

   1. **Open it here.** The PDF and its password go to pdf.js in this browser
      (pdf-text.js), and nowhere else. A small lock opens when it does.
   2. **Check what we found, by person.** statement.js suggests a row for each
      line that names a folio, and the person keeps, fixes or drops each one,
      or adds a row from any line it read. Nothing is assumed about how a real
      registrar lays out its statement (known-issues 46), so every row shows
      the words it came from.
   3. **Show me, then import.** The rows kept — person, name, folio, value, and
      nothing else — become a CSV that goes through the ordinary import, dry
      run first, exactly like a spreadsheet (import.js).
   ============================================================================= */

import { api } from "../api.js";
import {
  el, mount, sheet, field, textInput, select, withBusy, toast, notice, moneyInput,
} from "../ui.js";
import { state, typesFlat } from "../state.js";
import { t } from "../i18n.js";
import { reload } from "../app.js";
import { readPdf, PdfError } from "../pdf-text.js";
import { lockMark } from "../recovery.js";
import {
  linesFromItems, suggestRows, matchMember, groupByPerson, toCsv, rowProblem, STATEMENT_MAPPING,
} from "../statement.js";

export function openStatementImport(onSaved) {
  const fileInput = el("input.input", {
    type: "file", accept: "application/pdf,.pdf", "aria-label": t("statement.file"),
  });
  const password = textInput({
    type: "password", autocomplete: "off", spellcheck: false, "aria-label": t("statement.password"),
  });
  const passwordField = field({ label: t("statement.password"), control: password, help: t("statement.password.help") });
  const status = el("div.stack-2", { "aria-live": "polite" });
  const open = el("button.btn.btn-primary.grow", { type: "button" }, t("statement.open"));

  const say = (key, { alert = false } = {}) => mount(status,
    notice(t(key), { tone: alert ? "alert" : "", role: alert ? "alert" : "status" }));

  open.onclick = () => withBusy(open, async () => {
    const file = fileInput.files?.[0];
    if (!file) { fileInput.focus(); return; }
    mount(status);
    let pages;
    try {
      pages = await readPdf(file, password.value);
    } catch (error) {
      const code = error instanceof PdfError ? error.code : "unreadable";
      if (code === "password-needed") { say("statement.error.passwordNeeded"); password.focus(); return; }
      if (code === "password-wrong") { say("statement.error.passwordWrong", { alert: true }); password.select(); return; }
      say(code === "too-large" ? "statement.error.tooLarge" : "statement.error.unreadable", { alert: true });
      return;
    }
    // The password has done its job; it is not kept for a moment longer.
    password.value = "";
    const lines = pages.map(linesFromItems);
    // The same lock that closes when something is sealed (recovery.js), opening.
    const lock = lockMark(true);
    mount(status, el("p.note", { role: "status" },
      lock, el("span", {}, t("statement.opened", { lines: lines.reduce((n, page) => n + page.length, 0), pages: lines.length }))));
    requestAnimationFrame(() => requestAnimationFrame(() => { lock.dataset.closed = "false"; }));
    // Long enough to see the lock open; short enough not to wait on it.
    await new Promise((resolve) => setTimeout(resolve, 450));
    modal.close();
    review(file.name, lines, onSaved);
  });

  const modal = sheet({
    title: t("statement.title"),
    body: el("div.stack-3", {},
      el("p.caption", { style: { margin: 0 } }, t("statement.intro")),
      notice(t("statement.private")),
      field({ label: t("statement.file"), control: fileInput }),
      passwordField,
      status,
    ),
    footer: [open],
  });
}

/* -----------------------------------------------------------------------------
   Step 2 and 3
   ----------------------------------------------------------------------------- */

function review(fileName, lines, onSaved) {
  const members = state.members || [];
  let rows = suggestRows(lines).map((row) => ({
    ...row, include: true, memberId: matchMember(row.person, members),
  }));
  let nextKey = 0;

  const lumpsum = typesFlat().find((type) => type.code === "mf_lumpsum");
  const typeSelect = select({
    options: typesFlat().map((type) => ({ value: type.id, label: `${type.categoryLabel} — ${type.label}` })),
    value: lumpsum?.id,
    "aria-label": t("statement.type"),
  });
  const visibilitySelect = select({
    options: [
      { value: "private", label: t("statement.visibility.private") },
      { value: "household", label: t("statement.visibility.household", { household: state.household.name }) },
    ],
    value: state.user?.defaultVisibility || state.household.defaultVisibility,
    "aria-label": t("statement.visibility"),
  });

  const summary = el("p.statement-summary", { "aria-live": "polite" });
  const groupsHost = el("div.stack-3", {});
  const outcome = el("div.stack-2", {});
  const error = el("div.help.error", { role: "alert", style: { minHeight: "1.15rem" } });
  const dryRun = el("button.btn.grow", { type: "button" }, t("statement.dryRun"));
  const commit = el("button.btn.btn-primary.grow", { type: "button", disabled: true }, t("statement.import"));

  // Any change after a dry run means the dry run no longer describes the file.
  const changed = () => { commit.disabled = true; mount(outcome); drawSummary(); };

  const kept = () => rows.filter((row) => row.include);

  const drawSummary = () => {
    const chosen = kept();
    const people = new Set(chosen.map((row) => row.memberId).filter(Boolean)).size;
    summary.textContent = chosen.length === 0
      ? t("statement.summary.none")
      : t("statement.summary", { rows: chosen.length, people });
  };

  const memberOptions = [
    { value: "", label: t("statement.person.choose") },
    ...members.map((member) => ({ value: member.id, label: member.displayName })),
  ];

  const rowCard = (row) => {
    const include = el("input", { type: "checkbox", checked: row.include });
    include.addEventListener("change", () => { row.include = include.checked; changed(); draw(); });

    const person = select({ options: memberOptions, value: row.memberId || "", "aria-label": t("statement.person") });
    person.addEventListener("change", () => { row.memberId = person.value || null; changed(); draw(); });

    const name = textInput({ value: row.name || "", "aria-label": t("statement.name") });
    name.addEventListener("input", () => { row.name = name.value; changed(); });

    const folio = textInput({ value: row.folio || "", "aria-label": t("statement.folio"), autocomplete: "off" });
    folio.addEventListener("input", () => { row.folio = folio.value.trim(); changed(); });

    const value = moneyInput({ "aria-label": t("statement.value") });
    value.input.addEventListener("input", () => { row.value = value.value(); changed(); });
    if (row.value !== null && row.value !== undefined) {
      // Through the field's own formatter, so it reads 1,25,000.50 like a typed amount.
      value.input.value = String(row.value);
      value.input.dispatchEvent(new Event("input"));
    }

    return el("div.card.card-tight.stack-2.statement-row", { "data-included": String(row.include) },
      el("label.check-row", {},
        include,
        el("span.grow", {},
          el("span.check-label", {}, row.name || t("statement.untitled")),
          row.source && el("span.caption", {}, `${t("statement.readFrom", { page: row.page })} “${row.source}”`),
        ),
      ),
      row.include && el("div.stack-2", {},
        field({ label: t("statement.person"), control: person }),
        field({ label: t("statement.name"), control: name }),
        el("div.grid-2", {},
          field({ label: t("statement.folio"), control: folio }),
          field({ label: t("statement.value"), control: value, help: t("statement.value.help") }),
        ),
      ),
    );
  };

  const draw = () => {
    const groups = groupByPerson(rows);
    const order = [...groups.keys()].sort((a, b) => (a === "" ? -1 : b === "" ? 1 : 0));
    mount(groupsHost,
      rows.length === 0 && notice(t("statement.nothingFound")),
      ...order.map((memberId) => {
        const list = groups.get(memberId);
        const who = members.find((member) => member.id === memberId);
        return el("section.stack-2", {},
          el("div.section-title", {},
            el("h4", {}, who ? who.displayName : t("statement.person.unassigned")),
            el("span.caption", {}, t("statement.count", { count: list.filter((row) => row.include).length }))),
          ...list.map(rowCard),
        );
      }),
    );
    drawSummary();
  };

  // The manual path: any line the PDF held can become a row.
  const allLines = el("details.stack-2", {},
    el("summary", {}, t("statement.allLines", { count: lines.reduce((n, page) => n + page.length, 0) })),
    el("p.caption", {}, t("statement.allLines.help")),
    ...lines.flatMap((page, pageIndex) => page.map((line) => el("div.row-between.statement-line", {},
      el("span.caption.grow", {}, line),
      el("button.btn.btn-ghost.btn-sm", {
        type: "button",
        onclick: () => {
          rows = [...rows, {
            key: `manual-${nextKey += 1}`, person: "", name: line, folio: "", value: null,
            page: pageIndex + 1, source: line, include: true, memberId: members.length === 1 ? members[0].id : null,
          }];
          changed();
          draw();
          toast(t("statement.added"));
        },
      }, t("statement.addRow")),
    ))),
  );

  const run = (button, isDryRun) => withBusy(button, async () => {
    error.textContent = "";
    const chosen = kept();
    if (chosen.length === 0) { error.textContent = t("statement.summary.none"); return; }
    const problem = chosen.map(rowProblem).find(Boolean);
    if (problem) { error.textContent = t(problem); return; }

    const file = new File([toCsv(chosen)], "statement.csv", { type: "text/csv" });
    let report;
    try {
      report = await api.runImport(state.household.id, file, {
        request: {
          typeId: typeSelect.value || null,
          visibility: visibilitySelect.value,
          mapping: STATEMENT_MAPPING,
          dryRun: isDryRun,
        },
      });
    } catch (apiError) {
      error.textContent = apiError.message;
      return;
    }
    mount(outcome,
      el("div.row.wrap", { style: { gap: "8px" } },
        el("span.chip.chip-static", {},
          report.dryRun ? t("statement.report.wouldAdd", { count: report.wouldImport }) : t("statement.report.added", { count: report.imported })),
        report.duplicates > 0 && el("span.chip.chip-static", {}, t("statement.report.already", { count: report.duplicates })),
        report.failed > 0 && el("span.chip.chip-static", {}, t("statement.report.problem", { count: report.failed })),
      ),
      el("p.caption", {}, report.note),
      ...report.rows.filter((row) => row.outcome === "failed" || row.outcome === "skipped").map((row) =>
        notice(`${row.title || ""} — ${row.message}`, { tone: "alert" })),
    );
    if (isDryRun) {
      commit.disabled = report.wouldImport === 0;
      return;
    }
    toast(t("statement.report.added", { count: report.imported }));
    modal.close();
    await (onSaved ? onSaved() : reload());
  });

  dryRun.onclick = () => run(dryRun, true);
  commit.onclick = () => run(commit, false);

  const modal = sheet({
    title: t("statement.review.title"),
    body: el("div.stack-3", {},
      summary,
      notice(t("statement.review.check")),
      field({ label: t("statement.type"), control: typeSelect }),
      field({ label: t("statement.visibility"), control: visibilitySelect }),
      groupsHost,
      allLines,
      error,
      outcome,
      el("p.caption", {}, t("statement.review.sent", { file: fileName })),
    ),
    footer: [dryRun, commit],
  });
  draw();
}
