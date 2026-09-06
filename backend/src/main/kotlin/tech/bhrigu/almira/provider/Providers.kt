package tech.bhrigu.almira.provider

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The five outside services this product will eventually want, each behind an
 * interface with a sandbox implementation that really works.
 *
 * The reason for the shape: every one of them needs an account, a registration
 * or a regulator's approval, and none of that can be arranged from a laptop.
 * Building the adapter now — with a sandbox that returns payloads shaped exactly
 * like the real ones, wired through the app and covered by tests — means the
 * part that can be got wrong is proven, and only the transport is left. Going
 * live is a credential and a property.
 *
 * docs/13 lists, per provider, exactly what flips it.
 */

/** Where a provider is in its life: not configured, sandboxed, or actually live. */
enum class ProviderMode { OFF, SANDBOX, LIVE }

data class ProviderStatus(
    val provider: String,
    val label: String,
    val mode: ProviderMode,
    val connected: Boolean,
    /** What the sandbox does, so nobody mistakes a fixture for their data. */
    val sandboxNote: String?,
    /** Exactly what is needed to make it real. */
    val toGoLive: List<String>,
    val lastSyncedAt: Instant? = null,
)

// --- documents from DigiLocker ------------------------------------------------

data class VaultDocument(
    val uri: String,
    val name: String,
    val issuer: String,
    val docType: String,
    val issuedOn: LocalDate?,
    val mimeType: String,
    val sizeBytes: Int,
)

interface DocumentVaultProvider {
    val mode: ProviderMode

    /** Where to send someone to consent. In sandbox this is a local page. */
    fun authorizationUrl(householdId: UUID, state: String): String

    /** Exchange the code for a session. Sandbox accepts any code. */
    fun exchange(householdId: UUID, code: String): ProviderSession

    fun list(session: ProviderSession): List<VaultDocument>

    fun fetch(session: ProviderSession, uri: String): ByteArray
}

data class ProviderSession(val token: String, val expiresAt: Instant, val scope: String)

// --- holdings from the Account Aggregator network -----------------------------

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

/** One account as the network describes it, before Almira makes a record of it. */
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

interface AccountAggregatorClient {
    val mode: ProviderMode
    fun requestConsent(householdId: UUID, request: ConsentRequest): ConsentHandle
    fun consentStatus(handle: String): ConsentHandle
    fun fetch(handle: String): List<DiscoveredHolding>
}

// --- capture over WhatsApp ----------------------------------------------------

data class InboundMessage(
    val from: String,
    val text: String,
    val receivedAt: Instant,
    val mediaId: String? = null,
)

interface WhatsAppGateway {
    val mode: ProviderMode

    /**
     * True when the payload really came from the provider. The sandbox accepts
     * anything and says so; a live gateway checks the signature, and an inbound
     * webhook that trusts its caller is an open door.
     */
    fun verify(signature: String?, body: ByteArray): Boolean

    fun parse(body: ByteArray): InboundMessage?

    /** What to send back. Recorded rather than sent when not live. */
    fun reply(to: String, text: String)
}
