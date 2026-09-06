package tech.bhrigu.almira.money

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.security.RequestUserContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

data class Converted(
    val amount: BigDecimal,
    val currency: String,
    /** Null when no rate was available: the figure is simply not known. */
    val convertedAmount: BigDecimal?,
    val convertedCurrency: String,
    val rate: BigDecimal?,
    val rateAsOf: LocalDate?,
    val rateSource: String?,
    val note: String?,
)

data class RecordRate(
    val baseCurrency: String,
    val quoteCurrency: String,
    val rate: BigDecimal,
    val asOf: LocalDate = LocalDate.now(),
)

/**
 * Money in more than one currency (docs/07 §1).
 *
 * The rule is the same one the value model already follows: **store what is
 * true, convert only to display.** A holding in dirhams is worth what it is
 * worth in dirhams. The rupee figure beside it is a rendering, made with a rate
 * that has a date and a source attached — and where no rate exists, nothing is
 * invented: the conversion comes back null with a sentence explaining what is
 * missing, and totals say how much they had to leave out.
 */
@Service
class CurrencyService(
    private val sources: List<RateSource>,
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val audit: AuditService,
    private val userContext: RequestUserContext,
) {

    fun convert(
        amount: BigDecimal?,
        from: String,
        to: String,
        householdId: UUID? = null,
        on: LocalDate = LocalDate.now(),
    ): Converted {
        if (amount == null) {
            return Converted(BigDecimal.ZERO, from, null, to, null, null, null, "No amount recorded.")
        }
        if (from.equals(to, ignoreCase = true)) {
            return Converted(amount, from, amount, to, BigDecimal.ONE, null, null, null)
        }

        val quote = quoteFor(from.uppercase(), to.uppercase(), on, householdId)
            ?: return Converted(
                amount, from, null, to, null, null, null,
                "No $from→$to rate recorded, so this isn't included in the total. " +
                    "Add one and it will be.",
            )

        return Converted(
            amount = amount,
            currency = from,
            convertedAmount = amount.multiply(quote.rate).setScale(2, RoundingMode.HALF_UP),
            convertedCurrency = to,
            rate = quote.rate,
            rateAsOf = quote.asOf,
            rateSource = quote.source,
            note = if (quote.source == "seed") {
                "Converted at a rate that shipped with the app, dated ${quote.asOf}. " +
                    "Record your own for anything that matters."
            } else {
                null
            },
        )
    }

    /**
     * Direct, then inverted. An inverse is exact enough for display and saves a
     * household recording every pair twice; it keeps the original's date and
     * says where it came from.
     */
    fun quoteFor(from: String, to: String, on: LocalDate, householdId: UUID?): RateQuote? {
        sources.firstNotNullOfOrNull { it.rate(from, to, on, householdId) }?.let { return it }

        return sources.firstNotNullOfOrNull { it.rate(to, from, on, householdId) }?.let { inverse ->
            RateQuote(
                base = from, quote = to,
                rate = BigDecimal.ONE.divide(inverse.rate, 8, RoundingMode.HALF_UP),
                asOf = inverse.asOf,
                source = "${inverse.source} (inverted)",
            )
        }
    }

    @Transactional(readOnly = true)
    fun rates(householdId: UUID, quoteCurrency: String): List<RateQuote> {
        households.get(householdId)
        return jdbc.query(
            """
            select distinct on (base_currency)
                   base_currency, quote_currency, rate, as_of, source
            from exchange_rates
            where quote_currency = :quote and (household_id is null or household_id = :hid)
            order by base_currency, (household_id is not null) desc, as_of desc
            """.trimIndent(),
            mapOf("quote" to quoteCurrency.uppercase(), "hid" to householdId),
        ) { rs, _ ->
            RateQuote(
                rs.getString("base_currency"), rs.getString("quote_currency"),
                rs.getBigDecimal("rate"), rs.getDate("as_of").toLocalDate(), rs.getString("source"),
            )
        }
    }

    @Transactional
    fun record(householdId: UUID, input: RecordRate): RateQuote {
        val userId = userContext.require()
        households.get(householdId)
        val base = input.baseCurrency.uppercase()
        val quote = input.quoteCurrency.uppercase()
        if (base.length != 3 || quote.length != 3) {
            throw ApiException.badRequest("currency_invalid", "Use three-letter currency codes.")
        }
        if (base == quote) {
            throw ApiException.badRequest("currency_same", "Those are the same currency.")
        }
        if (input.rate <= BigDecimal.ZERO) {
            throw ApiException.badRequest("rate_invalid", "A rate has to be more than zero.")
        }

        jdbc.update(
            """
            insert into exchange_rates (base_currency, quote_currency, rate, as_of, source,
                                        household_id, created_by)
            values (:base, :quote, :rate, :asOf, 'manual', :hid, :by)
            on conflict (base_currency, quote_currency, as_of, source, household_id)
              do update set rate = excluded.rate
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("base", base).addValue("quote", quote).addValue("rate", input.rate)
                .addValue("asOf", input.asOf).addValue("hid", householdId).addValue("by", userId),
        )
        audit.record(
            householdId = householdId, actorUserId = userId, action = "rate.record",
            entityType = "household", entityId = householdId,
            diff = mapOf("pair" to "$base/$quote", "rate" to input.rate.toPlainString()),
        )
        return quoteFor(base, quote, input.asOf, householdId)!!
    }
}
