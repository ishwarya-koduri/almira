[‹ Index](README.md)

# 12 · Zero-knowledge mode — the scheme

This document exists so a second client can implement the **same** scheme. A
format only the web client understands is a format that loses data the first
time somebody switches devices, and this is the one feature where losing data is
irreversible by design.

Scope: the **optional** zero-knowledge mode from [Doc 05 §4](05-security-and-privacy.md#4-encryption).
Everything else in Almira is encrypted with a key the server can reach — envelope
encryption with a per-household DEK, which protects a stolen database and
nothing else. This is the other kind: **the server has no means to open these
fields**, and the tests prove it by going looking for the plaintext in the
database.

---

## 1. What is sealed, and what is not

Sealed: individual **field values** the person chooses — a locker address, the
name of the person holding a key, a note about who owes what and why.

Not sealed: the record's title, its value, its type, who owns it. Those drive
totals, search and reports, and pretending otherwise would produce an app that
appears to work and quietly cannot.

The consequences are stated in the UI, not buried:

- a sealed field **cannot be searched, sorted or OCR'd** on the server;
- there is **no recovery** — a forgotten passphrase means the data is gone, and
  that is exactly what "the server cannot read it" costs when it is true;
- everything else about the record is unchanged.

---

## 2. Keys

```
passphrase ──PBKDF2──▶ wrapping key ──AES-GCM──▶ [ wrapped content key ]  → server
                                                        │
                             random 256-bit content key ─┘ (never leaves the client)
                                                        │
                                                        └──AES-GCM──▶ sealed field values → server
```

**Wrapping key** — derived from the passphrase, used for nothing but wrapping.

| | |
|---|---|
| KDF | `PBKDF2` with `HMAC-SHA-256` |
| Iterations | **600 000** for new keys; the server refuses anything below **100 000** |
| Salt | 16 random bytes, per user per household, stored in `e2e_keys.kdf_salt` |
| Output | 256 bits, imported as an AES-GCM key |

**The passphrase, as bytes** — **NFC, then UTF-8, and nothing else.**

A passphrase is re-typed independently on every client, and "ఖ" or "é" has more
than one valid Unicode spelling: a browser IME and an Android IME can emit
different bytes for the same keystrokes. PBKDF2 turns one differing byte into an
entirely different key, so without this the passphrase works in one client,
fails in the other, is indistinguishable from a typo, and — because there is no
recovery — takes the data with it.

Nothing is **trimmed**: a trailing space belongs to the passphrase, both clients
keep it byte for byte, and the interface says so rather than quietly helping.

Derive from those bytes with a PBKDF2 the client controls — **not** a
`PBEKeySpec`/`SecretKeyFactory` pair, whose char-to-byte step belongs to the
platform provider and is not the same on Android as on the JVM. The one step
that decides whether two clients agree is not delegated.

**Content key** — 256 random bits from `crypto.getRandomValues`. Every sealed
value is encrypted under this key, so changing the passphrase rewraps one small
blob instead of rewriting every field.

**Verifier** — the constant string `almira` encrypted under the content key with
no AAD, stored alongside the wrapped key. A client that decrypts it successfully
knows the passphrase was right, without having to fetch and attempt a record.

Both wrapped key and verifier use the envelope format in §3 with no AAD.

The server stores `kdf`, `kdf_salt`, `iterations`, `wrap_algorithm`,
`wrapped_key`, `verifier`, `key_version` — and never the passphrase, the
wrapping key or the content key.

---

## 3. The envelope

Every ciphertext — wrapped key, verifier and each sealed field — is the same
byte layout, **base64url without padding**:

```
┌────────┬──────────────┬──────────┬────────────────────────────┐
│ version│  keyVersion  │    iv    │  ciphertext ‖ GCM tag      │
│ 1 byte │   4 bytes BE │ 12 bytes │  n bytes ‖ 16 bytes        │
└────────┴──────────────┴──────────┴────────────────────────────┘
   0x01      1, 2, 3…      random
```

- **version** is the format version. It is `1`. A client that meets a version it
  does not know must refuse to guess.
- **keyVersion** matches `e2e_keys.key_version`, so a client can tell that a
  value was written under an older content key.
- **iv** is 12 random bytes per encryption. Never reused.
- AES-GCM with a **128-bit tag**, which is what WebCrypto produces by default.

**What the plaintext is.** A sealed value is the **raw UTF-8 bytes of a
string** — no JSON, no object, no key ordering, and therefore no canonical-form
problem to get wrong. A value that looks like `{"a":1}` is stored as those seven
characters and comes back as them, because no client parses it.

And unlike the passphrase above, a value is **never normalised**. Those are two
rules pointing opposite ways and it matters which is which: the passphrase is
canonicalised so that two clients derive one key, while a value is bytes one
client produced that another must reproduce exactly, so touching it would
silently rewrite what somebody wrote.

If a sealed value ever needs structure, that is a **new version byte** with both
clients taught to read it — never a convention agreed in a comment.

---

## 4. Additional authenticated data

Every **field** ciphertext is bound to the exact place it lives:

```
AAD = "{householdId}|{recordType}|{recordId}|{fieldKey}"   (UTF-8)
```

Without this, anyone able to write the database could move a ciphertext to
another record and have the client decrypt it there — the same reasoning as the
server-side envelope's `householdId|table.column` binding. With it, a moved
value fails to open, which is the correct outcome and is covered by a test.

The wrapped key and the verifier use **no AAD**: they are not tied to a record.

---

## 5. The exchange

```
GET    /api/v1/households/{id}/e2e                        → status + key envelope + caveats
PUT    /api/v1/households/{id}/e2e/key                    → store or rotate the wrapped key
GET    /api/v1/households/{id}/e2e/values?recordType&recordId
PUT    /api/v1/households/{id}/e2e/values/{recordType}/{recordId}/{fieldKey}
DELETE /api/v1/households/{id}/e2e/values/{recordType}/{recordId}/{fieldKey}
```

The server performs exactly one check on what it is given: that it is
well-formed base64 of a plausible length. Anything more would require
understanding the contents, which is the property being sold. That check exists
for one failure mode — a client bug that posts the note in the clear while the
interface says it is sealed — and it is tested.

A sealed value is as visible as the record it belongs to: the rows pass the same
row-level security predicate, because *which* records have sealed fields is
itself information.

---

## 6. Rotating the passphrase

1. Unlock with the old passphrase and hold the content key.
2. Derive a new wrapping key from the new passphrase with a **new salt**.
3. Wrap the same content key; increment `key_version`.
4. `PUT /e2e/key`.

No field is rewritten, because none needs to be. A client that wants to
re-encrypt under a genuinely new content key must read, decrypt, re-encrypt and
write every value — which is a migration, not a rotation, and should show
progress rather than pretending to be instant.

---

## 7. Implementing it elsewhere

WebCrypto (browser), and the equivalents on Android and iOS:

| Step | WebCrypto | Kotlin/JVM | Swift |
|---|---|---|---|
| Derive | `deriveBits({name:'PBKDF2', hash:'SHA-256', salt, iterations})` | `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")` | `CryptoKit` + `CommonCrypto` PBKDF2 |
| Encrypt | `encrypt({name:'AES-GCM', iv, additionalData}, key, data)` | `Cipher.getInstance("AES/GCM/NoPadding")` + `updateAAD` | `AES.GCM.seal(_:using:authenticating:)` |
| Random | `crypto.getRandomValues` | `SecureRandom` | `SystemRandomNumberGenerator` |

The backend test suite (`E2eApiTest`) implements the client half on the JVM and
round-trips it through the real API — so the Kotlin column above is not a
suggestion, it is running code you can copy.

**Key storage on a device.** The web client holds the content key in memory for
the session only and never writes it to `localStorage`; the passphrase is asked
for again after a reload. A native app should hold it in the Keychain or
Keystore behind a biometric prompt, which is strictly better and is the reason
[Doc 05 §2](05-security-and-privacy.md) treats the browser as the weaker surface.

---

## 8. What this does not defend against

Stated plainly, because a security feature that oversells itself is worse than
none:

- **A compromised client.** Malicious JavaScript served to the browser, or a
  device with malware, sees the passphrase as the user types it. Zero-knowledge
  storage does not survive a hostile endpoint.
- **A malicious server that changes the code it serves.** The web client is
  delivered by the same server that stores the ciphertext; a server that wanted
  the plaintext could ship a build that exfiltrates it. This is the fundamental
  limit of browser-delivered end-to-end encryption, it applies to every product
  in this category, and the honest mitigations are a native client with a
  signed, reviewable binary and — eventually — reproducible builds.
- **Metadata.** That a field is sealed, when it was written, how long it is, and
  which record it belongs to are all visible to the server.
- **Traffic analysis** and anything else outside the storage boundary.

[‹ Index](README.md)
