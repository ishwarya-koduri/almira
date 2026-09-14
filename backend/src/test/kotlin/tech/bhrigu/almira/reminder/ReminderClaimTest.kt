package tech.bhrigu.almira.reminder

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import tech.bhrigu.almira.support.ApiTestBase
import java.time.LocalDate

/**
 * The sweep claims a reminder before it queues anything for it.
 *
 * A notifier added here does what the world can do while a notification is
 * being queued — the person completes the reminder, or another sweep runs —
 * and the tests assert what that leaves behind. Before the claim moved in
 * front, both happened after the notifications were queued and before the
 * reminder was marked: the second sweep queued it again, and the completion was
 * overwritten.
 *
 * Its own file rather than ReminderSweepTest, which belongs to the infra
 * session.
 */
@DisplayName("Reminder sweep: the claim comes before the notification")
@Import(ReminderClaimTest.Interfering::class)
class ReminderClaimTest : ApiTestBase() {

    class InterferingNotifier : Notifier {
        @Volatile var reminderId: String? = null
        @Volatile var duringDelivery: (() -> Unit)? = null
        override val channel = "test"
        override fun deliver(notification: OutboundNotification) {
            if (notification.reminderId?.toString() != reminderId) return
            val hook = duringDelivery ?: return
            duringDelivery = null // once, and not again for a nested sweep
            hook()
        }
    }

    @TestConfiguration
    class Interfering {
        @Bean
        fun interferingNotifier() = InterferingNotifier()
    }

    @Autowired private lateinit var worker: ReminderWorker
    @Autowired private lateinit var interfering: InterferingNotifier

    @AfterEach
    fun clear() {
        interfering.duringDelivery = null
        interfering.reminderId = null
    }

    private fun dueReminder(): Pair<String, String> {
        val token = signIn()
        val householdId = createHousehold(token)["id"].asText()
        val reminder = post(
            "/api/v1/households/$householdId/reminders", token,
            mapOf("title" to "Claim test", "dueDate" to LocalDate.now().minusDays(1).toString(), "leadDays" to 0),
        ).json()
        return householdId to reminder["id"].asText()
    }

    @Test
    fun `a second sweep while the first is queueing notifies nobody again`() {
        val (_, reminderId) = dueReminder()
        interfering.reminderId = reminderId
        interfering.duringDelivery = { worker.sweep() }

        worker.sweep()

        assertThat(
            db.queryForObject("select count(*) from notifications where reminder_id = ?::uuid", Long::class.java, reminderId),
        ).describedAs("in-app notification rows for one reminder swept twice").isEqualTo(1L)
    }

    @Test
    fun `a reminder completed while it is being notified keeps its next date pending`() {
        val (_, reminderId) = dueReminder()
        val next = LocalDate.now().plusMonths(1)
        interfering.reminderId = reminderId
        interfering.duringDelivery = {
            // What ReminderRepository.advance does when a recurring reminder is completed.
            db.update(
                "update reminders set due_date = ?, status = 'pending', snoozed_until = null where id = ?::uuid",
                next, reminderId,
            )
        }

        worker.sweep()

        val row = db.queryForMap("select status, due_date from reminders where id = ?::uuid", reminderId)
        assertThat(row["due_date"].toString()).isEqualTo(next.toString())
        assertThat(row["status"]).describedAs("status of the advanced reminder after the sweep").isEqualTo("pending")
    }
}
