package tech.bhrigu.almira.lifecycle

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestTemplate
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

@DisplayName("Leaving a household: seven days, your records with you, joint ones decided by you")
class DepartureApiTest : LifecycleTestSupport() {

    @Autowired private lateinit var completion: DepartureCompletion

    private lateinit var ishwarya: String
    private lateinit var ravi: String
    private lateinit var householdId: String
    private lateinit var ishwaryaMemberId: String
    private lateinit var raviMemberId: String

    private val http = RestTemplate(org.springframework.http.client.JdkClientHttpRequestFactory()).apply {
        errorHandler = object : org.springframework.web.client.ResponseErrorHandler {
            override fun hasError(response: org.springframework.http.client.ClientHttpResponse) = false
            override fun handleError(response: org.springframework.http.client.ClientHttpResponse) = Unit
        }
    }

    @BeforeEach
    fun setUp() {
        ishwarya = signIn()
        ravi = signIn()
        val household = createHousehold(ishwarya, "Koduri", "private", "Ishwarya")
        householdId = household.path("id").asText()
        ishwaryaMemberId = household.path("myMemberId").asText()
        raviMemberId = addMember(ishwarya, householdId, "Ravi").path("id").asText()
        joinHousehold(ishwarya, householdId, raviMemberId, ravi, role = "admin")
    }

    private val statement = "Ravi's locker statement".toByteArray()

    private fun seed(): Map<String, String> {
        val gold = capture(ravi, householdId, "gold_physical", "Ravi's gold", BigDecimal(200_000))
        val joint = capture(
            ishwarya, householdId, "gold_physical", "Joint gold", BigDecimal(1_000_000), visibility = "household",
            owners = listOf(
                mapOf("memberId" to ishwaryaMemberId, "sharePct" to 60),
                mapOf("memberId" to raviMemberId, "sharePct" to 40),
            ),
        )
        val hers = capture(ishwarya, householdId, "gold_physical", "Ishwarya's gold", BigDecimal(300_000))
        val account = post(
            "/api/v1/households/$householdId/accounts", ravi,
            mapOf("label" to "Ravi's savings", "accountKind" to "savings", "number" to "998877665544", "storeFullNumber" to true),
        ).json()
        upload(ravi, gold.path("id").asText())
        return mapOf(
            "gold" to gold.path("id").asText(), "joint" to joint.path("id").asText(),
            "hers" to hers.path("id").asText(), "account" to account.path("id").asText(),
        )
    }

