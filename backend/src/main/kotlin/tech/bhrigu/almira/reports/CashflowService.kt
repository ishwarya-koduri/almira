package tech.bhrigu.almira.reports

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.common.IndianNumbers
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.reminder.DueDates
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class CashflowEntry(
    val date: LocalDate,
    /** "out" or "in". */
    val direction: String,
    val kind: String,
    val title: String,
    val amount: BigDecimal,
    val amountFormatted: String,
    val recordId: UUID,
    val recordType: String,
)

data class CashflowMonth(
    val month: String,
    val out: BigDecimal,
    val outFormatted: String,
    val inflow: BigDecimal,
    val inflowFormatted: String,
    val net: BigDecimal,
    val netFormatted: String,
    val entries: List<CashflowEntry>,
)

data class Cashflow(val from: LocalDate, val until: LocalDate, val months: List<CashflowMonth>)

/**
 * Money in and money out, month by month (docs/10 Epic 1.6).
 *
 * Built from the records themselves rather than only from reminders. A SIP that
 * nobody made a reminder for is still money leaving the account on the 5th, and
 * a calendar that only showed what someone remembered to ask about would be
 * exactly as incomplete as the spreadsheet this replaces.
 *
 * Everything here is a projection from what is recorded — a known EMI, a known
 * premium date. Nothing is forecast, estimated or extrapolated; docs/08 §6 is
 * clear that the moment this starts predicting, it stops being a record.
 */
