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

**Concretely**, a sealed value is a row in `sealed_values`, addressed by four
components and nothing else:

| | | |
|---|---|---|
| `householdId` | uuid | the household the record belongs to |
| `recordType` | one of `investment`, `liability`, `account`, `member`, `estate_document`, `document` | a closed vocabulary the server enforces |
| `recordId` | uuid | the record |
| `fieldKey` | 1–64 characters, non-blank, no `\|` | chosen by the client, not by the server |

There is no registry of sealable field names. `fieldKey` is whatever the client
calls it — `locker_address`, `who_holds_it` — and the server stores it without
opinion. That is deliberate: a server-side list of sealable fields would be a
server-side statement about what the sealed data *is*, which is the property
being sold.

Two limits apply to the ciphertext:

- **64 000 characters** of base64url, enforced by the server. That is 47 967
  plaintext bytes once the header, tag and base64 expansion are taken off — the
  number the acceptance matrix in §9 exercises exactly.
- **The shape of an envelope** (§3): at least **33 bytes** decoded, version
  byte `1`, key version ≥ 1 — so that a client which posts plain text where
  ciphertext belongs is rejected rather than stored. The floor was 17 bytes
  until [Doc 20](20-where-and-who.md) put "key with Amma" into a sealed field:
  17 is a header with no tag, and let through any 23-character run of letters
  that happened to be valid base64. The table carries the same 33-byte floor
  (`ciphertext_is_at_least_an_envelope`, 44 base64 characters) underneath.

The consequences are stated in the UI, not buried:

- a sealed field **cannot be searched, sorted or OCR'd** on the server;
- **the server cannot recover it** — a forgotten passphrase means the data is
  gone, unless the person made a recovery sheet or recovery shares (§10), which
  are made on the device and never reach the server;
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
fails in the other, is indistinguishable from a typo, and — because the server
cannot recover it — takes the data with it.

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
`wrapped_key`, `verifier`, `key_version`, and (since V55) `content_key_id`
(§10.4) — and never the passphrase, the wrapping key or the content key.

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

**The smallest legal envelope is 33 bytes** — 1 + 4 + 12 header, plus a 16-byte
tag and no ciphertext at all. That is what sealing an **empty string** produces,
and it is legal: an empty sealed value is a value. A reader that requires the
body to be *longer* than the tag rejects it, which looks exactly like corruption
and is not. This is not hypothetical — it is the bug the iOS bridge shipped with
for an hour, caught only because the acceptance matrix carries an empty value.

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

**The two uuids are lowercased, on seal and on open, whatever case they arrive
in.** `household_id` and `record_id` are Postgres `uuid` columns, so the server
echoes them lowercase regardless of what it was sent. A client that seals with
an uppercase id it happens to be holding, and opens with the server's echo,
produces a value that will not open **in the client that wrote it**, with
nothing to explain why. Lowercase with the locale-independent mapping: on a
Turkish device the locale-sensitive one is a different answer.

`recordType` and `fieldKey` are used **verbatim** — not lowercased, not trimmed.

**No component may contain `|` (U+007C).** The separator is not escaped, so a
component holding one would make the AAD ambiguous. Both clients refuse at AAD
construction, and the server refuses a `fieldKey` holding one with
`400 field_invalid` (known-issues 7, closed), so the rule holds where the data
lands and not only in the clients that happen to exist today.

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

