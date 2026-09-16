/* =============================================================================
   Without a connection: the family handbook and the people to ring (P-21).

   The page someone opens on a bad day, possibly in a hospital corridor with no
   signal. It shows the copy offline-store.js kept, says plainly how old it is,
   and offers one thing to do: try the connection again. Phone numbers are
   links, because ringing the CA is the likeliest next step and a phone can do
   that without a network.

   What is kept, how, and what it does not protect against: offline-store.js
   and docs/16 §"The offline copy".
   ============================================================================= */

import { api } from "./api.js";
import { el, mount, notice, segmented, toast } from "./ui.js";
import { t } from "./i18n.js";
import * as store from "./offline-store.js";

/* -----------------------------------------------------------------------------
   Keeping it current
   ----------------------------------------------------------------------------- */

const REFRESH_EVERY_MS = 30 * 60 * 1000;
let lastKept = 0;
let lastHousehold = null;

/**
 * Keeps a fresh copy when the setting is on. `parts` are responses a screen
 * already has (continuity.js passes its own); without them they are fetched,
 * at most once every half hour. Never throws: a failed copy is an old copy.
 */
export async function keepOfflineCopy(householdId, parts = null) {
  if (!store.isEnabled() || !store.isSupported()) return false;
  // Left the household, or never had one: nothing of theirs stays here.
  if (!householdId) { await store.wipe(); lastHousehold = null; return false; }
  // A different household replaces the copy now, not in half an hour.
  if (householdId !== lastHousehold) { lastKept = 0; lastHousehold = householdId; }
  if (!parts && Date.now() - lastKept < REFRESH_EVERY_MS) return false;
  try {
    const [handbook, contacts, trusted] = parts
      ? [parts.handbook, parts.contacts, parts.trusted]
      : await Promise.all([
          api.handbook(householdId),
          api.contacts(householdId).catch(() => []),
          api.trustedContacts(householdId).catch(() => []),
        ]);
    const kept = await store.keep({ handbook, contacts, trusted, householdId });
    if (kept) lastKept = Date.now();
    return kept;
  } catch {
    return false;
  }
}

/** The kept copy, or null. */
export function readOfflineCopy() { return store.read(); }

export function lastUpdated(savedAt) {
  const { key, count } = store.lastUpdatedKey(savedAt);
  return t(key, { count });
}

/* -----------------------------------------------------------------------------
   The page
   ----------------------------------------------------------------------------- */

const tel = (phone) => `tel:${String(phone).replace(/[^\d+]/g, "")}`;

function person(contact) {
  const detail = [contact.role || contact.kind, contact.organisation].filter(Boolean).join(" · ");
  return el("div.list-row", { style: { cursor: "default" } },
    el("div.grow", {},
      el("div.title", {}, contact.name),
      detail && el("div.meta", {}, detail),
    ),
    contact.phone && el("a.btn.btn-sm", { href: tel(contact.phone), "aria-label": t("offline.call", { name: contact.name }) },
      contact.phone),
  );
}

/**
 * The whole page, drawn from a kept copy. `onRetry` is the one primary action.
 */
