/* =============================================================================
   The second family member's first screen (X-80, docs/03 §1.4).

   Someone who has just accepted an invitation used to land on a Home full of
   another person's numbers, with no word about what they were looking at or
   what of theirs anyone else would see. So: "Ishwarya invited you to the
   Koduri household", then three cards —

     · what you'll see: records shared with you, with a few names as a preview
     · what stays yours: what you own that nobody else can read
     · what they'll see of yours: nothing, until you share something

   Every number is the server's, counted through the same row-level security
   that decides what each of them can open, so the cards cannot promise more
   privacy than the database keeps. The first action is optional: add one thing
   of your own, or just look around.
   ============================================================================= */

import { api } from "../api.js";
import { el, mount, skeletonRows, notice, icon } from "../ui.js";
import { state } from "../state.js";
import { t } from "../i18n.js";
import { navigate } from "../app.js";
import { openCapture } from "./capture.js";
import { helpMark } from "../glossary.js";

function kindWord(kind) { return t(`welcome.kind.${kind}`); }

function countCard(title, card, emptyLine, lineFor, extra) {
  return el("section.card.stack-2.welcome-card", { "aria-label": title },
    el("h3", {}, title),
    el("p", { style: { margin: 0 } }, card.count === 0 ? emptyLine : lineFor(card.count)),
    card.preview.length > 0 && el("ul.plain-list", {},
      ...card.preview.map((record) => el("li.row", { style: { gap: "8px" } },
        el("span.grow", {}, record.title),
        el("span.caption", {}, kindWord(record.kind))))),
    extra,
  );
}

export async function welcomeScreen(host) {
  mount(host, skeletonRows(3));
  let welcome;
  try {
    welcome = await api.welcome(state.household.id);
  } catch {
    navigate("home");
    return;
  }

  const done = async (then) => {
    await api.welcomeSeen(state.household.id).catch(() => {});
    then();
  };
  const advisor = welcome.myRole === "advisor";

  mount(host, el("div.stack", {},
    el("div.stack-2", {},
      el("span.overline", {}, t("welcome.overline")),
      el("h1", {}, welcome.ownerName
        ? t("welcome.title", { name: welcome.ownerName, household: welcome.householdName })
        : t("welcome.titleNoName", { household: welcome.householdName })),
      el("p.muted", { style: { margin: 0 } }, advisor ? t("welcome.introAdvisor") : t("welcome.intro")),
    ),

    el("div.grid.grid-2", {},
      countCard(t("welcome.see.title"), welcome.youWillSee, t("welcome.see.none"),
        (count) => t("welcome.see.some", { count })),
      countCard(
        t("welcome.yours.title"), welcome.staysYours,
        welcome.defaultVisibility === "private" ? t("welcome.yours.nonePrivate") : t("welcome.yours.noneShared"),
        (count) => t("welcome.yours.some", { count }),
        el("p.caption", { style: { margin: 0 } },
          el("span.term", {}, t("welcome.yours.adminNote"), helpMark("private"))),
      ),
      countCard(t("welcome.theirs.title"), welcome.theyWillSeeOfYours, t("welcome.theirs.none"),
        (count) => t("welcome.theirs.some", { count })),
    ),

    notice(t("welcome.changeAnyTime")),

    el("div.row.wrap", {},
      !advisor && el("button.btn.btn-primary", {
        type: "button",
        onclick: () => done(() => { navigate("home"); openCapture(); }),
      }, icon("plus"), t("welcome.addOne")),
      el("button.btn", { type: "button", onclick: () => done(() => navigate("home")) }, t("welcome.lookAround")),
    ),
  ));
  host.querySelector("h1")?.setAttribute("tabindex", "-1");
  host.querySelector("h1")?.focus();
}
