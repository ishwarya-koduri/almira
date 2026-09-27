package tech.almira.continuity

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import tech.almira.support.ApiTestBase
import java.math.BigDecimal

@DisplayName("Heir mode: one task at a time, shared with a few, and only while the window is open")
class HeirModeApiTest : ApiTestBase() {

    private lateinit var owner: String
    private lateinit var heir: String
    private lateinit var householdId: String
    private lateinit var ownerMemberId: String
    private lateinit var heirMemberId: String
    private lateinit var requestId: String

    @BeforeEach
    fun setUp() {
        owner = signIn()
        heir = signIn()
        val household = createHousehold(owner, "Koduri", "private", "Ishwarya")
        householdId = household.path("id").asText()
        ownerMemberId = household.path("myMemberId").asText()
        heirMemberId = addMember(owner, householdId, "Ravi").path("id").asText()
        // A viewer on purpose: somebody's trusted contact is often exactly that.
        joinHousehold(owner, householdId, heirMemberId, heir, role = "viewer")

        capture(
            owner, householdId, "insurance_term", "LIC term cover", BigDecimal("2000000"),
            attributes = mapOf("policy_no" to "5567123456", "sum_assured" to 2000000, "premium_amount" to 18400),
        )
        capture(owner, householdId, "gold_physical", "Her gold", BigDecimal("500000"), attributes = mapOf())
        post(
            "/api/v1/households/$householdId/liabilities", owner,
            mapOf(
                "kind" to "home", "title" to "HDFC home loan", "outstanding" to 2400000, "visibility" to "private",
                "holders" to listOf(mapOf("memberId" to ownerMemberId, "responsibilityPct" to 100)),
            ),
        )
        post(
            "/api/v1/households/$householdId/emergency/contacts", owner,
            mapOf("trustedMemberId" to heirMemberId, "waitDays" to 7),
        )
        requestId = post(
            "/api/v1/households/$householdId/emergency/requests", heir,
            mapOf("subjectMemberId" to ownerMemberId, "reason" to "Hospital"),
        ).json().path("id").asText()
    }

    private val base get() = "/api/v1/households/$householdId/emergency/requests/$requestId/heir"

    private fun openTheWindow() {
        db.update(
            """
            update emergency_requests
            set requested_at = now() - interval '10 days', unlock_at = now() - interval '3 days'
            where id = ?::uuid
            """.trimIndent(),
            requestId,
        )
        db.update(
            """
            update user_sessions set last_used_at = now() - interval '30 days'
            where user_id in (select m.user_id from members m where m.household_id = ?::uuid and m.user_id is not null)
            """.trimIndent(),
            householdId,
        )
    }

    private fun start(situation: String = "passed_away") = post(base, heir, mapOf("situation" to situation))

    private fun titles(plan: JsonNode) = plan.path("tasks").map { it.path("title").asText() }

