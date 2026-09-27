package tech.almira.market

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** What one run of one file did. Counts only. */
data class PriceFeedResult(
    val source: String,
    val prices: Int,
    /** Holdings that named a fund or share found in the file. */
    val matched: Int,
    /** Valuations written or corrected. */
    val valued: Int,
    /** Holdings left alone because someone entered a value on or after the price's date. */
    val keptEnteredValue: Int,
)

/**
 * Turns the day's published prices into valuations (docs/13 §6).
 *
 * **Which holdings.** Active, in rupees, with units, of a built-in type whose
 * price is published: a mutual fund that gives its ISIN or AMFI scheme code, a
 * listed share or a REIT that gives its ISIN or ticker. Nothing is inferred
 * from a name — "HDFC Flexi Cap" matches a dozen plans and options with
 * different NAVs, and a confident wrong NAV is worse than none.
 *
 * **Never over what someone entered.** A valuation someone typed or imported
 * dated on or after the price's date wins, and the holding is left alone. One
 * dated before stays in the history; the new figure is current, and the screen
 * says so beside it ("Valued at NAV as of 11 Sep 2026", with the entered value
 * and its date underneath). A person who wants their own figure back enters it,
 * or clears the scheme code to stop the daily valuation.
 *
 * **Which connection.** A scheduled job has no user, so this writes on the
 * system connection, as the reminder sweep does. It reads nothing about a
 * holding beyond what it needs to match a price, sends nothing anywhere, and a
 * valuation it writes is visible to exactly the people who could already see
 * the holding — the valuation policies follow the investment's.
 *
 * Always present, and harmless: without [PriceFeedJob], which exists only when
 * switched on, nothing calls it except the tests.
 */
@Component
class PriceFeedValuations(
    @Qualifier("systemJdbcBypassingRls") private val system: NamedParameterJdbcTemplate,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun apply(source: String, prices: List<MarketPrice>, today: LocalDate): PriceFeedResult {
        // Old lines are real — AMFI still lists schemes that stopped in 2017 —
        // but a price from last month is not "today's NAV", and a date in the
        // future is a broken file.
        val usable = prices.filter { it.source == source && !it.asOf.isAfter(today) && !it.asOf.isBefore(today.minusDays(MAX_AGE_DAYS)) }
        if (usable.isEmpty()) return PriceFeedResult(source, 0, 0, 0, 0)

        val byIsin = usable.flatMap { price -> price.isins.map { it to price } }.groupBy({ it.first }, { it.second })
        val byCode = usable.associateBy { it.code }
        val bySymbol = usable.filter { it.symbol != null && (source != "nse" || it.series in NSE_EQUITY_SERIES) }
            .groupBy { it.symbol!! }

        var matched = 0
        var valued = 0
        var kept = 0
        val perHousehold = mutableMapOf<UUID, Int>()

        candidates(source).forEach { holding ->
            val price = match(holding, source, today, byIsin, byCode, bySymbol) ?: return@forEach
            matched++

            val latestEntered = system.queryForObject(
                "select max(as_of_date) from valuations where investment_id = :id and source <> 'price_feed'",
                mapOf("id" to holding.id), LocalDate::class.java,
            )
            if (latestEntered != null && !latestEntered.isBefore(price.asOf)) {
                kept++
                return@forEach
            }

            val value = holding.quantity.multiply(price.price).setScale(2, RoundingMode.HALF_UP)
            val written = system.update(
                """
                insert into valuations (investment_id, as_of_date, value, quantity, source,
                                        price_source, unit_price, instrument)
                values (:id, :asOf, :value, :quantity, 'price_feed', :priceSource, :unitPrice, :instrument)
                on conflict (investment_id, as_of_date) do update
                  set value = excluded.value, quantity = excluded.quantity,
                      price_source = excluded.price_source, unit_price = excluded.unit_price,
                      instrument = excluded.instrument
                  -- Only ever corrects its own row. The check above already
                  -- keeps it off an entered one; this makes it impossible.
                  where valuations.source = 'price_feed'
                    and (valuations.value, valuations.unit_price, valuations.instrument)
                        is distinct from (excluded.value, excluded.unit_price, excluded.instrument)
                """.trimIndent(),
                mapOf(
                    "id" to holding.id, "asOf" to price.asOf, "value" to value, "quantity" to holding.quantity,
                    "priceSource" to source, "unitPrice" to price.price,
                    "instrument" to (if (source == "amfi") price.code else price.isins.firstOrNull() ?: price.code),
                ),
            )
            if (written > 0) {
                valued++
                perHousehold.merge(holding.householdId, 1, Int::plus)
            }
        }

        // One line per household per file rather than one per holding: a family
        // with forty funds should not find forty entries in their activity
        // every night. The valuations themselves carry the detail.
        perHousehold.forEach { (householdId, count) ->
            system.update(
                """
                insert into activity_log (household_id, actor_user_id, action, entity_type, entity_id, diff)
                values (:hid, null, 'investment.valuations_from_prices', 'household', :hid, cast(:diff as jsonb))
                """.trimIndent(),
                mapOf(
                    "hid" to householdId,
                    "diff" to mapper.writeValueAsString(mapOf("source" to source, "count" to count, "asOf" to usable.maxOf { it.asOf }.toString())),
                ),
            )
        }

        log.info(
            "price feed {}: {} prices, {} holdings matched, {} valued, {} kept an entered value",
            source, usable.size, matched, valued, kept,
        )
        return PriceFeedResult(source, usable.size, matched, valued, kept)
    }

    private data class Holding(
        val id: UUID,
        val householdId: UUID,
        val typeCode: String,
        val quantity: BigDecimal,
        val identifier: String?,
        val symbol: String?,
        val exchange: String?,
    )

    private fun candidates(source: String): List<Holding> = system.query(
        """
        select i.id, i.household_id, t.code as type_code, i.quantity,
               nullif(upper(trim(i.attributes ->> 'isin')), '') as identifier,
               nullif(upper(trim(i.attributes ->> 'symbol')), '') as symbol,
               nullif(lower(trim(i.attributes ->> 'exchange')), '') as exchange
        from investments i
        join investment_types t on t.id = i.type_id and t.household_id is null
        where i.deleted_at is null
          and i.status = 'active'
          and i.currency = 'INR'
          and i.quantity > 0
          and t.code in (:types)
        """.trimIndent(),
        mapOf("types" to if (source == "amfi") FUND_TYPES else LISTED_TYPES),
    ) { rs, _ ->
        Holding(
            id = rs.getObject("id", UUID::class.java),
            householdId = rs.getObject("household_id", UUID::class.java),
            typeCode = rs.getString("type_code"),
            quantity = rs.getBigDecimal("quantity"),
            identifier = rs.getString("identifier"),
            symbol = rs.getString("symbol"),
            exchange = rs.getString("exchange"),
        )
    }

    private fun match(
        holding: Holding,
        source: String,
        today: LocalDate,
        byIsin: Map<String, List<MarketPrice>>,
        byCode: Map<String, MarketPrice>,
        bySymbol: Map<String, List<MarketPrice>>,
    ): MarketPrice? {
        if (source == "amfi") {
            val id = holding.identifier ?: return null
            return if (id.all(Char::isDigit)) byCode[id] else byIsin[id]?.maxByOrNull { it.asOf }
        }
        // A share listed on both exchanges is valued from the one the holding
        // names; "both" or nothing means NSE, and BSE only for what NSE lacks.
        val preferred = if (holding.exchange == "bse") "bse" else "nse"
        if (source != preferred && isValuedFrom(holding, preferred, today)) return null
        holding.identifier?.let { isin -> return byIsin[isin]?.maxByOrNull { it.asOf } }
        val symbol = holding.symbol ?: return null
        return bySymbol[symbol]?.singleOrNull()
    }

    /**
     * Whether the holding's own exchange has priced it this past week, in which
     * case the other exchange leaves it alone. Answered from what was written:
     * the job reads NSE before BSE, so NSE's valuation for the day is already
     * there when BSE's file arrives.
     */
    private fun isValuedFrom(holding: Holding, exchange: String, today: LocalDate): Boolean = system.queryForObject(
        """
        select exists (select 1 from valuations
                       where investment_id = :id and source = 'price_feed'
                         and price_source = :exchange and as_of_date >= :since)
        """.trimIndent(),
        mapOf("id" to holding.id, "exchange" to exchange, "since" to today.minusDays(MAX_AGE_DAYS)), Boolean::class.java,
    ) == true

    companion object {
        const val MAX_AGE_DAYS = 7L
        val FUND_TYPES = listOf("mf_sip", "mf_lumpsum")
        val LISTED_TYPES = listOf("stock_listed", "reit")

        /** NSE series for ordinary shares, trade-for-trade and SME segments, and REITs/InvITs. Not bonds, not SGBs. */
        val NSE_EQUITY_SERIES = setOf("EQ", "BE", "BZ", "SM", "ST", "RR", "IV")
    }
}

