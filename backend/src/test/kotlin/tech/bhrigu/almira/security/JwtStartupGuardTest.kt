package tech.bhrigu.almira.security

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tech.bhrigu.almira.config.AlmiraProperties
import java.time.Duration

/**
 * The same refusal the key-encryption key already makes, for the same reason: a
 * deployment that starts with the development signing secret looks entirely
 * healthy and mints tokens anybody who has read this repository can forge.
 */
@DisplayName("Signing secrets outside development")
class JwtStartupGuardTest {

    private fun properties(environment: String, secret: String) = AlmiraProperties(
        db = AlmiraProperties.Db(
            url = "jdbc:postgresql://localhost:55432/almira",
            ownerUser = "almira",
            ownerPassword = "x",
            appUser = "almira_app",
            appPassword = "x",
        ),
        jwt = AlmiraProperties.Jwt(secret = secret, accessTtl = Duration.ofMinutes(15)),
        otp = AlmiraProperties.Otp(),
        environment = environment,
    )

    @Test
    fun `production refuses to start with the development secret`() {
        assertThatThrownBy {
            JwtService(properties("production", AlmiraProperties.DEVELOPMENT_JWT_SECRET))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("refusing to start")
    }

    @Test
    fun `production refuses a secret too short to be worth signing with`() {
        assertThatThrownBy { JwtService(properties("production", "short")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("at least 32")
    }

    /**
     * The hole this exists for. With ALMIRA_ENV unset, application.yml used to
     * resolve the environment to development, both checks above were skipped,
     * and the published secret signed sessions. Reproduced on the real jar
     * before the fix: a token signed with that secret, for a session id the
     * server never issued, returned 200 from /api/v1/me as a real user.
     */
    @Test
    fun `an unset environment is not development and refuses the published secret`() {
        assertThatThrownBy { JwtService(properties("", AlmiraProperties.DEVELOPMENT_JWT_SECRET)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("refusing to start")
            .hasMessageContaining("ALMIRA_ENV is not set")
    }

    /**
     * Failing closed per check, not refusing everything: with no environment
     * but a real secret, this check has nothing to object to. The environment
     * being unset is only dangerous because it used to unlock a relaxation.
     */
    @Test
    fun `an unset environment with a real secret passes this check`() {
        val service = JwtService(properties("", "K".repeat(48)))
        assertThat(service.accessTtlSeconds).isEqualTo(900)
    }

    @Test
    fun `production starts with a real secret`() {
        val service = JwtService(properties("production", "K".repeat(48)))
        assertThat(service.accessTtlSeconds).isEqualTo(900)
    }

    /**
     * Development keeps working with no configuration at all — the app has to
     * run out of the box, which is the whole reason the placeholder exists.
     */
    @Test
    fun `development still starts with the default`() {
        val service = JwtService(properties("development", AlmiraProperties.DEVELOPMENT_JWT_SECRET))
        assertThat(service.accessTtlSeconds).isEqualTo(900)
    }
}
