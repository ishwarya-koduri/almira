/* =============================================================================
   Headings someone can jump between (X-85).

   A screen reader lists a page's headings and moves through them by level.
   The screens here choose h3 and h4 for how they look — a card's title is an
   h4 because an h4 is the right size — so read as an outline, Settings went
   straight from its h1 to a row of h4s, and Home had no h1 at all. Changing
   every tag would change every screen's type.

   Instead the outline is corrected where it is read: each heading keeps its
   tag and its look, and gets an aria-level that says where it sits. The page
   always has one level-1 heading — the screen's own h1, or its title when a
   screen draws it as an h2, or a visually hidden one naming the screen — and
   nothing below it skips a level. A sheet is outlined on its own, under its
   title. `outline` has no DOM, so scripts/check-a11y.js runs it under jsc.
   ============================================================================= */

/**
 * Tag levels in reading order → the levels to announce. A heading sits one
 * below the nearest heading before it with a smaller tag level.
 *
 *   outline([1, 4, 4, 3, 4]) → [1, 2, 2, 2, 3]
 *
 * @param start the level the first heading takes (1 for a page, 2 in a sheet)
 */
export function outline(levels, start = 1) {
  const open = [];
  return levels.map((level) => {
    while (open.length && open[open.length - 1] >= level) open.pop();
    open.push(level);
    return open.length + start - 1;
  });
}

const HEADINGS = "h1, h2, h3, h4, h5, h6, [role='heading']";

function tagLevel(node) {
  if (node.dataset.outlineTag) return Number(node.dataset.outlineTag);
  const own = /^H(\d)$/.exec(node.tagName);
  const level = own ? Number(own[1]) : Number(node.getAttribute("aria-level")) || 2;
  node.dataset.outlineTag = String(level);
  return level;
}

function apply(nodes, start) {
  const levels = outline(nodes.map(tagLevel), start);
  nodes.forEach((node, index) => {
    const wanted = String(levels[index]);
    const own = /^H(\d)$/.exec(node.tagName);
    if (own && own[1] === wanted) node.removeAttribute("aria-level");
    else if (node.getAttribute("aria-level") !== wanted) node.setAttribute("aria-level", wanted);
  });
}

/**
 * Outlines the page under `main` and each open sheet. `title` names the screen
 * for the hidden level-1 heading when the screen draws none of its own.
 */
export function outlinePage(main, title) {
  if (!main) return;
  const inMain = (node) => !node.closest("[role='dialog'], [aria-hidden='true'], [hidden]");
  let hidden = main.querySelector(":scope > h1.page-title");
  const nodes = [...main.querySelectorAll(HEADINGS)].filter((node) => node !== hidden && inMain(node));
  const ownTitle = nodes.find((node) => tagLevel(node) === 1) || (nodes[0] && tagLevel(nodes[0]) === 2 ? nodes[0] : null);
  if (ownTitle) {
    hidden?.remove();
  } else if (title) {
    if (!hidden) {
      hidden = document.createElement("h1");
      hidden.className = "page-title sr-only";
      hidden.tabIndex = -1;
      main.prepend(hidden);
    }
    if (hidden.textContent !== title) hidden.textContent = title;
    nodes.unshift(hidden);
  }
  apply(nodes, 1);

  for (const panel of document.querySelectorAll("[role='dialog']")) {
    apply([...panel.querySelectorAll(HEADINGS)].filter((node) => !node.closest("[aria-hidden='true'], [hidden]")), 1);
  }
}

/** The heading that names the page, for focus to land on after navigating. */
export function pageTitle(main) {
  if (!main) return null;
  return [...main.querySelectorAll(HEADINGS)].find((node) =>
    !node.closest("[role='dialog']") && (node.getAttribute("aria-level") === "1" || (node.tagName === "H1" && !node.hasAttribute("aria-level"))));
}
