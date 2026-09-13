package tech.bhrigu.almira.reports

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.household.HouseholdService
import java.time.LocalDate
import java.util.UUID

data class CompletenessCheck(
    val code: String,
    /** What is missing, said plainly. */
    val label: String,
    /** What to do about it, as a sentence, not a scold. */
    val fix: String,
    val done: Int,
    val outstanding: Int,
    /** The records to offer a one-tap fix for. Capped, because a list of 400 helps nobody. */
    val investmentIds: List<UUID>,
    val weight: Int,
)

data class Completeness(
    /**
     * Rounded down, so it is 100 only when nothing is missing. When [scoreEarned]
     * is false this is 0 and means nothing: v1 froze `score` as a required
     * integer, so "no number" is said by [scoreEarned], not by a null here.
     */
    val score: Int,
    /**
     * False when the records have not earned a number (nothing recorded that
     * you can see). Clients show [scoreExplanation] instead of a percentage.
     * docs/18 §6: never show a number the data did not earn.
     */
    val scoreEarned: Boolean,
    /** Why there is no number. Present only when [scoreEarned] is false. */
    val scoreExplanation: String?,
    val recordCount: Int,
    val scoreLabel: String,
    val checks: List<CompletenessCheck>,
    /** The single most useful next thing, or null when there is nothing to fix. */
    val nextStep: String?,
    val note: String,
)

/**
 * The completeness score, as a pure function so its edges can be tested
 * without a thousand holdings (known-issues 19, docs/22 §1).
 */
object CompletenessScore {

    /**
     * floor(100 × earned ÷ possible), weighted, in integer arithmetic. Rounding
     * down is the guarantee: with any item outstanding, earned < possible, so
     * the result is at most 99. Rounding to nearest showed 100 with one missing
     * nominee among a few hundred holdings.
     *
     * Null when nothing can be scored: no number is better than 100 or 0.
     */
    fun of(checks: List<CompletenessCheck>): Int? {
        val earned = checks.sumOf { it.done.toLong() * it.weight }
        val possible = checks.sumOf { (it.done.toLong() + it.outstanding) * it.weight }
        if (possible == 0L) return null
        return (earned * 100 / possible).toInt()
    }
}

/**
 * How usable this registry would be to someone who did not build it.
 *
 * The score is not a rating of the person — it is a measure of whether the
 * record would survive being read by a family member who has never seen it
 * (docs/01 §10, docs/10 Epic 2.5). So it counts the four things that decide
 * that: does anyone inherit it, can it be found, is there proof, and is the
 * value still believable.
 *
 * Everything is computed under the caller's own RLS, so two members can see
 * different scores for the same household and both are right.
 */
