/* =============================================================================
   Display preferences: theme, text size, data saver.

   Each is remembered per browser and shown as an attribute on <html>, which is
   all tokens.css needs to change how everything looks. index.html applies the
   same three before the first paint, so a dark or large-text reader never
   sees a flash of the default; this module is the one that changes them.

   None of this is sent to the server. How a page looks is a property of the
   device someone is holding, and a parent's larger text should not follow
   their child's sign-in on another phone.
   ============================================================================= */

const KEYS = { theme: "almira.theme", text: "almira.text", data: "almira.data" };

export const THEMES = ["system", "light", "dark", "clear"];
export const TEXT_SIZES = ["standard", "larger", "largest"];
export const DATA_MODES = ["auto", "on", "off"];

function read(key, allowed, fallback) {
  try {
    const value = localStorage.getItem(key);
    return allowed.includes(value) ? value : fallback;
  } catch { return fallback; }
}

function write(key, value) {
  try { localStorage.setItem(key, value); } catch { /* private mode: this session only */ }
}

export const prefs = {
  get theme() { return read(KEYS.theme, THEMES, "system"); },
  get text() { return read(KEYS.text, TEXT_SIZES, "standard"); },
  get data() { return read(KEYS.data, DATA_MODES, "auto"); },

  setTheme(value) { if (THEMES.includes(value)) { write(KEYS.theme, value); apply(); } },
  setText(value) { if (TEXT_SIZES.includes(value)) { write(KEYS.text, value); apply(); } },
  setData(value) { if (DATA_MODES.includes(value)) { write(KEYS.data, value); apply(); } },
};

/**
 * True when the page should save data: the person said so, or they left it on
 * Automatic and the browser reports Data Saver (the Save-Data hint, or the
 * prefers-reduced-data query where a browser has it).
 */
export function savingData() {
  const mode = prefs.data;
  if (mode === "on") return true;
  if (mode === "off") return false;
  try {
    if (navigator.connection && navigator.connection.saveData) return true;
    return window.matchMedia("(prefers-reduced-data: reduce)").matches;
  } catch { return false; }
}

export function apply() {
  const root = document.documentElement;
  const theme = prefs.theme;
  if (theme === "system") root.removeAttribute("data-theme");
  else root.setAttribute("data-theme", theme);

  const text = prefs.text;
  if (text === "standard") root.removeAttribute("data-text");
  else root.setAttribute("data-text", text);

  if (savingData()) root.setAttribute("data-save-data", "on");
  else root.removeAttribute("data-save-data");
}
