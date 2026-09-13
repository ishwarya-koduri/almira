/* =============================================================================
   Where the original is, and who holds the key (docs/20).

   "Original in the steel almirah, second shelf." "Locker at SBI Ameerpet, key
   with Amma." The most damaging sentences this household could write down, so
   both are sealed with the passphrase from docs/12 and there is no plain
   version to fall back to. The server stores two ciphertexts per record and
   cannot read either.

   Which makes search a thing this device does. The index comes down as
   ciphertext, is opened here after unlocking, and is searched in memory:

     · nothing is searched on the server, and the query never leaves the page —
       not in a request, not in the address bar, not in localStorage;
     · nothing is searched while locked;
     · every value is decrypted to search, so the cost grows with the number of
       records (AES-GCM on a few hundred short strings is milliseconds; this is
       a note for the day it is thousands, not a problem today);
     · matching is plain substring after lower-casing, with no fuzzy index —
       an index of the words would be a second copy of the words.

   The opened values live in this module's closures for as long as the screen is
   up, and are dropped when it locks or is left.
   ============================================================================= */

import { api } from "./api.js";
import { el, mount, sheet, field, textInput, withBusy, toast, skeletonRows } from "./ui.js";
import { state } from "./state.js";
import { t, language } from "./i18n.js";
import { e2e, unlock as unlockE2e, sealField, unsealField, openSealedValue } from "./e2e.js";

/** The contract with every other client, and with the server's index. */
export const FIELD = {
  originalLocation: "original_location",
  keyHolder: "key_holder",
};

const SLOTS = [
  { slot: "originalLocation", fieldKey: FIELD.originalLocation, label: "where.location" },
  { slot: "keyHolder", fieldKey: FIELD.keyHolder, label: "where.keyHolder" },
];

/* -----------------------------------------------------------------------------
   Opening
   ----------------------------------------------------------------------------- */

/**
 * One slot, opened — or the reason it isn't. A value someone else sealed is not
 * a corrupt value, and saying which is the difference between "ask Ravi" and
 * "something is broken".
 */
async function openSlot(recordType, recordId, fieldKey, value) {
  if (!value) return { state: "empty" };
  if (!value.sealedByMe) return { state: "theirs" };
  if (!e2e.isUnlocked) return { state: "locked" };
  try {
    const text = await openSealedValue(state.household.id, recordType, recordId, fieldKey, value.ciphertext);
    return { state: "open", text };
  } catch {
    return { state: "unreadable" };
  }
}

async function openRecord(record) {
  const [originalLocation, keyHolder] = await Promise.all(SLOTS.map(({ slot, fieldKey }) =>
    openSlot(record.recordType, record.recordId, fieldKey, record[slot])));
  return { ...record, opened: { originalLocation, keyHolder } };
}

function slotText(opened) {
  switch (opened.state) {
    case "open": return opened.text;
    case "empty": return null;
    case "theirs": return t("where.theirs");
    case "locked": return t("where.lockedValue");
    default: return t("where.unreadable");
  }
}

/* -----------------------------------------------------------------------------
   Unlocking, inline — the passphrase is asked for where it is needed
   ----------------------------------------------------------------------------- */

function unlockForm(onUnlocked) {
  const passphrase = textInput({
    type: "password", autocomplete: "current-password", "aria-label": t("security.passphrase"),
  });
  const error = el("div.help.error", { style: { minHeight: "1.15rem" } });
  const button = el("button.btn.btn-primary.btn-sm", { type: "button" }, t("security.unlock"));
  const submit = () => withBusy(button, async () => {
    error.textContent = "";
    try {
      await unlockE2e(state.household.id, passphrase.value);
      passphrase.value = "";
      await onUnlocked();
    } catch (problem) {
      error.textContent = problem.message;
    }
  });
  button.onclick = submit;
  passphrase.addEventListener("keydown", (event) => { if (event.key === "Enter") submit(); });
  return el("div.stack-2", {},
    field({ label: t("security.passphrase"), control: passphrase }),
    error,
    el("div.row", {}, button),
  );
}

/* -----------------------------------------------------------------------------
   The card on a record's own screen
   ----------------------------------------------------------------------------- */

/**
 * @param legacy  an unsealed "kept at" note the record already carries, if any,
 *                and how to clear it — see docs/20 §1. Shown as a warning, with
 *                a way to move it into the sealed field.
 */
