# Going live

What each outside service needs before Almira can use it for real, what the
code already promises it, and what has never been checked.

**Every provider but email is a fake today.** `sandbox` means *our own in-process
imitation* — no request leaves the server. `disabled` means not offered on this
server: it starts, reports `DISABLED`, and answers every call with 409
`provider_disabled`. Account Aggregator is `disabled` by default — it is cut
from v1, because a production FIU must be regulated by RBI, SEBI, IRDAI or PFRDA. Setting any other provider to `live`
refuses to start, on purpose, because no live adapter exists for it
(`ProviderModeCheck`; `GoLiveDocTest` fails the build if that stops being true
while this file still says it). Email has one: `SmtpEmailSender`, for any SMTP
relay, tested against a fake SMTP server on loopback and never yet against a
real relay. So nothing below has been exercised against a real provider, and
nothing below should be read as finished.

[Doc 13](docs/13-providers-and-going-live.md) is the design: the sandboxes, the
switch, and the failure contract every adapter answers to. This file is the
checklist for the day the accounts exist. The research behind it is dated
2026-09 and cited where it is used.

---

## Where each one stands

| Provider | Config name | Can it be built today? | Blocked on | Detail |
|---|---|---|---|---|
| DigiLocker — documents | `digilocker` | **No** | GST registration → GSTN-verified entity on API Setu; a server in India; there is no separate sandbox | [providers/digilocker.md](docs/providers/digilocker.md) |
| Account Aggregator — holdings | `aa` | **No** — **cut from v1** (owner's decision, 2026-09-13); `disabled` by default | Company PAN + GSTIN for even the Setu sandbox; production FIU status needs an RBI/SEBI/IRDAI/PFRDA-regulated entity, which is the reason for the cut | [providers/account-aggregator.md](docs/providers/account-aggregator.md) |
| iOS push (APNs) | `push` | **No** | A paid Apple Developer membership; *and* device-token registration in the native app (the API and the table for it exist since V60) | [providers/push.md](docs/providers/push.md) |
| Real SMS delivery (India) | `sms` | **No** (the API is reachable; delivery is not) | GST registration → DLT entity, header and verbatim template registration; the release keystore first | [providers/sms.md](docs/providers/sms.md) |
| Android push (FCM) | `push` | Transport yes, usefully no | Self-serve Firebase project; the same missing registration call in the app as iOS | [providers/push.md](docs/providers/push.md#android-fcm) |
| WhatsApp — capture | `whatsapp` | Yes, against Meta's test number | A Meta developer account | [below](#whatsapp--reachable-now) |
| Email | `email` | **Built** — a live SMTP adapter exists, off unless `live` | A sending domain the owner controls, and a relay account | [below](#email--built-waiting-for-a-relay) |

**GST registration gates three of the four blocked rows** — DLT, DigiLocker via
API Setu, and the AA sandbox. It is the first thing to do if any of them is
wanted, and it is a business registration, not a technical step.

---

## What every live adapter has in common

Read these once; each provider page assumes them.

1. **An interface already exists** in
   `backend/src/main/kotlin/tech/almira/provider/` (or `auth/OtpSender.kt`).
   A live adapter implements it and is annotated
   `@ConditionalOnProperty(name = ["almira.providers.<name>.mode"], havingValue = "live")`.
   Nothing above the interface changes.
2. **It classifies, it does not retry.** Every transport error becomes a
   `ProviderFailure` of one of four kinds — `TIMEOUT`, `UNAVAILABLE`,
   `REJECTED`, `INSUFFICIENT_BALANCE` — and `ProviderCalls` owns the timeout,
   the retries and the backoff ([Doc 13, "When a provider fails"](docs/13-providers-and-going-live.md#when-a-provider-fails)).
   `INSUFFICIENT_BALANCE` is the *account-level* kind: use it for any refusal
   of **our** account (expired key, suspended, out of credit), not only money.
3. **Its `detail` never carries** a phone number, a one-time code, a message
   body, a token or a document. Exceptions end up in logs.
4. **Its name goes into `ProviderModeCheck.implemented`**, and only then does
   `live` stop refusing. `GoLiveDocTest` will fail at that moment and point
   here: update the provider's row and its "not watched failing" section in
   the same change.
5. **Config lives in three places kept in step**: `AlmiraProperties.kt`,
   `application.yml`, `.env.production.example` — and the default mode also in
   `ProviderModeCheck.DEFAULT_MODES`; `ProviderModeCheckTest` reads all four back.
   Env names are `ALMIRA_PROVIDER_<NAME>_*`. Modes are `disabled`, `sandbox`,
   `live`; `off` is refused.
6. **It gets a contract test against a fake HTTP server** replaying the
   provider's documented responses — success, and one per failure kind —
   before it is ever pointed at the real thing. That test is what gets watched
   failing; the real provider cannot be made to fail on demand.
7. **Interactive or background is already decided**
   ([Doc 13, "Interactive and background"](docs/13-providers-and-going-live.md#interactive-and-background)).
   A one-time code is one attempt under `almira.otp.send-timeout`, never retried.
   A notification is sent by the outbox worker, never in a request, and a
   notification channel's adapter must pass the idempotency key to its provider
   and declare `honoursIdempotencyKey` truthfully: `true` only if the provider
   drops repeats of a key, which makes the channel at-least-once; otherwise
   `false`, at-most-once.

---

## Reachable now

These can be built without a business registration. They are brief because
nothing about them is unusual.

### WhatsApp — reachable now

**Interface** `WhatsAppGateway` (`verify`, `parse`, `reply`) · **Timeouts**
10 s × 3, 500 ms backoff.

**The owner supplies**: a Meta developer account and app with the WhatsApp
product added (Meta provides a test sender number and allows a handful of
recipient numbers); a permanent system-user access token
(`ALMIRA_PROVIDER_WHATSAPP_API_KEY`); the app secret for
`X-Hub-Signature-256` (`ALMIRA_PROVIDER_WHATSAPP_WEBHOOK_SECRET`); a public
HTTPS webhook URL; for production, a verified business and approved templates.

**Gaps a live adapter must close, found reading the code**: the inbound route
is `/api/v1/households/{householdId}/connect/whatsapp/inbound`, but Meta calls
one URL for the whole app — the household has to come from the sender's
number, not the path; and Meta's webhook verification handshake (a `GET`
echoing a challenge) has no endpoint. Both are API additions.

**Not watched failing**: signature verification. The sandbox accepts every
payload. A live `verify` must be watched rejecting a wrong signature before
the webhook is exposed.

### Email — built, waiting for a relay

**Interface** `ChannelSender` with `channel = "email"` · **Live adapter**
`SmtpEmailSender` · **Timeouts** 10 s × 3 (but see idempotency below).

**The owner supplies**: a relay that speaks SMTP with STARTTLS (SES, Postmark,
Resend and a company server all do) and its credentials; a sending domain with
SPF, DKIM and DMARC published; a verified from-address. Then:

```
ALMIRA_PROVIDER_EMAIL_MODE=live
ALMIRA_PROVIDER_EMAIL_SMTP_HOST=email-smtp.ap-south-1.amazonaws.com
ALMIRA_PROVIDER_EMAIL_SMTP_PORT=587
ALMIRA_PROVIDER_EMAIL_SMTP_USERNAME=…
ALMIRA_PROVIDER_EMAIL_SMTP_PASSWORD=…
ALMIRA_PROVIDER_EMAIL_SMTP_FROM=Almira <reminders@your-domain>
```

`live` refuses to start without a host and a from-address. STARTTLS is required
unless `ALMIRA_PROVIDER_EMAIL_SMTP_START_TLS=false`, which only a relay on the
same host should need. The deployment must pass these variables through to the
application (`deploy/docker-compose.prod.yml` does not list them yet).

**Who it reaches**: the address on the person's account, looked up when the
message is sent ([Doc 13, "Who a message is for"](docs/13-providers-and-going-live.md#who-a-message-is-for)).
An account with no address records `skipped`, `no_recipient`. Email is also the
closed alpha's sign-in path ([Doc 13 §5](docs/13-providers-and-going-live.md#sign-in-codes-by-email--the-closed-alpha)),
and a live adapter is what that needed.

**Idempotency**: SMTP has no send-side de-duplication, so the adapter declares
`honoursIdempotencyKey = false` and email is at-most-once: a timeout is recorded,
not retried. The key becomes the Message-ID.

**Watched**: against `FakeSmtpServer` in `LiveEmailDeliveryApiTest` — delivery
with the why-line and the promise, a refused recipient (`rejected`), a refused
from-address (`insufficient_balance`), an unreachable relay (`unavailable`), no
address (`no_recipient`), a quiet hour. **Not watched failing**: a real relay,
TLS negotiation, authentication, a 4xx greylisting reply, bounce handling (a
bounce arrives later, by email, and nothing reads it).

### Android push — reachable, not useful yet

Covered with iOS in [providers/push.md](docs/providers/push.md#android-fcm):
the Firebase side is self-serve, the device-token side does not exist.

---

## Nothing here is "watched failing"

Per [Doc 19](docs/19-pen-test-pack.md), no check is trusted until it has been
watched failing. For every provider above the honest status is **not watched
failing**: there is no live adapter to watch, and the sandbox's failure
switch (`SandboxFaults`) proves the *policy* — retries, user messages, the OTP
counter table — not any real provider's behaviour. Each provider page ends
with what would have to happen for that to change.
