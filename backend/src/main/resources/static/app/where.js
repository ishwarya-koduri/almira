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
import { el, mount, field, textInput, withBusy, toast, skeletonRows, formatDate } from "./ui.js";
import { state } from "./state.js";
import { t, language } from "./i18n.js";
import { e2e, unlock as unlockE2e, sealField, unsealField, openSealedValue, openSealedValueAs } from "./e2e.js";
import { sealReassurance, sealedLineText } from "./recovery.js";
import { guidedFlow } from "./guided.js";

/** The contract with every other client, and with the server's index. */
export const FIELD = {
  originalLocation: "original_location",
  keyHolder: "key_holder",
  // The access chain (docs/27 §6): who next, and who after them. Sealed exactly
  // like the key holder, because where the names are sealed the chain is too.
  keyHolder2: "key_holder_2",
  keyHolder3: "key_holder_3",
};

const SLOTS = [
  { slot: "originalLocation", fieldKey: FIELD.originalLocation, label: "where.location" },
  { slot: "keyHolder", fieldKey: FIELD.keyHolder, label: "where.keyHolder", position: 1 },
  { slot: "keyHolder2", fieldKey: FIELD.keyHolder2, label: "chain.backup", position: 2 },
  { slot: "keyHolder3", fieldKey: FIELD.keyHolder3, label: "chain.third", position: 3 },
];

/** The "checked with them" tick at a place in the chain, or null. */
const tickAt = (record, position) => (record.chain || []).find((tick) => tick.position === position) || null;

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
  if (!value.sealedByMe) {
    // Someone else's, opened only if this session holds their key from their
    // recovery sheet or shares (docs/12 §10.5). Otherwise say who could open it.
    const memberId = value.access?.sealedByMemberId;
    if (memberId && e2e.hasKeyFor(memberId)) {
      try {
        const text = await openSealedValueAs(memberId, state.household.id, recordType, recordId, fieldKey, value.ciphertext);
        return { state: "openedWithRecovery", text, access: value.access };
      } catch {
        return { state: "unreadable" };
      }
    }
    return { state: "theirs", access: value.access };
  }
  if (!e2e.isUnlocked) return { state: "locked" };
  try {
    const text = await openSealedValue(state.household.id, recordType, recordId, fieldKey, value.ciphertext);
    return { state: "open", text };
  } catch {
    return { state: "unreadable" };
  }
}

async function openRecord(record) {
  const values = await Promise.all(SLOTS.map(({ slot, fieldKey }) =>
    openSlot(record.recordType, record.recordId, fieldKey, record[slot])));
  return { ...record, opened: Object.fromEntries(SLOTS.map(({ slot }, i) => [slot, values[i]])) };
}

function slotText(opened) {
  switch (opened.state) {
    case "open": return opened.text;
    case "openedWithRecovery": return opened.text;
    case "empty": return null;
    case "theirs": return sealedLineText(opened.access);
    case "locked": return t("where.lockedValue");
    default: return t("where.unreadable");
  }
}

/* -----------------------------------------------------------------------------
   Unlocking, inline — the passphrase is asked for where it is needed
   ----------------------------------------------------------------------------- */

