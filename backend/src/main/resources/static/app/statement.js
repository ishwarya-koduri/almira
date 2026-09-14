/* =============================================================================
   A statement's words, turned into rows a person checks (P-11).

   No DOM, no pdf.js: the page positions come in, lines and suggested rows come
   out, so scripts/check-statement.js can test every rule here without a
   browser.

   What this deliberately is not: a parser for the CAMS, KFintech, NSDL or CDSL
   consolidated statement. Nobody working on Almira has confirmed how those are
   laid out from an authoritative sample, and a parser written from guesses
   would read the wrong number into someone's net worth with total confidence
   (known-issues 58). So the rules here are generic and say so: a line that
   names a folio is a suggestion, the line above it is its likely name, the
   last amount on it is its likely value, and the person picks, fixes or drops
   every one before anything is sent.
   ============================================================================= */

/**
 * pdf.js text items → lines of text, top to bottom, per page.
 *
 * An item is { str, transform: [a, b, c, d, x, y], width, height }. Items whose
 * baselines sit within a few points of each other are one line; within a line
 * they are ordered left to right and joined with a space where there is a
 * visible gap, so "Folio No:" and "1234567/89" do not run together.
 */
export function linesFromItems(items) {
  const usable = (items || [])
    .filter((item) => item && typeof item.str === "string" && item.str.trim() !== "" && Array.isArray(item.transform))
    .map((item) => ({
      text: item.str,
      x: Number(item.transform[4]) || 0,
      y: Number(item.transform[5]) || 0,
      width: Number(item.width) || 0,
      size: Math.abs(Number(item.transform[3])) || Number(item.height) || 10,
    }));

  // PDF y grows upwards: the top of the page is the largest y.
  usable.sort((a, b) => (b.y - a.y) || (a.x - b.x));

  const rows = [];
  for (const item of usable) {
    const row = rows[rows.length - 1];
    if (row && Math.abs(row.y - item.y) <= Math.max(2, Math.min(row.size, item.size) * 0.4)) {
      row.items.push(item);
    } else {
      rows.push({ y: item.y, size: item.size, items: [item] });
    }
  }

  return rows.map((row) => {
    row.items.sort((a, b) => a.x - b.x);
    let text = "";
    let end = null;
    for (const item of row.items) {
      const gap = end === null ? 0 : item.x - end;
      if (text && gap > item.size * 0.15 && !/\s$/.test(text) && !/^\s/.test(item.text)) text += " ";
      text += item.text;
      end = item.x + item.width;
    }
    return text.replace(/\s+/g, " ").trim();
  }).filter(Boolean);
}

/** "1,25,000.50" → 125000.5. Grouping of any kind is accepted; anything else is null. */
export function amountFrom(text) {
  const match = /^-?\d{1,3}(?:,\d{2,3})*(?:\.\d+)?$|^-?\d+(?:\.\d+)?$/.exec(String(text ?? "").trim());
  if (!match) return null;
  const value = Number(match[0].replace(/,/g, ""));
  return Number.isFinite(value) ? value : null;
}

