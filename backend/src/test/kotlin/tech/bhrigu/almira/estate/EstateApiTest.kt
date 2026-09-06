package tech.bhrigu.almira.estate

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal

@DisplayName("Estate, contacts, and nominee ≠ heir")
class EstateApiTest : ApiTestBase() {

    private lateinit var owner: String
    private lateinit var spouse: String
    private lateinit var householdId: String
    private lateinit var ownerMemberId: String
    private lateinit var spouseMemberId: String

    @BeforeEach
    fun setUp() {
        owner = signIn()
        spouse = signIn()
        val household = createHousehold(owner, "Koduri", "private", "Ishwarya")
        householdId = household.path("id").asText()
        ownerMemberId = household.path("myMemberId").asText()
        spouseMemberId = addMember(owner, householdId, "Ravi").path("id").asText()
        joinHousehold(owner, householdId, spouseMemberId, spouse)
    }

    private fun contact(token: String, body: Map<String, Any?>) =
        post("/api/v1/households/$householdId/contacts", token, body)

    private fun will(token: String, body: Map<String, Any?>) =
        post("/api/v1/households/$householdId/estate/documents", token, body)

    // --- contacts -------------------------------------------------------------

    @Test
    fun `a contact is a card, not a user — the CA who will never install this still counts`() {
        val created = contact(
            owner,
            mapOf(
                "kind" to "ca", "name" to "Ramesh Rao", "organisation" to "Rao & Associates",
                "phone" to "+91 98765 43210", "visibility" to "household",
            ),
        )
        assertThat(created.status()).isEqualTo(HttpStatus.CREATED)
        assertThat(created.json().path("name").asText()).isEqualTo("Ramesh Rao")
        assertThat(get("/api/v1/households/$householdId/contacts", spouse).json())
            .describedAs("a household contact is for the household")
            .hasSize(1)
    }

    @Test
    fun `a contact is linked to the records it handles, and found from either side`() {
        val holding = capture(
            owner, householdId, "insurance_term", "LIC term cover", BigDecimal("2000000"),
            visibility = "household",
            attributes = mapOf("policy_no" to "5567123456", "sum_assured" to 2000000, "premium_amount" to 18400),
        )
        val agent = contact(owner, mapOf("kind" to "agent", "name" to "Suresh", "visibility" to "household"))
            .json().path("id").asText()

        val linked = post(
            "/api/v1/households/$householdId/contacts/$agent/links", owner,
            mapOf("entityType" to "investment", "entityId" to holding.path("id").asText(), "role" to "Sold the policy"),
        ).json()
        assertThat(linked.path("links")).hasSize(1)
        assertThat(linked.path("links").first().path("entityTitle").asText()).isEqualTo("LIC term cover")

        val forRecord = get(
            "/api/v1/households/$householdId/contacts" +
                "?entityType=investment&entityId=${holding.path("id").asText()}",
            owner,
        ).json()
        assertThat(forRecord.map { it.path("name").asText() }).containsExactly("Suresh")
    }

