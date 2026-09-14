/* =============================================================================
   The web client's recovery arithmetic, without a browser (docs/12 §10).

       /System/Library/Frameworks/JavaScriptCore.framework/Versions/A/Helpers/jsc \
         -m scripts/check-recovery.js
       # or: node scripts/check-recovery.js  (as an ES module)

   The same published vectors and the same fixed answers as
   backend/src/test/.../e2e/RecoveryReferenceTest.kt. Those answers were made by
   a third implementation before either client was asked, so this proves the
   browser agrees with the JVM rather than with itself. HKDF and AES-GCM are
   WebCrypto's and are not reachable here; e2e.js carries its own self-test for
   those, against RFC 5869 and the same constants.
   ============================================================================= */

import {
  gfMul, gfInv, split, combine, crc16, base32, encodeCode, decodeCode, normaliseCode, qrPayload,
  TYPE_KEY, TYPE_SHARE, SECRET_BYTES,
} from "../backend/src/main/resources/static/app/recovery-codes.js";

const log = typeof print === "function" ? print : console.log;
let failures = 0;
function expect(label, actual, wanted) {
  const a = JSON.stringify(actual);
  const w = JSON.stringify(wanted);
  if (a === w) log(`  ok   ${label}`);
  else { failures += 1; log(`  FAIL ${label}\n         got    ${a}\n         wanted ${w}`); }
}
function throws(label, action, fragment) {
  try {
    action();
    failures += 1;
    log(`  FAIL ${label}\n         did not throw`);
  } catch (error) {
    if (fragment && !String(error.message).includes(fragment)) {
      failures += 1;
      log(`  FAIL ${label}\n         threw "${error.message}"`);
    } else {
      log(`  ok   ${label}`);
    }
  }
}
const hex = (bytes) => Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
const bytes = (length, f) => Uint8Array.from({ length }, (_, i) => f(i));
// Deterministic, so a failure reproduces; the properties hold for any source.
let seed = 0x2545f491;
const next = () => { seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5; return (seed >>> 0) & 0xff; };

log("GF(2^8) — FIPS-197");
expect("{57}·{83} = {c1}", gfMul(0x57, 0x83), 0xc1);
expect("{57}·{13} = {fe}", gfMul(0x57, 0x13), 0xfe);
expect("{53}⁻¹ = {ca}", gfInv(0x53), 0xca);
let inverses = true;
for (let a = 1; a < 256; a++) if (gfMul(a, gfInv(a)) !== 1) inverses = false;
expect("a · a⁻¹ = 1 for every non-zero a", inverses, true);

log("\nThe checksum and the alphabet");
expect("CRC-16/CCITT-FALSE of \"123456789\" is 0x29B1", crc16(Array.from("123456789", (c) => c.charCodeAt(0))), 0x29b1);
expect("\"foobar\" in Crockford base32", base32(Array.from("foobar", (c) => c.charCodeAt(0))), "CSQPYRK1E8");

log("\nShamir, 2 of 3");
let roundTrips = true;
for (let n = 0; n < 300; n++) {
  const secret = bytes(SECRET_BYTES, next);
  const shares = split(secret, 2, 3, next);
  for (const [a, b] of [[0, 1], [0, 2], [1, 2], [2, 0]]) {
    if (hex(combine([shares[a], shares[b]])) !== hex(secret)) roundTrips = false;
  }
}
expect("any two of three shares give back the secret (300 random secrets)", roundTrips, true);

let uniform = true;
for (let x = 1; x <= 3; x++) {
  for (let s = 0; s < 256; s++) {
    const seen = new Set();
    for (let a = 0; a < 256; a++) seen.add(split(Uint8Array.of(s), 2, 3, () => a)[x - 1].y[0]);
    if (seen.size !== 256) uniform = false;
  }
}
expect("one share alone says nothing: a → share byte is a bijection for every secret byte", uniform, true);
throws("the same share twice is refused", () => combine([{ x: 1, y: new Uint8Array(21) }, { x: 1, y: new Uint8Array(21) }]), "same share");

log("\nThe fixed answers RecoveryReferenceTest asserts");
const secret = bytes(21, (i) => i);
const shares = split(secret, 2, 3, (i) => 0xa0 + i);
expect("share bytes", shares.map((s) => hex(s.y)), [
  "a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0",
  "5b585d5e57545152434045464f4c494a6b686d6e67",
  "fbf9fffdf3f1f7f5ebe9efede3e1e7e5dbd9dfddd3",
]);
expect("the recovery key's code", encodeCode(TYPE_KEY, 0, secret), "04000-0820C-20A1G-7104G-M2RC1-M70Y4-0H289-H8JCE");
expect("the share codes", shares.map((s) => encodeCode(TYPE_SHARE, s.x, s.y)), [
  "080T1-850M2-GA185-0M2GA-1850M-2GA18-50M2G-A0JJ3",
  "0815P-P2XBS-BN8MA-J8D04-AHJF9-H4MMT-V8DNQ-6E5SC",
  "081ZQ-YFZZQ-SZ3XZ-NXFMY-ZVF3W-7KYBP-YSVZE-X7EZW",
]);
expect("two typed share codes combine back to the secret",
  hex(combine(["0815p p2xbs bn8ma j8do4 ahjf9 h4mmt v8dnq 6e5sc", "081ZQ-YFZZQ-SZ3XZ-NXFMY-ZVF3W-7KYBP-YSVZE-X7EZW"]
    .map(decodeCode).map((c) => ({ x: c.x, y: c.body })))),
  hex(secret));

log("\nA code as people copy it");
const code = encodeCode(TYPE_KEY, 0, secret);
expect("lower case, spaces, O for 0, l for 1", hex(decodeCode(code.toLowerCase().replace(/-/g, " ").replace(/0/g, "o").replace(/1/g, "l")).body), hex(secret));
const plain = normaliseCode(code);
let caught = 0;
let total = 0;
for (let position = 0; position < plain.length; position++) {
  for (const replacement of "0123456789ABCDEFGHJKMNPQRSTVWXYZ") {
    if (replacement === plain[position]) continue;
    total += 1;
    try { decodeCode(plain.slice(0, position) + replacement + plain.slice(position + 1)); } catch { caught += 1; }
  }
}
expect(`every single-character typo is caught (${total})`, caught, total);
throws("a short code says how long it is", () => decodeCode(plain.slice(1)), "40");
throws("a character that is never in a code is named", () => decodeCode(plain.slice(1) + "U"), "\"U\"");
expect("the QR payload", qrPayload(code), "ALMIRA-RECOVERY:040000820C20A1G7104GM2RC1M70Y40H289H8JCE");

if (failures > 0) {
  log(`\n${failures} failing`);
  throw new Error(`${failures} failing`);
}
log("\nall passing");
