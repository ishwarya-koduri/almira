[‹ Index](README.md)

# 19 · Pen-test pack

> **Status.** Assembled for Phase 1 item 5. §"How the verification is verified"
> comes first because it is load-bearing; then the threat model and trust
> boundaries (§A), where each guarantee is actually enforced (§B–§F), and
> §"What I would attack first". Read §A before the rest: it says which
> attacker each later claim is answering.
>
> Written from the code as it stands at the item-4/item-5 merge, by the person
> who built the item-4 artefacts. That is a conflict of interest, and §"What I
> would attack first" is where it is spent down rather than hidden.

---

## How the verification is verified

Put first because it is load-bearing for everything after it. Every claim in
this pack rests on a check, and a check that cannot fail is not evidence — it is
a comfortable noise. This project has produced two of those, in one week, and
both were in the checking layer rather than in the product.

**The rule: no check is trusted until it has been watched failing.**

Not "until it passes". Until someone has broken the thing it guards, seen the
check go red, read the message it printed, and put the thing back. A check
written and observed only in its passing state has been observed in the one
state that carries no information.

### The two that got through, and what they teach

**A probe that reported a failing test as passing.** A script read JUnit XML and
picked the failure node with

```python
node = tc.find('failure') or tc.find('error')
```

An `ElementTree` element is **falsy when it has no child elements**. A
`<failure message="…">stack trace</failure>` has text and no children, so it is
falsy, so `or` moved past it and returned `None`, so the script printed that the
test had passed. The build had already said `BUILD FAILED` two lines above; the
summary contradicted it and the summary was the thing being read.

*The lesson is not "know that Python gotcha".* It is that the tool which
**summarises** results is itself untested code, and it fails in the direction
that looks like success. A summariser must be handed a known failure and made to
report it before anyone reads its output.

**A query that checked the wrong rows.** A test asserted that an interrupted
write left a key untouched, and read `select … from e2e_keys` — every
household's key, in a suite that shares a database. It blew up on `single()`,
which was luck: had two rows been identical it would have passed while checking
nothing in particular.

*The lesson:* a check scoped more widely than the thing it is checking may be
right by accident. Scope narrowly enough that the assertion can only be about
the case at hand.

### What is therefore required of a new check

1. **Break it on purpose, once.** Change the code or the data so the check must
   fail, run it, and keep the failure output. If it does not fail, it is not
   checking what its name says.
2. **Read the failure message as a stranger would.** It should name the thing
   that is wrong and where to look. `AadCaseTest` says *"AAD case mismatch"* in
   every message for exactly this reason: the symptom is an authentication-tag
   failure, which reads like a broken cipher, and the message is what stops the
   next person spending a day on the wrong layer.
3. **Restore, and confirm green.** Byte-for-byte — `git diff` empty, not "looks
   the same".
4. **Scope the assertion to the case.** If it reads more than the thing under
   test, it can pass for a reason unrelated to the thing under test.
5. **Distrust summaries hardest.** Anything that aggregates, counts, greps or
   parses results is code between you and the truth. Cross-check it against the
   raw signal — the exit code, the runner's own output — at least once.

### Where this has been done, and where it has not

