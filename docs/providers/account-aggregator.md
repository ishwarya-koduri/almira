[‹ Going live](../../GO-LIVE.md) · [Doc 13](../13-providers-and-going-live.md)

# Account Aggregator — holdings

**Status: cut from v1 — disabled by default. Cannot be built today. Not watched failing.**

A person consents, through the RBI Account Aggregator network, to share their
bank, deposit and fund data; Almira imports it as ordinary records at the
household's default visibility, with the provider's figure as a dated
valuation. The sandbox (`SandboxAccountAggregator`) makes the person approve,
refuses to fetch before approval, and returns data shaped like FI data.

---

## The cut

**The owner's decision (2026-09-13): Account Aggregator is cut from v1.** The
reason: production access as a Financial Information User requires the FIU to be
regulated by RBI, SEBI, IRDAI or PFRDA (research dated 2026-09). A family asset
registry is not, and is unlikely to become, one of those.

**What "cut" means here** — off by default and hidden, with the code path kept
for a future regulated partner (an FIU that offers the data onward under its own
licence, for instance — unexplored):

- `almira.providers.aa.mode` defaults to **`disabled`** in `application.yml`,
  `AlmiraProperties` and `.env.production.example`
  (`ALMIRA_PROVIDER_AA_MODE=disabled`). The application starts normally.
- `GET …/connect/providers` reports `account_aggregator` with `mode: DISABLED`,
  `connected: false` (even for a household that connected it in sandbox before)
  and no `sandboxNote`.
- `POST …/aa/consent`, `GET …/aa/consent` and `POST …/aa/import` answer
  **409 `provider_disabled`**, `details.provider = "aa"`: "The Account Aggregator
  isn't offered on this server." Nothing is written — no pending connection, no
  audit row — and the adapter is never called.
- The web client leaves every provider the server reports as `DISABLED` out of
  Settings → Connected services. It reads the status; nothing about AA is
  hard-coded, so a server with `aa: sandbox` shows it again. The native app has
  never had a provider or connect surface, so it has nothing to hide.
- **Kept, deliberately**: `AccountAggregatorClient`, `SandboxAccountAggregator`,
  the three connect endpoints, `ConnectService`'s import logic, and their tests
  (`ProviderApiTest` and `ProviderFailureApiTest` ask for `aa.mode=sandbox`,
  `SandboxFailureMatrixTest` builds the sandbox directly).

To try it locally: `ALMIRA_PROVIDER_AA_MODE=sandbox`.

Before this, the cut could not be made: `off` passed `ProviderModeCheck` and
then the application failed to start on a missing `AccountAggregatorClient`
bean (known-issues 11, watched on 2026-09-13 with the jar from `94d839e`). `off`
is now refused with a sentence naming `disabled`.

---

## (a) The interface contract

`backend/src/main/kotlin/tech/bhrigu/almira/provider/Providers.kt`:

```kotlin
interface AccountAggregatorClient {
    val mode: ProviderMode
    fun requestConsent(householdId: UUID, request: ConsentRequest): ConsentHandle
    fun consentStatus(handle: String): ConsentHandle
    fun fetch(handle: String): List<DiscoveredHolding>
}

data class ConsentRequest(
    val purpose: String,
    val fiTypes: List<String>,
    val fromDate: LocalDate,
    val toDate: LocalDate,
)

data class ConsentHandle(
    val handle: String,
    val status: String,
    val approvalUrl: String?,
    val expiresAt: Instant,
)

data class DiscoveredHolding(
    val fiType: String,
    val institution: String,
    val maskedAccount: String,
    val displayName: String,
    val currentValue: java.math.BigDecimal?,
    val currency: String,
    val asOf: LocalDate,
    val detail: Map<String, String> = emptyMap(),
)
```

**Call sites** — `ConnectService`, provider name `aa`:

