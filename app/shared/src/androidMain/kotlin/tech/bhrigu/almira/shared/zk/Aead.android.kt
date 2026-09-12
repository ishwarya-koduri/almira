package tech.bhrigu.almira.shared.zk

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val TAG_BITS = 128

private val random = SecureRandom()

actual fun aesGcmSeal(
    key: ByteArray,
    iv: ByteArray,
    plaintext: ByteArray,
    aad: ByteArray?,
): ByteArray {
    require(key.size == 32) { "the content key is 32 bytes" }
    require(iv.size == Envelope.IV_BYTES) { "the iv is ${Envelope.IV_BYTES} bytes" }

    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
    aad?.let(cipher::updateAAD)
    return cipher.doFinal(plaintext)
}

actual fun aesGcmOpen(
    key: ByteArray,
    iv: ByteArray,
    body: ByteArray,
    aad: ByteArray?,
): ByteArray {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
    aad?.let(cipher::updateAAD)
    return try {
        cipher.doFinal(body)
    } catch (failure: AEADBadTagException) {
        // Collapsed on purpose: a wrong key and a wrong AAD are the same answer
        // to anyone outside, and separating them would tell someone which half
        // they had right.
        throw AeadFailure(failure)
    } catch (failure: javax.crypto.BadPaddingException) {
        throw AeadFailure(failure)
    } catch (failure: IllegalArgumentException) {
        throw AeadFailure(failure)
    }
}

actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
