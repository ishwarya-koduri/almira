package tech.bhrigu.almira.lifecycle

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.math.BigDecimal
import java.time.LocalDate

@DisplayName("Coming of age: a note to the parents, and a welcome for the young adult")
class ComingOfAgeApiTest : LifecycleTestSupport() {

    @Autowired private lateinit var notices: ComingOfAgeNotices

    private lateinit var ishwarya: String
    private lateinit var householdId: String
    private lateinit var aaravMemberId: String

    @BeforeEach
    fun setUp() {
        ishwarya = signIn()
        householdId = createHousehold(ishwarya, "Koduri", "private", "Ishwarya").path("id").asText()
        aaravMemberId = post(
            "/api/v1/households/$householdId/members", ishwarya,
            mapOf(
                "displayName" to "Aarav", "relationship" to "child",
                "dateOfBirth" to LocalDate.now().minusYears(18).withDayOfMonth(1).toString(),
            ),
        ).json().path("id").asText()
    }

    @Test
    fun `the household is told once in the birthday month`() {
        assertThat(notices.run(LocalDate.now())).isGreaterThanOrEqualTo(1)
        assertThat(notices.run(LocalDate.now())).describedAs("once, however often the sweep runs").isZero()

        val listed = get("/api/v1/households/$householdId/coming-of-age", ishwarya).json()
        assertThat(listed.single().path("memberName").asText()).isEqualTo("Aarav")
        assertThat(listed.single().path("hasLogin").asBoolean()).isFalse()
        assertThat(templatesFor(userId(ishwarya)).count { it == "lifecycle.coming_of_age.guardian" }).isEqualTo(1)
    }

    @Test
    fun `signed in as himself, he sees what is his and chooses what stays visible`() {
        capture(
            ishwarya, householdId, "gold_physical", "Aarav's gold coin", BigDecimal(50_000),
            visibility = "household", owners = listOf(mapOf("memberId" to aaravMemberId, "sharePct" to 100)),
        )
        notices.run(LocalDate.now())
        assertThat(get("/api/v1/households/$householdId/coming-of-age/welcome", ishwarya).status())
            .isEqualTo(HttpStatus.NOT_FOUND)

        val aarav = signIn()
        joinHousehold(ishwarya, householdId, aaravMemberId, aarav, role = "editor")

        val welcome = get("/api/v1/households/$householdId/coming-of-age/welcome", aarav).json()
        val coin = welcome.path("records").single()
        assertThat(coin.path("title").asText()).isEqualTo("Aarav's gold coin")
        assertThat(coin.path("visibility").asText()).isEqualTo("household")

        val accepted = post(
            "/api/v1/households/$householdId/coming-of-age/welcome", aarav,
            mapOf("choices" to listOf(mapOf("recordType" to "investment", "recordId" to coin.path("recordId").asText(), "visibility" to "private"))),
        )
        assertThat(accepted.status()).describedAs(accepted.body).isEqualTo(HttpStatus.OK)
        assertThat(accepted.json().path("welcomedAt").isNull).isFalse()

        assertThat(get("/api/v1/households/$householdId/investments", ishwarya).json().map { it.path("title").asText() })
            .describedAs("private to him now, so not to his parent")
            .doesNotContain("Aarav's gold coin")
        assertThat(get("/api/v1/households/$householdId/investments", aarav).json().map { it.path("title").asText() })
            .contains("Aarav's gold coin")
    }
}
