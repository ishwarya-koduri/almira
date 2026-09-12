@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package tech.bhrigu.almira.shared.zk

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.posix.memcpy

/**
 * AES-256-GCM on iOS has to come from Swift, and that is a finding rather than
 * a preference.
 *
 * Every other primitive in this module is C and therefore reachable from
 * Kotlin/Native directly: PBKDF2 and HMAC from CommonCrypto, the random from
 * Security, the Keychain from Security, the prompt from LocalAuthentication.
 * AES-GCM is the one exception. It lives in **CryptoKit**, which is Swift-only
 * and cannot be called from Kotlin/Native at all — and the public CommonCrypto
 * headers in the iOS SDK expose no GCM whatsoever, not even a mode constant for
 * it. The GCM entry points people remember from CommonCrypto are declared in
 * `CommonCryptorSPI.h`, which the SDK does not ship.
 *
 * So exactly one primitive is injected from the Swift side at startup. The seam
 * still holds — nothing above `Aead.kt` knows where AES came from — but "iOS is
 * a target-add, not a rewrite" needs the qualification that the add includes
 * about forty lines of Swift, and this is the reason.
 *
 * Rolling GCM by hand over CommonCrypto's AES-CTR was considered and rejected.
 * The authenticating half is GHASH, and a hand-written GHASH inside a product
 * whose whole claim is that the server cannot read the data is not a trade
 * worth making.
 *
 * The bridge speaks `NSData` rather than `ByteArray` on purpose: `ByteArray`
 * reaches Swift as `KotlinByteArray`, which has no bulk accessor, so every
 * payload would cross the boundary one byte at a time — 48 000 calls at the
 * ceiling A0 sets. `NSData` arrives in Swift as `Data`, which is what CryptoKit
 * wants anyway.
 */
interface AppleAead {
    /** Returns the ciphertext with its 16-byte tag attached, as the envelope expects. */
    fun seal(key: NSData, iv: NSData, plaintext: NSData, aad: NSData): NSData

    /**
     * Returns null when the tag does not verify, and never says which of the
     * several reasons it was. Named `unseal` rather than `open` because `open`
     * is a declaration modifier on the Swift side of this boundary.
     */
    fun unseal(key: NSData, iv: NSData, body: NSData, aad: NSData): NSData?
}

private var installed: AppleAead? = null

/**
 * Called once from Swift before any Compose content exists.
 *
 * There is no default and no fallback. The only thing worse than no encryption
 * here would be encryption that nobody chose.
 */
fun installAppleAead(aead: AppleAead) {
    installed = aead
}

private fun aead(): AppleAead = installed
    ?: error("installAppleAead has not been called — see Aead.ios.kt.")

actual fun aesGcmSeal(
    key: ByteArray,
    iv: ByteArray,
    plaintext: ByteArray,
    aad: ByteArray?,
): ByteArray {
    require(key.size == 32) { "the content key is 32 bytes" }
    require(iv.size == Envelope.IV_BYTES) { "the iv is ${Envelope.IV_BYTES} bytes" }
    return aead().seal(
        key = key.toNSData(),
        iv = iv.toNSData(),
        plaintext = plaintext.toNSData(),
        aad = (aad ?: ByteArray(0)).toNSData(),
    ).toByteArray()
}

actual fun aesGcmOpen(
    key: ByteArray,
    iv: ByteArray,
    body: ByteArray,
    aad: ByteArray?,
): ByteArray = aead().unseal(
    key = key.toNSData(),
    iv = iv.toNSData(),
    body = body.toNSData(),
    aad = (aad ?: ByteArray(0)).toNSData(),
)?.toByteArray() ?: throw AeadFailure()

actual fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    val status = bytes.usePinned { pinned ->
        SecRandomCopyBytes(kSecRandomDefault, size.convert(), pinned.addressOf(0))
    }
    // Never fall back to anything weaker. An IV that is not random is a GCM key
    // recovered from two messages.
    check(status == 0) { "SecRandomCopyBytes failed with status $status" }
    return bytes
}

// `NSData.create` and `memcpy` both copy, so neither side ends up holding a
// pointer into the other's memory after the call returns.

internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }
    }

internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
