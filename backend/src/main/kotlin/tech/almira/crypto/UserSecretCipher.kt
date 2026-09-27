package tech.almira.crypto

import org.springframework.stereotype.Service
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A sealed per-person secret and the KEK that can open it. */
class SealedUserSecret(val blob: ByteArray, val kekId: String) {
    override fun toString() = "SealedUserSecret([${blob.size} bytes], kekId=$kekId)"
}

/**
 * Envelope encryption for a secret that belongs to a PERSON, not a household.
 *
 * [EnvelopeCipher] keys everything by household, which is right for records and
 * wrong for an authenticator secret: someone can have no household yet, or
 * leave one, and still has to sign in. So each secret gets a data key of its
 * own, wrapped by the same [KeyManagementService] KEK and stored beside it. The
 * database holds the wrapped key and the ciphertext; the KEK stays outside it,
 * as for every other encrypted field (docs/05 §4).
 *
 * The AAD binds the value to the person and the column, so a blob copied into
 * another user's row fails to authenticate rather than signing them in with
 * somebody else's authenticator.
 *
 * Blob: `0x01 ‖ wrappedLen(2 BE) ‖ wrapped DEK ‖ iv(12) ‖ ciphertext ‖ tag`.
 * Nothing is cached: a secret is opened once per sign-in, and a DEK per secret
 * in memory would buy nothing.
 */
@Service
class UserSecretCipher(private val kms: KeyManagementService) {

    private val random = SecureRandom()

    fun seal(userId: UUID, field: String, plaintext: ByteArray): SealedUserSecret {
        val dek = ByteArray(DEK_BYTES).also(random::nextBytes)
        try {
            val wrapped = kms.wrap(dek)
            require(wrapped.size <= 0xFFFF) { "wrapped key is too large" }
            val iv = ByteArray(IV_BYTES).also(random::nextBytes)
            val body = Cipher.getInstance(ALGORITHM).run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(TAG_BITS, iv))
                updateAAD(aad(userId, field))
                doFinal(plaintext)
            }
            val blob = ByteBuffer.allocate(1 + 2 + wrapped.size + IV_BYTES + body.size)
                .put(FORMAT_VERSION)
                .putShort(wrapped.size.toShort())
                .put(wrapped)
                .put(iv)
                .put(body)
                .array()
            return SealedUserSecret(blob, kms.kekId)
        } finally {
            dek.fill(0)
        }
    }

    fun open(userId: UUID, field: String, blob: ByteArray): ByteArray {
        require(blob.size > 1 + 2 + IV_BYTES) { "sealed secret is malformed" }
        val buffer = ByteBuffer.wrap(blob)
        val version = buffer.get()
        require(version == FORMAT_VERSION) { "unsupported sealed secret format: $version" }
        val wrappedLength = buffer.short.toInt() and 0xFFFF
        require(buffer.remaining() > wrappedLength + IV_BYTES) { "sealed secret is malformed" }
        val wrapped = ByteArray(wrappedLength).also(buffer::get)
        val iv = ByteArray(IV_BYTES).also(buffer::get)
        val body = ByteArray(buffer.remaining()).also(buffer::get)

        val dek = kms.unwrap(wrapped)
        return try {
            Cipher.getInstance(ALGORITHM).run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(TAG_BITS, iv))
                updateAAD(aad(userId, field))
                doFinal(body)
            }
        } catch (e: AEADBadTagException) {
            throw IllegalStateException("could not open $field: it failed authentication", e)
        } finally {
            dek.fill(0)
        }
    }

    private fun aad(userId: UUID, field: String) = "user:$userId|$field".toByteArray(Charsets.UTF_8)

    private companion object {
        const val ALGORITHM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val DEK_BYTES = 32
        const val FORMAT_VERSION: Byte = 1
    }
}
