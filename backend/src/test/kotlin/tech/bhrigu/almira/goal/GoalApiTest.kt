package tech.bhrigu.almira.goal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal
import java.time.LocalDate

@DisplayName("Goals")
class GoalApiTest : ApiTestBase() {

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

    private fun goal(
        name: String = "Aarav's degree",
        target: Int = 1_600_000,
        date: String? = "2039-06-01",
        visibility: String = "household",
        token: String = owner,
    ) = post(
        "/api/v1/households/$householdId/goals", token,
        buildMap {
            put("name", name)
            put("targetAmount", target)
            put("visibility", visibility)
            date?.let { put("targetDate", it) }
        },
    )

    private fun holding(title: String, value: Int, visibility: String = "household") =
        capture(
            owner, householdId, "mf_sip", title, BigDecimal(value), visibility,
            attributes = mapOf("scheme_name" to title, "sip_amount" to "10000", "sip_day" to "5"),
        ).path("id").asText()

    private fun map(goalId: String, investmentId: String, pct: Int, token: String = owner) = post(
        "/api/v1/households/$householdId/goals/$goalId/investments", token,
        mapOf("investmentId" to investmentId, "allocationPct" to pct),
    )

    // --- progress ------------------------------------------------------------

    @Test
    fun `a goal with nothing pointed at it says so, rather than showing zero percent`() {
        val created = goal().json()
        assertThat(created.path("funded").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(created.path("progress").path("note").asText())
            .contains("map a holding")
    }

    @Test
    fun `progress is the allocated share of each holding, not the whole thing`() {
        val goalId = goal(target = 1_000_000).json().path("id").asText()
        val sip = holding("Parag Parikh Flexi Cap", 400_000)
        map(goalId, sip, 50)

        val result = get("/api/v1/households/$householdId/goals/$goalId", owner).json()
        assertThat(result.path("funded").decimalValue())
            .describedAs("half of ₹4,00,000")
            .isEqualByComparingTo(BigDecimal(200_000))
        assertThat(result.path("progress").path("percentComplete").decimalValue())
            .isEqualByComparingTo(BigDecimal("20.0"))
        assertThat(result.path("progress").path("shortfall").decimalValue())
            .isEqualByComparingTo(BigDecimal(800_000))
    }

    /** One SIP genuinely can fund two goals — that is the point of an allocation. */
    @Test
    fun `a holding can fund two goals`() {
        val house = goal("House deposit", 2_000_000).json().path("id").asText()
        val retirement = goal("Retirement", 5_000_000).json().path("id").asText()
        val sip = holding("Index fund", 600_000)

        map(house, sip, 40)
        map(retirement, sip, 60)

        assertThat(
            get("/api/v1/households/$householdId/goals/$house", owner).json()
                .path("funded").decimalValue(),
        ).isEqualByComparingTo(BigDecimal(240_000))
        assertThat(
            get("/api/v1/households/$householdId/goals/$retirement", owner).json()
                .path("funded").decimalValue(),
        ).isEqualByComparingTo(BigDecimal(360_000))
    }

    @Test
    fun `a holding cannot be more than fully allocated`() {
        val house = goal("House deposit", 2_000_000).json().path("id").asText()
        val retirement = goal("Retirement", 5_000_000).json().path("id").asText()
        val sip = holding("Index fund", 600_000)

        map(house, sip, 80)
        val response = map(retirement, sip, 40)

        assertThat(response.status())
            .describedAs("120% of a holding does not exist")
            .isIn(HttpStatus.BAD_REQUEST, HttpStatus.CONFLICT)
    }

    @Test
    fun `holdings pointed at nothing are surfaced, not forced`() {
        val goalId = goal().json().path("id").asText()
        val allocated = holding("Allocated fund", 100_000)
        holding("Nothing points at this", 50_000)
        map(goalId, allocated, 100)

        val unallocated = get("/api/v1/households/$householdId/goals-unallocated", owner).json()
            .map { it.path("title").asText() }
        assertThat(unallocated).containsExactly("Nothing points at this")
    }

    @Test
    fun `a fully funded goal says so and stops asking for more`() {
        val goalId = goal(target = 100_000).json().path("id").asText()
        map(goalId, holding("Big fund", 150_000), 100)

        val progress = get("/api/v1/households/$householdId/goals/$goalId", owner)
            .json().path("progress")
        assertThat(progress.path("shortfall").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(progress.path("percentComplete").decimalValue())
            .describedAs("capped — 150% funded reads as a bug, not a triumph")
            .isEqualByComparingTo(BigDecimal("100.0"))
        assertThat(progress.path("note").asText()).isEqualTo("Fully funded.")
    }

    /**
     * On-track compares the share of money set aside with the share of time
     * gone. No return assumption, no projection — the product does not get to
     * have an opinion (docs/08 §6).
     */
    @Test
    fun `on-track compares money set aside against time elapsed`() {
        val far = goal("Distant", 1_000_000, date = LocalDate.now().plusYears(20).toString())
            .json().path("id").asText()
        map(far, holding("Fund A", 100_000), 100)

        val progress = get("/api/v1/households/$householdId/goals/$far", owner).json().path("progress")
        assertThat(progress.path("onTrack").asBoolean())
            .describedAs("10% funded on day one of a twenty-year goal is ahead, not behind")
            .isTrue()
        assertThat(progress.path("monthlyToClose").decimalValue().toDouble()).isGreaterThan(0.0)
    }

    @Test
    fun `a goal with no deadline has nothing to be on track for`() {
        val goalId = goal("Someday", 500_000, date = null).json().path("id").asText()
        val progress = get("/api/v1/households/$householdId/goals/$goalId", owner).json().path("progress")
        assertThat(progress.path("onTrack").isNull || progress.path("onTrack").isMissingNode).isTrue()
    }

    // --- privacy -------------------------------------------------------------

    /**
     * A goal names something private: what someone is saving for, and how far
     * short they are.
     */
    @Test
    fun `a private goal is invisible to an admin`() {
        val privateGoal = goal("Leaving fund", 500_000, visibility = "private")
            .json().path("id").asText()

        assertThat(get("/api/v1/households/$householdId/goals/$privateGoal", spouse).status())
            .isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(get("/api/v1/households/$householdId/goals", spouse).json()).isEmpty()
    }

    /**
     * The mapping is the leak nobody thinks of: a shared holding pointed at a
     * private goal would announce that the goal exists, and how much is aimed
     * at it.
     */
    @Test
    fun `a shared holding does not reveal the private goal it funds`() {
        val privateGoal = goal("Leaving fund", 500_000, visibility = "private")
            .json().path("id").asText()
        val shared = holding("Shared fund", 300_000, visibility = "household")
        map(privateGoal, shared, 60)

        // The admin sees the holding, and no sign of what it is pointed at.
        assertThat(get("/api/v1/households/$householdId/investments/$shared", spouse).status())
            .isEqualTo(HttpStatus.OK)
        assertThat(get("/api/v1/households/$householdId/goals", spouse).json()).isEmpty()

        // And it reads as unallocated to them, which is the honest answer:
        // saying otherwise would confirm a goal they cannot see.
        assertThat(
            get("/api/v1/households/$householdId/goals-unallocated", spouse).json()
                .map { it.path("title").asText() },
        ).contains("Shared fund")
    }

    @Test
    fun `an admin cannot map a holding to a goal they cannot see`() {
        val privateGoal = goal("Leaving fund", 500_000, visibility = "private")
            .json().path("id").asText()
        val shared = holding("Shared fund", 300_000)

        assertThat(map(privateGoal, shared, 50, token = spouse).status())
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `sharing a goal makes it and its funding visible`() {
        val goalId = goal("Retirement", 5_000_000, visibility = "private").json().path("id").asText()
        map(goalId, holding("Index fund", 500_000), 100)

        patch(
            "/api/v1/households/$householdId/goals/$goalId/visibility", owner,
            mapOf("visibility" to "household"),
        )

        val theirs = get("/api/v1/households/$householdId/goals/$goalId", spouse).json()
        assertThat(theirs.path("funded").decimalValue()).isEqualByComparingTo(BigDecimal(500_000))
        assertThat(theirs.path("fundedBy")).hasSize(1)
    }

    // --- validation ----------------------------------------------------------

    @Test
    fun `a goal needs a target worth aiming at`() {
        assertThat(
            post(
                "/api/v1/households/$householdId/goals", owner,
                mapOf("name" to "Vague", "targetAmount" to 0),
            ).errorCode(),
        ).isEqualTo("target_required")
    }

    @Test
    fun `every goal carries the not-advice note`() {
        assertThat(goal().json().path("disclaimer").asText())
            .contains("not financial advice")
    }
}
