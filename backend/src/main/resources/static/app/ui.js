/* =============================================================================
   Small DOM helpers and shared components.
   No framework: this client is a companion surface, and the launch client is
   Compose Multiplatform. Everything here maps to a composable rather than
   accumulating a second architecture to maintain.
   ============================================================================= */

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
   Numbers. The server sends the canonical formatted strings for anything it
   has already computed (totals, values) so a figure never disagrees with itself
   across surfaces. These are for numbers the client derives locally.
   ----------------------------------------------------------------------------- */

export function groupIndian(value) {
  const n = Math.trunc(Math.abs(Number(value) || 0)).toString();
  const sign = Number(value) < 0 ? "-" : "";
  if (n.length <= 3) return sign + n;
  const last3 = n.slice(-3);
  const rest = n.slice(0, -3);
  const pairs = [];
  for (let i = rest.length; i > 0; i -= 2) pairs.unshift(rest.slice(Math.max(0, i - 2), i));
  return `${sign}${pairs.join(",")},${last3}`;
}

export const rupees = (value) =>
  value === null || value === undefined ? "—" : `₹${groupIndian(value)}`;

export function formatDate(iso) {
  if (!iso) return "—";
  return new Date(iso).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" });
}

export function relativeDays(iso) {
  if (!iso) return "";
  const days = Math.round((new Date(iso) - new Date()) / 86400000);
  if (days === 0) return "today";
  if (days === 1) return "tomorrow";
  if (days > 0) return `in ${days} days`;
  return `${Math.abs(days)} days ago`;
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