const FOLIO = /\bfolio\s*(?:no\.?|number|#)?\s*[:\-]?\s*([A-Z0-9][A-Z0-9\/\-]{3,24})/i;
const PERSON = /^(?:investor|investor name|name|holder|first holder|unit ?holder)\s*[:\-]\s*(.{2,80})$/i;
const VALUE = /\b(?:market value|current value|valuation|value)\b\s*(?:\(?(?:inr|rs\.?|₹)\)?)?\s*[:\-]?\s*(?:inr|rs\.?|₹)?\s*(-?[\d,]+(?:\.\d+)?)/i;
const NUMBER = /-?\d{1,3}(?:,\d{2,3})+(?:\.\d+)?|-?\d+\.\d+|-?\d{4,}/g;

/**
 * Suggested rows from a statement's lines.
 *
 * pages: [[line, line, ...], ...]. Returns rows of
 * { key, person, name, folio, value, page, line, source } where `source` is the
 * text the row was read from, shown beside it so a person can check a number
 * against the words it came from.
 */
export function suggestRows(pages) {
  const rows = [];
  let person = "";
  (pages || []).forEach((lines, pageIndex) => {
    let previous = "";
    (lines || []).forEach((line, lineIndex) => {
      const who = PERSON.exec(line);
      if (who) { person = who[1].trim(); previous = ""; return; }

      const folio = FOLIO.exec(line);
      if (!folio) {
        if (/[a-z]{3,}/i.test(line) && !/:\s*$/.test(line)) previous = line;
        return;
      }

      // The name is whatever the line says before "Folio", or else the line above.
      const before = line.slice(0, folio.index).replace(/[\s|:\-–]+$/, "").trim();
      const name = /[a-z]{3,}/i.test(before) ? before : previous;

      const labelled = VALUE.exec(line.slice(folio.index + folio[0].length));
      const numbers = line.slice(folio.index + folio[0].length).match(NUMBER) || [];
      const value = amountFrom(labelled ? labelled[1] : numbers[numbers.length - 1]);

      rows.push({
        key: `${pageIndex + 1}:${lineIndex + 1}`,
        person,
        name,
        folio: folio[1],
        value,
        page: pageIndex + 1,
        line: lineIndex + 1,
        source: name && name !== before ? `${name} · ${line}` : line,
      });
      previous = "";
    });
  });
  return rows;
}

/**
 * A name as written on a statement → a household member, only when it is not a
 * guess: the member's full display name, or every word of it, appears in the
 * statement's name. "Aarav" matches "Aarav Example"; "Ravi" matches nobody in a
 * household with two Ravis. Returns the member id or null.
 */
export function matchMember(statementName, members) {
  const words = new Set(String(statementName || "").toLowerCase().split(/[^\p{L}\p{N}]+/u).filter(Boolean));
  if (!words.size) return null;
  const matches = (members || []).filter((member) => {
    const own = String(member.displayName || "").toLowerCase().split(/[^\p{L}\p{N}]+/u).filter(Boolean);
    return own.length > 0 && own.every((word) => words.has(word));
  });
  return matches.length === 1 ? matches[0].id : null;
}

/** Rows → { memberId: rows } in the order people first appear. Unassigned rows are under "". */
export function groupByPerson(rows) {
  const groups = new Map();
  for (const row of rows || []) {
    const key = row.memberId || "";
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(row);
  }
  return groups;
}

function csvCell(value) {
  const text = value === null || value === undefined ? "" : String(value);
  // A leading = + - @ makes a spreadsheet run a formula; the import reads
  // text, but the same file may be opened in Excel by someone checking it.
  const safe = /^[=+\-@]/.test(text) && !/^-?\d/.test(text) ? `'${text}` : text;
  return /[",\n\r]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe;
}

export const STATEMENT_COLUMNS = ["Person", "Fund", "Folio", "Market value"];

/**
 * The rows a person kept, as the CSV the existing import reads. Only what they
 * checked leaves the device: the member id, a name, a folio and what it is
 * worth — never the PDF, its password, or the lines nobody chose.
 */
export function toCsv(rows) {
  const lines = [STATEMENT_COLUMNS.join(",")];
  for (const row of rows || []) {
    lines.push([row.memberId, row.name, row.folio, row.value].map(csvCell).join(","));
  }
  return `${lines.join("\n")}\n`;
}

/**
 * The mapping that CSV needs, for POST /import. A statement's value is what a
 * holding is worth, so it lands as a valuation, never as the amount invested.
 */
export const STATEMENT_MAPPING = {
  owner: "Person", title: "Fund", reference: "Folio", currentValue: "Market value",
};

/** What stops a row from being sent, or null when it is ready. */
export function rowProblem(row) {
  if (!row.memberId) return "statement.problem.person";
  if (!String(row.name || "").trim()) return "statement.problem.name";
  if (row.value !== null && row.value !== undefined && row.value !== "" && !Number.isFinite(Number(row.value))) {
    return "statement.problem.value";
  }
  return null;
}
