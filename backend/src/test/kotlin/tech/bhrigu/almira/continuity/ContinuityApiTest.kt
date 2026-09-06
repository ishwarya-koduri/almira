package tech.bhrigu.almira.continuity

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestTemplate
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal

@DisplayName("Continuity: how your family claims this, and the handbook")
class ContinuityApiTest : ApiTestBase() {

    private lateinit var owner: String
    private lateinit var spouse: String
    private lateinit var householdId: String
    private lateinit var spouseMemberId: String

    @BeforeEach
    fun setUp() {
        owner = signIn()
        spouse = signIn()
        householdId = createHousehold(owner, "Koduri", "private", "Ishwarya").path("id").asText()
        spouseMemberId = addMember(owner, householdId, "Ravi").path("id").asText()
        joinHousehold(owner, householdId, spouseMemberId, spouse)
    }

    private fun policy(visibility: String = "household") = capture(
        owner, householdId, "insurance_term", "LIC term cover", BigDecimal("2000000"),
        visibility = visibility,
        attributes = mapOf("policy_no" to "5567123456", "sum_assured" to 2000000, "premium_amount" to 18400),
    )

    // --- the transmission guide ----------------------------------------------

    @Test
    fun `a policy comes with the steps, the forms and who to call`() {
        val id = policy().path("id").asText()

        val guide = get("/api/v1/households/$householdId/continuity/transmission/$id", owner).json()
        assertThat(guide.path("title2").asText()).isEqualTo("Claiming a life insurance policy")
        assertThat(guide.path("steps").map { it.path("step").asText() })
            .describedAs("what a family actually has to do, in order")
            .contains("Tell the insurer")
        assertThat(guide.path("steps").first().path("detail").asText()).contains("3783")
        assertThat(guide.path("authority").asText()).isEqualTo("The insurance company")
        assertThat(guide.path("disclaimer").asText()).contains("not legal advice")
    }

    /**
     * A checklist that guesses is worse than one that asks: "you have this" is
     * a bad thing to read while standing at a counter without it.
     */
    @Test
    fun `the document checklist only ticks what the record actually says`() {
        val id = policy().path("id").asText()

        val before = get("/api/v1/households/$householdId/continuity/transmission/$id", owner).json()
        val kycBefore = before.path("documents").first { it.path("name").asText().contains("KYC") }
        assertThat(kycBefore.path("ready").asBoolean()).isFalse()
        assertThat(kycBefore.path("note").asText()).contains("No nominee recorded")

        call(
            HttpMethod.PUT, "/api/v1/households/$householdId/investments/$id/nominees", owner,
            mapOf("nominees" to listOf(mapOf("memberId" to spouseMemberId, "sharePct" to 100))),
        )

        val after = get("/api/v1/households/$householdId/continuity/transmission/$id", owner).json()
        assertThat(after.path("documents").first { it.path("name").asText().contains("KYC") }
            .path("ready").asBoolean()).isTrue()
        assertThat(after.path("nominees").map { it.asText() }).containsExactly("Ravi")
    }

    @Test
    fun `a type with no guide of its own falls back to its category`() {
        val gold = capture(
            owner, householdId, "gold_jewelry", "Wedding set", BigDecimal("500000"),
            visibility = "household",
        )
        val guide = get(
            "/api/v1/households/$householdId/continuity/transmission/${gold.path("id").asText()}", owner,
        ).json()
        assertThat(guide.path("title2").asText()).isEqualTo("Physical gold and jewellery")
    }

