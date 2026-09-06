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
4. `ALMIRA_PROVIDERS_DIGILOCKER_MODE=live`, plus `..._CLIENT_ID` / `..._CLIENT_SECRET`.

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
4. `ALMIRA_PROVIDERS_AA_MODE=live` plus the gateway URL and certificate paths.

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
4. `ALMIRA_PROVIDERS_WHATSAPP_MODE=live` plus token and app secret.

---

## 4 · SMS — one-time codes and reminders

**Interface** `ChannelSender` (`channel = "sms"`) · **Sandbox** `SandboxSmsSender`

In development the OTP is returned in the response and printed to the log, which
is why the whole sign-in flow works with no provider at all. The sandbox sender
records that a message would have gone, and never its body: an SMS body carries
the amount and the institution, and logs are the least protected thing here.

**To go live** — India-specific, and the part people forget:

1. A provider account (Twilio, MSG91, Kaleyra…).
2. **DLT registration on an Indian telecom operator's portal**: register the
   entity, the sender ID, and *every message template*. Unregistered templates
   are dropped by the operator, silently.
3. Map each `template` to its registered DLT template id.
4. `ALMIRA_PROVIDERS_SMS_MODE=live` plus provider credentials and the sender id.

---

## 5 · Email and push

**Interfaces** `ChannelSender` (`email`, `push`) · **Sandboxes** `SandboxEmailSender`, `SandboxPushSender`

**Email to go live**: a sending domain with SPF, DKIM and DMARC published; a
provider account (SES, Postmark, Resend); a verified from-address;
`ALMIRA_PROVIDERS_EMAIL_MODE=live`.

**Push to go live**: an FCM project and service-account JSON; an APNs key for
iOS; and — the piece that does not exist yet — **device token registration**,
which needs the native app. Until then push has nowhere to go, which is why it
is last.

---

## What "the stand-in" means now

Notifications remain a stand-in, but not an unverifiable one. Every outbound
message is recorded in `outbound_messages` — channel, template, title, status,
never a body — so the in-app list works today, a test can assert that the person
who should have been told was told, and switching a channel on changes where a
row goes rather than whether it exists.

Reading that table is restricted to the person the message was for. The log of
what somebody was told is as personal as what it was about.

---

## The shape to copy

A live adapter is a class implementing the same interface, annotated
`@ConditionalOnProperty(name = "almira.providers.X.mode", havingValue = "live")`.
Nothing above it changes: not the service, not the controller, not the tests
that already run against the sandbox. That is the whole reason for building it
this way before the accounts exist.

[‹ Index](README.md)
