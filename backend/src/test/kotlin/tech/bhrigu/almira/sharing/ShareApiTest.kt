package tech.bhrigu.almira.sharing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal

@DisplayName("Guest links: one slice, for a while, and nothing else")
class ShareApiTest : ApiTestBase() {

    private lateinit var owner: String
    private lateinit var spouse: String
    private lateinit var householdId: String

    @BeforeEach
    fun setUp() {
        owner = signIn()
        spouse = signIn()
        householdId = createHousehold(owner, "Koduri", "private", "Ishwarya").path("id").asText()
        val spouseMemberId = addMember(owner, householdId, "Ravi").path("id").asText()
        joinHousehold(owner, householdId, spouseMemberId, spouse)
    }

    private fun share(token: String, body: Map<String, Any?>) =
        post("/api/v1/households/$householdId/shares", token, body)

    private fun openLink(url: String) = get("/api/v1/share/${url.substringAfterLast('/')}")

    @Test
    fun `a link shares one slice, and says who opened it`() {
        val ppf = capture(
            owner, householdId, "ppf", "PPF, SBI", BigDecimal("150000"),
            visibility = "household",
            attributes = mapOf("account_no" to "123456789", "yearly_contribution" to 150000),
        )
        assertThat(ppf.path("id").asText()).isNotBlank()

        val created = share(
            owner,
            mapOf(
                "label" to "Tax pack for Ramesh", "scope" to "tax_pack",
                "financialYear" to "2026-27", "expiresInDays" to 7,
                "recipientHint" to "Ramesh (CA)",
            ),
        )
        assertThat(created.status()).isEqualTo(HttpStatus.CREATED)
        val url = created.json().path("url").asText()
        assertThat(url).contains("/share/")

        val opened = openLink(url)
        assertThat(opened.status()).isEqualTo(HttpStatus.OK)
        assertThat(opened.json().path("label").asText()).isEqualTo("Tax pack for Ramesh")
        assertThat(opened.json().path("taxPack").path("financialYear").asText()).isEqualTo("2026-27")
        assertThat(opened.json().path("notice").asText()).contains("read-only")

        val views = get(
            "/api/v1/households/$householdId/shares/${created.json().path("id").asText()}/views", owner,
        ).json()
        assertThat(views).describedAs("sharing without this is sharing into the dark").hasSize(1)
    }

    /**
     * The scope is materialised when the link is made, under the sharer's own
     * visibility — so a link cannot contain what the sharer could not see, and
     * cannot quietly widen later as the household adds records.
     */
    @Test
    fun `a link shows only its own slice, never the rest of the household`() {
        val shared = capture(
            owner, householdId, "gold_physical", "Shared gold", BigDecimal("100000"),
            visibility = "household",
        )
        capture(
            owner, householdId, "gold_physical", "Her private gold", BigDecimal("900000"),
            visibility = "private",
        )
        capture(
            owner, householdId, "fd", "Untouched FD", BigDecimal("500000"),
            visibility = "household", attributes = mapOf("interest_rate" to 7.1),
        )

        val url = share(
            owner,
            mapOf(
                "label" to "One holding", "scope" to "records",
                "investmentIds" to listOf(shared.path("id").asText()),
            ),
        ).json().path("url").asText()

        val payload = openLink(url).json()
        assertThat(payload.path("records").map { it.path("title").asText() })
            .describedAs("the slice, and only the slice")
            .containsExactly("Shared gold")
    }

    @Test
    fun `a link cannot be made to contain what the sharer cannot see`() {
        val theirs = capture(
            spouse, householdId, "gold_physical", "His private gold", BigDecimal("100000"),
            visibility = "private",
        )

        val refused = share(
            owner,
            mapOf(
                "label" to "Sneaky", "scope" to "records",
                "investmentIds" to listOf(theirs.path("id").asText()),
            ),
        )
        assertThat(refused.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(refused.errorCode()).isEqualTo("scope_empty")
    }

    @Test
    fun `a withdrawn link stops working immediately`() {
        capture(
            owner, householdId, "gold_physical", "Gold", BigDecimal("100000"), visibility = "household",
        )
        val created = share(owner, mapOf("label" to "Handbook", "scope" to "handbook")).json()
        val url = created.path("url").asText()
        assertThat(openLink(url).status()).isEqualTo(HttpStatus.OK)

        delete("/api/v1/households/$householdId/shares/${created.path("id").asText()}", owner)
        assertThat(openLink(url).status()).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `an expired link and a withdrawn one are indistinguishable from outside`() {
        capture(owner, householdId, "gold_physical", "Gold", BigDecimal("1"), visibility = "household")
        val created = share(owner, mapOf("label" to "Handbook", "scope" to "handbook")).json()
        delete("/api/v1/households/$householdId/shares/${created.path("id").asText()}", owner)

        val withdrawn = openLink(created.path("url").asText())
        val neverExisted = get("/api/v1/share/definitely-not-a-real-token")

        assertThat(withdrawn.status()).isEqualTo(neverExisted.status())
        assertThat(withdrawn.json().path("error").path("message").asText())
            .describedAs("telling them apart would confirm that a link once existed")
            .isEqualTo(neverExisted.json().path("error").path("message").asText())
    }

    @Test
    fun `a link stops after the number of views it was given`() {
        capture(owner, householdId, "gold_physical", "Gold", BigDecimal("1"), visibility = "household")
        val url = share(
            owner, mapOf("label" to "Once", "scope" to "handbook", "maxViews" to 1),
        ).json().path("url").asText()

        assertThat(openLink(url).status()).isEqualTo(HttpStatus.OK)
        assertThat(openLink(url).status()).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `documents are left out unless they were deliberately included`() {
        val holding = capture(
            owner, householdId, "gold_physical", "Gold", BigDecimal("100000"), visibility = "household",
        )
        assertThat(holding.path("id").asText()).isNotBlank()

        val plain = share(
            owner,
            mapOf(
                "label" to "No papers", "scope" to "records",
                "investmentIds" to listOf(holding.path("id").asText()),
            ),
        ).json()
        assertThat(plain.path("itemCount").asInt())
            .describedAs("one record, no documents")
            .isEqualTo(1)
    }

    @Test
    fun `someone else's link is not mine to see or to withdraw`() {
        capture(spouse, householdId, "gold_physical", "His gold", BigDecimal("1"), visibility = "household")
        val theirs = share(spouse, mapOf("label" to "Theirs", "scope" to "handbook")).json()

        assertThat(get("/api/v1/households/$householdId/shares", owner).json())
            .describedAs("which private records someone shared is itself private")
            .isEmpty()
        assertThat(
            delete("/api/v1/households/$householdId/shares/${theirs.path("id").asText()}", owner).status(),
        ).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `a link has to name a slice that exists`() {
        val refused = share(owner, mapOf("label" to "Empty", "scope" to "records", "investmentIds" to emptyList<String>()))
        assertThat(refused.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(refused.errorCode()).isEqualTo("scope_empty")
    }

    @Test
    fun `a link cannot outlive ninety days`() {
        val refused = share(owner, mapOf("label" to "Forever", "scope" to "handbook", "expiresInDays" to 365))
        assertThat(refused.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(refused.errorCode()).isEqualTo("expiry_invalid")
    }
}
