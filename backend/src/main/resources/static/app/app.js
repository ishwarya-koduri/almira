/* =============================================================================
   Shell, routing and bootstrap.
   ============================================================================= */

import { api, auth, ApiError } from "./api.js";
import { el, mount, toast, segmented } from "./ui.js";
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
      el("span.brand-mark", { "aria-hidden": "true" }, "A"),
      t("app.name"),
    ),
    el("nav.segmented", { "aria-label": "Sections" },
      ...Object.entries(routes).map(([name, route]) =>
        el("button", {
          type: "button",
          "aria-pressed": name === active,
          "aria-current": name === active ? "page" : null,
          onclick: () => navigate(name),
        }, t(route.label))),
    ),
    el("button.btn.btn-primary.btn-sm", {
      type: "button",
      onclick: () => openCapture(),
      title: "Add something",
    }, t("app.add")),
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
  const view = el("main", {});
  mount(root, el("div.app", {}, topbar(name), view));
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
