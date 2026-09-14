/* =============================================================================
   Drafts and the offline queue, on this device only (X-83, docs/03 §3.4).

   A half-filled form survives a dropped connection, a closed tab and a phone
   call: every field that is safe to keep is written here as it is typed, and a
   save made while offline waits here until there is a network.

   What is never kept, whatever a form asks:
     · anything sealed, and the where-and-who lines (docs/20). Those are sealed
       on the device before they leave it; a readable copy in storage would undo
       the point. A draft refuses those field keys outright.
     · identifiers: account, policy, folio, PRAN, UAN and certificate numbers,
       phone numbers, addresses. Not sealed today, but not something to leave
       lying in a browser's storage either.
     · long free text (notes), past a few hundred characters.

   Drafts are per person: the storage key carries the user id, and every draft
   and queued save on the device is removed at sign-out (api.js auth.clear), so
   the next person to sign in on a shared phone sees none of them.

   No imports, and storage is passed in, so scripts/check-drafts.js can assert
   all of this without a browser.
   ============================================================================= */

const DRAFTS = "almira.drafts.v1.";
const OUTBOX = "almira.outbox.v1.";
const MAX_VALUE = 300;
const MAX_DRAFTS = 20;
const MAX_QUEUED = 50;

/**
 * Field keys a draft will not hold. Matched against the whole key, and against
 * each part of a compound key such as "attr:policy_no".
 */
const NEVER = [
  /sealed/i, /passphrase/i, /recovery/i, /secret/i, /password/i, /otp/i,
  /location/i, /key_?holder/i, /where/i, /who/i, /custody/i, /held_in/i,
  /(^|_)no$/i, /number/i, /(^|_)id$/i, /pran/i, /(^|_)uan$/i, /(^|_)cin$/i, /isin/i,
  /phone/i, /email/i, /address/i, /wallet/i, /pan$/i, /aadhaar/i, /ifsc/i,
  /survey/i, /registration/i, /notes?$/i, /co_owners/i, /borrower/i, /agent/i,
  /vault/i, /written_record/i,
];

export function isDraftable(fieldKey) {
  const key = String(fieldKey || "");
  if (!key) return false;
  const parts = [key, ...key.split(":")];
  return !parts.some((part) => NEVER.some((pattern) => pattern.test(part)));
}

function read(storage, key, fallback) {
  try {
    const raw = storage.getItem(key);
    return raw ? JSON.parse(raw) : fallback;
  } catch {
    return fallback;
  }
}

function write(storage, key, value) {
  try { storage.setItem(key, JSON.stringify(value)); return true; } catch { return false; }
}

/**
 * @param storage  localStorage, or anything with getItem/setItem/removeItem/key/length
 * @param userId   the signed-in person; no id, no drafts
 * @param now      a clock, for tests
 */
