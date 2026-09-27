package tech.almira.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import java.util.concurrent.atomic.AtomicLong

/**
 * Proving an address before anything can be sent to it (stage 1 of emailing an
 * export).
 *
 * The flow is deliberately the phone-change flow with the channel swapped: a
 * signed-in session, a step-up, a one-time code to the address itself, and the
 * address is on the account only once that code comes back. What it must never
 * become is a way to sign in — that is `users.email`, and this is a separate
 * table on purpose.
 *
 * Its own context, because the feature flag is off everywhere else and that is
 * the point of it: every other test in the suite runs with
 * `almira.exports.email.enabled` unset and must be unaffected.
 */
@DisplayName("Proving an email address")
@TestPropertySource(properties = ["almira.exports.email.enabled=true"])
// A context of its own (the flag is off everywhere else), closed after this
// class so its connection pools do not stay open beside every cached context.
// One extra cached context was enough to run the test database out of
// connection slots: 25 provider and sharing contexts failed to load, which is
// the same failure commit 8479f1b fixed for five of them.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VerifiedEmailApiTest : SignInApiTestBase() {

    private fun uniqueAddress(): String = "proved.${counter.incrementAndGet()}.${System.nanoTime()}@example.test"

    private fun add(account: Account, address: String) =
        // The cooldown is per address and per purpose; a fresh address in each
        // test means it is never in the way, but a retry inside one test would be.
        redis.delete("otp:email:cooldown:verify_email:$address").let {
            post("/api/v1/me/email-addresses", account.token, mapOf("address" to address))
        }

    private fun confirm(account: Account, address: String, code: String, requestId: String?) =
        post(
            "/api/v1/me/email-addresses/confirm", account.token,
            buildMap {
                put("address", address)
                put("code", code)
                requestId?.let { put("requestId", it) }
            },
        )

    private fun listed(account: Account): List<String> {
        val response = get("/api/v1/me/email-addresses", account.token)
        // Asserted here: a helper that returns an empty list for a 404 turns
        // "the route is missing" into "the address was not added", which is a
        // different bug and a much longer afternoon.
        assertThat(response.statusCode.value()).describedAs(response.body).isEqualTo(200)
        return response.json().path("addresses").map { it.path("address").asText() }
    }

    @Test
    fun `needs a session - and one that has just confirmed it's you`() {
        val anonymous = post("/api/v1/me/email-addresses", body = mapOf("address" to uniqueAddress()))
        assertThat(anonymous.statusCode.value()).isEqualTo(401)

        val account = signUp()
        val notConfirmed = add(account, uniqueAddress())
        assertThat(notConfirmed.statusCode.value()).describedAs(notConfirmed.body).isEqualTo(403)
        assertThat(notConfirmed.errorCode()).isEqualTo("step_up_required")
        assertThat(notConfirmed.json().path("error").path("message").asText())
            .contains("email address")

        val confirmWithout = confirm(account, uniqueAddress(), "123456", null)
        assertThat(confirmWithout.statusCode.value()).isEqualTo(403)
        assertThat(confirmWithout.errorCode()).isEqualTo("step_up_required")
    }

    @Test
    fun `a code to the address is what puts it on the account, and a wrong one does not`() {
        val account = signUp()
        stepUpByCode(account)
        val address = uniqueAddress()

        val challenge = add(account, address)
        assertThat(challenge.statusCode.value()).describedAs(challenge.body).isEqualTo(200)
        val requestId = challenge.json().path("requestId").asText()
        val code = challenge.json().path("developmentCode").asText()
        assertThat(code).describedAs("the sandbox sender echoes it in development").isNotBlank()

        // Not on the account yet. Asking for a code is not proving anything.
        assertThat(listed(account)).doesNotContain(address)

        val wrong = confirm(account, address, "000000", requestId)
        assertThat(wrong.errorCode()).isEqualTo("otp_invalid")
        assertThat(listed(account)).doesNotContain(address)

        val proved = confirm(account, address, code, requestId)
        assertThat(proved.statusCode.value()).describedAs(proved.body).isEqualTo(200)
        assertThat(proved.json().path("address").asText()).isEqualTo(address)
        assertThat(listed(account)).contains(address)
    }

    @Test
    fun `proving an address is not a way to sign in with it`() {
        val account = signUp()
        stepUpByCode(account)
        val address = uniqueAddress()
        val challenge = add(account, address)
        confirm(
            account, address,
            challenge.json().path("developmentCode").asText(),
            challenge.json().path("requestId").asText(),
        )

        // users.email is the identity column. A proved delivery address must
        // never land in it, or the account has a second front door.
        val identity = db.queryForObject(
            "select coalesce(email::text, '') from users where id = ?::uuid",
            String::class.java, account.userId,
        )
        assertThat(identity).describedAs("the account's sign-in identity").isEmpty()
    }

    @Test
    fun `an address already proved on another account answers exactly as a new one does`() {
        val first = signUp()
        stepUpByCode(first)
        val shared = uniqueAddress()
        val firstChallenge = add(first, shared)
        confirm(
            first, shared,
            firstChallenge.json().path("developmentCode").asText(),
            firstChallenge.json().path("requestId").asText(),
        )
        assertThat(listed(first)).contains(shared)

        val second = signUp()
        stepUpByCode(second)
        val fresh = uniqueAddress()

        val onShared = add(second, shared)
        val onFresh = add(second, fresh)

        // Same status and the same fields, or "that address is taken" becomes a
        // way to ask whether a stranger has an account here.
        assertThat(onShared.statusCode.value()).isEqualTo(onFresh.statusCode.value())
        assertThat(onShared.json().fieldNames().asSequence().toSet())
            .isEqualTo(onFresh.json().fieldNames().asSequence().toSet())

        // And it really can be proved on the second account too: one mailbox in
        // a household is not a conflict.
        val proved = confirm(
            second, shared,
            onShared.json().path("developmentCode").asText(),
            onShared.json().path("requestId").asText(),
        )
        assertThat(proved.statusCode.value()).describedAs(proved.body).isEqualTo(200)
        assertThat(listed(second)).contains(shared)
    }

    @Test
    fun `the address is audited masked, never in full`() {
        val account = signUp()
        stepUpByCode(account)
        val address = uniqueAddress()
        val challenge = add(account, address)
        confirm(
            account, address,
            challenge.json().path("developmentCode").asText(),
            challenge.json().path("requestId").asText(),
        )

        val diff = db.queryForObject(
            "select diff::text from activity_log where actor_user_id = ?::uuid and action = 'auth.email_proved'",
            String::class.java, account.userId,
        )!!
        assertThat(diff).doesNotContain(address)
        assertThat(diff).contains("example.test")
    }

    @Test
    fun `removing one takes it off the account`() {
        val account = signUp()
        stepUpByCode(account)
        val address = uniqueAddress()
        val challenge = add(account, address)
        val proved = confirm(
            account, address,
            challenge.json().path("developmentCode").asText(),
            challenge.json().path("requestId").asText(),
        )
        val id = proved.json().path("id").asText()

        val removed = delete("/api/v1/me/email-addresses/$id", account.token)
        assertThat(removed.statusCode.value()).describedAs(removed.body).isEqualTo(204)
        assertThat(listed(account)).doesNotContain(address)

        // Somebody else's row is not theirs to remove, and the answer does not
        // say whether it exists.
        val stranger = signUp()
        assertThat(delete("/api/v1/me/email-addresses/$id", stranger.token).statusCode.value())
            .isEqualTo(404)
    }

    @Test
    fun `an account cannot collect an unbounded list of destinations`() {
        val account = signUp()
        stepUpByCode(account)

        repeat(5) {
            val address = uniqueAddress()
            val challenge = add(account, address)
            assertThat(challenge.statusCode.value()).describedAs(challenge.body).isEqualTo(200)
            val proved = confirm(
                account, address,
                challenge.json().path("developmentCode").asText(),
                challenge.json().path("requestId").asText(),
            )
            assertThat(proved.statusCode.value()).describedAs(proved.body).isEqualTo(200)
        }

        val sixth = add(account, uniqueAddress())
        assertThat(sixth.errorCode()).isEqualTo("too_many_addresses")
    }

    private companion object {
        val counter = AtomicLong(0)
    }
}