| Check | Watched failing? | How |
|---|---|---|
| `scripts/check-spec.py` | yes | found a real contradiction on its first run — the reference implementation using an API the spec forbids by name |
| `AadCaseTest` | yes | lowercasing removed from `Aad.of`; message and case-diff read; restored |
| rotation-interrupted-before-commit | yes | `setRollbackOnly()` removed; failed with *"a salt from a rotation that never committed must not survive"* |
| the icon safe-zone measurement | yes | caught a shipped maskable icon at 0.451 against a 0.400 limit |
| the Gradle wrapper fix | yes | jar moved aside in a throwaway clone; `./gradlew` failed; restored |
| page-checksum startup refusal | yes | real jar booted with `ALMIRA_ENV` unset against a checksums-off database; refused, Flyway history unchanged; two decision mutations turned 6 and 3 tests red; restored ([Doc 17 §3](17-deploying.md)) |
| OTP echo gated on environment | yes | production boot returned `developmentCode` before the fix; after it, the identical request returns 503 with no code in Redis or the log; gate removed → red (`OtpServiceTest`, on master) |
| unset `ALMIRA_ENV` fails closed | yes | published JWT secret accepted a forged session before the fix; five-boot table; `EnvironmentDefaultTest` watched failing with the old default ([Doc 17 §3](17-deploying.md)) |
| the restore structural sweep | yes | ciphertext truncated to 30 bytes in a restored copy; named that row and no other; `restore-row.sh` put it back; field opened again ([Doc 17 §6](17-deploying.md)) |
| the stored-digest check | yes | one body byte flipped through a trigger-suppressing write; sweep passed, digest check caught it; the same flip via ordinary `UPDATE` did not trip it — the documented limit |
| page-checksum layer under it | yes | one on-disk page byte flipped in the stopped volume; Postgres refused the page (`invalid page in block 0`) |
| wrong-KMS-key startup refusal | yes | restored copy booted with a different `ALMIRA_KMS_MASTER_KEY`; refused, naming the key it had and the key the data expects; right key logs `opens all N household key(s)` |
| whole-stack restore round trip | yes | seeded RLS/envelope/document/ZK data, backed up, restored into an empty stack, read every piece back through the API (`deploy/restore/drill/`) |
| readiness = safe to serve | yes | owner-role connection reported not-ready (503); mutation dropping the RLS clause turned the case red (`HealthProbesTest`) |
| reminder sweep actually runs | yes | the sweep's owner template was resolving to the runtime pool, so it found nothing under RLS and notified no one, silently; `ReminderSweepTest` fails against that wiring |
| guest-link audit IP not forgeable | yes | `ShareController` now hashes the proxy-vouched address, not the raw first `X-Forwarded-For`; `ShareAuditAddressTest` watched failing with the fix removed (master `2b0ca3f`) |
| per-network wrong-code cap | yes | over the hourly per-IP wrong-code cap a correct code gets no verdict and the challenge is untouched; `OtpServiceTest`/`ClientAddressTest` watched failing (master `2b0ca3f`) |
| email allowlist not enumerable | yes | status, body, headers and timing identical for allowed vs not; `EmailSignInApiTest` (master) |
| email code never leaks | yes | no code in any log/response across the email path; `OtpCodeNeverLeaksTest` email path (master) |
| email code not cross-usable | yes | an email code rejected on a phone challenge; `EmailOtpTest` (master) |
| provider give-up leaks no detail | yes | on give-up one WARN with provider/operation/kind/attempts only; a case where adapter detail leaked was watched failing; `ProviderGiveUpLogTest` (master) |
| spent code not burned on list failure | yes | a DigiLocker list failure no longer rolls back a connection whose code was already consumed; `ProviderFailureApiTest` (master) |
| location/key-holder stays private | yes | a sealed original-location/key-holder never reaches another member through a private record; `GET /households/{id}/where-and-who` test (master) |
| sealed overwrite is refused | yes | overwriting another member's sealed value returns 409 (master) |
| readiness cannot be gamed | yes | continuity readiness caps at 99 while any record is left out; `leftOut` field; per-viewer (master) |
| StillTrue sweep runs as owner+user | yes | `@Qualifier("ownerDataSource")` and `app.user_id` set per person, each watched failing when removed (master) |

**For a tester reading this:** the honest summary is that the checks have been
adversarially tested more carefully than the product has. Treat the table above
as the list of claims with evidence behind them, and everything else in this
pack as a claim with an argument behind it. The difference matters, and
[Doc 18 §4](18-handover.md) is the standing list of what has never been verified
at all.

---

## A · Threat model and trust boundaries

