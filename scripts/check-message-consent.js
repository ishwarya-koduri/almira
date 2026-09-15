/* =============================================================================
   "Want a reminder when this is due?" asks, and never answers for the person,
   without a browser (docs/23 "Asked when it helps", V125).

       /System/Library/Frameworks/JavaScriptCore.framework/Versions/A/Helpers/jsc \
         -m scripts/check-message-consent.js

   Nothing is ticked when the sheet opens and "Yes" does nothing until something
   is; a yes sends exactly the channels ticked and where it was asked; "Not now"
   records no consent and is kept for the thing it was said beside; the question
   is only asked when the server says to, once a visit per holding, and again
   for a different holding (V141); someone whose yes is from before channels is
   told why they are asked again (V142); and Home's To review card asks about
   the Still true? digest the same calm way, nothing ticked (V141).
   ============================================================================= */

import "./lib/jsc-page-globals.js";

const log = typeof print === "function" ? print : console.log;
let failures = 0;
function expect(label, actual, wanted) {
  const a = JSON.stringify(actual);
  const w = JSON.stringify(wanted);
  if (a === w) log(`  ok   ${label}`);
  else { failures += 1; log(`  FAIL ${label}\n         got    ${a}\n         wanted ${w}`); }
}

/* --- just enough of a DOM for ui.js's sheet() and el() ----------------------- */

class Node {
  constructor(tag) {
    this.tagName = tag; this.children = []; this.parent = null; this.attrs = {};
    this.style = {}; this.listeners = {}; this.className = ""; this.dataset = {};
    this.checked = false; this.disabled = false; this.textContent = ""; this.offsetWidth = 0;
    this.id = ""; this.type = "";
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
  all() { return [this, ...this.children.flatMap((c) => c.all())]; }
}
globalThis.Node = Node;
const body = new Node("body");
globalThis.document = {
  body,
  activeElement: body,
  listeners: {},
  documentElement: new Node("html"),
  createElement: (tag) => new Node(tag),
  createTextNode: (text) => Object.assign(new Node("#text"), { text }),
  querySelector: () => null,
  addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); },
  removeEventListener(type, fn) { this.listeners[type] = (this.listeners[type] || []).filter((f) => f !== fn); },
};

const apiModule = await import("../backend/src/main/resources/static/app/api.js");
const { api } = apiModule;
const i18n = await import("../backend/src/main/resources/static/app/i18n.js");
const consent = await import("../backend/src/main/resources/static/app/message-consent.js");

const calls = [];
let askAnswer = { ask: true, channels: ["email", "sms", "push"], noticeVersion: "2026-09-14" };
api.messagesAsk = async (context) => { calls.push(["ask", context ?? null]); return askAnswer; };
api.messagesNotNow = async (context) => { calls.push(["notNow", context ?? null]); return { ...askAnswer, ask: false }; };
api.changeConsent = async (purpose, given, extra) => { calls.push(["consent", purpose, given, extra]); return {}; };

const openSheets = () => body.all().filter((n) => n.attrs.role === "dialog");
const boxes = (sheet) => sheet.all().filter((n) => n.tagName === "input" && n.type === "checkbox");
const buttons = (sheet) => sheet.all().filter((n) => n.tagName === "button");
// The handler, whether set as a property or a listener. A real browser does not
// click a disabled button; calling it anyway proves the handler refuses too.
const click = async (node) => {
  if (node.onclick) await node.onclick({});
  for (const fn of node.listeners.click || []) await fn({});
};
const tick = async (box) => { box.checked = true; box.onchange(); };
const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

log("\nOnly a save with a date we remind about asks");
expect("a fixed deposit with a maturity date", consent.holdingImpliesReminder({ maturityDate: "2027-01-01", attributes: {} }), true);
expect("a policy with a premium date", consent.holdingImpliesReminder({ attributes: { premium_due_date: "2027-01-01" } }), true);
expect("a SIP day", consent.holdingImpliesReminder({ attributes: { sip_day: 5 } }), true);
expect("gold with no date", consent.holdingImpliesReminder({ attributes: { purity: "22k" } }), false);
expect("a loan with an EMI day", consent.liabilityImpliesReminder({ emiDay: 5 }), true);
expect("a loan without one", consent.liabilityImpliesReminder({ title: "Hand loan" }), false);

const fdA = { contextType: "investment", contextId: "11111111-1111-1111-1111-111111111111" };
const fdB = { contextType: "investment", contextId: "22222222-2222-2222-2222-222222222222" };

log("\nThe query names the context, and nothing when there is none");
expect("a holding", apiModule.askQuery(fdA), "?contextType=investment&contextId=11111111-1111-1111-1111-111111111111");
expect("the digest", apiModule.askQuery(consent.DIGEST_CONTEXT), "?contextType=still_true_digest");
expect("none", apiModule.askQuery(undefined), "");

log("\nThe server says not to ask: nothing opens");
askAnswer = { ask: false, channels: ["email", "sms"], noticeVersion: "2026-09-14" };
await consent.offerRemindersOutsideTheApp(fdA);
expect("no sheet", openSheets().length, 0);

