package tech.bhrigu.almira.auth

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tech.bhrigu.almira.config.AlmiraProperties
import tech.bhrigu.almira.config.HealthController
import tech.bhrigu.almira.provider.FailureKind
import tech.bhrigu.almira.provider.ProviderCalls
import tech.bhrigu.almira.provider.ProviderFailure
import tech.bhrigu.almira.provider.SandboxFault
import tech.bhrigu.almira.provider.SandboxFaults
import tech.bhrigu.almira.support.ApiTestBase
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.sql.DataSource

/**
 * The sign-in email outbox worker, against the real database: what it sends,
 * what it drops without a provider call, what it records, and that no body
 * outlives the record of how its message ended. The request path that fills
 * the queue is EmailOtpTest and EmailSignInApiTest.
 */
// Closed after the class: one more cached server holding pools took the shared
// test database past max_connections for the suites after it.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("The sign-in email outbox")
@Import(SignInEmailOutboxTest.Recording::class)
class SignInEmailOutboxTest : ApiTestBase() {

    class RecordingEmailSender : EmailOtpSender {
        val sent = CopyOnWriteArrayList<Pair<String, String>>()
        val faults = SandboxFaults()
        val refuses: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        override val available = true
        override fun send(email: String, code: String) {
            sent += email to code
            if (email in refuses) throw ProviderFailure(FailureKind.REJECTED, "test: this address is refused")
            faults.apply("email")
        }
    }

    @TestConfiguration
    class Recording {
        @Bean @Primary
        fun outboxTestEmailSender() = RecordingEmailSender()
    }

    @Autowired private lateinit var sender: RecordingEmailSender
    @Autowired private lateinit var outbox: SignInEmailOutbox
    @Autowired private lateinit var props: AlmiraProperties
    @Autowired private lateinit var calls: ProviderCalls
    @Autowired @Qualifier("jdbc") private lateinit var requests: NamedParameterJdbcTemplate
    @Autowired private lateinit var runtimeDataSource: DataSource
    @Autowired @Qualifier("ownerDataSource") private lateinit var ownerDataSource: DataSource

    private val logs = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(SignInEmailOutbox::class.java) as Logger

    @BeforeEach
    fun setUp() {
        outbox.drain()
        sender.sent.clear()
        logs.list.clear()
        logs.start()
        logger.addAppender(logs)
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(logs)
        sender.faults.clear()
        sender.refuses.clear()
        outbox.beforeDecision = null
        outbox.drain()
    }

    private fun request(email: String) = post("/api/v1/auth/otp/email/request", body = mapOf("email" to email)).also {
        check(it.statusCode.value() == 200) { "request for a code failed: ${it.body}" }
    }.json().path("requestId").asText()

    private fun records() = db.queryForList(
        "select e.id, e.status, e.failure, e.send_started_at, b.message_id as body from sign_in_code_emails e " +
            "left join sign_in_code_email_bodies b on b.message_id = e.id order by e.created_at",
    )

    private fun newest() = records().last()

    private fun unlisted() = "stranger.${UUID.randomUUID()}@example.test"

    @Test
    fun `a listed address is sent the code its challenge was stored for, once, and the body goes with the record`() {
        val requestId = request(listed[0])
        assertThat(newest()["status"]).isEqualTo("queued")
        assertThat(newest()["body"]).isNotNull()

        assertThat(outbox.drain()).isEqualTo(SignInEmailDrainResult(sent = 1))
        val (to, code) = sender.sent.single()
        assertThat(to).isEqualTo(listed[0])
        assertThat(newest()["status"]).isEqualTo("sent")
        assertThat(newest()["body"]).describedAs("the body, deleted with the record").isNull()
        assertThat(outbox.drain()).describedAs("never sent twice").isEqualTo(SignInEmailDrainResult())

        val login = post("/api/v1/auth/otp/email/verify", body = mapOf("email" to listed[0], "code" to code, "requestId" to requestId))
        assertThat(login.statusCode.value()).describedAs(login.body).isEqualTo(200)
    }

