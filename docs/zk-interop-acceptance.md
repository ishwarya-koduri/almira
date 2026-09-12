# Zero-knowledge interop — acceptance spec

**Status: AGREED v1.** Reconciled from two independently written sets of
criteria. Part B is now nine rulings, not nine questions.

**The hard gate:** nothing real is sealed on either client until **B1 and B2
have landed on both**. B1 is the only decision whose cost goes from *one line in
`e2e.js`* to *silently unrecoverable data* the instant a real field is
encrypted. Everything else can be worked deliberately once the passphrase
derives the same key on both sides.

**Settle order:** B1 + B2 together (they are one change) → B3 → B5 into docs/12
→ B6 / B7 → B4 / B8 / B9.

**Landed so far:** B1, B2, B3 and B5, on both clients, with vectors and
negatives — see the end of Part B.

**What "done" means, in one sentence:** the Android app opens a field the web
client sealed, and the web client opens a field the app sealed, with the same
passphrase and no coordination beyond this document.

Everything below is either **frozen** — already true in running code, and the
app must match it exactly — or **open**, meaning docs/12 does not say, the two
implementations happen to agree today by accident, and a decision is needed
before either of us writes a line. The open items are the whole reason this
document exists: every one of them produces two clients that both appear to
work and can never read each other.

Sources, in order of authority where they disagree:
`backend/src/main/resources/static/app/e2e.js` (the running web client) ·
`backend/.../e2e/SealedFieldService.kt` (what the server accepts) ·
`backend/src/test/.../E2eApiTest.kt` (the JVM reference half) ·
`docs/12-end-to-end-encryption.md` (the scheme as written).

---

## Part A — Frozen. The app matches these or it is wrong.

### A0 · Every constant, as a number

Written out rather than left as "whatever the code does", because a constant
that lives only in an implementation is a constant the other client guesses at.

| Constant | Value |
|---|---|
| KDF | PBKDF2 with HMAC-SHA-256 |
| Iterations, new key | `600000` |
| Iterations, existing key | the server's stored `iterations` (never a constant) |
| Iterations, server floor | `100000`; below this the server returns `400` |
| Salt length | exactly `16` bytes |
| Salt source | CSPRNG (`crypto.getRandomValues` / `SecureRandom`) |
| Derived key length | `256` bits, used directly as the AES key |
| Content key length | `256` bits, CSPRNG |
| Cipher | `AES-256-GCM`, `NoPadding` |
| IV length | `12` bytes, fresh per encryption, CSPRNG |
| GCM tag length | `128` bits, **appended** to the ciphertext, never detached |
| Envelope: version | offset `0`, length `1`, value `0x01` |
| Envelope: keyVersion | offset `1`, length `4`, **big-endian unsigned** |
| Envelope: IV | offset `5`, length `12` |
| Envelope: body | offset `17` to end — ciphertext ‖ 16-byte tag |
| Envelope: header size | `17` bytes; smallest whole envelope `33` bytes |
| Transport encoding | base64url, **no padding**, on emit; padded or not accepted on read |
| AAD field order | `householdId`, `recordType`, `recordId`, `fieldKey` — this order, always |
| AAD separator | `\|` U+007C, no escaping, fields may not contain it |
| AAD encoding | UTF-8 of the joined string, **no normalisation** |
| AAD on wrapped key and verifier | **none** |
| Verifier plaintext | the 6 ASCII bytes `almira` |
| Passphrase encoding | NFC, then UTF-8 — see B1 |
| Value plaintext | raw UTF-8 of the string, never normalised — see B1, B5 |

### A1 · Key derivation

| | |
|---|---|
| KDF | PBKDF2 with HMAC-SHA-256 |
| Output | 256 bits, used directly as an AES-GCM key |
| Iterations, **new** keys | `600000` |
| Iterations, **existing** keys | whatever `GET /e2e` returns in `key.iterations` — **never the constant** |
| Server floor | rejects `iterations < 100000` with `400` |
| Salt | 16 random bytes, base64url-unpadded in `kdfSalt`; server rejects fewer than 16 decoded bytes |
| Salt lifetime | new salt on enable **and** on every passphrase rotation; never reused across rotations |

- [ ] Unlock derives with the server's `iterations`, not `600000`.
- [ ] Enable and rotate each generate a fresh 16-byte salt from a CSPRNG.
- [ ] The derived 256 bits are used as the AES key with no further hashing.

