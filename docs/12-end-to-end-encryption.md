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
| `recordType` | one of `investment`, `liability`, `account`, `member`, `estate_document` | a closed vocabulary the server enforces |
| `recordId` | uuid | the record |
| `fieldKey` | 1–64 characters, non-blank | chosen by the client, not by the server |

There is no registry of sealable field names. `fieldKey` is whatever the client
calls it — `locker_address`, `who_holds_it` — and the server stores it without
opinion. That is deliberate: a server-side list of sealable fields would be a
server-side statement about what the sealed data *is*, which is the property
being sold.

Two limits apply to the ciphertext:

- **64 000 characters** of base64url, enforced by the server. That is 47 967
  plaintext bytes once the header, tag and base64 expansion are taken off — the
  number the acceptance matrix in §9 exercises exactly.
- **17 bytes decoded minimum**, so that a client which posts plain text where
  ciphertext belongs is rejected rather than stored.

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
construction. The server does **not** enforce this on `fieldKey` — see
`docs/known-issues.md` — which is unexploitable today only because the three
components before it cannot contain a pipe, and stops being unexploitable the
day a fifth component is added.

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

**The write is atomic, and it has to be.** There is no recovery in this scheme,
so a vault left with a new salt and an old wrapped key is not a bug to fix on
Monday — it is every sealed field in that household, gone. Three things make a
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
**zero** rows of `sealed_values`, and no row of `e2e_keys` may contain the
passphrase or a plaintext verifier. This is a grep against the live database,
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

[‹ Index](README.md)
