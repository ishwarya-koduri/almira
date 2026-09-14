package tech.bhrigu.almira.market

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate

/**
 * Valuations from the day's published prices, end to end through the API.
 *
 * The job is driven by hand with the fixture files — nothing here reaches AMFI
 * or an exchange, and the application context these run in has no job at all,
 * because the feed is off unless switched on. "Today" is pinned to three days
 * after the fixtures' date, so the tests do not age out.
 */
@DisplayName("Price feed: valued at NAV, never over what someone entered")
class PriceFeedApiTest : ApiTestBase() {

    @Autowired private lateinit var context: ApplicationContext
    @Autowired private lateinit var valuations: PriceFeedValuations

    private lateinit var owner: String
    private lateinit var householdId: String

    private val today = LocalDate.parse("2026-09-14")
    private fun fixture(name: String) = javaClass.getResourceAsStream("/market/$name")!!.readAllBytes()
    private val navFile by lazy { fixture("NAVAll-2026-09-11-excerpt.txt") }
    private val nseFile by lazy { fixture("BhavCopy_NSE_CM_0_0_0_20260911_F_0000-excerpt.csv.zip") }
    private val bseFile by lazy { fixture("BhavCopy_BSE_CM_0_0_0_20260911_F_0000-excerpt.CSV") }

    @BeforeEach
    fun setUp() {
        owner = signIn()
        householdId = createHousehold(owner, "Koduri", "private", "Ishwarya").path("id").asText()
    }

    private fun fund(identifier: String?, units: String = "120"): String = post(
        "/api/v1/households/$householdId/investments", owner,
        mapOf(
            "typeId" to typeId(owner, householdId, "mf_lumpsum"),
            "title" to "Parag Parikh Flexi Cap",
            "investedAmount" to 8000,
            "quantity" to BigDecimal(units),
            "attributes" to buildMap {
                put("scheme_name", "Parag Parikh Flexi Cap Fund - Direct Growth")
                identifier?.let { put("isin", it) }
            },
        ),
    ).also { check(it.statusCode.is2xxSuccessful) { it.body!! } }.json().path("id").asText()

    private fun share(symbol: String, exchange: String?, units: Int = 10): String = post(
        "/api/v1/households/$householdId/investments", owner,
        mapOf(
            "typeId" to typeId(owner, householdId, "stock_listed"),
            "title" to symbol,
            "quantity" to units,
            "attributes" to buildMap {
                put("symbol", symbol)
                exchange?.let { put("exchange", it) }
            },
        ),
    ).also { check(it.statusCode.is2xxSuccessful) { it.body!! } }.json().path("id").asText()

    private fun holding(id: String) = get("/api/v1/households/$householdId/investments/$id", owner).json()

    private fun amfi(on: LocalDate = today) = valuations.apply("amfi", AmfiNavFile.parse(navFile), on)

    @Test
    fun `nothing that fetches prices exists unless it is switched on`() {
        assertThat(context.getBeansOfType(PriceFeedJob::class.java)).isEmpty()
        assertThat(context.getBeansOfType(MarketFileFetcher::class.java)).isEmpty()
        assertThat(context.getBean(MarketDataProperties::class.java).prices.enabled).isFalse()
    }

    @Test
    fun `a fund with its ISIN and units is valued at the day's NAV, labelled with where and when`() {
        val id = fund("inf879o01027")

        amfi()

        val record = holding(id)
        // 120 units × ₹89.5712
        assertThat(record.path("value").decimalValue()).isEqualByComparingTo("10748.54")
        assertThat(record.path("valueFormatted").asText()).isEqualTo("₹10,748")
        assertThat(record.path("valueBasis").asText()).isEqualTo("valued")
        assertThat(record.path("valuedOn").asText()).describedAs("the NAV's date, not the day the job ran").isEqualTo("2026-09-11")
        assertThat(record.path("valuationSource").asText()).isEqualTo("price_feed")
        assertThat(record.path("priceSource").asText()).isEqualTo("amfi")
        assertThat(record.path("unitPrice").decimalValue()).isEqualByComparingTo("89.5712")

        val trail = db.queryForMap(
            "select diff ->> 'source' as source, actor_user_id from activity_log " +
                "where household_id = ?::uuid and action = 'investment.valuations_from_prices'",
            householdId,
        )
        assertThat(trail["source"]).isEqualTo("amfi")
        assertThat(trail["actor_user_id"]).isNull()
    }

    @Test
    fun `the AMFI scheme code works as well as the ISIN`() {
        val id = fund("120716", units = "10")
        amfi()
        assertThat(holding(id).path("value").decimalValue()).isEqualByComparingTo("1647.89")
    }

    @Test
    fun `a fund that names no scheme is not guessed at from its name`() {
        val id = fund(null)
        amfi()
        assertThat(holding(id).path("valueBasis").asText()).isEqualTo("at_cost")
    }

    @Test
    fun `a value someone entered on or after the NAV's date is left alone`() {
        val id = fund("INF879O01027")
        post(
            "/api/v1/households/$householdId/investments/$id/valuations", owner,
            mapOf("value" to 11000, "asOfDate" to "2026-09-12"),
        )

        amfi()

        val record = holding(id)
        assertThat(record.path("value").decimalValue()).isEqualByComparingTo("11000")
        assertThat(record.path("valuationSource").asText()).isEqualTo("manual")
        assertThat(record.hasNonNull("priceSource")).isFalse()
    }