### A2 · The two keys

- **Wrapping key** — from the passphrase. Used for exactly one thing: wrapping
  the content key. Never touches a field.
- **Content key** — 256 random bits, generated on the client, never sent. Every
  sealed field is under this key. Rotation rewraps *this*, so no field is
  rewritten.

- [ ] `wrappedKey` = envelope over the content key's **raw 32 bytes**, sealed
      with the **wrapping key**, **no AAD**.
- [ ] `verifier` = envelope over the 6 ASCII bytes `almira`, sealed with the
      **content key**, **no AAD**.
- [ ] The content key is never written to disk in the clear, never logged, and
      never sent.

### A3 · The envelope

```
┌─────────┬──────────────┬──────────┬──────────────────────────┐
│ version │  keyVersion  │    iv    │  ciphertext ‖ GCM tag    │
│ 1 byte  │  4 bytes BE  │ 12 bytes │  n bytes  ‖  16 bytes    │
└─────────┴──────────────┴──────────┴──────────────────────────┘
   0x01     unsigned        random          AES-256-GCM
```

- [ ] Cipher is `AES/GCM/NoPadding`, 256-bit key, **128-bit tag**, tag appended
      to the ciphertext (which is what both WebCrypto and JCE produce).
- [ ] IV is 12 fresh CSPRNG bytes for **every** encryption. Never derived,
      never counted, never reused.
- [ ] `keyVersion` is written big-endian unsigned at offset 1.
- [ ] Transport encoding is **base64url without padding**. The app **emits**
      unpadded and **accepts** padded or not, because the server decodes with
      both alphabets.
- [ ] Header is 17 bytes; body starts at offset 17. Minimum whole envelope is
      33 bytes (empty plaintext + tag).

### A4 · Additional authenticated data

```
AAD = UTF-8 of  "{householdId}|{recordType}|{recordId}|{fieldKey}"
```

- [ ] Every **field** envelope carries it.
- [ ] `wrappedKey` and `verifier` carry **none** — they belong to no record.
- [ ] A value moved to another record, another field, or another household
      fails to open. This is a required behaviour, not an accident.

### A5 · Proving the passphrase before use

The order is load-bearing and the app must not shorten it:

1. `GET /e2e` → `enabled`, `key.{kdfSalt, iterations, wrappedKey, verifier, keyVersion}`.
2. Derive the wrapping key from the passphrase, that salt, those iterations.
3. Decrypt `wrappedKey` → the content key's raw bytes.
4. Import as AES-256.
5. **Open `verifier` and check it is exactly `almira`.**
6. Only now is the session unlocked.

- [ ] Step 5 is present. Step 3 succeeding is not sufficient: it proves the
      wrap opened, not that the key is usable, and skipping it moves the first
      failure to a real record.
- [ ] A failure at step 3 or 5 is reported as *wrong passphrase*, in the
      server's own register — not as a decryption error pointed at someone's
      records — and changes nothing.

### A6 · What the server will and will not accept

| | |
|---|---|
| `recordType` ∈ | `investment`, `liability`, `account`, `member`, `estate_document` |
| `fieldKey` | non-blank, ≤ 64 characters |
| `ciphertext` | valid base64 (url or standard), ≥ 17 decoded bytes, ≤ 64000 **characters** of base64 |
| `kdfSalt` | ≥ 16 decoded bytes |
| `wrappedKey` | ≥ 28 decoded bytes |
| `verifier` | ≥ 16 decoded bytes |
| `kdf` | stored; defaults to `PBKDF2-SHA256` |
| `wrapAlgorithm` | stored; defaults to `AES-GCM-256` |

- [ ] The app sends `kdf: "PBKDF2-SHA256"` and `wrapAlgorithm: "AES-GCM-256"`
      explicitly rather than relying on the defaults, and **refuses to unlock**
      if the server returns anything else — a future scheme must not be opened
      by guessing.

---

## Part B — Decided. Nine rulings.

Each of these was a way to ship two clients that both work and cannot read each
other. None is covered by docs/12 as written; all are now settled here.

### B1 · Passphrase normalisation — **DECIDED, LANDED**

> **Normalise the passphrase to NFC before UTF-8. Never normalise a value.**

