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
import java.time.LocalDate
import java.util.UUID

/**
 * A household whose last owner goes while it holds records (docs/05 §12.7).
 *
 * The owner's answers: the household goes dormant and the family is told; the
 * departed person's own data is erased on schedule all the same, while what
 * they shared with the household survives under a former member (D8); and while
 * it is dormant only membership is frozen (D7).
 *
 * Each refusal is proven by what did NOT happen — no invitation, no member, no
 * departure, no role changed — not only by the refusal (docs/known-issues.md,
 * "A guard runs before the action it guards").
 */
@DisplayName("A dormant household: erased on schedule, membership frozen, taken on explicitly")
class DormantHouseholdApiTest : LifecycleTestSupport() {

    @Autowired private lateinit var sweep: LifecycleSweep
    @Autowired private lateinit var completion: DepartureCompletion

    private lateinit var ishwarya: String
    private lateinit var ishwaryaPhone: String
    private lateinit var ishwaryaName: String
    private lateinit var ravi: String
    private lateinit var meera: String
    private lateinit var householdId: String
    private lateinit var ishwaryaMemberId: String
    private lateinit var raviMemberId: String
    private lateinit var meeraMemberId: String
    private lateinit var herGold: String
    private lateinit var familyGold: String
    private lateinit var jointGold: String

    private val later: Instant get() = Instant.now().plus(Duration.ofDays(31))