GET    /api/v1/households/{id}/e2e/recovery                   → your recovery copies (§10)
GET    /api/v1/households/{id}/e2e/recovery/members/{memberId} → someone's, under an open emergency window
PUT    /api/v1/households/{id}/e2e/recovery/{kind}            → make or replace a copy (step-up)
DELETE /api/v1/households/{id}/e2e/recovery/{kind}            → remove a copy (step-up)
POST   /api/v1/households/{id}/e2e/recovery/{kind}/practice   → record a practice unlock
```

The server performs exactly one check on what it is given: that it is
well-formed base64 shaped like an envelope — long enough, version `1`, a key
version of at least 1 (§1). Anything more would require
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
4. `PUT /e2e/key`, with `contentKeyId` (§10.4).

No field is rewritten, because none needs to be. A client that wants to
re-encrypt under a genuinely new content key must read, decrypt, re-encrypt and
write every value — which is a migration, not a rotation, and should show
progress rather than pretending to be instant.

**The write is atomic, and it has to be.** The server cannot recover anything,
and a recovery copy (§10) exists only if the person made one, so a vault left
with a new salt and an old wrapped key is not a bug to fix on Monday — it is
every sealed field in that household, gone. Three things make a
torn write impossible rather than unlikely:

1. The whole rotation is **one request**. The client sends salt, iterations,
   wrapped key, verifier and version together.
2. The server writes it as **one `insert … on conflict do update`** touching one
   row. Postgres makes a single statement atomic by itself.
3. That statement sits inside **one `@Transactional`**, which also carries the
   audit row, so the record of the rotation cannot outlive the rotation.

And the client proves the old passphrase *before* any of it, so a rotation
started with a typo fails before a byte is written.

Asserted by `a rotation interrupted before commit leaves the old key untouched`:
the real service method runs inside a transaction that is then rolled back —
which is what a process dying mid-request looks like — and every column is
checked to be byte-for-byte what it was, with the original passphrase still
unwrapping the original content key.

**The case that is *not* covered, and cannot be:** a rotation that commits and
whose response never reaches the client. No data is lost — the new passphrase
works — but the client reports a failure and the person will try the old one and
be refused. That is a confusing five minutes, not a loss, and the interface
should say so: *if a rotation reports a failure, try the new passphrase before
assuming nothing changed.*

---

## 7. Implementing it elsewhere

WebCrypto (browser), and the equivalents on Android and iOS:

| Step | WebCrypto | Kotlin/JVM & Android | Kotlin/Native (iOS) |
|---|---|---|---|
| Derive | `deriveBits({name:'PBKDF2', hash:'SHA-256', salt, iterations})` | **RFC 8018 §5.2 over `Mac("HmacSHA256")`** | **RFC 8018 §5.2 over `CCHmac`** (`platform.CoreCrypto`) |
| Encrypt | `encrypt({name:'AES-GCM', iv, additionalData}, key, data)` | `Cipher.getInstance("AES/GCM/NoPadding")` + `updateAAD` | **Swift `AES.GCM.seal(_:using:nonce:authenticating:)`, injected** |
| Random | `crypto.getRandomValues` | `SecureRandom` | `SecRandomCopyBytes` |

Three of those cells are not the obvious answer, and each was a finding:

- **Not `SecretKeyFactory`/`PBEKeySpec`.** Its char-to-byte step belongs to the
  platform provider and is not the same on Android as on the JVM — which would
  put the one step that decides whether two clients agree outside our control.
  §2 says this; an earlier version of this table contradicted it.
- **Not `CCKeyDerivationPBKDF`** on iOS. Its password parameter maps to a Kotlin
  `String?`, which hands the same text-to-bytes decision to the interop layer
  and cannot carry a NUL besides.
- **AES-GCM on iOS cannot come from Kotlin at all.** CryptoKit is Swift-only and
  unreachable from Kotlin/Native, and the public CommonCrypto headers in the iOS
  SDK expose no GCM whatsoever — the entry points people remember are in
  `CommonCryptorSPI.h`, which the SDK does not ship. So it is injected from
  Swift at startup. Anyone implementing a fourth client on Apple platforms will
  meet this and should not spend the afternoon we spent.

The backend suite (`E2eApiTest`) implements the client half on the JVM and
round-trips it through the real API, and `app/shared` implements it for real on
both mobile targets — so none of the columns above is a suggestion.

**Key storage on a device.** The web client holds the content key in memory for
the session only and never writes it to `localStorage`; the passphrase is asked
for again after a reload. **A native app does the same, and must not do better.**

An earlier version of this document suggested keeping it in the Keychain or
Keystore behind a biometric prompt, on the grounds that hardware-backed storage
is stronger than a variable. That was wrong, and wrong in the way that quietly
removes the feature: it would make *device compromise plus a biometric* enough
to read sealed fields. The passphrase is a **second secret the device never
holds** — that is the whole difference between "sealed" and "stored somewhere
convenient", and it is why a phone that can restore the session with a
fingerprint still cannot show a sealed note.

So on every client the content key lives in memory while the app is in front,
is dropped when it leaves, and is derived again from the passphrase next time.
The two clients then have the same security posture and not merely the same
ciphertext. [Doc 05 §2](05-security-and-privacy.md) still treats the browser as
the weaker surface, for the delivery reason in §8 below rather than for key
storage.

---

## 8. Acceptance — what must be true, and how it is proved

This section is the contract for cross-client interop. It is normative: a client
that does not satisfy it is not a client, however plausible its output looks.

### 8.1 The conformance vector

Every value fixed, so the answer is a constant rather than a round trip. These
bytes were produced by the shipped web client in a browser and are asserted
against, unchanged, by every other implementation.

| | |
|---|---|
| passphrase | `correct horse battery staple ` — **with the trailing space** |
| salt | the 16 bytes `00 01 02 … 0f` |
| iterations | 600 000 |
| iv | the 12 bytes `a0 a1 a2 … ab` |
| householdId | `58276CAE-2448-4D51-8C9D-29FEFD3225D4` — **supplied uppercase on purpose** |
| recordType | `investment` |
| recordId | `167D9136-E238-48CF-B093-0F51D9A43C8D` — likewise |
| fieldKey | `locker_address` |
| plaintext | `Locker 12, ఖజానా, Kakinada ` — Telugu, and a trailing space |

must produce

```
derived key  17c0b45fe7d3dcc10b70395e28a8cc533a0c8113691b174d39b8a205f2085f6f
AAD          58276cae-2448-4d51-8c9d-29fefd3225d4|investment|167d9136-e238-48cf-b093-0f51d9a43c8d|locker_address
envelope     AQAAAAGgoaKjpKWmp6ipqqvPXvr272LHpln2v1MfVTtWxjXLbZR0eNYAsS5bJYmnCrpDPstqzByPY2RZI1X1WKjF52IsjQ
```

One value, and it pins all of it: NFC on the passphrase, UTF-8 after it, the
trailing space surviving both, PBKDF2-HMAC-SHA256 at 600 000 rounds, the AAD
field order, **the uuids lowercased inside it**, AES-256-GCM with a 128-bit tag,
the envelope byte layout, big-endian key version, and base64url without padding.

**Assert against these constants, never against a fresh round trip of your
own.** A round trip proves a client agrees with itself, which is exactly the
failure being looked for: two implementations can each be internally consistent
and disagree with each other. If a round trip passes while this vector fails,
something is compensating, and a compensating difference in key derivation is
the one failure that cannot be recovered from.

### 8.2 The value shapes that must survive

Not a sample. Each of these has broken a real implementation:

| Shape | Why it is in the list |
|---|---|
| Telugu with a trailing space | NFC on the value would silently rewrite it; a trim would lose the space |
| an emoji | a client that counts UTF-16 units instead of bytes truncates it |
| `{"a":1}` | a client that parses values would turn seven characters into an object |
| **the empty string** | a 33-byte envelope; a body-longer-than-tag check rejects it |
| three spaces | a trim would turn it into the empty string |
| 47 967 bytes | exactly 64 000 base64 characters — the server's ceiling |

### 8.3 The failure cases, and exactly what each must do

Nothing here may throw past the caller and take a screen down: one unreadable
value is one unreadable value.

| Case | What the client must do |
|---|---|
| **Wrong passphrase** | The verifier fails to decrypt. Report *"That passphrase doesn't open this. Nothing has been changed."* Nothing is fetched, nothing is written, and the content key is not set. A passphrase differing by one byte — the same words without the trailing space — must fail here. |
| **Truncated or corrupt payload** | `parse` refuses: fewer than 33 bytes, not base64, or a `keyVersion` of 0 or negative. Surface as unreadable, in caution colour, beside the field. Never as a decryption success. |
| **Moved ciphertext** | A byte-identical row under a different `recordId` or `fieldKey` fails the AAD and must refuse. This is the only thing that distinguishes a working AAD from one being silently ignored, so it is a required test and not an optional one. |
| **Unknown envelope version** | The version byte is not `1`. Refuse, and say *"sealed by a newer version of Almira"* — a thing to explain, not a thing to apologise for. |

**"A field written by an older client" needs splitting in two, because the two
halves behave oppositely and conflating them produces a wrong test:**

- **An older *envelope version*** — does not exist. Version `1` is the first and
  only format. There is nothing older to read, and the only defined behaviour is
  the refusal above for versions it does not know. A test that claims to
  exercise "an older client" by writing version `0` is testing the malformed
  path, and should say so.
- **An older *`keyVersion`*** — **opens normally, and must.** Rotating a
  passphrase rewraps the *same* content key under a new wrapping key and
  increments `key_version`; it does not re-encrypt a single field. So values
  written before a rotation carry a lower `keyVersion` and decrypt with the
  current content key exactly as they always did. `keyVersion` is carried so a
  client can *tell*, never so it can *choose* — a reader that selects a key on
  this field is a reader that breaks on the first rotation.

  The test that matters: seal a value, rotate the passphrase, and open that
  value with the new passphrase. It must open, and its `keyVersion` must still
  be the old number.

### 8.4 And the server holds none of it

After any acceptance run, the plaintext of every sealed value must appear in
**zero** rows of `sealed_values`, no row of `e2e_keys` may contain the
passphrase or a plaintext verifier, and no row of `e2e_recovery_wraps`,
`e2e_recovery_slots` or `activity_log` may contain a recovery code, a share or
the secret behind them (§10.8). This is a grep against the live database,
not an inspection of the code.

### 8.5 What has been proved, and on what

| | |
|---|---|
| web → Android, all six shapes, live API | done |
| Android → web | done |
| web → iOS, all six shapes, live API | done |
| iOS → web | done |
| the vector in 9.1 on JVM, Android, Kotlin/Native and the browser | done, byte-identical |
| moved ciphertext refused, both clients | done |
| wrong passphrase refused, both clients | done |
| truncated payload, stored and read through the live API | done |
| a value sealed before a rotation, opened after it — web, app, and the JVM reference | done |
| the app reading values across a rotation the **web** performed, and the reverse | done |
| docs/12 checked against the code that implements it, on every test run | `scripts/check-spec.py`, 31 assertions |

**How the rotation case was proved**, since it is the one that cannot be
discovered late. On the live stack, with eight real sealed values written before
any rotation:

- the JVM reference seals at `keyVersion` 1, rotates to a new passphrase with a
  new salt, recovers the **same** content key from the new passphrase, and opens
  the pre-rotation value — whose envelope still says `keyVersion` 1;
- the app rotates, is refused by the old passphrase, opens all eight
  pre-rotation fields under the new one, and rotates back;
- the web does the same, independently, and all eight open on both sides of it;
- and each client opens values across a rotation **the other one performed** —
  the household went through key versions 1 → 7 during the run and every value
  still opens under the passphrase this document names.

Two things that went wrong while proving it, both worth keeping:

- The reference implementation this document points at as "code you can copy"
  was deriving its key with `PBEKeySpec`/`SecretKeyFactory` — the API §2 forbids
  by name. It passed for years because this suite only ever round-tripped its
  own output, and a reference half that agrees with itself proves nothing. It
  now derives the way §7 says and asserts the §8.1 vector, so it is pinned to
  the browser's bytes rather than to its own.
- The first rotation run reported 7 of 8 fields keeping their key version. That
  was the harness holding a baseline captured before it re-sealed one field, not
  a rotation defect — but it is a fair warning about how easily this particular
  check is written wrongly.

---

## 9. What this does not defend against

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
- **Silent corruption in storage, backup or restore.** The server cannot
  validate a ciphertext beyond its shape, because reading it is the thing it
  cannot do. So a flipped bit in a backup, or a restore that truncates a column,
  is invisible until somebody opens that field months later and it fails — and
  by then the good copy may be gone. See below: this is addressable, and is not
  addressed yet.
- **Traffic analysis** and anything else outside the storage boundary.

### On detecting corruption without weakening the scheme

The question is whether storing a length and a checksum beside each envelope
would close the gap above. It would, mostly, and it leaks nothing — but the
reasoning needs two corrections.

**It leaks nothing. That part is right.** Both are functions of bytes the server
already holds. The ciphertext is in front of it; it can already measure the
length and compute any digest it likes. Recording either adds no information the
server did not have, so there is no confidentiality cost at all.

**But it is not an integrity control against an attacker.** Anyone who can
change a ciphertext can recompute a checksum over the new bytes. What defends
against deliberate tampering is the GCM tag, which already exists and which only
the key holder can forge. A stored checksum defends against *accidents* —
bit-rot, a truncated column, a bad restore — and its value is entirely in
**when** you find out, not in whether an adversary can get away with something.

**And "verified on write" buys nothing.** A checksum the server computes from
what it just received will always match what it just received; the comparison is
tautological. Value only exists on the read or restore side, comparing today's
bytes against a digest recorded earlier. A checksum supplied by the *client*
would be a different proposition — but that is a change to the frozen v1
request body, so it is off the table.

So, in order of what actually helps:

1. **Turn on Postgres page checksums.** This is the layer whose job this is, and
   on the development stack `data_checksums` is **off** — the `initdb` default.
   It must be decided before the production database has data: enabling it later
   needs the server stopped and `pg_checksums --enable` run over every page.
   Bigger win than anything at the application layer, and free.
2. **Verify structure after every restore.** Every `ciphertext` must be valid
   base64url, at least 33 bytes, version byte `1`, key version ≥ 1. No schema
   change, no new column, and it catches truncation and header damage — the
   most likely restore failures. This belongs in the restore runbook, because a
   restore that silently returns unopenable fields is the worst version of this.
3. **Then, if still wanted, a stored digest.** Server-computed SHA-256 of the
   ciphertext, written alongside it, compared during restore verification. It is
   additive, internal, and needs no API change. It closes the one case the
   structural sweep cannot: a flipped bit inside the body, which parses
   perfectly and simply will not open.

Only the first two are cheap enough to be obvious. The third is real, and it is
worth doing once there is a production database whose backups matter.

---

## 10. Recovery

A forgotten passphrase used to be the end of everything sealed, and a family
could read "where the will is" only if someone had told them the passphrase.
Both are the same missing thing: a second way to reach the content key that is
not the passphrase. §10 adds it without the server learning anything. Built in
V55, the web client and the backend; the native app does not make or use copies
yet (known-issues 24).

### 10.1 What exists

Up to **two copies** of the content key per person per household, each optional:

| `kind` | what the person keeps | opens with |
|---|---|---|
| `recovery_key` | a **recovery sheet**: one 40-character code, printed, kept with the will | the code |
| `recovery_shares` | **three recovery shares**: three codes, one for each of three people | any two of them |

Each copy is the content key wrapped under a key derived from a **secret the
server never receives**, exactly as the passphrase wrap is (§2). A copy is not a
second content key: every sealed value, and every rotation, stays as it was.

### 10.2 The secret and the code

The secret is **21 random bytes** (168 bits) from `crypto.getRandomValues`, a
different one for each copy. A code is 25 bytes written in Crockford's base32
(`0123456789ABCDEFGHJKMNPQRSTVWXYZ`, no I, L, O or U), 40 characters, printed
as 8 groups of 5:

| bytes | |
|---|---|
| 0 | type: `0x01` recovery sheet, `0x02` share |
| 1 | x: `0` for a sheet, `1`, `2` or `3` for a share |
| 2–22 | the secret (sheet) or this share's 21 bytes (share) |
| 23–24 | CRC-16/CCITT-FALSE (poly `0x1021`, init `0xFFFF`) of bytes 0–22, big-endian |

Reading one: upper-case, drop spaces and dashes, read `O` as `0` and `I`/`L` as
`1`. A wrong length, a character outside the alphabet, or a checksum mismatch is
refused **before** anything is derived, with a sentence that says which. Every
single-character substitution is caught (a character is 5 bits, a burst CRC-16
always detects). A QR code on the sheet would carry
`ALMIRA-RECOVERY:` followed by the 40 characters; the web client does not draw
one yet (known-issues 24).

### 10.3 Shares

Shamir's secret sharing, **2 of 3, byte by byte over GF(2⁸)** with the AES
polynomial x⁸ + x⁴ + x³ + x + 1:

- For each secret byte `s`, one coefficient `a`, **uniform over all 256 values,
  zero included**. Share `x` is `s ⊕ a·x` for x = 1, 2, 3. Excluding zero
  would make a share byte never equal its secret byte, which is information.
- Combining is Lagrange interpolation at zero over whichever shares are given:
  `s = Σ yⱼ · Π_{m≠j} xₘ / (xₘ ⊕ xⱼ)`. Inversion is `a²⁵⁴`, square-and-multiply,
  with no tables.
- Two shares from **different** sets, or a share typed into the wrong slot,
  do not fail: they combine into a different 21 bytes. So a combined secret is
  never trusted until the wrap's GCM tag accepts it, and then the verifier and
  the key id confirm it (§10.4). That is also why the checksum sits on each
  share: a typo is caught on the typo, not as a vague "doesn't open".

One share alone says nothing: for any secret byte and any x ≠ 0 the map a → share
byte is a bijection, so every secret is equally likely. Both implementations test
exactly that for all 256 × 256 × 3 cases.

### 10.4 Keys, wraps, and keeping them consistent

| | |
|---|---|
| KDF | **HKDF-SHA256** (RFC 5869), not PBKDF2: stretching slows guesses at something a person chose, and nobody chose 168 random bits |
| salt | 16 random bytes per copy, `kdf_salt` |
| info | UTF-8 `almira recovery v1\|{kind}`, so a sheet's secret can never open the shares' copy |
| output | 256 bits, imported as an AES-GCM key |
| `wrapped_key` | the §3 envelope of the raw content key, no AAD |
| `verifier` | the §3 envelope of `almira` under the content key, no AAD |
| `content_key_id` | HMAC-SHA256(content key, `almira content key id v1`), first 16 bytes, base64url: 22 characters |

The **content key id** is a PRF output: it says *which* key without saying
anything *about* it, which lets the server keep copies consistent without being
able to open any of them. It is enforced in two places, both under a lock on the
person's `e2e_keys` row so neither can interleave with the other:

- **A copy of the wrong key is refused.** `PUT /e2e/recovery/{kind}` answers
  `409 recovery_key_mismatch` when its `contentKeyId` is not the one the
  passphrase row records. A row that recorded none (written before V55, or by a
  client that does not send it) takes the first copy's id; the client unlocked
  before it could wrap the key.
- **The key cannot move out from under a copy.** While any copy exists,
  `PUT /e2e/key` must carry `contentKeyId` equal to the copies'. A rotation
  (§6) does, and every copy keeps opening. A different key, or a write that does
  not say, answers `409 recovery_copies_would_break` and changes nothing. A key
  write without the field when no copies exist stores the id as unknown rather
  than leaving a stale one.

A copy and its holders are written in **one transaction**, so neither exists
without the other. Making, replacing and removing a copy need a **step-up**
(`403 step_up_required`): a copy is a second way into everything sealed, and a
borrowed unlocked browser should be enough neither to make one and walk away with
the paper nor to swap the one in the drawer for a dud. Replacing resets
`practiced_at`; the old paper stops working, and the interface says so.

**The order on the device matters.** The web client makes the secret, the wrap
and the codes, shows the codes to print, and saves the copy only after the
person says they have printed or written them down. The secret is wiped from
the function's memory before it returns; the codes stay in the open sheet's
closure and go when it closes.

**Practice** (`POST …/{kind}/practice`) happens on the device: decode, combine,
derive, unwrap, check the verifier and the key id, then record the date. The
server cannot check it happened, because checking would need the key, so the
interface says "you practised", never "we confirmed". It changes nothing else,
not even the lock state.

**Forgot the passphrase:** open the copy, derive a new passphrase wrap with a new
salt, and `PUT /e2e/key` at `keyVersion + 1` with the same `contentKeyId`. That
is §6 with the copy in place of the old passphrase, so it is one atomic write,
and the other copy keeps working.

### 10.5 The family

- `e2e_recovery_wraps` is readable by its owner and by the person holding an
  **open emergency window on that person** (`app.emergency_open_on_user`, the
  same conditions as V25, narrowed to one subject). Nobody but the owner can
  write one. `GET /e2e/recovery/members/{memberId}` answers `404` to anyone
  else, whether or not copies exist.
- `e2e_recovery_slots` holds `holders` — roles as the family says them, "Amma",
  "our lawyer" — and `practiced_at`. **Plain text on purpose**, readable by
  household members who can see a value that person sealed (the check runs
  under the reader's own row-level security), never by a guest. The interface
  asks for a role, never a place.
- A sealed line in the handbook, its PDF, the where-and-who index and the
  emergency view is **never blank**: "Sealed by Ishwarya · our lawyer keeps the
  recovery sheet · ask our lawyer". No pronouns: the server does not know them.
- With the sheet or two shares, the person under the window opens the key in
  memory, beside their own, and reads what that person sealed on the records the
  window shows. Lock or a reload drops it. They can read; they cannot rewrite
  the value, which stays the sealer's (docs/20 §5).

### 10.6 Fixed answers

Asserted identically by `RecoveryReferenceTest` (JVM), `scripts/check-recovery.js`
(the web client's arithmetic, under `jsc -m`) and `recoverySelfTest()` in
`e2e.js` (WebCrypto, in a browser). They were produced by a third, independent
implementation first, for the reason in §8.1.

| | |
|---|---|
| GF(2⁸) | `57·83 = c1`, `57·13 = fe`, `53⁻¹ = ca` (FIPS-197) |
| HKDF | RFC 5869 test case 1 |
| CRC-16/CCITT-FALSE | `"123456789"` → `29b1` |
| base32 | `"foobar"` → `CSQPYRK1E8` |
| secret | the 21 bytes `00 01 … 14`; share coefficient for byte i is `a0 + i` |
| sheet code | `04000-0820C-20A1G-7104G-M2RC1-M70Y4-0H289-H8JCE` |
| share codes | `080T1-850M2-GA185-0M2GA-1850M-2GA18-50M2G-A0JJ3`, `0815P-P2XBS-BN8MA-J8D04-AHJF9-H4MMT-V8DNQ-6E5SC`, `081ZQ-YFZZQ-SZ3XZ-NXFMY-ZVF3W-7KYBP-YSVZE-X7EZW` |
| wrapping key, salt `00 … 0f` | sheet `94b5b1c89dd4196ad9b6de8cdd669f446013310874b9eb4d0bcea169811061ce`, shares `716457295b3df062e884456a9d4d37945b5cc88f5390a0da4d5045d3d50beb64` |
| key id of the key `40 41 … 5f` | `VrqqDb7VVhHIkN5-ZUdCvQ` |

### 10.7 What this changes about §9

Recovery adds ways in, so it adds ways to lose. Stated as plainly as §9:

- **The paper is the key.** Anyone with the sheet, or any two shares, can open
  everything the person sealed, forever, until the copy is replaced or removed.
  That is the feature. Where the paper lives is the person's decision, and the
  interface says so where it is made.
- **Two share holders can collude.** 2-of-3 means exactly that.
- **Who holds a share is not sealed.** A member who can see something the person
  sealed learns "Amma holds a recovery share". A database dump learns it too.
  That is why the field asks for a role and is refused over 60 characters.
- **A server that serves hostile JavaScript sees the code as it is typed**, the
  same limit §9 states for the passphrase, and no worse.
- **A malicious or broken server can delete or corrupt a copy.** It cannot
  forge one that opens (it has no key to wrap), and it cannot make a good secret
  open a dud without the tag refusing. What it can do is make the sheet stop
  working, silently. Practising is the defence, which is why the interface asks
  for it yearly, and V55 extends V31's stored digests and the restore sweep to
  the copies.
- **Nothing to stretch, nothing to guess.** 168 bits is beyond search, so HKDF's
  lack of a work factor costs nothing. A code read aloud loses no entropy to the
  checksum, which is extra bytes, not part of the secret.
- **An open emergency window plus the sheet opens it.** Also the feature. A
  window alone does not: the wrap is useless without the secret, and a trusted
  contact with no paper reads the same "ask our lawyer" as anyone else.

### 10.8 What is verified, and what is not

**Verified:** `RecoveryApiTest` (the JVM half through the real API: a sheet and
shares open the key; no code, share or secret appears in any recovery row or the
audit log; creating, replacing and removing need a step-up; practising changes
no wrap column; a rotation keeps both copies opening; a different key, or a
write without the id, is refused and changes nothing; a copy of the wrong key is
refused; a forgotten passphrase is replaced from the sheet and a sealed value
opens again; someone else's copies are 404 until a window opens and then open
with the sheet; malformed bodies are refused without echo). `RecoveryReferenceTest`
and `scripts/check-recovery.js` (§10.6, the round trips, single-share uniformity,
every single-character typo). `SealedAccessApiTest` (the sentences, in the
handbook, its PDF and the index, only to someone who can see the value). The SQL
privacy suite (who reads and writes each table, the veto, guests). In a browser
against a local server: `selfTest()` and `recoverySelfTest()` pass; a sheet was
made, saved after a step-up, practised with a typo (refused) and in lower case
with `l` for `1` (a tick); "Forgot it?" set a new passphrase from the sheet and
the old one was refused; a spouse under an open window opened the location, the
key holder and a sealed note with the sheet.

**Not verified:** the native app (it neither makes nor uses copies); the printed
page on paper (the print stylesheet was not sent to a printer); a QR code (not
drawn); the recovery screens at phone width, in Telugu or Hindi (their strings
fall back to English), and at 200% text.

[‹ Index](README.md)