Two rules pointing opposite ways, and confusing them loses data in one
direction or corrupts it in the other:

- **The passphrase** is re-typed independently on every client. "ఖ" and "é" each
  have more than one valid Unicode spelling, and a browser IME and an Android
  IME can emit different bytes for the same keystrokes. PBKDF2 turns one
  differing byte into an entirely different key — a passphrase that works in the
  browser, fails on the phone, is indistinguishable from a typo, and by design
  cannot be recovered. So it is canonicalised: **NFC, then UTF-8**.
- **A sealed value** is bytes one client produced that another must reproduce
  exactly. Normalising it on the way in or out would silently rewrite what
  somebody wrote. So there is no normalisation anywhere near a value, ever, and
  both implementations carry a comment saying why, so nobody adds one for
  symmetry.

**Whitespace:** nothing is trimmed, on either client, at either end. A trailing
space belongs to the passphrase. The interface warns — "Spaces count, including
one at the end" — rather than quietly "helping", because a space removed by one
client and not the other is the same unrecoverable failure as a mismatched
Unicode form.

### B2 · Deriving the key — **DECIDED, LANDED**

> **Never `PBEKeySpec`. Feed canonical bytes to a PBKDF2 we control.**

`PBEKeySpec` takes a `char[]` and leaves the char→byte step to the provider;
Android's provider is not the JDK's, and the encoding it picks is not part of
any contract we can point at. That step is exactly the one that decides whether
two clients derive the same key, so it is not delegated: the passphrase arrives
as bytes already canonicalised by B1, and PBKDF2-HMAC-SHA256 (RFC 8018 §5.2) is
written out over a `Mac` that only ever does HMAC-SHA256 — an unambiguous
primitive.

**Proven by two kinds of vector, because they prove different things:** a
published PBKDF2-HMAC-SHA256 answer proves the arithmetic; a vector generated by
running the shipped `e2e.js` derivation in a real browser proves the two clients
*agree*, which is the part that matters. An implementation can be textbook-
correct and still disagree with its counterpart about what bytes the passphrase
was.

### B3 · UUID case in the AAD — **DECIDED, LANDED**

> **UUIDs in the AAD are lowercased, on seal and on open, on both clients,
> whatever the source.**

`household_id` and `record_id` are Postgres `uuid` columns, so the server always
echoes lowercase. Each client builds the AAD from the id it holds when sealing
and from the server's echo when opening; anything that uppercases a UUID between
those two moments produces a value that will not open **in the client that wrote
it**. Canonicalise at AAD construction.

**Required negative:** seal with an uppercased UUID in the AAD and confirm it
does not open against the lowercase echo — proving the canonicalisation is
load-bearing rather than incidental.

**The separator is enforced, not merely forbidden.** Both clients refuse any
component containing `|` at AAD construction, before anything can be sealed
with it.

On whether a collision is reachable today: it is not. Of the four components,
`householdId` and `recordId` are Postgres `uuid` columns and `recordType` is a
five-word vocabulary, so none of the three that *precede* the free one can hold
a pipe — the first three separators always delimit exactly, and a `fieldKey`
full of pipes still parses unambiguously. The enforcement is for the day a
fifth component is added and that reasoning quietly stops being true, and
because a rule stated in A0 and checked nowhere is a rule that is already
half-gone. See docs/known-issues.md for the server's side of this.

### B4 · `fieldKey` is byte-exact — **DECIDED**

`field_key` is `text`: echoed verbatim and inside the AAD, so `Locker` and
`locker` are different fields that cannot open each other. Convention: lowercase
`snake_case`, ≤ 64 characters, treated as opaque and exact.

**Required:** a checked-in known-answer test — fixed passphrase, salt and AAD
inputs producing exact expected bytes, asserted in **both** clients. A
derivation that is "compatible" without a KAT is compatible until it is not.

### B5 · What is encrypted — **DECIDED, LANDED**

> **The plaintext is the raw UTF-8 bytes of the value's string. Wrapping it in
> anything is a version-byte bump, never a convention.**

This was the thing most feared and it turns out to be the good news: there is no
JSON, no object, no key ordering, and therefore no canonicalisation problem at
all. It now has to be *stated* in docs/12 so no client later wraps a value "for
convenience" and breaks the other.

**Required test:** seal a value that looks like JSON — `{"a":1}` — and confirm it
round-trips as that literal seven-character string, proving no client parses it.

