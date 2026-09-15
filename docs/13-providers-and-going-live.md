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
> **not watched failing** — which today is every provider against its real
> service. Email is the one with a live adapter (SMTP, §5), proven against a fake
> relay. This document is the design those pages build on.

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
  start for every provider but `email`, because no other live adapter exists.
  Live email needs an SMTP host and a from-address (§5).
- **`disabled`** — not offered on this server, and a normal state rather than an
  error. The application starts; `connect/providers` reports `mode: DISABLED`
  and `connected: false`; every call to a disabled DigiLocker, Account
  Aggregator or WhatsApp answers **409 `provider_disabled`** with
  `details.provider`, before anything is written or called. A disabled `sms`,
  `email` or `push` channel has no sender: notifications skip it and record no
  row for it. The default for `aa`.

  One combination refuses: **email as a sign-in channel with `email: disabled`**.
  Email codes are sent through the email provider, so the two settings say
  opposite things — email sign-in is offered, and there is no email. The
  provider being absent is still normal; *offering sign-in through* an absent
  provider is not a state anyone chose. It refuses at startup naming both
  settings (`SignInChannels`) rather than starting with email quietly left out
  of `/auth/otp/channels`: a channel that is not enabled ends the email alpha,
  which signs every email-only tester out at startup (docs/13 §5), and a
  provider switch must not do that as a side effect. `sms: disabled` has no such
  rule, because phone codes go through `almira.otp.provider`, not the `sms`
  provider (known-issues 12).

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
    digilocker: { mode: disabled }  # disabled | sandbox | live — hidden until a client offers it
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
and the gaps in the current code — is [providers/sms.md](providers/sms.md).
A reminder's text goes to the number on the person's account, looked up when it
is sent ("Who a message is for", below), and is one line: the title and why it
came. That line is the shape each DLT template is registered in. **Real SMS
delivery is not built** and stays sandbox: DLT registration is a business
registration, not code.

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

**Email to go live**: the live adapter exists — `SmtpEmailSender`, for any relay
that speaks SMTP with STARTTLS (SES, Postmark, Resend and a company server all
do). What it needs is a sending domain with SPF, DKIM and DMARC published, a
relay account, a verified from-address, and:

```
ALMIRA_PROVIDER_EMAIL_MODE=live
ALMIRA_PROVIDER_EMAIL_SMTP_HOST=…        # required
ALMIRA_PROVIDER_EMAIL_SMTP_FROM=…        # required, e.g. Almira <reminders@your-domain>
ALMIRA_PROVIDER_EMAIL_SMTP_PORT=587
ALMIRA_PROVIDER_EMAIL_SMTP_USERNAME=…
ALMIRA_PROVIDER_EMAIL_SMTP_PASSWORD=…
ALMIRA_PROVIDER_EMAIL_SMTP_START_TLS=true
```

- `live` without a host or a from-address refuses to start, naming what is
  missing (`ProviderModeCheck`). A username is optional: a relay on a private
  network may take none.
- STARTTLS is **required**, not merely offered, unless turned off — a relay
  that will not negotiate TLS is refused rather than sent a reminder's amount
  in plain text. Only a relay on the same host should need `false`.
- **At-most-once.** SMTP has no send-side de-duplication, so the adapter
  declares `honoursIdempotencyKey = false`: a timeout is recorded, not retried.
  The idempotency key, hashed, is the Message-ID.
- Failures map to the four kinds: our credentials or our from-address refused →
  `insufficient_balance` (ours to fix); this recipient refused → `rejected`; no
  answer → `timeout`; could not connect, or a temporary 4xx → `unavailable`.
- Nothing is logged but the port and whether STARTTLS is on: never the
  recipient, the subject or the body.
- A subject is built from a title a household member typed, so every control
  character in it becomes a space: a line break cannot add a header or a
  recipient.
- `LiveEmailDeliveryApiTest` runs the real application with `email: live`
  against `FakeSmtpServer` on loopback. Not watched against a real relay: TLS,
  authentication, greylisting, and bounces (which arrive later, by email, and
  which nothing reads).

### Sign-in codes by email — the closed alpha

The owner's route for the alpha: one-time codes by email, to allowlisted
testers, with email as the **only** sign-in so nobody ends up with two accounts.
It is built on this channel and on the failure contract below, with no email
provider chosen. What it still needs is what email needs: the relay and domain
above, and a deployment that passes the variables through to the application
(`deploy/docker-compose.prod.yml` does not list the SMTP ones yet).

```
ALMIRA_SIGN_IN_CHANNELS=email
ALMIRA_ALPHA_EMAIL_ALLOWLIST=asha@example.com,ravi.k+alpha@example.com
ALMIRA_PROVIDER_EMAIL_MODE=live        # plus the SMTP settings above
ALMIRA_ALPHA_EMAIL_DECOY_SINK=...      # required with live: an address that discards mail
```

