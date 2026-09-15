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
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tech.bhrigu.almira.config.AlmiraProperties
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
            assertThat(restarted.drain()).isEqualTo(SignInEmailDrainResult(dropped = 1))
        } finally {
            restarted.close()
        }
        assertThat(sender.sent).isEmpty()
        assertThat(newest()["status"]).isEqualTo("dropped")

        // And a server that no longer offers email sign-in drops everything.
        request(listed[2])
        val phoneOnly = props.copy(auth = props.auth.copy(signInChannels = listOf("phone")))
        SignInEmailOutbox(requests, ownerDataSource, sender, calls, SignInChannels(phoneOnly), props).use {
            assertThat(it.drain()).isEqualTo(SignInEmailDrainResult(dropped = 1))
        }
        assertThat(sender.sent).isEmpty()
    }

    @Test
    fun `a provider refusing a listed address is recorded and logged for the operator, with the address masked`() {
        sender.refuses += listed[3]
        request(listed[3])
        assertThat(outbox.drain()).isEqualTo(SignInEmailDrainResult(failed = 1))
        assertThat(newest()["status"]).isEqualTo("failed")
        assertThat(newest()["failure"]).isEqualTo("rejected")
        assertThat(newest()["body"]).isNull()
        val errors = logs.list.filter { it.level.toString() == "ERROR" }.map { it.formattedMessage }
        assertThat(errors).singleElement().satisfies({ line ->
            assertThat(line).startsWith("SIGN-IN EMAIL REFUSED").doesNotContain(listed[3])
        })
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
            assertThat(outbox.drain()).describedAs(fault.name).isEqualTo(SignInEmailDrainResult(failed = 1))
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

        assertThat(outbox.drain()).isEqualTo(SignInEmailDrainResult(unconfirmed = 1, expired = 1))
        assertThat(sender.sent).isEmpty()
        val byId = records().associateBy { it["id"] }
        assertThat(byId.getValue(started)["status"]).isEqualTo("unconfirmed")
        assertThat(byId.getValue(old)["status"]).isEqualTo("expired")
        assertThat(db.queryForObject("select count(*) from sign_in_code_email_bodies", Int::class.java)).isZero()
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
        private val listed = (0..9).map { "outbox.tester+$it.$run@example.test" }

        @JvmStatic
        @DynamicPropertySource
        fun emailSignIn(registry: DynamicPropertyRegistry) {
            registry.add("almira.auth.sign-in-channels") { "phone,email" }
            registry.add("almira.auth.email-allowlist") { listed.joinToString(",") }
        }
    }
}
