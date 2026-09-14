package tech.bhrigu.almira.lifecycle

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

@DisplayName("Closing an account: a preview, thirty days, and then a purge that keeps its promises")
class AccountClosureApiTest : LifecycleTestSupport() {

    @Autowired private lateinit var purge: AccountPurge
    @Autowired private lateinit var sweep: LifecycleSweep

    private lateinit var ishwarya: String
    private lateinit var ravi: String
    private lateinit var householdId: String
    private lateinit var ishwaryaMemberId: String
    private lateinit var raviMemberId: String

    @BeforeEach
    fun setUp() {
        ishwarya = signIn()
        ravi = signIn()
        val household = createHousehold(ishwarya, "Koduri", "private", "Ishwarya")
        householdId = household.path("id").asText()
        ishwaryaMemberId = household.path("myMemberId").asText()
        raviMemberId = addMember(ishwarya, householdId, "Ravi").path("id").asText()
        joinHousehold(ishwarya, householdId, raviMemberId, ravi, role = "admin")
    }

    private fun seed(): Map<String, String> {
        val raviGold = capture(ravi, householdId, "gold_physical", "Ravi's gold", BigDecimal(200_000))
        val flat = capture(
            ishwarya, householdId, "gold_physical", "Joint locker gold", BigDecimal(4_000_000),
            visibility = "household",
            owners = listOf(
                mapOf("memberId" to ishwaryaMemberId, "sharePct" to 60),
                mapOf("memberId" to raviMemberId, "sharePct" to 40),
            ),
        )
        val ishGold = capture(ishwarya, householdId, "gold_physical", "Ishwarya's gold", BigDecimal(300_000))
        val loan = post(
            "/api/v1/households/$householdId/liabilities", ravi,
            mapOf("title" to "Ravi's bike loan", "kind" to "other", "outstanding" to 80_000),
        ).json()
        // Ishwarya's record that nominates Ravi: hers, and it should still say who.
        put(
            "/api/v1/households/$householdId/investments/${ishGold.path("id").asText()}/nominees", ishwarya,
            mapOf("nominees" to listOf(mapOf("memberId" to raviMemberId, "sharePct" to 100))),
        )
        return mapOf(
            "raviGold" to raviGold.path("id").asText(), "flat" to flat.path("id").asText(),
            "ishGold" to ishGold.path("id").asText(), "loan" to loan.path("id").asText(),
        )
    }

    private fun put(path: String, token: String, body: Any) =
        call(org.springframework.http.HttpMethod.PUT, path, token, body)

    private fun closureId(userId: String): UUID = db.queryForObject(
        "select id from account_closures where user_id = ?::uuid and cancelled_at is null", UUID::class.java, userId,
    )!!

    @Test
    fun `the preview puts what is only yours on one side and what you share on the other`() {
        seed()
        val preview = get("/api/v1/me/closure/preview", ravi).json()

        val erased = preview.path("erased").map { it.path("title").asText() }
        assertThat(erased).contains("Ravi's gold", "Ravi's bike loan")
        assertThat(erased).doesNotContain("Joint locker gold", "Ishwarya's gold")

        val joint = preview.path("stays").first { it.path("title").asText() == "Joint locker gold" }
        assertThat(joint.path("detail").asText()).contains("Ishwarya")
        assertThat(preview.path("blockers")).isEmpty()
        assertThat(preview.path("waitDays").asInt()).isEqualTo(30)
        assertThat(preview.path("retention").asText()).contains("one year").contains("8(3)")
    }

    @Test
    fun `an owner who is the only one who runs a household is asked to hand it on first`() {
        val meera = signIn()
        val other = createHousehold(meera, "Meera's", "private", "Meera")
        val otherId = other.path("id").asText()
        val guest = signIn()
        val guestMember = addMember(meera, otherId, "Kiran").path("id").asText()
        joinHousehold(meera, otherId, guestMember, guest, role = "editor")

        val preview = get("/api/v1/me/closure/preview", meera).json()
        assertThat(preview.path("blockers").map { it.path("code").asText() }).containsExactly("owner_needs_successor")

        stepUp(meera)
        val refused = post("/api/v1/me/closure", meera)
        assertThat(refused.status()).isEqualTo(HttpStatus.CONFLICT)
        assertThat(refused.errorCode()).isEqualTo("closure_blocked")
    }

