package tech.bhrigu.almira.money

import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class RateQuote(
    val base: String,
    val quote: String,
    val rate: BigDecimal,
    val asOf: LocalDate,
    val source: String,
)

/**
 * Where a conversion rate comes from.
 *
 * The interface exists so that plugging in a live feed later is a new class and
 * a configuration flag, not a rewrite: nothing above this knows whether a rate
 * was typed in by hand or fetched this morning. Every rate carries its date and
 * its source, and both travel all the way to the screen — a converted figure
 * without them is a number pretending to be a fact.
 *
 * Implementations are consulted in [Order]; the first with an answer wins.
 */
interface RateSource {
    val name: String

    /** The best rate on or before [on], or null. Null is a legitimate answer. */
    fun rate(base: String, quote: String, on: LocalDate, householdId: UUID?): RateQuote?
}

/**
 * Rates from the database: seeded ones that ship with the product, and whatever
 * a household has recorded for itself. A household's own rate wins, because
 * they know what they actually got.
 */
@Component
@Order(100)
class StoredRateSource(private val jdbc: NamedParameterJdbcTemplate) : RateSource {

    override val name = "stored"

    override fun rate(base: String, quote: String, on: LocalDate, householdId: UUID?): RateQuote? =
        jdbc.query(
            """
            select base_currency, quote_currency, rate, as_of, source
            from exchange_rates
            where base_currency = :base and quote_currency = :quote
              and as_of <= :on
              and (household_id is null or household_id = :hid)
            order by (household_id is not null) desc, as_of desc
            limit 1
            """.trimIndent(),
            mapOf("base" to base, "quote" to quote, "on" to on, "hid" to householdId),
        ) { rs, _ ->
            RateQuote(
                base = rs.getString("base_currency"),
                quote = rs.getString("quote_currency"),
                rate = rs.getBigDecimal("rate"),
                asOf = rs.getDate("as_of").toLocalDate(),
                source = rs.getString("source"),
            )
        }.firstOrNull()
}
