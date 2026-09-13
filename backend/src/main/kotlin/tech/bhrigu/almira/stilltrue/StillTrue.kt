package tech.bhrigu.almira.stilltrue

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.security.RequestUserContext
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * One record the caller is being asked about (docs/21).
 *
 * [reason] says why now: `period` when it has simply not been confirmed for
 * [periodMonths], `key_date` when a maturity, renewal or end date has passed
 * since the last confirmation — the moment the answer may have changed.
 */
data class StillTrueItem(
    val recordType: String,
    val recordId: UUID,
    /** Not sealed: the caller can see the record, and titles drive every list. */
    val title: String,
    val periodMonths: Int,
    /** The later of creation, the last value refresh and the last confirmation. */
    val lastConfirmedAt: Instant,
    val keyDate: LocalDate?,
    val dueOn: LocalDate,
    val snoozedUntil: LocalDate?,
    val isDue: Boolean,
    val reason: String,
)

data class StillTrueList(val items: List<StillTrueItem>)

/** Named apart from the reminder's snooze body: OpenAPI schema names are global. */
data class StillTrueSnoozeBody(val until: LocalDate)

object StillTrue {
    val RECORD_TYPES = listOf("investment", "liability", "account", "estate_document")

    /** The furthest a question can be put off. Beyond a year it is not a snooze. */
    const val MAX_SNOOZE_DAYS = 366L

    /** What a notification says. A count, never a title or an amount. */
    fun digestTitle(count: Int): String =
        if (count == 1) "Still true? 1 record to check" else "Still true? $count records to check"

    const val DIGEST_TEMPLATE = "still_true.digest"
}

/**
 * "Still true?" for a person, through their own row-level security.
 *
 * Everything is read from `still_true_items`, the same view the sweep reads, so
 * what the list shows and what a notification counted cannot drift apart.
 */
