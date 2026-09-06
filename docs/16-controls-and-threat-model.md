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
| A session | Token theft, a borrowed unlocked phone | Single-use refresh with reuse-detection; step-up for sensitive reads | `AuthApiTest` |
| A guest link | Anyone who receives or guesses one | 256-bit token stored hashed; scope materialised at creation; read-only guest transaction; expiry, view cap, revocation | `ShareApiTest`, SQL suite guest section |
| Emergency access | A trusted contact acting too early, or a coercive one | Waiting period, owner veto, **inactivity requirement**, notifications, audit, continuity-only reveal, read-only | `EmergencyAccessApiTest`, SQL suite emergency section |
| The audit trail | The application itself | `almira_app` has INSERT only on `activity_log` | `R__grants.sql`; SQL suite |
| Imported provider data | A provider returning too much, or the wrong household's | Imports arrive at the household's default visibility; the AA sandbox proves the consent gate | `ProviderApiTest` |

---

## 3 · STRIDE, briefly

| | Where it applies | Control |
|---|---|---|
| **Spoofing** | Sign-in, guest links, the WhatsApp webhook | OTP with rate limits; 256-bit link tokens; signature verification required of any live gateway — and the sandbox says loudly that it verifies nothing |
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

### Cryptography
| | |
|---|---|
| CR-1 | Envelope encryption, per-household DEK — `crypto/EnvelopeCipher.kt`, `V9__encryption_keys.sql` |
| CR-2 | KEK outside the database; refuses to start without one outside development — `crypto/LocalKeyManagement.kt` |
| CR-3 | AAD binds ciphertext to household, table and column — `crypto/EnvelopeCipher.kt` |
| CR-4 | Zero-knowledge fields: PBKDF2-SHA-256 ≥ 100k (600k for new keys), AES-256-GCM, AAD-bound — `app/e2e.js`, `e2e/SealedFieldService.kt`, [Doc 12](12-end-to-end-encryption.md) |
| CR-5 | No key material in the repository; a test fails the build if any appears — `crypto/LocalKeyManagementTest.kt` — "no key material is committed anywhere in the source tree" |
| CR-6 | Tokens (refresh, download tickets, guest links) stored only as SHA-256 hashes |

### Identity and session
| | |
|---|---|
| ID-1 | Phone + OTP; no stored passwords |
| ID-2 | Single-use refresh tokens with reuse revocation — `auth/AuthService.kt`, `auth/SessionRevoker.kt` |
| ID-3 | Step-up re-authentication for sensitive reads, scoped to the session — `auth/StepUpService.kt` |
| ID-4 | OTP purposes namespaced so a sign-in code cannot elevate a session |
| ID-5 | Rate limits per phone and per IP — `auth/OtpService.kt` |

### Data protection
| | |
|---|---|
| DP-1 | Masking by default; full numbers only on opt-in, then encrypted and step-up gated |
| DP-2 | Documents encrypted before storage; single-use, short-lived download tickets |
| DP-3 | Exports scoped by the caller's own visibility — `reports/ExportService.kt` |
| DP-4 | Notification bodies never logged or stored — `provider/Delivery.kt` |
| DP-5 | IP addresses hashed where recorded at all — `sharing/ShareController.kt` |

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

---

## 5 · How to verify, in about ten minutes

```bash
./scripts/dev.sh test        # unit + full-stack, then the SQL privacy suite
./scripts/dev.sh             # in one terminal
./scripts/e2e-phase0.sh      # 65 checks — foundations and privacy
./scripts/e2e-phase2.sh      # 40 checks — goals, returns, tax, capture, reports
./scripts/e2e-phase3.sh      # 40 checks — estate, continuity, sharing, emergency, zero-knowledge
```

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

---

## 6 · Known gaps

| | |
|---|---|
| No external penetration test has been performed | To be scheduled against a deployed environment |
| The zero-knowledge scheme has had no third-party cryptographic review | Doc 12 is written so one is possible |
| Browser-delivered E2E depends on the server serving honest code | Mitigated only by a native client with a signed binary |
| Backups, DR, key rotation cadence and incident response are undocumented | Operational, and out of scope for a codebase that has not been deployed |
| No formal DPDP or SOC 2 programme | Design aligns; the programme is separate work |

[‹ Index](README.md)
