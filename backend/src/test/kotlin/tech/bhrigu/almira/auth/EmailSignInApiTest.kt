package tech.bhrigu.almira.auth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.ResponseEntity
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tech.bhrigu.almira.provider.SandboxFault
import tech.bhrigu.almira.provider.SandboxFaults
import tech.bhrigu.almira.support.ApiTestBase
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Closed-alpha sign-in by email, over real HTTP, on a server offering both
 * channels with an allowlist.
 *
 * The central claim is that nobody outside can tell an allowlisted address from
 * any other: the same status, the same fields, the same headers, the same
 * refusals in the same order, and an answer that does not wait for an email to
 * be sent — while the listed address really is sent a code and the other is not.
 */
@DisplayName("Sign-in by email for the closed alpha")
@Import(EmailSignInApiTest.Recording::class)
class EmailSignInApiTest : ApiTestBase() {

    /** Records codes, can be told to be slow or to fail, never echoes. */
    class ControllableEmailSender : EmailOtpSender {
        val sent = CopyOnWriteArrayList<Pair<String, String>>()
        val faults = SandboxFaults()
        @Volatile var delay: Duration = Duration.ZERO
        override val available = true
        override fun send(email: String, code: String) {
            if (!delay.isZero) Thread.sleep(delay.toMillis())
            sent += email to code
            faults.apply("email")
        }

        /** Waits for the [nth] code (1-based) to [email]: the send happens after the answer. */
        fun codeFor(email: String, nth: Int = 1): String {
            val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
            while (System.nanoTime() < deadline) {
                val codes = sent.filter { it.first == email }
                if (codes.size >= nth) return codes[nth - 1].second
                Thread.sleep(10)
            }
            throw AssertionError("code #$nth was never sent to $email")
        }
    }

    @TestConfiguration
    class Recording {
        @Bean @Primary
        fun controllableEmailSender() = ControllableEmailSender()
    }

    @Autowired private lateinit var sender: ControllableEmailSender
    @Autowired private lateinit var redis: org.springframework.data.redis.core.StringRedisTemplate

    @AfterEach
    fun reset() {
        sender.delay = Duration.ZERO
        sender.faults.clear()
    }

    private fun requestCode(email: String) = post("/api/v1/auth/otp/email/request", body = mapOf("email" to email))

    private fun verifyCode(email: String, code: String, requestId: String? = null) = post(
        "/api/v1/auth/otp/email/verify",
        body = buildMap { put("email", email); put("code", code); requestId?.let { put("requestId", it) } },
    )

    /** Everything about a response a caller could compare, with the values that must vary taken out. */
    private fun shape(r: ResponseEntity<String>): String {
        val json = r.json()
        if (json is ObjectNode) {
            json.path("error").let { (it as? ObjectNode)?.remove("at") }
            json.remove("requestId")?.let { assertThat(it.asText()).matches("[0-9a-f-]{36}") }
        }
        // Retry-after can differ by the second it took to run; nothing else may.
        val body = json.toString().replace(Regex("[0-9]+ seconds"), "N seconds")
            .replace(Regex("\"retryAfterSeconds\":[0-9]+"), "\"retryAfterSeconds\":N")
        val headers = r.headers.keys.map { it.lowercase() }.sorted()
        return "${r.statusCode.value()} $headers $body"
    }

    private fun wrongFor(code: String) = if (code == "000000") "111111" else "000000"

    @Test
    fun `the server says which channels it offers`() {
        val r = get("/api/v1/auth/otp/channels")
        assertThat(r.statusCode.value()).isEqualTo(200)
        assertThat(r.json().path("channels").map(JsonNode::asText)).containsExactly("phone", "email")
    }