    @Test
    fun `asking needs a step-up, waits thirty days, and can be taken back`() {
        assertThat(post("/api/v1/me/closure", ravi).errorCode()).isEqualTo("step_up_required")
        stepUp(ravi)
        val requested = post("/api/v1/me/closure", ravi)
        assertThat(requested.status()).describedAs(requested.body).isEqualTo(HttpStatus.CREATED)
        val closesAfter = Instant.parse(requested.json().path("closesAfter").asText())
        assertThat(Duration.between(Instant.now(), closesAfter).toDays()).isBetween(29L, 30L)
        assertThat(get("/api/v1/me/closure", ravi).json().path("pending").asBoolean()).isTrue()
        assertThat(templatesFor(userId(ravi))).contains("lifecycle.closure.requested")

        val kept = post("/api/v1/me/closure/cancel", ravi)
        assertThat(kept.json().path("pending").asBoolean()).isFalse()
        assertThat(
            db.queryForObject(
                "select count(*) from activity_log where action in ('account.closure.request', 'account.closure.cancel') and actor_user_id = ?::uuid",
                Int::class.java, userId(ravi),
            ),
        ).isEqualTo(2)
    }

    @Test
    fun `after thirty days the purge erases what was listed and keeps what was promised`() {
        val ids = seed()
        val raviId = userId(ravi)
        val hisAuditRows = db.queryForList(
            "select id from activity_log where actor_user_id = ?::uuid", Long::class.java, raviId,
        )
        assertThat(hisAuditRows).isNotEmpty()

        stepUp(ravi)
        post("/api/v1/me/closure", ravi)
        val closure = closureId(raviId)

        assertThat(purge.purge(closure, Instant.now().plus(Duration.ofDays(29)))).describedAs("not yet").isNull()
        assertThat(get("/api/v1/me", ravi).status()).isEqualTo(HttpStatus.OK)

        val result = purge.purge(closure, Instant.now().plus(Duration.ofDays(31)))
        assertThat(result).isNotNull
        assertThat(result!!.householdsLeft).isEqualTo(1)

        fun exists(table: String, id: String) =
            db.queryForObject("select count(*) from $table where id = ?::uuid", Int::class.java, id) == 1

        assertThat(exists("users", raviId)).isFalse()
        assertThat(exists("members", raviMemberId)).isFalse()
        assertThat(exists("investments", ids.getValue("raviGold"))).isFalse()
        assertThat(exists("liabilities", ids.getValue("loan"))).isFalse()
        assertThat(exists("investments", ids.getValue("ishGold"))).isTrue()

        // The joint record stays, whole, with Ishwarya holding all of it.
        val shares = db.queryForList(
            "select share_pct from investment_ownerships where investment_id = ?::uuid",
            BigDecimal::class.java, UUID.fromString(ids.getValue("flat")),
        )
        assertThat(shares).hasSize(1)
        assertThat(shares.first()).isEqualByComparingTo("100")

        // Her nomination still says who she nominated.
        assertThat(
            db.queryForObject(
                "select nominee_name from investment_nominees where investment_id = ?::uuid",
                String::class.java, UUID.fromString(ids.getValue("ishGold")),
            ),
        ).isEqualTo("Ravi")

        // Security records are kept, without his name on them.
        assertThat(
            db.queryForObject(
                "select count(*) from activity_log where id in (${hisAuditRows.joinToString()}) and actor_user_id is null",
                Int::class.java,
            ),
        ).isEqualTo(hisAuditRows.size)
        assertThat(
            db.queryForObject(
                "select count(*) from activity_log where action = 'account.purge' and entity_id = ?::uuid",
                Int::class.java, closure,
            ),
        ).isEqualTo(1)
        assertThat(
            db.queryForObject(
                "select user_id is null and purged_at is not null from account_closures where id = ?::uuid",
                Boolean::class.java, closure,
            ),
        ).isTrue()

        // And his signed-in session ends now, not when its token expires.
        assertThat(get("/api/v1/me", ravi).status().value()).isEqualTo(401)

        // Ishwarya's household is untouched from where she sits.
        assertThat(get("/api/v1/households/$householdId/investments", ishwarya).json().map { it.path("title").asText() })
            .contains("Joint locker gold", "Ishwarya's gold")
    }

