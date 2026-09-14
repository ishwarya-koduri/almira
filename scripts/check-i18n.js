/* =============================================================================
   Every word through t(), and every key in three languages (X-06, docs/14).

       /System/Library/Frameworks/JavaScriptCore.framework/Versions/A/Helpers/jsc \
         -m scripts/check-i18n.js
       # or: node scripts/check-i18n.js  (as an ES module)

   Four things, none of which a browser is needed for:

   1. Every key English has, Telugu and Hindi have too — written in i18n.js or
      machine-drafted in i18n-te.js / i18n-hi.js — with the same {placeholders}.
      A draft for a key English no longer has is stale and fails.
   2. Every t("key") the client calls exists in English.
   3. No hard-coded English sentence is left in the client's code: a string
      literal that reads like words, outside a comment, fails unless it is on
      the short list below with the reason it stays.
   4. Amounts keep Indian grouping in every language: no translation rewrites
      a figure it was given.

   The files checked are the ones the app and the guest page actually load,
   found by following their imports from app.js and guest.js.
   ============================================================================= */

import { catalogue } from "../backend/src/main/resources/static/app/i18n.js";

const log = typeof print === "function" ? print : console.log;
const readText = (path) => (typeof readFile === "function"
  ? readFile(path)
  : globalThis.require("fs").readFileSync(path, "utf8"));
const APP = "backend/src/main/resources/static/app/";

let failures = 0;
function fail(message) { failures += 1; log(`  FAIL ${message}`); }
function ok(message) { log(`  ok   ${message}`); }

const { en, te, hi, drafts } = catalogue();
const placeholders = (text) => [...String(text).matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort().join(",");

/* --- 1 · three languages --------------------------------------------------- */

log("\nEvery key in Telugu and Hindi, with the same placeholders");
for (const [code, reviewed, draft] of [["te", te, drafts.te], ["hi", hi, drafts.hi]]) {
  let missing = 0;
  let wrong = 0;
  for (const key of Object.keys(en)) {
    const text = reviewed[key] ?? draft[key];
    if (text === undefined) { missing += 1; if (missing <= 10) fail(`${code} has no words for ${key}`); continue; }
    if (placeholders(text) !== placeholders(en[key])) {
      wrong += 1;
      fail(`${code} ${key} has {${placeholders(text)}} where English has {${placeholders(en[key])}}`);
    }
  }
  if (missing > 10) fail(`… and ${missing - 10} more keys ${code} has no words for`);
  if (!missing && !wrong) ok(`${code}: all ${Object.keys(en).length} keys, placeholders matching`);
  const stale = Object.keys(draft).filter((key) => en[key] === undefined);
  if (stale.length) fail(`${code} drafts keys English does not have: ${stale.slice(0, 5).join(", ")}`);
  else ok(`${code}: no stale drafts`);
  const doubled = Object.keys(draft).filter((key) => reviewed[key] !== undefined);
  if (doubled.length) fail(`${code} drafts keys i18n.js already translates: ${doubled.slice(0, 5).join(", ")}`);
  else ok(`${code}: no draft shadows a written translation`);
}

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
      const parts = (base + match[1]).split("/");
      const resolved = [];
      for (const part of parts) {
        if (part === "." || part === "") continue;
        if (part === "..") resolved.pop(); else resolved.push(part);
      }
      const path = resolved.join("/");
      if (!path.startsWith("vendor/")) queue.push(path);
    }
  }
  return [...seen].sort();
}

/**
 * String literals outside comments, with their line. Template literals keep
 * their text and drop ${…}. Regular expression literals are skipped when they
 * follow something that cannot end an expression.
 */
