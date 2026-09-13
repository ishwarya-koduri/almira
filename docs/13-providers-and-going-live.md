[‹ Index](README.md)

# 13 · Providers — what is built, and exactly what flips each one live

Five things Almira will eventually want from someone else. Every one needs an
account, a registration or a regulator's approval, and none of that can be
arranged from a laptop — so each is built now as **an adapter behind an
interface with a sandbox that really works**, wired through the app and covered
by tests.

The part that can be got wrong is therefore already proven: consent states,
duplicate handling, where an imported record's privacy comes from, what happens
to a document once it arrives. What is left for the day the accounts exist is
the transport.

> **The go-live checklist is [GO-LIVE.md](../GO-LIVE.md).** It records, per
> provider, the interface contract in code terms, what the partner and the owner
> must supply, the ordered steps and smoke test to flip it live, and what is
> **not watched failing** — which today is every provider, because no live
> adapter exists. This document is the design those pages build on.

> Nothing here is switched on by default. `GET /households/{id}/connect/providers`
> reports each provider's mode and the exact list below, so whoever deploys this
> can see what remains rather than reading it here.
>
> **Account Aggregator is cut from v1** (owner's decision, 2026-09-13) and is
> `disabled` by default — see §2.

---

## The switch

Every provider reads one property, with three values:

- **`sandbox`** — the in-process sandbox implementation. The default for every
  provider but `aa`.
- **`live`** — the real adapter, which requires its credentials. Refuses to
  start today, because no live adapter exists.
- **`disabled`** — not offered on this server, and a normal state rather than an
  error. The application starts; `connect/providers` reports `mode: DISABLED`
  and `connected: false`; every call to a disabled DigiLocker, Account
  Aggregator or WhatsApp answers **409 `provider_disabled`** with
  `details.provider`, before anything is written or called. A disabled `sms`,
  `email` or `push` channel has no sender: notifications skip it and record no
  row for it, and email sign-in answers `otp_unavailable`. The default for `aa`.

Anything else refuses to start, with a sentence. That includes `off`, which was
the old name for this state: it passed the startup check and then crashed the
application for three providers (known-issues 11), so it is refused by name and
the message says to write `disabled`. One spelling, so the configuration, the
startup check and the status endpoint all use the same word.

409 rather than 503 or 404: 503 is what a provider *outage* answers, and clients
may retry it; this will not change by retrying. 404 is how this API says "no
such household, or not yours". The refusal is about how the server is
configured, and clients branch on the code.

```yaml
almira:
  providers:
    digilocker: { mode: sandbox }   # disabled | sandbox | live
    aa:         { mode: disabled }  # cut from v1
    whatsapp:   { mode: sandbox }
    sms:        { mode: sandbox }
    email:      { mode: sandbox }
    push:       { mode: sandbox }
```

Credentials never live in this file. They come from the environment, and from a
secrets manager in anything resembling production.

---

## 1 · DigiLocker — documents

**Interface** `DocumentVaultProvider` · **Sandbox** `SandboxDocumentVault`

The sandbox returns three documents an Indian household usually has there — a
PAN card, an LIC policy, a driving licence — as **real PDFs with a text layer**,
so the whole path runs: consent, list, fetch, store encrypted, read the text,
propose fields. A test asserts the policy number is not readable on disk
afterwards, exactly as for an uploaded file.

**To go live**

1. Register on the DigiLocker partner portal (NeGD) and complete organisation KYC.
2. Obtain `client_id` and `client_secret`.
3. Register a redirect URI on a public HTTPS host.
4. `ALMIRA_PROVIDER_DIGILOCKER_MODE=live`, plus `..._CLIENT_ID` / `..._CLIENT_SECRET`.

Research dated 2026-09 adds three blockers this list used to omit: access is
through API Setu and needs a **GSTN-verified** entity, there is **no separate
sandbox**, and the **server must be in India**. The full checklist, and four
gaps in `ConnectService` a live adapter cannot paper over, are in
[providers/digilocker.md](providers/digilocker.md).

**Then implement**: the OAuth exchange and the issued-documents API against
their spec. The sandbox already defines the shape.

---

## 2 · Account Aggregator — holdings

**Interface** `AccountAggregatorClient` · **Sandbox** `SandboxAccountAggregator`

Consent is requested, not assumed: the sandbox returns `PENDING` and only turns
`ACTIVE` when asked again, because a flow that skips consent in testing is a
flow nobody has tested. Fetching before approval is refused. The payload is
shaped like real FI data — masked account numbers, a value, an as-of date, and
never a credential, which is the whole point of the AA framework
([Doc 05 §8](05-security-and-privacy.md)).

What arrives becomes ordinary records at the **household's own default
visibility**, with the provider's figure recorded as a dated valuation rather
than a claim about today, and the provider's own fields (IFSC, folio, units) as
record-level custom fields. Re-importing recognises what is already there by the
masked account number.

**To go live**

1. Register as an FIU with an Account Aggregator (Sahamati onboarding).
2. Obtain a signed client certificate for the AA gateway.
3. Publish a purpose code and a consent template; have both approved.
4. `ALMIRA_PROVIDER_AA_MODE=live` plus the gateway URL and certificate paths.

Production FIU status needs an entity regulated by RBI, SEBI, IRDAI or PFRDA,
and even the Setu sandbox needs a Company PAN and GSTIN. **Cut from v1 — the
owner's decision, 2026-09-13, for exactly that reason.** `aa` defaults to
`disabled`: the three AA endpoints answer 409 `provider_disabled`, and the web
client leaves it out of Connected services because the server reports it
`DISABLED`. The interface, the sandbox and its tests are kept for a future
regulated partner; `ALMIRA_PROVIDER_AA_MODE=sandbox` brings the sandbox back.
Details in [providers/account-aggregator.md](providers/account-aggregator.md).

**Never**: scraping, credential collection, or "just give us your net-banking
password". The AA network exists precisely so that nobody has to.

---

## 3 · WhatsApp — capture

**Interface** `WhatsAppGateway` · **Sandbox** `SandboxWhatsAppGateway`

The sandbox parses Meta's webhook shape, so the live parser is this one. An
inbound message becomes a **proposal**, exactly like typing into quick add —
never a saved record. Texting a number must not be able to write to a registry.

The sandbox **does not check the signature and says so**, in the provider status
and in its own log line. An inbound webhook that trusts its caller is an open
door; do not expose this endpoint publicly in sandbox mode.

**To go live**

1. A Meta business account with a verified WhatsApp number.
2. A permanent access token, and the app secret for `X-Hub-Signature-256`.
3. Message templates approved by Meta (session messages are time-limited).
4. `ALMIRA_PROVIDER_WHATSAPP_MODE=live` plus token and app secret.

---

## 4 · SMS — one-time codes and reminders

**Interface** `ChannelSender` (`channel = "sms"`) · **Sandbox** `SandboxSmsSender`

In development — `ALMIRA_ENV=development`, set explicitly — the OTP is returned
in the response and printed to the log, which is why the whole sign-in flow works
with no provider at all. In any other environment that sender refuses with 503
`otp_unavailable` before a code exists ([Doc 17 §5](17-deploying.md)). The sandbox sender
records that a message would have gone, and never its body: an SMS body carries
the amount and the institution, and logs are the least protected thing here.

**To go live** — India-specific, and the part people forget:

1. A provider account (Twilio, MSG91, Kaleyra…).
2. **DLT registration on an Indian telecom operator's portal**: register the
   entity, the sender ID, and *every message template*. Unregistered templates
   are dropped by the operator, silently.
3. Map each `template` to its registered DLT template id.
4. `ALMIRA_PROVIDER_SMS_MODE=live` plus provider credentials and the sender id.

The full checklist — GST before DLT, the release keystore before the template,
and five gaps in the current code — is [providers/sms.md](providers/sms.md).

### The one-time-code template has a fifth requirement

The Android app fills the code in by itself, using **SMS Retriever** — Play
Services hands the app one message and no SMS permission is involved anywhere.
The price is that the message has to identify itself, and the registered DLT
template must carry it:

```
<#> 123456 is your Almira code. FkcDLUV0sv8
```

- The last token is an **eleven-character hash of the signing certificate**, so
  it is different for the debug build, for a locally signed release, and again
  for the build Google Play re-signs. Getting it wrong fails **silently**: the
  message arrives, looks perfect, and autofill simply never happens.
- The app computes its own and shows it in the development banner on the code
  step, so the value to register is read off the build rather than derived by
  hand. For a Play-signed release, take the certificate from Play Console's app
  signing page.
- `<#>` is optional and worth keeping: it lets a messaging app hide the message.
- The whole body must stay within 140 bytes.

The **DLT template must be registered with that hash in it**, which means a
release signed by a new key needs a template change and a re-registration. Plan
that with the key, not after it.

iOS needs none of this: `textContentType = .oneTimeCode` makes the keyboard
offer the code, and the message needs nothing special.

---

## 5 · Email and push

**Interfaces** `ChannelSender` (`email`, `push`) · **Sandboxes** `SandboxEmailSender`, `SandboxPushSender`

**Email to go live**: a sending domain with SPF, DKIM and DMARC published; a
provider account (SES, Postmark, Resend); a verified from-address;
`ALMIRA_PROVIDER_EMAIL_MODE=live`.

### Sign-in codes by email — the closed alpha

The owner's route for the alpha: one-time codes by email, to allowlisted
testers, with email as the **only** sign-in so nobody ends up with two accounts.
It is built on this channel and on the failure contract below, with no email
provider chosen. What it still needs is what email needs: a live email adapter
(none exists, and `mode: live` refuses to start until one does), the checklist
above, and a deployment that passes the variables below through to the
application.

```
ALMIRA_SIGN_IN_CHANNELS=email
ALMIRA_ALPHA_EMAIL_ALLOWLIST=asha@example.com,ravi.k+alpha@example.com
ALMIRA_PROVIDER_EMAIL_MODE=live        # once a live email adapter exists
```

- **Switch.** `almira.auth.sign-in-channels` is `phone`, `email` or both;
  `phone` when unset, so development and every suite are unchanged. A value it
  does not understand, an empty list, a malformed allowlist entry, or email
  with an empty allowlist all refuse to start (`SignInChannels`). The startup
  log says how many addresses are listed, never which.
- **Sender.** `ChannelEmailOtpSender` hands the code to whichever email
  `ChannelSender` the mode selected, with the address as `recipientHint` and
  the code only in the body. It can deliver when the channel is `live`, or when
  it is the sandbox **in development** — the same rule as the log SMS sender.
  Anywhere else (today: every non-development server, since no live email
  adapter exists) the request is `503 otp_unavailable` before a code exists.
  Nothing is written to `outbound_messages`: a sign-in code is not a
  notification, and a table of codes sent is a table worth stealing.
- **Same hardening as phone**, in `OtpService`, under keys of its own
  (`otp:email:…`, `otp:rate:email:…`) and its own HMAC key
  (`almira/otp-code/email/v1`), so nothing stored for an address verifies for a
  number. The per-address cap is `max-per-hour`; the per-network request cap and
  the per-network wrong-code cap are **shared** with phone, so alternating
  channels does not double them.
- **No enumeration.** An address off the allowlist gets a *decoy*: the same
  cooldown, counts, stored challenge, request id, lifetime and response, with a
  random stored value no code matches and no email sent. For that to hold, an
  allowlisted address's email is sent **after** the response
  (`OtpDelivery.DEFERRED`) — otherwise the answer would take a provider round
  trip longer. The send is still one interactive attempt through
  `ProviderCalls` (`almira.otp.send-timeout`, no retry, the
  `PROVIDER ACCOUNT PROBLEM` ERROR line — see "Interactive and background"
  below).
- **A failed send is not silent** (owner's decision, 2026-09: *the tester sees
  "we couldn't send the code" — silence is indistinguishable from a code that
  never arrived*). The code step polls
  `GET /api/v1/auth/otp/email/delivery/{requestId}`: `sending`, then `sent`,
  `delayed` (resend open now) or `failed` with the reason
  (`otp_delivery_failed`, `otp_provider_unavailable`,
  `otp_service_unavailable`; resend open now). The web client says it in
  English, Telugu and Hindi; the native app in English, as the rest of it.
  Only `sent` is shown as sent: a status still `sending` after 30 polls, or
  one that cannot be read, is shown as delayed with resend open.
  A failure does to the challenge, cooldown and counts exactly what a reported
  send does ("What a failed send does to a one-time code", below): nobody is
  locked out, or charged a request, by our failure.

  **How a decoy stays indistinguishable.** A decoy's status is settled by the
  same code on the same executor as a real send, with one line different: in
  place of the provider call it waits as long as the **last real email send**
  took and ends the way that send ended (`otp:email:provider-weather` in Redis,
  shared by every instance). So with the provider healthy both say `sent`; with
  it timing out, unreachable or out of credit both say `delayed` or `failed`
  with the same reason, lift the same cooldown and give back the same count.
  Every outcome, real or decoy, is applied on a whole-second tick from the
  request, so a real send's variable latency and a decoy's replayed one land
  on the same tick. `EmailSignInApiTest` holds a listed and an unlisted address
  to the same request, the same settled status (status, fields, headers), the
  same settling time class and the same answer to asking again — with the
  provider healthy, unavailable, out of credit and timing out.

  **What is still distinguishable, plainly:**
  1. **A rejection.** When the provider refuses one address synchronously
     (a suppression list, a mailbox it knows is dead), the listed address
     reports `failed` / `otp_delivery_failed`; an unlisted address never does,
     because a rejection is about an address, not the provider. Seeing
     "couldn't deliver to that address" therefore means *listed, and
     undeliverable*.
  2. **A change in the provider.** Decoys replay the last real send, and only a
     real send updates what they replay (kept a day). So after the provider
     changes state (goes down, comes back, runs out of credit, starts timing
     out) the window is not a moment: it stays open until some listed address
     is next sent a code, however long that is. Inside it, every probe is a
     clean bit — an unlisted candidate reports the old state, a listed one the
     new — and the first listed candidate probed is itself the send that closes
     the window. So per provider transition a prober can rule out as many
     unlisted candidates as their per-network allowance pays for, and confirm at
     most one listed address, unless a real tester signs in first. They cannot
     cause a transition, but an outage can be public (the provider's status
     page), so they need not see it through a listed address.
  3. **Latency on a tick boundary.** A real send whose latency happens to
     straddle a whole second can settle one tick apart from a decoy replaying
     the previous send's latency. It takes many requests and a provider
     latency near a second to see, and each request costs the prober their
     per-network allowance.
  4. **A fresh Redis**, or a day without any real send: decoys assume a
     healthy provider answering in 300 ms until one real send is seen.
- **Taking a tester off the list ends their access** (owner's decision,
  2026-09). The list is configuration, so removal is a restart with the
  address gone. `AlphaAllowlistAccess` then enforces it three times: at startup,
  before the web server takes a request, it revokes every live session of an
  email-only account that is no longer listed — refresh tokens in the database,
  the session id in the revocation cache so its access token stops too — and
  audits each (`auth.session_ended_not_allowlisted`, `via: startup`); on every
  authenticated request it checks the account (cached a minute; the list cannot
  change while a server runs) and revokes on the spot (`via: request`); on
  refresh, uncached (`via: refresh`). A session therefore outlives a removal by
  nothing beyond the restart. In a rolling deploy an old server still holding
  the old list can sign the tester in until it stops; any new server refuses
  that session on its first request. Accounts with a phone number are never
  touched, and on a server without email sign-in the check does not run.
  `AlphaAllowlistRemovalApiTest` seeds sessions before its server starts and
  asserts the starting server ended them.

  **Why the list stays in configuration.** Moving it to the database would let
  it change without a restart, and would need what that implies: an operator
  surface to edit it (none exists, and one is itself something to attack), an
  audit trail of edits, and the per-request check reading the table instead
  of memory. For a closed alpha of a handful of testers a restart is minutes and
  happens anyway on deploy, and with the startup revocation there is no window
  in which a removed tester keeps access. Revisit if the list starts changing
  more often than the deploys do.
- **Step-up** goes to the account's email when phone is not offered or the
  account has no number, and reports failures normally
  (`OtpDelivery.REPORTED`): the caller already owns the address.
- **Development echo** still applies to email in development, and only for a
  listed address. That one difference is development-only by construction.

### Push

**Push to go live**: an FCM project and service-account JSON; an APNs key for
iOS; and — the piece that does not exist yet — **device token registration**,
which needs the native app. Until then push has nowhere to go, which is why it
is last. The APNs and FCM requirements, and the shape device-token
registration would need, are in [providers/push.md](providers/push.md).

---

## What "the stand-in" means now

Notifications remain a stand-in, but not an unverifiable one. Every outbound
message is recorded in `outbound_messages` — channel, template, title, status
(`queued` until the background worker has sent it), which way it failed and
after how many attempts, never a body — so the in-app
list (`GET /me/messages`) works today, a test can assert that the person who
should have been told was told, and switching a channel on changes where a row
goes rather than whether it exists.

Reading that table is restricted to the person the message was for. The log of
what somebody was told is as personal as what it was about.

---

## Interactive and background

Every provider call is one of two kinds, decided by one question: **is a person
waiting for it?** (Owner's decision, 2026-09.)

| | Interactive | Background |
|---|---|---|
| **What** | One-time codes by SMS and email — sign-in and step-up. DigiLocker and Account Aggregator connect calls. A WhatsApp capture's reply. | Every notification on `sms`, `email` and `push`: reminders, still-true nudges, emergency-access notices. |
| **Where it runs** | In the request. | `NotificationOutbox`, a worker on the owner connection, after the request or sweep has committed a `queued` row. |
| **Attempts** | One-time codes: **exactly one**, under `almira.otp.send-timeout` (5 s), whatever the provider's `max-attempts`. Connect calls: the provider's policy, as described below. | The provider's policy: `timeout`, `max-attempts`, `retry-backoff`. |
| **Retry** | The person's resend button. | The worker, within `max-attempts`; see the idempotency rules below. |

### One-time codes are interactive

The person is looking at the screen, so a code request is one attempt with its
own short timeout and no backoff (`ProviderCalls.callOnce`). It is never retried
by the server. A timeout means *no answer*, not *not delivered*: retrying one
was how a single request could become two or three billed texts
(known-issues 21). With one attempt that cannot happen, so there is nothing to
de-duplicate.

`almira.otp.send-timeout` (`ALMIRA_OTP_SEND_TIMEOUT`, default `5s`, bounded to
more than zero and at most 15 s at startup): a gateway's send API answers when
it has *accepted* a message, normally well under a second, so five seconds is
several slow answers long and still short enough that the person sees
"delayed" and a working resend button while they are looking. The provider's
own `timeout` (10 s for SMS) is not used for a code.

What one failed attempt leaves is in "What a failed send does to a one-time
code" below. In short: a timeout keeps the challenge (the late text works), lifts
the cooldown (resend at once, `resendAfterSeconds: 0`), and keeps both hourly
counts (repeated timeouts still reach the caps). A resend replaces the
challenge, so a code that arrives late after it no longer works.

Connect calls stay in the request and under the provider's policy: their
retries are few, both non-repeatable operations already refuse to retry a
timeout, and the person is waiting for the answer either way. A WhatsApp reply
also stays in the request, because whether it went is part of the capture's
answer (`replyFailure`); a live Meta webhook's own response deadline is a reason
to revisit that before WhatsApp goes live (known-issues 21).

### Notifications are background work

Nobody is sitting in front of a reminder. `RecordingNotifier.deliver` writes the
`in_app` row as `sent` (it is the database, not a provider) and one `queued` row
per configured channel, in the caller's transaction, and returns. It never calls
a provider. The worker is woken after the commit and also polls every
`almira.outbox.poll-interval` (`PT2S`); it claims queued rows `for update skip
locked`, sends each through `ProviderCalls` with the provider's policy, and
records `sent` or `failed`, the failure kind and the attempts on the same row.
The body waits in `outbound_message_bodies`, which the runtime role cannot read,
and is deleted when the row finishes. A request or sweep that notifies no longer
waits on any provider (`NotificationOutboxTest` holds a hanging push to that).

The worker uses the owner data source by explicit qualifier: on the runtime
pool, row-level security shows a user-less worker no rows, and it would do
nothing, successfully, forever. `NotificationOutboxTest` pins the pool and role,
and shows a worker built on the runtime pool finding nothing.

### Idempotency keys, and what they guarantee

Every queued row carries `idempotency_key`, unique in the table, naming one
logical message on one channel: `<logical>:<channel>`. The logical part is
`reminder:<reminder id>:<date it fires for>:<user>` for a reminder — so a sweep
that runs again before the reminder is marked (a crash, a second server) queues
nothing new — and `<template>:<random>` for everything else, whose own
bookkeeping (the still-true nudge rows, the emergency request) already decides
whether there is a message. A second enqueue with the same key writes nothing.

The worker passes the key to the adapter on every attempt:
`ChannelSender.send(notification, recipientHint, idempotencyKey)`. Before calling
the provider it commits, for that row alone, a stamp of `send_started_at` and
a lease that runs from that moment for longer than the provider's whole retry
budget. (A batch is claimed together, but a claim only reserves rows; each row
is stamped just before its own send, so rows a worker never reached are not
marked as possibly sent, and a slow batch does not eat the lease of the row
being sent.) So a worker that dies *after the
provider accepted a message and before recording it* leaves a row that says so.
What happens next is the channel's declared property,
`ChannelSender.honoursIdempotencyKey` — deliberately without a default:

| | Provider de-duplicates by key (`true`) | Provider does not (`false`) |
|---|---|---|
| **Promise** | **At-least-once to the provider, once to the person.** | **At-most-once.** |
| **A timeout** | Retried within `max-attempts`, same key. | Not retried. Recorded `failed`, `timeout`. |
| **A send cut off by a crash** | Sent again when the lease runs out, same key; the provider drops the repeat. | Never sent again. Recorded `failed`, `timeout` ("not confirmed — it may still arrive"). |
| **Cost** | Correctness rests on the provider honouring the key. | A message the dying worker had not yet handed over is lost. The in-app row still has it. |
| **Channels today** | `sms` and `email` sandboxes, which count deliveries per key and drop repeats. | `push` (FCM and APNs have no send-side de-duplication: a collapse id replaces a notification on screen, it does not stop a second arriving). |

**The limits, plainly.**

- "Never twice" for an at-least-once channel is only as true as the provider's
  de-duplication. **A live SMS adapter must pass the key to the provider** (a
  client reference or idempotency header it de-duplicates on) and may only
  declare `honoursIdempotencyKey = true` if the provider documents that it drops
  repeats — for how long, too: a provider that remembers keys for 24 hours is
  fine, one that remembers them for a minute is not, because the lease is
  longer. If the chosen provider cannot, the adapter declares `false` and SMS
  notifications become at-most-once. The same applies to a live email adapter.
- The key is per logical message. Two different reminders are two messages,
  and a still-true nudge a month later is a new message by design.
- The one row a worker had stamped as started when it died, but whose provider
  call had not really begun (it died in the few statements between), is to the
  next worker indistinguishable from one that sent. At-most-once channels lose
  that one message rather than risk a duplicate. Rows claimed in the same batch
  that the worker never reached are not stamped and are sent normally
  (`NotificationOutboxTest` "a worker that stops mid-batch…").
- A claimed but not-yet-started row whose batch ran slower than its claim lease
  can be taken by another server; the first worker's stamp then matches no row
  (it is bound to the claim token) and it leaves the row alone. The row being
  sent has a lease of `max-attempts × (timeout + 30 s) + 1 min` counted from its
  own start, and `ProviderCalls` enforces the timeout itself, so a live send is
  mistaken for a dead one only through a bug, not a slow provider or a long
  batch. Two live servers were not exercised together.
- One-time codes are not in the outbox and have no stored key: they are one
  attempt, which is the stronger guarantee.

---

## When a provider fails

Every call to every adapter above — the three notification channels, one-time
codes, DigiLocker, the Account Aggregator and WhatsApp replies — goes through
one policy, `ProviderCalls`. It is the only reader of each provider's
`timeout`, `max-attempts` and `retry-backoff` — which apply to everything except
a one-time code, whose single attempt is described in "Interactive and
background" above:

```yaml
almira:
  providers:
    sms: { timeout: 10s, max-attempts: 3, retry-backoff: 500ms }   # likewise for each
```

- **Timeout** is per attempt and enforced by `ProviderCalls` itself: the call
  runs on its own virtual thread and is interrupted when time is up. An adapter
  that hangs cannot hold a request for longer.
- **max-attempts** counts every attempt, the first included. `1` turns retrying
  off. Bounded to 1–10 at startup.
- **retry-backoff** doubles per attempt, with jitter (between half and all of
  the doubled delay), capped at 30s.
- Only a **timeout** or **unavailable** is retried. A rejection or an empty
  balance never is.
- Two operations must not happen twice, so a timeout on them is **not**
  retried: redeeming a DigiLocker authorisation code (it works once; a second
  try would come back "rejected" and blame the person for our wait) and
  creating an Account Aggregator consent (a second one leaves two to approve).
  "Unavailable" is still retried there, because nothing was accepted.
- Redeeming a DigiLocker code and listing what it opened are **not** one
  transaction. The connection is committed as soon as the code redeems, so a
  list that then times out or is unavailable leaves the connection in place:
  `complete` answers with the list's `provider_*` code and
  `details.connected: true`, and `GET …/connect/digilocker/documents` lists
  again without a new code. Rolling the connection back with the list would
  leave the person a spent code and no way to finish.
- Anything an adapter throws that is not a `ProviderFailure` is a bug or a
  domain refusal (a consent that is not active yet). It is not retried and
  passes through unchanged.

A live adapter's only job here is to translate its transport's errors into one
of the four kinds below. It must not add its own retry loop.

### Timeout — `timeout`

The provider did not answer in time. It may have received the request.

| | |
|---|---|
| **Retried** | Yes, up to `max-attempts` (except the two operations above) — but never a one-time code, and never a notification on a channel whose provider does not honour idempotency keys. |
| **User — one-time code** | 504 `otp_delivery_delayed`: "Your code is taking longer than usual to send. If it arrives, it will work. If it doesn't, you can ask for a new one now." The details carry `requestId`, `expiresInSeconds` and `resendAfterSeconds`, which is `0`. |
| **User — connect** | 504 `provider_timeout`: "DigiLocker is taking too long to answer. Please try again in a minute." |
| **User — WhatsApp reply** | The capture still succeeds (200); `replyFailure: "provider_timeout"`. |
| **User — notification** | The `outbound_messages` row is `failed`, `failure = timeout`, with its `attempts`; `GET /me/messages` says "Not confirmed — the service took too long to answer. It may still arrive." |
| **Operator** | An INFO line per retry, and one WARN whenever any call gives up — a code, a connect or a notification: `PROVIDER CALL FAILED: provider=<provider> operation=<operation> kind=<kind> attempts=<n>`, with nothing from the call itself (no recipient, code, body or adapter detail). No alert: timeouts are expected in small numbers. A rising count is worth a dashboard. |

For a one-time code, **the challenge and both hourly counts stand, and the
cooldown is lifted**: the text may still arrive and must still work until the
person asks again, and asking again must not wait out a timer.

### Unavailable — `unavailable`

The provider could not take the request at all — connection refused, a 503, a
maintenance window. Nothing was accepted.

| | |
|---|---|
| **Retried** | Yes, up to `max-attempts`, including the two non-repeatable operations — but not a one-time code, which is one attempt. |
| **User — one-time code** | 503 `otp_provider_unavailable`: "We couldn't reach our text message service just now, so no code was sent…" |
| **User — connect** | 503 `provider_unavailable`. WhatsApp: `replyFailure: "provider_unavailable"`. |
| **User — notification** | `failure = unavailable`; "Not sent — the service wasn't reachable. Nothing for you to do." |
| **Operator** | As for timeouts. |

### Delivery failed — `rejected`

The provider answered and refused *this* message or request: an invalid or
unreachable number, a DLT template the operator does not recognise, an expired
authorisation code.

| | |
|---|---|
| **Retried** | Never. The next attempt gets the same refusal and, for SMS, may be billed. |
| **User — one-time code** | 422 `otp_delivery_failed`: "We couldn't deliver a code to that number. Please check it and try again." |
| **User — connect** | 422 `provider_rejected`: "…turned that request down. Please start again from the beginning." WhatsApp: `replyFailure: "provider_rejected"`. |
| **User — notification** | `failure = rejected`; "Not delivered — it was refused for this address or number. Check your contact details." |
| **Operator** | The same `PROVIDER CALL FAILED: provider=<provider> operation=<operation> kind=rejected attempts=<n>` WARN for every rejected code, connect or notification. Many rejections at once usually mean a template or sender id problem, not many bad numbers. |

### Insufficient balance — `insufficient_balance`

The provider refused **us**: out of credit, over quota, suspended. Nothing the
person does will help, and they must not be told otherwise.

| | |
|---|---|
| **Retried** | Never. |
| **User — one-time code** | 503 `otp_service_unavailable`: "Sign-in codes can't be sent right now. This is a problem on our side, not with your number, and we've been alerted…" |
| **User — connect** | 503 `provider_account_unavailable`: "…isn't available on our side right now. It isn't anything you did, and we've been alerted." WhatsApp: `replyFailure: "provider_account_unavailable"`. |
| **User — notification** | `failure = insufficient_balance`; "Not sent — a problem on our side, not with your details. We've been alerted." |
| **Operator** | The `PROVIDER CALL FAILED` WARN, and an **ERROR** log line on every occurrence, with fixed wording to alert on: `PROVIDER ACCOUNT PROBLEM: <provider> refused <operation> on account grounds`. `ProviderCalls.accountProblems()` holds the last time per provider. Top up or fix the account; nothing on that provider is delivered until then. |

### What a failed send does to a one-time code

Three things have to hold at once: a person must not be locked out by our
failure, a late code must not work once a newer one exists, and an attacker must
not get unthrottled requests out of it. Every row is after exactly **one** send
attempt.

| Outcome | Challenge | Cooldown | Per-number hourly count | Per-network hourly count |
|---|---|---|---|---|
| Timeout | kept, until a resend replaces it | **lifted** | kept | kept |
| Rejected, unavailable, insufficient balance | removed | lifted | given back | **kept** |

A timeout keeps the challenge because the text may still arrive, and lifts the
cooldown because the person is looking at the screen and resend is their retry.
The counts are kept because, as far as anyone can tell, a text went: that is
what stops timeouts buying free requests. A resend overwrites the challenge, so
the late code then answers `otp_stale` (with its request id) or `otp_invalid`.

When nothing was delivered there is no code worth keeping, and a person who
fixes a typo — or tries again once we have topped up — is not refused as "too
many attempts". The per-network count is never given back: that is the limit
that stops one network hammering the endpoint, and it holds whether or not our
provider works. `OtpServiceTest` has a test for each row.

Email **sign-in** follows the same table, applied after the response and
reported through `GET /auth/otp/email/delivery/{requestId}` rather than in it
(§5), and a decoy follows it too, replaying the last real send.

The existing 503 `otp_unavailable` is a different thing again: no sender is
configured at all, and nothing is generated.

### Testing the failures

`SandboxFaults` is the sandbox adapters' failure switch. Every sandbox adapter
asks it, before each operation, whether to succeed, throw one of the four
kinds, or hang. It has no endpoint, property or environment variable, so only
code holding the bean — tests — can set it. Adapter names are the provider
names plus `otp`.

- `ProviderCallsTest` — the policy itself: attempts, backoff, never-retried
  kinds, non-repeatable operations, the timeout actually cutting off a hang,
  and `callOnce` making one attempt under its own timeout.
- `NotificationOutboxTest` — a notifying request that does not wait for a
  hanging channel; one key per message, handed to the provider; the worker's
  pool and role, and a runtime-pool worker finding nothing; a send cut off
  before it was recorded, never delivered twice on either kind of channel; a
  message queued twice, once.
- `SandboxFailureMatrixTest` — every sandbox adapter operation × every fault.
- `ProviderFailureApiTest` — each outcome through HTTP: notification rows and
  `GET /me/messages`, one-time code errors, connect errors, WhatsApp replies.
- `OtpServiceTest` — the challenge, cooldown and counter table above; one
  send per request under every fault with the provider allowing five; the code's
  timeout, not the provider's, cutting off a hang; resend at once after a timeout
  and the late code refused.
- `EmailOtpTest` — the same for email, plus its own HMAC key, the decoy that no
  code of the million can complete, and a send that happens after the answer.
- `EmailSignInApiTest`, `SignInChannelSwitchApiTest` — the allowlist cannot be
  seen from outside; a switched-off channel refuses; step-up by email.
- `ProviderDisabledStartupTest` — the real application starts with each provider
  `disabled` alone, all six together, and with nothing set (`aa` disabled).
- `ProviderDisabledApiTest` — all six disabled, over HTTP: status `DISABLED` and
  never connected, every connect call 409 `provider_disabled` with nothing
  written, notifications recorded in-app only.
- `ProviderModeCheckTest` — `disabled` accepted, `off` refused by name, the four
  copies of the default modes in agreement, an unknown OTP sender refused.

---

## The shape to copy

A live adapter is a class implementing the same interface, annotated
`@ConditionalOnProperty(name = "almira.providers.X.mode", havingValue = "live")`.
Nothing above it changes: not the service, not the controller, not the tests
that already run against the sandbox. That is the whole reason for building it
this way before the accounts exist.

[‹ Index](README.md)
