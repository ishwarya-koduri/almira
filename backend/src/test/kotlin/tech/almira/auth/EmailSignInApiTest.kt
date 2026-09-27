package tech.almira.auth

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
import tech.almira.provider.FailureKind
import tech.almira.provider.ProviderFailure
import tech.almira.provider.SandboxFault
import tech.almira.provider.SandboxFaults
import tech.almira.support.ApiTestBase
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Closed-alpha sign-in by email, over real HTTP, on a server offering both
 * channels with an allowlist.
 *
 * The central claim is that nobody outside can tell an allowlisted address from
 * any other: the same status, the same fields, the same headers, the same
 * refusals in the same order, and an answer that neither waits for nor calls the
 * email provider — while the outbox worker really sends the listed address a
 * code and drops the other's message unsent.
 */
@DisplayName("Sign-in by email for the closed alpha")
@Import(EmailSignInApiTest.Recording::class)
class EmailSignInApiTest : ApiTestBase() {

    /**
     * Records codes, can be told to be slow or to fail, never echoes. [faults]
     * fail every send, as a provider that is down does; [refuses] are refused
     * one address at a time, as a provider refuses a suppressed mailbox.
     */
    class ControllableEmailSender : EmailOtpSender {
        /** The worker, which the suite does not run on its own (almira.outbox.background). */
        @Volatile var outbox: SignInEmailOutbox? = null
        val sent = CopyOnWriteArrayList<Pair<String, String>>()
        val faults = SandboxFaults()
        val refuses: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        @Volatile var delay: Duration = Duration.ZERO
        override val available = true
        override fun send(email: String, code: String) {
            if (!delay.isZero) Thread.sleep(delay.toMillis())
            sent += email to code
            if (email in refuses) throw ProviderFailure(FailureKind.REJECTED, "test: this address is refused")
            faults.apply("email")
        }