Landed. The docs/12 sentence went in early, with B1, because the two rules —
normalise the passphrase, never the value — only make sense written together.
What landed here is the code and the proof: a named `SealedValue` pair on the
app so the rules have somewhere to live and a test has something to hold, the
byte encoding agreed with the browser vector by vector, and the round trip run
through the **deployed** envelope rather than a copy of it.

### B6 · The version byte — **DECIDED**

> **Read it, and refuse anything but `0x01`. Fail closed.**

`open()` already does; the `wrappedKey` path in `unlock()` slices straight past
it. One envelope parser, used for all three envelope kinds.

**Required negative:** a `wrappedKey` carrying an unknown version byte is
refused, not best-effort parsed. This is what makes a future format bump safe
instead of silently misread.

### B7 · `keyVersion` inside the wrap envelopes — **DECIDED**

The web writes the current key version; the JVM reference hard-codes `1`. Only
one wrapped key exists at a time, so nothing reads it — which is precisely why it
will drift, and the first rotation is when that is discovered. Pin: the field
equals the key version it belongs to, readers must not use it to select a key,
and web and JVM are asserted to stamp and expect the same thing.

### B8 · Empty, whitespace, and the ceiling — **DECIDED**

Add to the value matrix alongside the Telugu, emoji and trailing-space cases:
the empty string, a whitespace-only string, and a value at the 64000-base64-
character ceiling. Empty and maximum are where off-by-one envelope arithmetic
lives.

### B9 · Where the content key lives on the device — **DECIDED: memory only**

> **Session-scoped, in memory, re-derived from the passphrase. Never persisted —
> not even Keystore-wrapped.**

This is the one where the tempting answer is wrong. The app now has RSA-KEK /
Keystore machinery from the previous stage, so wrapping the content key with it
looks natural — and it would make *device compromise plus a biometric* enough to
read zero-knowledge fields, which destroys the whole point. The passphrase is a
**second secret the device never holds**. Keep the derived key in memory while
unlocked, drop it on `onStop` exactly as the token data key is dropped, and
re-derive on next use — matching the web's posture, so the two clients have the
same security properties and not merely the same ciphertext.

**Definition of done for B9 includes the documentation fix.** docs/12 §7 still
says "hold it in the Keychain or Keystore behind a biometric prompt, which is
strictly better" — which this ruling makes wrong, because it would mean device
plus biometric is enough to read sealed fields. Correcting that sentence is part
of B9 landing, not a note attached to it: a spec that contradicts the document
it was read from is a trap for whoever reads the document next.

---

### What has landed

**B1 and B2, on both clients, in one change.** Web: `passphraseBytes()` applies
NFC before UTF-8 and `valueBytes()` documents that values never are; the
passphrase field warns that spaces count. App: `PassphraseKey` in `commonMain`
with `expect normalizeNfc` / `expect pbkdf2HmacSha256`, and an Android actual
that writes PBKDF2 out over `Mac`.

Seven JVM tests and four on-device tests, all passing:

| Vector | Value |
|---|---|
| Published `("password","salt",4096,32)` | `c5e478d5…aa98134a` |
| Web-generated, NFC precomposed *and* decomposed | `935d4178…a61333d3` |
| Web-generated, Telugu `ఖజానా తాళం` | `3d93d05a…7e042c03` |
| **Control** — decomposed with no NFC | `ab55994b…1f7a58b2` — *different, as it must be* |

The control is the one that matters: it asserts the failure being prevented, so
a normalisation quietly removed later turns that test green and the pair above
red, which is the alarm we want.

Run on the emulator against Android's own provider as well as the JDK's, because
"a different implementation of the same standard" is the exact shape of
assumption that has cost this project time twice already. 600 000 iterations take
**523 ms** on the emulator.

**B3, on both clients.** Six more tests on the app and the same three checks run
against the deployed `e2e.js` in a browser. Both produce the identical AAD from
an uppercase id:

```
58276cae-2448-4d51-8c9d-29fefd3225d4|investment|167d9136-e238-48cf-b093-0f51d9a43c8d|locker_address
```

Proved with a real AEAD rather than string comparison — comparing two AAD byte
arrays only shows that a file agrees with itself. A value sealed while holding
an uppercase id **opens** against the server's lowercase echo; the AAD a
non-canonicalising client would build **fails the GCM tag** against that same
echo; a component carrying `|` is refused before anything is sealed.

