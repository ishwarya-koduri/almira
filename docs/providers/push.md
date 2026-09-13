[‹ Going live](../../GO-LIVE.md) · [Doc 13](../13-providers-and-going-live.md)

# Push — iOS (APNs) and Android (FCM)

**Status: iOS cannot be built today. Android's transport can, but has nothing to
send to. Not watched failing.**

Push is the reminder or emergency-access notice arriving on a phone. Today it is
recorded in `outbound_messages` and shown in the in-app list; the sandbox
(`SandboxPushSender`) records that a push would have gone, and nothing else.

The blocker common to both platforms is not a credential. **Almira does not
collect device tokens** — no client asks for notification permission or
registers for remote notifications, there is no table to keep a token in, and
no endpoint to send one to. Until that exists, a live push sender would
authenticate perfectly and have no address.

---

## (a) The interface contract

`backend/src/main/kotlin/tech/bhrigu/almira/provider/Delivery.kt`, the same
interface as SMS and email:

```kotlin
interface ChannelSender {
    val channel: String
    val mode: ProviderMode
    val honoursIdempotencyKey: Boolean
    fun send(notification: OutboundNotification, recipientHint: String?, idempotencyKey: String): String
}
```

and `reminder/Notifier.kt`:

```kotlin
data class OutboundNotification(
    val userId: UUID,
    val householdId: UUID?,
    val reminderId: UUID?,
    val template: String,
    val title: String,
    val body: String,
    val idempotencyKey: String? = null,
)
```

A push sender has `channel = "push"`, returns the provider's name for the audit
row, and is called by the `NotificationOutbox` worker through `ProviderCalls`
under the provider name `push` (operation `notify`), from a row
`RecordingNotifier.deliver` queued — never inside a request. **Timeouts** 10 s
× 3, 500 ms backoff. Callers today: `ReminderWorker`, `StillTrueSweep` and
`EmergencyService`.

