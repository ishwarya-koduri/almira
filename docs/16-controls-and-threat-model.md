[‹ Index](README.md)

# 16 · Controls and threat model

*The companion to [Doc 15](15-security-whitepaper.md), written for an auditor or
a reviewer. The whitepaper says what is true; this says where to look and how to
check it.*

---

## 1 · System description

| | |
|---|---|
| **What it is** | A private registry of a household's assets, liabilities, documents and estate paperwork |
| **What it never does** | Move money, hold funds, or store a bank/broker credential |
| **Backend** | Kotlin 2.1 / Spring Boot 3.5 on JDK 21; PostgreSQL 16; Redis for one-time codes and rate limits |
| **Clients** | A framework-free web client served from the backend. A native app is planned and is not in scope here |
| **Tenancy** | Multi-tenant by household, enforced in the database |
| **Data classes** | Identifiers (phone), financial records, document contents, estate instruments, sealed (zero-knowledge) fields |

### Trust boundaries

```
 person ──TLS──▶ web client ──bearer token──▶ API ──almira_app──▶ PostgreSQL (RLS)
                     │                          │                      │
             passphrase stays here        KEK outside the DB     ciphertext at rest
                     │                          │
        sealed fields encrypted here     documents encrypted here → object storage
```

1. **Browser ↔ API** — authenticated by a 15-minute bearer token.
2. **API ↔ database** — the API holds *no* privilege to bypass row-level
   security; identity is per transaction.
3. **Application ↔ key material** — the key-encryption key lives outside the
   database; the zero-knowledge passphrase never leaves the browser.
4. **Household ↔ household** — no cross-household read path exists.
5. **Member ↔ member** — the per-record visibility model, which is the boundary
   most products in this category do not have at all.

---

## 2 · Assets, adversaries, and what stops them

| Asset | Adversary | Defence | Verified by |
|---|---|---|---|
| One member's private holdings | Another member, including an admin | RLS predicate on every table and view; 404 not 403 | `rls_privacy_test.sql`, `PrivacyApiTest` |
| A household's data | Another household's user | `is_household_member` on every policy | SQL suite: "a user in another household sees nothing at all" |
| Account and policy numbers | Anyone with a database dump | Envelope encryption, per-household DEK, KEK outside the DB, AAD binding to household/table/column | `EnvelopeCipherTest`, `AccountApiTest` |
| Document contents | The same | Encrypted before storage; short-lived single-use download tickets after a step-up | `DocumentApiTest` |
| Sealed fields | The operator, a subpoena, a full backup | Client-side AES-GCM under a PBKDF2 key the server never sees | `E2eApiTest` — searches the database for the plaintext |
| Recovery copies of the sealed-field key | The operator, a database dump, a borrowed unlocked session, a member who is not the owner | The copy is wrapped under HKDF of a 168-bit secret printed on the device and never sent; making, replacing or removing one needs a step-up; readable only by the owner and by whoever holds an open emergency window on them; a passphrase write cannot orphan a copy | `RecoveryApiTest` — searches every recovery row and the audit log for the code, the shares and the secret; SQL suite recovery section |
| A session | Token theft, a borrowed unlocked phone | Single-use refresh with reuse-detection (audited in the revoking transaction); step-up for sensitive reads | `AuthApiTest`, `RefreshReuseApiTest` |
| An account | A stranger given a recycled number, a SIM swap | Second factor after the one-time code; factor changes need the factor; two ways in; every new sign-in announced | `SecondFactorApiTest`, `PasskeyApiTest`, `PhoneChangeApiTest` |
| A guest link | Anyone who receives or guesses one | 256-bit token stored hashed; scope materialised at creation; read-only guest transaction; expiry, view cap, revocation | `ShareApiTest`, SQL suite guest section |
| Emergency access | A trusted contact acting too early, or a coercive one | Waiting period, owner veto, **inactivity requirement**, notifications, audit, continuity-only reveal, read-only | `EmergencyAccessApiTest`, SQL suite emergency section |
| The audit trail | The application itself | `almira_app` has INSERT only on `activity_log` | `R__grants.sql`; SQL suite |
| Imported provider data | A provider returning too much, or the wrong household's | Imports arrive at the household's default visibility; the AA sandbox proves the consent gate | `ProviderApiTest` |
| A statement's password, a photographed paper | The operator, any third-party service | Opened and read in the browser by vendored pdf.js and tesseract.js; only the rows or words the person keeps are sent; no library is fetched from anywhere else | `ClientVendorAssetsTest`, `scripts/browser-checks/on-device.html` |
| The offline copy of the handbook | Whoever later uses a shared or lost device; a copy of the browser profile | Off by default; an allowlist of fields; AES-GCM under a non-extractable key held apart; deleted on sign-out and after 30 days — see below | `scripts/check-offline-store.js`, `on-device.html` |

