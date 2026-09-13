[‹ Going live](../../GO-LIVE.md) · [Doc 13](../13-providers-and-going-live.md)

# DigiLocker — documents

**Status: cannot be built today. Not watched failing.**

A person connects their DigiLocker, sees the documents issued to them, and
imports the ones they choose into the vault, encrypted like any upload. The
sandbox (`SandboxDocumentVault`) returns three real PDFs with a text layer, so
everything after the transport is exercised.

---

## (a) The interface contract

`backend/src/main/kotlin/tech/bhrigu/almira/provider/Providers.kt`:

```kotlin
interface DocumentVaultProvider {
    val mode: ProviderMode
    fun authorizationUrl(householdId: UUID, state: String): String
    fun exchange(householdId: UUID, code: String): ProviderSession
    fun list(session: ProviderSession): List<VaultDocument>
    fun fetch(session: ProviderSession, uri: String): ByteArray
}

data class ProviderSession(val token: String, val expiresAt: Instant, val scope: String)

data class VaultDocument(
    val uri: String,
    val name: String,
    val issuer: String,
    val docType: String,
    val issuedOn: LocalDate?,
    val mimeType: String,
    val sizeBytes: Int,
)
```

(Signatures are checked against the source by `GoLiveDocTest`.)

**Call sites** — all in `ConnectService`, all through `ProviderCalls` under the
provider name `digilocker`:

| API | Adapter call | Operation name | Retried after a timeout? |
|---|---|---|---|
| `POST …/connect/digilocker/start` | `authorizationUrl` | — (no network) | — |
| `POST …/connect/digilocker/complete` `{code}` | `exchange`, then `list` | `exchange`, `list` | `exchange`: **no** (a code redeems once). `list`: yes |
| `POST …/connect/digilocker/import` `{uris}` | `list`, then `fetch` per uri | `list`, `fetch` | yes |

**Timeouts** `ALMIRA_PROVIDER_DIGILOCKER_TIMEOUT=15s`, `MAX_ATTEMPTS=3`,
`RETRY_BACKOFF=500ms`. An import of *n* documents is *n + 1* provider calls
inside one request; at the defaults the worst case is roughly 46 s per call.

**How a live adapter classifies** (to be confirmed against the partner's actual
error codes, which have not been seen):

| Transport outcome | Kind | What the person sees |
|---|---|---|
| no answer within the timeout | `TIMEOUT` | 504 `provider_timeout` |
| connection refused, 5xx, maintenance | `UNAVAILABLE` | 503 `provider_unavailable` |
| invalid or already-used code, expired token, unknown document uri | `REJECTED` | 422 `provider_rejected` |
| our client credentials refused, partner access suspended, quota exhausted | `INSUFFICIENT_BALANCE` (account-level) | 503 `provider_account_unavailable`, ERROR `PROVIDER ACCOUNT PROBLEM` |

### Gaps a live adapter cannot paper over

Found by reading `ConnectService` in this stage. Harmless against a fake;
each is a real defect against the real service, and each needs a change above
the adapter.

1. **The OAuth `state` is never checked.** `startDocumentVault` generates one
   and stores it; `completeDocumentVault(householdId, code)` does not take it
   and so cannot compare. Against a real authorisation server that is a
   login-CSRF: someone can make a household import *their* DigiLocker. The
   `complete` endpoint needs a `state` field (additive to v1) and a comparison.
2. **The session token is stored in plaintext in the wrong column.**
   `upsertConnection` writes `session.token` into
   `provider_connections.external_ref`, a column V24 documents as "never a
   credential". V24 already has `access_token_enc` (for the household DEK) and
   `expires_at`; neither is written. A live bearer token in `external_ref` is
   readable by any household member through RLS and by anyone with a backup.
3. **Expiry is invented.** `activeSession` rebuilds a session with
   `expiresAt = now + 1h` on every read, whatever the real token says. There is
   no refresh; an expired token will come back from the provider as `REJECTED`,
   and the person will be told to start again — acceptable, but it should be
   read from `expires_at`, not made up.
