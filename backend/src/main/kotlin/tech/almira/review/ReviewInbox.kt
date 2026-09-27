package tech.almira.review

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tech.almira.common.ApiException
import tech.almira.common.IndianNumbers
import tech.almira.continuity.HandoverChecks
import tech.almira.continuity.HandoverReadinessService
import tech.almira.household.HouseholdService
import tech.almira.security.RequestUserContext
import tech.almira.stilltrue.StillTrueService
import java.time.LocalDate
import java.util.UUID

/**
 * One thing waiting for this person, as one card.
 *
 * [kind] is `maturity`, `still_true`, `no_nominee` or `no_document`, and it is
 * what the client acts on; [detail] is the server's English sentence for a client
 * that does not know the kind (Doc 14). [reason] is Still true?'s own reason
 * (`period` or `key_date`) and absent for the rest.
 */
data class ReviewItem(
    val kind: String,
    val recordType: String,
    val recordId: UUID,
    val title: String,
    val date: LocalDate? = null,
    val reason: String? = null,
    val valueFormatted: String? = null,
    val detail: String,
)

data class ReviewInbox(
    val count: Int,
    val items: List<ReviewItem>,
    /** Always a sentence, so an empty inbox says something rather than nothing. */
    val summary: String,
)

/**
 * "To review" (catch-up plan X-51): the one queue that replaced a stack of
 * identical "Worth a look" cards.
 *
 * It asks nothing new. Each source is an existing answer, read as the caller:
 *   · a maturity of something they hold, in the next [MATURITY_DAYS] days or
 *     the last week and not yet renewed — the moment to decide about the money;
 *   · a record Still true? is asking this person about (docs/21) — only what
 *     they own or hold, never someone else's policy;
 *   · a missing nominee or scan, exactly as handover readiness names it
 *     (docs/22), so the two lists cannot disagree about what is missing.
 *
 * Order is by urgency, not by source: a date that is about to pass, then a
 * question that has come due, then the two gaps. Within each, the sources' own
 * order. Nothing here is a judgement about an investment (docs/08 §6).
 */
@Service
class ReviewService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val userContext: RequestUserContext,
    private val stillTrue: StillTrueService,
    private val readiness: HandoverReadinessService,
) {

    @Transactional(readOnly = true)
    fun inbox(householdId: UUID): ReviewInbox {
        userContext.require()
        // A guest link reviews nothing; readiness refuses it for the same reason.
        if (userContext.currentGuestShareId() != null) throw ApiException.forbidden()
        households.get(householdId)

        val maturities = jdbc.query(
            """
            select i.id, i.title, i.maturity_date, iv.effective_value
            from investments i
            join households h on h.id = i.household_id
            left join investment_value iv on iv.investment_id = i.id
            where i.household_id = :hid
              and i.deleted_at is null
              and i.status = 'active'
              and i.maturity_date between (now() at time zone h.time_zone)::date - 7
                                      and (now() at time zone h.time_zone)::date + $MATURITY_DAYS
              -- What happens to the money is a holder's decision, as Still true? is
              -- a holder's question: seeing a shared FD is not holding it.
              and app.owns_investment(i.id)
              and not exists (select 1 from investments s where s.rolled_from_id = i.id and s.deleted_at is null)
            order by i.maturity_date, lower(i.title), i.id
            """.trimIndent(),
            mapOf("hid" to householdId),
        ) { rs, _ ->
            val value = rs.getBigDecimal("effective_value")
            ReviewItem(
                kind = MATURITY,
                recordType = "investment",
                recordId = rs.getObject("id", UUID::class.java),
                title = rs.getString("title"),
                date = rs.getDate("maturity_date").toLocalDate(),
                valueFormatted = value?.let(IndianNumbers::rupees),
                detail = "It matures soon. Decide whether to renew it or where the money goes.",
            )
        }

        val questions = stillTrue.due(householdId).items.map {
            ReviewItem(
                kind = STILL_TRUE,
                recordType = it.recordType,
                recordId = it.recordId,
                title = it.title,
                date = it.dueOn,
                reason = it.reason,
                detail = if (it.reason == "key_date") {
                    "A date on it has passed. Is it still as recorded?"
                } else {
                    "Nobody has confirmed it in a while. Is it still as recorded?"
                },
            )
        }

        val gaps = readiness.readiness(householdId).gaps
            .filter { it.recordType != null && it.recordId != null && it.title != null }
            .mapNotNull { gap ->
                when (gap.check) {
                    HandoverChecks.NOMINEE -> NO_NOMINEE
                    HandoverChecks.DOCUMENT -> NO_DOCUMENT
                    else -> null
                }?.let { kind ->
                    ReviewItem(
                        kind = kind, recordType = gap.recordType!!, recordId = gap.recordId!!,
                        title = gap.title!!, detail = gap.fix,
                    )
                }
            }
            .sortedBy { if (it.kind == NO_NOMINEE) 0 else 1 }

        val items = order(maturities + questions + gaps)
        return ReviewInbox(
            count = items.size,
            items = items,
            summary = if (items.isEmpty()) EMPTY else
                "${items.size} ${if (items.size == 1) "thing" else "things"} to look at, one at a time.",
        )
    }

    companion object {
        const val MATURITY = "maturity"
        const val STILL_TRUE = "still_true"
        const val NO_NOMINEE = "no_nominee"
        const val NO_DOCUMENT = "no_document"
        const val MATURITY_DAYS = 30

        const val EMPTY = "Nothing waiting. Your family is in good shape."

        private val RANK = listOf(MATURITY, STILL_TRUE, NO_NOMINEE, NO_DOCUMENT)

        /** Urgency first; a stable sort keeps each source's own order inside its kind. */
        fun order(items: List<ReviewItem>): List<ReviewItem> = items.sortedBy { RANK.indexOf(it.kind) }
    }
}

@RestController
@RequestMapping("/api/v1/households/{householdId}")
class ReviewController(private val service: ReviewService) {

    /** Everything waiting for this person, in the order to look at it (X-51). */
    @GetMapping("/review")
    fun review(@PathVariable householdId: UUID): ReviewInbox = service.inbox(householdId)
}