### The offline copy (P-21)

What the web client keeps when someone turns on *Readable without a connection*
on a device, and exactly what that does and does not protect
(`app/offline-store.js`).

**What is kept.** The fields the offline page draws, copied by allowlist: each
included holding's title, type, institution, reference number, nominees' names,
formatted value and the claim steps; debts by title, lender and outstanding
amount; wills and papers by title, kind, executors and date; the people who help
with their role, organisation, phone and email; trusted people by name. **Never**
sealed values, where anything is kept, notes, addresses, internal ids, or
anything the API adds later until someone adds it to the list on purpose. The
copy is what this reader could already see: it is taken from responses the
database had already filtered.

**How.** AES-GCM-256 with a random 96-bit IV and fixed associated data, under a
key made by `crypto.subtle.generateKey` with `extractable: false`. The key is
stored in one IndexedDB database, the ciphertext in another. Nothing is put in
Cache Storage, and the service worker never sees it.

**When it goes.** Key first, then data, on: signing out; a refresh the server
refuses (a revoked session, once the device is next online); leaving the
household; turning the setting off; a copy that fails to decrypt; a copy older
than 30 days or dated in the future. Signing out also turns the setting off, so
the next person to sign in on the device does not inherit it.

| Against | Does it help? |
|---|---|
| Someone who picks up the device after the owner signed out | **Yes.** The key and the data are deleted; a fragment left behind is ciphertext without a key |
| A backup, sync or forensic copy of one IndexedDB database | **Yes.** The key and the data are not in the same database, and a non-extractable key's bytes are not readable by script |
| A copy of the whole browser profile taken while the copy exists | **No, not by itself.** The browser stores a non-extractable key in its own profile; someone who can run that profile can decrypt. The refresh token beside it in `localStorage` already gives such a person the live account, so the copy adds little to what they have. Full-disk encryption and an OS lock are what defend this |
| A script running on this origin (XSS) | **No.** It can ask the browser to decrypt, exactly as the page does. The same script could read everything the page reads while online |
| A session revoked from another device while this one stays offline | **Partly.** The copy stays readable offline until the device reaches the server, or for at most 30 days |
| Stale information in an emergency | Said on the page: "Last updated …", and "It may be out of date" |

A passphrase or device unlock (WebAuthn PRF) wrapping the key would close the
profile-copy row at the cost of needing to remember something in a crisis; it is
not built (known-issues 60).

---

## 3 · STRIDE, briefly

| | Where it applies | Control |
|---|---|---|
| **Spoofing** | Sign-in, guest links, the WhatsApp webhook | OTP with rate limits, and a second factor after it for accounts that have one; 256-bit link tokens; signature verification required of any live gateway — and the sandbox says loudly that it verifies nothing |
| **Tampering** | Records, audit log, ciphertext | Optimistic concurrency with version checks; append-only audit; AAD binds every ciphertext to its location |
| **Repudiation** | Any sensitive action | Append-only `activity_log` with actor, action, entity and diff; emergency and share flows log every transition |
| **Information disclosure** | The whole product, really | Section 2 above |
| **Denial of service** | Auth endpoints, uploads, imports | Per-phone and per-IP rate limits; upload size caps; import row caps. Infrastructure-level protection is an operational matter |
| **Elevation of privilege** | Roles, grants, guest sessions | Capability checks separate from visibility; only a holder may grant; guest sessions are read-only in the database, not merely by convention |

---

## 4 · Control catalogue

Grouped the way a reviewer usually asks. Each names the file to read.

