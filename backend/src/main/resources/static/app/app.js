/* =============================================================================
   Shell, routing and bootstrap.
   ============================================================================= */

import { api, auth, ApiError, isUnreachable } from "./api.js";
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
import { rightsScreen } from "./screens/rights.js";
import { setOwner } from "./cache.js";
import { shelvesScreen, loadFirstSession } from "./shelves.js";
import { guideScreen } from "./screens/guide.js";
import { welcomeScreen } from "./screens/welcome.js";
import { flushQueued } from "./draft-ui.js";
import { heirScreen } from "./screens/heir.js";
import { hereScreen } from "./continuity-signals.js";
import { loadPlan, readOnlyNotice } from "./plan.js";
import { noteScreen } from "./support.js";
import { offlineScreen, readOfflineCopy, keepOfflineCopy } from "./offline.js";
import { drawInPlace } from "./redraw.js";

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
  // Reached from Settings rather than the rail: somewhere you go on purpose.
  rights: { label: "rights.title", render: rightsScreen, hidden: true },
  // The first session (docs/03 §1): reached from Home, onboarding and an
  // accepted invitation, never from the row of sections.
  shelves: { label: "shelves.title", render: shelvesScreen, hidden: true },
  welcome: { label: "welcome.overline", render: welcomeScreen, hidden: true },
  guide: { label: "guide.title", render: guideScreen, hidden: true },
};

/**
 * Five destinations, the same on a phone's tabs and a tablet's or desktop's
 * rail. Each opens its first route; the others are one tap away in the row of
 * sections at the top of the screen. The destination's own name also works as
 * an address (#/holdings), so a link can point at a place, not a screen.
 */
export const DESTINATIONS = [
  { name: "home", label: "nav.home", icon: "home", routes: ["home", "shelves", "welcome", "guide"] },
  { name: "holdings", label: "nav.holdings", icon: "holdings", routes: ["investments", "liabilities", "accounts"] },
  // Almira's clearest edge, so a main tab of its own with an almirah for an
  // icon, and never a "More" away (X-34).
  { name: "plan", label: "nav.plan", icon: "almirah", routes: ["continuity", "where", "goals"] },
  { name: "reports", label: "nav.reports", icon: "reports", routes: ["reports", "tax"] },
  // "rights" is hidden: it belongs under You (so that tab is lit) but is
  // reached from Settings, not the row of sections.
  { name: "you", label: "nav.you", icon: "you", routes: ["settings", "family", "rights"] },
];

/** Whether a screen exists here. Lets a link to a flow built elsewhere degrade to words. */
export function hasRoute(name) { return Boolean(routes[name]); }

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
  const visible = destination.routes.filter((name) => !routes[name].hidden);
  if (visible.length < 2) return null;
  return el("nav.subnav", { "aria-label": t("nav.inSection", { section: t(destination.label) }) },
    ...visible.map((name) => el("a", {
      href: `#/${name}`,
      "aria-current": name === active ? "page" : null,
    }, t(routes[name].label))),
  );
}

/**
 * @param inPlace keep the screen that is showing until its replacement is drawn
 *                (X-05) — for a redraw of the same screen, never for navigation
 */
