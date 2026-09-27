package tech.almira.investment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.math.BigDecimal
import tech.almira.support.ApiTestBase

/**
 * Rupees a gram, times the grams, is what the almirah records.
 *
 * Found by the owner on a phone (2026-09-26): they typed 5000 into "Amount
 * paid" meaning the rate for one gram, put 4 in the weight, and Almira recorded
 * five thousand rupees of gold instead of twenty thousand. Nothing refused it,
 * nothing looked wrong, and a net worth that is quietly a quarter of the truth
 * is worse than one that is obviously broken.
 *
 * The owner's ruling (2026-09-26): the total stays the field of record, the rate
 * is stored beside it, and sending a rate with a quantity and no total means the
 * two multiplied. That last rule is what this pins, on create and on edit,
 * because it is the one that decides what a family's gold is worth.
 */
@DisplayName("A rate and a quantity are the total they make")
class RatePerUnitApiTest : ApiTestBase() {

    private lateinit var owner: String
    private lateinit var householdId: String
    private lateinit var goldTypeId: String

    @BeforeEach
    fun setUp() {
        owner = signIn()
        householdId = createHousehold(owner, "Koduri", "household", "Ishwarya").path("id").asText()
        goldTypeId = typeId(owner, householdId, "gold_physical")
    }

    private fun capture(body: Map<String, Any?>) = post(
        "/api/v1/households/$householdId/investments", owner,
        mapOf("typeId" to goldTypeId, "visibility" to "household") + body,
    )

    private fun stored(id: String): Pair<BigDecimal?, BigDecimal?> {
        val row = db.queryForMap(
            "select invested_amount, rate_per_unit from investments where id = ?::uuid", id,
        )
        return row["invested_amount"] as BigDecimal? to row["rate_per_unit"] as BigDecimal?
    }

    @Test
    fun `a rate and a quantity with no total are the two multiplied`() {
        val made = capture(
            mapOf(
                "title" to "Amma's bangles", "ratePerUnit" to 5000, "quantity" to 4, "unit" to "g",
                "attributes" to mapOf("purity" to "22k"),
            ),
        )
        assertThat(made.status()).describedAs(made.body).isEqualTo(HttpStatus.CREATED)

        val (total, rate) = stored(made.json().path("id").asText())
        assertThat(total).describedAs("5,000 a gram for 4 grams").isEqualByComparingTo("20000")
        assertThat(rate).describedAs("and what a gram cost is kept beside it").isEqualByComparingTo("5000")
    }

    @Test
    fun `the total is the field of record when both are sent`() {
        // A person who corrects the total after the rate means the total: a
        // jeweller's bill is rarely the rate times the weight to the rupee.
        val made = capture(
            mapOf(
                "title" to "Bill with making charges", "investedAmount" to 21500,
                "ratePerUnit" to 5000, "quantity" to 4, "unit" to "g",
                "attributes" to mapOf("purity" to "22k"),
            ),
        )
        val (total, rate) = stored(made.json().path("id").asText())
        assertThat(total).isEqualByComparingTo("21500")
        assertThat(rate).isEqualByComparingTo("5000")
    }

    @Test
    fun `a total on its own is untouched, as it always was`() {
        val made = capture(
            mapOf(
                "title" to "Coins", "investedAmount" to 60000, "quantity" to 10, "unit" to "g",
                "attributes" to mapOf("purity" to "24k"),
            ),
        )
        val (total, rate) = stored(made.json().path("id").asText())
        assertThat(total).isEqualByComparingTo("60000")
        assertThat(rate).describedAs("nothing is invented").isNull()
    }

    @Test
    fun `editing by the rate multiplies too, using the weight already recorded`() {
        val made = capture(
            mapOf(
                "title" to "Chain", "investedAmount" to 20000, "quantity" to 4, "unit" to "g",
                "attributes" to mapOf("purity" to "22k"),
            ),
        )
        val id = made.json().path("id").asText()
        // From the record itself: the create answer says what was made, not what
        // version it is now.
        val version = get("/api/v1/households/$householdId/investments/$id", owner).json().path("version").asInt()

        // The rate was wrong: it was 6,000 a gram, and the weight is unchanged.
        val edited = call(
            org.springframework.http.HttpMethod.PATCH,
            "/api/v1/households/$householdId/investments/$id", owner,
            mapOf("ratePerUnit" to 6000, "version" to version),
        )
        assertThat(edited.status()).describedAs(edited.body).isEqualTo(HttpStatus.OK)

        val (total, rate) = stored(id)
        assertThat(total).describedAs("6,000 a gram for the 4 grams already recorded").isEqualByComparingTo("24000")
        assertThat(rate).isEqualByComparingTo("6000")
    }

    @Test
    fun `a rate without a quantity is refused rather than stored meaninglessly`() {
        val refused = capture(
            mapOf("title" to "Rate with nothing to multiply", "ratePerUnit" to 5000),
        )
        assertThat(refused.status().is2xxSuccessful)
            .describedAs("a rate with nothing to multiply says nothing: ${refused.body}")
            .isFalse()
    }
}
