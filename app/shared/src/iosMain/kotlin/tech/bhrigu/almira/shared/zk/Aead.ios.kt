package tech.bhrigu.almira.shared.zk

/**
 * Unwritten, and throwing so it stays visible — the same bar as the Keychain
 * and LocalAuthentication stubs.
 *
 * The iOS version is CryptoKit: `AES.GCM.seal(_:using:nonce:authenticating:)`
 * and `AES.GCM.open(_:using:authenticating:)`, with `SymmetricKey(data:)` and
 * `AES.GCM.Nonce(data:)`, plus `SecRandomCopyBytes` for the random. Never
 * compiled: this target needs the Kotlin/Native toolchain, which is not
 * installed by choice. See docs/known-issues.md.
 */
actual fun aesGcmSeal(key: ByteArray, iv: ByteArray, plaintext: ByteArray, aad: ByteArray?): ByteArray =
    error("AES-GCM for iOS is not written yet — see docs/known-issues.md.")

actual fun aesGcmOpen(key: ByteArray, iv: ByteArray, body: ByteArray, aad: ByteArray?): ByteArray =
    error("AES-GCM for iOS is not written yet — see docs/known-issues.md.")

actual fun secureRandomBytes(size: Int): ByteArray =
    error("SecRandomCopyBytes for iOS is not written yet — see docs/known-issues.md.")
