/* =============================================================================
   The almirah: "The 15 things most Indian families have" (P-10, docs/03 §1.2).

   Laid out as three shelves of five, easiest first: a savings account, a
   deposit and the gold on the top shelf; the house and the will on the bottom
   one, because those take a conversation, not twenty seconds. A ring fills as
   things go on the shelves.

   Nothing on a shelf is ticked by hand. The server counts it from the records
   this person can see, so a shelf empties again if its record is removed, and
   never fills from someone else's private one. A shelf that does not apply
   ("we have no NPS") can be skipped, and leaves the ring rather than holding it
   below 100 for ever.

   When the setup is for someone else (X-32), every line speaks about them —
   "Does Amma have a bank locker?" — and counts only their records.
   ============================================================================= */

import { api } from "./api.js";
import { el, mount, ring, categoryIcon, icon, toast } from "./ui.js";
import { state, update, helpingWhom } from "./state.js";
import { t } from "./i18n.js";
import { starterFor, openStarter } from "./starters.js";

const ICON = {
  savings_account: "cash", fixed_deposit: "deposits", gold: "gold", health_insurance: "insurance",
  life_insurance: "insurance", ppf: "retirement", epf: "retirement", mutual_funds: "mutual_funds",
  shares: "equity", small_savings: "retirement", nps: "retirement", loans: "universal",
  locker: "almirah", property: "real_estate", will: "bonds",
};

/** The first session, or null (an older server, or a failed load). Never throws. */
export async function loadFirstSession(householdId) {
  try {
    const session = await api.firstSession(householdId);
    update({ firstSession: session });
    return session;
  } catch {
    return null;
  }
}

/** "Does Amma have a bank locker?" or "A bank locker". */
function ask(code) {
  const person = helpingWhom();
  return person ? t(`shelf.${code}.askFor`, { name: person.displayName }) : t(`shelf.${code}.ask`);
}

function ringLabel(session) {
  return t("shelves.ringLabel", { done: session.done, total: session.total - session.skipped });
}

async function setSkipped(session, code, skip) {
  const skipped = new Set(session.shelves.filter((s) => s.skipped).map((s) => s.code));
  if (skip) skipped.add(code); else skipped.delete(code);
  const next = await api.updateFirstSession(state.household.id, { skippedShelves: [...skipped] });
  update({ firstSession: next });
  return next;
}

function shelfRow(shelf, session, redraw) {
  const starter = starterFor(shelf.code);
  const person = helpingWhom();
  const open = () => starter && openStarter(starter, { person, onSaved: redraw });

  const status = shelf.done
    ? el("span.shelf-state.shelf-done", {}, icon("tick", "shelf-tick"), t("shelves.onShelf"))
    : shelf.skipped
      ? el("span.shelf-state", {}, t("shelves.skipped"))
      : null;

  return el("li.shelf-item", { "data-shelf": shelf.code, "data-done": String(shelf.done) },
    el("button.shelf-open", {
      type: "button",
      disabled: shelf.done,
      "aria-label": shelf.done ? `${ask(shelf.code)}. ${t("shelves.onShelf")}` : `${ask(shelf.code)}. ${t("shelves.add")}`,
      onclick: open,
    },
      categoryIcon(ICON[shelf.code] || "universal"),
      el("span.grow.shelf-text", {},
        el("span.shelf-name", {}, ask(shelf.code)),
        !shelf.done && starter && el("span.caption", {}, t("shelves.startWith", { name: t(`starter.${starter.code}.title`) })),
      ),
      status,
    ),
    !shelf.done && el("button.btn.btn-ghost.btn-sm.shelf-skip", {
      type: "button",
      onclick: async () => {
        await setSkipped(session, shelf.code, !shelf.skipped);
        redraw();
      },
    }, shelf.skipped ? t("shelves.unskip") : t("shelves.skip")),
  );
}

/** The whole almirah: the ring and three shelves. */
export async function shelvesScreen(host) {
  const redraw = async () => {
    const session = await loadFirstSession(state.household.id);
    if (!session) { mount(host, el("p.muted", {}, t("app.somethingWrong"))); return; }
    const person = helpingWhom();
    const shelves = [session.shelves.slice(0, 5), session.shelves.slice(5, 10), session.shelves.slice(10, 15)];

    mount(host, el("div.stack", {},
      el("div.row-between.wrap.almirah-head", {},
        el("div.stack-2", { style: { minWidth: 0, flex: "1 1 20rem" } },
          el("h1", {}, person ? t("shelves.titleFor", { name: person.displayName }) : t("shelves.title")),
          el("p.muted", { style: { margin: 0 } }, t("shelves.intro")),
        ),
        ring(session.percent, { label: ringLabel(session), size: "lg" }),
      ),
      el("div.almirah", {},
        ...shelves.map((items, index) => el("section.shelf", { "aria-label": t(`shelves.shelf${index + 1}`) },
          el("h2.overline", {}, t(`shelves.shelf${index + 1}`)),
          el("ul.shelf-items", {}, ...items.map((shelf) => shelfRow(shelf, session, redraw))),
        )),
      ),
      person && session.someoneCanBeInvited && el("div.card.stack-2", {},
        el("h4", {}, t("helper.inviteTitle", { name: person.displayName })),
        el("p.caption", { style: { margin: 0 } }, t("helper.inviteBody", { name: person.displayName })),
        el("div.row", {}, el("a.btn.btn-sm", { href: "#/family" }, t("helper.inviteAction", { name: person.displayName }))),
      ),
    ));
  };
  await redraw();
}

/**
 * The Home card: the ring, how many are on the shelves, and the next three to
 * add. Gone once every shelf that applies has something on it.
 */
export function shelvesCard(session) {
  if (!session || session.percent >= 100) return null;
  const person = helpingWhom();
  const next = session.shelves.filter((s) => !s.done && !s.skipped).slice(0, 3);
  return el("div.card.stack-3", { "data-shelves": "true" },
    el("div.row", { style: { gap: "16px", alignItems: "center" } },
      ring(session.percent, { label: ringLabel(session) }),
      el("div.stack-2", { style: { minWidth: 0 } },
        el("h4", {}, person ? t("shelves.titleFor", { name: person.displayName }) : t("shelves.title")),
        el("span.caption", {}, ringLabel(session)),
      ),
    ),
    next.length > 0 && el("div.row.wrap", { style: { gap: "8px" } },
      ...next.map((shelf) => el("button.chip", {
        type: "button",
        onclick: () => {
          const starter = starterFor(shelf.code);
          if (starter) openStarter(starter, { person }).catch(() => toast(t("app.somethingWrong"), { tone: "error" }));
        },
      }, categoryIcon(ICON[shelf.code] || "universal"), t(`starter.${starterFor(shelf.code)?.code}.title`)))),
    el("div.row", {}, el("a.link-quiet", { href: "#/shelves" }, t("shelves.openAll"))),
  );
}
