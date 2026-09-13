package tech.bhrigu.almira.provider

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import tech.bhrigu.almira.config.AlmiraProperties
import tech.bhrigu.almira.reminder.OutboundNotification
import tech.bhrigu.almira.reminder.ReminderWorker
import tech.bhrigu.almira.support.ApiTestBase
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * Notifications are background work (docs/13 "Interactive and background"):
 * queued by whatever caused them, sent by a worker, never twice per key.
 *
 * Through the real wiring — the real notifier, the real sandbox channels and
 * their fault switch, the real database — because every claim here is about
 * what really happens between a request, a table and a provider.
 */
@DisplayName("Notification outbox")
class NotificationOutboxTest : ApiTestBase() {

    @Autowired private lateinit var outbox: NotificationOutbox
    @Autowired private lateinit var faults: SandboxFaults
    @Autowired private lateinit var notifier: RecordingNotifier
    @Autowired private lateinit var channels: List<ChannelSender>
    @Autowired private lateinit var sms: SandboxSmsSender
    @Autowired private lateinit var email: SandboxEmailSender
    @Autowired private lateinit var push: SandboxPushSender
    @Autowired private lateinit var calls: ProviderCalls
    @Autowired private lateinit var props: AlmiraProperties
    @Autowired private lateinit var reminders: ReminderWorker
    @Autowired private lateinit var runtimeDataSource: DataSource

    @Autowired
    @Qualifier("ownerDataSource")
    private lateinit var ownerDataSource: DataSource

    private lateinit var owner: String
    private lateinit var householdId: String
    private lateinit var ownerUserId: String

    @BeforeEach
    fun setUp() {
        outbox.drain()
        faults.clear()
        owner = signIn()
        val household = createHousehold(owner, "Koduri", "private", "Ishwarya")
        householdId = household.path("id").asText()
        ownerUserId = db.queryForObject(
            "select user_id::text from members where id = ?::uuid", String::class.java, household.path("myMemberId").asText(),
        )!!
    }

    @AfterEach
    fun tearDown() {
        faults.clear()
        outbox.drain()
    }

    private fun nameEmergencyContact() = post(
        "/api/v1/households/$householdId/emergency/contacts", owner,
        mapOf(
            "trustedMemberId" to post(
                "/api/v1/households/$householdId/members", owner,
                mapOf("displayName" to "Meera", "relationship" to "sibling"),
            ).json().path("id").asText().also { joinHousehold(owner, householdId, it, signIn()) },
            "waitDays" to 14,
        ),
    )

    private data class Row(
        val id: String, val channel: String, val status: String, val failure: String?,
        val attempts: Int, val key: String?,
    )

    private fun rows(): List<Row> = db.query(
        """
        select id::text, channel, status, failure, attempts, idempotency_key from outbound_messages
        where household_id = ?::uuid and template = 'emergency.named' order by channel
        """.trimIndent(),
        { rs, _ ->
            Row(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getString(6),
            )
        },
        householdId,
    )

    private fun byChannel() = rows().associateBy { it.channel }

    private fun bodiesFor(ids: List<String>) = ids.sumOf {
        db.queryForObject("select count(*) from outbound_message_bodies where message_id = ?::uuid", Int::class.java, it)!!
    }

    @Test
    fun `a request that notifies returns without waiting for a channel that hangs`() {
        // Sent inline, this hangs the request for push's whole 10s timeout.
        faults.script("push", SandboxFault.HANG)

        val started = System.nanoTime()
        val named = nameEmergencyContact()
        val elapsed = Duration.ofNanos(System.nanoTime() - started)

        assertThat(named.statusCode.is2xxSuccessful).describedAs(named.body).isTrue()
        assertThat(elapsed).describedAs("the request queues; it does not wait on the provider").isLessThan(Duration.ofSeconds(3))
        assertThat(byChannel().keys).containsExactlyInAnyOrder("in_app", "sms", "email", "push")

        // And the worker does send it, and records the hang as what it was.
        outbox.drain()
        val after = byChannel()
        assertThat(after["in_app"]!!.status).isEqualTo("sent")
        assertThat(after["sms"]!!.status).isEqualTo("sent")
        assertThat(after["email"]!!.status).isEqualTo("sent")
        assertThat(after["push"]!!).extracting("status", "failure", "attempts")
            .describedAs("push cannot de-duplicate, so a timeout is not retried")
            .containsExactly("failed", "timeout", 1)
    }

    @Test
    fun `every queued message has its own key, the worker hands that key to the provider, and the body goes`() {
        nameEmergencyContact()
        val queued = rows().filter { it.channel != "in_app" }
        assertThat(queued.map { it.key }).doesNotContainNull().doesNotHaveDuplicates()
        queued.forEach { assertThat(it.key).endsWith(":${it.channel}") }
        assertThat(rows().single { it.channel == "in_app" }.key).isNull()

        // Woken by the commit, it may already have run; draining waits for it either way.
        outbox.drain()

        val after = byChannel()
        val senders = mapOf("sms" to sms.deliveries, "email" to email.deliveries, "push" to push.deliveries)
        senders.forEach { (channel, deliveries) ->
            assertThat(after[channel]!!.status).describedAs("$channel: the worker must find the queued row").isEqualTo("sent")
            assertThat(deliveries.times(after[channel]!!.key!!))
                .describedAs("$channel: the provider saw the row's own key, once").isEqualTo(1)
        }
        assertThat(bodiesFor(queued.map { it.id })).describedAs("a body is kept only while it is on its way").isZero()
    }

