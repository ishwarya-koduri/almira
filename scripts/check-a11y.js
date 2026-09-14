/* =============================================================================
   Screen reader support that can be checked without one (X-85, docs/02 §9).

       /System/Library/Frameworks/JavaScriptCore.framework/Versions/A/Helpers/jsc \
         -m scripts/check-a11y.js
       # or: node scripts/check-a11y.js  (as an ES module)

   1. The heading outline (headings.js): levels never skip, and a sheet or a
      page starts where it should.
   2. Every button the client builds has a name: words inside it, or an
      aria-label when all it shows is an icon. Read out of the source, for every
      el("button…") in the files the app loads.
   3. Every chart carries a sentence: areaTrend and donut are always given a
      summary, and partRing a label.
   4. The pieces a screen reader leans on stay in ui.js: toasts in a live
      region and failures as alerts, sheets named, holding focus and giving it
      back, a field's help and error tied to its control.

   What only a page can show — names as computed, targets, text size — is
   scripts/browser-checks/a11y-audit.js; what only a person can judge is the
   TalkBack and VoiceOver script in docs/02 §9.
   ============================================================================= */

import { outline } from "../backend/src/main/resources/static/app/headings.js";

const log = typeof print === "function" ? print : console.log;
const readText = (path) => (typeof readFile === "function"
  ? readFile(path)
  : globalThis.require("fs").readFileSync(path, "utf8"));
const APP = "backend/src/main/resources/static/app/";

let failures = 0;
function expect(label, actual, wanted) {
  const a = JSON.stringify(actual);
  const w = JSON.stringify(wanted);
  if (a === w) log(`  ok   ${label}`);
  else { failures += 1; log(`  FAIL ${label}\n         got    ${a}\n         wanted ${w}`); }
}
function fail(message) { failures += 1; log(`  FAIL ${message}`); }

/* --- 1 · the outline -------------------------------------------------------- */

log("\nHeadings someone can jump between");
expect("Settings: an h1, then card titles drawn as h4", outline([1, 4, 4, 4]), [1, 2, 2, 2]);
expect("a card's own sections sit under it", outline([1, 4, 5, 4]), [1, 2, 3, 2]);
expect("a screen titled with an h2 starts at level 1", outline([2, 3, 3]), [1, 2, 2]);
expect("a smaller tag after a deeper run goes back up", outline([1, 3, 4, 4, 3, 2]), [1, 2, 3, 3, 2, 2]);
expect("never deeper than one below the heading before", outline([1, 6]), [1, 2]);
expect("a sheet can start below the page", outline([3, 4], 2), [2, 3]);
expect("no headings, no levels", outline([]), []);

/* --- the files the client loads -------------------------------------------- */

function loadedFiles() {
  const seen = new Set();
  const queue = ["app.js", "guest.js"];
  while (queue.length) {
    const file = queue.shift();
    if (seen.has(file)) continue;
    seen.add(file);
    const source = readText(APP + file);
    const base = file.includes("/") ? file.slice(0, file.lastIndexOf("/") + 1) : "";
    for (const match of source.matchAll(/(?:import|export)\s[^;]*?from\s+"(\.{1,2}\/[^"]+\.js)"/g)) {
      const resolved = [];
      for (const part of (base + match[1]).split("/")) {
        if (part === "." || part === "") continue;
        if (part === "..") resolved.pop(); else resolved.push(part);
      }
      const path = resolved.join("/");
      if (!path.startsWith("vendor/")) queue.push(path);
    }
  }
  return [...seen].sort();
}

