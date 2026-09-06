package tech.bhrigu.almira.continuity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal

@DisplayName("Emergency access: a delay, a veto, and only what was marked")
class EmergencyAccessApiTest : ApiTestBase() {

    private lateinit var owner: String
    private lateinit var trusted: String
    private lateinit var householdId: String
    private lateinit var ownerMemberId: String
    private lateinit var trustedMemberId: String

    @BeforeEach
    fun setUp() {
        owner = signIn()
        trusted = signIn()
        val household = createHousehold(owner, "Koduri", "private", "Ishwarya")
        householdId = household.path("id").asText()
        ownerMemberId = household.path("myMemberId").asText()
        trustedMemberId = addMember(owner, householdId, "Ravi").path("id").asText()
        joinHousehold(owner, householdId, trustedMemberId, trusted)
    }

    private fun nameContact(waitDays: Int = 14) = post(
        "/api/v1/households/$householdId/emergency/contacts", owner,
        mapOf("trustedMemberId" to trustedMemberId, "waitDays" to waitDays),
    )

    private fun requestAccess(token: String = trusted, reason: String? = "Hospital") = post(
        "/api/v1/households/$householdId/emergency/requests", token,
        mapOf("subjectMemberId" to ownerMemberId, "reason" to reason),
    )

    /**
     * Ages the request rather than moving the unlock alone: the database
     * insists an unlock cannot precede its request, which is exactly the
     * invariant we would want if some future code path tried to shorten a wait.
     */
    private fun fastForwardPastTheWait() {
        db.update(
            """
            update emergency_requests
            set requested_at = now() - interval '20 days',
                unlock_at    = now() - interval '6 days'
            where household_id = ?::uuid
            """.trimIndent(),
            householdId,
        )
    }

    @Test
    fun `naming a trusted contact tells them, and shows on both sides`() {
        val named = nameContact()
        assertThat(named.status()).isEqualTo(HttpStatus.CREATED)
        assertThat(named.json().path("trustedMemberName").asText()).isEqualTo("Ravi")

        val theirView = get("/api/v1/households/$householdId/emergency/contacts", trusted).json()
        assertThat(theirView).hasSize(1)
        assertThat(theirView.first().path("theyTrustMe").asBoolean())
            .describedAs("being someone's emergency contact is a responsibility, not a secret from them")
            .isTrue()
    }

    @Test
    fun `only someone who was named can ask`() {
        val refused = requestAccess()
        assertThat(refused.status()).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(refused.json().path("error").path("message").asText()).contains("named")
    }

    @Test
    fun `a request waits, and the person it concerns is told and can stop it`() {
        nameContact()
        val request = requestAccess()
        assertThat(request.status()).isEqualTo(HttpStatus.CREATED)
        assertThat(request.json().path("status").asText()).isEqualTo("waiting")
        assertThat(request.json().path("secondsUntilUnlock").asLong()).isGreaterThan(0)

        val ownersView = get("/api/v1/households/$householdId/emergency/requests", owner).json()
        assertThat(ownersView).describedAs("a request they never hear about is a backdoor").hasSize(1)

        val vetoed = post(
            "/api/v1/households/$householdId/emergency/requests/" +
                "${request.json().path("id").asText()}/veto",
            owner,
        )
        assertThat(vetoed.json().path("status").asText()).isEqualTo("vetoed")
        assertThat(vetoed.json().path("explanation").asText()).contains("Nothing was opened")
    }

    @Test
    fun `while it is waiting, nothing has opened`() {
        capture(
            owner, householdId, "gold_physical", "Her private gold", BigDecimal("500000"),
            visibility = "private",
        )
        nameContact()
        requestAccess()

        assertThat(get("/api/v1/households/$householdId/investments", trusted).json())
            .describedAs("the delay is the safeguard; it has to actually hold")
            .isEmpty()
    }

    @Test
    fun `when the window opens, continuity records are visible — and nothing else`() {
        val included = capture(
            owner, householdId, "gold_physical", "Her private gold", BigDecimal("500000"),
            visibility = "private",
        )
        val excluded = capture(
            owner, householdId, "gold_physical", "Not for the family", BigDecimal("300000"),
            visibility = "private",
        )
        patch(
            "/api/v1/households/$householdId/investments/${excluded.path("id").asText()}", owner,
            mapOf("version" to 1, "isInContinuity" to false),
        )
        assertThat(included.path("id").asText()).isNotBlank()

        nameContact()
        requestAccess()
        fastForwardPastTheWait()

        val visible = get("/api/v1/households/$householdId/investments", trusted).json()
            .map { it.path("title").asText() }
        assertThat(visible)
            .describedAs("privacy is for life, continuity is for after — and only for what was marked")
            .containsExactly("Her private gold")
    }

