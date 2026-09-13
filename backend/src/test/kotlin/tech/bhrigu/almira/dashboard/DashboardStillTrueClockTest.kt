package tech.bhrigu.almira.dashboard

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * Known-issues 18: the Home card "not confirmed lately" and "Still true?" used to
 * keep two clocks (a flat six months against docs/21's per-type periods). The
 * card now reads the same view, so a holding is counted exactly when "Still
 * true?" says it is due.
 *
 * Records are aged on the owner connection, as in StillTrueApiTest; both answers
 * are read through the API, as the owner.
 */
@DisplayName("Dashboard — the 'not confirmed' card keeps the still-true clock")
class DashboardStillTrueClockTest : ApiTestBase() {

    private lateinit var owner: String
    private lateinit var householdId: String

    @BeforeEach
    fun setUp() {
        owner = signIn()
        householdId = createHousehold(owner, "Koduri", "private", "Ishwarya").path("id").asText()
    }

    private fun holding(type: String, title: String): String =
        capture(
            owner, householdId, type, title, BigDecimal(100_000),
            attributes = if (type == "fd") mapOf("interest_rate" to 7.1) else emptyMap(),
        ).path("id").asText()

    private fun age(id: String, months: Int) {
        db.update(
            "update investments set created_at = now() - make_interval(months => ?), last_verified_at = null where id = ?::uuid",
            months, id,
        )
    }

    private fun notConfirmedCard(): JsonNode? {
        val response = get("/api/v1/households/$householdId/dashboard?scope=household", owner)
        assertThat(response.status()).describedAs(response.body).isEqualTo(HttpStatus.OK)
        return response.json().path("attention").firstOrNull { it.path("code").asText() == "not_verified" }
    }

    private fun cardIds(): Set<String> =
        notConfirmedCard()?.path("investmentIds")?.map { it.asText() }?.toSet() ?: emptySet()

    private fun stillTrueDue(): Set<String> =
        get("/api/v1/households/$householdId/still-true", owner).json().path("items")
            .filter { it.path("recordType").asText() == "investment" && it.path("isDue").asBoolean() }
            .map { it.path("recordId").asText() }.toSet()

    @Test
    fun `a holding due under still-true is counted, and one not yet due is not`() {
        // Due under still-true: past a per-type period that is shorter than six months,
        // past twelve months, and a week past a maturity.
        val cashFourMonths = holding("savings_buffer", "Buffer")        // cash: 3 months
        val fdThirteenMonths = holding("fd", "Old FD")                  // 12 months
        val maturedFd = holding("fd", "Matured FD")                     // key date
        // Not due under still-true: a flat six-month clock would have counted the first two.
        val fdSevenMonths = holding("fd", "Seven-month FD")             // 12 months, not yet
        val snoozedFd = holding("fd", "Snoozed FD")
        val fresh = holding("fd", "New FD")

        age(cashFourMonths, 4)
        age(fdThirteenMonths, 13)
        age(maturedFd, 2)
        val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))
        db.update("update investments set maturity_date = ?::date where id = ?::uuid", today.minusDays(10).toString(), maturedFd)
        age(fdSevenMonths, 7)
        age(snoozedFd, 13)
        val snooze = post(
            "/api/v1/households/$householdId/still-true/investment/$snoozedFd/snooze", owner,
            mapOf("until" to today.plusMonths(1).toString()),
        )
        assertThat(snooze.status()).describedAs(snooze.body).isEqualTo(HttpStatus.OK)

        val due = stillTrueDue()
        assertThat(due).containsExactlyInAnyOrder(cashFourMonths, fdThirteenMonths, maturedFd)

        assertThat(cardIds())
            .describedAs("the card counts exactly what still-true says is due — one clock")
            .containsExactlyInAnyOrderElementsOf(due)
            .doesNotContain(fdSevenMonths, snoozedFd, fresh)
        assertThat(notConfirmedCard()!!.path("count").asInt()).isEqualTo(3)
    }

    @Test
    fun `answering still-true clears the card, and nothing due means no card`() {
        val cash = holding("savings_buffer", "Buffer")
        age(cash, 4)
        assertThat(cardIds()).containsExactly(cash)

        val confirmed = post("/api/v1/households/$householdId/still-true/investment/$cash/confirm", owner)
        assertThat(confirmed.status()).describedAs(confirmed.body).isEqualTo(HttpStatus.OK)

        assertThat(notConfirmedCard()).isNull()
    }
}