        /** Waits for the [nth] code (1-based) to [email]: the send happens after the answer, when the worker runs. */
        fun codeFor(email: String, nth: Int = 1): String {
            outbox?.drain()
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
    @Autowired private lateinit var outbox: SignInEmailOutbox

    @AfterEach
    fun reset() {
        sender.faults.clear()
        sender.refuses.clear()
        sender.delay = Duration.ZERO
        // Nothing of this test is left queued for another class's worker to decide.
        outbox.drain()
    }

    @org.junit.jupiter.api.BeforeEach
    fun connect() {
        sender.outbox = outbox
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
        // Hop-by-hop headers describe the connection, not the answer: Tomcat closes a
        // keep-alive connection after its 100th request with "Connection: close",
        // and the status polls below make that land on a compared response at random.
        val headers = r.headers.keys.map { it.lowercase() }.filterNot { it in HOP_BY_HOP }.sorted()
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

    /**
     * The owner's decision (2026-09-15): nothing on the request path talks to
     * the provider, for any address. A provider taking 900 ms, or failing,
     * cannot show in the answer, because the answer never reaches it: the email
     * is queued and sent later by the worker, which here runs when the test says.
     */
    @Test
    fun `the answer never waits for or calls the provider, so it takes no longer for an address on the list`() {
        sender.delay = Duration.ofMillis(900)
        val insider = listed[2]
        val outsider = "slow-outsider-$run@example.test"
        // Warm both paths so the first request's class loading is not measured.
        requestCode("warm-$run@example.test")
        outbox.drain()
        val before = sender.sent.size

        fun timed(email: String): Pair<ResponseEntity<String>, Long> {
            val start = System.nanoTime()
            val r = requestCode(email)
            return r to Duration.ofNanos(System.nanoTime() - start).toMillis()
        }
        val (insiderResponse, insiderMillis) = timed(insider)
        val (outsiderResponse, outsiderMillis) = timed(outsider)
        assertThat(shape(insiderResponse)).isEqualTo(shape(outsiderResponse))
        assertThat(sender.sent.size).describedAs("provider calls made on the request path").isEqualTo(before)
        assertThat(insiderMillis)
            .describedAs("an allowlisted request answered in ${insiderMillis}ms against a 900ms send " +
                "(the outsider took ${outsiderMillis}ms)")
            .isLessThan(750)
        assertThat(queued()).describedAs("both queued, the same way").isEqualTo(2)
        assertThat(outbox.drain()).isEqualTo(SignInEmailDrainResult(sent = 1, dropped = 1))
        assertThat(sender.sent.map { it.first }.drop(before)).containsExactly(insider)
    }

    private fun queued() = db.queryForObject(
        "select count(*) from sign_in_code_emails e join sign_in_code_email_bodies b on b.message_id = e.id where e.status = 'queued'",
        Int::class.java,
    )!!

    private fun delivery(requestId: String) = get("/api/v1/auth/otp/email/delivery/$requestId")

    /**
     * Polls the delivery status until it has settled: the final answer, and how
     * long after [askedAt] (a System.nanoTime from before the request) it did.
     */
    private fun settled(request: ResponseEntity<String>, askedAt: Long = System.nanoTime()): Pair<ResponseEntity<String>, Long> {
        val requestId = request.json().path("requestId").asText()
        val start = askedAt
        val deadline = start + Duration.ofSeconds(15).toNanos()
        while (System.nanoTime() < deadline) {
            val r = delivery(requestId)
            check(r.statusCode.value() == 200) { "delivery status ${r.statusCode}: ${r.body}" }
            if (r.json().path("status").asText() != "sending") {
                return r to Duration.ofNanos(System.nanoTime() - start).toMillis()
            }
            Thread.sleep(20)
        }
        throw AssertionError("the delivery status for $requestId never settled")
    }

    /** The status says sent this long after the request: the send timeout below plus the margin. */
    private val settleAt = SEND_TIMEOUT.plus(OtpService.SETTLE_MARGIN).toMillis()

    /** A wrong code against what [email]'s request left, as a stranger would probe it. */
    private fun wrongCodeProbe(email: String) = verifyCode(email, "000000")

    /**
     * Whatever the worker does with the email — sends it, has it refused, finds
     * the provider down, out of credit or timing out, drops it, or has not got
     * to it yet — a listed and an unlisted address see the same answer, the
     * same status settling to `sent` at the same moment, the same resend
     * refusal and the same wrong-code answer. The status cannot follow the
     * worker: only a listed address could ever fail.
     */
    @Test
    fun `listed and unlisted addresses see the same answer, status, timing, resend and wrong code, whatever the worker did`() {
        val states = listOf("not yet decided", "healthy", "UNAVAILABLE", "INSUFFICIENT_BALANCE", "TIMEOUT", "refusing the listed address")
        for ((i, state) in states.withIndex()) {
            sender.faults.clear()
            sender.refuses.clear()
            val insider = "same.$i.$run@example.test".also { extraListed(it) }
            val outsider = "same-outsider.$i.$run@example.test"
            SandboxFault.entries.firstOrNull { it.name == state }?.let { sender.faults.always("email", it) }
            if (state.startsWith("refusing")) sender.refuses += insider

            val outsiderAsked = System.nanoTime()
            val outsiderRequest = requestCode(outsider)
            val insiderAsked = System.nanoTime()
            val insiderRequest = requestCode(insider)
            if (state != "not yet decided") outbox.drain()
            val (outsiderStatus, outsiderMillis) = settled(outsiderRequest, outsiderAsked)
            val (insiderStatus, insiderMillis) = settled(insiderRequest, insiderAsked)

            assertThat(shape(insiderRequest)).describedAs(state).isEqualTo(shape(outsiderRequest))
            assertThat(shape(insiderStatus)).describedAs(state).isEqualTo(shape(outsiderStatus))
            assertThat(insiderStatus.json().path("status").asText()).describedAs(state).isEqualTo("sent")
            assertThat(listOf(insiderMillis, outsiderMillis)).describedAs("$state: settled after ms")
                .allSatisfy { assertThat(it).isBetween(settleAt - 20, settleAt + 900) }

            val again = listOf(insider, outsider).map(::requestCode)
            assertThat(again[0].statusCode.value()).describedAs(state).isEqualTo(429)
            assertThat(shape(again[0])).describedAs("$state: asking again").isEqualTo(shape(again[1]))
            assertThat(shape(wrongCodeProbe(insider))).describedAs("$state: a wrong code").isEqualTo(shape(wrongCodeProbe(outsider)))
            outbox.drain()
        }
        assertThat(sender.sent.map { it.first }).noneMatch { it.startsWith("same-outsider") }
        assertThat(sender.sent.count { it.first.startsWith("same.") }).describedAs("one attempt per listed request").isEqualTo(6)
    }

    /**
     * Signal 1 (docs/13 §5), over HTTP and asked again and again: the provider
     * refuses one listed address and accepts everything else. Nothing a
     * stranger can ask for — the answer, the delivery status, when it settles,
     * the resend, a wrong code — may differ from an address that is not listed.
     */
    @Test
    fun `a provider refusing a listed address answers exactly as for an unlisted one, however many times it is probed`() {
        val insider = listed[3]
        val outsider = "refused-outsider-$run@example.test"
        sender.refuses += insider
        repeat(3) { round ->
            val label = "probe ${round + 1}"
            redis.delete(listOf("otp:email:cooldown:login:$insider", "otp:email:cooldown:login:$outsider"))
            val (outsiderAsked, outsiderRequest) = System.nanoTime() to requestCode(outsider)
            val (insiderAsked, insiderRequest) = System.nanoTime() to requestCode(insider)
            outbox.drain()
            val (outsiderStatus, outsiderMillis) = settled(outsiderRequest, outsiderAsked)
            val (insiderStatus, insiderMillis) = settled(insiderRequest, insiderAsked)
            assertThat(shape(insiderRequest)).describedAs(label).isEqualTo(shape(outsiderRequest))
            assertThat(shape(insiderStatus)).describedAs(label).isEqualTo(shape(outsiderStatus))
            assertThat(insiderStatus.json().path("status").asText()).describedAs(label).isEqualTo("sent")
            assertThat(listOf(insiderMillis, outsiderMillis)).describedAs("$label: settled after ms")
                .allSatisfy { assertThat(it).isBetween(settleAt - 20, settleAt + 900) }
            val again = listOf(insider, outsider).map(::requestCode)
            assertThat(again[0].statusCode.value()).describedAs(label).isEqualTo(429)
            assertThat(shape(again[0])).describedAs("$label: asking again").isEqualTo(shape(again[1]))
            assertThat(shape(wrongCodeProbe(insider))).describedAs("$label: a wrong code").isEqualTo(shape(wrongCodeProbe(outsider)))
        }
        assertThat(sender.sent.count { it.first == insider }).describedAs("the listed address really was tried each time").isEqualTo(3)
        assertThat(sender.sent.map { it.first }).doesNotContain(outsider)
        assertThat(db.queryForList("select status, failure from sign_in_code_emails where status = 'failed' and failure = 'rejected'"))
            .describedAs("the operator's record of the refusals").hasSizeGreaterThanOrEqualTo(3)
    }

    /** The line under the code step names the same channel for everyone, before anyone is signed in. */
    @Test
    fun `the contact line's channel can be read before signing in, the same for every address`() {
        val r = get("/api/v1/auth/otp/contact")
        assertThat(r.statusCode.value()).describedAs(r.body).isEqualTo(200)
        assertThat(r.json().path("configured").asBoolean()).isFalse()
        assertThat(r.body).isEqualTo(get("/api/v1/auth/otp/contact").body)
    }

    @Test
    fun `an unknown request id is not found, and the status says nothing about any address`() {
        val r = delivery(java.util.UUID.randomUUID().toString())
        assertThat(r.statusCode.value()).isEqualTo(404)
        assertThat(r.errorCode()).isEqualTo("otp_request_unknown")
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
        private val extra = (0..5).map { "same.$it.$run@example.test" }

        /** Only a check that a test uses an address the allowlist below really has. */
        fun extraListed(address: String) = check(address in extra) { "$address is not on the test allowlist" }

        /** Short, so every status here says sent two seconds after its request rather than six. */
        private val SEND_TIMEOUT: Duration = Duration.ofSeconds(1)

        /** RFC 9110 §7.6.1: per-connection, set by the server whoever asks. */
        private val HOP_BY_HOP = setOf("connection", "keep-alive")

        @JvmStatic
        @DynamicPropertySource
        fun emailSignIn(registry: DynamicPropertyRegistry) {
            registry.add("almira.auth.sign-in-channels") { "phone,email" }
            registry.add("almira.otp.send-timeout") { "${SEND_TIMEOUT.toMillis()}ms" }
            // Every other response closes its connection, so a comparison that counted
            // the Connection header fails every run rather than one run in several.
            registry.add("server.tomcat.max-keep-alive-requests") { "2" }
            // Typed untidily on purpose: the list is normalised as sign-in is.
            registry.add("almira.auth.email-allowlist") { (listed + extra).joinToString(" , ") { it.uppercase() } }
        }
    }
}