    /**
     * The guard (docs/known-issues.md, "A guard runs before the action it
     * guards"): the allowlist is asked before the send is stamped, a code is
     * derived or a provider called, so an unlisted address's message reaches
     * no provider at all.
     */
    @Test
    fun `an unlisted address's message is dropped with no provider call, and its body goes with the record`() {
        val stranger = unlisted()
        request(stranger)
        assertThat(outbox.drain()).isEqualTo(SignInEmailDrainResult(dropped = 1))
        assertThat(sender.sent).describedAs("provider calls").isEmpty()
        val record = newest()
        assertThat(record["status"]).isEqualTo("dropped")
        assertThat(record["failure"]).isNull()
        assertThat(record["send_started_at"]).describedAs("no send was started").isNull()
        assertThat(record["body"]).isNull()
        assertThat(db.queryForObject("select count(*) from sign_in_code_email_bodies", Int::class.java)).isZero()
    }

    @Test
    fun `a tester taken off the list while their message waited is dropped, with no provider call`() {
        request(listed[1])
        // The restart with the address gone: the same queue, a worker whose list no longer has it.
        val narrowed = props.copy(auth = props.auth.copy(emailAllowlist = listed.filter { it != listed[1] }))
        val restarted = SignInEmailOutbox(requests, ownerDataSource, sender, calls, SignInChannels(narrowed), props)
        try {
            assertThat(restarted.drain()).describedAs("an intended drop: no operator alert").isEqualTo(SignInEmailDrainResult(dropped = 1))
        } finally {
            restarted.close()
        }
        assertThat(sender.sent).isEmpty()
        assertThat(newest()["status"]).isEqualTo("dropped")

        // And a server that no longer offers email sign-in drops everything; a
        // listed address dropped that way got nothing, and the operator is told.
        request(listed[2])
        val phoneOnly = props.copy(auth = props.auth.copy(signInChannels = listOf("phone")))
        SignInEmailOutbox(requests, ownerDataSource, sender, calls, SignInChannels(phoneOnly), props).use {
            assertThat(it.drain()).isEqualTo(SignInEmailDrainResult(dropped = 1, alerts = 1))
        }
        assertThat(sender.sent).isEmpty()
    }

    @Test
    fun `a provider refusing a listed address is recorded and logged for the operator, with the address masked`() {
        sender.refuses += listed[3]
        request(listed[3])
        assertThat(outbox.drain()).isEqualTo(SignInEmailDrainResult(failed = 1, alerts = 1))
        assertThat(newest()["status"]).isEqualTo("failed")
        assertThat(newest()["failure"]).isEqualTo("rejected")
        assertThat(newest()["body"]).isNull()
        val errors = logs.list.filter { it.level.toString() == "ERROR" }.map { it.formattedMessage }
        assertThat(errors).hasSize(2)
        assertThat(errors[0]).startsWith("SIGN-IN EMAIL REFUSED").doesNotContain(listed[3])
        assertThat(errors[1]).startsWith(SignInEmailOutbox.ALERT_EVENT).doesNotContain(listed[3])
    }

    @Test
    fun `a provider that is down, out of credit or timing out is recorded as such, and the next message still goes`() {
        for ((fault, failure) in listOf(
            SandboxFault.UNAVAILABLE to "unavailable",
            SandboxFault.INSUFFICIENT_BALANCE to "insufficient_balance",
            SandboxFault.TIMEOUT to "timeout",
        )) {
            sender.faults.always("email", fault)
            redis.delete("otp:email:cooldown:login:${listed[4]}")
            request(listed[4])
            assertThat(outbox.drain()).describedAs(fault.name).isEqualTo(SignInEmailDrainResult(failed = 1, alerts = 1))
            assertThat(newest()["failure"]).describedAs(fault.name).isEqualTo(failure)
            assertThat(newest()["body"]).describedAs(fault.name).isNull()
            sender.faults.clear()
        }
        redis.delete("otp:email:cooldown:login:${listed[4]}")
        request(listed[4])
        assertThat(outbox.drain()).isEqualTo(SignInEmailDrainResult(sent = 1))
        assertThat(sender.sent).describedAs("one attempt each, never retried").hasSize(4)
    }

    @Autowired private lateinit var redis: org.springframework.data.redis.core.StringRedisTemplate

