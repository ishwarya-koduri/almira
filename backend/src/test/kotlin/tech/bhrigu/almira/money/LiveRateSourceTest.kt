package tech.bhrigu.almira.money

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import tech.bhrigu.almira.market.MarketDataProperties
import tech.bhrigu.almira.market.MarketFileFetcher
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The live exchange-rate adapter, against the ECB's real file format.
 *
 * The fixture is eurofxref-daily.xml as published for 11 September 2026. The
 * adapter is built by hand around a fetcher that returns it, so nothing here
 * reaches the network — and the application context these tests run in must
 * not contain the adapter at all, because it is off unless switched on.
 */
@DisplayName("Live exchange rates (ECB), off by default")
class LiveRateSourceTest : ApiTestBase() {

    @Autowired private lateinit var context: ApplicationContext

    @Autowired
    @Qualifier("systemJdbcBypassingRls")
    private lateinit var system: NamedParameterJdbcTemplate

    private val fixture = javaClass.getResourceAsStream("/market/eurofxref-daily-2026-09-11.xml")!!.readAllBytes()
    private val fetched = mutableListOf<String>()
    private val fetcher = MarketFileFetcher { url, _ -> fetched += url; fixture }

    private lateinit var owner: String
    private lateinit var householdId: String

    @BeforeEach
    fun setUp() {
        owner = signIn()
        householdId = createHousehold(owner, "Koduri", "household", "Ishwarya").path("id").asText()
    }

    /** Shared rates are shared by every test in the suite: leave the seeded ones as they were. */
    @AfterEach
    fun removeRecordedRates() {
        db.update("delete from exchange_rates where source = ?", LiveRateSource.SOURCE)
    }

    private fun adapter() = LiveRateSource(MarketDataProperties(fx = MarketDataProperties.Fx(enabled = true)), fetcher, system)

    @Test
    fun `nothing that can fetch rates exists unless it is switched on`() {
        assertThat(context.getBeansOfType(LiveRateSource::class.java)).isEmpty()
        assertThat(context.getBeansOfType(MarketFileFetcher::class.java))
            .describedAs("no class that reaches the internet is created by default")
            .isEmpty()
        assertThat(context.getBean(MarketDataProperties::class.java).fx.enabled).isFalse()
    }

    @Test
    fun `reads the ECB file, and crosses through the euro`() {
        val snapshot = EcbReferenceRates.parse(fixture)

        assertThat(snapshot.asOf).isEqualTo(LocalDate.parse("2026-09-11"))
        assertThat(snapshot.perEuro["INR"]).isEqualByComparingTo("110.7675")
        assertThat(snapshot.perEuro["USD"]).isEqualByComparingTo("1.1592")
        // 110.7675 / 1.1592
        assertThat(snapshot.rate("USD", "INR")).isEqualByComparingTo("95.55512422")
        assertThat(snapshot.rate("EUR", "INR")).isEqualByComparingTo("110.7675")
        assertThat(snapshot.rate("AED", "INR")).describedAs("the ECB does not publish dirhams").isNull()
    }

    @Test
    fun `a file that is not the ECB's is refused rather than half-read`() {
        assertThatThrownBy { EcbReferenceRates.parse("<html>Service unavailable</html>".toByteArray()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a refresh records dated shared rupee rates marked ecb, once`() {
        val live = adapter()

        val first = live.refresh()
        val second = live.refresh()

        assertThat(fetched).containsOnly(MarketDataProperties.Fx().url)
        assertThat(first).isGreaterThan(20)
        assertThat(second).describedAs("a second run the same day records nothing new").isZero()
        val stored = db.queryForMap(
            "select rate, as_of, household_id from exchange_rates where source = 'ecb' and base_currency = 'USD' and quote_currency = 'INR'",
        )
        assertThat(stored["rate"] as BigDecimal).isEqualByComparingTo("95.55512422")
        assertThat(stored["as_of"].toString()).isEqualTo("2026-09-11")
        assertThat(stored["household_id"]).isNull()
    }

    @Test
    fun `a conversion then says it used the ECB rate, and as of when`() {
        adapter().refresh()

        val converted = get(
            "/api/v1/households/$householdId/rates/convert?amount=100&from=USD&to=INR&on=2026-09-14", owner,
        ).json()

        assertThat(converted.path("convertedAmount").decimalValue()).isEqualByComparingTo("9555.51")
        assertThat(converted.path("rateSource").asText()).isEqualTo("ecb")
        assertThat(converted.path("rateAsOf").asText()).isEqualTo("2026-09-11")
    }

    @Test
    fun `a household's own rate still wins over the live one`() {
        adapter().refresh()
        post(
            "/api/v1/households/$householdId/rates", owner,
            mapOf("baseCurrency" to "USD", "quoteCurrency" to "INR", "rate" to 90, "asOf" to "2026-09-01"),
        )

        val converted = get(
            "/api/v1/households/$householdId/rates/convert?amount=100&from=USD&to=INR&on=2026-09-14", owner,
        ).json()
        assertThat(converted.path("rateSource").asText()).describedAs("they know what they actually got").isEqualTo("manual")
        assertThat(converted.path("convertedAmount").decimalValue()).isEqualByComparingTo("9000.00")
    }

    @Test
    fun `as a rate source it answers pairs nobody stored, and never before its own date`() {
        val live = adapter()
        assertThat(live.rate("USD", "GBP", LocalDate.parse("2026-09-14"), null))
            .describedAs("nothing is known before the first refresh")
            .isNull()

        live.refresh()

        val usdGbp = live.rate("USD", "GBP", LocalDate.parse("2026-09-14"), null)!!
        assertThat(usdGbp.rate).isEqualByComparingTo("0.74029503")
        assertThat(usdGbp.source).isEqualTo("ecb")
        assertThat(usdGbp.asOf).isEqualTo(LocalDate.parse("2026-09-11"))
        assertThat(live.rate("USD", "GBP", LocalDate.parse("2026-09-10"), null)).isNull()
    }
}