    @Test
    fun `you cannot read the guide for a holding you cannot see`() {
        val id = policy(visibility = "private").path("id").asText()
        assertThat(get("/api/v1/households/$householdId/continuity/transmission/$id", spouse).status())
            .describedAs("a claim guide names the holding, so it inherits its privacy")
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    // --- the family handbook --------------------------------------------------

    @Test
    fun `the handbook says what there is, where it is and who to call`() {
        val id = policy().path("id").asText()
        call(
            HttpMethod.PUT, "/api/v1/households/$householdId/investments/$id/nominees", owner,
            mapOf("nominees" to listOf(mapOf("memberId" to spouseMemberId, "sharePct" to 100))),
        )
        val agent = post(
            "/api/v1/households/$householdId/contacts", owner,
            mapOf("kind" to "agent", "name" to "Suresh", "phone" to "9876543210", "visibility" to "household"),
        ).json().path("id").asText()
        post(
            "/api/v1/households/$householdId/contacts/$agent/links", owner,
            mapOf("entityType" to "investment", "entityId" to id),
        )

        val handbook = get("/api/v1/households/$householdId/continuity/handbook", owner).json()
        val entry = handbook.path("entries").first()
        assertThat(entry.path("title").asText()).isEqualTo("LIC term cover")
        assertThat(entry.path("reference").asText())
            .describedAs("the number that makes it findable again")
            .isEqualTo("5567123456")
        assertThat(entry.path("nominees").first().asText()).isEqualTo("Ravi (100%)")
        assertThat(entry.path("contacts").first().path("name").asText()).isEqualTo("Suresh")
        assertThat(entry.path("howToClaim").asText()).contains("nominee claims directly")
    }

    /**
     * Privacy is for life; continuity is for after. A record kept out of the
     * summary is counted so the family knows the list is not everything — and
     * never named, so they do not learn what it was.
     */
    @Test
    fun `a record left out of continuity is counted but not named`() {
        policy()
        val secret = capture(
            owner, householdId, "gold_physical", "Emergency buffer", BigDecimal("300000"),
            visibility = "household",
        )
        patch(
            "/api/v1/households/$householdId/investments/${secret.path("id").asText()}", owner,
            mapOf("version" to 1, "isInContinuity" to false),
        )

        val handbook = get("/api/v1/households/$householdId/continuity/handbook", owner).json()
        assertThat(handbook.path("entries").map { it.path("title").asText() })
            .containsExactly("LIC term cover")
        assertThat(handbook.path("excludedCount").asInt()).isEqualTo(1)
        assertThat(handbook.path("note").asText()).contains("deliberately left out")
    }

    @Test
    fun `the handbook shows only what the person printing it can see`() {
        policy(visibility = "private")
        capture(
            owner, householdId, "gold_physical", "Household gold", BigDecimal("100000"),
            visibility = "household",
        )

        val theirs = get("/api/v1/households/$householdId/continuity/handbook", spouse).json()
        assertThat(theirs.path("entries").map { it.path("title").asText() })
            .describedAs("no emergency unlock yet, so private stays private")
            .containsExactly("Household gold")
    }

    @Test
    fun `the printed handbook is a readable PDF`() {
        val id = policy().path("id").asText()
        call(
            HttpMethod.PUT, "/api/v1/households/$householdId/investments/$id/nominees", owner,
            mapOf("nominees" to listOf(mapOf("memberId" to spouseMemberId, "sharePct" to 100))),
        )
        post(
            "/api/v1/households/$householdId/liabilities", owner,
            mapOf(
                "kind" to "home", "title" to "HDFC home loan", "outstanding" to 2400000,
                "visibility" to "household",
                "holders" to listOf(mapOf("memberId" to spouseMemberId, "responsibilityPct" to 100)),
            ),
        )

        val bytes = RestTemplate().exchange(
            url("/api/v1/households/$householdId/continuity/handbook.pdf"),
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { setBearerAuth(owner) }),
            ByteArray::class.java,
        ).body!!

        Loader.loadPDF(bytes).use { document ->
            val text = PDFTextStripper().getText(document)
            assertThat(text).contains("For my family")
            assertThat(text).contains("LIC term cover")
            assertThat(text).contains("Nominee: Ravi")
            assertThat(text)
                .describedAs("it says what to do, not only what exists")
                .contains("To claim:")
            assertThat(text).contains("WHAT IS OWED")
        }
    }

    @Test
    fun `an empty household still prints something sensible`() {
        val bytes = RestTemplate().exchange(
            url("/api/v1/households/$householdId/continuity/handbook.pdf"),
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { setBearerAuth(owner) }),
            ByteArray::class.java,
        ).body!!
        Loader.loadPDF(bytes).use {
            assertThat(PDFTextStripper().getText(it)).contains("For my family")
        }
    }
}
