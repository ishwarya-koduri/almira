package tech.almira.lifecycle

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.math.BigDecimal

@DisplayName("Succession: named by the owner, claimed only on the event, and never a way to see more")
class SuccessionApiTest : LifecycleTestSupport() {

    private lateinit var ishwarya: String
    private lateinit var ravi: String
    private lateinit var meera: String
    private lateinit var householdId: String
    private lateinit var ishwaryaMemberId: String
    private lateinit var raviMemberId: String

    @BeforeEach
    fun setUp() {
        ishwarya = signIn()
        ravi = signIn()
        meera = signIn()
        val household = createHousehold(ishwarya, "Koduri", "private", "Ishwarya")
        householdId = household.path("id").asText()
        ishwaryaMemberId = household.path("myMemberId").asText()
        raviMemberId = addMember(ishwarya, householdId, "Ravi").path("id").asText()
        joinHousehold(ishwarya, householdId, raviMemberId, ravi, role = "editor")
        val meeraMemberId = addMember(ishwarya, householdId, "Meera").path("id").asText()
        joinHousehold(ishwarya, householdId, meeraMemberId, meera, role = "admin")
    }

    private fun name(token: String = ishwarya, memberId: String = raviMemberId) =
        call(HttpMethod.PUT, "/api/v1/households/$householdId/successor", token, mapOf("memberId" to memberId))

    private fun claim() = post("/api/v1/households/$householdId/successor/claim", ravi)

    @Test
    fun `the owner names someone, who is told, and nobody else can see it`() {
        assertThat(name(meera).status()).isEqualTo(HttpStatus.FORBIDDEN)
        val named = name()
        assertThat(named.status()).describedAs(named.body).isEqualTo(HttpStatus.OK)

        val his = get("/api/v1/households/$householdId/successor", ravi).json()
        assertThat(his.path("youAreTheSuccessor").asBoolean()).isTrue()
        assertThat(his.path("canClaim").asBoolean()).isFalse()
        assertThat(get("/api/v1/households/$householdId/successor", meera).json().path("named").asBoolean()).isFalse()
        assertThat(templatesFor(userId(ravi))).contains("lifecycle.successor.named")

        val kid = addMember(ishwarya, householdId, "Aarav").path("id").asText()
        assertThat(name(memberId = kid).errorCode()).isEqualTo("successor_needs_login")
    }

    @Test
    fun `a claim waits for the event, and a week after a memorial`() {
        name()
        assertThat(claim().errorCode()).isEqualTo("succession_not_yet")

        stepUp(meera)
        post("/api/v1/households/$householdId/members/$ishwaryaMemberId/memorial", meera)
            .also { assertThat(it.status()).describedAs(it.body).isEqualTo(HttpStatus.OK) }
        assertThat(claim().errorCode()).describedAs("the same week").isEqualTo("succession_not_yet")

        // A week on. The trigger refuses to rewrite a memorial, so the test steps around it.
        db.execute(
            """
            do $$ begin
              set local session_replication_role = replica;
              update member_memorials set marked_at = now() - interval '8 days'
               where household_id = '$householdId'::uuid;
            end $$
            """.trimIndent(),
        )
        assertThat(get("/api/v1/households/$householdId/successor", ravi).json().path("canClaim").asBoolean()).isTrue()
        assertThat(claim().errorCode()).isEqualTo("step_up_required")
        stepUp(ravi)
        val claimed = claim()
        assertThat(claimed.status()).describedAs(claimed.body).isEqualTo(HttpStatus.OK)
        assertThat(claimed.json().path("claimedBasis").asText()).isEqualTo("passed_away")
        assertThat(household(ravi, householdId).path("myRole").asText()).isEqualTo("owner")
        assertThat(
            db.queryForObject(
                "select count(*) from activity_log where action = 'household.succession.claim' and household_id = ?::uuid",
                Int::class.java, householdId,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `carrying the household on opens none of the owner's private records`() {
        // One she marked for the family (the default), one she kept out of it.
        capture(ishwarya, householdId, "gold_physical", "For the family", BigDecimal(500_000))
        post(
            "/api/v1/households/$householdId/investments", ishwarya,
            mapOf(
                "typeId" to typeId(ishwarya, householdId, "gold_physical"), "title" to "Ishwarya's private gold",
                "investedAmount" to 100_000, "visibility" to "private", "isInContinuity" to false,
            ),
        ).also { check(it.statusCode.is2xxSuccessful) { it.body!! } }
        name()
        // Ravi is her trusted contact, asks, and the window opens.
        post(
            "/api/v1/households/$householdId/emergency/contacts", ishwarya,
            mapOf("trustedMemberId" to raviMemberId, "waitDays" to 14),
        )
        post("/api/v1/households/$householdId/emergency/requests", ravi, mapOf("subjectMemberId" to ishwaryaMemberId))
        val herId = userId(ishwarya)
        db.update(
            "update emergency_requests set requested_at = now() - interval '20 days', unlock_at = now() - interval '6 days' where household_id = ?::uuid",
            householdId,
        )
        db.update("update user_sessions set last_used_at = now() - interval '30 days' where user_id = ?::uuid", herId)

        stepUp(ravi)
        val claimed = claim()
        assertThat(claimed.status()).describedAs(claimed.body).isEqualTo(HttpStatus.OK)
        assertThat(claimed.json().path("claimedBasis").asText()).isEqualTo("emergency_access")
        assertThat(household(ravi, householdId).path("myRole").asText()).isEqualTo("admin")

        assertThat(get("/api/v1/households/$householdId/investments", ravi).json().map { it.path("title").asText() })
            .describedAs("what she marked for the family opens through the window; running the household opens nothing more")
            .contains("For the family")
            .doesNotContain("Ishwarya's private gold")
    }
}
