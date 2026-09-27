package tech.almira.auth

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tech.almira.common.ApiException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * An authenticator app and recovery codes, from outside: setting one up, the
 * second step it adds to a sign-in (the recycled-number defence), confirming
 * it's you with it, and the rules around taking it away.
 */
@DisplayName("Second factor: authenticator app and recovery codes")
class SecondFactorApiTest : SignInApiTestBase() {

    private fun code(secret: ByteArray, stepsAhead: Long = 0) = Totp.code(secret, Totp.step(Instant.now()) + stepsAhead)

    /** As if thirty seconds had passed since the last accepted code. */
    private fun aStepPasses(userId: String) {
        db.update("update user_totp_factors set last_used_step = last_used_step - 3 where user_id = ?::uuid", userId)
    }

    private class Enrolled(val secret: ByteArray, val recoveryCodes: List<String>)

    private fun enrolAuthenticator(account: Account): Enrolled {
        stepUpByCode(account)
        val setup = post("/api/v1/auth/authenticator", account.token)
        assertThat(setup.statusCode.value()).describedAs(setup.body).isEqualTo(200)
        assertThat(setup.headers.cacheControl).contains("no-store")
        val secret = Base32.decode(setup.json().path("secret").asText())!!
        val confirmed = post("/api/v1/auth/authenticator/confirm", account.token, mapOf("code" to code(secret)))
        assertThat(confirmed.statusCode.value()).describedAs(confirmed.body).isEqualTo(200)
        return Enrolled(secret, confirmed.json().path("recoveryCodes").map(JsonNode::asText))
    }

    private fun sessions(userId: String) =
        db.queryForObject("select count(*) from user_sessions where user_id = ?::uuid", Int::class.java, userId)!!