### Access control
| | |
|---|---|
| AC-1 | Per-record visibility enforced in PostgreSQL — `db/migrations/V4__rls_privacy.sql` |
| AC-2 | Application connects as a non-owner role — `config/DatabaseConfig.kt`, `infra/postgres-init/01-app-role.sql` |
| AC-3 | Transaction-scoped identity, discarded at commit — `config/RlsTransactionManager.kt` |
| AC-4 | Capability (role) checks separated from visibility — `app.can_write_household`, `app.can_read_record` |
| AC-5 | Only a record's holder may grant visibility — `V8__generalise_visibility_grants.sql` |
| AC-6 | Advisors see only explicit grants — `V23__currencies_and_advisors.sql` |
| AC-7 | Guest sessions are read-only in the database — `V21__guests_are_read_only.sql` |
| AC-8 | Emergency access reveals continuity records only, and only to the requester — `V20`, `EmergencyService.kt` |
| AC-9 | The emergency window opens only after real inactivity by the person it concerns — `V25__emergency_needs_real_inactivity.sql`, `security/SessionActivity.kt` |
| AC-10 | A recovery copy is readable by its owner and by the person holding an open window on that owner only; who holds a share only by members who can see a value that person sealed — `V55__recovery_for_sealed_fields.sql` (`app.emergency_open_on_user`) |

