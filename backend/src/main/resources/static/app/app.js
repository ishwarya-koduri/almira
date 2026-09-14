/* =============================================================================
   Shell, routing and bootstrap.
   ============================================================================= */

import { api, auth, ApiError } from "./api.js";
import { el, mount, toast, icon, notice } from "./ui.js";
import { apply as applyPrefs, savingData } from "./prefs.js";
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
import { whereScreen } from "./where.js";

// Labels are resolved at render time rather than here, so switching language
// redraws the navigation without a reload. Every route that ever existed is
// still here and still answers at its own address; what changed is how they
// are grouped for someone finding their way (X-37).
const routes = {
  home: { label: "nav.home", render: homeScreen },
  investments: { label: "nav.investments", render: investmentsScreen },
  liabilities: { label: "nav.liabilities", render: liabilitiesScreen },
  accounts: { label: "nav.accounts", render: accountsScreen },
  goals: { label: "nav.goals", render: goalsScreen },
  tax: { label: "nav.tax", render: taxScreen },
  reports: { label: "nav.reports", render: reportsScreen },
  // Inside Family plan this is its overview; "For my family" beside "Family"
  // was the look-alike pair that made people guess.
  continuity: { label: "nav.overview", render: continuityScreen },
  where: { label: "nav.where", render: whereScreen },
  family: { label: "nav.people", render: familyScreen },
  settings: { label: "nav.settings", render: settingsScreen },
};

/**
 * Five destinations, the same on a phone's tabs and a tablet's or desktop's
 * rail. Each opens its first route; the others are one tap away in the row of
 * sections at the top of the screen. The destination's own name also works as
 * an address (#/holdings), so a link can point at a place, not a screen.
 */
export const DESTINATIONS = [
  { name: "home", label: "nav.home", icon: "home", routes: ["home"] },
  { name: "holdings", label: "nav.holdings", icon: "holdings", routes: ["investments", "liabilities", "accounts"] },
  // Almira's clearest edge, so a main tab of its own with an almirah for an
  // icon, and never a "More" away (X-34).
  { name: "plan", label: "nav.plan", icon: "almirah", routes: ["continuity", "where", "goals"] },
  { name: "reports", label: "nav.reports", icon: "reports", routes: ["reports", "tax"] },
  { name: "you", label: "nav.you", icon: "you", routes: ["settings", "family"] },
];

const root = document.getElementById("root");

export function navigate(name) {
  if (location.hash !== `#/${name}`) location.hash = `#/${name}`;
  else render();
}

function currentRoute() {
  const name = location.hash.replace(/^#\//, "") || "home";
  if (routes[name]) return name;
  const destination = DESTINATIONS.find((d) => d.name === name);
  return destination ? destination.routes[0] : "home";
}

const destinationOf = (route) =>
  DESTINATIONS.find((d) => d.routes.includes(route)) || DESTINATIONS[0];

/* -----------------------------------------------------------------------------
   Chrome (D-02, X-02, X-37)

   One navigation element. Under 600px CSS lays it out as bottom tabs with the
   + raised above them; from 600px it is a rail of icons with labels and the +
   at its top; from 1100px the rail is wide enough to set labels beside the
   icons. It used to be ten tabs across the top, which pushed Add off the edge
   of an 800px screen and made the page scroll sideways.
   ----------------------------------------------------------------------------- */

function topbar() {
  // Only a phone shows this: the rail carries the name everywhere else.
  return el("header.topbar", {}, brand());
}

function brand() {
  return el("a.brand", { href: "#/home" },
    // The mark, not a letter in a rounded square — the same file every icon
    // in the repository is rendered from.
    el("img.brand-mark", { src: "/icons/favicon.svg", alt: "", width: 24, height: 24 }),
    el("span.brand-name", {}, t("app.name")),
  );
}

function navigation(active) {
  const current = destinationOf(active);
  return el("nav.nav", { "aria-label": t("nav.sections") },
    brand(),
    // Capture is the hero (docs/03 §3): one teal +, in the same place on
    // every screen.
    el("button.nav-add", {
      type: "button",
      "aria-label": t("app.addSomething"),
      title: t("app.addSomething"),
      onclick: () => openCapture(),
    }, icon("plus"), el("span.nav-add-label", { "aria-hidden": "true" }, t("app.add").replace(/^＋\s*/, ""))),
    ...DESTINATIONS.map((destination) => el("a.tab", {
      href: `#/${destination.routes[0]}`,
      "aria-current": destination === current ? "page" : null,
    },
      icon(destination.icon, "tab-icon"),
      el("span.tab-label", {}, t(destination.label)),
    )),
  );
}

/** The sections inside a destination that has more than one. */
function sections(active) {
  const destination = destinationOf(active);
  if (destination.routes.length < 2) return null;
  return el("nav.subnav", { "aria-label": t("nav.inSection", { section: t(destination.label) }) },
    ...destination.routes.map((name) => el("a", {
      href: `#/${name}`,
      "aria-current": name === active ? "page" : null,
    }, t(routes[name].label))),
  );
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
  const view = el("div#view", {});
  const main = el("main", {},
    // X-72: say once, quietly, that the page is saving data and why a chart
    // might wait for a tap.
    savingData() && notice(t("data.lightMode"), { role: "note" }),
    sections(name),
    view);
  mount(root, el("div.app", {}, topbar(), main, navigation(name)));
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

// Theme, text size and data saver, before anything draws. index.html has
// already done the same from storage; this also catches Save-Data.
applyPrefs();

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
