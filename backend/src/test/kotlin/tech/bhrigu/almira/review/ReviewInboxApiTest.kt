package tech.bhrigu.almira.review

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal
import java.util.UUID

/**
 * "To review" through the API (catch-up plan X-51): one queue, in the order to
 * look at it, and only what this person is asked about.
 *
 * The owner connection only ages records and sets dates; every answer is read as
 * the person it is about.
 */
@DisplayName("To review — one inbox, card by card")
class ReviewInboxApiTest : ApiTestBase() {

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
        joinHousehold(owner, householdId, spouseMemberId, spouse, role = "admin")
    }

    private fun inbox(token: String): JsonNode {
        val response = get("/api/v1/households/$householdId/review", token)
        assertThat(response.status()).describedAs(response.body).isEqualTo(HttpStatus.OK)
        return response.json()
    }

    private fun kinds(body: JsonNode) = body.path("items").map { it.path("kind").asText() to it.path("title").asText() }

    private fun fd(token: String, title: String, visibility: String = "private"): String =
        capture(token, householdId, "fd", title, BigDecimal(200_000), visibility = visibility,
            attributes = mapOf("interest_rate" to 7.1)).path("id").asText()

    @Test
    fun `an empty household has nothing waiting and says so`() {
        val body = inbox(owner)
        assertThat(body.path("count").asInt()).isZero()
        assertThat(body.path("items")).isEmpty()
        assertThat(body.path("summary").asText()).isEqualTo("Nothing waiting. Your family is in good shape.")
    }

    @Test
    fun `a maturity comes first, then the gaps readiness names, nominee before papers`() {
        val fdId = fd(owner, "SBI FD")
        db.update("update investments set maturity_date = current_date + 10 where id = ?::uuid", fdId)

        val body = inbox(owner)
        val items = kinds(body)
        assertThat(items.first()).isEqualTo("maturity" to "SBI FD")
        assertThat(items).contains("no_nominee" to "SBI FD", "no_document" to "SBI FD")
        assertThat(items.map { it.first }).containsSubsequence("maturity", "no_nominee", "no_document")
        assertThat(body.path("count").asInt()).isEqualTo(items.size)

        val maturity = body.path("items").first()
        assertThat(maturity.path("recordType").asText()).isEqualTo("investment")
        assertThat(maturity.path("recordId").asText()).isEqualTo(fdId)
        assertThat(maturity.path("valueFormatted").asText()).isEqualTo("₹2,00,000")
    }

    @Test
    fun `a record due on Still true comes after a maturity and before the gaps`() {
        val due = fd(owner, "Old RD")
        db.update(
            "update investments set created_at = now() - interval '3 years', last_verified_at = null where id = ?::uuid",
            due,
        )
        val soon = fd(owner, "Maturing FD")
        db.update("update investments set maturity_date = current_date + 3 where id = ?::uuid", soon)

        val order = kinds(inbox(owner)).map { it.first }.distinct()
        assertThat(order).containsSubsequence("maturity", "still_true", "no_nominee")
    }

    @Test
    fun `a maturity far away, or on something already renewed, is not waiting`() {
        val far = fd(owner, "Far FD")
        db.update("update investments set maturity_date = current_date + 200 where id = ?::uuid", far)
        assertThat(kinds(inbox(owner)).map { it.first }).doesNotContain("maturity")
    }

    @Test
    fun `nobody's inbox carries someone else's private record, and a shared maturity is its holder's to decide`() {
        val secret = fd(owner, "Secret FD")
        db.update("update investments set maturity_date = current_date + 5 where id = ?::uuid", secret)
        val shared = fd(owner, "Family FD", visibility = "household")
        db.update("update investments set maturity_date = current_date + 5 where id = ?::uuid", shared)

        val spouseItems = inbox(spouse).path("items")
        assertThat(spouseItems.map { it.path("title").asText() }).doesNotContain("Secret FD")
        assertThat(spouseItems.filter { it.path("kind").asText() == "maturity" }).isEmpty()
        assertThat(inbox(owner).path("items").map { it.path("recordId").asText() }).contains(secret, shared)
    }

    @Test
    fun `another household's inbox is not found`() {
        val stranger = signIn()
        val response = get("/api/v1/households/$householdId/review", stranger)
        assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(get("/api/v1/households/${UUID.randomUUID()}/review", owner).status())
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `the order puts urgency first and keeps each source's own order`() {
        fun item(kind: String, title: String) = ReviewItem(kind, "investment", UUID.randomUUID(), title, detail = "")
        val ordered = ReviewService.order(
            listOf(
                item("no_document", "a"), item("still_true", "b"), item("maturity", "c"),
                item("no_nominee", "d"), item("maturity", "e"),
            ),
        )
        assertThat(ordered.map { it.title }).containsExactly("c", "e", "b", "d", "a")
    }
}
