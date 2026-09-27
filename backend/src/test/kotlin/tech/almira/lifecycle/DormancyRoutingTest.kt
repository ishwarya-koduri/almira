package tech.almira.lifecycle

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The owner's answers (2026-09-15, V147):
 *
 * - "After 90 days with nobody accepting, route to operator repair rather than
 *   leaving it claimable forever. 'Dormant forever' should be a decision, never
 *   something reached by drift."
 * - "Freezing edits to OTHER people's date of birth or login is right. Freezing a
 *   member's edit of their OWN login is not: someone who changes their phone
 *   number mid-dormancy would be locked out of their own family's records."
 */
@DisplayName("A dormancy nobody takes on goes to an operator after 90 days; your own login is never frozen")
class DormancyRoutingTest : LifecycleTestSupport() {

    @Autowired private lateinit var sweep: LifecycleSweep

    private lateinit var ravi: String
    private lateinit var householdId: String
    private lateinit var raviMemberId: String
    private lateinit var meeraMemberId: String

    @BeforeEach
    fun setUp() {
        val owner = signIn()
        ravi = signIn()
        val meera = signIn()
        householdId = createHousehold(owner, "Koduri", "private", "Ishwarya").path("id").asText()
        capture(owner, householdId, "gold_physical", "Family gold", BigDecimal(900_000), visibility = "household")
        raviMemberId = addMember(owner, householdId, "Ravi").path("id").asText()
        joinHousehold(owner, householdId, raviMemberId, ravi, role = "admin")
        meeraMemberId = addMember(owner, householdId, "Meera").path("id").asText()
        joinHousehold(owner, householdId, meeraMemberId, meera, role = "viewer")
        db.update(
            "insert into account_closures (user_id, requested_at, purge_after) values (?::uuid, now() - interval '31 days', now() - interval '1 day')",
            userId(owner),
        )
        sweep.run(Instant.now())
        check(open() == 1) { "the household went dormant" }
    }

    private fun count(sql: String, vararg args: Any): Int = db.queryForObject(sql, Int::class.java, *args)!!

    private fun open() = count("select count(*) from household_dormancies where household_id = ?::uuid and ended_at is null", householdId)

    private fun openFor(days: Int) = db.update(
        "update household_dormancies set opened_to_others_at = now() - make_interval(days => ?) where household_id = ?::uuid and ended_at is null",
        days, householdId,
    )

    private fun routed() = count(
        "select count(*) from household_dormancies where household_id = ?::uuid and ended_at is null and routed_to_repair_at is not null",
        householdId,
    )

    private fun routedNotices() = count(
        "select count(*) from outbound_messages where household_id = ?::uuid and template = 'lifecycle.household.routed_to_repair' and channel = 'in_app'",
        householdId,
    )

    @Test
    fun `under 90 days it stays open to the family, and after 90 it is an operator's, told once`() {
        openFor(89)
        assertThat(sweep.run(Instant.now()).dormanciesRoutedToRepair).isZero()
        assertThat(routed()).isZero()
        assertThat(get("/api/v1/households/$householdId/dormancy", ravi).json().path("canAccept").asBoolean()).isTrue()

        openFor(91)
        assertThat(sweep.run(Instant.now()).dormanciesRoutedToRepair).isEqualTo(1)
        assertThat(routed()).isEqualTo(1)
        assertThat(routedNotices()).describedAs("Ravi and Meera, in the app").isEqualTo(2)

        assertThat(sweep.run(Instant.now()).dormanciesRoutedToRepair).describedAs("once").isZero()
        assertThat(routedNotices()).isEqualTo(2)

        val view = get("/api/v1/households/$householdId/dormancy", ravi).json()
        assertThat(view.path("dormant").asBoolean()).isTrue()
        assertThat(view.path("canAccept").asBoolean()).isFalse()
        assertThat(view.path("handedToOperatorsAt").asText()).isNotEmpty()
        assertThat(view.path("explanation").asText()).contains("90 days")

        // An operator may now be asked, though Ravi would once have been eligible.
        assertThat(
            db.queryForObject(
                "select app.dormancy_has_someone_to_take_it_on(id) from household_dormancies where household_id = ?::uuid and ended_at is null",
                Boolean::class.java, householdId,
            ),
        ).isFalse()
    }

