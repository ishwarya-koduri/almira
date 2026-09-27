package tech.almira.lifecycle

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
        // His alone, but shared with the household: the family's emergency fund.
        val shared = capture(
            ravi, householdId, "gold_physical", "Emergency fund gold", BigDecimal(500_000), visibility = "household",
        )
        val account = post(
            "/api/v1/households/$householdId/accounts", ravi,
            mapOf("label" to "Ravi's savings", "accountKind" to "savings", "number" to "998877665544", "storeFullNumber" to true),
        ).json()
        upload(ravi, gold.path("id").asText())
        return mapOf(
            "gold" to gold.path("id").asText(), "joint" to joint.path("id").asText(),
            "hers" to hers.path("id").asText(), "account" to account.path("id").asText(),
            "shared" to shared.path("id").asText(),
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
            .doesNotContain("Joint gold", "Ishwarya's gold", "Emergency fund gold")
        // Owner's decision (2026-09-16): he shared it, so it stays — and he takes a copy.
        val kept = preview.path("staysWithHousehold").first { it.path("title").asText() == "Emergency fund gold" }
        assertThat(kept.path("detail").asText()).contains("Former member").contains("copy")
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
        assertThat(result.copiesTaken).describedAs("his part of the joint gold, and a copy of what he shared").isEqualTo(2)
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
    fun `his part of a joint holding valued from the day's prices keeps where that price came from`() {
        val ids = seed()
        val joint = UUID.fromString(ids["joint"])
        db.update(
            """
            insert into valuations (investment_id, as_of_date, value, quantity, source, price_source, unit_price, instrument)
            values (?, current_date + 1, 1200000, 100, 'price_feed', 'amfi', 12000, '122639')
            """.trimIndent(),
            joint,
        )
        stepUp(ravi)
        leave(ravi)
        val id = departureId(ravi)
        val decided = patch(
            "/api/v1/households/$householdId/departures/$id", ravi,
            mapOf("decisions" to listOf(mapOf("recordType" to "investment", "recordId" to ids["joint"], "decision" to "take_my_share"))),
        )
        assertThat(decided.status()).describedAs(decided.body).isEqualTo(HttpStatus.OK)

        val result = completion.complete(UUID.fromString(id), Instant.now().plus(Duration.ofDays(8)))!!
        assertThat(result.copiesTaken).describedAs("his part of the joint gold, and a copy of what he shared").isEqualTo(2)
        assertThat(members(ishwarya, householdId).map { it.path("id").asText() }).doesNotContain(raviMemberId)

        val copy = db.queryForMap(
            """
            select v.value, v.quantity, v.source, v.price_source, v.unit_price, v.instrument
              from valuations v join investments i on i.id = v.investment_id
             where i.household_id = ? and i.title = 'Joint gold (my part)' and v.source = 'price_feed'
            """.trimIndent(),
            result.destinationHouseholdId,
        )
        assertThat(copy["value"] as BigDecimal).isEqualByComparingTo("480000")
        assertThat(copy["quantity"] as BigDecimal).isEqualByComparingTo("40")
        assertThat(copy["price_source"]).isEqualTo("amfi")
        assertThat(copy["unit_price"] as BigDecimal).isEqualByComparingTo("12000")
        assertThat(copy["instrument"]).isEqualTo("122639")
    }

    /**
     * Owner's decision (2026-09-16): *leaving isn't erasure — don't apply the rule.
     * Someone who leaves keeps their account and takes a copy of what's theirs. The
     * household keeps the shared records, because they're shared: other members
     * contributed to them and depend on them. Same "Former member" label.*
     */
    @Test
    fun `what he shared with the household stays there under Former member, and he takes a copy`() {
        val ids = seed()
        stepUp(ravi)
        leave(ravi)
        val result = completion.complete(UUID.fromString(departureId(ravi)), Instant.now().plus(Duration.ofDays(8)))!!
        val destination = result.destinationHouseholdId!!

        // The household's record is where it was, held by a former member with no name.
        val held = db.queryForMap(
            """
            select i.household_id::text as household, i.visibility, m.display_name, m.former_since is not null as former,
                   m.user_id is null as unlinked, o.share_pct
              from investments i join investment_ownerships o on o.investment_id = i.id
              join members m on m.id = o.member_id
             where i.id = ?::uuid
            """.trimIndent(),
            UUID.fromString(ids.getValue("shared")),
        )
        assertThat(held).containsEntry("household", householdId).containsEntry("visibility", "household")
            .containsEntry("display_name", "Former member").containsEntry("former", true).containsEntry("unlinked", true)
        assertThat(held["share_pct"] as BigDecimal).isEqualByComparingTo("100")
        assertThat(get("/api/v1/households/$householdId/investments", ishwarya).json().map { it.path("title").asText() })
            .describedAs("Ishwarya still has the fund the family depends on").contains("Emergency fund gold")

        // And he has his copy, private, in his own household, at full size.
        val copy = db.queryForMap(
            "select title, visibility, invested_amount from investments where household_id = ?::uuid and title like 'Emergency fund%'",
            destination,
        )
        assertThat(copy).containsEntry("title", "Emergency fund gold").containsEntry("visibility", "private")
        assertThat(copy["invested_amount"] as BigDecimal).isEqualByComparingTo("500000")
        assertThat(result.copiesTaken).describedAs("the copy he takes of what stays").isGreaterThanOrEqualTo(1)

        // His own private gold went with him, as it always did.
        assertThat(
            db.queryForObject(
                "select household_id::text from investments where id = ?::uuid", String::class.java,
                UUID.fromString(ids.getValue("gold")),
            ),
        ).isEqualTo(destination.toString())
    }

    @Test
    fun `choosing to erase leaves what he shared with the household, and erases only what was private`() {
        val ids = seed()
        stepUp(ravi)
        leave(ravi, mapOf("privateRecords" to "export_and_erase"))
        completion.complete(UUID.fromString(departureId(ravi)), Instant.now().plus(Duration.ofDays(8)))
        assertThat(
            db.queryForObject("select count(*) from investments where id = ?::uuid", Int::class.java, UUID.fromString(ids.getValue("gold"))),
        ).describedAs("private to him").isZero()
        assertThat(
            db.queryForMap(
                """
                select m.display_name, m.former_since is not null as former from investments i
                  join investment_ownerships o on o.investment_id = i.id join members m on m.id = o.member_id
                 where i.id = ?::uuid
                """.trimIndent(),
                UUID.fromString(ids.getValue("shared")),
            ),
        ).describedAs("shared with them, so theirs").containsEntry("display_name", "Former member")
            .containsEntry("former", true)
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
