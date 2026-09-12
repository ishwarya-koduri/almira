/* Settings — preferences, sessions, and the trash. */

import { api, auth } from "../api.js";
import {
  el, mount, sheet, select, field, textInput, toast, empty, rupees, formatDate, withBusy, segmented,
} from "../ui.js";
import { state, update } from "../state.js";
import { reload, redraw } from "../app.js";
import { t, language, LANGUAGES } from "../i18n.js";
import { e2e, enable as enableE2e, unlock as unlockE2e } from "../e2e.js";

export async function settingsScreen(host) {
  // Settings is a stack of independent things, and it used to be an
  // all-or-nothing render: one endpoint returning 404 replaced the entire
  // screen with "We couldn't find that", including the parts that were fine.
  // Each card now fails on its own and says so in its own box.
  const cards = await Promise.all([
    safely(() => preferencesCard()),
    safely(() => languageCard(host)),
    safely(() => securityCard(host)),
    safely(() => sharingCard(host)),
    safely(() => ratesCard(host)),
    safely(() => connectCard(host)),
    safely(() => trashCard()),
    safely(() => sessionsCard()),
    safely(() => aboutCard()),
  ]);

  mount(host, el("div.stack", {}, el("h1", {}, t("nav.settings")), ...cards));
}

async function safely(render) {
  try {
    return await render();
  } catch (error) {
    return el("div.card", {},
      el("p.caption.muted", {}, `This part couldn't load: ${error.message}`));
  }
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
    const updated = await api.patch("/api/v1/me", { defaultVisibility: visibility.value });
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

/* -----------------------------------------------------------------------------
   Language. The app's own words change; the figures do not — ₹1,76,875 is how
   the amount is written here whatever language surrounds it (docs/14).
   ----------------------------------------------------------------------------- */

function languageCard(host) {
  return el("div.card.stack-3", {},
    el("h4", {}, t("settings.language")),
    el("div.field", {},
      segmented(
        LANGUAGES.map((entry) => ({ value: entry.code, label: entry.native })),
        language.code,
        (code) => { language.set(code); redraw(); },
      ),
      el("span.help", {}, t("settings.languageHelp")),
    ),
  );
}

/* -----------------------------------------------------------------------------
   Zero-knowledge mode (docs/12)
   ----------------------------------------------------------------------------- */

async function securityCard(host) {
  const status = await api.e2eStatus(state.household.id).catch(() => null);
  if (!status) return null;

  const body = el("div.stack-3", {});
  const error = el("div.help.error", { style: { minHeight: "1.15rem" } });

  if (!status.enabled) {
    const passphrase = textInput({
      type: "password", autocomplete: "new-password", "aria-label": t("security.passphrase"),
    });
    const confirm = textInput({
      type: "password", autocomplete: "new-password", "aria-label": "Again",
    });
    const button = el("button.btn.btn-primary", { type: "button" }, t("security.enable"));

    button.onclick = () => withBusy(button, async () => {
      error.textContent = "";
      if (passphrase.value.length < 12) {
        error.textContent = "Use at least twelve characters — a sentence you'll remember.";
        return;
      }
      if (passphrase.value !== confirm.value) {
        error.textContent = "Those don't match.";
        return;
      }
      try {
        await enableE2e(state.household.id, passphrase.value);
        toast("Set up. Nothing is sealed yet.");
        await settingsScreen(host);
      } catch (apiError) {
        error.textContent = apiError.message;
      }
    });

    body.append(
      field({
        label: t("security.passphrase"),
        control: passphrase,
        // Said out loud because neither client trims, deliberately: a space
        // belongs to the passphrase, and silently removing it in one client
        // and not the other is the same unrecoverable failure as a mismatched
        // Unicode form. Warning is honest; "helping" is not.
        help: "Spaces count, including one at the end. There is no way to recover this.",
      }),
      field({ label: "Type it again", control: confirm }),
      el("div.banner", {}, t("security.noRecovery")),
      error,
      el("div.row", {}, button),
    );
  } else if (e2e.isUnlocked) {
    const lock = el("button.btn.btn-sm", { type: "button" }, t("security.lock"));
    lock.onclick = () => { e2e.lock(); settingsScreen(host); };
    body.append(
      el("div.row-between", {},
        el("span", {}, t("security.unlocked")),
        lock,
      ),
      el("p.caption.muted", {},
        `${status.sealedFieldCount} sealed ${status.sealedFieldCount === 1 ? "field" : "fields"}.`),
    );
  } else {
    const passphrase = textInput({
      type: "password", autocomplete: "current-password", "aria-label": t("security.passphrase"),
    });
    const button = el("button.btn.btn-primary", { type: "button" }, t("security.unlock"));
    button.onclick = () => withBusy(button, async () => {
      error.textContent = "";
      try {
        await unlockE2e(state.household.id, passphrase.value);
        toast(t("security.unlocked"));
        await settingsScreen(host);
      } catch (apiError) {
        error.textContent = apiError.message;
      }
    });
    body.append(
      el("div.row-between", {}, el("span", {}, t("security.locked")), el("span.caption.muted", {},
        `${status.sealedFieldCount} sealed`)),
      field({ label: t("security.passphrase"), control: passphrase }),
      error,
      el("div.row", {}, button),
    );
  }

  return el("div.card.stack-3", {},
    el("h4", {}, t("security.title")),
    el("p.caption.muted", {}, t("security.explain")),
    body,
    el("details", {},
      el("summary.caption", {}, "What this costs"),
      el("div.stack-2", { style: { paddingTop: "8px" } },
        ...(status.caveats || []).map((caveat) => el("p.caption.muted", {}, caveat)),
      ),
    ),
  );
}

/* -----------------------------------------------------------------------------
   Guest links (docs/05 §7)
   ----------------------------------------------------------------------------- */

async function sharingCard(host) {
  const shares = await api.shares(state.household.id).catch(() => []);

  return el("div.card.stack-3", {},
    el("div.row-between.wrap", {},
      el("h4", {}, t("sharing.title")),
      el("button.btn.btn-sm", { type: "button", onclick: () => newShare(host) }, t("sharing.create")),
    ),
    shares.length === 0
      ? el("p.caption.muted", {}, t("sharing.empty"))
      : el("div.stack-2", {}, ...shares.map((share) => {
          const revoke = el("button.btn.btn-sm.btn-danger", { type: "button" }, t("sharing.revoke"));
          revoke.onclick = () => withBusy(revoke, async () => {
            await api.revokeShare(state.household.id, share.id);
            toast("Withdrawn. The link stops working immediately.");
            await settingsScreen(host);
          });
          return el("div.row-between.wrap", {},
            el("div", {},
              el("div", {}, share.label),
              el("span.caption.muted", {},
                `${share.scope} · ${share.itemCount} records · ` +
                `${t("sharing.expires")} ${formatDate(share.expiresAt)} · ` +
                `${t("sharing.views")} ${share.viewCount}${share.revokedAt ? " · withdrawn" : ""}`),
            ),
            !share.revokedAt && revoke,
          );
        })),
  );
}

function newShare(host) {
  const label = textInput({ placeholder: "Tax pack for Ramesh", "aria-label": "Label" });
  const scope = select({
    options: [
      { value: "tax_pack", label: "This year's tax pack" },
      { value: "handbook", label: "The family handbook" },
    ],
    "aria-label": "What to share",
  });
  const days = select({
    options: [
      { value: "1", label: "1 day" }, { value: "7", label: "7 days" },
      { value: "30", label: "30 days" }, { value: "90", label: "90 days" },
    ],
    value: "7",
    "aria-label": "For how long",
  });
  const error = el("div.help.error", { style: { minHeight: "1.15rem" } });
  const create = el("button.btn.btn-primary.grow", { type: "button" }, t("sharing.create"));
  const result = el("div.stack-2", {});

  create.onclick = () => withBusy(create, async () => {
    error.textContent = "";
    try {
      const share = await api.createShare(state.household.id, {
        label: label.value.trim() || "Shared",
        scope: scope.value,
        expiresInDays: Number(days.value),
      });
      // Shown once, and only here: the server keeps a hash, so this link cannot
      // be recovered later — it can only be withdrawn and replaced.
      mount(result,
        // What is actually inside, before it is sent: the family handbook
        // deliberately includes what is private to its owner.
        share.scopeNote && el("div.banner", {}, share.scopeNote),
        el("div.banner", {}, "Copy this now — it isn't shown again."),
        el("code", { style: { wordBreak: "break-all", fontSize: "var(--text-caption)" } }, share.url),
        el("button.btn.btn-sm", {
          type: "button",
          onclick: async () => {
            try {
              await navigator.clipboard.writeText(share.url);
              toast(t("sharing.copied"));
            } catch { toast("Select the link and copy it."); }
          },
        }, "Copy"),
      );
      create.disabled = true;
    } catch (apiError) {
      error.textContent = apiError.message;
    }
  });

  const modal = sheet({
    title: t("sharing.create"),
    body: el("div.stack-3", {},
      el("p.caption.muted", {},
        "A link shows one slice, read-only, until it expires — and you can withdraw it at " +
        "any time. It can never show more than you can see yourself."),
      field({ label: "What is it for?", control: label }),
      field({ label: "What to share", control: scope }),
      field({ label: "For how long", control: days }),
      error,
      result,
    ),
    footer: [create],
    onClose: () => settingsScreen(host),
  });
}

/* -----------------------------------------------------------------------------
   Rates and connected services
   ----------------------------------------------------------------------------- */

async function ratesCard(host) {
  const rates = await api.rates(state.household.id).catch(() => []);
  if (rates.length === 0) return null;

  return el("div.card.stack-3", {},
    el("h4", {}, t("money.rates")),
    el("p.caption.muted", {},
      "Holdings are kept in the currency they're in. These are only used to show a total."),
    el("div.stack-2", {}, ...rates.slice(0, 8).map((rate) => el("div.row-between", {},
      el("span", {}, `1 ${rate.base} = ${rate.rate} ${rate.quote}`),
      el("span.caption.muted", {}, `${rate.source} · ${t("money.asOf")} ${formatDate(rate.asOf)}`),
    ))),
  );
}

async function connectCard(host) {
  const providers = await api.providers(state.household.id).catch(() => []);
  if (providers.length === 0) return null;

  return el("div.card.stack-3", {},
    el("h4", {}, t("connect.title")),
    ...providers.map((provider) => el("details", {},
      el("summary", {},
        `${provider.label} — ${
          provider.mode === "LIVE" ? t("connect.live")
            : provider.mode === "SANDBOX" ? t("connect.sandbox") : t("connect.off")}`),
      el("div.stack-2", { style: { paddingTop: "8px" } },
        provider.sandboxNote && el("p.caption.muted", {}, provider.sandboxNote),
        el("span.overline", {}, t("connect.toGoLive")),
        ...provider.toGoLive.map((step) => el("p.caption.muted", {}, `· ${step}`)),
      ),
    )),
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
  const sessions = await api.get("/api/v1/auth/sessions");
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
            await api.del(`/api/v1/auth/sessions/${session.id}`);
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