export function createDrafts({ storage, userId, now = () => Date.now() }) {
  const enabled = Boolean(storage && userId);
  const draftsKey = `${DRAFTS}${userId}`;
  const outboxKey = `${OUTBOX}${userId}`;
  const all = () => (enabled ? read(storage, draftsKey, {}) : {});

  function saveField(formKey, fieldKey, value, meta = {}, { keepExisting = false } = {}) {
    if (!enabled || !isDraftable(fieldKey)) return false;
    const drafts = all();
    const draft = drafts[formKey] || { fields: {}, meta: {} };
    // A refused save put back must not overwrite what was typed since.
    if (keepExisting && draft.fields[fieldKey] !== undefined) return true;
    const text = value === null || value === undefined ? "" : String(value);
    if (text === "") delete draft.fields[fieldKey];
    else draft.fields[fieldKey] = text.slice(0, MAX_VALUE);
    draft.meta = { ...draft.meta, ...safeMeta(meta) };
    draft.savedAt = now();
    if (Object.keys(draft.fields).length === 0) delete drafts[formKey];
    else drafts[formKey] = draft;
    // The oldest go first once there are too many to be a to-do list.
    const keys = Object.keys(drafts).sort((a, b) => drafts[b].savedAt - drafts[a].savedAt);
    keys.slice(MAX_DRAFTS).forEach((key) => delete drafts[key]);
    return write(storage, draftsKey, drafts);
  }

  /**
   * A queued capture the server refused goes back to the person as the draft
   * of its form, through the same rules as any draft. False when it carries no
   * form (queued before forms were recorded) or nothing in it could be kept.
   */
  function returnAsDraft(item) {
    const formKey = typeof item.formKey === "string" ? item.formKey : null;
    if (!enabled || item.kind !== "capture" || !formKey) return false;
    const body = item.body || {};
    const meta = { typeCode: formKey.replace(/^capture:/, ""), title: body.title, householdId: item.householdId };
    const fields = [["title", body.title]];
    for (const [key, value] of Object.entries(body)) {
      if (QUEUE_KEYS.has(key) || key === "title" || key === "visibility") continue;
      if (value !== null && typeof value === "object") continue;
      fields.push([key.replace(/[A-Z]/g, (c) => `_${c.toLowerCase()}`), value]);
    }
    for (const [key, value] of Object.entries(body.attributes || {})) fields.push([`attr:${key}`, value]);
    if (body.owners?.[0]?.memberId) fields.push(["owner", body.owners[0].memberId]);
    let kept = false;
    for (const [key, value] of fields) {
      if (value === null || value === undefined || value === "") continue;
      if (saveField(formKey, key, value, meta, { keepExisting: true })) kept = true;
    }
    return kept;
  }

  return {
    enabled,

    /**
     * Keeps one field. Returns true when it was kept, false when the field is
     * one a draft never holds (or storage is unavailable), so the form shows the
     * "Saved on this phone" tick only for what is really saved.
     */
    save(formKey, fieldKey, value, meta = {}) {
      return saveField(formKey, fieldKey, value, meta);
    },

    get(formKey) { return all()[formKey] || null; },

    /** Newest first. */
    list() {
      const drafts = all();
      return Object.entries(drafts)
        .map(([formKey, draft]) => ({ formKey, ...draft }))
        .sort((a, b) => b.savedAt - a.savedAt);
    },

    discard(formKey) {
      if (!enabled) return;
      const drafts = all();
      delete drafts[formKey];
      write(storage, draftsKey, drafts);
    },

    /* --- saves made offline --------------------------------------------- */

    /**
     * Queues a save to send later. The body is checked the same way a draft is:
     * a body carrying a field a draft may not hold is refused, not queued.
     */
    queue(kind, householdId, body, label, formKey) {
      if (!enabled) return false;
      const unsafe = Object.keys(body || {}).filter((key) => !isDraftable(key) && !QUEUE_KEYS.has(key));
      const unsafeAttributes = Object.keys(body?.attributes || {}).filter((key) => !isDraftable(key));
      if (unsafe.length || unsafeAttributes.length) return false;
      const queued = read(storage, outboxKey, []);
      if (queued.length >= MAX_QUEUED) return false;
      const item = { kind, householdId, body, label: String(label || "").slice(0, 120), queuedAt: now() };
      if (typeof formKey === "string") item.formKey = formKey.slice(0, 60);
      queued.push(item);
      return write(storage, outboxKey, queued);
    },

    pending() { return enabled ? read(storage, outboxKey, []) : []; },

    /**
     * Sends what is queued, oldest first. `send(item)` resolves "sent" or
     * "refused" (the server answered and said no: it goes back to the person
     * as the draft of its form; `returnedAsDraft` on the refused item says
     * whether anything could be put back), or throws when there is still no
     * network (it stays queued, and so does everything after it).
     */
    async flush(send) {
      if (!enabled) return { sent: [], refused: [] };
      const queued = read(storage, outboxKey, []);
      const sent = [];
      const refused = [];
      while (queued.length) {
        const item = queued[0];
        let outcome;
        try { outcome = await send(item); } catch { break; }
        if (outcome === "refused") refused.push({ ...item, returnedAsDraft: returnAsDraft(item) });
        else sent.push(item);
        queued.shift();
        write(storage, outboxKey, queued);
      }
      return { sent, refused };
    },
  };
}

/** Top-level body keys a queued capture may carry although they would not be a draft field. */
const QUEUE_KEYS = new Set(["id", "typeId", "institutionId", "owners", "visibleToMemberIds", "attributes", "customFields"]);

/** Only a type code and a title travel with a draft: enough for "Finish adding HDFC FD?". */
function safeMeta(meta) {
  const out = {};
  if (typeof meta.typeCode === "string") out.typeCode = meta.typeCode.slice(0, 40);
  if (typeof meta.title === "string") out.title = meta.title.slice(0, 120);
  if (typeof meta.householdId === "string") out.householdId = meta.householdId.slice(0, 40);
  return out;
}

/** Every draft and queued save on this device, for everyone. Called at sign-out. */
export function clearAllDrafts(storage) {
  if (!storage) return;
  try {
    const doomed = [];
    for (let i = 0; i < storage.length; i += 1) {
      const key = storage.key(i);
      if (key && (key.startsWith(DRAFTS) || key.startsWith(OUTBOX))) doomed.push(key);
    }
    doomed.forEach((key) => storage.removeItem(key));
  } catch { /* storage unavailable: nothing was kept either */ }
}
