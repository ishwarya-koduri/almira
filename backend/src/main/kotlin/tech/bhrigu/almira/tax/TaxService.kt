package tech.bhrigu.almira.tax

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.common.IndianNumbers
import tech.bhrigu.almira.household.HouseholdService
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

data class DeductionSource(
    val investmentId: UUID,
    val title: String,
    val amount: BigDecimal,
    val amountFormatted: String,
    /** "transactions" when derived from recorded movements, "declared" otherwise. */
    val basis: String,
)

data class DeductionMeter(
    val section: String,
    val label: String,
    val description: String,
    val limit: BigDecimal,
    val limitFormatted: String,
    val used: BigDecimal,
    val usedFormatted: String,
    val remaining: BigDecimal,
    val remainingFormatted: String,
    val percentUsed: BigDecimal,
    val sources: List<DeductionSource>,
    val note: String?,
)

data class GainsBucket(
    val term: String,
    val label: String,
    val proceeds: BigDecimal,
    val costBasis: BigDecimal,
    val gain: BigDecimal,
    val gainFormatted: String,
    val disposals: Int,
)

data class UnrealizedPosition(
    val investmentId: UUID,
    val title: String,
    val currentValue: BigDecimal,
    val costOfHoldings: BigDecimal,
    val unrealizedGain: BigDecimal,
    val unrealizedGainFormatted: String,
    /** Where the gain would fall if sold today — for planning, not a projection. */
    val termIfSoldToday: String,
)

data class CapitalGains(
    val financialYear: String,
    val realized: List<GainsBucket>,
    val netRealized: BigDecimal,
    val netRealizedFormatted: String,
    val unrealized: List<UnrealizedPosition>,
    val disclaimer: String,
)

data class InterestIncome(
    val financialYear: String,
    val total: BigDecimal,
    val totalFormatted: String,
    val bySource: List<DeductionSource>,
)

data class TaxPack(
    val financialYear: String,
    val memberId: UUID?,
    val memberName: String?,
    val deductions: List<DeductionMeter>,
    val capitalGains: CapitalGains,
    val interestIncome: InterestIncome,
    val disclaimer: String,
)

/**
 * The India tax layer (docs/01 §9, docs/10 Epic 2.3).
 *
 * **Informational, not tax advice.** Every figure here is a summary of what the
 * household has recorded, arranged the way a return asks for it. It is a
 * starting point for a person or their CA, not a filing — and the disclaimer
 * travels on every response rather than living in a footer someone can miss.
 *
 * Nothing is cached. A deduction meter computed in October and shown in January
 * would be quietly wrong, and a stale tax figure is worse than a slow one.
 *
 * Everything reads through the caller's own row-level security, so a member's
 * tax pack contains their holdings and the ones shared with them — never a
 * spouse's private ELSS, even though it would raise their 80C.
 */
