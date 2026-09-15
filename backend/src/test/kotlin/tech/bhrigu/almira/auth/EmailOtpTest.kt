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
import tech.bhrigu.almira.provider.FailureKind
import tech.bhrigu.almira.provider.ProviderFailure
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
import java.time.Instant
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.DoubleSupplier
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * One-time codes by email, against a real Redis, as OtpServiceTest does for
 * phone. What is different about email is tested here: its own key and HMAC
 * namespace, the challenge an address off the allowlist gets, the email that is
 * queued rather than sent on the request path (the worker that sends or drops
 * it is SignInEmailOutboxTest), the delivery status that follows the clock, and
 * the sender that refuses outside development.
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

    /**
     * Remembers codes instead of delivering them, and fails as the faults say —
     * for every recipient, as a provider that is down fails. [refuses] are the
     * addresses it rejects one by one, as a provider refuses a suppressed
     * mailbox and accepts the next.
     */
    class RecordingEmailSender(
        override val available: Boolean = true,
        override val exposesCodeForDevelopment: Boolean = false,
        val faults: SandboxFaults = SandboxFaults(),
    ) : EmailOtpSender {
        val sent = CopyOnWriteArrayList<Pair<String, String>>()
        val refuses: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        @Volatile var gate: CountDownLatch? = null
        override fun send(email: String, code: String) {
            gate?.await(10, TimeUnit.SECONDS)
            sent += email to code
            if (email in refuses) throw ProviderFailure(FailureKind.REJECTED, "test: this address is refused")
            faults.apply("email")
        }
        fun lastCode() = sent.last().second
    }

    /** What the request path handed the outbox, in order. It never sends. */
    class RecordingOutbox : SignInCodeOutbox {
        data class Queued(val address: String, val purpose: String, val requestId: String, val codeLength: Int)
        val queued = CopyOnWriteArrayList<Queued>()
        @Volatile var failWith: RuntimeException? = null
        override fun enqueue(address: String, purpose: String, requestId: String, codeLength: Int) {
            failWith?.let { throw it }
            queued += Queued(address, purpose, requestId, codeLength)
        }
    }

    /** A clock a test moves by hand. */
    class HandClock(@Volatile var now: Instant = START) : Clock() {
        override fun getZone(): java.time.ZoneId = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?): Clock = this
        override fun instant(): Instant = now
        companion object { val START: Instant = Instant.parse("2026-09-15T06:00:00Z") }
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

    private var outbox = RecordingOutbox()

    private fun service(
        email: EmailOtpSender = RecordingEmailSender(),
        props: AlmiraProperties = props(),
        phone: OtpSender = OtpServiceTest.RecordingSender(),
        // Sent at once, unless a test is about when.
        settleDelay: java.time.Duration = java.time.Duration.ZERO,
        clock: Clock = Clock.systemUTC(),
    ): OtpService {
        outbox = RecordingOutbox()
        return OtpService(
            redis, phone, props,
            ProviderCalls(props, Sleeper { }, DoubleSupplier { 1.0 }, Clock.systemUTC()),
            email, outbox, settleDelay, clock,
        )
    }

    /** The code the worker would send for the last message queued to [email]. */
    private fun queuedCode(email: String, props: AlmiraProperties = props()): String {
        val q = outbox.queued.last { it.address == email }
        return QueuedEmailCodes(props).code(q.purpose, q.address, q.requestId, q.codeLength)
    }

    private fun address() = "tester.${Random.nextLong(1, Long.MAX_VALUE)}+alpha@example.test"
    private fun ip() = "198.51.100.${Random.nextInt(1, 255)}-${Random.nextLong()}"

    private fun refusal(block: () -> Unit): ApiException =
        runCatching(block).exceptionOrNull() as? ApiException
            ?: throw AssertionError("expected an ApiException")

    private fun keysFor(email: String) = redis.keys("otp:*$email*")
    private fun challenge(email: String, purpose: String = OtpService.LOGIN) =
        redis.opsForHash<String, String>().entries("otp:email:challenge:$purpose:$email")

    private val login = OtpService.LOGIN
    private val listedDelivery = OtpDelivery.DEFERRED
    private val unlistedDelivery = OtpDelivery.DECOY

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

            // And so the request is refused before a code, a key or a queued message exists.
            for (delivery in listOf(listedDelivery, unlistedDelivery)) {
                val email = address()
                val otp = service(email = sender, props = props(environment))
                val e = refusal { otp.requestByEmail(email, ip(), login, delivery) }
                assertThat(e.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                assertThat(e.code).isEqualTo("otp_unavailable")
                assertThat(e.message).contains("email")
                assertThat(keysFor(email)).describedAs("nothing stored for '$environment'").isEmpty()
                assertThat(outbox.queued).describedAs("nothing queued for '$environment'").isEmpty()
            }
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
        val phone = OtpServiceTest.RecordingSender()
        val otp = service(phone = phone)
        // The same string as both an address and a "number", so only the HMAC
        // key can tell the two apart.
        val shared = address()

        otp.requestByEmail(shared, ip(), login, listedDelivery)
        val emailCode = queuedCode(shared)
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
        val otp = service()
        val a = address()
        otp.requestByEmail(a, ip(), login, listedDelivery)
        val code = queuedCode(a)
        otp.verifyByEmail(a, code, null, login)
        assertThat(refusal { otp.verifyByEmail(a, code, null, login) }.code).isEqualTo("otp_expired")

        val b = address()
        otp.requestByEmail(b, ip(), login, listedDelivery)
        val bCode = queuedCode(b)
        val wrong = wrongFor(bCode)
        assertThat((1..5).map { refusal { otp.verifyByEmail(b, wrong, null, login) }.code })
            .containsExactly("otp_invalid", "otp_invalid", "otp_invalid", "otp_invalid", "otp_locked")
        assertThat(refusal { otp.verifyByEmail(b, bCode, null, login) }.code).isEqualTo("otp_expired")
    }

    // --- the code that is queued ------------------------------------------------------

    /**
     * The queue holds an address and a request id, never a code. The code is
     * derived from them under a key of its own, so the worker can send it and a
     * dump of the queue cannot — and a message pointed at another address, or
     * carrying another request id, derives a code that matches nothing.
     */
    @Test
    fun `a queued code is derived from the request, bound to its address and id, and matches the stored challenge`() {
        val otp = service()
        val a = address()
        val c = otp.requestByEmail(a, ip(), login, listedDelivery)
        assertThat(outbox.queued.single()).isEqualTo(RecordingOutbox.Queued(a, login, c.requestId, 6))

        val codes = QueuedEmailCodes(props())
        val code = codes.code(login, a, c.requestId, 6)
        assertThat(code).matches("[0-9]{6}")
        assertThat(codes.code(login, a, c.requestId, 8)).matches("[0-9]{8}")
        assertThat(QueuedEmailCodes(props()).code(login, a, c.requestId, 6)).describedAs("stable across instances").isEqualTo(code)
        assertThat(codes.code(login, "someone.else@example.test", c.requestId, 6)).describedAs("another address").isNotEqualTo(code)
        assertThat(codes.code(login, a, java.util.UUID.randomUUID().toString(), 6)).describedAs("another id").isNotEqualTo(code)
        val otherKey = props().copy(jwt = AlmiraProperties.Jwt("a-different-secret-that-is-also-long-enough-for-hmac"))
        assertThat(QueuedEmailCodes(otherKey).code(login, a, c.requestId, 6)).describedAs("another server key").isNotEqualTo(code)

        otp.verifyByEmail(a, code, c.requestId, login)
    }

    /** Spread over the digits, as a random code is: no digit, and no leading zero, is avoided. */
    @Test
    fun `derived codes use every digit in every position`() {
        val codes = QueuedEmailCodes(props())
        val seen = Array(6) { mutableSetOf<Char>() }
        repeat(2_000) {
            codes.code(login, address(), java.util.UUID.randomUUID().toString(), 6).forEachIndexed { i, d -> seen[i] += d }
        }
        seen.forEachIndexed { i, digits -> assertThat(digits).describedAs("position $i").hasSize(10) }
    }

    // --- the address that is not on the list -----------------------------------------

    /**
     * The stored value must match NO code, not merely not the one generated —
     * so this tries every one of the million six-digit codes under the real
     * derived key, the way someone who guessed the scheme would.
     */
    @Test
    fun `an unlisted address stores a challenge no code can ever complete, and is queued exactly as a listed one`() {
        val email = RecordingEmailSender(exposesCodeForDevelopment = true)
        val otp = service(email = email)
        val unlisted = address()

        val c = otp.requestByEmail(unlisted, ip(), login, unlistedDelivery)
        assertThat(email.sent).describedAs("nothing sent on the request path").isEmpty()
        assertThat(outbox.queued).containsExactly(RecordingOutbox.Queued(unlisted, login, c.requestId, 6))
        assertThat(c.developmentCode).describedAs("not even in development").isNull()
        assertThat(c.channel).isEqualTo(OtpChannel.EMAIL)
        val stored = challenge(unlisted)
        assertThat(stored.keys).containsExactlyInAnyOrder("hash", "requestId", "attempts")
        assertThat(stored["requestId"]).isEqualTo(c.requestId)
        assertThat(redis.getExpire("otp:email:challenge:login:$unlisted", TimeUnit.SECONDS)).isBetween(290L, 300L)
        assertThat(redis.hasKey("otp:email:cooldown:login:$unlisted")).isTrue()

        val derived = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
            SecretKeySpec(doFinal("almira/otp-code/email/v1".toByteArray()), "HmacSHA256")
        }
        val mac = Mac.getInstance("HmacSHA256").apply { init(derived) }
        val target = HexFormat.of().parseHex(stored["hash"])
        val prefix = "login|$unlisted|".toByteArray()
        val matching = (0 until 1_000_000).firstOrNull { n ->
            mac.update(prefix)
            mac.doFinal("%06d".format(n).toByteArray()).contentEquals(target)
        }
        assertThat(matching).describedAs("a six-digit code that completes an unlisted address's challenge").isNull()
        // Including the one the worker would derive, were it ever to send it.
        assertThat(refusal { otp.verifyByEmail(unlisted, queuedCode(unlisted), c.requestId, login) }.code).isEqualTo("otp_invalid")

        // The harness can find a real one, so "found none" means something.
        otp.requestByEmail("$unlisted.real", ip(), login, listedDelivery)
        val realTarget = HexFormat.of().parseHex(challenge("$unlisted.real")["hash"])
        mac.update("login|$unlisted.real|".toByteArray())
        assertThat(mac.doFinal(queuedCode("$unlisted.real").toByteArray()).contentEquals(realTarget)).isTrue()
    }

    // --- the request path, for every address -----------------------------------------

    /** Everything a request leaves behind that someone outside could reach, with the address and id taken out. */
    private fun observable(otp: OtpService, email: String, requestId: String, network: String) = listOf(
        otp.emailDelivery(requestId).copy(requestId = "-"),
        challenge(email).keys.sorted(),
        challenge(email)["attempts"],
        redis.getExpire("otp:email:challenge:login:$email", TimeUnit.SECONDS) in 295L..300L,
        redis.getExpire("otp:email:cooldown:login:$email", TimeUnit.SECONDS) in 25L..30L,
        redis.opsForValue().get("otp:rate:email:$email"),
        redis.opsForValue().get("otp:rate:ip:$network"),
        keysFor(email).map { it.replace(email, "@") }.sorted(),
        redis.keys("otp:*$requestId*").map { it.replace(requestId, "#") }.sorted(),
    )

    /**
     * The owner's decision (2026-09-15): the request path never talks to the
     * provider, for any address. A provider that is down, out of credit,
     * timing out, refusing the address or hanging makes no difference to the
     * request, because nothing on it calls one.
     */
    @Test
    fun `nothing on the request path calls a provider, for any address, whatever the provider is doing`() {
        for (state in listOf("healthy", "UNAVAILABLE", "INSUFFICIENT_BALANCE", "TIMEOUT", "REJECTED", "refusing", "hanging")) {
            val email = RecordingEmailSender()
            SandboxFault.entries.firstOrNull { it.name == state }?.let { email.faults.always("email", it) }
            if (state == "hanging") email.gate = CountDownLatch(1)
            val otp = service(email = email)
            val (listed, unlisted) = address() to address()
            if (state == "refusing") email.refuses += listed
            try {
                val start = System.nanoTime()
                val r = otp.requestByEmail(listed, ip(), login, listedDelivery)
                val d = otp.requestByEmail(unlisted, ip(), login, unlistedDelivery)
                assertThat(java.time.Duration.ofNanos(System.nanoTime() - start).toMillis()).describedAs(state).isLessThan(2_000)
                assertThat(email.sent).describedAs("$state: provider calls on the request path").isEmpty()
                assertThat(outbox.queued.map { it.requestId }).describedAs(state).containsExactly(r.requestId, d.requestId)
            } finally {
                email.gate?.countDown()
            }
        }
    }

    @Test
    fun `a listed and an unlisted request leave the same things behind, whichever is asked first`() {
        for (unlistedFirst in listOf(true, false)) {
            val label = "unlisted ${if (unlistedFirst) "first" else "second"}"
            val otp = service()
            val (listed, unlisted) = address() to address()
            val (n1, n2) = ip() to ip()
            fun real() = otp.requestByEmail(listed, n1, login, listedDelivery)
            fun other() = otp.requestByEmail(unlisted, n2, login, unlistedDelivery)
            val (r, d) = if (unlistedFirst) other().let { real() to it } else real().let { it to other() }
            assertThat(r.copy(requestId = "-")).describedAs(label).isEqualTo(d.copy(requestId = "-"))
            assertThat(observable(otp, unlisted, d.requestId, n2)).describedAs(label)
                .isEqualTo(observable(otp, listed, r.requestId, n1))
            assertThat(outbox.queued.map { it.copy(address = "@", requestId = "#") }.distinct()).describedAs(label).hasSize(1)
        }
    }

    @Test
    fun `a resend replaces the earlier emailed code at once, for every address, whatever becomes of its email`() {
        val otp = service(props = props(otp = AlmiraProperties.Otp(resendCooldown = java.time.Duration.ZERO, maxPerHour = 1_000, maxPerIpPerHour = 1_000)))
        val (listed, unlisted) = address() to address()
        val first = otp.requestByEmail(listed, ip(), login, listedDelivery)
        val firstCode = queuedCode(listed)
        val firstUnlisted = otp.requestByEmail(unlisted, ip(), login, unlistedDelivery)
        val second = otp.requestByEmail(listed, ip(), login, listedDelivery)
        val secondCode = queuedCode(listed)
        val secondUnlisted = otp.requestByEmail(unlisted, ip(), login, unlistedDelivery)

        assertThat(redis.keys("otp:replaced-by:*").filter { it.endsWith(second.requestId) || it.endsWith(secondUnlisted.requestId) })
            .describedAs("nothing is set aside to come back").isEmpty()
        fun probe(email: String, code: String, id: String?) = refusal { otp.verifyByEmail(email, code, id, login) }.let { it.code to it.details }
        assertThat(probe(listed, wrongFor(secondCode), first.requestId)).isEqualTo(probe(unlisted, wrongFor(secondCode), firstUnlisted.requestId))
        assertThat(probe(listed, wrongFor(secondCode), first.requestId).first).isEqualTo("otp_stale")
        if (firstCode != secondCode) {
            assertThat(refusal { otp.verifyByEmail(listed, firstCode, second.requestId, login) }.code).isEqualTo("otp_invalid")
        }
        otp.verifyByEmail(listed, secondCode, second.requestId, login)
    }

    /**
     * The queue is the database: when it cannot be written the request fails,
     * for a reason that is ours and the same for every address, and it costs
     * nobody a code, a cooldown or an hourly request.
     */
    @Test
    fun `a message that cannot be queued fails the request the same way for every address, and costs nobody their code`() {
        val results = listOf(listedDelivery, unlistedDelivery).map { delivery ->
            val otp = service()
            val a = address()
            val network = ip()
            val first = otp.requestByEmail(a, network, login, delivery)
            redis.delete("otp:email:cooldown:login:$a")
            outbox.failWith = org.springframework.dao.DataAccessResourceFailureException("test: the database is away")
            val e = refusal { otp.requestByEmail(a, network, login, delivery) }
            assertThat(challenge(a)["requestId"]).describedAs("$delivery: the earlier challenge stands").isEqualTo(first.requestId)
            listOf(
                e.status, e.code, e.message, e.details,
                redis.hasKey("otp:email:cooldown:login:$a"),
                redis.opsForValue().get("otp:rate:email:$a"),
                redis.opsForValue().get("otp:rate:ip:$network"),
            )
        }
        assertThat(results[0]).isEqualTo(results[1])
        assertThat(results[0].take(2)).containsExactly(HttpStatus.SERVICE_UNAVAILABLE, "otp_service_unavailable")
        assertThat(results[0][4]).describedAs("cooldown lifted").isEqualTo(false)
        assertThat(results[0][5]).describedAs("the failed request given back").isEqualTo("1")
    }

    // --- the delivery status ------------------------------------------------------------

    /**
     * The status follows the clock, not the worker: `sending` until the moment
     * the request recorded, then `sent`, the same for a listed address and an
     * unlisted one to the millisecond. A worker that sent, failed, was refused
     * or dropped the email cannot change it, because nothing it does reaches it.
     */
    @Test
    fun `the status is sending until the moment the request recorded, then sent, for every address alike`() {
        val clock = HandClock()
        val otp = service(settleDelay = java.time.Duration.ofSeconds(6), clock = clock)
        val r = otp.requestByEmail(address(), ip(), login, listedDelivery)
        val d = otp.requestByEmail(address(), ip(), login, unlistedDelivery)
        for ((offset, expected) in listOf(0L to "sending", 5_999L to "sending", 6_000L to "sent", 290_000L to "sent")) {
            clock.now = HandClock.START.plusMillis(offset)
            val statuses = listOf(r, d).map { otp.emailDelivery(it.requestId) }
            assertThat(statuses.map { it.copy(requestId = "-") }.distinct()).describedAs("at +${offset}ms").hasSize(1)
            assertThat(statuses[0].status).describedAs("at +${offset}ms").isEqualTo(expected)
            assertThat(statuses[0].failure).isNull()
            assertThat(statuses[0].message).isNull()
            assertThat(statuses[0].resendAfterSeconds).isNull()
        }
    }

    @Test
    fun `the packaged delay keeps sending as long as it was, the send timeout and a second`() {
        val clock = HandClock()
        val p = props()
        val otp = OtpService(
            redis, OtpServiceTest.RecordingSender(), p,
            ProviderCalls(p, Sleeper { }, DoubleSupplier { 1.0 }, Clock.systemUTC()),
            RecordingEmailSender(), RecordingOutbox(), p.otp.sendTimeout.plus(OtpService.SETTLE_MARGIN), clock,
        )
        val c = otp.requestByEmail(address(), ip(), login, unlistedDelivery)
        clock.now = clock.now.plus(p.otp.sendTimeout).plusMillis(999)
        assertThat(otp.emailDelivery(c.requestId).status).isEqualTo("sending")
        clock.now = clock.now.plusMillis(1)
        assertThat(otp.emailDelivery(c.requestId).status).isEqualTo("sent")
    }

    @Test
    fun `a request id that was never issued is not found, whatever it looks like`() {
        for (id in listOf(java.util.UUID.randomUUID().toString(), "not-a-uuid", "../../otp:rate:email:x")) {
            val e = refusal { service().emailDelivery(id) }
            assertThat(e.status).describedAs(id).isEqualTo(HttpStatus.NOT_FOUND)
            assertThat(e.code).describedAs(id).isEqualTo("otp_request_unknown")
        }
    }

    /** A status a server before the outbox wrote is still read for the few minutes it lives. */
    @Test
    fun `a status written before the outbox still reads as it did`() {
        val otp = service()
        val id = java.util.UUID.randomUUID().toString()
        redis.opsForHash<String, String>().putAll("otp:email:delivery:$id", mapOf("state" to "failed", "failure" to "unavailable"))
        redis.expire("otp:email:delivery:$id", java.time.Duration.ofMinutes(1))
        assertThat(otp.emailDelivery(id).failure).isEqualTo("otp_provider_unavailable")
        redis.opsForHash<String, String>().putAll("otp:email:delivery:$id", mapOf("state" to "sending"))
        redis.opsForHash<String, String>().delete("otp:email:delivery:$id", "failure")
        assertThat(otp.emailDelivery(id).status).describedAs("no settle moment recorded: never claims sent").isEqualTo("sending")
    }

    // --- reported (step-up) --------------------------------------------------------

    @Test
    fun `a reported email send fails the phone way, in email words, a refusal included, because the caller owns the address`() {
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
            assertThat(outbox.queued).describedAs("a reported send is not queued").isEmpty()
        }
    }

    @Test
    fun `a reported email code is one send, whatever the failure and the provider's attempts`() {
        val generous = AlmiraProperties.Provider(timeout = java.time.Duration.ofSeconds(60), maxAttempts = 5)
        val quick = AlmiraProperties.Otp(maxPerHour = 1_000, maxPerIpPerHour = 1_000, sendTimeout = java.time.Duration.ofMillis(200))
        val props = props(otp = quick).copy(providers = AlmiraProperties.Providers(sms = generous, email = generous))
        for (fault in SandboxFault.entries) {
            val email = RecordingEmailSender(faults = SandboxFaults(java.time.Duration.ofSeconds(2)))
                .apply { faults.always("email", fault) }
            val otp = service(email = email, props = props)
            runCatching { otp.requestByEmail(address(), ip(), OtpService.STEP_UP, OtpDelivery.REPORTED) }
            assertThat(email.sent).describedAs(fault.name).hasSize(1)
        }
    }

    // --- limits ---------------------------------------------------------------------

    @Test
    fun `one address has its own hourly allowance, listed or not`() {
        for (delivery in listOf(listedDelivery, unlistedDelivery)) {
            val email = RecordingEmailSender()
            val otp = service(
                email = email,
                props = props(otp = AlmiraProperties.Otp(resendCooldown = java.time.Duration.ZERO, maxPerHour = 2, maxPerIpPerHour = 1_000)),
            )
            val a = address()
            repeat(2) { otp.requestByEmail(a, ip(), login, delivery) }
            val e = refusal { otp.requestByEmail(a, ip(), login, delivery) }
            assertThat(e.status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
            assertThat(e.message).contains("this email address")
            assertThat(outbox.queued).describedAs("$delivery: the refused request is not queued").hasSize(2)
            assertThat(email.sent).isEmpty()
        }
    }

    @Test
    fun `a network's allowances are shared across channels, so alternating does not double them`() {
        val phone = OtpServiceTest.RecordingSender()
        val otp = service(
            phone = phone,
            props = props(otp = AlmiraProperties.Otp(maxPerHour = 1_000, maxPerIpPerHour = 3, maxVerifyFailuresPerIpPerHour = 2)),
        )
        val network = ip()
        otp.request("+9198${Random.nextLong(10_000_000, 99_999_999)}", network)
        otp.requestByEmail(address(), network, login, listedDelivery)
        otp.request("+9198${Random.nextLong(10_000_000, 99_999_999)}", network)
        assertThat(refusal { otp.requestByEmail(address(), network, login, unlistedDelivery) }.message).contains("this network")

        // One wrong code by email and one by phone use up an allowance of two.
        val a = address(); otp.requestByEmail(a, ip(), login, listedDelivery)
        assertThat(refusal { otp.verifyByEmail(a, wrongFor(queuedCode(a)), null, login, network) }.code).isEqualTo("otp_invalid")
        val p = "+9197${Random.nextLong(10_000_000, 99_999_999)}"; otp.request(p, ip())
        assertThat(refusal { otp.verify(p, wrongFor(phone.lastCode()), null, ip = network) }.code).isEqualTo("otp_invalid")
        val b = address(); otp.requestByEmail(b, ip(), login, listedDelivery)
        val e = refusal { otp.verifyByEmail(b, queuedCode(b), null, login, network) }
        assertThat(e.status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
    }

    @Test
    fun `a challenge at its cap does not accept the right code, however it got there`() {
        val otp = service(props = props(otp = AlmiraProperties.Otp(maxAttempts = 3, maxPerHour = 1_000, maxPerIpPerHour = 1_000)))
        val a = address()
        val c = otp.requestByEmail(a, ip(), login, listedDelivery)
        redis.opsForHash<String, String>().put("otp:email:challenge:login:$a", "attempts", "3")
        assertThat(refusal { otp.verifyByEmail(a, queuedCode(a), c.requestId, login) }.code).isEqualTo("otp_expired")
        assertThat(challenge(a)).describedAs("and it is removed").isEmpty()
    }

    private fun wrongFor(code: String) = if (code == "000000") "111111" else "000000"
}
