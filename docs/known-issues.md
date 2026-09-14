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

---

## 15. A failed sign-in email is invisible to the tester, and costs them requests

**Resolved, with named residual signals** (2026-09-13, "Allowlist and visible
failure"). Kept as a stub.

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
through the same code, replaying the last real send's outcome and latency, so a
failing provider fails for both (`EmailSignInApiTest`, `EmailOtpTest`).

**What is still distinguishable** — the full list, with conditions, is in
docs/13 §5:

1. A synchronous **rejection** of one address is reported for a listed address
   and never for an unlisted one: "couldn't deliver to that address" means
   listed and undeliverable.
2. After a **change in the provider's state**, decoys report the old state
   until a listed address is next sent a code — however long that is. In that
   window each probe is a clean listed/unlisted bit: a prober who knows of an
   outage (a public status page) can rule out unlisted candidates and confirm
   one listed address per provider transition; that confirming probe closes the
   window.
3. A real send whose latency **straddles a whole second** can settle one tick
   away from a decoy.
4. With **no real send in the last day** (or a fresh Redis), decoys assume a
   healthy provider.

**Risk if left** Each needs either a listed, undeliverable address or probing
inside a provider transition (at most one listed address confirmed per
transition), and every probe spends the prober's per-network allowance. The operator alerts still matter: the WARN
`one-time code by email not confirmed sent` and the ERROR
`PROVIDER ACCOUNT PROBLEM`.

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
  shows the sentence and no percentage when `scoreEarned` is false. The web card
  does (`static/app/completeness.js`, `scripts/check-completeness.js`). Before,
  `score` was 100.
- **Everything done is 100**, with `scoreEarned: true`.

Watched failing: rounding half up again (unit and API tests get 100 for one gap
in a thousand), returning 100 for nothing recorded (three tests), and the web
helper ignoring `scoreEarned` (it shows "0%").

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
guesses. Decoys go through the same scripts, so a decoy's failed resend falls
back the way a real one does. Same for phone and email, sign-in and step-up.
Proven by four tests in `OtpServiceTest` and three in `EmailOtpTest` (the
`known-issues 22` sections), each watched failing.

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
- A memorial stops messages from the moment it is made; a message already queued
  to the outbox a second earlier can still go.

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

**Where** `db/migrations/V45__data_rights_and_parental_consent.sql`
(`app.messages_consent_withdrawn`), `backend/.../privacy/MessageConsent.kt`.

**What** A person with no `messages` event at all — everyone who signed up
before V45 — still gets reminders and the "still true?" digest by email and
text. Only an explicit withdrawal stops them. The page shows such a person "Not
asked yet: sent as before until you choose".

**Which is right** Not decided. Rule 3 wants consent that is given, not
presumed; stopping every existing reminder silently on the day this shipped
would have been its own harm, and there are no live email or SMS providers yet.

**Why it is still here** It is a product and legal decision, not a code one.

**When to fix** Before 13 May 2027. Either ask everyone once (the notice
acceptance is the natural moment) and flip the default in
`app.messages_consent_withdrawn` to "not given", or have counsel say the
current behaviour is acceptable.

**Risk if left** Email or text reminders to someone who was never asked, once a
live provider exists.

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