    @Test
    fun `the worker is on the owner pool, and the runtime pool would find nothing`() {
        assertThat(outbox.poolName()).isEqualTo("almira-owner")
        assertThat(outbox.databaseRole())
            .isEqualTo(db.queryForObject("select current_user", String::class.java))
            .isNotEqualTo(JdbcTemplate(runtimeDataSource).queryForObject("select current_user", String::class.java))

        val misWired = NotificationOutbox(runtimeDataSource, channels, calls, props)
        outbox.whilePaused {
            nameEmergencyContact()
            val queued = rows().filter { it.channel != "in_app" }
            assertThat(queued.map { it.status }).containsOnly("queued").hasSize(3)

            val result = misWired.drain()
            assertThat(result.touched).describedAs("row-level security shows a user-less worker nothing").isZero()
            assertThat(rows().filter { it.channel != "in_app" }.map { it.status }).containsOnly("queued")
            assertThat(JdbcTemplate(runtimeDataSource).queryForObject("select count(*) from outbound_message_bodies", Int::class.java))
                .describedAs("and the runtime role cannot read a queued body at all").isZero()
            assertThat(bodiesFor(queued.map { it.id })).isEqualTo(3)
        }
        misWired.close()
        outbox.drain()
        assertThat(rows().filter { it.channel != "in_app" }.map { it.status }).containsOnly("sent")
    }

    /** The worker that died between the provider accepting a message and writing that down. */
    private class Crash : RuntimeException("worker stopped")

    @Test
    fun `a send cut off before it was recorded is never sent twice`() {
        val dying = NotificationOutbox(
            ownerDataSource, channels, calls, props.copy(outbox = props.outbox.copy(batchSize = 1)),
        ).apply { afterSendBeforeRecord = { throw Crash() } }

        outbox.whilePaused {
            nameEmergencyContact()
            // One row per drain: each is sent, and then the worker dies.
            repeat(3) { assertThat(runCatching { dying.drain() }.exceptionOrNull()).isInstanceOf(Crash::class.java) }
        }
        dying.close()

        val crashed = byChannel()
        val deliveries = mapOf("sms" to sms.deliveries, "email" to email.deliveries, "push" to push.deliveries)
        deliveries.forEach { (channel, d) ->
            assertThat(crashed[channel]!!.status).describedAs("$channel: nothing recorded").isEqualTo("queued")
            assertThat(d.times(crashed[channel]!!.key!!)).describedAs("$channel: but it went").isEqualTo(1)
        }
        // The dead worker's leases run out.
        db.update(
            "update outbound_messages set claimed_until = now() - interval '1 second' where household_id = ?::uuid and status = 'queued'",
            householdId,
        )

        // The poll may get there first; draining waits for it either way.
        outbox.drain()

        val after = byChannel()
        assertThat(push.deliveries.times(after["push"]!!.key!!))
            .describedAs("push cannot de-duplicate: at-most-once, so it is not called again").isEqualTo(1)
        assertThat(after["push"]!!).extracting("status", "failure")
            .describedAs("recorded as unconfirmed — it may have arrived").containsExactly("failed", "timeout")

        for (channel in listOf("sms", "email")) {
            assertThat(after[channel]!!).extracting("status", "attempts")
                .describedAs("$channel honours keys: at-least-once, sent again with the same key")
                .containsExactly("sent", 2)
            assertThat(deliveries.getValue(channel).times(after[channel]!!.key!!))
                .describedAs("$channel: and the provider delivered it once").isEqualTo(1)
        }
    }

    @Test
    fun `the same logical message asked for twice is queued once per channel`() {
        val message = OutboundNotification(
            userId = UUID.fromString(ownerUserId), householdId = UUID.fromString(householdId), reminderId = null,
            template = "outbox.test", title = "Twice", body = "once", idempotencyKey = "outbox-test:${UUID.randomUUID()}",
        )
        notifier.deliver(message)
        notifier.deliver(message)

        val perChannel = db.queryForList(
            """
            select channel, count(*) as n from outbound_messages
            where household_id = ?::uuid and template = 'outbox.test' and channel <> 'in_app' group by channel
            """.trimIndent(),
            householdId,
        ).associate { it["channel"] as String to (it["n"] as Number).toInt() }
        assertThat(perChannel).containsOnlyKeys("sms", "email", "push").allSatisfy { _, n -> assertThat(n).isEqualTo(1) }
    }

    @Test
    fun `a reminder swept again before its status changed is not queued again`() {
        val reminderId = post(
            "/api/v1/households/$householdId/reminders", owner,
            mapOf("title" to "Premium", "dueDate" to LocalDate.now().minusDays(1).toString(), "leadDays" to 0),
        ).json().path("id").asText()

        reminders.sweep()
        // As if the sweep had died before marking it notified, or a second server swept too.
        db.update("update reminders set status = 'pending' where id = ?::uuid", reminderId)
        reminders.sweep()

        val sms = db.queryForObject(
            """
            select count(*) from outbound_messages
            where household_id = ?::uuid and template like 'reminder.%' and channel = 'sms'
            """.trimIndent(),
            Int::class.java, householdId,
        )
        assertThat(sms).describedAs("one reminder, one date, one person: one text").isEqualTo(1)
    }
}
