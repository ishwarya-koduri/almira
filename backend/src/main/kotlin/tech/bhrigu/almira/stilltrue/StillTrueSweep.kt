package tech.bhrigu.almira.stilltrue

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tech.bhrigu.almira.reminder.Notifier
import tech.bhrigu.almira.reminder.OutboundNotification
import java.security.MessageDigest
import java.util.UUID
import javax.sql.DataSource

/** What one run did. Counts only: nothing here is about what the records say. */
data class StillTrueSweepResult(
    val householdsWithDueRecords: Int,
    val peopleNudged: Int,
    val recordsNudged: Int,
)

/**
 * The sweep that brings unconfirmed records back to their owners (docs/21 §5).
 *
 * **Which connection, and why it is named here.** A scheduled job has no user,
 * so on the runtime pool every row-level security predicate denies and every
 * query returns nothing — and a sweep that finds nothing looks exactly like a
 * sweep with nothing to do. So this injects the OWNER data source by explicit
 * qualifier. An unqualified `DataSource` is the @Primary runtime pool, and this
 * class would compile, start, run hourly and never tell anyone anything.
 * StillTrueSweepTest fails if that happens.
 *
 * **Who is told.** The owner connection sees every household, so privacy is
 * enforced in who is asked, not in what the job can read. For each person who
 * can write in a household with something due, one transaction on the owner
 * connection sets `app.user_id` to that person — transaction-local, exactly as
 * [tech.bhrigu.almira.config.RlsTransactionManager] does for a request — and
 * reads `still_true_items`, whose ownership predicates read that setting. The
 * list a person is nudged about is therefore, by construction, the list they
 * would see in the app. Without the setting the view is empty.
 *
 * **Not nagging.** A record is nudged once per question: again only after it is
 * confirmed or snoozed and falls due again, or after thirty days ignored. One
 * message per person per run, carrying a count, never a title or an amount.
 *
 * **Failures.** The nudge is marked, then handed to the notifiers after the
 * transaction commits. Handing over is only queueing: the in-app row and one
 * queued row per channel, each with its own idempotency key. The notification
 * outbox sends them in the background (docs/13 "Interactive and background"),
 * so the sweep never waits on a provider. A channel that fails is recorded on
 * its row and is not re-sent by the next sweep — the record is already marked
 * nudged, and a timed-out text may already have arrived.
 */
