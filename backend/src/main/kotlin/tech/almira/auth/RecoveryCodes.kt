package tech.almira.auth

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Recovery codes: printed or written down once, for the day the phone with the
 * authenticator is lost.
 *
 * Twelve characters from an alphabet with no 0/O, 1/I/L or U/V to misread off
 * paper — about 58 bits each, grouped `XXXX-XXXX-XXXX`. Stored as
 * PBKDF2-HMAC-SHA256 with a per-code salt, so a database dump does not hand
 * over a way in, and an offline search of 2^58 codes at this cost is out of
 * reach. Online, they are behind the same attempt limits as the authenticator
 * code. The JDK has PBKDF2, so this needs no library.
 */
object RecoveryCodes {
    const val COUNT = 10
    private const val GROUPS = 3
    private const val GROUP_LENGTH = 4
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTWXYZ23456789"
    private const val ITERATIONS = 20_000
    private const val HASH_BITS = 256
    private const val SALT_BYTES = 16

    private val random = SecureRandom()

    class Stored(val salt: ByteArray, val hash: ByteArray)

    fun generate(): List<String> = List(COUNT) {
        (1..GROUPS).joinToString("-") {
            (1..GROUP_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        }
    }

    /**
     * What a person types, made comparable: case, spaces and dashes ignored.
     * Null when it cannot be a recovery code, so nothing is hashed for junk.
     */
    fun normalise(typed: String): String? {
        val clean = typed.uppercase().filter { it != '-' && !it.isWhitespace() }
        return clean.takeIf { it.length == GROUPS * GROUP_LENGTH && it.all { c -> c in ALPHABET } }
    }

    fun store(code: String): Stored {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return Stored(salt, hash(requireNotNull(normalise(code)), salt))
    }

    fun matches(normalised: String, salt: ByteArray, hash: ByteArray): Boolean =
        MessageDigest.isEqual(hash(normalised, salt), hash)

    private fun hash(normalised: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(normalised.toCharArray(), salt, ITERATIONS, HASH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
