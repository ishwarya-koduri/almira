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

/**
 * The one envelope reader, used for all three kinds of envelope — wrapped key,
 * verifier and every sealed field.
 *
 * There used to be two: this one, and an inline slice in `unlock` that took
 * offsets 5..17 out of the wrapped key without ever looking at the version
 * byte. Nothing was wrong with the bytes it produced, which is exactly the
 * problem — a second parser is correct right up until a format changes, and
 * then it reads the wrong offsets out of a newer envelope and hands back
 * plausible rubbish instead of an error (docs/zk-interop-acceptance.md B6).
 *
 * Fails closed on everything: unknown version, truncation, not-base64. A sealed
 * value nobody can read is a thing someone gets told about. A sealed value read
 * wrongly is an address they will believe.
 */
function parseEnvelope(envelopeText) {
  let raw;
  try {
    raw = fromBase64Url(envelopeText);
  } catch {
    throw new Error("This doesn't look like sealed data.");
  }
  if (raw.length < 33) throw new Error("This doesn't look like sealed data.");
  if (raw[0] !== 1) {
    // A version we do not know is not a thing to guess at: guessing here means
    // showing someone the wrong bytes and calling them their note.
    throw new Error("This was sealed by a newer version of Almira.");
  }
  const keyVersion = new DataView(raw.buffer, raw.byteOffset).getUint32(1, false);
  if (keyVersion < 1) throw new Error("This doesn't look like sealed data.");

  return { keyVersion, iv: raw.slice(5, 17), body: raw.slice(17) };
}