| API | Adapter call | Operation | Retried after a timeout? |
|---|---|---|---|
| `POST …/connect/aa/consent` | `requestConsent` | `consent` | **no** — a second consent leaves the person two to approve |
| `GET …/connect/aa/consent` | `consentStatus` | `consent-status` | yes |
| `POST …/connect/aa/import` | `fetch` | `fetch` | yes |

`ConsentRequest` is fixed in `ConnectService`: purpose "Personal finance
management", FI types `DEPOSIT`, `TERM_DEPOSIT`, `MUTUAL_FUNDS`, `EQUITIES`, the
last twelve months. `status` is the provider's string; only `"ACTIVE"` is acted
on. **Timeouts** 20 s × 3, 500 ms backoff.

A refusal that is *not* a `ProviderFailure` from `fetch` (the sandbox's "consent
is not active") becomes 400 `consent_not_active`; a `ProviderFailure` becomes
its own answer (504 / 503 / 422 / 503-account, as for DigiLocker).

### Where the real network does not fit this shape

Not verified against any AA's specification; stated so the first person with
the spec knows where to look.

- **Polling versus notification.** The interface polls `consentStatus`. The AA
  network, as generally described, notifies the FIU of consent and data-ready
  events by calling it back. That needs inbound endpoints (additive) and
  request-signature verification on them.
- **Fetching is a session, and the data is encrypted to the FIU.** A real fetch
  is a request for a data session, a wait, then retrieval of data encrypted to
  a key the FIU holds. `fetch(handle)` returning holdings synchronously hides
  all of that inside the adapter, which is fine only if the wait fits within
  the timeout — it may not.
- **The consent handle is stored in `external_ref`**, which is correct: a
  consent handle is not a credential.

---

## (b) What the partner and the owner must supply

| Requirement | For | Source |
|---|---|---|
| **Company PAN and GSTIN** | even the Setu AA sandbox | research 2026-09 |
| **Regulation by RBI, SEBI, IRDAI or PFRDA** | production FIU status | research 2026-09 — the blocker |
| FIU onboarding with an AA (Sahamati) | production | partner |
| Client credentials and the gateway's signing certificate / keys | sandbox and production | partner → `ALMIRA_PROVIDER_AA_CLIENT_ID`, `…_CLIENT_SECRET`, `…_BASE_URL` (no config key for certificate paths exists yet) |
| An approved purpose code and consent template | production | partner |
| A public HTTPS host for notification callbacks | if the network notifies | owner |

---

## (c) Flipping it live, in order — only if the cut is reversed

1. Resolve the regulatory route (the owner's call, with advice). Without it,
   stop here.
2. Company PAN + GSTIN → Setu sandbox access.
3. Reconcile the interface with the spec (notifications, data sessions,
   decryption), additively. Add config keys for certificates to all three
   config files.
4. `LiveAccountAggregator` plus a contract test against a fake gateway: consent
   pending → active, fetch refused before active, each failure kind, a
   duplicate consent *not* created after a timeout. Each watched failing.
5. Add `aa` to `ProviderModeCheck.implemented`; update this page and `GO-LIVE.md`.
6. `ALMIRA_PROVIDER_AA_MODE=live` plus credentials.
7. **Smoke test** (manual): startup; `connect/providers` shows `mode: LIVE`;
   the owner consents for their own savings account in a real AA app;
   `GET …/aa/consent` turns `ACTIVE`; import creates one record with a masked
   account number and a dated valuation; a second import creates nothing new.
   Negative: revoke the consent in the AA app → import is refused.

---

## (d) Not verified — not watched failing

| What | Why |
|---|---|
| Every call against a real AA | No adapter; production access needs a regulated entity; sandbox needs PAN + GSTIN |
| The fit of a polling, synchronous interface to the real network | Spec not in hand |
| Failure classification | No real responses seen |
| The web client leaving a `DISABLED` provider out of Settings | No automated test drives that card; checked by hand in a browser against a running server when the cut was made |

What would make it watched: a regulatory route, then steps 3–4 with each test
observed red, then step 7's negative against the real network.

[‹ Going live](../../GO-LIVE.md)
