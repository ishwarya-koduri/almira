/* =============================================================================
   Zero-knowledge mode, in the browser (docs/12).

   The passphrase never leaves this file. What goes to the server is a wrapped
   key it cannot unwrap and ciphertext it cannot open — and the tests on the
   backend go looking for the plaintext in the database to prove it.

   The content key lives in memory for the session only. Writing it to
   localStorage would hand it to any script on the origin and quietly undo the
   whole feature, so a reload asks for the passphrase again. That is the cost,
   and it is stated in the interface rather than worked around.
   ============================================================================= */

import { api } from "./api.js";

const ITERATIONS = 600_000;
const VERIFIER = "almira";

/** Session-only. Cleared on reload, on sign-out, and on lock. */
let contentKey = null;
let keyVersion = 1;

export const e2e = {
  get isUnlocked() { return contentKey !== null; },
  lock() { contentKey = null; },
};

/* -----------------------------------------------------------------------------
   Bytes
   ----------------------------------------------------------------------------- */

const encoder = new TextEncoder();
const decoder = new TextDecoder();

function toBase64Url(bytes) {
  let binary = "";
  new Uint8Array(bytes).forEach((b) => { binary += String.fromCharCode(b); });
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function fromBase64Url(text) {
  const padded = text.replace(/-/g, "+").replace(/_/g, "/");
  const binary = atob(padded + "=".repeat((4 - (padded.length % 4)) % 4));
  return Uint8Array.from(binary, (c) => c.charCodeAt(0));
}

const randomBytes = (length) => crypto.getRandomValues(new Uint8Array(length));

/* -----------------------------------------------------------------------------
   The envelope: version(1) | keyVersion(4) | iv(12) | ciphertext+tag
   ----------------------------------------------------------------------------- */

async function seal(key, plaintextBytes, aad, version = keyVersion) {
  const iv = randomBytes(12);
  const body = new Uint8Array(await crypto.subtle.encrypt(
    { name: "AES-GCM", iv, ...(aad ? { additionalData: aad } : {}) },
    key,
    plaintextBytes,
  ));

  const envelope = new Uint8Array(1 + 4 + 12 + body.length);
  envelope[0] = 1;
  new DataView(envelope.buffer).setUint32(1, version, false);
  envelope.set(iv, 5);
  envelope.set(body, 17);
  return toBase64Url(envelope);
}

async function open(key, envelopeText, aad) {
  const raw = fromBase64Url(envelopeText);
  if (raw[0] !== 1) {
    // A version we do not know is not a thing to guess at: guessing here means
    // showing someone the wrong bytes and calling them their note.
    throw new Error("This was sealed by a newer version of Almira.");
  }
  const iv = raw.slice(5, 17);
  const body = raw.slice(17);
  const plain = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv, ...(aad ? { additionalData: aad } : {}) },
    key,
    body,
  );
  return decoder.decode(plain);
}

/**
 * Binds a ciphertext to the exact field it was written for, so a value moved to
 * another record fails to open rather than decrypting somewhere it does not
 * belong (docs/12 §4).
 */
const aadFor = (householdId, recordType, recordId, fieldKey) =>
  encoder.encode(`${householdId}|${recordType}|${recordId}|${fieldKey}`);

async function wrappingKeyFrom(passphrase, salt, iterations) {
  const base = await crypto.subtle.importKey(
    "raw", encoder.encode(passphrase), "PBKDF2", false, ["deriveKey"],
  );
  return crypto.subtle.deriveKey(
    { name: "PBKDF2", hash: "SHA-256", salt, iterations },
    base,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"],
  );
}

const importContentKey = (bytes) =>
  crypto.subtle.importKey("raw", bytes, { name: "AES-GCM", length: 256 }, true, ["encrypt", "decrypt"]);

/* -----------------------------------------------------------------------------
   Setting up, unlocking, rotating
   ----------------------------------------------------------------------------- */