log("\nAsked in context: nothing ticked, and Yes waits for a choice");
askAnswer = { ask: true, channels: ["email", "sms", "push"], noticeVersion: "2026-09-14" };
calls.length = 0;
await consent.offerRemindersOutsideTheApp(fdA);
let [sheet] = openSheets();
expect("the server was asked about this holding", calls[0], ["ask", fdA]);
expect("one sheet opened", openSheets().length, 1);
expect("no asked-again line for someone never asked", sheet.all().some((n) => "data-asking-again" in n.attrs), false);
expect("one box per channel the server offers", boxes(sheet).length, 3);
expect("none of them ticked", boxes(sheet).map((b) => b.checked), [false, false, false]);
let [notNow, yes] = buttons(sheet).slice(-2);
expect("Yes is disabled until something is ticked", yes.disabled, true);
await click(yes);
expect("and pressing it anyway records nothing", calls.filter((c) => c[0] === "consent").length, 0);

await tick(boxes(sheet)[1]);
expect("ticking SMS enables Yes", yes.disabled, false);
await click(yes);
await settle();
expect("a yes names exactly what was ticked, and where it was asked",
  calls.filter((c) => c[0] === "consent"), [["consent", "messages", true, { channels: ["sms"], askedIn: "in_context" }]]);
expect("and the sheet closes", openSheets().length, 0);

log("\nAsked once a visit per holding; a different holding is a different question (V141)");
calls.length = 0;
await consent.offerRemindersOutsideTheApp(fdA);
expect("the same holding does not ask again", [openSheets().length, calls.length], [0, 0]);
await consent.offerRemindersOutsideTheApp(fdB);
expect("a different holding asks the server, and the sheet opens", [calls.length, openSheets().length], [1, 1]);
[sheet] = openSheets();
let [notNowB] = buttons(sheet).slice(-2);
await click(notNowB);
await settle();
expect("its not now is kept for that holding", calls.filter((c) => c[0] === "notNow"), [["notNow", fdB]]);

log("\n\"Not now\" records no consent");
calls.length = 0;
consent.openMessagesConsent({ channels: ["email", "sms"], askedIn: "in_context", context: fdA });
[sheet] = openSheets();
[notNow, yes] = buttons(sheet).slice(-2);
await tick(boxes(sheet)[0]);
await click(notNow);
await settle();
expect("only the not-now is sent, even with a box ticked", calls, [["notNow", fdA]]);
expect("and the sheet closes", openSheets().length, 0);

log("\nFrom Settings, closing is only closing");
calls.length = 0;
consent.openMessagesConsent({ channels: ["email"], askedIn: "settings" });
[sheet] = openSheets();
[notNow] = buttons(sheet).slice(-2);
await click(notNow);
await settle();
expect("nothing is recorded", calls.length, 0);

log("\nAsked again, and told why (V142)");
consent.openMessagesConsent({ channels: ["email", "sms"], askedIn: "settings", askingAgain: true });
[sheet] = openSheets();
expect("the sheet says the choices changed", sheet.all().some((n) => "data-asking-again" in n.attrs), true);
expect("and still ticks nothing", boxes(sheet).map((b) => b.checked), [false, false]);
await click(buttons(sheet).slice(-2)[0]);
await settle();

log("\nHome asks about the digest the same calm way (V141)");
expect("no answer from the server, no ask", consent.digestAsk(null), null);
calls.length = 0;
const answered = [];
let block = consent.digestAsk({ ask: true, channels: ["email", "push"], noticeVersion: "2026-09-14" }, { onAnswered: (yes) => answered.push(yes) });
body.append(block);
let [choose, notNowDigest] = block.all().filter((n) => n.tagName === "button");
await click(choose);
[sheet] = openSheets();
expect("Choose how opens the sheet", openSheets().length, 1);
expect("worded for the digest", sheet.all().some((n) => n.text === i18n.t("messagesConsent.digest.title") || n.textContent === i18n.t("messagesConsent.digest.title")), true);
expect("nothing ticked", boxes(sheet).map((b) => b.checked), [false, false]);
let [, yesDigest] = buttons(sheet).slice(-2);
expect("Yes waits for a choice", yesDigest.disabled, true);
await tick(boxes(sheet)[1]);
await click(yesDigest);
await settle();
expect("a yes names what was ticked", calls.filter((c) => c[0] === "consent"),
  [["consent", "messages", true, { channels: ["push"], askedIn: "in_context" }]]);
expect("and the ask leaves the card", [block.isConnected, answered], [false, [true]]);

calls.length = 0;
block = consent.digestAsk({ ask: true, channels: ["email"], noticeVersion: "2026-09-14" });
body.append(block);
[, notNowDigest] = block.all().filter((n) => n.tagName === "button");
await click(notNowDigest);
await settle();
expect("Not now beside the digest is kept for the digest, and records no consent", calls, [["notNow", { contextType: "still_true_digest" }]]);
expect("and the ask leaves the card", block.isConnected, false);

if (failures) { log(`\n${failures} failed`); throw new Error(`${failures} check(s) failed`); }
log("\nall passing");
