package tech.bhrigu.almira.liability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal

/**
 * The asset privacy tests, mirrored onto debt.
 *
 * Aggregates are the sneaky leak path. A private record can be perfectly hidden
 * from a list and still betrayed by a total that moves when it should not — and
 * with net worth the leak is subtractive, so it is easy to overlook: a debt
 * nobody can see would make someone else's number smaller for no visible
 * reason. These tests pin the arithmetic, not just the visibility.
 *
 * Ravi is an ADMIN throughout, for the same reason as the asset suite: the
 * interesting claim is that the most privileged role still cannot see what is
 * not theirs.
 */
@DisplayName("A member's private debts stay private")
class LiabilityPrivacyTest : ApiTestBase() {

    private lateinit var ishwarya: String
    private lateinit var ravi: String
    private lateinit var householdId: String
    private lateinit var ishwaryaMemberId: String
    private lateinit var raviMemberId: String

    private lateinit var goldId: String
    private lateinit var flatId: String

    @BeforeEach
    fun setUp() {
        ishwarya = signIn()
        ravi = signIn()

        val household = createHousehold(ishwarya, "Koduri", "private", "Ishwarya")
        householdId = household.path("id").asText()
        ishwaryaMemberId = household.path("myMemberId").asText()
        raviMemberId = addMember(ishwarya, householdId, "Ravi").path("id").asText()
        joinHousehold(ishwarya, householdId, raviMemberId, ravi, role = "admin")

        goldId = capture(
            ishwarya, householdId, "gold_physical", "Family gold",
            BigDecimal(1_000_000), visibility = "household",
        ).path("id").asText()

        flatId = capture(
            ishwarya, householdId, "universal", "Flat, Kakinada", BigDecimal(4_000_000),
            visibility = "household",
            owners = listOf(
                mapOf("memberId" to ishwaryaMemberId, "sharePct" to 50),
                mapOf("memberId" to raviMemberId, "sharePct" to 50),
            ),
        ).path("id").asText()
    }

    private fun createLiability(
        token: String,
        title: String,
        kind: String,
        outstanding: BigDecimal,
        visibility: String = "private",
        holders: List<Map<String, Any>> = emptyList(),
        securedBy: String? = null,
        emiAmount: Int? = null,
        emiDay: Int? = null,
    ) = post(
        "/api/v1/households/$householdId/liabilities", token,
        buildMap {
            put("title", title)
            put("kind", kind)
            put("outstanding", outstanding)
            put("visibility", visibility)
            if (holders.isNotEmpty()) put("holders", holders)
            if (securedBy != null) put("securedByInvestmentId", securedBy)
            if (emiAmount != null) put("emiAmount", emiAmount)
            if (emiDay != null) put("emiDay", emiDay)
        },
    ).also {
        check(it.statusCode.is2xxSuccessful) { "creating '$title' failed: ${it.body}" }
    }.json()

    private fun netWorth(token: String, scope: String = "household") =
        get("/api/v1/households/$householdId/dashboard?scope=$scope", token)
            .json().path("netWorth").decimalValue()

    private fun totalLiabilities(token: String, scope: String = "household") =
        get("/api/v1/households/$householdId/dashboard?scope=$scope", token)
            .json().path("totalLiabilities").decimalValue()

    // -------------------------------------------------------------------------

