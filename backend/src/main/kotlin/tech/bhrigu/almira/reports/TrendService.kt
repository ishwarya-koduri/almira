package tech.bhrigu.almira.reports

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.common.IndianNumbers
import tech.bhrigu.almira.household.HouseholdService
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class TrendPoint(
    val date: LocalDate,
    val assets: BigDecimal,
    val liabilities: BigDecimal,
    val netWorth: BigDecimal,
    val netWorthFormatted: String,
)

data class NetWorthTrend(
    val scope: String,
    val points: List<TrendPoint>,
    /**
     * When this household first recorded anything. Months before it are real
     * zeros in the sense that nothing was tracked yet — not a claim that net
     * worth was zero. The client starts the line here rather than drawing a
     * flat run that reads like history.
     */
    val historyBeginsAt: LocalDate?,
    val basis: String,
)

/**
 * Net worth over time, drawn only from what was recorded (docs/10 Epic 1.5).
 *
 * Each point asks: at this date, what was the latest valuation for each holding,
 * and the latest recorded balance for each debt? Nothing is interpolated between
 * snapshots and nothing is projected past the last one. A line that invented the
 * months between two valuations would look more informative and be less true —
 * and this is a chart people will read as a fact about their life.
 *
 * A holding with no valuation on or before a date contributes its invested
 * amount if it existed by then, and nothing if it did not: the past should not
 * be redrawn by something bought last week.
 */
@Service
class TrendService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
) {

    @Transactional(readOnly = true)
    fun netWorthTrend(householdId: UUID, months: Int, scope: String, memberId: UUID?): NetWorthTrend {
        households.get(householdId)
        val window = months.coerceIn(1, 120)

        val memberFilter = when (scope) {
            "household" -> null
            "me" -> households.members(householdId).firstOrNull { it.isMe }?.id
                ?: throw ApiException.badRequest("no_member", "You aren't a member of this household.")
            "member" -> memberId
                ?: throw ApiException.badRequest("member_required", "Choose whose trend to show.")
            else -> throw ApiException.badRequest(
                "scope_invalid", "Scope must be me, household or member.",
            )
        }

        val points = jdbc.query(
            """
            with months as (
              select (date_trunc('month', current_date) - (n || ' months')::interval
                     + interval '1 month - 1 day')::date as as_of
              from generate_series(:months - 1, 0, -1) as n
            ),
            -- Each holding's value as it stood at each month end: the latest
            -- snapshot on or before that date, falling back to what was paid.
            asset_points as (
              select m.as_of,
                     sum(
                       coalesce(
                         (select v.value from valuations v
                           where v.investment_id = i.id and v.as_of_date <= m.as_of
                           order by v.as_of_date desc limit 1),
                         i.invested_amount, 0
                       ) * o.share_pct / 100.0
                     ) as assets
              from months m
              join investments i
                on i.household_id = :hid
               and i.deleted_at is null
               and i.created_at::date <= m.as_of
              join investment_ownerships o on o.investment_id = i.id
              where (:memberId::uuid is null or o.member_id = :memberId::uuid)
              group by m.as_of
            ),
            debt_points as (
              select m.as_of,
                     sum(
                       coalesce(
                         (select b.outstanding from liability_balances b
                           where b.liability_id = l.id and b.as_of_date <= m.as_of
                           order by b.as_of_date desc limit 1),
                         0
                       ) * h.responsibility_pct / 100.0
                     ) as liabilities
              from months m
              join liabilities l
                on l.household_id = :hid
               and l.deleted_at is null
               and l.created_at::date <= m.as_of
              join liability_holders h on h.liability_id = l.id
              where (:memberId::uuid is null or h.member_id = :memberId::uuid)
              group by m.as_of
            )
            select m.as_of,
                   coalesce(a.assets, 0)      as assets,
                   coalesce(d.liabilities, 0) as liabilities
            from months m
            left join asset_points a on a.as_of = m.as_of
            left join debt_points  d on d.as_of = m.as_of
            order by m.as_of
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("hid", householdId)
                .addValue("months", window)
                .addValue("memberId", memberFilter),
        ) { rs, _ ->
            val assets = rs.getBigDecimal("assets")
            val liabilities = rs.getBigDecimal("liabilities")
            val net = assets - liabilities
            TrendPoint(
                date = rs.getDate("as_of").toLocalDate(),
                assets = assets,
                liabilities = liabilities,
                netWorth = net,
                netWorthFormatted = IndianNumbers.rupees(net),
            )
        }

        val historyBeginsAt = jdbc.query(
            """
            select min(created_at)::date as began from (
              select i.created_at from investments i
                where i.household_id = :hid and i.deleted_at is null
              union all
              select l.created_at from liabilities l
                where l.household_id = :hid and l.deleted_at is null
            ) all_records
            """.trimIndent(),
            mapOf("hid" to householdId),
        ) { rs, _ -> rs.getDate("began")?.toLocalDate() }.firstOrNull()

        return NetWorthTrend(
            scope = scope,
            points = points,
            historyBeginsAt = historyBeginsAt,
            basis = "Drawn from the values and balances you've recorded. " +
                "Nothing between snapshots is guessed.",
        )
    }
}
