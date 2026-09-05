package tech.bhrigu.almira.reminder

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * The sweep that makes reminders actually remind.
 *
 * It runs without a user, so it uses the system connection that bypasses
 * row-level security — a scheduled job genuinely operates outside anyone's view
 * of the data, and pretending to be a member to read it would be worse. The
 * compensating rule is that it only ever notifies people who can already see the
 * record: the owners of a holding, the holders of a debt, or every member for a
 * reminder attached to nothing. Privacy is enforced in WHO IS TOLD rather than
 * in what the job can read.
 *
 * "Due" is evaluated in the household's own time zone. A family in Kakinada
 * should not be told their premium is due tomorrow because a server in another
 * hemisphere has already turned the page.
 */
@Component
class ReminderWorker(
    @Qualifier("systemJdbcBypassingRls") private val system: NamedParameterJdbcTemplate,
    private val notifiers: List<Notifier>,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Hourly: often enough to be timely, rare enough to be cheap. */
    @Scheduled(cron = "0 5 * * * *")
    @Transactional
    fun sweep() {
        val due = findDue()
        if (due.isEmpty()) return

        var queued = 0
        due.forEach { reminder ->
            recipientsFor(reminder).forEach { userId ->
                queue(userId, reminder)
                queued++
            }
            system.update(
                """
                update reminders set status = 'notified', last_notified_at = now()
                where id = :id
                """.trimIndent(),
                mapOf("id" to reminder.id),
            )
        }
        log.info("reminder sweep: {} due, {} notifications queued", due.size, queued)
    }

    private data class DueReminder(
        val id: UUID,
        val householdId: UUID,
        val investmentId: UUID?,
        val liabilityId: UUID?,
        val kind: String,
        val title: String,
        val dueDate: java.time.LocalDate,
    )

    private fun findDue(): List<DueReminder> = system.query(
        """
        select r.id, r.household_id, r.investment_id, r.liability_id,
               r.kind, r.title, r.due_date
        from reminders r
        join households h on h.id = r.household_id
        where r.deleted_at is null
          and r.status = 'pending'
          -- "Today" in the household's own zone, not the server's.
          and (coalesce(r.snoozed_until, r.due_date) - r.lead_days)
              <= (now() at time zone h.time_zone)::date
        order by r.due_date
        limit 500
        """.trimIndent(),
        emptyMap<String, Any>(),
    ) { rs, _ ->
        DueReminder(
            id = rs.getObject("id", UUID::class.java),
            householdId = rs.getObject("household_id", UUID::class.java),
            investmentId = rs.getObject("investment_id", UUID::class.java),
            liabilityId = rs.getObject("liability_id", UUID::class.java),
            kind = rs.getString("kind"),
            title = rs.getString("title"),
            dueDate = rs.getDate("due_date").toLocalDate(),
        )
    }

    /**
     * Only people who can already see the record. An owner or holder always can,
     * by definition — which is why this list can be computed without consulting
     * the visibility rules and still never tell anyone something new.
     */
    private fun recipientsFor(reminder: DueReminder): List<UUID> = when {
        reminder.investmentId != null -> system.query(
            """
            select distinct m.user_id from investment_ownerships o
              join members m on m.id = o.member_id
            where o.investment_id = :id and m.user_id is not null and m.deleted_at is null
            """.trimIndent(),
            mapOf("id" to reminder.investmentId),
        ) { rs, _ -> rs.getObject("user_id", UUID::class.java) }

        reminder.liabilityId != null -> system.query(
            """
            select distinct m.user_id from liability_holders h
              join members m on m.id = h.member_id
            where h.liability_id = :id and m.user_id is not null and m.deleted_at is null
            """.trimIndent(),
            mapOf("id" to reminder.liabilityId),
        ) { rs, _ -> rs.getObject("user_id", UUID::class.java) }

        else -> system.query(
            """
            select hm.user_id from household_memberships hm
            where hm.household_id = :hid and hm.status = 'active'
            """.trimIndent(),
            mapOf("hid" to reminder.householdId),
        ) { rs, _ -> rs.getObject("user_id", UUID::class.java) }
    }

    private fun queue(userId: UUID, reminder: DueReminder) {
        val payload = mapOf(
            "reminderId" to reminder.id.toString(),
            "kind" to reminder.kind,
            "title" to reminder.title,
            "dueDate" to reminder.dueDate.toString(),
        )
        system.update(
            """
            insert into notifications
              (user_id, household_id, reminder_id, channel, template, payload, status, sent_at)
            values (:userId, :hid, :reminderId, :channel, :template,
                    cast(:payload as jsonb), 'sent', now())
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("hid", reminder.householdId)
                .addValue("reminderId", reminder.id)
                .addValue("channel", notifiers.firstOrNull()?.channel ?: "in_app")
                .addValue("template", "reminder.${reminder.kind}")
                .addValue("payload", mapper.writeValueAsString(payload)),
        )
        notifiers.forEach {
            it.deliver(
                OutboundNotification(
                    userId = userId, householdId = reminder.householdId,
                    reminderId = reminder.id, template = "reminder.${reminder.kind}",
                    title = reminder.title,
                    body = "Due ${reminder.dueDate}",
                ),
            )
        }
    }
}