export async function enable(householdId, passphrase) {
  const salt = randomBytes(16);
  const wrappingKey = await wrappingKeyFrom(passphrase, salt, ITERATIONS);

  const rawContentKey = randomBytes(32);
  const key = await importContentKey(rawContentKey);

  const envelope = {
    kdf: "PBKDF2-SHA256",
    kdfSalt: toBase64Url(salt),
    iterations: ITERATIONS,
    wrapAlgorithm: "AES-GCM-256",
    wrappedKey: await seal(wrappingKey, rawContentKey, null, 1),
    verifier: await seal(key, encoder.encode(VERIFIER), null, 1),
    keyVersion: 1,
  };
  await api.putE2eKey(householdId, envelope);

  contentKey = key;
  keyVersion = 1;
  return envelope;
}

export async function unlock(householdId, passphrase) {
  const status = await api.e2eStatus(householdId);
  if (!status.enabled) throw new Error("No passphrase has been set up for this household yet.");

  const stored = status.key;
  const wrappingKey = await wrappingKeyFrom(
    passphrase, fromBase64Url(stored.kdfSalt), stored.iterations,
  );

  let rawContentKey;
  try {
    const raw = fromBase64Url(stored.wrappedKey);
    rawContentKey = new Uint8Array(await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: raw.slice(5, 17) }, wrappingKey, raw.slice(17),
    ));
  } catch {
    // The verifier exists so this is a clean "wrong passphrase" rather than a
    // decryption error pointed at someone's records.
    throw new Error("That passphrase doesn't open this. Nothing has been changed.");
  }

  const key = await importContentKey(rawContentKey);
  await open(key, stored.verifier, null);   // proves the key, not just the wrap

  contentKey = key;
  keyVersion = stored.keyVersion;
  return true;
}

/**
 * A new passphrase rewraps the same content key, so nothing already sealed has
 * to be rewritten (docs/12 §6).
 */
export async function rotate(householdId, currentPassphrase, newPassphrase) {
  await unlock(householdId, currentPassphrase);
  const rawContentKey = new Uint8Array(await crypto.subtle.exportKey("raw", contentKey));

  const salt = randomBytes(16);
  const wrappingKey = await wrappingKeyFrom(newPassphrase, salt, ITERATIONS);
  const nextVersion = keyVersion + 1;

  await api.putE2eKey(householdId, {
    kdf: "PBKDF2-SHA256",
    kdfSalt: toBase64Url(salt),
    iterations: ITERATIONS,
    wrapAlgorithm: "AES-GCM-256",
    wrappedKey: await seal(wrappingKey, rawContentKey, null, nextVersion),
    verifier: await seal(contentKey, encoder.encode(VERIFIER), null, nextVersion),
    keyVersion: nextVersion,
  });
  keyVersion = nextVersion;
}

/* -----------------------------------------------------------------------------
   Fields
   ----------------------------------------------------------------------------- */

export async function sealField(householdId, recordType, recordId, fieldKey, text) {
  if (!contentKey) throw new Error("Unlock zero-knowledge mode first.");
  const ciphertext = await seal(
    contentKey, encoder.encode(text), aadFor(householdId, recordType, recordId, fieldKey),
  );
  return api.sealValue(householdId, recordType, recordId, fieldKey, { ciphertext, keyVersion });
}

export async function readSealed(householdId, recordType, recordId) {
  const values = await api.sealedValues(householdId, recordType, recordId);
  if (!contentKey) return values.map((v) => ({ ...v, locked: true }));

  return Promise.all(values.map(async (value) => {
    try {
      return {
        ...value,
        locked: false,
        text: await open(
          contentKey, value.ciphertext,
          aadFor(householdId, value.recordType, value.recordId, value.fieldKey),
        ),
      };
    } catch {
      // Shown as unreadable rather than thrown: one bad value must not take the
      // whole screen down with it.
      return { ...value, locked: true, error: "This one couldn't be opened." };
    }
  }));
}

export const unsealField = (householdId, recordType, recordId, fieldKey) =>
  api.unsealValue(householdId, recordType, recordId, fieldKey);
