/* =============================================================================
   Small DOM helpers and shared components.
   No framework: this client is a companion surface, and the launch client is
   Compose Multiplatform. Everything here maps to a composable rather than
   accumulating a second architecture to maintain.
   ============================================================================= */

import { groupIndian, relativeKey, rupees, compactRupees } from "./format.js";
import { t } from "./i18n.js";
import { savingData } from "./prefs.js";

/**
 * el("div.card", { onclick }, child, child)
 *
 * Shorthand is `tag#id.class.class` — id before classes, matching CSS selector
 * order. "form.stack#my-id" would parse the id as part of the class name.
 */
export function el(spec, props = {}, ...children) {
  const [tagAndId, ...classes] = String(spec).split(".");
  const [tag, id] = tagAndId.split("#");
  const node = document.createElement(tag || "div");
  if (id) node.id = id;
  if (classes.length) node.className = classes.join(" ");

  for (const [key, value] of Object.entries(props || {})) {
    // ARIA attributes are strings, not boolean attributes: aria-pressed="false"
    // is meaningful and must be written, and `true` has to become the literal
    // "true" rather than an empty attribute. Treating them like `disabled`
    // silently breaks every selected state and every toggle a screen reader
    // announces — which is exactly what happened here.
    if (key.startsWith("aria-")) {
      if (value !== null && value !== undefined) node.setAttribute(key, String(value));
      continue;
    }
    if (value === null || value === undefined || value === false) continue;
    if (key === "class") node.className = [node.className, value].filter(Boolean).join(" ");
    else if (key === "style" && typeof value === "object") Object.assign(node.style, value);
    else if (key.startsWith("on") && typeof value === "function") {
      node.addEventListener(key.slice(2).toLowerCase(), value);
    } else if (key === "html") node.innerHTML = value;
    // `form` and `list` exist as read-only properties on form controls, so
    // assigning them throws in module (strict) code. They have to be set as
    // attributes -- which is also the only way to associate a submit button
    // with a form it is not nested inside.
    else if (key in node && key !== "list" && key !== "form") node[key] = value;
    else node.setAttribute(key, value === true ? "" : value);
  }
  append(node, children);
  return node;
}

function append(parent, children) {
  for (const child of children.flat(Infinity)) {
    if (child === null || child === undefined || child === false) continue;
    parent.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
}

export function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); return node; }
export function mount(node, ...children) { clear(node); append(node, children); return node; }

/* -----------------------------------------------------------------------------
   Numbers and dates. The server sends the canonical formatted strings for
   anything it has already computed (totals, values) so a figure never
   disagrees with itself across surfaces. The rules live in format.js, which
   has no DOM and is checked by scripts/check-format.js.
   ----------------------------------------------------------------------------- */

export { groupIndian, rupees, compactRupees, withoutZeroRows } from "./format.js";

export function formatDate(iso) {
  if (!iso) return "—";
  return new Date(iso).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" });
}

/** "in 4 days", in the reader's language. */
export function relativeDays(iso) {
  const relative = relativeKey(iso);
  return relative ? t(relative.key, { count: relative.count }) : "";
}

/**
 * A money figure that fits where it is put (D-11): the full ₹42,00,000 where
 * there is room, ₹42 L in a tight phone list. Both are in the DOM and CSS
 * chooses; a screen reader always hears the full figure, because the short
 * one is hidden from it.
 *
 * @param formatted the server's canonical string, when it sent one
 * @param value     the raw number, for the short form
 */
export function money(formatted, value) {
  const full = formatted || rupees(value);
  const short = compactRupees(value);
  if (!short || short === full) return el("span.money", {}, full);
  return el("span.money", {},
    el("span.money-full", {}, full),
    el("span.money-short", { "aria-hidden": "true" }, short));
}

/**
 * "in 4 days", with the date itself one step away: always in the accessible
 * name and the tooltip, and shown beside it wherever there is room.
 */
