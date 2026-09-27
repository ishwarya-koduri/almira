package tech.almira.privacy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestTemplate
import tech.almira.support.ApiTestBase
import java.math.BigDecimal

/**
 * A write the database refuses is reported as refused.
 *
 * Row-level security does not raise on a refused UPDATE or DELETE: it filters
 * the row out, and the statement simply reports that nothing changed. Where the
 * service looked at the row count that was fine; where it did not, a viewer got
 * 204 for a delete that deleted nothing, and 200 with the record echoed back for
 * a visibility change that changed nothing. The record was safe and the answer
 * was a lie, which is worse than a refusal: the client shows the row gone, the
 * next reload brings it back, and nobody knows which screen to believe.
 *
 * docs/05 §3.6 says the model is enforced at three layers and that the service
 * re-checks the same predicate. These tests are that second layer, and they
 * assert the state that did not change, not only the status code — a 403 over a
 * record that was deleted anyway would pass a status-only test.
 *
 * Ravi is a `viewer` here on purpose: he can read every record in this
 * household, so a 404 would be wrong. The right answer is "you can read this,
 * but it isn't yours to change".
 */
@DisplayName("A write the database refuses is reported as refused")
class RefusedWritesDoNotReportSuccessApiTest : ApiTestBase() {

    private lateinit var ishwarya: String
    private lateinit var ravi: String
    private lateinit var householdId: String
    private lateinit var raviMemberId: String

    @BeforeEach
    fun setUp() {
        ishwarya = signIn()
        ravi = signIn()

        val household = createHousehold(ishwarya, "Koduri", "household", "Ishwarya")
        householdId = household.path("id").asText()
        raviMemberId = addMember(ishwarya, householdId, "Ravi").path("id").asText()
        joinHousehold(ishwarya, householdId, raviMemberId, ravi, role = "viewer")
    }

    private val REFUSED = "You can read this, but it isn't yours to change."

    private fun assertRefused(response: org.springframework.http.ResponseEntity<String>) {
        assertThat(response.status()).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(response.json().path("error").path("message").asText()).isEqualTo(REFUSED)
    }

    // --- investments ---------------------------------------------------------

    private fun holding(title: String) = capture(
        ishwarya, householdId, "universal", title, BigDecimal(100_000), "household",
    ).path("id").asText()

    @Test
    fun `a viewer cannot delete a holding, and is told so rather than shown a 204`() {
        val id = holding("Family gold")
        // The viewer really can read it — so this is a refusal, not a 404.
        assertThat(get("/api/v1/households/$householdId/investments/$id", ravi).status())
            .isEqualTo(HttpStatus.OK)

        assertRefused(delete("/api/v1/households/$householdId/investments/$id", ravi))

        assertThat(get("/api/v1/households/$householdId/investments/$id", ishwarya).status())
            .describedAs("the record the refusal was about is still there")
            .isEqualTo(HttpStatus.OK)
        assertThat(get("/api/v1/households/$householdId/trash", ishwarya).json())
            .describedAs("and it did not quietly land in the trash either")
            .isEmpty()
    }

    @Test
    fun `a viewer cannot change a holding's visibility, and is not handed the record back`() {
        val id = holding("SBI FD")

        assertRefused(
            patch(
                "/api/v1/households/$householdId/investments/$id/visibility", ravi,
                mapOf("visibility" to "private"),
            ),
        )

        assertThat(
            get("/api/v1/households/$householdId/investments/$id", ishwarya)
                .json().path("visibility").asText(),
        ).describedAs("still household-shared").isEqualTo("household")
    }

    @Test
    fun `a viewer cannot clear a holding's nominees`() {
        val id = holding("LIC policy")
        val set = call(
            HttpMethod.PUT, "/api/v1/households/$householdId/investments/$id/nominees", ishwarya,
            mapOf("nominees" to listOf(mapOf("name" to "Padmini", "sharePct" to 100))),
        )
        assertThat(set.status()).isEqualTo(HttpStatus.OK)

        assertRefused(
            call(
                HttpMethod.PUT, "/api/v1/households/$householdId/investments/$id/nominees", ravi,
                mapOf("nominees" to emptyList<Any>()),
            ),
        )

        assertThat(
            get("/api/v1/households/$householdId/investments/$id", ishwarya)
                .json().path("nominees"),
        ).describedAs("a nominee list is a legal instruction; it is still there").hasSize(1)
    }

