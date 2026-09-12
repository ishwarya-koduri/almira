# Zero-knowledge interop — acceptance spec

**Status: DRAFT, for reconciliation.** Written before any interop code, to be
merged with the criteria written independently from the other side. Nothing is
built against this until both versions agree.

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

## Part B — Open. Decisions needed before code.

Each of these is a way to ship two clients that both work and cannot read each
other. None of them is covered by docs/12.

### B1 · Passphrase normalisation — the dangerous one

**Today:** neither client normalises. The web does `TextEncoder().encode(passphrase)`
on whatever the DOM holds.

**Why it matters here more than elsewhere:** this app is English, Telugu and
Hindi. Indic text, and any accented Latin, has more than one valid Unicode
representation of the same visible string. A browser IME and an Android IME can
emit different ones for the same keystrokes. The result is a passphrase that
works in one client, fails in the other, is indistinguishable from a typo, and
because there is no recovery by design, the data is **gone**.

**Recommendation:** normalise to **NFC** immediately before UTF-8 encoding, on
every client, in the same change. Requires one line in `e2e.js`. Nothing real
is sealed yet, so the migration cost is zero today and unbounded later.

- [ ] **Decision:** NFC / NFKC / none. If none, we accept the above knowingly.

### B2 · Passphrase → bytes on Android

**Today:** the JVM reference uses `PBEKeySpec(passphrase.toCharArray(), …)` with
`PBKDF2WithHmacSHA256`. How a `char[]` becomes bytes is **provider-specific**,
and Android's provider is not the JDK's.

**Recommendation:** the app does not use `PBEKeySpec`. It encodes the passphrase
to UTF-8 itself and runs PBKDF2-HMAC-SHA256 over those bytes via `Mac`, so the
encoding is ours and identical by construction. Proven against a published
RFC-style test vector **and** against a vector generated by the web client, both
committed as tests.

- [ ] **Decision:** own PBKDF2 over explicit UTF-8 bytes (recommended) vs
      `PBEKeySpec` plus a proving test.

### B3 · UUID case in the AAD

**Today:** `household_id` and `record_id` are Postgres `uuid` columns, so the
server **always echoes lowercase**, whatever the client sent. The web builds the
AAD from the id it happens to hold when sealing, and from the server's echo when
opening. Those differ the moment anything uppercases a UUID.

**Recommendation:** the AAD uses the **canonical lowercase hyphenated** form,
always, on both sides, and each client lowercases before building it.

- [ ] **Decision:** confirm lowercase-canonical, and add it to docs/12 §4 so it
      is specified rather than emergent.

### B4 · `fieldKey` is byte-exact

`field_key` is `text`: the server echoes it verbatim, and it is inside the AAD.
`Locker` and `locker` are different fields that cannot open each other.

- [ ] **Decision:** pin a convention — lowercase `snake_case`, ≤ 64 characters —
      and treat the key as opaque and exact everywhere.

### B5 · What exactly is encrypted — and the JSON trap

**Today, and this is the good news:** the plaintext is the **raw UTF-8 of the
field's string**. `encoder.encode(text)`. There is no JSON, no object, no key
ordering, and therefore no canonicalisation problem at all.

- [ ] **Decision:** state in docs/12 that a sealed value **is a string**, that
      no client may wrap it in JSON "for convenience", and that if a structured
      sealed value is ever needed it is a **format change requiring a version
      byte bump**, not a quiet convention.

### B6 · The version byte is not checked everywhere

`open()` refuses an unknown version. The `wrappedKey` path in `unlock()` slices
straight past it.

- [ ] **Decision:** one envelope parser, used for all three envelope kinds,
      refusing any version but `0x01`.

### B7 · `keyVersion` inside the wrap envelopes

The web writes the **current** key version into the `wrappedKey` and `verifier`
envelopes. The JVM reference hard-codes `1`. Only one wrapped key exists at a
time, so nothing reads it — which is exactly why it will drift.

- [ ] **Decision:** the field equals the key version it belongs to; readers must
      not use it to select a key.

### B8 · Empty and very large values

An empty string seals to a 33-byte envelope and the server accepts it. 64000
base64 characters is roughly 47 KB of plaintext.

- [ ] **Decision:** does the app allow sealing an empty value, or is that a
      delete? What does it show at the ceiling — and does it stop the person
      before they lose their typing?

### B9 · Where the content key lives on the device

docs/12 §7 asks for Keychain/Keystore behind a biometric prompt. The app now has
exactly that machinery from the previous stage.

- [ ] **Decision:** the content key is held under the same RSA-KEK envelope as
      the session tokens, dropped by the same `forget()` on background, and
      re-derived by passphrase when the Keystore copy is unavailable. It is
      never in plain SharedPreferences and never in a log.

---

## Part C — The acceptance tests

Each is a real round trip against the running stack, not a unit test of our own
assumptions. **Every positive is paired with the negative that proves the
mechanism**, because a positive alone cannot tell a working AAD from an ignored
one.

### C1 · The decisive pair

- [ ] **Web seals, app opens.** Enable on the web with passphrase *P*, seal a
      field on an investment, sign in on the app, unlock with *P*, read it back
      byte-identical — including a value with Telugu text, an emoji, and a
      trailing space.
- [ ] **App seals, web opens.** The reverse, same record, different field.

### C2 · Negatives that prove the mechanism

- [ ] **Moved ciphertext fails.** Take a sealed value, write it to another
      `recordId` (or `fieldKey`) directly in the database, and confirm **both**
      clients refuse it. Proves the AAD is real and not decorative.
- [ ] **Wrong passphrase fails at the verifier**, cleanly, with the wrong-
      passphrase message and no change to stored state.
- [ ] **A flipped tag byte fails.** One bit in the last 16 bytes, and the value
      is unreadable rather than wrong.
- [ ] **Version `0x02` is refused** by both clients rather than guessed at.
- [ ] **The server never holds the plaintext.** Seal a distinctive string, then
      `grep` the database dump for it and find nothing.

### C3 · Rotation

- [ ] Rotate on the web; the app unlocks with the **new** passphrase, opens a
      field sealed **before** rotation, and the old passphrase no longer works.
- [ ] `keyVersion` increments and the app records it.

### C4 · Lifecycle, since this app now has a lock

- [ ] Backgrounding drops the content key with the session key, and returning
      asks for the biometric prompt; if the content key was held in the
      Keystore, unlocking restores it without re-asking for the passphrase.
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
