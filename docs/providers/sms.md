[‹ Going live](../../GO-LIVE.md) · [Doc 13 §4](../13-providers-and-going-live.md#4--sms--one-time-codes-and-reminders) · [MOVE.md](../../MOVE.md#the-release-keystore--the-order-matters-and-it-is-easy-to-get-wrong)

# Real SMS delivery (India)

**Status: cannot be built today — an SMS provider's API is reachable, delivery
to Indian numbers is not. Not watched failing.**

This is the one that blocks everything else. Sign-in is phone plus one-time
code, and the only code sender, `LoggingOtpSender`, works only with
`ALMIRA_ENV=development`. **A deployed Almira cannot sign anybody in until this
page is done** ([Doc 17 §5](../17-deploying.md)).

SMS carries two kinds of message through two interfaces, both on the `sms`
provider's account, timeout and retry policy.

---

## (a) The interface contract

### One-time codes — `backend/src/main/kotlin/tech/bhrigu/almira/auth/OtpSender.kt`

```kotlin
interface OtpSender {
    fun send(phone: String, code: String)
    val available: Boolean get() = true
    val exposesCodeForDevelopment: Boolean get() = false
}
```

- `phone` is E.164 as `OtpService` normalised it; `code` is `almira.otp.length`
  digits (6). The adapter composes the message body. The code is generated,
  hashed and checked by `OtpService` — the adapter only delivers.
- A live sender leaves `exposesCodeForDevelopment` false. `available` is true
  only when it is configured.
- Called as `calls.call("sms", "otp") { sender.send(phone, code) }` — the same
  path for sign-in and step-up; the sender cannot tell them apart, so one DLT
  template serves both.
- **A timeout is retried** (the operation is idempotent by default). The same
  code may therefore arrive twice, and be billed twice. That is the intended
  trade: the code is the same, and a person with no code is worse.

What each outcome does, already implemented and tested in `OtpServiceTest` and
`ProviderFailureApiTest` against `SandboxFaults`:

| Kind | Response | Challenge / cooldown / per-number count | Per-network count |
|---|---|---|---|
| `TIMEOUT` | 504 `otp_delivery_delayed` | kept | kept |
| `UNAVAILABLE` | 503 `otp_provider_unavailable` | removed / lifted / given back | kept |
| `REJECTED` | 422 `otp_delivery_failed` | removed / lifted / given back | kept |
| `INSUFFICIENT_BALANCE` | 503 `otp_service_unavailable` + ERROR `PROVIDER ACCOUNT PROBLEM` | removed / lifted / given back | kept |

### Reminders — `backend/src/main/kotlin/tech/bhrigu/almira/provider/Delivery.kt`

```kotlin
interface ChannelSender {
    val channel: String
    val mode: ProviderMode
    fun send(notification: OutboundNotification, recipientHint: String?): String
}
```

`channel = "sms"`, provider `sms`, operation `notify`, result recorded in
`outbound_messages`. See [push.md](push.md#a-the-interface-contract) for
`OutboundNotification`.

**Timeouts** `ALMIRA_PROVIDER_SMS_TIMEOUT=10s`, `MAX_ATTEMPTS=3`,
`RETRY_BACKOFF=500ms`: worst case ≈31 s inside the sign-in request.

### How a live adapter classifies

Provider-specific; the shape every Indian SMS gateway shares:

| Outcome | Kind |
|---|---|
| no answer in time | `TIMEOUT` |
| connection refused, 5xx | `UNAVAILABLE` |
| invalid or non-mobile number, number on the operator's do-not-disturb list for this category, template id refused *synchronously* | `REJECTED` |
| out of credit, API key refused, account suspended, sender id not approved for the account | `INSUFFICIENT_BALANCE` (account-level) |

**The dangerous case is not in that table.** An operator drops a message whose
text does not match its registered template **silently** (research 2026-09).
The gateway has usually already said "accepted" by then, so `send` returns
normally, the row says `sent`, and nobody receives anything. Only a delivery
report reveals it, and **the contract has no delivery reports**: `send` is
fire-and-classify. That is a gap to close before relying on SMS for anything but
sign-in, where the person's own "I didn't get it" is the report.

### Gaps a live sender cannot paper over

Found reading the code in this stage.

1. **No `almira.otp.provider` value other than `log` exists, and choosing one
   crashes startup.** Run on 2026-09-13 against the jar from `94d839e` with
   `ALMIRA_OTP_PROVIDER=sms`:
   `Parameter 1 of constructor in tech.bhrigu.almira.auth.OtpService required a bean of type 'tech.bhrigu.almira.auth.OtpSender' that could not be found.`
   `ProviderModeCheck` checks `almira.providers.*.mode` but not
   `almira.otp.provider`, so this is the obscure failure that check exists to
   replace. A live sender should read `almira.providers.sms.mode=live` (one
   switch), or the check must learn the second property.
2. **The body is not composed anywhere, and the app hash has no home.** The
   one-time-code SMS must be, verbatim to the registered template,
   `<#> 123456 is your Almira code. <app hash>` within 140 bytes
   ([Doc 13 §4](../13-providers-and-going-live.md#the-one-time-code-template-has-a-fifth-requirement)).
   No config key holds the eleven-character hash, and it differs between
   debug, locally signed release and Play-re-signed builds.
3. **One template id for many templates.** `…_SMS_TEMPLATE_ID` is a single
   value. DLT registers each message text separately: the one-time code and
   every reminder `template` need their own id.
4. **No recipient for reminders.** `RecordingNotifier` calls
   `send(notification, null)`; there is no lookup from `userId` to a phone
   number. Sign-in is unaffected (`OtpSender` is given the phone).
5. **Delivery is synchronous** — see `GO-LIVE.md`, "What every live adapter has
   in common", item 7.

---

## (b) What the owner must supply

| Requirement | Notes |
|---|---|
| **GST registration** | Gates DLT entity registration (research 2026-09) |
| **DLT registration of the entity** on a telecom operator's DLT portal | Yields the principal entity id |
| **A registered sender id (header)** | → `ALMIRA_PROVIDER_SMS_SENDER_ID` |
| **Every message text registered verbatim as a content template** | Unregistered or mismatching texts are dropped silently. → a template id per template (gap 3) |
| **The release app hash in the one-time-code template** | Only after the release keystore exists and is backed up — see ordering below |
| An SMS gateway account (MSG91, Kaleyra, Twilio…) linked to that DLT entity | → `ALMIRA_PROVIDER_SMS_BASE_URL`, `…_API_KEY` |
| Credit on that account, and an alert on `PROVIDER ACCOUNT PROBLEM` | An empty balance is a sign-in outage |

### The ordering that must not be shortcut

From [MOVE.md](../../MOVE.md#the-release-keystore--the-order-matters-and-it-is-easy-to-get-wrong),
and repeated because doing it out of order costs days of re-registration:

1. Be on the **personal machine**, project verified there.
2. **Create the release keystore** there.
3. **Back it up** — password manager and one offline copy — before it signs anything.
4. **Derive the release app hash** from it (a release build shows its own on the
   sign-in screen). If the app ships through Play with Play App Signing, the
   hash that matters is from Play's certificate.
5. **Only then register the DLT one-time-code template**, with that hash as
   literal text.

The debug hash is useful for a development template; it is not the one to
register for users.

---

## (c) Flipping it live, in order

1. GST registration → DLT entity → header → gateway account.
2. The keystore ordering above → register the one-time-code template with the
   release hash; register each reminder template.
3. Close gaps 1–3: one switch for the live OTP sender (and `ProviderModeCheck`
   refusing a non-`log` `almira.otp.provider` with a sentence); config keys for
   the app hash and per-template ids in all three config files; a test that
   the composed body matches the registered template byte for byte and fits
   140 bytes. Each watched failing (change a character → red).
4. `LiveSmsOtpSender` (and, when reminders go out by SMS, a `ChannelSender`)
   plus a contract test against a fake HTTP gateway: success and one response
   per row of the classification table, each watched failing by breaking the
   mapping. No code, phone number or body in any exception `detail` — assert it,
   as `OtpCodeNeverLeaksTest` does.
5. Add `sms` to `ProviderModeCheck.implemented`; update this page and `GO-LIVE.md`.
6. Configure:
   ```
   ALMIRA_ENV=production
   ALMIRA_PROVIDER_SMS_MODE=live
   ALMIRA_PROVIDER_SMS_BASE_URL=…
   ALMIRA_PROVIDER_SMS_API_KEY=…
   ALMIRA_PROVIDER_SMS_SENDER_ID=…
   ALMIRA_PROVIDER_SMS_TEMPLATE_ID=…      # until gap 3 splits it
   ```
7. **Smoke test**:
   - `POST /api/v1/auth/otp/request` for the owner's phone returns 200 with **no**
     `developmentCode`.
   - The text arrives from the registered header, and on a **release-signed**
     Android build the code fills itself in. Autofill failing while the text
     arrives means the hash in the template is wrong — the silent failure.
   - `./scripts/smoke-prod.sh https://…` with `SMOKE_CODE_A` / `SMOKE_CODE_B`
     read off two real phones passes end to end (the script already supports
     real codes).
   - Negatives, the ones that make it *watched*: a well-formed number that is
     not a mobile → 422 `otp_delivery_failed` and the owner can immediately
     retry with the right number; a deliberately wrong API key → 503
     `otp_service_unavailable` and one ERROR `PROVIDER ACCOUNT PROBLEM: sms
     refused otp on account grounds` in the log.

---

## (d) Not verified — not watched failing

| What | Why |
|---|---|
| Any message reaching an Indian handset | No gateway, no DLT registration, no adapter |
| The classification table | Gateway-specific codes not seen |
| Silent operator drops being noticed | No delivery reports in the contract; cannot be detected today at all |
| The release app hash and autofill on a release build | No release keystore exists yet (MOVE.md) |
| Startup refusing a bad `almira.otp.provider` with a sentence | Not implemented; it crashes obscurely (gap 1, verified) |
| Reminder SMS to the right person | No recipient lookup (gap 4) |

What `SandboxFaults` *does* prove, and is not to be confused with the above:
given each failure kind, `OtpService` answers correctly and keeps or returns
the right counters. That is tested (commit `94d839e` records which of those
tests were watched failing); a real provider producing those kinds is not.

What would make it watched: step 3 and 4's tests observed red with their fixes
removed, then step 7's negatives observed against the real gateway.

[‹ Going live](../../GO-LIVE.md)
