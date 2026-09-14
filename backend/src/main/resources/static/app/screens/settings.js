/* Settings — preferences, how you sign in, sessions, and the trash. */

import { api, auth } from "../api.js";
import {
  el, mount, sheet, select, field, textInput, toast, empty, rupees, formatDate, withBusy, segmented, notice,
} from "../ui.js";
import { state, update } from "../state.js";
import { reload, redraw, navigate } from "../app.js";
import { t, language, LANGUAGES } from "../i18n.js";
import { e2e, enable as enableE2e, unlock as unlockE2e } from "../e2e.js";
import { recoveryCard, forgotPassphrase } from "../recovery.js";
import { privacyLink } from "../privacy.js";
import { prefs, THEMES, TEXT_SIZES, DATA_MODES } from "../prefs.js";
import { accountCard } from "../lifecycle.js";
import { howYouSignInCard } from "../sign-in-security.js";
import { notificationsCard, quietPromiseCard } from "../notifications.js";
import { measurementCard } from "../measurement.js";
import { helpMark } from "../glossary.js";
import { planCard } from "../plan.js";
import { supportCard } from "../support.js";
import { offlineSettingsCard } from "../offline.js";

export async function settingsScreen(host) {
  // Settings is a stack of independent things, and it used to be an
  // all-or-nothing render: one endpoint returning 404 replaced the entire
  // screen with "We couldn't find that", including the parts that were fine.
  // Each card now fails on its own and says so in its own box.
  const cards = await Promise.all([
    safely(() => preferencesCard()),
    safely(() => languageCard(host)),
    safely(() => howYouSignInCard(() => settingsScreen(host))),
    safely(() => notificationsCard()),
    safely(() => quietPromiseCard()),
    safely(() => securityCard(host)),
    safely(() => recoverySettings(host)),
    safely(() => sharingCard(host)),
    safely(() => ratesCard(host)),
    safely(() => connectCard(host)),
    safely(() => trashCard()),
    safely(() => offlineSettingsCard(state.household.id)),
    safely(() => sessionsCard()),
    safely(() => accountCard(host, () => settingsScreen(host))),
    safely(() => measurementCard()),
    safely(() => helpCard()),
    safely(() => planCard()),
    safely(() => supportCard()),
    safely(() => privacyCard()),
    safely(() => aboutCard()),
  ]);

  mount(host, el("div.stack", {}, el("h1", {}, t("nav.settings")), ...cards));
}

/** "Who else can open this?" — only once there is something to open (docs/12 §10). */
async function recoverySettings(host) {
  const status = await api.e2eStatus(state.household.id).catch(() => null);
  if (!status?.enabled) return null;
  return recoveryCard(host, () => settingsScreen(host));
}

async function safely(render) {
  try {
    return await render();
  } catch (error) {
    return el("div.card", {},
      el("p.caption.muted", {}, t("settings.cardFailed", { reason: error.message })));
  }
}