    @BeforeEach
    fun setUp() {
        ishwaryaPhone = uniquePhone()
        // Unique, so a scan of every table for it finds only this test's rows.
        ishwaryaName = "Ishwarya-" + UUID.randomUUID().toString().take(8)
        ishwarya = signIn(ishwaryaPhone)
        ravi = signIn()
        meera = signIn()
        val household = createHousehold(ishwarya, "Koduri", "private", ishwaryaName)
        householdId = household.path("id").asText()
        ishwaryaMemberId = household.path("myMemberId").asText()
        raviMemberId = addMember(ishwarya, householdId, "Ravi").path("id").asText()
        joinHousehold(ishwarya, householdId, raviMemberId, ravi, role = "admin")
        meeraMemberId = addMember(ishwarya, householdId, "Meera").path("id").asText()
        joinHousehold(ishwarya, householdId, meeraMemberId, meera, role = "viewer")
        herGold = capture(ishwarya, householdId, "gold_physical", "Her private gold", BigDecimal(300_000))
            .path("id").asText()
        familyGold = capture(ishwarya, householdId, "gold_physical", "Family gold", BigDecimal(900_000), visibility = "household")
            .path("id").asText()
        jointGold = capture(
            ishwarya, householdId, "gold_physical", "Joint gold", BigDecimal(400_000), visibility = "private",
            owners = listOf(
                mapOf("memberId" to ishwaryaMemberId, "sharePct" to 60),
                mapOf("memberId" to raviMemberId, "sharePct" to 40),
            ),
        ).path("id").asText()
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

    /** Every base table, with how many of its rows mention any of [needles] anywhere, as text. */
    private fun rowsMentioning(vararg needles: String): Map<String, Int> =
        db.queryForList(
            "select format('%I.%I', table_schema, table_name) from information_schema.tables " +
                "where table_schema in ('public', 'ops') and table_type = 'BASE TABLE' and table_name <> 'flyway_schema_history'",
            String::class.java,
        ).associateWith { table ->
            val where = needles.joinToString(" or ") { "x::text ilike ?" }
            count("select count(*) from $table x where $where", *needles.map { "%$it%" }.toTypedArray())
        }.filterValues { it > 0 }

    @Test
    fun `the last owner's closure is carried out on schedule - her data is erased, and what she shared stays with the household`() {
        // A household of her own too, which nobody else signs in to.
        val solo = createHousehold(ishwarya, "Just me", "private", ishwaryaName).path("id").asText()
        capture(ishwarya, solo, "gold_physical", "Solo gold", BigDecimal(1))
        val herId = userId(ishwarya)
        consentToMessages(ishwarya)
        // The dormancy notice is not on the essential list, so it goes beyond the app
        // only to people who said yes to messages (V125).
        consentToMessages(ravi)
        consentToMessages(meera)
        val auditBefore = count("select count(*) from activity_log where household_id = ?::uuid", householdId)
        requestClosure(ishwarya)

        val result = sweep.run(later)
        assertThat(result.householdsLeftDormant).describedAs("other suites' closures may be due too").isGreaterThanOrEqualTo(1)

        // Her right did not wait on the household: the account is gone, on the day.
        assertThat(count("select count(*) from users where id = ?::uuid", herId)).describedAs("her account").isZero()
        assertThat(count("select count(*) from user_sessions where user_id = ?::uuid", herId)).isZero()
        assertThat(
            count(
                "select count(*) from account_closures c join household_dormancies d on d.closure_id = c.id " +
                    "where d.household_id = ?::uuid and c.purged_at is not null and c.user_id is null",
                householdId,
            ),
        ).describedAs("the closure is recorded as carried out").isEqualTo(1)
        assertThat(count("select count(*) from households where id = ?::uuid", solo)).isZero()

        // Nothing of hers is left anywhere: every table, every column, as text. The one
        // exception is the audit log's own rule (docs/05 §12.1, DPDP Rules 8(3)): its
        // security rows are kept for a year, and a row about her account keeps that
        // account's id as its subject — an id nothing else holds any more, with no
        // number, name or content beside it.
        val phone = ishwaryaPhone.removePrefix("+")
        assertThat(rowsMentioning(herId, phone, ishwaryaName).filterKeys { it != "public.activity_log" })
            .describedAs("rows still naming her, her number or her id").isEmpty()
        assertThat(rowsMentioning(phone, ishwaryaName)).describedAs("her number or her name, even in the log").isEmpty()
        assertThat(
            count(
                "select count(*) from activity_log x where x::text ilike ? " +
                    "and not (entity_type = 'user' and entity_id = ?::uuid and actor_user_id is null and household_id is null " +
                    "and coalesce(diff::text, '') not ilike ?)",
                "%$herId%", herId, "%$herId%",
            ),
        ).describedAs("her id in the log only as the subject of a kept security row").isZero()

        // What was private to her alone is erased, not exposed.
        assertThat(count("select count(*) from investments where id = ?::uuid", herGold)).describedAs("private to her").isZero()
        assertThat(get("/api/v1/households/$householdId/investments/$herGold", ravi).status()).isEqualTo(HttpStatus.NOT_FOUND)

        // What she shared stays, readable by those it was shared with, held by a former member.
        val seen = get("/api/v1/households/$householdId/investments", meera).json().map { it.path("title").asText() }
        assertThat(seen).contains("Family gold").doesNotContain("Her private gold", "Joint gold")
        assertThat(get("/api/v1/households/$householdId/investments/$familyGold", ravi).status()).isEqualTo(HttpStatus.OK)
        assertThat(get("/api/v1/households/$householdId/investments/$jointGold", ravi).status())
            .describedAs("the joint gold: its other holder still sees it").isEqualTo(HttpStatus.OK)
        assertThat(get("/api/v1/households/$householdId/investments/$jointGold", meera).status())
            .describedAs("and nobody else does").isEqualTo(HttpStatus.NOT_FOUND)
        val former = db.queryForMap(
            """
            select m.display_name, m.user_id, m.date_of_birth, m.former_since is not null as former,
                   (select share_pct from investment_ownerships f where f.investment_id = ?::uuid and f.member_id = m.id) as joint_share
              from members m join investment_ownerships o on o.member_id = m.id and o.investment_id = ?::uuid
            """.trimIndent(),
            jointGold, familyGold,
        )
        assertThat(former).containsEntry("display_name", "Former member").containsEntry("user_id", null)
            .containsEntry("date_of_birth", null).containsEntry("former", true)
        assertThat((former["joint_share"] as BigDecimal).toInt()).describedAs("her part of the joint gold stays with it").isEqualTo(60)
        assertThat(members(ravi, householdId).map { it.path("displayName").asText() }).contains("Former member")

        // The household is dormant, and the log of what happened in it is kept, without her.
        assertThat(
            db.queryForMap(
                "select reason, closure_id is not null as has_closure, owner_user_id, ended_at from household_dormancies where household_id = ?::uuid",
                householdId,
            ),
        ).containsEntry("reason", "owner_closing_account").containsEntry("has_closure", true)
            .containsEntry("owner_user_id", null).containsEntry("ended_at", null)
        assertThat(count("select count(*) from activity_log where household_id = ?::uuid", householdId))
            .describedAs("the audit rows from before are kept (a year, DPDP Rules 8(3))").isGreaterThan(auditBefore)
        assertThat(count("select count(*) from activity_log where action = 'member.erased' and household_id = ?::uuid and actor_user_id is null", householdId))
            .isEqualTo(1)
        assertThat(count("select count(*) from activity_log where action = 'household.dormancy.open' and household_id = ?::uuid", householdId))
            .isEqualTo(1)

        // Told in the app and through the outbox, in words that say nothing of what she held.
        listOf(ravi, meera).map { userId(it) }.forEach { other ->
            assertThat(messages(other, "lifecycle.household.dormant", inApp = true)).isEqualTo(1)
            assertThat(messages(other, "lifecycle.household.dormant", inApp = false)).isGreaterThanOrEqualTo(1)
        }
        val title = db.queryForObject(
            "select title from outbound_messages where user_id = ?::uuid and template = 'lifecycle.household.dormant' and channel = 'in_app'",
            String::class.java, userId(ravi),
        )
        assertThat(title).isEqualTo("Koduri needs someone to run it").doesNotContain("gold").doesNotContain("clos")
        assertThat(dormancy(ravi).json().path("ownerName").isTextual).describedAs("an erased person has no name here").isFalse()

        // The sweep runs every hour. It does not tell anyone twice, or do anything more.
        sweep.run(later.plus(Duration.ofHours(1)))
        assertThat(messages(userId(ravi), "lifecycle.household.dormant", inApp = true)).isEqualTo(1)
        assertThat(openDormancies()).isEqualTo(1)
        assertThat(get("/api/v1/households/$householdId/investments/$familyGold", meera).status()).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `during a memorial's week, reads, the handbook and the download work, an admin still renames, and membership changes are refused before they happen`() {
        val grandma = addMember(ishwarya, householdId, "Grandma").path("id").asText()
        val pending = post(
            "/api/v1/households/$householdId/invitations", ishwarya, mapOf("phone" to uniquePhone(), "role" to "viewer"),
        ).json().path("token").asText()
        val joiner = signIn()

        stepUp(ravi)
        post("/api/v1/households/$householdId/members/$ishwaryaMemberId/memorial", ravi)
            .also { assertThat(it.status()).describedAs(it.body).isEqualTo(HttpStatus.OK) }
        assertThat(openDormancies()).isEqualTo(1)
        assertThat(household(ravi, householdId).path("dormant").asBoolean()).isTrue()

        // Reads, the handbook and Download everything are untouched.
        assertThat(get("/api/v1/households/$householdId/investments", meera).json().map { it.path("title").asText() })
            .contains("Family gold").doesNotContain("Her private gold")
        assertThat(get("/api/v1/households/$householdId/continuity/handbook", meera).status()).isEqualTo(HttpStatus.OK)
        stepUp(meera)
        val zip = RestTemplate(org.springframework.http.client.JdkClientHttpRequestFactory()).exchange(
            url("/api/v1/me/export"), HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { setBearerAuth(meera) }), ByteArray::class.java,
        )
        assertThat(zip.statusCode.value()).isEqualTo(200)
        assertThat(zip.body!!.copyOf(2)).isEqualTo(byteArrayOf(0x50, 0x4b))

        // What an admin could do that is not membership stays open (D7).
        val renamed = patch("/api/v1/households/$householdId", ravi, mapOf("name" to "Koduri family"))
        assertThat(renamed.status()).describedAs(renamed.body).isEqualTo(HttpStatus.OK)
        assertThat(household(meera, householdId).path("name").asText()).isEqualTo("Koduri family")
        assertThat(patch("/api/v1/households/$householdId/members/$grandma", ravi, mapOf("displayName" to "Ammamma")).status())
            .isEqualTo(HttpStatus.OK)
        capture(ravi, householdId, "gold_physical", "Ravi's coins", BigDecimal(10_000))

        // Membership is frozen, and each refusal leaves nothing behind.
        val invitations = count("select count(*) from invitations where household_id = ?::uuid", householdId)
        val invite = post("/api/v1/households/$householdId/invitations", ravi, mapOf("phone" to uniquePhone(), "role" to "viewer"))
        assertThat(invite.errorCode()).isEqualTo("household_dormant")
        assertThat(invite.json().path("error").path("message").asText()).contains("download everything")
        assertThat(count("select count(*) from invitations where household_id = ?::uuid", householdId)).isEqualTo(invitations)

        val joined = post("/api/v1/invitations/accept", joiner, mapOf("token" to pending))
        assertThat(joined.errorCode()).describedAs(joined.body).isEqualTo("household_dormant")
        assertThat(count("select count(*) from household_memberships where household_id = ?::uuid and user_id = ?::uuid", householdId, userId(joiner)))
            .describedAs("nobody joined").isZero()
        assertThat(count("select count(*) from members where household_id = ?::uuid and user_id = ?::uuid", householdId, userId(joiner))).isZero()

        val people = count("select count(*) from members where household_id = ?::uuid and deleted_at is null", householdId)
        assertThat(addMember(ravi, householdId, "Cousin").path("error").path("code").asText()).isEqualTo("household_dormant")
        assertThat(delete("/api/v1/households/$householdId/members/$grandma", ravi).errorCode()).isEqualTo("household_dormant")
        assertThat(count("select count(*) from members where household_id = ?::uuid and deleted_at is null", householdId))
            .describedAs("nobody added or removed").isEqualTo(people)

        val asked = post("/api/v1/households/$householdId/departures", ravi, mapOf("memberId" to meeraMemberId))
        assertThat(asked.errorCode()).isEqualTo("household_dormant")
        assertThat(count("select count(*) from household_departures where household_id = ?::uuid", householdId)).isZero()

        val dob = patch("/api/v1/households/$householdId/members/$grandma", ravi, mapOf("dateOfBirth" to LocalDate.of(1950, 1, 1).toString()))
        assertThat(dob.errorCode()).isEqualTo("household_dormant")
        assertThat(db.queryForObject("select date_of_birth from members where id = ?::uuid", LocalDate::class.java, grandma)).isNull()
        assertThat(role(meera)).isEqualTo("viewer")

        // And nobody takes it on in the week a memorial can be corrected (his step-up still stands).
        assertThat(accept(ravi).errorCode()).isEqualTo("dormancy_not_yet")
        assertThat(role(ravi)).isEqualTo("admin")

        // "I'm here": the owner comes back, and membership is open again.
        delete("/api/v1/households/$householdId/members/$ishwaryaMemberId/memorial", ishwarya)
            .also { assertThat(it.status()).describedAs(it.body).isEqualTo(HttpStatus.OK) }
        assertThat(openDormancies()).isZero()
        assertThat(addMember(ravi, householdId, "Cousin").path("displayName").asText()).isEqualTo("Cousin")
    }

    @Test
    fun `an adult member takes it on with a step-up, and it is audited`() {
        requestClosure(ishwarya)
        sweep.run(later)

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

        // Taking it on is capability, never sight; and her private gold is not there to see.
        assertThat(get("/api/v1/households/$householdId/investments/$herGold", ravi).status()).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(get("/api/v1/households/$householdId/investments/$jointGold", meera).status()).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(addMember(ravi, householdId, "Cousin").path("displayName").asText()).isEqualTo("Cousin")
    }

    @Test
    fun `without a step-up the whole transfer fails atomically - nothing anywhere changes`() {
        requestClosure(ishwarya)
        sweep.run(later)
        val raviId = userId(ravi)
        val columns = db.queryForList(
            """
            select format('%I.%I', c.table_schema, c.table_name) as t, c.column_name as col
              from information_schema.columns c
              join information_schema.tables t on t.table_schema = c.table_schema and t.table_name = c.table_name
             where c.table_schema = 'public' and t.table_type = 'BASE TABLE'
               and c.column_name in ('household_id', 'user_id', 'actor_user_id') and c.data_type = 'uuid'
            """.trimIndent(),
        )

        // Every row of this household's and Ravi's, table by table, and the rows that decide it.
        fun snapshot(): Map<String, Any?> = columns.associate { row ->
            val id = if (row["col"] == "household_id") householdId else raviId
            "${row["t"]}.${row["col"]}" to count("select count(*) from ${row["t"]} where ${row["col"]} = ?::uuid", id)
        } + mapOf(
            "memberships" to db.queryForList(
                "select user_id::text, role, status from household_memberships where household_id = ?::uuid order by user_id",
                householdId,
            ).toString(),
            "dormancy" to db.queryForList(
                "select (to_jsonb(d) - 'id')::text as row from household_dormancies d where household_id = ?::uuid", householdId,
            ).toString(),
            "outbound" to count("select count(*) from outbound_messages"),
            "bodies" to count("select count(*) from outbound_message_bodies"),
            "audit" to count("select count(*) from activity_log"),
        )

        val before = snapshot()
        assertThat(before.keys.size).describedAs("the enumeration found the tables").isGreaterThan(40)
        val refused = accept(ravi)
        assertThat(refused.errorCode()).isEqualTo("step_up_required")
        assertThat(snapshot()).isEqualTo(before)
        assertThat(role(ravi)).isEqualTo("admin")
        assertThat(openDormancies()).isEqualTo(1)
        assertThat(count("select count(*) from activity_log where action = 'household.ownership.accept' and household_id = ?::uuid", householdId))
            .isZero()
    }

    @Test
    fun `with nobody who may take it on it stays dormant, and her own data is erased all the same`() {
        // Only an advisor and a restricted member are left besides her.
        val hid = createHousehold(ishwarya, "Rao", "private", "Ishwarya").path("id").asText()
        capture(ishwarya, hid, "gold_physical", "Rao gold", BigDecimal(5), visibility = "household")
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
        sweep.run(Instant.now())

        assertThat(count("select count(*) from users where id = ?::uuid", herId)).isZero()
        assertThat(count("select count(*) from household_dormancies where household_id = ?::uuid and ended_at is null", hid)).isEqualTo(1)
        assertThat(count("select count(*) from investments where household_id = ?::uuid", hid)).describedAs("the household's gold").isEqualTo(1)

        assertThat(get("/api/v1/households/$hid/dormancy", advisor).json().path("canAccept").asBoolean()).isFalse()
        stepUp(advisor)
        val refused = post("/api/v1/households/$hid/dormancy/accept", advisor)
        assertThat(refused.status()).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(refused.errorCode()).isEqualTo("ownership_not_eligible")
        stepUp(teen)
        assertThat(post("/api/v1/households/$hid/dormancy/accept", teen).errorCode()).isEqualTo("ownership_not_eligible")
        assertThat(household(advisor, hid).path("myRole").asText()).isEqualTo("advisor")
        assertThat(messages(userId(advisor), "lifecycle.household.dormant", inApp = true)).isZero()

        // Someone outside learns nothing, not even that there is a household.
        val outsider = signIn()
        assertThat(get("/api/v1/households/$hid/dormancy", outsider).status()).isEqualTo(HttpStatus.NOT_FOUND)
        stepUp(outsider)
        assertThat(post("/api/v1/households/$hid/dormancy/accept", outsider).status()).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `an owner's departure waits before step one, the owner runs nothing meanwhile, and cancelling it ends it`() {
        val herId = userId(ishwarya)
        stepUp(ishwarya)
        val started = post("/api/v1/households/$householdId/departures", ishwarya)
        assertThat(started.status()).describedAs(started.body).isEqualTo(HttpStatus.CREATED)
        val departureId = UUID.fromString(started.json().path("id").asText())
        val householdsBefore = count("select count(*) from household_memberships where user_id = ?::uuid", herId)

        assertThat(completion.complete(departureId, Instant.now().plus(Duration.ofDays(8)))).isNull()
        assertThat(count("select count(*) from household_memberships where user_id = ?::uuid", herId))
            .describedAs("no household of her own was made").isEqualTo(householdsBefore)
        assertThat(db.queryForObject("select household_id::text from investments where id = ?::uuid", String::class.java, herGold))
            .describedAs("her gold did not move").isEqualTo(householdId)
        assertThat(db.queryForObject("select reason from household_dormancies where household_id = ?::uuid and ended_at is null", String::class.java, householdId))
            .isEqualTo("owner_leaving")
        assertThat(messages(userId(meera), "lifecycle.household.dormant", inApp = true)).isEqualTo(1)
        assertThat(messages(herId, "lifecycle.household.dormant.you", inApp = true)).isEqualTo(1)

        // She is the owner whose going caused it: she cannot take it on, or run it meanwhile.
        assertThat(dormancy(ishwarya).json().path("canAccept").asBoolean()).isFalse()
        val renamed = patch("/api/v1/households/$householdId", ishwarya, mapOf("name" to "Mine"))
        assertThat(renamed.errorCode()).isEqualTo("household_dormant")
        assertThat(household(meera, householdId).path("name").asText()).isEqualTo("Koduri")

        post("/api/v1/households/$householdId/departures/$departureId/cancel", ishwarya)
            .also { assertThat(it.status()).describedAs(it.body).isEqualTo(HttpStatus.OK) }
        assertThat(openDormancies()).isZero()
        assertThat(db.queryForObject("select ended_reason from household_dormancies where household_id = ?::uuid", String::class.java, householdId))
            .isEqualTo("owner_returned")
        assertThat(role(ishwarya)).isEqualTo("owner")
        assertThat(messages(userId(ravi), "lifecycle.household.running_again", inApp = true)).isEqualTo(1)

        // Taken on after all: the departure is carried out once someone has.
        val again = UUID.fromString(post("/api/v1/households/$householdId/departures", ishwarya).json().path("id").asText())
        assertThat(completion.complete(again, Instant.now().plus(Duration.ofDays(8)))).isNull()
        stepUp(meera)
        assertThat(accept(meera).status()).isEqualTo(HttpStatus.OK)
        assertThat(completion.complete(again, Instant.now().plus(Duration.ofDays(8)))).isNotNull
        assertThat(db.queryForObject("select household_id::text from investments where id = ?::uuid", String::class.java, herGold))
            .isNotEqualTo(householdId)
    }
}