**`honoursIdempotencyKey = false`.** Neither APNs nor FCM drops a repeated send
(a collapse id replaces a notification on screen; it does not stop a second
one arriving), so push is **at-most-once**: a timeout is recorded, not retried,
and a send cut off by a crash is recorded unconfirmed, never re-sent
([Doc 13](../13-providers-and-going-live.md#idempotency-keys-and-what-they-guarantee)).
A duplicate push is the nag the product promises not to be; a lost one is still
in the in-app list.

### The gap: there is no recipient

`RecordingNotifier` calls every sender as `send(notification, null)`. The
interface's only notion of a recipient is `recipientHint: String?`, and nothing
fills it. For push, "the recipient" is *zero or more device tokens per user*,
per platform, each of which can be revoked by the platform at any time.

**Not built in this stage, deliberately.** It is not small: it is a migration
(a new table with row-level security so a user reads and writes only their
own tokens), an additive v1 endpoint, and native code in both apps. What it
would need, so the first person to build it starts from the constraints:

| Piece | Shape | Why |
|---|---|---|
| Storage | per row: `user_id`, `platform` (`ios` / `android`), an installation id chosen by the app, the token, for iOS the APNs environment (`development` / `production`), `app_version`, `last_seen_at` | An install re-registers on launch; tokens rotate; a development build's APNs token is refused by the production gateway and vice versa |
| Registration | `PUT /api/v1/me/devices/{installationId}` `{platform, token, environment}`; `DELETE` on sign-out | Idempotent by installation, so a re-launch does not multiply rows |
| Resolution | the push sender (or the notifier) looks up the user's tokens and sends to each | `recipientHint` is a single string; a user can have two phones |
| Pruning | a `REJECTED` answer that means "this token is dead" deletes that token | Otherwise every reminder is sent, and refused, forever |
| Payload | the notification **title only**, never `body` | `body` carries amounts and institutions. A push payload transits Apple's or Google's servers and appears on a lock screen; `outbound_messages` already refuses to store the body for the same reason |

### How a live adapter classifies

APNs answers per request over HTTP/2; FCM HTTP v1 answers per message. The
mapping below is from the providers' published error names as generally
documented, not from responses seen here.

| Outcome | APNs | FCM | Kind |
|---|---|---|---|
| no answer in time | — | — | `TIMEOUT` |
| provider down | 500, 503 | 500, 503, `UNAVAILABLE` | `UNAVAILABLE` |
| this token is dead or wrong | 410 `Unregistered`, 400 `BadDeviceToken`, `DeviceTokenNotForTopic` | 404 `UNREGISTERED`, 400 `INVALID_ARGUMENT` on the token | `REJECTED` (and prune the token) |
| **our** credential refused | 403 `InvalidProviderToken`, `ExpiredProviderToken` | 401, 403 `SENDER_ID_MISMATCH` / permission denied | `INSUFFICIENT_BALANCE` — the account-level kind; the name predates push |
| throttled | 429 `TooManyRequests` | 429 `QUOTA_EXCEEDED` | `UNAVAILABLE` (retry later) |

A send to a user with several tokens is several provider calls; one dead token
must not fail the others. That loop lives in the sender, and only the
per-token call goes through `ProviderCalls`.

---

## (b) What the owner must supply

### iOS (APNs)

| Requirement | Notes |
|---|---|
| **A paid Apple Developer Program membership** ($99/yr) | Nothing below is obtainable without it (research 2026-09) |
| An **APNs authentication key** (`.p8`) | Downloadable **once**. Back it up like the release keystore — a password manager and one offline copy |
| The **key id** | Shown with the key |
| The **team id** | From the membership |
| The **bundle id** | `tech.bhrigu.almira` (from `app/iosApp/iosApp.xcodeproj`); the APNs topic |
| The Push Notifications capability on that app id, and the `aps-environment` entitlement in the iOS app | Absent today |

No config key exists for a `.p8` path, key id or team id. `…_PUSH_API_KEY` is a
single string and does not fit. They must be added to `AlmiraProperties.kt`,
`application.yml` and `.env.production.example` together — and because one
`push` provider covers two platforms, probably as `push.apns.*` and
`push.fcm.*`, or as two providers. That is a decision for whoever writes it.

### Android (FCM)

Self-serve (research 2026-09): a Firebase project, the Android app
(`tech.bhrigu.almira`) added to it, `google-services.json` in the Android app,
and a **service-account JSON** for the server to call FCM HTTP v1. The service
account JSON is a credential: environment or secrets manager, never the repo.

FCM can also deliver to iOS if the APNs key is uploaded to Firebase. That
removes a server-side APNs client; it does not remove the Apple membership.

---

## (c) Flipping it live, in order

1. **Device tokens first**, for the platform being turned on: the storage, the
   endpoint and the app code above, each with tests — a user cannot read or
   overwrite another's token (watched failing by loosening the policy), a
   re-registration does not add a row.
2. Payload rule: a test that a push request never contains
   `OutboundNotification.body` (watched failing by putting it in).
3. Obtain the credentials in (b); add the config keys to all three files.
4. `ApnsPushSender` / `FcmPushSender` plus contract tests against a fake HTTP
   server replaying each row of the classification table, including the prune
   on a dead token. Each watched failing.
5. Delivery is already off the request thread (the notification outbox,
   [Doc 13](../13-providers-and-going-live.md#interactive-and-background)); keep
   `honoursIdempotencyKey = false` unless the chosen provider changes that.
6. Add `push` to `ProviderModeCheck.implemented`; update this page and
   `GO-LIVE.md`.
7. `ALMIRA_PROVIDER_PUSH_MODE=live` plus credentials.
8. **Smoke test** (manual): a release-signed build on the owner's phone
   registers a token; a reminder due now arrives on the lock screen showing
   only its title; `outbound_messages` has a `push` row `sent` with the live
   provider's name. Negative: uninstall the app, trigger another reminder —
   the row is `failed` / `rejected` and the token is gone. For iOS, repeat with
   a development build against the APNs development environment and confirm
   the production gateway refuses its token.

---

## (d) Not verified — not watched failing

| What | Why |
|---|---|
| Any APNs call | No membership, no key, no adapter |
| Any FCM call | No Firebase project, no adapter |
| Device-token registration, storage and pruning | Does not exist anywhere |
| Title-only payloads | No push payload is built anywhere to check |
| The classification table | Error names from public documentation; no response seen |

What would make it watched: steps 1, 2 and 4, each test observed red with its
fix removed, then step 8's negative on a real device.

[‹ Going live](../../GO-LIVE.md)
