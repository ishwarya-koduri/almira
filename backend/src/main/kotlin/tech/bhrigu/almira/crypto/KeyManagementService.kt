package tech.bhrigu.almira.crypto

/**
 * Wraps and unwraps data-encryption keys with a key-encryption key that this
 * application never holds in a form it can store.
 *
 * This is the seam a managed KMS plugs into (AWS KMS, GCP KMS, Vault Transit).
 * The interface is deliberately narrow — two operations on opaque bytes — so a
 * provider swap changes one class and nothing else. See [LocalKeyManagement]
 * for what a provider owes the rest of the system.
 */
interface KeyManagementService {

    /** Identifies the KEK, so rotating it does not orphan existing ciphertext. */
    val kekId: String

    /** Encrypts a plaintext DEK for storage. */
    fun wrap(dataKey: ByteArray): ByteArray

    /** Recovers a plaintext DEK. Throws if the ciphertext was not produced by this KEK. */
    fun unwrap(wrappedDataKey: ByteArray): ByteArray
}
