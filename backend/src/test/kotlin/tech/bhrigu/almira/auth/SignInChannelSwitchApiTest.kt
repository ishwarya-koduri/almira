package tech.bhrigu.almira.auth

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tech.bhrigu.almira.security.JwtService
import tech.bhrigu.almira.support.ApiTestBase
import java.time.Instant

/**
 * The channel switch, from outside: a channel the server does not offer is
 * refused before anything about the caller is looked at, and step-up never
 * falls back to a channel sign-in has turned off.
 */
class SignInChannelSwitchApiTest {

    /** The configuration every other suite runs with: phone only. */
    @DisplayName("A phone-only server")
    class PhoneOnly : ApiTestBase() {

        @Autowired private lateinit var repo: AuthRepository
        @Autowired private lateinit var jwt: JwtService

        @Test
        fun `offers phone, and refuses both email endpoints with a code that names the channel`() {
            assertThat(get("/api/v1/auth/otp/channels").json().path("channels").map(JsonNode::asText))
                .containsExactly("phone")
            val request = post("/api/v1/auth/otp/email/request", body = mapOf("email" to "someone@example.test"))
            val verify = post(
                "/api/v1/auth/otp/email/verify",
                body = mapOf("email" to "someone@example.test", "code" to "123456"),
            )
            for (r in listOf(request, verify)) {
                assertThat(r.statusCode.value()).describedAs(r.body).isEqualTo(403)
                assertThat(r.errorCode()).isEqualTo("sign_in_channel_disabled")
                assertThat(r.json().path("error").path("details").path("channel").asText()).isEqualTo("email")
            }
        }

        /**
         * An email-only account has no way in here, so it holds no session here
         * either: its first request ends the session (AlphaAllowlistAccess —
         * a server without email sign-in is the alpha ended). Before
         * 2026-09-14 this account reached step-up and was told
         * `no_step_up_channel`; that answer is now tested on the email-only
         * server, for a phone-only account.
         */
        @Test
        fun `an email-only account's session is ended on its first request, step-up included`() {
            val user = repo.createWithEmail("stranded-${System.nanoTime()}@example.test")
            val session = repo.createSession(user.id, "test", null, null, Instant.now().plus(jwt.refreshTtl))
            val token = jwt.issueAccessToken(user.id, session)

            for (path in listOf("/api/v1/auth/step-up/request", "/api/v1/auth/step-up/verify")) {
                val r = post(path, token, mapOf("code" to "123456"))
                assertThat(r.statusCode.value()).describedAs("$path: ${r.body}").isEqualTo(401)
            }
            assertThat(db.queryForObject("select revoked_reason from user_sessions where id = ?", String::class.java, session))
                .isEqualTo(AlphaAllowlistAccess.REASON)
        }
    }

    /** The alpha configuration: email alone, with an allowlist. */
    @DisplayName("An email-only server")
    class EmailOnly : ApiTestBase() {

        @Autowired private lateinit var repo: AuthRepository
        @Autowired private lateinit var jwt: JwtService

        /**
         * StepUpService read `user.phone!!`, and an account with no way to
         * receive a code here was a 500 on the way to seeing an account number.
         * A phone-only account on an email-only server is that account now.
         */
        @Test
        fun `a phone-only account asking to step up is told why, not given a 500`() {
            val user = repo.createWithPhone(uniquePhone())
            val session = repo.createSession(user.id, "test", null, null, Instant.now().plus(jwt.refreshTtl))
            val token = jwt.issueAccessToken(user.id, session)

            for (path in listOf("/api/v1/auth/step-up/request", "/api/v1/auth/step-up/verify")) {
                val r = post(path, token, mapOf("code" to "123456"))
                assertThat(r.statusCode.value()).describedAs("$path: ${r.body}").isEqualTo(400)
                assertThat(r.errorCode()).isEqualTo("no_step_up_channel")
            }
        }

        @Test
        fun `offers email, and refuses both phone endpoints before looking at the number`() {
            assertThat(get("/api/v1/auth/otp/channels").json().path("channels").map(JsonNode::asText))
                .containsExactly("email")
            val phone = uniquePhone()
            val request = post("/api/v1/auth/otp/request", body = mapOf("phone" to phone))
            val verify = post("/api/v1/auth/otp/verify", body = mapOf("phone" to phone, "code" to "123456"))
            for (r in listOf(request, verify)) {
                assertThat(r.statusCode.value()).describedAs(r.body).isEqualTo(403)
                assertThat(r.errorCode()).isEqualTo("sign_in_channel_disabled")
                assertThat(r.json().path("error").path("details").path("channel").asText()).isEqualTo("phone")
                assertThat(r.body).doesNotContain(phone)
            }
            // A malformed number is refused the same way: nothing about it was read.
            assertThat(post("/api/v1/auth/otp/request", body = mapOf("phone" to "12")).errorCode())
                .isEqualTo("sign_in_channel_disabled")
        }

        @Test
        fun `an account that has a number too confirms itself by email, never by the channel that is off`() {
            val address = listed
            val challenge = post("/api/v1/auth/otp/email/request", body = mapOf("email" to address))
            assertThat(challenge.statusCode.value()).describedAs(challenge.body).isEqualTo(200)
            // The development sandbox echoes, which is what makes this runnable with no provider.
            val code = challenge.json().path("developmentCode").asText()
            assertThat(code).matches("[0-9]{6}")
            val token = post("/api/v1/auth/otp/email/verify", body = mapOf("email" to address, "code" to code))
                .json().path("accessToken").asText()
            val id = get("/api/v1/me", token).json().path("id").asText()
            db.update("update users set phone = ? where id = ?::uuid", uniquePhone(), id)

            val stepUp = post("/api/v1/auth/step-up/request", token)
            assertThat(stepUp.statusCode.value()).describedAs(stepUp.body).isEqualTo(200)
            assertThat(stepUp.json().path("channel").asText()).isEqualTo("email")
            val elevated = post(
                "/api/v1/auth/step-up/verify", token,
                mapOf("code" to stepUp.json().path("developmentCode").asText()),
            )
            assertThat(elevated.statusCode.value()).describedAs(elevated.body).isEqualTo(200)
        }

        companion object {
            private val listed = "only.email.${System.nanoTime()}@example.test"

            @JvmStatic
            @DynamicPropertySource
            fun emailOnly(registry: DynamicPropertyRegistry) {
                registry.add("almira.auth.sign-in-channels") { "email" }
                registry.add("almira.auth.email-allowlist") { listed }
            }
        }
    }
}
