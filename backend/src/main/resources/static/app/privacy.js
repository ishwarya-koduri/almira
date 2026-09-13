/* =============================================================================
   The privacy notice, as the app shows it (docs/23-privacy-notice.md).

   Opened from Settings and from onboarding. The words are in i18n.js, in
   English, Telugu and Hindi, so the notice reads in the language the rest of
   the app does. docs/23 is the same notice with the reasoning behind it; when
   one changes, so does the other (scripts/check-spec.py holds them together).

   It is a draft, and says so: it has not had legal review, and the question of
   a key holder's consent is open. It never states a legal conclusion.
   ============================================================================= */

import { el, sheet } from "./ui.js";
import { t } from "./i18n.js";

const SECTIONS = [
  ["privacy.stored.title", ["privacy.stored.body"]],
  ["privacy.readable.title", ["privacy.readable.body"]],
  ["privacy.keyHolder.title", [
    "privacy.keyHolder.body",
    "privacy.keyHolder.sealed",
    "privacy.keyHolder.guidance",
    "privacy.keyHolder.pending",
  ]],
  ["privacy.never.title", ["privacy.never.body"]],
];

export function privacyNotice() {
  return el("div.stack-3", { "data-privacy-notice": "" },
    el("div.banner", {}, t("privacy.status")),
    ...SECTIONS.map(([title, paragraphs]) => el("section.stack-2", {},
      el("h4", {}, t(title)),
      ...paragraphs.map((key) => el("p", { style: { margin: 0 } }, t(key))),
    )),
  );
}

export function openPrivacyNotice() {
  return sheet({ title: t("privacy.title"), body: privacyNotice() });
}

/** The link both entry points use, so the words are the same in each. */
export function privacyLink() {
  return el("button.btn.btn-ghost.btn-sm", {
    type: "button", onclick: () => openPrivacyNotice(),
  }, t("privacy.open"));
}
