package tech.bhrigu.almira.crypto

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tech.bhrigu.almira.config.AlmiraProperties
import java.util.Base64

/**
 * The guards that stop this provider being used where it should not be.
 * A KEK that protects nothing is worse than none, because it looks like
 * protection in an audit.
 */
@DisplayName("The local key provider refuses to run insecurely")
class LocalKeyManagementTest {

    private val realKey = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

    @TempDir lateinit var tempDir: java.nio.file.Path

    private fun props(key: String, environment: String, keyFile: String = "") = AlmiraProperties(
        db = AlmiraProperties.Db("jdbc:postgresql://x/y", "u", "p", "u2", "p2"),
        jwt = AlmiraProperties.Jwt("secret-long-enough-for-hmac256-signing-here"),
        otp = AlmiraProperties.Otp(),
        encryption = AlmiraProperties.Encryption(
            masterKey = key,
            devKeyFile = keyFile.ifEmpty { tempDir.resolve("dev-kek").toString() },
        ),
        environment = environment,
    )

    @Test
    fun `refuses to start in production with no key`() {
        assertThatThrownBy { LocalKeyManagement(props("", "production")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("refusing to start")
    }

    /**
     * There is no key in the repository to reject any more. The guard that
     * matters is that production never falls back to a generated one: a deploy
     * that "works" while protecting data with a key nobody chose, kept nowhere
     * durable and lost on the next container, is worse than one that refuses to
     * start.
     */
    @Test
    fun `production never falls back to a generated key`() {
        val keyFile = tempDir.resolve("should-not-appear")
        assertThatThrownBy { LocalKeyManagement(props("", "production", keyFile.toString())) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(java.nio.file.Files.exists(keyFile))
            .describedAs("and it does not quietly leave a key file behind either")
            .isFalse()
    }

    @Test
    fun `development generates its own key, once, per install`() {
        val keyFile = tempDir.resolve("dev-kek")
        val first = LocalKeyManagement(props("", "development", keyFile.toString()))
        assertThat(java.nio.file.Files.exists(keyFile)).isTrue()

        // Restarting reuses it, or every restart would orphan the previous run's data.
        val second = LocalKeyManagement(props("", "development", keyFile.toString()))
        assertThat(second.kekId).isEqualTo(first.kekId)
    }

    @Test
    fun `two installs never share a development key`() {
        val a = LocalKeyManagement(props("", "development", tempDir.resolve("a").toString()))
        val b = LocalKeyManagement(props("", "development", tempDir.resolve("b").toString()))
        assertThat(a.kekId).isNotEqualTo(b.kekId)
    }

    /** Deleting the file is the rotation procedure — and it has real consequences. */
    @Test
    fun `deleting the key file rotates it, and old wrapped keys stop opening`() {
        val keyFile = tempDir.resolve("dev-kek")
        val before = LocalKeyManagement(props("", "development", keyFile.toString()))
        val wrapped = before.wrap(ByteArray(32) { 7 })

        java.nio.file.Files.delete(keyFile)
        val after = LocalKeyManagement(props("", "development", keyFile.toString()))

        assertThat(after.kekId).isNotEqualTo(before.kekId)
        assertThatThrownBy { after.unwrap(wrapped) }
            .describedAs("rotating a KEK makes what it protected unreadable — by design")
            .isInstanceOf(javax.crypto.AEADBadTagException::class.java)
    }

    @Test
    fun `refuses a key that is not 32 bytes`() {
        val short = Base64.getEncoder().encodeToString(ByteArray(16))
        assertThatThrownBy { LocalKeyManagement(props(short, "production")) }
            .hasMessageContaining("32 bytes")
    }

    @Test
    fun `no key material is committed anywhere in the source tree`() {
        // The regression this guards: a base64 blob that decodes to 32 bytes,
        // sitting in a Kotlin file next to the encryption system, is exactly what
        // teaches people that keys in version control are sometimes fine.
        val sources = java.io.File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
        val base64Like = Regex("\"([A-Za-z0-9+/]{40,}={0,2})\"")

        val suspects = sources.flatMap { file ->
            base64Like.findAll(file.readText()).mapNotNull { match ->
                val decoded = runCatching { Base64.getDecoder().decode(match.groupValues[1]) }
                    .getOrNull()
                if (decoded != null && decoded.size == 32) "${'$'}{file.name}: ${'$'}{match.groupValues[1]}" else null
            }
        }.toList()

        assertThat(suspects).describedAs("32-byte base64 literals in source").isEmpty()
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
