package tech.bhrigu.almira.investment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.support.ApiTestBase

/**
 * Dates that a family record can actually have.
 *
 * A `date` column takes year 1 and year 9999 without complaint, and the API used
 * to pass them through: a maturity date of `0001-01-01`, of `9999-12-31`, of
 * `+10000-01-01`, and a date of birth in 1700 were all saved with a 201. None of
 * them is a typo anyone spots on the screen afterwards, and none of them is
 * inert — the maturity date is what the reminder worker schedules from and what
 * a rollover starts the next term at.
 *
 * Both edges are checked in each direction, because a bound that is off by a day
 * is the kind of thing only the edge finds: the first date in range is accepted,
 * the last date out of range is refused.
 */
@DisplayName("Dates are bounded to ones a record can have")
class SensibleDatesApiTest : ApiTestBase() {

    private lateinit var owner: String
    private lateinit var householdId: String
    private lateinit var universalTypeId: String

    @BeforeEach
    fun setUp() {
        owner = signIn()
        householdId = createHousehold(owner, "Koduri", "household", "Ishwarya")
            .path("id").asText()
        universalTypeId = typeId(owner, householdId, "universal")
    }

    private fun captureWith(field: String, date: String) = post(
        "/api/v1/households/$householdId/investments", owner,
        mapOf(
            "typeId" to universalTypeId, "title" to "Dated $date",
            "investedAmount" to 1000, "visibility" to "household", field to date,
        ),
    )

    private fun member(dob: String) = post(
        "/api/v1/households/$householdId/members", owner,
        mapOf("displayName" to "Someone", "relationship" to "other", "dateOfBirth" to dob),
    )

    // --- a holding's own dates -----------------------------------------------

    @Test
    fun `a maturity date outside the calendar a record lives on is refused`() {
        listOf("0001-01-01", "1899-12-31", "9999-12-31", "+10000-01-01").forEach { date ->
            val refused = captureWith("maturityDate", date)
            assertThat(refused.status())
                .describedAs("maturityDate $date was accepted")
                .isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(refused.errorCode()).isEqualTo("date_out_of_range")
        }
    }

    @Test
    fun `the first and last dates in range are accepted`() {
        assertThat(captureWith("maturityDate", "1900-01-01").status())
            .describedAs("the earliest date allowed must not be refused with the ones below it")
            .isEqualTo(HttpStatus.CREATED)
        assertThat(captureWith("maturityDate", "2200-12-31").status())
            .describedAs("the latest date allowed must not be refused with the ones above it")
            .isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `a start date is bounded the same way, and nothing is written when it is not`() {
        val before = get("/api/v1/households/$householdId/investments", owner).json().size()

        val refused = captureWith("startDate", "0001-01-01")
        assertThat(refused.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(refused.errorCode()).isEqualTo("date_out_of_range")

        assertThat(get("/api/v1/households/$householdId/investments", owner).json().size())
            .describedAs("refused before the row was written, not rolled back after")
            .isEqualTo(before)
    }

    @Test
    fun `an edit cannot put a date out of range that a capture could not`() {
        val id = captureWith("maturityDate", "2030-04-01").json().path("id").asText()
        val version = get("/api/v1/households/$householdId/investments/$id", owner)
            .json().path("version").asInt()

        val refused = patch(
            "/api/v1/households/$householdId/investments/$id", owner,
            mapOf("version" to version, "maturityDate" to "9999-12-31"),
        )
        assertThat(refused.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(refused.errorCode()).isEqualTo("date_out_of_range")

        assertThat(
            get("/api/v1/households/$householdId/investments/$id", owner)
                .json().path("maturityDate").asText(),
        ).describedAs("the date on the record is the one that was already there")
            .isEqualTo("2030-04-01")
    }

    // --- a person's date of birth --------------------------------------------

    @Test
    fun `a date of birth before anyone alive is refused, at both ends of the rule`() {
        val tooEarly = member("1700-01-01")
        assertThat(tooEarly.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(tooEarly.errorCode()).isEqualTo("dob_too_early")

        assertThat(member("1899-12-31").errorCode())
            .describedAs("the day before the earliest allowed")
            .isEqualTo("dob_too_early")

        assertThat(member("1900-01-01").status())
            .describedAs("the earliest allowed itself")
            .isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `the future is still refused, and by its own name`() {
        assertThat(member("2999-01-01").errorCode()).isEqualTo("dob_future")
    }

    @Test
    fun `an edit cannot move a date of birth out of range either`() {
        val created = member("1980-06-01").json()
        val memberId = created.path("id").asText()

        val refused = patch(
            "/api/v1/households/$householdId/members/$memberId", owner,
            mapOf("version" to created.path("version").asInt(), "dateOfBirth" to "1700-01-01"),
        )
        assertThat(refused.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(refused.errorCode()).isEqualTo("dob_too_early")

        assertThat(
            get("/api/v1/households/$householdId/members", owner).json()
                .first { it.path("id").asText() == memberId }
                .path("dateOfBirth").asText(),
        ).isEqualTo("1980-06-01")
    }

    // --- the same rule, next door --------------------------------------------

    @Test
    fun `a loan cannot end outside the calendar, and cannot pay you to borrow`() {
        val outOfRange = post(
            "/api/v1/households/$householdId/liabilities", owner,
            mapOf(
                "title" to "Personal loan", "kind" to "personal", "outstanding" to 100_000,
                "visibility" to "household", "endDate" to "9999-12-31",
            ),
        )
        assertThat(outOfRange.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(outOfRange.errorCode()).isEqualTo("date_out_of_range")

        val negative = post(
            "/api/v1/households/$householdId/liabilities", owner,
            mapOf(
                "title" to "Personal loan", "kind" to "personal", "outstanding" to 100_000,
                "visibility" to "household", "interestRate" to -5,
            ),
        )
        assertThat(negative.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(negative.errorCode()).isEqualTo("interest_rate_negative")

        assertThat(get("/api/v1/households/$householdId/liabilities", owner).json())
            .describedAs("neither refusal left a loan behind")
            .isEmpty()
    }
}