Almira holds a household's whole financial life — what they own, what they owe,
where the papers are, who inherits. The people it protects data *from* are
partly the same people it shares data *with*, which is the shape that makes it
hard: a spouse, an adult child, an advisor and a nominee all see some of it and
must not see the rest.

### Who the attackers are

| # | Attacker | What they have | What must still hold |
|---|---|---|---|
| T1 | Another member of the same household | A real session, a role (owner/admin/editor/viewer/restricted/advisor) | Cannot read a record marked private to someone else; an admin has no more *read* than anyone else |
| T2 | A holder of a share/invite/emergency token | One unauthenticated token | Sees only what that token names, for as long as it lives |
| T3 | A signed-in stranger | A valid session for their *own* empty household | Cannot reach any other household's rows at all |
| T4 | Network attacker | Can send requests; cannot read the DB | No account takeover; no sign-in as someone else; rate limits hold |
| T5 | Database-dump holder | A full `pg_dump` + documents, **no** KMS key, **no** passphrases | Account numbers, document bodies and sealed fields stay unreadable; everything else is plaintext (see §C) |
| T6 | Operator / rogue insider / subpoena | The running server, the KMS key, the ability to serve modified client code | Reads everything the server can read; **cannot** read zero-knowledge fields — but can serve JS that steals the passphrase next time (§E) |
| T7 | Physical-storage attacker | The disk under the volume | Page checksums catch a flipped bit ([Doc 17 §3](17-deploying.md)); at-rest encryption of the volume is out of scope and not claimed |

The product's one load-bearing promise is **T1 and T5**: household members are
walled from each other by row-level security, and a stolen database is walled
from everyone by envelope encryption, with zero-knowledge fields walled even
from **T6**. Everything below says where each of those is actually enforced —
and, as often, where it is enforced *only* in application code and would fall to
a bug there.

### The trust boundaries, from outside in

1. **TLS terminator → app.** Almira does not terminate TLS; it sets HSTS and
   trusts a reverse proxy in front ([Doc 17 §1](17-deploying.md)). The app binds
   to loopback only. **The proxy is inside the trust boundary for client IP** —
   Tomcat's `forward-headers-strategy=native` trusts `X-Forwarded-For` only from
   loopback/private peers and takes the right-most untrusted address, so the
   proxy must *append* rather than pass through (§F). A proxy on a public address
   needs its range configured or the per-network rate limit is forgeable.
2. **App → Postgres, as two roles.** The schema owner runs migrations; a
   non-owner runtime role serves every request and cannot bypass RLS. This is
   the wall behind T1/T3 and is examined in §B.
3. **App → KMS.** The master key wraps per-household data keys. The wall behind
   T5 for server-encrypted columns; §D.
4. **Browser → server, for sealed fields.** The passphrase and the content key
   never leave the browser. The wall behind T6 for those fields; §E. **This
   boundary is only as good as the JavaScript the server sent**, which is the
   crack in it.

---

## B · Where RLS is actually enforced (T1, T3)

**The mechanism.** `DatabaseConfig` builds two connection pools
(`config/DatabaseConfig.kt`): an owner pool (schema owner, migrations only) and
an `@Primary` runtime pool as `almira_app`, a `nosuperuser nobypassrls` role
(`deploy/bootstrap-db.sql`). Every request runs on the runtime pool.
`RlsTransactionManager` stamps `set_config('app.user_id', …, true)` — transaction
-local — onto the connection from a `ThreadLocal` set by `JwtAuthFilter` and
cleared in `finally`. Policies read `app.current_user_id()` =
`nullif(current_setting('app.user_id', true), '')::uuid`. **A query with no
transaction, or no identity, sees a NULL user, and every membership predicate is
then false — it fails closed, silently.** 46 tables carry RLS; the privacy
predicate is `can_read_record` = active member AND (owns it OR
`visibility='household'` OR a scoped grant); views are all `security_invoker`.

