/* =============================================================================
   Shell, routing and bootstrap.
   ============================================================================= */

import { api, auth, ApiError } from "./api.js";
import { el, mount, toast, segmented, sheet } from "./ui.js";
import { state, update } from "./state.js";
import { authScreen } from "./screens/auth.js";
import { onboardingScreen } from "./screens/onboarding.js";
import { homeScreen } from "./screens/home.js";
import { investmentsScreen } from "./screens/investments.js";
import { liabilitiesScreen } from "./screens/liabilities.js";
import { accountsScreen } from "./screens/accounts.js";
import { familyScreen } from "./screens/family.js";
import { settingsScreen } from "./screens/settings.js";
import { goalsScreen } from "./screens/goals.js";
import { taxScreen } from "./screens/tax.js";
import { reportsScreen } from "./screens/reports.js";
import { continuityScreen } from "./screens/continuity.js";
import { t, language } from "./i18n.js";
import { openCapture } from "./screens/capture.js";

// Labels are resolved at render time rather than here, so switching language
// redraws the navigation without a reload.
const routes = {
  home: { label: "nav.home", render: homeScreen },
  investments: { label: "nav.investments", render: investmentsScreen },
  liabilities: { label: "nav.liabilities", render: liabilitiesScreen },
  accounts: { label: "nav.accounts", render: accountsScreen },
  goals: { label: "nav.goals", render: goalsScreen },
  tax: { label: "nav.tax", render: taxScreen },
  reports: { label: "nav.reports", render: reportsScreen },
  continuity: { label: "nav.continuity", render: continuityScreen },
  family: { label: "nav.family", render: familyScreen },
  settings: { label: "nav.settings", render: settingsScreen },
};

const root = document.getElementById("root");

export function navigate(name) {
  if (location.hash !== `#/${name}`) location.hash = `#/${name}`;
  else render();
}

