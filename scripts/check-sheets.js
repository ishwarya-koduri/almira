/* =============================================================================
   Stacked sheets close one at a time, without a browser.

       /System/Library/Frameworks/JavaScriptCore.framework/Versions/A/Helpers/jsc \
         -m scripts/check-sheets.js
       # or: node scripts/check-sheets.js  (as an ES module)

   A "?" explanation opened over the add-holding form, then Escape: only the
   explanation closes, the form and what was typed into it stay, and the page
   stays unscrollable until the last sheet is gone.
   ============================================================================= */

const log = typeof print === "function" ? print : console.log;
let failures = 0;
function expect(label, actual, wanted) {
  const a = JSON.stringify(actual);
  const w = JSON.stringify(wanted);
  if (a === w) log(`  ok   ${label}`);
  else { failures += 1; log(`  FAIL ${label}\n         got    ${a}\n         wanted ${w}`); }
}

/* --- just enough of a DOM for ui.js's sheet() --------------------------------- */

class Node {
  constructor(tag) {
    this.tagName = tag; this.children = []; this.parent = null; this.attrs = {};
    this.style = {}; this.listeners = {}; this.className = "";
  }
  get isConnected() { let n = this; while (n.parent) n = n.parent; return n === globalThis.document.body; }
  append(...kids) { for (const k of kids) { k.parent?.removeChild(k); k.parent = this; this.children.push(k); } }
  removeChild(k) { this.children = this.children.filter((c) => c !== k); k.parent = null; }
  remove() { this.parent?.removeChild(this); }
  setAttribute(k, v) { this.attrs[k] = String(v); }
  hasAttribute(k) { return k in this.attrs; }
  addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); }
  removeEventListener(type, fn) { this.listeners[type] = (this.listeners[type] || []).filter((f) => f !== fn); }
  querySelector() { return null; }
  querySelectorAll() { return []; }
  closest() { return null; }
  focus() { globalThis.document.activeElement = this; }
}
globalThis.Node = Node;
const body = new Node("body");
globalThis.document = {
  body,
  activeElement: body,
  listeners: {},
  createElement: (tag) => new Node(tag),
  createTextNode: (text) => Object.assign(new Node("#text"), { text }),
  querySelector: () => null,
  addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); },
  removeEventListener(type, fn) { this.listeners[type] = (this.listeners[type] || []).filter((f) => f !== fn); },
};
function press(key) {
  const event = { key, shiftKey: false, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } };
  // As a browser does: every listener registered when dispatch began is called.
  for (const fn of [...(document.listeners.keydown || [])]) fn(event);
}

const { sheet } = await import("../backend/src/main/resources/static/app/ui.js");

log("\nEscape closes only the sheet on top");
{
  const closed = [];
  const form = sheet({ title: "Add a holding", body: [], onClose: () => closed.push("form") });
  const help = sheet({ title: "What does private mean?", body: [], onClose: () => closed.push("help") });
  expect("both open", [form.panel.parent?.isConnected, help.panel.parent?.isConnected], [true, true]);
  expect("the page does not scroll under them", body.style.overflow, "hidden");

  press("Escape");
  expect("the explanation closed, the form did not", closed, ["help"]);
  expect("the form is still on the page", form.panel.isConnected, true);
  expect("the page still does not scroll under the form", body.style.overflow, "hidden");

  press("Escape");
  expect("a second Escape closes the form", closed, ["help", "form"]);
  expect("and the page scrolls again", body.style.overflow, "");
  expect("no listener is left behind", (document.listeners.keydown || []).length, 0);

  form.close();
  expect("closing twice does not report twice", closed, ["help", "form"]);
}

log("\nClosing a lower sheet by its button leaves the top one working");
{
  const closed = [];
  const lower = sheet({ title: "Papers", body: [], onClose: () => closed.push("lower") });
  const upper = sheet({ title: "Confirm it's you", body: [], onClose: () => closed.push("upper") });
  lower.close();
  expect("the page still does not scroll under the top sheet", body.style.overflow, "hidden");
  press("Escape");
  expect("Escape then closes the top sheet", closed, ["lower", "upper"]);
  expect("and the page scrolls again", body.style.overflow, "");
}

if (failures > 0) {
  log(`\n${failures} failing`);
  throw new Error(`${failures} failing`);
}
log("\nall passing");
