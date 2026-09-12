@file:OptIn(ExperimentalEncodingApi::class)

package tech.bhrigu.almira.shared.zk

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The one envelope reader, used for all three kinds of envelope.
 *
 * ```
 * ┌─────────┬──────────────┬──────────┬──────────────────────────┐
 * │ version │  keyVersion  │    iv    │  ciphertext ‖ GCM tag    │
 * │ 1 byte  │  4 bytes BE  │ 12 bytes │  n bytes  ‖  16 bytes    │
 * └─────────┴──────────────┴──────────┴──────────────────────────┘
 *    0x01      unsigned       random
 * ```
 *
 * Wrapped key, verifier and every sealed field share this layout, and B6 of
 * docs/zk-interop-acceptance.md is the ruling that they must share this *code*
 * as well. The web client had two readers: one that checked the version byte
 * and one — the `wrappedKey` path — that sliced straight past it. Nothing was
 * wrong with the bytes it produced, which is exactly the problem: a second
 * parser is correct right up until the day a format changes, and then it
 * silently reads the wrong offsets out of a newer envelope and hands back
 * plausible rubbish.
 *
 * So this **fails closed** on everything: an unknown version, a truncated
 * envelope, base64 that is not base64. A sealed value that cannot be read is a
 * sealed value the person is told about. A sealed value read wrongly is a
 * number or an address they will believe.
 */
object Envelope {

    const val VERSION: Int = 1
    const val IV_BYTES: Int = 12
    const val TAG_BYTES: Int = 16

    /** version(1) + keyVersion(4) + iv(12). The body starts here. */
    const val HEADER_BYTES: Int = 1 + 4 + IV_BYTES

    /** Header plus a tag: what an envelope over an empty plaintext weighs. */
    const val MINIMUM_BYTES: Int = HEADER_BYTES + TAG_BYTES

    data class Parsed(
        /**
         * Which content key this was written under. Carried so a client can
         * *tell*, never so it can choose — only one wrapped key exists at a
         * time, and a reader that selects on this field is a reader that will
         * be wrong after the first rotation (B7).
         */
        val keyVersion: Int,
        val iv: ByteArray,
        /** Ciphertext with its 16-byte tag still attached, as GCM wants it. */
        val body: ByteArray,
    )

    /** Emitted base64url **without padding**, which is what the other client emits. */
    fun build(keyVersion: Int, iv: ByteArray, body: ByteArray): String {
        require(keyVersion > 0) { "keyVersion starts at 1" }
        require(iv.size == IV_BYTES) { "the iv is $IV_BYTES bytes, not ${iv.size}" }
        require(body.size >= TAG_BYTES) { "the body must carry at least a $TAG_BYTES-byte tag" }

        val bytes = ByteArray(HEADER_BYTES + body.size)
        bytes[0] = VERSION.toByte()
        bytes[1] = (keyVersion ushr 24).toByte()
        bytes[2] = (keyVersion ushr 16).toByte()
        bytes[3] = (keyVersion ushr 8).toByte()
        bytes[4] = keyVersion.toByte()
        iv.copyInto(bytes, 5)
        body.copyInto(bytes, HEADER_BYTES)
        return ENCODER.encode(bytes)
    }

    /**
     * Accepts base64url with or without padding, because the contract promises
     * to read both even though it only ever writes one.
     *
     * @throws UnsupportedEnvelopeVersion when the version byte is not [VERSION]
     * @throws MalformedEnvelope when it is not an envelope at all
     */
    fun parse(text: String): Parsed {
        val bytes = try {
            DECODER.decode(text)
        } catch (_: IllegalArgumentException) {
            throw MalformedEnvelope("this is not base64")
        }

        if (bytes.size < MINIMUM_BYTES) {
            throw MalformedEnvelope("only ${bytes.size} bytes; the smallest envelope is $MINIMUM_BYTES")
        }

        // Read before anything else, and refuse before reading anything else.
        val version = bytes[0].toInt() and 0xFF
        if (version != VERSION) throw UnsupportedEnvelopeVersion(version)

        val keyVersion = (bytes[1].toInt() and 0xFF shl 24) or
            (bytes[2].toInt() and 0xFF shl 16) or
            (bytes[3].toInt() and 0xFF shl 8) or
            (bytes[4].toInt() and 0xFF)
        // Unsigned on the wire, and nothing sane reaches the top bit. A value
        // that does is a misread envelope, not a household on its two billionth
        // passphrase.
        if (keyVersion <= 0) throw MalformedEnvelope("key version $keyVersion is not a version")

        return Parsed(
            keyVersion = keyVersion,
            iv = bytes.copyOfRange(5, HEADER_BYTES),
            body = bytes.copyOfRange(HEADER_BYTES, bytes.size),
        )
    }

    private val ENCODER = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    private val DECODER = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
}

/**
 * Separate from [MalformedEnvelope] because the two deserve different
 * sentences. This one is "a newer Almira wrote this", which is a thing to
 * explain; the other is "this is broken", which is a thing to apologise for.
 */
class UnsupportedEnvelopeVersion(val version: Int) :
    Exception("This was sealed by a newer version of Almira (envelope version $version).")

class MalformedEnvelope(detail: String) : Exception("This doesn't look like sealed data: $detail.")
