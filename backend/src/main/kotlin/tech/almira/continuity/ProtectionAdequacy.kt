package tech.almira.continuity

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tech.almira.audit.AuditService
import tech.almira.common.ApiException
import tech.almira.common.IndianNumbers
import tech.almira.household.HouseholdService
import tech.almira.security.RequestUserContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

/**
 * "Term cover is about 4× annual expenses" — the arithmetic, and the words
 * (docs/27 §7). Pure, so every sentence can be reasoned about and tested.
 *
 * It states a ratio and what the ratio means in years, and nothing more. There
 * is no target, no "enough", no red: whether a family's cover is right depends
 * on loans, goals, savings, ages and a dozen things this does not know, and
 * saying otherwise would be advice.
 */
object ProtectionMath {

    /** Rounded DOWN to one decimal: the figure never flatters the cover. */
    fun multiple(cover: BigDecimal, expenses: BigDecimal): BigDecimal? =
        if (expenses.signum() <= 0) null else cover.divide(expenses, 1, RoundingMode.DOWN)

    /** "4", "4.5", "0.8" — no trailing ".0". */
    fun words(multiple: BigDecimal): String = multiple.stripTrailingZeros().let {
        if (it.scale() <= 0) it.setScale(0).toPlainString() else it.toPlainString()
    }

    fun headline(cover: BigDecimal, expenses: BigDecimal?): String = when {
        expenses == null -> "Add what the household spends in a year to see how term cover compares."
        cover.signum() == 0 -> "No term cover is recorded that you can see."
        else -> {
            val m = multiple(cover, expenses)!!
            if (m.signum() == 0) "Term cover is less than a tenth of annual expenses."
            else "Term cover is about ${words(m)}× annual expenses."
        }
    }

    fun years(cover: BigDecimal, expenses: BigDecimal): String {
        val m = multiple(cover, expenses)!!
        return if (m < BigDecimal.ONE) {
            "That is less than one year of what you said the household spends, before inflation or returns."
        } else {
            val whole = m.setScale(0, RoundingMode.DOWN).toInt()
            "That is roughly $whole ${if (whole == 1) "year" else "years"} of what you said the household spends, " +
                "before inflation or returns."
        }
    }

    fun dependants(names: List<String>): String = when (names.size) {
        0 -> "You haven't said who depends on this income."
        1 -> "You've said one person depends on it: ${names.first()}."
        else -> "You've said ${names.size} people depend on it: ${names.joinToString(", ")}."
    }

    /** The ring's reach. A scale, not a target: it says nothing about what is enough. */
    const val GAUGE_SCALE = 20

    fun gaugePercent(multiple: BigDecimal?): Int? =
        multiple?.let { minOf(100, it.multiply(BigDecimal(100)).divide(BigDecimal(GAUGE_SCALE), 0, RoundingMode.DOWN).toInt()) }
}

data class ProtectionPolicy(
    val investmentId: UUID,
    val title: String,
    /** Whose policy, by the members recorded as owning it. */
    val policyholders: List<String>,
    val sumAssured: BigDecimal,
    val sumAssuredFormatted: String,
    val coverUntil: LocalDate?,
)

data class ProtectionDependant(val memberId: UUID, val name: String)

data class ProtectionAdequacy(
    /** `needs_expenses` | `no_term_cover` | `ready` */
    val status: String,
    /** Words first: always a sentence. */
    val headline: String,
    val details: List<String>,
    val termCover: BigDecimal,
    val termCoverFormatted: String,
    val termCoverInWords: String,
    val annualExpenses: BigDecimal?,
    val annualExpensesFormatted: String?,
    val annualExpensesInWords: String?,
    /** Cover ÷ expenses, rounded down to one decimal. Absent without both. */
    val multiple: BigDecimal?,
    /** 0–100 on a fixed scale of [ProtectionMath.GAUGE_SCALE]×, for a soft ring. Not a target. */
    val gaugePercent: Int?,
    val gaugeScale: Int = ProtectionMath.GAUGE_SCALE,
    val dependants: List<ProtectionDependant>,
    val policies: List<ProtectionPolicy>,
    /** Term policies you can see in a currency other than rupees, not added in. */
    val notCounted: Int,
    val caveats: List<String>,
)

