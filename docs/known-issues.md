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

**Where** `backend/.../auth/AuthController.kt` (`OtpVerifyBody`).

**What** Omitting the `phone` field returns

```
500  {"error":{"code":"internal_error","message":"Something went wrong on our side…"}}
```

The cause is `HttpMessageNotReadableException` wrapping Jackson's
`MissingKotlinParameterException` for a non-nullable Kotlin constructor
parameter. It escapes the validation handler and lands in the generic 500 path.

The same path used to log Jackson's message at ERROR, and for some malformed
bodies that message quotes the input — `Unrecognized token 'x27020424'` — so a
one-time code could reach the production log. That part is fixed
(`UnreadableBodyMessages`, proved by `OtpCodeNeverLeaksTest`); the status is
still 500, and still wrong.

**What it should be** A 400 naming the field, like every other bad request the
API answers.

**Why it is still here** v1 is frozen, and no client hits this: both the web
client and the app always send `phone`. Found by a hand-written curl probe.

**When to fix** The next backend pass. Adding an
`@ExceptionHandler(HttpMessageNotReadableException::class)` that maps a missing
or unreadable body to a 400 would cover this and every sibling body type at
once. It is additive — a status changing from 500 to 400 on a malformed request
is not a v1 contract break.

**Risk if left** Low. It misreports a client error as a server error, which
costs someone debugging time and makes a genuine outage harder to spot in logs.

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

**Where** `auth/SignInChannels.kt` (the allowlist) and `AuthService.verifyEmailOtp`,
which is the only place it is checked.

**What** The allowlist gates signing in and nothing else. A tester removed from
`ALMIRA_ALPHA_EMAIL_ALLOWLIST` (which takes a restart) cannot sign in again, but
every session they already hold keeps working until its refresh token expires
(30 days), and step-up by email still reaches them. Only a code issued before
the removal and verified after it is refused, as `otp_expired`; that path has no
test, because the allowlist cannot be changed without a restart.

**Which is right** For a closed alpha, removal should end access: revoke that
user's sessions when the server starts without their address, or check the
allowlist on refresh.

**When to fix** Before the first time somebody has to be removed from the alpha.
Until then, removing a tester means removing the address *and* revoking their
sessions by hand (`user_sessions.revoked_at`).

**Risk if left** A removed tester keeps reading their own household's data.
Nobody else's: row-level security is unaffected.

---

## 15. A failed sign-in email is invisible to the tester, and costs them requests

**Where** `OtpService.deliverInBackground` (`OtpDelivery.UNREPORTED`).

**What** Deliberate, and the price of the allowlist being invisible: the email
request answers before the email is sent, so a rejected address, an outage or an
empty provider balance never reaches the person. They see "check your email",
nothing arrives, and after the 30-second cooldown they can ask again — and each
attempt counts toward the per-address hourly cap (5), because giving the count
back would let anyone tell a listed address from an unlisted one. Phone sign-in
reports and forgives all of these.

**Which is right** This, for as long as the allowlist exists. The operator side
is what needs to be real: the WARN `one-time code by email not confirmed sent`
and the ERROR `PROVIDER ACCOUNT PROBLEM` must be alerted on before the alpha
starts, or a broken provider is discovered by testers reporting silence.

**When to fix** Revisit when email sign-in stops being allowlisted: then there
is nothing to enumerate, and it can report failures the way phone does.

**Risk if left** Testers locked out for up to an hour by our failure, with
nothing on screen saying why.

---

## 16. The frozen v1 contract does not describe email sign-in yet

**Where** `docs/api/openapi-v1.json`, against the live `/v3/api-docs`.

**What** `GET /auth/otp/channels`, `POST /auth/otp/email/request`,
`POST /auth/otp/email/verify` and the `channel` field on `OtpChallengeResponse`
are additive, so `OpenApiContractTest` passes — but the committed contract the
apps are built against does not list them. The stage that added them did not
re-freeze the file.

**Which is right** Re-freeze deliberately with `./scripts/freeze-api-spec.sh`
once the owner has looked at the new endpoints, so the handoff artefact matches
the server.

**When to fix** Before anyone builds a third client from the JSON alone.
docs/api/README.md already describes the endpoints.

**Risk if left** None for the two existing clients, which were changed alongside.

---