    @Test
    fun `nothing starts while the request is still waiting`() {
        assertThat(start().status())
            .describedAs("a plan exists only inside an open window, and 404 says nothing about why")
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `an open window becomes a list of tasks about the person, in order, with no amounts anywhere`() {
        openTheWindow()
        val response = start()
        assertThat(response.status()).isEqualTo(HttpStatus.OK)
        val plan = response.json()

        assertThat(titles(plan)).containsExactly(
            "Get copies of the death certificate",
            "Claim Her gold",
            "Claim LIC term cover",
            "Tell the lender about HDFC home loan",
            "If someone asks who the heirs are",
        )
        assertThat(plan.path("subjectName").asText()).isEqualTo("Ishwarya")
        assertThat(plan.path("doneCount").asInt()).isZero()
        assertThat(plan.path("nextTaskId").asText()).isEqualTo(plan.path("tasks").first().path("id").asText())

        val body = response.body!!
        assertThat(body).describedAs("heir mode never shows a value or a total")
            .doesNotContain("2000000").doesNotContain("20,00,000").doesNotContain("500000")
            .doesNotContain("2400000").doesNotContain("totalIncluded").doesNotContain("₹")
        assertThat(plan.path("tasks")[2].path("steps").size())
            .describedAs("a claim task carries the playbook's steps")
            .isGreaterThan(0)
    }

    @Test
    fun `a person who is not managing gets a different, gentler list`() {
        openTheWindow()
        val plan = start("cannot_manage").json()
        assertThat(titles(plan)).containsExactly(
            "Check whether you can act for them",
            "Keep HDFC home loan paid",
            "Check LIC term cover is paid up",
            "Keep the regular payments going",
        )
    }

    @Test
    fun `stopping here, coming back, and the next task moves on`() {
        openTheWindow()
        val plan = start().json()
        val first = plan.path("tasks")[0].path("id").asText()
        val second = plan.path("tasks")[1].path("id").asText()

        assertThat(post("$base/pause", heir).json().path("paused").asBoolean()).isTrue()
        assertThat(get(base, heir).json().path("paused").asBoolean()).isTrue()

        val resumed = post("$base/resume", heir).json()
        assertThat(resumed.path("paused").asBoolean()).isFalse()
        assertThat(resumed.path("nextTaskId").asText()).describedAs("the same place").isEqualTo(first)

        val done = patch("$base/tasks/$first", heir, mapOf("status" to "done")).json()
        assertThat(done.path("doneCount").asInt()).isEqualTo(1)
        assertThat(done.path("nextTaskId").asText()).isEqualTo(second)

        val later = patch("$base/tasks/$second", heir, mapOf("status" to "later")).json()
        assertThat(later.path("nextTaskId").asText())
            .describedAs("later goes to the back of the list, not away")
            .isEqualTo(later.path("tasks")[2].path("id").asText())

        assertThat(start().json().path("doneCount").asInt())
            .describedAs("opening heir mode again keeps what was done")
            .isEqualTo(1)
        assertThat(patch("$base/tasks/$first", heir, mapOf("status" to "finished")).status())
            .isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `a task handed to a relative opens for them, and nothing else does`() {
        openTheWindow()
        val plan = start().json()
        val goldTask = plan.path("tasks").first { it.path("title").asText() == "Claim Her gold" }.path("id").asText()

        val helper = post("$base/helpers", heir, mapOf("name" to "Meera", "relationship" to "sister"))
        assertThat(helper.status()).isEqualTo(HttpStatus.CREATED)
        val helperId = helper.json().path("id").asText()
        val link = helper.json().path("url").asText()
        assertThat(link).contains("/help/")
        val token = link.substringAfterLast("/")

        call(HttpMethod.PUT, "$base/tasks/$goldTask/helper", heir, mapOf("helperId" to helperId))

        val view = get("/api/v1/share/$token/tasks")
        assertThat(view.status()).isEqualTo(HttpStatus.OK)
        assertThat(view.json().path("helperName").asText()).isEqualTo("Meera")
        assertThat(view.json().path("sharedBy").asText()).describedAs("the name the family knows").isEqualTo("Ravi")
        assertThat(view.json().path("tasks").map { it.path("title").asText() }).containsExactly("Claim Her gold")
        assertThat(view.body!!).doesNotContain("500000").doesNotContain("LIC term cover").doesNotContain("HDFC")

        assertThat(get("/api/v1/share/$token").status())
            .describedAs("a helper's token is not a way into the ordinary guest page")
            .isEqualTo(HttpStatus.NOT_FOUND)

        assertThat(get("/api/v1/households/$householdId/emergency/requests/$requestId/heir", owner).status())
            .describedAs("the plan is the heir's, not the household's")
            .isEqualTo(HttpStatus.NOT_FOUND)

        // Taken back: their link shows nothing of it.
        val removed = delete("$base/helpers/$helperId", heir)
        assertThat(removed.status()).isEqualTo(HttpStatus.OK)
        assertThat(get("/api/v1/share/$token/tasks").status()).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `at most five people at a time`() {
        openTheWindow()
        start()
        repeat(5) { assertThat(post("$base/helpers", heir, mapOf("name" to "Helper $it")).status()).isEqualTo(HttpStatus.CREATED) }
        val sixth = post("$base/helpers", heir, mapOf("name" to "One too many"))
        assertThat(sixth.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(sixth.errorCode()).isEqualTo("helpers_full")
        assertThat(post("$base/helpers", heir, mapOf("name" to " ")).status()).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `when the window shuts, the plan and every helper link shut with it`() {
        openTheWindow()
        val plan = start().json()
        val task = plan.path("tasks")[1].path("id").asText()
        val helper = post("$base/helpers", heir, mapOf("name" to "Meera")).json()
        call(HttpMethod.PUT, "$base/tasks/$task/helper", heir, mapOf("helperId" to helper.path("id").asText()))
        val token = helper.path("url").asText().substringAfterLast("/")

        // She was only travelling.
        post("/api/v1/households/$householdId/emergency/requests/$requestId/veto", owner)

        assertThat(get(base, heir).status()).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(get("/api/v1/share/$token/tasks").status()).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(patch("$base/tasks/$task", heir, mapOf("status" to "done")).status()).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `starting, sharing and taking back are in the audit log`() {
        openTheWindow()
        start()
        val helperId = post("$base/helpers", heir, mapOf("name" to "Meera")).json().path("id").asText()
        delete("$base/helpers/$helperId", heir)
        val actions = db.queryForList(
            "select action from activity_log where household_id = ?::uuid", String::class.java, householdId,
        )
        assertThat(actions).contains("heir.plan.start", "heir.helper.add", "heir.helper.remove", "share.revoke")
    }
}
