package tech.bhrigu.almira.investment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal

@DisplayName("Duplicating and renewing")
class RolloverApiTest : ApiTestBase() {

    private lateinit var owner: String
    private lateinit var householdId: String
    private lateinit var spouseMemberId: String

    @BeforeEach
    fun setUp() {
        owner = signIn()
        householdId = createHousehold(owner, "Koduri", "private", "Ishwarya").path("id").asText()
        spouseMemberId = addMember(owner, householdId, "Partner").path("id").asText()
    }

    private fun fd(title: String, amount: String, attributes: Map<String, Any?> = mapOf("interest_rate" to 7.1)) =
        capture(owner, householdId, "fd", title, BigDecimal(amount), attributes = attributes)

    // --- duplicate -----------------------------------------------------------

    @Test
    fun `a duplicate carries the shape and leaves the history behind`() {
        val original = fd("ICICI FD 2024", "100000")
        val id = original.path("id").asText()
        post(
            "/api/v1/households/$householdId/investments/$id/valuations", owner,
            mapOf("value" to 107100, "asOfDate" to "2026-08-01"),
        )

        val copy = post("/api/v1/households/$householdId/investments/$id/duplicate", owner)
        assertThat(copy.status()).isEqualTo(HttpStatus.CREATED)

        val record = copy.json().path("investment")
        assertThat(record.path("title").asText()).isEqualTo("ICICI FD 2024 (copy)")
        assertThat(record.path("investedAmount").decimalValue()).isEqualByComparingTo(BigDecimal("100000"))
        assertThat(BigDecimal(record.path("attributes").path("interest_rate").asText()))
            .isEqualByComparingTo(BigDecimal("7.1"))

        assertThat(
            get(
                "/api/v1/households/$householdId/investments/${record.path("id").asText()}/valuations",
                owner,
            ).json(),
        )
            .describedAs("copying a valuation would invent a purchase that never happened")
            .isEmpty()
    }

    @Test
    fun `a duplicate keeps the owners and nominees, because they are usually the same`() {
        val original = capture(
            owner, householdId, "fd", "Joint FD", BigDecimal("200000"),
            attributes = mapOf("interest_rate" to 7.0),
            visibility = "household",
            owners = listOf(mapOf("memberId" to spouseMemberId, "sharePct" to 100)),
        )
        val id = original.path("id").asText()
        // The nominee list is a legal instruction, so it is set as a unit: PUT.
        call(
            org.springframework.http.HttpMethod.PUT,
            "/api/v1/households/$householdId/investments/$id/nominees", owner,
            mapOf("nominees" to listOf(mapOf("name" to "Aarav", "relationship" to "son", "sharePct" to 100))),
        )

        val copy = post(
            "/api/v1/households/$householdId/investments/$id/duplicate", owner,
            mapOf("title" to "Joint FD 2027"),
        ).json().path("investment")

        assertThat(copy.path("owners").map { it.path("memberId").asText() })
            .containsExactly(spouseMemberId)
        assertThat(copy.path("nominees").map { it.path("name").asText() })
            .containsExactly("Aarav")
    }

    @Test
    fun `duplicating the same tap twice makes one copy, not two`() {
        val id = fd("ICICI FD", "100000").path("id").asText()
        val copyId = uuid()

        repeat(2) {
            post(
                "/api/v1/households/$householdId/investments/$id/duplicate", owner,
                mapOf("id" to copyId),
            )
        }
        assertThat(get("/api/v1/households/$householdId/investments?q=copy", owner).json())
            .hasSize(1)
    }

    // --- rollover ------------------------------------------------------------

    @Test
    fun `renewing a maturity keeps the old record and points the new one at it`() {
        val original = post(
            "/api/v1/households/$householdId/investments", owner,
            mapOf(
                "typeId" to typeId(owner, householdId, "fd"),
                "title" to "ICICI FD",
                "investedAmount" to 100000,
                "startDate" to "2025-04-01",
                "maturityDate" to "2026-04-01",
                "attributes" to mapOf("interest_rate" to 7.1),
            ),
        ).json()
        val id = original.path("id").asText()

        val rolled = post(
            "/api/v1/households/$householdId/investments/$id/rollover", owner,
            mapOf("investedAmount" to 107100, "maturityDate" to "2027-04-01"),
        )
        assertThat(rolled.status()).isEqualTo(HttpStatus.CREATED)

        val previous = rolled.json().path("previous")
        assertThat(previous.path("status").asText())
            .describedAs("the old FD is matured, not deleted — the history is the point")
            .isEqualTo("matured")
        assertThat(previous.path("investedAmount").decimalValue())
            .isEqualByComparingTo(BigDecimal("100000"))

        val created = rolled.json().path("created").path("investment")
        assertThat(created.path("title").asText()).isEqualTo("ICICI FD")
        assertThat(created.path("investedAmount").decimalValue())
            .isEqualByComparingTo(BigDecimal("107100"))
        assertThat(created.path("startDate").asText())
            .describedAs("the new term starts where the old one ended")
            .isEqualTo("2026-04-01")
        assertThat(created.path("maturityDate").asText()).isEqualTo("2027-04-01")
        assertThat(created.path("rolledFromId").asText()).isEqualTo(id)
    }

