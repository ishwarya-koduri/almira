/* =============================================================================
   Words from their world, and a "?" for the hard ones (X-35, X-42, docs/03 §1.6).

   The legal words stay. "Nominee" is on every bank form a family will ever
   fill in, and replacing it with a softer word would leave them unable to match
   what we say to what the bank says. So the word is kept and explained: a small
   "?" beside it opens one paragraph, in plain words, in the reader's language.

   Each entry is a code; its words live in i18n.js as glossary.<code>.term and
   glossary.<code>.body, so the paragraph is translated with everything else.
   ============================================================================= */

import { el, sheet } from "./ui.js";
import { t } from "./i18n.js";

/** In the order the guide lists them: the words a family meets first come first. */
export const TERMS = [
  "nominee", "heir", "will", "sealed", "passphrase", "recovery_sheet",
  "private", "shared", "confirm_its_you", "folio", "demat", "maturity",
  "ppf", "epf", "nps", "locker", "succession_certificate", "transmission",
];

export function termTitle(code) { return t(`glossary.${code}.term`); }
export function termBody(code) { return t(`glossary.${code}.body`); }

/** One paragraph, in a sheet. Closing it returns focus to the "?" that opened it. */
export function explain(code) {
  sheet({
    title: termTitle(code),
    body: el("p", { "data-glossary": code }, termBody(code)),
  });
}

/**
 * The "?" itself. A 44px target around a small mark, named for a screen reader
 * as "What does nominee mean?" rather than "question mark".
 */
export function helpMark(code) {
  return el("button.help-mark", {
    type: "button",
    "aria-label": t("glossary.whatIs", { term: termTitle(code) }),
    title: t("glossary.whatIs", { term: termTitle(code) }),
    onclick: (event) => { event.preventDefault(); event.stopPropagation(); explain(code); },
  }, el("span", { "aria-hidden": "true" }, "?"));
}

/** A word with its "?" beside it: `term("nominee")`, or with the caller's own wording. */
export function term(code, text) {
  return el("span.term", {}, text ?? termTitle(code), helpMark(code));
}

/** Every term and its paragraph, for the guide. */
export function glossaryList() {
  return el("dl.glossary", {},
    ...TERMS.flatMap((code) => [
      el("dt", { id: `term-${code}` }, termTitle(code)),
      el("dd", {}, termBody(code)),
    ]));
}