    private fun upload(token: String, investmentId: String) {
        val body = LinkedMultiValueMap<String, Any>().apply {
            add("file", object : ByteArrayResource(statement) { override fun getFilename() = "locker.txt" })
        }
        val headers = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
            setBearerAuth(token)
        }
        val response = http.exchange(
            url("/api/v1/households/$householdId/documents?docType=statement&entityType=investment&entityId=$investmentId"),
            HttpMethod.POST, HttpEntity(body, headers), String::class.java,
        )
        check(response.statusCode.is2xxSuccessful) { "upload failed: ${response.body}" }
    }

    private fun leave(token: String, body: Map<String, Any?> = emptyMap()) =
        post("/api/v1/households/$householdId/departures", token, body)

    private fun departureId(userToken: String) =
        get("/api/v1/households/$householdId/departures", userToken).json().first { it.path("status").asText() == "pending" }
            .path("id").asText()

    @Test
    fun `the preview separates what goes with you from what you share`() {
        seed()
        val preview = get("/api/v1/households/$householdId/departures/preview", ravi).json()
        assertThat(preview.path("goesWithYou").map { it.path("title").asText() })
            .contains("Ravi's gold", "Ravi's savings", "locker.txt")
            .doesNotContain("Joint gold", "Ishwarya's gold")
        val joint = preview.path("joint").single()
        assertThat(joint.path("title").asText()).isEqualTo("Joint gold")
        assertThat(joint.path("otherHolders").map { it.asText() }).containsExactly("Ishwarya")
        assertThat(preview.path("waitDays").asInt()).isEqualTo(7)
    }

    @Test
    fun `leaving waits seven days, tells the household neutrally, and can be undone`() {
        assertThat(leave(ravi).errorCode()).isEqualTo("step_up_required")
        stepUp(ravi)
        val started = leave(ravi)
        assertThat(started.status()).describedAs(started.body).isEqualTo(HttpStatus.CREATED)
        assertThat(started.json().path("privateRecords").asText()).isEqualTo("take")

        val seenByHer = get("/api/v1/households/$householdId/departures", ishwarya).json().single()
        assertThat(seenByHer.path("memberName").asText()).isEqualTo("Ravi")
        assertThat(seenByHer.path("privateRecords").let { it.isNull || it.isMissingNode }).describedAs("his choice is not hers to see").isTrue()
        assertThat(seenByHer.path("canCancel").asBoolean()).isFalse()
        assertThat(templatesFor(userId(ishwarya))).contains("lifecycle.departure.started")

        val cancelled = post("/api/v1/households/$householdId/departures/${started.json().path("id").asText()}/cancel", ravi)
        assertThat(cancelled.json().path("status").asText()).isEqualTo("cancelled")
    }

    @Test
    fun `an admin can ask someone to leave, and only an admin can withdraw that`() {
        stepUp(ishwarya)
        val asked = leave(ishwarya, mapOf("memberId" to raviMemberId))
        assertThat(asked.status()).describedAs(asked.body).isEqualTo(HttpStatus.CREATED)
        assertThat(templatesFor(userId(ravi))).contains("lifecycle.departure.asked")
        val id = asked.json().path("id").asText()

        assertThat(post("/api/v1/households/$householdId/departures/$id/cancel", ravi).status())
            .isEqualTo(HttpStatus.FORBIDDEN)
        // He still chooses what happens to what is his.
        val chose = patch("/api/v1/households/$householdId/departures/$id", ravi, mapOf("privateRecords" to "export_and_erase"))
        assertThat(chose.json().path("privateRecords").asText()).isEqualTo("export_and_erase")
        assertThat(patch("/api/v1/households/$householdId/departures/$id", ishwarya, mapOf("privateRecords" to "take")).status())
            .isEqualTo(HttpStatus.FORBIDDEN)

        assertThat(post("/api/v1/households/$householdId/departures/$id/cancel", ishwarya).json().path("status").asText())
            .isEqualTo("cancelled")
    }

    @Test
    fun `after seven days what is his moves to his own household, still readable, and his part of the joint gold passes on`() {
        val ids = seed()
        stepUp(ravi)
        leave(ravi)
        val id = departureId(ravi)
        val decided = patch(
            "/api/v1/households/$householdId/departures/$id", ravi,
            mapOf("decisions" to listOf(mapOf("recordType" to "investment", "recordId" to ids["joint"], "decision" to "take_my_share"))),
        )
        assertThat(decided.status()).describedAs(decided.body).isEqualTo(HttpStatus.OK)
        assertThat(
            patch(
                "/api/v1/households/$householdId/departures/$id", ravi,
                mapOf("decisions" to listOf(mapOf("recordType" to "investment", "recordId" to ids["hers"], "decision" to "stays"))),
            ).status(),
        ).describedAs("no decision about a record that is not his").isEqualTo(HttpStatus.NOT_FOUND)

        assertThat(completion.complete(UUID.fromString(id), Instant.now().plus(Duration.ofDays(6)))).isNull()
        val result = completion.complete(UUID.fromString(id), Instant.now().plus(Duration.ofDays(8)))!!
        assertThat(result.copiesTaken).isEqualTo(1)
        val newHousehold = result.destinationHouseholdId.toString()

        // His view: a household of his own, with his things, set to private.
        val mine = get("/api/v1/households", ravi).json().map { it.path("id").asText() }
        assertThat(mine).contains(newHousehold).doesNotContain(householdId)
        val titles = get("/api/v1/households/$newHousehold/investments", ravi).json().associate {
            it.path("title").asText() to it
        }
        assertThat(titles.keys).contains("Ravi's gold", "Joint gold (my part)")
        assertThat(titles.getValue("Ravi's gold").path("visibility").asText()).isEqualTo("private")

        // Re-encrypted for the new household: the full number still opens, and so does the file.
        // (The step-up from leaving is still good: five minutes, this session.)
        val number = post("/api/v1/households/$newHousehold/accounts/${ids["account"]}/reveal-number", ravi)
        assertThat(number.status()).describedAs(number.body).isEqualTo(HttpStatus.OK)
        assertThat(number.json().path("number").asText()).isEqualTo("998877665544")
        val document = get("/api/v1/households/$newHousehold/documents", ravi).json().single()
        val ticket = post("/api/v1/households/$newHousehold/documents/${document.path("id").asText()}/access", ravi).json()
        val file = http.exchange(
            url("/api/v1/documents/download?token=${ticket.path("token").asText()}"),
            HttpMethod.GET, HttpEntity<Void>(HttpHeaders()), ByteArray::class.java,
        )
        assertThat(file.body).isEqualTo(statement)

        // Her view: he is gone, the joint gold is all hers, and nothing of his is visible.
        assertThat(members(ishwarya, householdId).map { it.path("id").asText() }).doesNotContain(raviMemberId)
        val shares = db.queryForList(
            "select share_pct from investment_ownerships where investment_id = ?::uuid",
            BigDecimal::class.java, UUID.fromString(ids["joint"]),
        )
        assertThat(shares.single()).isEqualByComparingTo("100")
        assertThat(get("/api/v1/households/$householdId/investments", ishwarya).json().map { it.path("title").asText() })
            .contains("Joint gold", "Ishwarya's gold").doesNotContain("Ravi's gold")
        assertThat(get("/api/v1/households/$householdId", ravi).status()).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(templatesFor(userId(ishwarya))).contains("lifecycle.departure.completed")
    }

    @Test
    fun `choosing to erase leaves no household behind and nothing of his`() {
        val ids = seed()
        stepUp(ravi)
        leave(ravi, mapOf("privateRecords" to "export_and_erase"))
        val result = completion.complete(UUID.fromString(departureId(ravi)), Instant.now().plus(Duration.ofDays(8)))!!
        assertThat(result.destinationHouseholdId).isNull()
        assertThat(result.erased).isGreaterThanOrEqualTo(3)
        assertThat(db.queryForObject("select count(*) from investments where id = ?::uuid", Int::class.java, UUID.fromString(ids["gold"])))
            .isZero()
        assertThat(get("/api/v1/households", ravi).json()).isEmpty()
    }

    @Test
    fun `an owner who is the only one who runs the household is asked to hand it on first`() {
        val meera = signIn()
        val other = createHousehold(meera, "Meera's", "private", "Meera").path("id").asText()
        val kiranToken = signIn()
        val kiran = addMember(meera, other, "Kiran").path("id").asText()
        joinHousehold(meera, other, kiran, kiranToken, role = "editor")
        stepUp(meera)
        val refused = post("/api/v1/households/$other/departures", meera)
        assertThat(refused.status()).isEqualTo(HttpStatus.CONFLICT)
        assertThat(refused.errorCode()).isEqualTo("departure_blocked")
    }
}
