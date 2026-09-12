package tech.bhrigu.almira.shared.zk

/**
 * AES-256-GCM, with the parameters the scheme fixes: a 12-byte IV supplied by
 * the caller and a 128-bit tag left attached to the ciphertext.
 *
 * Platform-provided rather than written out, unlike PBKDF2 next door — and the
 * difference is deliberate. PBKDF2 had to be ours because the step that decides
 * whether two clients agree is turning a passphrase into bytes, and that is the
 * provider's business in the one-line version. Here every input is already
 * bytes: key, iv, plaintext, additional data. There is nothing left for a
 * provider to have an opinion about, so using the platform's audited AES is
 * strictly better than a hand-rolled one.
 */
expect fun aesGcmSeal(key: ByteArray, iv: ByteArray, plaintext: ByteArray, aad: ByteArray?): ByteArray

/** @throws AeadFailure when the tag does not verify — wrong key, wrong AAD, or tampering. */
expect fun aesGcmOpen(key: ByteArray, iv: ByteArray, body: ByteArray, aad: ByteArray?): ByteArray

/** Cryptographically secure bytes. Used for IVs, salts and the content key. */
expect fun secureRandomBytes(size: Int): ByteArray

/**
 * One failure for every way a GCM open can fail, because from the outside they
 * are the same event and must be treated as one: the tag did not verify.
 *
 * Never distinguish "wrong key" from "wrong AAD" to a caller. Both mean this
 * ciphertext is not yours to read, and a message that separates them is a
 * message that helps somebody work out which half they got right.
 */
class AeadFailure(cause: Throwable? = null) :
    Exception("This could not be opened.", cause)