export function unlockForm(onUnlocked) {
  const passphrase = textInput({
    type: "password", autocomplete: "current-password", "aria-label": t("security.passphrase"),
  });
  const error = el("div.help.error", { role: "alert", style: { minHeight: "1.15rem" } });
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
 * There is no unsealed note to warn about or move any more: the plaintext
 * columns are gone (V33, docs/20 §1), and the server refuses them if sent.
 */
export function whereWhoCard(recordType, recordId) {
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

    // The backups show only once there is one: an empty chain is not a gap.
    const rows = SLOTS
      .filter(({ slot, position }) => !position || position === 1 || opened.opened[slot].state !== "empty")
      .map(({ slot, label, position }) => el("div.row-between", { style: { fontSize: "var(--text-sm)" } },
        el("span.muted", {}, t(label)),
        el("span", { style: { textAlign: "right" } },
          slotText(opened.opened[slot]) || t("where.notRecorded"),
          position && tickAt(opened, position) && el("span.caption", {},
            ` · ${t("chain.checkedOn", { date: formatDate(tickAt(opened, position).confirmedAt) })}`)),
      ));

    mount(host,
      el("div.overline", {}, t("where.cardTitle")),
      ...rows,
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

function suggestions() {
  // Roles and relationships to pick from, in the reader's language — never the
  // names of members or contacts. The helper text asks for "Amma" or "the CA"
  // rather than a full name, so the one-tap choices must not offer full names
  // (the owner's decision, docs/20 §4). Nothing is linked either way.
  return t("where.keyHolderSuggestions").split("|").map((s) => s.trim()).filter(Boolean);
}

/**
 * One question per screen (X-58): where the original is, who holds the key,
 * and who next if they can't be reached, and after them (docs/27 §6). Each
 * answer is sealed on this device and saved the moment its step is left, so
 * stopping half-way keeps what was answered; the step itself is kept by the
 * server, the words never are (V91).
 */
export async function openEditor(record, onSaved) {
  const current = (slot) => (["open", "openedWithRecovery"].includes(record.opened[slot].state) ? record.opened[slot].text : "");
  // Not theirs to change even when opened with a recovery copy: the value stays
  // the sealer's (docs/20 §5), and an heir reads it, never rewrites it.
  const theirs = (slot) => ["theirs", "openedWithRecovery"].includes(record.opened[slot].state);
  const saved = Object.fromEntries(SLOTS.map(({ slot }) => [slot, current(slot)]));
  // "Checked with them", per place in the chain: what the box says, and what
  // the server holds. Only the person who sealed a name can tick it, because
  // nobody else can read who it names.
  const ticked = Object.fromEntries([1, 2, 3].map((position) => [position, Boolean(tickAt(record, position))]));
  const onServer = { ...ticked };

  /** Kept exactly as typed — no trim — because a sealed value is never normalised (docs/12 §3). */
  const sealStep = (slot) => async (text) => {
    const { fieldKey, position } = SLOTS.find((s) => s.slot === slot);
    if (text !== saved[slot]) {
      // Blank means "remove", which is a choice about the field, not a rewrite.
      if (text.trim() === "") {
        if (saved[slot] !== "" || record.opened[slot].state !== "empty") {
          await unsealField(state.household.id, record.recordType, record.recordId, fieldKey);
        }
      } else {
        await sealField(state.household.id, record.recordType, record.recordId, fieldKey, text);
      }
      saved[slot] = text;
      // Writing or removing a name clears its tick on the server.
      if (position) onServer[position] = false;
    }
    // After the name is sealed, so the tick is given to the name as it now stands.
    if (position && text.trim() !== "") {
      if (ticked[position] && !onServer[position]) {
        await api.confirmChain(state.household.id, record.recordType, record.recordId, position);
        onServer[position] = true;
      } else if (!ticked[position] && onServer[position]) {
        await api.unconfirmChain(state.household.id, record.recordType, record.recordId, position).catch(() => {});
        onServer[position] = false;
      }
    }
  };

  const question = (slot, labelKey, build, skip) => ({
    key: slot,
    question: t(labelKey),
    help: theirs(slot) ? slotText({ state: "theirs", access: record.opened[slot].access }) : null,
    skip: (answers) => theirs(slot) || Boolean(skip?.(answers)),
    build,
    save: sealStep(slot),
  });

  const listId = `where-people-${record.recordId}`;
  /** A name in the chain, with its "Checked with them" box. */
  const holderStep = (slot, labelKey, helpKey, skip) => question(slot, labelKey, () => {
    const { position } = SLOTS.find((s) => s.slot === slot);
    const people = el("datalist#" + listId, {});
    suggestions().forEach((role) => people.append(el("option", { value: role })));
    const holder = el("input.input", {
      type: "text", value: saved[slot], list: listId,
      placeholder: t("where.keyHolderPlaceholder"), "aria-label": t(labelKey), autocomplete: "off",
    });
    const box = el("input", { type: "checkbox", checked: ticked[position] });
    box.onchange = () => { ticked[position] = box.checked; };
    return {
      node: el("div.stack-2", {},
        sealReassurance([holder]), holder, people,
        helpKey && el("p.caption", {}, t(helpKey)),
        el("label.check-row", {}, box, el("span.grow", {}, el("span.check-label", {}, t("chain.checked"))))),
      value: () => holder.value,
    };
  }, skip);

  await guidedFlow({
    flow: "where_and_who",
    subject: `${record.recordType}:${record.recordId}`,
    title: record.title,
    keep: [],
    steps: [
      question("originalLocation", "where.location", () => {
        const location = el("textarea.textarea", {
          rows: 3, value: saved.originalLocation,
          placeholder: t("where.locationPlaceholder"), "aria-label": t("where.location"),
        });
        return {
          node: el("div.stack-2", {}, sealReassurance([location]), location, el("p.caption", {}, t("where.locationHelp"))),
          value: () => location.value,
        };
      }),
      holderStep("keyHolder", "where.keyHolder", "where.keyHolderHelp"),
      holderStep("keyHolder2", "chain.backup", "chain.help"),
      // A third only once there is a second: an empty chain is not a gap.
      holderStep("keyHolder3", "chain.third", null,
        (answers) => (answers.keyHolder2 ?? saved.keyHolder2).trim() === "" && saved.keyHolder3 === ""),
    ].filter((step) => !theirs(step.key)).concat(
      // All of them are someone else's: nothing to ask, only to say so.
      SLOTS.every(({ slot }) => theirs(slot))
        ? [{ key: "nothing", question: t("where.editorIntro"), build: () => ({ node: el("p.muted", {}, slotText({ state: "theirs", access: record.opened.originalLocation.access })), value: () => null }) }]
        : [],
    ),
    finish: {
      label: t("app.save"),
      run: async () => {
        toast(t("where.saved"));
        await onSaved?.();
      },
    },
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
      // An empty backup is not a gap; a backup's words are searchable like the first.
      if (missing) return location.state === "empty" || holder.state === "empty";
      if (!query) return SLOTS.some(({ slot }) => record.opened[slot].state !== "empty");
      return [record.title, ...SLOTS.map(({ slot }) => record.opened[slot].text)]
        .some((text) => fold(text).includes(query));
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
          color: ["open", "empty", "openedWithRecovery", "theirs"].includes(opened.state) ? null : "var(--caution)",
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
    record.opened.keyHolder2.state !== "empty" && line("chain.backup", record.opened.keyHolder2),
    record.opened.keyHolder3.state !== "empty" && line("chain.third", record.opened.keyHolder3),
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
