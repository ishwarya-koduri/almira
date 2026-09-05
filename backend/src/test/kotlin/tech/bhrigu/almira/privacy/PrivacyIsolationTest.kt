package tech.bhrigu.almira.privacy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal

/**
 * The tests that must never go green while the product is broken.
 *
 * docs/05 §3 makes one promise above all others: joining a household does not
 * mean surrendering financial privacy, and no role — not owner, not admin —
 * can read another member's Private records. Everything else in Almira is a
 * feature; this is the licence to exist.
 *
 * Ravi is deliberately an ADMIN in every scenario. Testing with a viewer would
 * prove nothing: the interesting claim is that the most privileged role in the
 * household still cannot see what it is not entitled to.
 */
@DisplayName("A member's private records stay private")
class PrivacyIsolationTest : ApiTestBase() {

    private lateinit var ishwarya: String
    private lateinit var ravi: String
    private lateinit var outsider: String
    private lateinit var householdId: String
    private lateinit var ishwaryaMemberId: String
    private lateinit var raviMemberId: String

    /**
     * The Fixed Deposit template marks the interest rate required, so a capture
     * without it is correctly rejected. Kept in one place rather than repeated,
     * so a template change surfaces once.
     */
    private val FD_FIELDS = mapOf<String, Any?>("interest_rate" to "7.1")

    @BeforeEach
    fun setUp() {
        ishwarya = signIn()
        ravi = signIn()
        outsider = signIn()

        val household = createHousehold(ishwarya, "Koduri", "private", "Ishwarya")
        householdId = household.path("id").asText()
        ishwaryaMemberId = household.path("myMemberId").asText()

        raviMemberId = addMember(ishwarya, householdId, "Ravi").path("id").asText()
        joinHousehold(ishwarya, householdId, raviMemberId, ravi, role = "admin")
    }