export function when(iso) {
  if (!iso) return el("span.when", {}, "—");
  const relative = relativeDays(iso);
  const date = formatDate(iso);
  return el("time.when", { datetime: String(iso).slice(0, 10), title: date, "aria-label": `${relative}, ${date}` },
    el("span", { "aria-hidden": "true" }, relative),
    el("span.when-date", { "aria-hidden": "true" }, ` · ${date}`));
}

/* -----------------------------------------------------------------------------
   Icons. One line set, 24px grid, 1.6 stroke, coloured by currentColor — so
   there is no icon font and no request (docs/25 §7).
   ----------------------------------------------------------------------------- */

export const ICONS = {
  home: '<path d="M3 10.5 12 3l9 7.5"/><path d="M5.5 9.5V20h13V9.5"/>',
  holdings: '<rect x="3.5" y="6" width="17" height="13" rx="2"/><path d="M8 6V4.5h8V6"/><path d="M3.5 11.5h17"/>',
  // An almirah: two doors and their handles. Almira's own name, as a place.
  almirah: '<rect x="5" y="3" width="14" height="18" rx="1.5"/><path d="M12 3v18"/><path d="M10 11v2M14 11v2"/><path d="M6.5 21v1.2M17.5 21v1.2"/>',
  reports: '<path d="M4 20V4"/><path d="M4 20h16"/><path d="M8.5 16v-4M12.5 16V8M16.5 16v-6"/>',
  you: '<circle cx="12" cy="8.5" r="3.5"/><path d="M5 20c1.2-3.6 3.8-5.5 7-5.5s5.8 1.9 7 5.5"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  arrow: '<path d="M5 12h14"/><path d="m13 6 6 6-6 6"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5.5"/><path d="M12 7.6v.2"/>',
  alert: '<circle cx="12" cy="12" r="9"/><path d="M12 7v6"/><path d="M12 16.4v.2"/>',
  // Categories (D-10)
  gold: '<ellipse cx="12" cy="7" rx="7" ry="3"/><path d="M5 7v5c0 1.7 3.1 3 7 3s7-1.3 7-3V7"/><path d="M5 12v5c0 1.7 3.1 3 7 3s7-1.3 7-3v-5"/>',
  deposits: '<path d="M3 9.5 12 4l9 5.5"/><path d="M5 10v8M9.5 10v8M14.5 10v8M19 10v8"/><path d="M3 20.5h18"/>',
  mutual_funds: '<path d="M12 3a9 9 0 1 0 9 9h-9z"/><path d="M15 3.5A9 9 0 0 1 20.5 9H15z"/>',
  equity: '<path d="M3 17.5 9 11l4 4 7.5-8"/><path d="M15.5 7H20.5v5"/>',
  ipo: '<path d="M12 3v4M12 17v4M3 12h4M17 12h4"/><circle cx="12" cy="12" r="3"/>',
  bonds: '<path d="M7 3.5h8.5L19 7v13.5H7z"/><path d="M10 10h6M10 13.5h6M10 17h4"/>',
  retirement: '<path d="M3 12a9 9 0 0 1 18 0z"/><path d="M12 12v6.5a2 2 0 0 1-4 0"/>',
  insurance: '<path d="M12 3 4.5 6v5.5c0 4.6 3.2 8.3 7.5 9.5 4.3-1.2 7.5-4.9 7.5-9.5V6z"/>',
  real_estate: '<path d="M3 10.5 12 3l9 7.5"/><path d="M5.5 9.5V20h13V9.5"/><path d="M10 20v-5h4v5"/>',
  alternatives: '<path d="M6 4h12l3 5-9 11L3 9z"/><path d="M3 9h18"/>',
  cash: '<rect x="3" y="6.5" width="18" height="11" rx="1.5"/><circle cx="12" cy="12" r="2.5"/>',
  universal: '<rect x="4" y="4" width="16" height="16" rx="2"/><path d="M4 9.5h16"/>',
};