4. **The authorisation URL is an in-app page.** A live `authorizationUrl`
   points at DigiLocker, with a registered redirect URI that lands on the web
   client and posts the code (and state) to `complete`. Whether PKCE is
   required is for the partner spec to say.

---

## (b) What the partner and the owner must supply

From the DigiLocker partner FAQ (research dated 2026-09):

| Requirement | Source | Who |
|---|---|---|
| **GST registration**, so the entity can be **GSTN-verified** — access is through API Setu, which requires it | FAQ Q32 | owner (business) |
| **No separate sandbox environment exists.** The first real call is against production, with a real person's documents | FAQ Q47 | — |
| **The server must be located in India** | FAQ Q9 | owner (hosting) — the deployment host in [Doc 17](../17-deploying.md) must be in an Indian region |
| Organisation onboarding and KYC on API Setu / the partner portal | partner | owner |
| `client_id` and `client_secret` | partner, after onboarding | → `ALMIRA_PROVIDER_DIGILOCKER_CLIENT_ID`, `…_CLIENT_SECRET` |
| A redirect URI on a public HTTPS host, registered with the partner | owner | the web client's origin |
| The API base URL and the document-list / fetch specification | partner | → `ALMIRA_PROVIDER_DIGILOCKER_BASE_URL` |

Because there is no sandbox, the contract test in step 4 below is the **only**
place failures can be rehearsed. Budget for that.

---

## (c) Flipping it live, in order

1. GST registration; GSTN verification; API Setu onboarding; receive client
   credentials and the spec.
2. Move (or confirm) the deployment host to an Indian region.
3. Close gaps 1–3 above: `state` on `complete`, token into `access_token_enc`
   with `expires_at`, expiry read not invented. Each with a test watched
   failing (remove the comparison / write plaintext → test red).
4. Write `LiveDocumentVault` against the spec, plus `LiveDocumentVaultContractTest`
   against a local fake HTTP server: a success, and one response per failure
   kind in the table above, each watched failing by breaking the
   classification.
5. Add `digilocker` to `ProviderModeCheck.implemented`; update this page and the
   `GO-LIVE.md` row (`GoLiveDocTest` will insist).
6. Configure:
   ```
   ALMIRA_PROVIDER_DIGILOCKER_MODE=live
   ALMIRA_PROVIDER_DIGILOCKER_BASE_URL=…
   ALMIRA_PROVIDER_DIGILOCKER_CLIENT_ID=…
   ALMIRA_PROVIDER_DIGILOCKER_CLIENT_SECRET=…
   ```
7. **Smoke test** (manual; no script exists — there is nothing to point one at):
   - Startup succeeds and `GET /api/v1/households/{id}/connect/providers`
     reports `digilocker` with `mode: LIVE`.
   - The owner connects their own DigiLocker from Settings, sees their real
     issued documents, imports one; the document opens in the vault and
     `provider_connections` has `mode = 'live'`, `access_token_enc` set and
     `external_ref` holding no token.
   - Negative: replay the same authorisation code to `complete` → 422
     `provider_rejected`, **one** attempt in the log (not retried).
   - Negative: a `complete` with a different `state` → refused.

---

## (d) Not verified — not watched failing

| What | Why it cannot be watched today |
|---|---|
| The OAuth flow, the document list and fetch against DigiLocker | No live adapter; no sandbox exists to write one against (FAQ Q47) |
| The error classification table above | The partner's error codes have not been seen; the table is a proposal |
| `state` verification | Not implemented (gap 1) |
| Token at rest encrypted | Not implemented (gap 2) |
| Hosting in India | A deployment property, not a code property; nothing checks it |

What would make it watched: steps 3–4 above, each test observed red with its
fix removed, then step 7's negatives observed against the real service.

[‹ Going live](../../GO-LIVE.md)
