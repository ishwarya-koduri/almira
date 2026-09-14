/* =============================================================================
   Redrawing a screen in place (X-05).

   Switching language used to empty the screen and draw it again from nothing.
   Settings waits for twenty cards before it shows any of them, so on a phone
   the page went blank for as long as the slowest card took — and, with nothing
   left to scroll, jumped back to the top, away from the language card the
   person had just tapped. On a slow connection it looked like Settings had
   broken.

   Now the screen that is showing stays where it is, in the old words, while the
   new one is drawn off the page; the two swap in one step, at the same scroll
   position, with a brief fade of the words. No DOM is touched here but the two
   nodes it is given, so scripts/check-redraw.js runs it under jsc.
   ============================================================================= */

/**
 * Draws `fresh` with `draw(fresh)` while `current` stays on screen, then puts
 * `fresh` where `current` was.
 *
 * The swap happens whether drawing succeeded or failed — a failed screen says
 * so in its own box, and that box has to be seen. It does not happen when
 * `current` has left the page in the meantime: the person has gone somewhere
 * else, and a late swap would draw the old screen over the new one.
 *
 * @param current  the node on screen now
 * @param fresh    an empty node, not yet on the page
 * @param draw     (fresh) => Promise; renders into `fresh`
 * @param scroller something with scrollX, scrollY and scrollTo — the window
 * @param focused  the element with focus before the redraw; the control in the
 *                 same position in the new screen gets it after
 */
export async function drawInPlace(current, fresh, draw, scroller = null, focused = null) {
  const x = scroller ? scroller.scrollX : 0;
  const y = scroller ? scroller.scrollY : 0;
  // Where the keyboard or screen reader was: the language button just pressed.
  // Swapping the nodes would otherwise drop focus to the top of the document.
  const focusAt = indexAmong(current, focused);
  try {
    await draw(fresh);
  } finally {
    if (current && current.isConnected) {
      current.replaceWith(fresh);
      fresh.classList?.add("view-redrawn");
      if (scroller) scroller.scrollTo(x, y);
      if (focusAt >= 0) focusables(fresh)[focusAt]?.focus?.({ preventScroll: true });
    }
  }
  return fresh;
}

const FOCUSABLE = 'a[href], button, input, select, textarea, summary, [tabindex]:not([tabindex="-1"])';

function focusables(node) {
  return node && typeof node.querySelectorAll === "function" ? Array.from(node.querySelectorAll(FOCUSABLE)) : [];
}

function indexAmong(node, focused) {
  return focused ? focusables(node).indexOf(focused) : -1;
}
