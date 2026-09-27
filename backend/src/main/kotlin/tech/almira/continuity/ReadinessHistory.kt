package tech.almira.continuity

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.almira.security.RequestUserContext
import java.time.LocalDate
import java.util.UUID

/**
 * How readiness has moved since about a month ago (docs/22 §1, "Movement").
 *
 * [saferCount] is the net number of things done since [comparedWith]: a nominee
 * recorded, a scan attached, a location sealed, someone named. Net, so that
 * adding one nominee while a scan goes missing is not "1 thing safer". Never
 * negative; [fewerDone] says when the count went the other way, so the client
 * can say so plainly instead of celebrating.
 */
data class ReadinessMovement(
    val comparedWith: LocalDate,
    val previousScore: Int?,
    val saferCount: Int,
    val fewerDone: Int,
)

/**
 * The small memory behind [ReadinessMovement]: one row a day, per person, per
 * household, holding counts and nothing else (V80).
 *
 * Written when a person reads their own readiness, because that is the only
 * time the numbers exist; a background job would have to compute readiness as
 * each person, which is exactly the kind of identity-switching the privacy model
 * keeps out of the server. The consequence is honest: no visit, no row, and the
 * comparison is with the last day someone looked, at least 28 days ago.
 */
@Service
class ReadinessHistoryService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val userContext: RequestUserContext,
    private val mapper: ObjectMapper,
) {

    @Transactional
    fun record(householdId: UUID, readiness: HandoverReadiness): ReadinessMovement? {
        val userId = userContext.require()
        val params = MapSqlParameterSource()
            .addValue("hid", householdId)
            .addValue("uid", userId)
            .addValue("score", readiness.score)
            .addValue("checks", mapper.writeValueAsString(countsOf(readiness)))

        val today = jdbc.queryForObject(
            "select (now() at time zone h.time_zone)::date from households h where h.id = :hid",
            params, LocalDate::class.java,
        ) ?: LocalDate.now()
        params.addValue("today", today)
            .addValue("baseline", today.minusDays(BASELINE_DAYS))
            .addValue("oldest", today.minusDays(KEEP_DAYS))

        val previous = jdbc.query(
            """
            select taken_on, score, checks::text as checks from readiness_snapshots
            where household_id = :hid and user_id = :uid and taken_on <= :baseline
            order by taken_on desc limit 1
            """.trimIndent(),
            params,
        ) { rs, _ ->
            Snapshot(
                takenOn = rs.getDate("taken_on").toLocalDate(),
                score = rs.getObject("score")?.let { rs.getInt("score") },
                done = doneIn(rs.getString("checks")),
            )
        }.firstOrNull()

        jdbc.update(
            """
            insert into readiness_snapshots (household_id, user_id, taken_on, score, checks)
            values (:hid, :uid, :today, :score, cast(:checks as jsonb))
            on conflict (household_id, user_id, taken_on)
              do update set score = excluded.score, checks = excluded.checks, updated_at = now()
            """.trimIndent(),
            params,
        )
        jdbc.update(
            "delete from readiness_snapshots where household_id = :hid and user_id = :uid and taken_on < :oldest",
            params,
        )

        return previous?.let { movement(it, readiness) }
    }

    private data class Snapshot(val takenOn: LocalDate, val score: Int?, val done: Int)

    private fun doneIn(json: String): Int = runCatching {
        mapper.readTree(json).fields().asSequence().sumOf { (_, pair) -> pair.path(0).asInt(0) }
    }.getOrDefault(0)

    companion object {
        /** "Last month": the latest day on or before four weeks ago. */
        const val BASELINE_DAYS = 28L
        const val KEEP_DAYS = 400L

        fun countsOf(readiness: HandoverReadiness): Map<String, List<Int>> =
            readiness.checks.associate { it.code to listOf(it.done, it.applicable) }

        internal fun movement(then: Int, now: Int, comparedWith: LocalDate, previousScore: Int?) =
            ReadinessMovement(
                comparedWith = comparedWith,
                previousScore = previousScore,
                saferCount = maxOf(0, now - then),
                fewerDone = maxOf(0, then - now),
            )
    }

    private fun movement(previous: Snapshot, readiness: HandoverReadiness) = movement(
        then = previous.done,
        now = readiness.checks.sumOf { it.done },
        comparedWith = previous.takenOn,
        previousScore = previous.score,
    )
}