@Component
class StillTrueSweep(
    @Qualifier("ownerDataSource") ownerDataSource: DataSource,
    private val notifiers: List<Notifier>,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jdbc = NamedParameterJdbcTemplate(ownerDataSource)
    private val transactions = TransactionTemplate(DataSourceTransactionManager(ownerDataSource))

    /** Hourly, so each household is reached inside its own daytime. */
    @Scheduled(cron = "0 15 * * * *")
    fun scheduled() {
        runCatching { run() }.onFailure {
            log.warn("still-true sweep failed: {}", it.javaClass.simpleName)
        }
    }

    fun run(): StillTrueSweepResult {
        val households = householdsWithDueRecords()
        if (households.isEmpty()) return StillTrueSweepResult(0, 0, 0)

        var people = 0
        var records = 0
        writersIn(households).forEach { (userId, householdId) ->
            val nudged = markForPerson(userId, householdId)
            if (nudged.isNotEmpty()) {
                people++
                records += nudged.size
                deliver(userId, householdId, nudged)
            }
        }
        log.info(
            "still-true sweep: {} household(s) with due records, {} person(s) nudged about {} record(s)",
            households.size, people, records,
        )
        return StillTrueSweepResult(households.size, people, records)
    }

    /**
     * Households with anything due, where it is daytime. Quiet hours matter more
     * once push is live: a question about a will at three in the morning is how
     * a notification permission gets revoked.
     */
    private fun householdsWithDueRecords(): List<UUID> = jdbc.query(
        """
        select distinct r.household_id
        from still_true_records r
        join households h on h.id = r.household_id
        where r.is_due
          and extract(hour from now() at time zone h.time_zone) between :fromHour and :untilHour
        """.trimIndent(),
        mapOf("fromHour" to DAY_STARTS_AT, "untilHour" to DAY_ENDS_BEFORE - 1),
    ) { rs, _ -> rs.getObject("household_id", UUID::class.java) }

    /** Only people who could act on the answer: active, and allowed to write. */
    private fun writersIn(households: List<UUID>): List<Pair<UUID, UUID>> = jdbc.query(
        """
        select hm.user_id, hm.household_id
        from household_memberships hm
        where hm.household_id in (:hids)
          and hm.status = 'active'
          and hm.role in ('owner', 'admin', 'editor')
        order by hm.household_id, hm.user_id
        """.trimIndent(),
        mapOf("hids" to households),
    ) { rs, _ -> rs.getObject("user_id", UUID::class.java) to rs.getObject("household_id", UUID::class.java) }

    /** Marks what this person is about to be told, and returns which records. */
    private fun markForPerson(userId: UUID, householdId: UUID): List<Due> = transactions.execute {
        // is_local => true: cleared by PostgreSQL at commit, so the next borrower
        // of this pooled connection carries nobody's identity.
        jdbc.query(
            "select set_config('app.user_id', :uid, true)",
            mapOf("uid" to userId.toString()),
        ) { _, _ -> }
        val due = jdbc.query(
            """
            select record_type, record_id, effective_due_on::text as due_on, nudged_at::text as nudged_at
            from still_true_items
            where household_id = :hid and nudge_eligible
            """.trimIndent(),
            mapOf("hid" to householdId),
        ) { rs, _ ->
            Due(
                rs.getString("record_type"), rs.getObject("record_id", UUID::class.java),
                rs.getString("due_on"), rs.getString("nudged_at"),
            )
        }
        due.forEach { (type, id) ->
            jdbc.update(
                """
                insert into record_confirmation_nudges (user_id, record_type, record_id, household_id, nudged_at)
                values (:uid, :type, :id, :hid, now())
                on conflict (user_id, record_type, record_id) do update set nudged_at = now()
                """.trimIndent(),
                mapOf("uid" to userId, "type" to type, "id" to id, "hid" to householdId),
            )
        }
        due
    } ?: emptyList()

    private fun deliver(userId: UUID, householdId: UUID, records: List<Due>) {
        val notification = OutboundNotification(
            userId = userId, householdId = householdId, reminderId = null,
            template = StillTrue.DIGEST_TEMPLATE,
            title = StillTrue.digestTitle(records.size),
            body = "Open Almira to confirm them or ask again later.",
            idempotencyKey = digestKey(userId, householdId, records),
        )
        notifiers.forEach { notifier ->
            // One notifier failing must not stop the next, or the next person.
            runCatching { notifier.deliver(notification) }.onFailure {
                log.warn("still-true nudge not delivered on {}: {}", notifier.channel, it.javaClass.simpleName)
            }
        }
    }

    /** A record this person is about to be asked about, and the state that made it a question. */
    private data class Due(val type: String, val id: UUID, val dueOn: String?, val lastNudgedAt: String?)

    /**
     * Names the question, not the run: the records, each with the due date and the
     * previous nudge that made it eligible. A second server sweeping the same state
     * asks nothing new; a record confirmed and due again, or ignored for thirty days,
     * is a new question and a new message. Hashed, so the key names no record.
     */
    private fun digestKey(userId: UUID, householdId: UUID, records: List<Due>): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(records.map { "${it.type}:${it.id}:${it.dueOn}:${it.lastNudgedAt}" }.sorted().joinToString(",").toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "still-true:$householdId:$userId:$digest"
    }

    private companion object {
        const val DAY_STARTS_AT = 9
        const val DAY_ENDS_BEFORE = 20
    }
}