## 17. Plaintext columns still hold "where the original is"

**Where** `investments.storage_location` (V3, the "Kept at" / "Where it's kept"
field in many investment type schemas), `estate_documents.location` (V18) and
`investment_templates.storage_location` (V16). They are read by `SearchService`,
`HandbookService` (including the PDF) and `TransmissionService`.

**What** [Doc 20](20-where-and-who.md) seals the location of the original under
the zero-knowledge scheme, with no plaintext fallback. These older columns hold
the same sentence in plaintext. The server can read them, search them and print
them. They cannot be removed while v1 is additive-only, and the server cannot
migrate them itself, because it has no key.

**Which is right** The sealed field. The web client no longer asks for either
column: the capture form and the new-will form show a pointer to the sealed card
instead. On any record that still has a plaintext note, it shows a warning with a
button that seals the note and clears the column. The button is offered only when
the sealed slot is empty or the person's own. Three things still write plain
text: whatever any other v1 client sends; the server's copy of `storage_location`
when a holding is duplicated (`InvestmentService`); and template save and apply
(`TemplateService`), which carry it between the holding and `investment_templates`.

**When to fix** When there is a v2 contract. Sooner, the server could stop
carrying the column into duplicates and templates, and a data migration could
take `storage_location` out of the seeded type schemas, so that the native app
stops being prompted for it. The native app needs the same warning and move.

**Risk if left** People keep writing the most damaging sentence in the database
into a field the server can read, next to a sealed field that exists for exactly
that sentence.

## 18. Two clocks for "not confirmed lately"

**Where** `DashboardService.attention` (the "Not confirmed in over six months"
card) and `app.still_true_period_months` / `still_true_records` (V29, [Doc 21](21-still-true.md)).

**What** The dashboard card counts a holding as stale after a flat six months
since `last_verified_at` (or creation). "Still true?" asks about a holding after
12 months (3 for cash), or a week after a maturity or renewal date. The same
holding can therefore sit on the Home attention list for six months before it is
ever asked about, and the card's "Review" button leads to the holdings list, not
to the question.

**Which is right** Doc 21's periods, which are argued per type; the card predates
them. Confirming through "Still true?" already stamps `last_verified_at`, so
answering the question does clear the card.

**Why it is still there** The dashboard response is part of frozen v1, and its
label and count are something the native app renders. Changing what the card
counts changes a v1 behaviour, which is the owner's call.

**When to fix** When the native app gains the "Still true?" surface: the card can
then read `still_true_items` (or be retired), and its button can open the list.

**Risk if left** Two answers to one question on the same screen, which is how a
freshness signal stops being believed.

## 19. The completeness score can say 100% with a gap still there

**Where** `CompletenessService.report` (`GET /reports/completeness`, the card on
Reports), compared with `HandoverReadinessService` ([Doc 22](22-handover-readiness.md)).

**What** Three things, each found while designing readiness:

- It rounds half up: `earned × 100 ÷ possible`, `RoundingMode.HALF_UP`. In a
  household of 200 active holdings where one has no nominee, 3 weighted points
  are missing out of well over a thousand, so the score shows **100**.
- With nothing recorded, it returns `score = 100` (the label says "Nothing to
  check yet", but the number is still 100).
- It weights each item and adds the items up, so a large household drowns out
  any single gap. The two scores also count different records: completeness
  counts `active` holdings, including ones left out of continuity, while
  readiness counts `active` and `matured` holdings that are in continuity,
  plus executed wills.

**Which is right** Doc 22 §1: round down, show no number when the data has not
earned one, and give each check equal weight. Doc 18 §6 is the rule both
features answer to.

**Why it is still there** The completeness response is frozen v1, and its
`score` is an `Int` that clients render. Changing the rounding is small, but it
is a v1 behaviour change, and so is making `score` nullable. That is the owner's
call, and it was out of scope for the change that found it.

**When to fix** When Reports is next touched. The least disruptive fix is to
round down, and to add an additive `scoreEarned: false` (or retire the card in
favour of readiness) rather than change `score` to null.

**Risk if left** Two percentages about the same household can disagree, and one
of them can claim to be complete when it is not.

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
  at-most-once: a claim committed just before the worker died loses that
  message rather than risk a second, and the in-app row is the only copy.
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