async function render({ inPlace = false } = {}) {
  if (!auth.isSignedIn) { mount(root, authScreen(afterSignIn)); return; }

  if (!state.user) {
    try { await loadSession(); }
    catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        await auth.clear(); mount(root, authScreen(afterSignIn)); return;
      }
      if (await showOfflineCopy(error, root)) return;
      mount(root, el("main.narrow", {}, el("div.banner", {}, error.message)));
      return;
    }
  }

  // No household yet: onboarding is the only sensible screen, so do not show
  // navigation that leads to empty ones.
  if (!state.household) {
    mount(root, el("div.app", {}, onboardingScreen(async ({ openShelves } = {}) => {
      await loadSession();
      if (openShelves) navigate("shelves"); else render();
    })));
    return;
  }

  // Heir mode is a place of its own (X-40): no navigation, no notices, nothing
  // but the task in front of them and a way back out.
  const heir = location.hash.match(/^#\/heir\/([0-9a-f-]{36})$/);
  if (heir) {
    const view = el("div#view", {});
    mount(root, el("div.app.heir-shell", {}, el("main", {}, view)));
    try {
      await heirScreen(view, heir[1]);
    } catch (error) {
      mount(view, el("div.banner", {}, error.message || t("app.somethingWrong")));
    }
    return;
  }

  const name = currentRoute();
  noteScreen(name);
  const view = el("div#view", {});
  const showing = inPlace ? document.getElementById("view") : null;
  // Read before the shell is rebuilt: moving the old screen blurs whatever had focus.
  const focused = document.activeElement;
  const main = el("main", {},
    // X-72: say once, quietly, that the page is saving data and why a chart
    // might wait for a tap.
    savingData() && notice(t("data.lightMode"), { role: "note" }),
    // A plan that has ended leaves the household read-only, never locked
    // (docs/28 §2). Said once, at the top, with where to read what still works.
    readOnlyNotice(state.plan),
    sections(name),
    // The screen already showing is moved into the new shell as it is, and
    // the new one takes its place once drawn: never a blank page in between.
    showing || view);
  mount(root, el("div.app", {}, topbar(), main, navigation(name)));
  try {
    if (showing) await drawInPlace(showing, view, routes[name].render, window, focused);
    else await routes[name].render(view);
  } catch (error) {
    if (await showOfflineCopy(error, root)) return;
    mount(view, el("div.banner", {}, error.message || t("app.somethingWrong")));
  }
}

/**
 * No connection, and a copy of the handbook kept on this device (P-21): show
 * that instead of an error. Anything else is left to the caller. Coming back
 * online draws the real app again by itself.
 */
async function showOfflineCopy(error, host) {
  if (!isUnreachable(error)) return false;
  const copy = await readOfflineCopy();
  if (!copy) return false;
  mount(host, el("div.app", {}, el("main", {}, offlineScreen(copy, { onRetry: render }))));
  if (!waitingForConnection) {
    waitingForConnection = true;
    window.addEventListener("online", () => { waitingForConnection = false; render(); }, { once: true });
  }
  return true;
}

let waitingForConnection = false;

async function afterSignIn() {
  await loadSession();
  render();
}

async function loadSession() {
  const [user, households] = await Promise.all([api.me(), api.households()]);
  // The last known views belong to this person; someone else's are dropped (X-38).
  setOwner(user?.id);
  const household = households[0] || null;
  update({ user, households, household });

  if (household) {
    const [members, taxonomy, plan] = await Promise.all([
      api.members(household.id),
      api.taxonomy(household.id),
      loadPlan(household.id),
    ]);
    update({ members, taxonomy, plan });
    // Who the first session is for changes how several screens speak (X-32).
    // Loaded beside the rest, and never a reason for the app not to open.
    await loadFirstSession(household.id);
    flushQueued(() => render());
  }
  // In the background, and at most every half hour: a copy for a day without
  // a connection, when this device has been asked to keep one.
  keepOfflineCopy(household?.id || null);
}

export async function reload() {
  await loadSession();
  render();
}

/**
 * Redraw the shell without refetching anything. Changing language has to reach
 * the navigation and the header, not only the screen that offered the switch.
 */
export function redraw() { render({ inPlace: true }); }

/* -----------------------------------------------------------------------------
   Bootstrap
   ----------------------------------------------------------------------------- */

window.addEventListener("hashchange", render);

// Saves made with no network go as soon as there is one (X-83).
window.addEventListener("online", () => { if (state.household) flushQueued(() => render()); });

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
  // A one-tap "I'm here" or "still reachable" link lands as #/here/<token>
  // (docs/27 §2). It needs nobody signed in, so it is answered before sign-in.
  // The token leaves the address bar at once and lives only in this page.
  const here = location.hash.match(/^#\/here\/([A-Za-z0-9_-]{43})$/);
  if (here) {
    history.replaceState(null, "", location.pathname);
    mount(root, hereScreen(here[1]));
    return;
  }
  const invite = location.hash.match(/^#\/invite\/(.+)$/);
  if (invite && auth.isSignedIn) {
    try {
      await api.acceptInvite(invite[1]);
      // The second family member's first screen says what they will see, what
      // stays theirs, and what others will see of theirs (X-80).
      history.replaceState(null, "", `${location.pathname}#/welcome`);
    } catch (error) {
      toast(error.message, { tone: "error" });
      history.replaceState(null, "", location.pathname);
    }
  }
  render();
})();
