[‹ Index](README.md) · [‹ Prev: Data Model](04-data-model.md) · [Next › Backend, API & Stack](06-backend-api-and-stack.md)

# 05 · Security & Privacy  ★ priority

**Guiding goal:** *A breach should reveal as little as possible, and Almira must never be where an attacker looks for bank passwords — because they aren't there.* Security here isn't a feature; it's the product's license to exist. People will only pour their entire financial life into something they trust more than a locker.

## 1. Threat model — what we defend against
Account takeover (credential stuffing, phishing) · database exfiltration (a dump must not reveal usable secrets) · broken access control (cross-household **and cross-member** leakage) · document leakage · a rogue insider · a lost/stolen device with an open session · abuse of scoped guest links · **coercion/surveillance of a vulnerable family member** (a first-class threat here, see §3).

## 2. Authentication & session
- Email/phone + password (**Argon2id**) and SSO (Google/Apple).
- **MFA** (TOTP + WebAuthn/passkeys); **required** for emergency-access grantors and for viewing full (unmasked) numbers.
- Short-lived access tokens + rotating refresh tokens; device/session list with remote revoke.
- **Mobile:** biometric app-lock (Face/Touch), secrets in Keychain/Keystore, auto-lock on background, no sensitive data in logs; optional jailbreak/root signal.

## 3. The intra-household privacy model  ← the answer to your question
**Principle:** *Joining a household never means surrendering financial privacy.* Almira keeps two axes completely separate:

- **Role = capabilities** (invite members, manage roles, edit shared records, billing). Roles are `owner · admin · editor · viewer · restricted`.
- **Visibility = which records you can see.** Governed per-record, **not** by role. **No role — not even owner or admin — can see another member's Private records.** Being the household creator lets you *manage the household*, not read everyone's private holdings.

### 3.1 Per-record visibility levels
Every user-owned record (investment, liability, account, goal, document) carries a `visibility`:
- **Private** — visible only to its **owner(s)** (the members on its ownership/holder list). Not to admins, not to other members.
- **Household** — visible to all active members of the household.
- **Scoped** — visible to specific members listed in `record_visibility_grants` (e.g., share this FD with your spouse but no one else).

**Joint records override "private" for co-owners:** you can't hide a jointly-owned asset from your co-owner — all owners always see records they co-own.

### 3.2 Defaults (a household decides its culture)
- A **household default** (`private` or `household`) set at creation, changeable later, and a **per-user default** that wins for that user's new records.
- Sensitive-by-nature items (e.g., personal insurance, a private savings buffer) **nudge toward Private** at capture, with one tap to share.
- Recommended default for trust: **Private-by-default with an easy "Share with household" toggle.** Transparent families can flip the household default to `household`.

### 3.3 Totals never leak
Each viewer's household net worth is computed **through their own visibility filter** — it sums only what they may see. The **owner** of private items sees the true total; **others** see the shared-only total. A private item contributes **nothing** (not even its amount) to another member's view. *(An advanced future option — "count in the household total but hide details" — is deliberately deferred, because it leaks the amount; v1 keeps the clean, honest Private/Shared/Scoped model.)*

### 3.4 Reconciling privacy with continuity
Privacy is *for life*; continuity is *for after*. A record can be **Private now** and still `is_in_continuity = true`, meaning it appears in the "For My Family" view **only when emergency access unlocks** (§6). So a member keeps something private from the family while alive, yet the family still isn't left guessing later. Privacy now, disclosure on the defined event — the two goals coexist.

### 3.5 Safety for vulnerable members
This model is also a safety feature. Because no role is omniscient, the app can't be weaponized for financial surveillance or coercion: a spouse/parent can't silently see another adult's private holdings. Visibility changes and any permitted cross-member view are **audited**; granting/revoking access notifies the affected member; and anyone can **leave a household with their own data** (§8). Minors/managed members are visible to their guardians by design; when a minor gains their own login at adulthood, control transfers to them.

### 3.6 Enforcement (defense in depth)
Encoded at **three layers** so a bug in one can't leak data:
1. **Postgres Row-Level Security** — the authoritative gate. A member may read a record iff:
   `is an owner/holder` **OR** `visibility='household'` **OR** (`visibility='scoped'` AND a matching `record_visibility_grants` row) **OR** (`emergency access unlocked` AND `is_in_continuity`).
