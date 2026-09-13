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

**Where** `app/shared/.../capture/FormModel.kt` (`toFormFields`) and
`backend/src/main/resources/static/app/screens/capture.js` (`captureForm`).

**What** Both clients generate the capture form from the type's `field_schema`,
so they always agree on *which* fields exist, what they are called, which are
required and what they accept. They order them differently:

- **App** — merges the re-labelled columns and the type's attributes into one
  list and sorts the whole thing by `sort`.
- **Web** — renders every re-labelled column first, then every attribute, so
  `sort` only orders within each of those two groups.

**Which is right** The app. The numbers in the schema plainly intend an
interleave: a Fixed Deposit's `interest_rate` is sort 30, between `Principal`
at 20 and `Opened on` at 40, and Principal → rate → dates is the order someone
reads off an FD receipt. The web pushes the rate below both dates.

**Why it is still here** The fix is a change to frozen-adjacent web code, and
it is worth making deliberately rather than as a side effect of an app stage.
This is an intended, known divergence until then.

**When to fix** The next time we open `capture.js` for its own reasons. Sort the
merged list the way `toFormFields` does; there is no API change and no data
change.

**Risk if left** Cosmetic only. Nothing that reaches the server can differ.

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

**Where** `backend/src/main/resources/static/sw.js` (`VERSION`).

**What** The shell is cached cache-first, deliberately (docs/17), so an edit to
`base.css`, `app.js` or any other shell asset is invisible to a browser that
has already installed the worker until `VERSION` changes. During development
this reads as "my change did nothing" — it cost a build and a wrong diagnosis
while building the phone navigation.

**Why it is still here** It is the cache strategy working as designed, not a
defect. Writing it down is cheaper than rediscovering it.

**When to fix** Not a fix — a habit. Bump `VERSION` in the same commit as any
shell asset change. If this keeps biting, the durable answer is to derive the
version from a build property rather than a hand-edited constant.

**Risk if left** Development friction only. Released builds are fine, because a
release always changes the version.

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

**Where** `backend/.../e2e/SealedFieldService.kt` — `fieldKey` is checked for
non-blank and a 64-character ceiling, and nothing else.

**What** `|` U+007C separates the four components of the zero-knowledge AAD and
is not escaped, so no component may contain one (docs/zk-interop-acceptance.md
A0, B3). Both clients now enforce that at AAD construction. The server does not,
so a third client — or a direct database write — could still store a key holding
one.

**Is it exploitable today?** No. Of the four components, two are Postgres `uuid`
columns and one is a five-word vocabulary, so none of the three that precede the
free one can hold a pipe; the first three separators always delimit exactly and a
key full of pipes still parses unambiguously. It becomes exploitable the day a
fifth component joins the AAD.

**When to fix** The next backend pass: reject `|` in `fieldKey` alongside the
existing blank and length checks. Additive, and no client sends one.

**Risk if left** None reachable now. The cost is that a rule the clients enforce
is not enforced where the data actually lands.

---

## 8. The web client can set up zero-knowledge mode but cannot seal a field

**Where** `backend/src/main/resources/static/app/` — `e2e.js` exports
`sealField`, `readSealed` and `unsealField`; no screen calls them.

**What** Settings can enable zero-knowledge mode, unlock it and lock it again.
There is no interface anywhere in the web client for sealing a value on a record
or for reading one back, so the feature is reachable from the browser only
through the module's exported functions.

This matters for how the interop evidence should be read: when the acceptance
run says "the web client sealed it", the shipped `e2e.js` really did the sealing
against the real API — but a person could not have done the same thing by
clicking, because there is no button. The Android app has the screen; the web
does not.

**When to fix** The web-widening pass. The detail sheet is the natural home: a
sealed field belongs beside the record it describes, not on a separate screen —
which is also a hint that the app's current standalone "Sealed" screen is a
staging post rather than the final shape.

**Risk if left** No correctness or data risk. The capability exists and is
proven; it is simply not offered.

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
surgery.

---

## 10. DigiLocker's connect flow would be unsafe against the real service

