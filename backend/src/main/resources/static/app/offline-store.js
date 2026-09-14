/* =============================================================================
   The family handbook, kept on this device for when there is no connection (P-21).

   Everything else in the web client is read from the server and forgotten: the
   service worker never stores an API response (sw.js). This is the one
   exception, and it is narrow on purpose. docs/16 §"The offline copy" is the
   threat model; the rules it relies on are these.

   **Off unless someone turns it on, on this device.** It is a setting, not a
   default, and Settings says not to on a shared computer.

   **An allowlist, not a response.** Only the fields the offline page draws are
   copied (`offlineCopy`): holdings by name, institution, reference, nominees
   and value; debts; wills by title; the people who help, with their phones.
   Sealed values, "where it is kept", notes and ids never are.

   **Encrypted, with a key that cannot be read out.** AES-GCM-256 under a
   WebCrypto key generated as non-extractable. The key lives in one IndexedDB
   database and the ciphertext in another, so a copy of one is not the other,
   and no script — ours included — can export the key's bytes.

   **Gone on sign-out, and after 30 days.** Signing out, a refresh the server
   refuses, or turning the setting off deletes the key first and the data
   second: once the key is gone, any copy of the ciphertext left behind — in a
   backup, or a store that failed to delete — is noise. A copy older than 30
   days is refused and deleted rather than shown as if it were current.

   What it does not do, stated in docs/16 too: it does not protect the copy from
   someone who can already use this browser profile while it is signed in, or
   from a script running on this origin. The refresh token in localStorage
   already gives either of them far more.
   ============================================================================= */

const KEY_DB = "almira-offline-key";
const DATA_DB = "almira-offline-data";
const STORE = "items";
const KEY_ID = "device-key";
const COPY_ID = "handbook";
const FLAG = "almira.offline";
const AAD = "almira-offline-copy-v1";
const aad = () => new TextEncoder().encode(AAD);

export const FORMAT = 1;
export const MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000;

/* -----------------------------------------------------------------------------
   The rules, with no browser storage in them (scripts/check-offline-store.js)
   ----------------------------------------------------------------------------- */

const pick = (source, keys) => Object.fromEntries(
  keys.filter((key) => source?.[key] !== undefined && source?.[key] !== null).map((key) => [key, source[key]]));
const strings = (list) => (Array.isArray(list) ? list.filter((item) => typeof item === "string") : []);

/**
 * What is kept: exactly what the offline page shows, and nothing it does not.
 * Anything the server adds to these responses later stays out until someone
 * adds it here on purpose.
 */
export function offlineCopy({ handbook, contacts, trusted, householdId, now = Date.now() }) {
  const contactFields = ["name", "kind", "role", "organisation", "phone", "email"];
  return {
    format: FORMAT,
    householdId,
    savedAt: now,
    handbook: {
      ...pick(handbook, ["householdName", "preparedFor", "preparedOn", "totalIncludedFormatted", "disclaimer"]),
      entries: (handbook?.entries || []).map((entry) => ({
        ...pick(entry, ["title", "typeLabel", "categoryCode", "institutionName", "reference", "accountLabel",
          "valueFormatted", "howToClaim"]),
        nominees: strings(entry.nominees),
        contacts: (entry.contacts || []).map((contact) => pick(contact, contactFields)),
      })),
      debts: (handbook?.debts || []).map((debt) =>
        pick(debt, ["title", "lender", "outstandingFormatted", "securedAgainst"])),
      instruments: (handbook?.instruments || []).map((item) => ({
        ...pick(item, ["title", "kind", "forMember", "executedOn"]),
        executors: strings(item.executors),
      })),
      contacts: (handbook?.contacts || []).map((contact) => pick(contact, contactFields)),
    },
    contacts: (contacts || []).map((contact) => pick(contact, contactFields)),
    trusted: (trusted || []).map((contact) => pick(contact, ["memberName", "trustedMemberName", "theyTrustMe", "waitDays"])),
  };
}

/** Whether a kept copy is too old to show, or from a future this device's clock cannot explain. */
export function isStale(copy, now = Date.now()) {
  if (!copy || copy.format !== FORMAT || !Number.isFinite(copy.savedAt)) return true;
  const age = now - copy.savedAt;
  return age > MAX_AGE_MS || age < -5 * 60 * 1000;
}