2. **Service layer** re-checks the same predicate and applies field-masking (last-4).
3. **UI** never requests or renders what the API won't return.
Search, reports, document access, and exports all pass through the **same** visibility predicate — there is no side-channel.

### 3.7 Visibility × Role matrix (reading)
| | Own records | Household-shared | Scoped-to-me | Others' Private | Continuity (pre-event) | Continuity (post-unlock) |
|---|---|---|---|---|---|---|
| Owner/Admin | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ (if trusted contact) |
| Editor/Viewer | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ (if trusted contact) |
| Restricted | ✓ | scoped | ✓ | ✗ | ✗ | ✓ (if trusted contact) |
| Guest (link) | — | slice only | — | ✗ | link scope only | — |
*Roles still differ on **capabilities** (edit/manage) — see [Doc 01 §11](01-product-and-scope.md) and Backend RBAC.*

## 4. Encryption
- **In transit:** TLS 1.3, HSTS.
- **At rest:** volume encryption + **field-level encryption** for the most sensitive columns (account/policy numbers, `mfa_secret`) via **envelope encryption** — per-household **DEK** wrapped by a **KEK** in a managed **KMS**; the DB stores only the wrapped DEK, never the KEK.
- **Documents** encrypted before object storage; access only via short-lived signed URLs after re-auth, and only if the caller passes the §3 visibility check.
- **Optional zero-knowledge mode** (privacy-hawk tier): sensitive fields + documents encrypted with a key derived from the user's passphrase; server stores only ciphertext. Honest trade-offs shown: no server-side OCR/search on those fields, and no password-reset recovery of that data.

## 5. Data minimization & masking
Store **masked** (last-4) by default; full numbers only on explicit opt-in, then field-encrypted, and viewing them requires re-auth/MFA. **Never store bank/broker passwords or OTPs — ever.** Redact sensitive values from logs, errors, analytics. Collect the minimum; every field justifies itself.

## 6. Emergency (continuity) access — safe by design
Trusted contact + **time-delayed inactivity unlock** (configurable window), **vetoable by the owner at any point** during the window, with notifications to both parties throughout, then a **scoped read** (continuity summary or full-read) — all logged. No silent backdoor; the delay + veto + audit make it both humane and safe.

## 7. Scoped external sharing (guest links)
Single-scope, read-only, expiring, revocable, rate-limited, fully audited links (e.g., the FY tax pack to a CA for 7 days). Opening a link never exposes anything outside its slice; sensitive documents can be excluded per share; the link resolves to a least-privilege guest principal that still passes the §3 predicate for its scope.

## 8. Compliance & data rights
**India DPDP Act 2023** alignment: purpose limitation; **consent when storing another adult's data** (a co-managing invite records consent); **guardian basis** for minors; data-principal rights. GDPR-style **export** (machine-readable) and **erase** (hard purge with audit). Clear data-ownership terms for shared households and for a **departing member** (their own records export cleanly; the admin chooses what transfers vs stays; private records go with their owner). Encrypted, tested **backups**; documented DR (RPO/RTO). Future auto-import only via India's **Account Aggregator** (consent-based, read-only) — never scraping or credential harvesting.

## 9. Auditing & monitoring
Append-only `activity_log` for writes, sensitive views, exports, logins, share creation, visibility changes, and access-grant transitions. Rate limiting; anomaly alerts (impossible travel, mass export); WAF; dependency/secret scanning; least-privilege service accounts; secrets in a manager, never in code.

## 10. Concurrency & sync
**Optimistic concurrency:** writes carry the record's `version`/`updated_at`; a stale write is rejected with the current state so the client can merge or prompt — never a silent overwrite. Offline captures use idempotency keys; sync conflicts surface a clear "keep mine / keep theirs / merge" choice. All resolutions land in the audit diff.

## 11. Secure SDLC
Threat-modeling per feature; code review; SAST/DAST; dependency and container scanning; signed builds; staged rollouts; periodic third-party penetration test and a **public security whitepaper** (trust is marketing here — see [Doc 08](08-differentiation-and-standout.md)).

[‹ Index](README.md) · [‹ Prev: Data Model](04-data-model.md) · [Next › Backend, API & Stack](06-backend-api-and-stack.md)
