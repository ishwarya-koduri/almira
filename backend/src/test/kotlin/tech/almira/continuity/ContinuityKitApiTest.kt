package tech.almira.continuity

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
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestTemplate
import tech.almira.support.ApiTestBase
import java.math.BigDecimal

@DisplayName("The emergency timeline, the emergency kit, and the envelope edition")
class ContinuityKitApiTest : ApiTestBase() {

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

    private val raw = RestTemplate(org.springframework.http.client.JdkClientHttpRequestFactory()).apply {
        errorHandler = object : org.springframework.web.client.ResponseErrorHandler {
            override fun hasError(response: org.springframework.http.client.ClientHttpResponse) = false
            override fun handleError(response: org.springframework.http.client.ClientHttpResponse) = Unit
        }
    }

    private fun bytes(method: HttpMethod, path: String, token: String): ResponseEntity<ByteArray> = raw.exchange(
        url(path), method, HttpEntity<Void>(HttpHeaders().apply { setBearerAuth(token) }), ByteArray::class.java,
    )

    private fun stepUp(token: String) {
        val challenge = post("/api/v1/auth/step-up/request", token).json()
        val verified = post(
            "/api/v1/auth/step-up/verify", token,
            mapOf("code" to challenge.path("developmentCode").asText(), "requestId" to challenge.path("requestId").asText()),
        )
        check(verified.statusCode.is2xxSuccessful) { "step-up failed: ${verified.body}" }
    }

    // --- X-41 -------------------------------------------------------------------