@Service
class TaxService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val mapper: ObjectMapper,
) {

    private val disclaimer =
        "A summary of what you've recorded, arranged the way a return asks for it. " +
            "Informational only — not tax advice. Check it with your CA before filing."

    @Transactional(readOnly = true)
    fun pack(householdId: UUID, memberId: UUID?, fyLabel: String?): TaxPack {
        households.get(householdId)
        val fy = FinancialYear.parse(fyLabel)
        val member = memberId?.let { target ->
            households.members(householdId).firstOrNull { it.id == target }
                ?: throw ApiException.notFound("We couldn't find that person.")
        }

        return TaxPack(
            financialYear = fy.label,
            memberId = member?.id,
            memberName = member?.displayName,
            deductions = deductions(householdId, member?.id, fy),
            capitalGains = capitalGains(householdId, member?.id, fy),
            interestIncome = interestIncome(householdId, member?.id, fy),
            disclaimer = disclaimer,
        )
    }

    // --- deductions -----------------------------------------------------------

    @Transactional(readOnly = true)
    fun deductions(householdId: UUID, memberId: UUID?, fy: FinancialYear): List<DeductionMeter> {
        val holdings = qualifyingHoldings(householdId, memberId, fy)

        val meters = DeductionRules.SECTIONS.map { section ->
            val sources = holdings
                .filter { DeductionRules.qualifies(section, it.typeCode, it.attributes) }
                .mapNotNull { holding ->
                    val amount = contributionFor(holding, section)
                    amount?.takeIf { it.signum() > 0 }?.let {
                        DeductionSource(holding.id, holding.title, it, IndianNumbers.rupees(it), holding.basis)
                    }
                }
            meter(section, sources)
        }

        return meters + homeLoanInterest(householdId, memberId, fy)
    }

    private fun meter(section: DeductionRules.Section, sources: List<DeductionSource>): DeductionMeter {
        val raw = sources.fold(BigDecimal.ZERO) { acc, s -> acc + s.amount }
        // Capped: claiming ₹2 lakh under 80C is not a thing, and showing it
        // uncapped would have someone plan around a deduction they cannot take.
        val used = raw.min(section.limit)
        val remaining = (section.limit - used).max(BigDecimal.ZERO)

        return DeductionMeter(
            section = section.code,
            label = section.label,
            description = section.description,
            limit = section.limit,
            limitFormatted = IndianNumbers.rupees(section.limit),
            used = used,
            usedFormatted = IndianNumbers.rupees(used),
            remaining = remaining,
            remainingFormatted = IndianNumbers.rupees(remaining),
            percentUsed = used.multiply(BigDecimal(100))
                .divide(section.limit, 1, RoundingMode.HALF_UP),
            sources = sources,
            note = when {
                sources.isEmpty() -> "Nothing recorded against this yet."
                raw > section.limit ->
                    "You've put in ${IndianNumbers.rupees(raw)}, but only " +
                        "${IndianNumbers.rupees(section.limit)} of it can be claimed."
                sources.any { it.basis == "declared" } ->
                    "Some figures come from what you entered rather than recorded payments."
                else -> null
            },
        )
    }

    /**
     * What was actually put in during the year.
     *
     * Recorded movements win where they exist. A declared annual figure is a
     * reasonable fallback for a PPF nobody logs monthly, but it is an intention
     * rather than a fact, so the source is reported alongside it.
     */
    private fun contributionFor(holding: Holding, section: DeductionRules.Section): BigDecimal? {
        if (holding.contributedInFy.signum() > 0) return holding.contributedInFy
        section.declaredAmountKeys.forEach { key ->
            (holding.attributes[key] as? String)?.toBigDecimalOrNull()
                ?.takeIf { it.signum() > 0 }
                ?.let { declared ->
                    // A monthly premium declared once is a yearly commitment.
                    val frequency = holding.attributes["premium_frequency"] as? String
                    return when (frequency) {
                        "monthly" -> declared.multiply(BigDecimal(12))
                        "quarterly" -> declared.multiply(BigDecimal(4))
                        "half_yearly" -> declared.multiply(BigDecimal(2))
                        else -> declared
                    }
                }
        }
        // A SIP declares a monthly amount rather than a yearly one.
        (holding.attributes["sip_amount"] as? String)?.toBigDecimalOrNull()
            ?.takeIf { it.signum() > 0 }
            ?.let { return it.multiply(BigDecimal(12)) }
        return null
    }

    private fun homeLoanInterest(
        householdId: UUID,
        memberId: UUID?,
        fy: FinancialYear,
    ): DeductionMeter {
        // Interest actually paid is not recorded transaction by transaction, so
        // this is an estimate from the outstanding balance and rate, and says so.
        val rows = jdbc.query(
            """
            select l.id, l.title, lc.outstanding, l.interest_rate,
                   coalesce(h.responsibility_pct, 100) as share
            from liabilities l
            join liability_current lc on lc.liability_id = l.id
            left join liability_holders h on h.liability_id = l.id
              and (cast(:memberId as uuid) is null or h.member_id = cast(:memberId as uuid))
            where l.household_id = :hid and l.deleted_at is null
              and l.kind = 'home' and l.status = 'active'
              and l.interest_rate is not null
              and (cast(:memberId as uuid) is null
                   or exists (select 1 from liability_holders lh
                              where lh.liability_id = l.id
                                and lh.member_id = cast(:memberId as uuid)))
            """.trimIndent(),
            MapSqlParameterSource().addValue("hid", householdId).addValue("memberId", memberId),
        ) { rs, _ ->
            val outstanding = rs.getBigDecimal("outstanding") ?: BigDecimal.ZERO
            val rate = rs.getBigDecimal("interest_rate") ?: BigDecimal.ZERO
            val share = rs.getBigDecimal("share") ?: BigDecimal(100)
            val estimate = outstanding.multiply(rate).divide(BigDecimal(100), 4, RoundingMode.HALF_UP)
                .multiply(share).divide(BigDecimal(100), 4, RoundingMode.HALF_UP)
            DeductionSource(
                rs.getObject("id", UUID::class.java), rs.getString("title"),
                estimate, IndianNumbers.rupees(estimate), "estimated",
            )
        }

        val section = DeductionRules.HOME_LOAN_INTEREST
        return meter(section, rows).copy(
            note = if (rows.isEmpty()) {
                "No home loan recorded."
            } else {
                "Estimated from the outstanding balance and rate — your lender's interest " +
                    "certificate is the figure to file."
            },
        )
    }

    // --- capital gains --------------------------------------------------------

    @Transactional(readOnly = true)
    fun capitalGains(householdId: UUID, memberId: UUID?, fy: FinancialYear): CapitalGains {
        val realized = jdbc.query(
            """
            select d.gain_term, sum(d.proceeds) as proceeds, sum(d.cost_basis) as cost,
                   sum(d.gain) as gain, count(*) as n
            from tax_lot_disposals d
            join investments i on i.id = d.investment_id
            where i.household_id = :hid and i.deleted_at is null
              and d.disposed_on between :from and :until
              and (cast(:memberId as uuid) is null or exists (
                    select 1 from investment_ownerships o
                    where o.investment_id = i.id and o.member_id = cast(:memberId as uuid)))
            group by d.gain_term
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("hid", householdId).addValue("memberId", memberId)
                .addValue("from", fy.start).addValue("until", fy.end),
        ) { rs, _ ->
            val term = rs.getString("gain_term")
            val gain = rs.getBigDecimal("gain")
            GainsBucket(
                term = term,
                label = if (term == "long") "Long-term" else "Short-term",
                proceeds = rs.getBigDecimal("proceeds"),
                costBasis = rs.getBigDecimal("cost"),
                gain = gain,
                gainFormatted = IndianNumbers.rupees(gain),
                disposals = rs.getInt("n"),
            )
        }

        val unrealized = jdbc.query(
            """
            select i.id, i.title, i.attributes, c.code as category_code,
                   v.effective_value,
                   coalesce(sum(l.remaining_qty * l.unit_cost), 0) as held_cost,
                   min(l.acquired_on) as oldest_lot
            from investments i
            join investment_types t on t.id = i.type_id
            join asset_categories c on c.id = t.category_id
            left join investment_value v on v.investment_id = i.id
            join tax_lots l on l.investment_id = i.id and l.remaining_qty > 0
            where i.household_id = :hid and i.deleted_at is null and i.status = 'active'
              and (cast(:memberId as uuid) is null or exists (
                    select 1 from investment_ownerships o
                    where o.investment_id = i.id and o.member_id = cast(:memberId as uuid)))
            group by i.id, i.title, i.attributes, c.code, v.effective_value
            """.trimIndent(),
            MapSqlParameterSource().addValue("hid", householdId).addValue("memberId", memberId),
        ) { rs, _ ->
            val value = rs.getBigDecimal("effective_value") ?: BigDecimal.ZERO
            val cost = rs.getBigDecimal("held_cost") ?: BigDecimal.ZERO
            val attributes: Map<String, Any?> = mapper.readValue(rs.getString("attributes"))
            val oldest = rs.getDate("oldest_lot")?.toLocalDate()
            val months = tech.bhrigu.almira.returns.HoldingPeriod
                .monthsFor(rs.getString("category_code"), attributes)
            UnrealizedPosition(
                investmentId = rs.getObject("id", UUID::class.java),
                title = rs.getString("title"),
                currentValue = value,
                costOfHoldings = cost,
                unrealizedGain = value - cost,
                unrealizedGainFormatted = IndianNumbers.rupees(value - cost),
                termIfSoldToday = if (oldest != null &&
                    java.time.LocalDate.now().isAfter(oldest.plusMonths(months.toLong()))
                ) "long" else "short",
            )
        }.filter { it.currentValue.signum() > 0 }

        val net = realized.fold(BigDecimal.ZERO) { acc, b -> acc + b.gain }
        return CapitalGains(
            financialYear = fy.label,
            realized = realized.sortedBy { it.term },
            netRealized = net,
            netRealizedFormatted = IndianNumbers.rupees(net),
            unrealized = unrealized.sortedByDescending { it.unrealizedGain },
            disclaimer = "Gains are worked out from the units you recorded selling, oldest " +
                "first. Informational — not tax advice.",
        )
    }

    // --- interest income ------------------------------------------------------

    @Transactional(readOnly = true)
    fun interestIncome(householdId: UUID, memberId: UUID?, fy: FinancialYear): InterestIncome {
        val rows = jdbc.query(
            """
            select i.id, i.title, sum(t.amount) as total
            from transactions t
            join investments i on i.id = t.investment_id
            where i.household_id = :hid and i.deleted_at is null
              and t.txn_type in ('interest','dividend')
              and t.txn_date between :from and :until
              and (cast(:memberId as uuid) is null or exists (
                    select 1 from investment_ownerships o
                    where o.investment_id = i.id and o.member_id = cast(:memberId as uuid)))
            group by i.id, i.title
            order by total desc
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("hid", householdId).addValue("memberId", memberId)
                .addValue("from", fy.start).addValue("until", fy.end),
        ) { rs, _ ->
            val amount = rs.getBigDecimal("total")
            DeductionSource(
                rs.getObject("id", UUID::class.java), rs.getString("title"),
                amount, IndianNumbers.rupees(amount), "transactions",
            )
        }

        val total = rows.fold(BigDecimal.ZERO) { acc, r -> acc + r.amount }
        return InterestIncome(fy.label, total, IndianNumbers.rupees(total), rows)
    }

    // --- shared query ---------------------------------------------------------

    private data class Holding(
        val id: UUID,
        val title: String,
        val typeCode: String,
        val attributes: Map<String, Any?>,
        val contributedInFy: BigDecimal,
        val basis: String,
    )

    private fun qualifyingHoldings(
        householdId: UUID,
        memberId: UUID?,
        fy: FinancialYear,
    ): List<Holding> = jdbc.query(
        """
        select i.id, i.title, t.code as type_code, i.attributes,
               coalesce((
                 select sum(tx.amount) from transactions tx
                 where tx.investment_id = i.id
                   and tx.txn_type in ('buy','contribution')
                   and tx.txn_date between :from and :until
               ), 0) as contributed
        from investments i
        join investment_types t on t.id = i.type_id
        where i.household_id = :hid and i.deleted_at is null and i.status = 'active'
          and (cast(:memberId as uuid) is null or exists (
                select 1 from investment_ownerships o
                where o.investment_id = i.id and o.member_id = cast(:memberId as uuid)))
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("hid", householdId).addValue("memberId", memberId)
            .addValue("from", fy.start).addValue("until", fy.end),
    ) { rs, _ ->
        val contributed = rs.getBigDecimal("contributed") ?: BigDecimal.ZERO
        Holding(
            id = rs.getObject("id", UUID::class.java),
            title = rs.getString("title"),
            typeCode = rs.getString("type_code"),
            attributes = mapper.readValue(rs.getString("attributes")),
            contributedInFy = contributed,
            basis = if (contributed.signum() > 0) "transactions" else "declared",
        )
    }
}
