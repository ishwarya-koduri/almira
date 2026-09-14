package tech.bhrigu.almira.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The authenticator code, against the RFC's own numbers, and the two rules this
 * service adds on top: one step either side, and a code works once.
 */
@DisplayName("Authenticator codes (RFC 6238)")
class TotpTest {

    private val rfcSecret = "12345678901234567890".toByteArray(Charsets.US_ASCII)

    @Test
    fun `matches the RFC 6238 appendix B vectors for SHA-1`() {
        val vectors = mapOf(
            59L to "94287082",
            1111111109L to "07081804",
            1111111111L to "14050471",
            1234567890L to "89005924",
            2000000000L to "69279037",
            20000000000L to "65353130",
        )
        vectors.forEach { (seconds, expected) ->
            assertThat(Totp.code(rfcSecret, Totp.step(Instant.ofEpochSecond(seconds)), digits = 8))
                .describedAs("T = $seconds").isEqualTo(expected)
        }
    }

    @Test
    fun `matches the RFC 4226 appendix D HOTP values, six digits`() {
        val expected = listOf("755224", "287082", "359152", "969429", "338314", "254676", "287922", "162583", "399871", "520489")
        expected.forEachIndexed { counter, code ->
            assertThat(Totp.code(rfcSecret, counter.toLong())).describedAs("counter $counter").isEqualTo(code)
        }
    }

    @Test
    fun `accepts one step either side and nothing further`() {
        val now = Instant.ofEpochSecond(1_700_000_000)
        val step = Totp.step(now)
        assertThat(Totp.matchingStep(rfcSecret, Totp.code(rfcSecret, step), now, null)).isEqualTo(step)
        assertThat(Totp.matchingStep(rfcSecret, Totp.code(rfcSecret, step - 1), now, null)).isEqualTo(step - 1)
        assertThat(Totp.matchingStep(rfcSecret, Totp.code(rfcSecret, step + 1), now, null)).isEqualTo(step + 1)
        assertThat(Totp.matchingStep(rfcSecret, Totp.code(rfcSecret, step - 2), now, null)).isNull()
        assertThat(Totp.matchingStep(rfcSecret, Totp.code(rfcSecret, step + 2), now, null)).isNull()
    }

    @Test
    fun `a code is good once - nothing at or before the last accepted step`() {
        val now = Instant.ofEpochSecond(1_700_000_000)
        val step = Totp.step(now)
        val code = Totp.code(rfcSecret, step)
        assertThat(Totp.matchingStep(rfcSecret, code, now, lastUsedStep = step)).isNull()
        assertThat(Totp.matchingStep(rfcSecret, Totp.code(rfcSecret, step - 1), now, lastUsedStep = step)).isNull()
        assertThat(Totp.matchingStep(rfcSecret, Totp.code(rfcSecret, step + 1), now, lastUsedStep = step)).isEqualTo(step + 1)
    }

    @Test
    fun `refuses anything that is not six digits without computing a match`() {
        val now = Instant.now()
        listOf("", "12345", "1234567", "12 456", "abcdef", "١٢٣٤٥٦").forEach {
            assertThat(Totp.matchingStep(rfcSecret, it, now, null)).describedAs("'$it'").isNull()
        }
    }

    @Test
    fun `base32 follows RFC 4648 and reads what an app shows`() {
        val vectors = mapOf("" to "", "f" to "MY", "fo" to "MZXQ", "foo" to "MZXW6", "foob" to "MZXW6YQ", "fooba" to "MZXW6YTB", "foobar" to "MZXW6YTBOI")
        vectors.forEach { (plain, encoded) ->
            assertThat(Base32.encode(plain.toByteArray())).isEqualTo(encoded)
            assertThat(Base32.decode(encoded)!!.toString(Charsets.US_ASCII)).isEqualTo(plain)
        }
        assertThat(Base32.decode("mzxw 6ytb-oi")!!.toString(Charsets.US_ASCII)).isEqualTo("foobar")
        assertThat(Base32.decode("MZXW1")).isNull()
    }

    @Test
    fun `the otpauth link carries the secret, the issuer and the defaults every app agrees on`() {
        val secret = Totp.newSecret()
        assertThat(secret).hasSize(20)
        val uri = Totp.otpauthUri(secret, "+91····4321")
        assertThat(uri).startsWith("otpauth://totp/Almira:")
            .contains("secret=${Base32.encode(secret)}", "issuer=Almira", "algorithm=SHA1", "digits=6", "period=30")
            .doesNotContain(" ", "+91")
    }
}
