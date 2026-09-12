package tech.bhrigu.almira.shared.zk

import java.text.Normalizer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

actual fun normalizeNfc(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFC)

/**
 * PBKDF2 written out, over a `Mac` that only ever does HMAC-SHA256.
 *
 * RFC 8018 §5.2. Thirty lines rather than one `SecretKeyFactory` call, and the
 * thirty lines are the point: the one-line version hands the password to the
 * provider as a `char[]` and lets it choose an encoding, which is precisely the
 * decision that must not be delegated. Here the password is already the exact
 * bytes agreed with the other client, and the provider is asked for nothing but
 * a standard MAC.
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

    val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(password, HMAC)) }
    val blockSize = mac.macLength
    val keyBytes = keyLengthBits / 8
    val blocks = (keyBytes + blockSize - 1) / blockSize

    val output = ByteArray(blocks * blockSize)
    val block = ByteArray(4)

    for (i in 1..blocks) {
        // The block index, big-endian, appended to the salt for U_1 only.
        block[0] = (i ushr 24).toByte()
        block[1] = (i ushr 16).toByte()
        block[2] = (i ushr 8).toByte()
        block[3] = i.toByte()

        mac.update(salt)
        var u = mac.doFinal(block)
        val accumulated = u.copyOf()

        repeat(iterations - 1) {
            u = mac.doFinal(u)
            for (b in accumulated.indices) accumulated[b] = (accumulated[b].toInt() xor u[b].toInt()).toByte()
        }
        accumulated.copyInto(output, (i - 1) * blockSize)
    }

    return output.copyOf(keyBytes)
}

private const val HMAC = "HmacSHA256"
