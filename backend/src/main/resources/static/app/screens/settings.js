/* Settings — preferences, sessions, and the trash. */

import { api, auth } from "../api.js";
import { el, mount, select, field, toast, empty, rupees, formatDate, withBusy, segmented } from "../ui.js";
import { state, update } from "../state.js";
import { reload } from "../app.js";

export async function settingsScreen(host) {
  mount(host, el("div.stack", {},
    el("h1", {}, "Settings"),
    preferencesCard(),
    await trashCard(),
    await sessionsCard(),
    aboutCard(),
  ));
}

function preferencesCard() {
  const theme = localStorage.getItem("almira.theme") || "system";

  const visibility = select({
    options: [
      { value: "private", label: "Private — only I can see new entries" },
      { value: "household", label: "Shared with my household" },
    ],
    value: state.user.defaultVisibility,
  });
  visibility.addEventListener("change", async () => {
    const updated = await api.patch("/api/me", { defaultVisibility: visibility.value });
    update({ user: updated });
    toast("Default updated.");
  });

  return el("div.card.stack-3", {},
    el("h4", {}, "Preferences"),
    field({
      label: "What should new entries default to?",
      control: visibility,
      help: "Your choice wins over the household default for anything you add.",
    }),
    el("div.field", {},
      el("span", { style: { fontSize: "var(--text-sm)", fontWeight: "500" } }, "Appearance"),
      segmented(
        [{ value: "system", label: "System" }, { value: "light", label: "Light" }, { value: "dark", label: "Dark" }],
        theme,
        (value) => {
          localStorage.setItem("almira.theme", value);
          applyTheme(value);
          settingsScreen(document.querySelector("main"));
        },
      ),
      el("span.help", {}, "Dark mode has full parity — nothing is hidden in either."),
    ),
  );
}

async function trashCard() {
  const rows = await api.trash(state.household.id);
  return el("div.card.stack-3", {},
    el("div.section-title", {}, el("h4", {}, "Trash"),
      el("span.caption.muted", {}, "Deleted entries are kept until you remove them")),
    rows.length === 0
      ? el("p.caption.muted", { style: { margin: 0 } }, "Nothing in the trash.")
      : el("div.list", {}, ...rows.map((row) => el("div.list-row", { style: { cursor: "default" } },
          el("div.grow", {},
            el("div.title", {}, row.title),
            el("div.meta", {}, `${row.typeLabel} · ${rupees(row.value)}`),
          ),
          el("button.btn.btn-sm", {
            type: "button",
            onclick: async (event) => {
              await withBusy(event.currentTarget, async () => {
                await api.restore(state.household.id, row.id);
                toast("Restored.");
                await reload();
              });
            },
          }, "Restore"),
        ))),
  );
}

async function sessionsCard() {
  const sessions = await api.get("/api/auth/sessions");
  return el("div.card.stack-3", {},
    el("div.section-title", {}, el("h4", {}, "Where you're signed in")),
    el("div.list", {}, ...sessions.map((session) => el("div.list-row", { style: { cursor: "default" } },
      el("div.grow", {},
        el("div.title", {}, session.deviceName || "Unknown device"),
        el("div.meta", {}, `Last used ${formatDate(session.lastUsedAt)}`),
      ),
      el("button.btn.btn-sm.btn-danger", {
        type: "button",
        onclick: async (event) => {
          await withBusy(event.currentTarget, async () => {
            await api.del(`/api/auth/sessions/${session.id}`);
            toast("Signed out on that device.");
            settingsScreen(document.querySelector("main"));
          });
        },
      }, "Sign out"),
    ))),
    el("div.row", {},
      el("button.btn.btn-danger", {
        type: "button",
        onclick: async () => { await api.signOut(); location.reload(); },
      }, "Sign out here"),
    ),
  );
}

function aboutCard() {
  return el("div.card.stack-3", {},
    el("h4", {}, "About"),
    el("p.caption.muted", { style: { margin: 0 } },
      "Almira never moves money, never holds funds, and never stores a bank password. " +
      "It is a record — which is exactly why it can track the things transactional apps can't."),
    el("p.caption.faint", { style: { margin: 0 } },
      "Figures are informational and are not financial advice."),
  );
}

export function applyTheme(value) {
  const root = document.documentElement;
  if (value === "system") root.removeAttribute("data-theme");
  else root.setAttribute("data-theme", value);
}