    @Test
    fun `a send started by a worker that stopped is never sent again, and a message past the code's lifetime is not sent`() {
        request(listed[5])
        val started = newest()["id"] as UUID
        db.update("update sign_in_code_emails set send_started_at = now(), claimed_until = now() - interval '1 second' where id = ?", started)
        request(unlisted())
        val old = newest()["id"] as UUID
        db.update("update sign_in_code_emails set created_at = now() - interval '6 minutes' where id = ?", old)

        // The unconfirmed one was listed (an alert); the expired one was not (none).
        assertThat(outbox.drain()).isEqualTo(SignInEmailDrainResult(unconfirmed = 1, expired = 1, alerts = 1))
        assertThat(sender.sent).isEmpty()
        val byId = records().associateBy { it["id"] }
        assertThat(byId.getValue(started)["status"]).isEqualTo("unconfirmed")
        assertThat(byId.getValue(old)["status"]).isEqualTo("expired")
        assertThat(db.queryForObject("select count(*) from sign_in_code_email_bodies", Int::class.java)).isZero()
    }

    // --- the operator alert (owner's decision, 2026-09-15; V130) ----------------

    @Autowired private lateinit var alerts: SignInEmailAlerts

    private fun alertLines() = logs.list.filter { it.level.toString() == "ERROR" && it.formattedMessage.startsWith(SignInEmailOutbox.ALERT_EVENT) }

    private fun flagged(id: Any?) = db.queryForObject("select operator_alert from sign_in_code_emails where id = ?", Boolean::class.java, id)

    /** The code a message for [address] would have carried: it must appear in no alert. */
    private fun codeFor(address: String, requestId: String) = QueuedEmailCodes(props).code("login", address, requestId, props.otp.length)

    /**
     * Every non-sent end for an address that is listed as the worker decides:
     * the provider failing and refusing, a stopped worker, the queue outliving
     * the code, and a server that no longer offers email sign-in. Each raises
     * one alert — an ERROR line named [SignInEmailOutbox.ALERT_EVENT] with no
     * address and no code, and the record's flag — and the operator's count
     * goes up by one.
     */
    @Test
    fun `every way a listed address's message ends unsent raises one operator alert, with no address and no code`() {
        val before = alerts.within().count
        val cases = mutableListOf<Triple<String, String, String>>() // label, address, request id

        fun expectOneAlert(label: String, address: String, requestId: String, result: SignInEmailDrainResult, expected: SignInEmailDrainResult) {
            assertThat(result).describedAs(label).isEqualTo(expected)
            assertThat(flagged(newest()["id"])).describedAs("$label: the record's flag").isTrue()
            val line = alertLines().last().formattedMessage
            assertThat(line).describedAs(label)
                .doesNotContain(address).doesNotContain(address.substringBefore('@'))
                .doesNotContain(codeFor(address, requestId))
            cases += Triple(label, address, requestId)
        }

        sender.faults.always("email", SandboxFault.UNAVAILABLE)
        var id = request(listed[10])
        expectOneAlert("provider down", listed[10], id, outbox.drain(), SignInEmailDrainResult(failed = 1, alerts = 1))
        sender.faults.clear()

        sender.refuses += listed[11]
        id = request(listed[11])
        expectOneAlert("provider refused", listed[11], id, outbox.drain(), SignInEmailDrainResult(failed = 1, alerts = 1))

        id = request(listed[12])
        db.update("update sign_in_code_emails set send_started_at = now(), claimed_until = now() - interval '1 second' where id = ?", newest()["id"])
        expectOneAlert("unconfirmed after a stopped worker", listed[12], id, outbox.drain(), SignInEmailDrainResult(unconfirmed = 1, alerts = 1))

        id = request(listed[13])
        db.update("update sign_in_code_emails set created_at = now() - interval '6 minutes' where id = ?", newest()["id"])
        expectOneAlert("expired in the queue", listed[13], id, outbox.drain(), SignInEmailDrainResult(expired = 1, alerts = 1))

        id = request(listed[14])
        val phoneOnly = props.copy(auth = props.auth.copy(signInChannels = listOf("phone")))
        SignInEmailOutbox(requests, ownerDataSource, sender, calls, SignInChannels(phoneOnly), props).use { restarted ->
            // The restarted worker logs through the same logger.
            expectOneAlert("dropped: email sign-in switched off", listed[14], id, restarted.drain(), SignInEmailDrainResult(dropped = 1, alerts = 1))
        }
        assertThat(newest()["failure"]).isEqualTo(SignInEmailOutbox.CHANNEL_DISABLED)

        assertThat(alertLines()).describedAs("one ERROR line per case").hasSize(cases.size)
        assertThat(alerts.within().count).describedAs("the operator's count").isEqualTo(before + cases.size)
        assertThat(alerts.within().newest).isNotNull()
        assertThat(sender.sent.map { it.first }).describedAs("nothing sent twice or to anyone else")
            .containsExactlyInAnyOrder(listed[10], listed[11])
    }

