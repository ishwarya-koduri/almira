/* =============================================================================
   The arithmetic of recovery (docs/12 §10), with nothing else in it.

   Pure functions over byte arrays: GF(2^8), Shamir's 2-of-3 split, and the
   printed code. No crypto.subtle, no DOM, no network — so the same file runs
   under a browser and under `jsc -m scripts/check-recovery.js`, and the fixed
   answers it is held to are the ones RecoveryReferenceTest holds the JVM to.

   What a code is, byte by byte (25 bytes, 40 characters, 8 groups of 5):

     type(1)  0x01 recovery key · 0x02 recovery share
     x(1)     0 for a key · 1, 2 or 3 for a share
     body(21) the secret, or this share's bytes of it
     crc(2)   CRC-16/CCITT-FALSE over the 23 bytes before it, big-endian

   in Crockford's base32, which has no I, L, O or U, so a code read aloud or
   copied by hand survives the usual mistakes. The checksum catches a wrong
   character before anything is attempted, which matters because a wrong
   share does not fail — it silently combines into a different secret, and
   only the wrap's GCM tag would say so.
   ============================================================================= */

export const SECRET_BYTES = 21;
export const TYPE_KEY = 0x01;
export const TYPE_SHARE = 0x02;
const ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

/* -----------------------------------------------------------------------------
   GF(2^8) with the AES polynomial x^8 + x^4 + x^3 + x + 1
   ----------------------------------------------------------------------------- */

export function gfMul(a, b) {
  let x = a & 0xff;
  let y = b & 0xff;
  let product = 0;
  while (y) {
    if (y & 1) product ^= x;
    const carry = x & 0x80;
    x = (x << 1) & 0xff;
    if (carry) x ^= 0x1b;
    y >>= 1;
  }
  return product;
}

/** a^254 = a^-1. Square-and-multiply, so there is no table to get wrong. */
export function gfInv(a) {
  if ((a & 0xff) === 0) throw new Error("zero has no inverse");
  let result = 1;
  let base = a & 0xff;
  for (let exponent = 254; exponent > 0; exponent >>= 1) {
    if (exponent & 1) result = gfMul(result, base);
    base = gfMul(base, base);
  }
  return result;
}

/* -----------------------------------------------------------------------------
   Shamir, byte-wise
   ----------------------------------------------------------------------------- */

/**
 * `coefficient(byteIndex, power)` returns a byte. In use it is uniformly
 * random over all 256 values, zero included: leaving zero out would mean a
 * share byte never equals its secret byte, which is information.
 */
export function split(secret, threshold, count, coefficient) {
  if (!(threshold >= 2 && threshold <= count && count <= 255)) throw new Error("bad threshold");
  const coefficients = Array.from(secret, (_, i) =>
    Array.from({ length: threshold - 1 }, (_, p) => coefficient(i, p + 1) & 0xff));
  const shares = [];
  for (let x = 1; x <= count; x++) {
    const y = new Uint8Array(secret.length);
    for (let i = 0; i < secret.length; i++) {
      let value = 0;
      for (let p = threshold - 1; p >= 1; p--) value = gfMul(value ^ coefficients[i][p - 1], x);
      y[i] = value ^ secret[i];
    }
    shares.push({ x, y });
  }
  return shares;
}

/** Lagrange interpolation at zero over the shares given. */
export function combine(shares) {
  const xs = new Set(shares.map((s) => s.x));
  if (shares.length === 0 || xs.size !== shares.length || xs.has(0)) {
    throw new Error("These shares can't be combined — two of them are the same share.");
  }
  const length = shares[0].y.length;
  if (shares.some((s) => s.y.length !== length)) throw new Error("These shares are different lengths.");
  const secret = new Uint8Array(length);
  for (let i = 0; i < length; i++) {
    let value = 0;
    for (let j = 0; j < shares.length; j++) {
      let basis = 1;
      for (let m = 0; m < shares.length; m++) {
        if (m === j) continue;
        basis = gfMul(basis, gfMul(shares[m].x, gfInv(shares[m].x ^ shares[j].x)));
      }
      value ^= gfMul(shares[j].y[i], basis);
    }
    secret[i] = value;
  }
  return secret;
}

/* -----------------------------------------------------------------------------
   The printed code
   ----------------------------------------------------------------------------- */

/** CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF, no reflection, no final xor. */
export function crc16(bytes) {
  let crc = 0xffff;
  for (const byte of bytes) {
    crc ^= byte << 8;
    for (let bit = 0; bit < 8; bit++) {
      crc = crc & 0x8000 ? ((crc << 1) ^ 0x1021) & 0xffff : (crc << 1) & 0xffff;
    }
  }
  return crc;
}

export function base32(bytes) {
  let out = "";
  let buffer = 0;
  let bits = 0;
  for (const byte of bytes) {
    buffer = ((buffer << 8) | byte) & 0xffff;
    bits += 8;
    while (bits >= 5) {
      out += ALPHABET[(buffer >> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) out += ALPHABET[(buffer << (5 - bits)) & 31];
  return out;
}

export function encodeCode(type, x, body) {
  if (body.length !== SECRET_BYTES) throw new Error("a code carries 21 bytes");
  const raw = new Uint8Array(25);
  raw[0] = type;
  raw[1] = x;
  raw.set(body, 2);
  const crc = crc16(raw.subarray(0, 23));
  raw[23] = crc >> 8;
  raw[24] = crc & 0xff;
  return base32(raw).match(/.{5}/g).join("-");
}

/** The code as people will type it: any case, spaces or dashes, O for 0 and I or L for 1. */
export function normaliseCode(text) {
  return String(text).toUpperCase().replace(/[\s-]/g, "").replace(/O/g, "0").replace(/[IL]/g, "1");
}

/**
 * Throws a sentence a person can act on. Never "invalid input": the reader
 * may be copying this off a sheet on the worst day of their year.
 */
export function decodeCode(text) {
  const cleaned = normaliseCode(text);
  if (cleaned.length !== 40) {
    throw new Error(`A code is 40 letters and numbers. This one has ${cleaned.length}.`);
  }
  const raw = new Uint8Array(25);
  let buffer = 0;
  let bits = 0;
  let at = 0;
  for (const character of cleaned) {
    const value = ALPHABET.indexOf(character);
    if (value < 0) throw new Error(`"${character}" never appears in a code. Check that character.`);
    buffer = ((buffer << 5) | value) & 0xffff;
    bits += 5;
    if (bits >= 8) {
      raw[at++] = (buffer >> (bits - 8)) & 0xff;
      bits -= 8;
    }
  }
  if (crc16(raw.subarray(0, 23)) !== ((raw[23] << 8) | raw[24])) {
    throw new Error("One of the characters is wrong. Check the code against the sheet, group by group.");
  }
  if (raw[0] !== TYPE_KEY && raw[0] !== TYPE_SHARE) {
    throw new Error("This code was made by a newer version of Almira.");
  }
  return { type: raw[0], x: raw[1], body: raw.slice(2, 23) };
}

/**
 * The text a QR code on the sheet carries. Uppercase letters, digits and a
 * colon, so a QR encoder can use its compact alphanumeric mode.
 */
export const qrPayload = (code) => `ALMIRA-RECOVERY:${normaliseCode(code)}`;
