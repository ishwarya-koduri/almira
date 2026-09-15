package tech.bhrigu.almira.lifecycle

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * The owner's answer (D6, docs/05 §12.7): "Acceptance is the whole point. Order
 * it, don't automate it: ask the named successor first, give them a longer
 * window, then open it to others."
 *
 * Meera, a viewer, is the successor Ishwarya named; Ravi is an admin. Each
 * refusal is proven by what did not happen — Ravi's role, the dormancy, the log.
 */
@DisplayName("A dormant household asks the named successor first, then everyone else")
class DormancyOrderApiTest : LifecycleTestSupport() {

    @Autowired private lateinit var sweep: LifecycleSweep

    private lateinit var ishwarya: String
    private lateinit var ravi: String
    private lateinit var meera: String
    private lateinit var householdId: String

    private val later: Instant get() = Instant.now().plus(Duration.ofDays(31))

    @BeforeEach
    fun setUp() {
        ishwarya = signIn()
        ravi = signIn()
        meera = signIn()
        householdId = createHousehold(ishwarya, "Koduri", "private", "Ishwarya").path("id").asText()
        val raviMemberId = addMember(ishwarya, householdId, "Ravi").path("id").asText()
        joinHousehold(ishwarya, householdId, raviMemberId, ravi, role = "admin")
        val meeraMemberId = addMember(ishwarya, householdId, "Meera").path("id").asText()
        joinHousehold(ishwarya, householdId, meeraMemberId, meera, role = "viewer")
        capture(ishwarya, householdId, "gold_physical", "Family gold", BigDecimal(900_000), visibility = "household")
        call(HttpMethod.PUT, "/api/v1/households/$householdId/successor", ishwarya, mapOf("memberId" to meeraMemberId))
            .also { assertThat(it.status()).describedAs(it.body).isEqualTo(HttpStatus.OK) }

        stepUp(ishwarya)
        post("/api/v1/me/closure", ishwarya).also { assertThat(it.status()).describedAs(it.body).isEqualTo(HttpStatus.CREATED) }
        sweep.run(later)
    }

    private fun count(sql: String, vararg args: Any): Int = db.queryForObject(sql, Int::class.java, *args)!!

    private fun dormancy(token: String) = get("/api/v1/households/$householdId/dormancy", token).json()

    private fun accept(token: String) = post("/api/v1/households/$householdId/dormancy/accept", token)

    private fun decline(token: String) = post("/api/v1/households/$householdId/dormancy/decline", token)

    private fun role(token: String) = household(token, householdId).path("myRole").asText()

    private fun inApp(token: String, template: String) = count(
        "select count(*) from outbound_messages where user_id = ?::uuid and template = ? and channel = 'in_app'",
        userId(token), template,
    )

    private fun audited(action: String) = count(
        "select count(*) from activity_log where household_id = ?::uuid and action = ?", householdId, action,
    )

    private fun expectOnlyTheSuccessorIsAsked() {
        val row = db.queryForMap(
            "select successor_member_id is not null as named, successor_until > now() + interval '13 days' as fortnight, " +
                "opened_to_others_at from household_dormancies where household_id = ?::uuid and ended_at is null",
            householdId,
        )
        assertThat(row).containsEntry("named", true).containsEntry("fortnight", true).containsEntry("opened_to_others_at", null)
        assertThat(inApp(meera, "lifecycle.household.asked_first")).isEqualTo(1)
        assertThat(inApp(ravi, "lifecycle.household.dormant")).describedAs("the others are not asked yet").isZero()
    }

    /** Ravi, with a step-up, tries and fails; nothing changes. */
    private fun raviCannotTakeItOn() {
        val view = dormancy(ravi)
        assertThat(view.path("canAccept").asBoolean()).isFalse()
        assertThat(view.path("askedFirstUntil").isTextual).isTrue()
        assertThat(view.path("youAreAskedFirst").asBoolean()).isFalse()
        assertThat(view.path("explanation").asText()).doesNotContain("Meera")
        stepUp(ravi)
        val refused = accept(ravi)
        assertThat(refused.status()).isEqualTo(HttpStatus.CONFLICT)
        assertThat(refused.errorCode()).isEqualTo("dormancy_successor_first")
        assertThat(role(ravi)).isEqualTo("admin")
        assertThat(audited("household.ownership.accept")).isZero()
        assertThat(count("select count(*) from household_dormancies where household_id = ?::uuid and ended_at is null", householdId))
            .isEqualTo(1)
        // Nor around the service: the database function refuses him the same way.
        val direct = runCatching {
            db.execute(
                org.springframework.jdbc.core.ConnectionCallback { c ->
                    c.autoCommit = false
                    try {
                        c.prepareStatement("select set_config('app.user_id', ?, true)").use { it.setString(1, userId(ravi)); it.execute() }
                        c.createStatement().use { it.execute("select app.accept_household_ownership('$householdId'::uuid)") }
                    } finally {
                        c.rollback()
                        c.autoCommit = true
                    }
                },
            )
        }
        assertThat(generateSequence(direct.exceptionOrNull()) { it.cause }.joinToString(" ") { it.message ?: "" })
            .contains("dormancy_successor_first")
        // And he cannot decline for her.
        assertThat(decline(ravi).errorCode()).isEqualTo("dormancy_not_asked")
        assertThat(audited("household.ownership.decline")).isZero()
    }