/**
 * The nightly price feed. **Off by default**: exists only with
 * `almira.market-data.prices.enabled=true` (docs/13 §6).
 *
 * Reads AMFI, then NSE, then BSE — in that order, so a share on both exchanges
 * is valued from NSE unless the holding names BSE — and hands each file to
 * [PriceFeedValuations]. A file that is not there (a holiday, a late
 * publication) is skipped with a line in the log; yesterday's valuation stays,
 * with its date.
 */
@Component
@ConditionalOnProperty(name = ["almira.market-data.prices.enabled"], havingValue = "true")
class PriceFeedJob(
    private val properties: MarketDataProperties,
    private val fetcher: MarketFileFetcher,
    private val valuations: PriceFeedValuations,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** After AMFI's evening publication; both bhavcopies are out by then. */
    @Scheduled(cron = "\${almira.market-data.prices.cron:0 30 23 * * *}", zone = "Asia/Kolkata")
    fun scheduled() {
        runCatching { run(LocalDate.now(INDIA)) }.onFailure {
            log.warn("price feed run failed: {}", it.javaClass.simpleName)
        }
    }

    fun run(today: LocalDate): List<PriceFeedResult> =
        ORDER.filter { it in properties.prices.sources }.mapNotNull { source ->
            runCatching {
                val bytes = fetcher.fetch(url(source, today), properties.prices.timeout)
                val prices = if (source == "amfi") AmfiNavFile.parse(bytes) else BhavcopyFile.parse(bytes, source)
                valuations.apply(source, prices, today)
            }.onFailure {
                log.warn("price feed {} skipped for {}: {}", source, today, it.message ?: it.javaClass.simpleName)
            }.getOrNull()
        }

    private fun url(source: String, day: LocalDate): String {
        val stamp = day.format(DateTimeFormatter.BASIC_ISO_DATE)
        return when (source) {
            "amfi" -> properties.prices.amfiUrl
            "nse" -> properties.prices.nseUrl.replace("{date}", stamp)
            else -> properties.prices.bseUrl.replace("{date}", stamp)
        }
    }

    private companion object {
        val ORDER = listOf("amfi", "nse", "bse")
        val INDIA: ZoneId = ZoneId.of("Asia/Kolkata")
    }
}