### Cryptography
| | |
|---|---|
| CR-1 | Envelope encryption, per-household DEK — `crypto/EnvelopeCipher.kt`, `V9__encryption_keys.sql` |
| CR-2 | KEK outside the database; refuses to start without one outside development — `crypto/LocalKeyManagement.kt` |
| CR-3 | AAD binds ciphertext to household, table and column — `crypto/EnvelopeCipher.kt` |
| CR-4 | Zero-knowledge fields: PBKDF2-SHA-256 ≥ 100k (600k for new keys), AES-256-GCM, AAD-bound — `app/e2e.js`, `e2e/SealedFieldService.kt`, [Doc 12](12-end-to-end-encryption.md) |
| CR-7 | Zero-knowledge recovery: a printed recovery key or 2-of-3 Shamir shares over GF(256), made on the device; HKDF-SHA-256 wrap of the same content key; content key id keeps copies consistent with the passphrase row — `app/recovery-codes.js`, `app/e2e.js`, `e2e/Recovery.kt`, `V55`, [Doc 12 §10](12-end-to-end-encryption.md#10-recovery) |
| CR-5 | No key material in the repository; a test fails the build if any appears — `crypto/LocalKeyManagementTest.kt` — "no key material is committed anywhere in the source tree" |
| CR-6 | Tokens (refresh, download tickets, guest links) stored only as SHA-256 hashes |

### Identity and session
| | |
|---|---|
| ID-1 | Phone + OTP; no stored passwords |
| ID-2 | Single-use refresh tokens with reuse revocation — `auth/AuthService.kt`, `auth/SessionRevoker.kt` |
| ID-3 | Step-up re-authentication for sensitive reads, and for making, replacing or removing a recovery copy, scoped to the session — `auth/StepUpService.kt`, `e2e/Recovery.kt` |
| ID-4 | OTP purposes namespaced so a sign-in code cannot elevate a session |
| ID-5 | Rate limits per phone and per IP — `auth/OtpService.kt` |
| ID-6 | Second factor after the one-time code for any account that has one: authenticator (RFC 6238, replay-guarded), passkey (WebAuthn), recovery code; five tries per pending sign-in, ten misses an hour per account — `auth/SecondFactorService.kt`, `auth/PasskeyService.kt` |
| ID-7 | Factor secrets sealed per person (`crypto/UserSecretCipher.kt`); recovery codes PBKDF2; factor tables under RLS to their owner — V50, SQL suite "second factors" |
| ID-8 | Removing or replacing a factor needs elevation by a factor, and never leaves fewer than two ways in — `auth/StepUpService.kt`, `auth/SignInMethodsService.kt` |
| ID-9 | Phone number change needs an elevated session and a code to the new number — `AuthService.verifyPhoneChange` |
| ID-10 | New sign-ins and sign-in changes audited and announced in-app and through the outbox — `auth/AccountNotices.kt` |

### Data protection
| | |
|---|---|
| DP-1 | Masking by default; full numbers only on opt-in, then encrypted and step-up gated |
| DP-2 | Documents encrypted before storage; single-use, short-lived download tickets |
| DP-3 | Exports scoped by the caller's own visibility — `reports/ExportService.kt` |
| DP-4 | Notification bodies never logged or stored — `provider/Delivery.kt` |
| DP-5 | IP addresses hashed where recorded at all — `sharing/ShareController.kt` |
| DP-6 | A protected statement is opened and a photo read in the browser; the server receives only the rows or words kept — `app/pdf-text.js`, `app/ocr.js` |
| DP-7 | The opt-in offline copy: allowlisted fields, AES-GCM under a non-extractable key held apart, deleted on sign-out and after 30 days — `app/offline-store.js` |

### Audit
| | |
|---|---|
| AU-1 | Append-only activity log; INSERT-only grant — `R__grants.sql` |
| AU-2 | Every share, emergency transition and provider import audited |
| AU-3 | Outbound messages recorded with outcome, never content |

### Software supply chain
| | |
|---|---|
| SC-1 | Pinned dependency versions; Gradle lockfile-free but explicit |
| SC-2 | Migrations are forward-only and checksum-validated by Flyway; `scripts/check-migration.sh` proves the chain applies to an empty database |
| SC-3 | The API is frozen at v1 and a contract test fails the build on any breaking change — `contract/OpenApiContractTest.kt` |
| SC-4 | Every data-writing development script refuses a non-local or non-development target, from one shared implementation — `scripts/lib/require-development-server.sh` |
| SC-5 | Browser libraries are vendored, pinned by version in their path, recorded with the npm integrity hash and a SHA-256 per file, and served from this origin only — `app/vendor/SOURCE`, `web/ClientVendorAssetsTest.kt` |

---

## 5 · How to verify, in about ten minutes

```bash
./scripts/dev.sh test        # unit + full-stack, then the SQL privacy suite
./scripts/dev.sh             # in one terminal
./scripts/e2e-phase0.sh      # 65 checks — foundations and privacy
./scripts/e2e-phase2.sh      # 40 checks — goals, returns, tax, capture, reports
./scripts/e2e-phase3.sh      # 46 checks — estate, continuity, sharing, emergency, zero-knowledge
```

The three end-to-end suites refuse to run against anything that is not a local
server reporting `environment: development`, so reviewing them cannot damage a
real deployment. `scripts/smoke-prod.sh` is the one written to run against a
deployment: it announces that it writes, and prints the SQL to undo it.

Three things worth doing by hand, because they are the claims most worth
disbelieving:

1. **Try to read another member's private holding** as an admin, through every
   surface: the API, search, the dashboard totals, the tax pack, the CSV export,
   the printed handbook. Each should return nothing, and the totals should
   differ between the two members.
2. **Open a guest link and try to widen it** — change the id in a URL, request
   another record, attempt a write. The database refuses; the transaction is
   read-only.
3. **Seal a field, then look in the database.** `select * from sealed_values`
   should tell you nothing, and neither should a full `pg_dump`.
4. **Make a recovery sheet, then look again.** Nothing in `e2e_recovery_wraps`,
   `e2e_recovery_slots` or `activity_log` should contain the printed code, and
   the sheet should still open everything after the passphrase is changed.

---

## 6 · Known gaps

| | |
|---|---|
| No external penetration test has been performed | To be scheduled against a deployed environment |
| The zero-knowledge scheme has had no third-party cryptographic review | Doc 12 is written so one is possible. Recovery (§10) adds a hand-written Shamir split over GF(256); it is pinned to FIPS-197 vectors and exhaustive single-share uniformity, which is evidence, not review |
| Recovery adds ways in | Whoever holds the sheet, or any two shares, opens everything the owner sealed; two share holders can collude; who holds a share is plain text to members who can see a sealed value; a hostile server can delete or corrupt a copy, silently until a practice unlock fails. Doc 12 §10.7 states each; the yearly practice prompt and V31/V55 digests are the detection, not prevention |
| Browser-delivered E2E depends on the server serving honest code | Mitigated only by a native client with a signed binary |
| Key rotation cadence is undocumented; DR is only the manual backup and restore in Doc 17 §6 | Operational, and out of scope for a codebase that has not been deployed |
| Incident and breach response is written ([Doc 26](26-incident-response.md)) but has no named owners, no counsel review and no rehearsal | Known-issues 25 |
| No formal DPDP or SOC 2 programme | Design aligns; the programme is separate work |
| A second factor is optional, and not yet required for emergency-access grantors | The card asks for two ways in; making it mandatory is a product decision (docs/05 §2) |
| The native app cannot finish a sign-in that needs a second factor | known-issues 29 |
| The offline copy of the handbook is readable by anyone who can run the browser profile it is in | Opt-in, and said so in Settings; a key wrapped by a passphrase or device unlock is not built (known-issues 60) |

[‹ Index](README.md)