function literals(source) {
  const out = [];
  let i = 0;
  let line = 1;
  let last = "";
  while (i < source.length) {
    const c = source[i];
    if (c === "\n") { line += 1; i += 1; continue; }
    if (source.startsWith("//", i)) { const j = source.indexOf("\n", i); i = j < 0 ? source.length : j; continue; }
    if (source.startsWith("/*", i)) {
      const j = source.indexOf("*/", i + 2);
      line += (source.slice(i, j).match(/\n/g) || []).length;
      i = j + 2; continue;
    }
    if (c === "/" && /[(,=:[!&|?{};]|^$|return$/.test(last)) {
      let j = i + 1;
      let inClass = false;
      while (j < source.length && source[j] !== "\n") {
        if (source[j] === "\\") { j += 2; continue; }
        if (source[j] === "[") inClass = true;
        else if (source[j] === "]") inClass = false;
        else if (source[j] === "/" && !inClass) break;
        j += 1;
      }
      i = j + 1; last = "x"; continue;
    }
    if (c === '"' || c === "'" || c === "`") {
      const startLine = line;
      let j = i + 1;
      let text = "";
      while (j < source.length && source[j] !== c) {
        if (source[j] === "\\") { text += source.slice(j, j + 2); j += 2; continue; }
        if (c === "`" && source.startsWith("${", j)) {
          let depth = 1;
          j += 2;
          while (j < source.length && depth) {
            if (source[j] === "{") depth += 1;
            else if (source[j] === "}") depth -= 1;
            j += 1;
          }
          text += "{}";
          continue;
        }
        if (source[j] === "\n") line += 1;
        text += source[j];
        j += 1;
      }
      out.push({ line: startLine, text });
      i = j + 1; last = "x"; continue;
    }
    if (!/\s/.test(c)) {
      const word = /^[A-Za-z_$][\w$]*/.exec(source.slice(i, i + 40));
      if (word) { last = word[0] === "return" ? "return" : "x"; i += word[0].length; continue; }
      last = c;
    }
    i += 1;
  }
  return out;
}

/** Reads like words a person would see: two words of letters, or a capitalised one. */
function readsLikeWords(text) {
  const plain = text.replace(/\{\}/g, " ").trim();
  if (plain.length < 2) return false;
  if (/^(https?:|mailto:|tel:|data:|\/|#\/|\.\/|<svg|<path|<rect|<circle|<ellipse|M\d|var\(|rgba?\(|[a-z-]+:\s)/.test(plain)) return false;
  // Selectors, keys, attribute names, media types, CSS: no spaces between letters, or code-shaped.
  if (/^[\w.#[\]="':>*+,~()-]+$/.test(plain) && !/^[A-Z][a-z]{2,}$/.test(plain)) return false;
  if (/^[a-z]+(\.[a-zA-Z0-9_]+)+$/.test(plain)) return false;
  // A selector list, or a property name built from parts ("{}Enabled").
  if (/^[.#[][\w-]/.test(plain) || /^\{\}[A-Z]\w*$/.test(text)) return false;
  // A class list: every token a hyphenated lowercase name ("page-title sr-only").
  if (/^[a-z][a-z0-9]*(-[a-z0-9]+)+(\s+[a-z][a-z0-9]*(-[a-z0-9]+)+)*$/.test(plain)) return false;
  return /[A-Za-z]{2,}/.test(plain) && (/[a-z]{2,}\s+[a-z]{2,}/i.test(plain) || /^[A-Z][a-z]{2,}/.test(plain));
}

/**
 * What stays English on purpose, by file and exact text. Keep this short: each
 * entry is a reason a Telugu reader will meet English.
 */
const STAYS = {
  // The names of browsers, systems and keys, and a name the server stores.
  "api.js": ["Bearer {}", "Firefox", "Edge", "Chrome", "Safari", "Browser", "Mac", "Windows", "Android", " on "],
  "sign-in-security.js": ["Passkey"],
  // The English number words, matching the server's line word for word (format.js, check-format.js).
  "format.js": ["One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten", "Eleven", "Twelve",
    "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen", "Twenty", "Thirty", "Forty",
    "Fifty", "Sixty", "Seventy", "Eighty", "Ninety", "Crore", "Lakh", "Thousand", "Hundred", "Zero", "Minus ",
    "Rupees {}"],
  // Matched against the server's own English sentence, not shown.
  "screens/import.js": ["Couldn't read"],
  // Keyboard key names, not words.
  "*": ["Enter", "Escape", "Tab", "Backspace", "ArrowDown", "ArrowUp", "ArrowLeft", "ArrowRight", "Home", "End",
    "Bearer {}", "noopener noreferrer", "Content-Type", "Content-Disposition", "Authorization", "Accept"],
  // Institution names are names.
  "starters.js": ["State Bank of India", "LIC of India", "India Post"],
  // Cross-client test vectors and their failure messages, run by a developer from the console (docs/12).
  "e2e.js": ["almira content key id v1", "almira recovery v1|{}", "HKDF, RFC 5869 test case 1",
    "the recovery sheet's wrapping key", "the recovery shares' wrapping key", "the content key id",
    "Recovery no longer agrees with the JVM reference:\\n", "  {}\\n    got      {}\\n    expected {}",
    "A sheet's code did not open its own copy.", "a sheet's code opens its copy", "correct horse battery staple ",
    "Locker 12, ఖజానా, Kakinada ", "derived key", "additional data", "round trip",
    "The two clients no longer agree:\\n"],
  // Programmer errors: thrown only when this file is called wrongly, never shown.
  "recovery-codes.js": ["zero has no inverse", "bad threshold", "a code carries 21 bytes"],
  // The engine's own status names, compared, not shown.
  "screens/photo-reader.js": ["recognizing text"],
  // Column headers of a file the family downloads and a registrar's format reads.
  "statement.js": ["Person", "Fund", "Folio", "Market value"],
};

/* --- 2 and 3 · the code ---------------------------------------------------- */

const files = loadedFiles();
log(`\nThe ${files.length} files the client loads`);
const usedKeys = new Map();
let hardCoded = 0;
for (const file of files) {
  if (file === "i18n.js" || file === "i18n-te.js" || file === "i18n-hi.js") continue;
  const source = readText(APP + file);
  for (const match of source.matchAll(/\bt\(\s*"([A-Za-z0-9_.]+)"/g)) usedKeys.set(match[1], file);
  for (const match of source.matchAll(/\b(?:has|categoryName)\(\s*"([A-Za-z0-9_.]+)"/g)) usedKeys.set(match[1], file);
  const allowed = new Set([...(STAYS[file] || []), ...STAYS["*"]]);
  for (const { line, text } of literals(source)) {
    if (!readsLikeWords(text) || allowed.has(text)) continue;
    // An i18n key, a t() argument built from parts, or a selector list.
    if (/^[a-zA-Z]+(\.[\w{}]+)+$/.test(text.trim())) continue;
    hardCoded += 1;
    fail(`${file}:${line} hard-coded "${text.length > 70 ? `${text.slice(0, 70)}…` : text}"`);
  }
}
if (!hardCoded) ok("no hard-coded English outside the list of what stays English on purpose");

const unknown = [...usedKeys].filter(([key]) => en[key] === undefined);
for (const [key, file] of unknown) fail(`${file} calls t("${key}"), which English does not have`);
if (!unknown.length) ok(`all ${usedKeys.size} keys called by name exist`);

/* --- 4 · figures ----------------------------------------------------------- */

log("\nFigures keep Indian grouping in every language");
let rewritten = 0;
for (const [code, reviewed, draft] of [["te", te, drafts.te], ["hi", hi, drafts.hi]]) {
  for (const key of Object.keys(en)) {
    const figures = (String(en[key]).match(/₹[\d,]+|\b\d{1,2}(,\d{2})+,\d{3}\b/g) || []).sort().join(" ");
    if (!figures) continue;
    const text = String(reviewed[key] ?? draft[key] ?? "");
    const theirs = (text.match(/₹[\d,]+|\b\d{1,2}(,\d{2})+,\d{3}\b/g) || []).sort().join(" ");
    if (theirs !== figures) { rewritten += 1; fail(`${code} ${key} writes "${theirs}" for "${figures}"`); }
  }
}
if (!rewritten) ok("every ₹ figure in English is written the same way in Telugu and Hindi");

if (failures > 0) {
  log(`\n${failures} failing`);
  throw new Error(`${failures} failing`);
}
log("\nall passing");
