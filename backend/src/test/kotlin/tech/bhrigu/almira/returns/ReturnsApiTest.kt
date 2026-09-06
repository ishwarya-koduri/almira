package tech.bhrigu.almira.returns

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal

/**
 * Transactions, lots and returns through the API — including the part that is
 * easy to forget, which is that a return is derived data and must be exactly as
 * private as the holding it derives from.
 */
@DisplayName("Transactions, tax lots and returns")
class ReturnsApiTest : ApiTestBase() {

    private lateinit var owner: String
    private lateinit var spouse: String
    private lateinit var householdId: String
    private lateinit var spouseMemberId: String

    @BeforeEach
    fun setUp() {
        owner = signIn()
        spouse = signIn()
        val household = createHousehold(owner, "Koduri", "private", "Ishwarya")
        householdId = household.path("id").asText()
        spouseMemberId = addMember(owner, householdId, "Ravi").path("id").asText()
        joinHousehold(owner, householdId, spouseMemberId, spouse, role = "admin")
    }

    private fun stock(visibility: String = "household", title: String = "Infosys") = capture(
        owner, householdId, "stock_listed", title, BigDecimal(150_000), visibility,
        attributes = mapOf("symbol" to "INFY", "exchange" to "nse"),
    ).path("id").asText()

    private fun record(
        id: String, type: String, date: String,
        quantity: String? = null, price: String? = null, amount: String? = null,
        ratio: String? = null, token: String = owner,
    ) = post(
        "/api/v1/households/$householdId/investments/$id/transactions", token,
        buildMap {
            put("txnType", type)
            put("txnDate", date)
            quantity?.let { put("quantity", BigDecimal(it)) }
            price?.let { put("price", BigDecimal(it)) }
            amount?.let { put("amount", BigDecimal(it)) }
            ratio?.let { put("ratio", it) }
        },
    )

    // --- lots ----------------------------------------------------------------

    @Test
    fun `buying opens lots and selling consumes the oldest first`() {
        val id = stock()
        record(id, "buy", "2023-01-10", quantity = "100", price = "1500", amount = "150000")
        record(id, "buy", "2023-08-10", quantity = "100", price = "1800", amount = "180000")
        record(id, "sell", "2024-06-01", quantity = "120", price = "2000", amount = "240000")

        val lots = get("/api/v1/households/$householdId/investments/$id/tax-lots", owner).json()
        assertThat(lots).hasSize(2)
        assertThat(lots[0].path("exhausted").asBoolean()).isTrue()
        assertThat(lots[1].path("remainingQty").decimalValue()).isEqualByComparingTo(BigDecimal(80))
    }

    /**
     * The property that makes rebuilding-from-scratch worth the cost: a purchase
     * entered late changes which units a past sale consumed, and therefore its
     * gain. Incremental maintenance would leave the two disagreeing.
     */
    @Test
    fun `a back-dated purchase re-derives the gain on an earlier sale`() {
        val id = stock()
        record(id, "buy", "2023-08-10", quantity = "100", price = "1800", amount = "180000")
        record(id, "sell", "2024-06-01", quantity = "100", price = "2000", amount = "200000")

        val before = get("/api/v1/households/$householdId/investments/$id/returns", owner)
            .json().path("realizedGain").decimalValue()
        assertThat(before).isEqualByComparingTo(BigDecimal(20_000))

        // An older, cheaper purchase surfaces. FIFO now consumes THAT one.
        record(id, "buy", "2023-01-10", quantity = "100", price = "1500", amount = "150000")

        val after = get("/api/v1/households/$householdId/investments/$id/returns", owner)
            .json().path("realizedGain").decimalValue()
        assertThat(after)
            .describedAs("the sale consumed the 1,500 lot, so the gain is larger")
            .isEqualByComparingTo(BigDecimal(50_000))
    }

    @Test
    fun `deleting a transaction re-derives everything that depended on it`() {
        val id = stock()
        record(id, "buy", "2023-01-10", quantity = "100", price = "1500", amount = "150000")
        val sell = record(id, "sell", "2024-06-01", quantity = "50", price = "2000", amount = "100000")
        val sellId = sell.json().first { it.path("txnType").asText() == "sell" }.path("id").asText()

        delete("/api/v1/households/$householdId/investments/$id/transactions/$sellId", owner)

        val lots = get("/api/v1/households/$householdId/investments/$id/tax-lots", owner).json()
        assertThat(lots.single().path("remainingQty").decimalValue())
            .describedAs("the units come back")
            .isEqualByComparingTo(BigDecimal(100))
    }

    // --- returns -------------------------------------------------------------

    @Test
    fun `a holding with no valuation reports no return, and says why`() {
        val id = stock()
        record(id, "buy", "2023-01-10", quantity = "100", price = "1500", amount = "150000")

        val performance = get("/api/v1/households/$householdId/investments/$id/returns", owner).json()
        assertThat(performance.path("xirr").isMissingNode || performance.path("xirr").isNull)
            .describedAs("a return of 0.00% would read as 'it went nowhere', not 'we don't know'")
            .isTrue()
        assertThat(performance.path("note").asText())
            .contains("Add today's value")
    }