    /**
     * Carrying the old maturity date forward would produce a record that matured
     * before it started — which then shows up on the dashboard as an overdue
     * maturity the day it is created.
     */
    @Test
    fun `a renewal does not inherit the maturity date it just passed`() {
        val id = post(
            "/api/v1/households/$householdId/investments", owner,
            mapOf(
                "typeId" to typeId(owner, householdId, "fd"),
                "title" to "SBI FD",
                "investedAmount" to 50000,
                "startDate" to "2025-04-01",
                "maturityDate" to "2026-04-01",
                "attributes" to mapOf("interest_rate" to 6.8),
            ),
        ).json().path("id").asText()

        val created = post("/api/v1/households/$householdId/investments/$id/rollover", owner)
            .json().path("created").path("investment")

        // hasNonNull, not isNull: an omitted field is a MissingNode, whose
        // isNull() is false — which would let this pass for the wrong reason.
        assertThat(created.hasNonNull("maturityDate"))
            .describedAs("ask for the new maturity date rather than guessing at it")
            .isFalse()
        assertThat(created.path("startDate").asText()).isEqualTo("2026-04-01")
    }

    /**
     * Without carrying the mapping across, renewing an FD would quietly drop the
     * goal it was earmarked for, and the goal's progress would fall by that much
     * with nothing on screen to explain it.
     */
    @Test
    fun `a renewal keeps funding the goal the original funded, and funds it once`() {
        val id = post(
            "/api/v1/households/$householdId/investments", owner,
            mapOf(
                "typeId" to typeId(owner, householdId, "fd"),
                "title" to "Education FD",
                "investedAmount" to 100000,
                "startDate" to "2025-04-01",
                "maturityDate" to "2026-04-01",
                "attributes" to mapOf("interest_rate" to 7.1),
            ),
        ).json().path("id").asText()

        val goalId = post(
            "/api/v1/households/$householdId/goals", owner,
            mapOf("name" to "Aarav UG", "targetAmount" to 1600000, "targetDate" to "2039-06-01"),
        ).json().path("id").asText()
        post(
            "/api/v1/households/$householdId/goals/$goalId/investments", owner,
            mapOf("investmentId" to id, "allocationPct" to 100),
        )

        post(
            "/api/v1/households/$householdId/investments/$id/rollover", owner,
            mapOf("investedAmount" to 107100),
        )

        val goal = get("/api/v1/households/$householdId/goals/$goalId", owner).json()
        assertThat(goal.path("funded").decimalValue())
            .describedAs("the renewal funds it; the record it replaced does not")
            .isEqualByComparingTo(BigDecimal("107100"))
        assertThat(goal.path("fundedBy").map { it.path("title").asText() })
            .containsExactly("Education FD")
    }

    @Test
    fun `the totals count the renewal once, not the renewal and the original`() {
        val id = post(
            "/api/v1/households/$householdId/investments", owner,
            mapOf(
                "typeId" to typeId(owner, householdId, "fd"),
                "title" to "ICICI FD",
                "investedAmount" to 100000,
                "startDate" to "2025-04-01",
                "maturityDate" to "2026-04-01",
                "attributes" to mapOf("interest_rate" to 7.1),
                "visibility" to "household",
            ),
        ).json().path("id").asText()

        val before = dashboardTotal(owner, householdId)
        post(
            "/api/v1/households/$householdId/investments/$id/rollover", owner,
            mapOf("investedAmount" to 107100, "visibility" to "household"),
        )
        val after = dashboardTotal(owner, householdId)

        assertThat(after)
            .describedAs("a matured record is history; only the live one counts")
            .isEqualByComparingTo(BigDecimal("107100"))
        assertThat(before).isEqualByComparingTo(BigDecimal("100000"))
    }
}
