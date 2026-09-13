[‹ Going live](../../GO-LIVE.md) · [Doc 13](../13-providers-and-going-live.md)

# Account Aggregator — holdings

**Status: cannot be built today. Recommended cut from v1, pending the owner's
decision. Not watched failing.**

A person consents, through the RBI Account Aggregator network, to share their
bank, deposit and fund data; Almira imports it as ordinary records at the
household's default visibility, with the provider's figure as a dated
valuation. The sandbox (`SandboxAccountAggregator`) makes the person approve,
refuses to fetch before approval, and returns data shaped like FI data.

---

## The recommendation, and what it does not mean

Production access as a Financial Information User requires the FIU to be
regulated by RBI, SEBI, IRDAI or PFRDA (research dated 2026-09). A family asset
registry is not, and is unlikely to become, one of those. The owner's
checklist therefore recommends **cutting Account Aggregator from v1**.

That is recorded here as a **recommendation, not a decision**. Nothing has been
removed: the interface, the sandbox, the connect endpoints, the tests and the
UI all remain, so the work is not lost if a regulated partner route appears
(an FIU that offers the data onward under its own licence, for instance —
unexplored).

**If it is cut, the switch does not exist yet.** Setting
`ALMIRA_PROVIDER_AA_MODE=off` passes `ProviderModeCheck`, and then the
application **fails to start**: `ConnectService` requires an
`AccountAggregatorClient` and `off` leaves none. Watched in this stage, running
the jar built from `94d839e` with `ALMIRA_ENV=development`, on 2026-09-13:

```
Parameter 7 of constructor in tech.bhrigu.almira.provider.ConnectService required
a bean of type 'tech.bhrigu.almira.provider.AccountAggregatorClient' that could not be found.
```

The same applies to `digilocker` and `whatsapp` (not run, same shape). `push`
with `off` was run and starts, because the notifier takes a list of senders;
`email` and `sms` have the same shape and were not run. Cutting AA
cleanly needs either an `off` implementation that answers every call with a
"not offered" refusal and reports `mode: OFF`, or hiding the card in the
clients. That is a small change, but a product decision should drive it, not
this document.

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

## (c) Flipping it live, in order — only if the recommendation is overturned

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
| `mode=off` as a way to cut it | Verified **broken** (startup fails) — see above |

What would make it watched: a regulatory route, then steps 3–4 with each test
observed red, then step 7's negative against the real network.

[‹ Going live](../../GO-LIVE.md)