**Where** `backend/.../provider/ConnectService.kt` — `startDocumentVault`,
`completeDocumentVault`, `upsertConnection`, `activeSession`.

**What** Two things that do not matter against the in-process sandbox and would
against DigiLocker. The OAuth `state` generated by `start` is never compared on
`complete` (which does not accept one), so a real flow could be completed with
somebody else's authorisation code — a household importing the wrong person's
documents. And the session token is written, in plaintext, to
`provider_connections.external_ref`, a column V24 documents as "never a
credential", while the `access_token_enc` and `expires_at` columns V24 made for
it stay empty; `activeSession` then invents a one-hour expiry on every read.

**Which is right** V24's design: token encrypted with the household DEK in
`access_token_enc`, real expiry in `expires_at`, and `state` checked.

**Why it is still here** Found while writing the go-live checklist, a docs stage.
Fixing it changes the v1 `complete` request (additively) and wants its own
watched-failing tests.

**When to fix** Before `digilocker` is added to `ProviderModeCheck.implemented`.
It is step 3 of [providers/digilocker.md](providers/digilocker.md).

**Risk if left** None while every provider is a sandbox, because `live` refuses
to start. Serious the day it does not.

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

**Where** `provider/Delivery.kt`, `RecordingNotifier.deliver`, which calls every
`ChannelSender` as `send(notification, null)`.

**What** No lookup from a user to a phone number, an email address or a device
token exists. A live SMS, email or push sender would authenticate and have
nowhere to send. For push there is also nothing to look up: no client registers
a device token and there is no table or endpoint for one.

**Which is right** A per-channel recipient lookup — for push, zero or more
tokens per user — designed in [providers/push.md](providers/push.md).

**When to fix** Before the first notification channel goes live. Delivery
itself is already off the request thread (the notification outbox, Doc 13); the
worker passes `recipientHint = null` for the same reason.

**Risk if left** None while every channel is a sandbox.

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
- **The refresh-reuse audit row.** Unrelated, noticed here:
  `AuthService.refresh` writes `auth.refresh_reuse_detected` inside the
  transaction its own throw rolls back, so that audit row is probably never
  kept (the revocation itself is, via `SessionRevoker`). Not verified; no test
  asserts the row.

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
from a development server built from the commit before this one, and now holds
120 paths, 163 operations and 179 schemas (was 109, 152, 164).

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

**Where** `backend/.../config/DatabaseConfig.kt` (`systemJdbc`, the
`systemJdbcBypassingRls` bean) and its only consumer, `reminder/ReminderWorker.kt`.

**What** The bean is meant to be a template on the *owner* pool. Its parameter
is `ownerDataSource: HikariDataSource`, with no `@Qualifier`. Both pools are
`HikariDataSource` at runtime, so once both exist the parameter matches both,
and `@Primary` on the runtime pool wins over the parameter's name. The
template is therefore built on `almira-app`: the hourly sweep runs under
row-level security with no user, sees no rows, sends nothing, and reports no
error. Two separate verifiers observed this at runtime during the build; this
entry was written from their reports and from reading the code, not from a new
run.

**Which is right** The fix already written on the unpushed branch
`infra/deploy-and-pentest`: `@Qualifier("ownerDataSource")` on the parameter,
with a comment explaining why, and `ReminderSweepTest` to prove the sweep sees
rows.

**Why it is still here** `DatabaseConfig.kt` is owned by the infra session, and
the fix lives on its branch. The build that noticed it was told not to touch the
file.

**When to fix** When `infra/deploy-and-pentest` is merged. Do not fix it a
second time on master: the two changes would conflict for nothing.

**Risk if left** No reminder is ever sent from master, silently. A maturity or
renewal date passes without the nudge the product promises. No privacy risk:
the failure is seeing too little, not too much.

---

## 21. Interactive provider calls still hold the request, and background delivery is only as idempotent as the provider

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

**What is still open**

- **Connect calls hold the request, and a database connection.** DigiLocker
  and Account Aggregator calls are interactive by the owner's rule (a person is
  waiting for the answer) and stay in `ConnectService`, inside `@Transactional`
  methods. At DigiLocker's defaults (`timeout: 15s`, `max-attempts: 3`) an
  outage holds the request and an app-pool connection for about 46 seconds.
  **Which is right**: call the provider before opening the transaction (or
  between two), and give connect calls an interactive budget of their own, as
  one-time codes now have. **When**: before DigiLocker goes live.