    @Test
    fun `a veto during the wait means the window never opens`() {
        capture(
            owner, householdId, "gold_physical", "Her private gold", BigDecimal("500000"),
            visibility = "private",
        )
        nameContact()
        val id = requestAccess().json().path("id").asText()
        post("/api/v1/households/$householdId/emergency/requests/$id/veto", owner)
        fastForwardPastTheWait()

        assertThat(get("/api/v1/households/$householdId/investments", trusted).json())
            .describedAs("a veto is not a pause")
            .isEmpty()
    }

    @Test
    fun `only the person it concerns can veto`() {
        nameContact()
        val id = requestAccess().json().path("id").asText()
        val refused = post("/api/v1/households/$householdId/emergency/requests/$id/veto", trusted)
        assertThat(refused.status()).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `an open window shows the family handbook, including the will`() {
        capture(
            owner, householdId, "gold_physical", "Her private gold", BigDecimal("500000"),
            visibility = "private",
        )
        post(
            "/api/v1/households/$householdId/estate/documents", owner,
            mapOf(
                "memberId" to ownerMemberId, "kind" to "will", "title" to "Ishwarya's will",
                "location" to "Home locker",
                "beneficiaries" to listOf(mapOf("memberId" to trustedMemberId, "sharePct" to 100)),
            ),
        )

        nameContact()
        requestAccess()
        fastForwardPastTheWait()

        val handbook = get("/api/v1/households/$householdId/continuity/handbook", trusted).json()
        assertThat(handbook.path("entries").map { it.path("title").asText() })
            .containsExactly("Her private gold")
        assertThat(handbook.path("instruments").map { it.path("title").asText() })
            .describedAs("an instrument nobody can read is one nobody can act on")
            .containsExactly("Ishwarya's will")
        assertThat(handbook.path("instruments").first().path("location").asText())
            .isEqualTo("Home locker")
    }

    @Test
    fun `an expired window closes on its own`() {
        capture(
            owner, householdId, "gold_physical", "Her private gold", BigDecimal("500000"),
            visibility = "private",
        )
        nameContact()
        requestAccess()
        db.update(
            """
            update emergency_requests
            set requested_at      = now() - interval '60 days',
                unlock_at         = now() - interval '46 days',
                access_expires_at = now() - interval '1 day'
            where household_id = ?::uuid
            """.trimIndent(),
            householdId,
        )

        assertThat(get("/api/v1/households/$householdId/investments", trusted).json())
            .describedAs("derived from the clock, so nothing can leave it switched on")
            .isEmpty()
        assertThat(
            get("/api/v1/households/$householdId/emergency/requests", trusted).json()
                .first().path("status").asText(),
        ).isEqualTo("ended")
    }

    @Test
    fun `an unlock reaches one person, not the household`() {
        val third = signIn()
        val thirdMemberId = addMember(owner, householdId, "Aarav").path("id").asText()
        joinHousehold(owner, householdId, thirdMemberId, third)

        capture(
            owner, householdId, "gold_physical", "Her private gold", BigDecimal("500000"),
            visibility = "private",
        )
        nameContact()
        requestAccess()
        fastForwardPastTheWait()

        assertThat(get("/api/v1/households/$householdId/investments", trusted).json()).hasSize(1)
        assertThat(get("/api/v1/households/$householdId/investments", third).json())
            .describedAs("the unlock belongs to whoever asked, not to everyone")
            .isEmpty()
    }

    @Test
    fun `the whole sequence is in the audit log`() {
        nameContact()
        val id = requestAccess().json().path("id").asText()
        post("/api/v1/households/$householdId/emergency/requests/$id/veto", owner)

        val actions = db.queryForList(
            "select action from activity_log where household_id = ?::uuid order by created_at",
            householdId,
        ).map { it["action"] as String }

        assertThat(actions).contains("emergency.contact.set", "emergency.request", "emergency.veto")
    }
}
