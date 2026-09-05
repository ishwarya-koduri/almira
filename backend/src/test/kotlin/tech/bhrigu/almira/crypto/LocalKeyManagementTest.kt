package tech.bhrigu.almira.crypto

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tech.bhrigu.almira.config.AlmiraProperties
import java.time.Duration
import java.util.Base64

/**
 * The guards that stop this provider being used where it should not be.
 * A KEK that protects nothing is worse than none, because it looks like
 * protection in an audit.
 */
@DisplayName("The local key provider refuses to run insecurely")
class LocalKeyManagementTest {

    private val realKey = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    private val publishedDevKey = "<scrubbed-development-key-placeholder>="

    private fun props(key: String, environment: String) = AlmiraProperties(
        db = AlmiraProperties.Db("jdbc:postgresql://x/y", "u", "p", "u2", "p2"),
        jwt = AlmiraProperties.Jwt("secret-long-enough-for-hmac256-signing-here"),
        otp = AlmiraProperties.Otp(),
        encryption = AlmiraProperties.Encryption(masterKey = key),
        environment = environment,
    )

    @Test
    fun `refuses to start in production with no key`() {
        assertThatThrownBy { LocalKeyManagement(props("", "production")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("refusing to start")
    }

    @Test
    fun `refuses to start in production with the published development key`() {
        assertThatThrownBy { LocalKeyManagement(props(publishedDevKey, "production")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("protects nothing")
    }

    @Test
    fun `refuses a key that is not 32 bytes`() {
        val short = Base64.getEncoder().encodeToString(ByteArray(16))
        assertThatThrownBy { LocalKeyManagement(props(short, "production")) }
            .hasMessageContaining("32 bytes")
    }

    @Test
    fun `refuses a key that is not base64`() {
        assertThatThrownBy { LocalKeyManagement(props("not base64!!", "production")) }
            .hasMessageContaining("base64")
    }

    @Test
    fun `starts in development without a key, so the app runs out of the box`() {
        val kms = LocalKeyManagement(props("", "development"))
        assertThat(kms.kekId).startsWith("local:")
    }

    @Test
    fun `wraps and unwraps a data key`() {
        val kms = LocalKeyManagement(props(realKey, "production"))
        val dataKey = ByteArray(32) { (it * 7).toByte() }
        val wrapped = kms.wrap(dataKey)

        assertThat(wrapped).isNotEqualTo(dataKey)
        assertThat(kms.unwrap(wrapped)).isEqualTo(dataKey)
    }

    @Test
    fun `a key wrapped by one KEK cannot be unwrapped by another`() {
        val first = LocalKeyManagement(props(realKey, "production"))
        val otherKey = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
        val second = LocalKeyManagement(props(otherKey, "production"))

        val wrapped = first.wrap(ByteArray(32))
        assertThatThrownBy { second.unwrap(wrapped) }
            .isInstanceOf(javax.crypto.AEADBadTagException::class.java)
        assertThat(first.kekId).isNotEqualTo(second.kekId)
    }

    @Test
    fun `the KEK identifier is a fingerprint, not the key`() {
        val kms = LocalKeyManagement(props(realKey, "production"))
        assertThat(kms.kekId).doesNotContain(realKey)
        assertThat(kms.kekId.length).isLessThan(realKey.length)
    }
}
