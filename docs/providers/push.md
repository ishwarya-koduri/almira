[‹ Going live](../../GO-LIVE.md) · [Doc 13](../13-providers-and-going-live.md)

# Push — iOS (APNs) and Android (FCM)

**Status: iOS cannot be built today. Android's transport can, but has nothing to
send to. Not watched failing.**

Push is the reminder or emergency-access notice arriving on a phone. Today it is
recorded in `outbound_messages` and shown in the in-app list; the sandbox
(`SandboxPushSender`) records that a push would have gone, and nothing else.

The blocker common to both platforms is not a credential. **No app registers a
device token yet.** The server has the table, the endpoint, the per-device send
and the pruning (V60, below); neither native app asks for notification
permission or calls the endpoint. Until one does, a live push sender would
authenticate perfectly and find no device to send to.

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

### Recipients: built on the server, not yet in the apps

Since V60 the server side of device tokens exists (docs/13, "Who a message is
for"; known-issues 13 resolved):

| Piece | What exists | Where |
|---|---|---|
| Storage | `user_devices`: `user_id`, `installation_id` (chosen by the app), `platform` (`ios` / `android`), `token`, `environment` (required for iOS), `app_version`, `last_seen_at`; one row per installation; own rows only under RLS, guests refused | `db/migrations/V60__notification_delivery.sql`, asserted in `db/tests/rls_privacy_test.sql` |
| Registration | `PUT /api/v1/me/devices/{installationId}` `{platform, token, environment, appVersion}`, idempotent by installation; `GET /api/v1/me/devices` (never the token); `DELETE` on sign-out. At most 10 per person, the longest unseen let go. Audited | `provider/NotificationSettings.kt` |
| Resolution | the outbox worker looks up every token for the person and calls the sender once per device, each with the row's key plus the installation id; the row is `sent` if any device took it | `NotificationOutbox.sendToEach`, `DeliveryDirectory` |
| Pruning | a `REJECTED` answer for a device deletes that device | the same |
| Payload | the title, and the short reason it came as its text — never `body` | `MessageTemplates.compose` |

What is **not** built: the native apps asking for notification permission and
calling the registration endpoint. Until they do, `user_devices` is empty in
practice, and a live push sender would record `skipped`, `no_recipient`.

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

1. **Device tokens first**, for the platform being turned on: the app code that
   asks permission and calls `PUT /api/v1/me/devices/{installationId}` on launch
   and `DELETE` on sign-out. The server side is built and tested
   (`NotificationDeliveryApiTest`, the RLS suite); what is left there is to
   watch the policy tests fail with the policy loosened.
2. Payload rule: the live sender must send `notification.title` and
   `notification.body` exactly as the worker composed them — for push the body
   is already the short reason, not the caller's body. Add a contract test that a
   push request never contains a caller's body (watched failing by putting it in).
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
| Device-token registration from an app | Neither app calls the endpoint |
| Storage, per-device send and pruning | Tested through the sandbox and the RLS suite, not watched failing |
| The push text is never the caller's body | Unit-tested in `MessageTemplatesTest`; no real payload is built |
| The classification table | Error names from public documentation; no response seen |

What would make it watched: steps 1, 2 and 4, each test observed red with its
fix removed, then step 8's negative on a real device.

[‹ Going live](../../GO-LIVE.md)
