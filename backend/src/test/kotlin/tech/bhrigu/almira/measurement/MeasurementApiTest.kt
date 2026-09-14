package tech.bhrigu.almira.measurement

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal

/**
 * What we measure, through the whole application: each event counts once, a
 * failed action counts nothing, and the three rules — opted out, a minor
 * involved, not on the list — hold over HTTP as well as in SQL
 * (db/tests/rls_privacy_test.sql).
 *
 * Counts are read as the schema owner, because the runtime role cannot read
 * them at all; that is asserted here too. Deltas, not totals: other classes in
 * the run add to the same day's rows.
 */
@DisplayName("Product measurement: events per day, about nobody")
class MeasurementApiTest : ApiTestBase() {

    private fun count(event: String, step: Int = 0): Long = db.queryForObject(
        """
        select coalesce(sum(count), 0) from measurement_daily_counts
        where event = ? and step = ? and day = (now() at time zone 'Asia/Kolkata')::date
        """.trimIndent(),
        Long::class.java, event, step,
    )!!

    private fun <T> delta(event: String, step: Int = 0, block: () -> T): Long {
        val before = count(event, step)
        block()
        return count(event, step) - before
    }

    @Test
    fun `a row holds a day, an event, a step and a count, and nothing else`() {
        val columns = db.queryForList(
            "select column_name from information_schema.columns where table_name = 'measurement_daily_counts' order by ordinal_position",
            String::class.java,
        )
        assertThat(columns).containsExactly("day", "event", "step", "count")
    }

    @Test
    fun `sign-in, a household, and a first holding each count once`() {
        lateinit var token: String
        assertThat(delta("sign_in_completed") { token = signIn() }).isEqualTo(1)

        lateinit var householdId: String
        assertThat(delta("household_created") { householdId = createHousehold(token).path("id").asText() })
            .isEqualTo(1)

        val firstHolding = delta("first_holding_added") {
            assertThat(delta("holding_added") {
                capture(token, householdId, "fd", "FD", BigDecimal("100000"),
                    attributes = mapOf("interest_rate" to "7.1"))
            }).isEqualTo(1)
        }
        assertThat(firstHolding).isEqualTo(1)

        val secondFirst = delta("first_holding_added") {
            capture(token, householdId, "fd", "FD two", BigDecimal("50000"),
                attributes = mapOf("interest_rate" to "7.1"))
        }
        assertThat(secondFirst).describedAs("only the household's first holding is a first").isEqualTo(0)
    }

    @Test
    fun `an action that fails counts nothing`() {
        val token = signIn()
        val householdId = createHousehold(token).path("id").asText()
        val refused = delta("holding_added") {
            val response = post(
                "/api/v1/households/$householdId/investments", token,
                mapOf("typeId" to typeId(token, householdId, "fd"), "title" to " ", "investedAmount" to 1),
            )
            assertThat(response.status().is4xxClientError).isTrue()
        }
        assertThat(refused).isEqualTo(0)
    }

    @Test
    fun `a holding owned by a child is not counted`() {
        val token = signIn()
        val household = createHousehold(token)
        val householdId = household.path("id").asText()
        val child = post(
            "/api/v1/households/$householdId/members", token,
            mapOf("displayName" to "Aarav", "relationship" to "child", "dateOfBirth" to "2015-04-02"),
        ).json().path("id").asText()

        val counted = delta("holding_added") {
            capture(token, householdId, "fd", "Aarav's FD", BigDecimal("25000"),
                owners = listOf(mapOf("memberId" to child, "sharePct" to 100)),
                attributes = mapOf("interest_rate" to "7.1"))
        }
        assertThat(counted).isEqualTo(0)
    }

    @Test
    fun `someone who opts out in Settings is not counted, and nobody else is affected`() {
        val quiet = signIn()
        val other = signIn()
        val quietHousehold = createHousehold(quiet).path("id").asText()
        val otherHousehold = createHousehold(other).path("id").asText()

        assertThat(get("/api/v1/me/measurement", quiet).json().path("optedOut").asBoolean()).isFalse()
        val set = call(HttpMethod.PUT, "/api/v1/me/measurement", quiet, mapOf("optedOut" to true))
        assertThat(set.status()).isEqualTo(HttpStatus.OK)
        assertThat(get("/api/v1/me/measurement", quiet).json().path("optedOut").asBoolean()).isTrue()
        assertThat(get("/api/v1/me/measurement", other).json().path("optedOut").asBoolean()).isFalse()
        assertThat(get("/api/v1/me/measurement", quiet).json().path("events").map { it.asText() })
            .hasSize(12).contains("capture_abandoned", "first_holding_added")

        assertThat(delta("holding_added") {
            capture(quiet, quietHousehold, "fd", "FD", BigDecimal("1000"),
                attributes = mapOf("interest_rate" to "7.1"))
        }).isEqualTo(0)
        assertThat(delta("capture_abandoned", 3) {
            post("/api/v1/measurement/abandoned", quiet, mapOf("form" to "capture", "step" to 3))
        }).isEqualTo(0)
        assertThat(delta("holding_added") {
            capture(other, otherHousehold, "fd", "FD", BigDecimal("1000"),
                attributes = mapOf("interest_rate" to "7.1"))
        }).isEqualTo(1)

        call(HttpMethod.PUT, "/api/v1/me/measurement", quiet, mapOf("optedOut" to false))
        assertThat(delta("holding_added") {
            capture(quiet, quietHousehold, "fd", "FD again", BigDecimal("1000"),
                attributes = mapOf("interest_rate" to "7.1"))
        }).isEqualTo(1)
    }

    @Test
    fun `the client reports which step a capture was abandoned at, and nothing else`() {
        val token = signIn()
        assertThat(delta("capture_abandoned", 2) {
            assertThat(post("/api/v1/measurement/abandoned", token, mapOf("form" to "capture", "step" to 2)).status())
                .isEqualTo(HttpStatus.NO_CONTENT)
        }).isEqualTo(1)

        assertThat(post("/api/v1/measurement/abandoned", token, mapOf("form" to "capture", "step" to 4)).status())
            .isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(post("/api/v1/measurement/abandoned", token, mapOf("form" to "net_worth", "step" to 1)).status())
            .isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(post("/api/v1/measurement/abandoned", body = mapOf("form" to "capture", "step" to 1)).status())
            .isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(call(HttpMethod.PUT, "/api/v1/me/measurement", token, emptyMap<String, Any>()).status())
            .isEqualTo(HttpStatus.BAD_REQUEST)
    }
}