function preferencesCard() {
  const visibility = select({
    options: [
      { value: "private", label: t("settings.visibility.private") },
      { value: "household", label: t("settings.visibility.household") },
    ],
    value: state.user.defaultVisibility,
  });
  visibility.addEventListener("change", async () => {
    const updated = await api.patch("/api/v1/me", { defaultVisibility: visibility.value });
    update({ user: updated });
    toast(t("settings.visibility.updated"));
  });

  // Theme, text size and data saver belong to this device, not the account
  // (prefs.js says why), so they change on the spot with nothing to save.
  const again = () => settingsScreen(document.getElementById("view"));
  const choice = (label, prefix, options, value, onChange, help) => el("div.field", {},
    el("span", {}, label),
    segmented(options.map((option) => ({ value: option, label: t(`${prefix}.${option}`) })),
      value, (next) => { onChange(next); again(); }),
    el("span.help", {}, help),
  );

  return el("div.card.stack-3", {},
    el("h4", {}, t("prefs.title")),
    field({
      label: t("settings.visibility.label"),
      control: visibility,
      help: t("settings.visibility.help"),
    }),
    choice(t("prefs.appearance"), "prefs.theme", THEMES, prefs.theme, prefs.setTheme, t("prefs.themeHelp")),
    // D-04: for a parent whose phone is set smaller than they would like.
    choice(t("prefs.textSize"), "prefs.text", TEXT_SIZES, prefs.text, prefs.setText, t("prefs.textHelp")),
    // X-72: Automatic follows the phone's Data Saver.
    choice(t("prefs.data"), "prefs.data", DATA_MODES, prefs.data, prefs.setData, t("prefs.dataHelp")),
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
  const error = el("div.help.error", { role: "alert", style: { minHeight: "1.15rem" } });

  if (!status.enabled) {
    const passphrase = textInput({
      type: "password", autocomplete: "new-password", "aria-label": t("security.passphrase"),
    });
    const confirm = textInput({
      type: "password", autocomplete: "new-password", "aria-label": t("security.again"),
    });
    const button = el("button.btn.btn-primary", { type: "button" }, t("security.enable"));

    button.onclick = () => withBusy(button, async () => {
      error.textContent = "";
      if (passphrase.value.length < 12) {
        error.textContent = t("security.tooShort");
        return;
      }
      if (passphrase.value !== confirm.value) {
        error.textContent = t("security.mismatch");
        return;
      }
      try {
        await enableE2e(state.household.id, passphrase.value);
        toast(t("security.setUp"));
        await settingsScreen(host);
      } catch (apiError) {
        error.textContent = apiError.message;
      }
    });

    body.append(
      field({
        label: [t("security.passphrase"), helpMark("passphrase")],
        control: passphrase,
        // Said out loud because neither client trims, deliberately: a space
        // belongs to the passphrase, and silently removing it in one client
        // and not the other is the same unrecoverable failure as a mismatched
        // Unicode form. Warning is honest; "helping" is not.
        help: t("security.passphraseHelp"),
      }),
      field({ label: t("security.again"), control: confirm }),
      el("p.notice", {}, el("span.info-mark", { "aria-hidden": "true" }, "i"), t("security.recoveryNote")),
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
        t(status.sealedFieldCount === 1 ? "security.sealedOne" : "security.sealedMany", { count: status.sealedFieldCount })),
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
        t("security.sealedCount", { count: status.sealedFieldCount }))),
      field({ label: t("security.passphrase"), control: passphrase }),
      error,
      el("div.row.wrap", {}, button,
        el("button.btn.btn-ghost", {
          type: "button", onclick: () => forgotPassphrase(() => settingsScreen(host)),
        }, t("recovery.forgot.link"))),
    );
  }

  return el("div.card.stack-3", {},
    el("h4.term", {}, t("security.title"), helpMark("sealed")),
    el("p.caption.muted", {}, t("security.explain")),
    body,
    el("details", {},
      el("summary.caption", {}, t("security.costs")),
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
            toast(t("sharing.withdrawn"));
            await settingsScreen(host);
          });
          return el("div.row-between.wrap", {},
            el("div", {},
              el("div", {}, share.label),
              el("span.caption.muted", {},
                [
                  t(`sharing.scope.${share.scope}`),
                  t("sharing.records", { count: share.itemCount }),
                  `${t("sharing.expires")} ${formatDate(share.expiresAt)}`,
                  `${t("sharing.views")} ${share.viewCount}`,
                  share.revokedAt && t("sharing.isWithdrawn"),
                ].filter(Boolean).join(" · ")),
            ),
            !share.revokedAt && revoke,
          );
        })),
  );
}

function newShare(host) {
  const label = textInput({ placeholder: t("sharing.labelExample") });
  const scope = select({
    options: [
      { value: "tax_pack", label: t("sharing.scope.tax_pack") },
      { value: "handbook", label: t("sharing.scope.handbook") },
    ],
  });
  const days = select({
    options: [
      { value: "1", label: t("sharing.oneDay") }, { value: "7", label: t("sharing.days", { count: 7 }) },
      { value: "30", label: t("sharing.days", { count: 30 }) }, { value: "90", label: t("sharing.days", { count: 90 }) },
    ],
    value: "7",
  });
  const error = el("div.help.error", { role: "alert", style: { minHeight: "1.15rem" } });
  const create = el("button.btn.btn-primary.grow", { type: "button" }, t("sharing.create"));
  const result = el("div.stack-2", {});

  create.onclick = () => withBusy(create, async () => {
    error.textContent = "";
    try {
      const share = await api.createShare(state.household.id, {
        label: label.value.trim() || t("sharing.defaultLabel"),
        scope: scope.value,
        expiresInDays: Number(days.value),
      });
      // Shown once, and only here: the server keeps a hash, so this link cannot
      // be recovered later — it can only be withdrawn and replaced.
      mount(result,
        // What is actually inside, before it is sent: the family handbook
        // deliberately includes what is private to its owner.
        share.scopeNote && el("div.banner", {}, share.scopeNote),
        el("div.banner", {}, t("sharing.copyNow")),
        el("code", { style: { wordBreak: "break-all", fontSize: "var(--text-caption)" } }, share.url),
        el("button.btn.btn-sm", {
          type: "button",
          onclick: async () => {
            try {
              await navigator.clipboard.writeText(share.url);
              toast(t("sharing.copied"));
            } catch { toast(t("sharing.copyByHand")); }
          },
        }, t("sharing.copy")),
      );
      create.disabled = true;
    } catch (apiError) {
      error.textContent = apiError.message;
    }
  });

  const modal = sheet({
    title: t("sharing.create"),
    body: el("div.stack-3", {},
      el("p.caption.muted", {}, t("sharing.explain")),
      field({ label: t("sharing.whatFor"), control: label }),
      field({ label: t("sharing.whatToShare"), control: scope }),
      field({ label: t("sharing.howLong"), control: days }),
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
    el("p.caption.muted", {}, t("money.ratesExplain")),
    el("div.stack-2", {}, ...rates.slice(0, 8).map((rate) => el("div.row-between", {},
      el("span", {}, `1 ${rate.base} = ${rate.rate} ${rate.quote}`),
      el("span.caption.muted", {}, `${rate.source} · ${t("money.asOf")} ${formatDate(rate.asOf)}`),
    ))),
  );
}

async function connectCard(host) {
  // A provider the server reports as DISABLED is not offered here at all — not
  // listed, not "not set up", no steps to switch it on. That is how Account
  // Aggregator is cut from v1: by the server's configuration, not by this file,
  // so a server that turns it back on shows it again with no client change.
  const providers = (await api.providers(state.household.id).catch(() => []))
    .filter((provider) => provider.mode !== "DISABLED");
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
    el("div.section-title", {}, el("h4", {}, t("trash.title")),
      el("span.caption.muted", {}, t("trash.explain"))),
    rows.length === 0
      ? el("p.caption.muted", { style: { margin: 0 } }, t("trash.empty"))
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
                toast(t("trash.restored"));
                await reload();
              });
            },
          }, t("trash.restore")),
        ))),
  );
}

