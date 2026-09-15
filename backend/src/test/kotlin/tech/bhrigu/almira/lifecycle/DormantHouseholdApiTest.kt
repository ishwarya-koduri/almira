package tech.bhrigu.almira.lifecycle

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestTemplate
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The owner's decision (docs/05 §12.7): never purge the last owner while the
 * household holds records. It goes dormant, the family is told, and an explicit
 * transfer comes before any purge.
 *
 * Each refusal is proven by what did NOT happen — the account, the membership
 * and the records still there; no household of her own made; no role changed —
 * not only by the refusal (docs/known-issues.md, "A guard runs before the action
 * it guards").
 */
@DisplayName("A dormant household: the last owner is never purged while it holds records")
class DormantHouseholdApiTest : LifecycleTestSupport() {

    @Autowired private lateinit var sweep: LifecycleSweep
    @Autowired private lateinit var completion: DepartureCompletion

    private lateinit var ishwarya: String
    private lateinit var ravi: String
    private lateinit var meera: String
    private lateinit var householdId: String
    private lateinit var ishwaryaMemberId: String
    private lateinit var raviMemberId: String
    private lateinit var herGold: String

    private val later: Instant get() = Instant.now().plus(Duration.ofDays(31))

    @BeforeEach
    fun setUp() {
        ishwarya = signIn()
        ravi = signIn()
        meera = signIn()
        val household = createHousehold(ishwarya, "Koduri", "private", "Ishwarya")
        householdId = household.path("id").asText()
        ishwaryaMemberId = household.path("myMemberId").asText()
        raviMemberId = addMember(ishwarya, householdId, "Ravi").path("id").asText()
        joinHousehold(ishwarya, householdId, raviMemberId, ravi, role = "admin")
        val meeraMemberId = addMember(ishwarya, householdId, "Meera").path("id").asText()
        joinHousehold(ishwarya, householdId, meeraMemberId, meera, role = "viewer")
        herGold = capture(ishwarya, householdId, "gold_physical", "Ishwarya's private gold", BigDecimal(300_000))
            .path("id").asText()
        capture(ishwarya, householdId, "gold_physical", "Family gold", BigDecimal(900_000), visibility = "household")
    }

    private fun requestClosure(token: String) {
        stepUp(token)
        post("/api/v1/me/closure", token).also { assertThat(it.status()).describedAs(it.body).isEqualTo(HttpStatus.CREATED) }
    }

    private fun count(sql: String, vararg args: Any): Int = db.queryForObject(sql, Int::class.java, *args)!!

    private fun openDormancies() = count(
        "select count(*) from household_dormancies where household_id = ?::uuid and ended_at is null", householdId,
    )

    private fun dormancy(token: String) = get("/api/v1/households/$householdId/dormancy", token)

    private fun accept(token: String) = post("/api/v1/households/$householdId/dormancy/accept", token)

    private fun role(token: String) = household(token, householdId).path("myRole").asText()

    /** Every message of this template written for this user, on any channel. */
    private fun messages(userId: String, template: String, inApp: Boolean) = count(
        "select count(*) from outbound_messages where user_id = ?::uuid and template = ? and (channel = 'in_app') = ?",
        userId, template, inApp,
    )

    @Test
    fun `the purge of the last owner waits - the household goes dormant, and nothing of hers there is erased`() {
        // A household of her own too, which nobody else signs in to: that part of the closure is safe.
        val solo = createHousehold(ishwarya, "Just me", "private", "Ishwarya").path("id").asText()
        capture(ishwarya, solo, "gold_physical", "Solo gold", BigDecimal(1))
        val herId = userId(ishwarya)
        requestClosure(ishwarya)

        val result = sweep.run(later)
        assertThat(result.closuresHeld).describedAs("other suites' closures may be due too").isGreaterThanOrEqualTo(1)

        assertThat(count("select count(*) from users where id = ?::uuid", herId)).describedAs("her account").isEqualTo(1)
        assertThat(
            count(
                "select count(*) from household_memberships where household_id = ?::uuid and user_id = ?::uuid and status = 'active' and role = 'owner'",
                householdId, herId,
            ),
        ).describedAs("her membership, still the owner").isEqualTo(1)
        assertThat(count("select count(*) from investments where id = ?::uuid", herGold)).describedAs("her private gold").isEqualTo(1)
        assertThat(count("select count(*) from account_closures where user_id = ?::uuid and purged_at is null and cancelled_at is null", herId))
            .describedAs("the closure is still pending").isEqualTo(1)
        assertThat(count("select count(*) from households where id = ?::uuid", solo))
            .describedAs("the household nobody else signs in to is erased as she asked").isZero()

        assertThat(
            db.queryForMap(
                "select reason, closure_id is not null as has_closure, ended_at from household_dormancies where household_id = ?::uuid",
                householdId,
            ),
        ).containsEntry("reason", "owner_closing_account").containsEntry("has_closure", true).containsEntry("ended_at", null)
        assertThat(count("select count(*) from activity_log where action = 'household.dormancy.open' and household_id = ?::uuid", householdId))
            .isEqualTo(1)
        assertThat(count("select count(*) from activity_log where action = 'account.purge.held'")).isGreaterThanOrEqualTo(1)

        // Told in the app and through the outbox, in words that say nothing of what she held.
        listOf(ravi, meera).map { userId(it) }.forEach { other ->
            assertThat(messages(other, "lifecycle.household.dormant", inApp = true)).isEqualTo(1)
            assertThat(messages(other, "lifecycle.household.dormant", inApp = false)).isGreaterThanOrEqualTo(1)
        }
        assertThat(messages(herId, "lifecycle.household.dormant.you", inApp = true)).isEqualTo(1)
        val title = db.queryForObject(
            "select title from outbound_messages where user_id = ?::uuid and template = 'lifecycle.household.dormant' and channel = 'in_app'",
            String::class.java, userId(ravi),
        )
        assertThat(title).isEqualTo("Koduri needs someone to run it").doesNotContain("gold").doesNotContain("clos")

        // The sweep runs every hour. It does not tell anyone twice, or do anything more.
        sweep.run(later.plus(Duration.ofHours(1)))
        assertThat(messages(userId(ravi), "lifecycle.household.dormant", inApp = true)).isEqualTo(1)
        assertThat(count("select count(*) from users where id = ?::uuid", herId)).isEqualTo(1)
        assertThat(openDormancies()).isEqualTo(1)
    }

    @Test
    fun `while dormant, everyone sees what they saw, owner and admin writes are refused in words, and the handbook and download work`() {
        requestClosure(ishwarya)
        sweep.run(later)

        assertThat(household(ravi, householdId).path("dormant").asBoolean()).isTrue()
        val view = dormancy(meera).json()
        assertThat(view.path("dormant").asBoolean()).isTrue()
        assertThat(view.path("reason").asText()).isEqualTo("owner_closing_account")
        assertThat(view.path("ownerName").asText()).isEqualTo("Ishwarya")
        assertThat(view.path("canAccept").asBoolean()).isTrue()

        val invitations = count("select count(*) from invitations where household_id = ?::uuid", householdId)
        val invite = post(
            "/api/v1/households/$householdId/invitations", ravi,
            mapOf("phone" to uniquePhone(), "role" to "viewer"),
        )
        assertThat(invite.status()).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(invite.errorCode()).isEqualTo("household_dormant")
        assertThat(invite.json().path("error").path("message").asText()).contains("takes it on").contains("download everything")
        assertThat(count("select count(*) from invitations where household_id = ?::uuid", householdId)).isEqualTo(invitations)

        val renamed = patch("/api/v1/households/$householdId", ravi, mapOf("name" to "Renamed"))
        assertThat(renamed.errorCode()).isEqualTo("household_dormant")
        assertThat(household(ravi, householdId).path("name").asText()).isEqualTo("Koduri")

        // The capability function says the same, so no path around the service gets further.
        assertThat(asUser(userId(ravi), "select app.can_administer_household('$householdId'::uuid)")).isFalse()
        assertThat(asUser(userId(ravi), "select app.can_write_household('$householdId'::uuid)")).isTrue()

        // What a member adds for themselves is not an owner's business: it still works.
        capture(ravi, householdId, "gold_physical", "Ravi's coins", BigDecimal(10_000))

        val seen = get("/api/v1/households/$householdId/investments", ravi).json().map { it.path("title").asText() }
        assertThat(seen).contains("Family gold", "Ravi's coins").doesNotContain("Ishwarya's private gold")
        assertThat(get("/api/v1/households/$householdId/investments/$herGold", ravi).status()).isEqualTo(HttpStatus.NOT_FOUND)

        assertThat(get("/api/v1/households/$householdId/continuity/handbook", meera).status()).isEqualTo(HttpStatus.OK)
        stepUp(meera)
        val http = RestTemplate(org.springframework.http.client.JdkClientHttpRequestFactory())
        val zip = http.exchange(
            url("/api/v1/me/export"), HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { setBearerAuth(meera) }), ByteArray::class.java,
        )
        assertThat(zip.statusCode.value()).isEqualTo(200)
        assertThat(zip.body!!.copyOf(2)).isEqualTo(byteArrayOf(0x50, 0x4b))
    }

    @Test
    fun `an adult member takes it on with a step-up, it is audited, and only then does the next sweep finish the purge`() {
        val herId = userId(ishwarya)
        requestClosure(ishwarya)
        sweep.run(later)

        // Without a step-up nothing changes: not the role, not the dormancy.
        assertThat(accept(ravi).errorCode()).isEqualTo("step_up_required")
        assertThat(role(ravi)).isEqualTo("admin")
        assertThat(openDormancies()).isEqualTo(1)
        assertThat(count("select count(*) from activity_log where action = 'household.ownership.accept' and household_id = ?::uuid", householdId))
            .isZero()

        stepUp(ravi)
        val accepted = accept(ravi)
        assertThat(accepted.status()).describedAs(accepted.body).isEqualTo(HttpStatus.OK)
        assertThat(accepted.json().path("dormant").asBoolean()).isFalse()
        assertThat(role(ravi)).isEqualTo("owner")
        assertThat(household(meera, householdId).path("dormant").asBoolean()).isFalse()
        assertThat(
            db.queryForMap(
                "select ended_reason, transferred_to::text as to from household_dormancies where household_id = ?::uuid",
                householdId,
            ),
        ).containsEntry("ended_reason", "transferred").containsEntry("to", userId(ravi))
        assertThat(count("select count(*) from activity_log where action = 'household.ownership.accept' and household_id = ?::uuid and actor_user_id = ?::uuid", householdId, userId(ravi)))
            .isEqualTo(1)
        assertThat(count("select count(*) from activity_log where action = 'household.dormancy.end' and household_id = ?::uuid", householdId))
            .isEqualTo(1)
        assertThat(messages(userId(meera), "lifecycle.household.ownership_accepted", inApp = true)).isEqualTo(1)

        // Taking it on is capability, never sight: her private gold is still hers alone.
        assertThat(get("/api/v1/households/$householdId/investments/$herGold", ravi).status()).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(get("/api/v1/households/$householdId/investments", ravi).json().map { it.path("title").asText() })
            .doesNotContain("Ishwarya's private gold")

        // Now the purge she asked for goes ahead, as docs/05 §12.1 says.
        val result = sweep.run(later.plus(Duration.ofHours(1)))
        assertThat(result.accountsErased).isGreaterThanOrEqualTo(1)
        assertThat(count("select count(*) from users where id = ?::uuid", herId)).isZero()
        assertThat(count("select count(*) from investments where id = ?::uuid", herGold)).describedAs("solely hers, erased").isZero()
        assertThat(household(meera, householdId).path("name").asText()).describedAs("the household carries on").isEqualTo("Koduri")
        assertThat(role(ravi)).isEqualTo("owner")
    }

    @Test
    fun `with nobody who may take it on it stays dormant, and is never purged`() {
        // Only an advisor and a restricted member are left besides her.
        val hid = createHousehold(ishwarya, "Rao", "private", "Ishwarya").path("id").asText()
        capture(ishwarya, hid, "gold_physical", "Rao gold", BigDecimal(5))
        val advisor = signIn()
        joinHousehold(ishwarya, hid, addMember(ishwarya, hid, "CA").path("id").asText(), advisor, role = "advisor")
        val teen = signIn()
        joinHousehold(ishwarya, hid, addMember(ishwarya, hid, "Teen").path("id").asText(), teen, role = "restricted")
        val preview = get("/api/v1/me/closure/preview", ishwarya).json()
        // Rao has no successor or admin, so a closure is refused up front; that promise is unchanged.
        assertThat(preview.path("blockers").map { it.path("householdId").asText() }).contains(hid)

        // The case the review found: the only admin was gone by purge time. Here the
        // closure is written as the sweep would find it, past the request-time check.
        val herId = userId(ishwarya)
        db.update(
            "insert into account_closures (user_id, requested_at, purge_after) values (?::uuid, now() - interval '31 days', now() - interval '1 day')",
            herId,
        )
        repeat(3) { sweep.run(Instant.now().plus(Duration.ofDays(400L * it))) }

        assertThat(count("select count(*) from users where id = ?::uuid", herId)).isEqualTo(1)
        assertThat(count("select count(*) from household_dormancies where household_id = ?::uuid and ended_at is null", hid)).isEqualTo(1)
        assertThat(count("select count(*) from investments where household_id = ?::uuid", hid)).isEqualTo(1)

        assertThat(get("/api/v1/households/$hid/dormancy", advisor).json().path("canAccept").asBoolean()).isFalse()
        stepUp(advisor)
        val refused = post("/api/v1/households/$hid/dormancy/accept", advisor)
        assertThat(refused.status()).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(refused.errorCode()).isEqualTo("ownership_not_eligible")
        stepUp(teen)
        assertThat(post("/api/v1/households/$hid/dormancy/accept", teen).errorCode()).isEqualTo("ownership_not_eligible")
        assertThat(household(advisor, hid).path("myRole").asText()).isEqualTo("advisor")

        // Advisors are not told; a restricted member cannot take it on, so is not asked to.
        assertThat(messages(userId(advisor), "lifecycle.household.dormant", inApp = true)).isZero()

        // Someone outside learns nothing, not even that there is a household.
        val outsider = signIn()
        assertThat(get("/api/v1/households/$hid/dormancy", outsider).status()).isEqualTo(HttpStatus.NOT_FOUND)
        stepUp(outsider)
        assertThat(post("/api/v1/households/$hid/dormancy/accept", outsider).status()).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `when the owner comes back by keeping her account the dormancy ends, and nothing was erased`() {
        requestClosure(ishwarya)
        sweep.run(later)
        assertThat(openDormancies()).isEqualTo(1)

        // She is the owner whose going caused it: she cannot "take it on", she keeps it.
        assertThat(dormancy(ishwarya).json().path("canAccept").asBoolean()).isFalse()

        post("/api/v1/me/closure/cancel", ishwarya).also { assertThat(it.status()).isEqualTo(HttpStatus.OK) }
        assertThat(openDormancies()).isZero()
        assertThat(db.queryForObject("select ended_reason from household_dormancies where household_id = ?::uuid", String::class.java, householdId))
            .isEqualTo("owner_returned")
        assertThat(household(ishwarya, householdId).path("dormant").asBoolean()).isFalse()
        assertThat(role(ishwarya)).isEqualTo("owner")
        assertThat(count("select count(*) from investments where id = ?::uuid", herGold)).isEqualTo(1)
        assertThat(messages(userId(ravi), "lifecycle.household.running_again", inApp = true)).isEqualTo(1)

        sweep.run(later.plus(Duration.ofHours(1)))
        assertThat(count("select count(*) from users where id = ?::uuid", userId(ishwarya))).isEqualTo(1)
    }

    @Test
    fun `an owner's departure waits before step one - no household of her own, nothing moved, until someone takes it on`() {
        val herId = userId(ishwarya)
        stepUp(ishwarya)
        val started = post("/api/v1/households/$householdId/departures", ishwarya)
        assertThat(started.status()).describedAs(started.body).isEqualTo(HttpStatus.CREATED)
        val departureId = UUID.fromString(started.json().path("id").asText())
        val householdsBefore = count("select count(*) from household_memberships where user_id = ?::uuid", herId)

        assertThat(completion.complete(departureId, Instant.now().plus(Duration.ofDays(8)))).isNull()
        assertThat(count("select count(*) from household_memberships where user_id = ?::uuid", herId))
            .describedAs("no household of her own was made").isEqualTo(householdsBefore)
        assertThat(db.queryForObject("select destination_household_id from household_departures where id = ?", UUID::class.java, departureId))
            .isNull()
        assertThat(db.queryForObject("select household_id::text from investments where id = ?::uuid", String::class.java, herGold))
            .describedAs("her gold did not move").isEqualTo(householdId)
        assertThat(db.queryForObject("select reason from household_dormancies where household_id = ?::uuid and ended_at is null", String::class.java, householdId))
            .isEqualTo("owner_leaving")
        assertThat(messages(userId(meera), "lifecycle.household.dormant", inApp = true)).isEqualTo(1)

        stepUp(meera)
        assertThat(accept(meera).status()).isEqualTo(HttpStatus.OK)
        assertThat(role(meera)).isEqualTo("owner")

        val done = completion.complete(departureId, Instant.now().plus(Duration.ofDays(8)))
        assertThat(done).isNotNull
        assertThat(db.queryForObject("select household_id::text from investments where id = ?::uuid", String::class.java, herGold))
            .describedAs("carried out once someone took it on").isNotEqualTo(householdId)
        assertThat(get("/api/v1/households/$householdId/investments/$herGold", meera).status()).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `a memorial on the last owner makes it dormant, nobody takes it on for a week, and I'm here ends it`() {
        stepUp(ravi)
        post("/api/v1/households/$householdId/members/$ishwaryaMemberId/memorial", ravi)
            .also { assertThat(it.status()).describedAs(it.body).isEqualTo(HttpStatus.OK) }
        assertThat(openDormancies()).isEqualTo(1)
        assertThat(messages(userId(meera), "lifecycle.household.dormant", inApp = true)).isEqualTo(1)

        val waiting = dormancy(ravi).json()
        assertThat(waiting.path("reason").asText()).isEqualTo("owner_passed_away")
        assertThat(waiting.path("canAccept").asBoolean()).isFalse()
        assertThat(accept(ravi).errorCode()).isEqualTo("dormancy_not_yet")
        assertThat(role(ravi)).isEqualTo("admin")
        // An admin cannot use the week to do what needs one.
        assertThat(patch("/api/v1/households/$householdId", ravi, mapOf("name" to "Ours now")).errorCode())
            .isEqualTo("household_dormant")

        delete("/api/v1/households/$householdId/members/$ishwaryaMemberId/memorial", ishwarya)
            .also { assertThat(it.status()).describedAs(it.body).isEqualTo(HttpStatus.OK) }
        assertThat(openDormancies()).isZero()
        assertThat(household(ravi, householdId).path("dormant").asBoolean()).isFalse()

        // Marked again (the step-up still stands), and a week on: now an adult takes it on.
        post("/api/v1/households/$householdId/members/$ishwaryaMemberId/memorial", ravi)
        db.update(
            "update household_dormancies set accept_from = now() - interval '1 minute' where household_id = ?::uuid and ended_at is null",
            householdId,
        )
        stepUp(meera)
        assertThat(accept(meera).status()).isEqualTo(HttpStatus.OK)
        assertThat(role(meera)).isEqualTo("owner")
        assertThat(get("/api/v1/households/$householdId/investments/$herGold", meera).status()).isEqualTo(HttpStatus.NOT_FOUND)
    }
}
