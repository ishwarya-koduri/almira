package tech.bhrigu.almira.advisor

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal

/**
 * An advisor is the third kind of outsider, after a guest link and an emergency
 * contact: someone who works with the household all year and should not need a
 * fresh link every week — but who is emphatically not family.
 *
 * The rule is one sentence: **they see only what has been explicitly shared with
 * them.** Household visibility does not reach them, and they cannot write.
 */
@DisplayName("An advisor sees what they were given, and nothing else")
class AdvisorApiTest : ApiTestBase() {

    private lateinit var owner: String
    private lateinit var advisor: String
    private lateinit var householdId: String
    private lateinit var advisorMemberId: String

    @BeforeEach
    fun setUp() {
        owner = signIn()
        advisor = signIn()
        householdId = createHousehold(owner, "Koduri", "household", "Ishwarya").path("id").asText()
        advisorMemberId = post(
            "/api/v1/households/$householdId/members", owner,
            mapOf("displayName" to "Ramesh (CA)", "relationship" to "other"),
        ).json().path("id").asText()
        joinHousehold(owner, householdId, advisorMemberId, advisor, role = "advisor")
    }

    @Test
    fun `a household-visible holding is still not the advisor's business`() {
        capture(
            owner, householdId, "gold_physical", "Household gold", BigDecimal("500000"),
            visibility = "household",
        )
        assertThat(get("/api/v1/households/$householdId/investments", advisor).json())
            .describedAs("shared with the household means the household, not their accountant")
            .isEmpty()
        assertThat(dashboardTotal(advisor, householdId)).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `what is shared with them explicitly, they can see`() {
        val holding = capture(
            owner, householdId, "ppf", "PPF, SBI", BigDecimal("150000"),
            visibility = "scoped", visibleTo = listOf(advisorMemberId),
            attributes = mapOf("account_no" to "1234", "yearly_contribution" to 150000),
        )
        assertThat(holding.path("id").asText()).isNotBlank()

        val theirs = get("/api/v1/households/$householdId/investments", advisor).json()
        assertThat(theirs.map { it.path("title").asText() }).containsExactly("PPF, SBI")
    }

    @Test
    fun `an advisor cannot change what they can see`() {
        val holding = capture(
            owner, householdId, "gold_physical", "Shared with the CA", BigDecimal("100000"),
            visibility = "scoped", visibleTo = listOf(advisorMemberId),
        )
        val id = holding.path("id").asText()
        assertThat(get("/api/v1/households/$householdId/investments/$id", advisor).status())
            .isEqualTo(HttpStatus.OK)

        val refused = patch(
            "/api/v1/households/$householdId/investments/$id", advisor,
            mapOf("version" to 1, "title" to "Renamed by the CA"),
        )
        assertThat(refused.status()).isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND)

        val stillCalled = get("/api/v1/households/$householdId/investments/$id", owner).json()
            .path("title").asText()
        assertThat(stillCalled).isEqualTo("Shared with the CA")
    }

    @Test
    fun `an advisor cannot add anything either`() {
        val refused = post(
            "/api/v1/households/$householdId/investments", advisor,
            mapOf(
                "typeId" to typeId(owner, householdId, "gold_physical"),
                "title" to "Added by the CA", "investedAmount" to 1000,
            ),
        )
        assertThat(refused.status()).isIn(HttpStatus.FORBIDDEN, HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `revoking the grant ends it, mid-engagement`() {
        val holding = capture(
            owner, householdId, "gold_physical", "Shared for now", BigDecimal("100000"),
            visibility = "scoped", visibleTo = listOf(advisorMemberId),
        )
        val id = holding.path("id").asText()
        assertThat(get("/api/v1/households/$householdId/investments", advisor).json()).hasSize(1)

        patch(
            "/api/v1/households/$householdId/investments/$id/visibility", owner,
            mapOf("visibility" to "household"),
        )
        assertThat(get("/api/v1/households/$householdId/investments", advisor).json())
            .describedAs("moving it back to the family takes it away from the advisor")
            .isEmpty()
    }

    @Test
    fun `the tax pack an advisor sees is built from their slice alone`() {
        capture(
            owner, householdId, "ppf", "PPF, SBI", BigDecimal("150000"),
            visibility = "scoped", visibleTo = listOf(advisorMemberId),
            attributes = mapOf("account_no" to "1234", "yearly_contribution" to 150000),
        )
        capture(
            owner, householdId, "ppf", "PPF, HDFC — not theirs", BigDecimal("150000"),
            visibility = "household",
            attributes = mapOf("account_no" to "9999", "yearly_contribution" to 150000),
        )

        val theirs = get("/api/v1/households/$householdId/tax/pack", advisor).json()
        val sources = theirs.path("deductions").flatMap { meter ->
            meter.path("sources").map { it.path("title").asText() }
        }
        assertThat(sources).containsExactly("PPF, SBI")
    }
}
