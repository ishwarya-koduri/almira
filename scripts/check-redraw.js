/* =============================================================================
   Switching language redraws the screen in place (X-05), without a browser.

       /System/Library/Frameworks/JavaScriptCore.framework/Versions/A/Helpers/jsc \
         -m scripts/check-redraw.js
       # or: node scripts/check-redraw.js  (as an ES module)
   ============================================================================= */

import { drawInPlace } from "../backend/src/main/resources/static/app/redraw.js";

const log = typeof print === "function" ? print : console.log;
let failures = 0;
function expect(label, actual, wanted) {
  const a = JSON.stringify(actual);
  const w = JSON.stringify(wanted);
  if (a === w) log(`  ok   ${label}`);
  else { failures += 1; log(`  FAIL ${label}\n         got    ${a}\n         wanted ${w}`); }
}

/** Just enough of a page: one slot holding a node, and a scroll position. */
function page() {
  const slot = { node: null };
  const node = (name) => {
    const n = {
      name, text: "", classes: [],
      get isConnected() { return slot.node === n; },
      replaceWith(other) { if (slot.node === n) slot.node = other; },
      classList: { add(c) { n.classes.push(c); } },
    };
    return n;
  };
  const scroller = { scrollX: 0, scrollY: 957, scrollTo(x, y) { this.scrollX = x; this.scrollY = y; } };
  return { slot, node, scroller };
}

async function run() {
  log("\nThe old screen stays until the new one is ready");
  {
    const { slot, node, scroller } = page();
    const current = node("settings, English"); current.text = "Settings";
    slot.node = current;
    const seenWhileDrawing = [];
    let finish;
    const drawing = drawInPlace(current, node("settings, Telugu"), (fresh) => new Promise((resolve) => {
      finish = () => { fresh.text = "సెట్టింగ్‌లు"; resolve(); };
    }), scroller);
    // The page as it looks while the twenty cards are still loading.
    seenWhileDrawing.push(slot.node.text);
    scroller.scrollY = 0; // what an emptied page did to the scroll position
    finish();
    await drawing;
    expect("never blank while drawing", seenWhileDrawing, ["Settings"]);
    expect("then the new words, in the same place", slot.node.text, "సెట్టింగ్‌లు");
    expect("at the scroll position it had", scroller.scrollY, 957);
    expect("with the fade marked", slot.node.classes, ["view-redrawn"]);
  }

  log("\nFocus stays on the control that was pressed");
  {
    const { slot, node } = page();
    const button = (label) => ({ label, focused: false, focus() { this.focused = true; } });
    const current = node("old"); slot.node = current;
    const oldButtons = [button("English"), button("తెలుగు"), button("हिन्दी")];
    current.querySelectorAll = () => oldButtons;
    const fresh = node("new");
    const newButtons = [button("English"), button("తెలుగు"), button("हिन्दी")];
    fresh.querySelectorAll = () => newButtons;
    await drawInPlace(current, fresh, async () => {}, null, oldButtons[1]);
    expect("the same button, in the new screen", newButtons.map((b) => b.focused), [false, true, false]);
  }

  log("\nA screen that fails still shows its failure");
  {
    const { slot, node } = page();
    const current = node("old"); slot.node = current;
    const fresh = node("new");
    let threw = false;
    try {
      await drawInPlace(current, fresh, async (host) => { host.text = "This part couldn't load"; throw new Error("x"); });
    } catch { threw = true; }
    expect("the error still reaches the caller", threw, true);
    expect("and the new node, with its message, is on the page", slot.node.text, "This part couldn't load");
  }

  log("\nLeaving before it finishes does not bring the old screen back");
  {
    const { slot, node } = page();
    const current = node("settings"); slot.node = current;
    const elsewhere = node("home"); elsewhere.text = "Home";
    await drawInPlace(current, node("settings again"), async () => { slot.node = elsewhere; });
    expect("the screen the person went to stays", slot.node.text, "Home");
  }
}

await run();
if (failures > 0) {
  log(`\n${failures} failing`);
  throw new Error(`${failures} redraw checks failed`);
}
log("\nall passing");