    @Test
    fun `once valued, absolute return and XIRR are reported`() {
        val id = stock()
        record(id, "buy", "2023-01-10", quantity = "100", price = "1500", amount = "150000")
        post(
            "/api/v1/households/$householdId/investments/$id/valuations", owner,
            mapOf("value" to 225_000),
        )

        val performance = get("/api/v1/households/$householdId/investments/$id/returns", owner).json()
        assertThat(performance.path("absoluteReturn").decimalValue().toDouble())
            .isCloseTo(50.0, org.assertj.core.api.Assertions.within(0.01))
        assertThat(performance.path("unrealizedGain").decimalValue())
            .isEqualByComparingTo(BigDecimal(75_000))
        assertThat(performance.path("xirr").isMissingNode)
            .describedAs("a purchase and a current value are enough to annualise")
            .isFalse()
        assertThat(performance.path("note").isMissingNode || performance.path("note").isNull).isTrue()
    }

    @Test
    fun `realized and unrealized gains are kept apart`() {
        val id = stock()
        record(id, "buy", "2023-01-10", quantity = "100", price = "1000", amount = "100000")
        record(id, "sell", "2024-06-01", quantity = "40", price = "1500", amount = "60000")
        post(
            "/api/v1/households/$householdId/investments/$id/valuations", owner,
            mapOf("value" to 90_000),
        )

        val performance = get("/api/v1/households/$householdId/investments/$id/returns", owner).json()
        assertThat(performance.path("realizedGain").decimalValue())
            .describedAs("40 units bought at 1,000 and sold at 1,500")
            .isEqualByComparingTo(BigDecimal(20_000))
        // 60 units still held, bought at ₹1,000 = ₹60,000 of cost, now worth
        // ₹90,000. Measuring against the ₹1,00,000 originally put in would
        // double-count the sale — those proceeds already left as realized gain.
        assertThat(performance.path("unrealizedGain").decimalValue())
            .describedAs("today's value against what the units STILL HELD cost")
            .isEqualByComparingTo(BigDecimal(30_000))

        // And the two halves reconcile: -100,000 paid, +60,000 received,
        // +90,000 still held = +50,000, which is 20,000 realized + 30,000 not.
        val realized = performance.path("realizedGain").decimalValue()
        val unrealized = performance.path("unrealizedGain").decimalValue()
        assertThat(realized + unrealized)
            .describedAs("realized and unrealized must add up to the whole position")
            .isEqualByComparingTo(BigDecimal(50_000))
    }

    @Test
    fun `the portfolio can be grouped without the totals disagreeing`() {
        val equity = stock(title = "Infosys")
        record(equity, "buy", "2023-01-10", quantity = "100", price = "1500", amount = "150000")
        post("/api/v1/households/$householdId/investments/$equity/valuations", owner,
             mapOf("value" to 200_000))

        val gold = capture(
            owner, householdId, "gold_physical", "Coins", BigDecimal(100_000), "household",
        ).path("id").asText()
        post("/api/v1/households/$householdId/investments/$gold/valuations", owner,
             mapOf("value" to 120_000))

        val total = get("/api/v1/households/$householdId/returns?groupBy=total", owner).json()
        val byCategory = get("/api/v1/households/$householdId/returns?groupBy=category", owner).json()

        val categorySum = byCategory.sumOf { it.path("currentValue").decimalValue() }
        assertThat(total.single().path("currentValue").decimalValue())
            .describedAs("a category total and the sum of its parts must agree")
            .isEqualByComparingTo(categorySum)
        assertThat(byCategory.map { it.path("label").asText() })
            .containsExactlyInAnyOrder("Equity", "Gold & Metals")
    }

    // --- privacy -------------------------------------------------------------

    /**
     * Returns are derived data, and derived data leaks just as well as the
     * original. A transaction list on a private holding would give away both
     * that it exists and roughly what it is worth.
     */
    @Test
    fun `transactions and returns on a private holding are invisible to an admin`() {
        val id = stock(visibility = "private", title = "Private position")
        record(id, "buy", "2023-01-10", quantity = "100", price = "1500", amount = "150000")

        assertThat(get("/api/v1/households/$householdId/investments/$id/transactions", spouse).status())
            .isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(get("/api/v1/households/$householdId/investments/$id/tax-lots", spouse).status())
            .isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(get("/api/v1/households/$householdId/investments/$id/returns", spouse).status())
            .isEqualTo(HttpStatus.NOT_FOUND)

        val portfolio = get("/api/v1/households/$householdId/returns?groupBy=investment", spouse).json()
        assertThat(portfolio.map { it.path("label").asText() })
            .describedAs("nor through the portfolio roll-up")
            .doesNotContain("Private position")
    }

    @Test
    fun `an admin cannot record a transaction against a private holding`() {
        val id = stock(visibility = "private", title = "Private position")
        assertThat(record(id, "buy", "2024-01-10", quantity = "10", price = "100", token = spouse).status())
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    // --- validation ----------------------------------------------------------

    @Test
    fun `a buy without a quantity is refused, with the reason`() {
        val id = stock()
        val response = record(id, "buy", "2024-01-10", amount = "100000")
        assertThat(response.errorCode()).isEqualTo("quantity_required")
        assertThat(response.json().path("error").path("message").asText())
            .contains("what was sold")
    }

    @Test
    fun `a transaction dated in the future is refused`() {
        val id = stock()
        val tomorrow = java.time.LocalDate.now().plusDays(1).toString()
        assertThat(record(id, "buy", tomorrow, quantity = "10", price = "100").errorCode())
            .isEqualTo("txn_future")
    }
}
