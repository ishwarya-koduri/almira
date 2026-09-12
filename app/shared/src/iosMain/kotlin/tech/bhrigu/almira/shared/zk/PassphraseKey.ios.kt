package tech.bhrigu.almira.shared.zk

import platform.Foundation.NSString
import platform.Foundation.precomposedStringWithCanonicalMapping

/**
 * `precomposedStringWithCanonicalMapping` **is** NFC — Foundation's name for
 * it. Written now rather than left throwing, because it is one call and getting
 * it wrong later would silently produce a different key from the other two
 * clients.
 *
 * Never compiled: this target needs the Kotlin/Native toolchain, which is not
 * installed by choice. See docs/known-issues.md.
 */
@Suppress("CAST_NEVER_SUCCEEDS")
actual fun normalizeNfc(text: String): String =
    (text as NSString).precomposedStringWithCanonicalMapping

/**
 * Unwritten, and throwing so it stays visible.
 *
 * The iOS version is `CCKeyDerivationPBKDF` from CommonCrypto with
 * `kCCPRFHmacAlgSHA256`, fed the same NFC-then-UTF-8 bytes. Like the Keychain
 * and LocalAuthentication stubs, this is real work that has not been done, so
 * it fails loudly rather than returning something plausible — a PBKDF2 that
 * silently returns the wrong bytes is a passphrase that appears to be wrong.
 */
actual fun pbkdf2HmacSha256(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    keyLengthBits: Int,
): ByteArray = error("PBKDF2 for iOS is not written yet — see docs/known-issues.md.")
