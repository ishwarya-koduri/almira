/* =============================================================================
   A sealed note on a record (docs/12 §1, known-issues 8, now closed).

   The web client could set up a passphrase and never seal anything a person
   chose to write. This is the card that does: one note per record, sealed on
   this device before it is sent, under the field key `sealed_note`. The native
   app lists and opens it generically, as it does any sealed value.

   Any other sealed value on the record — one a native app wrote under a key of
   its own — is shown here too, read-only, so nothing sealed is invisible in
   the browser. "Where the original is" and "who holds the key" have their own
   card (where.js) and are left to it.
   ============================================================================= */

import { api } from "./api.js";
import { el, mount, sheet, withBusy, toast } from "./ui.js";
import { state } from "./state.js";
import { t } from "./i18n.js";
import { e2e, sealField, unsealField, openSealedValue } from "./e2e.js";
import { FIELD, unlockForm } from "./where.js";
import { lockMark, sealReassurance } from "./recovery.js";

/** The contract with every other client. */
export const NOTE_FIELD = "sealed_note";

const WHERE_KEYS = new Set(Object.values(FIELD));

async function openValue(recordType, recordId, value) {
  if (value.sealedByMe === false) return { ...value, state: "theirs" };
  if (!e2e.isUnlocked) return { ...value, state: "locked" };
  try {
    return { ...value, state: "open", text: await openSealedValue(state.household.id, recordType, recordId, value.fieldKey, value.ciphertext) };
  } catch {
    return { ...value, state: "unreadable" };
  }
}

function label(fieldKey) {
  return fieldKey === NOTE_FIELD ? t("note.title") : t("note.otherField", { key: fieldKey });
}

export function sealedNoteCard(recordType, recordId) {
  const host = el("div.card.card-tight.stack-2", { "data-sealed-note": "true" },
    el("div.overline", {}, t("note.title")),
    el("div.skeleton", { style: { height: "40px" } }),
  );

  const draw = async () => {
    let status;
    let values;
    try {
      [status, values] = await Promise.all([
        api.e2eStatus(state.household.id),
        api.sealedValues(state.household.id, recordType, recordId),
      ]);
    } catch {
      mount(host, el("div.overline", {}, t("note.title")), el("p.muted.text-sm", {}, t("app.somethingWrong")));
      return;
    }
    const opened = await Promise.all(values
      .filter((value) => !WHERE_KEYS.has(value.fieldKey))
      .map((value) => openValue(recordType, recordId, value)));
    const note = opened.find((value) => value.fieldKey === NOTE_FIELD);

    const rows = opened.map((value) => el("div.stack-2", {},
      el("div.row", { style: { gap: "8px", alignItems: "center" } }, lockMark(true),
        el("span.muted.text-sm", {}, label(value.fieldKey))),
      el("p", { style: { whiteSpace: "pre-wrap", margin: 0, color: value.state === "unreadable" ? "var(--caution)" : null } },
        value.state === "open" ? value.text
          : value.state === "theirs" ? t("where.theirs")
            : value.state === "locked" ? t("where.lockedValue")
              : t("where.unreadable")),
    ));

    let action = null;
    if (!status.enabled) {
      action = el("p.muted.text-sm", {}, t("note.needsPassphrase"));
    } else if (!e2e.isUnlocked) {
      action = opened.length > 0 ? unlockForm(draw) : el("p.muted.text-sm", {}, t("note.unlockToAdd"));
    } else if (!note || note.state === "open") {
      action = el("div.row", {}, el("button.btn.btn-sm", {
        type: "button", onclick: () => editNote(recordType, recordId, note, draw),
      }, note ? t("note.edit") : t("note.add")));
    }

    mount(host,
      el("div.overline", {}, t("note.title")),
      ...rows,
      opened.length === 0 && status.enabled && e2e.isUnlocked && el("p.muted.text-sm", {}, t("note.empty")),
      action,
    );
  };

  draw();
  return host;
}

function editNote(recordType, recordId, note, onSaved) {
  const text = el("textarea.textarea", {
    rows: 5, value: note?.text || "", "aria-label": t("note.title"), placeholder: t("note.placeholder"),
  });
  const error = el("div.help.error", { role: "alert", style: { minHeight: "1.15rem" } });
  const save = el("button.btn.btn-primary.grow", { type: "button" }, t("app.save"));
  const modal = sheet({
    title: t("note.title"),
    body: el("div.stack-3", {},
      sealReassurance([text], "note.reassurance"),
      text,
      el("p.muted.text-sm", {}, t("note.help")),
      error,
    ),
    footer: [save],
  });
  save.onclick = () => withBusy(save, async () => {
    error.textContent = "";
    try {
      // Exactly as typed: a sealed value is never trimmed or normalised
      // (docs/12 §3). Empty means remove, which is a choice about the field.
      if (text.value.trim() === "") {
        if (note) await unsealField(state.household.id, recordType, recordId, NOTE_FIELD);
      } else if (text.value !== note?.text) {
        await sealField(state.household.id, recordType, recordId, NOTE_FIELD, text.value);
      }
      modal.close();
      toast(t("note.saved"));
      await onSaved?.();
    } catch (problem) {
      error.textContent = problem.message;
    }
  });
}
