package tech.almira.lifecycle

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
        assertThat(guardianBody(userId(ishwarya))).describedAs("with no login, nothing reaches the child: the parent is told so")
            .contains("and still needs telling")
    }

    /** The in-app copy keeps the title, so that is where "still needs telling" has to be. */
    private fun guardianBody(userId: String): String = db.queryForObject(
        "select title from outbound_messages where user_id = ?::uuid and channel = 'in_app' and template = 'lifecycle.coming_of_age.guardian'",
        String::class.java, userId,
    )!!

    private fun childTold(): Boolean = db.queryForObject(
        "select child_told_at is not null from coming_of_age_notices where member_id = ?::uuid", Boolean::class.java, aaravMemberId,
    )!!

    /** Owner's decision, 2026-09-15 (V146): with no channel to the child, told at their first sign-in. */
    @Test
    fun `a child with no login is told the moment they first sign in as themselves, once`() {
        notices.run(LocalDate.now())
        assertThat(childTold()).isFalse()

        val aarav = signIn()
        joinHousehold(ishwarya, householdId, aaravMemberId, aarav, role = "editor")
        val aaravUser = userId(aarav)
        assertThat(templatesFor(aaravUser).count { it == ComingOfAgeNotices.CHILD_TEMPLATE })
            .describedAs("the child's own notice, in the app").isEqualTo(1)
        assertThat(childTold()).isTrue()
        assertThat(notices.tellOnFirstSignIn(java.util.UUID.fromString(aaravMemberId), java.util.UUID.fromString(aaravUser)))
            .describedAs("never twice").isFalse()
        assertThat(templatesFor(aaravUser).count { it == ComingOfAgeNotices.CHILD_TEMPLATE }).isEqualTo(1)
    }

    /** A child who already has a login is told by the sweep itself, outside the app too, with no consent asked. */
    @Test
    fun `a child who already has a login is told at once, essential, and the parent is told they were`() {
        val aarav = signIn()
        joinHousehold(ishwarya, householdId, aaravMemberId, aarav, role = "editor")
        val aaravUser = userId(aarav)
        assertThat(templatesFor(aaravUser)).doesNotContain(ComingOfAgeNotices.CHILD_TEMPLATE)

        assertThat(notices.run(LocalDate.now())).describedAs("noticed though they have a login").isGreaterThanOrEqualTo(1)
        assertThat(childTold()).isTrue()
        assertThat(templatesFor(aaravUser).count { it == ComingOfAgeNotices.CHILD_TEMPLATE }).isEqualTo(1)
        assertThat(
            db.queryForObject(
                "select count(*) from outbound_messages where user_id = ?::uuid and template = ? and channel <> 'in_app'",
                Int::class.java, aaravUser, ComingOfAgeNotices.CHILD_TEMPLATE,
            ),
        ).describedAs("essential: queued outside the app though the child never said yes to messages").isPositive()
        assertThat(guardianBody(userId(ishwarya))).contains("and has been told")
        assertThat(templatesFor(aaravUser)).describedAs("the child is not sent the note meant for the adults")
            .doesNotContain("lifecycle.coming_of_age.guardian")
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