    @Test
    fun `an older entered value stays in the history beside the newer NAV`() {
        val id = fund("INF879O01027")
        post(
            "/api/v1/households/$householdId/investments/$id/valuations", owner,
            mapOf("value" to 9500, "asOfDate" to "2026-09-01"),
        )

        amfi()

        assertThat(holding(id).path("valuationSource").asText()).isEqualTo("price_feed")
        val history = get("/api/v1/households/$householdId/investments/$id/valuations", owner).json()
        assertThat(history.map { it.path("source").asText() }).containsExactly("price_feed", "manual")
        assertThat(history[1].path("value").decimalValue()).isEqualByComparingTo("9500")
        assertThat(history[0].path("priceSource").asText()).isEqualTo("amfi")
    }

    @Test
    fun `a value typed over the same day's NAV becomes the person's, and stops claiming to be a NAV`() {
        val id = fund("INF879O01027")
        amfi()

        post(
            "/api/v1/households/$householdId/investments/$id/valuations", owner,
            mapOf("value" to 12000, "asOfDate" to "2026-09-11"),
        )

        val record = holding(id)
        assertThat(record.path("value").decimalValue()).isEqualByComparingTo("12000")
        assertThat(record.path("valuationSource").asText()).isEqualTo("manual")
        assertThat(record.hasNonNull("unitPrice")).isFalse()

        amfi()
        assertThat(holding(id).path("value").decimalValue())
            .describedAs("and the next run does not take it back")
            .isEqualByComparingTo("12000")
    }

    @Test
    fun `running the same file twice writes nothing the second time`() {
        val id = fund("INF879O01027")
        amfi()
        amfi()
        assertThat(
            db.queryForObject("select count(*) from valuations where investment_id = ?::uuid", Int::class.java, id),
        ).isEqualTo(1)
    }

    @Test
    fun `a NAV more than a week old is not today's NAV`() {
        val id = fund("INF879O01027")
        amfi(on = LocalDate.parse("2026-09-30"))
        assertThat(holding(id).path("valueBasis").asText()).isEqualTo("at_cost")
    }

    @Test
    fun `a share is valued at its closing price, from the exchange it names`() {
        val onNse = share("INFY", exchange = "nse")
        val onBse = share("INFY", exchange = "bse")
        val unnamed = share("TCS", exchange = null)

        valuations.apply("nse", BhavcopyFile.parse(nseFile, "nse"), today)
        valuations.apply("bse", BhavcopyFile.parse(bseFile, "bse"), today)

        assertThat(holding(onNse).path("value").decimalValue()).isEqualByComparingTo("10377.00")
        assertThat(holding(onNse).path("priceSource").asText())
            .describedAs("BSE's file, read second, does not overwrite NSE's price")
            .isEqualTo("nse")
        assertThat(holding(onBse).path("value").decimalValue()).isEqualByComparingTo("10382.00")
        assertThat(holding(onBse).path("priceSource").asText()).isEqualTo("bse")
        assertThat(holding(unnamed).path("priceSource").asText()).isEqualTo("nse")
    }

    /** Only the feed can put a published-price label on a valuation. */
    @Test
    fun `the application's own database role cannot write a price-fed valuation`() {
        val id = fund("INF879O01027")
        val appUser = tech.bhrigu.almira.support.TestInfra.dbAppUser
        val app = org.springframework.jdbc.datasource.DriverManagerDataSource(
            tech.bhrigu.almira.support.TestInfra.dbUrl, appUser, tech.bhrigu.almira.support.TestInfra.dbAppPassword,
        )
        val ownerUserId = db.queryForObject(
            "select created_by from investments where id = ?::uuid", java.util.UUID::class.java, id,
        )
        val refused = runCatching {
            org.springframework.jdbc.core.JdbcTemplate(app).execute { connection: java.sql.Connection ->
                connection.autoCommit = false
                connection.createStatement().use { it.execute("select set_config('app.user_id', '$ownerUserId', true)") }
                connection.createStatement().use {
                    it.executeUpdate(
                        "insert into valuations (investment_id, as_of_date, value, source, price_source, unit_price, instrument) " +
                            "values ('$id', date '2026-09-10', 1, 'price_feed', 'amfi', 1, '122639')",
                    )
                }
                connection.rollback()
            }
        }
        assertThat(refused.exceptionOrNull())
            .describedAs("row-level security refuses the label, even on the owner's own holding")
            .isNotNull()
            .rootCause()
            .hasMessageContaining("row-level security")
    }

    @Test
    fun `the job reads each file from its address for the day, and a missing one is skipped`() {
        val asked = mutableListOf<String>()
        val fetcher = MarketFileFetcher { url, _: Duration ->
            asked += url
            when {
                url.contains("NAVAll") -> navFile
                url.contains("NSE") -> nseFile
                else -> throw MarketFileUnavailable("HTTP 404 from www.bseindia.com")
            }
        }
        val job = PriceFeedJob(MarketDataProperties(prices = MarketDataProperties.Prices(enabled = true)), fetcher, valuations)
        val id = fund("INF879O01027")

        val results = job.run(today)

        assertThat(asked).containsExactly(
            "https://portal.amfiindia.com/spages/NAVAll.txt",
            "https://nsearchives.nseindia.com/content/cm/BhavCopy_NSE_CM_0_0_0_20260914_F_0000.csv.zip",
            "https://www.bseindia.com/download/BhavCopy/Equity/BhavCopy_BSE_CM_0_0_0_20260914_F_0000.CSV",
        )
        assertThat(results.map { it.source }).containsExactly("amfi", "nse")
        assertThat(holding(id).path("priceSource").asText()).isEqualTo("amfi")
    }

    @Test
    fun `a private holding's price-fed value is as private as the holding`() {
        val id = fund("INF879O01027")
        amfi()

        val stranger = signIn()
        assertThat(get("/api/v1/households/$householdId/investments/$id", stranger).status())
            .isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(get("/api/v1/households/$householdId/investments/$id/valuations", stranger).status())
            .isEqualTo(HttpStatus.NOT_FOUND)
    }
}