async function open(key, envelopeText, aad) {
  const { iv, body } = parseEnvelope(envelopeText);
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
 *
 * **UUIDs are lowercased here, always.** `household_id` and `record_id` are
 * Postgres `uuid` columns, so the server echoes them lowercase whatever it was
 * sent — and this function is called with the id the client happens to hold
 * when sealing, and with the server's echo when opening. Anything that
 * uppercases a UUID between those two moments produces a value that will not
 * open *in the client that wrote it*. Canonicalising at construction is what
 * makes the two calls agree.
 *
 * `toLowerCase` and not `toLocaleLowerCase`: this has to be the same mapping on
 * every device, and a locale-sensitive one is not.
 */
function aadFor(householdId, recordType, recordId, fieldKey) {
  const parts = [householdId.toLowerCase(), recordType, recordId.toLowerCase(), fieldKey];

  // The separator has to stay a separator. Nothing that reaches here can
  // contain one today — two of these are uuid columns and the third is a fixed
  // vocabulary — so this is about the field key, and about the day someone adds
  // a fifth component and the "nothing contains a pipe" reasoning stops being
  // true. Refusing costs nothing; discovering it later costs a silent collision.
  if (parts.some((part) => part.includes("|"))) {
    throw new Error("A record id or field name may not contain the | character.");
  }
  return encoder.encode(parts.join("|"));
}

/**
 * The passphrase, as bytes, canonically.
 *
 * **NFC first, and only here.** A passphrase is re-typed independently on every
 * client, and "ఖ" or "é" has more than one valid Unicode spelling — a browser
 * IME and an Android IME can emit different bytes for the same keystrokes.
 * PBKDF2 turns a one-byte difference into an entirely different key, so the
 * passphrase would work in one client, fail in the other, be indistinguishable
 * from a typo, and — because there is deliberately no recovery — take the data
 * with it. Normalising makes the same keystrokes derive the same key everywhere.
 *
 * **Nothing else is ever normalised.** See [valueBytes]: a sealed value is bytes
 * one client produced and another must reproduce exactly, so normalising it
 * would silently rewrite what somebody wrote. Two rules, opposite directions,
 * and confusing them corrupts data in one direction or loses it in the other.
 *
 * No trimming either. A trailing space belongs to the passphrase, both clients
 * keep it byte for byte, and the interface warns rather than "helping".
 */
const passphraseBytes = (passphrase) => encoder.encode(passphrase.normalize("NFC"));

async function wrappingKeyFrom(passphrase, salt, iterations) {
  const base = await crypto.subtle.importKey(
    "raw", passphraseBytes(passphrase), "PBKDF2", false, ["deriveKey"],
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

  // Parsed before anything is attempted, and by the same reader the fields use,
  // so an envelope from a newer Almira is refused here rather than being sliced
  // at offsets that may no longer mean what they meant.
  const wrapped = parseEnvelope(stored.wrappedKey);

  let rawContentKey;
  try {
    rawContentKey = new Uint8Array(await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: wrapped.iv }, wrappingKey, wrapped.body,
    ));
  } catch {
    // Only a failure to *decrypt* is a wrong passphrase. A malformed or
    // future-versioned envelope threw above, with its own sentence, because
    // telling someone their passphrase is wrong when it is not is worse than
    // telling them nothing.
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

/**
 * The value, as bytes, **verbatim**.
 *
 * The raw UTF-8 of the string and nothing else: no JSON, no object, no key
 * ordering — so there is no canonicalisation problem to get wrong, and a value
 * that looks like `{"a":1}` is stored as those seven characters and comes back
 * as them. If a sealed value ever needs structure, that is a version-byte bump
 * in the envelope, never a quiet convention (docs/12 §3).
 *
 * Deliberately **not** normalised, unlike [passphraseBytes]. Somebody's note is
 * theirs as typed.
 */
const valueBytes = (text) => encoder.encode(text);

export async function sealField(householdId, recordType, recordId, fieldKey, text) {
  if (!contentKey) throw new Error("Unlock zero-knowledge mode first.");
  const ciphertext = await seal(
    contentKey, valueBytes(text), aadFor(householdId, recordType, recordId, fieldKey),
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

/* -----------------------------------------------------------------------------
   The interop known answer (docs/zk-interop-acceptance.md B4)
   ----------------------------------------------------------------------------- */

/**
 * One fixed value that pins the whole chain, asserted identically in the app's
 * `InteropKatTest`.
 *
 * Everything is fixed — passphrase, salt, iterations, IV, the four AAD
 * components, the plaintext — so the envelope is a constant. A single value,
 * but it covers NFC on the passphrase and UTF-8 after it, the trailing space
 * surviving both, PBKDF2-HMAC-SHA256 at 600 000 rounds, the AAD field order
 * with its UUIDs lowercased, AES-256-GCM with a 128-bit tag, the envelope byte
 * layout, the big-endian key version, and base64url without padding. Any one of
 * those drifting from the app turns this red.
 *
 * Run it from the console — `import('/app/e2e.js').then(m => m.selfTest())` —
 * or from anywhere that wants to know the two clients still agree.
 */
export async function selfTest() {
  const PASSPHRASE = "correct horse battery staple ";
  const SALT = new Uint8Array(16).map((_, i) => i);
  const ITERATIONS = 600000;
  const IV = new Uint8Array(12).map((_, i) => 0xA0 + i);
  const HOUSEHOLD = "58276CAE-2448-4D51-8C9D-29FEFD3225D4";
  const RECORD = "167D9136-E238-48CF-B093-0F51D9A43C8D";
  const FIELD_KEY = "locker_address";
  const PLAINTEXT = "Locker 12, ఖజానా, Kakinada ";

  const EXPECTED_KEY = "17c0b45fe7d3dcc10b70395e28a8cc533a0c8113691b174d39b8a205f2085f6f";
  const EXPECTED_AAD =
    "58276cae-2448-4d51-8c9d-29fefd3225d4|investment|167d9136-e238-48cf-b093-0f51d9a43c8d|locker_address";
  const EXPECTED_ENVELOPE =
    "AQAAAAGgoaKjpKWmp6ipqqvPXvr272LHpln2v1MfVTtWxjXLbZR0eNYAsS5bJYmnCrpDPstqzByPY2RZI1X1WKjF52IsjQ";

  const hex = (bytes) =>
    [...new Uint8Array(bytes)].map((b) => b.toString(16).padStart(2, "0")).join("");

  const base = await crypto.subtle.importKey(
    "raw", passphraseBytes(PASSPHRASE), "PBKDF2", false, ["deriveBits"],
  );
  const keyBits = await crypto.subtle.deriveBits(
    { name: "PBKDF2", hash: "SHA-256", salt: SALT, iterations: ITERATIONS }, base, 256,
  );
  const key = await crypto.subtle.importKey("raw", keyBits, { name: "AES-GCM" }, false, ["encrypt", "decrypt"]);

  const aad = aadFor(HOUSEHOLD, "investment", RECORD, FIELD_KEY);
  const body = new Uint8Array(await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: IV, additionalData: aad }, key, valueBytes(PLAINTEXT),
  ));
  const envelope = new Uint8Array(17 + body.length);
  envelope[0] = 1;
  new DataView(envelope.buffer).setUint32(1, 1, false);
  envelope.set(IV, 5);
  envelope.set(body, 17);

  const checks = [
    ["derived key", hex(keyBits), EXPECTED_KEY],
    ["additional data", decoder.decode(aad), EXPECTED_AAD],
    ["envelope", toBase64Url(envelope), EXPECTED_ENVELOPE],
    // And back again, which is the half a person actually experiences.
    ["round trip", await open(key, EXPECTED_ENVELOPE, aad), PLAINTEXT],
  ];

  const failed = checks.filter(([, actual, expected]) => actual !== expected);
  if (failed.length) {
    throw new Error(
      "The two clients no longer agree:\n" +
        failed.map(([what, actual, expected]) => `  ${what}\n    got      ${actual}\n    expected ${expected}`).join("\n"),
    );
  }
  return { agreed: checks.map(([what]) => what) };
}
