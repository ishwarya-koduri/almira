package tech.bhrigu.almira.auth

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.config.AlmiraProperties
import tech.bhrigu.almira.provider.ProviderCalls
import tech.bhrigu.almira.provider.SandboxFault
import tech.bhrigu.almira.provider.SandboxFaults
import tech.bhrigu.almira.provider.Sleeper
import java.time.Clock
import java.util.function.DoubleSupplier
import tech.bhrigu.almira.support.TestInfra
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The OTP rules, against a real Redis — the atomicity claims are claims about
 * Redis, and a mock would agree with whatever the code assumed.
 */
@DisplayName("One-time codes")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OtpServiceTest {

    private val factory = LettuceConnectionFactory(TestInfra.redisHost, TestInfra.redisPort)
        .apply { afterPropertiesSet() }
    private val redis = StringRedisTemplate(factory).apply { afterPropertiesSet() }

    @AfterAll
    fun close() = factory.destroy()

    /** Remembers codes instead of delivering them. Never echoes. */
    class RecordingSender(
        override val available: Boolean = true,
        override val exposesCodeForDevelopment: Boolean = false,
    ) : OtpSender {
        val sent = CopyOnWriteArrayList<Pair<String, String>>()
        override fun send(phone: String, code: String) { sent += phone to code }
        fun lastCode() = sent.last().second
    }

    private fun props(
        environment: String = "development",
        otp: AlmiraProperties.Otp = AlmiraProperties.Otp(maxPerHour = 1_000, maxPerIpPerHour = 1_000),
        secret: String = "test-only-secret-that-is-long-enough-for-hmac256-signing",
    ) = AlmiraProperties(
        db = AlmiraProperties.Db("jdbc:postgresql://x/y", "u", "p", "u2", "p2"),
        jwt = AlmiraProperties.Jwt(secret),
        otp = otp,
        environment = environment,
    )

    private fun phone() = "+9190" + Random.nextLong(10_000_000, 99_999_999)
    private fun ip() = "198.51.100.${Random.nextInt(1, 255)}-${Random.nextLong()}"

    private fun refusal(block: () -> Unit): ApiException =
        runCatching(block).exceptionOrNull() as? ApiException
            ?: throw AssertionError("expected an ApiException")

    private fun keysFor(phone: String) = redis.keys("otp:*$phone*")

    // --- the echo, and the sender that cannot deliver ------------------------

    @Test
    fun `outside development the log sender generates nothing, stores nothing and says so`() {
        for (environment in listOf("production", "", "staging")) {
            val service = OtpService(redis, LoggingOtpSender(props(environment)), props(environment))
            val number = phone()
            val e = refusal { service.request(number, ip()) }
            assertThat(e.status).describedAs(environment).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            assertThat(e.code).isEqualTo("otp_unavailable")
            assertThat(keysFor(number)).describedAs("nothing stored for '$environment'").isEmpty()
        }
    }

    @Test
    fun `the code is echoed only when development was explicitly chosen`() {
        val dev = OtpService(redis, LoggingOtpSender(props("development")), props("development"))
        assertThat(dev.request(phone(), ip()).developmentCode).matches("[0-9]{6}")

        // A sender that claims it may echo, in a real environment: the
        // environment decides, not the sender.
        for (environment in listOf("production", "")) {
            val sender = RecordingSender(exposesCodeForDevelopment = true)
            val c = OtpService(redis, sender, props(environment)).request(phone(), ip())
            assertThat(c.developmentCode).describedAs(environment).isNull()
            assertThat(sender.sent).hasSize(1)
        }
    }

    @Test
    fun `the challenge's own toString never prints the code`() {
        val c = OtpService(redis, LoggingOtpSender(props()), props()).request(phone(), ip())
        assertThat(c.toString()).doesNotContain(c.developmentCode!!)
    }

    // --- what is stored -------------------------------------------------------

    @Test
    fun `a Redis dump does not give the code back`() {
        val sender = RecordingSender()
        val number = phone()
        OtpService(redis, sender, props()).request(number, ip())
        val code = sender.lastCode()
        val stored = redis.opsForHash<String, String>().entries("otp:challenge:login:$number")

        val sha256 = MessageDigest.getInstance("SHA-256").digest(code.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertThat(stored["hash"]).isNotEqualTo(sha256)
        assertThat(stored.values.joinToString()).doesNotContain(code)

        // And the value is keyed: the same code under another secret stores
        // something else, so a million-entry table built without the secret
        // matches nothing.
        val other = RecordingSender()
        val otherNumber = phone()
        OtpService(redis, other, props(secret = "a-different-secret-that-is-also-long-enough-for-hmac"))
            .request(otherNumber, ip())
        val otherStored = redis.opsForHash<String, String>().entries("otp:challenge:login:$otherNumber")
        assertThat(otherStored["hash"]).isNotEqualTo(stored["hash"])
    }

    @Test
    fun `a code for one number or flow does not verify another`() {
        val sender = RecordingSender()
        val service = OtpService(redis, sender, props())
        val a = phone(); val b = phone()
        service.request(a, ip()); val codeA = sender.lastCode()
        service.request(b, ip())
        // Copy A's stored challenge over B's, as someone with Redis write access might.
        val stolen = redis.opsForHash<String, String>().entries("otp:challenge:login:$a")
        redis.opsForHash<String, String>().putAll("otp:challenge:login:$b", stolen)
        assertThat(refusal { service.verify(b, codeA, null) }.code).isEqualTo("otp_invalid")

        service.request(a, ip(), OtpService.STEP_UP)
        val stepUpStolen = redis.opsForHash<String, String>().entries("otp:challenge:step_up:$a")
        redis.opsForHash<String, String>().putAll("otp:challenge:login:$a", stepUpStolen)
        assertThat(refusal { service.verify(a, sender.lastCode(), null) }.code).isEqualTo("otp_invalid")
    }

    // --- single use -----------------------------------------------------------

    @Test
    fun `a code works once`() {
        val sender = RecordingSender()
        val service = OtpService(redis, sender, props())
        val number = phone()
        service.request(number, ip())
        service.verify(number, sender.lastCode(), null)
        assertThat(refusal { service.verify(number, sender.lastCode(), null) }.code)
            .isEqualTo("otp_expired")
    }

    @Test
    fun `a code works once even when two correct attempts race`() {
        val sender = RecordingSender()
        val service = OtpService(redis, sender, props())
        val pool = Executors.newFixedThreadPool(8)
        try {
            repeat(40) {
                val number = phone()
                service.request(number, ip())
                val code = sender.lastCode()
                val start = CountDownLatch(1)
                val outcomes = (1..8).map {
                    pool.submit<Boolean> { start.await(); runCatching { service.verify(number, code, null) }.isSuccess }
                }
                start.countDown()
                assertThat(outcomes.count { it.get(10, TimeUnit.SECONDS) }).isEqualTo(1)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    // --- guessing -------------------------------------------------------------

    @Test
    fun `wrong guesses lock the challenge, and the right code is refused afterwards`() {
        val sender = RecordingSender()
        val service = OtpService(redis, sender, props())
        val number = phone()
        service.request(number, ip())
        val code = sender.lastCode()
        val wrong = if (code == "000000") "111111" else "000000"
        val codes = (1..5).map { refusal { service.verify(number, wrong, null) } }
        assertThat(codes.map { it.code }).containsExactly(
            "otp_invalid", "otp_invalid", "otp_invalid", "otp_invalid", "otp_locked",
        )
        assertThat(codes[0].details["attemptsRemaining"]).isEqualTo(4)
        assertThat(refusal { service.verify(number, code, null) }.code).isEqualTo("otp_expired")
    }

    @Test
    fun `racing wrong guesses cannot buy more than the allowed attempts`() {
        val sender = RecordingSender()
        val service = OtpService(redis, sender, props())
        val pool = Executors.newFixedThreadPool(16)
        try {
            val number = phone()
            service.request(number, ip())
            val code = sender.lastCode()
            val wrong = (0 until 60).map { "%06d".format(it) }.filter { it != code }.take(48)
            val start = CountDownLatch(1)
            val results = wrong.map { guess ->
                pool.submit<String> { start.await(); refusal { service.verify(number, guess, null) }.code }
            }
            start.countDown()
            val codes = results.map { it.get(10, TimeUnit.SECONDS) }
            // Without the atomic count, every racer read attempts=0 and was told
            // it had four tries left — 48 guesses judged against one count.
            assertThat(codes.count { it == "otp_invalid" }).isLessThanOrEqualTo(4)
            assertThat(codes.count { it == "otp_locked" }).isEqualTo(1)
            assertThat(refusal { service.verify(number, code, null) }.code).isEqualTo("otp_expired")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a stale request id is refused without burning an attempt`() {
        val sender = RecordingSender()
        val service = OtpService(redis, sender, props())
        val number = phone()
        val c = service.request(number, ip())
        assertThat(refusal { service.verify(number, sender.lastCode(), "not-" + c.requestId) }.code)
            .isEqualTo("otp_stale")
        assertThat(redis.opsForHash<String, String>().get("otp:challenge:login:$number", "attempts"))
            .isEqualTo("0")
        service.verify(number, sender.lastCode(), c.requestId)
    }

    // --- time -----------------------------------------------------------------

    @Test
    fun `a code expires`() {
        val sender = RecordingSender()
        val service = OtpService(
            redis, sender,
            props(otp = AlmiraProperties.Otp(ttl = Duration.ofSeconds(1), resendCooldown = Duration.ZERO,
                maxPerHour = 1_000, maxPerIpPerHour = 1_000)),
        )
        val number = phone()
        service.request(number, ip())
        Thread.sleep(1_600)
        assertThat(refusal { service.verify(number, sender.lastCode(), null) }.code).isEqualTo("otp_expired")
    }

    @Test
    fun `the default lifetime is five minutes and Redis holds it to that`() {
        val service = OtpService(redis, RecordingSender(), props())
        val number = phone()
        assertThat(service.request(number, ip()).expiresInSeconds).isEqualTo(300)
        assertThat(redis.getExpire("otp:challenge:login:$number", TimeUnit.SECONDS)).isBetween(290L, 300L)
    }

    @Test
    fun `configuration cannot make a code guessable`() {
        fun otp(block: AlmiraProperties.Otp.() -> AlmiraProperties.Otp) =
            props(otp = AlmiraProperties.Otp().block())
        listOf(
            otp { copy(length = 4) } to "length",
            otp { copy(ttl = Duration.ofHours(1)) } to "ttl",
            otp { copy(ttl = Duration.ZERO) } to "ttl",
            otp { copy(maxAttempts = 0) } to "max-attempts",
            otp { copy(maxAttempts = 100) } to "max-attempts",
        ).forEach { (p, name) ->
            assertThatThrownBy { OtpService(redis, RecordingSender(), p) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("almira.otp.$name")
        }
    }

    // --- rate limits ----------------------------------------------------------

    @Test
    fun `a resend inside the cooldown is refused, and the other flow is not`() {
        val service = OtpService(redis, RecordingSender(), props())
        val number = phone()
        service.request(number, ip())
        val e = refusal { service.request(number, ip()) }
        assertThat(e.status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(e.details["retryAfterSeconds"] as Long).isBetween(1L, 30L)
        service.request(number, ip(), OtpService.STEP_UP)
    }

    @Test
    fun `one number cannot be sent more than its hourly allowance, from any network`() {
        val sender = RecordingSender()
        val service = OtpService(
            redis, sender,
            props(otp = AlmiraProperties.Otp(resendCooldown = Duration.ZERO, maxPerHour = 3, maxPerIpPerHour = 1_000)),
        )
        val number = phone()
        repeat(3) { service.request(number, ip()) }
        val e = refusal { service.request(number, ip()) }
        assertThat(e.status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(e.message).contains("this phone")
        assertThat(sender.sent).hasSize(3)
    }

    @Test
    fun `one network cannot request more than its hourly allowance, across numbers`() {
        val sender = RecordingSender()
        val service = OtpService(
            redis, sender,
            props(otp = AlmiraProperties.Otp(resendCooldown = Duration.ZERO, maxPerHour = 1_000, maxPerIpPerHour = 3)),
        )
        val address = ip()
        repeat(3) { service.request(phone(), address) }
        val e = refusal { service.request(phone(), address) }
        assertThat(e.status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(e.message).contains("this network")
        service.request(phone(), ip())
        assertThat(sender.sent).hasSize(4)
    }

    @Test
    fun `one network cannot keep guessing across many numbers`() {
        val sender = RecordingSender()
        val service = OtpService(
            redis, sender,
            props(otp = AlmiraProperties.Otp(maxPerHour = 1_000, maxPerIpPerHour = 1_000, maxVerifyFailuresPerIpPerHour = 3)),
        )
        val attacker = ip()

        // Successes from the network do not count toward it.
        repeat(5) {
            val n = phone(); service.request(n, ip()); service.verify(n, sender.lastCode(), null, ip = attacker)
        }

        // Three wrong codes, each at a different person's live challenge.
        repeat(3) {
            val n = phone(); service.request(n, ip())
            val wrong = if (sender.lastCode() == "000000") "111111" else "000000"
            assertThat(refusal { service.verify(n, wrong, null, ip = attacker) }.code).isEqualTo("otp_invalid")
        }

        // The fourth number: refused even with the right code, and its
        // challenge is not touched, so its owner can still use it.
        val victim = phone(); service.request(victim, ip())
        val e = refusal { service.verify(victim, sender.lastCode(), null, ip = attacker) }
        assertThat(e.status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(e.message).contains("this network")
        assertThat(redis.opsForHash<String, String>().get("otp:challenge:login:$victim", "attempts")).isEqualTo("0")
        service.verify(victim, sender.lastCode(), null, ip = ip())
    }

    // --- when the send fails --------------------------------------------------

    /**
     * Records the code as a provider that received it would, then fails the way
     * the sandbox faults say — so "it timed out but the text arrived" is a case
     * a test can hold the code for.
     */
    class FaultySender(val faults: SandboxFaults = SandboxFaults()) : OtpSender {
        val sent = CopyOnWriteArrayList<Pair<String, String>>()
        override fun send(phone: String, code: String) {
            sent += phone to code
            faults.apply("otp")
        }
        fun lastCode() = sent.last().second
    }

    private fun failingService(
        fault: SandboxFault,
        otp: AlmiraProperties.Otp = AlmiraProperties.Otp(maxPerHour = 1_000, maxPerIpPerHour = 1_000),
    ): Pair<OtpService, FaultySender> {
        val sender = FaultySender().apply { faults.always("otp", fault) }
        val props = props(otp = otp)
        val calls = ProviderCalls(props, Sleeper { }, DoubleSupplier { 1.0 }, Clock.systemUTC())
        return OtpService(redis, sender, props, calls) to sender
    }

    @Test
    fun `a timed-out send keeps the challenge and the cooldown, so a late text still signs in`() {
        val (service, sender) = failingService(SandboxFault.TIMEOUT)
        val number = phone()
        val e = refusal { service.request(number, ip()) }
        assertThat(e.status).isEqualTo(HttpStatus.GATEWAY_TIMEOUT)
        assertThat(e.code).isEqualTo("otp_delivery_delayed")
        assertThat(sender.sent).describedAs("retried: three attempts in total").hasSize(3)
        assertThat(e.message + e.details).doesNotContain(sender.lastCode())

        assertThat(refusal { service.request(number, ip()) }.status)
            .describedAs("it may have been sent, so the cooldown stands")
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        service.verify(number, sender.lastCode(), e.details["requestId"] as String)
    }

    @Test
    fun `a failed delivery, an unavailable provider and an empty balance leave nothing live and no cooldown`() {
        val expected = mapOf(
            SandboxFault.REJECTED to (HttpStatus.UNPROCESSABLE_ENTITY to "otp_delivery_failed"),
            SandboxFault.UNAVAILABLE to (HttpStatus.SERVICE_UNAVAILABLE to "otp_provider_unavailable"),
            SandboxFault.INSUFFICIENT_BALANCE to (HttpStatus.SERVICE_UNAVAILABLE to "otp_service_unavailable"),
        )
        for ((fault, outcome) in expected) {
            val (service, sender) = failingService(fault)
            val number = phone()
            val e = refusal { service.request(number, ip()) }
            assertThat(e.status).describedAs(fault.name).isEqualTo(outcome.first)
            assertThat(e.code).describedAs(fault.name).isEqualTo(outcome.second)
            assertThat(sender.sent).describedAs("attempts for $fault")
                .hasSize(if (fault == SandboxFault.UNAVAILABLE) 3 else 1)
            assertThat(e.message + e.details).doesNotContain(sender.lastCode())
            assertThat(redis.hasKey("otp:challenge:login:$number")).describedAs(fault.name).isFalse()
            assertThat(redis.hasKey("otp:cooldown:login:$number")).describedAs(fault.name).isFalse()

            // The code that was generated for the failed send is worthless.
            sender.faults.clear()
            val stale = sender.lastCode()
            service.request(number, ip())
            if (stale != sender.lastCode()) {
                assertThat(refusal { service.verify(number, stale, null) }.code).isEqualTo("otp_invalid")
            }
        }
    }

    @Test
    fun `our failure does not use up a person's hourly allowance`() {
        val (service, sender) = failingService(
            SandboxFault.INSUFFICIENT_BALANCE,
            AlmiraProperties.Otp(maxPerHour = 2, maxPerIpPerHour = 1_000),
        )
        val number = phone()
        repeat(4) {
            assertThat(refusal { service.request(number, ip()) }.code).isEqualTo("otp_service_unavailable")
        }
        sender.faults.clear() // topped up
        service.request(number, ip())
        service.verify(number, sender.lastCode(), null)
    }

    @Test
    fun `but a network does not get free requests out of it`() {
        val (service, _) = failingService(
            SandboxFault.REJECTED,
            AlmiraProperties.Otp(maxPerHour = 1_000, maxPerIpPerHour = 3),
        )
        val address = ip()
        repeat(3) { assertThat(refusal { service.request(phone(), address) }.code).isEqualTo("otp_delivery_failed") }
        val e = refusal { service.request(phone(), address) }
        assertThat(e.status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(e.message).contains("this network")
    }
}