/** The arguments of a call whose "(" is at `open`, split at top-level commas. */
function callArguments(source, open) {
  const args = [];
  let depth = 0;
  let quote = null;
  let start = open + 1;
  for (let i = open; i < source.length; i += 1) {
    const c = source[i];
    if (quote) {
      if (c === "\\") { i += 1; continue; }
      if (c === quote) quote = null;
      continue;
    }
    if (c === "/" && source[i + 1] === "/") { i = source.indexOf("\n", i); continue; }
    if (c === "/" && source[i + 1] === "*") { i = source.indexOf("*/", i) + 1; continue; }
    if (c === '"' || c === "'" || c === "`") { quote = c; continue; }
    if (c === "(" || c === "[" || c === "{") depth += 1;
    else if (c === ")" || c === "]" || c === "}") {
      depth -= 1;
      if (depth === 0) { args.push(source.slice(start, i).trim()); return { args: args.filter(Boolean), end: i }; }
    } else if (c === "," && depth === 1) { args.push(source.slice(start, i).trim()); start = i + 1; }
  }
  return { args, end: source.length };
}

const lineOf = (source, index) => source.slice(0, index).split("\n").length;

/* --- 2 and 3 · buttons and charts ------------------------------------------- */

log("\nEvery button has a name, every chart a sentence");
const files = loadedFiles();
let buttons = 0;
let charts = 0;
for (const file of files) {
  const source = readText(APP + file);
  for (const match of source.matchAll(/\bel\(\s*["'`]button[^"'`]*["'`]/g)) {
    buttons += 1;
    const { args } = callArguments(source, match.index + match[0].indexOf("("));
    const props = args[1] || "";
    const children = args.slice(2);
    const labelled = /["']?aria-label(ledby)?["']?\s*:/.test(props);
    // Words: any child that is not only an icon. A variable is trusted to hold words.
    const worded = children.some((child) => !/^icon\(/.test(child));
    // A button whose words are added later (button.textContent = …) says so with a label.
    if (!labelled && !worded) fail(`${file}:${lineOf(source, match.index)} a button with no words and no aria-label`);
  }
  for (const match of source.matchAll(/\b(areaTrend|donut)\(/g)) {
    if (file === "ui.js") continue;
    charts += 1;
    const { args } = callArguments(source, match.index + match[1].length);
    if (!/\bsummary\b/.test(args[1] || "")) fail(`${file}:${lineOf(source, match.index)} ${match[1]} without a summary`);
  }
  for (const match of source.matchAll(/\b(partRing)\(/g)) {
    if (file === "ui.js") continue;
    charts += 1;
    const { args } = callArguments(source, match.index + match[1].length);
    if (!/\blabel\b/.test(args[1] || "")) fail(`${file}:${lineOf(source, match.index)} partRing without a label`);
  }
}
if (!failures) log(`  ok   ${buttons} buttons in ${files.length} files, ${charts} charts and rings, each named`);

/* --- 4 · what ui.js promises ------------------------------------------------- */

log("\nThe shared pieces keep their promises");
const ui = readText(`${APP}ui.js`);
const has = (label, pattern) => expect(label, pattern.test(ui), true);
has("toasts are a polite live region", /toast-host", \{ role: "status", "aria-live": "polite" \}/);
has("a failed toast is an alert", /role: tone === "error" \? "alert" : null/);
has("a sheet is a modal dialog named by its title", /role: "dialog", "aria-modal": "true", "aria-labelledby": titleId/);
has("a sheet keeps Tab inside it", /event\.key !== "Tab"[\s\S]{0,400}last\.focus\(\)[\s\S]{0,200}first\.focus\(\)/);
has("a sheet gives focus back, or to the screen's title", /returnTo\.find\([\s\S]{0,300}main h1, main \[aria-level='1'\]/);
has("a field's help is read with its control", /aria-describedby/);
has("an error is announced and marks the control invalid", /setAttribute\("role", "alert"\)[\s\S]{0,400}setAttribute\("aria-invalid", "true"\)/);
has("the short money figure is hidden from a screen reader, the full one is not", /money-short", \{ "aria-hidden": "true" \}/);
has("a chart's svg is an image named by its summary", /role: "img", "aria-label": summary/);

if (failures > 0) {
  log(`\n${failures} failing`);
  throw new Error(`${failures} failing`);
}
log("\nall passing");
