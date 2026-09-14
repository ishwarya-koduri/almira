/* =============================================================================
   What a screen reader and a thumb need from whatever is on the page (X-85).

   Runs in a browser against the live app — paste it into the console, or load
   it with the browser session a check drives — and returns the problems it
   finds on the page as it is now:

       const { audit } = await import("/checks/a11y-audit.js");   // serve.py
       audit()   // → { problems: [...], summary: "…" }

   It is the automatic half of docs/02 §9. The other half — listening to it
   with TalkBack and VoiceOver — is the manual script in that section, because
   no script can say whether a sentence makes sense read aloud.

   Rules, each one a thing a person has met:
     name      every control has an accessible name (button, link, field, summary)
     image     every image and role="img" says what it shows, or is hidden
     heading   one level-1 heading in <main>, and no level skipped on the way down
     landmark  one <main>, and every <nav> is named
     dialog    every dialog is named
     ref       aria-labelledby / aria-describedby point at ids that exist; ids are unique
     target    a control is at least 44 × 44 CSS px (inline links in a sentence excepted)
     text      no visible text below 13px
   ============================================================================= */

const CONTROLS = 'button, a[href], input:not([type="hidden"]), select, textarea, summary, [role="button"], [role="tab"], [role="checkbox"], [role="switch"]';

function visible(node) {
  if (node.closest("[hidden], [aria-hidden='true']")) return false;
  const style = getComputedStyle(node);
  if (style.display === "none" || style.visibility === "hidden") return false;
  const box = node.getBoundingClientRect();
  return box.width > 0 && box.height > 0;
}

function textOf(node) {
  let text = "";
  for (const child of node.childNodes) {
    if (child.nodeType === Node.TEXT_NODE) text += child.textContent;
    else if (child.nodeType === Node.ELEMENT_NODE) {
      if (child.getAttribute("aria-hidden") === "true" || child.hidden) continue;
      if (child.tagName === "IMG") text += child.getAttribute("alt") || "";
      else text += textOf(child);
    }
  }
  return text.replace(/\s+/g, " ").trim();
}

/** The accessible name, by the parts of the algorithm this client uses. */
export function accessibleName(node) {
  const labelledby = node.getAttribute("aria-labelledby");
  if (labelledby) {
    const text = labelledby.split(/\s+/).map((id) => document.getElementById(id)).filter(Boolean).map(textOf).join(" ").trim();
    if (text) return text;
  }
  const label = node.getAttribute("aria-label");
  if (label && label.trim()) return label.trim();
  if (/^(INPUT|SELECT|TEXTAREA)$/.test(node.tagName)) {
    if (node.id) {
      const forLabel = document.querySelector(`label[for="${CSS.escape(node.id)}"]`);
      if (forLabel && textOf(forLabel)) return textOf(forLabel);
    }
    const wrapping = node.closest("label");
    if (wrapping) {
      // The label's own words, not the value of the control inside it.
      const clone = wrapping.cloneNode(true);
      clone.querySelectorAll("input, select, textarea, .help").forEach((inner) => inner.remove());
      const text = textOf(clone);
      if (text) return text;
    }
    if (node.type === "button" || node.type === "submit") return node.value || "";
  } else {
    const text = textOf(node);
    if (text) return text;
  }
  return (node.getAttribute("title") || "").trim();
}