@Service
class CashflowService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
) {

    @Transactional(readOnly = true)
    fun calendar(householdId: UUID, months: Int): Cashflow {
        households.get(householdId)
        val from = LocalDate.now()
        val until = from.plusMonths(months.coerceIn(1, 24).toLong())

        val entries = buildList {
            addAll(emiEntries(householdId, from, until))
            addAll(sipEntries(householdId, from, until))
            addAll(premiumEntries(householdId, from, until))
            addAll(rentEntries(householdId, from, until))
            addAll(maturityEntries(householdId, from, until))
        }.sortedBy { it.date }

        val byMonth = entries.groupBy { it.date.withDayOfMonth(1) }
        val months = generateSequence(from.withDayOfMonth(1)) { it.plusMonths(1) }
            .takeWhile { !it.isAfter(until) }
            .map { month ->
                val group = byMonth[month].orEmpty()
                val out = group.filter { it.direction == "out" }
                    .fold(BigDecimal.ZERO) { a, e -> a + e.amount }
                val inflow = group.filter { it.direction == "in" }
                    .fold(BigDecimal.ZERO) { a, e -> a + e.amount }
                CashflowMonth(
                    month = month.toString().take(7),
                    out = out, outFormatted = IndianNumbers.rupees(out),
                    inflow = inflow, inflowFormatted = IndianNumbers.rupees(inflow),
                    net = inflow - out, netFormatted = IndianNumbers.rupees(inflow - out),
                    entries = group,
                )
            }
            .toList()

        return Cashflow(from, until, months)
    }

    private fun emiEntries(householdId: UUID, from: LocalDate, until: LocalDate) = jdbc.query(
        """
        select l.id, l.title, l.emi_amount, l.emi_day
        from liabilities l
        where l.household_id = :hid and l.deleted_at is null and l.status = 'active'
          and l.emi_day is not null and l.emi_amount is not null
        """.trimIndent(),
        mapOf("hid" to householdId),
    ) { rs, _ ->
        Triple(
            rs.getObject("id", UUID::class.java),
            rs.getString("title") to rs.getBigDecimal("emi_amount"),
            rs.getInt("emi_day"),
        )
    }.flatMap { (id, titleAmount, day) ->
        DueDates.monthlyOccurrences(day, from, until).map {
            entry(it, "out", "emi", titleAmount.first, titleAmount.second, id, "liability")
        }
    }

    private fun sipEntries(householdId: UUID, from: LocalDate, until: LocalDate) = jdbc.query(
        """
        select i.id, i.title,
               (i.attributes ->> 'sip_amount') as amount,
               (i.attributes ->> 'sip_day')    as day
        from investments i
        where i.household_id = :hid and i.deleted_at is null and i.status = 'active'
          -- jsonb_exists rather than the `?` operator: NamedParameterJdbcTemplate
          -- cannot tell `?` from a placeholder and refuses to mix the two.
          and jsonb_exists(i.attributes, 'sip_day')
          and jsonb_exists(i.attributes, 'sip_amount')
        """.trimIndent(),
        mapOf("hid" to householdId),
    ) { rs, _ ->
        Triple(
            rs.getObject("id", UUID::class.java),
            rs.getString("title") to rs.getString("amount")?.toBigDecimalOrNull(),
            rs.getString("day")?.toIntOrNull(),
        )
    }.filter { it.second.second != null && it.third != null }
        .flatMap { (id, titleAmount, day) ->
            DueDates.monthlyOccurrences(day!!.coerceIn(1, 31), from, until).map {
                entry(it, "out", "sip", titleAmount.first, titleAmount.second!!, id, "investment")
            }
        }

    private fun premiumEntries(householdId: UUID, from: LocalDate, until: LocalDate) = jdbc.query(
        """
        select i.id, i.title,
               (i.attributes ->> 'premium_amount')    as amount,
               (i.attributes ->> 'premium_due_date')  as due,
               (i.attributes ->> 'premium_frequency') as frequency
        from investments i
        where i.household_id = :hid and i.deleted_at is null and i.status = 'active'
          and jsonb_exists(i.attributes, 'premium_due_date')
        """.trimIndent(),
        mapOf("hid" to householdId),
    ) { rs, _ ->
        listOf(
            rs.getObject("id", UUID::class.java), rs.getString("title"),
            rs.getString("amount"), rs.getString("due"), rs.getString("frequency"),
        )
    }.flatMap { row ->
        val id = row[0] as UUID
        val title = row[1] as String
        val amount = (row[2] as String?)?.toBigDecimalOrNull() ?: return@flatMap emptyList()
        val due = runCatching { LocalDate.parse(row[3] as String) }.getOrNull()
            ?: return@flatMap emptyList()
        val recurrence = when (row[4] as String?) {
            "monthly" -> "monthly"; "quarterly" -> "quarterly"
            "half_yearly" -> "half_yearly"; "single" -> "none"; else -> "yearly"
        }

        val dates = mutableListOf<LocalDate>()
        var cursor = due
        // Catch up past dates so a premium set last year still shows its next one.
        while (cursor.isBefore(from) && recurrence != "none") {
            cursor = DueDates.advance(cursor, recurrence, due.dayOfMonth)
        }
        while (!cursor.isAfter(until) && (dates.isEmpty() || recurrence != "none")) {
            if (!cursor.isBefore(from)) dates += cursor
            if (recurrence == "none") break
            cursor = DueDates.advance(cursor, recurrence, due.dayOfMonth)
        }
        dates.map { entry(it, "out", "premium", title, amount, id, "investment") }
    }

    private fun rentEntries(householdId: UUID, from: LocalDate, until: LocalDate) = jdbc.query(
        """
        select i.id, i.title, (i.attributes ->> 'rent_received') as rent
        from investments i
        where i.household_id = :hid and i.deleted_at is null and i.status = 'active'
          and jsonb_exists(i.attributes, 'rent_received')
        """.trimIndent(),
        mapOf("hid" to householdId),
    ) { rs, _ ->
        Triple(
            rs.getObject("id", UUID::class.java), rs.getString("title"),
            rs.getString("rent")?.toBigDecimalOrNull(),
        )
    }.filter { it.third != null && it.third!!.signum() > 0 }
        .flatMap { (id, title, rent) ->
            // Rent has no recorded day, so the 1st is the honest default rather
            // than a guess dressed up as precision.
            DueDates.monthlyOccurrences(1, from, until).map {
                entry(it, "in", "rent", title, rent!!, id, "investment")
            }
        }

    private fun maturityEntries(householdId: UUID, from: LocalDate, until: LocalDate) = jdbc.query(
        """
        select i.id, i.title, i.maturity_date, v.effective_value
        from investments i
        left join investment_value v on v.investment_id = i.id
        where i.household_id = :hid and i.deleted_at is null and i.status = 'active'
          and i.maturity_date between :from and :until
        """.trimIndent(),
        mapOf("hid" to householdId, "from" to from, "until" to until),
    ) { rs, _ ->
        entry(
            rs.getDate("maturity_date").toLocalDate(), "in", "maturity",
            rs.getString("title"), rs.getBigDecimal("effective_value") ?: BigDecimal.ZERO,
            rs.getObject("id", UUID::class.java), "investment",
        )
    }

    private fun entry(
        date: LocalDate, direction: String, kind: String, title: String,
        amount: BigDecimal, recordId: UUID, recordType: String,
    ) = CashflowEntry(
        date = date, direction = direction, kind = kind, title = title,
        amount = amount, amountFormatted = IndianNumbers.rupees(amount),
        recordId = recordId, recordType = recordType,
    )
}