    @Test
    fun `an admin cannot see, edit or delete another member's private debt`() {
        val loan = createLiability(ishwarya, "Personal loan", "personal", BigDecimal(300_000))
        val id = loan.path("id").asText()

        val visible = get("/api/v1/households/$householdId/liabilities", ravi).json()
            .map { it.path("title").asText() }
        assertThat(visible).doesNotContain("Personal loan")

        assertThat(get("/api/v1/households/$householdId/liabilities/$id", ravi).status())
            .describedAs("404, not 403 — a 403 would confirm the debt exists")
            .isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(
            patch(
                "/api/v1/households/$householdId/liabilities/$id", ravi,
                mapOf("version" to 1, "title" to "hijacked"),
            ).status(),
        ).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(delete("/api/v1/households/$householdId/liabilities/$id", ravi).status())
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    /**
     * The arithmetic that matters. Two private debts, one each way, so the
     * difference between the two viewers' net worth is exactly the two amounts
     * neither is allowed to see.
     */
    @Test
    fun `a private debt does not move another viewer's net worth`() {
        createLiability(ishwarya, "Personal loan", "personal", BigDecimal(300_000))
        createLiability(ravi, "Credit card", "credit_card", BigDecimal(50_000))
        createLiability(
            ishwarya, "HDFC home loan", "home", BigDecimal(4_000_000),
            visibility = "household",
            holders = listOf(
                mapOf("memberId" to ishwaryaMemberId, "responsibilityPct" to 50),
                mapOf("memberId" to raviMemberId, "responsibilityPct" to 50),
            ),
        )

        // Assets are the same for both: gold 10L + flat 40L = 50L.
        // Ishwarya sees her own 3L personal loan plus the 40L home loan.
        assertThat(totalLiabilities(ishwarya)).isEqualByComparingTo(BigDecimal(4_300_000))
        assertThat(netWorth(ishwarya)).isEqualByComparingTo(BigDecimal(700_000))

        // Ravi sees his own 50k card plus the same 40L home loan — never her 3L.
        assertThat(totalLiabilities(ravi)).isEqualByComparingTo(BigDecimal(4_050_000))
        assertThat(netWorth(ravi)).isEqualByComparingTo(BigDecimal(950_000))

        // And the gap between the two figures is exactly the two private debts.
        assertThat(netWorth(ravi) - netWorth(ishwarya))
            .describedAs("nothing leaks in either direction")
            .isEqualByComparingTo(BigDecimal(250_000))
    }

    /** The debt mirror of the joint-holding test: split by share, counted once. */
    @Test
    fun `a joint loan is split by responsibility and never double-counted`() {
        createLiability(
            ishwarya, "HDFC home loan", "home", BigDecimal(4_000_000),
            visibility = "household",
            holders = listOf(
                mapOf("memberId" to ishwaryaMemberId, "responsibilityPct" to 50),
                mapOf("memberId" to raviMemberId, "responsibilityPct" to 50),
            ),
        )

        assertThat(totalLiabilities(ishwarya, "household"))
            .describedAs("the household owes the whole loan exactly once")
            .isEqualByComparingTo(BigDecimal(4_000_000))

        assertThat(totalLiabilities(ravi, "me"))
            .describedAs("each borrower carries their share, not the whole")
            .isEqualByComparingTo(BigDecimal(2_000_000))

        // Ravi personally: half the flat (20L) minus half the loan (20L) = 0.
        assertThat(netWorth(ravi, "me")).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `responsibility that does not total 100 percent is refused`() {
        val response = post(
            "/api/v1/households/$householdId/liabilities", ishwarya,
            mapOf(
                "title" to "Bad split", "kind" to "home", "outstanding" to 100000,
                "holders" to listOf(
                    mapOf("memberId" to ishwaryaMemberId, "responsibilityPct" to 60),
                    mapOf("memberId" to raviMemberId, "responsibilityPct" to 30),
                ),
            ),
        )
        assertThat(response.errorCode()).isEqualTo("responsibility_must_total_100")
    }

    /**
     * Encumbrance is a second-order leak path: the loan is hidden, but the
     * "₹3L owed against this" line on a SHARED asset would announce it. Both
     * ends have to be visible for the link to appear.
     */
    @Test
    fun `an encumbrance from a private loan is invisible on a shared asset`() {
        createLiability(
            ishwarya, "Personal loan", "personal", BigDecimal(300_000),
            visibility = "private", securedBy = goldId,
        )

        val hersGold = get("/api/v1/households/$householdId/investments/$goldId", ishwarya).json()
        assertThat(hersGold.path("encumbrance").decimalValue())
            .isEqualByComparingTo(BigDecimal(300_000))
        assertThat(hersGold.path("netEquity").decimalValue())
            .describedAs("value minus what is owed against it")
            .isEqualByComparingTo(BigDecimal(700_000))

        val hisGold = get("/api/v1/households/$householdId/investments/$goldId", ravi).json()
        // The API omits null fields, so an absent encumbrance is a MISSING node
        // rather than a null one — `isNull` alone would quietly pass on a real
        // leak, since MissingNode.isNull() is false.
        assertThat(hisGold.path("encumbrance").isMissingNode || hisGold.path("encumbrance").isNull)
            .describedAs("the shared asset must not hint at a private debt against it")
            .isTrue()
        assertThat(hisGold.path("netEquity").isMissingNode || hisGold.path("netEquity").isNull)
            .describedAs("nor via net equity, which would give the amount away by subtraction")
            .isTrue()
        // He still sees the gold itself, at its full value — only the debt is hidden.
        assertThat(hisGold.path("value").decimalValue()).isEqualByComparingTo(BigDecimal(1_000_000))
    }

    @Test
    fun `a shared loan shows as an encumbrance to everyone who can see both ends`() {
        createLiability(
            ishwarya, "HDFC home loan", "home", BigDecimal(4_000_000),
            visibility = "household", securedBy = flatId,
            holders = listOf(
                mapOf("memberId" to ishwaryaMemberId, "responsibilityPct" to 50),
                mapOf("memberId" to raviMemberId, "responsibilityPct" to 50),
            ),
        )
        val flat = get("/api/v1/households/$householdId/investments/$flatId", ravi).json()
        assertThat(flat.path("encumbrance").decimalValue()).isEqualByComparingTo(BigDecimal(4_000_000))
        assertThat(flat.path("netEquity").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `making a shared debt private removes it from everyone else's net worth`() {
        val loan = createLiability(
            ishwarya, "Car loan", "car", BigDecimal(600_000), visibility = "household",
        )
        val id = loan.path("id").asText()
        assertThat(totalLiabilities(ravi)).isEqualByComparingTo(BigDecimal(600_000))

        patch(
            "/api/v1/households/$householdId/liabilities/$id/visibility", ishwarya,
            mapOf("visibility" to "private"),
        )

        assertThat(get("/api/v1/households/$householdId/liabilities/$id", ravi).status())
            .isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(totalLiabilities(ravi))
            .describedAs("retroactively, and in the totals too")
            .isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `a co-borrower always sees a joint loan, even marked private`() {
        val loan = createLiability(
            ishwarya, "Family loan", "family", BigDecimal(200_000), visibility = "private",
            holders = listOf(
                mapOf("memberId" to ishwaryaMemberId, "responsibilityPct" to 50),
                mapOf("memberId" to raviMemberId, "responsibilityPct" to 50),
            ),
        )
        assertThat(
            get("/api/v1/households/$householdId/liabilities/${loan.path("id").asText()}", ravi).status(),
        ).describedAs("you cannot hide a debt from the person who shares it").isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `recording a payment moves the balance and keeps the history`() {
        val loan = createLiability(ishwarya, "Car loan", "car", BigDecimal(600_000))
        val id = loan.path("id").asText()

        val after = post(
            "/api/v1/households/$householdId/liabilities/$id/balances", ishwarya,
            mapOf("outstanding" to 550_000, "note" to "October EMI"),
        ).json()

        assertThat(after.path("outstanding").decimalValue()).isEqualByComparingTo(BigDecimal(550_000))
        assertThat(netWorth(ishwarya)).isEqualByComparingTo(BigDecimal(4_450_000))

        val history = get("/api/v1/households/$householdId/liabilities/$id/balances", ishwarya).json()
        assertThat(history)
            .describedAs("the trend is drawn from what was recorded, not interpolated")
            .hasSize(1) // opening balance and payment share today's date
    }

    @Test
    fun `an EMI appears in what is coming up`() {
        createLiability(
            ishwarya, "HDFC home loan", "home", BigDecimal(4_000_000),
            visibility = "household", emiAmount = 22_000, emiDay = 5,
            holders = listOf(mapOf("memberId" to ishwaryaMemberId, "responsibilityPct" to 100)),
        )
        val upcoming = get("/api/v1/households/$householdId/dashboard", ishwarya).json().path("upcoming")
        val emis = upcoming.filter { it.path("kind").asText() == "emi" }
        assertThat(emis).describedAs("money out belongs beside money in").hasSize(1)
        assertThat(emis[0].path("value").decimalValue()).isEqualByComparingTo(BigDecimal(22_000))
    }
}
