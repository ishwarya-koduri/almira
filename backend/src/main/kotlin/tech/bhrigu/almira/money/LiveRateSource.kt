package tech.bhrigu.almira.money

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tech.bhrigu.almira.market.MarketDataProperties
import tech.bhrigu.almira.market.MarketFileFetcher
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

/**
 * The European Central Bank's euro reference rates, as published in
 * eurofxref-daily.xml: one date, and one rate per currency for one euro.
 *
 * Read with two patterns rather than an XML parser. The file's shape has not
 * changed in two decades and is only ever these elements; a general parser
 * would bring entity expansion and external references along with it, which is
 * a poor trade for a file fetched from the internet.
 */
object EcbReferenceRates {

    data class Snapshot(val asOf: LocalDate, val perEuro: Map<String, BigDecimal>) {
        /** 1 [base] in [quote], crossed through the euro. Null when either is not published. */
        fun rate(base: String, quote: String): BigDecimal? {
            val basePerEuro = if (base == "EUR") BigDecimal.ONE else perEuro[base] ?: return null
            val quotePerEuro = if (quote == "EUR") BigDecimal.ONE else perEuro[quote] ?: return null
            return quotePerEuro.divide(basePerEuro, 8, RoundingMode.HALF_UP)
        }
    }

    private val TIME = Regex("""<Cube\s+time=['"](\d{4}-\d{2}-\d{2})['"]""")
    private val RATE = Regex("""<Cube\s+currency=['"]([A-Z]{3})['"]\s+rate=['"](\d+(?:\.\d+)?)['"]\s*/>""")

    fun parse(bytes: ByteArray): Snapshot {
        val text = bytes.toString(Charsets.UTF_8)
        val asOf = TIME.find(text)?.groupValues?.get(1)?.let(LocalDate::parse)
            ?: throw IllegalArgumentException("no reference date in the ECB file")
        val rates = RATE.findAll(text)
            .mapNotNull { match -> match.groupValues[2].toBigDecimalOrNull()?.takeIf { it.signum() > 0 }?.let { match.groupValues[1] to it } }
            .toMap()
        require(rates.isNotEmpty()) { "no rates in the ECB file" }
        return Snapshot(asOf, rates)
    }
}

/**
 * Live exchange rates, as a [RateSource] (docs/13 §6). **Off by default**:
 * this bean exists only with `almira.market-data.fx.enabled=true`.
 *
 * Once a day it reads the ECB file and does two things with it. It records a
 * shared rate into each currency the ECB publishes against the rupee, marked
 * `ecb` and dated with the ECB's own date — so [StoredRateSource] serves it, a
 * converted figure can always be traced, and a restart loses nothing. And it
 * keeps the day's file in memory to answer pairs nobody stored, crossed through
 * the euro.
 *
 * It never outranks a household. [StoredRateSource] is consulted first and
 * prefers a household's own rate over any shared one, because they know what
 * they actually got; this answers only where nothing is stored. A seeded rate
 * is simply older than the ECB's, and the newer date wins.
 *
 * Nothing about any household is sent anywhere: the request is a plain GET for
 * a public file.
 */
@Component
@Order(150)
@ConditionalOnProperty(name = ["almira.market-data.fx.enabled"], havingValue = "true")
class LiveRateSource(
    private val properties: MarketDataProperties,
    private val fetcher: MarketFileFetcher,
    @Qualifier("systemJdbcBypassingRls") private val system: NamedParameterJdbcTemplate,
) : RateSource {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = SOURCE

    @Volatile
    private var snapshot: EcbReferenceRates.Snapshot? = null

    override fun rate(base: String, quote: String, on: LocalDate, householdId: UUID?): RateQuote? {
        val current = snapshot?.takeIf { !it.asOf.isAfter(on) } ?: return null
        val rate = current.rate(base, quote) ?: return null
        return RateQuote(base, quote, rate, current.asOf, SOURCE)
    }

    /** After the ECB publishes (about 16:00 CET), in India's evening. */
    @Scheduled(cron = "\${almira.market-data.fx.cron:0 15 21 * * *}", zone = "Asia/Kolkata")
    fun scheduled() {
        runCatching { refresh() }.onFailure {
            // A missed day keeps yesterday's rate, which still carries its date.
            log.warn("exchange-rate refresh skipped: {}", it.message ?: it.javaClass.simpleName)
        }
    }

    /** Fetches and records today's rates. Returns how many rupee rates were recorded. */
    fun refresh(): Int {
        val fresh = EcbReferenceRates.parse(fetcher.fetch(properties.fx.url, properties.fx.timeout))
        snapshot = fresh

        var recorded = 0
        (fresh.perEuro.keys + "EUR").filter { it != QUOTE }.forEach { base ->
            val rate = fresh.rate(base, QUOTE) ?: return@forEach
            // Not ON CONFLICT: household_id is null for a shared rate, and nulls
            // are never equal in a unique index, so a second run would duplicate.
            recorded += system.update(
                """
                insert into exchange_rates (base_currency, quote_currency, rate, as_of, source)
                select :base, :quote, :rate, :asOf, :source
                where not exists (
                  select 1 from exchange_rates
                  where base_currency = :base and quote_currency = :quote and as_of = :asOf
                    and source = :source and household_id is null
                )
                """.trimIndent(),
                mapOf("base" to base, "quote" to QUOTE, "rate" to rate, "asOf" to fresh.asOf, "source" to SOURCE),
            )
        }
        log.info("exchange rates: ECB reference rates for {} ({} new rupee rates)", fresh.asOf, recorded)
        return recorded
    }

    companion object {
        const val SOURCE = "ecb"
        private const val QUOTE = "INR"
    }
}
