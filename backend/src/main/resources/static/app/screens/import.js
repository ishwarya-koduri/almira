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

const MAPPABLE = [
  ["title", "Name"],
  ["investedAmount", "Amount"],
  ["quantity", "Quantity"],
  ["unit", "Unit"],
  ["startDate", "Start date"],
  ["maturityDate", "Maturity date"],
  ["institution", "Institution"],
  ["reference", "Folio / policy / receipt number"],
  ["type", "Type (if the sheet says)"],
  ["notes", "Notes"],
];

export function openImport(onSaved) {
  const fileInput = el("input.input", {
    type: "file",
    accept: ".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "aria-label": "Spreadsheet",
  });
  const body = el("div.stack-3", {},
    el("p.caption.muted", {},
      "A CSV or Excel file with one holding per row. Nothing is saved until you've seen what it would do."),
    field({ label: "Choose a file", control: fileInput }),
  );
  const next = el("button.btn.btn-primary.grow", { type: "button" }, "Read the file");

  next.onclick = () => withBusy(next, async () => {
    const file = fileInput.files?.[0];
    if (!file) { fileInput.focus(); return; }
    const preview = await api.importPreview(state.household.id, file);
    modal.close();
    mapAndRun(file, preview, onSaved);
  });

  const modal = sheet({ title: "Import a spreadsheet", body, footer: [next] });
}

function mapAndRun(file, preview, onSaved) {
  const columnOptions = [
    { value: "", label: "— not imported —" },
    ...preview.headers.map((header) => ({ value: header, label: header })),
  ];

  const mappingControls = new Map();
  const mappingFields = MAPPABLE.map(([key, label]) => {
    const control = select({
      options: columnOptions,
      value: preview.suggestedMapping?.[key] || "",
      "aria-label": label,
    });
    mappingControls.set(key, control);
    return field({ label, control });
  });

  const typeSelect = select({
    options: [
      { value: "", label: "Take it from the sheet, or Anything Else" },
      ...state.taxonomy.flatMap((category) =>
        category.types.map((type) => ({ value: type.id, label: `${category.categoryLabel} — ${type.label}` }))),
    ],
    "aria-label": "Type for every row",
  });

  const visibilitySelect = select({
    options: [
      { value: "private", label: "Private — only the owner" },
      { value: "household", label: `Shared with ${state.household.name}` },
    ],
    value: state.user?.defaultVisibility || state.household.defaultVisibility,
    "aria-label": "Who can see these",
  });

  const outcome = el("div.stack-2", {});
  const error = el("div.help.error", { style: { minHeight: "1.15rem" } });
  const dryRun = el("button.btn.grow", { type: "button" }, "Show me what would happen");
  const commit = el("button.btn.btn-primary.grow", { type: "button", disabled: true }, "Import");

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
      toast(`${report.imported} added${report.failed ? `, ${report.failed} skipped` : ""}.`);
      modal.close();
      await (onSaved ? onSaved() : reload());
    }
  });

  const showReport = (report) => {
    mount(outcome,
      el("div.row.wrap", { style: { gap: "8px" } },
        el("span.chip.chip-static", {}, `${report.total} rows`),
        el("span.chip.chip-static", {},
          report.dryRun ? `${report.wouldImport} to add` : `${report.imported} added`),
        report.duplicates > 0 && el("span.chip.chip-static", {}, `${report.duplicates} already here`),
        report.failed > 0 && el("span.chip.chip-static", { style: { color: "var(--caution)" } },
          `${report.failed} with a problem`),
      ),
      el("p.caption.muted", {}, report.note),
      // Problems first, then the rows that will land but lost a cell on the way.
      // Both are things to look at before pressing Import; the rest is noise.
      ...report.rows
        .filter((row) => row.outcome === "failed" || row.outcome === "skipped")
        .slice(0, 20)
        .map((row) => el("div.card.card-tight", {},
          el("b", {}, `Row ${row.row}`), " ",
          el("span.muted", {}, row.title || ""), " — ", row.message)),
      ...report.rows
        .filter((row) => row.outcome !== "failed" && row.outcome !== "skipped"
          && (row.message || "").includes("Couldn't read"))
        .slice(0, 20)
        .map((row) => el("p.caption.muted", {},
          `Row ${row.row} — ${row.message}`)),
    );
  };

  dryRun.onclick = () => run(dryRun, true);
  commit.onclick = () => run(commit, false);

  const modal = sheet({
    title: `Import ${preview.fileName}`,
    body: el("div.stack-3", {},
      el("p.caption.muted", {}, preview.note),
      el("div.stack-2", {},
        el("span.overline", {}, "First rows"),
        sampleTable(preview),
      ),
      el("div.stack-2", {},
        el("span.overline", {}, "Which column is which"),
        ...mappingFields,
      ),
      field({
        label: "Type for every row", control: typeSelect,
        help: "One sheet is usually one kind of thing — all your FDs, or all your funds. " +
          "Leave it unset only if a column above says the type.",
      }),
      field({ label: "Who can see these?", control: visibilitySelect }),
      error,
      outcome,
    ),
    footer: [dryRun, commit],
  });
}

function sampleTable(preview) {
  const wrap = el("div", { style: { overflowX: "auto" } });
  const table = el("table.table", {},
    el("thead", {}, el("tr", {}, ...preview.headers.map((header) => el("th", {}, header)))),
    el("tbody", {}, ...preview.sample.map((row) =>
      el("tr", {}, ...preview.headers.map((header) => el("td", {}, row[header] || ""))))),
  );
  wrap.append(table);
  return wrap;
}