    @Test
    fun `an allowlisted address signs in, becoming an email-only account, and signs in again as the same one`() {
        val address = listed[0]
        val typed = "  " + address.uppercase() + " "
        val challenge = requestCode(typed)
        assertThat(challenge.statusCode.value()).describedAs(challenge.body).isEqualTo(200)
        assertThat(challenge.json().path("channel").asText()).isEqualTo("email")
        assertThat(challenge.json().has("developmentCode")).isFalse()
        val code = sender.codeFor(address)

        val login = verifyCode(typed, code, challenge.json().path("requestId").asText())
        assertThat(login.statusCode.value()).describedAs(login.body).isEqualTo(200)
        assertThat(login.json().path("isNewUser").asBoolean()).isTrue()
        val user = login.json().path("user")
        assertThat(user.path("email").asText()).isEqualTo(address)
        assertThat(user.has("phone")).isFalse()
        assertThat(db.queryForObject("select auth_provider from users where email = ?", String::class.java, address))
            .isEqualTo("email")
        val token = login.json().path("accessToken").asText()
        assertThat(get("/api/v1/me", token).json().path("email").asText()).isEqualTo(address)

        // The cooldown is thirty seconds of real time; lift it rather than sit through it.
        redis.delete("otp:email:cooldown:login:$address")
        val again = requestCode(address)
        assertThat(again.statusCode.value()).describedAs(again.body).isEqualTo(200)
        val second = verifyCode(address, sender.codeFor(address, nth = 2))
        assertThat(second.statusCode.value()).describedAs(second.body).isEqualTo(200)
        assertThat(second.json().path("isNewUser").asBoolean()).isFalse()
        assertThat(second.json().path("user").path("id").asText()).isEqualTo(user.path("id").asText())
    }

    @Test
    fun `an address off the allowlist cannot be told apart from one on it, and is sent nothing`() {
        val insider = listed[1]
        val outsider = "outsider-$run@example.test"

        val requests = listOf(insider, outsider).map(::requestCode)
        assertThat(shape(requests[0])).isEqualTo(shape(requests[1]))
        assertThat(requests[0].statusCode.value()).isEqualTo(200)
        val insiderCode = sender.codeFor(insider)

        // Asking again at once: both refused, identically.
        val resends = listOf(insider, outsider).map(::requestCode)
        assertThat(resends[0].statusCode.value()).isEqualTo(429)
        assertThat(shape(resends[0])).isEqualTo(shape(resends[1]))

        // A stale request id, then wrong codes until the lock, then the lock itself.
        val stale = listOf(insider, outsider).map { verifyCode(it, "123456", "not-the-request") }
        assertThat(stale[0].errorCode()).isEqualTo("otp_stale")
        assertThat(shape(stale[0])).isEqualTo(shape(stale[1]))
        repeat(5) { attempt ->
            val guesses = listOf(insider, outsider).map { verifyCode(it, wrongFor(insiderCode)) }
            assertThat(guesses[0].errorCode()).isEqualTo(if (attempt < 4) "otp_invalid" else "otp_locked")
            assertThat(shape(guesses[0])).describedAs("guess ${attempt + 1}").isEqualTo(shape(guesses[1]))
        }
        val afterLock = listOf(insider, outsider).map { verifyCode(it, insiderCode) }
        assertThat(afterLock[0].errorCode()).isEqualTo("otp_expired")
        assertThat(shape(afterLock[0])).isEqualTo(shape(afterLock[1]))

        assertThat(sender.sent.map { it.first }).doesNotContain(outsider)
    }

    @Test
    fun `the answer does not wait for the email, so it takes no longer for an address on the list`() {
        sender.delay = Duration.ofMillis(1_500)
        val insider = listed[2]
        val outsider = "slow-outsider-$run@example.test"
        // Warm both paths so the first request's class loading is not measured.
        requestCode("warm-$run@example.test")

        fun timed(email: String): Pair<ResponseEntity<String>, Long> {
            val start = System.nanoTime()
            val r = requestCode(email)
            return r to Duration.ofNanos(System.nanoTime() - start).toMillis()
        }
        val (insiderResponse, insiderMillis) = timed(insider)
        val (outsiderResponse, outsiderMillis) = timed(outsider)
        assertThat(shape(insiderResponse)).isEqualTo(shape(outsiderResponse))
        assertThat(insiderMillis)
            .describedAs("an allowlisted request answered in ${insiderMillis}ms against a 1500ms send " +
                "(the outsider took ${outsiderMillis}ms); waiting for the send would reveal the list")
            .isLessThan(750)
        sender.codeFor(insider)
    }