    /** The guard is in the database function, before the role change: proven by the role that did not change. */
    @Test
    fun `once it is an operator's, taking it on in the app is refused and nothing changes`() {
        openFor(91)
        sweep.run(Instant.now())
        stepUp(ravi)
        val refused = post("/api/v1/households/$householdId/dormancy/accept", ravi)
        assertThat(refused.status()).describedAs(refused.body).isEqualTo(HttpStatus.CONFLICT)
        assertThat(refused.errorCode()).isEqualTo("dormancy_routed_to_repair")
        assertThat(household(ravi, householdId).path("myRole").asText()).isEqualTo("admin")
        assertThat(open()).isEqualTo(1)
        assertThat(count("select count(*) from activity_log where household_id = ?::uuid and action = 'household.ownership.accept'", householdId)).isZero()

        // Straight at the function, past the service's own check: the same refusal.
        val direct = runCatching {
            asUser(userId(ravi), "select app.accept_household_ownership('$householdId'::uuid) is null")
        }
        assertThat(cause(direct)).contains("dormancy_routed_to_repair")
        assertThat(household(ravi, householdId).path("myRole").asText()).isEqualTo("admin")
    }

    /** What the database said: the message under Spring's wrapper, or "" when nothing was refused. */
    private fun cause(result: Result<*>): String = when (val e = result.exceptionOrNull()) {
        null -> ""
        is org.springframework.dao.DataAccessException -> e.mostSpecificCause.message ?: ""
        else -> e.message ?: ""
    }

    private fun asUserUpdate(userId: String, sql: String): Result<Int> = runCatching {
        db.execute(
            org.springframework.jdbc.core.ConnectionCallback { connection ->
                val auto = connection.autoCommit
                connection.autoCommit = false
                try {
                    connection.prepareStatement("select set_config('app.user_id', ?, true)").use {
                        it.setString(1, userId)
                        it.execute()
                    }
                    connection.createStatement().use { it.executeUpdate(sql) }
                } finally {
                    connection.rollback()
                    connection.autoCommit = auto
                }
            },
        )!!
    }

    @Test
    fun `while dormant a member may change their own login, but not someone else's, and nobody's date of birth`() {
        val newAccount = UUID.fromString(userId(signIn()))
        val own = asUserUpdate(userId(ravi), "update members set user_id = '$newAccount' where id = '$raviMemberId'")
        assertThat(own.exceptionOrNull()).describedAs("his own login").isNull()
        assertThat(own.getOrNull()).isEqualTo(1)

        val others = asUserUpdate(userId(ravi), "update members set user_id = '$newAccount' where id = '$meeraMemberId'")
        assertThat(cause(others)).describedAs("Meera's login").contains("household_dormant")

        val birthday = asUserUpdate(userId(ravi), "update members set date_of_birth = date '1990-01-01' where id = '$raviMemberId'")
        assertThat(cause(birthday)).describedAs("even his own date of birth").contains("household_dormant")
    }

    /** The case the owner named: a new phone number mid-dormancy does not lock anyone out. */
    @Test
    fun `changing your phone number while the household is dormant keeps you in it`() {
        stepUp(ravi)
        val newPhone = uniquePhone()
        val challenge = post("/api/v1/auth/phone/request", ravi, mapOf("phone" to newPhone))
        assertThat(challenge.status()).describedAs(challenge.body).isEqualTo(HttpStatus.OK)
        val changed = post(
            "/api/v1/auth/phone/verify", ravi,
            mapOf(
                "phone" to newPhone, "code" to challenge.json().path("developmentCode").asText(),
                "requestId" to challenge.json().path("requestId").asText(),
            ),
        )
        assertThat(changed.status()).describedAs(changed.body).isEqualTo(HttpStatus.OK)

        val again = signIn(newPhone)
        assertThat(get("/api/v1/households/$householdId", again).status()).isEqualTo(HttpStatus.OK)
        assertThat(household(again, householdId).path("myRole").asText()).isEqualTo("admin")
        assertThat(get("/api/v1/households/$householdId/dormancy", again).json().path("dormant").asBoolean()).isTrue()
    }
}
