package tech.bhrigu.almira.reports

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.common.IndianNumbers
import tech.bhrigu.almira.household.HouseholdService
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

data class Concentration(
    val kind: String,
    val label: String,
    val value: BigDecimal,
    val valueFormatted: String,
    val percentage: BigDecimal,
    val investmentIds: List<UUID>,
)

data class LiquidityBucket(
    val code: String,
    val label: String,
    val description: String,
    val value: BigDecimal,
    val valueFormatted: String,
    val percentage: BigDecimal,
    val count: Int,
)

data class Insights(
    val totalAssets: BigDecimal,
    val totalAssetsFormatted: String,
    val holdingCount: Int,
    /** The largest single holding, institution and category, largest first. */
    val concentration: List<Concentration>,
    val liquidity: List<LiquidityBucket>,
    /** Observations, in plain words. Never a recommendation. */
    val observations: List<String>,
    val disclaimer: String,
)

/**
 * Where the money is bunched, and how quickly it could be reached.
 *
 * Both are questions people actually ask, and both are one step from advice —
 * so this reports proportions and says nothing about what to do with them. "Two
 * thirds of this is at one bank" is a fact; "diversify" is advice, and this app
 * does not give it (docs/08 §6).
 *
 * Liquidity is a rough guide drawn from what is recorded — a maturity date, a
 * category — not a market judgement, and the note says so.
 */
