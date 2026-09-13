# Building a client against Almira v1

The contract is [`openapi-v1.json`](openapi-v1.json) — 109 paths, 152 operations,
164 schemas. Generate a typed client from it; do not hand-write one.

**v1 is additive-only.** New endpoints and new optional fields may appear; nothing
will be removed, renamed or retyped. A breaking change goes to `/api/v2` and v1
stays as it is for as long as a released app depends on it. `OpenApiContractTest`
fails the backend build if that promise is broken, so a client can rely on it.

Everything below is behaviour the schema cannot express. It is short, and every
line of it will otherwise be learned the hard way.

---

## Authentication

```
GET  /api/v1/auth/otp/channels                → { channels: ["phone"] | ["email"] | ["phone","email"] }
POST /api/v1/auth/otp/request        { phone }        → requestId, expiresInSeconds, resendAfterSeconds, channel
POST /api/v1/auth/otp/verify         { phone, code }  → accessToken, refreshToken, isNewUser, user
POST /api/v1/auth/otp/email/request  { email }        → the same challenge shape
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
email request **never reports a delivery failure** — `otp_delivery_failed`,
`otp_provider_unavailable`, `otp_service_unavailable` and `otp_delivery_delayed`
are phone-only answers at sign-in. The send happens after the response. The
one exception is development, where `developmentCode` is present only for an
allowed address.

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
| 422 | `otp_delivery_failed` | Nothing was delivered. Let the person correct the number and ask again straight away. |
| 503 | `otp_provider_unavailable` | Nothing was sent. Suggest trying again in a few minutes. |
| 503 | `otp_service_unavailable` | Our account problem. Show the message; do not suggest checking the number. |

Connecting DigiLocker or the Account Aggregator fails the same four ways, as
`provider_timeout` (504), `provider_unavailable` (503), `provider_rejected` (422)
and `provider_account_unavailable` (503), with `details.provider`. The WhatsApp
webhook still answers 200 and sets `replyFailure` to one of those codes.

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
gets `400 no_step_up_channel` (this used to be a 500 for email-only accounts).
Unlike email sign-in, a step-up email that cannot be sent **does** answer with
the failure codes above — the caller already owns the address, so there is
nothing to enumerate.
Elevation belongs to the **session**, so confirming on a phone does not unlock a
browser someone else is sitting in front of.

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
carries the ids to fix, so offer one tap. An empty household scores 100 with a
`nextStep` inviting a first record; do not render 0%.

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