- **Switch.** `almira.auth.sign-in-channels` is `phone`, `email` or both;
  `phone` when unset, so development and every suite are unchanged. A value it
  does not understand, an empty list, a malformed allowlist entry, email
  with an empty allowlist, email with `almira.providers.email.mode=disabled`,
  email on a `live` provider without a decoy sink, and a sink that is not an
  address or is on the allowlist all refuse to start (`SignInChannels`). The startup
  log says how many addresses are listed, never which.
- **Sender.** `ChannelEmailOtpSender` hands the code to whichever email
  `ChannelSender` the mode selected, with the address as `recipientHint` and
  the code only in the body. It can deliver when the channel is `live`, or when
  it is the sandbox **in development** — the same rule as the log SMS sender.
  Anywhere else (a non-development server still on the sandbox) the request is
  `503 otp_unavailable` before a code exists.
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
  random stored value no code matches and no email to that address. For that
  to hold, an allowlisted address's email is sent **after** the response
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
  (`otp_provider_unavailable`, `otp_service_unavailable`; resend open now).
  The web client says it in English, Telugu and Hindi; the native app in
  English, as the rest of it. Only `sent` is shown as sent: a status still
  `sending` after 30 polls, or one that cannot be read, is shown as delayed
  with resend open. A failure does to the challenge, cooldown and counts
  exactly what a reported send does ("What a failed send does to a one-time
  code", below): nobody is locked out, or charged a request, by our failure.
  **One exception, by the owner's enumeration rule below:** the provider
  refusing one address (`otp_delivery_failed`) is not reported on this path —
  Signal 1.

  **How a decoy stays indistinguishable.** A decoy runs the same code on the
  same executor as a real send and makes the **same provider call**, under the
  same timeout, addressed to the *decoy sink* instead of the address
  (`ALMIRA_ALPHA_EMAIL_DECOY_SINK`: an address that accepts and discards
  mail, such as the provider's mailbox simulator; required once the email
  provider is `live`, refused if it is on the allowlist; in the sandbox a
  placeholder `decoy-sink@almira.invalid` that nothing delivers to). So a decoy
  fails, times out or succeeds with the provider as it is at that moment. Every
  outcome, real or decoy, is applied at one moment — `almira.otp.send-timeout`
  plus one second after the request (six seconds by default) — however long
  its call took. `EmailSignInApiTest` holds a listed and an unlisted address to
  the same request, settled status (status, fields, headers), settling time,
  resend answer and wrong-code answer with the provider healthy, unavailable,
  out of credit, timing out and refusing the listed address; probes the refusal
  three times over; and changes the provider's state five times with unlisted
  probes before any listed address. `EmailOtpTest` does the same against Redis
  directly, in both orders, and times seven latencies including a refusal and a
  timeout.

  **The enumeration rule** (owner, 2026-09): *acceptable* — a signal that only
  confirms membership to someone who already knows the exact address;
  *not acceptable* — a signal that lets someone discover or enumerate addresses
  they did not already have. Applied strictly: an answer that differs per
  submitted address and can be asked again, for a guessed or harvested list of
  candidates (name variants at a family domain), discovers addresses, even
  though each query needs an exact string. A signal is acceptable only if it
  cannot be used to test candidates at scale — it needs something only the
  address's holder has, or fires once and the prober cannot make it fire again.

  **Every signal, classified** (2026-09-14):

  1. **The provider refusing a listed address — not acceptable; closed.**
     A suppression list or a mailbox the provider knows is dead refuses one
     address and accepts the next. Only a listed address is ever sent to, so
     only a listed address could be refused, and the refusal showed four ways:
     the status (`failed` / `otp_delivery_failed`), the cooldown (lifted, so a
     resend was answered instead of `429`), the per-address count (given back),
     and the challenge (removed, so a wrong code answered `otp_expired` rather
     than `otp_invalid`, and the earlier request id came back). Each is an
     answer per submitted address, repeatable against a candidate list at
     every probe — it discovers every listed address the provider will not
     deliver to. **What was done:** on the sign-in path a refusal is applied as
     a sent code in every respect a stranger can probe — status `sent`, cooldown
     kept, count kept, challenge kept (its code reached nobody), nothing put
     back — and logged at ERROR, `SIGN-IN EMAIL REFUSED`, with the masked
     address, for the operator. **The conflict with the earlier decision, plainly:**
     a tester whose address the provider refuses is no longer told "we couldn't
     send the code" on screen. That cannot be kept without the leak. The tester
     and a prober typing the tester's address send identical requests, and the
     tester of a refused address holds nothing a prober lacks — the one thing
     only they have is the mailbox, which is exactly what is not receiving. So
     anything the server shows the tester about that refusal it shows everyone
     who types the address, and an unlisted address can never show it back,
     because nothing is sent to it (mirroring would mean emailing strangers).
     The smallest remaining gap is therefore not a leak but that silence, for
     this one failure: the tester sees `sent`, the web client's standing line
     ("If it hasn't arrived in a minute, look in your spam folder") and the
     change-address button, and the operator has the ERROR line to reach them.
     Step-up by email still reports a refusal: the caller is signed in and
     already owns the address. If the owner prefers the notice to the
     protection, the change is one line (`OtpService.settleInBackground`), and
     this signal returns to *not acceptable*.
  2. **A change in the provider's state — not acceptable; closed.** Decoys used
     to replay the last real send (`otp:email:provider-weather`), so after the
     provider went down, came back, ran out of credit or began timing out,
     every probe of an unlisted address reported the old state until a listed
     address was next sent a code. Inside that window each probe was a clean
     listed/unlisted bit: it ruled out unlisted candidates as far as the
     prober's per-network allowance paid, and confirmed one listed address per
     transition. That tests candidates at scale; that a transition cannot be
     caused matters little, since outages are public. **What was done:** the
     replay is gone. A decoy makes its own provider call to the decoy sink, so
     its outcome is the provider's state at that moment, as a real send's is.
     The weather key is no longer written or read.
  3. **Latency on a tick boundary — not acceptable; closed.** Outcomes were
     applied on the whole second after the call, and a decoy's call was a
     replay of an earlier send's latency, so a send straddling a second settled
     a tick apart from a decoy. Statistical and slow (5 requests an hour per
     address), but still an answer per submitted address that can be asked
     again, so under the strict rule not acceptable. **What was done:** every
     outcome is applied at the same moment after the request, so how long any
     call took is not visible at all.
  4. **A fresh Redis, or a day with no real send — not acceptable; closed.**
     Decoys assumed a healthy provider answering in 300 ms until a real send
     was seen, which is Signal 2 with the window open from the start. **What
     was done:** gone with the replay; a decoy has nothing to assume.
  5. **A probe of a listed address rewrote what the next decoy reported — not
     acceptable; closed.** Not in the earlier list. Because a real send wrote
     the weather and decoys read it, a prober could ask for candidate X and
     then for an address of their own: if the second answer's settling time or
     outcome followed X's send rather than the one before, X was listed. It
     needed no provider transition, only a provider whose latency varied, and
     could be repeated for every candidate. **What was done:** as Signal 2 —
     nothing a real send does is read by any other request.
  6. **What else was checked, and is the same for both** (acceptable, because
     none differs): the request's status, body, headers and response time; the
     `429` cooldown and its `Retry-After`; the per-address and per-network
     `429`s and their counters (a decoy counts as a request); the `400`
     `email_invalid`; the `503` `otp_unavailable` (checked before the address
     matters); the delivery status's `404` for an unknown or expired id; a wrong
     code's `otp_invalid` and attempts remaining, `otp_locked`, `otp_stale`,
     `otp_expired`; the restore of an earlier code after an outright provider
     failure (a decoy's challenge is restored the same way); and the
     development echo (development only). The **right code** does sign a
     listed address in and never an unlisted one: that needs the code from the
     mailbox, information only the holder has — *acceptable* by the rule.
  7. **Introduced by the fix, for the live adapter to hold — not yet
     verifiable.** A decoy's call goes to one sink address. If the provider
     throttled or suppressed *that recipient* separately (a per-recipient rate
     limit hit by many probes), decoys could fail while listed sends did not —
     and a prober could try to cause it. So the live adapter must classify
     anything about one recipient as `rejected` (applied as sent, above), never
     as `unavailable`, and the sink must be one the provider does not throttle
     per recipient (a mailbox simulator). Each decoy is also a billed send,
     bounded by the per-network request cap (20 an hour). Neither can be tested
     until a live email adapter exists; add both to its watched-failing list.
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
  touched.

  **The rule, whatever the configuration.** An email-only account (no phone
  number) may hold a session only while the server offers email sign-in *and*
  lists its address. The check runs on every server, including one that offers
  phone only, so no change to either variable can leave an email-only session
  alive: taking one address off, taking the last one off, and taking email out
  of `ALMIRA_SIGN_IN_CHANNELS` all sign the affected testers out at the next
  startup (and on their next request or refresh, on any server already running
  the new configuration). On a phone-only server this costs one account read
  per signed-in account per minute.

  **Ending the alpha.** Email on with an empty allowlist still refuses to start
  — a sign-in screen offering a channel nobody can use helps no one — so the
  clean way to end the alpha is `ALMIRA_SIGN_IN_CHANNELS=phone` (the list may be
  emptied or left; it is ignored while email is off) and a restart. Every
  email-only tester is signed out before the server takes a request, each
  audited as above; the startup log line says how many were ended and that
  email is off. Turning email back on later does not bring those sessions back:
  the testers sign in again.

  `AlphaAllowlistRemovalApiTest` seeds sessions before its server starts and
  asserts the starting server ended them; `AlphaAllowlistEndedAtStartupApiTest`
  does the same for a server started with email off, with the list emptied
  (`EveryoneRemoved`) and with it left as it was (`EmailTurnedOff`).

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
iOS; and **the apps registering their device tokens**. The server side of that
exists since V60 — `user_devices`, `PUT /api/v1/me/devices/{installationId}`,
a send to every device a person has, and pruning of a token the platform
refuses ("Who a message is for", below) — but neither native app asks for
notification permission or calls the endpoint yet. Until one does, push has
nowhere to go, which is why it is last. The APNs and FCM requirements are in
[providers/push.md](providers/push.md).

---

## 6 · Market data — prices and exchange rates

Not a provider in the sense above: no account, no contract, no key. AMFI, NSE,
BSE and the European Central Bank publish these files for anyone. It lives here
because it is the one other place the server reaches out, and the rule is the
same — **off unless switched on**. With the defaults, no class that can make the
request is even created (`LiveRateSourceTest` asserts it), and nothing about a
household is ever sent: each fetch is a plain download of a public file, over
HTTPS, with redirects refused and a size cap (`HttpMarketFileFetcher`).

| Setting | Default | What it does |
|---|---|---|
| `ALMIRA_MARKET_FX_ENABLED` | `false` | Daily ECB euro reference rates |
| `ALMIRA_MARKET_FX_CRON` | `0 15 21 * * *` (IST) | After the ECB publishes, around 16:00 CET |
| `ALMIRA_MARKET_PRICES_ENABLED` | `false` | Nightly AMFI NAVs and NSE/BSE closing prices |
| `ALMIRA_MARKET_PRICES_CRON` | `0 30 23 * * *` (IST) | After AMFI's evening file; both bhavcopies are out by then |
| `ALMIRA_MARKET_PRICES_SOURCES` | `amfi,nse,bse` | Which files to read |

### Exchange rates — `LiveRateSource`

A `RateSource` (the interface V23 left for this). Once a day it reads
`eurofxref-daily.xml` and:

- records a **shared rupee rate** for every currency the ECB publishes, crossed
  through the euro, marked `source = 'ecb'` and dated with the ECB's own date —
  so the stored-rate lookup serves it, every converted figure can be traced, and
  a restart loses nothing. A second run the same day records nothing new;
- keeps the day's file in memory to answer a pair nobody stored (USD→GBP),
  consulted **after** stored rates.

It never outranks a household: a rate the household recorded still wins, because
they know what they actually got. It simply beats the rates that shipped with the
app by being newer. Currencies the ECB does not publish — the Gulf currencies
among them — keep the seeded or household rate, and a holding in a currency with
no rate at all is still left out of the total and said out loud (docs/07 §1).

The web shows both amounts side by side on a holding held abroad: its own
("USD 12,500"), and "≈ ₹11,94,439" beneath with `1 USD = 95.5551 INR · rate as of
11 Sep 2026 · ECB reference rate`.

**To switch on:** set `ALMIRA_MARKET_FX_ENABLED=true`. Nothing else is needed.
**Not watched failing:** a failed fetch logs one WARN (`exchange-rate refresh
skipped`) and the last recorded rate stays, with its date.

### NAVs and closing prices — `PriceFeedJob`

**The files, as confirmed from the publishers on 14 September 2026** (excerpts
are the test fixtures in `backend/src/test/resources/market/`):

- **AMFI** `https://portal.amfiindia.com/spages/NAVAll.txt` — semicolon-separated,
  CRLF, section headings and fund-house names on their own lines. The header is
  now `Scheme Code;ISIN Div Payout/ ISIN Growth;ISIN Div Reinvestment;Scheme
  Name;Plan;Option;Net Asset Value;Date`; it used to have no `Plan` and `Option`.
  Columns are found by name, so both layouts read. Dates are `11-Sep-2026`. The
  file still lists schemes that stopped publishing (with dates from 2017).
- **NSE** `https://nsearchives.nseindia.com/content/cm/BhavCopy_NSE_CM_0_0_0_<yyyyMMdd>_F_0000.csv.zip`
  and **BSE** `https://www.bseindia.com/download/BhavCopy/Equity/BhavCopy_BSE_CM_0_0_0_<yyyyMMdd>_F_0000.CSV`
  — the common UDiFF layout both exchanges moved to in July 2024 (`TradDt`,
  `FinInstrmTp`, `FinInstrmId`, `ISIN`, `TckrSymb`, `SctySrs`, `FinInstrmNm`,
  `ClsPric`, …); NSE zips it, BSE does not. Only `STK` lines are read, at the
  closing price. The retired pre-2024 bhavcopy layouts are not read.

**Which holdings are valued** (`PriceFeedValuations`): active, in rupees, with
units, of a built-in type —

- a mutual fund (`mf_sip`, `mf_lumpsum`) whose new *ISIN or AMFI scheme code*
  field (V66) names it — by ISIN (either of a line's two) or by scheme code;
- a listed share or REIT (`stock_listed`, `reit`) by ISIN, or by ticker. A share
  is valued from the exchange its *Exchange* field names; "Both" or nothing
  means NSE, and BSE only where NSE has not priced it this week.

Nothing is matched from a name. Value = units × price, to the paisa. The
valuation is dated with the price's own date, marked `price_feed` with the file
(`amfi`/`nse`/`bse`), the price per unit and the scheme code or ISIN it matched.
A price more than seven days old, or dated in the future, is not used.

**Never over what someone entered.** A valuation someone typed or imported,
dated on or after the price's date, wins: the holding is left alone. One dated
before stays in the history, and the new figure is current — so the screen says
so: "Valued at NAV as of 11 Sep 2026", "₹89.5712 a unit × 120 units", and "Your
own value of ₹9,500 from 1 Sep 2026 is kept in the history." A value typed over
the same day's price becomes the person's and loses the price label. Clearing
the ISIN stops the daily valuation. The database backs the label: the
application role cannot write a `price_feed` valuation at all (V66 policy,
asserted in `db/tests/rls_privacy_test.sql` and `PriceFeedApiTest`); only the
job's system connection can.

**Privacy.** A price-fed valuation follows its holding's visibility exactly, as
every valuation does. The activity log gets one line per household per file
(`investment.valuations_from_prices`, a count, no titles or amounts), not one
per holding. The request to AMFI or an exchange carries nothing but the file's
address.

**To switch on:** `ALMIRA_MARKET_PRICES_ENABLED=true`. **Not watched failing
against the live sites from a server** — see known issue 37 ("The price and rate feeds have only been fetched from a laptop"). A file that is not
there (a holiday, a late publication, a refused request) logs one WARN (`price
feed <source> skipped`) and the last valuation stays, with its date.

---

## What "the stand-in" means now

Notifications remain a stand-in on every channel but live email, but not an
unverifiable one. Every outbound
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
| **Where it runs** | In the request — with **no database transaction open** around the call. | `NotificationOutbox`, a worker on the owner connection, after the request or sweep has committed a `queued` row. |
| **Attempts** | One-time codes: **exactly one**, under `almira.otp.send-timeout` (5 s), whatever the provider's `max-attempts`. Connect calls: the provider's policy, all of it inside `almira.providers.connect-budget` (20 s). | The provider's policy: `timeout`, `max-attempts`, `retry-backoff`. |
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

Connect calls stay in the request, because the person is waiting for the
answer, but two things changed (known-issues 21):

- **No transaction is open while a provider is called.** `ConnectService` reads
  what it needs in one short transaction, calls the provider with none open, and
  writes what came back in another. A hanging DigiLocker used to hold an
  app-pool connection for the whole retry budget; ten people waiting on an outage
  would have been the whole pool. An import still stores everything or nothing:
  every fetch happens first, then one transaction stores them.
- **The call as a whole has a budget.** `ProviderCalls.interactive` gives each
  attempt the provider's `timeout` or what is left of
  `almira.providers.connect-budget` (`ALMIRA_PROVIDER_CONNECT_BUDGET`, default
  `20s`), whichever is less, and starts no retry whose backoff would end past it.
  DigiLocker's defaults (15 s × 3) used to mean about 46 seconds of spinner.

`ConnectCallsOutsideTransactionsApiTest` samples the app pool while DigiLocker
and the aggregator hang, and was watched failing with `@Transactional` put back.

A WhatsApp reply also stays in the request, because whether it went is part of
the capture's answer (`replyFailure`); a live Meta webhook's own response
deadline is a reason to revisit that before WhatsApp goes live (known-issues 21).

### Notifications are background work

Nobody is sitting in front of a reminder. `RecordingNotifier.deliver` writes the
`in_app` row as `sent` (it is the database, not a provider) and one `queued` row
per configured channel the message may go on — any, for an essential notice; only
those the person said yes to, for anything else (V125, "Pacing" below) — in the
caller's transaction, and returns. It never calls
a provider. The worker is woken after the commit and also polls every
`almira.outbox.poll-interval` (`PT2S`); it claims queued rows `for update skip
locked`, sends each through `ProviderCalls` with the provider's policy, and
records `sent` or `failed`, the failure kind and the attempts on the same row.
The body waits in `outbound_message_bodies`, which the runtime role cannot read,
and is deleted in the same statement that records the row as finished, so a
worker that stops just after recording leaves no body behind; any body whose row
is no longer `queued` is also deleted at the start of every claim. Nothing else
reads the table — `NotificationOutboxTest` fails if application code or a
migration other than V32 names it. A request or sweep that notifies no longer
waits on any provider (`NotificationOutboxTest` holds a hanging push to that).

The worker uses the owner data source by explicit qualifier: on the runtime
pool, row-level security shows a user-less worker no rows, and it would do
nothing, successfully, forever. `NotificationOutboxTest` pins the pool and role,
and shows a worker built on the runtime pool finding nothing.

### Who a message is for

Resolved when the worker sends, not when the row is written, so a number
changed in between is the number used (`DeliveryDirectory`, on the owner
connection for the same reason as the worker — known-issues 13, resolved):

| Channel | Address |
|---|---|
| `sms` | the account's phone number |
| `email` | the account's email address |
| `push` | every row in `user_devices` for the person, newest first, at most 10 |

- Push is **one provider call per device**, each with the row's key plus the
  installation id, so a provider that de-duplicates does not mistake the second
  phone for a repeat of the first. The row is `sent` when any device took it. A
  device the platform answers `rejected` for is deleted.
- A **live** channel with no address for the person records `skipped`,
  `failure = no_recipient` — "Not sent — we don't have somewhere to send this for
  you. It's here instead." A sandbox is still called without one: it reaches
  nobody either way.
- An account that is not `active`, or is deleted, has no address.

Devices are registered by the apps: `PUT /api/v1/me/devices/{installationId}`
with `{platform, token, environment, appVersion}` — `ios` needs `environment`,
because an APNs development token is refused by the production gateway —
idempotent by installation, audited as `device.registered`; `GET` lists them
without the token; `DELETE` on sign-out. Row-level security keeps each person to
their own rows, an admin included, and a guest link can do none of it (V60,
`db/tests/rls_privacy_test.sql`).

### What a message says

`MessageTemplates` words every message at send time, around the title and body
its producer supplied:

- **every message says why it came**, in one sentence — "You're getting this
  because you own or hold this record in Almira, and its date is coming up." —
  and an email ends with the quiet promise and where to change what you get;
- **push** carries the title and the short reason as its text, never the body:
  a push payload passes through Apple or Google and sits on a lock screen;
- **SMS** is one line, the title and the short reason — the DLT template shape;
- a reminder's body gives the date in words and the amount the way the handbook
  writes it, with the words beneath: "₹2,40,000", "Two Lakh Forty Thousand
  Rupees".

**Languages.** English is reviewed. Telugu and Hindi are written, beside the
English, and marked `needsReview`; a draft is **never sent** — a person whose
locale is `te-IN` gets English until a native speaker signs the draft off and
it joins `MessageTemplates.REVIEWED`. The titles producers supply are English,
as the server's other sentences are (Doc 14).

### Pacing

The promise Settings makes ("Our quiet promise"): **at most one reminder a day,
none in your quiet hours, never a sales message, and every reminder says why it
came.** `DeliveryPacing` keeps it as the worker claims each row:

0. **Nothing is queued without consent** (V125). `app.enqueue_outbound_message`
   writes a row for a non-essential message only when the person's latest
   `messages` consent is `given` and names that channel; with no consent at all
   — everyone who was never asked — only the in-app row is written. Consent is
   opt-in and asked for in the app (Doc 23 "Asked when it helps").
   **A stop made while it waited holds.** Before pacing, the worker asks again
   what queueing asked: a person since marked as passed away (`skipped`,
   `notifications_stopped`, except the warnings V103 lets through), or a
   non-essential message for someone with no consent on that channel any more
   (`skipped`, `no_consent`). A row waiting out quiet hours or the daily limit
   does not go after either.
1. **Essential messages go.** The notices that protect the person's account or
   let them stop something done in their name — listed in Doc 23 "Notices that
   protect your account" — are not under consent, not held by quiet hours, not
   counted against the day, and not stopped by a switched-off channel. Which they
   are is one explicit list, `app.message_is_essential` (V125), asked by the
   queueing, the worker's re-check and pacing; `MessageTemplates.ESSENTIAL_TEMPLATES`
   is the same list for the wording, and `MessagesConsentTest` fails if the two
   differ. A template that is on neither list — a new kind of message — is not
   essential. Being named an emergency contact, a child coming of age and other
   notes about the household are not on it.
2. **A switched-off channel is skipped** — `skipped`, `turned_off` — in every
   mode. Preferences: `GET`/`PUT /api/v1/me/notification-preferences`
   (`smsEnabled`, `emailEnabled`, `pushEnabled`, `quietFrom`, `quietUntil` as
   `HH:mm`), audited as `notifications.preferences_changed`. No row means every
   channel on and quiet hours 21:00–08:00.
3. **Quiet hours** are the person's, in the household's time zone (India's for a
   message that belongs to no household); a window may cross midnight. A message
   that would land inside one waits: `not_before` is set to the end of the
   window and `deferred_for = quiet_hours`, and nothing claims it until then.
4. **One non-essential message a day.** If a different logical message already
   started out to the person today, on any channel, this one waits for tomorrow
   at the end of their quiet hours (`deferred_for = daily_limit`). One logical
   message on three channels is one message. A message held back like that for
   more than a week is out of date: it is `skipped`, `daily_limit`, and stays in
   the in-app list.

Pacing applies to **live** channels. A sandbox reaches nobody's phone, and
pacing it would only make development and the suites depend on the time of day;
`NotificationDeliveryApiTest` turns it on for the sandbox with a fixed clock to
prove each rule, and `LiveEmailDeliveryApiTest` shows a live email held by a
quiet hour. A switched-off channel is honoured in every mode. With the quiet-hours,
daily-limit and switched-off checks removed, six of those tests failed.

What is not promised: two servers deciding at the same moment could each let
a different message through as the day's first. Like the sweeps (Doc 21 §7),
this assumes one scheduling instance; a second needs the claim to take a
per-person lock.

The Still true? sweep has rules of its own on top of these — at most one a
week, "Ask me later", and never on a family birthday or death anniversary
(Doc 21 §6).

### After a restore: `body_not_restored`

A backup does not carry queued bodies. `scripts/backup.sh` leaves the data of
`outbound_message_bodies` out of the dump, the same way Redis is left out (the
table itself is in it, empty), so a backup taken mid-drain holds no rendered
message in plaintext (docs/17 §6). The cost is on the restored server: a row
that was `queued` when the backup was taken comes back with no body.

The worker does not send it — there are no words, and an empty or stand-in
text would be worse than nothing — and does not leave it queued for a body that
cannot come. It records the row `failed` with `failure = body_not_restored`,
attempts unchanged — without pacing it first, so it neither waits out quiet
hours nor takes the day's one message — and logs one WARN per row naming only the channel and the
message id: `outbox: a queued <channel> message has no body (a restore does not
bring bodies back); marked failed as body_not_restored, not sent (message <id>)`.
That holds for a row whose send had started on a channel that honours keys too;
one on an at-most-once channel is still recorded `timeout`, because it may have
gone. The person sees it in `/me/messages`: "Not sent — it was still waiting to
go out when our service was restored from a backup. Nothing for you to do." The
in-app row, which has no body to lose, is unaffected. `NotificationOutboxTest`
holds all of this.

### Idempotency keys, and what they guarantee

Every queued row carries `idempotency_key`, unique in the table, naming one
logical message on one channel: `<logical>:<channel>`. Since V35 the in-app row
carries one too (`<logical>:in_app`), so the same logical message is listed once
in `/me/messages`, not once per time it was asked for. The logical part names the
event, never the call:

- a reminder: `reminder:<reminder id>:<date it fires for>:<user>` — a sweep that
  runs again before the reminder is marked (a crash, a second server) queues
  nothing new;
- a still-true digest: `still-true:<household>:<user>:<hash>`, the hash over the
  records asked about, each with its due date and the previous nudge that made it
  a question — a second sweep of the same state asks nothing new, and a record due
  again or ignored for thirty days is a new message;
- an emergency notice: `emergency.named:<contact id>`, `emergency.requested:<request
  id>` or `emergency.vetoed:<request id>`, then `:<user>` — naming the same contact
  again, or vetoing twice, tells each person once.

Only a caller that names no key gets `<template>:<random>`. A second enqueue with
the same key writes nothing.

Two workers can hold the same row only one after the other: a claim whose lease
ran out can be taken, and the new claim has a new `claim_token`. Every write a
worker makes to a claimed row — the send stamp and the outcome — is conditional on
its own token, so a worker that was paused past its lease finds the row no longer
its own and leaves it without calling the provider. A row finished without a send
(unconfirmed, skipped, bodiless) has its token cleared, so a worker that had
already sent before its claim was taken over cannot write its outcome over the one
the takeover recorded either — not turn an unconfirmed push into `sent`, not add
its attempts a second time — and it counts nothing it did not record.
`NotificationOutboxTest` runs both interleavings with two workers: paused before
the send, and paused after the send and before the record.

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
| Rejected, unavailable, insufficient balance | removed; the one it replaced is **put back** if still live | lifted | given back | **kept** |

A timeout keeps the challenge because the text may still arrive, and lifts the
cooldown because the person is looking at the screen and resend is their retry.
The counts are kept because, as far as anyone can tell, a text went: that is
what stops timeouts buying free requests. A resend overwrites the challenge, so
the late code then answers `otp_stale` (with its request id) or `otp_invalid`.

When nothing was delivered there is no new code worth keeping — but the code
it replaced may have arrived, so that one is put back (known-issues 22): with
its own lifetime, its own wrong-code count plus any wrong codes tried against
the failed one, and answering to the failed request's id as well as its own,
because an emailed code's step already switched to that id. It is put back only
if nothing newer has replaced the failed request, and never after a timeout or
a send that worked: a code that may have gone out is always the newest one.

**The attempt cap is security, not housekeeping.** If putting a code back also
reset its wrong-code count, "guess, resend until it fails, guess again" would
be unlimited guesses at a live code. So a code and the resend that set it aside
share **one** allowance until that resend's send settles:

- a wrong code typed while the resend is in flight is judged against its own
  count **plus** the earlier code's (`RECORD_MISS`), and `attemptsRemaining`
  says so; at the cap both challenges are removed, so there is nothing to put
  back;
- a code put back carries its own count plus the wrong codes tried against the
  failed one (`FALL_BACK`), and is not put back at all if that reaches the cap;
- a challenge at its cap never accepts even the right code (`CONSUME` checks).
  With the shared count this and the `FALL_BACK` check cannot be reached
  through the API; they stay for a miss counted the old way (a rolling
  deploy), and are tested by setting that state in Redis;
- putting a code back never extends its life, and the per-network wrong-code
  count, the per-number count of sends that went out and the per-network
  request count all keep counting across restores.

Once the resend has settled as sent or delayed, the earlier code is gone and
the new code counts only its own wrong codes, as any fresh code does.
`OtpServiceTest` and `EmailOtpTest` ("a restored code keeps its attempt cap")
prove each point for phone and email, including guesses racing a failing resend.

And a person who fixes a typo — or tries again once we have topped up — is not
refused as "too many attempts". The per-network count is never given back: that is the limit
that stops one network hammering the endpoint, and it holds whether or not our
provider works. `OtpServiceTest` has a test for each row.

Email **sign-in** follows the same table, applied after the response and
reported through `GET /auth/otp/email/delivery/{requestId}` rather than in it
(§5), except that a rejection is applied as sent (§5, Signal 1); and a decoy
follows it too, from its own call to the decoy sink.

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
  code of the million can complete, a send that happens after the answer, and
  a decoy that matches a real send under every provider state, a refusal and a
  change of state, applied at one moment.
- `EmailSignInApiTest`, `SignInChannelSwitchApiTest` — the allowlist cannot be
  seen from outside, under a healthy, failing, refusing and changing provider,
  probed repeatedly; a switched-off channel refuses; step-up by email.
- `ProviderDisabledStartupTest` — the real application starts with each provider
  `disabled` alone, all six together, and with nothing set (`aa` disabled).
- `ProviderDisabledApiTest` — all six disabled, over HTTP: status `DISABLED` and
  never connected, every connect call 409 `provider_disabled` with nothing
  written, notifications recorded in-app only.
- `ProviderModeCheckTest` — `disabled` accepted, `off` refused by name, the four
  copies of the default modes in agreement, an unknown OTP sender refused.
- `NotificationDeliveryApiTest` — the number and every device a message goes
  to, a dead token forgotten, device and preference endpoints (validation,
  another person's device 404, audit), a switched-off channel, quiet hours, one
  message a day, a message held a week.
- `LiveEmailDeliveryApiTest` — the live SMTP adapter against a fake relay:
  delivery with the reason and the promise, no address, a quiet hour, a refused
  recipient, a refused from-address, an unreachable relay.
- `MessageTemplatesTest` — every template, channel and language says why; push
  and SMS never carry the body; drafts are never used.
- `ConnectCallsOutsideTransactionsApiTest` — no app-pool connection held while a
  connect provider hangs, and the budget ends the wait.

---

## The shape to copy

A live adapter is a class implementing the same interface, annotated
`@ConditionalOnProperty(name = "almira.providers.X.mode", havingValue = "live")`.
Nothing above it changes: not the service, not the controller, not the tests
that already run against the sandbox. That is the whole reason for building it
this way before the accounts exist.

[‹ Index](README.md)