    @Test
    fun `before naming someone, the owner sees the dated picture and what they would never see`() {
        capture(owner, householdId, "gold_physical", "Kept for the family", BigDecimal("100000"))
        val leftOut = capture(owner, householdId, "gold_physical", "Not for anyone", BigDecimal("100000"))
        patch(
            "/api/v1/households/$householdId/investments/${leftOut.path("id").asText()}", owner,
            mapOf("version" to 1, "isInContinuity" to false),
        )

        val preview = get(
            "/api/v1/households/$householdId/emergency/preview?trustedMemberId=$spouseMemberId&waitDays=7", owner,
        )
        assertThat(preview.status()).isEqualTo(HttpStatus.OK)
        val json = preview.json()
        assertThat(json.path("steps").map { it.path("title").asText() })
            .containsExactly("Ravi asks", "You're told", "7 days to say no", "Ravi sees the family plan", "It closes by itself")
        assertThat(json.path("willSee").first().asText()).startsWith("1 thing marked for the family plan")
        assertThat(json.path("neverSee").first().asText()).isEqualTo("The 1 entry you left out of the family plan")
        assertThat(preview.body!!).describedAs("counts, never titles").doesNotContain("Not for anyone")

        assertThat(get("/api/v1/households/$householdId/emergency/preview?trustedMemberId=$ownerMemberId", owner).status())
            .describedAs("you cannot be your own emergency contact").isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(get("/api/v1/households/$householdId/emergency/preview?trustedMemberId=$spouseMemberId&waitDays=0", owner).status())
            .isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `a request carries its timeline, on both sides`() {
        post("/api/v1/households/$householdId/emergency/contacts", owner, mapOf("trustedMemberId" to spouseMemberId, "waitDays" to 7))
        post("/api/v1/households/$householdId/emergency/requests", spouse, mapOf("subjectMemberId" to ownerMemberId))

        val ownersView = get("/api/v1/households/$householdId/emergency/requests", owner).json().first()
        assertThat(ownersView.path("timeline").map { it.path("title").asText() })
            .containsExactly("Ravi asked", "You were told", "7 days to say no", "Ravi sees the family plan", "It closes by itself")
        assertThat(ownersView.path("timeline").map { it.path("state").asText() })
            .containsExactly("done", "done", "now", "next", "next")

        val theirs = get("/api/v1/households/$householdId/emergency/requests", spouse).json().first()
        assertThat(theirs.path("timeline")[0].path("title").asText()).isEqualTo("You asked")
    }

    // --- X-61 -------------------------------------------------------------------

    @Test
    fun `the emergency kit names who can ask and who to call, and its code points at the app`() {
        post("/api/v1/households/$householdId/emergency/contacts", owner, mapOf("trustedMemberId" to spouseMemberId, "waitDays" to 14))
        post(
            "/api/v1/households/$householdId/contacts", owner,
            mapOf("kind" to "lawyer", "name" to "Ramesh Rao", "phone" to "98765 43210", "visibility" to "household"),
        )

        val kit = get("/api/v1/households/$householdId/continuity/emergency-kit", owner).json()
        assertThat(kit.path("trusted").first().path("name").asText()).isEqualTo("Ravi")
        assertThat(kit.path("whoToCall").first().path("phone").asText()).isEqualTo("98765 43210")
        assertThat(kit.path("appUrl").asText()).isEqualTo(url("/#/continuity"))
        assertThat(kit.path("keepItNote").asText()).isEqualTo("Keep this in the almirah.")

        val pdf = bytes(HttpMethod.GET, "/api/v1/households/$householdId/continuity/emergency-kit.pdf", owner)
        assertThat(pdf.statusCode).isEqualTo(HttpStatus.OK)
        Loader.loadPDF(pdf.body!!).use { document ->
            assertThat(document.numberOfPages).isEqualTo(1)
            assertThat(PDFTextStripper().getText(document)).contains("Ramesh Rao").contains("Keep this in the almirah.")
            assertThat(PrintedPagesTest.decode(PrintedPagesTest.render(document))).isEqualTo(url("/#/continuity"))
        }
    }

    // --- P-28 -------------------------------------------------------------------

    @Test
    fun `the envelope edition needs a step-up, is numbered, and a new edition turns the old code off`() {
        capture(owner, householdId, "gold_physical", "Her gold", BigDecimal("500000"))
        val path = "/api/v1/households/$householdId/continuity/handbook/envelope.pdf"

        val refused = call(HttpMethod.POST, path, owner)
        assertThat(refused.status()).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(refused.errorCode()).isEqualTo("step_up_required")

        stepUp(owner)
        val first = bytes(HttpMethod.POST, path, owner)
        assertThat(first.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(first.headers.getFirst("X-Almira-Edition")).isEqualTo("1")
        val firstLink = Loader.loadPDF(first.body!!).use { document ->
            val text = PDFTextStripper().getText(document)
            assertThat(text).contains("This works even if Almira is gone.")
            assertThat(text).describedAs("the handbook itself follows the cover, in full").contains("Her gold").contains("WHAT THERE IS")
            PrintedPagesTest.decode(PrintedPagesTest.render(document))
        }
        assertThat(firstLink).startsWith(url("/share/"))
        val firstToken = firstLink.substringAfterLast("/")

        val opened = get("/api/v1/share/$firstToken")
        assertThat(opened.status()).isEqualTo(HttpStatus.OK)
        assertThat(opened.json().path("handbook").path("entries").map { it.path("title").asText() }).containsExactly("Her gold")

        assertThat(get("/share/$firstToken").status())
            .describedAs("the code opens a page, not a sign-in error (known issue 43)")
            .isEqualTo(HttpStatus.OK)

        val second = bytes(HttpMethod.POST, path, owner)
        assertThat(second.headers.getFirst("X-Almira-Edition")).isEqualTo("2")
        val secondToken = Loader.loadPDF(second.body!!).use { PrintedPagesTest.decode(PrintedPagesTest.render(it)) }
            .substringAfterLast("/")

        assertThat(get("/api/v1/share/$firstToken").status())
            .describedAs("an old envelope stops opening anything once a newer one exists")
            .isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(get("/api/v1/share/$secondToken").status()).isEqualTo(HttpStatus.OK)

        val links = get("/api/v1/households/$householdId/shares", owner).json()
        assertThat(links.map { it.path("label").asText() })
            .describedAs("each edition's link is listed like any link, so it can be seen and withdrawn")
            .contains("Family handbook, envelope edition 2")
        val expiry = java.time.Instant.parse(links.first { it.path("label").asText().endsWith("2") }.path("expiresAt").asText())
        assertThat(expiry).isAfter(java.time.Instant.now().plus(java.time.Duration.ofDays(360)))

        assertThat(db.queryForList("select action from activity_log where household_id = ?::uuid", String::class.java, householdId))
            .contains("continuity.handbook.envelope", "share.revoke")
    }

    @Test
    fun `an empty handbook has no envelope to print`() {
        stepUp(owner)
        val empty = call(HttpMethod.POST, "/api/v1/households/$householdId/continuity/handbook/envelope.pdf", owner)
        assertThat(empty.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(empty.errorCode()).isEqualTo("handbook_empty")
    }
}