    @Test
    fun `an admin cannot list, read, edit or delete another member's private record`() {
        val fd = capture(
            ishwarya, householdId, "fd", "SBI FD", BigDecimal(500_000), "private",
            attributes = FD_FIELDS,
        )
        val fdId = fd.path("id").asText()

        val visibleToRavi = get("/api/households/$householdId/investments", ravi).json()
            .map { it.path("title").asText() }
        assertThat(visibleToRavi)
            .describedAs("an admin's list must not contain another member's private record")
            .doesNotContain("SBI FD")

        assertThat(get("/api/households/$householdId/investments/$fdId", ravi).status())
            .describedAs("404, not 403 — a 403 would confirm the record exists")
            .isEqualTo(HttpStatus.NOT_FOUND)

        assertThat(
            patch(
                "/api/households/$householdId/investments/$fdId", ravi,
                mapOf("version" to 1, "title" to "hijacked"),
            ).status(),
        ).isEqualTo(HttpStatus.NOT_FOUND)

        assertThat(delete("/api/households/$householdId/investments/$fdId", ravi).status())
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `a private record contributes nothing to another member's total, not even its amount`() {
        capture(
            ishwarya, householdId, "fd", "Private FD", BigDecimal(500_000), "private",
            attributes = FD_FIELDS,
        )
        capture(ishwarya, householdId, "gold_physical", "Family gold", BigDecimal(100_000), "household")

        assertThat(dashboardTotal(ishwarya, householdId))
            .describedAs("the owner sees her own true total")
            .isEqualByComparingTo(BigDecimal(600_000))

        assertThat(dashboardTotal(ravi, householdId))
            .describedAs("the private 500,000 must be absent from the admin's number entirely")
            .isEqualByComparingTo(BigDecimal(100_000))
    }

    @Test
    fun `a co-owner always sees a joint record, even one marked private`() {
        val joint = capture(
            ishwarya, householdId, "universal", "Flat, Kakinada", BigDecimal(4_000_000),
            visibility = "private",
            owners = listOf(
                mapOf("memberId" to ishwaryaMemberId, "sharePct" to 50),
                mapOf("memberId" to raviMemberId, "sharePct" to 50),
            ),
        )
        val id = joint.path("id").asText()

        assertThat(get("/api/households/$householdId/investments/$id", ravi).status())
            .describedAs("you cannot hide a jointly owned asset from your co-owner")
            .isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `a joint holding is split by share and never double-counted`() {
        capture(
            ishwarya, householdId, "universal", "Flat", BigDecimal(4_000_000),
            visibility = "household",
            owners = listOf(
                mapOf("memberId" to ishwaryaMemberId, "sharePct" to 50),
                mapOf("memberId" to raviMemberId, "sharePct" to 50),
            ),
        )

        assertThat(dashboardTotal(ishwarya, householdId, scope = "household"))
            .describedAs("the household counts the whole flat exactly once")
            .isEqualByComparingTo(BigDecimal(4_000_000))

        assertThat(dashboardTotal(ravi, householdId, scope = "me"))
            .describedAs("each owner is attributed their share, not the whole")
            .isEqualByComparingTo(BigDecimal(2_000_000))
    }

    @Test
    fun `a scoped record is visible only to the members it names`() {
        val third = signIn()
        val thirdMemberId = addMember(ishwarya, householdId, "Meera").path("id").asText()
        joinHousehold(ishwarya, householdId, thirdMemberId, third, role = "editor")

        val scoped = capture(
            ishwarya, householdId, "fd", "Shared with Ravi only", BigDecimal(200_000),
            visibility = "scoped", visibleTo = listOf(raviMemberId), attributes = FD_FIELDS,
        )
        val id = scoped.path("id").asText()

        assertThat(get("/api/households/$householdId/investments/$id", ravi).status())
            .describedAs("the named member sees it")
            .isEqualTo(HttpStatus.OK)
        assertThat(get("/api/households/$householdId/investments/$id", third).status())
            .describedAs("another household member does not")
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `revoking a scoped grant ends access immediately and removes the amount from totals`() {
        val scoped = capture(
            ishwarya, householdId, "fd", "Shared FD", BigDecimal(200_000),
            visibility = "scoped", visibleTo = listOf(raviMemberId), attributes = FD_FIELDS,
        )
        val id = scoped.path("id").asText()
        assertThat(dashboardTotal(ravi, householdId)).isEqualByComparingTo(BigDecimal(200_000))

        patch(
            "/api/households/$householdId/investments/$id/visibility", ishwarya,
            mapOf("visibility" to "private"),
        )

        assertThat(get("/api/households/$householdId/investments/$id", ravi).status())
            .describedAs("access ends mid-session, not at next login")
            .isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(dashboardTotal(ravi, householdId))
            .describedAs("and the amount leaves the total with it")
            .isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `changing a record from household to private hides it retroactively everywhere`() {
        val gold = capture(
            ishwarya, householdId, "gold_physical", "Coins", BigDecimal(100_000), "household",
        )
        val id = gold.path("id").asText()
        assertThat(get("/api/households/$householdId/investments/$id", ravi).status())
            .isEqualTo(HttpStatus.OK)

        patch(
            "/api/households/$householdId/investments/$id/visibility", ishwarya,
            mapOf("visibility" to "private"),
        )

        val titles = get("/api/households/$householdId/investments", ravi).json()
            .map { it.path("title").asText() }
        assertThat(titles).doesNotContain("Coins")
        assertThat(get("/api/households/$householdId/investments?q=Coins", ravi).json())
            .describedAs("search is not a side-channel")
            .isEmpty()
        assertThat(dashboardTotal(ravi, householdId)).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `a user from another household sees nothing at all`() {
        capture(ishwarya, householdId, "gold_physical", "Coins", BigDecimal(100_000), "household")

        assertThat(get("/api/households/$householdId/investments", outsider).status())
            .isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(get("/api/households/$householdId/members", outsider).status())
            .describedAs("not even the roster")
            .isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(get("/api/households/$householdId/dashboard", outsider).status())
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `an unauthenticated request reaches nothing`() {
        assertThat(get("/api/households/$householdId/investments").status())
            .isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(get("/api/me").status()).isEqualTo(HttpStatus.UNAUTHORIZED)
    }
}
