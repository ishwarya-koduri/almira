package tech.almira.continuity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import tech.almira.support.ApiTestBase
import java.time.LocalDate

@DisplayName("Guided flows remember the step, and the lost-money sweep remembers the answer")
class GuidedFlowAndLostMoneyApiTest : ApiTestBase() {

    private lateinit var owner: String
    private lateinit var spouse: String
    private lateinit var householdId: String
    private lateinit var ownerMemberId: String
    private lateinit var spouseMemberId: String
    private lateinit var childMemberId: String

    @BeforeEach
    fun setUp() {
        owner = signIn()
        spouse = signIn()
        val household = createHousehold(owner, "Koduri", "household", "Ishwarya")
        householdId = household.path("id").asText()
        ownerMemberId = household.path("myMemberId").asText()
        spouseMemberId = addMember(owner, householdId, "Ravi").path("id").asText()
        childMemberId = addMember(owner, householdId, "Aarav").path("id").asText()
        joinHousehold(owner, householdId, spouseMemberId, spouse)
    }

    private fun put(path: String, token: String, body: Any) = call(HttpMethod.PUT, path, token, body)

    // --- X-58 -------------------------------------------------------------------

    @Test
    fun `a flow is saved at every step and comes back to the same question, for that person only`() {
        val path = "/api/v1/households/$householdId/guided-flows/emergency_setup"
        assertThat(get(path, owner).status()).isEqualTo(HttpStatus.NOT_FOUND)

        val saved = put(path, owner, mapOf("step" to 1, "answers" to mapOf("trustedMemberId" to spouseMemberId)))
        assertThat(saved.status()).isEqualTo(HttpStatus.OK)
        put(path, owner, mapOf("step" to 2, "answers" to mapOf("trustedMemberId" to spouseMemberId, "waitDays" to 14)))

        val back = get(path, owner).json()
        assertThat(back.path("step").asInt()).isEqualTo(2)
        assertThat(back.path("answers").path("waitDays").asInt()).isEqualTo(14)

        assertThat(get(path, spouse).status()).describedAs("your place in a flow is yours").isEqualTo(HttpStatus.NOT_FOUND)

        assertThat(delete(path, owner).status()).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(get(path, owner).status()).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `only the answers a flow keeps, in the shape it keeps them`() {
        val path = "/api/v1/households/$householdId/guided-flows/estate_document"
        assertThat(put(path, owner, mapOf("step" to 1, "answers" to mapOf("location" to "Steel almirah"))).errorCode())
            .describedAs("where the original is is sealed, and never a draft answer")
            .isEqualTo("answers_invalid")
        assertThat(put(path, owner, mapOf("step" to 1, "answers" to mapOf("executedOn" to "yesterday"))).errorCode())
            .isEqualTo("answers_invalid")
        assertThat(put(path, owner, mapOf("step" to 99)).errorCode()).isEqualTo("step_invalid")
        assertThat(put(path, owner, mapOf("step" to 2, "answers" to mapOf("kind" to "will", "title" to "Ishwarya's will"))).status())
            .isEqualTo(HttpStatus.OK)
        assertThat(get("/api/v1/households/$householdId/guided-flows/nonsense", owner).status()).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `where and who keeps the step and never the words`() {
        val record = "investment:${java.util.UUID.randomUUID()}"
        val path = "/api/v1/households/$householdId/guided-flows/where_and_who?subject=$record"
        assertThat(put(path, owner, mapOf("step" to 1)).status()).isEqualTo(HttpStatus.OK)
        assertThat(put(path, owner, mapOf("step" to 1, "answers" to mapOf("originalLocation" to "Second shelf"))).errorCode())
            .isEqualTo("answers_invalid")
        assertThat(put("/api/v1/households/$householdId/guided-flows/where_and_who", owner, mapOf("step" to 1)).errorCode())
            .describedAs("a per-record flow says which record")
            .isEqualTo("subject_invalid")

        // And the table itself refuses words, whatever a future endpoint does.
        val refused = runCatching {
            db.update(
                """
                update guided_flow_drafts set answers = '{"originalLocation":"Second shelf"}'::jsonb
                where flow = 'where_and_who' and household_id = ?::uuid
                """.trimIndent(),
                householdId,
            )
        }
        assertThat(refused.isFailure).isTrue()
    }

    // --- P-25 -------------------------------------------------------------------

    @Test
    fun `the sweep links to three portals and fetches nothing`() {
        val sweep = get("/api/v1/households/$householdId/lost-money", owner).json()
        assertThat(sweep.path("portals").map { it.path("code").asText() }).containsExactly("udgam", "iepf", "epfo")
        assertThat(sweep.path("portals").map { it.path("url").asText() }).allMatch { it.startsWith("https://") }
        assertThat(sweep.path("portals").first().path("claim").size()).isGreaterThan(0)
        assertThat(sweep.path("checks").size()).isZero()
    }

    @Test
    fun `checked, nothing, or found — with the date — and the person it is about can see it`() {
        val path = "/api/v1/households/$householdId/lost-money/iepf"
        val checked = put(path, owner, mapOf("memberId" to spouseMemberId, "status" to "nothing", "checkedOn" to "2026-09-01"))
        assertThat(checked.status()).isEqualTo(HttpStatus.OK)
        assertThat(checked.json().path("checkedOn").asText()).isEqualTo("2026-09-01")
        assertThat(checked.json().path("memberName").asText()).isEqualTo("Ravi")

        assertThat(get("/api/v1/households/$householdId/lost-money", spouse).json().path("checks").size())
            .describedAs("a search in Ravi's name is Ravi's to see")
            .isEqualTo(1)

        assertThat(put(path, owner, mapOf("memberId" to spouseMemberId, "status" to "maybe")).errorCode()).isEqualTo("status_invalid")
        assertThat(put(path, owner, mapOf("memberId" to spouseMemberId, "status" to "checked", "checkedOn" to LocalDate.now().plusDays(5).toString())).errorCode())
            .isEqualTo("checked_on_invalid")
        assertThat(put("/api/v1/households/$householdId/lost-money/lottery", owner, mapOf("memberId" to spouseMemberId, "status" to "checked")).status())
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `a search about someone else is not everyone's business`() {
        put(
            "/api/v1/households/$householdId/lost-money/udgam", owner,
            mapOf("memberId" to childMemberId, "status" to "checked"),
        )
        assertThat(get("/api/v1/households/$householdId/lost-money", spouse).json().path("checks").size())
            .describedAs("neither the one who looked nor the one it is about")
            .isZero()
    }

    @Test
    fun `something found becomes a record for the family plan, with how to claim it`() {
        val found = post(
            "/api/v1/households/$householdId/lost-money/udgam/found", owner,
            mapOf("memberId" to ownerMemberId, "title" to "Old SBI savings account", "amount" to 42000, "whereFound" to "SBI Guntur"),
        )
        assertThat(found.status()).isEqualTo(HttpStatus.CREATED)
        val investmentId = found.json().path("investmentId").asText()
        assertThat(found.json().path("check").path("status").asText()).isEqualTo("found")
        assertThat(found.json().path("check").path("recordTitle").asText()).isEqualTo("Old SBI savings account")

        val record = get("/api/v1/households/$householdId/investments/$investmentId", owner).json()
        assertThat(record.path("isInContinuity").asBoolean()).isTrue()
        assertThat(record.path("notes").asText()).startsWith("How to claim it:").contains("Go to the bank named in the result")

        val handbook = get("/api/v1/households/$householdId/continuity/handbook", owner).json()
        assertThat(handbook.path("entries").map { it.path("title").asText() }).contains("Old SBI savings account")
        assertThat(db.queryForList("select action from activity_log where household_id = ?::uuid", String::class.java, householdId))
            .contains("lost_money.check", "lost_money.found")
    }
}