    /**
     * The intended drops raise nothing: an unlisted address, however its
     * message ends, and a tester taken off the list while their message waited.
     */
    @Test
    fun `an unlisted address's message and a tester taken off the list raise no operator alert`() {
        val before = alerts.within().count

        request(unlisted())
        assertThat(outbox.drain()).isEqualTo(SignInEmailDrainResult(dropped = 1))
        assertThat(flagged(newest()["id"])).isFalse()

        request(unlisted())
        db.update("update sign_in_code_emails set created_at = now() - interval '6 minutes' where id = ?", newest()["id"])
        assertThat(outbox.drain()).isEqualTo(SignInEmailDrainResult(expired = 1))
        assertThat(flagged(newest()["id"])).isFalse()

        request(unlisted())
        db.update("update sign_in_code_emails set send_started_at = now(), claimed_until = now() - interval '1 second' where id = ?", newest()["id"])
        assertThat(outbox.drain()).isEqualTo(SignInEmailDrainResult(unconfirmed = 1))
        assertThat(flagged(newest()["id"])).isFalse()

        request(listed[15])
        val narrowed = props.copy(auth = props.auth.copy(emailAllowlist = listed.filter { it != listed[15] }))
        SignInEmailOutbox(requests, ownerDataSource, sender, calls, SignInChannels(narrowed), props).use {
            assertThat(it.drain()).isEqualTo(SignInEmailDrainResult(dropped = 1))
        }
        assertThat(flagged(newest()["id"])).isFalse()

        // A restore that did not bring a body back leaves no address to ask about: no alert of
        // its own, but counted against its restore, which RestoredMessageAlerts says once (V145).
        request(listed[16])
        db.update("delete from sign_in_code_email_bodies where message_id = ?", newest()["id"])
        assertThat(outbox.drain()).isEqualTo(SignInEmailDrainResult(failed = 1, notRestored = 1))
        assertThat(flagged(newest()["id"])).isFalse()

        assertThat(alertLines()).isEmpty()
        assertThat(logs.list.filter { it.level.toString() == "ERROR" }).isEmpty()
        assertThat(alerts.within().count).isEqualTo(before)
        assertThat(sender.sent).isEmpty()
    }

    /**
     * What a client can reach is the same whether the alert fired or not: the
     * request's answer, the delivery status, and /health, which says nothing of
     * alerts without the operator's token — or with a wrong one.
     */
    @Test
    fun `an alert changes nothing a client sees, and only the operator's token shows the count`() {
        sender.faults.always("email", SandboxFault.UNAVAILABLE)
        val listedAsk = post("/api/v1/auth/otp/email/request", body = mapOf("email" to listed[17]))
        val strangerAsk = post("/api/v1/auth/otp/email/request", body = mapOf("email" to unlisted()))
        val healthBefore = get("/health").body
        assertThat(outbox.drain()).isEqualTo(SignInEmailDrainResult(failed = 1, dropped = 1, alerts = 1))
        sender.faults.clear()

        fun shape(r: org.springframework.http.ResponseEntity<String>) =
            r.statusCode to r.body!!.replace(Regex("\"requestId\":\"[0-9a-f-]{36}\""), "#")
        assertThat(shape(listedAsk)).isEqualTo(shape(strangerAsk))
        // Once both have reached the moment fixed at the request, the two statuses
        // say the same thing, the failed listed one and the dropped stranger's.
        fun status(ask: org.springframework.http.ResponseEntity<String>) =
            get("/api/v1/auth/otp/email/delivery/${ask.json()["requestId"].asText()}")
        val deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos()
        while (status(listedAsk).json()["status"].asText() != "sent" || status(strangerAsk).json()["status"].asText() != "sent") {
            check(System.nanoTime() < deadline) { "the statuses never reached sent" }
            Thread.sleep(200)
        }
        fun statusShape(ask: org.springframework.http.ResponseEntity<String>) = status(ask).let {
            it.statusCode to it.body!!.replace(Regex("\"requestId\":\"[0-9a-f-]{36}\""), "#")
                .replace(Regex("\"resendAfterSeconds\":[0-9]+"), "N")
        }
        assertThat(statusShape(listedAsk)).isEqualTo(statusShape(strangerAsk))

        assertThat(get("/health").body).describedAs("no token: /health as it always was").isEqualTo(healthBefore)
        assertThat(get("/health").json().has("signInEmailNotDelivered")).isFalse()
        val wrong = withHeader("/health", HealthController.OPS_TOKEN_HEADER, "x".repeat(OPS_TOKEN.length))
        assertThat(wrong.body).describedAs("a wrong token: the same").isEqualTo(healthBefore)

        val operator = withHeader("/health", HealthController.OPS_TOKEN_HEADER, OPS_TOKEN).json()
        assertThat(operator["signInEmailNotDelivered"]["lastHour"].asInt()).isGreaterThanOrEqualTo(1)
        assertThat(operator["signInEmailNotDelivered"]["newest"].asText()).isNotEmpty()
        assertThat(operator.toString()).doesNotContain(listed[17]).doesNotContain(listed[17].substringBefore('@'))
    }