export function icon(name, className = "icon") {
  return el(`span.${className}`, {
    "aria-hidden": "true",
    html: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"
                stroke-linecap="round" stroke-linejoin="round" focusable="false">${ICONS[name] || ICONS.universal}</svg>`,
  });
}

/**
 * A category's line icon on a soft tint of its colour (D-10). The label is
 * always written beside it by the caller; this is recognition, not meaning.
 */
export function categoryIcon(categoryCode, color) {
  const tone = color || `var(--cat-${categoryCode}, var(--ink-muted))`;
  const node = icon(ICONS[categoryCode] ? categoryCode : "universal", "cat-icon");
  node.style.setProperty("--cat", tone);
  return node;
}

/* -----------------------------------------------------------------------------
   Notice — a quiet footnote with an information mark (X-52). Disclaimers,
   "informational only", tax notes: muted text, never a coloured box. A real
   problem is an error and says so with role="alert"; a due is rust. This is
   neither.
   ----------------------------------------------------------------------------- */

export function notice(content, { role = null, tone = "" } = {}) {
  return el(`div.notice${tone === "alert" ? ".notice-alert" : ""}`, { role },
    icon(tone === "alert" ? "alert" : "info", "notice-mark"),
    el("div.notice-body", {}, content));
}

/* -----------------------------------------------------------------------------
   Toast — 4 seconds, with Undo where something was removed (docs/02 §6.13)
   ----------------------------------------------------------------------------- */

let toastHost;

export function toast(message, { action, onAction, tone = "" } = {}) {
  if (!toastHost) {
    toastHost = el("div.toast-host", { role: "status", "aria-live": "polite" });
    document.body.append(toastHost);
  }
  const node = el(`div.toast${tone === "error" ? ".toast-error" : ""}`, {},
    el("span", {}, message),
    action && el("button", {
      type: "button",
      onclick: () => { node.remove(); onAction?.(); },
    }, action),
  );
  toastHost.append(node);
  setTimeout(() => node.remove(), action ? 7000 : 4000);
}

/* -----------------------------------------------------------------------------
   Sheet — bottom sheet on mobile, centred dialog on desktop.
   Focus is trapped and Escape closes, because a modal you cannot leave by
   keyboard is a trap in the literal sense.
   ----------------------------------------------------------------------------- */

export function sheet({ title, body, footer, onClose }) {
  const previousFocus = document.activeElement;

  const close = () => {
    scrim.remove();
    document.removeEventListener("keydown", onKey);
    document.body.style.overflow = "";
    previousFocus?.focus?.();
    onClose?.();
  };

  const onKey = (event) => {
    if (event.key === "Escape") { event.preventDefault(); close(); }
    if (event.key !== "Tab") return;
    const focusable = panel.querySelectorAll(
      'button, [href], input, select, textarea, summary, [tabindex]:not([tabindex="-1"])');
    if (!focusable.length) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
  };

  const panel = el("div.sheet", { role: "dialog", "aria-modal": "true", "aria-label": title },
    el("div.grabber"),
    el("div.sheet-head", {},
      el("h3.grow", {}, title),
      el("button.btn.btn-ghost.btn-sm", { type: "button", "aria-label": "Close", onclick: close }, "Close"),
    ),
    el("div.sheet-body", {}, body),
    footer && el("div.sheet-foot", {}, footer),
  );

  const scrim = el("div.scrim", {
    onclick: (event) => { if (event.target === scrim) close(); },
  }, panel);

  document.body.append(scrim);
  document.body.style.overflow = "hidden";
  document.addEventListener("keydown", onKey);
  panel.querySelector("input, select, textarea, button")?.focus();

  return { close, panel };
}

/* -----------------------------------------------------------------------------
   Form fields
   ----------------------------------------------------------------------------- */

export function field({ label, required, help, control, id }) {
  const node = el("label.field", { for: id },
    el("span", {}, label, required && el("span.req", {}, "*")),
    control,
    el("span.help", {}, help || ""),
  );
  node.setError = (message) => {
    node.dataset.invalid = message ? "true" : "false";
    const helpNode = node.querySelector(".help");
    helpNode.textContent = message || help || "";
    helpNode.classList.toggle("error", Boolean(message));
  };
  return node;
}

export function textInput(props = {}) { return el("input.input", { type: "text", ...props }); }

/**
 * Money field: leading ₹, Indian grouping applied as you type, and the
 * amount-in-words helper beneath. Validation happens on blur, not per
 * keystroke — being told "invalid" while still typing the second digit is
 * the classic way to make a form feel hostile (docs/02 §6.2).
 */
export function moneyInput({ onblur, ...props } = {}) {
  const input = el("input.input", {
    type: "text", inputMode: "decimal", autocomplete: "off", ...props,
  });
  const wrap = el("div.money-wrap", {}, el("span.rupee", {}, "₹"), input);

  input.addEventListener("input", () => {
    const caretAtEnd = input.selectionStart === input.value.length;
    const digits = input.value.replace(/[^\d.]/g, "");
    if (!digits) { input.value = ""; return; }
    const [whole, ...fraction] = digits.split(".");
    input.value = groupIndian(whole) + (fraction.length ? `.${fraction.join("")}` : "");
    if (caretAtEnd) input.setSelectionRange(input.value.length, input.value.length);
  });
  if (onblur) input.addEventListener("blur", onblur);

  wrap.value = () => {
    const raw = input.value.replace(/,/g, "").trim();
    return raw === "" ? null : Number(raw);
  };
  wrap.input = input;
  return wrap;
}

export function select({ options, value, ...props } = {}) {
  return el("select.select", props,
    ...options.map((option) => el("option", {
      value: option.value,
      selected: option.value === value,
    }, option.label)),
  );
}

/** Two to four mutually exclusive choices (D-09). Five or more want a picker. */
export function segmented(options, value, onChange) {
  const node = el("div.segmented", { role: "group" });
  options.forEach((option) => {
    node.append(el("button", {
      type: "button",
      "aria-pressed": option.value === value,
      onclick: () => onChange(option.value),
    }, option.label));
  });
  return node;
}

/**
 * Filters: one row that scrolls sideways rather than wrapping into two on a
 * phone (D-09). Children are .chip buttons.
 */
export function chipRow(label, ...chips) {
  return el("div.chip-row", { role: "group", "aria-label": label }, ...chips);
}

export function categoryDot(categoryCode, color) {
  return el("span.dot", {
    style: { background: color || `var(--cat-${categoryCode}, var(--ink-faint))` },
    "aria-hidden": "true",
  });
}

/* -----------------------------------------------------------------------------
   States
   ----------------------------------------------------------------------------- */

export function empty({ title, body, action }) {
  return el("div.empty", {}, el("h3", {}, title), el("p", {}, body), action);
}

export function skeletonRows(count = 3) {
  return el("div.stack-3", {}, ...Array.from({ length: count }, () =>
    el("div.skeleton", { style: { height: "56px" } })));
}

/** Runs an async action with the button locked and its width preserved. */
export async function withBusy(button, action) {
  button.style.minWidth = `${button.offsetWidth}px`;
  button.dataset.loading = "true";
  try { return await action(); }
  finally { button.dataset.loading = "false"; button.style.minWidth = ""; }
}

/* -----------------------------------------------------------------------------
   Progress ring — readiness and completeness (D-06). Teal, like every meter:
   a score is progress, not treasure, so it never borrows the hero's colour.
   ----------------------------------------------------------------------------- */

export function ring(percent, { label, size = "md" } = {}) {
  const clamped = Math.max(0, Math.min(100, Number(percent) || 0));
  const node = el(`div.ring.ring-${size}`, {
    role: "img",
    "aria-label": label || `${clamped}%`,
  }, el("span.ring-label", { "aria-hidden": "true" }, `${clamped}%`));
  // A custom property cannot be assigned through style's object form.
  node.style.setProperty("--ring", `${clamped * 3.6}deg`);
  return node;
}

/* -----------------------------------------------------------------------------
   On demand (X-72). With Data Saver on, something heavy — a chart, a
   thumbnail — waits behind a button instead of loading by itself.
   ----------------------------------------------------------------------------- */

export function onDemand(label, build) {
  if (!savingData()) return build();
  const host = el("div.on-demand", {});
  const button = el("button.btn.btn-ghost", {
    type: "button",
    onclick: () => mount(host, build()),
  }, label || t("block.showChart"));
  host.append(button);
  return host;
}

/* -----------------------------------------------------------------------------
   Charts (D-08). Plain SVG in theme colours: an area trend with a faint grid
   and one emphasised end point, and a donut whose labels sit beside it rather
   than in a legend. No red/green, and every chart carries its own summary as
   an accessible name, because a shape is not a number.
   ----------------------------------------------------------------------------- */

const SVG_NS = "http://www.w3.org/2000/svg";
function svg(tag, attrs = {}, ...children) {
  const node = document.createElementNS(SVG_NS, tag);
  for (const [key, value] of Object.entries(attrs)) node.setAttribute(key, String(value));
  children.forEach((child) => child && node.append(child));
  return node;
}

/** points: [{ label, value }] in order. summary: one sentence a screen reader hears. */
export function areaTrend(points, { summary, height = 160 } = {}) {
  const width = 600;
  const pad = 8;
  const values = points.map((p) => Number(p.value) || 0);
  const max = Math.max(...values, 1);
  const min = Math.min(...values, 0);
  const x = (i) => pad + (i * (width - pad * 2)) / Math.max(1, points.length - 1);
  const y = (v) => height - pad - ((v - min) * (height - pad * 2)) / Math.max(1, max - min);
  const line = values.map((v, i) => `${i ? "L" : "M"}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(" ");
  const area = `${line} L${x(values.length - 1).toFixed(1)},${height - pad} L${x(0).toFixed(1)},${height - pad} Z`;
  const grid = [0.25, 0.5, 0.75].map((f) =>
    svg("line", { class: "chart-grid", x1: 0, x2: width, y1: height * f, y2: height * f }));
  const last = values.length - 1;

  return el("figure.chart", {},
    svg("svg", { viewBox: `0 0 ${width} ${height}`, role: "img", "aria-label": summary || "", preserveAspectRatio: "none" },
      ...grid,
      svg("path", { class: "chart-area", d: area }),
      svg("path", { class: "chart-line", d: line }),
      last >= 0 && svg("circle", { class: "chart-end", cx: x(last), cy: y(values[last]), r: 5 }),
    ),
    points.length > 0 && el("figcaption.chart-axis", { "aria-hidden": "true" },
      el("span", {}, points[0].label), el("span", {}, points[last].label)),
  );
}

/** segments: [{ label, value, color, display }]. Labels are listed beside the ring, never in a key. */
export function donut(segments, { summary } = {}) {
  const total = segments.reduce((sum, s) => sum + (Number(s.value) || 0), 0) || 1;
  const r = 40;
  const circumference = 2 * Math.PI * r;
  let offset = 0;
  const arcs = segments.map((segment) => {
    const length = ((Number(segment.value) || 0) / total) * circumference;
    const arc = svg("circle", {
      class: "chart-arc", cx: 50, cy: 50, r,
      stroke: segment.color || "var(--accent)",
      "stroke-dasharray": `${length.toFixed(2)} ${(circumference - length).toFixed(2)}`,
      "stroke-dashoffset": (-offset).toFixed(2),
    });
    offset += length;
    return arc;
  });
  return el("figure.chart.chart-donut", {},
    svg("svg", { viewBox: "0 0 100 100", role: "img", "aria-label": summary || "" },
      svg("circle", { class: "chart-track", cx: 50, cy: 50, r }), ...arcs),
    el("ul.chart-labels", {}, ...segments.map((segment) => el("li", {},
      el("span.dot", { style: { background: segment.color || "var(--accent)" }, "aria-hidden": "true" }),
      el("span.grow", {}, segment.label),
      el("span.muted", {}, segment.display || `${Math.round(((Number(segment.value) || 0) / total) * 100)}%`)))),
  );
}