    /**
     * A link must not become a way to confirm that a record you cannot see
     * exists — which is the whole reason reads are 404 rather than 403.
     */
    @Test
    fun `you cannot link a contact to a holding you cannot see`() {
        val hers = capture(
            owner, householdId, "gold_physical", "Her private gold", BigDecimal("500000"),
            visibility = "private",
        )
        val theirContact = contact(spouse, mapOf("name" to "Their banker", "visibility" to "private"))
            .json().path("id").asText()

        val refused = post(
            "/api/v1/households/$householdId/contacts/$theirContact/links", spouse,
            mapOf("entityType" to "investment", "entityId" to hers.path("id").asText()),
        )
        assertThat(refused.status()).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `a private contact is invisible to the rest of the household`() {
        contact(owner, mapOf("name" to "Her lawyer", "kind" to "lawyer", "visibility" to "private"))
        assertThat(get("/api/v1/households/$householdId/contacts", spouse).json()).isEmpty()
    }

    // --- estate documents -----------------------------------------------------

    @Test
    fun `a will records where the original is, who executes it, and who inherits`() {
        val flat = capture(
            owner, householdId, "property", "Flat, Kakinada", BigDecimal("6000000"),
            visibility = "household", attributes = mapOf("address" to "Kakinada"),
        )
        val created = will(
            owner,
            mapOf(
                "memberId" to ownerMemberId,
                "kind" to "will",
                "title" to "Ishwarya's will",
                "executedOn" to "2024-06-12",
                "location" to "Home locker, second shelf",
                "registered" to true,
                "roles" to listOf(mapOf("role" to "executor", "memberId" to spouseMemberId)),
                "beneficiaries" to listOf(
                    mapOf("investmentId" to flat.path("id").asText(), "memberId" to spouseMemberId, "sharePct" to 100),
                ),
            ),
        )
        assertThat(created.status()).isEqualTo(HttpStatus.CREATED)

        val document = created.json()
        assertThat(document.path("location").asText())
            .describedAs("a will nobody can find is a will that does not exist")
            .isEqualTo("Home locker, second shelf")
        assertThat(document.path("roles").first().path("name").asText()).isEqualTo("Ravi")
        assertThat(document.path("beneficiaries").first().path("investmentTitle").asText())
            .isEqualTo("Flat, Kakinada")
        assertThat(document.path("disclaimer").asText()).contains("nothing here is legal advice")
    }

    @Test
    fun `an executor can be a contact who is not a member of the household`() {
        val lawyer = contact(owner, mapOf("kind" to "lawyer", "name" to "Adv. Meera", "visibility" to "household"))
            .json().path("id").asText()

        val document = will(
            owner,
            mapOf(
                "memberId" to ownerMemberId, "kind" to "will", "title" to "Will",
                "roles" to listOf(mapOf("role" to "executor", "contactId" to lawyer)),
                "beneficiaries" to listOf(mapOf("memberId" to spouseMemberId, "sharePct" to 100)),
            ),
        ).json()
        assertThat(document.path("roles").first().path("name").asText()).isEqualTo("Adv. Meera")
    }

    @Test
    fun `a will is private by default, and an admin cannot read it`() {
        will(
            owner,
            mapOf(
                "memberId" to ownerMemberId, "kind" to "will", "title" to "Ishwarya's will",
                "beneficiaries" to listOf(mapOf("name" to "Aarav", "sharePct" to 100)),
            ),
        )
        assertThat(get("/api/v1/households/$householdId/estate/documents", spouse).json())
            .describedAs("what someone has chosen to leave, and to whom, is theirs")
            .isEmpty()
    }

    @Test
    fun `a shared will is readable by the household but stays its own member's to change`() {
        val document = will(
            owner,
            mapOf(
                "memberId" to ownerMemberId, "kind" to "will", "title" to "Ishwarya's will",
                "visibility" to "household",
                "beneficiaries" to listOf(mapOf("memberId" to spouseMemberId, "sharePct" to 100)),
            ),
        ).json()
        val id = document.path("id").asText()

        assertThat(get("/api/v1/households/$householdId/estate/documents/$id", spouse).status())
            .isEqualTo(HttpStatus.OK)

        val refused = patch(
            "/api/v1/households/$householdId/estate/documents/$id", spouse,
            mapOf("version" to document.path("version").asInt(), "title" to "Rewritten"),
        )
        assertThat(refused.status()).isEqualTo(HttpStatus.FORBIDDEN)
    }

    // --- nominee ≠ heir -------------------------------------------------------

    /**
     * The reason this module exists. In India a nominee receives and an heir
     * inherits, and families discover the difference at the worst moment.
     */
    @Test
    fun `a policy that pays a nominee the will does not name is flagged`() {
        val policy = capture(
            owner, householdId, "insurance_term", "LIC term cover", BigDecimal("2000000"),
            visibility = "household",
            attributes = mapOf("policy_no" to "5567", "sum_assured" to 2000000, "premium_amount" to 18400),
        )
        val policyId = policy.path("id").asText()

        call(
            HttpMethod.PUT, "/api/v1/households/$householdId/investments/$policyId/nominees", owner,
            mapOf("nominees" to listOf(mapOf("name" to "Mohan (brother)", "sharePct" to 100))),
        )
        will(
            owner,
            mapOf(
                "memberId" to ownerMemberId, "kind" to "will", "title" to "Ishwarya's will",
                "beneficiaries" to listOf(
                    mapOf("investmentId" to policyId, "memberId" to spouseMemberId, "sharePct" to 100),
                ),
            ),
        )

        val mismatches = get("/api/v1/households/$householdId/estate/mismatches", owner).json()
        assertThat(mismatches).hasSize(1)
        val flagged = mismatches.first()
        assertThat(flagged.path("nominees").map { it.asText() }).containsExactly("Mohan (brother)")
        assertThat(flagged.path("heirs").map { it.asText() }).containsExactly("Ravi")
        assertThat(flagged.path("explanation").asText())
            .describedAs("states the difference; does not say which one is wrong")
            .contains("A nominee receives the money; an heir inherits it")
    }

    @Test
    fun `agreement is not a mismatch`() {
        val policy = capture(
            owner, householdId, "insurance_term", "LIC term cover", BigDecimal("2000000"),
            visibility = "household",
            attributes = mapOf("policy_no" to "5567", "sum_assured" to 2000000, "premium_amount" to 18400),
        )
        val policyId = policy.path("id").asText()
        call(
            HttpMethod.PUT, "/api/v1/households/$householdId/investments/$policyId/nominees", owner,
            mapOf("nominees" to listOf(mapOf("memberId" to spouseMemberId, "sharePct" to 100))),
        )
        will(
            owner,
            mapOf(
                "memberId" to ownerMemberId, "kind" to "will", "title" to "Will",
                "beneficiaries" to listOf(
                    mapOf("investmentId" to policyId, "memberId" to spouseMemberId, "sharePct" to 100),
                ),
            ),
        )
        assertThat(get("/api/v1/households/$householdId/estate/mismatches", owner).json()).isEmpty()
    }

    /**
     * The flag is derived from two records, and derived data leaks as readily as
     * either of them: a mismatch on a private holding is nobody else's news.
     */
    @Test
    fun `a mismatch on a private holding is invisible to everyone else`() {
        val gold = capture(
            owner, householdId, "gold_physical", "Her gold", BigDecimal("500000"), visibility = "private",
        )
        val goldId = gold.path("id").asText()
        call(
            HttpMethod.PUT, "/api/v1/households/$householdId/investments/$goldId/nominees", owner,
            mapOf("nominees" to listOf(mapOf("name" to "Mohan", "sharePct" to 100))),
        )
        will(
            owner,
            mapOf(
                "memberId" to ownerMemberId, "kind" to "will", "title" to "Will",
                "visibility" to "household",
                "beneficiaries" to listOf(
                    mapOf("investmentId" to goldId, "memberId" to spouseMemberId, "sharePct" to 100),
                ),
            ),
        )

        assertThat(get("/api/v1/households/$householdId/estate/mismatches", owner).json()).hasSize(1)
        assertThat(get("/api/v1/households/$householdId/estate/mismatches", spouse).json())
            .describedAs("a mismatch names the holding; the holding is private")
            .isEmpty()
    }

    @Test
    fun `a draft will is not compared against anything yet`() {
        val policy = capture(
            owner, householdId, "insurance_term", "Policy", BigDecimal("100000"),
            visibility = "household",
            attributes = mapOf("policy_no" to "1", "sum_assured" to 100000, "premium_amount" to 1000),
        )
        val policyId = policy.path("id").asText()
        call(
            HttpMethod.PUT, "/api/v1/households/$householdId/investments/$policyId/nominees", owner,
            mapOf("nominees" to listOf(mapOf("name" to "Mohan", "sharePct" to 100))),
        )
        will(
            owner,
            mapOf(
                "memberId" to ownerMemberId, "kind" to "will", "title" to "Draft will",
                "status" to "draft",
                "beneficiaries" to listOf(
                    mapOf("investmentId" to policyId, "memberId" to spouseMemberId, "sharePct" to 100),
                ),
            ),
        )
        assertThat(get("/api/v1/households/$householdId/estate/mismatches", owner).json())
            .describedAs("an unexecuted draft is an intention, not an instrument")
            .isEmpty()
    }

    @Test
    fun `a role has to name someone`() {
        val refused = will(
            owner,
            mapOf(
                "memberId" to ownerMemberId, "kind" to "will", "title" to "Will",
                "roles" to listOf(mapOf("role" to "executor")),
            ),
        )
        assertThat(refused.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(refused.errorCode()).isEqualTo("role_needs_a_person")
    }
}
