package tech.bhrigu.almira.household

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal

/**
 * "What Ravi sees" (catch-up plan X-56) must leak in neither direction: the
 * viewer's private records never appear as something the member sees, and the
 * member's private records never reach the viewer at all — not a title, not a
 * count, not an amount in a total.
 *
 * Ishwarya (owner) and Ravi (ADMIN, to prove role is not sight), Aarav with no
 * sign-in. Everything is read through the API, as each of them.
 */
@DisplayName("What a member sees — a preview that leaks in neither direction")
class MemberPreviewApiTest : ApiTestBase() {

    private lateinit var ish: String
    private lateinit var ravi: String
    private lateinit var householdId: String
    private lateinit var ishMemberId: String
    private lateinit var raviMemberId: String
    private lateinit var aaravMemberId: String

    @BeforeEach
    fun setUp() {
        ish = signIn()
        ravi = signIn()
        val household = createHousehold(ish, "Koduri", "private", "Ishwarya")
        householdId = household.path("id").asText()
        ishMemberId = household.path("myMemberId").asText()
        raviMemberId = addMember(ish, householdId, "Ravi").path("id").asText()
        joinHousehold(ish, householdId, raviMemberId, ravi, role = "admin")
        aaravMemberId = addMember(ish, householdId, "Aarav").path("id").asText()

        val rate = mapOf("interest_rate" to 7.1)
        capture(ish, householdId, "fd", "Ishwarya secret FD", BigDecimal(500_000), visibility = "private", attributes = rate)
        capture(ish, householdId, "gold_physical", "Family gold", BigDecimal(100_000), visibility = "household")
        capture(
            ish, householdId, "fd", "FD for Ravi", BigDecimal(200_000),
            visibility = "scoped", visibleTo = listOf(raviMemberId), attributes = rate,
        )
        capture(
            ish, householdId, "fd", "FD for Aarav", BigDecimal(300_000),
            visibility = "scoped", visibleTo = listOf(aaravMemberId), attributes = rate,
        )
        capture(ravi, householdId, "gold_physical", "Ravi private gold", BigDecimal(300_000), visibility = "private")
    }

    private fun preview(token: String, memberId: String): JsonNode {
        val response = get("/api/v1/households/$householdId/members/$memberId/preview", token)
        assertThat(response.status()).describedAs(response.body).isEqualTo(HttpStatus.OK)
        return response.json()
    }

    private fun titles(node: JsonNode) = node.map { it.path("title").asText() }

    @Test
    fun `Ishwarya's look at Ravi shows what he sees of hers, and her private things as absent`() {
        val body = preview(ish, raviMemberId)
        assertThat(body.path("canSignIn").asBoolean()).isTrue()
        assertThat(titles(body.path("sees"))).containsExactlyInAnyOrder("Family gold", "FD for Ravi")
        assertThat(titles(body.path("notInTheirView")))
            .containsExactlyInAnyOrder("Ishwarya secret FD", "FD for Aarav")
        // The figure is only what both see: 1,00,000 + 2,00,000.
        assertThat(body.path("sharedAssetsFormatted").asText()).isEqualTo("₹3,00,000")
        // His own private gold is his: not a title, not in the figure, anywhere.
        assertThat(get("/api/v1/households/$householdId/members/$raviMemberId/preview", ish).body)
            .doesNotContain("Ravi private gold")
    }

    @Test
    fun `Ravi's look at Ishwarya never carries her private FD, and shows his own private gold as absent`() {
        val raw = get("/api/v1/households/$householdId/members/$ishMemberId/preview", ravi).body!!
        assertThat(raw).doesNotContain("Ishwarya secret FD").doesNotContain("FD for Aarav")

        val body = preview(ravi, ishMemberId)
        assertThat(titles(body.path("sees"))).containsExactlyInAnyOrder("Family gold", "FD for Ravi")
        assertThat(titles(body.path("notInTheirView"))).containsExactly("Ravi private gold")
        assertThat(body.path("notInTheirView").first().has("valueFormatted")).isFalse()
    }

    @Test
    fun `someone with no sign-in sees nothing, and the preview says why`() {
        val body = preview(ish, aaravMemberId)
        assertThat(body.path("canSignIn").asBoolean()).isFalse()
        assertThat(body.path("sees")).isEmpty()
        assertThat(body.path("explanation").asText()).contains("no sign-in")
    }

    @Test
    fun `a preview of yourself is refused, and one in another household is not found`() {
        val self = get("/api/v1/households/$householdId/members/$ishMemberId/preview", ish)
        assertThat(self.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(self.errorCode()).isEqualTo("preview_is_you")

        val stranger = signIn()
        val otherHousehold = createHousehold(stranger, "Strangers", "household", "Outsider")
        val outsiderMemberId = otherHousehold.path("myMemberId").asText()
        assertThat(get("/api/v1/households/$householdId/members/$outsiderMemberId/preview", ish).status())
            .isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(get("/api/v1/households/$householdId/members/$raviMemberId/preview", stranger).status())
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `a preview is audited with the member it was about and nothing it contained`() {
        preview(ish, raviMemberId)
        val rows = db.queryForList(
            "select entity_id::text as entity, diff::text as diff from activity_log where household_id = ?::uuid and action = 'member.preview'",
            householdId,
        )
        assertThat(rows).hasSize(1)
        assertThat(rows.first()["entity"]).isEqualTo(raviMemberId)
        assertThat(rows.first()["diff"]).isNull()
    }
}
