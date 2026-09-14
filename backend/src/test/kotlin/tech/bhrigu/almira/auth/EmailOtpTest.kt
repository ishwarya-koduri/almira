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
        // Its own provider weather, so one test's failing provider is not the next one's.
        "otp:email:provider-weather:test:${Random.nextLong()}",
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
    private val unreported = OtpDelivery.DEFERRED

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
            override val honoursIdempotencyKey = true
            override fun send(notification: OutboundNotification, recipientHint: String?, idempotencyKey: String) = "live"
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
            override val honoursIdempotencyKey = true
            override fun send(notification: OutboundNotification, recipientHint: String?, idempotencyKey: String): String {
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

    // --- sending after the answer, and saying how it went ---------------------------

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
    fun `a deferred send that fails says so, removes the code, opens resend and gives the address its request back`() {
        val expected = mapOf(
            SandboxFault.REJECTED to "otp_delivery_failed",
            SandboxFault.UNAVAILABLE to "otp_provider_unavailable",
            SandboxFault.INSUFFICIENT_BALANCE to "otp_service_unavailable",
        )
        for ((fault, code) in expected) {
            val email = RecordingEmailSender().apply { faults.always("email", fault) }
            val otp = service(email = email)
            val a = address()
            val network = ip()
            val c = otp.requestByEmail(a, network, login, unreported)

            val s = otp.emailDelivery(c.requestId)
            assertThat(s.status).describedAs(fault.name).isEqualTo("failed")
            assertThat(s.failure).describedAs(fault.name).isEqualTo(code)
            assertThat(s.message).describedAs(fault.name).startsWith("We couldn't send the code.")
            assertThat(s.message).describedAs(fault.name).doesNotContain(email.lastCode(), a)
            assertThat(s.resendAfterSeconds).describedAs(fault.name).isEqualTo(0L)

            assertThat(challenge(a)).describedAs(fault.name).isEmpty()
            assertThat(redis.hasKey("otp:email:cooldown:login:$a")).describedAs(fault.name).isFalse()
            assertThat(redis.opsForValue().get("otp:rate:email:$a")).describedAs(fault.name).isEqualTo("0")
            assertThat(redis.opsForValue().get("otp:rate:ip:$network")).describedAs(fault.name).isEqualTo("1")
            assertThat(refusal { otp.verifyByEmail(a, email.lastCode(), null, login) }.code)
                .describedAs(fault.name).isEqualTo("otp_expired")
        }
    }

    @Test
    fun `a deferred send that times out says it is delayed, keeps its code and opens resend`() {
        val email = RecordingEmailSender().apply { faults.always("email", SandboxFault.TIMEOUT) }
        val otp = service(email = email)
        val a = address()
        val c = otp.requestByEmail(a, ip(), login, unreported)
        val s = otp.emailDelivery(c.requestId)
        assertThat(s.status).isEqualTo("delayed")
        assertThat(s.failure).isNull()
        assertThat(s.resendAfterSeconds).isEqualTo(0L)
        assertThat(redis.hasKey("otp:email:cooldown:login:$a")).isFalse()
        assertThat(redis.opsForValue().get("otp:rate:email:$a")).isEqualTo("1")
        otp.verifyByEmail(a, email.lastCode(), null, login)
    }

    @Test
    fun `a deferred send that works says sent, and keeps the cooldown`() {
        val email = RecordingEmailSender()
        val otp = service(email = email)
        val a = address()
        val c = otp.requestByEmail(a, ip(), login, unreported)
        assertThat(otp.emailDelivery(c.requestId).status).isEqualTo("sent")
        assertThat(otp.emailDelivery(c.requestId).message).isNull()
        assertThat(redis.hasKey("otp:email:cooldown:login:$a")).isTrue()
        otp.verifyByEmail(a, email.lastCode(), null, login)
    }

    @Test
    fun `the status is sending until the send has settled`() {
        val email = RecordingEmailSender().apply { gate = CountDownLatch(1) }
        val otp = service(email = email, background = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
        val c = otp.requestByEmail(address(), ip(), login, unreported)
        try {
            assertThat(otp.emailDelivery(c.requestId).status).isEqualTo("sending")
        } finally {
            email.gate!!.countDown()
        }
    }

    @Test
    fun `a request id that was never issued is not found, whatever it looks like`() {
        for (id in listOf(java.util.UUID.randomUUID().toString(), "not-a-uuid", "../../otp:rate:email:x")) {
            val e = refusal { service().emailDelivery(id) }
            assertThat(e.status).describedAs(id).isEqualTo(HttpStatus.NOT_FOUND)
            assertThat(e.code).describedAs(id).isEqualTo("otp_request_unknown")
        }
    }

    /** Everything a decoy or a real deferred request leaves behind that someone outside could probe. */
    private fun observable(otp: OtpService, email: String, requestId: String, network: String) = listOf(
        otp.emailDelivery(requestId).copy(requestId = "-"),
        challenge(email).isEmpty(),
        redis.hasKey("otp:email:cooldown:login:$email"),
        redis.opsForValue().get("otp:rate:email:$email"),
        redis.opsForValue().get("otp:rate:ip:$network"),
    )

    @Test
    fun `a decoy settles the way the last real send did, for every way a provider can be`() {
        for (fault in listOf(null, SandboxFault.TIMEOUT, SandboxFault.UNAVAILABLE, SandboxFault.INSUFFICIENT_BALANCE)) {
            val sender = RecordingEmailSender().apply { fault?.let { faults.always("email", it) } }
            val otp = service(email = sender)
            val (listed, unlisted) = address() to address()
            val (n1, n2) = ip() to ip()
            val real = otp.requestByEmail(listed, n1, login, unreported)
            val decoy = otp.requestByEmail(unlisted, n2, login, OtpDelivery.DECOY)
            assertThat(observable(otp, unlisted, decoy.requestId, n2)).describedAs("${fault ?: "healthy"}")
                .isEqualTo(observable(otp, listed, real.requestId, n1))
            assertThat(sender.sent.map { it.first }).doesNotContain(unlisted)
        }
    }

    @Test
    fun `a rejection belongs to one address, so a decoy after it reports sent`() {
        val sender = RecordingEmailSender().apply { faults.always("email", SandboxFault.REJECTED) }
        val otp = service(email = sender)
        otp.requestByEmail(address(), ip(), login, unreported)
        val decoy = otp.requestByEmail(address(), ip(), login, OtpDelivery.DECOY)
        assertThat(otp.emailDelivery(decoy.requestId).status).isEqualTo("sent")
    }

    @Test
    fun `a decoy takes as long as the last real send, and both settle on the same whole second`() {
        val sender = RecordingEmailSender(faults = SandboxFaults(java.time.Duration.ofMillis(1_300)))
            .apply { faults.always("email", SandboxFault.HANG) }
        val otp = service(email = sender)
        fun settleMillis(email: String, delivery: OtpDelivery): Long {
            val start = System.nanoTime()
            otp.requestByEmail(email, ip(), login, delivery)
            return java.time.Duration.ofNanos(System.nanoTime() - start).toMillis()
        }
        val real = settleMillis(address(), unreported)
        val decoy = settleMillis(address(), OtpDelivery.DECOY)
        // A 1.3 s send settles on the two-second tick; so must its shadow.
        assertThat(real).isBetween(1_950L, 2_600L)
        assertThat(decoy).isBetween(1_950L, 2_600L)
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

    @Test
    fun `an email code is one send, reported or not, whatever the failure and the provider's attempts`() {
        val generous = AlmiraProperties.Provider(timeout = java.time.Duration.ofSeconds(60), maxAttempts = 5)
        val quick = AlmiraProperties.Otp(maxPerHour = 1_000, maxPerIpPerHour = 1_000, sendTimeout = java.time.Duration.ofMillis(200))
        val props = props(otp = quick).copy(providers = AlmiraProperties.Providers(sms = generous, email = generous))
        for (fault in SandboxFault.entries) {
            for (delivery in listOf(OtpDelivery.REPORTED, OtpDelivery.DEFERRED)) {
                val email = RecordingEmailSender(faults = SandboxFaults(java.time.Duration.ofSeconds(2)))
                    .apply { faults.always("email", fault) }
                val otp = service(email = email, props = props)
                val purpose = if (delivery == OtpDelivery.REPORTED) OtpService.STEP_UP else login
                runCatching { otp.requestByEmail(address(), ip(), purpose, delivery) }
                assertThat(email.sent).describedAs("$fault / $delivery").hasSize(1)
            }
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

    // --- a resend that fails does not cost the code that did arrive (known-issues 22) ---

    @Test
    fun `a resend whose email fails leaves the earlier emailed code working, under the id the code step now holds`() {
        for (fault in listOf(SandboxFault.REJECTED, SandboxFault.UNAVAILABLE, SandboxFault.INSUFFICIENT_BALANCE)) {
            val email = RecordingEmailSender()
            val otp = service(email = email)
            val a = address()
            val first = otp.requestByEmail(a, ip(), login, unreported)
            assertThat(otp.emailDelivery(first.requestId).status).isEqualTo("sent")
            val arrived = email.lastCode()

            redis.delete("otp:email:cooldown:login:$a")
            email.faults.always("email", fault)
            // The answer comes before the send, so the code step switches to this id.
            val resend = otp.requestByEmail(a, ip(), login, unreported)
            assertThat(otp.emailDelivery(resend.requestId).status).describedAs(fault.name).isEqualTo("failed")

            assertThat(challenge(a)["requestId"]).describedAs("$fault: the earlier challenge is back").isEqualTo(first.requestId)
            otp.verifyByEmail(a, arrived, resend.requestId, login)
        }
    }

    @Test
    fun `an earlier emailed code does not come back once a newer one was sent or may have been`() {
        for (newer in listOf(null, SandboxFault.TIMEOUT)) {
            val email = RecordingEmailSender()
            val otp = service(email = email)
            val a = address()
            val first = otp.requestByEmail(a, ip(), login, unreported)
            val firstCode = email.lastCode()

            redis.delete("otp:email:cooldown:login:$a")
            newer?.let { email.faults.always("email", it) }
            val second = otp.requestByEmail(a, ip(), login, unreported)
            val secondCode = email.lastCode()

            redis.delete("otp:email:cooldown:login:$a")
            email.faults.always("email", SandboxFault.UNAVAILABLE)
            val third = otp.requestByEmail(a, ip(), login, unreported)
            assertThat(otp.emailDelivery(third.requestId).status).isEqualTo("failed")

            val label = newer?.name ?: "sent"
            assertThat(challenge(a)["requestId"]).describedAs(label).isEqualTo(second.requestId)
            assertThat(refusal { otp.verifyByEmail(a, firstCode, first.requestId, login) }.code)
                .describedAs(label).isEqualTo("otp_stale")
            if (firstCode != secondCode) {
                assertThat(refusal { otp.verifyByEmail(a, firstCode, third.requestId, login) }.code)
                    .describedAs(label).isEqualTo("otp_invalid")
            }
            otp.verifyByEmail(a, secondCode, third.requestId, login)
        }
    }

    @Test
    fun `a decoy's failed resend falls back the way a real one does`() {
        val sender = RecordingEmailSender()
        val otp = service(email = sender)
        val (listed, unlisted) = address() to address()
        val real = otp.requestByEmail(listed, ip(), login, unreported)
        val realCode = sender.lastCode()
        val decoy = otp.requestByEmail(unlisted, ip(), login, OtpDelivery.DECOY)

        // The provider goes down: the listed address's resend fails, and so the
        // decoy's replays a failure.
        redis.delete("otp:email:cooldown:login:$listed")
        redis.delete("otp:email:cooldown:login:$unlisted")
        sender.faults.always("email", SandboxFault.UNAVAILABLE)
        val realResend = otp.requestByEmail(listed, ip(), login, unreported)
        val decoyResend = otp.requestByEmail(unlisted, ip(), login, OtpDelivery.DECOY)
        assertThat(otp.emailDelivery(decoyResend.requestId).status).isEqualTo("failed")

        // What someone outside can probe: a wrong code under the resend's id is
        // judged (otp_invalid), not refused as stale or expired, for both.
        fun probe(email: String, requestId: String, wrong: String) =
            refusal { otp.verifyByEmail(email, wrong, requestId, login) }.code
        assertThat(challenge(unlisted)["requestId"]).isEqualTo(decoy.requestId)
        assertThat(challenge(listed)["requestId"]).isEqualTo(real.requestId)
        assertThat(challenge(unlisted).keys).isEqualTo(challenge(listed).keys.map { it.replace(realResend.requestId, decoyResend.requestId) }.toSet())
        assertThat(probe(listed, realResend.requestId, wrongFor(realCode))).isEqualTo("otp_invalid")
        assertThat(probe(unlisted, decoyResend.requestId, wrongFor(realCode))).isEqualTo("otp_invalid")
    }

    // --- a restored emailed code keeps its attempt cap (security: known-issues 22) --

    private val outright = listOf(SandboxFault.REJECTED, SandboxFault.UNAVAILABLE, SandboxFault.INSUFFICIENT_BALANCE)

    private fun wrongCodes(n: Int, vararg avoid: String) =
        (0 until n + avoid.size).map { "%06d".format(it) }.filter { it !in avoid }.take(n)

    private fun attempts(email: String) =
        redis.opsForHash<String, String>().get("otp:email:challenge:login:$email", "attempts")

    /** Records the code before it blocks, so a test knows the in-flight code while the send is held. */
    class GatedEmailSender(val faults: SandboxFaults = SandboxFaults()) : EmailOtpSender {
        override val available = true
        override val exposesCodeForDevelopment = false
        val sent = CopyOnWriteArrayList<Pair<String, String>>()
        val gates = java.util.concurrent.ConcurrentLinkedQueue<Pair<CountDownLatch, CountDownLatch>>()
        override fun send(email: String, code: String) {
            sent += email to code
            gates.poll()?.let { (entered, release) -> entered.countDown(); release.await(10, TimeUnit.SECONDS) }
            faults.apply("email")
        }
    }

    private fun settled(otp: OtpService, requestId: String): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val status = otp.emailDelivery(requestId).status
            if (status != "sending") return status
            Thread.sleep(5)
        }
        throw AssertionError("send for $requestId never settled")
    }

    @Test
    fun `a restored emailed code has only the attempts it had left, for every outright failure and every count`() {
        val max = 5
        for (fault in outright) {
            for (k in 1 until max) {
                val email = RecordingEmailSender()
                val otp = service(email = email)
                val a = address()
                val first = otp.requestByEmail(a, ip(), login, unreported)
                val code = email.lastCode()
                val wrong = wrongCodes(max, code)
                repeat(k) { i ->
                    assertThat(refusal { otp.verifyByEmail(a, wrong[i], first.requestId, login) }.code).isEqualTo("otp_invalid")
                }

                redis.delete("otp:email:cooldown:login:$a")
                email.faults.always("email", fault)
                val resend = otp.requestByEmail(a, ip(), login, unreported)
                assertThat(otp.emailDelivery(resend.requestId).status).isEqualTo("failed")
                email.faults.clear()

                val label = "$fault after $k wrong"
                assertThat(challenge(a)["requestId"]).describedAs("$label: restored").isEqualTo(first.requestId)
                assertThat(attempts(a)).describedAs("$label: attempts kept, not reset").isEqualTo("$k")
                val next = refusal { otp.verifyByEmail(a, wrong[k], resend.requestId, login) }
                if (k + 1 < max) {
                    assertThat(next.code).describedAs(label).isEqualTo("otp_invalid")
                    assertThat(next.details["attemptsRemaining"]).describedAs("$label: remaining").isEqualTo(max - k - 1)
                } else {
                    assertThat(next.code).describedAs("$label: the last attempt locks").isEqualTo("otp_locked")
                    assertThat(refusal { otp.verifyByEmail(a, code, resend.requestId, login) }.code)
                        .describedAs("$label: right code refused once locked").isEqualTo("otp_expired")
                }
            }
        }
    }

    @Test
    fun `guessing and failing to resend an email in a loop never judges more than the allowed wrong codes`() {
        val max = 5
        for (fault in outright) {
            val email = RecordingEmailSender()
            val otp = service(email = email)
            val a = address()
            var current = otp.requestByEmail(a, ip(), login, unreported).requestId
            val code = email.lastCode()
            val judged = mutableListOf<String>()
            for ((i, guess) in wrongCodes(max * 6, code).withIndex()) {
                val id = if (i % 2 == 0) current else null
                judged += refusal { otp.verifyByEmail(a, guess, id, login) }.code
                redis.delete("otp:email:cooldown:login:$a")
                email.faults.always("email", fault)
                runCatching { current = otp.requestByEmail(a, ip(), login, unreported).requestId }
                email.faults.clear()
            }
            assertThat(judged.count { it == "otp_invalid" || it == "otp_locked" })
                .describedAs("$fault: wrong codes judged against one code across ${max * 6} restores: $judged")
                .isLessThanOrEqualTo(max)
            assertThat(judged.count { it == "otp_locked" }).describedAs("$fault: locked once").isEqualTo(1)
            assertThat(refusal { otp.verifyByEmail(a, code, current, login) }.code)
                .describedAs("$fault: right code refused after the cap").isIn("otp_expired", "otp_stale")
            assertThat(refusal { otp.verifyByEmail(a, code, null, login) }.code)
                .describedAs("$fault: and without the id").isIn("otp_expired", "otp_invalid")
        }
    }

    @Test
    fun `a locked emailed code is not brought back by a resend that fails`() {
        for (fault in outright) {
            val email = RecordingEmailSender()
            val otp = service(email = email, props = props(otp = AlmiraProperties.Otp(maxAttempts = 3, maxPerHour = 1_000, maxPerIpPerHour = 1_000)))
            val a = address()
            val first = otp.requestByEmail(a, ip(), login, unreported)
            val code = email.lastCode()
            assertThat(wrongCodes(3, code).map { refusal { otp.verifyByEmail(a, it, first.requestId, login) }.code })
                .containsExactly("otp_invalid", "otp_invalid", "otp_locked")
            redis.delete("otp:email:cooldown:login:$a")
            email.faults.always("email", fault)
            val resend = otp.requestByEmail(a, ip(), login, unreported)
            assertThat(otp.emailDelivery(resend.requestId).status).isEqualTo("failed")
            assertThat(challenge(a)).describedAs("$fault: nothing restored").isEmpty()
            assertThat(refusal { otp.verifyByEmail(a, code, resend.requestId, login) }.code).describedAs(fault.name).isEqualTo("otp_expired")
        }
    }

    @Test
    fun `restoring an emailed code does not give it a longer life`() {
        val email = RecordingEmailSender()
        val otp = service(
            email = email,
            // Each deferred send settles on a whole-second tick, so the life
            // must outlast a few of them for a restore to happen at all.
            props = props(otp = AlmiraProperties.Otp(ttl = java.time.Duration.ofSeconds(5), resendCooldown = java.time.Duration.ZERO,
                maxPerHour = 1_000, maxPerIpPerHour = 1_000)),
        )
        val a = address()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        val first = otp.requestByEmail(a, ip(), login, unreported).requestId
        var current = first
        val code = email.lastCode()
        var lastLife = redis.getExpire("otp:email:challenge:login:$a", TimeUnit.MILLISECONDS)
        var restores = 0
        email.faults.always("email", SandboxFault.REJECTED)
        while (System.nanoTime() < deadline + TimeUnit.MILLISECONDS.toNanos(500)) {
            runCatching { current = otp.requestByEmail(a, ip(), login, unreported).requestId }
            val life = redis.getExpire("otp:email:challenge:login:$a", TimeUnit.MILLISECONDS)
            if (life > 0) {
                assertThat(challenge(a)["requestId"]).isEqualTo(first)
                restores++
                assertThat(life).describedAs("a restore never adds life").isLessThanOrEqualTo(lastLife)
                lastLife = life
                assertThat(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(life))
                    .describedAs("never past the first send's expiry")
                    .isLessThanOrEqualTo(deadline + TimeUnit.MILLISECONDS.toNanos(50))
            }
            Thread.sleep(100)
        }
        assertThat(restores).describedAs("the earlier code was put back at least twice").isGreaterThanOrEqualTo(2)
        assertThat(refusal { otp.verifyByEmail(a, code, current, login) }.code).isEqualTo("otp_expired")
    }

    @Test
    fun `wrong codes racing a failing email resend cannot buy more than the allowed attempts`() {
        val max = 5
        val pool = java.util.concurrent.Executors.newFixedThreadPool(16)
        val background = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        try {
            repeat(15) { round ->
                val sender = GatedEmailSender()
                val otp = service(email = sender, background = background)
                val a = address()
                val first = otp.requestByEmail(a, ip(), login, unreported)
                assertThat(settled(otp, first.requestId)).isEqualTo("sent")
                val code = sender.sent.last().second
                val k = round % max
                val judged = CopyOnWriteArrayList<String>()
                wrongCodes(k, code).forEach { judged += refusal { otp.verifyByEmail(a, it, null, login) }.code }

                redis.delete("otp:email:cooldown:login:$a")
                val entered = CountDownLatch(1); val release = CountDownLatch(1)
                sender.gates += entered to release
                sender.faults.always("email", SandboxFault.REJECTED)
                val resend = otp.requestByEmail(a, ip(), login, unreported)
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
                val resendCode = sender.sent.last().second

                val start = CountDownLatch(1)
                val guesses = wrongCodes(16, code, resendCode).drop(k).map { guess ->
                    pool.submit<String> { start.await(); refusal { otp.verifyByEmail(a, guess, null, login) }.code }
                }
                start.countDown()
                release.countDown()
                guesses.forEach { judged += it.get(10, TimeUnit.SECONDS) }
                assertThat(settled(otp, resend.requestId)).isEqualTo("failed")

                wrongCodes(16 + max, code, resendCode).drop(16).forEach {
                    judged += refusal { otp.verifyByEmail(a, it, null, login) }.code
                }
                assertThat(judged.count { it == "otp_invalid" || it == "otp_locked" })
                    .describedAs("round $round, $k before the race: $judged")
                    .isLessThanOrEqualTo(max)
                assertThat(refusal { otp.verifyByEmail(a, code, resend.requestId, login) }.code)
                    .describedAs("round $round: the earlier code is refused after the cap").isIn("otp_expired", "otp_stale")
            }
        } finally {
            pool.shutdownNow()
            background.shutdownNow()
        }
    }

    @Test
    fun `wrong codes that lock an in-flight email challenge lock the code it would restore`() {
        val sender = GatedEmailSender()
        val background = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        try {
            val otp = service(
                email = sender, background = background,
                props = props(otp = AlmiraProperties.Otp(maxAttempts = 3, maxPerHour = 1_000, maxPerIpPerHour = 1_000)),
            )
            val a = address()
            val first = otp.requestByEmail(a, ip(), login, unreported)
            assertThat(settled(otp, first.requestId)).isEqualTo("sent")
            val code = sender.sent.last().second
            assertThat(refusal { otp.verifyByEmail(a, wrongFor(code), null, login) }.code).isEqualTo("otp_invalid")

            redis.delete("otp:email:cooldown:login:$a")
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            sender.gates += entered to release
            sender.faults.always("email", SandboxFault.REJECTED)
            val resend = otp.requestByEmail(a, ip(), login, unreported)
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            val resendCode = sender.sent.last().second
            val during = wrongCodes(3, code, resendCode).drop(1).map { refusal { otp.verifyByEmail(a, it, null, login) }.code }
            assertThat(during).describedAs("the third wrong code of three locks").containsExactly("otp_invalid", "otp_locked")
            release.countDown()
            assertThat(settled(otp, resend.requestId)).isEqualTo("failed")

            assertThat(challenge(a)).describedAs("nothing restored").isEmpty()
            // The right code first: a restored challenge at its cap must not accept it.
            assertThat(refusal { otp.verifyByEmail(a, code, resend.requestId, login) }.code).isEqualTo("otp_expired")
            assertThat(refusal { otp.verifyByEmail(a, wrongCodes(4, code).last(), resend.requestId, login) }.code)
                .describedAs("no fourth wrong code is judged").isEqualTo("otp_expired")
        } finally {
            background.shutdownNow()
        }
    }

    @Test
    fun `a code at its cap is never put back, even when the in-flight misses were counted without it`() {
        // An instance still running the script from before the shared count (a
        // rolling deploy) records misses against the in-flight challenge alone.
        // FALL_BACK must still refuse to restore at the cap.
        val sender = GatedEmailSender()
        val background = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        try {
            val otp = service(
                email = sender, background = background,
                props = props(otp = AlmiraProperties.Otp(maxAttempts = 3, maxPerHour = 1_000, maxPerIpPerHour = 1_000)),
            )
            val a = address()
            val first = otp.requestByEmail(a, ip(), login, unreported)
            assertThat(settled(otp, first.requestId)).isEqualTo("sent")
            val code = sender.sent.last().second
            wrongCodes(2, code).forEach { refusal { otp.verifyByEmail(a, it, null, login) } }

            redis.delete("otp:email:cooldown:login:$a")
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            sender.gates += entered to release
            sender.faults.always("email", SandboxFault.REJECTED)
            val resend = otp.requestByEmail(a, ip(), login, unreported)
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            redis.opsForHash<String, String>().increment("otp:email:challenge:login:$a", "attempts", 1)
            release.countDown()
            assertThat(settled(otp, resend.requestId)).isEqualTo("failed")

            assertThat(challenge(a)).describedAs("2 + 1 of 3: not put back").isEmpty()
            assertThat(refusal { otp.verifyByEmail(a, code, first.requestId, login) }.code).isEqualTo("otp_expired")
        } finally {
            background.shutdownNow()
        }
    }

    @Test
    fun `a challenge at its cap does not accept the right code, however it got there`() {
        val email = RecordingEmailSender()
        val otp = service(email = email, props = props(otp = AlmiraProperties.Otp(maxAttempts = 3, maxPerHour = 1_000, maxPerIpPerHour = 1_000)))
        val a = address()
        val c = otp.requestByEmail(a, ip(), login, unreported)
        redis.opsForHash<String, String>().put("otp:email:challenge:login:$a", "attempts", "3")
        assertThat(refusal { otp.verifyByEmail(a, email.lastCode(), c.requestId, login) }.code).isEqualTo("otp_expired")
        assertThat(challenge(a)).describedAs("and it is removed").isEmpty()
    }

    @Test
    fun `a network's wrong-code allowance still counts across emailed restores`() {
        val email = RecordingEmailSender()
        val otp = service(
            email = email,
            props = props(otp = AlmiraProperties.Otp(maxAttempts = 10, maxPerHour = 1_000, maxPerIpPerHour = 1_000, maxVerifyFailuresPerIpPerHour = 3)),
        )
        val attacker = ip()
        val a = address()
        var current = otp.requestByEmail(a, ip(), login, unreported).requestId
        val code = email.lastCode()
        for (guess in wrongCodes(3, code)) {
            assertThat(refusal { otp.verifyByEmail(a, guess, null, login, attacker) }.code).isEqualTo("otp_invalid")
            redis.delete("otp:email:cooldown:login:$a")
            email.faults.always("email", SandboxFault.REJECTED)
            current = otp.requestByEmail(a, ip(), login, unreported).requestId
            email.faults.clear()
        }
        val e = refusal { otp.verifyByEmail(a, code, current, login, attacker) }
        assertThat(e.status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(e.message).contains("this network")
        assertThat(attempts(a)).isEqualTo("3")
    }

    @Test
    fun `an address's and a network's request allowances still count across emailed restores`() {
        val email = RecordingEmailSender()
        val otp = service(
            email = email,
            props = props(otp = AlmiraProperties.Otp(resendCooldown = java.time.Duration.ZERO, maxPerHour = 2, maxPerIpPerHour = 1_000)),
        )
        val a = address()
        otp.requestByEmail(a, ip(), login, unreported)
        email.faults.always("email", SandboxFault.UNAVAILABLE)
        repeat(6) { otp.requestByEmail(a, ip(), login, unreported) }
        assertThat(redis.opsForValue().get("otp:rate:email:$a"))
            .describedAs("the delivered send still counts; only the failed ones are given back").isEqualTo("1")
        email.faults.clear()
        otp.requestByEmail(a, ip(), login, unreported)
        val e = refusal { otp.requestByEmail(a, ip(), login, unreported) }
        assertThat(e.status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(e.message).contains("this email address")

        val netEmail = RecordingEmailSender()
        val netOtp = service(
            email = netEmail,
            props = props(otp = AlmiraProperties.Otp(resendCooldown = java.time.Duration.ZERO, maxPerHour = 1_000, maxPerIpPerHour = 3)),
        )
        val network = ip()
        val b = address()
        netOtp.requestByEmail(b, network, login, unreported)
        netEmail.faults.always("email", SandboxFault.REJECTED)
        repeat(2) { netOtp.requestByEmail(b, network, login, unreported) }
        assertThat(refusal { netOtp.requestByEmail(b, network, login, unreported) }.message).contains("this network")
    }
}
