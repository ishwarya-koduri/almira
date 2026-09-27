package tech.almira.reports

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import tech.almira.auth.SignInApiTestBase
import java.util.concurrent.atomic.AtomicLong

/**
 * An export is emailed only to an address the account has proved.
 *
 * The guard, before the sending it guards exists — which is the order this
 * repository insists on, and the reason it does: a guard written after the
 * action it protects has to be retro-fitted onto every path that already
 * reaches it, and one path is always missed (docs/known-issues, "A guard runs
 * before the action it guards"). So the delivery is stage three, and until it
 * lands a destination that passes this guard reaches a plain, deliberate 501.
 *
 * That last case is the one that makes the rest mean anything. A guard that
 * refuses everything passes every refusal test ever written for it, so one test
 * here proves the opposite: a proved address is NOT refused.
 */
@DisplayName("Emailing an export: only to a proved address")
@TestPropertySource(properties = ["almira.exports.email.enabled=true"])
// A context of its own (the flag is off everywhere else), closed after this
// class so its connection pools do not stay open beside every cached context.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExportByEmailGuardApiTest : SignInApiTestBase() {

    private lateinit var owner: Account
    private lateinit var householdId: String

    @BeforeEach
    fun setUp() {
        owner = signUp()
        householdId = createHousehold(owner.token, "Koduri", "private", "Ishwarya").path("id").asText()
    }

    private fun uniqueAddress(): String = "dest.${counter.incrementAndGet()}.${System.nanoTime()}@example.test"

    /** Proves an address the way a person would: a code to it, and the code back. */
    private fun prove(account: Account, address: String): String {
        stepUpByCode(account)
        redis.delete("otp:email:cooldown:verify_email:$address")
        val challenge = post("/api/v1/me/email-addresses", account.token, mapOf("address" to address))
        check(challenge.statusCode.value() == 200) { "could not ask for a code: ${challenge.body}" }
        val confirmed = post(
            "/api/v1/me/email-addresses/confirm", account.token,
            mapOf(
                "address" to address,
                "code" to challenge.json().path("developmentCode").asText(),
                "requestId" to challenge.json().path("requestId").asText(),
            ),
        )
        check(confirmed.statusCode.value() == 200) { "could not prove the address: ${confirmed.body}" }
        return address
    }

    private fun sendTo(account: Account, address: String, format: String = "csv") = post(
        "/api/v1/households/$householdId/reports/export/email", account.token,
        mapOf("address" to address, "format" to format),
    )

    @Test
    fun `an address nobody proved is refused`() {
        prove(owner, uniqueAddress())

        val refused = sendTo(owner, uniqueAddress())
        assertThat(refused.statusCode.value()).describedAs(refused.body).isEqualTo(403)
        assertThat(refused.errorCode()).isEqualTo("email_not_proved")
        assertThat(refused.json().path("error").path("message").asText())
            .describedAs("says what to do, not merely no")
            .containsIgnoringCase("confirm")
    }

    @Test
    fun `an account with no proved address at all is told how to get one`() {
        val refused = sendTo(owner, uniqueAddress())
        assertThat(refused.statusCode.value()).describedAs(refused.body).isEqualTo(403)
        assertThat(refused.errorCode()).isEqualTo("email_not_proved")
    }

    @Test
    fun `somebody else's proved address is refused exactly as an unknown one is`() {
        val stranger = signUp()
        val theirs = prove(stranger, uniqueAddress())

        val toTheirs = sendTo(owner, theirs)
        val toNobodys = sendTo(owner, uniqueAddress())

        // Same status, same code, same words. Otherwise "that address belongs to
        // someone else" is a way to ask who has an account here.
        assertThat(toTheirs.statusCode.value()).isEqualTo(toNobodys.statusCode.value())
        assertThat(toTheirs.errorCode()).isEqualTo(toNobodys.errorCode())
        assertThat(toTheirs.json().path("error").path("message").asText())
            .isEqualTo(toNobodys.json().path("error").path("message").asText())
    }

    @Test
    fun `the guard is not simply refusing everything - a proved address gets past it`() {
        val proved = prove(owner, uniqueAddress())

        val past = sendTo(owner, proved)
        assertThat(past.statusCode.value())
            .describedAs("past the guard, and into the sending that stage three builds: ${past.body}")
            .isEqualTo(501)
        assertThat(past.errorCode()).isEqualTo("not_implemented")
    }

    @Test
    fun `an address that is not an address is refused before anything else`() {
        val refused = sendTo(owner, "not-an-address")
        assertThat(refused.statusCode.value()).describedAs(refused.body).isEqualTo(400)
        assertThat(refused.errorCode()).isEqualTo("email_invalid")
    }

    @Test
    fun `a household that is not yours does not become reachable by emailing it`() {
        val stranger = signUp()
        val proved = prove(stranger, uniqueAddress())

        // The stranger's own proved address, but the owner's household.
        val refused = post(
            "/api/v1/households/$householdId/reports/export/email", stranger.token,
            mapOf("address" to proved, "format" to "csv"),
        )
        assertThat(refused.statusCode.value())
            .describedAs("a household you cannot see is not found: ${refused.body}")
            .isEqualTo(404)
    }

    private companion object {
        val counter = AtomicLong(0)
    }
}