function currentRoute() {
  const name = location.hash.replace(/^#\//, "") || "home";
  return routes[name] ? name : "home";
}

/* -----------------------------------------------------------------------------
   Chrome
   ----------------------------------------------------------------------------- */

function topbar(active) {
  return el("header.topbar", {},
    el("div.brand", {},
      // The mark, not a letter in a rounded square. It was an "A" for as long
      // as there was no logo; there is one now, and it is the same file every
      // icon in the repository is rendered from.
      el("img.brand-mark", {
        src: "/icons/favicon.svg",
        alt: "",
        width: 22,
        height: 22,
        "aria-hidden": "true",
      }),
      t("app.name"),
    ),
    // The full rail, for screens wide enough to hold ten destinations. On a
    // phone CSS hides it and [tabbar] takes over; both are rendered so that
    // rotating or resizing never needs a redraw.
    el("nav.segmented.only-wide", { "aria-label": "Sections" },
      ...Object.entries(routes).map(([name, route]) =>
        el("button", {
          type: "button",
          "aria-pressed": name === active,
          "aria-current": name === active ? "page" : null,
          onclick: () => navigate(name),
        }, t(route.label))),
    ),
    el("button.btn.btn-primary.btn-sm.only-wide", {
      type: "button",
      onclick: () => openCapture(),
      title: "Add something",
    }, t("app.add")),
  );
}

/* -----------------------------------------------------------------------------
   The phone's navigation.

   Ten destinations in one horizontal strip is a desktop idea, and on a 375px
   screen it simply ran off the edge with no way to reach what was past it. So
   the phone gets the three places people actually live in, the thing this whole
   product exists to make easy in the middle where a thumb already rests, and
   everything else one tap away behind More.

   Both navigations are always in the DOM and CSS chooses; there is no width
   listener and no redraw on rotate.
   ----------------------------------------------------------------------------- */

const PHONE_TABS = ["home", "investments", "liabilities"];
const PHONE_MORE = ["accounts", "goals", "tax", "reports", "continuity", "family", "settings"];

// Drawn rather than borrowed: a 20px stroke set costs nothing, matches the
// hairline weight the rest of the interface uses, and takes its colour from
// the tab, so there is no icon font and no request.
const ICONS = {
  home: '<path d="M3 10.5 12 3l9 7.5"/><path d="M5.5 9.5V20h13V9.5"/>',
  investments: '<path d="M3 17.5 9 11l4 4 7.5-8"/><path d="M15.5 3H21v5.5"/>',
  liabilities: '<path d="M12 3v12"/><path d="M7.5 10.5 12 15l4.5-4.5"/><path d="M4 20h16"/>',
  more: '<circle cx="5" cy="12" r="1.4"/><circle cx="12" cy="12" r="1.4"/><circle cx="19" cy="12" r="1.4"/>',
};

const icon = (name) => el("span.tab-icon", {
  "aria-hidden": "true",
  html: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"
              stroke-linecap="round" stroke-linejoin="round">${ICONS[name]}</svg>`,
});

function tabbar(active) {
  const tab = (name) => el("button.tab", {
    type: "button",
    "aria-pressed": name === active,
    "aria-current": name === active ? "page" : null,
    onclick: () => navigate(name),
  }, icon(name), el("span.tab-label", {}, t(routes[name].label)));

  return el("nav.tabbar", { "aria-label": "Sections" },
    tab(PHONE_TABS[0]),
    tab(PHONE_TABS[1]),
    // Capture is the hero (docs/03 §3), so on a phone it is not a link in a
    // bar — it is the one raised control, in the easiest place to reach.
    el("button.tab-add", {
      type: "button",
      "aria-label": "Add something",
      onclick: () => openCapture(),
    }, el("span", { "aria-hidden": "true" }, "＋")),
    tab(PHONE_TABS[2]),
    el("button.tab", {
      type: "button",
      "aria-pressed": PHONE_MORE.includes(active),
      "aria-haspopup": "dialog",
      onclick: () => openMore(active),
    }, icon("more"), el("span.tab-label", {}, t("nav.more"))),
  );
}

function openMore(active) {
  const modal = sheet({
    title: t("nav.more"),
    body: el("div.more-grid", {},
      ...PHONE_MORE.map((name) => el("button.more-item", {
        type: "button",
        "aria-current": name === active ? "page" : null,
        onclick: () => { modal.close(); navigate(name); },
      }, t(routes[name].label))),
    ),
  });
  return modal;
}

async function render() {
  if (!auth.isSignedIn) { mount(root, authScreen(afterSignIn)); return; }

  if (!state.user) {
    try { await loadSession(); }
    catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        auth.clear(); mount(root, authScreen(afterSignIn)); return;
      }
      mount(root, el("main.narrow", {}, el("div.banner", {}, error.message)));
      return;
    }
  }

  // No household yet: onboarding is the only sensible screen, so do not show
  // navigation that leads to empty ones.
  if (!state.household) {
    mount(root, el("div.app", {}, onboardingScreen(async () => { await loadSession(); render(); })));
    return;
  }

  const name = currentRoute();
  const view = el("main", {});
  mount(root, el("div.app", {}, topbar(name), view, tabbar(name)));
  try {
    await routes[name].render(view);
  } catch (error) {
    mount(view, el("div.banner", {}, error.message || "Something went wrong."));
  }
}

async function afterSignIn() {
  await loadSession();
  render();
}

async function loadSession() {
  const [user, households] = await Promise.all([api.me(), api.households()]);
  const household = households[0] || null;
  update({ user, households, household });

  if (household) {
    const [members, taxonomy] = await Promise.all([
      api.members(household.id),
      api.taxonomy(household.id),
    ]);
    update({ members, taxonomy });
  }
}

export async function reload() {
  await loadSession();
  render();
}

/**
 * Redraw the shell without refetching anything. Changing language has to reach
 * the navigation and the header, not only the screen that offered the switch.
 */
export function redraw() { render(); }

/* -----------------------------------------------------------------------------
   Bootstrap
   ----------------------------------------------------------------------------- */

window.addEventListener("hashchange", render);

// The document says which language it is in, for screen readers and for the
// browser's own text handling.
document.documentElement.lang = language.code;

window.addEventListener("unhandledrejection", (event) => {
  const error = event.reason;
  if (error instanceof ApiError) {
    toast(error.message, { tone: "error" });
    event.preventDefault();
  }
});

// An invitation link lands here as #/invite/<token>. Accept it, then continue
// as normal — the token is single-use, so it is consumed and removed from the
// address bar rather than left sitting in history.
(async function start() {
  const invite = location.hash.match(/^#\/invite\/(.+)$/);
  if (invite && auth.isSignedIn) {
    try {
      await api.acceptInvite(invite[1]);
      toast("You've joined the household.");
    } catch (error) {
      toast(error.message, { tone: "error" });
    }
    history.replaceState(null, "", location.pathname);
  }
  render();
})();