**B5, on both clients.** The plaintext encoding, agreed vector by vector with
the shipped `valueBytes`:

| Value | Bytes |
|---|---|
| `{"a":1}` | `7b2261223a317d` — seven characters, not an object |
| `ఖజానా తాళం` | `e0b096…e0b082` |
| `🔐 locker ` | `f09f9490206c6f636b657220` — trailing space kept |
| `""` | *(empty)* |
| `"   "` | `202020` |
| `cafe` + U+0301 | `63616665cc81` — **still decomposed** |

That last row is the mirror of B1 and the reason both rules are written down:
the passphrase folds to one form, a value never does. Seven values round-trip
unchanged through the **deployed** browser envelope — including the empty
string and the whitespace-only one — and five app tests assert the same bytes.

---

## Part C — The acceptance tests

Each is a real round trip against the running stack, not a unit test of our own
assumptions. **Every positive is paired with the negative that proves the
mechanism**, because a positive alone cannot tell a working AAD from an ignored
one.

### C1 · The decisive pair

- [ ] **Web seals, app opens.** Enable on the web with passphrase *P*, seal a
      field on an investment, sign in on the app, unlock with *P*, read it back
      byte-identical — across a value matrix of: Telugu text, an emoji, a
      trailing space, the **empty string**, a **whitespace-only** string, one
      that **looks like JSON**, and one at the **64000-character ceiling** (B8).
- [ ] **App seals, web opens.** The reverse, same record, different field.

### C2 · Negatives that prove the mechanism

- [ ] **Moved ciphertext fails.** Take a sealed value, write it to another
      `recordId` (or `fieldKey`) directly in the database, and confirm **both**
      clients refuse it. Proves the AAD is real and not decorative.
- [ ] **Wrong passphrase fails at the verifier**, cleanly, with the wrong-
      passphrase message and no change to stored state.
- [ ] **Wrong passphrase fails clean.** No partial plaintext, no garbage shown
      as a note, no state changed — it stops at the verifier (A5 step 5).
- [ ] **A flipped ciphertext byte fails the GCM tag.** One bit anywhere in the
      body, and the value is unreadable rather than wrong.
- [ ] **Version `0x02` is refused** by both clients rather than guessed at,
      including on the `wrappedKey` path (B6).
- [ ] **An uppercased UUID in the AAD does not open** against the server's
      lowercase echo (B3).
- [ ] **A value that looks like JSON round-trips as a string.** `{"a":1}` comes
      back as those seven characters, proving no client parses it (B5).
- [ ] **The cross-normalisation proof.** Seal on the web with a combining-mark
      passphrase, open on the app where the IME produces the precomposed form —
      succeeds. With a **control** showing the same pair fails without NFC, so
      B1's fix is demonstrably load-bearing rather than asserted.
      *Derivation half already proven; the seal-and-open half waits for the ZK
      stage.*
- [ ] **The server never holds the plaintext.** Seal a distinctive string, then
      `grep` the database dump for it and find nothing.

### C3 · Rotation

- [ ] Rotate on the web; the app unlocks with the **new** passphrase, opens a
      field sealed **before** rotation, and the old passphrase no longer works.
- [ ] `keyVersion` increments and the app records it.

### C4 · Lifecycle, since this app now has a lock

- [ ] Backgrounding drops the content key exactly as it drops the session data
      key, and returning asks for the **passphrase** again — not merely the
      biometric. Per B9 the content key is never persisted, so a biometric alone
      must not be enough to read a sealed field.
- [ ] Force-stop and relaunch does **not** silently unlock sealed fields.
- [ ] A locked session shows sealed fields as locked, never as empty — an empty
      field reads as "there is nothing here", which is a different and wrong
      statement.

---

## Part D — Explicitly not in this stage

- Sealing anything that is not a field value. Titles, amounts, types and
  ownership stay in the clear by design (docs/12 §1), and the app must not
  invent a way to seal them.
- Recovery. There is none, and the app says so before the passphrase is set,
  not after.
- Re-encrypting every field under a new content key. That is a migration with
  progress, not a rotation (docs/12 §6).
- Anything in docs/12 §8: a compromised device, a hostile server shipping a
  hostile web build, metadata, traffic analysis.