**Proven at the boundary.** `smoke-prod.sh` signs two members into one household
and asserts different net-worth totals through real HTTP, proxy and pool
included; `PrivacyIsolationTest` and the SQL suite (`db/tests/rls_privacy_test.sql`)
assert per-member isolation. `/health` and `/health/ready` both report the role
serving requests, and readiness returns 503 if it is ever the owner — so an
instance wired to bypass RLS cannot pass a health check (`HealthProbesTest`,
watched failing).

### POSSIBLE GAPS — most of these are enforced only in Kotlin, not in RLS

- **`FORCE ROW LEVEL SECURITY` is set on no table.** The owner (a superuser in
  the current compose, since `POSTGRES_USER` is the owner) skips RLS entirely.
  That is fine for migrations, but it means the whole privacy model rests on the
  runtime role *never* being the owner — which `/health` watches, and which the
  compose file could undo with one wrong env value. **Recommend** a non-superuser
  owner and `force row level security` on the member-data tables as defence in
  depth.
- **Four tables have no RLS at all** — `users`, `user_sessions`,
  `refresh_tokens`, `invitations` — and full CRUD is granted on them
  (`R__grants.sql`). Their only guard is a correct `WHERE` in `AuthRepository`.
  A missing clause there exposes every user's phone and email. T3-critical;
  untested by the RLS suite because there is no policy to test.
- **Emergency unlock is household-wide, not subject-scoped.**
  `emergency_reveals` (`V25`) checks only that *some* open request by the caller
  exists in the household, not that the row belongs to the subject. Once one
  request unlocks, the requester sees **every** member's `is_in_continuity`
  investments, liabilities and estate documents — and that flag defaults true.
  Managed members (no login) can never stop the clock. This is the one item I
  would call a present **bypass of the core promise**, not a hardening gap. The
  SQL test only checks the subject's own records, so it passes.
- **The guest-share clamp is only on the record tables.** `guest_scope_allows`
  is ORed into investments/liabilities/accounts/goals/contacts/estate/documents,
  but a handbook-scope share runs `HandbookService` under the sharer's full
  identity, and members/memberships/reminders/notifications/grants carry no
  guest clamp. Expiry, revocation and `max_views` are enforced only in Kotlin,
  and the `max_views` check is check-then-increment (raceable).
- **Reminders can point across households.** `reminders_insert` checks only
  `can_write_household`, not that the referenced investment/liability is visible
  or same-household; the sweep then notifies that other household with the
  attacker's title, and the FK doubles as an existence oracle.
- **Definer functions with no authorisation:** `record_guest_view`,
  `record_outbound_message`, `document_links_of`, `record_holder_member_ids`,
  `record_created_by` — `execute` is granted to the app role with no
  `revoke … from public`. Reachable only through code today; a SQL-injection
  anywhere turns them into read/insert primitives.
- **The reminder sweep silently did nothing** until this item: its "owner"
  template resolved to the runtime pool (both are `HikariDataSource`, `@Primary`
  won the unqualified inject), so it ran under RLS with no user and found no
  rows, hourly, with no error. Fixed and pinned (`ReminderSweepTest`). The
  lesson is in the "verification" section: **a job that runs as nobody and a job
  wired to the wrong pool look identical from the outside — empty.**

---

## C · What a database dump does and does not reveal (T5)

