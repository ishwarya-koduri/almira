# Known issues

A running log of things found while building, that are **not** being fixed in
the change that found them — either because the code is frozen, because the fix
belongs to a feature we have not opened yet, or because the right moment to
reconcile it is the next time someone is already in that file.

The rule for this file: an entry is written the moment it is noticed, with
enough detail that whoever picks it up does not have to rediscover it. An entry
is deleted when it is fixed, and the commit that fixes it says so.

Status: **open** unless a later line says otherwise.

---

## 1. Field order differs between the app and the web client

**Resolved** (2026-09-14, "Plans and support"). Kept as a stub so the number
still means something where it is cited.

`captureForm` in `static/app/screens/capture.js` now merges the re-labelled
columns and the type's attributes into one list and orders it by `sort`, the
way the native `toFormFields` always did: a Fixed Deposit reads Principal (20)
→ interest rate (30) → Opened on (40), as on the receipt. A missing `sort`
counts as 100 on both sides, and on equal numbers columns come first in the
schema's own order, as Kotlin's stable `sortedBy` over `columns + attributes`
does. Which fields exist, their labels and what they accept were never
different. The one leftover is a comment: `FormModel.kt` still describes the
web as ordering by group, and is for the next pass over `app/` to reword. Not
covered by an automated test — the web client has none for screens (docs/20
§9); checked by reading the two orderings side by side against the FD schema.

---

## 2. `POST /api/v1/auth/otp/verify` returns 500 for a missing `phone`

**Resolved** (2026-09-13, "Malformed bodies"). Kept as a stub so the number
still means something where it is cited.

Owner's decision: a request the server cannot read is answered as the caller's
mistake. `ApiErrorHandler` now maps each of these, which all used to reach the
catch-all as `500 internal_error` with an ERROR log:

- a required body field missing or `null` → `400 validation_failed`,
  `details.fields.<name>` = "This is required" (the declared name or path, e.g.
  `owners[0].memberId`; a map key the caller wrote is shown as `[*]`);
- JSON that does not parse, a field of the wrong type, an empty body →
  `400 malformed_request`, "We couldn't read that request.";
- a body in a content type the endpoint does not read → `415 unsupported_media_type`;
- a path or query parameter that does not convert (a non-UUID household id), a
  missing required query parameter, a missing multipart part →
  `400 malformed_request` with `details.parameter` naming it.

Each is logged once at INFO with the method, the route **pattern** and the
exception type, never the message or the concrete URL. `UnreadableBodyMessages`
still strips Jackson's message where it is made, and keeps only a missing
field's declared name. The correction is recorded in the API README's
changelog, under the freeze rule added for it.

Proven by `MalformedRequestTest` (every input above over HTTP: status, code,
envelope, no ERROR from any logger, the one INFO line, and a marker sent in the
bad part never in the response or any log at INFO or above),
`MissingRequiredFieldTest` (names come from declarations, never map keys) and
`OtpCodeNeverLeaksTest` (statuses now 400; still every logger at DEBUG).

One input was missed then and is covered now (2026-09-16): a body of the bare
literal `null`. It is valid JSON, so it never failed inside Jackson the way
every other case here did; it read as nothing and tripped the Kotlin null check
on the way out of the converter, which is a 500 on 99 of the 137 endpoints that
read a body, ten of them reachable with no token. It answers
`400 malformed_request` with the rest, in `MalformedRequestTest`.

---

## 3. A static-asset change needs a service-worker version bump

**Resolved** (2026-09-14, "Ops catch-up"). Kept as a stub so the number still
means something where it is cited.