export function offlineScreen(copy, { onRetry } = {}) {
  const { handbook } = copy;
  const updated = lastUpdated(copy.savedAt);
  const retry = el("button.btn.btn-primary", { type: "button", onclick: () => onRetry?.() }, t("offline.retry"));

  // The people who help, once each: the handbook's and the household's lists overlap.
  const seen = new Set();
  const helpers = [...(handbook.contacts || []), ...(copy.contacts || [])].filter((contact) => {
    const key = `${contact.name}|${contact.phone || ""}`.toLowerCase();
    if (!contact.name || seen.has(key)) return false;
    seen.add(key);
    return true;
  });
  const trusted = (copy.trusted || []).filter((contact) => !contact.theyTrustMe && contact.trustedMemberName);

  return el("div.narrow.offline", { style: { marginInline: "auto" } },
    el("div.stack", {},
      el("div.row-between.wrap", {},
        el("h2", {}, handbook.householdName || t("continuity.title")),
        el("span.chip.chip-static", { title: new Date(copy.savedAt).toLocaleString() },
          t("offline.lastUpdated", { when: updated })),
      ),
      notice(t("offline.intro"), { role: "status" }),
      el("div.row", {}, retry),

      helpers.length > 0 && el("section.card.stack-3", { "aria-labelledby": "offline-helpers" },
        el("h3#offline-helpers", {}, t("contacts.title")),
        el("div.list", {}, ...helpers.map(person)),
      ),

      trusted.length > 0 && el("section.card.stack-3", { "aria-labelledby": "offline-trusted" },
        el("h3#offline-trusted", {}, t("emergency.title")),
        ...trusted.map((contact) => el("p", { style: { margin: 0 } }, contact.trustedMemberName)),
      ),

      el("section.card.stack-3", { "aria-labelledby": "offline-holdings" },
        el("div.row-between.wrap", {},
          el("h3#offline-holdings", {}, `${t("continuity.included")} · ${handbook.entries.length}`),
          handbook.entries.length > 0 && handbook.totalIncludedFormatted && el("b", {}, handbook.totalIncludedFormatted),
        ),
        handbook.entries.length === 0
          ? el("p.caption", {}, t("offline.nothingIncluded"))
          : el("div.list", {}, ...handbook.entries.map((entry) => el("div.list-row", { style: { cursor: "default" } },
              el("div.grow", {},
                el("div.title", {}, entry.title),
                el("div.meta", {}, [
                  entry.institutionName,
                  entry.reference,
                  entry.nominees.length ? `${t("continuity.nominee")}: ${entry.nominees.join(", ")}` : t("continuity.noNominee"),
                ].filter(Boolean).join(" · ")),
                entry.howToClaim && el("details", {},
                  el("summary.caption", {}, t("offline.howToClaim")),
                  el("p.caption", { style: { whiteSpace: "pre-line" } }, entry.howToClaim)),
              ),
              el("div.amount", {}, el("b", {}, entry.valueFormatted || "–")),
            ))),
      ),

      handbook.debts.length > 0 && el("section.card.stack-3", { "aria-labelledby": "offline-debts" },
        el("h3#offline-debts", {}, t("home.owed")),
        ...handbook.debts.map((debt) => el("div.row-between", {},
          el("span", {}, `${debt.title}${debt.lender ? ` · ${debt.lender}` : ""}`),
          el("span.owed", {}, debt.outstandingFormatted || "–"),
        )),
      ),

      handbook.instruments.length > 0 && el("section.card.stack-3", { "aria-labelledby": "offline-papers" },
        el("h3#offline-papers", {}, t("offline.papers")),
        ...handbook.instruments.map((item) => el("div", {},
          el("div", {}, item.title),
          item.executors.length > 0 && el("div.caption", {}, `${t("offline.executors")}: ${item.executors.join(", ")}`),
        )),
      ),

      handbook.disclaimer && notice(handbook.disclaimer),
    ),
  );
}

/* -----------------------------------------------------------------------------
   The setting (Settings → This device)
   ----------------------------------------------------------------------------- */

export function offlineSettingsCard(householdId) {
  const card = el("div.card.stack-3", {});

  const draw = async () => {
    const on = store.isEnabled();
    const copy = on ? await store.read() : null;
    mount(card,
      el("h4", {}, t("offline.setting.title")),
      el("p.caption", { style: { margin: 0 } }, t("offline.setting.body")),
      store.isSupported()
        ? segmented([
            { value: "off", label: t("offline.setting.off") },
            { value: "on", label: t("offline.setting.on") },
          ], on ? "on" : "off", async (value) => {
            if (value === "on") {
              store.enable();
              // Kept straight away, not in half an hour.
              lastKept = 0;
              const kept = await keepOfflineCopy(householdId);
              toast(kept ? t("offline.setting.kept") : t("offline.setting.notYet"));
            } else {
              await store.disable();
              toast(t("offline.setting.removed"));
            }
            draw();
          })
        : notice(t("offline.setting.unsupported")),
      on && el("p.caption", { style: { margin: 0 } },
        copy ? t("offline.lastUpdated", { when: lastUpdated(copy.savedAt) }) : t("offline.setting.notYet")),
      notice(t("offline.setting.shared")),
    );
  };
  draw();
  return card;
}
