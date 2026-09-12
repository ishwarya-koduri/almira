package tech.bhrigu.almira.shared.api

import kotlinx.serialization.Serializable

/**
 * The zero-knowledge surface of the frozen v1 contract.
 *
 * Everything here is either ciphertext or a parameter for producing it. The
 * server stores all of it and can read none of it, which is the property the
 * whole feature sells — see docs/12 and docs/zk-interop-acceptance.md.
 */

@Serializable
data class E2eStatus(
    val enabled: Boolean,
    val sealedFieldCount: Int = 0,
    val key: E2eKeyEnvelope? = null,
    /** The trade-offs, in the server's words, to be shown rather than summarised. */
    val caveats: List<String> = emptyList(),
)

@Serializable
data class E2eKeyEnvelope(
    val kdf: String,
    val kdfSalt: String,
    val iterations: Int,
    val wrapAlgorithm: String,
    /** The content key, sealed under the passphrase-derived wrapping key. */
    val wrappedKey: String,
    /** The word `almira`, sealed under the content key. Proves the key, not the wrap. */
    val verifier: String,
    val keyVersion: Int,
    val updatedAt: String? = null,
)

@Serializable
data class SealedField(
    val recordType: String,
    val recordId: String,
    val fieldKey: String,
    val ciphertext: String,
    val keyVersion: Int,
    val algorithm: String,
    val updatedAt: String? = null,
)

@Serializable
data class PutSealedValueBody(val ciphertext: String, val keyVersion: Int)

/** Just enough of an investment to pick one and name it. */
@Serializable
data class InvestmentRow(
    val id: String,
    val title: String,
    val typeLabel: String,
    val categoryCode: String,
    val valueFormatted: String? = null,
)