export function whereWhoCard(recordType, recordId, { legacy } = {}) {
  const host = el("div.card.card-tight.stack-2", {},
    el("div.overline", {}, t("where.cardTitle")),
    el("div.skeleton", { style: { height: "40px" } }),
  );

  const draw = async () => {
    let status;
    let record;
    try {
      [status, record] = await Promise.all([
        api.e2eStatus(state.household.id),
        api.whereAndWho(state.household.id, recordType, recordId).then((index) => index.records[0]),
      ]);
    } catch {
      mount(host, el("div.overline", {}, t("where.cardTitle")),
        el("p.caption.muted", {}, t("app.somethingWrong")));
      return;
    }
    if (!record) { host.remove(); return; }
    const opened = await openRecord(record);

    const rows = SLOTS.map(({ slot, label }) => el("div.row-between", { style: { fontSize: "var(--text-sm)" } },
      el("span.muted", {}, t(label)),
      el("span", { style: { textAlign: "right" } }, slotText(opened.opened[slot]) || t("where.notRecorded")),
    ));

    const canEdit = status.enabled && e2e.isUnlocked;
    mount(host,
      el("div.overline", {}, t("where.cardTitle")),
      ...rows,
      legacy?.text && el("div.banner", {},
        el("p", {}, t("where.legacy", { text: legacy.text })),
        canEdit && legacy.clear && el("button.btn.btn-sm", {
          type: "button",
          onclick: (event) => withBusy(event.currentTarget, async () => {
            // Never over a location already sealed: that one is the person's
            // later word, and the unsealed note is the thing being retired.
            if (opened.opened.originalLocation.state === "empty") {
              await sealField(state.household.id, recordType, recordId, FIELD.originalLocation, legacy.text);
            }
            await legacy.clear();
            legacy.text = null;
            toast(t("where.legacyMoved"));
            await draw();
          }),
        }, t("where.legacyMove")),
      ),
      !status.enabled
        ? el("p.caption.muted", {}, t("where.needsPassphrase"))
        : !e2e.isUnlocked
          ? unlockForm(draw)
          : el("div.row", {},
              el("button.btn.btn-sm", {
                type: "button",
                onclick: () => openEditor(opened, draw),
              }, t("where.edit"))),
    );
  };

  draw();
  return host;
}

/* -----------------------------------------------------------------------------
   The editor — two sentences, sealed on this device before they are sent
   ----------------------------------------------------------------------------- */

async function suggestions() {
  // Names to pick from, copied as text. Nothing is linked: a link would be a
  // plaintext pointer from a sealed record to a real person (docs/20 §4).
  const contacts = await api.contacts(state.household.id).catch(() => []);
  const names = [...state.members.map((m) => m.displayName), ...contacts.map((c) => c.name)]
    .filter(Boolean);
  return [...new Set(names)];
}

export async function openEditor(record, onSaved) {
  const listId = `where-people-${record.recordId}`;
  const people = el("datalist#" + listId, {});
  suggestions().then((names) => names.forEach((name) => people.append(el("option", { value: name }))));

  const current = (slot) => (record.opened[slot].state === "open" ? record.opened[slot].text : "");
  const theirs = (slot) => record.opened[slot].state === "theirs";

  const location = el("textarea.textarea", {
    rows: 3, value: current("originalLocation"), disabled: theirs("originalLocation"),
    placeholder: t("where.locationPlaceholder"), "aria-label": t("where.location"),
  });
  const holder = el("input.input", {
    type: "text", value: current("keyHolder"), disabled: theirs("keyHolder"), list: listId,
    placeholder: t("where.keyHolderPlaceholder"), "aria-label": t("where.keyHolder"),
    autocomplete: "off",
  });
  const error = el("div.help.error", { style: { minHeight: "1.15rem" } });
  const save = el("button.btn.btn-primary.grow", { type: "button" }, t("app.save"));

  save.onclick = () => withBusy(save, async () => {
    error.textContent = "";
    try {
      for (const [slot, control] of [["originalLocation", location], ["keyHolder", holder]]) {
        if (theirs(slot)) continue;
        const { fieldKey } = SLOTS.find((s) => s.slot === slot);
        const text = control.value;
        const had = record.opened[slot].state !== "empty";
        // Kept exactly as typed — no trim — because a sealed value is never
        // normalised (docs/12 §3). Blank means "remove", which is a choice
        // about the field, not a rewrite of what somebody wrote.
        if (text.trim() === "") {
          if (had) await unsealField(state.household.id, record.recordType, record.recordId, fieldKey);
        } else if (text !== current(slot)) {
          await sealField(state.household.id, record.recordType, record.recordId, fieldKey, text);
        }
      }
      modal.close();
      toast(t("where.saved"));
      await onSaved?.();
    } catch (problem) {
      error.textContent = problem.message;
    }
  });

  const modal = sheet({
    title: record.title,
    body: el("div.stack-3", {},
      el("p.caption.muted", {}, t("where.editorIntro")),
      field({ label: t("where.location"), control: location,
        help: theirs("originalLocation") ? t("where.theirs") : "" }),
      field({ label: t("where.keyHolder"), control: holder,
        help: theirs("keyHolder") ? t("where.theirs") : t("where.keyHolderHelp") }),
      people,
      error,
    ),
    footer: [save],
  });
}

/* -----------------------------------------------------------------------------
   The screen: every record, and a search box that runs here
   ----------------------------------------------------------------------------- */

/** For matching only. The stored value is never touched (docs/12 §3). */
const fold = (text) => (text || "").normalize("NFKC").toLocaleLowerCase(language.code);

