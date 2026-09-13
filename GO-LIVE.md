# Going live

What each outside service needs before Almira can use it for real, what the
code already promises it, and what has never been checked.

**Every provider is a fake today.** `sandbox` means *our own in-process
imitation* — no request leaves the server. `disabled` means not offered on this
server: it starts, reports `DISABLED`, and answers every call with 409
`provider_disabled`. Account Aggregator is `disabled` by default — it is cut
from v1, because a production FIU must be regulated by RBI, SEBI, IRDAI or PFRDA. Setting any provider to `live`
refuses to start, on purpose, because no live adapter exists for any of them
(`ProviderModeCheck`; `GoLiveDocTest` fails the build if that stops being true
while this file still says it). So nothing below has been exercised against a
real provider, and nothing below should be read as finished.

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
| iOS push (APNs) | `push` | **No** | A paid Apple Developer membership; *and* device-token registration, which does not exist in any client or in the API | [providers/push.md](docs/providers/push.md) |
| Real SMS delivery (India) | `sms` | **No** (the API is reachable; delivery is not) | GST registration → DLT entity, header and verbatim template registration; the release keystore first | [providers/sms.md](docs/providers/sms.md) |
| Android push (FCM) | `push` | Transport yes, usefully no | Self-serve Firebase project; the same missing device-token registration as iOS | [providers/push.md](docs/providers/push.md#android-fcm) |
| WhatsApp — capture | `whatsapp` | Yes, against Meta's test number | A Meta developer account | [below](#whatsapp--reachable-now) |
| Email | `email` | Yes, once a provider is chosen | A sending domain the owner controls | [below](#email--reachable-once-a-provider-is-chosen) |

**GST registration gates three of the four blocked rows** — DLT, DigiLocker via
API Setu, and the AA sandbox. It is the first thing to do if any of them is
wanted, and it is a business registration, not a technical step.

---

## What every live adapter has in common

Read these once; each provider page assumes them.

1. **An interface already exists** in
   `backend/src/main/kotlin/tech/bhrigu/almira/provider/` (or `auth/OtpSender.kt`).
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
7. **Delivery is still synchronous.** Worst case per call is
   `max-attempts × timeout` plus backoff (≈31 s for SMS at the defaults).
   Before a notification channel goes live, move delivery onto the reminder
   worker or lower its attempts ([Doc 13](docs/13-providers-and-going-live.md#when-a-provider-fails)).

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

### Email — reachable once a provider is chosen

**Interface** `ChannelSender` with `channel = "email"` · **Timeouts** 10 s × 3.

**The owner supplies**: a choice of provider (SES, Postmark, Resend…) and its
API key (`ALMIRA_PROVIDER_EMAIL_API_KEY`); a sending domain with SPF, DKIM and
DMARC published; a verified from-address.

**Gap**: like SMS and push, `RecordingNotifier` calls every sender with
`recipientHint = null` — there is no lookup from a user to an address yet.
Email **is** a sign-in path now, for the closed alpha: one-time codes to
allowlisted addresses, built on this channel ([Doc 13 §5](docs/13-providers-and-going-live.md#sign-in-codes-by-email--the-closed-alpha)).
It cannot sign anybody in outside development until a live email adapter
exists, so this provider is also the alpha's blocker. A one-time code passes
the full address as `recipientHint`; notifications still pass null.

**Not watched failing**: everything; no adapter exists.

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