data class ProtectionInputsBody(
    val annualExpenses: BigDecimal? = null,
    val dependantMemberIds: List<UUID> = emptyList(),
)

/**
 * Protection adequacy, as information (docs/27 §7, docs/08 §3).
 *
 * Per viewer, like readiness: the term policies counted are the ones this
 * person sees by ordinary sight (never through an emergency window), and the
 * annual expenses and dependants are theirs, entered by them, read by nobody
 * else (V95's policy on `protection_inputs`).
 */
@Service
class ProtectionAdequacyService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val audit: AuditService,
    private val userContext: RequestUserContext,
) {

    @Transactional(readOnly = true)
    fun view(householdId: UUID): ProtectionAdequacy {
        userContext.require()
        if (userContext.currentGuestShareId() != null) throw ApiException.notFound()
        households.get(householdId)

        val inputs = jdbc.query(
            """
            select annual_expenses, dependant_member_ids from protection_inputs
            where household_id = :hid and user_id = app.current_user_id()
            """.trimIndent(),
            mapOf("hid" to householdId),
        ) { rs, _ ->
            val ids = (rs.getArray("dependant_member_ids")?.array as? Array<*>)?.filterIsInstance<UUID>().orEmpty()
            rs.getBigDecimal("annual_expenses") to ids
        }.firstOrNull()
        val expenses = inputs?.first
        val members = households.members(householdId).associateBy { it.id }
        val dependants = inputs?.second.orEmpty()
            .mapNotNull { id -> members[id]?.let { ProtectionDependant(id, it.displayName) } }

        // A policy with no readable sum assured is not cover we can state.
        val rows = jdbc.query(POLICIES, mapOf("hid" to householdId)) { rs, _ ->
            val currency = rs.getString("currency")
            val policy = rs.getBigDecimal("sum_assured")?.let { sum ->
                ProtectionPolicy(
                    investmentId = rs.getObject("id", UUID::class.java),
                    title = rs.getString("title"),
                    policyholders = (rs.getArray("holders")?.array as? Array<*>)?.filterIsInstance<String>().orEmpty(),
                    sumAssured = sum,
                    sumAssuredFormatted = IndianNumbers.rupees(sum),
                    coverUntil = rs.getDate("maturity_date")?.toLocalDate(),
                )
            }
            currency to policy
        }
        val policies = rows.filter { it.first.equals("INR", ignoreCase = true) }.mapNotNull { it.second }
        val notCounted = rows.count { !it.first.equals("INR", ignoreCase = true) }
        val cover = policies.fold(BigDecimal.ZERO) { acc, p -> acc + p.sumAssured }
        val multiple = expenses?.let { ProtectionMath.multiple(cover, it) }

        val status = when {
            expenses == null -> "needs_expenses"
            cover.signum() == 0 -> "no_term_cover"
            else -> "ready"
        }
        val details = buildList {
            if (expenses != null && cover.signum() > 0) add(ProtectionMath.years(cover, expenses))
            add(ProtectionMath.dependants(dependants.map { it.name }))
            if (notCounted > 0) {
                add("$notCounted term ${if (notCounted == 1) "policy" else "policies"} in another currency " +
                    "${if (notCounted == 1) "is" else "are"} not added in.")
            }
        }
        return ProtectionAdequacy(
            status = status,
            headline = ProtectionMath.headline(cover, expenses),
            details = details,
            termCover = cover,
            termCoverFormatted = IndianNumbers.rupees(cover),
            termCoverInWords = IndianNumbers.words(cover),
            annualExpenses = expenses,
            annualExpensesFormatted = expenses?.let(IndianNumbers::rupees),
            annualExpensesInWords = expenses?.let(IndianNumbers::words),
            multiple = multiple,
            gaugePercent = ProtectionMath.gaugePercent(multiple),
            dependants = dependants,
            policies = policies,
            notCounted = notCounted,
            caveats = CAVEATS,
        )
    }

    @Transactional
    fun saveInputs(householdId: UUID, body: ProtectionInputsBody): ProtectionAdequacy {
        val userId = userContext.require()
        households.get(householdId)
        val expenses = body.annualExpenses?.setScale(2, RoundingMode.HALF_UP)
        if (expenses != null && (expenses.signum() <= 0 || expenses >= BigDecimal("1000000000000"))) {
            throw ApiException.badRequest(
                "expenses_invalid", "Annual expenses should be more than zero and less than a lakh crore.",
                mapOf("fields" to mapOf("annualExpenses" to "Enter what the household spends in a year")),
            )
        }
        val ids = body.dependantMemberIds.distinct()
        if (ids.size > 30) throw ApiException.badRequest("too_many_dependants", "That is more people than we can list.")
        val members = households.members(householdId)
        val me = members.firstOrNull { it.isMe }?.id
        if (ids.any { id -> members.none { it.id == id } }) {
            throw ApiException.badRequest("member_unknown", "Someone chosen isn't in this household.")
        }
        if (me != null && me in ids) {
            throw ApiException.badRequest("dependant_is_you", "Choose the people who depend on this income, not yourself.")
        }
        jdbc.update(
            """
            insert into protection_inputs (household_id, user_id, annual_expenses, dependant_member_ids)
            values (:hid, :uid, :expenses, cast(:ids as uuid[]))
            on conflict (household_id, user_id) do update set
              annual_expenses = excluded.annual_expenses,
              dependant_member_ids = excluded.dependant_member_ids
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("hid", householdId).addValue("uid", userId).addValue("expenses", expenses)
                .addValue("ids", ids.joinToString(",", "{", "}")),
        )
        // What changed, not the figure: an amount in the log is an amount in every export of it.
        audit.record(
            householdId = householdId, actorUserId = userId, action = "continuity.protection.inputs",
            entityType = "household", entityId = householdId,
            diff = mapOf("hasExpenses" to (expenses != null), "dependants" to ids.size),
        )
        return view(householdId)
    }

    private companion object {
        /**
         * Term life only: an endowment's or a ULIP's sum assured is partly savings,
         * and health cover pays bills, not an income. Ordinary sight only, as in
         * readiness (docs/22 §5). Cover that has ended is not cover.
         */
        val POLICIES = """
            select i.id, i.title, i.currency, i.maturity_date,
                   case when (i.attributes ->> 'sum_assured') ~ '^[0-9]+(\.[0-9]+)?$'
                        then (i.attributes ->> 'sum_assured')::numeric end as sum_assured,
                   array(select m.display_name from investment_ownerships o
                         join members m on m.id = o.member_id
                         where o.investment_id = i.id order by m.display_name) as holders
            from investments i
            join investment_types t on t.id = i.type_id
            where i.household_id = :hid
              and t.code = 'insurance_term'
              and i.deleted_at is null
              and i.status = 'active'
              and (i.maturity_date is null or i.maturity_date >= current_date)
              and app.can_read_record(i.household_id, i.visibility, 'investment', i.id, app.owns_investment(i.id))
            order by lower(i.title)
        """.trimIndent()

        val CAVEATS = listOf(
            "This is information, not advice. Almira does not know your loans, goals, savings or the ages of " +
                "the people who depend on you, and a figure like this cannot say what is right for your family.",
            "Only term life policies you can see are counted, at their recorded sum assured. Endowment and ULIP " +
                "cover, cover through an employer, and policies that have ended are not.",
            "Annual expenses and who depends on them are what you entered. Only you see them.",
            "A licensed adviser can look at the whole picture with you.",
        )
    }
}

@RestController
@RequestMapping("/api/v1/households/{householdId}/continuity/protection")
class ProtectionAdequacyController(private val service: ProtectionAdequacyService) {

    @GetMapping
    fun protectionAdequacy(@PathVariable householdId: UUID): ProtectionAdequacy = service.view(householdId)

    @PutMapping("/inputs")
    fun saveProtectionInputs(@PathVariable householdId: UUID, @RequestBody body: ProtectionInputsBody): ProtectionAdequacy =
        service.saveInputs(householdId, body)
}
