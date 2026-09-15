package tech.bhrigu.almira.provider

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import tech.bhrigu.almira.reminder.OutboundNotification
import tech.bhrigu.almira.support.ApiTestBase
import java.time.Duration
import java.util.UUID

/**
 * Owner's decision, 2026-09-15: *alert on body_not_restored, once per restore
 * with a count, not per message* (V145; docs/13 "After a restore").
 *
 * The messages are made the way a restore makes them — queued rows whose bodies
 * are gone — and drained by the application's own outbox, so these also prove
 * the worker is wired to count them.
 */
@DisplayName("A restore that loses queued messages alerts the operator once, with a count")
class RestoredMessageAlertsTest : ApiTestBase() {

    @Autowired private lateinit var outbox: NotificationOutbox
    @Autowired private lateinit var notifier: RecordingNotifier
    @Autowired private lateinit var alerts: RestoredMessageAlerts

    private lateinit var owner: String
    private lateinit var householdId: String
    private lateinit var ownerUserId: String

    private val logs = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(RestoredMessageAlerts::class.java) as Logger

    @BeforeEach
    fun setUp() {
        outbox.drain()
        db.update("delete from restore_events")
        owner = signIn()
        consentToMessages(owner)
        val household = createHousehold(owner, "Koduri", "private", "Ishwarya")
        householdId = household.path("id").asText()
        ownerUserId = db.queryForObject(
            "select user_id::text from members where id = ?::uuid", String::class.java, household.path("myMemberId").asText(),
        )!!
        logs.list.clear()
        logs.start()
        logger.addAppender(logs)
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(logs)
        db.update("delete from restore_events")
    }

    private val body = "Your FD at SBI matures on Thursday."
    private val title = "FD matures soon"

    /** Queues one message on every off-app channel, then removes the bodies, as a restore does. */
    private fun loseQueuedMessages(): Int {
        val key = "restore-test:${UUID.randomUUID()}"
        outbox.whilePaused {
            notifier.deliver(
                OutboundNotification(
                    userId = UUID.fromString(ownerUserId), householdId = UUID.fromString(householdId), reminderId = null,
                    template = "reminder.maturity", title = title, body = body, idempotencyKey = key,
                ),
            )
        }
        return db.update(
            """
            delete from outbound_message_bodies b using outbound_messages o
             where o.id = b.message_id and o.idempotency_key like ? and o.channel <> 'in_app' and o.status = 'queued'
            """.trimIndent(),
            "$key:%",
        )
    }

    private fun events() = db.queryForList(
        "select id, recorded_by, backup_name, not_restored, alerted_at, alerted_count from restore_events order by restored_at",
    )

    private fun errorLines() = logs.list.filter { it.level == Level.ERROR }.map { it.formattedMessage }

    @Test
    fun `a recorded restore collects its lost messages and is alerted once, with the count, and never again`() {
        db.update("insert into restore_events (recorded_by, backup_name) values ('restore.sh', 'almira-20260915T101500Z')")
        val lost = loseQueuedMessages()
        assertThat(lost).describedAs("the channels this household's owner said yes to").isGreaterThanOrEqualTo(2)

        val drained = outbox.drain()
        assertThat(drained.notRestored).isEqualTo(lost)
        assertThat(events()).hasSize(1).first()
            .extracting("recorded_by", "not_restored", "alerted_at").containsExactly("restore.sh", lost, null)

        assertThat(alerts.raiseDue()).describedAs("not yet: the last one was found under a minute ago").isEmpty()
        assertThat(errorLines()).isEmpty()

        val raised = alerts.raiseDue(settle = Duration.ZERO)
        assertThat(raised).hasSize(1).first().extracting("recordedBy", "backupName", "count")
            .containsExactly("restore.sh", "almira-20260915T101500Z", lost)
        assertThat(errorLines()).hasSize(1).first().satisfies({ line ->
            assertThat(line).startsWith(RestoredMessageAlerts.ALERT_EVENT).contains("$lost queued message(s)")
                .doesNotContain(body).doesNotContain(title).doesNotContain(ownerUserId).doesNotContain(householdId)
        })

        assertThat(alerts.raiseDue(settle = Duration.ZERO)).describedAs("once per restore").isEmpty()

        // Found later (the other worker, say): counted, shown, not alerted a second time.
        val more = loseQueuedMessages()
        outbox.drain()
        assertThat(alerts.raiseDue(settle = Duration.ZERO)).isEmpty()
        assertThat(errorLines()).hasSize(1)
        assertThat(events()).hasSize(1).first()
            .extracting("not_restored", "alerted_count").containsExactly(lost + more, lost)
        assertThat(alerts.latest()).isNotNull.extracting("notRestored", "alertedCount").containsExactly(lost + more, lost)
    }

    @Test
    fun `a restore nobody recorded is detected once, however many times its losses are found`() {
        loseQueuedMessages()
        outbox.drain()
        loseQueuedMessages()
        outbox.drain()
        alerts.noticed(1)

        assertThat(events()).describedAs("one detected restore, not one per finding").hasSize(1).first()
            .extracting("recorded_by", "backup_name").containsExactly("detected", null)
        val total = events().single()["not_restored"] as Int
        assertThat(alerts.raiseDue(settle = Duration.ZERO)).hasSize(1).first().extracting("count").isEqualTo(total)
        assertThat(errorLines()).hasSize(1).first().satisfies({ assertThat(it).contains("detected") })
    }

    @Test
    fun `a restore from more than a day ago is not the one a new loss belongs to`() {
        db.update(
            """
            insert into restore_events (recorded_by, restored_at, not_restored, first_noticed_at, last_noticed_at, alerted_at, alerted_count)
            values ('restore.sh', now() - interval '3 days', 4, now() - interval '3 days', now() - interval '3 days', now() - interval '3 days', 4)
            """.trimIndent(),
        )
        alerts.noticed(2)
        assertThat(events()).hasSize(2).last().extracting("recorded_by", "not_restored").containsExactly("detected", 2)
        assertThat(alerts.raiseDue(settle = Duration.ZERO)).describedAs("the new restore is alerted; the old one is not again")
            .hasSize(1).first().extracting("count").isEqualTo(2)
    }

    @Test
    fun `nothing lost, nothing recorded and nothing raised`() {
        alerts.noticed(0)
        outbox.drain()
        assertThat(events()).isEmpty()
        assertThat(alerts.raiseDue(settle = Duration.ZERO)).isEmpty()
        assertThat(alerts.latest()).isNull()
        assertThat(errorLines()).isEmpty()
    }

    @Test
    fun `a sign-in email that lost its body counts against the same restore`() {
        db.update("insert into restore_events (recorded_by) values ('restore.sh')")
        val lost = loseQueuedMessages()
        outbox.drain()
        // A queued sign-in email with no body, as a restore leaves one; its worker counts it too.
        db.update("insert into sign_in_code_emails (status) values ('queued')")
        signInEmails.drain()
        assertThat(events()).hasSize(1).first().extracting("not_restored").isEqualTo(lost + 1)
        assertThat(db.queryForObject("select count(*) from sign_in_code_emails where failure = 'body_not_restored'", Int::class.java))
            .isGreaterThanOrEqualTo(1)
    }

    @Autowired private lateinit var signInEmails: tech.bhrigu.almira.auth.SignInEmailOutbox
}
