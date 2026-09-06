package tech.bhrigu.almira.template

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal

@DisplayName("Templates")
class TemplateApiTest : ApiTestBase() {

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
        spouseMemberId = addMember(owner, householdId, "Partner").path("id").asText()
        joinHousehold(owner, householdId, spouseMemberId, spouse)
    }

    private fun template(token: String, body: Map<String, Any?>) =
        post("/api/v1/households/$householdId/templates", token, body)

    @Test
    fun `a template saves the shape of a capture, and applying it saves the record`() {
        val created = template(
            owner,
            mapOf(
                "name" to "Quarterly FD",
                "typeId" to typeId(owner, householdId, "fd"),
                "title" to "ICICI FD",
                "investedAmount" to 200000,
                "attributes" to mapOf("interest_rate" to 7.1),
            ),
        ).json()

        val applied = post(
            "/api/v1/households/$householdId/templates/${created.path("id").asText()}/apply", owner,
            mapOf("investedAmount" to 250000, "startDate" to "2026-04-01"),
        )
        assertThat(applied.status()).isEqualTo(HttpStatus.CREATED)

        val record = applied.json().path("investment")
        assertThat(record.path("title").asText()).isEqualTo("ICICI FD")
        assertThat(record.path("investedAmount").decimalValue())
            .describedAs("what was different this time wins over the template")
            .isEqualByComparingTo(BigDecimal("250000"))
        assertThat(BigDecimal(record.path("attributes").path("interest_rate").asText()))
            .isEqualByComparingTo(BigDecimal("7.1"))
        assertThat(record.path("startDate").asText()).isEqualTo("2026-04-01")

        assertThat(
            get("/api/v1/households/$householdId/templates", owner).json()
                .first().path("useCount").asInt(),
        ).isEqualTo(1)
    }

    @Test
    fun `saving an existing record as a template copies its shape, not its history`() {
        val holding = capture(
            owner, householdId, "fd", "SBI FD", BigDecimal("100000"),
            attributes = mapOf("interest_rate" to 6.8),
        )
        val investmentId = holding.path("id").asText()
        post(
            "/api/v1/households/$householdId/investments/$investmentId/valuations", owner,
            mapOf("value" to 106800, "asOfDate" to "2026-08-01"),
        )

        val saved = template(
            owner, mapOf("name" to "SBI FD again", "fromInvestmentId" to investmentId),
        ).json()

        assertThat(saved.path("typeCode").asText()).isEqualTo("fd")
        assertThat(BigDecimal(saved.path("attributes").path("interest_rate").asText()))
            .describedAs("numbers survive as text, because money and rates never round-trip through a float")
            .isEqualByComparingTo(BigDecimal("6.8"))

        val applied = post(
            "/api/v1/households/$householdId/templates/${saved.path("id").asText()}/apply", owner,
            mapOf("title" to "SBI FD 2027"),
        ).json().path("investment")
        assertThat(applied.path("valueBasis").asText())
            .describedAs("a new record has no valuation history of its own yet")
            .isNotEqualTo("valuation")
    }

    // --- privacy -------------------------------------------------------------

    /**
     * "Save as template" must not become a quiet way to republish a private
     * holding: the template carries the record's own details with it.
     */
    @Test
    fun `a template made from a private record cannot be shared with the household`() {
        val privateHolding = capture(
            owner, householdId, "gold_physical", "Wedding gold", BigDecimal("500000"),
            visibility = "private",
        )

        val refused = template(
            owner,
            mapOf(
                "name" to "Gold purchases",
                "fromInvestmentId" to privateHolding.path("id").asText(),
                "visibility" to "household",
            ),
        )
        assertThat(refused.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(refused.errorCode()).isEqualTo("template_would_widen")
    }

    @Test
    fun `and it cannot be widened afterwards either`() {
        val privateHolding = capture(
            owner, householdId, "gold_physical", "Wedding gold", BigDecimal("500000"),
            visibility = "private",
        )
        val saved = template(
            owner,
            mapOf("name" to "Gold purchases", "fromInvestmentId" to privateHolding.path("id").asText()),
        ).json()

        val refused = patch(
            "/api/v1/households/$householdId/templates/${saved.path("id").asText()}", owner,
            mapOf("version" to saved.path("version").asInt(), "visibility" to "household"),
        )
        assertThat(refused.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(refused.errorCode()).isEqualTo("template_would_widen")
    }

    @Test
    fun `my private template is not visible to anyone else`() {
        template(owner, mapOf("name" to "My FDs", "typeId" to typeId(owner, householdId, "fd")))

        assertThat(get("/api/v1/households/$householdId/templates", spouse).json())
            .describedAs("private is private, even for a form preset")
            .isEmpty()
    }

    @Test
    fun `a shared template can be used by the household but changed only by its maker`() {
        val shared = template(
            owner,
            mapOf(
                "name" to "Household FD",
                "typeId" to typeId(owner, householdId, "fd"),
                "attributes" to mapOf("interest_rate" to 7.1),
                "visibility" to "household",
            ),
        ).json()
        val id = shared.path("id").asText()

        val seen = get("/api/v1/households/$householdId/templates", spouse).json()
        assertThat(seen).hasSize(1)
        assertThat(seen.first().path("mine").asBoolean())
            .describedAs("the client needs to know not to offer an edit button")
            .isFalse()

        val used = post(
            "/api/v1/households/$householdId/templates/$id/apply", spouse,
            mapOf("title" to "Partner's FD", "investedAmount" to 100000),
        )
        assertThat(used.status()).isEqualTo(HttpStatus.CREATED)
        assertThat(get("/api/v1/households/$householdId/templates/$id", owner).json()
            .path("useCount").asInt())
            .describedAs("someone else's use still counts")
            .isEqualTo(1)

        val refused = patch(
            "/api/v1/households/$householdId/templates/$id", spouse,
            mapOf("version" to shared.path("version").asInt(), "name" to "Renamed"),
        )
        assertThat(refused.status()).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `two templates of mine cannot share a name`() {
        val body = mapOf("name" to "Quarterly FD", "typeId" to typeId(owner, householdId, "fd"))
        template(owner, body)
        assertThat(template(owner, body).status()).isEqualTo(HttpStatus.CONFLICT)

        assertThat(template(spouse, body).status())
            .describedAs("but it is my name to reuse, not the household's")
            .isEqualTo(HttpStatus.CREATED)
    }

    /**
     * A template is a shortcut, not a way around the type's own rules — an FD
     * without its interest rate is not a record anyone can use later.
     */
    @Test
    fun `applying a half-filled template is refused, naming what is missing`() {
        val bare = template(
            owner, mapOf("name" to "Rough FD", "typeId" to typeId(owner, householdId, "fd")),
        ).json()

        val refused = post(
            "/api/v1/households/$householdId/templates/${bare.path("id").asText()}/apply", owner,
            mapOf("investedAmount" to 100000),
        )
        assertThat(refused.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(refused.errorCode()).isEqualTo("attributes_invalid")
        assertThat(
            refused.json().path("error").path("details").path("fields")
                .path("interest_rate").asText(),
        )
            .describedAs("the client has to know which field to ask for")
            .isEqualTo("Interest rate is needed")
    }

    @Test
    fun `a template needs either a type or a record to copy`() {
        val refused = template(owner, mapOf("name" to "Nothing in particular"))
        assertThat(refused.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(refused.errorCode()).isEqualTo("type_required")
    }
}
