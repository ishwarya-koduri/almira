package tech.bhrigu.almira.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.config.AlmiraProperties
import tech.bhrigu.almira.provider.ChannelSender
import tech.bhrigu.almira.provider.ProviderCalls
import tech.bhrigu.almira.provider.ProviderMode
import tech.bhrigu.almira.provider.SandboxEmailSender
import tech.bhrigu.almira.provider.SandboxFault
import tech.bhrigu.almira.provider.SandboxFaults
import tech.bhrigu.almira.provider.SandboxSmsSender
import tech.bhrigu.almira.provider.Sleeper
import tech.bhrigu.almira.reminder.OutboundNotification
import tech.bhrigu.almira.support.TestInfra
import java.time.Clock
import java.util.HexFormat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.function.DoubleSupplier
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * One-time codes by email, against a real Redis, as OtpServiceTest does for
 * phone. What is different about email is tested here: its own key and HMAC
 * namespace, the decoy an address off the allowlist gets, the send that
 * happens after the answer, and the sender that refuses outside development.
 */
@DisplayName("One-time codes by email")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmailOtpTest {

    private val factory = LettuceConnectionFactory(TestInfra.redisHost, TestInfra.redisPort)
        .apply { afterPropertiesSet() }
    private val redis = StringRedisTemplate(factory).apply { afterPropertiesSet() }

    @AfterAll
    fun close() = factory.destroy()

    private val secret = "test-only-secret-that-is-long-enough-for-hmac256-signing"

    /** Remembers codes instead of delivering them, and fails as the faults say. */
    class RecordingEmailSender(
        override val available: Boolean = true,
        override val exposesCodeForDevelopment: Boolean = false,
        val faults: SandboxFaults = SandboxFaults(),
    ) : EmailOtpSender {
        val sent = CopyOnWriteArrayList<Pair<String, String>>()
        @Volatile var gate: CountDownLatch? = null
        override fun send(email: String, code: String) {
            gate?.await(10, TimeUnit.SECONDS)
            sent += email to code
            faults.apply("email")
        }
        fun lastCode() = sent.last().second
    }

    private fun props(
        environment: String = "development",
        otp: AlmiraProperties.Otp = AlmiraProperties.Otp(maxPerHour = 1_000, maxPerIpPerHour = 1_000),
    ) = AlmiraProperties(
        db = AlmiraProperties.Db("jdbc:postgresql://x/y", "u", "p", "u2", "p2"),
        jwt = AlmiraProperties.Jwt(secret),
        otp = otp,
        environment = environment,
    )

    private val inline = Executor { it.run() }

    private fun service(
        email: EmailOtpSender = RecordingEmailSender(),
        props: AlmiraProperties = props(),
        background: Executor = inline,
        phone: OtpSender = OtpServiceTest.RecordingSender(),
    ) = OtpService(
        redis, phone, props,
        ProviderCalls(props, Sleeper { }, DoubleSupplier { 1.0 }, Clock.systemUTC()),
        email, background,
    )

    private fun address() = "tester.${Random.nextLong(1, Long.MAX_VALUE)}+alpha@example.test"
    private fun ip() = "198.51.100.${Random.nextInt(1, 255)}-${Random.nextLong()}"

    private fun refusal(block: () -> Unit): ApiException =
        runCatching(block).exceptionOrNull() as? ApiException
            ?: throw AssertionError("expected an ApiException")

    private fun keysFor(email: String) = redis.keys("otp:*$email*")
    private fun challenge(email: String, purpose: String = OtpService.LOGIN) =
        redis.opsForHash<String, String>().entries("otp:email:challenge:$purpose:$email")

    private val login = OtpService.LOGIN
    private val unreported = OtpDelivery.UNREPORTED

    // --- the sender ----------------------------------------------------------

    @Test
    fun `the sandbox email sender can deliver only in development, and then echoes`() {
        val sandbox = SandboxEmailSender(SandboxFaults())
        val dev = ChannelEmailOtpSender(props("development"), listOf(SandboxSmsSender(SandboxFaults()), sandbox))
        assertThat(dev.available).isTrue()
        assertThat(dev.exposesCodeForDevelopment).isTrue()

        for (environment in listOf("production", "", "staging")) {
            val sender = ChannelEmailOtpSender(props(environment), listOf(sandbox))
            assertThat(sender.available).describedAs(environment).isFalse()
            assertThat(sender.exposesCodeForDevelopment).describedAs(environment).isFalse()

            // And so the request is refused before a code or a key exists.
            val email = address()
            val e = refusal { service(email = sender, props = props(environment)).requestByEmail(email, ip(), login, unreported) }
            assertThat(e.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            assertThat(e.code).isEqualTo("otp_unavailable")
            assertThat(e.message).contains("email")
            assertThat(keysFor(email)).describedAs("nothing stored for '$environment'").isEmpty()
        }

        // mode: disabled leaves no email ChannelSender at all.
        val off = ChannelEmailOtpSender(props("development"), listOf(SandboxSmsSender(SandboxFaults())))
        assertThat(off.available).isFalse()
    }

    @Test
    fun `a live email channel can deliver in production, and never echoes`() {
        val live = object : ChannelSender {
            override val channel = "email"
            override val mode = ProviderMode.LIVE
            override fun send(notification: OutboundNotification, recipientHint: String?) = "live"
        }
        val sender = ChannelEmailOtpSender(props("production"), listOf(live))
        assertThat(sender.available).isTrue()
        assertThat(sender.exposesCodeForDevelopment).isFalse()
    }

    @Test
    fun `the code goes in the body, the address in the hint, and neither in the subject`() {
        val seen = CopyOnWriteArrayList<Pair<OutboundNotification, String?>>()
        val capture = object : ChannelSender {
            override val channel = "email"
            override val mode = ProviderMode.SANDBOX
            override fun send(notification: OutboundNotification, recipientHint: String?): String {
                seen += notification to recipientHint
                return "capture"
            }
        }
        ChannelEmailOtpSender(props("development"), listOf(capture)).send("asha@example.test", "48213907")
        val (message, hint) = seen.single()
        assertThat(hint).isEqualTo("asha@example.test")
        assertThat(message.body).contains("48213907")
        assertThat(message.title).doesNotContain("48213907")
        assertThat(message.template).doesNotContain("48213907")
    }

    // --- what is stored --------------------------------------------------------

    @Test
    fun `an email code has its own key, and cannot be moved onto a phone challenge or back`() {
        val email = RecordingEmailSender()
        val phone = OtpServiceTest.RecordingSender()
        val otp = service(email = email, phone = phone)
        // The same string as both an address and a "number", so only the HMAC
        // key can tell the two apart.
        val shared = address()

        otp.requestByEmail(shared, ip(), login, unreported)
        val emailCode = email.lastCode()
        val emailStored = challenge(shared)
        assertThat(emailStored.values.joinToString()).doesNotContain(emailCode)
        redis.opsForHash<String, String>().putAll("otp:challenge:login:$shared", emailStored)
        assertThat(refusal { otp.verify(shared, emailCode, null) }.code).isEqualTo("otp_invalid")

        val other = "phone.${Random.nextLong()}@example.test"
        otp.request(other, ip())
        redis.opsForHash<String, String>().putAll(
            "otp:email:challenge:login:$other",
            redis.opsForHash<String, String>().entries("otp:challenge:login:$other"),
        )
        assertThat(refusal { otp.verifyByEmail(other, phone.lastCode(), null, login) }.code).isEqualTo("otp_invalid")
    }

    @Test
    fun `a code works once and wrong guesses lock it, as for phone`() {
        val email = RecordingEmailSender()
        val otp = service(email = email)
        val a = address()
        otp.requestByEmail(a, ip(), login, unreported)
        otp.verifyByEmail(a, email.lastCode(), null, login)
        assertThat(refusal { otp.verifyByEmail(a, email.lastCode(), null, login) }.code).isEqualTo("otp_expired")

        val b = address()
        otp.requestByEmail(b, ip(), login, unreported)
        val wrong = if (email.lastCode() == "000000") "111111" else "000000"
        assertThat((1..5).map { refusal { otp.verifyByEmail(b, wrong, null, login) }.code })
            .containsExactly("otp_invalid", "otp_invalid", "otp_invalid", "otp_invalid", "otp_locked")
        assertThat(refusal { otp.verifyByEmail(b, email.lastCode(), null, login) }.code).isEqualTo("otp_expired")
    }

    // --- the decoy ---------------------------------------------------------------

    /**
     * The stored value must match NO code, not merely not the one generated —
     * so this tries every one of the million six-digit codes under the real
     * derived key, the way someone who guessed the scheme would.
     */
    @Test
    fun `a decoy stores a challenge that no code can ever complete, and sends nothing`() {
        val email = RecordingEmailSender(exposesCodeForDevelopment = true)
        val otp = service(email = email)
        val decoy = address()

        val c = otp.requestByEmail(decoy, ip(), login, OtpDelivery.DECOY)
        assertThat(email.sent).isEmpty()
        assertThat(c.developmentCode).describedAs("not even in development").isNull()
        assertThat(c.channel).isEqualTo(OtpChannel.EMAIL)
        val stored = challenge(decoy)
        assertThat(stored.keys).containsExactlyInAnyOrder("hash", "requestId", "attempts")
        assertThat(stored["requestId"]).isEqualTo(c.requestId)
        assertThat(redis.getExpire("otp:email:challenge:login:$decoy", TimeUnit.SECONDS)).isBetween(290L, 300L)
        assertThat(redis.hasKey("otp:email:cooldown:login:$decoy")).isTrue()

        val derived = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
            SecretKeySpec(doFinal("almira/otp-code/email/v1".toByteArray()), "HmacSHA256")
        }
        val mac = Mac.getInstance("HmacSHA256").apply { init(derived) }
        val target = HexFormat.of().parseHex(stored["hash"])
        val prefix = "login|$decoy|".toByteArray()
        val matching = (0 until 1_000_000).firstOrNull { n ->
            mac.update(prefix)
            mac.doFinal("%06d".format(n).toByteArray()).contentEquals(target)
        }
        assertThat(matching).describedAs("a six-digit code that completes the decoy").isNull()

        // The harness can find a real one, so "found none" means something.
        otp.requestByEmail(decoy + ".real", ip(), login, unreported)
        val realTarget = HexFormat.of().parseHex(challenge(decoy + ".real")["hash"])
        mac.update("login|$decoy.real|".toByteArray())
        assertThat(mac.doFinal(email.lastCode().toByteArray()).contentEquals(realTarget)).isTrue()
    }

    // --- sending after the answer -------------------------------------------------

    @Test
    fun `the answer does not wait for the send`() {
        val email = RecordingEmailSender().apply { gate = CountDownLatch(1) }
        val otp = service(email = email, background = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
        val a = address()
        try {
            val c = otp.requestByEmail(a, ip(), login, unreported)
            assertThat(c.requestId).isNotBlank()
            assertThat(email.sent).describedAs("answered while the send was still blocked").isEmpty()
        } finally {
            email.gate!!.countDown()
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (email.sent.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
        otp.verifyByEmail(a, email.lastCode(), null, login)
    }

    @Test
    fun `a failed unreported send leaves the challenge unusable but in place, and everything else as it was`() {
        for (fault in listOf(SandboxFault.REJECTED, SandboxFault.UNAVAILABLE, SandboxFault.INSUFFICIENT_BALANCE)) {
            val email = RecordingEmailSender().apply { faults.always("email", fault) }
            val otp = service(email = email)
            val a = address()
            val c = otp.requestByEmail(a, ip(), login, unreported)
            assertThat(c.requestId).isNotBlank()

            assertThat(challenge(a)["requestId"]).describedAs(fault.name).isEqualTo(c.requestId)
            assertThat(redis.hasKey("otp:email:cooldown:login:$a")).describedAs(fault.name).isTrue()
            assertThat(redis.opsForValue().get("otp:rate:email:$a")).describedAs(fault.name).isEqualTo("1")
            // A live code nobody received is worthless, and now provably so.
            assertThat(refusal { otp.verifyByEmail(a, email.lastCode(), null, login) }.code)
                .describedAs(fault.name).isEqualTo("otp_invalid")
        }
    }

    @Test
    fun `a timed-out unreported send keeps its code, because it may still arrive`() {
        val email = RecordingEmailSender().apply { faults.always("email", SandboxFault.TIMEOUT) }
        val otp = service(email = email)
        val a = address()
        otp.requestByEmail(a, ip(), login, unreported)
        otp.verifyByEmail(a, email.lastCode(), null, login)
    }

    // --- reported (step-up) --------------------------------------------------------

    @Test
    fun `a reported email send fails the phone way, in email words`() {
        val expected = mapOf(
            SandboxFault.REJECTED to "otp_delivery_failed",
            SandboxFault.UNAVAILABLE to "otp_provider_unavailable",
            SandboxFault.INSUFFICIENT_BALANCE to "otp_service_unavailable",
        )
        for ((fault, code) in expected) {
            val email = RecordingEmailSender().apply { faults.always("email", fault) }
            val otp = service(email = email)
            val a = address()
            val e = refusal { otp.requestByEmail(a, ip(), OtpService.STEP_UP, OtpDelivery.REPORTED) }
            assertThat(e.code).describedAs(fault.name).isEqualTo(code)
            assertThat(e.message).describedAs(fault.name).doesNotContain("text message", "number")
            assertThat(e.message + e.details).doesNotContain(email.lastCode())
            assertThat(keysFor(a).filter { "rate" !in it }).describedAs(fault.name).isEmpty()
            assertThat(redis.opsForValue().get("otp:rate:email:$a")).describedAs(fault.name).isEqualTo("0")
        }
    }

    // --- limits ---------------------------------------------------------------------

    @Test
    fun `one address has its own hourly allowance`() {
        val email = RecordingEmailSender()
        val otp = service(
            email = email,
            props = props(otp = AlmiraProperties.Otp(resendCooldown = java.time.Duration.ZERO, maxPerHour = 2, maxPerIpPerHour = 1_000)),
        )
        val a = address()
        repeat(2) { otp.requestByEmail(a, ip(), login, OtpDelivery.DECOY) }
        val e = refusal { otp.requestByEmail(a, ip(), login, unreported) }
        assertThat(e.status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(e.message).contains("this email address")
        assertThat(email.sent).isEmpty()
    }

    @Test
    fun `a network's allowances are shared across channels, so alternating does not double them`() {
        val email = RecordingEmailSender()
        val phone = OtpServiceTest.RecordingSender()
        val otp = service(
            email = email,
            phone = phone,
            props = props(otp = AlmiraProperties.Otp(maxPerHour = 1_000, maxPerIpPerHour = 3, maxVerifyFailuresPerIpPerHour = 2)),
        )
        val network = ip()
        otp.request("+9198${Random.nextLong(10_000_000, 99_999_999)}", network)
        otp.requestByEmail(address(), network, login, unreported)
        otp.request("+9198${Random.nextLong(10_000_000, 99_999_999)}", network)
        assertThat(refusal { otp.requestByEmail(address(), network, login, unreported) }.message).contains("this network")

        // One wrong code by email and one by phone use up an allowance of two.
        val a = address(); otp.requestByEmail(a, ip(), login, unreported)
        assertThat(refusal { otp.verifyByEmail(a, wrongFor(email.lastCode()), null, login, network) }.code).isEqualTo("otp_invalid")
        val p = "+9197${Random.nextLong(10_000_000, 99_999_999)}"; otp.request(p, ip())
        assertThat(refusal { otp.verify(p, wrongFor(phone.lastCode()), null, ip = network) }.code).isEqualTo("otp_invalid")
        val b = address(); otp.requestByEmail(b, ip(), login, unreported)
        val e = refusal { otp.verifyByEmail(b, email.lastCode(), null, login, network) }
        assertThat(e.status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
    }

    private fun wrongFor(code: String) = if (code == "000000") "111111" else "000000"
}
