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

---

## The switch

Every provider reads one property. Absent or `sandbox` means the sandbox
implementation; `live` selects the real adapter and requires its credentials.

```yaml
almira:
  providers:
    digilocker: { mode: sandbox }   # sandbox | live
    aa:         { mode: sandbox }
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
and even the Setu sandbox needs a Company PAN and GSTIN. **Cutting AA from v1 is
recommended, pending the owner's decision** — see
[providers/account-aggregator.md](providers/account-aggregator.md), which also
records that `mode: off` does not currently start.

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
  (`OtpDelivery.UNREPORTED`) — otherwise the answer would take a provider round
  trip longer, and "we couldn't deliver" is a reply only a listed address could
  get. The send still goes through `ProviderCalls` (timeout, retries, the
  `PROVIDER ACCOUNT PROBLEM` ERROR line). What changes is the table below: a
  failure other than a timeout makes the challenge unusable in place and keeps
  the cooldown and both counts, because giving them back would make a listed
  address behave differently. The person waits out the cooldown and asks again;
  the operator sees a WARN per failed send and the ERROR line for an account
  problem. `EmailSignInApiTest` holds both addresses to the same status, fields,
  headers and refusals, and holds the allowlisted answer to under half a
  deliberately slow send.
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
message is recorded in `outbound_messages` — channel, template, title, status,
which way it failed and after how many attempts, never a body — so the in-app
list (`GET /me/messages`) works today, a test can assert that the person who
should have been told was told, and switching a channel on changes where a row
goes rather than whether it exists.

Reading that table is restricted to the person the message was for. The log of
what somebody was told is as personal as what it was about.

---

## When a provider fails

Every call to every adapter above — the three notification channels, one-time
codes, DigiLocker, the Account Aggregator and WhatsApp replies — goes through
one policy, `ProviderCalls`. It is the only reader of each provider's
`timeout`, `max-attempts` and `retry-backoff`:

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
- Anything an adapter throws that is not a `ProviderFailure` is a bug or a
  domain refusal (a consent that is not active yet). It is not retried and
  passes through unchanged.

A live adapter's only job here is to translate its transport's errors into one
of the four kinds below. It must not add its own retry loop.

A caution for whoever writes the first live adapter: these calls are made
inside the request that needs them. The worst case per call is `max-attempts ×
timeout` plus the backoff — about 31 seconds for SMS at the defaults — and a
notification tries three channels in turn. That is fine for a sandbox and too
long for a busy server. Before a notification channel goes live, move delivery
onto the reminder worker (the `queued` status in `outbound_messages` exists for
it), or lower the attempts for that provider.

### Timeout — `timeout`

The provider did not answer in time. It may have received the request.

| | |
|---|---|
| **Retried** | Yes, up to `max-attempts` (except the two operations above). |
| **User — one-time code** | 504 `otp_delivery_delayed`: "Your code is taking longer than usual to send. If it arrives, it will work…" The details carry `requestId`, `expiresInSeconds` and `resendAfterSeconds`. |
| **User — connect** | 504 `provider_timeout`: "DigiLocker is taking too long to answer. Please try again in a minute." |
| **User — WhatsApp reply** | The capture still succeeds (200); `replyFailure: "provider_timeout"`. |
| **User — notification** | The `outbound_messages` row is `failed`, `failure = timeout`, with its `attempts`; `GET /me/messages` says "Not confirmed — the service took too long to answer. It may still arrive." |
| **Operator** | An INFO line per retry, and one WARN whenever any call gives up — a code, a connect or a notification: `PROVIDER CALL FAILED: provider=<provider> operation=<operation> kind=<kind> attempts=<n>`, with nothing from the call itself (no recipient, code, body or adapter detail). No alert: timeouts are expected in small numbers. A rising count is worth a dashboard. |

For a one-time code, **the challenge, the cooldown and both hourly counts all
stand**: the text may still arrive, and a late code must still work.

### Unavailable — `unavailable`

The provider could not take the request at all — connection refused, a 503, a
maintenance window. Nothing was accepted.

| | |
|---|---|
| **Retried** | Yes, up to `max-attempts`, including the two non-repeatable operations. |
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

Two things have to hold at once: a person must not be locked out by our
failure, and an attacker must not get unthrottled requests out of it.

| Outcome | Challenge | Cooldown | Per-number hourly count | Per-network hourly count |
|---|---|---|---|---|
| Timeout | kept | kept | kept | kept |
| Rejected, unavailable, insufficient balance | removed | lifted | given back | **kept** |

When nothing was delivered there is no code worth keeping, and a person who
fixes a typo — or tries again once we have topped up — is not refused as "too
many attempts". The per-network count is never given back: that is the limit
that stops one network hammering the endpoint, and it holds whether or not our
provider works. `OtpServiceTest` has a test for each row.

The existing 503 `otp_unavailable` is a different thing again: no sender is
configured at all, and nothing is generated.

### Testing the failures

`SandboxFaults` is the sandbox adapters' failure switch. Every sandbox adapter
asks it, before each operation, whether to succeed, throw one of the four
kinds, or hang. It has no endpoint, property or environment variable, so only
code holding the bean — tests — can set it. Adapter names are the provider
names plus `otp`.

- `ProviderCallsTest` — the policy itself: attempts, backoff, never-retried
  kinds, non-repeatable operations, the timeout actually cutting off a hang.
- `SandboxFailureMatrixTest` — every sandbox adapter operation × every fault.
- `ProviderFailureApiTest` — each outcome through HTTP: notification rows and
  `GET /me/messages`, one-time code errors, connect errors, WhatsApp replies.
- `OtpServiceTest` — the challenge, cooldown and counter table above.
- `EmailOtpTest` — the same for email, plus its own HMAC key, the decoy that no
  code of the million can complete, and a send that happens after the answer.
- `EmailSignInApiTest`, `SignInChannelSwitchApiTest` — the allowlist cannot be
  seen from outside; a switched-off channel refuses; step-up by email.

---

## The shape to copy

A live adapter is a class implementing the same interface, annotated
`@ConditionalOnProperty(name = "almira.providers.X.mode", havingValue = "live")`.
Nothing above it changes: not the service, not the controller, not the tests
that already run against the sandbox. That is the whole reason for building it
this way before the accounts exist.

[‹ Index](README.md)
