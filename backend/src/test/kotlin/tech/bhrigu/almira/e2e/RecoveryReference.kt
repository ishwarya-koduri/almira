package tech.bhrigu.almira.e2e

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The client half of docs/12 §10, on the JVM — the reference a second client
 * copies from, the same way `E2eApiTest` is for §2–§4.
 *
 * None of this runs on the server. The server never holds a recovery code, a
 * share or the key they derive; it is here so the API tests can do what a
 * browser does and prove the round trip for real, and so the constants in
 * `scripts/check-recovery.js` are pinned by a second implementation rather
 * than by the web client agreeing with itself.
 */
object RecoveryReference {

    const val SECRET_BYTES = 21
    const val CODE_BYTES = 25
    const val TYPE_KEY: Int = 0x01
    const val TYPE_SHARE: Int = 0x02
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()

    // --- GF(2^8) with the AES polynomial x^8 + x^4 + x^3 + x + 1 --------------

    fun gfMul(a: Int, b: Int): Int {
        var x = a and 0xFF
        var y = b and 0xFF
        var product = 0
        while (y != 0) {
            if (y and 1 != 0) product = product xor x
            val carry = x and 0x80
            x = (x shl 1) and 0xFF
            if (carry != 0) x = x xor 0x1B
            y = y ushr 1
        }
        return product
    }

    /** a^254 = a^-1 for a ≠ 0. Square-and-multiply; no tables to get wrong. */
    fun gfInv(a: Int): Int {
        require(a and 0xFF != 0) { "zero has no inverse" }
        var result = 1
        var base = a and 0xFF
        var exponent = 254
        while (exponent > 0) {
            if (exponent and 1 != 0) result = gfMul(result, base)
            base = gfMul(base, base)
            exponent = exponent ushr 1
        }
        return result
    }

    // --- Shamir, k-of-n, byte-wise -----------------------------------------------

    data class Share(val x: Int, val y: ByteArray)

    /**
     * [coefficients] supplies the k−1 random coefficients for each byte; taken
     * from a SecureRandom unless a test fixes them. Every coefficient is uniform
     * over all 256 values, zero included: excluding zero would make a share
     * byte never equal the secret byte, which is a leak.
     */
    fun split(
        secret: ByteArray,
        threshold: Int,
        count: Int,
        coefficients: (byteIndex: Int, power: Int) -> Int = { _, _ -> random.nextInt(256) },
    ): List<Share> {
        require(threshold in 2..count && count <= 255)
        val coeffs = Array(secret.size) { i -> IntArray(threshold - 1) { p -> coefficients(i, p + 1) and 0xFF } }
        return (1..count).map { x ->
            Share(x, ByteArray(secret.size) { i ->
                // Horner, from the highest power down to the secret byte.
                var y = 0
                for (p in threshold - 1 downTo 1) y = gfMul(y xor coeffs[i][p - 1], x)
                (y xor (secret[i].toInt() and 0xFF)).toByte()
            })
        }
    }

    /** Lagrange interpolation at zero over whichever shares are given. */
    fun combine(shares: List<Share>): ByteArray {
        require(shares.isNotEmpty() && shares.map { it.x }.toSet().size == shares.size && shares.none { it.x == 0 })
        val length = shares.first().y.size
        require(shares.all { it.y.size == length })
        return ByteArray(length) { i ->
            var value = 0
            for (j in shares.indices) {
                var basis = 1
                for (m in shares.indices) {
                    if (m == j) continue
                    // x_m / (x_m − x_j); subtraction is xor in GF(2^8).
                    basis = gfMul(basis, gfMul(shares[m].x, gfInv(shares[m].x xor shares[j].x)))
                }
                value = value xor gfMul(shares[j].y[i].toInt() and 0xFF, basis)
            }
            value.toByte()
        }
    }

    // --- the printed code --------------------------------------------------------

    /** CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF, no reflection, no xorout. */
    fun crc16(bytes: ByteArray): Int {
        var crc = 0xFFFF
        for (b in bytes) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF }
        }
        return crc
    }

    fun base32(bytes: ByteArray): String = buildString {
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                append(ALPHABET[(buffer ushr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) append(ALPHABET[(buffer shl (5 - bits)) and 31])
    }

    /** type · x · 21 secret bytes · CRC-16 of the first 23, as 8 groups of 5. */
    fun encodeCode(type: Int, x: Int, payload: ByteArray): String {
        require(payload.size == SECRET_BYTES)
        val head = byteArrayOf(type.toByte(), x.toByte()) + payload
        val crc = crc16(head)
        val text = base32(head + byteArrayOf((crc ushr 8).toByte(), crc.toByte()))
        return text.chunked(5).joinToString("-")
    }

    data class Code(val type: Int, val x: Int, val payload: ByteArray)

    fun decodeCode(text: String): Code {
        val cleaned = text.uppercase().filterNot { it == '-' || it.isWhitespace() }
            .map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; else -> it } }.joinToString("")
        require(cleaned.length == 40) { "a code is 40 characters" }
        var buffer = 0L
        var bits = 0
        val out = java.io.ByteArrayOutputStream()
        for (c in cleaned) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "not a code character" }
            buffer = (buffer shl 5) or v.toLong()
            bits += 5
            if (bits >= 8) {
                out.write(((buffer ushr (bits - 8)) and 0xFF).toInt())
                bits -= 8
            }
        }
        val raw = out.toByteArray()
        require(raw.size == CODE_BYTES)
        val crc = crc16(raw.copyOfRange(0, 23))
        require(((raw[23].toInt() and 0xFF) shl 8 or (raw[24].toInt() and 0xFF)) == crc) { "a character is wrong" }
        return Code(raw[0].toInt() and 0xFF, raw[1].toInt() and 0xFF, raw.copyOfRange(2, 23))
    }

    // --- keys ----------------------------------------------------------------------

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    /** RFC 5869, written out. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = java.io.ByteArrayOutputStream()
        var previous = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            previous = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
            out.write(previous)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    fun wrappingKey(secret: ByteArray, salt: ByteArray, kind: String): SecretKey =
        SecretKeySpec(hkdf(secret, salt, "almira recovery v1|$kind".toByteArray(), 32), "AES")

    fun contentKeyId(contentKey: ByteArray): String =
        b64(hmac(contentKey, "almira content key id v1".toByteArray()).copyOf(16))

    fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun seal(key: SecretKey, plaintext: ByteArray, keyVersion: Int = 1, iv: ByteArray = randomBytes(12)): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val body = cipher.doFinal(plaintext)
        val header = byteArrayOf(
            1, (keyVersion ushr 24).toByte(), (keyVersion ushr 16).toByte(), (keyVersion ushr 8).toByte(),
            keyVersion.toByte(),
        )
        return b64(header + iv + body)
    }

    fun open(key: SecretKey, envelope: String): ByteArray {
        val raw = Base64.getUrlDecoder().decode(envelope)
        require(raw.size >= 33 && raw[0].toInt() == 1)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw.copyOfRange(5, 17)))
        return cipher.doFinal(raw.copyOfRange(17, raw.size))
    }

    fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)
}
