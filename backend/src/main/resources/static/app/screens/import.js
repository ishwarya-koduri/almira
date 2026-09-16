/* =============================================================================
   Import a spreadsheet (docs/03 §10 "Migrate", docs/10 Epic 2.4.3).

   Three steps, in the order that keeps trust: read the file and propose a
   mapping, show what *would* happen, then do it. The dry run is not a courtesy
   — it is the difference between an import you can undo in your head and one
   you find out about later.

   Corrupt rows are reported and the good ones still land. An all-or-nothing
   import of a hundred-row sheet, refused for three bad dates, is how people
   give up and go back to the spreadsheet.
   ============================================================================= */

import { api } from "../api.js";
import { el, mount, sheet, field, select, withBusy, toast } from "../ui.js";
import { state } from "../state.js";
import { reload } from "../app.js";
import { t, categoryName } from "../i18n.js";

// The column each field can come from; its label is looked up when drawn.
const MAPPABLE = ["title", "investedAmount", "quantity", "unit", "startDate", "maturityDate",
  "institution", "reference", "type", "notes"];

export function openImport(onSaved) {
  const fileInput = el("input.input", {
    type: "file",
    accept: ".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  });
  const body = el("div.stack-3", {},
    el("p.caption.muted", {}, t("import.explain")),
    field({ label: t("import.chooseFile"), control: fileInput }),
  );
  const next = el("button.btn.btn-primary.grow", { type: "button" }, t("import.read"));

  next.onclick = () => withBusy(next, async () => {
    const file = fileInput.files?.[0];
    if (!file) { fileInput.focus(); return; }
    const preview = await api.importPreview(state.household.id, file);
    modal.close();
    mapAndRun(file, preview, onSaved);
  });

  const modal = sheet({ title: t("import.title"), body, footer: [next] });
}

function mapAndRun(file, preview, onSaved) {
  const columnOptions = [
    { value: "", label: t("import.notImported") },
    ...preview.headers.map((header) => ({ value: header, label: header })),
  ];

  const mappingControls = new Map();
  const mappingFields = MAPPABLE.map((key) => {
    const label = t(`import.column.${key}`);
    const control = select({
      options: columnOptions,
      value: preview.suggestedMapping?.[key] || "",
    });
    mappingControls.set(key, control);
    return field({ label, control });
  });

  const typeSelect = select({
    options: [
      { value: "", label: t("import.typeFromSheet") },
      ...state.taxonomy.flatMap((category) =>
        category.types.map((type) => ({
          value: type.id, label: `${categoryName(category.categoryCode, category.categoryLabel)} · ${type.label}`,
        }))),
    ],
  });

  const visibilitySelect = select({
    options: [
      { value: "private", label: t("detail.visibility.private") },
      { value: "household", label: t("common.sharedWith", { name: state.household.name }) },
    ],
    value: state.user?.defaultVisibility || state.household.defaultVisibility,
  });

  // The dry run's answer arrives after a wait, so it is announced (X-85).
  const outcome = el("div.stack-2", { "aria-live": "polite" });
  const error = el("div.help.error", { role: "alert", style: { minHeight: "1.15rem" } });
  const dryRun = el("button.btn.grow", { type: "button" }, t("import.dryRun"));
  const commit = el("button.btn.btn-primary.grow", { type: "button", disabled: true }, t("import.commit"));

  const request = () => ({
    typeId: typeSelect.value || null,
    visibility: visibilitySelect.value,
    mapping: Object.fromEntries(
      [...mappingControls].map(([key, control]) => [key, control.value || null])),
  });

  const run = (button, isDryRun) => withBusy(button, async () => {
    error.textContent = "";
    let report;
    try {
      report = await api.runImport(state.household.id, file, {
        request: { ...request(), dryRun: isDryRun },
      });
    } catch (apiError) {
      // Shown here rather than as a toast: the thing to change — usually the
      // type, or a column — is on this screen, and a message that fades away
      // leaves someone pressing the same button again.
      error.textContent = apiError.message;
      return;
    }
    showReport(report);
    if (isDryRun) commit.disabled = report.total === 0;
    if (!isDryRun) {
      toast(report.failed
        ? t("import.doneSkipped", { added: report.imported, skipped: report.failed })
        : t("import.done", { added: report.imported }));
      modal.close();
      await (onSaved ? onSaved() : reload());
    }
  });

  const showReport = (report) => {
    mount(outcome,
      el("div.row.wrap", { style: { gap: "8px" } },
        el("span.chip.chip-static", {}, t("import.rows", { count: report.total })),
        el("span.chip.chip-static", {},
          report.dryRun ? t("import.toAdd", { count: report.wouldImport }) : t("import.added", { count: report.imported })),
        report.duplicates > 0 && el("span.chip.chip-static", {}, t("import.alreadyHere", { count: report.duplicates })),
        report.failed > 0 && el("span.chip.chip-static", { style: { color: "var(--caution)" } },
          t("import.withProblem", { count: report.failed })),
      ),
      el("p.caption.muted", {}, report.note),
      // Problems first, then the rows that will land but lost a cell on the way.
      // Both are things to look at before pressing Import; the rest is noise.
      ...report.rows
        .filter((row) => row.outcome === "failed" || row.outcome === "skipped")
        .slice(0, 20)
        .map((row) => el("div.card.card-tight", {},
          el("b", {}, t("import.row", { row: row.row })), " ",
          el("span.muted", {}, row.title || ""), " · ", row.message)),
      ...report.rows
        .filter((row) => row.outcome !== "failed" && row.outcome !== "skipped"
          && (row.message || "").includes("Couldn't read"))
        .slice(0, 20)
        .map((row) => el("p.caption.muted", {},
          `${t("import.row", { row: row.row })}: ${row.message}`)),
    );
  };

  dryRun.onclick = () => run(dryRun, true);
  commit.onclick = () => run(commit, false);

  const modal = sheet({
    title: t("import.fileTitle", { name: preview.fileName }),
    body: el("div.stack-3", {},
      el("p.caption.muted", {}, preview.note),
      el("div.stack-2", {},
        el("span.overline", {}, t("import.firstRows")),
        sampleTable(preview),
      ),
      el("div.stack-2", {},
        el("span.overline", {}, t("import.whichColumn")),
        ...mappingFields,
      ),
      field({ label: t("import.typeForEvery"), control: typeSelect, help: t("import.typeHelp") }),
      field({ label: t("import.whoCanSee"), control: visibilitySelect }),
      error,
      outcome,
    ),
    footer: [dryRun, commit],
  });
}

function sampleTable(preview) {
  const wrap = el("div", { style: { overflowX: "auto" }, role: "region", tabindex: "0", "aria-label": t("import.firstRows") });
  const table = el("table.table", {},
    el("thead", {}, el("tr", {}, ...preview.headers.map((header) => el("th", {}, header)))),
    el("tbody", {}, ...preview.sample.map((row) =>
      el("tr", {}, ...preview.headers.map((header) => el("td", {}, row[header] || ""))))),
  );
  wrap.append(table);
  return wrap;
}
