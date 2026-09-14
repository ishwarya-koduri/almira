/* =============================================================================
   The web client's design tokens hold their promises (docs/25).

   Reads tokens.css and base.css as text and checks the things a reviewer
   cannot see by eye:
     · every text pair in every theme meets its contrast ratio — 4.5:1 in
       Light and Dark, 7:1 in Clear, 3:1 for a focus ring
     · the dark and Clear themes are the same whether chosen in Settings or
       taken from the device, so the two copies of each cannot drift
     · base.css writes no literal colour, no type under the 13px floor, and
       never uses faint ink as a text colour

       /System/Library/Frameworks/JavaScriptCore.framework/Versions/A/Helpers/jsc \
         scripts/check-design-tokens.js
       # or: node scripts/check-design-tokens.js
   ============================================================================= */

const log = typeof print === "function" ? print : console.log;
const read = typeof readFile === "function"
  ? readFile
  : (path) => require("fs").readFileSync(path, "utf8");

const APP = "backend/src/main/resources/static/app";
const tokensCss = read(`${APP}/tokens.css`);
const baseCss = read(`${APP}/base.css`);

let failures = 0;
function check(label, ok, detail = "") {
  if (ok) log(`  ok   ${label}`);
  else { failures += 1; log(`  FAIL ${label}${detail ? `\n         ${detail}` : ""}`); }
}

/* --- reading the themes ---------------------------------------------------- */

const stripComments = (css) => css.replace(/\/\*[\s\S]*?\*\//g, "");

/** The declarations of the first rule whose selector is exactly `selector`. */
function block(css, selector, from = 0) {
  const source = stripComments(css);
  const start = source.indexOf(`${selector} {`, from);
  if (start < 0) return null;
  const open = source.indexOf("{", start);
  const close = source.indexOf("}", open);
  const tokens = {};
  for (const [, name, value] of source.slice(open + 1, close).matchAll(/--([\w-]+)\s*:\s*([^;]+);/g)) {
    tokens[name] = value.trim();
  }
  return tokens;
}

/** The theme rule nested inside the first @media block whose query starts with `query`. */
function mediaBlock(css, query, selector) {
  const source = stripComments(css);
  const at = source.indexOf(`@media ${query}`);
  return at < 0 ? null : block(source, selector, at);
}

const light = block(tokensCss, ":root");
const dark = block(tokensCss, ':root[data-theme="dark"]');
const clear = block(tokensCss, ':root[data-theme="clear"]');
check("the light, dark and Clear themes are all declared", Boolean(light && dark && clear));

const same = (a, b) => JSON.stringify(Object.entries(a || {}).sort()) === JSON.stringify(Object.entries(b || {}).sort());
check("dark from the device matches dark chosen in Settings",
  same(mediaBlock(tokensCss, "(prefers-color-scheme: dark)", ':root:not([data-theme="light"]):not([data-theme="clear"])'), dark));
check("Clear from the device matches Clear chosen in Settings",
  same(mediaBlock(tokensCss, "(prefers-contrast: more)", ':root:not([data-theme="light"]):not([data-theme="dark"])'), clear));

/* --- contrast (WCAG 2.x relative luminance) -------------------------------- */

function resolve(theme, name, seen = 0) {
  const value = theme[name] ?? light[name];
  if (value === undefined) throw new Error(`no token --${name}`);
  const ref = /^var\(--([\w-]+)\)$/.exec(value);
  return ref && seen < 5 ? resolve(theme, ref[1], seen + 1) : value;
}

function luminance(hex) {
  const match = /^#([0-9a-f]{6})$/i.exec(hex);
  if (!match) throw new Error(`not a six-digit colour: ${hex}`);
  const channel = (offset) => {
    const c = parseInt(match[1].slice(offset, offset + 2), 16) / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(0) + 0.7152 * channel(2) + 0.0722 * channel(4);
}

function ratio(a, b) {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

// [foreground, background, what it is]
const TEXT_PAIRS = [
  ["ink", "canvas", "body text"],
  ["ink", "surface-sunken", "text on a sunken ground"],
  ["ink-muted", "canvas", "captions and notices"],
  ["ink-muted", "surface", "captions on a card"],
  ["ink-muted", "surface-sunken", "an unselected segment"],
  ["accent-ink", "accent", "the primary button's label"],
  ["accent-text", "surface", "a link"],
  ["accent-text", "accent-soft", "a selected tab or chip"],
  ["brass", "surface", "the net-worth figure"],
  ["caution", "surface", "an amount owed"],
  ["caution", "caution-soft", "an EMI-due pill"],
  ["on-strong", "ink", "a toast"],
];

const THEMES = [["light", light, 4.5], ["dark", dark, 4.5], ["clear", clear, 7]];

for (const [name, theme, minimum] of THEMES) {
  if (!theme) continue;
  for (const [fg, bg, what] of TEXT_PAIRS) {
    const value = ratio(resolve(theme, fg), resolve(theme, bg));
    check(`${name}: ${what} (--${fg} on --${bg}) ${value.toFixed(2)}:1 ≥ ${minimum}`, value >= minimum);
  }
  const focus = ratio(resolve(theme, "focus"), resolve(theme, "canvas"));
  check(`${name}: the focus ring ${focus.toFixed(2)}:1 ≥ 3`, focus >= 3);
}

// The two figures the plan names, so a change to either is a conscious one.
check("light: gold stays decoration — under 3:1, so it is never text",
  ratio(light.gold, light.surface) < 3);
check("light: the accent is 8:1 on white", ratio(light.accent, light.surface) >= 7.95);
check("Clear: a block's border is visible (3:1 or better)",
  ratio(resolve(clear, "block-border"), resolve(clear, "surface")) >= 3);

/* --- base.css uses tokens, not values --------------------------------------- */

const rules = stripComments(baseCss).replace(/url\("[^"]*"\)/g, "url()");

const literals = [...rules.matchAll(/#[0-9a-f]{3,8}\b|\brgba?\(|\bhsla?\(/gi)].map((m) => m[0]);
check("base.css has no literal colour", literals.length === 0, literals.join(", "));

const small = [...rules.matchAll(/font-size\s*:\s*([\d.]+)(px|rem)/g)]
  .filter(([, n, unit]) => (unit === "px" ? Number(n) : Number(n) * 16) < 13)
  .map((m) => m[0]);
check("base.css sets no type under 13px", small.length === 0, small.join(", "));

const sizes = Object.entries(light)
  .filter(([name]) => name.startsWith("text-"))
  .filter(([, value]) => Number.parseFloat(value) * 16 < 13);
check("no type token is under 13px", sizes.length === 0, sizes.map(([n]) => n).join(", "));

const faintText = [...rules.matchAll(/(?:^|[;{\s])color\s*:\s*var\(--ink-faint\)/g)];
check("faint ink is never a text colour", faintText.length === 0);

const durations = [...stripComments(tokensCss).matchAll(/--(fast|base|slow)\s*:\s*(\d+)ms/g)]
  .filter(([, , ms]) => Number(ms) >= 200);
check("every motion token is under 200ms", durations.length === 0, durations.map((m) => m[0]).join(", "));

if (failures > 0) {
  log(`\n${failures} failing`);
  throw new Error(`${failures} failing`);
}
log("\nall passing");