    private fun withHeader(path: String, name: String, value: String): org.springframework.http.ResponseEntity<String> {
        val headers = org.springframework.http.HttpHeaders().apply { set(name, value) }
        return org.springframework.web.client.RestTemplate().exchange(
            url(path), org.springframework.http.HttpMethod.GET, org.springframework.http.HttpEntity<Void>(headers), String::class.java,
        )
    }

    @Test
    fun `a message another worker holds is left to it`() {
        request(listed[6])
        val id = newest()["id"] as UUID
        db.update("update sign_in_code_emails set claim_token = gen_random_uuid(), claimed_until = now() + interval '1 minute' where id = ?", id)
        assertThat(outbox.drain()).isEqualTo(SignInEmailDrainResult())
        assertThat(sender.sent).isEmpty()
        db.update("update sign_in_code_emails set claimed_until = now() - interval '1 second' where id = ?", id)
        assertThat(outbox.drain()).isEqualTo(SignInEmailDrainResult(sent = 1))
    }

    @Test
    fun `finished records are kept for the retention period and then deleted`() {
        request(unlisted())
        outbox.drain()
        val id = newest()["id"] as UUID
        db.update("update sign_in_code_emails set finished_at = now() - interval '31 days' where id = ?", id)
        request(unlisted())
        outbox.drain()
        assertThat(db.queryForObject("select count(*) from sign_in_code_emails where id = ?", Int::class.java, id)).isZero()
    }

    /** Nothing the runtime role holds can read where a sign-in email goes or how it ended, or write around the function. */
    @Test
    fun `the runtime role can queue a message and cannot read, change or delete one`() {
        request(unlisted())
        val runtime = JdbcTemplate(runtimeDataSource)
        for (sql in listOf(
            "select count(*) from sign_in_code_emails",
            "select count(*) from sign_in_code_email_bodies",
        )) {
            assertThatThrownBy { runtime.queryForObject(sql, Int::class.java) }.describedAs(sql).hasStackTraceContaining("permission denied")
        }
        for (sql in listOf(
            "update sign_in_code_emails set status = 'sent'",
            "delete from sign_in_code_email_bodies",
            "insert into sign_in_code_emails default values",
        )) {
            assertThatThrownBy { runtime.update(sql) }.describedAs(sql).hasStackTraceContaining("permission denied")
        }
        assertThat(db.queryForObject("select count(*) from sign_in_code_email_bodies", Int::class.java)).isEqualTo(1)
    }

    @Test
    fun `the worker reads the queue on the owner connection, and the request path writes it on the runtime one`() {
        assertThat(JdbcTemplate(runtimeDataSource).queryForObject("select current_user", String::class.java)).isEqualTo("almira_app")
        // A request writes through the definer function; the worker, which could not read on the runtime pool, finds it.
        request(listed[7])
        assertThat(outbox.drain().sent).isEqualTo(1)
    }

    companion object {
        private val run = System.nanoTime()
        private val listed = (0..19).map { "outbox.tester+$it.$run@example.test" }
        private val OPS_TOKEN = "ops-token-for-the-outbox-test-${"0".repeat(24)}"

        @JvmStatic
        @DynamicPropertySource
        fun emailSignIn(registry: DynamicPropertyRegistry) {
            registry.add("almira.auth.sign-in-channels") { "phone,email" }
            registry.add("almira.auth.email-allowlist") { listed.joinToString(",") }
            registry.add("almira.ops.health-token") { OPS_TOKEN }
        }
    }
}
