package tech.almira.lifecycle

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestTemplate
import tech.almira.document.DocumentStorage
import tech.almira.security.SessionRevocationCache
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

@DisplayName("Closing an account: a preview, thirty days, and then a purge that keeps its promises")
class AccountClosureApiTest : LifecycleTestSupport() {

    @Autowired private lateinit var purge: AccountPurge
    @Autowired private lateinit var sweep: LifecycleSweep
    @Autowired private lateinit var storage: DocumentStorage
    @Autowired private lateinit var revocations: SessionRevocationCache
    @Autowired @Qualifier("ownerDataSource") private lateinit var ownerDataSource: javax.sql.DataSource

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
        // Recorded only in his name, but shared with the household: the household's too.
        val raviShared = capture(
            ravi, householdId, "gold_physical", "Ravi's household gold", BigDecimal(50_000), visibility = "household",
        )
        // Ishwarya's will names Ravi as executor, with a contact card that has his number.
        val card = db.queryForObject(
            "insert into contacts (household_id, name, phone) values (?::uuid, 'Ravi K', '+919800000001') returning id",
            UUID::class.java, householdId,
        )!!
        val will = db.queryForObject(
            "insert into estate_documents (household_id, member_id, kind, title) values (?::uuid, ?::uuid, 'will', 'Her will') returning id",
            UUID::class.java, householdId, ishwaryaMemberId,
        )!!
        db.update(
            """
            insert into estate_roles (estate_document_id, role, member_id, contact_id, note)
            values (?::uuid, 'executor', ?::uuid, ?::uuid, 'Ravi keeps the locker key; he and I disagreed about the flat')
            """.trimIndent(),
            will, raviMemberId, card,
        )
        db.update(
            """
            insert into estate_beneficiaries (estate_document_id, member_id, relationship, note, share_pct)
            values (?::uuid, ?::uuid, 'brother-in-law', 'Half to the temple fund', 50)
            """.trimIndent(),
            will, raviMemberId,
        )
        // Ishwarya's record that nominates Ravi: hers, and it should still say who.
        put(
            "/api/v1/households/$householdId/investments/${ishGold.path("id").asText()}/nominees", ishwarya,
            mapOf("nominees" to listOf(mapOf("memberId" to raviMemberId, "sharePct" to 100))),
        )
        return mapOf(
            "raviGold" to raviGold.path("id").asText(), "flat" to flat.path("id").asText(),
            "ishGold" to ishGold.path("id").asText(), "loan" to loan.path("id").asText(),
            "raviShared" to raviShared.path("id").asText(), "will" to will.toString(), "card" to card.toString(),
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
        assertThat(joint.path("detail").asText()).contains("Ishwarya").contains("Former member")

        // The same split as a dormant household's, though this one is not (owner's decision, 2026-09-15).
        assertThat(erased).doesNotContain("Ravi's household gold")
        val shared = preview.path("stays").first { it.path("title").asText() == "Ravi's household gold" }
        assertThat(shared.path("detail").asText()).contains("Former member")
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

        // One rule for every erasure (owner's decision, 2026-09-15): what he shared, and his
        // part of the joint record, stay — held by a former member with no name of anyone.
        fun heldBy(id: String) = db.queryForList(
            """
            select m.display_name, m.former_since is not null as former, m.user_id is null as unlinked, o.share_pct
              from investment_ownerships o join members m on m.id = o.member_id
             where o.investment_id = ?::uuid order by o.share_pct
            """.trimIndent(),
            UUID.fromString(id),
        )
        assertThat(exists("investments", ids.getValue("raviShared"))).isTrue()
        assertThat(heldBy(ids.getValue("raviShared")).single())
            .containsEntry("display_name", "Former member").containsEntry("former", true).containsEntry("unlinked", true)
        val joint = heldBy(ids.getValue("flat"))
        assertThat(joint).hasSize(2)
        assertThat(joint[0]).containsEntry("display_name", "Former member").containsEntry("former", true)
        assertThat(joint[0]["share_pct"] as BigDecimal).isEqualByComparingTo("40")
        assertThat(joint[1]["display_name"]).isEqualTo("Ishwarya")
        assertThat(
            db.queryForObject(
                "select count(*) from members where household_id = ?::uuid and former_since is not null",
                Int::class.java, householdId,
            ),
        ).describedAs("one former member holds both").isEqualTo(1)

        // Her nomination still says who she nominated.
        assertThat(
            db.queryForObject(
                "select nominee_name from investment_nominees where investment_id = ?::uuid",
                String::class.java, UUID.fromString(ids.getValue("ishGold")),
            ),
        ).isEqualTo("Ravi")

        // Her will still says who she named, as the household wrote it — unlinked from him,
        // and with no way through it to his number. The card itself is the household's.
        assertThat(
            db.queryForMap(
                "select person_name, member_id is null as unlinked, contact_id is null as no_contact from estate_roles where estate_document_id = ?::uuid",
                UUID.fromString(ids.getValue("will")),
            ),
        ).containsEntry("person_name", "Ravi").containsEntry("unlinked", true).containsEntry("no_contact", true)
        assertThat(
            db.queryForObject("select count(*) from contacts where id = ?::uuid", Int::class.java, UUID.fromString(ids.getValue("card"))),
        ).describedAs("the card itself is contact data and goes (owner, 2026-09-16)").isZero()
        // Owner's ruling (2026-09-17): the words stay and he becomes "Former member"
        // in them, the same name his holder rows carry. Her will still says what to do.
        assertThat(
            db.queryForObject(
                "select note from estate_roles where estate_document_id = ?::uuid", String::class.java,
                UUID.fromString(ids.getValue("will")),
            ),
        ).isEqualTo("Former member keeps the locker key; he and I disagreed about the flat")
        // Text that does not name him is her own writing about her own will, and stays.
        assertThat(
            db.queryForMap(
                "select person_name, relationship, note from estate_beneficiaries where estate_document_id = ?::uuid",
                UUID.fromString(ids.getValue("will")),
            ),
        ).containsEntry("person_name", "Ravi").containsEntry("relationship", "brother-in-law")
            .containsEntry("note", "Half to the temple fund")

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

    /**
     * A name is a name, not a pattern. Somebody whose name carries brackets or a
     * dot would be read as a regular expression by the replacement that puts
     * "Former member" in their place, and could match the wrong words or none.
     */
    @Test
    fun `a name with brackets in it is replaced as written, and matches nothing else`() {
        val awkward = "A. (Ravi) K."
        val memberId = addMember(ishwarya, householdId, awkward).path("id").asText()
        val joiner = signIn()
        joinHousehold(ishwarya, householdId, memberId, joiner, role = "editor")
        val will = db.queryForObject(
            "insert into estate_documents (household_id, member_id, kind, title) values (?::uuid, ?::uuid, 'will', 'Her will') returning id",
            UUID::class.java, householdId, ishwaryaMemberId,
        )!!
        db.update(
            """
            insert into estate_roles (estate_document_id, role, member_id, note)
            values (?::uuid, 'executor', ?::uuid, 'A. (Ravi) K. has the keys. Ax (Ravi) Ky is somebody else.')
            """.trimIndent(),
            will, memberId,
        )

        stepUp(joiner)
        post("/api/v1/me/closure", joiner)
        assertThat(purge.purge(closureId(userId(joiner)), Instant.now().plus(Duration.ofDays(31)))).isNotNull

        assertThat(
            db.queryForMap(
                "select person_name, note from estate_roles where estate_document_id = ?::uuid", will,
            ),
        ).containsEntry("person_name", awkward)
            .containsEntry("note", "Former member has the keys. Ax (Ravi) Ky is somebody else.")
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
    fun `a departure he started or left through, or a memorial he marked, does not stop his erasure`() {
        val raviId = userId(ravi)
        val ishwaryaId = userId(ishwarya)
        val ammaMemberId = addMember(ishwarya, householdId, "Amma").path("id").asText()
        // He left another household once, taking his records into one of his own.
        val oldHome = createHousehold(ishwarya, "Old home", "private", "Ishwarya").path("id").asText()
        val hisOldMemberId = addMember(ishwarya, oldHome, "Ravi").path("id").asText()
        val hisOwn = createHousehold(ravi, "Ravi's own", "private", "Ravi").path("id").asText()
        val left = db.queryForObject(
            """
            insert into household_departures (household_id, member_id, user_id, started_by, started_by_admin,
                                              requested_at, effective_at, completed_at, destination_household_id)
            values (?::uuid, ?::uuid, ?::uuid, ?::uuid, false,
                    now() - interval '30 days', now() - interval '20 days', now() - interval '20 days', ?::uuid)
            returning id
            """.trimIndent(),
            UUID::class.java, oldHome, hisOldMemberId, raviId, raviId, hisOwn,
        )!!
        // As an admin he asked Ishwarya to leave, and it is still waiting.
        val asked = db.queryForObject(
            """
            insert into household_departures (household_id, member_id, user_id, started_by, started_by_admin,
                                              requested_at, effective_at)
            values (?::uuid, ?::uuid, ?::uuid, ?::uuid, true, now(), now() + interval '7 days')
            returning id
            """.trimIndent(),
            UUID::class.java, householdId, ishwaryaMemberId, ishwaryaId, raviId,
        )!!
        // He marked Amma as passed away, and reversed it.
        val memorial = db.queryForObject(
            """
            insert into member_memorials (household_id, member_id, marked_by, basis, reversed_at, reversed_by)
            values (?::uuid, ?::uuid, ?::uuid, 'admin', now(), ?::uuid)
            returning id
            """.trimIndent(),
            UUID::class.java, householdId, ammaMemberId, raviId, raviId,
        )!!

        // Nobody signed in can take a name off a departure the way the purge now does.
        val clearedBySomeone = runCatching {
            asUser(
                ishwaryaId,
                "with u as (update household_departures set started_by = null where id = '$asked' returning 1) " +
                    "select count(*) > 0 from u",
            )
        }
        assertThat(clearedBySomeone.exceptionOrNull()?.message).contains("cannot be changed")

        stepUp(ravi)
        post("/api/v1/me/closure", ravi)
        val closure = closureId(raviId)

        assertThat(purge.purge(closure, Instant.now().plus(Duration.ofDays(31)))).isNotNull
        assertThat(db.queryForObject("select count(*) from users where id = ?::uuid", Int::class.java, raviId)).isZero()
        assertThat(db.queryForObject("select count(*) from households where id = ?::uuid", Int::class.java, hisOwn)).isZero()
        assertThat(db.queryForObject("select count(*) from household_departures where id = ?", Int::class.java, left))
            .describedAs("his own departure goes with him").isZero()
        assertThat(
            db.queryForObject(
                "select started_by is null and started_by_admin and cancelled_at is null from household_departures where id = ?",
                Boolean::class.java, asked,
            ),
        ).describedAs("Ishwarya's departure stays, without his name").isTrue()
        assertThat(
            db.queryForObject(
                "select marked_by is null and reversed_by is null and reversed_at is not null from member_memorials where id = ?",
                Boolean::class.java, memorial,
            ),
        ).describedAs("the memorial stays, without his name").isTrue()
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
    @Test
    fun `bytes storage fails to delete after the purge stay queued and the next sweep deletes them`() {
        val gold = capture(ravi, householdId, "gold_physical", "Ravi's gold", BigDecimal(200_000))
        val http = RestTemplate(org.springframework.http.client.JdkClientHttpRequestFactory())
        val uploaded = http.exchange(
            url("/api/v1/households/$householdId/documents?docType=statement&entityType=investment&entityId=${gold.path("id").asText()}"),
            HttpMethod.POST,
            HttpEntity(
                LinkedMultiValueMap<String, Any>().apply {
                    add("file", object : ByteArrayResource("Ravi's will".toByteArray()) { override fun getFilename() = "will.txt" })
                },
                HttpHeaders().apply { contentType = MediaType.MULTIPART_FORM_DATA; setBearerAuth(ravi) },
            ),
            String::class.java,
        )
        val key = db.queryForObject(
            "select storage_key from documents where id = ?::uuid", String::class.java,
            mapper.readTree(uploaded.body).path("id").asText(),
        )!!
        stepUp(ravi)
        post("/api/v1/me/closure", ravi).also { assertThat(it.status()).describedAs(it.body).isEqualTo(HttpStatus.CREATED) }

        // Storage is down for the moment the purge deletes bytes.
        val down = object : DocumentStorage by storage {
            override fun delete(key: String) = throw java.io.UncheckedIOException(java.io.IOException("storage unavailable"))
        }
        val later = Instant.now().plus(Duration.ofDays(31))
        assertThat(AccountPurge(ownerDataSource, down, revocations).purge(closureId(userId(ravi)), later)).isNotNull
        assertThat(db.queryForObject("select count(*) from documents where storage_key = ?", Int::class.java, key)).isZero()
        assertThat(storage.get(key)).describedAs("the bytes are still stored").isNotEmpty()
        assertThat(
            db.queryForObject("select attempts from pending_storage_deletions where storage_key = ?", Int::class.java, key),
        ).describedAs("the key is recorded, with the failed attempt").isEqualTo(1)

        sweep.run(later)
        assertThat(db.queryForObject("select count(*) from pending_storage_deletions where storage_key = ?", Int::class.java, key))
            .isZero()
        assertThatThrownBy { storage.get(key) }.describedAs("the bytes are gone").isInstanceOf(Exception::class.java)
    }
}