The durable answer this entry named is in: `backend/build.gradle.kts` computes a
fingerprint (first 12 hex of a SHA-256 over the path and bytes of every file
under `static/` except `sw.js`) and `processResources` appends it to `VERSION`,
so the served worker says `almira-v24+352f1ee77391` and any asset change turns
the cache over. The hand-written part is kept and may still be bumped. The build
**fails**, naming this entry, if `sw.js` does not have exactly one
`const VERSION = "…";` line to stamp — watched failing with the line renamed to
`let`. Proven by `ServiceWorkerVersionTest` (the classpath worker carries the
fingerprint of the current sources; one changed CSS line changes it; the
worker's own bytes do not). `scripts/check-service-worker.js` reads the source
file, so it still sees the unstamped value, which it never asserts on.

---

## 4. Screenshot and recents protection is off in debug builds

**Where** `app/androidApp/.../MainActivity.kt`.

**What** `FLAG_SECURE` keeps a family's net worth out of the recents thumbnail
and out of screenshots. It is applied only when `!BuildConfig.DEBUG`, because
it also blacks out `adb exec-out screencap`, and a stage whose evidence is a
black rectangle cannot be reviewed.

**Why it is still here** Deliberate, and the release path is correct. What is
*unverified* is the release path: every screenshot in the review of this stage
is from a debug build, so the flag has been reasoned about rather than seen
working.

**When to fix** Not a fix — a verification. Build once with
`assembleRelease`, confirm `screencap` returns black and the recents card shows
a blank app, then record that here and delete this entry.

**Risk if left** None in production. The risk is only that we believe something
we have not watched happen.

---

## 5. iOS one-time-code autofill cannot be reached from Compose 1.8.2

**Where** `app/shared/src/commonMain/.../signin/OtpKeyboard.kt` and its two
actuals.

**What** iOS autofill is declarative: a field with
`textContentType = .oneTimeCode` makes the keyboard offer the code from the most
recent message, and no runtime API is called — which is why the iOS
`OtpAutofill` actual is an honest no-op rather than a stub that throws.

The attribute itself cannot be set. Compose Multiplatform 1.8.2's iOS text input
builds its traits in `androidx.compose.ui.platform.getUITextInputTraits`, whose
only input is `ImeOptions`, and `ImeOptions` carries no content type. Read off
the shipped klib rather than inferred:

- the entire iOS Compose UI klib contains exactly three content-type constants —
  `UITextContentTypePassword`, `UITextContentTypeEmailAddress` and
  `UITextContentTypeTelephoneNumber`. There is no `OneTimeCode` anywhere in it.
- `androidx.compose.ui.autofill.ContentType` *does* declare `SmsOtpCode`, but
  the string appears in no file under `package_androidx.compose.ui.platform` —
  the iOS text-input service never reads that semantics property.

**What this found on the way** `KeyboardType.NumberPassword` maps to
`UITextContentTypePassword`, so the six-cell code field was telling iOS it was a
password field and iOS would have offered saved passwords and Strong Password
over a one-time code. That is worse than offering nothing, and it is fixed:
`otpKeyboardType` is now `expect`/`actual`, staying `NumberPassword` on Android
(where it also keeps the code out of the keyboard's suggestions and dictionary)
and becoming a plain `Number` on iOS, which claims no content type at all.

**When to fix** Either a Compose version that maps `ContentType.SmsOtpCode`
through to `textContentType`, or a `UIKitView`-hosted `UITextField` on iOS only.
The second is available today and is deliberately not taken: it would replace
the six-cell field on one platform, and a visible design divergence is a poor
trade for one convenience.

**Risk if left** iOS users type six digits by hand. No incorrect behaviour and
no data risk now that the password claim is gone.

---

## 6. AES-GCM on iOS comes from Swift, not from the shared module

**Where** `app/shared/src/iosMain/.../zk/Aead.ios.kt` and
`app/iosApp/iosApp/CryptoKitAead.swift`.

**What** Not a defect — a constraint worth recording, because it qualifies a
claim earlier stages leaned on. Every other iOS-specific primitive in this app
is C and is called from Kotlin directly: the Keychain and the random from
Security, PBKDF2 and HMAC over CommonCrypto, the prompt from
LocalAuthentication. AES-256-GCM is the exception:

- CryptoKit is Swift-only and unreachable from Kotlin/Native.
- the public CommonCrypto headers in the iOS SDK expose no GCM at all — not
  `CCCryptorGCM*`, not `kCCModeGCM`. Those live in `CommonCryptorSPI.h`, which
  the SDK does not ship.

So `AppleAead` is injected from Swift by `installAppleAead` before any Compose
content exists. The seam holds — nothing above `Aead.kt` knows — but "iOS is a
target-add, not a rewrite" is true with the qualification that the add includes
about forty lines of Swift, and this is the whole reason any Swift beyond the
app shell exists.

Rolling GCM over CommonCrypto's AES-CTR by hand was considered and rejected: the
authenticating half is GHASH, and a hand-written GHASH inside a product whose
entire claim is that the server cannot read the data is not a trade worth
making.

**Consequence to keep in mind** A future iOS entry point that forgets
`installAppleAead` fails loudly on the first sealed field — there is no default
and no fallback, by design. And because the bridge only exists at runtime, the
envelope third of the B4 vector cannot be asserted in a Kotlin/Native test; it
is asserted at launch instead, by `zkSelfTest()`, against the same checked-in
constant.

**When to fix** Only if CryptoKit ever becomes reachable from Kotlin/Native, or
if Apple ships GCM in the public CommonCrypto headers. Neither is expected.

---

## 7. The server allows `|` in a sealed field's key

**Resolved** (2026-09-14, "Recovery for sealed fields"). Kept as a stub so the
number still means something where it is cited.

`SealedFieldService.seal` now refuses a `fieldKey` containing `|` with
`400 field_invalid`, beside the existing blank and 64-character checks, so the
rule both clients enforce at AAD construction is also enforced where the data
lands (docs/12 §1, §4). No client sends one, so nothing that worked stops
working. Proven by `E2eApiTest` ("a field key containing the AAD separator is
refused"), which also checks that nothing was stored.

---

## 8. The web client can set up zero-knowledge mode but cannot seal a field

**Resolved** (2026-09-14, "Recovery for sealed fields"). Kept as a stub so the
number still means something where it is cited.

Partly stale by the time it was fixed: the where-and-who editor (docs/20) had
already sealed "where the original is" and "who holds the key" from the browser
since V28. What was still missing was a sealed field a person *chose to write*.
Holdings, loans and accounts now carry a **Sealed note** card beside the
where-and-who card (`static/app/sealed-notes.js`): one note per record under
`fieldKey` `sealed_note`, sealed on the device, with the reassurance line and a
lock that closes as you type. Any other sealed value on the record, such as one
the native app wrote under its own key, is listed there read-only, so nothing
sealed is invisible in the browser. `GET /e2e/values` gained `sealedByMe`
(additive) so the card can say "sealed by someone else" rather than
"unreadable". Checked in a browser against a local server: a note with a
trailing space was sealed, reopened exactly, and the stored row was an envelope
without the words. The card has no automated test (the web client has none for
screens; see docs/20 §9).

---

## 9. A signed-in user with no household has no way out

**Where** `app/shared/src/commonMain/.../App.kt` — the `household == null`
branch of `SignedIn`.

**What** When `households()` comes back empty the app renders one line,
*"No household yet. The web client can create one."*, and nothing else. There is
no sign-out, no back, no retry. The only way off that screen is to delete the
app, because the session is in the Keychain and survives a relaunch straight
back onto the same dead end.

**How it was found** A UI test typed a phone number into an unfocused field, so
ten digits landed somewhere unintended and were sent as a sign-in. Signing in
creates the account when it does not exist — by design — so the run signed in as
a person who had never existed, who therefore belonged to no household, and the
app was stuck. A real person hits exactly this by mistyping their own number by
one digit.

**What it should be** The same escape the lock screen already has. That screen
offers *"Sign in as someone else"* precisely because being unable to get past a
gate must never be terminal, and this screen needs the same line for the same
reason. An invitation hint would help too, but the sign-out is the bug.

**When to fix** The next pass over `App.kt`. It is one `TextButton` calling the
`onSignOut` that is already threaded into `SignedIn`, so there is nothing to
design and nothing to plumb.

**Risk if left** Low severity, high annoyance, and it looks like data loss from
the outside: someone who fat-fingers their number sees an app with none of their
records and no way to try again. Not a privacy or correctness problem — the
empty household is genuinely empty.

**There is one such account in the personal dev stack.** `+919666873377`, made
by the run described above. It is deliberately left in place: it belongs to no
household and holds nothing, so it cannot affect a total or a privacy check, and
hand-deleting a `users` row risks orphaning its sessions and audit entries for
no gain. It is also the live case for reproducing this issue. It should go when
there is a proper account-deletion flow to remove it with — not by row
surgery. That flow now exists (docs/05 §12.1): sign in as it on the web client,
Settings → Close my account, and the lifecycle sweep erases it thirty days
later. A household-less account has nothing to preview and nothing to hand on.

---

## 10. DigiLocker's connect flow would be unsafe against the real service

**Resolved** (2026-09-14, "Plans and support"). Kept as a stub so the number
still means something where it is cited.

`ConnectService` now follows V24's design. `start` keeps a hash of the OAuth
`state`, who started, and a fifteen-minute expiry; `complete` takes `state`
(additive to v1) and refuses a missing, different, someone else's or stale one
with `400 connect_state_mismatch` before the authorisation code is redeemed.
The session token is encrypted with the household's data key into
`access_token_enc`, with the real `expires_at` and `scope`; `external_ref` is
left empty, and an expired session is refused with `400 connection_expired`
rather than sent. V100 removed the sandbox tokens already sitting in
`external_ref`. DigiLocker is also `disabled` by default now, so it stays hidden
until a client offers it. Proven by `DigiLockerSessionApiTest` (each refusal
leaves the connection pending with no token; the stored bytes are ciphertext; a
replayed state is refused; a blob moved to another household does not decrypt).
What remains before live is in [providers/digilocker.md](providers/digilocker.md):
the adapter itself, and PKCE if the partner asks for it.

---

## 11. `mode: off` does not start for DigiLocker, Account Aggregator or WhatsApp

**Resolved** (2026-09-13, "Disabled mode, AA cut"). Kept as a stub so the number
still means something where it is cited.

`disabled` is now a first-class mode for every provider. DigiLocker, Account
Aggregator and WhatsApp each have a disabled adapter (`provider/DisabledProviders.kt`)
that reports `mode: DISABLED`; `ConnectService` refuses every call to one with
409 `provider_disabled` before writing or calling anything, and the status
endpoint reports it as never connected. A disabled `sms`, `email` or `push` has
no sender, so `RecordingNotifier` skips it and records no row. `off` itself is
now **refused** by `ProviderModeCheck` with a sentence naming `disabled`, rather
than accepted as a second spelling.

Proven by `ProviderDisabledStartupTest` (the real application, each provider
disabled alone, all together, and the defaults), `ProviderDisabledApiTest` (every
call over HTTP, the status, notifications) and `ProviderModeCheckTest`; and once
with the built jar, all six disabled.

---

## 12. Choosing any one-time-code sender but `log` fails obscurely

**Resolved as a refusal** (2026-09-13, same change). `ProviderModeCheck` now
reads `almira.otp.provider` and refuses anything but `log` at startup with a
sentence pointing at [providers/sms.md](providers/sms.md), instead of dying on
"required a bean of type 'tech.bhrigu.almira.auth.OtpSender'". Watched in
`ProviderModeCheckTest`.

What is **not** done is the other half of "which is right": one switch, where
`almira.providers.sms.mode=live` selects a live OTP sender. That comes with the
live SMS sender (gap 1 in providers/sms.md), and until then `sms.mode` and
`otp.provider` stay independent — in particular `sms: disabled` does not stop
development sign-in codes, which go to the log, not through SMS.

---

## 13. Notification channels are never told who the recipient is

**Resolved** (2026-09-14, "Notification delivery"). Kept as a stub so the number
still means something where it is cited.

The outbox worker looks the person up when it sends (`DeliveryDirectory`, owner
connection): the account's phone number for `sms`, its email address for
`email`, and every registered device for `push`, one call per device. Devices
have a table (`user_devices`, V60, own rows only under RLS) and endpoints
(`PUT`/`GET`/`DELETE /api/v1/me/devices/{installationId}`); a token the platform
rejects is deleted. A live channel with no address records `skipped`,
`no_recipient`. Proven by `NotificationDeliveryApiTest` (the number reaches the
SMS sandbox, both devices reach push, a rejected device is forgotten),
`LiveEmailDeliveryApiTest` (the address reaches a fake SMTP relay; no address is
recorded) and the RLS suite. Neither native app registers a device yet — that is
entry 35. Since V50 the account notices — "New sign-in on …", a changed
phone number, a factor added or removed — are queued on every channel; they are
essential (`MessageTemplates`), so pacing and quiet hours never hold them, and
they now reach a live channel the same way.

---

## 14. Taking an address off the alpha allowlist does not sign it out

**Resolved** (2026-09-13, "Allowlist and visible failure"). Kept as a stub so
the number still means something where it is cited.

Owner's decision: removing a tester from the allowlist ends their sessions.
`AlphaAllowlistAccess` enforces it when a server starts without the address
(every live session of that email-only account revoked — refresh tokens, and the
session id in `SessionRevocationCache` so the access token stops too — before
the web server takes a request), on every authenticated request, and on
refresh; each ending is audited as `auth.session_ended_not_allowlisted` with
`via`. Accounts with a phone number are never touched. Proven by
`AlphaAllowlistRemovalApiTest`, which seeds sessions before its server starts.
The list stays in configuration; docs/13 §5 says why.

Since 2026-09-14 the check runs whatever the channel switch says: an email-only
account keeps a session only while email sign-in is on and its address is
listed. So taking the last tester off, or ending the alpha with
`ALMIRA_SIGN_IN_CHANNELS=phone`, signs every email-only tester out at startup
too (before, both left their sessions alive until they expired, because a server
without email did not run the check at all). Email on with an empty list still
refuses to start; turning email off is how the alpha ends (docs/13 §5). Proven
by `AlphaAllowlistEndedAtStartupApiTest`.

**What is left, narrowed:**

- **A rolling deploy.** An old server still running with the old list can sign
  a removed tester in, and serve them, until it stops. Any new server refuses
  that session on its first request or refresh. On the single-server compose
  deployment (Doc 17) this does not arise.
- **A phone-only server now reads accounts it used not to.** The per-request
  check runs everywhere, so a phone-only server reads each signed-in account
  once a minute (cached per account) to learn it has a phone. Not measured;
  expected to be negligible beside the request itself.
The refresh-reuse audit row, noticed here, is fixed (2026-09-14):
`auth.refresh_reuse_detected` used to be written in the refresh's own
transaction, which the refusal that follows rolls back, so the row was never
kept. It is now written inside `SessionRevoker`'s own transaction, only by the
call that ended the session. Proven by `RefreshReuseApiTest`, watched failing on
the audit assertion before the move.
The same rollback took `auth.second_factor_requested` (fixed 2026-09-15): it was
written in `verifyOtp`/`verifyEmailOtp`'s transaction just before the
`second_factor_required` refusal rolled it back, so a correct one-time code on
an account with a second factor (the recycled-number signal) left no row. It is
now written in a transaction of its own (`SecondFactorService.asUser`). Proven
by `SecondFactorApiTest`, watched failing on the audit assertion before the
move. No notice is sent for this event; that remains a product suggestion.

---

## 15. A failed sign-in email is invisible to the tester, and costs them requests

**Resolved** (2026-09-13, "Allowlist and visible failure"; residual signals
classified and closed 2026-09-14, "Allowlist enumeration"). Kept as a stub.

Owner's decision: a failed email send must not be silent. The email request
still answers before sending, but the code step now polls
`GET /api/v1/auth/otp/email/delivery/{requestId}` and says "We couldn't send
the code" (web: en/te/hi; native: en) with the reason, or that it is delayed,
and opens resend at once. Only a status that said "sent" is shown as sent: if
the status never settles within 30 polls, or cannot be read, the code step
shows the delayed banner with resend open (`deliveryWhenAskingStops`,
`EmailDelivery.whenAskingStops`). A failure now does what a reported phone failure
does — challenge removed, cooldown lifted, the per-address count given back —
so it no longer costs the tester requests. Decoys for unlisted addresses settle
through the same code, from their own provider call to the decoy sink
(`ALMIRA_ALPHA_EMAIL_DECOY_SINK`), so a failing provider fails for both at the
same moment (`EmailSignInApiTest`, `EmailOtpTest`).

**Superseded** (2026-09-15, "Sign-in emails go through an outbox, and an
unlisted address's is dropped there"): the decoy sink is gone, and with it the
visible failure. Every request is queued; the status says `sent` for everyone;
a tester whose code does not come has "Didn't arrive in two minutes? Contact us".

**The residual signals, classified** (2026-09-14, "Allowlist enumeration"). The
owner's rule: a signal that only confirms an address someone already has is
acceptable; one that lets someone discover addresses from a guessed list is
not. Every signal that was listed here — a provider's **rejection** of one
listed address, the window after a **provider state change**, **latency on a
tick boundary**, a **fresh Redis** — and one that was not (a probe of a listed
address rewrote what the next decoy reported) was not acceptable, and is
closed: decoys make their own provider call, every outcome is applied at one
moment after the request, and a rejection on the sign-in path is applied as
sent in every probeable respect. The cost, stated plainly in docs/13 §5
(Signal 1): a tester whose address the provider refuses is not told on screen;
the operator gets `ERROR SIGN-IN EMAIL REFUSED` with the masked address.

**Risk if left** None known from outside. Two conditions for the live email
adapter, untestable until it exists (docs/13 §5, item 7): anything about one
recipient must be classified `rejected`, never `unavailable`, and the sink must
not be throttled per recipient. Each decoy is a billed send, bounded by the
per-network cap. Operator alerts: WARN `one-time code by email not confirmed
sent`, ERROR `PROVIDER ACCOUNT PROBLEM`, ERROR `SIGN-IN EMAIL REFUSED`.

---

## 16. The frozen v1 contract does not describe email sign-in yet

**Resolved** (2026-09-14, "Re-freeze spec"). Kept as a stub so the number still
means something where it is cited.

Owner's decision: "regenerate the frozen spec to include the new endpoints.
They're additive, and endpoints outside the contract test are how drift starts."
`docs/api/openapi-v1.json` was regenerated with `scripts/freeze-api-spec.sh`
from a development server built from the working tree of the re-freeze commit
itself (6f5e049) — including its `snoozeStillTrue` rename below, which is why the
frozen file already carries that operationId — and now holds 120 paths,
163 operations and 179 schemas (was 109, 152, 164).

`OpenApiContractTest` now also fails when the server serves a path, operation,
parameter, response code, schema, field or enum value the frozen file does not
list (`OpenApiCompatibility.undeclared`), and says to re-freeze for an additive
change, so this drift cannot recur silently.

It had drifted by more than email sign-in: eleven paths were outside the file —
`GET /auth/otp/channels`, `POST /auth/otp/email/request`,
`POST /auth/otp/email/verify`, `GET /auth/otp/email/delivery/{requestId}`,
`GET …/connect/digilocker/documents`, `GET …/continuity/readiness`,
`GET …/still-true`, `POST …/still-true/{recordType}/{recordId}/confirm`,
`POST …/still-true/{recordType}/{recordId}/snooze`, `GET …/where-and-who` and
`GET /me/messages` — with fifteen new schemas. Four existing schemas grew:
`OtpChallengeResponse.channel` (optional), `WhatsAppCapture.replyFailure`
(optional), `Completeness.scoreEarned` (required, response only) and
`scoreExplanation` (optional), and `ProviderStatus.mode` gained `DISABLED`.
Nothing was removed, renamed or retyped; the old file is a strict subset by
`OpenApiCompatibility.check(old, new)`.

One real break was found on the way and fixed rather than frozen: the still-true
snooze handler was also named `snooze`, so springdoc renamed the frozen
`POST …/reminders/{id}/snooze` operationId from `snooze` to `snooze_1` — a method
rename in every generated client, which the contract test did not look at. The
handler is now `snoozeStillTrue`, and `OpenApiCompatibility` now reports a
changed operationId (watched failing against the old file before the rename).

---

## 17. Plaintext columns still hold "where the original is" — retired in V33 and V34

**Where** `investments.storage_location` (V3), `investment_templates.storage_location`
(V16), `estate_documents.location` (V18), and `storage_location` in eight seeded
type schemas. Also two seeded type fields that asked the same question into
`attributes`: `business_equity.agreement_location` ("Where the agreement is")
and `crypto.wallet_hint` ("Where the keys are"), V6.

**What was wrong** [Doc 20](20-where-and-who.md) seals the location of the
original, with no plaintext fallback. These columns held the same sentence in
plain text, and the server still wrote it: into duplicates, between holdings and
templates, and from any v1 client that sent it.

**What was done** On the owner's decision ("Do it before real users exist; it's a
migration now and a data-migration problem later"), V33 drops the three columns,
takes the field out of every type, and adds constraints so it cannot return. It
**refuses** instead, and changes nothing, while any non-empty value exists, and
its message says how many and how to seal and clear them (Doc 20 §1). Every
server path that read, copied or printed a location is gone, and a request that
still sends text gets `400 plaintext_location_retired` without an echo. The web
client and the native app neither show nor send it.

V33 only looked for `storage_location`, so review found the two `attributes`
fields still asked for, copied into duplicates and templates, and searched. V34
retires them the same way (refuse with counts while any non-empty value exists,
then remove from types and attributes, constraints, and the same
`plaintext_location_retired` refusal by name). `scripts/check-spec.py` scans for
all three keys.

**What is left** Kept here, not deleted, because it is not finished everywhere:

- Any database that still has notes must have them moved **with a build from
  before V33** (for example `02a198d`), whose web client has the move button.
  The owner's development database held one such holding when V33 was written.
  A database with `agreement_location` or `wallet_hint` values needs a build
  from before V34 (for example `47c9e74`); the owner's development database had
  none when V34 was written.
- A location typed into a field that is not sealed (a title, the notes, a custom
  field with another name) cannot be retired by a migration. The capture form
  points to the sealed card instead.
- The native app has no key-holder field; its generic sealed-field screen shows
  the guidance only if someone types the internal key `key_holder` or
  `original_location`.
- The v1 schema still lists `storageLocation` / `location` / `whereItIsKept`
  (always absent in responses, refused with text in requests). Removing them is
  a v2 change.
- `docs/19-pen-test-pack.md` still describes the columns as present; it belongs
  to the infra session and was not edited in this change.

**Risk if left** None on the server. A database that was not moved cannot start
this build, which is the intended failure.

## 18. Two clocks for "not confirmed lately"

**Resolved** (2026-09-14, "Honest completeness"). Kept as a stub so the number
still means something where it is cited.

Owner's decision: the dashboard card uses the same per-type periods as "Still
true?", with no second clock. `DashboardService` now counts a holding in the
`not_verified` attention item exactly when `still_true_records.is_due` is true
for it, read under the caller's RLS from the same view the still-true list and
sweep read. That brings along everything Doc 21 decides about *when*: the
per-type period (3 months for cash, 12 otherwise), a maturity or renewal date a
week past, a snooze, and the household's time zone (the card used the server's
UTC date). The label is now "Due to be confirmed as still true". The code
`not_verified` and the item's shape are unchanged. Proven by
`DashboardStillTrueClockTest`, watched failing against the flat six months: a
4-month-old cash buffer and a matured FD were missed, and a 7-month-old FD and a
snoozed one were counted.
Checked by hand on a development server: Home listed a 4-month-old cash buffer
under "Still true?" and the card said "1 holding", with a 7-month-old gold
holding in neither. The Reports card showed the sentence and no percentage for
an empty household, and "37%" once three holdings existed.

**What is left, narrowed:**

- **Who, not when.** The card counts every due holding the caller can *see*;
  the still-true list shows only the ones the caller is *asked* about (owners,
  or the recorder, Doc 21 §4). An admin can therefore see a household FD counted
  on the card that is not on their own "Still true?" list. Both agree on whether
  it is due.
- **Holdings only.** The card carries `investmentIds`, so loans, accounts and
  estate documents that are due appear only in "Still true?".
- **The card's "Review" button** still opens the holdings list, not the
  question. The native app renders neither the card nor the list.

## 19. The completeness score can say 100% with a gap still there

**Resolved** (2026-09-14, "Honest completeness"). Kept as a stub so the number
still means something where it is cited.

Owner's decision: completeness never shows 100 while any gap exists.

- **Rounded down.** `CompletenessScore.of` is floor(100 × earned ÷ possible), in
  integer arithmetic, so with anything outstanding it is at most 99. 250 complete
  holdings with one left out of the family summary (1 item in 1000) is 99, not
  100. `CompletenessScoreTest` checks every household size from 1 to 2000 with a
  single gap; `ReportsApiTest` checks the 1-in-1000 case end to end.
- **Nothing recorded is no number.** The response keeps `score` (v1 froze it as
  a required integer, and `OpenApiContractTest` refuses un-requiring it), sets it
  to 0, and adds `scoreEarned: false` and a `scoreExplanation` sentence. A client
  shows the sentence and no percentage unless `scoreEarned` is true. The web card
  does (`static/app/completeness.js`, `scripts/check-completeness.js`); a
  response without `scoreEarned` is no number there either, since the web client
  is served by the server it talks to. Before, `score` was 100.
- **Both clients.** The native app shows neither score yet. Its models
  (`app/shared/.../api/Models.kt`: `Completeness`, `HandoverReadiness`) carry
  `scoreEarned`/`scoreExplanation` now, with `scoreEarned` required, and keep
  `score` private: a screen can only ask `display()`, which is a percentage or
  the sentence (`ScoreDisplayTest`). `scripts/check-spec.py` ("SCORES") fails if
  any web module other than `completeness.js`/`readiness.js`, or any native
  source outside `Models.kt`, reads a `score`, or if those places stop reading
  `scoreEarned` / the null first. Checked in a running server: a brand-new
  household's Reports and For my family cards show the sentence and no "%".
- **Everything done is 100**, with `scoreEarned: true`.

Watched failing: rounding half up again (unit and API tests get 100 for one gap
in a thousand), returning 100 for nothing recorded (three tests), and the web
helper ignoring `scoreEarned` (it shows "0%"); the guard with a screen reading
`score` directly (web and native), with the readiness null test removed, and
with `Completeness.score` made public; `ScoreDisplayTest` with `display()`
ignoring `scoreEarned` (it gets `Percent(0%)`) and with a default for it.

**What is left, and deliberately not changed:**

- **Weighting.** Completeness still weights items and adds them up, so a large
  household dilutes a single gap (to 99, never 100). Readiness (Doc 22) gives
  each check equal weight. The two answer different questions; the owner's
  decision was about the 100, not the model.
- **Different records.** Completeness counts `active` holdings, including ones
  left out of continuity; readiness counts `active`/`matured` holdings in
  continuity, plus executed wills. The two percentages can still differ.
- **A stale client.** An app shell cached from before this change prints
  `score` for an empty household as "0%" (next to "Nothing to check yet") until
  the service worker (v24) updates it. The native app has no completeness
  screen.
- **The `scoreLabel` bands** are unchanged: 90 to 99 still reads "Your family
  could pick this up tomorrow", beside a `nextStep` naming the gap.

---

## 20. The reminder sweep reads through row-level security on master, and finds nothing

**Resolved** (2026-09-14, verified during "Failed resend keeps the earlier code").
Kept as a stub so the number still means something where it is cited.

The infra session's fix is on master (`32bfe38`, `infra/deploy-and-pentest`
merged): `DatabaseConfig.systemJdbc` takes
`@Qualifier("ownerDataSource") ownerDataSource: HikariDataSource`, with the
comment explaining why, and `ReminderSweepTest` proves the sweep writes a
notification for a due reminder. Verified at runtime, not only by reading: a
throwaway probe on a full application context (`ApiTestBase`, throwaway
database) found the `systemJdbcBypassingRls` template on pool `almira-owner`,
connected as `almira` (the schema owner), and `ReminderSweepTest` passed in the
same run. The probe was not committed: `DatabaseConfig` and its tests are the
infra session's.

---

## 21. A WhatsApp reply is sent inside its webhook, and background delivery is only as idempotent as the provider

**Status** Partly fixed by the owner's "interactive vs background" decision
(docs/13, "Interactive and background"). What this entry used to describe:

- **Duplicate texts — fixed.** A one-time-code send (SMS and email, sign-in and
  step-up) is now exactly one attempt under `almira.otp.send-timeout` (5 s),
  never retried whatever the provider's `max-attempts`
  (`ProviderCalls.callOnce`, `OtpService.issue`). One request cannot become two
  texts. A timeout keeps the challenge, lifts the cooldown so the person can
  resend at once, and keeps both hourly counts; a resend replaces the challenge,
  so a late code stops working. `OtpServiceTest`, `EmailOtpTest`.
- **Notification latency — fixed.** Reminders, still-true nudges and
  emergency-access notices on `sms`, `email` and `push` are background work:
  `RecordingNotifier` queues a row per channel with a unique idempotency key,
  and `NotificationOutbox` (owner connection) sends it with the provider's retry
  policy. No request or sweep waits on a notification provider.
  `NotificationOutboxTest`.

- **Connect calls — fixed** (2026-09-14). DigiLocker and Account Aggregator
  calls still run in the request, because a person is waiting for the answer,
  but never inside a transaction: `ConnectService` reads, calls with none open,
  then writes. The whole call, retries included, is bounded by
  `almira.providers.connect-budget` (20 s) through `ProviderCalls.interactive`.
  `ConnectCallsOutsideTransactionsApiTest` samples the app pool during a hang
  (watched failing with `@Transactional` put back). What remains is inherent: the
  person's own request waits up to the budget.

- **A redelivered WhatsApp message — fixed** (2026-09-14, "Plans and support").
  The sandbox parser reads Meta's message id, and `WhatsAppDeliveries`
  remembers the first delivery of each id per household for seven days in
  Redis (a hash of it, after the signature check). A redelivery answers 200
  with `duplicate: true` (additive) and neither parses nor replies again. A
  payload without an id is new every time, as before; if Redis is unreachable
  the message is treated as new and a WARN logged. `ProviderFailureApiTest`
  ("a redelivered WhatsApp message is answered once…"): with the reply
  rejecting, the first delivery reports `provider_rejected` and the second
  does not try.

**What is still open**
- **A WhatsApp reply is still sent inside the inbound webhook.** Its outcome is
  part of the capture's answer (`replyFailure`), so it was not moved. A slow
  reply can still make Meta redeliver, but the redelivery no longer captures
  or replies twice (above). Two consequences remain: the webhook waits up to the
  reply's timeout, and a message whose handling failed after its id was
  remembered is not retried by a redelivery. **When**: before WhatsApp goes
  live — queue the reply through the notification outbox and mark the id only
  once the capture is answered.
- **The idempotency guarantee rests on the provider.** On a channel whose
  adapter declares `honoursIdempotencyKey = true` (the `sms` and `email`
  sandboxes), a timeout and a send cut off by a crash are sent again with the
  same key, and only the provider's de-duplication stops a second delivery. A
  live adapter that declares `true` for a provider that does not de-duplicate
  — or remembers keys for less than the worker's lease, about 3.5 minutes at
  the SMS defaults — brings duplicate texts back. On `false` (push) delivery is
  at-most-once: the one send a worker had stamped as started when it died
  loses that message rather than risk a second (the rest of its batch is sent
  normally), and the in-app row is the only copy. Live email over SMTP
  (`SmtpEmailSender`) declares `false` too: SMTP cannot drop a repeat.
- **Logical keys cover reminders, still-true digests and emergency-access
  notices** (`reminder:<id>:<firesOn>:<user>`, a digest key for the exact due
  state, `emergency.named:<contactId>`, `emergency.requested:<requestId>`,
  `emergency.vetoed:<requestId>`), and in-app rows are keyed too (V35), so a
  repeated sweep or a second server queues nothing new. Only the reminder and
  `emergency.named` keys were watched failing. One consequence to know: saving
  the same emergency contact again — even with a changed wait — does not notify
  the trusted member a second time; deleting and re-adding the contact does.

**Risk if left** None while WhatsApp is a sandbox. Live: WhatsApp webhooks
captured twice, and — only if an adapter misdeclares its provider — duplicate
notifications.

---

## 22. A code request that replaces a live one and then fails leaves neither

**Resolved** (2026-09-14, "Failed resend keeps the earlier code"). Kept as a
stub so the number still means something where it is cited.

`OtpService.issue` no longer overwrites the challenge it replaces. One Redis
script (`REPLACE`) moves it aside under the new request's id — `RENAME`, so it
keeps its own remaining lifetime — and writes the new one. What happens to the
set-aside challenge depends on how the new send went:

- **Sent** or **timed out**: it is deleted. A code that went out, or may have,
  is the newest, and an older code never comes back after it.
- **Rejected, unavailable, insufficient balance**: `FALL_BACK` removes the new
  challenge and puts the old one back, atomically, only if the new challenge is
  still the current one, the old one has not expired, the old request's own
  send is not recorded as failed (`otp:not-sent:<id>`), and the old attempts
  plus the wrong codes tried against the new challenge are under the cap. The
  restored challenge also answers to the failed request's id, because an
  emailed code's step switched to that id before the send.

Throttling is unchanged: the cooldown, both hourly counts and what a failure
gives back are exactly as before, and a failed request cannot reset or add
guesses. Same for phone, and for email step-up. Email sign-in no longer falls
back: its email is queued and nothing the send does comes back to the challenge
(entry "Sign-in emails go through an outbox, and an unlisted address's is
dropped there"), so a resend replaces the earlier code at once.
Proven by four tests in `OtpServiceTest` and three in `EmailOtpTest` (the
`known-issues 22` sections), each watched failing, and by the attempt-cap tests
below.

**The attempt cap on a restored code** (2026-09-14, "Restored-code attempt
cap"; treated as security). Tests written first against de68d05 found a hole:
a wrong code typed while the resend was in flight was counted only against the
new challenge's own count, so with *k* attempts used on the earlier code the
in-flight challenge still took the full `max-attempts` wrong codes — up to
`2 × max-attempts − 1` judged per counted send, and the per-number count of a
failed resend is given back. `FALL_BACK` did refuse to restore at the cap, but
nothing proved it: disabling that check left every test green. Now
`RECORD_MISS` counts the set-aside challenge's attempts too and removes both at
the cap, and `CONSUME` refuses a challenge at its cap. With the shared count
the `FALL_BACK` and `CONSUME` checks are no longer reachable through the API;
they stay for a miss recorded without the shared count (an instance on the old
script during a rolling deploy), and tests set that state in Redis directly so
each check is proven on its own. Proven for phone and email, each watched
failing against the unfixed code and against mutations (attempts reset on
restore, the shared count removed, the `FALL_BACK` check disabled, the
`CONSUME` check disabled, a fresh lifetime on restore, the count given back to
zero, restored misses not counted for the network): attempts kept for every
outright failure and every *k*; a
guess-and-failing-resend loop run six times past the cap judges at most
`max-attempts` wrong codes and then refuses the right one; a locked code is not
brought back; no restore adds lifetime; parallel wrong codes racing a failing
resend stay within the cap; the network wrong-code allowance and the per-number
and per-network request allowances count across restores.

One consequence: while a resend is in flight, its `attemptsRemaining` counts
the earlier code's wrong codes too, so someone who typed wrong codes and then
pressed resend has fewer tries at the new code until its send settles.

**What is left, narrowed:**

- **While the new send is in flight** (up to `send-timeout`, 5 s, for a
  reported send; until it settles for email) the earlier code answers
  `otp_stale` under its own id, as before. It works again once the send has
  failed.
- **Only with a zero cooldown**: if a third request replaces a second whose
  send is still in flight, and the second then fails, the first is not put
  back when the third fails too. The default 30-second cooldown makes this
  unreachable.
- **The failed request's delivery status still says "We couldn't send the
  code"** with resend open; it does not say that the earlier code still works.
  Clients unchanged.

---

## 23. Migration V26 on the infra branch sits below V27 to V30 on master

**Resolved** (2026-09-14, verified during "Failed resend keeps the earlier code").
Kept as a stub so the number still means something where it is cited.

The infra migration was renumbered before it landed: master has
`db/migrations/V31__ciphertext_digests.sql` (`32bfe38`) and no `V26` on master
or on `infra/deploy-and-pentest`; `deploy/restore/digest-check.sql` and
`docs/17-deploying.md` name `V31`. Verified at runtime: a throwaway database
migrated by the full application recorded versions 25, 27, 28, … 35 in that
order, all successful, with 31 `ciphertext digests` between 30 and 32.
`outOfOrder` is still not enabled anywhere.

The same class of risk, re-checked for the catch-up plan (2026-09-14): its
workstreams were given disjoint version ranges (V36–V39 and up, this sign-in
work V50), so versions will have gaps on master. Gaps are harmless; what is not
is a database that has applied a **higher** version before a lower one lands,
because Flyway (no `outOfOrder`) then refuses to start with "Detected resolved
migration not applied to database". The safe resolution, without rewriting any
applied migration: merge every range before any shared or production database
migrates past V35, and if one ever has, renumber the not-yet-applied lower
migration above the highest applied version before it lands — exactly what was
done for V26 → V31. `outOfOrder` stays off.

---

## 24. The design revision reached the web client, not the native app, and not every screen

**Where** `app/shared/src/commonMain/kotlin/tech/bhrigu/almira/shared/theme/`;
the web screens `screens/goals.js`, `screens/continuity.js`, `screens/auth.js`.

**What** Doc 25 is the token reference and `static/app/tokens.css` follows it.
Three things do not yet:

1. **The native theme** still has the earlier palette and scale: the hero is
   `gold` (2.42:1 on white), `cautionSoft` is `#FBEFE4`, there is no `brass`,
   no Clear palette, no Noto faces, no 13sp floor, no Larger text and no
   five-destination navigation. Doc 25 §9 is the exact list.
2. **One teal action per screen** is held by the shell — the + is the one teal
   fill it owns — but a few screens still draw their own primary button beside
   it: "New goal" on Goals, "Print" on Family plan. Each is a real action;
   which of them stays teal is a per-screen call for whoever next owns that
   screen. ("Add" on Investments is a plain button since X-54.)
3. **`.faint` in `screens/auth.js`** (two captions). `base.css` now draws
   `.faint` as muted ink, so they are legible; the class name is only
   misleading.

**Why it is still here** The native `app/` tree and the sign-in screen are
being changed in parallel work, and a token change in the middle of that is a
merge conflict with no product gain. The screen buttons need a decision, not a
find-and-replace.

**When to fix** The native theme: in the next change to `AlmiraColors.kt`, with
Doc 25 §9 open. The rest: the next time someone is in that screen.

**Risk if left** On a phone app build, the net-worth figure fails contrast and
captions are small — exactly the problems the web client no longer has. On the
web, a second teal button competes with the +; nothing is unreadable.

---

## 25. The public site, the breach plan and the usability protocol wait on people, not code

**Where** `site/`, [Doc 26](26-incident-response.md),
[Doc 24](24-usability-sessions.md).

**What** All three are written and none can finish without a decision or a
person the repository does not have:

- **Nobody is named in the breach plan.** Doc 26 §2 has roles and no names, and
  every notice template says `[CONTACT]`. Until both are filled in, there is a
  runbook and still no owner. The plan has not been read by counsel or
  rehearsed; there is no procedure for rotating `ALMIRA_KMS_MASTER_KEY`; and
  whether the CERT-In six-hour directions apply is unconfirmed.
- **The site's placeholders.** Pricing says no price is decided; *What we
  measure* says nothing has been decided; status is hand-written with no feed;
  the security page has no reporting address and no pen-test summary, because
  none has been done (Doc 15 §11). The plan's direction also asks for a real
  founder on the home page, which is the owner's to write. The domain is
  undecided, so the `/sign-in` redirect in Doc 17 §10 names `example.in`.
- **Nothing is native-reviewed.** The Telugu and Hindi home pages say so on the
  page and are `noindex`; the Telugu and Hindi breach notices and the consent
  script are marked "needs native review".
- **No usability session has been run.** Doc 24 is the protocol only; the row
  it serves stays open until five sessions have produced plan rows.

**Which is right** The documents as written: each says what it is waiting on
rather than pretending.

**Why it is still here** Each item needs an owner decision, a lawyer, a native
speaker or a family, not a change to the code.

**When to fix** Before launch for the breach-plan names, the contact address,
counsel's read and the site's reporting address; before the Telugu or Hindi pages
are indexed for their review; before any UX row is acted on for the sessions.
`scripts/check-site.py` forces the draft notice and `noindex` to be removed
together.

**Risk if left** A breach handled with nobody named and a notice written in a
hurry; a public page in a language nobody checked.

---

## 26. Sealed values do not move with a departure

**Where** `backend/.../lifecycle/DepartureCompletion.kt` (`move`).

**What** When someone leaves a household and takes what is theirs, the rows move
and the server-encrypted fields are re-encrypted for the new household. Sealed
values (Doc 12) cannot be: their AAD is `householdId|recordType|recordId|fieldKey`,
so a ciphertext moved to another household no longer opens, and the server
cannot re-seal what it cannot read. They are dropped from the old household. The
person's "Download everything" has them as ciphertext, the departure preview
counts them (`sealedFieldsThatStayBehind`), and the Leave household sheet says
"seal them again afterwards".

**Which is right** A client-side step: before the seven days are up, a device
that is unlocked opens each sealed value on the records that will move and
seals it again under the destination household's id once the move lands. That
needs the destination id before completion (it is created at completion today)
and a client flow neither client has.

**Why it is still here** The client half is a Doc 12 change in two clients, one
of which could not seal a field when this was written (known issue 8, since resolved on the web).

**When to fix** With known issue 8, or the first time someone asks for their
sealed "where it is" lines to survive a move.

**Risk if left** A person who leaves with sealed fields must re-enter them from
their download. Nothing leaks: the values are dropped, not moved somewhere they
open.

---

## 27. The lifecycle flows have a web client and no native screens

**Where** `app/shared/...` — the Compose app.

**What** Closing an account, Download everything, Leave household, Mark as
passed away, a successor and the coming-of-age welcome are in the API and the
web client (`static/app/lifecycle.js`) only. Apple requires in-app account
deletion for an app with sign-up (Doc 09), so the native app cannot ship to the
App Store until it has at least Close my account. Doc 09's and Doc 10's launch
checkboxes stay unticked for this reason.

**Also open, in the same area**
- The `lifecycle.*` strings are English only; Telugu and Hindi fall back to
  English (docs/14).
- The privacy notice (Doc 23) does not yet say that security records are kept
  for a year after an account closes. The closure screen does. Changing the
  notice needs all three languages and belongs with its legal review.
- The readable PDF in the download uses the standard PDF fonts, so Telugu and
  Devanagari names print as question marks there; the CSV and JSON carry them
  exactly, and the README in the zip says so.
- The coming-of-age month is India's month, not the household's `time_zone`.
- A memorial stops messages from the moment it is made. The outbox worker asks
  again as it claims each queued row, as it does for withdrawn consent to
  messages, so an email, text or push waiting out quiet hours or the daily limit
  is recorded `skipped` (`notifications_stopped`, `no_consent`) instead of
  going. Only a send already started when the memorial or withdrawal lands can
  still arrive.
- A memorial does not stop the warnings a living person needs in order to say
  no (V103, `app.never_stopped_by_memorial`): an emergency request or check-in
  about them, a successor claiming the household, being asked to leave, and
  account-security notices. Before V103 an admin who was also someone's trusted
  contact could mark them and then ask for emergency access without the request
  ever reaching them. The inactivity sweep still skips a memorialised owner, so
  it raises no request against them either.

**When to fix** The native screens before an App Store submission; the rest when
someone is next in those files.

**Risk if left** No App Store release; otherwise cosmetic.

---

## 28. Testcontainers cannot reach Docker Engine 29 without an API version

**Where** `backend/build.gradle.kts` (the `test` task), on a machine running
Docker Desktop with Engine 29.

**What** `./gradlew test` without `ALMIRA_TEST_DB_URL` fails every integration
test with "Could not find a valid Docker environment": the docker-java client
inside Testcontainers negotiates an API version the engine no longer accepts
(`400` on `/info`). Setting the client's version fixes it:

    JAVA_TOOL_OPTIONS=-Dapi.version=1.44 ./gradlew test --tests '…'

**Why it is still here** Found while building the lifecycle flows; the durable
fix (pass `api.version` to the test JVM from the build script, or a Testcontainers
release that negotiates) is a build change outside that work.

**When to fix** The next time someone is in `build.gradle.kts`.

**Risk if left** Development friction only. CI uses service containers.

---

## 29. The native app cannot finish a sign-in that needs a second factor

**Where** `app/shared/.../signin/SignInController.kt` and its screen.

**What** Since V50 an account can add an authenticator app, a passkey and
recovery codes (web: Settings, How you sign in). A correct one-time code to
such an account answers `401 second_factor_required` with
`details.secondFactorToken` and `details.methods`, and the session starts only
at `POST /auth/second-factor/{authenticator,recovery-code,passkey}`
(docs/api/README.md "Second factor"). The native app knows none of this: it
shows the server's message ("One more step…") as a failed sign-in, and there is
no way forward in it. An account without a second factor is unaffected, and the
app offers no way to add one, so only someone who set one up on the web meets it.

**Which is right** The web client's second step (`secondFactorStep` in
`static/app/sign-in-security.js`): an authenticator code field, a passkey button
where the platform supports one, a recovery code, and "Start again".

**When to fix** Before the native app is given to anyone who uses the web
client's How you sign in card. Authenticator and recovery code are two plain
POSTs; a passkey needs the platform's credential manager.

**Risk if left** A person locks themselves out of the native app (not the web)
by adding a second factor.

---

## 30. The sign-in security screens are English in Telugu and Hindi

**Where** `static/app/i18n.js`, every `signin.*` key.

**What** The How you sign in card, the confirm sheet, the authenticator and
recovery-code sheets, the phone-number change and the second sign-in step were
written in English only. `t()` falls back to English, so a Telugu or Hindi
reader sees these screens in English. The server's own sentences
(`second_factor_invalid` and the rest) are English everywhere, as docs/14 says.

**When to fix** With a reviewed translation; these are words about account
security, where a loose translation does harm.

---

## 31. Recovery for sealed fields: what is not built yet

**Where** `app/` (native), `static/app/recovery.js`, `static/app/i18n.js`,
docs/12 §10.

**What** Recovery (V55, docs/12 §10) is built on the server and in the web
client. Four things are not:

- **The native app neither makes nor uses recovery copies.** It can still
  rotate a passphrase for a key with no copies. **If a copy exists — made on the
  web — the app's rotation is refused** with `409 recovery_copies_would_break`,
  because it does not send `contentKeyId` and the server will not let a write
  that does not name the key risk orphaning the copy. No data is lost; the app
  should say to change the passphrase on the web until it sends the id.
  To do on the app branch: HMAC-SHA256 key id on every `PUT /e2e/key`, then
  the §10 flows, asserting the §10.6 constants (`InteropKatTest` style). This
  branch did not edit `app/`, which is being changed elsewhere.
- **No QR code is drawn on the printed sheet.** `qrPayload()` defines what it
  would carry (`ALMIRA-RECOVERY:` and the 40 characters, QR alphanumeric mode).
  A QR encoder is a few hundred lines with Reed-Solomon; writing one by hand
  without a decoder to test it against was not worth risking on a page whose
  job is to open things years later, and adding a dependency needs a decision.
  The code is printed large, in groups of five, with a checksum that catches a
  mistyped character.
- **The recovery and sealed-line strings are English only** (`seal.*`,
  `recovery.*`, `note.*`, `ready.openable.*`). Telugu and Hindi fall back to
  English. They need a translator who can keep "recovery share" and "any two
  open it" exact; a wrong word here is a family that thinks one share is enough.
- **Not seen:** the print stylesheet on paper, the recovery sheets at phone
  width and at 200% text.

**Risk if left** The native one is the only one with a sharp edge: a person who
made a sheet on the web and then changes their passphrase in the app is refused,
with a sentence, and nothing changes. The others are completeness.

---

## 32. Consent to messages is assumed until someone withdraws it

**Resolved** (2026-09-15, "Consent to messages is asked for"). Kept as a stub so
the number still means something where it is cited.

Owner's decision: *"Message consent: opt-in, not opt-out. Ask at the moment the
first reminder would be useful. Do not inherit consent from people who were
never asked."* V125 turned the question round: `app.messages_consent_given(user,
channel)` is true only when the person's latest `messages` event is `given` and
names that channel, and `app.enqueue_outbound_message` asks it **before** it
writes a row, so a reminder, the Still true? digest or any other non-essential
message is never queued for someone who did not say yes. Nobody was migrated
into consent. The worker asks again as it claims a row (`no_consent`). The
essential account and security notices that go regardless are one list,
`app.message_is_essential` (docs/23 "Notices that protect your account"). The
question is asked in the web client right after a save that makes a reminder,
and on Your data rights. `MessagesConsentTest` proves nothing is queued or sent
without a yes; it was watched failing with the check moved after the insert.
What is still open is "Consent to messages is asked for on the web only, and
some reminders are never asked about".

---

## 33. Parental consent checks the adult's sign-in, not their age

**Where** `backend/.../privacy/ParentalConsent.kt`, `parental_consents.verification`.

**What** Rule 10 asks for *verifiable* consent from a parent who is an adult.
What Almira records is a declaration ("I am 18 or older, and this child's
parent or lawful guardian") confirmed by a fresh step-up code to the adult's
own sign-in phone or email. That proves who is signed in. It proves nothing
about age: a phone number or an email address does not.

**Which is right** Rule 10 points at reliable identity and age details already
held, or a virtual token from an entity entrusted by law — DigiLocker's age
token is the obvious one. `verification` is a checked column so a second value
(`digilocker_age_token`) can be added without touching existing rows.

**Why it is still here** DigiLocker needs a registered partner account and its
connect flow is not safe yet (known issue 10). No outside call is added until it
can be config-gated and tested against a sandbox.

**When to fix** With DigiLocker going live, before 13 May 2027 if counsel says
the declaration is not enough.

**Risk if left** A minor could record "consent" for a sibling. Low while every
child in Almira is added by the household that already holds their records.

---

## 34. Rights requests are answered by hand, and nominees cannot come forward in the product

**Where** `data_rights_requests`, `data_rights_nominees` (V45).

**What** A correction or complaint lands with its reply-by date, and nothing
else happens: there is no operator screen, no alert as the date approaches, and
the answer (`response`, `answered_at`, `status = answered`) has to be written on
the owner connection. A nominee is recorded, but there is no flow for them to
come forward, prove the death or incapacity, and act.

**Which is right** An operator queue ordered by `respond_by`, with the answer
written through a narrow function and audited; a nominee flow that ends in the
same operator queue.

**Why it is still here** It needs a staff role this product does not have yet,
and a decision on what evidence a nominee must bring.

**When to fix** Before any real person can send a request — at the latest,
before 13 May 2027.

**Risk if left** A request sits unanswered past its promised date.

---

## 35. Only email can reach a person yet, and what is left for the rest

**Where** `provider/` (the channels), the native apps, `MessageTemplates.kt`,
`deploy/docker-compose.prod.yml`.

**What** The delivery path is built end to end — recipients, devices,
preferences, quiet hours, one message a day, words that say why (docs/13) — and
one live adapter exists, SMTP email. The rest is not code this repository can
finish on its own:

- **SMS and WhatsApp stay sandbox.** A real Indian SMS needs DLT registration
  of the entity, header and every template, which needs GST registration first
  (docs/providers/sms.md); WhatsApp needs a Meta-verified business and approved
  templates. Both are business registrations. The SMS line a template must match
  is fixed now (`MessageTemplates`, one line: title and short reason).
- **Push has no device.** The server stores tokens and sends to each, but
  neither native app asks for notification permission or calls
  `PUT /api/v1/me/devices/{installationId}` (docs/providers/push.md step 1).
- **Telugu and Hindi message wording are drafts** (`MessageTemplates.TELUGU`,
  `HINDI`, `needsReview = true`) and are never sent: a te/hi user gets English
  until a native speaker reviews them and adds the language to `REVIEWED`. The
  new web strings (`notify.*`, `still.askLater`, `still.askedLater`,
  `family.diedOn*`) are English only and fall back in te/hi.
- **The production compose file does not pass `ALMIRA_PROVIDER_EMAIL_SMTP_*`**
  through to the application. It belongs to the deploy session; until it does,
  `email: live` in `.env` alone refuses to start for want of a host.
- **Bounces are not read.** A relay accepts a message and the bounce arrives
  later by email; nothing marks the address bad, so a dead address keeps being
  sent to (and each is recorded `sent`).
- **`no_recipient` is visible.** On a server with live email, a person who
  signed in by phone sees each reminder's email row in `/me/messages` as "Not
  sent — we don't have somewhere to send this for you". Honest, and possibly
  noisy; the web list may want to fold these.
- **Pacing assumes one scheduling instance**, as the sweeps do (docs/21 §7): two
  workers deciding in the same moment could each let a different message
  through as a person's first of the day.

**When to fix** Each when its provider is chosen; the compose file before
turning email live; the drafts before inviting a Telugu- or Hindi-first tester.

**Risk if left** None for correctness or privacy. The cost is that a reminder
reaches a phone only by email.

---

## 36. Testcontainers cannot reach this machine's Docker (Engine API 1.55)

**Where** `backend/src/test/.../support/TestInfra.kt`; Testcontainers 1.21.3.

**What** Found while building notification delivery. With Docker Desktop's
engine at API 1.55 (minimum 1.40), every Testcontainers strategy fails with
`BadRequestException (Status 400)` and "Could not find a valid Docker
environment", so `./gradlew test` with no `ALMIRA_TEST_*` variables starts no
database. `DOCKER_API_VERSION=1.44` did not help. The suites ran against an
external pair instead, exactly as `TestInfra` documents: a `postgres:16-alpine`
with `testcontainers-init.sql` applied (database `almira_test`) and a
`redis:7-alpine`, via `ALMIRA_TEST_DB_URL`, `ALMIRA_TEST_REDIS_HOST` and
`ALMIRA_TEST_REDIS_PORT`.

**See also** entry 28, "Testcontainers cannot reach Docker Engine 29 without an
API version", which records the workaround (`-Dapi.version=1.44`).

**Which is right** A Testcontainers version whose docker-java negotiates the API
version (1.21.x pins an old default), or an `api.version` in
`~/.testcontainers.properties` on machines with a new engine. Not verified which.

**When to fix** The next dependency update. **Risk if left** Local friction
only; CI uses service containers.
---

## 37. The price and rate feeds have only been fetched from a laptop

**Where** `market/MarketData.kt` (`HttpMarketFileFetcher`), `market/PriceFeed.kt`,
`money/LiveRateSource.kt`; docs/13 §6.

**What** Both feeds are off by default, and every test drives them with
fixtures cut from the real files. The file formats and addresses were confirmed
by downloading them on 14 September 2026 from a developer machine in India. No
deployed server has fetched them. NSE's archive in particular is known to refuse
requests it takes for automated traffic from some hosting ranges, and none of
the three publishers promise their addresses.

**What happens if it fails** Nothing breaks: the run logs `price feed <source>
skipped` or `exchange-rate refresh skipped` at WARN and the last valuation or
rate stays, with its date. But nobody is told, and a family would see "Valued
at NAV as of" an ever older date.

**When to fix** Before switching either flag on in production: run the job once
from the production host and read the log line; add an alert on the WARN (or on
the newest `price_feed` valuation being more than four days old) the way docs/17
alerts on provider failures.

---

## 38. A holding abroad with a loan against it shows net equity in mixed currencies

**Where** `investment/InvestmentController.kt` (`toResponse`, `netEquity`).

**What** Found while formatting foreign values (P-30). `netEquity` is the
holding's value minus the debt secured against it, subtracted as plain numbers.
A holding in USD against a rupee loan subtracts rupees from dollars. Rare —
a loan is seldom recorded against a foreign asset — and the dashboard's own
totals convert before adding, so no household total is wrong.

**When to fix** When liabilities learn their currency: convert the encumbrance
into the holding's currency with `CurrencyService`, or leave `netEquity` null
when the currencies differ and say why.

---

## 39. The phone app shows neither the "Didn't understand" chips nor the NAV stamp

**Where** `app/shared` (quick-add chips, holding detail).

**What** The web client renders the new quick-add fields (`notUnderstood`,
`start`/`end`, `hint`, rate/maturity/nominee chips) and the price stamp
(`valuationSource`, `priceSource`, `unitPrice`). All are additive v1 fields, so
the app keeps working, but it still shows only the old chips, and a price-fed
value there reads like any other snapshot.

**When to fix** The next app stage that touches capture or the holding screen.

---

## 40. The privacy notice does not mention product measurement yet

**Where** `docs/23-privacy-notice.md` and the in-app notice (`privacy.*` keys in
`static/app/i18n.js`, held to docs/23 by `scripts/check-spec.py`).

**What** V70 added aggregate product measurement ([What we measure](what-we-measure.md)):
twelve events counted per day with no identifier, on by default, with a per-person
opt-out in Settings. The Settings card and its sheet say this in plain words, but
the privacy notice's "What Almira stores" section does not, and it should say that
counts exist and that nothing about the person is in them.

**Why it is still here** The notice is a draft awaiting legal review, is written
in three languages, and is checked against docs/23 sentence by sentence. A
change to it belongs with whoever owns that review, not in the change that added
the counting, and a Telugu and Hindi sentence should not be guessed.

**When to fix** At the notice's next revision: one sentence under "What Almira
stores" linking to What we measure, in all three languages, in docs/23 and
`i18n.js` together. The Settings strings (`measure.*`) are English only today
and fall back in Telugu and Hindi.

**Risk if left** A reader of the notice alone would not learn that per-day counts
are kept. Nothing about them is personal, and the switch is one screen away.

---

## 41. With `ALMIRA_STORAGE_PROVIDER=s3`, backups do not include the documents

**Resolved** (2026-09-15, `014e2e9`), by the owner's answer rather than by
putting the bucket in the backup: *neither refuse nor warn — require explicit
acknowledgement*. `backup.sh` refuses an `s3` deployment unless run with
`ALMIRA_BACKUP_DOCUMENTS=external`, and then says in its output and in
`manifest.json` (`documents.in_this_backup: false`, with the bucket and never a
credential) that the documents are not in it; the flag with the filesystem
provider is refused as a contradiction. `restore.sh`, `restore-drill-local.sh
--from` and `drill.py verify --manifest` require the same acknowledgement for
such a backup, refuse it for a filesystem target, and treat a manifest without
the field per the target's provider — all before they write anything
(docs/17 §6 "Documents in object storage"). What brings the documents back is
still the provider's versioning or replication, which is the deployment's to
turn on. Kept below as it was, so the number means something where it is cited.

**Where** `scripts/backup.sh`, `scripts/restore.sh`, `document/S3DocumentStorage.kt`.

**What** `backup.sh` tars the documents **volume**. With the S3 provider the
documents are in a bucket, and neither script reads or writes it, so a backup
taken on an S3 deployment has the document rows and not their bytes.

**Why it is still here** The provider is new and off by default, no deployment
uses it, and the right answer depends on the provider chosen (object versioning,
replication, or a `mc mirror` into the backup) — an owner's decision.

**When to fix** Before any deployment sets `s3`. Either teach `backup.sh` to mirror
the bucket prefix into the backup and `restore.sh` to push it back (with the
manifest's hashes), or document the provider-side versioning the restore drill
relies on. docs/17 §6 says this meanwhile.

**Risk if left** None on the default. On an `s3` deployment, a restore from these
backups alone would bring back every document as a missing file.

---

## 42. p95 at 20 concurrent users was over the load budget on a loaded laptop

**Where** `scripts/load-test.sh`, docs/17 §9.

**What** The one run so far: 2 000 requests from 20 people, 20 at a time, no
failures, p95 1 178 ms against an 800 ms budget (dashboard p95 1 214 ms, adding a
holding 1 322 ms). The same server at one request at a time had p95 195 ms. The
machine had a load average of 40–66 from other test suites during the run, so
this does not say whether the application or the laptop was the limit.

**When to fix** Re-run on the first real host with nothing else on it. If the
dashboard is still over budget there, profile `DashboardService` first: it is the
most-called read and the slowest.

**Risk if left** Unknown until measured cleanly; the five-family alpha is far
below 20 concurrent requests.

---

## 43. A guest link has no page to open

**Resolved** (2026-09-14, continuity-ux). Kept as a stub so the number still
means something where it is cited.

`/share/<token>` (and a heir-mode helper's `/help/<token>`) now forwards to
`static/guest.html` (`sharing/GuestPage.kt`), permitted without a token in
`SecurityConfig`. The page holds no data, sends no Referer, stores nothing, and
reads the slice from `/api/v1/share/<token>` (or `…/tasks`) — a handbook, a tax
pack with its PDF and CSV links, or a list of records — read-only. The tax
screen's "Share with my CA" may now hand out the page link as well as the PDF
link; that choice was left to the tax screen. `ContinuityKitApiTest` asserts the
page answers `200`.

---

## 44. The CA pack cannot print Telugu or Devanagari

**Where** `backend/.../tax/TaxPackPdf.kt` (`Fonts.safe`).

**What** The PDF embeds Fraunces and Inter, which carry Latin and the rupee
sign but no Indic scripts. The same applies to the emergency kit and the
envelope edition's cover (`continuity/Printed.kt`, `PrintFonts`), and the
handbook pages behind it are still the standard PDF faces, which replace
anything outside WinAnsi. A holding or person named in Telugu or Hindi prints
with those characters replaced by `?`, the same trade the holdings PDF export
makes — better than a pack that fails to save.

**Which is right** Noto Serif/Sans Telugu and Devanagari embedded as fallback
faces, per docs/02, with complex-script shaping (PDFBox does not shape; this
needs a shaping step or pre-shaped glyph runs).

**Why it is still here** The Noto files are not in the repository, and shaping
is a change of its own.

**When to fix** When the first household with Indic-script holding names asks
for a tax pack, or with the design-system typography work.

**Risk if left** Unreadable names in a document sent to a CA; the figures, ISINs
and dates are unaffected.

---

## 45. AIS and Form 26AS reconciliation is not built

**Where** `docs/tax/capital-gains.md` §7; docs/01 §9 ("optional AIS/26AS
reconciliation later"); docs/10 story 2.3.3.

**What** Nothing compares the recorded sales, interest and dividends with the
Annual Information Statement or Form 26AS.

**Which is right** An import of the taxpayer's own downloaded AIS (JSON) or 26AS
(PDF/text), matched to recorded sales, interest and TDS, with each mismatch
shown as a line to look at — config-gated, no portal credentials ever stored.

**Why it is still here** It needs real taxpayer statements to build and test
against, and a decision on whether a downloaded AIS may be stored at all.

**When to fix** When a household offers a statement for testing and the storage
decision is made.

**Risk if left** A recorded sale that the department knows about but the
household forgot is not caught before filing.

---

## 46. The last known view is kept for Home and Holdings, not every tab

**Where** `static/app/cache.js` (the `KEEP` list), `screens/home.js`,
`screens/investments.js`; every other screen.

**What** X-38 asked that every tab show the last view instantly and refresh
quietly. Home (dashboard, trend, To review) and the holdings list do, and so do
a holding's value history and readiness reads. Owed, Accounts, Goals, Family
plan, Reports, Tax and Household still draw placeholders on each visit, because
each fetches several things its own way and needs the same small change
`homeScreen` has: draw from `api.peek(path)`, fetch, redraw only if different.

**Which is right** The same pattern on each screen, adding each read to `KEEP`
only after checking it carries no sealed value (the e2e, where-and-who and
account endpoints must never be on the list).

**Why it is still here** Scope: the two most-visited screens first, and the
others are being changed in parallel work.

**When to fix** The next time someone is in each of those screens.

**Risk if left** A second of grey on revisiting those tabs; nothing is wrong.

---

## 47. To review, readiness movement, the holding panel and "What Ravi sees" are web-only and English-only

**Where** `static/app/review.js`, `readiness.js`, `screens/detail.js`,
`screens/family.js`, `i18n.js`; the native `app/` tree; server sentences in
`review/ReviewInbox.kt` and `household/MemberPreview.kt`.

**What** The new strings (`review.*`, `ready.movement.*`, `ready.scoreIs`,
`ready.outOf100`, `detail.*`, `holding.ownedBy`, `privacy.pill.*`,
`family.role.*`, `family.relationship.*`, `family.preview.*`, `home.trend.*`,
`home.allocation.summary`, `block.updatedNow`, `block.notRefreshed`, and the
Home labels that were English literals) exist in English only; Telugu and Hindi
fall back to English. The server's `detail`, `summary`, `explanation` and
`caveats` sentences are English, as Doc 14 says server sentences are for now.
The phone app has none of these screens. Three limits are by design and worth
knowing: readiness movement needs a visit about a month earlier (no visit, no
row, no movement); the member preview shows ordinary sight only, never what an
emergency unlock would reveal; and a debt in the preview is labelled by its
kind code, not the translated kind.

**Why it is still here** Translations should be written by someone fluent, not
guessed, and the native app is being worked on elsewhere.

**When to fix** With the next Telugu and Hindi pass, and when the native Home and
Family screens are built.

**Risk if left** A Telugu or Hindi reader sees these cards in English.

---

## 48. A record kept for someone else can only be shared with the whole household

**Where** `static/app/state.js` (`startingVisibility`); V8
`app.can_grant_visibility`; docs/03 §1.3.

**What** When an adult child sets Almira up for a parent (X-32), what they add
is recorded as the parent's. Only a holder may share a record with named people
(V8), so the helper cannot make "Amma's necklace" visible to just themselves.
The web client therefore starts such a record at *Shared with the household*,
says so on the form, and Amma can make it private when she joins. In a
household with siblings, they see it too.

**Which is right** Undecided, and deliberately not decided in code. Either the
creator of a record for a managed member (no login) may grant sight to
themselves — a change to the grant rule and its SQL assertions — or the helper
path stays household-shared. It is a privacy-model decision for the owner.

**Why it is still here** Weakening a grant rule is not a side effect a UI change
should carry.

**When to fix** When the owner decides; the change is V8's function, its
assertions in `db/tests/rls_privacy_test.sql`, and `startingVisibility`.

**Risk if left** A helper's records for a parent are visible to every member of
the household until the parent joins and changes them.

---

## 49. The first session is in English only

**Where** `static/app/i18n.js`: every key under `check.*`, `onboard.*`,
`shelves.*`, `shelf.*`, `starter.*`, `goalStarter.*`, `welcome.*`, `draft.*`,
`glossary.*`, `guide.*`, `help.*`, `helper.*`, `capture.titleFor` and
`capture.visibility.helping`.

**What** The readiness check, the shelves, the welcome, the guide and the
glossary have English strings only; Telugu and Hindi fall back to English
(docs/14). The glossary paragraphs in particular need a translator who knows
the bank-form words in each language.

**When to fix** With the next translation pass; no code change.

**Risk if left** A Telugu or Hindi reader meets their first screens in English.

---

## 50. The welcome names the household owner, and opens the first household

**Where** `guidance/Welcome.kt`; `static/app/app.js` (`loadSession`).

**What** The welcome says "{owner} invited you", using the owner's name on the
roster, because an invitee cannot read the invitations table (its policies are
for admins) and the invitation's `invited_by` is not otherwise exposed. An admin
who sent the link is not named. Separately, the client always opens
`households[0]`: someone who already had a household and accepts an invitation
to a second one is welcomed to the first.

**Which is right** The inviter's own name, returned by `app.accept_invitation`
alongside the household, and the client switching to the household just joined.

**When to fix** The next time invitations are opened; the second half belongs
with multi-household switching.

**Risk if left** A slightly wrong name on one screen; a welcome to the wrong
household for the rare person in two.

---

## 51. An open emergency window reveals continuity records across the household, not only the subject's

**Where** `app.emergency_reveals` (V20, V25) and the `investments_read`,
`liabilities_read` and `estate_documents_read` policies that call it.

**What** Found while building heir mode. `emergency_reveals(household_id,
is_in_continuity)` is true for *any* continuity-marked record in the household
while the caller holds an open window on *anyone* there. A window on Amma
therefore also shows Nanna's private holdings that he marked for the family,
though nobody asked about Nanna and Nanna was not told. The V40 and V55 helpers
(`has_open_emergency_window`, `emergency_open_on_user`) were already narrowed to
one subject; this one never was. Heir mode's own tasks are built only from the
subject's records (`HeirModeService.sources`), and the emergency preview says
"marked for the family plan" in counts, but the ordinary endpoints and the
handbook show the wider set.

**Which is right** The reveal should require the record to belong to the
subject: an ownership (or holder, or `estate_documents.member_id`) match on the
subject of an open request, the way `emergency_open_on_user` matches the subject.

**Why it is still here** It changes what three read policies return, which is
a privacy-model change with its own review, and every emergency and memorial
test depends on it.

**When to fix** Before emergency access is used in a household with more than
one adult who marks private records for the family.

**Risk if left** A trusted contact sees another member's private continuity
records during a window that member was not told about.

---

## 52. Heir mode: what a helper cannot do, and what has no native screen

**Where** `continuity/HeirMode.kt`, `static/app/screens/heir.js`,
`static/app/guest.js`; `app/` has none of it.

**What**
- A helper's page is read-only. Only the person holding the window marks a task
  done; a helper tells them. A write from an unauthenticated guest session would
  be the first of its kind (V21), and deserves its own design.
- Almira does not send a helper their link. It is shown once, with the device's
  share sheet or a copy button, because nothing here can message someone who is
  not a member.
- The situation ("has died" or "can't manage") is chosen once; the API accepts a
  change, and the screen does not offer one yet.
- The heir screen hides the app's notices by being a separate shell. Reminders
  and "Still true?" messages that the delivery sweep sends to the *heir* are not
  paused while a plan is open; the sweep already skips birthdays and death
  anniversaries of anyone in the household (V60).
- None of heir mode, guided flows, the lost-money sweep, the timeline or the
  printed pages has a native screen (see entry 27 for the lifecycle flows).

**Which is right** A helper page that can say "done" through a narrow definer
function; a quiet period for the heir's own reminders while a plan is open; the
native screens from Doc 03 §8.

**When to fix** After the first real use of heir mode says which of these hurt.

**Risk if left** A little more coordination by phone between relatives.

---

## 53. The continuity screens added with heir mode are English in Telugu and Hindi

**Where** `static/app/i18n.js`: every `guided.*`, `heir.*`, `lostMoney.*`,
`guest.*`, `kit.*`, `envelope.*`, `print.*`, `steps.*`, `estate.q.*`,
`estate.kind.*` key, and the `emergency.*` keys added with the timeline.

**What** They exist in English only and fall back to English, as `t()` does.
The server's task words, portal descriptions, timeline sentences and printed
pages are English too, like every server sentence (docs/14).

**Which is right** Reviewed Telugu and Hindi, by a speaker — these are the words
someone reads in the week after a death.

**When to fix** With the next translation pass.

**Risk if left** A Telugu or Hindi reader meets English on the screens where
plain words matter most.

---

## 54. Continuity signals have a web client and no native screens

**Where** `backend/src/main/resources/static/app/continuity-signals.js`,
`where.js`; docs/27 §8. Nothing under `app/`.

**What** "If you go quiet", still-reachable ticks, "Do you know where it is?",
the backup key holders and term cover against expenses are built on the server
and the web client only. The native app neither shows the cards nor opens
`#/here/<token>` links, and its sealed-field screen does not know
`key_holder_2` or `key_holder_3` by name.

**Which is right** The same cards in the native app, reading the same endpoints;
a one-tap link opening in the browser is fine as it is, since it needs no
sign-in.

**Why it is still here** The `app/` tree is being worked on elsewhere (as for
issues 24 and 27).

**When to fix** With the native Family plan screen.

**Risk if left** A phone-only owner cannot turn the trigger on, answer a
question or confirm they are reachable without the web client. A backup key
holder sealed on the web shows on the phone as "a sealed note".

---

## 55. The continuity-signal strings are English in Telugu and Hindi

**Where** `static/app/i18n.js`: `quiet.*`, `reach.*`, `ask.*`, `protect.*`,
`chain.*`, `here.*`, `ready.asked*`. The server sentences in docs/27 (the
check-in and reachability messages, the protection headline and caveats) are
English by the rule in docs/14.

**What** They fall back to English for a Telugu or Hindi reader.

**Which is right** Reviewed translations. The words about going quiet and
emergency access are the ones that most need a native speaker: "never sounds
alarming" does not survive a literal translation.

**Why it is still here** No reviewer was available, and a machine draft of these
particular sentences could frighten the person reading it.

**When to fix** With the next translation review (docs/14).

**Risk if left** A mixed-language card on For my family.

---

## 56. Plans have no price, no payment gateway, and no warning before they end

**Where** `plans/` (`PlanProperties`, `HouseholdPlanService`,
`PlanReadOnlyGuard`), `scripts/household-plan.sh`, [Doc 28](28-plans-and-support.md) §1–§3.

**What** The shape of a plan is built — config-driven definitions priced per
household, a `household_plans` row, the grace period, and a lapsed household
becoming read-only (never locked). What is not: a price (every screen says none
is decided), any payment gateway (`paymentsEnabled: false`; the only way a plan
changes is an operator running the script as the schema owner), and any message
to the family while a plan is in `grace`.

**Which is right** A price the owner decides, a gateway that sets the same
`paid_through` the script sets, and a reminder at the start of the grace period
and a week before read-only, through the existing notification outbox.

**Why it is still here** Price and gateway are owner decisions and need a
merchant account; building a gateway integration without one would be a fake.
The reminder waits for there being something to renew.

**When to fix** When a price and a gateway are chosen.

**Risk if left** None while no household has a plan row, which is every
household: nothing ends. Once an operator sets a date, a family reaches
read-only without being told first — they still keep every read, the handbook,
Download everything and closing their account.

---

## 57. The native app has no plan, read-only notice or support code screens

**Where** `app/shared` (the Compose app).

**What** The web client shows the plan under Settings, a quiet notice while a
household is read-only, and *Get help* for support codes. The native app shows
none of these. A write refused with `403 plan_read_only` still reaches the
person, as the server's own sentence, wherever the app already shows an error's
message.

**Which is right** The same three on native, from the same endpoints.

**Why it is still here** This change was not allowed to touch `app/`.

**When to fix** The next pass over Settings in the native app.

**Risk if left** Low: nothing is locked, and the refusal explains itself.

---

## 58. A consolidated account statement is opened on the device, but no registrar's layout is parsed

**Where** `backend/src/main/resources/static/app/statement.js` (`suggestRows`),
`screens/statement-import.js`; P-11.

**What** Import a statement (CAS) decrypts the PDF with its password in the
browser, extracts every line, and suggests a row for each line that names a
folio — likely name from the line above, value from a number labelled "value"
or else the last on the line, person from an "Investor:" or "Name:" line above.
Those rules are generic. They are **not** a reader for the CAMS or KFintech
CAS or the NSDL or CDSL eCAS: nobody on the project has confirmed those layouts
from an authoritative sample or the registrars' own documentation, and the
fixture (`scripts/browser-checks/fixtures/statement-synthetic-aes256.pdf`,
`StatementFixtureTest`) is invented. A real statement may yield no suggestions,
suggestions with the wrong value (units, NAV or cost instead of market value),
or rows for lines that are not holdings. The review screen says the
suggestions are unconfirmed, shows the words each came from, and lets every
row be fixed, dropped or added from any line.

**Which is right** A reader per format, each written against a statement the
project may keep as a fixture (or one built from a registrar's published
specification), recognising the scheme, ISIN, folio, closing units, NAV, cost
and market value per holder, with the generic path kept as the fallback.

**Why it is still here** It needs real statements, or permission to derive
fixtures from them, and a human decision on keeping such a file in the
repository. Parsing: **partial**.

**When to fix** When a household offers a statement (with its details
replaced) or a registrar's specification is to hand.

**Risk if left** More typing and checking than the feature promises. Nothing
wrong is saved without the person having seen it beside its source words.

---

## 59. Reading a photo on the device has been checked on a synthetic image in one browser

**Where** `app/ocr.js`, `screens/photo-reader.js`,
`scripts/browser-checks/on-device.html`; P-13.

**What** tesseract.js 7 with the English integer model reads a clean, drawn
image of a policy bond in about a second on a desktop Chromium (confidence 95),
and the whole path — read, send the words with the photo, chips with their
patches of the photo, the capture form filled — was run against a local
server. Not yet checked: real photographs (angle, glare, folds, low light); a
phone, where the engine and model are held while the sheet is open and released
when it closes, and how much memory that needs was not measured; Safari and
Firefox; and Telugu or Hindi text, for which no model is shipped. A PDF that is a scan with no text layer is still
not read on the device; only images are.

**Which is right** A pass on two or three mid-range Android phones and an
iPhone with real bonds, FD receipts and passbooks, and a decision on shipping
`tel` and `hin` models (about 2–4 MB each, loaded only when chosen). Rendering
a scanned PDF's pages with pdf.js and reading them the same way is a small
addition once the phone results are known.

**Why it is still here** It needs devices and real paper, which the machine it
was built on does not have.

**When to fix** Before the capture screen is promoted as reading photos.

**Risk if left** Poor readings on real photos. Every field is a suggestion shown
beside its source and nothing saves until the form does.

---

## 60. The offline copy of the handbook is not protected from someone who can run the browser profile

**Where** `app/offline-store.js`; docs/16 §2 "The offline copy"; P-21.

**What** The copy is encrypted under a non-extractable WebCrypto key kept in a
separate IndexedDB database, and deleted on sign-out and after 30 days. A
non-extractable key cannot be read by script, but the browser stores it in the
same profile, so someone who copies the whole profile while a copy exists, or
who uses the unlocked browser, can read it. A session revoked from elsewhere
leaves the copy readable offline until this device next reaches the server (or
30 days pass). Verified in desktop Chromium through
`scripts/browser-checks/on-device.html`; the offline launch through the service
worker on a real phone is not yet verified (Doc 17 §7 already notes the same for
the shell).

**Which is right** Optionally wrap the key with a secret the person supplies at
reading time — a WebAuthn PRF from the device's own unlock where supported, a
short passphrase otherwise — so a copied profile is not enough. It is a trade
against being able to read the handbook in a hurry, and is a product decision.

**Why it is still here** The decision above, and WebAuthn PRF support on the
phones this is for.

**When to fix** With that decision, or if the setting is ever offered by default.

**Risk if left** On a device with no disk encryption or screen lock, the
handbook's names, references and values are as exposed as the signed-in
session already is.

---

## 61. A private holding for someone else cannot be given a first value by the person who creates it

**Where** `investment/InvestmentService.kt` (`create`, the `initialValuation`
insert after the row); found through `importing/ImportService.kt`.

**What** Creating a holding as **private** and owned by another member (for
example a parent recording a managed child's fund), with `initialValuation`,
fails with `403 forbidden`: the holding row is written, and the valuation insert
that follows is refused by row-level security because the creator cannot see
the private row they just made. The whole create rolls back. `POST
/investments` has this today. The statement and spreadsheet import now retry
such a row without its value and say "Saved without its value"
(`ImportApiTest`).

**Which is right** Either the create path writes the first valuation in the
same statement as the row (so it is covered by the row's insert policy), or the
valuations insert policy admits a record's creator for the transaction that
created it. Either keeps the 404-not-403 rule for everyone else.

**Why it is still here** It is a change to the investments write path and its
RLS policies, which belongs with that module rather than an import.

**When to fix** The next change to `InvestmentService.create` or the valuations
policies.

**Risk if left** A private holding recorded for someone else has no value until
its owner adds one; totals for them under-count it.

---

## 62. A paper's type is whatever its uploader said, and papers are served from the app's origin

**Where** `document/DocumentController.kt` (`upload`, `DocumentDownloadController`),
`document/DocumentService.kt` (`upload`).

**What changed** Redeeming a download ticket used to answer with the stored
type and `Content-Disposition: inline`. A household member could attach an SVG
or an HTML file (the type comes from the multipart part, so a hand-built request
can say `text/html`), and when someone else opened it from Papers its script ran
on the app's origin and could read `localStorage["almira.refresh"]`. Now only
`application/pdf`, `image/png`, `image/jpeg` and `image/webp` are shown in the
browser, under their own type. Everything else, SVG and HTML included, is sent
as `attachment` with `application/octet-stream`. Every download except a PDF also
carries `Content-Security-Policy: sandbox; default-src 'none'`. PDFs do not,
because browsers will not open their PDF viewer in a sandboxed document
(`DocumentApiTest`, "a paper that could run script…"). The `mimeType` in the
document list is unchanged.

**What is still open** Uploads are not checked against an allowlist or
re-sniffed, so a paper can still be stored with a misleading type. Documents
are still served from the same origin as `/app`.

**Which is right** Probably both: refuse (or relabel as octet-stream) types
outside the allowlist on upload, and serve downloads from a separate host.
Refusing on upload is a product decision, because it would turn away papers
that some members already attach (Word files, spreadsheets). A separate host is
a deployment change.

**When to fix** The next change to document upload, or when the deployment
gains a second hostname.

**Risk if left** Low while the download is served as an attachment. A future
change that serves the stored type inline again would reopen the hole.

---

## 63. A key-holder ask about someone else's record reaches only people who already see it

**Where** `continuity/KeyHolderAsks.kt` (`KeyHolderAskService.ask`), the
`key_holder_asks_insert` policy and `app.holds_askable_record` (V106).

**What changed** The ask used to check only that the *asker* could see the
record. So an advisor with a grant, a member a record was scoped to, or whoever
held an open emergency window could ask about a record they did not hold, and
the record's title went to a member the owner never shared it with: it was
stored in `key_holder_asks.thing`, sent in the notification, and readable by
that member. Now the asker must own, owe, hold, file or upload the record, or
the person asked must already see it by ordinary sight (`app.member_would_see`).
Otherwise the ask is `404`, in the service and in the policy
(`KeyHolderAskApiTest`, "someone a record is shared with cannot carry its title…").
Asking about your own record works as before.

**What is still open** `app.member_would_see` (V81) answers only for
investments and liabilities. For an account, a paper or an estate document that
the asker does not hold, it always says no. So those can be asked about only by
their holder, even when the person asked could already see them.

**Which is right** Extend `app.member_would_see` to the other three record kinds,
mirroring their read policies.

**When to fix** The next change to V81's function or to the family preview.

**Risk if left** None for privacy. A trusted contact working through a window
cannot ask about the subject's accounts or papers, even when the person they
would ask can already see them.

---

## 64. "The phone number on your account was changed" was texted only to the new number

**Resolved** (2026-09-15). Kept so the number means something where it is cited.

**Where** `auth/AuthService.kt` (`verifyPhoneChange`), `auth/AccountNotices.kt`
(`phoneChanged`), `provider/Delivery.kt` (`RecordingNotifier`),
`provider/NotificationOutbox.kt`, and `app.enqueue_outbound_message` (V108).

**What changed** The outbox worker finds an SMS recipient when it sends (entry
13): the account's phone. After a change that is already the new number, so the
only text about the change went to the handset that had just proved it — in a
takeover, the attacker's — and the number the owner still holds heard nothing.
Now the notice is also queued as one more SMS whose address is fixed when it is
queued: the number the account changed from. The address waits beside the body
in `outbound_message_bodies` (unreadable to the runtime role) and is deleted
with it once the message is finished. Only `auth.phone_changed` on `sms` may
carry an address; the function refuses anything else. Push, email, the in-app
row and the text to the new number are unchanged. Proven by
`PhoneChangeApiTest` ("the old number is texted that the number changed…").

**What is still open** A phone-only account steps up with a code to its current
number, so where the attacker already receives that number's texts (a SIM swap)
the extra text reaches them too. It helps when step-up was done another way (an
authenticator, a recovery code, an already elevated stolen session).

---

## 65. A household can be left with no owner when its successor or admin is gone by purge time

**Resolved** (2026-09-15). Kept so the number means something where it is cited.

**Where** `lifecycle/AccountPurge.kt` (`purge`), `lifecycle/DepartureCompletion.kt`
(`complete`), `lifecycle/Dormancy.kt`, `lifecycle/Memorials.kt`, and
`household_dormancies`, `app.going_leaves_household_ownerless`,
`app.accept_household_ownership` (V120).

**What changed** The owner decided (2026-09-15): never purge the last owner
while the household holds records; move it to dormant, tell the remaining
members, and require an explicit transfer before any purge. Before anything is
carried out, the purge and the departure sweep now ask whether the person is the
last owner able to act (another owner who is memorialised does not count) of a
household that still has other members and holds records. If so, that household
is made dormant and left exactly as it was — the membership, what the person
holds there, and for a closure the account itself — and the closure or departure
stays pending. A departure is stopped before step one, so no household of the
leaver's own is made and no document bytes are copied. The rest of a closure is
carried out (households nobody else signs in to are erased; the person leaves
the others). A memorial on the last owner makes the household dormant too, by a
trigger. While dormant, `can_administer_household` and `is_household_owner`
answer false for everyone, so nothing that needs an owner or admin can be done;
the API says `household_dormant` in words; reads, member writes, the handbook
and Download everything are unchanged. It ends when an adult member with a login
(admin, editor or viewer; not an advisor, a restricted member, a minor,
someone memorialised, or someone closing their account or leaving) accepts it
with a step-up — a week after a memorial, at once otherwise — or when the owner
comes back (Keep my account, cancelling the departure, or "I'm here"). Only
then does the next sweep carry out the rest. The remaining members are told in
the app and through the outbox (`lifecycle.household.*`, essential). A household
with nobody eligible stays dormant and is never erased for that. Proven by
`DormantHouseholdApiTest` and the "dormant household" block of
`db/tests/rls_privacy_test.sql`.

**Revised** (2026-09-15, the owner's answers to the two entries this left open,
both now resolved): a closure is no longer held by the household — the person is
erased on the day and what they shared stays under a former member (V136); the
named successor is asked first, for a window, before anyone else (V135); a
dormant household freezes membership only (V135); and a household with nobody
eligible has an operator repair (V137). docs/05 §12.7 is the current statement.

**What is still open** See "Only a closure is carried out while a household is
dormant, and only there does what was shared survive an erasure", "An operator
repair records where the evidence is, and nothing checks it", and "Marking
someone as passed away through the admin door, and a date of birth, are frozen
with membership".

---

## 66. Erased documents' bytes stayed in storage for good if the delete after the purge failed

**Resolved** (2026-09-15). Kept so the number means something where it is cited.

**Where** `lifecycle/AccountPurge.kt` (`purge`), `lifecycle/DepartureCompletion.kt`
(`complete`), `lifecycle/LifecycleSweep.kt` (`run`),
`lifecycle/PendingStorageDeletions.kt`, and `pending_storage_deletions` (V109).

**What changed** A purge, and a departure that erases or moves documents,
deletes the document rows in its transaction and the stored bytes only after it
commits. The storage keys were held only in memory and each delete was
best-effort, logged by exception class alone. A storage outage at that moment
left the bytes in the bucket permanently, with nothing naming them; in a
household that carries on, the household's data key still exists, so the
"erased" paper stayed readable to anyone holding the key-encryption key. Now
the keys are written to `pending_storage_deletions` in the same transaction.
Each is deleted after commit as before, and a key leaves the table only once
storage has confirmed the delete; one that fails keeps its row (with an attempt
count) and every lifecycle sweep tries it again first. A key a `documents` row
names again is dropped from the table without deleting the bytes. The table is
the owner connection's alone: row-level security on, no policy, nothing granted
to the runtime role. Proven by `AccountClosureApiTest` ("bytes storage fails to
delete after the purge stay queued and the next sweep deletes them").

**What is still open** Bytes that were orphaned this way before V109 are not
found: nothing recorded their keys, and finding them means listing the bucket
against `documents`. The upload rollback in `DocumentService` and the removal of
new copies when a departure's second step fails remain best-effort.

---

## 67. A queued offline save the server refused was deleted, so what was typed was lost

**Resolved** (2026-09-15). Kept so the number means something where it is cited.

**Where** `app/drafts.js` (`queue`, `flush`), `app/draft-ui.js` (`queueCapture`,
`flushQueued`), `app/screens/capture.js` (`submit`); X-83.

**What changed** A capture saved without a network is queued and its form draft
discarded, so the queued item was the only copy of what was typed. When the
network came back and the server refused it (a 400 for a value it will not take,
a 404 for a household since left, or any other answer that is not "offline" or
`already_exists`), `flush` removed it from the outbox and the person got only a
toast asking them to add it again. Now a queued capture records its form
(`capture:<type code>`), and a refused one is written back as that form's draft
through the same rules as any draft (identifiers and sealed fields are still
never kept) before it leaves the outbox. A field typed into that form since is
not overwritten. The toast says the record is back as a draft on Home, and the
resume card reopens it. Proven by `scripts/check-drafts.js` ("a refused save
comes back as the draft of its form").

**What is still open** Saves queued before this change carry no form and still
get the old "add it again" toast. `flushQueued` still treats a 5xx as a refusal
rather than "try again later" (`api.js isUnreachable` counts 5xx as unreachable;
`draft-ui.js isOffline` does not); such a save is now kept as a draft instead of
being lost, but it is not resent on its own. Custom field definitions and the
scoped member list are not part of a draft and are chosen again.

---

## 68. A guard runs before the action it guards

**The rule** (owner, 2026-09-14): a check that refuses something runs *before*
the action it exists to prevent — not after it, relying on a rollback, a
clean-up or a message to undo it. And its test proves **the action did not
happen** when the check refuses, not merely that the check refused; it is
watched failing with the check moved back after the action.

Why it is a rule and not a fix: it was broken twice before anyone swept for it.
6b's teardown enforced its limit after the fact, and a test checked its
database URL only after Flyway had migrated the wrong database
(TestDatabaseGuard, `41aa94d`). A sweep then found the class elsewhere. A
rollback undoes rows; it does not undo a migration already committed, a data
key cached in memory, a file in storage, a provider call, a message queued, or
a process already serving.

**Moved in front** (each commit names its watched-failing run):

| Guard | Used to run after | Now | Commit |
|---|---|---|---|
| Sign-in channels, key-encryption key, JWT secret, OTP and provider bounds | Flyway migrate (and, in development, writing a new key file) | `StartupSettingsCheck`, an EnvironmentPostProcessor, before any context | `c377840` |
| Per-network OTP request cap | the per-number counter's INCR | network cap first | `4e69e8a` |
| Write permission on document upload (and capture, DigiLocker import) | provisioning/caching a data key and `storage.put` | `HouseholdService.requireWriter` (app.can_write_household) first | `9bc6396` |
| A cached data key matches the stored one | encrypting under it | checked on every use | `a9a71ca` |
| Creator can see the account/loan; holder, owner and role validation on edits; a holding's values before its custom fields; a contact's link targets; a share's scope; write permission before encrypting an account number | the inserts/updates, audit and read-back ("Saved, but…") | before any write | `0fb4fd5` |
| Admin check for DigiLocker complete and AA consent; writer check for both imports | redeeming the code, creating the consent, list/fetch | before the provider call | `d6dca3f` |
| Removing a stored document file | only an insert failure | any rollback (afterCompletion) | `a274bd9` |
| Download ticket single use | GET, then an unchecked DEL | GETDEL | `9bdf10b` |
| Guest link expiry, revocation, view limit | a stale read; the count was unconditional | the count carries the check (V36) | `3dcc6d1` |
| Reminder "notified" claim | queueing the notifications, unconditional | conditional claim first, released if queueing throws | `5a52174` |
| Test database is not `almira` | (the check was in front, but `?sslmode=` slipped past it) | reads the database name | `ac118c1` |
| up.sh runtime role does not bypass RLS | the app starting and serving | SQL check before `up -d app` | `784aa65` |
| uitest bridge port is free | starting the bridge | `claim-port.sh` before it | `3bb4ace` |
| verify.sh scratch services are its own | the backend suite | preflight, and the suite gated on them | `57df7e6` |
| The application's runtime role cannot bypass RLS (superuser, BYPASSRLS, CREATEROLE, owns an RLS table, or can SET ROLE to such a role) | the application migrating and serving; only `/health` reported it | `RuntimeRoleCheck` before Flyway migrates, and again before serving; every environment | `5d06681` |
| bootstrap-db.sql: the runtime role is not the owner, and cannot bypass RLS | its ALTER ROLE (which set the owner's password); "start the application, then check" | before the ALTER ROLE; the made role checked before "Done" | `5d06681` |
| Nothing forced a bean refusal ahead of Flyway | — (each had to remember) | `StartupRefusal`: the Flyway bean's parameter; `StartupRefusalOrderTest` fails on one outside it | `b5d7b72` |
| Key-encryption key opens this database | Flyway migrate (it injected Flyway) | a `StartupRefusal`, before migrate | `b5d7b72` |
| S3 bucket reachable; privacy, plans, support and continuity properties | Flyway migrate (built when first injected) | a `StartupRefusal`; bound in `StartupSettingsCheck` | `b5d7b72` |
| backup.sh documents volume exists; bodies table is the one excluded | `pg_dump` writing the dump | before the backup directory is created | `850d8e3` |
| restore.sh documents volume is empty | the runtime role and `pg_restore` | step 2, with the other target checks | `7700220` |
| restore-row.sh backup copy intact (sweep rules, digest); live row exists | the UPDATE over the live row | before the UPDATE, in the same transaction | `7700220` |
| backup.sh: documents in S3 acknowledged (`ALMIRA_BACKUP_DOCUMENTS=external`), and not claimed for the filesystem | — (new) | before the database is asked whether it is up, let alone dumped | `014e2e9` |
| restore.sh, restore-drill-local.sh --from, drill.py verify: the same acknowledgement for a backup whose documents are external | — (new) | step 0, before `compose up`, a container or a request | `014e2e9` |
| freeze-api-spec.sh fetched spec is complete | emptying the frozen file | into a temporary file, renamed over it only when valid | `713b64e` |
| smoke-prod.sh deployment checks (up, database, rlsEnforced, not owner) | requesting codes, signing in, creating a household | stops before the first write | `6f6847d` |
| A sign-in email's address is on the allowlist | a provider call (to the decoy sink) for every unlisted address | `SignInEmailOutbox` asks the list before stamping the send, deriving a code or calling a provider | `03c9508` |
| No email, SMS or push without consent to messages | (absence of consent counted as a yes, so the row was written) | `app.enqueue_outbound_message` asks `app.messages_consent_given` before the insert; `MessagesConsentTest` looks for the row and the provider call (V125) | `25dc628` |
| Only the named successor may take a dormant household on during their window | — (new) | `app.accept_household_ownership` refuses others before the role change and the audit line (V135); watched failing with the check moved after the update — Ravi's direct call went through (`DormancyOrderApiTest`) | ws/dormant-ordered |
| An operator repair has a request, its wait, its before-notice, a dormant household and an eligible member | — (new) | `ops.carry_out_dormancy_repair` decides the outcome, and audits it, before the role change (V137); watched failing with the role change moved in front — the advisor became owner during the wait (`DormancyRepairTest`) | ws/dormant-ordered |
| dormancy-repair.sh is enabled for this environment | — (new) | before psql is called; watched failing with the gate moved to the end (`scripts/tests/dormancy-repair-refuses-unless-enabled.sh`) | ws/dormant-ordered |
| A closure's household is made dormant before the person's rows in it are touched | — (new) | `AccountPurge` opens the dormancy first; watched failing with it moved after the erasure — the successor was gone and nobody was asked first (`DormancyOrderApiTest`) | ws/dormant-ordered |
| Write permission on deleting a record, changing its visibility, replacing its nominees or adding a valuation | nothing: row-level security filtered the refused UPDATE/DELETE to zero rows and the service never looked, so a viewer got 204 and 200 over records that had not moved | each service asks `app.can_modify_*` (and, for documents, the rule `documents_delete` spells out) before the write; watched failing on master, where each case reports success over a record still exactly as it was (`RefusedWritesDoNotReportSuccessApiTest`) | `fbb6f00` |

---
| A guest link's view limit is at least one | — (new); `maxViews: 0` and below were accepted, and every open of the link answered 404 | `ShareService.create` refuses beside the expiry check, before a token is minted or a row written; watched failing with the check removed — a zero-view link was created 201 (`ShareApiTest`) | worktree-agent-a2fa0fd8eeb7d75f4 |
| Guest-link open rate limits, per link and per network | — (new); the endpoint had none, though docs/05 §7 called it rate limited | `ShareService.admit` spends both caps before `resolve_guest_share`, the view count, the audit row and the payload; watched failing with the call removed — 429 never came, and a refused open is proven not to be a view (`GuestShareRateLimitApiTest`) | worktree-agent-a2fa0fd8eeb7d75f4 |

The scripts' tests are in `scripts/tests/` and run the real scripts against
throwaway containers and volumes, or with a stub `curl`; each was watched failing
against the script as it was before its commit.

**Decided, no change** (owner, 2026-09-15): the runtime-role refusal keeps
CREATEROLE, although PostgreSQL 16 narrowed what CREATEROLE allows. Recorded in
docs/16 AC-2 and docs/17 §3.

**Still open:**

- **A refusal and the write it refuses inside one request transaction cannot be
  told apart by order.** Moving the membership freeze in `InvitationService`
  after the insert, or the step-up after `app.accept_household_ownership`,
  left `DormantHouseholdApiTest` passing: the refusal rolls the write back, and
  nothing the write did is visible outside the transaction. The owner accepted
  that for the transfer (D-proof, 2026-09-15); the tests instead prove the whole
  request fails atomically (every row of the household's and the caller's,
  table by table) and were watched failing with the refusal removed. The
  invitation freeze is also in the database for accepting; creating an
  invitation has no row-level security (see "Invitations have no row-level
  security").
- **`StartupRefusalOrderTest` finds a refusal by its words.** It fails on a
  class that says "Refusing to start" outside the two mechanisms, and on a
  properties class that refuses in an init block without being bound early. A
  refusal worded some other way, in an ordinary bean, is found only if it is a
  `StartupRefusal` — which is what review has to ask.
- **The key-encryption key check reads `encryption_keys` before migrating.** If
  a future migration changed that table's `kek_id` or `wrapped_dek` columns,
  the check would read the old shape; nothing pending does.
- **A setting supplied by `@DynamicPropertySource` is invisible to
  `StartupSettingsCheck`** — it arrives after the check. The beans still check
  it. Tests that switch email on must supply the allowlist the same way
  (`OtpCodeNeverLeaksTest`).
- **The reminder sweep is still one transaction over two pools.** The claim is
  in front and released on a throw, but a throw on reminder N still rolls back
  the queued rows of reminders 1…N−1, whose claims have committed. Not a check
  after an action, so not moved here.
- **A guest view is counted before its payload is built**, so a payload that
  fails still uses a view. Deliberate in the code ("accounting first"); a
  design decision, not reordered here.
- **verify.sh's preflight port check was not watched failing** (the harness
  covers the suite gate), and verify.sh still `docker rm -f`s its own two
  containers by name at the start of a run, which its header says it never
  does.

---

## 69. Backups carried queued message bodies that docs/13 said they left out

**Resolved** (2026-09-15, `850d8e3`). Kept so the number means something where it is cited.

**Where** `scripts/backup.sh`, docs/13 "After a restore: `body_not_restored`",
docs/17 §6.

**What was wrong** docs/13 and docs/16 (DP-4) said a backup leaves out the rows
of `outbound_message_bodies`, and the worker was built for that — a restored
queued message fails once as `body_not_restored`. `backup.sh` dumped the table
in full, so a backup taken while a one-time code or reminder was waiting held
its rendered text in plaintext.

**What changed** The dump uses `--exclude-table-data=public.outbound_message_bodies`
(the table is kept, empty) and the manifest lists it under
`excluded_table_data`. Because that option matches by name and says nothing
when it matches no table, `backup.sh` refuses, before dumping, if a bodies table
exists under any other name. `scripts/tests/backup-checks-before-dumping.sh`
proves the dump has the table, none of its rows and no body text.

**What is still open** Backups taken before this commit contain the bodies of
whatever was queued when they were taken; they age out with the retention
period. Documents in S3 are still not in a backup, but a backup now refuses to
be taken without saying so (entry 41, resolved). The sign-in email queue's
bodies are dumped in full ("Backups carry queued sign-in email addresses").

---

## 70. Sign-in emails go through an outbox, and an unlisted address's is dropped there

**Resolved** (2026-09-15, "Decoy outbox"). Owner's decision: *don't ship the
decoy sink; equalise timing without paying for probes; always enqueue on the
request path, whatever the address, and let the worker drop unlisted ones. A
narrow, documented leak among five known testers is acceptable; a stranger
spending money by typing addresses is not.*

What was there: an unlisted address got a decoy challenge and a live provider
send to `ALMIRA_ALPHA_EMAIL_DECOY_SINK`, so its delivery status followed the
provider as a listed address's did. Every probe was a billed send, and the
server refused to start on a live provider without the sink.

What there is now (docs/13 §5):

- The setting, its startup refusal, `DEFAULT_EMAIL_DECOY_SINK`, the decoy's
  provider call, the `.env.production.example` block and the compose
  pass-through are removed (`SignInChannelsTest` fails if any comes back).
- Every email sign-in request, listed or not, does the same work and queues one
  row through `app.enqueue_sign_in_code_email` (V110). Nothing on the request
  path calls a provider (`EmailOtpTest`).
- The worker (`SignInEmailOutbox`) asks the allowlist when it reaches the row:
  listed, one send; not listed, `dropped` with no provider call, body deleted
  in the statement that records it (`SignInEmailOutboxTest`, watched failing
  with the check moved after the send: both the unlisted and the removed-tester
  tests saw a provider call).
- The queue holds no code: the worker derives it (`QueuedEmailCodes`).
- The delivery status says `sending`, then `sent` at a moment fixed with the
  request, for everyone. The "We couldn't send the code" notice no longer
  appears for sign-in; failures are logged and recorded for the operator.
- Every code step shows "Didn't arrive in two minutes? Contact us" (web: en,
  te and hi drafts), linked from the unauthenticated `GET /auth/otp/contact`.
- `OtpCodeNeverLeaksTest` also looks for the code a dropped message would have
  carried, in logs, responses, headers and both queue tables.

**Measured** (`scripts/measure-sign-in-timing.py`, method and table in docs/13
§5). Request latency over 1,000 alternating pairs: median 5.79 ms listed,
5.84 ms unlisted; p95 8.92 / 8.68; p99 11.75 / 12.52; Mann-Whitney p 0.18, KS
p 0.31. The status's first `sent` over 500 pairs: median 1205.19 / 1205.23 ms
after the request, Mann-Whitney p 0.63, KS p 0.40. Status, body length, body,
headers, both status shapes, the cooldown `429`, stale, wrong-code, lock and
unknown-id answers identical throughout. No residual difference found.

**Costs, stated plainly.**

- A tester whose email fails — refused, provider down, out of credit — is shown
  `sent` and is not given resend early: the cooldown and the hourly count
  stand, as they do for an unlisted address. Before, an outage lifted both.
  They have the "Contact us" line; the operator has the log lines and the
  `failed` records.
- A resend replaces the earlier emailed code at once, even if the resend's
  email then fails; before, an outright failure put the earlier code back.
- An address added to the allowlist (a restart) while its request's message
  waits is sent a code that does not match: its challenge was stored unlisted.
  Asking again works.
- The worker's decision is visible in the database (`sign_in_code_emails.status`,
  `sent` against `dropped`) to anyone with the owner connection. Not to the
  runtime role or any client. **Kept, 30 days** (owner's decision, 2026-09-15:
  no change): the owner connection already holds the allowlist, and the records
  are what tell a dropped send from a provider failure when a tester says the
  code never came (docs/13 §5).

**The operator is not left in the dark** (owner's decision, 2026-09-15: *a stuck
tester is fine seeing "sent"; nobody noticing is not*; `1039412`, `22d0060`).
Every non-sent end of a listed address's message — provider failure or refusal,
unconfirmed, expired, dropped because email sign-in was switched off — raises an
operator alert: an ERROR line `SIGN-IN EMAIL NOT DELIVERED` with no address or
code, `operator_alert` on the record (V130), and a count on `/health` behind
`ALMIRA_OPS_HEALTH_TOKEN` that `check-health.sh --ops-token-file` fails on. An
unlisted address and a tester removed while queued raise nothing. Nothing a
client can reach changes (`SignInEmailOutboxTest`; phase D of the timing
harness). What is left is entry "The operator alert for an undelivered sign-in
email is only as loud as what watches it".

---

## 71. The native sign-in code step has no "Didn't arrive in two minutes? Contact us" line

The web client's email code step shows, for every address, "Didn't arrive in
two minutes? Contact us", linked to the deployment's support channel from
`GET /api/v1/auth/otp/contact` (entry "Sign-in emails go through an outbox, and
an unlisted address's is dropped there"). The native app's email code step
(`app/`, another session's tree) does not. It still polls the delivery status,
which for sign-in now only ever says `sending` then `sent`, so its "We couldn't
send the code" branch is unreachable against this server rather than wrong.

**Risk if left** A native tester whose code never comes has the spam-folder
line and no route to the operator on that screen.

**Fix** Add the line under the code step in the native client, reading
`GET /api/v1/auth/otp/contact`, following only `https://wa.me/…` and `mailto:`
links (as `signInContactLink` in `static/app/auth-outcome.js`), and the same
words without a link when `configured` is false; strings through the native
catalogue, English first.

---

## 72. Consent to messages is asked for on the web only, and some reminders are never asked about

**Where** `static/app/message-consent.js` (the ask), `screens/capture.js`,
`screens/liabilities.js` and `review.js` (where it is asked), V125, V141, V142,
docs/23 "Asked when it helps".

**What** Since V125 nobody gets a reminder outside the app without a yes
("Consent to messages is assumed until someone withdraws it", resolved). What
is not finished is *asking*:

- **The native app never asks.** It has no Your data rights screen and no
  prompt, so someone who only uses the phone app gets in-app reminders and
  nothing else until they say yes on the web. Nothing is sent wrongly; the
  phone app just cannot say yes yet. Nor can it pass a context, so a native
  "Not now", when there is one, should send `{contextType, contextId}` (V141).
- **Not every reminder is asked about in context.** The web asks after adding a
  holding with a maturity, premium, renewal or SIP date, a loan with an EMI
  day, and on Home before the first Still true? digest. It does not ask after
  editing an existing record to add a date, after a CSV or statement import, or
  after a queued offline save goes through. Those people are asked the next
  time they add something dated, on Home when a Still true? question is
  waiting, or can say yes on Your data rights.
- **Telugu and Hindi** for the question, the channel names, the digest and
  asked-again wording and the changed notice paragraph are machine drafts
  (`i18n-te.js`, `i18n-hi.js`), not reviewed; so are the server's
  `YOUR_PLACE` reasons in `MessageTemplates`.

Answered by the owner and closed here (2026-09-15): which informational notices
are essential (V140: "does it change YOUR rights or obligations?"), how long
"Not now" lasts and what it is about (V141: 90 days, per holding and for the
digest), and a yes from before channels (V142: not inherited, asked again). One
edge of the first answer is still open: "No message tells a child they have
come of age". The dormancy notices were put to the same test when the branches
met (V143, entry 80).

**Why it is still here** The native screens and the other entry points are
separate pieces of work.

**When to fix** The native prompt before the phone app is given to anyone who
does not also use the web. The rest when the screens involved are next opened.

**Risk if left** Nothing is sent without consent. The risk is the opposite one:
people who would want reminders outside the app are not asked at the moment
they would say yes.
---

## 73. A dormant household with nobody who may take it on waits for good

**Resolved** (2026-09-15, owner's answers D8 and D8b). Kept so the number means
something where it is cited.

The departed owner's erasure no longer waits on the household: the purge erases
them on the day and keeps what they shared under a former member (V136,
`AccountPurge`). A household with nobody eligible has an operator repair: a
documented request (`dormancy_repair_requests`), audited, a notice to the
household before, a wait of at least seven days, then the change and a notice
after, only through `scripts/dormancy-repair.sh` as the schema owner with
`ALMIRA_OPS_DORMANCY_REPAIR=enabled` (V137). `ops.dormant_households_nobody_may_take_on()`
lists them. Proven by `DormantHouseholdApiTest` ("with nobody who may take it
on…") and `DormancyRepairTest`. What remains is "An operator repair records
where the evidence is, and nothing checks it".

---

## 74. The last owner of a household with records must be taken on explicitly, even with a successor named

**Resolved** (2026-09-15, owner's answers D6 and D7). Kept so the number means
something where it is cited.

(1) Naming is not the transfer: "Acceptance is the whole point. Order it, don't
automate it." The named successor, if eligible, is asked first and alone for
the successor's window (`almira.lifecycle.dormancy.successor-window`, 14 days),
after the general wait (a week after a memorial, none otherwise); they accept
with a step-up or decline; on a decline, at the end of the window or when they
stop being eligible, every eligible member may, and is told then (V135,
`DormancyOffers`). The automatic handover where the household holds no records
is unchanged. (2) The memorial week is kept, but only membership is frozen: an
admin who remains keeps every other admin capability (renaming, connections,
editing names). Proven by `DormancyOrderApiTest`, `DormantHouseholdApiTest`
("during a memorial's week…") and the SQL suite's dormant block.

---

## 75. A memorial's date is whatever its row says, so the runtime role could shorten a succession claim's week

**Where** `member_memorials_insert` policy (V40), `app.claim_household_succession` (V41).

**What** Found while adding dormancy. The insert policy does not constrain
`marked_at`, and the claim function counts its week from `marked_at`. The API
never sets the column (it takes the default), so no request can do this; SQL run
as the runtime role could insert a memorial dated eight days ago and claim at
once. The dormancy's own week (V120) is counted from when the row is written, so
it is not affected.

**Why not fixed** Outside this workstream's migration of V41's function; a fix
(a check that `marked_at` is within a minute of `now()` on insert, or counting
from `created_at`) would also change test fixtures that backdate memorials.

**Risk if left** Needs SQL access as the runtime role and an admin or open
emergency window in that household.

---

## 76. Dormant households have a web card and no native screen, and their words are English on the server

**Where** `app/lifecycle.js` (`dormancyCard`), `app/screens/family.js`,
`lifecycle/Dormancy.kt`, `app/` (native).

**What** The web Family screen shows a dormant household, a "Take on the
household" button and, for the successor asked first, "Decline". The native app
has none of these; a member there sees
`household_dormant` refusals in words but cannot take the household on. The
card's Telugu and Hindi are machine drafts; the explanation and the notices are
English sentences from the server, as the other lifecycle ones are.

**Why not fixed** The native lifecycle screens are another session's work.

**Risk if left** A native-only family must use the web to take a dormant
household on.

---

## 77. The operator alert for an undelivered sign-in email is only as loud as what watches it

**Where** `auth/SignInCodeOutbox.kt`, `auth/SignInEmailAlerts.kt`,
`config/HealthController.kt`, `scripts/check-health.sh`, docs/17 §8.

**What** The alert (entry "Sign-in emails go through an outbox, and an unlisted
address's is dropped there") is pulled, not pushed: an ERROR log line, which
needs a log collector with a rule on `SIGN-IN EMAIL NOT DELIVERED`, and a count
on `/health`, which needs `ALMIRA_OPS_HEALTH_TOKEN` set and
`check-health.sh --ops-token-file` in a cron with `--alert-cmd`. Neither exists on
any deployment yet, because there is no deployment. Also:

- the count covers the last hour, so a check that runs less often than hourly
  can miss one, and one that runs every minute alerts every minute for an hour;
- a message whose body a restore did not bring back (`body_not_restored`) is not
  alerted, because nothing is left to say whether its address was listed;
- a tester dropped because email sign-in was switched off *and* the allowlist
  was emptied in the same restart is indistinguishable from a removal, and is
  not alerted;
- phase D of `scripts/measure-sign-in-timing.py` has been run once at 100 / 10
  pairs, not at the 1,000 / 500 of the original measurement.

**Why not fixed** Pushing needs a channel the owner chooses (mail, SMS, chat),
and nothing is network-enabled by default. A full-size timing run is an hour of
a quiet machine.

**When to fix** With the first deployment: set the token, add the cron line,
and point the log collector at the event name (docs/17 §8). Run phase D at full
size before the alpha opens.

**Risk if left** A tester stuck on "sent" goes unnoticed until they say so —
the outcome the owner ruled out — on any deployment that has not wired either
surface.

---

## 78. Backups carry queued sign-in email addresses

**Resolved** (2026-09-15), by the owner's answer: *exclude — more firmly than the
reminder bodies; a backup holding them is a credential store*. `backup.sh` leaves
out the rows of `public.sign_in_code_email_bodies` as well as
`public.outbound_message_bodies`, lists both in `excluded_table_data`, and refuses
before dumping when any table whose name contains `bod` is not one of the two.
`scripts/tests/backup-checks-before-dumping.sh` proves the rows are absent from
the dump and that an unknown bodies table refuses with nothing written, watched
failing with the refusal turned into a message. The entry is kept below as it
was found.

**Where** `scripts/backup.sh`, `sign_in_code_email_bodies` (V110).

**What** `backup.sh` leaves out the rows of `outbound_message_bodies` only, and
refuses when a table matching `%message%bod%` is anything else.
`sign_in_code_email_bodies` does not match that pattern and is dumped in full, so
a backup taken while a sign-in email waits holds its address, purpose and
request id — for an unlisted address as much as a listed one. It holds no code
(the code is derived with a key the backup does not contain), and a body lives
only until the worker gets to it, normally within the poll interval (2 s). The
worker already handles a restored message with no body (`body_not_restored`),
so leaving the rows out would change nothing on restore.

**Why not fixed** Found while working on backups in another change; the exclusion
check's pattern and its test are the place to extend, and it deserves its own
watched-failing run.

**When to fix** Before the email alpha is backed up in earnest: exclude
`public.sign_in_code_email_bodies` the way the notification bodies are, list it
in `excluded_table_data`, and widen the refusal to any table whose name ends in
`bodies`.

**Risk if left** A stolen backup names the few addresses whose sign-in email was
in flight at that second.

---

## 79. No message tells a child they have come of age

**Resolved** (2026-09-15, V146), by the owner's answer: *a child-only notice —
their rights are the ones changing. With no channel to them, show it in-app at
their next sign-in and tell the guardian the child still needs telling.* The
sweep notices a child whether or not they have a login. With one, the child is
sent `lifecycle.coming_of_age.you` at once, essential (on
`app.message_is_essential` and `ESSENTIAL_TEMPLATES` together); without one, the
admins' note is titled "… and still needs telling" and the child is given the
notice at their first sign-in (`InvitationController.accept` →
`ComingOfAgeNotices.tellOnFirstSignIn`). `child_told_at` keeps it to once,
watched failing with that guard removed (`ComingOfAgeApiTest`). The entry is
kept below as it was found.

**Where** `lifecycle/ComingOfAge.kt` (`ComingOfAgeNotices`), V140,
`provider/MessageTemplates.kt`, docs/23 "Notices that protect your account".

**What** The owner's answer (V140): a child coming of age changes that child's
rights, so the notice is essential **to the child**. No such message exists.
The monthly sweep notices only a child with no login of their own, and tells
the adults who run the household (`lifecycle.coming_of_age.guardian`), who are
not the child and so stay under consent; the child is welcomed in the app when
they first sign in. A minor who already has their own login is not noticed at
all, so nothing reaches them either.

**Why not fixed** Adding a notice to the child means widening who the sweep
notices and what the welcome flow does for someone already signed in, which is
lifecycle behaviour beyond the consent work. The classification is ready: a
template sent only to the child (for example `lifecycle.coming_of_age.you`)
goes on `app.message_is_essential` and `ESSENTIAL_TEMPLATES` together.

**Risk if left** A young adult with their own login is not told outside the app
that what is held in their name is now theirs to manage; they see it when they
next open the household.

---

## 80. The dormancy notices have not been put to the owner's test

**Resolved** (2026-09-15, integration of the consent and dormant-household
work). Kept as a stub so the number still means something where it is cited.

V143 puts `lifecycle.household.dormant`, `.dormant.you`, `.running_again` and
`.ownership_accepted` on `app.message_is_essential` and
`MessageTemplates.ESSENTIAL_TEMPLATES` (worded as `YOUR_PLACE`): each changes
the recipient's rights or access — nobody can invite or remove people, an adult
may take the household on, the owner's own going is held, or that right ends
because someone runs it again. This matches docs/05 §12.7, which already called
`lifecycle.household.dormant` essential. Household news ("someone else joined
or left") stays under consent. Pinned by `MessagesConsentTest` (sent on every
channel without consent), `MessageTemplatesTest` and `rls_privacy_test.sql`.

---

## 81. Only a closure is carried out while a household is dormant, and only there does what was shared survive an erasure

**Resolved in part** (2026-09-15), by the owner's answer: *apply the split to
every erasure. A special case that only fires on dormancy will drift out of step
with the general path.* (1) is fixed: `AccountPurge` now erases a person from
every household they leave by one set of lines — private records and their
papers erased, what was shared and their part of joint records kept under a
former member — and `AccountClosureService.closurePreview` promises the same.
`AccountClosureApiTest` proves it in a household that is not dormant, watched
failing against the old path; the names others' records kept are now unlinked
with no contact-card link (flagged for counsel, Doc 23). Departures were settled the next day
(2026-09-16), by the owner: *leaving isn't erasure — don't apply the rule ... the
household keeps the shared records, because they're shared. Same "Former member"
label.* So a departure now moves or erases only what was private to the person
alone, leaves what they shared held by a former member, and gives a person who is
taking their records a full-size private copy of each such holding;
`DepartureApiTest` proves both choices, watched failing against the old rule.
What remains: (2) as written; and (3) as written, now for every erasure. The
entry is kept below as it was found.

**Where** `lifecycle/AccountPurge.kt`, `lifecycle/DepartureCompletion.kt`,
`LifecycleWrites.becomeFormerMember` (V136), docs/05 §12.1, §12.7.

**What** The owner's D8 answer was applied to the purge of a household's last
owner, which is where it was asked. Three things it did not settle:
(1) In any *other* household a closure still erases everything the person
solely held, whatever its visibility, as §12.1 always said — so a
household-shared holding recorded only in the departing member's name vanishes
from a household that is not dormant, while the same holding in a dormant one
stays under "Former member". (2) An owner's *departure* still waits for someone
to take the household on: the person keeps their account, so no right of theirs
is delayed, but what they hold there does not move. (3) A joint record that was
private and whose other holder has no login (a child) survives the erasure,
readable by nobody, as it was before.

**Why not fixed** Whether "the household's records belong to the other members
and survive" is the rule for every erasure, or only when nobody is left to run
the household, is the owner's call; applying it everywhere changes what
every closure preview has promised.

**Risk if left** Inconsistency between two households, not loss of anything
private: private records are erased in both cases.

---

## 82. An operator repair records where the evidence is, and nothing checks it

**Resolved in part** (2026-09-15, V147; 2026-09-16, V148), by the owner's answers. A request now
records the *kind* of evidence seen and who saw it and when — a death certificate
or equivalent when the trigger is a death, otherwise a written request from a
member or a legal representative, refused when it does not fit the trigger — and
never the document. It is designed for two operators (a second one, not the
requester, approves), with a single-operator mode that stores and audits its
reason. The wait starts when the before-notice is actually sent. A household with no
reachable address is no longer stuck (V148): an operator records that the notice
was given by post, phone or in person, with what was done and when, and the wait
runs from then — with two operators required and no acting alone, proven by
`DormancyRepairTest` watched failing with the guard removed. What remains true:
the database cannot check that the evidence exists or says what the operator
believes, and it cannot check that a notice an operator says was posted was
posted. The entry is kept below as it was found.

**Where** `dormancy_repair_requests.evidence_reference` (V137),
`scripts/dormancy-repair.sh`, docs/05 §12.7.

**What** A request needs a reason, the requester's name and relationship, and a
reference to where the evidence is kept (a ticket, a case file). The database
checks those are present and that the household is told a week or more before
anything is done; it cannot check that the evidence exists or says what the
operator believes. There is no runbook for what evidence is enough (a death
certificate, a succession certificate, a court order), no second operator
required, and the notices go only to addresses on the members' own accounts — a
household whose remaining members never sign in may not see them.

**Why not fixed** What counts as sufficient evidence, and whether a second
person must approve, are policy for the owner and counsel, not code.

**Risk if left** A mistaken or deceived operator could hand a household to the
wrong member after a wait nobody noticed. Every step is audited with the
operator's name, the request is readable by the household, and the owner
credentials plus the environment switch are needed.

---

## 83. Marking someone as passed away through the admin door, and a date of birth, are frozen with membership

**Resolved** (2026-09-15, V147), by the owner's answer: *confirm the freeze lasting
the whole dormancy — during dormancy nobody holds authority — but narrow it:
freezing edits to OTHER people's date of birth or login is right; freezing a
member's edit of their OWN login is not.* `app.members_frozen_while_dormant` now
lets a member change the login on their own row; another person's login, anyone's
date of birth and removal stay frozen. `DormancyRoutingTest` proves both, and that
a phone-number change mid-dormancy keeps the member in the household, watched
failing with the exemption removed. The entry is kept below as it was found.

**Where** `member_memorials_insert`, `members_insert`/`members_delete` policies,
`app.members_frozen_while_dormant` (V135), `HouseholdService.addMember`,
`updateMember`.

**What** The owner listed invite, remove, role change, successor change and who
may accept as frozen during a dormancy, and everything else open. Three more
were read as membership and frozen too: marking another member as passed away
through the admin door (it takes away their capabilities, and would change who
may take the household on — the trusted-contact door stays open); adding a
person without a login (the roster); and changing a member's date of birth or
login (a date of birth decides who is a minor, and so who may take it on).
Renaming a member stays open. The freeze also applies to a dormancy caused by a
closure or departure, not only to the week after a memorial.

**Why not fixed** Confirmation needed that these belong with membership, and
that the freeze lasts as long as the dormancy rather than only the memorial's
week.

**Risk if left** A remaining admin cannot record a second death, or correct a
wrong date of birth, until someone takes the household on.

---

## 84. Invitations have no row-level security

**Where** `invitations` (V1, V7), `invitation/InvitationService.kt`.

**What** Found while freezing membership. `invitations` has no RLS enabled and
no policies; creating, listing and revoking are guarded only by the service's
role check (and now its dormancy check). Accepting goes through
`app.accept_invitation`, which does refuse while dormant (V135). The runtime role
could insert an invitation for any household with SQL.

**Why not fixed** Enabling RLS on the table changes every invitation path and is
outside this workstream's scope; a token still has to be delivered and accepted
through the checked function to have any effect.

**Risk if left** Needs SQL access as the runtime role; an invitation written that
way cannot be accepted into a dormant household, and elsewhere still needs its
token.

---

## 85. After a wrong code, the app's code field loses focus

**Resolved** (2026-09-17), by the owner's ruling: *focus goes to the first cell
after a wrong code.* The code screen now asks for focus whenever the refusal has
been shown, keyed on the error as well as on the unlock animation, so it holds
whether or not the key rattled; it waits for the animation to finish, because
focusing under it raises the keyboard behind the doors. The controller already
cleared the code on a refusal, so the caret lands in cell one. The entry is kept
below as it was found.

**Where** `app/shared/src/commonMain/kotlin/tech/bhrigu/almira/shared/signin/SignInScreen.kt`,
`SignInController.verify`.

**What** Found by driving the Android app on the emulator (2026-09-16). The
controller clears the code on a refusal on purpose: "the next attempt is the only
thing this person wants". But the field also loses focus and the keyboard closes,
so the next attempt needs a tap on the field first. During the test the taps that
followed went nowhere and the tries-left count stayed where it was until the
field was tapped again, which reads as though the app has stopped responding.

**Why not fixed** Where focus should go after a refusal, and whether the keyboard
should stay up, is a design decision about the screen rather than a defect with
one right answer. The same screen is shared with iOS, so it wants deciding once.

**Risk if left** No security or data risk. It costs a tap at the moment somebody
is already having trouble getting in, and it can look like a frozen screen.

---

## 86. The service worker could not be verified in this session

**Where** `backend/src/main/resources/static/index.html` (registration),
`backend/src/main/resources/static/sw.js`.

**What** Live testing of the web client ran in the in-app browser, which refuses
every service worker registration: registering `sw.js` fails with "An unknown
error occurred when fetching the script", and so does registering any other
same-origin script, so it is the browser and not the app. The file itself is
served correctly (200, `text/javascript`) and `scripts/check-service-worker.js`
passes, but the offline shell was never actually exercised against a running
server in this session.

**Why not fixed** Nothing to fix was found; what is missing is the verification.
It needs an ordinary browser pointed at a development server, offline mode
toggled, and the shell opened.

**Risk if left** Unknown rather than broken. Offline behaviour is an enhancement
(the app works online exactly as before without it), but the "opens without a
network" promise in docs/16 rests on a check nobody has run end to end.

---
## 87. A contact or an estate document a viewer may read is refused as "not found"

**Where** `estate/EstateService.kt` (`deleteContact`, `deleteDocument`),
`contacts_delete` / `estate_documents_delete` (V18).

**What** Found while sweeping the record types for the hole fixed in `fbb6f00`.
These two do **not** have that hole: both check the row count and throw, so
nothing is reported as done that was not done. But what they throw is
`notFound`, and a viewer who can read the contact perfectly well is told it does
not exist. Investments, liabilities, accounts, goals and documents now answer
"You can read this, but it isn't yours to change" in the same situation.

**Why not fixed** Saying so needs a predicate for "may write this contact" that
the service can ask before it acts, and there is no `app.can_modify_contact` or
`app.can_modify_estate_document` to ask — those two policies spell their rule
out inline. Adding them is a migration, and `fbb6f00` deliberately touched no
SQL. Answering 404 is safe, and for a record the caller cannot read it is the
right answer; it is only the readable-but-not-writable case that misleads.

**Risk if left** A viewer or an advisor deleting a contact is told the contact is
gone from under them rather than that it is not theirs to remove, and reloads to
find it still listed. No data is at risk either way.

---

## 88. A rate-limited guest link reads as a gone one

**Where** `static/app/guest.js` (`load`, `failed`), `sharing/ShareService.rateLimit`.

**What** Found while adding the per-link and per-network caps on opening a guest
link. `load` treats any non-2xx as nothing and the page then says the link has
expired or been withdrawn, so a `429` shows the same words as a `404`. That is
right for the two answers the page was written for, and wrong for this one:
the link is fine, and the reader only has to wait. The API says which it is
(`error.code` is `rate_limited`, with `details.retryAfterSeconds`).

**Why not fixed** The honest page needs its own words in English, Telugu and
Hindi (docs/14), and the web client has no screen tests (docs/20 §9). The caps
are sized so that a person reading the page never reaches them; the reader who
does is a script.

**Risk if left** A CA who somehow met the cap would be told the link is gone and
would ask for a new one, rather than waiting. Nothing is lost, and the old link
still works.

---

## 89. A new person can sign in to the app and then cannot start anything

**Where** `app/shared/src/commonMain/kotlin/tech/bhrigu/almira/shared/App.kt`
(`SignedIn`, the `household == null` branch), `POST /api/v1/households`.

**What** Found on a real phone, 2026-09-26, doing the first end-to-end run
against a backend over Wi-Fi (docs/18 §4). Sign-in works. The account it makes
is new, so it has no household, and the app says: *"No household yet. The web
client can create one."* That sentence is the whole screen, apart from the Sign
out added this morning (issue 85's fix). The app can **use** a household and
cannot **start** one, so a person who meets Almira on a phone is sent to another
device before they can record anything.

Nothing is broken underneath: the API creates households perfectly well
(`POST /api/v1/households` is what the web client calls, and what was used from
the Mac to unblock that phone). What is missing is the screen.

**Why not fixed** The thin slice was scoped to reading an existing household on
a phone, and this is a piece of onboarding rather than a defect in what was
built. It needs the choices the web client's first run asks about (who it is
for, who it tracks, what new entries default to, the household's name and
yours), which is a design pass, not a form.

**Risk if left** It is the first thing a new user meets, and it tells them to go
away and come back. Every native-first install stops there. It also makes "a
real new user can onboard entirely from the app" untestable, which is half of
the docs/18 §4 milestone: the sign-in half is proven on real hardware, the
"and then use it" half is only reachable with a household someone made for you
elsewhere.