    // --- liabilities ---------------------------------------------------------

    private fun loan(title: String) = post(
        "/api/v1/households/$householdId/liabilities", ishwarya,
        mapOf(
            "title" to title, "kind" to "personal", "outstanding" to 250_000,
            "visibility" to "household",
        ),
    ).json().path("id").asText()

    @Test
    fun `a viewer cannot delete a loan or change its visibility`() {
        val id = loan("Personal loan")

        assertRefused(delete("/api/v1/households/$householdId/liabilities/$id", ravi))
        assertRefused(
            patch(
                "/api/v1/households/$householdId/liabilities/$id/visibility", ravi,
                mapOf("visibility" to "private"),
            ),
        )

        val still = get("/api/v1/households/$householdId/liabilities/$id", ishwarya)
        assertThat(still.status()).isEqualTo(HttpStatus.OK)
        assertThat(still.json().path("visibility").asText()).isEqualTo("household")
    }

    // --- accounts ------------------------------------------------------------

    private fun account(label: String) = post(
        "/api/v1/households/$householdId/accounts", ishwarya,
        mapOf("label" to label, "accountKind" to "savings", "visibility" to "household"),
    ).json().path("id").asText()

    @Test
    fun `a viewer cannot delete an account or change its visibility`() {
        val id = account("SBI savings")

        assertRefused(delete("/api/v1/households/$householdId/accounts/$id", ravi))
        assertRefused(
            patch(
                "/api/v1/households/$householdId/accounts/$id/visibility", ravi,
                mapOf("visibility" to "private"),
            ),
        )

        val still = get("/api/v1/households/$householdId/accounts/$id", ishwarya)
        assertThat(still.status()).isEqualTo(HttpStatus.OK)
        assertThat(still.json().path("visibility").asText()).isEqualTo("household")
    }

    // --- goals ---------------------------------------------------------------

    private fun goal(name: String) = post(
        "/api/v1/households/$householdId/goals", ishwarya,
        mapOf("name" to name, "targetAmount" to 1_000_000, "visibility" to "household"),
    ).json().path("id").asText()

    @Test
    fun `a viewer cannot delete a goal or change its visibility`() {
        val id = goal("House deposit")

        assertRefused(delete("/api/v1/households/$householdId/goals/$id", ravi))
        assertRefused(
            patch(
                "/api/v1/households/$householdId/goals/$id/visibility", ravi,
                mapOf("visibility" to "private"),
            ),
        )

        val still = get("/api/v1/households/$householdId/goals/$id", ishwarya)
        assertThat(still.status()).isEqualTo(HttpStatus.OK)
        assertThat(still.json().path("visibility").asText()).isEqualTo("household")
    }

    // --- documents -----------------------------------------------------------

    private val upload = RestTemplate().apply {
        errorHandler = object : org.springframework.web.client.ResponseErrorHandler {
            override fun hasError(r: org.springframework.http.client.ClientHttpResponse) = false
            override fun handleError(r: org.springframework.http.client.ClientHttpResponse) = Unit
        }
    }

    @Test
    fun `a member who did not upload a document cannot delete it`() {
        val body = LinkedMultiValueMap<String, Any>().apply {
            val resource = object : ByteArrayResource("POLICY 5567".toByteArray()) {
                override fun getFilename() = "policy.pdf"
            }
            add("file", resource)
        }
        val uploaded = upload.exchange(
            url("/api/v1/households/$householdId/documents?docType=policy"),
            HttpMethod.POST,
            HttpEntity(
                body,
                HttpHeaders().apply {
                    contentType = MediaType.MULTIPART_FORM_DATA
                    setBearerAuth(ishwarya)
                },
            ),
            String::class.java,
        )
        assertThat(uploaded.statusCode.is2xxSuccessful)
            .describedAs("upload failed: ${uploaded.body}").isTrue()
        val id = uploaded.json().path("id").asText()

        assertRefused(delete("/api/v1/households/$householdId/documents/$id", ravi))

        assertThat(get("/api/v1/households/$householdId/documents/$id", ishwarya).status())
            .describedAs("the proof is still in the vault")
            .isEqualTo(HttpStatus.OK)
    }
}