@Service
class InsightsService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
) {

    @Transactional(readOnly = true)
    fun build(householdId: UUID): Insights {
        households.get(householdId)
        val rows = load(householdId)
        val total = rows.fold(BigDecimal.ZERO) { acc, r -> acc + r.value }

        if (rows.isEmpty() || total.signum() == 0) {
            return Insights(
                totalAssets = BigDecimal.ZERO, totalAssetsFormatted = IndianNumbers.rupees(BigDecimal.ZERO),
                holdingCount = rows.size, concentration = emptyList(), liquidity = emptyList(),
                observations = if (rows.isEmpty()) {
                    listOf("Nothing recorded yet.")
                } else {
                    listOf("None of these holdings has a value recorded yet, so there is nothing to compare.")
                },
                disclaimer = DISCLAIMER,
            )
        }

        val largestHolding = rows.maxBy { it.value }
        val byInstitution = rows.filter { it.institution != null }.groupBy { it.institution!! }
        val byCategory = rows.groupBy { it.categoryLabel }

        val concentration = buildList {
            add(
                Concentration(
                    "holding", largestHolding.title, largestHolding.value,
                    IndianNumbers.rupees(largestHolding.value),
                    share(largestHolding.value, total), listOf(largestHolding.id),
                ),
            )
            byInstitution.maxByOrNull { (_, group) -> group.sumOf { it.value } }?.let { (name, group) ->
                val value = group.sumOf { it.value }
                add(
                    Concentration(
                        "institution", name, value, IndianNumbers.rupees(value),
                        share(value, total), group.map { it.id },
                    ),
                )
            }
            byCategory.maxByOrNull { (_, group) -> group.sumOf { it.value } }?.let { (label, group) ->
                val value = group.sumOf { it.value }
                add(
                    Concentration(
                        "category", label, value, IndianNumbers.rupees(value),
                        share(value, total), group.map { it.id },
                    ),
                )
            }
        }.sortedByDescending { it.percentage }

        val today = LocalDate.now()
        val liquidity = rows.groupBy { bucketFor(it, today) }
            .map { (code, group) ->
                val value = group.sumOf { it.value }
                LiquidityBucket(
                    code = code, label = BUCKET_LABELS.getValue(code),
                    description = BUCKET_DESCRIPTIONS.getValue(code),
                    value = value, valueFormatted = IndianNumbers.rupees(value),
                    percentage = share(value, total), count = group.size,
                )
            }
            .sortedBy { BUCKET_ORDER.indexOf(it.code) }

        return Insights(
            totalAssets = total,
            totalAssetsFormatted = IndianNumbers.rupees(total),
            holdingCount = rows.size,
            concentration = concentration,
            liquidity = liquidity,
            observations = observations(concentration, liquidity, rows),
            disclaimer = DISCLAIMER,
        )
    }

    /**
     * Facts with a threshold attached, so the client can show them without
     * having to decide what counts as notable — and without either of us
     * turning a proportion into a recommendation.
     */
    private fun observations(
        concentration: List<Concentration>,
        liquidity: List<LiquidityBucket>,
        rows: List<Row>,
    ): List<String> = buildList {
        concentration.firstOrNull { it.kind == "institution" && it.percentage >= BigDecimal(40) }
            ?.let { add("${it.percentage}% of what's recorded sits with ${it.label}.") }
        concentration.firstOrNull { it.kind == "holding" && it.percentage >= BigDecimal(25) }
            ?.let { add("${it.label} alone is ${it.percentage}% of the total.") }
        liquidity.firstOrNull { it.code == "reachable" }
            ?.let { add("${it.percentage}% could be reached without waiting for a maturity date.") }
            ?: add("Nothing recorded here could be reached without waiting for a maturity date.")
        rows.count { it.basis == "at_cost" }.takeIf { it > 0 }?.let {
            add("$it ${if (it == 1) "holding is" else "holdings are"} still counted at what was paid, not what it's worth now.")
        }
    }

    private fun bucketFor(row: Row, today: LocalDate): String = when {
        row.maturityDate != null && row.maturityDate.isAfter(today) -> "locked"
        row.categoryCode in SLOW_CATEGORIES -> "slow"
        else -> "reachable"
    }

    private fun share(part: BigDecimal, total: BigDecimal): BigDecimal =
        part.multiply(BigDecimal(100)).divide(total, 1, RoundingMode.HALF_UP)

    private data class Row(
        val id: UUID,
        val title: String,
        val categoryCode: String,
        val categoryLabel: String,
        val institution: String?,
        val value: BigDecimal,
        val basis: String,
        val maturityDate: LocalDate?,
    )

    private fun load(householdId: UUID): List<Row> = jdbc.query(
        """
        select i.id, i.title, i.maturity_date,
               c.code as category_code, c.label as category_label,
               coalesce(inst.name, acct_inst.name) as institution_name,
               coalesce(v.effective_value, 0) as effective_value,
               coalesce(v.value_basis, 'unknown') as value_basis
        from investments i
        join investment_types t on t.id = i.type_id
        join asset_categories c on c.id = t.category_id
        left join investment_value v on v.investment_id = i.id
        left join institutions inst on inst.id = i.institution_id
        left join accounts acct on acct.id = i.account_id
        left join institutions acct_inst on acct_inst.id = acct.institution_id
        where i.household_id = :hid
          and i.deleted_at is null
          and i.status = 'active'
          and not exists (select 1 from investments s
                          where s.rolled_from_id = i.id and s.deleted_at is null)
        """.trimIndent(),
        mapOf("hid" to householdId),
    ) { rs, _ ->
        Row(
            id = rs.getObject("id", UUID::class.java),
            title = rs.getString("title"),
            categoryCode = rs.getString("category_code"),
            categoryLabel = rs.getString("category_label"),
            institution = rs.getString("institution_name"),
            value = rs.getBigDecimal("effective_value"),
            basis = rs.getString("value_basis"),
            maturityDate = rs.getDate("maturity_date")?.toLocalDate(),
        )
    }

    private companion object {
        val SLOW_CATEGORIES = setOf("real_estate", "alternatives")
        val BUCKET_ORDER = listOf("reachable", "locked", "slow")
        val BUCKET_LABELS = mapOf(
            "reachable" to "Reachable",
            "locked" to "Locked until a date",
            "slow" to "Slow to sell",
        )
        val BUCKET_DESCRIPTIONS = mapOf(
            "reachable" to "No maturity date recorded, so nothing is stopping you.",
            "locked" to "Has a maturity date still to come.",
            "slow" to "Property and alternatives take time to turn into money.",
        )
        const val DISCLAIMER =
            "Informational, not financial advice. Liquidity here is a rough guide from " +
                "what's recorded — a maturity date and a category — not a market judgement."
    }
}