export function audit(root = document) {
  const problems = [];
  const add = (rule, node, detail) => problems.push({
    rule, detail,
    where: node ? `${node.tagName.toLowerCase()}${node.id ? `#${node.id}` : ""}${node.className && typeof node.className === "string" ? `.${node.className.trim().split(/\s+/).join(".")}` : ""}` : "",
    text: node ? (node.outerHTML || "").slice(0, 120) : "",
  });

  for (const node of root.querySelectorAll(CONTROLS)) {
    // A file input stays in the rule even when styled away, unless it is hidden
    // outright and a named button opens it.
    if (!visible(node) && !(node.tagName === "INPUT" && node.type === "file" && !node.hidden)) continue;
    if (node.disabled && node.tagName !== "BUTTON") continue;
    if (!accessibleName(node)) add("name", node, "no accessible name");
    if (node.tagName === "INPUT" && !node.getAttribute("aria-label") && !node.closest("label") && !node.getAttribute("aria-labelledby")
      && !(node.id && document.querySelector(`label[for="${CSS.escape(node.id)}"]`)) && node.placeholder) {
      add("name", node, "named only by its placeholder, which disappears as someone types");
    }
    const box = node.getBoundingClientRect();
    const inSentence = node.tagName === "A" && node.closest("p, li, .caption") && getComputedStyle(node).display === "inline";
    const checkbox = node.tagName === "INPUT" && /checkbox|radio/.test(node.type) && node.closest("label");
    if (visible(node) && !inSentence && !checkbox && (box.width < 44 - 0.5 || box.height < 44 - 0.5)) {
      add("target", node, `${Math.round(box.width)} × ${Math.round(box.height)} px`);
    }
  }

  for (const node of root.querySelectorAll("img")) {
    if (!node.hasAttribute("alt")) add("image", node, "img without alt");
  }
  for (const node of root.querySelectorAll('[role="img"]')) {
    if (node.closest("[aria-hidden='true']")) continue;
    if (!accessibleName(node)) add("image", node, 'role="img" without a name');
  }

  const main = document.querySelectorAll("main");
  if (main.length !== 1) add("landmark", null, `${main.length} <main> elements`);
  for (const nav of root.querySelectorAll("nav")) {
    if (!nav.getAttribute("aria-label") && !nav.getAttribute("aria-labelledby")) add("landmark", nav, "nav without a name");
  }

  const scope = document.querySelector("[role='dialog']:last-of-type") ? null : main[0];
  if (scope) {
    // A level-1 heading is an h1 nobody re-levelled, or anything given aria-level="1".
    const h1 = [...scope.querySelectorAll("h1, h2, h3, h4, h5, h6, [role='heading']")].filter((heading) =>
      !heading.closest("[role='dialog']")
      && (heading.getAttribute("aria-level") === "1" || (heading.tagName === "H1" && !heading.hasAttribute("aria-level"))));
    if (h1.length !== 1) add("heading", null, `${h1.length} level-1 headings in <main>`);
    let previous = 0;
    for (const heading of scope.querySelectorAll("h1, h2, h3, h4, h5, h6, [role='heading']")) {
      if (!visible(heading) && !heading.classList.contains("sr-only")) continue;
      const level = heading.getAttribute("aria-level") ? Number(heading.getAttribute("aria-level")) : Number(heading.tagName[1]);
      if (previous && level > previous + 1) add("heading", heading, `h${previous} then h${level}`);
      previous = level;
    }
  }

  for (const dialog of root.querySelectorAll("[role='dialog']")) {
    if (!accessibleName(dialog)) add("dialog", dialog, "dialog without a name");
  }

  const ids = new Map();
  for (const node of root.querySelectorAll("[id]")) ids.set(node.id, (ids.get(node.id) || 0) + 1);
  for (const [id, count] of ids) if (count > 1) add("ref", null, `id "${id}" used ${count} times`);
  for (const node of root.querySelectorAll("[aria-labelledby], [aria-describedby], [aria-controls]")) {
    for (const attribute of ["aria-labelledby", "aria-describedby", "aria-controls"]) {
      for (const id of (node.getAttribute(attribute) || "").split(/\s+/).filter(Boolean)) {
        if (!document.getElementById(id)) add("ref", node, `${attribute} points at missing #${id}`);
      }
    }
  }

  const walker = document.createTreeWalker(root.body || root, NodeFilter.SHOW_TEXT);
  const small = new Set();
  while (walker.nextNode()) {
    const parent = walker.currentNode.parentElement;
    if (!parent || !walker.currentNode.textContent.trim() || small.has(parent)) continue;
    if (!visible(parent) || parent.closest(".sr-only, script, style")) continue;
    if (parseFloat(getComputedStyle(parent).fontSize) < 13 - 0.01) {
      small.add(parent);
      add("text", parent, `${getComputedStyle(parent).fontSize}: "${walker.currentNode.textContent.trim().slice(0, 40)}"`);
    }
  }

  const counts = problems.reduce((out, p) => ({ ...out, [p.rule]: (out[p.rule] || 0) + 1 }), {});
  return {
    problems,
    summary: problems.length ? Object.entries(counts).map(([rule, n]) => `${rule} ${n}`).join(", ") : "no problems",
  };
}
