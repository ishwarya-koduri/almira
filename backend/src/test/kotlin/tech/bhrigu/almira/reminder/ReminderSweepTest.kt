package tech.bhrigu.almira.reminder

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tech.bhrigu.almira.support.ApiTestBase
import java.time.LocalDate

/**
 * The hourly sweep actually finds due reminders and notifies someone.
 *
 * It had no test, and it was silently doing nothing: its "system" connection,
 * meant to be the schema owner, was being wired to the runtime pool, where
 * row-level security with no user in the transaction returns zero rows. No
 * error, no log line — `if (due.isEmpty()) return`. Found while building the
 * restore drill (docs/19). Every assertion reads the notification through the
 * owner connection, so it is about what was written, not about visibility.
 */
@DisplayName("Reminder sweep: a due reminder produces a notification")
class ReminderSweepTest : ApiTestBase() {

    @Autowired private lateinit var worker: ReminderWorker

    @Test
    fun `a household reminder that is due notifies its members`() {
        val token = signIn()
        val householdId = createHousehold(token)["id"].asText()
        val reminder = post(
            "/api/v1/households/$householdId/reminders", token,
            mapOf("title" to "Sweep test", "dueDate" to LocalDate.now().minusDays(1).toString(), "leadDays" to 0),
        ).json()
        val reminderId = reminder["id"].asText()

        worker.sweep()

        val notified = db.queryForObject(
            "select count(*) from notifications where reminder_id = ?::uuid", Long::class.java, reminderId,
        )
        val status = db.queryForObject(
            "select status from reminders where id = ?::uuid", String::class.java, reminderId,
        )
        assertThat(notified).describedAs("notifications written for the due reminder").isPositive()
        assertThat(status).isEqualTo("notified")
    }
}
