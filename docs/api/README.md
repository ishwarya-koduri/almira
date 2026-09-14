# Building a client against Almira v1

The contract is [`openapi-v1.json`](openapi-v1.json) — 154 paths, 205 operations,
218 schemas. Generate a typed client from it; do not hand-write one.

**v1 is additive-only.** New endpoints and new optional fields may appear; nothing
will be removed, renamed or retyped. A breaking change goes to `/api/v2` and v1
stays as it is for as long as a released app depends on it. `OpenApiContractTest`
fails the backend build if that promise is broken, so a client can rely on it.
**Operation ids are part of the promise**: a generated client names its methods
after them, so a changed `operationId` fails the build too. **The file is complete**:
the same test fails when the server serves a path, operation, parameter, response
code, schema, field or enum value this file does not list, so what you generate
from it is everything there is.

**Added at the 2026-09-14 re-freeze** — built before it, and described below, but
only now in the file:

| Endpoint | Described in |
|---|---|
| `GET /auth/otp/channels` | [Authentication](#authentication) |
| `POST /auth/otp/email/request`, `POST /auth/otp/email/verify` | [Authentication](#authentication) |
| `GET /auth/otp/email/delivery/{requestId}` | [Authentication](#authentication) |
| `GET /me/messages` | [Authentication](#authentication), after the provider failures |
| `GET /households/{householdId}/connect/digilocker/documents` | [Authentication](#authentication), after the provider failures |
| `GET /households/{householdId}/where-and-who` | [Phase 3 and 4](#phase-3-and-4-the-parts-that-decide-who-sees-what) |
| `GET /households/{householdId}/still-true`, `POST …/still-true/{recordType}/{recordId}/confirm`, `POST …/snooze` | [Phase 3 and 4](#phase-3-and-4-the-parts-that-decide-who-sees-what) |
| `GET /households/{householdId}/continuity/readiness` | [Phase 3 and 4](#phase-3-and-4-the-parts-that-decide-who-sees-what) |

**Added with the second factor (V50), 2026-09-14** — all additive; see
[Second factor and how you sign in](#second-factor-and-how-you-sign-in):
`POST /auth/second-factor/authenticator`, `…/recovery-code`, `…/passkey/options`,
`…/passkey`; `GET /auth/sign-in-methods`; `POST /auth/authenticator`,
`POST /auth/authenticator/confirm`, `DELETE /auth/authenticator`;
`POST /auth/recovery-codes`; `POST /auth/passkeys/options`, `POST /auth/passkeys`,
`DELETE /auth/passkeys/{id}`; `POST /auth/step-up/authenticator`,
`…/step-up/recovery-code`, `…/step-up/passkey/options`, `…/step-up/passkey`;
`POST /auth/phone/request`, `POST /auth/phone/verify`.

Existing schemas grew in the same freeze: `OtpChallengeResponse.channel`,
`WhatsAppCapture.replyFailure`, `Completeness.scoreEarned` (always present) and
`scoreExplanation`, and `ProviderStatus.mode` gained `DISABLED`. A client that
maps enums strictly must accept a value it does not know.

A response the contract never declared, which reports a server fault for input the server rejected, may be corrected to the 4xx the API README already documents. Record each such correction in the API README's changelog.

Everything below is behaviour the schema cannot express. It is short, and every
line of it will otherwise be learned the hard way.

---

## Authentication

```
GET  /api/v1/auth/otp/channels                → { channels: ["phone"] | ["email"] | ["phone","email"] }
POST /api/v1/auth/otp/request        { phone }        → requestId, expiresInSeconds, resendAfterSeconds, channel
POST /api/v1/auth/otp/verify         { phone, code }  → accessToken, refreshToken, isNewUser, user
POST /api/v1/auth/otp/email/request  { email }        → the same challenge shape
GET  /api/v1/auth/otp/email/delivery/{requestId}      → requestId, status, failure?, message?, resendAfterSeconds?
POST /api/v1/auth/otp/email/verify   { email, code }  → the same login shape
POST /api/v1/auth/refresh            { refreshToken } → a new pair
```

**Ask the server how to sign in first.** `GET /auth/otp/channels` is public and
the same for everybody. Show only what it lists; when it lists both, lead with
phone and offer one quiet switch. A server older than the endpoint answers 404 —
treat that, and any failure, as `["phone"]`. The endpoints of a channel that is
not listed answer `403 sign_in_channel_disabled` with `details.channel` and
`details.enabledChannels`, before the body is looked at. The closed alpha runs
**email only**: with both on, one person can sign in once with a number and once
with an address and end up with two accounts nothing joins.

**Email sign-in is allowlisted, and the answer never says whether you are on
the list.** An address that is not allowed gets exactly what an allowed one
gets — `200`, the same fields, the same cooldown `429`, the same `otp_stale`,
`otp_invalid` (with `attemptsRemaining`), `otp_locked` and `otp_expired` in the
same order — and simply never receives a code. So a client must not branch on
anything here and must not say "not invited": go to the code step, and if the
code never comes, the person asks whoever invited them. For the same reason the
email **request** never reports a delivery failure — `otp_delivery_failed`,
`otp_provider_unavailable`, `otp_service_unavailable` and `otp_delivery_delayed`
are phone-only *responses* at sign-in. The send happens after the response. The
one exception is development, where `developmentCode` is present only for an
allowed address.

**But a failed email is never silent.** On the code step, poll
`GET /auth/otp/email/delivery/{requestId}` about once a second until `status`
is not `sending`:

| `status` | Do |
|---|---|
| `sending` | Ask again shortly. |
| `sent` | Nothing more to say. |
| `delayed` | Show the delayed sentence; resend is open now (`resendAfterSeconds: 0`). The code still works if it arrives. |
| `failed` | Say **"We couldn't send the code."** with the sentence for `failure` (`otp_delivery_failed` · `otp_provider_unavailable` · `otp_service_unavailable`, the same advice as the phone codes); resend is open now. This request's code is gone; if it replaced an earlier code that is still live, that code works again, under either request id. |

Unknown or expired request ids are `404 otp_request_unknown`; treat that, a
server without the endpoint, and any status you do not know as "stop asking".
An address that is not allowed gets a status too, and it settles the way an
allowed address's would with the email provider as it is — so a failing
provider fails for both, and nothing here needs a branch either. Outcomes are
applied on whole-second ticks from the request. What is still distinguishable,
and when, is in [Doc 13 §5](../13-providers-and-going-live.md#sign-in-codes-by-email--the-closed-alpha).

**Taking an address off the allowlist signs that tester out.** Once a server
runs without the address, the account's refresh token answers `401` and its
access token stops working on the next request — there is no grace period to
wait out. An account with a phone number is never affected.

Addresses are trimmed and lower-cased on the server and nothing else — dots and
`+tags` are kept, because outside Gmail they name different mailboxes. Send
whatever the user typed. A malformed address is `400 email_invalid`, whoever
asks. The first successful verify for an address creates an account whose only
identifier is that address (`user.phone` absent).

`channel` on a challenge (`phone` or `email`) is additive: say "we texted you" or
"we emailed you" from it, and assume phone when it is absent.

Send `Authorization: Bearer <accessToken>`. It lasts 15 minutes.

**Refresh tokens are single-use.** Presenting one that has already been rotated
is treated as theft — the whole session is revoked and the user must sign in
again. Two consequences for a client:

- **Never refresh concurrently.** Several requests failing at once must share one
  refresh, not each start their own; the second looks exactly like reuse. The web
  client guards this with a single in-flight promise (`app/api.js`).
- A `401` after a successful refresh is real. Do not retry in a loop.

Phone numbers are normalised server-side: `98765 43210`, `+91 98765-43210` and
`09876543210` are one account. Send whatever the user typed.

In development the OTP is returned in `developmentCode` and printed to the log,
so the whole flow works with no SMS provider. It is absent in every other
environment.

A server with no working code sender answers the request with `503` and
`otp_unavailable`, and sends nothing. Show the message; retrying will not help.
Today that is every server not running in development.

When a sender exists but the send itself fails, the code says which way — and
each one needs different words ([docs/13 "When a provider fails"](../13-providers-and-going-live.md#when-a-provider-fails)):

| Status | Code | What to do |
|---|---|---|
| 504 | `otp_delivery_delayed` | Go to the code step anyway: the text may still arrive and will work. `details.requestId` is the challenge; offer a resend after `details.resendAfterSeconds`, which is `0` — the server made one attempt and never retries, so resend is the retry and is open at once. A resend replaces the challenge; the late code then stops working. |
| 422 | `otp_delivery_failed` | Nothing was delivered. Let the person correct the number and ask again straight away. For this and the two 503s below: an earlier code from the same flow that is still live keeps working, so stay on the code step if you were on it. |
| 503 | `otp_provider_unavailable` | Nothing was sent. Suggest trying again in a few minutes. |
| 503 | `otp_service_unavailable` | Our account problem. Show the message; do not suggest checking the number. |

Connecting DigiLocker or the Account Aggregator fails the same four ways, as
`provider_timeout` (504), `provider_unavailable` (503), `provider_rejected` (422)
and `provider_account_unavailable` (503), with `details.provider`. The WhatsApp
webhook still answers 200 and sets `replyFailure` to one of those codes.
When `POST …/connect/digilocker/complete` connected but could not list
(`details.connected = true`), the code is spent: list with
`GET …/connect/digilocker/documents`, which needs none.

A provider this server does not offer is not a failure: `GET …/connect/providers`
reports it with `mode: DISABLED`, and every call to it — DigiLocker, Account
Aggregator (disabled by default: cut from v1) or the WhatsApp webhook — answers
`409` `provider_disabled` with `details.provider`. Retrying will not help; hide
whatever the client offers for a provider reported as `DISABLED`. `mode: OFF`
remains in the enum for compatibility and is never sent.
`GET /api/v1/me/messages` lists the caller's own notifications, with `status`,
`failure`, `attempts` and a ready-to-show `failureMessage`.

### Step-up

Revealing a full account number, or opening a document, needs a **recent
re-authentication on that session** — not just a valid token. A borrowed unlocked
phone should not be enough.

```
GET  /api/v1/auth/step-up          → { elevated, expiresInSeconds }
POST /api/v1/auth/step-up/request  → sends an OTP
POST /api/v1/auth/step-up/verify   → elevates this session for 5 minutes
```

A `403` with code `step_up_required` is the signal to walk the user through it.

The code goes where this account can sign in on this server: its phone number
when phone is offered and it has one, otherwise its email address. The
challenge's `channel` says which. An account with nothing on an offered channel
gets `400 no_step_up_channel` (this used to be a 500 for email-only accounts) —
today a phone-only account on an email-only server. An email-only account on a
server without email sign-in never gets that far: its session is ended and the
request is `401` (docs/13 §5).
Unlike email sign-in, a step-up email that cannot be sent **does** answer with
the failure codes above — the caller already owns the address, so there is
nothing to enumerate.
Elevation belongs to the **session**, so confirming on a phone does not unlock a
browser someone else is sitting in front of.

### Second factor and how you sign in

An account may add an **authenticator app**, **passkeys** and **recovery codes**.
Nothing changes for an account that has none.

**Sign-in gains a second step for an account that has one.** A correct code to
`POST /auth/otp/verify` or `/auth/otp/email/verify` then answers
`401 second_factor_required` — not a session — with:

```
details: { secondFactorToken, methods: ["authenticator" | "passkey" | "recovery_code", …], expiresInSeconds: 300 }
POST /api/v1/auth/second-factor/authenticator    { secondFactorToken, code }       → the login shape
POST /api/v1/auth/second-factor/recovery-code    { secondFactorToken, code }       → the login shape
POST /api/v1/auth/second-factor/passkey/options  { secondFactorToken }             → { requestId, options }
POST /api/v1/auth/second-factor/passkey          { secondFactorToken, requestId, credential } → the login shape
```

Branch on the code, not the status: a client that treats every 401 from verify
as "wrong code" strands the person. Wrong answers are `400 second_factor_invalid`
with `attemptsRemaining`; the fifth is `second_factor_locked`; a used, expired or
locked token is `second_factor_expired` — start again from the phone or address.
After ten wrong answers in an hour an account answers `429`. A recovery code
works once, and using one tells the account's devices.

**Passkeys.** `options` is exactly what `navigator.credentials.create()` /
`.get()` takes, with its binary fields (`challenge`, `user.id`, credential `id`s)
as base64url strings; send the credential back the same way (`rawId`,
`clientDataJSON`, `attestationObject` or `authenticatorData`/`signature`/
`userHandle` as base64url). `app/sign-in-security.js` has both conversions. A
ceremony is answered once. A server without a passkey domain answers
`503 passkeys_unavailable`, and `GET /auth/sign-in-methods` says
`passkeysAvailable: false`.

**How you sign in** — `GET /auth/sign-in-methods`: masked `phone` and `email`
with whether each signs in here, `authenticator`, `passkeys`, `recoveryCodesLeft`,
`count`, `minimum` (2) and `meetsMinimum`. Ask for a second way until it is met.

**Changing it needs this session confirmed.** Adding an authenticator
(`POST /auth/authenticator` → `secret`, `otpauthUri`; then `…/confirm { code }` →
`recoveryCodes`), adding a passkey, removing either and replacing recovery codes
answer `403 step_up_required` until the session is elevated. When the account
already has a second factor, a code alone is not enough: the 403 carries
`details.requires: "second_factor"`, and only
`POST /auth/step-up/{authenticator,recovery-code,passkey}` will do. Removing a
factor that would leave fewer than two ways in is `409 sign_in_methods_minimum`:
add the replacement first. Someone else's passkey id is `404`. Recovery codes
come back only when made, with `Cache-Control: no-store` — show them once.

**Changing the phone number**: `POST /auth/phone/request { phone }` on an elevated
session (by a code to the current channel or any factor) sends a code to the new
number; `POST /auth/phone/verify { phone, code, requestId }` returns the updated
user and spends the elevation. `400 phone_unchanged` for the same number;
`409 phone_in_use`, only after the code is proven, for a number another account
holds. The old number no longer signs in to this account.

Every sign-in to an existing account, and each of these changes, appears in
`GET /me/messages` (templates `auth.new_sign_in`, `auth.phone_changed`,
`auth.authenticator_added`, …) on every device.

---

## Privacy — the part that shapes the UI

Every read passes a per-record visibility check in the database. A record the
user may not see **returns 404, not 403**: a 403 would confirm it exists.

So: never write UI that says "you don't have permission to view this holding."
As far as this user is concerned, it does not exist. Say "we couldn't find that."

Totals come through the same filter. Two members of one household will see
different net worth figures, and **both are correct**. Do not describe either as
"the household total" in a way that implies the other is wrong or incomplete.

`POST /investments` returns `visibleToYou`. It is `false` when someone saves a
private record owned by another member — the record exists, and its author
genuinely cannot read it back. Say so; do not let it look like the save failed.

---

## Money

Decimal, never floating point. Parse into `BigDecimal`, not `Double`.

Amounts also arrive pre-formatted — `valueFormatted`, `netWorthFormatted`,
`netWorthInWords` — in Indian grouping (`₹1,76,875`, `Fourteen Lakh Ninety-Three
Thousand`). **Prefer the formatted strings.** They are computed once on the
server so a phone, a PDF and the family handbook cannot disagree, and the
grouping is not what `NumberFormat` does by default in most locales.

Negative amounts use U+2212 before the symbol: `−₹53,400`.

`valueBasis` says how a figure is known: `valued` (a recorded snapshot),
`at_cost` (what was paid), `custom_field`, or `unknown`. **Show it.** A value
displayed without its basis is a claim about the market that nobody made.

---

## Writes

- **Optimistic concurrency.** Writes carry the record's `version`. A stale write
  returns `409` with `currentVersion` — reload and let the user merge, never
  silently overwrite.
- **Client-supplied ids.** Creates accept `id`. Generate a UUID at capture time
  and keep it: an offline capture keeps its identity, and re-sending returns the
  same record rather than a duplicate. This is how offline-first works here.
- **Ownership shares must total 100.** So must liability `responsibilityPct` and
  nominee `sharePct`. Enforce it in the UI; the API will reject otherwise, with
  the running total in the error.
- Errors are `{ error: { code, message, details } }`. `code` is stable and safe
  to branch on. `message` is written for a person and safe to show as-is.
  `details.fields` maps field names to messages for inline form errors.
- **A request the server cannot read** is a 4xx, never a 500. A required body
  field that is missing or `null` is `400 validation_failed` with
  `details.fields.<name>: "This is required"` (a nested field by its path,
  `owners[0].memberId`). A body that is not JSON, has a field of the wrong type,
  or is empty is `400 malformed_request`. A path or query parameter that does
  not convert (a household id that is not a UUID), or a required query
  parameter or upload part that is missing, is `400 malformed_request` with
  `details.parameter`. A body in a content type the endpoint does not read is
  `415 unsupported_media_type`. None of these repeats the value that was sent;
  they are programming errors in a client, so log them, don't retry them.

---

## Capture is data-driven

`GET /households/{id}/taxonomy` returns categories, types, and each type's
`schema`:

- `schema.common` **relabels first-class columns** for that type — a fixed
  deposit's "Principal" and a gold purchase's "Amount paid" are both
  `investedAmount`.
- `schema.fields` are type-specific values that go in `attributes`.
- `group` is `essential` (render on the form, capped at five) or `more` (behind
  "More details").

**Build the form from this, not from a hand-written list per type.** The server
validates against the same definition, so a generated form can never ask for
something the API will reject — and a new asset type becomes a data change
rather than an app release.

Unknown attribute keys are rejected, deliberately: a typo that lands in `jsonb`
looks saved and is never seen again.

---

## Phase 2: what the schema still cannot tell you

**Returns are allowed to be missing.** `Performance` carries nulls for
`xirr`, `cagr`, `absoluteReturn` and the gains, each with a `note` saying why —
"Showing what you paid. Add today's value to see the return." Render the note.
A null shown as `0%` is a claim the data does not support, and XIRR in
particular is null wherever the cash flows cannot produce an honest answer.

**Tax is informational and never cached.** Every response carries `disclaimer`;
show it with the numbers, not in a footer. `DeductionSource.basis` says whether a
figure came from recorded `transactions`, a `declared` amount, or an `estimated`
one — a meter that hides this is stating an opinion. The financial year runs
1 April – 31 March; `fy` accepts `2026-27`, `2026-2027` or `2026`.

**Nothing a parser produces is saved.** `POST /capture/parse-text` and
`/capture/parse-document` return proposed fields with the text each came from, so
they can be shown as editable chips and dropped individually. A document upload
is the exception: the file itself is stored, encrypted, whether or not anything
could be read from it — the proof is the durable part, and `extractedFrom` says
`pdf-text-layer` or `none`.

**Import: preview, then commit.** `POST /import/preview` proposes a column
mapping; `POST /import` runs it, `dryRun: true` by default. On a dry run read
`wouldImport`, not `imported` — `imported` is 0 there and reads as "nothing will
happen". A row whose cell was present but unreadable still imports, with a
message naming the cell; show those before the Import button, not after. Rows are
numbered as the spreadsheet numbers them, header included. Re-importing the same
file adds nothing.

**Templates belong to their maker.** `mine` is false for one someone shared —
usable, not editable, so do not offer an edit affordance. A template saved from a
record can never be shared more widely than that record; the refusal code is
`template_would_widen`. Applying one can fail on a field the type requires
(`attributes_invalid`, with `details.fields`) — offer the full form rather than a
dead end.

**Duplicate keeps the shape; rollover keeps the history.** A duplicate copies
type, institution, owners and nominees, and deliberately not the valuations or
transactions. A rollover marks the old record `matured`, keeps it, sets
`rolledFromId` on the new one and carries the goal allocations across. A record
superseded by a rollover stops counting toward net worth and goal funding — so
the two never both count — while remaining visible as history.

**Completeness is a measure of the records, not of the person.** Each check
carries the ids to fix, so offer one tap. The `score` is rounded down, so it is
100 only when every check is done; one gap among a thousand items is 99. When
`scoreEarned` is `false` (nothing recorded that the caller can see) there is no
number: `score` is 0 only because v1 requires an integer there. Render
`scoreExplanation` and the `nextStep` inviting a first record, and no
percentage — neither 0% nor 100%. (Before 2026-09-14 an empty household scored
100 and `scoreEarned` did not exist; known-issues 19.)

**"Not confirmed lately" on the dashboard is the "Still true?" clock.** The
`not_verified` attention item counts the holdings `still-true` considers due
(per-type periods, key dates, snoozes; docs/21 §6), not a flat six months.

---

## Phase 3 and 4: the parts that decide who sees what

**Three kinds of outsider, and they behave differently.** A *guest link* has no
login: the token is the credential, `GET /api/v1/share/{token}` is
unauthenticated, and the payload is one slice, read-only, until it expires. An
*emergency contact* is a member who asked and waited. An *advisor* is a member
whose role means household visibility never reaches them — they see only records
explicitly shared with them, and a `403` on a write is the expected answer, not
an error to report.

**A share tells you what is inside before you send it.** `scopeNote` on the
creation response says how many records, and how many of those are private to
the sharer — the family handbook deliberately includes those. Show it. The `url`
is returned exactly once, because the server keeps only a hash; a lost link can
be withdrawn and replaced, never recovered.

**An expired link and a withdrawn one answer identically.** Do not write UI that
distinguishes them: telling them apart would confirm a link once existed.

**Emergency access is a state machine with a clock *and* a silence.** `status`
is `waiting`, `open`, `vetoed`, `withdrawn` or `ended`, derived from timestamps
rather than stored — so a client should re-read rather than cache it, and
`secondsUntilUnlock` renders a countdown honestly. The window also stays shut
while `subjectHasBeenActive` is true: using Almira after somebody asked about
you is the plainest possible statement that you are reachable, and it stops the
clock without you having to understand what a veto is. Render `explanation`,
which says which of the two is happening. Only the subject may veto; only the
requester may withdraw. When a window is open, the ordinary
endpoints simply return more rows: there is no separate "emergency mode" API,
which is exactly why nothing can forget to apply it.

**The nominee-versus-will flag is derived from two records**, so it appears only
when both are visible to you. Render `explanation` as written — it states the
difference and deliberately does not say which side is wrong.

**Zero-knowledge fields are opaque and the server means it.** `PUT
/e2e/values/…` takes base64 and returns it unchanged; the server can tell you
nothing about the contents, cannot search or sort them, and rejects anything
that is not plausibly ciphertext. A client that has not unlocked should render a
sealed field as locked rather than blank. The scheme — PBKDF2 parameters,
envelope layout, AAD construction — is in [Doc 12](../12-end-to-end-encryption.md)
and the backend test implements the client half in Kotlin, which you can copy.

**Where the original is, and who holds the key, are two sealed values per
record.** Seal them with `PUT /e2e/values/{recordType}/{recordId}/original_location`
and `…/key_holder` for `investment`, `liability`, `account`, `document` and
`estate_document`. `GET /where-and-who` returns every record you can see with
both slots as ciphertext, plus `sealedByMe`. A value somebody else sealed will
not open with your key, so say so rather than calling it corrupt. Search is
yours to do on the device after unlocking, because the server cannot. Overwriting
another member's value answers `409 sealed_by_someone_else`. Since V28 a
ciphertext must be at least a 33-byte envelope with version byte 1. See
[Doc 20](../20-where-and-who.md).

**Recovery copies are a second wrapped copy of the content key, and nothing
more.** `PUT /e2e/recovery/recovery_key` or `…/recovery_shares` stores a wrap
the client made under a key derived (HKDF) from a secret it printed; the server
never receives the code, a share or the secret. Creating, replacing and removing
a copy answer `403 step_up_required` until the session has stepped up.
`POST …/{kind}/practice` records that the owner opened it on the device and
changes nothing else. `GET /e2e/recovery/members/{memberId}` returns someone
else's copies only to the person holding an open emergency window on them, and
`404` to everyone else. **One rule reaches the existing `PUT /e2e/key`:** while
copies exist, the body must carry `contentKeyId` for the same content key, or
the write answers `409 recovery_copies_would_break` and nothing changes. A
rotation that sends it keeps every copy working; a client that does not know the
field can still rotate a key that has no copies. The scheme — code format,
Shamir over GF(256), HKDF info, key id — is [Doc 12 §10](../12-end-to-end-encryption.md#10-recovery),
with the client half in Kotlin in `RecoveryReference.kt`.

**A sealed line is never blank.** `sealed` on each handbook entry and
instrument, and `access` on each where-and-who value, say who sealed it and who
holds a recovery sheet or share: render `sentence`, or build your own from the
fields. Only someone who can see the value already gets the line.
`GET /continuity/readiness` adds `sealedAccess` — how many sealed locations
someone other than the sealer could open — which never changes `score`.

**"Still true?" is a list, a yes and a later.** `GET /still-true` returns the
records that are due now and that the caller owns or holds. Each one carries a
`reason`: `period` when it has not been confirmed for `periodMonths`, or
`key_date` when a maturity, renewal or end date has passed since the last
confirmation. `POST /still-true/{recordType}/{recordId}/confirm` answers it.
`…/snooze` with `{"until"}` puts it off: the date must be after the household's
today and within a year. Someone who can see a record but does not own it gets
404 on both, the same as for a record that does not exist. A value refresh (a
valuation, a loan balance) also counts as a confirmation. An edit or a visibility
change does not. See [Doc 21](../21-still-true.md).

**Handover readiness is a number only when it is earned.**
`GET /continuity/readiness` returns `score` (0–100, rounded down) and
`scoreExplanation`. `score` is left out, like any null field, when nothing
counted applies, and the explanation then says why. Render the sentence, never a
0. `complete` is true only when there are no gaps and nothing is left out of the
family summary; while `leftOutCount` is above 0 the score is at most 99, and
`leftOut` names those records so the client can show them as a to-do.
`checks` always lists all four
checks in order, and `gaps` names each missing item with a `reason` to
translate, an English `fix`, and `recordType`/`recordId` as the deep link. It is
computed as the caller, so two members can get different answers and both are
right. See [Doc 22](../22-handover-readiness.md).

**Money is stored in the currency it is in.** `currency` is per record; the
dashboard converts into the household's base currency and reports what it could
not convert in `unconverted` — render that, because the total is deliberately
smaller than the truth rather than wrong. A conversion carries `rate`, `rateAsOf`
and `rateSource`; a figure without them is a number pretending to be a fact.

**Providers are in sandbox until a credential says otherwise.**
`GET /connect/providers` reports each one's `mode` and the list of what would
make it live. Anything imported arrives at the household's default visibility,
never wider, and an inbound WhatsApp message is a *proposal*, never a saved
record.

---

## Endings and changes: closing, leaving, succession, a memorial, turning eighteen

Everything here waits before it acts, and the wait is in the database, not the
client: thirty days to close an account, seven to leave a household, a week after
a memorial before a successor can claim. Render the dates the server returns and
never compute your own. See [Doc 05 §12](../05-security-and-privacy.md#12-the-end-of-an-account-and-the-changes-in-between).

**Previews change nothing, and every title in one is the caller's own.**
`GET /me/closure/preview` returns `erased`, `stays`, `blockers`, `waitDays` and
`retention`; `GET /households/{id}/departures/preview` returns `goesWithYou`,
`staysWithHousehold`, `joint` (each with `otherHolders` and the leaver's
`decision`), `blockers` and `sealedFieldsThatStayBehind`. Show both columns side
by side, show every blocker as the thing to do next, and show `retention` as
written — it is the legal sentence.

**The starts need a step-up** (`403 step_up_required`): `POST /me/closure`,
`POST /households/{id}/departures`, `POST /households/{id}/members/{memberId}/memorial`,
`POST /households/{id}/successor/claim`, and `GET /me/export`. A download cannot
read an error body easily, so check `GET /auth/step-up` before starting one.
`409 closure_blocked` and `409 departure_blocked` carry `details.blockers`.

**Undo is a first-class action.** `POST /me/closure/cancel` keeps the account;
`POST /households/{id}/departures/{departureId}/cancel` is allowed to whoever
started it (`canCancel` says whether that is the caller). An admin-started
departure cannot be withdrawn by the person leaving, but they still choose
`privateRecords` (`take` or `export_and_erase`) and each joint `decision`
(`stays` or `take_my_share`) with `PATCH`. Anyone else sees only who is leaving
and when: `privateRecords` and `decisions` are left out for them.

**A memorial makes an account read-only in that household.** `MemberResponse`
gains `passedAway` and `memorialisedAt`; `HouseholdResponse` gains `readOnly`,
which is true for the caller when they are the one marked. Every write under
that household then answers `403 memorial_read_only`, except
`DELETE /households/{id}/members/{memberId}/memorial` by that person — the "I'm
here" that undoes it. Messages to a memorialised person stop, apart from the one
telling them they were marked.

**Removing a member is for people without a login.** `DELETE /members/{id}` on
someone with their own login answers `409 member_has_login`: ask them to leave
instead. `409 member_has_holdings` now carries `details.visible` and
`details.hidden`; the hidden ones are private to whoever recorded them, those
people have been told, and the response never names a record.

**A successor is visible to two people.** `GET /households/{id}/successor`
answers `named: false` to everyone but the owner and the person named. `canClaim`
is true only when the claim would succeed; `explanation` says why not.

**Coming of age is a notice, then a welcome.** `GET /households/{id}/coming-of-age`
lists members who turned or are turning eighteen this month; the next step for
one with `hasLogin: false` is the ordinary invitation with their `memberId`.
`GET …/coming-of-age/welcome` is `404` for anyone but that young adult, and
`POST` with `choices` sets each record `private` or `household` through their
own row-level security.

**`GET /me/export` is `application/zip`**: `almira-everything.pdf`,
`almira-everything.json`, `csv/…`, `documents/…` and `README.txt`. It holds what
the caller can see and nothing else, account numbers to the last four digits,
and sealed values as ciphertext.

---

## Measurement

Almira counts a closed list of twelve events per day and nothing about who
([What we measure](../what-we-measure.md)). Three endpoints, all for the
signed-in caller:

- `GET /api/v1/me/measurement` → `{ "optedOut": false, "events": [ …12 codes ] }`.
- `PUT /api/v1/me/measurement` with `{ "optedOut": true }` turns it off for this
  person; `false` turns it back on. `optedOut` is required.
- `POST /api/v1/measurement/abandoned` with `{ "form": "capture", "step": 1..3 }`
  when the add form is closed without saving. `204`, whether or not it was
  counted (an opted-out caller gets the same answer). Fire and forget: never
  block closing a form on it.

## A cold-start lesson, learned the expensive way

An early build flagged **every brand-new record** as "not confirmed in over six
months", because the freshness check treated an unset `lastVerifiedAt` as
overdue. A new user's first sight of the product was a list of problems they had
not had time to have.

It was caught by seeding realistic data and *looking at the screen* — no unit
test would have. Two things follow for the app:

1. **Empty and attention states must greet a new user with a next step, not a
   list of faults.** "Add your first holding — a fixed deposit takes twenty
   seconds", not "3 holdings need attention". An attention list that cries wolf
   on day one is one nobody reads on day ninety, and this product's whole value
   is that people keep it current (docs/08 §5).
2. **Run the app on a device with realistic data, early and often.** Seed it —
   `scripts/demo-data.sh` does this for the backend — and look. The bugs that
   matter most here are the ones that only appear when the screen is full.

---

## Where to look

| | |
|---|---|
| Browsable docs | `http://localhost:8080/docs` |
| Contract | [`openapi-v1.json`](openapi-v1.json) |
| Design system | `backend/src/main/resources/static/app/tokens.css` — colours, type scale, spacing, motion, dark mode. Each token maps to one in the Compose theme. |
| A working client | `backend/src/main/resources/static/app/` — small, framework-free, and it exercises every flow the app needs. |
| Product docs | [`docs/`](../README.md) |

---

## Changelog

Corrections made under the freeze rule at the top of this file: responses the
contract never declared, that reported a server fault for input the server
rejected, corrected to a documented 4xx. Newest first. Additive changes to the
contract itself are in `openapi-v1.json` and are not listed here.

### 2026-09-14 — a DigiLocker completion needs the state it was started with

`POST /households/{id}/connect/digilocker/complete` takes `state` beside `code`
(the value `…/digilocker/start` returned). A completion whose `state` is
missing, not the one handed out, handed to a different person, or older than
fifteen minutes answers `400 connect_state_mismatch`, and the code is not
redeemed. A session that has run out answers `400 connection_expired` on
`…/documents` and `…/import`. DigiLocker is `disabled` by default, and no client
has ever called these, so nothing that shipped is affected (known-issues 10).

### 2026-09-14 — removing a member who has their own login is refused

`DELETE /households/{id}/members/{memberId}` on a member with a login used to
answer `204` and soft-delete the member row while leaving their membership
active — a person who could still sign in to a household that no longer listed
them. It now answers `409 member_has_login`; the way out is a departure
(`POST /households/{id}/departures` with `memberId`). `409 member_has_holdings`
gains `details.visible` and `details.hidden`, and counts records the caller
cannot see, which it used to count as zero.

### 2026-09-13 — a request the server cannot read is a 4xx

Every one of these used to answer `500 internal_error` ("Something went wrong on
our side") and write an ERROR log. The error envelope is unchanged; nothing the
caller sent is repeated in the new responses.

| Input | Was | Now |
|---|---|---|
| A required body field missing (e.g. `phone` on `POST /auth/otp/verify`) | `500 internal_error` | `400 validation_failed`, `details.fields.<name>: "This is required"` |
| A required body field sent as `null` | `500 internal_error` | `400 validation_failed`, `details.fields.<name>: "This is required"` |
| A required field missing inside a list item | `500 internal_error` | `400 validation_failed`, `details.fields["owners[0].memberId"]` |
| A body that is not valid JSON (unterminated, bad token) | `500 internal_error` | `400 malformed_request` |
| A body field of the wrong type (an array for a string, a non-UUID for a UUID) | `500 internal_error` | `400 malformed_request` |
| An empty body where one is required | `500 internal_error` | `400 malformed_request` |
| A body in a content type the endpoint does not read (text to a JSON endpoint, JSON to an upload endpoint) | `500 internal_error` | `415 unsupported_media_type` |
| A path variable that does not convert (a household id that is not a UUID) | `500 internal_error` | `400 malformed_request`, `details.parameter` |
| A query parameter that does not convert (`limit=abc`) | `500 internal_error` | `400 malformed_request`, `details.parameter` |
| A required query parameter missing (`q` on search) | `500 internal_error` | `400 malformed_request`, `details.parameter` |
| A required multipart part missing (`file` on a document upload) | `500 internal_error` | `400 malformed_request`, `details.parameter` |
