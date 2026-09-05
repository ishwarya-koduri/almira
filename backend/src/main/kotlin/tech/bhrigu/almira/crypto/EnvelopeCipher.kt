package tech.bhrigu.almira.crypto

import org.springframework.stereotype.Service
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Field-level encryption with per-household data keys (docs/05 §4).
 *
 * Every value is bound to WHERE it lives by putting the household id, the table
 * and the column into the AEAD's additional authenticated data. A ciphertext
 * therefore decrypts only in the exact position it was written: copying an
 * `accounts.number_enc` blob into another household's row, or into a different
 * column, fails to authenticate rather than silently revealing the value. Without
 * that binding, a database write is enough to move a secret somewhere it will be
 * shown to the wrong person.
 *
 * The stored blob carries its own key version, so re-keying a household does not
 * require rewriting existing rows — old values keep decrypting under the key
 * they were written with.
 *
 * Plaintext DEKs are cached in memory. That is the point of envelope encryption
 * (one KMS call per household, not per field) and it is also the honest limit of
 * it: anyone who can read this process's memory can read the cached keys. The
 * KEK stays outside the process; the DEKs cannot.
 */
@Service
class EnvelopeCipher(
    private val kms: KeyManagementService,
    private val keys: EncryptionKeyRepository,
) {
    private val random = SecureRandom()
    private val cache = ConcurrentHashMap<CacheKey, SecretKeySpec>()

    private data class CacheKey(val householdId: UUID, val keyVersion: Int)

    /**
     * [field] is the position this value occupies, as "table.column". It is
     * authenticated, not encrypted, so it must be identical on the way back out.
     */
    fun encrypt(householdId: UUID, field: String, plaintext: String): ByteArray {
        val active = keys.activeKey(householdId) { kms.wrap(newDataKey()) to kms.kekId }
        val dek = dataKey(householdId, active.keyVersion, active.wrappedDek)

        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(ALGORITHM).apply {
            init(Cipher.ENCRYPT_MODE, dek, GCMParameterSpec(TAG_BITS, iv))
            updateAAD(aad(householdId, field))
        }
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        return ByteBuffer.allocate(1 + 4 + IV_BYTES + body.size)
            .put(FORMAT_VERSION)
            .putInt(active.keyVersion)
            .put(iv)
            .put(body)
            .array()
    }

    fun decrypt(householdId: UUID, field: String, blob: ByteArray): String {
        require(blob.size > 1 + 4 + IV_BYTES) { "ciphertext is malformed" }
        val buffer = ByteBuffer.wrap(blob)

        val version = buffer.get()
        require(version == FORMAT_VERSION) { "unsupported ciphertext format: $version" }
        val keyVersion = buffer.int
        val iv = ByteArray(IV_BYTES).also(buffer::get)
        val body = ByteArray(buffer.remaining()).also(buffer::get)

        val wrapped = keys.wrappedKey(householdId, keyVersion)
            ?: throw IllegalStateException(
                "no key version $keyVersion for household $householdId — " +
                    "a retired key was deleted, and the data it protected is unreadable",
            )
        val dek = dataKey(householdId, keyVersion, wrapped)

        return try {
            val cipher = Cipher.getInstance(ALGORITHM).apply {
                init(Cipher.DECRYPT_MODE, dek, GCMParameterSpec(TAG_BITS, iv))
                updateAAD(aad(householdId, field))
            }
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            // Tampering, a moved ciphertext, or the wrong field name. All three
            // are failures, and none should reveal which.
            throw IllegalStateException("could not decrypt $field: it failed authentication", e)
        }
    }

    /** Drops cached keys for a household — after re-keying, or on eviction. */
    fun forget(householdId: UUID) {
        cache.keys.removeIf { it.householdId == householdId }
    }

    private fun dataKey(householdId: UUID, version: Int, wrapped: ByteArray): SecretKeySpec =
        cache.computeIfAbsent(CacheKey(householdId, version)) {
            SecretKeySpec(kms.unwrap(wrapped), "AES")
        }

    private fun newDataKey(): ByteArray = ByteArray(DEK_BYTES).also(random::nextBytes)

    private fun aad(householdId: UUID, field: String) =
        "$householdId|$field".toByteArray(Charsets.UTF_8)

    private companion object {
        const val ALGORITHM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val DEK_BYTES = 32
        const val FORMAT_VERSION: Byte = 1
    }
}