export async function whereScreen(host) {
  mount(host, skeletonRows(4));

  const [status, index] = await Promise.all([
    api.e2eStatus(state.household.id),
    api.whereAndWho(state.household.id),
  ]);

  const records = index.records;
  const withAnything = records.filter((r) => r.originalLocation || r.keyHolder);

  // Presence is the one thing the server knows too (docs/20 §6), so it is
  // shown even while locked.
  const presence = el("p.caption.muted", {},
    t("where.presence", { recorded: withAnything.length, total: records.length }));
  const header = el("div.stack-2", {},
    el("h2", {}, t("where.title")),
    el("p.muted", {}, t("where.intro")),
    presence,
  );
  const caveats = el("details", {},
    el("summary.caption", {}, t("where.caveatsTitle")),
    el("div.stack-2", { style: { paddingTop: "8px" } },
      ...(index.caveats || []).map((caveat) => el("p.caption.muted", {}, caveat))),
  );

  if (!status.enabled) {
    mount(host, el("div.stack", {}, header,
      el("div.card.stack-2", {}, el("p", {}, t("where.needsPassphrase"))), caveats));
    return;
  }

  if (!e2e.isUnlocked) {
    mount(host, el("div.stack", {}, header,
      el("div.card.stack-3", {},
        el("p", {}, t("where.unlockToSearch")),
        unlockForm(() => whereScreen(host)),
      ),
      caveats));
    return;
  }

  // Opened once per visit, held in this closure, and gone when the screen is.
  let opened = await Promise.all(records.map(openRecord));

  const search = textInput({
    type: "search", placeholder: t("where.searchPlaceholder"), "aria-label": t("where.search"),
    // Not remembered by the browser: the query is as sensitive as the answer.
    autocomplete: "off", spellcheck: false,
  });
  const onlyMissing = el("button.chip", { type: "button", "aria-pressed": "false" }, t("where.onlyMissing"));
  const results = el("div.stack-2", { "aria-live": "polite" });
  const lock = el("button.btn.btn-sm", { type: "button" }, t("security.lock"));
  lock.onclick = () => {
    e2e.lock();
    opened = null;
    whereScreen(host);
  };

  const render = () => {
    const query = fold(search.value.trim());
    const missing = onlyMissing.getAttribute("aria-pressed") === "true";
    const matches = opened.filter((record) => {
      const location = record.opened.originalLocation;
      const holder = record.opened.keyHolder;
      if (missing) return location.state === "empty" || holder.state === "empty";
      if (!query) return location.state !== "empty" || holder.state !== "empty";
      return [record.title, location.text, holder.text].some((text) => fold(text).includes(query));
    });

    mount(results,
      matches.length === 0
        ? el("p.caption.muted", {}, query ? t("where.noMatches") : t("where.nothingYet"))
        : matches.map((record) => resultRow(record, query, () => openEditor(record, refresh))),
    );
  };

  const refresh = async () => {
    const fresh = await api.whereAndWho(state.household.id);
    opened = await Promise.all(fresh.records.map(openRecord));
    presence.textContent = t("where.presence", {
      recorded: fresh.records.filter((r) => r.originalLocation || r.keyHolder).length,
      total: fresh.records.length,
    });
    render();
  };

  search.addEventListener("input", render);
  onlyMissing.onclick = () => {
    onlyMissing.setAttribute("aria-pressed",
      onlyMissing.getAttribute("aria-pressed") === "true" ? "false" : "true");
    render();
  };

  mount(host, el("div.stack", {},
    el("div.row-between.wrap", {}, header, lock),
    el("div.card.stack-3", {},
      field({ label: t("where.search"), control: search, help: t("where.searchHelp") }),
      el("div.row.wrap", { style: { gap: "8px" } }, onlyMissing),
      results,
    ),
    caveats,
  ));
  render();
  search.focus();
}

function resultRow(record, query, onEdit) {
  const line = (label, opened) => {
    const text = slotText(opened);
    return el("div.row-between", { style: { fontSize: "var(--text-sm)", alignItems: "flex-start" } },
      el("span.muted", {}, t(label)),
      el("span", {
        style: {
          textAlign: "right",
          color: opened.state === "open" || opened.state === "empty" ? null : "var(--caution)",
        },
      }, text ? highlight(text, opened.state === "open" ? query : "") : t("where.notRecorded")),
    );
  };

  return el("button.card.card-tight.stack-2", {
    type: "button",
    style: { textAlign: "left", width: "100%", cursor: "pointer" },
    onclick: onEdit,
  },
    el("div.row-between", {},
      el("b", {}, highlight(record.title, query)),
      el("span.caption.muted", {}, t(`where.type.${record.recordType}`)),
    ),
    line("where.location", record.opened.originalLocation),
    line("where.keyHolder", record.opened.keyHolder),
  );
}

/**
 * Marks the first match. Built from text nodes, never innerHTML: these strings
 * are a person's own words and may contain anything.
 */
function highlight(text, query) {
  if (!query) return text;
  const at = fold(text).indexOf(query);
  // Folding can change length (NFKC, some lower-casings); if the offsets would
  // not line up, show the text unmarked rather than mark the wrong letters.
  if (at < 0 || fold(text).length !== text.length) return text;
  return el("span", {},
    text.slice(0, at),
    el("mark", {}, text.slice(at, at + query.length)),
    text.slice(at + query.length));
}
