/* =============================================================================
   A statement's lines and suggested rows, without a browser (P-11).

       /System/Library/Frameworks/JavaScriptCore.framework/Versions/A/Helpers/jsc \
         -m scripts/check-statement.js

   The lines below are the synthetic fixture's (scripts/browser-checks/fixtures,
   StatementFixtureTest), as pdf.js hands them over. They are not a real
   registrar's layout, and nothing here claims to parse one (known-issues 58).
   ============================================================================= */

import {
  linesFromItems, amountFrom, suggestRows, matchMember, groupByPerson, toCsv, rowProblem, STATEMENT_MAPPING,
} from "../backend/src/main/resources/static/app/statement.js";

const log = typeof print === "function" ? print : console.log;
let failures = 0;
function expect(label, actual, wanted) {
  const a = JSON.stringify(actual);
  const w = JSON.stringify(wanted);
  if (a === w) log(`  ok   ${label}`);
  else { failures += 1; log(`  FAIL ${label}\n         got    ${a}\n         wanted ${w}`); }
}

const item = (str, x, y, width = str.length * 5, size = 10) => ({ str, transform: [size, 0, 0, size, x, y], width, height: size });

log("\nLines from positioned text");
expect("items on one baseline become one line, left to right",
  linesFromItems([item("1234567/89", 110, 700), item("Folio No:", 48, 700, 45)]),
  ["Folio No: 1234567/89"]);
expect("top of the page first",
  linesFromItems([item("second", 48, 684), item("first", 48, 700)]), ["first", "second"]);
expect("a baseline a hair off is still the same line",
  linesFromItems([item("Units", 48, 700.4, 25), item("40.000", 80, 699.8)]), ["Units 40.000"]);
expect("pieces that touch are not split by a space",
  linesFromItems([item("1,25,", 48, 700, 25), item("000.50", 73, 700)]), ["1,25,000.50"]);
expect("empty and malformed items are dropped",
  linesFromItems([item("  ", 1, 1), { str: "x" }, null, item("kept", 48, 700)]), ["kept"]);

log("\nAmounts");
expect("Indian grouping", amountFrom("1,25,000.50"), 125000.5);
expect("western grouping", amountFrom("125,000.50"), 125000.5);
expect("plain", amountFrom("40000"), 40000);
expect("words are not an amount", amountFrom("nil"), null);
expect("nothing is not zero", amountFrom(""), null);

const pages = [
  [
    "Statement of holdings - SYNTHETIC TEST FIXTURE, not a real registrar format",
    "Period 01-Apr-2026 to 31-Aug-2026",
    "Investor: Ishwarya Example",
    "Example Flexi Cap Fund - Direct Growth",
    "Folio No: 1234567/89 Units 1,234.567 Value 1,25,000.50",
    "Example Liquid Fund - Direct Growth",
    "Folio No: 99887766 Units 40.000 Value 40,000.00",
  ],
  [
    "Investor: Aarav Example",
    "Example Index Fund - Regular Growth",
    "Folio No: 55501234 Units 312.100 Value 18,250.75",
  ],
];

log("\nSuggested rows");
const rows = suggestRows(pages);
expect("one row for each line that names a folio", rows.map((r) => r.folio), ["1234567/89", "99887766", "55501234"]);
expect("the name is the line above", rows[0].name, "Example Flexi Cap Fund - Direct Growth");
expect("the value is the one labelled, not the units", rows.map((r) => r.value), [125000.5, 40000, 18250.75]);
expect("the person carries across the page break until the next", rows.map((r) => r.person),
  ["Ishwarya Example", "Ishwarya Example", "Aarav Example"]);
expect("each row says where it was read from", [rows[2].page, rows[2].line], [2, 3]);
expect("a name on the same line as the folio is used",
  suggestRows([["Some Fund Growth | Folio: AB1234 Value 500.00"]])[0].name, "Some Fund Growth");
expect("an unlabelled last number is taken as the value",
  suggestRows([["Folio 7788990 12.5 3,400.00"]])[0].value, 3400);
expect("no folio, no suggestion", suggestRows([["Total 1,65,251.25"]]), []);

log("\nPeople");
const members = [{ id: "m1", displayName: "Ishwarya" }, { id: "m2", displayName: "Aarav" }, { id: "m3", displayName: "Ravi" }, { id: "m4", displayName: "Ravi" }];
expect("every word of a member's name is in the statement's", matchMember("Ishwarya Example", members), "m1");
expect("case does not matter", matchMember("AARAV EXAMPLE", members), "m2");
expect("two members who could be meant is nobody", matchMember("Ravi Kumar", members), null);
expect("part of a word is not a match", matchMember("Aaravind", members), null);
expect("no name, no match", matchMember("", members), null);

const assigned = rows.map((r) => ({ ...r, memberId: matchMember(r.person, members) }));
expect("rows group by person, in order", [...groupByPerson(assigned).keys()], ["m1", "m2"]);
expect("unmatched rows group together", [...groupByPerson([{ memberId: null }, { memberId: "m1" }]).keys()], ["", "m1"]);

log("\nWhat is sent");
const csv = toCsv([{ memberId: "m1", name: 'Fund, "Growth"', folio: "123/45", value: 125000.5 },
  { memberId: "m2", name: "=HYPERLINK(1)", folio: "", value: null }]);
expect("a header the import reads, then one line per row", csv.split("\n"),
  ["Person,Fund,Folio,Market value", 'm1,"Fund, ""Growth""",123/45,125000.5', "m2,'=HYPERLINK(1),,", ""]);
expect("the mapping names those headers", Object.values(STATEMENT_MAPPING).sort(), ["Folio", "Fund", "Market value", "Person"]);
expect("a value goes in as what it is worth, never as the amount invested", "investedAmount" in STATEMENT_MAPPING, false);
expect("a row with no person is not ready", rowProblem({ name: "x" }), "statement.problem.person");
expect("a row with no name is not ready", rowProblem({ memberId: "m1", name: " " }), "statement.problem.name");
expect("a row with no value is ready", rowProblem({ memberId: "m1", name: "x", value: null }), null);

log(failures === 0 ? "\nAll statement checks passed.\n" : `\n${failures} statement checks failed.\n`);
if (typeof quit === "function") quit(failures === 0 ? 0 : 1);
else if (typeof process !== "undefined") process.exit(failures === 0 ? 0 : 1);