    @Test
    fun `setting up needs a confirmed session, stores only ciphertext, and hands out ten recovery codes once`() {
        val account = signUp()
        val refused = post("/api/v1/auth/authenticator", account.token)
        assertThat(refused.statusCode.value()).isEqualTo(403)
        assertThat(refused.errorCode()).isEqualTo("step_up_required")

        val enrolled = enrolAuthenticator(account)
        assertThat(enrolled.recoveryCodes).hasSize(10)

        val stored = db.queryForObject(
            "select secret_enc from user_totp_factors where user_id = ?::uuid", ByteArray::class.java, account.userId,
        )!!
        assertThat(String(stored, Charsets.ISO_8859_1)).doesNotContain(String(enrolled.secret, Charsets.ISO_8859_1))
        assertThat(String(stored, Charsets.US_ASCII)).doesNotContain(Base32.encode(enrolled.secret))
        val hashes = db.queryForList(
            "select encode(code_hash, 'escape') as h from user_recovery_codes where user_id = ?::uuid", account.userId,
        ).map { it["h"].toString() }
        assertThat(hashes).hasSize(10).noneMatch { h -> enrolled.recoveryCodes.any { h.contains(it.replace("-", "")) } }

        val methods = get("/api/v1/auth/sign-in-methods", account.token).json()
        assertThat(methods.path("authenticator").asBoolean()).isTrue()
        assertThat(methods.path("phoneSignIn").asBoolean()).isTrue()
        assertThat(methods.path("count").asInt()).isEqualTo(2)
        assertThat(methods.path("meetsMinimum").asBoolean()).isTrue()
        assertThat(methods.path("recoveryCodesLeft").asInt()).isEqualTo(10)
        assertThat(methods.path("phone").asText()).doesNotContain(account.phone.takeLast(10).take(6))

        assertThat(
            db.queryForObject(
                "select count(*) from activity_log where actor_user_id = ?::uuid and action = 'auth.authenticator_added'",
                Int::class.java, account.userId,
            ),
        ).isEqualTo(1)
        assertThat(
            db.queryForObject(
                "select count(*) from outbound_messages where user_id = ?::uuid and template = 'auth.authenticator_added' and channel = 'in_app'",
                Int::class.java, account.userId,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `a wrong code does not set the authenticator up`() {
        val account = signUp()
        stepUpByCode(account)
        val secret = Base32.decode(post("/api/v1/auth/authenticator", account.token).json().path("secret").asText())!!
        val wrong = code(secret, 5)
        val refused = post("/api/v1/auth/authenticator/confirm", account.token, mapOf("code" to wrong))
        assertThat(refused.errorCode()).isEqualTo("authenticator_code_invalid")
        assertThat(get("/api/v1/auth/sign-in-methods", account.token).json().path("authenticator").asBoolean()).isFalse()
    }

    /**
     * T-05: the number is recycled. The stranger who now receives its texts gets
     * a correct one-time code — and no session, only a request for a factor
     * they do not have.
     */
    @Test
    fun `a correct one-time code to an account with a second factor starts no session until the factor is given`() {
        val account = signUp()
        val enrolled = enrolAuthenticator(account)
        val before = sessions(account.userId)

        val first = otpSignIn(account.phone, deviceName = "Stranger's phone")
        assertThat(first.statusCode.value()).describedAs(first.body).isEqualTo(401)
        assertThat(first.errorCode()).isEqualTo("second_factor_required")
        val details = first.json().path("error").path("details")
        assertThat(details.path("methods").map(JsonNode::asText)).containsExactly("authenticator", "recovery_code")
        assertThat(first.body).doesNotContain("accessToken", "refreshToken")
        assertThat(sessions(account.userId)).describedAs("no session for a code alone").isEqualTo(before)
        val token = details.path("secondFactorToken").asText()
        assertThat(token).hasSizeGreaterThanOrEqualTo(40)
        assertThat(
            db.queryForObject(
                "select count(*) from activity_log where actor_user_id = ?::uuid and action = 'auth.second_factor_requested'",
                Int::class.java, account.userId,
            ),
        ).describedAs("the request for a second factor is kept, though the sign-in's transaction rolls back").isEqualTo(1)

        val wrong = post(
            "/api/v1/auth/second-factor/authenticator",
            body = mapOf("secondFactorToken" to token, "code" to code(enrolled.secret, 7)),
        )
        assertThat(wrong.errorCode()).isEqualTo("second_factor_invalid")
        assertThat(wrong.json().path("error").path("details").path("attemptsRemaining").asInt()).isEqualTo(4)
        assertThat(wrong.json().path("error").path("message").asText()).isEqualTo("That code doesn't match. 4 tries left.")

        // Down to the last one, it counts in English: "1 try left", not "1 tries left".
        repeat(2) {
            post(
                "/api/v1/auth/second-factor/authenticator",
                body = mapOf("secondFactorToken" to token, "code" to code(enrolled.secret, 7)),
            )
        }
        val lastOne = post(
            "/api/v1/auth/second-factor/authenticator",
            body = mapOf("secondFactorToken" to token, "code" to code(enrolled.secret, 7)),
        )
        assertThat(lastOne.json().path("error").path("message").asText())
            .describedAs(lastOne.body).isEqualTo("That code doesn't match. 1 try left.")
        assertThat(sessions(account.userId)).isEqualTo(before)

        val good = code(enrolled.secret, 1)
        val signedIn = post(
            "/api/v1/auth/second-factor/authenticator",
            body = mapOf("secondFactorToken" to token, "code" to good),
        )
        assertThat(signedIn.statusCode.value()).describedAs(signedIn.body).isEqualTo(200)
        assertThat(signedIn.json().path("user").path("id").asText()).isEqualTo(account.userId)
        assertThat(sessions(account.userId)).isEqualTo(before + 1)
        assertThat(get("/api/v1/me", signedIn.json().path("accessToken").asText()).statusCode.value()).isEqualTo(200)

        // The token is used up, and so is the code.
        val again = post(
            "/api/v1/auth/second-factor/authenticator",
            body = mapOf("secondFactorToken" to token, "code" to good),
        )
        assertThat(again.errorCode()).isEqualTo("second_factor_expired")
        val replay = otpSignIn(account.phone).json().path("error").path("details").path("secondFactorToken").asText()
        assertThat(
            post("/api/v1/auth/second-factor/authenticator", body = mapOf("secondFactorToken" to replay, "code" to good))
                .errorCode(),
        ).describedAs("a code seen once cannot be used again").isEqualTo("second_factor_invalid")

        // The other devices were told, in-app and on the queued channels, with the device named.
        val notices = db.queryForList(
            "select channel, title from outbound_messages where user_id = ?::uuid and template = 'auth.new_sign_in'",
            account.userId,
        )
        assertThat(notices.map { it["channel"] }).contains("in_app")
        assertThat(notices.map { it["title"] }).contains("New sign-in on Stranger's phone")
        assertThat(
            db.queryForObject(
                "select diff->>'secondFactor' from activity_log where actor_user_id = ?::uuid and action = 'auth.login' order by created_at desc limit 1",
                String::class.java, account.userId,
            ),
        ).isEqualTo("authenticator")
    }

    @Test
    fun `five wrong answers end a pending sign-in`() {
        val account = signUp()
        val enrolled = enrolAuthenticator(account)
        val token = otpSignIn(account.phone).json().path("error").path("details").path("secondFactorToken").asText()
        repeat(4) {
            assertThat(
                post("/api/v1/auth/second-factor/authenticator", body = mapOf("secondFactorToken" to token, "code" to "000000"))
                    .errorCode(),
            ).isIn("second_factor_invalid")
        }
        val fifth = post("/api/v1/auth/second-factor/authenticator", body = mapOf("secondFactorToken" to token, "code" to "000000"))
        assertThat(fifth.errorCode()).isEqualTo("second_factor_locked")
        val afterwards = post(
            "/api/v1/auth/second-factor/authenticator",
            body = mapOf("secondFactorToken" to token, "code" to code(enrolled.secret, 1)),
        )
        assertThat(afterwards.errorCode()).isEqualTo("second_factor_expired")
    }

    @Test
    fun `a recovery code signs in once, and the account is told`() {
        val account = signUp()
        val enrolled = enrolAuthenticator(account)
        val recovery = enrolled.recoveryCodes.first()

        val token = otpSignIn(account.phone).json().path("error").path("details").path("secondFactorToken").asText()
        val signedIn = post(
            "/api/v1/auth/second-factor/recovery-code",
            body = mapOf("secondFactorToken" to token, "code" to recovery.lowercase()),
        )
        assertThat(signedIn.statusCode.value()).describedAs(signedIn.body).isEqualTo(200)

        val token2 = otpSignIn(account.phone).json().path("error").path("details").path("secondFactorToken").asText()
        assertThat(
            post("/api/v1/auth/second-factor/recovery-code", body = mapOf("secondFactorToken" to token2, "code" to recovery))
                .errorCode(),
        ).isEqualTo("second_factor_invalid")

        val methods = get("/api/v1/auth/sign-in-methods", signedIn.json().path("accessToken").asText()).json()
        assertThat(methods.path("recoveryCodesLeft").asInt()).isEqualTo(9)
        assertThat(
            db.queryForObject(
                "select count(*) from outbound_messages where user_id = ?::uuid and template = 'auth.recovery_code_used' and channel = 'in_app'",
                Int::class.java, account.userId,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `an account without a second factor signs in exactly as before, and the new sign-in is announced`() {
        val account = signUp()
        val again = otpSignIn(account.phone)
        assertThat(again.statusCode.value()).isEqualTo(200)
        assertThat(again.json().path("isNewUser").asBoolean()).isFalse()
        assertThat(
            db.queryForObject(
                "select count(*) from outbound_messages where user_id = ?::uuid and template = 'auth.new_sign_in' and channel = 'in_app'",
                Int::class.java, account.userId,
            ),
        ).describedAs("the first sign-in made the account; the second is news").isEqualTo(1)
    }

    @Test
    fun `taking the authenticator away needs the authenticator, and never leaves fewer than two ways in`() {
        val account = signUp()
        val enrolled = enrolAuthenticator(account)
        stepUpByCode(account)

        val byCodeOnly = delete("/api/v1/auth/authenticator", account.token)
        assertThat(byCodeOnly.statusCode.value()).isEqualTo(403)
        assertThat(byCodeOnly.json().path("error").path("details").path("requires").asText()).isEqualTo("second_factor")

        val confirmed = post("/api/v1/auth/step-up/authenticator", account.token, mapOf("code" to code(enrolled.secret, 1)))
        assertThat(confirmed.statusCode.value()).describedAs(confirmed.body).isEqualTo(200)

        val refused = delete("/api/v1/auth/authenticator", account.token)
        assertThat(refused.statusCode.value()).describedAs(refused.body).isEqualTo(409)
        assertThat(refused.errorCode()).isEqualTo("sign_in_methods_minimum")
        assertThat(get("/api/v1/auth/sign-in-methods", account.token).json().path("authenticator").asBoolean()).isTrue()
    }

    @Test
    fun `replacing recovery codes needs the second factor, and the old ones stop working`() {
        val account = signUp()
        val enrolled = enrolAuthenticator(account)
        stepUpByCode(account)
        assertThat(post("/api/v1/auth/recovery-codes", account.token).statusCode.value()).isEqualTo(403)

        aStepPasses(account.userId)
        assertThat(
            post("/api/v1/auth/step-up/recovery-code", account.token, mapOf("code" to enrolled.recoveryCodes[3])).statusCode.value(),
        ).isEqualTo(200)
        val replaced = post("/api/v1/auth/recovery-codes", account.token)
        assertThat(replaced.statusCode.value()).describedAs(replaced.body).isEqualTo(200)
        val fresh = replaced.json().path("recoveryCodes").map(JsonNode::asText)
        assertThat(fresh).hasSize(10).doesNotContainAnyElementsOf(enrolled.recoveryCodes)

        val token = otpSignIn(account.phone).json().path("error").path("details").path("secondFactorToken").asText()
        assertThat(
            post("/api/v1/auth/second-factor/recovery-code", body = mapOf("secondFactorToken" to token, "code" to enrolled.recoveryCodes[0]))
                .errorCode(),
        ).isEqualTo("second_factor_invalid")
        assertThat(
            post("/api/v1/auth/second-factor/recovery-code", body = mapOf("secondFactorToken" to token, "code" to fresh[0]))
                .statusCode.value(),
        ).isEqualTo(200)
    }

    @Test
    fun `someone else's factors are not theirs to see or use`() {
        val owner = signUp()
        enrolAuthenticator(owner)
        val other = signUp()
        val theirs = get("/api/v1/auth/sign-in-methods", other.token).json()
        assertThat(theirs.path("authenticator").asBoolean()).isFalse()
        assertThat(theirs.path("recoveryCodesLeft").asInt()).isZero()
        assertThat(theirs.path("count").asInt()).isEqualTo(1)
        assertThat(theirs.path("meetsMinimum").asBoolean()).isFalse()
        // Confirming it's you with a factor you don't have is simply wrong.
        assertThat(post("/api/v1/auth/step-up/authenticator", other.token, mapOf("code" to "123456")).errorCode())
            .isEqualTo("second_factor_invalid")
    }

    @Test
    fun `passkeys are off until a domain is configured, and say so`() {
        val account = signUp()
        assertThat(get("/api/v1/auth/sign-in-methods", account.token).json().path("passkeysAvailable").asBoolean()).isFalse()
        stepUpByCode(account)
        val refused = post("/api/v1/auth/passkeys/options", account.token)
        assertThat(refused.statusCode.value()).isEqualTo(503)
        assertThat(refused.errorCode()).isEqualTo("passkeys_unavailable")
    }

    @Autowired private lateinit var secondFactorService: SecondFactorService

    /**
     * Runs [calls] callers at once through [attempt], each holding its answer
     * open until all of them have reached the check (or a short wait runs out),
     * and returns how many answers were checked.
     */
    private fun answersCheckedInParallel(calls: Int, attempt: (verify: () -> Boolean) -> Unit): Int {
        val checked = AtomicInteger()
        val allInside = CountDownLatch(calls)
        val pool = Executors.newFixedThreadPool(calls)
        try {
            val done = (1..calls).map {
                pool.submit {
                    runCatching {
                        attempt {
                            checked.incrementAndGet()
                            allInside.countDown()
                            allInside.await(2, TimeUnit.SECONDS)
                            false
                        }
                    }.onFailure { if (it !is ApiException) throw it }
                }
            }
            done.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        return checked.get()
    }

    @Test
    fun `wrong answers sent in parallel are counted before any is checked`() {
        val none = SecondFactors(authenticator = true, passkeys = 0, recoveryCodesLeft = 0)

        val signingIn = UUID.randomUUID()
        val token = secondFactorService.challenge(signingIn, none, null, null, null).details["secondFactorToken"] as String
        val atSignIn = answersCheckedInParallel(20) { verify -> secondFactorService.complete(token) { verify() } }
        assertThat(atSignIn).describedAs("answers checked for one pending sign-in").isEqualTo(SecondFactorService.MAX_ATTEMPTS)

        val confirming = UUID.randomUUID()
        val whenConfirming = answersCheckedInParallel(20) { verify -> secondFactorService.guarded(confirming) { verify() } }
        assertThat(whenConfirming.toLong())
            .describedAs("answers checked for one account in an hour")
            .isEqualTo(SecondFactorService.MAX_MISSES_PER_ACCOUNT_PER_HOUR)
    }
}