async function sessionsCard() {
  const sessions = await api.get("/api/v1/auth/sessions");
  return el("div.card.stack-3", {},
    el("div.section-title", {}, el("h4", {}, t("sessions.title"))),
    el("div.list", {}, ...sessions.map((session) => el("div.list-row", { style: { cursor: "default" } },
      el("div.grow", {},
        el("div.title", {}, session.deviceName || t("sessions.unknownDevice")),
        el("div.meta", {}, t("sessions.lastUsed", { date: formatDate(session.lastUsedAt) })),
      ),
      el("button.btn.btn-sm.btn-danger", {
        type: "button",
        onclick: async (event) => {
          await withBusy(event.currentTarget, async () => {
            await api.del(`/api/v1/auth/sessions/${session.id}`);
            toast(t("sessions.signedOutThere"));
            settingsScreen(document.getElementById("view"));
          });
        },
      }, t("sessions.signOut")),
    ))),
    el("div.row", {},
      el("button.btn.btn-danger", {
        type: "button",
        // signOut resolves once the offline copy is deleted too, so the reload
        // cannot interrupt that.
        onclick: async () => { await api.signOut(); location.reload(); },
      }, t("sessions.signOutHere")),
    ),
  );
}

/**
 * Help, and a person to ask (X-42, docs/03 §1.6). The channel is the deployment's
 * to configure (almira.support.*). Unset, the card says so rather than showing
 * an address nobody reads. A WhatsApp or email link is opened by the phone
 * itself: nothing is sent from here, and no provider is involved.
 */
async function helpCard() {
  const contact = await api.supportContact().catch(() => ({ configured: false }));
  return el("div.card.stack-3", { "data-help": "true" },
    el("h4", {}, t("help.title")),
    el("p.caption", { style: { margin: 0 } }, t("help.guideLine")),
    el("div.row", {}, el("a.btn.btn-sm", { href: "#/guide" }, t("help.openGuide"))),
    el("h4", {}, t("help.contact")),
    contact.configured
      ? el("div.stack-2", {},
          el("a.btn.btn-sm", {
            href: contact.link, target: "_blank", rel: "noopener noreferrer",
            style: { alignSelf: "flex-start" },
          }, contact.channel === "whatsapp" ? t("help.whatsapp") : t("help.email")),
          el("span.caption", {}, contact.display),
          contact.replyTime && el("span.caption", {}, t("help.replyTime", { time: contact.replyTime })),
        )
      : el("p.caption", { style: { margin: 0 } }, t("help.notSetUp")),
  );
}

function privacyCard() {
  return el("div.card.stack-3", {},
    el("h4", {}, t("privacy.title")),
    el("p.caption.muted", { style: { margin: 0 } }, t("privacy.summary")),
    el("div.row.wrap", {}, privacyLink(),
      el("button.btn.btn-ghost.btn-sm", { type: "button", onclick: () => navigate("rights") },
        t("rights.open"))),
  );
}

function aboutCard() {
  return el("div.card.stack-3", {},
    el("h4", {}, t("about.title")),
    el("p.caption.muted", { style: { margin: 0 } }, t("about.body")),
    notice(t("about.notAdvice")),
  );
}

/** Kept for callers from before prefs.js; the preference lives there now. */
export function applyTheme(value) { prefs.setTheme(value); }
