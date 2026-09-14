package tech.bhrigu.almira.continuity

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Readiness shows movement (docs/22 §6, catch-up plan X-33): "N things safer
 * than last month", from one row of counts a day per person.
 *
 * The owner connection only back-dates a row, because a month cannot pass inside
 * a test; every answer is read through the API, as the person.
 */
@DisplayName("Handover readiness — movement since last month")
class ReadinessMovementApiTest : ApiTestBase() {

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

    private fun readiness(token: String = owner): JsonNode {
        val response = get("/api/v1/households/$householdId/continuity/readiness", token)
        assertThat(response.status()).describedAs(response.body).isEqualTo(HttpStatus.OK)
        return response.json()
    }

    private fun rows(): List<Map<String, Any?>> = db.queryForList(
        "select user_id::text as user_id, taken_on, score, checks::text as checks from readiness_snapshots where household_id = ?::uuid",
        householdId,
    )

    private fun backdate(days: Long) {
        db.update(
            "update readiness_snapshots set taken_on = taken_on - ?::int where household_id = ?::uuid",
            days, householdId,
        )
    }

    private fun policy(): String = capture(
        owner, householdId, "insurance_term", "LIC Jeevan Anand", BigDecimal(500_000),
        attributes = mapOf("policy_no" to "123456789", "sum_assured" to 1_000_000, "premium_amount" to 12_000),
    ).path("id").asText()

    @Test
    fun `the first look has nothing to compare with, and keeps one row of counts for today`() {
        policy()
        val body = readiness()
        assertThat(body.has("movement")).isFalse()

        readiness()   // a second look the same day replaces, not adds
        val stored = rows()
        assertThat(stored).hasSize(1)
        assertThat(stored.first()["checks"] as String).contains("nominee").doesNotContain("LIC")
    }

    @Test
    fun `a nominee recorded since last month is one thing safer`() {
        val id = policy()
        val before = readiness()
        backdate(35)

        val response = call(
            HttpMethod.PUT, "/api/v1/households/$householdId/investments/$id/nominees", owner,
            mapOf("nominees" to listOf(mapOf("memberId" to spouseMemberId, "sharePct" to 100))),
        )
        assertThat(response.statusCode.is2xxSuccessful).describedAs(response.body).isTrue()

        val movement = readiness().path("movement")
        assertThat(movement.path("saferCount").asInt()).isEqualTo(1)
        assertThat(movement.path("fewerDone").asInt()).isZero()
        assertThat(movement.path("comparedWith").asText()).isEqualTo(
            LocalDate.parse(rows().minOf { it["taken_on"].toString() }).toString(),
        )
        if (before.has("score")) {
            assertThat(movement.path("previousScore").asInt()).isEqualTo(before.path("score").asInt())
        }
    }

    @Test
    fun `a look from a week ago is not last month`() {
        policy()
        readiness()
        backdate(7)
        assertThat(readiness().has("movement")).isFalse()
    }

    @Test
    fun `each person's history is their own`() {
        policy()
        readiness(owner)
        readiness(spouse)
        assertThat(rows().map { it["user_id"] }.distinct()).hasSize(2)
    }

    @Test
    fun `movement is net, and never below zero`() {
        val day = LocalDate.of(2026, 8, 1)
        assertThat(ReadinessHistoryService.movement(then = 3, now = 5, comparedWith = day, previousScore = 40))
            .isEqualTo(ReadinessMovement(day, 40, saferCount = 2, fewerDone = 0))
        assertThat(ReadinessHistoryService.movement(then = 5, now = 4, comparedWith = day, previousScore = null))
            .isEqualTo(ReadinessMovement(day, null, saferCount = 0, fewerDone = 1))
    }
}