    @Test
    fun `a failing email provider does not change the answer for an address on the list`() {
        val insider = listed[3]
        val outsider = "failing-outsider-$run@example.test"
        for ((i, fault) in listOf(SandboxFault.REJECTED, SandboxFault.INSUFFICIENT_BALANCE).withIndex()) {
            sender.faults.always("email", fault)
            val a = if (i == 0) insider else listed[4]
            val b = "$i-$outsider"
            val responses = listOf(a, b).map(::requestCode)
            assertThat(responses[0].statusCode.value()).describedAs(fault.name).isEqualTo(200)
            assertThat(shape(responses[0])).describedAs(fault.name).isEqualTo(shape(responses[1]))
            sender.codeFor(a)
        }
    }

    @Test
    fun `a malformed address is refused the same way whoever asks`() {
        val r = requestCode("not-an-address")
        assertThat(r.statusCode.value()).isEqualTo(400)
        assertThat(r.errorCode()).isEqualTo("email_invalid")
    }

    // --- step-up ------------------------------------------------------------------

    private fun signInByEmail(address: String): String {
        val challenge = requestCode(address)
        check(challenge.statusCode.value() == 200) { "email request failed: ${challenge.body}" }
        return verifyCode(address, sender.codeFor(address)).json().path("accessToken").asText()
    }

    @Test
    fun `an email-only account confirms itself by email`() {
        val address = listed[5]
        val token = signInByEmail(address)
        val before = sender.sent.size

        val challenge = post("/api/v1/auth/step-up/request", token)
        assertThat(challenge.statusCode.value()).describedAs(challenge.body).isEqualTo(200)
        assertThat(challenge.json().path("channel").asText()).isEqualTo("email")
        assertThat(sender.sent.size).describedAs("sent before answering, not after").isEqualTo(before + 1)
        val (to, code) = sender.sent.last()
        assertThat(to).isEqualTo(address)

        assertThat(post("/api/v1/auth/step-up/verify", token, mapOf("code" to wrongFor(code))).errorCode())
            .isEqualTo("otp_invalid")
        val elevated = post(
            "/api/v1/auth/step-up/verify", token,
            mapOf("code" to code, "requestId" to challenge.json().path("requestId").asText()),
        )
        assertThat(elevated.statusCode.value()).describedAs(elevated.body).isEqualTo(200)
        assertThat(get("/api/v1/auth/step-up", token).json().path("elevated").asBoolean()).isTrue()
    }

    @Test
    fun `a step-up email that cannot be delivered says so, because the caller already owns the address`() {
        val token = signInByEmail(listed[6])
        sender.faults.always("email", SandboxFault.REJECTED)
        val r = post("/api/v1/auth/step-up/request", token)
        assertThat(r.statusCode.value()).isEqualTo(422)
        assertThat(r.errorCode()).isEqualTo("otp_delivery_failed")
    }

    @Test
    fun `a phone account still confirms itself by phone, and phone comes first when an account has both`() {
        val phone = uniquePhone()
        val token = signIn(phone)
        val challenge = post("/api/v1/auth/step-up/request", token)
        assertThat(challenge.statusCode.value()).describedAs(challenge.body).isEqualTo(200)
        assertThat(challenge.json().path("channel").asText()).isEqualTo("phone")
        val code = challenge.json().path("developmentCode").asText()
        assertThat(post("/api/v1/auth/step-up/verify", token, mapOf("code" to code)).statusCode.value()).isEqualTo(200)

        val both = signIn(uniquePhone())
        val id = get("/api/v1/me", both).json().path("id").asText()
        db.update("update users set email = ? where id = ?::uuid", listed[7], id)
        val before = sender.sent.size
        val r = post("/api/v1/auth/step-up/request", both)
        assertThat(r.json().path("channel").asText()).isEqualTo("phone")
        assertThat(sender.sent.size).isEqualTo(before)
    }

    companion object {
        private val run = System.nanoTime()
        private val listed = (0..9).map { "alpha.tester+$it.$run@example.test" }

        @JvmStatic
        @DynamicPropertySource
        fun emailSignIn(registry: DynamicPropertyRegistry) {
            registry.add("almira.auth.sign-in-channels") { "phone,email" }
            // Typed untidily on purpose: the list is normalised as sign-in is.
            registry.add("almira.auth.email-allowlist") { listed.joinToString(" , ") { it.uppercase() } }
        }
    }
}