/**
 * "Last updated 2 hours ago", as an i18n key and a count. Calendar days are
 * not the point here — how old the copy is, is.
 */
export function lastUpdatedKey(savedAt, now = Date.now()) {
  const minutes = Math.max(0, Math.floor((now - savedAt) / 60000));
  if (minutes < 1) return { key: "offline.updated.justNow", count: 0 };
  if (minutes < 60) return { key: minutes === 1 ? "offline.updated.minute" : "offline.updated.minutes", count: minutes };
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return { key: hours === 1 ? "offline.updated.hour" : "offline.updated.hours", count: hours };
  const days = Math.floor(hours / 24);
  return { key: days === 1 ? "offline.updated.day" : "offline.updated.days", count: days };
}

/* -----------------------------------------------------------------------------
   The switch
   ----------------------------------------------------------------------------- */

export function isEnabled() {
  try { return localStorage.getItem(FLAG) === "on"; } catch { return false; }
}

export function isSupported() {
  return typeof indexedDB !== "undefined" && Boolean(globalThis.crypto?.subtle);
}

export function enable() {
  try { localStorage.setItem(FLAG, "on"); } catch { /* private mode: stays off */ }
}

export async function disable() {
  try { localStorage.removeItem(FLAG); } catch { /* ignore */ }
  await wipe();
}

/* -----------------------------------------------------------------------------
   IndexedDB, as little of it as will do
   ----------------------------------------------------------------------------- */

function open(name) {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(name, 1);
    request.onupgradeneeded = () => request.result.createObjectStore(STORE);
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error("blocked"));
  });
}

async function run(name, mode, action) {
  const db = await open(name);
  try {
    return await new Promise((resolve, reject) => {
      const tx = db.transaction(STORE, mode);
      const request = action(tx.objectStore(STORE));
      tx.oncomplete = () => resolve(request?.result);
      tx.onerror = () => reject(tx.error);
      tx.onabort = () => reject(tx.error);
    });
  } finally {
    db.close();
  }
}

function remove(name) {
  return new Promise((resolve) => {
    const request = indexedDB.deleteDatabase(name);
    request.onsuccess = () => resolve(true);
    request.onerror = () => resolve(false);
    // Another tab holds it open; it goes when that tab lets go.
    request.onblocked = () => resolve(false);
  });
}

async function deviceKey({ create }) {
  const existing = await run(KEY_DB, "readonly", (store) => store.get(KEY_ID));
  if (existing || !create) return existing || null;
  const key = await crypto.subtle.generateKey({ name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
  await run(KEY_DB, "readwrite", (store) => store.put(key, KEY_ID));
  return key;
}

/* -----------------------------------------------------------------------------
   Keep, read, forget
   ----------------------------------------------------------------------------- */

/** Encrypts and keeps a copy. Does nothing unless the setting is on. */
export async function keep(parts) {
  if (!isEnabled() || !isSupported()) return false;
  const copy = offlineCopy(parts);
  const key = await deviceKey({ create: true });
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv, additionalData: aad() }, key, new TextEncoder().encode(JSON.stringify(copy)));
  await run(DATA_DB, "readwrite", (store) => store.put({ format: FORMAT, iv, ciphertext }, COPY_ID));
  return true;
}

/**
 * The kept copy, or null: when there is none, when the setting is off, when
 * it cannot be decrypted (the key was deleted, or it was tampered with), or
 * when it is too old — and in each of those last cases it is deleted too.
 */
export async function read({ now = Date.now() } = {}) {
  if (!isEnabled() || !isSupported()) return null;
  try {
    const sealed = await run(DATA_DB, "readonly", (store) => store.get(COPY_ID));
    if (!sealed) return null;
    const key = await deviceKey({ create: false });
    if (!key) { await wipe(); return null; }
    const plain = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: sealed.iv, additionalData: aad() }, key, sealed.ciphertext);
    const copy = JSON.parse(new TextDecoder().decode(plain));
    if (isStale(copy, now)) { await wipe(); return null; }
    return copy;
  } catch {
    await wipe();
    return null;
  }
}

/**
 * Deletes the key, then the data. Safe to call at any time, including when
 * nothing was ever kept; never throws.
 */
export async function wipe() {
  if (typeof indexedDB === "undefined") return;
  await remove(KEY_DB);
  await remove(DATA_DB);
}
