package tech.bhrigu.almira.money

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal

@DisplayName("Multi-currency: stored native, converted only to display")
class CurrencyApiTest : ApiTestBase() {

    private lateinit var owner: String
    private lateinit var householdId: String

    @BeforeEach
    fun setUp() {
        owner = signIn()
        householdId = createHousehold(owner, "Koduri", "household", "Ishwarya").path("id").asText()
    }

    private fun foreignHolding(title: String, amount: Int, currency: String) = post(
        "/api/v1/households/$householdId/investments", owner,
        mapOf(
            "typeId" to typeId(owner, householdId, "universal"),
            "title" to title,
            "investedAmount" to amount,
            "currency" to currency,
            "visibility" to "household",
        ),
    )

    @Test
    fun `a holding keeps its own currency, and the total is converted`() {
        val created = foreignHolding("Emirates NBD savings", 10_000, "AED")
        assertThat(created.status()).isEqualTo(HttpStatus.CREATED)
        assertThat(created.json().path("investment").path("currency").asText()).isEqualTo("AED")

        // 10,000 AED at the seeded 24.10 = ₹2,41,000.
        assertThat(dashboardTotal(owner, householdId))
            .isEqualByComparingTo(BigDecimal("241000.00"))
    }

    @Test
    fun `a household's own rate beats the one that shipped with the app`() {
        foreignHolding("Emirates NBD savings", 10_000, "AED")
        post(
            "/api/v1/households/$householdId/rates", owner,
            mapOf("baseCurrency" to "AED", "quoteCurrency" to "INR", "rate" to 25),
        )
        assertThat(dashboardTotal(owner, householdId))
            .describedAs("they know what they actually got")
            .isEqualByComparingTo(BigDecimal("250000.00"))
    }

    /**
     * A total that quietly omits a holding is worse than one that is smaller
     * than the truth and says so.
     */
    @Test
    fun `a currency with no rate is left out of the total, and said out loud`() {
        foreignHolding("Old kroner account", 5_000, "NOK")
        capture(owner, householdId, "gold_physical", "Gold", BigDecimal("100000"), visibility = "household")

        val dashboard = get("/api/v1/households/$householdId/dashboard?scope=household", owner).json()
        assertThat(dashboard.path("totalAssets").decimalValue())
            .isEqualByComparingTo(BigDecimal("100000.00"))
        val unconverted = dashboard.path("unconverted").first()
        assertThat(unconverted.path("currency").asText()).isEqualTo("NOK")
        assertThat(unconverted.path("count").asInt()).isEqualTo(1)
        assertThat(unconverted.path("note").asText()).contains("Add a rate and they will be")
    }

    @Test
    fun `a conversion carries the rate, its date and where it came from`() {
        val converted = get(
            "/api/v1/households/$householdId/rates/convert?amount=100&from=USD&to=INR", owner,
        ).json()
        assertThat(converted.path("convertedAmount").decimalValue())
            .isEqualByComparingTo(BigDecimal("8850.00"))
        assertThat(converted.path("rateSource").asText()).isEqualTo("seed")
        assertThat(converted.path("rateAsOf").asText()).isNotBlank()
        assertThat(converted.path("note").asText())
            .describedAs("a shipped rate is a starting point, and says so")
            .contains("shipped with the app")
    }

    @Test
    fun `an unknown pair converts to nothing rather than to a guess`() {
        val converted = get(
            "/api/v1/households/$householdId/rates/convert?amount=100&from=NOK&to=INR", owner,
        ).json()
        assertThat(converted.hasNonNull("convertedAmount")).isFalse()
        assertThat(converted.path("note").asText()).contains("No NOK→INR rate recorded")
    }

    @Test
    fun `the inverse of a known rate is used before giving up`() {
        val converted = get(
            "/api/v1/households/$householdId/rates/convert?amount=8850&from=INR&to=USD", owner,
        ).json()
        assertThat(converted.path("convertedAmount").decimalValue())
            .isEqualByComparingTo(BigDecimal("100.00"))
        assertThat(converted.path("rateSource").asText()).contains("inverted")
    }

    @Test
    fun `a rate has to be a rate`() {
        val refused = post(
            "/api/v1/households/$householdId/rates", owner,
            mapOf("baseCurrency" to "AED", "quoteCurrency" to "AED", "rate" to 1),
        )
        assertThat(refused.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(refused.errorCode()).isEqualTo("currency_same")
    }

    @Test
    fun `the rates a household would actually use are listed with their provenance`() {
        post(
            "/api/v1/households/$householdId/rates", owner,
            mapOf("baseCurrency" to "AED", "quoteCurrency" to "INR", "rate" to 25),
        )
        val rates = get("/api/v1/households/$householdId/rates?quote=INR", owner).json()
            .associateBy { it.path("base").asText() }
        assertThat(rates["AED"]!!.path("source").asText()).isEqualTo("manual")
        assertThat(rates["USD"]!!.path("source").asText()).isEqualTo("seed")
    }
}