@Service
class StillTrueService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val audit: AuditService,
    private val userContext: RequestUserContext,
) {

    @Transactional(readOnly = true)
    fun due(householdId: UUID): StillTrueList {
        userContext.require()
        households.get(householdId)
        val items = jdbc.query(
            "$SELECT where household_id = :hid and is_due order by effective_due_on, lower(title), record_id",
            mapOf("hid" to householdId),
        ) { rs, _ -> item(rs) }
        return StillTrueList(items)
    }

    /**
     * "Yes, this is still true."
     *
     * Only the people asked may answer — the owners, holders or the person whose
     * will it is. Anyone else gets the same 404 as for a record they cannot see:
     * being able to read a household-visible record is not knowing whether it is
     * still in force.
     */
    @Transactional
    fun confirm(householdId: UUID, recordType: String, recordId: UUID): StillTrueItem {
        val userId = requireWriter()
        households.get(householdId)
        find(householdId, recordType, recordId)

        jdbc.update(
            """
            insert into record_confirmations (record_type, record_id, household_id, confirmed_at, confirmed_by)
            values (:type, :id, :hid, now(), :uid)
            on conflict (record_type, record_id) do update
              set confirmed_at = now(), confirmed_by = :uid,
                  -- An answer ends a snooze: there is nothing left to put off.
                  snoozed_until = null, snoozed_by = null, updated_at = now()
            """.trimIndent(),
            params(householdId, recordType, recordId).addValue("uid", userId),
        )
        // The holding's own "Last confirmed" line and the dashboard's freshness
        // card read this column. One answer should move all three.
        if (recordType == "investment") {
            jdbc.update(
                "update investments set last_verified_at = now() where id = :id and deleted_at is null",
                mapOf("id" to recordId),
            )
        }
        audit.record(
            householdId = householdId, actorUserId = userId, action = "record.confirm_still_true",
            entityType = recordType, entityId = recordId,
        )
        return find(householdId, recordType, recordId)
    }

    /** "Ask me later." Moves the question; says nothing about the answer. */
    @Transactional
    fun snooze(householdId: UUID, recordType: String, recordId: UUID, until: LocalDate): StillTrueItem {
        val userId = requireWriter()
        households.get(householdId)
        val current = find(householdId, recordType, recordId)
        // "Today" where the family lives, which is what due dates are in.
        val today = jdbc.queryForObject(
            "select (now() at time zone time_zone)::date from households where id = :hid",
            mapOf("hid" to householdId), LocalDate::class.java,
        )!!
        if (!until.isAfter(today)) {
            throw ApiException.badRequest("snooze_past", "Choose a date after today.")
        }
        if (until.isAfter(today.plusDays(StillTrue.MAX_SNOOZE_DAYS))) {
            throw ApiException.badRequest(
                "snooze_too_far", "Choose a date within a year. Longer than that isn't a snooze.",
            )
        }
        jdbc.update(
            """
            insert into record_confirmations (record_type, record_id, household_id, snoozed_until, snoozed_by)
            values (:type, :id, :hid, :until, :uid)
            on conflict (record_type, record_id) do update
              set snoozed_until = :until, snoozed_by = :uid, updated_at = now()
            """.trimIndent(),
            params(householdId, recordType, recordId).addValue("until", until).addValue("uid", userId),
        )
        audit.record(
            householdId = householdId, actorUserId = userId, action = "record.snooze_still_true",
            entityType = recordType, entityId = recordId,
            diff = mapOf("from" to current.snoozedUntil?.toString(), "to" to until.toString()),
        )
        return find(householdId, recordType, recordId)
    }

    private fun requireWriter(): UUID {
        val userId = userContext.require()
        // A guest link is read-only in the database's own terms already; saying
        // so here turns a failed statement into a sentence.
        if (userContext.currentGuestShareId() != null) throw ApiException.forbidden()
        return userId
    }

    private fun find(householdId: UUID, recordType: String, recordId: UUID): StillTrueItem {
        if (recordType !in StillTrue.RECORD_TYPES) {
            throw ApiException.badRequest(
                "record_type_invalid", "Choose one of: ${StillTrue.RECORD_TYPES.joinToString()}.",
            )
        }
        return jdbc.query(
            "$SELECT where household_id = :hid and record_type = :type and record_id = :id",
            params(householdId, recordType, recordId),
        ) { rs, _ -> item(rs) }.firstOrNull() ?: throw ApiException.notFound()
    }

    private fun params(householdId: UUID, recordType: String, recordId: UUID) =
        MapSqlParameterSource().addValue("hid", householdId)
            .addValue("type", recordType).addValue("id", recordId)

    private fun item(rs: java.sql.ResultSet): StillTrueItem {
        val keyDate = rs.getObject("key_date", LocalDate::class.java)
        val dueOn = rs.getObject("due_on", LocalDate::class.java)
        return StillTrueItem(
            recordType = rs.getString("record_type"),
            recordId = rs.getObject("record_id", UUID::class.java),
            title = rs.getString("title"),
            periodMonths = rs.getInt("period_months"),
            lastConfirmedAt = rs.getTimestamp("last_confirmed_at").toInstant(),
            keyDate = keyDate,
            dueOn = rs.getObject("effective_due_on", LocalDate::class.java),
            snoozedUntil = rs.getObject("snoozed_until", LocalDate::class.java),
            isDue = rs.getBoolean("is_due"),
            reason = if (keyDate != null && dueOn == keyDate.plusDays(KEY_DATE_GRACE_DAYS)) "key_date" else "period",
        )
    }

    private companion object {
        const val SELECT = """
            select record_type, record_id, title, period_months, last_confirmed_at, key_date,
                   due_on, snoozed_until, effective_due_on, is_due
            from still_true_items
        """

        /** Mirrors `key_date + 7` in V29's still_true_records. */
        const val KEY_DATE_GRACE_DAYS = 7L
    }
}

@RestController
@RequestMapping("/api/v1/households/{householdId}/still-true")
class StillTrueController(private val service: StillTrueService) {

    /** What the caller is being asked about now: due, and theirs to answer. */
    @GetMapping
    fun due(@PathVariable householdId: UUID): StillTrueList = service.due(householdId)

    @PostMapping("/{recordType}/{recordId}/confirm")
    fun confirm(
        @PathVariable householdId: UUID,
        @PathVariable recordType: String,
        @PathVariable recordId: UUID,
    ): StillTrueItem = service.confirm(householdId, recordType, recordId)

    @PostMapping("/{recordType}/{recordId}/snooze")
    fun snooze(
        @PathVariable householdId: UUID,
        @PathVariable recordType: String,
        @PathVariable recordId: UUID,
        @RequestBody body: StillTrueSnoozeBody,
    ): StillTrueItem = service.snooze(householdId, recordType, recordId, body.until)
}