With a full dump and the documents tarball but no KMS key and no passphrases,
these columns are **ciphertext**: `accounts.number_enc` (only when the member
opted to store the full number), document file bytes on disk, and every sealed
field. **Everything else is plaintext** and must be assumed read: names, phone
numbers, emails, every amount (`invested_amount`, `outstanding`, `emi_amount`,
valuations, transactions), institution names, `storage_location` (e.g. "SBI
locker Kakinada"), the will's `location`, nominee and beneficiary names, notes,
and `documents.file_name` + `content_sha256` (the plaintext hash — which lets a
dump-holder confirm a household holds a *known* file without any key). The legacy
plaintext location columns — `investments.storage_location`,
`estate_documents.location`, `investment_templates.storage_location` — remain;
the web client no longer writes them, and the sealed `original_location` /
`key_holder` fields (V28) are their zero-knowledge replacement, but a dump still
reads whatever the old rows hold.

This is by design — the product encrypts the fields whose exposure is
individually catastrophic, not the whole database — but a tester and a privacy
reviewer should see the real line. **POSSIBLE GAPS:** `users.mfa_secret_enc` and
`provider_connections.access_token_enc` are declared encrypted but never written
by any code path today (confirm no token lands in an adjacent plaintext `detail`
column when the adapters land); `kek_id` is a 48-bit public fingerprint of the
KEK, harmless for a random key but a confirmation oracle for a guessed one.

---

## D · The KMS envelope (T5, T6-for-reading)

`LocalKeyManagement` loads a 32-byte master key (KEK) from
`ALMIRA_KMS_MASTER_KEY`; `EnvelopeCipher` generates one 32-byte data key (DEK)
per household, wraps it under the KEK with AES-256-GCM, and stores the wrapped
DEK in `encryption_keys` beside a `kek_id`. Field encryption is AES-256-GCM, 12-
byte random IV, 128-bit tag, blob `0x01 ‖ keyVersion(4 BE) ‖ iv(12) ‖ ct ‖ tag`,
**AAD = `"$householdId|$field"`**. `aws`/`gcp` are named seams with no
implementation (setting them leaves no bean and the app fails to start — an
honest failure).

The wrong-key case is now closed at startup: `KeyEncryptionKeyCheck` refuses to
boot if the loaded KEK does not open the household keys already stored, instead
of failing at the first reveal months later (watched both ways, §"verification"
table; [Doc 17 §6](17-deploying.md)).

### POSSIBLE GAPS

- **AAD binds household + field, not row id.** Within one household, ciphertext
  can be moved between rows — a private account's `number_enc` copied onto an
  account the attacker can see, then revealed — and it decrypts, because the AAD
  still matches. Crosses the per-record visibility line. Needs DB write access
  (so T5/T6, not T1), but it is a real weakening of what GCM's AAD is there for.
  **Recommend** binding the row id (and the envelope header) into the AAD.
- **No KEK rotation.** `unwrap` ignores the stored `kek_id` and uses the one
  loaded key; there is no keyring and no rewrap job. Rotating the master key
  makes every wrapped DEK undecryptable. DEK `key_version`/`retired_at` exist in
  the schema but nothing ever writes version 2. A key compromise today has no
  recovery path short of re-encrypting by hand.
- **Unwrapped DEKs live forever in memory.** An unbounded `ConcurrentHashMap`
  with a `forget()` that has no callers; a heap dump yields every DEK used since
  boot. T6-adjacent.

---

## E · The zero-knowledge field set (T6)

**What it is.** Optional sealed string fields on investments, liabilities,
accounts, members and estate documents. The passphrase is stretched in the
browser (PBKDF2-HMAC-SHA256, 600 000 iterations, 16-byte per-user salt) to a
wrapping key; that wraps a random 32-byte content key; the content key encrypts
each field with the same envelope as §D but **AAD =
`household|recordType|recordId|fieldKey`, uuids lowercased** (`static/app/e2e.js`,
`app/shared/.../zk/Aad.kt`, docs/12). The server stores `e2e_keys` (salt,
iterations, wrapped key, a verifier, key version) and `sealed_values`
(ciphertext, key version), and by its own comment checks only that what it is
handed is "well-formed base64 of a plausible length".

**What it protects.** A database dump (T5) and a rogue operator reading the
database (T6) get bytes. The content key is never sent. The restore drill proves
a sealed value survives backup/restore and still opens only with the passphrase,
in an independent implementation of the envelope checked against docs/12's fixed
vector.

**What it does NOT protect against — state this plainly to any reviewer:**

- **A compromised or malicious server serving modified `e2e.js`.** The passphrase
  is typed into a page the server delivered; the server can ship a version that
  exfiltrates it. docs/12 §9 accepts this. Zero-knowledge here means "the server
  does not *store* the ability to read", not "the server *cannot* read next
  time". There is no subresource integrity or code-signing on the client.
- **Same-origin script (XSS).** The content key sits in a module-level variable;
  any script on the origin can read it. **And document download serves the
  uploader's own `Content-Type` inline from the app origin with no CSP** — an
  uploaded `text/html`/`image/svg+xml` document is stored XSS that runs on the
  app origin (`nosniff` + `frame-options deny` are set; a CSP is not). That
  single gap threatens both the session (§F) and every sealed field. I rank it
  first in "what I'd attack".
- **Destroying access with a stolen session.** `PUT …/e2e/key` is a blind
  upsert with no step-up and no proof of the old key; a stolen session (or a
  double `enable()`) overwrites the wrapped key and makes every sealed value
  permanently unreadable. `key_version` is not checked to increase, so an old
  `e2e_keys` row (a leaked-then-rotated passphrase) can be rolled back.
- **A lost passphrase.** No recovery, by design.
- **Server-chosen KDF cost.** The client accepts whatever iteration count the
  server sends; a tampered low count is not flagged, only fails to open.
- **Offline guessing** by a dump-holder: one guess = 600k SHA-256 + one GCM tag
  check, not memory-hard, GPU-friendly, but per-user-salted so it is per-target.
  Nothing enforces passphrase strength. Argon2id would be the upgrade.

The server's on-write validation is also **weaker than the clients'** (accepts
17–32-byte blobs, bad version bytes, key version ≤ 0, standard base64) — which is
exactly why the restore sweep applies the *client* rule instead (§"verification",
[Doc 17 §6](17-deploying.md)).

---

## F · OTP and the auth surface (T4)

Sign-in is phone + one-time code; there is no password. Code is 6 digits from
`SecureRandom`, 5-minute TTL, stored in Redis as an HMAC keyed from the signing
secret over `purpose|phone|code` (not a reversible SHA-256 of six digits any
more), consumed and miss-counted by atomic Lua that re-checks the `requestId`.
Five wrong tries per code, 30-second resend, five requests per hour per phone,
`ALMIRA_OTP_MAX_PER_IP_PER_HOUR` per network. JWT is HS256, 15-minute access /
30-day sliding refresh, refresh rotated on use with reuse-detection that revokes
the session; revocations live in Redis for the access-token lifetime. Startup
refuses a published or short signing secret, and (as of the item-1 work) refuses
a code length outside 6–8, TTL over 10 minutes, or attempts outside 1–10.

**The critical one, now closed.** A production server used to return the code in
the response body, because the `log` sender set an "echo" flag and nothing
checked the environment — reproduced anonymously against a production boot
(§"Sign-in" in [Doc 17 §5](17-deploying.md)). Fixed: the echo is gated on an
explicitly-chosen development environment, and a real deployment returns 503 with
no code generated. It is in the table above as watched-failing on master. The
flip side is that **a deployed Almira cannot sign anyone in until a real SMS
sender exists** — a functional block, not a security hole, and the correct
trade.

### POSSIBLE GAPS

- **Redis is a hard dependency for the whole authenticated surface.** OTP and
  the `JwtAuthFilter` revocation check both throw on a Redis outage — fail
  closed, good — but a Redis restart without persistence lets revoked access
  tokens work again until they expire (~16 min). Sessions and rate limits are
  all in Redis by design (§C says don't back it up).
- **Refresh rotation is raceable.** `findRefresh` takes no row lock and
  `markRotated` has no `rotated_to is null` guard, so two simultaneous refreshes
  with one stolen token can both succeed and fork the session without tripping
  reuse detection. No rate limit on `/auth/refresh`.
- **Invitation tokens are not bound to a recipient.** 256-bit token, single-use,
  14-day — but the invited phone/email is never compared to the accepting user,
  so anyone holding the token joins with the invited role and can claim a managed
  member's records. And `owner` is an invitable role, so an admin can mint a
  second owner. No rate limit on accept.
- **Email sign-in is a new, alpha-only channel** (`POST /auth/otp/email/request`
  and `/verify`, `GET /auth/otp/channels`). It sends only to an explicit
  allowlist (`ALMIRA_ALPHA_EMAIL_ALLOWLIST`), returns 503 outside development
  until a live email adapter exists, and — proven — cannot be enumerated by
  status, body, headers or timing (`EmailSignInApiTest`), does not leak the code
  (`OtpCodeNeverLeaksTest`), and its code cannot be replayed on a phone challenge
  (`EmailOtpTest`). **Enabling email with an empty allowlist refuses to start**,
  by design; a disabled channel refuses with `403 sign_in_channel_disabled`. The
  residual questions a tester should press: the allowlist re-check at verify
  (marked not-watched-failing by the author), and that email is a second
  account-bearing identifier joined to the same user.
- **Account enumeration** is limited (request is identical for known/unknown
  numbers) but the same endpoint is sign-up and sign-in with an `isNewUser` flag;
  once a code is in hand, existence leaks.
- **`X-Forwarded-For` handling is now consistent.** Both the OTP path and
  `ShareController`'s audit-IP hash take the proxy-vouched address, not a
  client-written header (`ShareAuditAddressTest` watched failing, master
  `2b0ca3f`). The proxy-append requirement in [Doc 17 §5](17-deploying.md) is the
  remaining operational condition.
- **Wrong-code guessing is now capped per network as well as per number.** Over
  the hourly per-IP wrong-code cap, even a correct code gets no verdict and the
  challenge is untouched (`ALMIRA_OTP_MAX_VERIFY_FAILURES_PER_IP_PER_HOUR`,
  master `2b0ca3f`), closing the earlier gap where the only verify-side limit was
  five tries per individual code.
- **A malformed JSON body still returns 500**, not a 400 envelope
  (known-issues #2) — noise that also means the generic handler is on the auth
  path.
- **Swagger UI and the full OpenAPI spec are `permitAll` in production.** Not a
  hole by itself; it hands an attacker the exact shape of every endpoint.
- **Removal doesn't revoke.** `removeMember` soft-deletes the `members` row but
  leaves `household_memberships.status='active'`, and `is_household_member`
  checks only that table — a "removed" member keeps reading household-visible
  data. T1-critical.

---

## G · The outside-provider surface (mostly not built yet)

None of the external integrations has a live adapter; each is a place a future
adapter will add attack surface, and each is listed here so the tester knows
what is *absent* rather than *safe*. All rows are **not watched failing** — they
are the shape of work to come, from the provider pages under `docs/providers/`.

| Provider | State, and what a tester should know |
|---|---|
| DigiLocker | No separate sandbox; API Setu needs GSTN verification; the server must sit in India. Known-issues #10: OAuth `state` is not checked and the token would land plaintext in `external_ref`. Setting it live with no adapter fails startup. |
| Account Aggregator | No adapter; a production FIU needs RBI/SEBI/IRDAI/PFRDA regulation; recommended cut from v1; `mode=off`, and any other value fails startup (known-issues #11). |
| iOS APNs / Android FCM | No adapter, no Apple membership, no Firebase account, and **no device-token registration anywhere** — so push cannot be tested end to end yet (known-issues #13). |
| Real SMS | No live sender; needs a DLT header and a verbatim-registered template (GST-gated), unregistered templates dropped silently; release-keystore → app-hash → template ordering (MOVE.md); `ALMIRA_OTP_PROVIDER` other than `log` fails startup (known-issues #12). |
| WhatsApp | Signature verification is a stub — **the sandbox accepts every payload**. First thing to fix before any inbound webhook is trusted. |
| Email | No adapter exists; the alpha channel (§F) never actually sends. |

**Provider-call hardening that *is* built and proven:** `ProviderCalls`
enforces a per-provider timeout, never retries `REJECTED` or
`INSUFFICIENT_BALANCE`, and logs exactly one WARN on give-up carrying only
provider/operation/kind/attempts — a case where adapter detail leaked into that
log was watched failing (`ProviderGiveUpLogTest`). A synchronous-call design
means a slow provider ties up a request thread and a duplicate text is possible
(known-issues #21) — worth probing under load.

### New endpoints and error codes (attack surface added since item 4)

Endpoints, all behind auth unless noted: `GET /me/messages` (RLS-isolated —
marked not-watched-failing), `GET /auth/otp/channels`,
`POST /auth/otp/email/request|verify`, `GET /households/{id}/where-and-who`,
`GET /households/{id}/still-true`,
`POST …/still-true/{type}/{id}/confirm|snooze`,
`GET /households/{id}/continuity/readiness`.

App-generated status codes a tester will meet, and which the **reverse proxy
must pass through** rather than replacing with its own error page:
`otp_delivery_delayed` 504, `otp_delivery_failed` 422,
`otp_provider_unavailable` 503, `otp_service_unavailable` 503,
`provider_timeout` 504, `provider_unavailable` 503, `provider_rejected` 422,
`provider_account_unavailable` 503, `sign_in_channel_disabled` 403. Two operator
alert lines exist: `ERROR 'PROVIDER ACCOUNT PROBLEM: …'` and
`WARN 'one-time code by email not confirmed sent'`.

---

## What I would attack first

Being adversarial about my own side and the whole system, in order:

1. **Upload a `text/html` document and open its download URL.** It is served
   inline from the app origin with the uploader's Content-Type and no CSP. If
   that runs script, it reads the in-memory zero-knowledge content key, the
   `localStorage` refresh token (30-day, self-renewing), and anything the
   session can reach — collapsing §E and §F at once. This is the highest-value,
   lowest-effort target and it is not in my item-4 code; it is the first thing I
   would hand a tester.
2. **File an emergency request and read another member's private records.**
   `emergency_reveals` is household-scoped, not subject-scoped (§B). If it
   reproduces against a live stack the way it reads on the page, it is a direct
   break of the core promise, and the existing test would not have caught it
   because it only checks the subject's own rows.
3. **Move a ciphertext between rows.** The envelope AAD omits the row id (§D), so
   with any write primitive a private account number can be relocated onto a
   visible account and revealed. Pair it with the definer functions that have no
   authorisation if a SQL-injection foothold exists.
4. **Race the refresh endpoint** with a single stolen refresh token (§F) to fork
   a session past reuse detection, then keep it alive indefinitely.
5. **Attack my own restore path by trusting the manifest too much.** The manifest
   is inside the backup; a tamperer who rewrites a ciphertext *and* its manifest
   row and *and* the stored digest (all three are in the dump) passes every check
   I built. My checks catch **accidental** damage — storage rot, a torn transfer,
   a wrong key — not an adversary who owns the backup file. I say so in
   [Doc 17 §6](17-deploying.md); a reviewer should hold me to it, because it is
   the most flattering thing in item 4 and the easiest to over-claim.

The honest ranking: the three worst findings here (the HTML-upload XSS, the
household-wide emergency reveal, and the AAD row-swap) are all in code I did
**not** write, and the check I am proudest of (the restore drill) is the one with
the clearest stated limit. Treat §"verification" as the list of claims with
evidence, §A–§F as claims with an argument, and this section as where to start
turning the second kind into the first.

[‹ Index](README.md)