@Service
class CompletenessService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
) {

    @Transactional(readOnly = true)
    fun report(householdId: UUID): Completeness {
        households.get(householdId)
        val rows = load(householdId)

        if (rows.isEmpty()) {
            // Not 100: nothing recorded is not complete. Not 0 either, as a
            // claim: a new user has not failed at anything. No number at all.
            return Completeness(
                score = 0, scoreEarned = false,
                scoreExplanation = NOTHING_TO_SCORE,
                recordCount = 0,
                scoreLabel = "Nothing to check yet",
                checks = emptyList(),
                nextStep = "Add your first holding and this will tell you what's missing.",
                note = NOTE,
            )
        }

        val checks = listOf(
            check(
                "nominee", "No nominee recorded",
                "Add who should receive this. A nominee is who the bank pays; " +
                    "it is not the same as who inherits it.",
                weight = 3, rows,
            ) { it.hasNominee },
            check(
                "proof", "No document attached",
                "Attach the certificate, receipt or statement, so nobody has to " +
                    "hunt for the paper later.",
                weight = 2, rows,
            ) { it.hasDocument },
            check(
                "linkage", "Not linked to an account",
                "Say which account this is funded from or held in.",
                weight = 2, rows.filter { it.expectsAccount },
            ) { it.hasAccount },
            check(
                "value", "Value not confirmed in the last year",
                "Add a current value, so the totals mean something.",
                weight = 2, rows,
            ) { it.valueIsFresh },
            check(
                "continuity", "Left out of the family summary",
                "Include it, or leave it out on purpose — either is fine, as long " +
                    "as it is a decision.",
                weight = 1, rows,
            ) { it.inContinuity },
        ).filter { it.done + it.outstanding > 0 }

        val score = CompletenessScore.of(checks)
            ?: return Completeness(
                score = 0, scoreEarned = false,
                scoreExplanation = NOTHING_TO_SCORE,
                recordCount = rows.size,
                scoreLabel = "Nothing to check yet",
                checks = checks,
                nextStep = null,
                note = NOTE,
            )

        // The most-weighted gap, not the longest list: one missing nominee
        // matters more than five missing links.
        val worst = checks.filter { it.outstanding > 0 }.maxByOrNull { it.outstanding * it.weight }

        return Completeness(
            score = score,
            scoreEarned = true,
            scoreExplanation = null,
            recordCount = rows.size,
            scoreLabel = when {
                score >= 90 -> "Your family could pick this up tomorrow"
                score >= 70 -> "Mostly there"
                score >= 40 -> "The bones are in place"
                else -> "Worth a quiet hour"
            },
            checks = checks,
            nextStep = worst?.let { "${it.outstanding} ${if (it.outstanding == 1) "record" else "records"}: ${it.fix}" },
            note = NOTE,
        )
    }

    private fun check(
        code: String,
        label: String,
        fix: String,
        weight: Int,
        rows: List<Row>,
        satisfied: (Row) -> Boolean,
    ): CompletenessCheck {
        val (done, missing) = rows.partition(satisfied)
        return CompletenessCheck(
            code = code, label = label, fix = fix,
            done = done.size, outstanding = missing.size,
            investmentIds = missing.take(MAX_IDS).map { it.id },
            weight = weight,
        )
    }

    private data class Row(
        val id: UUID,
        val hasNominee: Boolean,
        val hasDocument: Boolean,
        val hasAccount: Boolean,
        val expectsAccount: Boolean,
        val valueIsFresh: Boolean,
        val inContinuity: Boolean,
    )

    private fun load(householdId: UUID): List<Row> = jdbc.query(
        """
        select i.id,
               exists (select 1 from investment_nominees n where n.investment_id = i.id) as has_nominee,
               exists (select 1 from document_links dl
                       where dl.entity_type = 'investment' and dl.entity_id = i.id) as has_document,
               (i.account_id is not null) as has_account,
               (c.code = any(:accountCategories)) as expects_account,
               (coalesce(v.valued_on, i.start_date, i.created_at::date) >= :freshFrom
                and v.value_basis <> 'unknown') as value_is_fresh,
               i.is_in_continuity
        from investments i
        join investment_types t on t.id = i.type_id
        join asset_categories c on c.id = t.category_id
        left join investment_value v on v.investment_id = i.id
        where i.household_id = :hid
          and i.deleted_at is null
          and i.status = 'active'
          and not exists (select 1 from investments s
                          where s.rolled_from_id = i.id and s.deleted_at is null)
        """.trimIndent(),
        mapOf(
            "hid" to householdId,
            "accountCategories" to CATEGORIES_WITH_ACCOUNTS.toTypedArray(),
            "freshFrom" to LocalDate.now().minusYears(1),
        ),
    ) { rs, _ ->
        Row(
            id = rs.getObject("id", UUID::class.java),
            hasNominee = rs.getBoolean("has_nominee"),
            hasDocument = rs.getBoolean("has_document"),
            hasAccount = rs.getBoolean("has_account"),
            expectsAccount = rs.getBoolean("expects_account"),
            valueIsFresh = rs.getBoolean("value_is_fresh"),
            inContinuity = rs.getBoolean("is_in_continuity"),
        )
    }

    private companion object {
        const val MAX_IDS = 50
        const val NOTHING_TO_SCORE =
            "Nothing is recorded that you can see yet, so there is nothing to score."
        const val NOTE =
            "A measure of how usable these records would be to someone who didn't create " +
                "them — not a judgement of the holdings themselves."
        val CATEGORIES_WITH_ACCOUNTS = listOf(
            "deposits", "mutual_funds", "equity", "ipo", "bonds", "retirement",
        )
    }
}