    @Test
    fun `a handbook edition, a lost-money check or a 2018 value he entered does not stop his erasure`() {
        val ids = seed()
        val raviId = userId(ravi)
        db.update(
            """
            insert into handbook_editions (household_id, created_by, edition, link_expires_at)
            values (?::uuid, ?::uuid, 1, now() + interval '365 days')
            """.trimIndent(),
            householdId, raviId,
        )
        db.update(
            """
            insert into lost_money_checks (household_id, member_id, portal, status, checked_on, created_by)
            values (?::uuid, ?::uuid, 'udgam', 'nothing', current_date, ?::uuid)
            """.trimIndent(),
            householdId, ishwaryaMemberId, raviId,
        )
        db.update(
            "insert into investment_fmv_2018 (investment_id, fmv_per_unit, recorded_by) values (?::uuid, 10, ?::uuid)",
            ids.getValue("ishGold"), raviId,
        )

        stepUp(ravi)
        post("/api/v1/me/closure", ravi)
        val closure = closureId(raviId)

        assertThat(purge.purge(closure, Instant.now().plus(Duration.ofDays(31)))).isNotNull
        assertThat(db.queryForObject("select count(*) from users where id = ?::uuid", Int::class.java, raviId)).isZero()
        assertThat(
            db.queryForObject("select count(*) from handbook_editions where household_id = ?::uuid", Int::class.java, householdId),
        ).describedAs("his editions go with him").isZero()
        assertThat(
            db.queryForObject(
                "select created_by is null from lost_money_checks where household_id = ?::uuid and member_id = ?::uuid",
                Boolean::class.java, householdId, ishwaryaMemberId,
            ),
        ).describedAs("the household's check stays, without his name").isTrue()
        assertThat(
            db.queryForObject(
                "select recorded_by is null from investment_fmv_2018 where investment_id = ?::uuid",
                Boolean::class.java, ids.getValue("ishGold"),
            ),
        ).isTrue()
    }

    @Test
    fun `an owner's household passes to the admin, and a household of one goes with its only person`() {
        stepUp(ishwarya)
        post("/api/v1/me/closure", ishwarya).also { assertThat(it.status()).describedAs(it.body).isEqualTo(HttpStatus.CREATED) }
        purge.purge(closureId(userId(ishwarya)), Instant.now().plus(Duration.ofDays(31)))
        assertThat(household(ravi, householdId).path("myRole").asText()).isEqualTo("owner")

        val solo = signIn()
        val soloHousehold = createHousehold(solo, "Just me", "private", "Solo").path("id").asText()
        capture(solo, soloHousehold, "gold_physical", "Solo gold", BigDecimal(1))
        val soloId = userId(solo)
        stepUp(solo)
        post("/api/v1/me/closure", solo)
        val logRows = db.queryForObject(
            "select count(*) from activity_log where household_id = ?::uuid", Int::class.java, soloHousehold,
        )!!

        val swept = sweep.run(Instant.now().plus(Duration.ofDays(31)))
        assertThat(swept.accountsErased).isGreaterThanOrEqualTo(1)
        assertThat(db.queryForObject("select count(*) from households where id = ?::uuid", Int::class.java, soloHousehold))
            .isZero()
        assertThat(db.queryForObject("select count(*) from users where id = ?::uuid", Int::class.java, soloId)).isZero()
        assertThat(
            db.queryForObject(
                "select count(*) from activity_log where household_id is null and entity_id = ?::uuid",
                Int::class.java, soloHousehold,
            ),
        ).describedAs("the household's own audit lines outlive it").isGreaterThanOrEqualTo(1)
        assertThat(logRows).isGreaterThanOrEqualTo(1)
    }
}
