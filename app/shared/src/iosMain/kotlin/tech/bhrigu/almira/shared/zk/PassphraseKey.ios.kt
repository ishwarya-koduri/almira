@file:OptIn(ExperimentalForeignApi::class)

package tech.bhrigu.almira.shared.zk

import platform.CoreCrypto.CCHmacContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCHmacFinal
import platform.CoreCrypto.CCHmacInit
import platform.CoreCrypto.CCHmacUpdate
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.Foundation.NSString
import platform.Foundation.precomposedStringWithCanonicalMapping

/**
 * `precomposedStringWithCanonicalMapping` **is** NFC — Foundation's name for
 * it. One call, and worth naming because it has to agree with
 * `java.text.Normalizer.Form.NFC` and with JavaScript's `normalize("NFC")` to
 * the byte, or one passphrase derives three different keys.
 */
@Suppress("CAST_NEVER_SUCCEEDS")
actual fun normalizeNfc(text: String): String =
    (text as NSString).precomposedStringWithCanonicalMapping

/**
 * PBKDF2-HMAC-SHA256 written out over CommonCrypto's HMAC — the same RFC 8018
 * loop as the Android side, differing only in whose HMAC does the work.
 *
 * **Not `CCKeyDerivationPBKDF`**, which would have been one call. Kotlin/Native
 * maps its `const char *password` parameter to `String?`, so using it would
 * hand the decision of how text becomes bytes back to the interop layer — the
 * exact delegation B2 exists to prevent, and on the platform where a
 * disagreement would be hardest to see. The password arrives here already
 * NFC-normalised and UTF-8 encoded; nothing below is allowed an opinion about
 * it. A `String?` parameter also cannot carry a NUL byte, which a passphrase
 * legitimately might.
 *
 * **Not CryptoKit either.** CryptoKit is Swift-only and unreachable from
 * Kotlin/Native without a hand-written Swift bridge, so every primitive on this
 * side comes from CommonCrypto and Security, which are C.
 *
 *   T_i = U_1 xor U_2 xor … xor U_c
 *   U_1 = PRF(P, S ‖ INT_BE32(i))
 *   U_j = PRF(P, U_{j-1})
 */
actual fun pbkdf2HmacSha256(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    keyLengthBits: Int,
): ByteArray {
    require(iterations > 0) { "iterations must be positive" }
    require(keyLengthBits > 0 && keyLengthBits % 8 == 0) { "key length must be whole bytes" }

    val blockSize = 32 // SHA-256
    val keyBytes = keyLengthBits / 8
    val blocks = (keyBytes + blockSize - 1) / blockSize
    val output = ByteArray(blocks * blockSize)

    // An empty array has no address to take, and an empty passphrase is a bad
    // idea rather than an impossible input.
    val key = if (password.isEmpty()) ByteArray(1) else password
    val keyLength = password.size

    key.usePinned { pinnedKey ->
        memScoped {
            val context = alloc<CCHmacContext>()
            val u = ByteArray(blockSize)
            val accumulated = ByteArray(blockSize)

            for (i in 1..blocks) {
                // U_1 = PRF(P, salt ‖ i), big-endian, and only for the first.
                val seed = salt + byteArrayOf(
                    (i ushr 24).toByte(),
                    (i ushr 16).toByte(),
                    (i ushr 8).toByte(),
                    i.toByte(),
                )

                seed.usePinned { pinnedSeed ->
                    u.usePinned { pinnedU ->
                        CCHmacInit(context.ptr, kCCHmacAlgSHA256, pinnedKey.addressOf(0), keyLength.convert())
                        CCHmacUpdate(context.ptr, pinnedSeed.addressOf(0), seed.size.convert())
                        CCHmacFinal(context.ptr, pinnedU.addressOf(0))
                    }
                }
                u.copyInto(accumulated)

                repeat(iterations - 1) {
                    u.usePinned { pinnedU ->
                        CCHmacInit(context.ptr, kCCHmacAlgSHA256, pinnedKey.addressOf(0), keyLength.convert())
                        CCHmacUpdate(context.ptr, pinnedU.addressOf(0), blockSize.convert())
                        CCHmacFinal(context.ptr, pinnedU.addressOf(0))
                    }
                    for (b in 0 until blockSize) {
                        accumulated[b] = (accumulated[b].toInt() xor u[b].toInt()).toByte()
                    }
                }
                accumulated.copyInto(output, (i - 1) * blockSize)
            }
        }
    }

    return output.copyOf(keyBytes)
}