    @Test
    fun `the named successor is asked first, alone, and takes it on with a step-up`() {
        expectOnlyTheSuccessorIsAsked()
        raviCannotTakeItOn()

        val hers = dormancy(meera)
        assertThat(hers.path("youAreAskedFirst").asBoolean()).isTrue()
        assertThat(hers.path("canAccept").asBoolean()).isTrue()
        assertThat(hers.path("canDecline").asBoolean()).isTrue()

        assertThat(accept(meera).errorCode()).describedAs("a step-up first").isEqualTo("step_up_required")
        assertThat(role(meera)).isEqualTo("viewer")
        stepUp(meera)
        assertThat(accept(meera).status()).isEqualTo(HttpStatus.OK)
        assertThat(role(meera)).isEqualTo("owner")
        assertThat(
            count(
                "select count(*) from activity_log where household_id = ?::uuid and action = 'household.ownership.accept' " +
                    "and actor_user_id = ?::uuid and diff ->> 'asSuccessor' = 'true'",
                householdId, userId(meera),
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `when the successor declines, it opens to the others, who are told then`() {
        expectOnlyTheSuccessorIsAsked()
        assertThat(decline(meera).status()).isEqualTo(HttpStatus.OK)
        assertThat(audited("household.ownership.decline")).isEqualTo(1)
        assertThat(count("select count(*) from household_dormancies where household_id = ?::uuid and successor_declined_at is not null and opened_to_others_at is not null", householdId))
            .isEqualTo(1)
        assertThat(inApp(ravi, "lifecycle.household.dormant")).isEqualTo(1)
        assertThat(decline(meera).errorCode()).describedAs("declining once").isEqualTo("dormancy_not_asked")

        assertThat(dormancy(ravi).path("canAccept").asBoolean()).isTrue()
        stepUp(ravi)
        assertThat(accept(ravi).status()).isEqualTo(HttpStatus.OK)
        assertThat(role(ravi)).isEqualTo("owner")

        // The sweep does not tell anyone a second time.
        sweep.run(later.plus(Duration.ofHours(1)))
        assertThat(inApp(ravi, "lifecycle.household.dormant")).isEqualTo(1)
    }

    @Test
    fun `when the successor's window passes, the sweep opens it to the others and tells them`() {
        expectOnlyTheSuccessorIsAsked()
        raviCannotTakeItOn()

        // A fortnight on. The window is counted in the database; it is moved back here.
        db.update(
            "update household_dormancies set accept_from = now() - interval '15 days', successor_until = now() - interval '1 minute' " +
                "where household_id = ?::uuid and ended_at is null",
            householdId,
        )
        val result = sweep.run(later)
        assertThat(result.dormanciesOpenedToOthers).isGreaterThanOrEqualTo(1)
        assertThat(inApp(ravi, "lifecycle.household.dormant")).isEqualTo(1)
        assertThat(inApp(meera, "lifecycle.household.dormant")).describedAs("she was asked already").isZero()

        val view = dormancy(ravi)
        assertThat(view.path("askedFirstUntil").isTextual).describedAs("nobody is asked first any more").isFalse()
        assertThat(view.path("canAccept").asBoolean()).isTrue()
        assertThat(decline(meera).errorCode()).describedAs("nothing to decline once it is open").isEqualTo("dormancy_not_asked")
        // His step-up from a moment ago still stands.
        assertThat(accept(ravi).status()).isEqualTo(HttpStatus.OK)
        assertThat(role(ravi)).isEqualTo("owner")
        assertThat(role(meera)).isEqualTo("viewer")
    }

    @Test
    fun `a successor who can no longer take it on is passed over at once`() {
        expectOnlyTheSuccessorIsAsked()
        // Meera starts leaving the household: she is no longer someone to ask.
        stepUp(meera)
        post("/api/v1/households/$householdId/departures", meera).also { assertThat(it.status()).describedAs(it.body).isEqualTo(HttpStatus.CREATED) }

        assertThat(dormancy(ravi).path("canAccept").asBoolean()).isTrue()
        sweep.run(Instant.now())
        assertThat(inApp(ravi, "lifecycle.household.dormant")).isEqualTo(1)
    }
}