- **A WhatsApp reply is sent inside the inbound webhook.** Its outcome is part
  of the capture's answer (`replyFailure`), so it was not moved. Meta expects a
  webhook to answer quickly and redelivers when it does not, which would
  capture the same message twice. **When**: before WhatsApp goes live — queue
  the reply and de-duplicate inbound messages by their id.
- **The idempotency guarantee rests on the provider.** On a channel whose
  adapter declares `honoursIdempotencyKey = true` (the `sms` and `email`
  sandboxes), a timeout and a send cut off by a crash are sent again with the
  same key, and only the provider's de-duplication stops a second delivery. A
  live adapter that declares `true` for a provider that does not de-duplicate
  — or remembers keys for less than the worker's lease, about 3.5 minutes at
  the SMS defaults — brings duplicate texts back. On `false` (push) delivery is
  at-most-once: the one send a worker had stamped as started when it died
  loses that message rather than risk a second (the rest of its batch is sent
  normally), and the in-app row is the only copy.
- **Only reminders have a deterministic logical key.** Still-true nudges and
  emergency-access notices get a random one per message; their own bookkeeping
  decides whether a message exists, but two servers sweeping the same household
  in the same instant could each queue a nudge. Harmless while one server runs.

**Risk if left** None while every provider is a sandbox. Live: a slow
DigiLocker starving the app pool, WhatsApp webhooks captured twice, and — only
if an adapter misdeclares its provider — duplicate notifications.

---

## 22. A code request that replaces a live one and then fails leaves neither

**Where** `auth/OtpService.issue`.

**What** A new request writes its challenge over the key of any existing one
(`putAll` on the same `challengeKey`), so the earlier code stops working at that
moment. If the send then fails with anything but a timeout, `CONSUME` removes
the new challenge too. The person is left with no working code: the old one was
overwritten, the new one was never delivered and has been deleted.

In practice the 30-second cooldown means this only happens when the earlier
code is at least that old. The cooldown and the per-number count are given
back, so they can ask again straight away; nothing is locked out.

**Which is right** Either keep the previous challenge until the new send
succeeds (write the new one under the request id, then swap on success), or put
the previous challenge back when the send fails, if it has not been used or
expired in the meantime.

**Why it is still here** Found while reviewing the provider failure contract;
not in that change's scope, and every path through it needs a Redis-backed
watched-failing test of its own.

**When to fix** The next time `OtpService` is opened, and before a live SMS
provider (whose outright rejections are what trigger it).

**Risk if left** Low. Someone who asked again because the first text was slow
can lose a code that would have worked, and has to ask a third time.

---

## 23. Migration V26 on the infra branch sits below V27 to V30 on master

**Where** `db/migrations/V26__ciphertext_digests.sql`, which exists only on
`infra/deploy-and-pentest`. Master skips 26, leaving the number free, and
already has `V27` to `V30`.

**What** Flyway applies migrations in version order and, by default, refuses to
start against a database that has applied a higher version than a pending one
(`outOfOrder` is not enabled anywhere in this repo). Any database migrated from
master, including every test database and the personal dev stack, will reject
V26 once the infra branch is merged. Leaving the number free does not help:
a database that has applied V30 treats a newly arrived V26 as a migration it
skipped.

**Which is right** Renumber the infra migration above the highest version on
master at merge time (V31 today), and update the references to `V26` in that
branch's `deploy/restore/digest-check.sql` and `docs/17-deploying.md`, which
both name it. Turning on `outOfOrder` is not the answer: it makes the schema
depend on the order a database happened to see the branches in.

**Why it is still here** The migration belongs to the infra session's
unpushed branch, and renumbering it there is that session's change.

**When to fix** At the merge of `infra/deploy-and-pentest`, before it lands.

**Risk if left** Loud, not silent: the application refuses to start with a
Flyway validation error on any already-migrated database.
