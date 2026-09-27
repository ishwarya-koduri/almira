package tech.almira.auth

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Time-based one-time passwords, RFC 6238 over RFC 4226, the way every
 * authenticator app reads an `otpauth://` link: HMAC-SHA1, six digits,
 * thirty-second steps. Those are the defaults Google Authenticator, Microsoft
 * Authenticator, 1Password and Aegis all agree on; anything else is a setting
 * some app silently ignores, and a code that never matches.
 *
 * Pure functions, no clock of its own, so the RFC's test vectors run as they are
 * written (TotpTest).
 */
object Totp {
    const val DIGITS = 6
    const val PERIOD_SECONDS = 30L

    /**
     * One step either side. A phone's clock drifts and a person takes a few
     * seconds to type; more than this and a code seen over a shoulder stays
     * good for minutes.
     */
    const val WINDOW = 1

    /** 160 bits, the HMAC-SHA1 block the RFC recommends. */
    const val SECRET_BYTES = 20

    private val random = SecureRandom()

    fun newSecret(): ByteArray = ByteArray(SECRET_BYTES).also(random::nextBytes)

    fun step(at: Instant): Long = Math.floorDiv(at.epochSecond, PERIOD_SECONDS)

    /** The code for [step]. [digits] and [algorithm] exist for the RFC's vectors. */
    fun code(secret: ByteArray, step: Long, digits: Int = DIGITS, algorithm: String = "HmacSHA1"): String {
        val mac = Mac.getInstance(algorithm).apply { init(SecretKeySpec(secret, algorithm)) }
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array())
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        var modulus = 1
        repeat(digits) { modulus *= 10 }
        return (binary % modulus).toString().padStart(digits, '0')
    }

    /**
     * The step [candidate] matches within [WINDOW] of [now], or null.
     *
     * Every step in the window is computed and compared in constant time,
     * whether or not an earlier one matched, so how long this takes says
     * nothing about which step was right. Only a step later than
     * [lastUsedStep] counts: a code is good once.
     */
    fun matchingStep(secret: ByteArray, candidate: String, now: Instant, lastUsedStep: Long?): Long? {
        if (candidate.length != DIGITS || candidate.any { it !in '0'..'9' }) return null
        val current = step(now)
        var found: Long? = null
        for (delta in -WINDOW..WINDOW) {
            val s = current + delta
            val matches = MessageDigest.isEqual(
                code(secret, s).toByteArray(Charsets.US_ASCII),
                candidate.toByteArray(Charsets.US_ASCII),
            )
            if (matches && (lastUsedStep == null || s > lastUsedStep) && found == null) found = s
        }
        return found
    }

    /**
     * What the QR code carries. The label is what the app lists the entry as,
     * so it names Almira and a masked identifier — enough to tell two accounts
     * apart on one phone, and nothing a glance at someone's authenticator
     * should give away.
     */
    fun otpauthUri(secret: ByteArray, label: String, issuer: String = ISSUER): String {
        val enc = { s: String -> java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20") }
        return "otpauth://totp/${enc(issuer)}:${enc(label)}" +
            "?secret=${Base32.encode(secret)}&issuer=${enc(issuer)}" +
            "&algorithm=SHA1&digits=$DIGITS&period=$PERIOD_SECONDS"
    }

    const val ISSUER = "Almira"
}

/** RFC 4648 base32, unpadded, upper case: what authenticator apps accept when a secret is typed. */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 0x1f])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1f])
        return out.toString()
    }

    /** Spaces, dashes and case are ignored, as an app shows them grouped. Null when not base32. */
    fun decode(text: String): ByteArray? {
        val clean = text.uppercase().filterNot { it == ' ' || it == '-' || it == '=' }
        val out = java.io.ByteArrayOutputStream(clean.length * 5 / 8)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}
