package tech.almira.reports

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tech.almira.auth.VerifiedEmailService
import tech.almira.common.ApiException
import tech.almira.security.RequestUserContext
import tech.almira.support.ApiTestBase
import java.util.UUID

/**
 * The guard is not part of the switch.
 *
 * `almira.exports.email.enabled` decides whether emailing an export is offered.
 * It does not decide whether the destination has to be proved — nothing does.
 * Turning the feature on must not be able to turn this off, so the guard lives
 * in an unconditional bean and the flag gates only the controller in front of
 * it.
 *
 * This runs in the ordinary suite context, where the flag is **off**, and that
 * is the point of it. It asks the bean directly rather than through the API for
 * the one reason that is legitimate here: with the flag off there is no route
 * to ask through, which is exactly the state being tested.
 */
@DisplayName("The proved-address guard is not part of the switch")
class ProvedAddressGuardIsUnconditionalTest : ApiTestBase() {

    @Autowired private lateinit var destinations: VerifiedEmailService
    @Autowired private lateinit var userContext: RequestUserContext

    @Test
    fun `with emailing exports switched off, the guard is still there and still tells the two apart`() {
        val token = signIn()
        val userId = UUID.fromString(get("/api/v1/me", token).json().path("id").asText())
        val proved = "unconditional.${System.nanoTime()}@example.test"

        // Never proved: refused.
        val refusal = userContext.runAs(userId) {
            catchThrowableOfType(
                { destinations.requireProved(userId, "stranger.${System.nanoTime()}@example.test") },
                ApiException::class.java,
            )
        }
        assertThat(refusal).describedAs("the guard is compiled in with the feature switched off").isNotNull
        assertThat(refusal.code).isEqualTo("email_not_proved")

        // Proved — written as the schema owner, because with the flag off there
        // is no endpoint to prove one through. What is being tested is the
        // guard's judgement, not the route to it.
        db.update(
            "insert into verified_email_addresses (user_id, address) values (?::uuid, ?)",
            userId, proved,
        )
        val accepted = userContext.runAs(userId) { destinations.requireProved(userId, proved) }
        assertThat(accepted.address).isEqualTo(proved)
    }
}
