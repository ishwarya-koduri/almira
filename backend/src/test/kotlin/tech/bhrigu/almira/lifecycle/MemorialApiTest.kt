package tech.bhrigu.almira.lifecycle

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.math.BigDecimal

@DisplayName("Marking someone as passed away: read-only, quiet, and undone by them")
class MemorialApiTest : LifecycleTestSupport() {

    private lateinit var ishwarya: String
    private lateinit var ravi: String
    private lateinit var meera: String
    private lateinit var householdId: String
    private lateinit var ishwaryaMemberId: String
    private lateinit var raviMemberId: String
    private lateinit var meeraMemberId: String

    @BeforeEach
    fun setUp() {
        ishwarya = signIn()
        ravi = signIn()
        meera = signIn()
        val household = createHousehold(ishwarya, "Koduri", "private", "Ishwarya")
        householdId = household.path("id").asText()
        ishwaryaMemberId = household.path("myMemberId").asText()
        raviMemberId = addMember(ishwarya, householdId, "Ravi").path("id").asText()
        joinHousehold(ishwarya, householdId, raviMemberId, ravi, role = "editor")
        meeraMemberId = addMember(ishwarya, householdId, "Meera").path("id").asText()
        joinHousehold(ishwarya, householdId, meeraMemberId, meera, role = "editor")
    }

    private fun mark(token: String, memberId: String = raviMemberId) =
        post("/api/v1/households/$householdId/members/$memberId/memorial", token, mapOf("note" to "13 Sep"))

    @Test
    fun `an admin marks someone, after confirming it is them, and the roster says so quietly`() {
        assertThat(mark(ishwarya).errorCode()).isEqualTo("step_up_required")

        stepUp(ishwarya)
        val marked = mark(ishwarya)
        assertThat(marked.status()).describedAs(marked.body).isEqualTo(HttpStatus.OK)
        assertThat(marked.json().path("passedAway").asBoolean()).isTrue()

        val roster = members(meera, householdId).first { it.path("id").asText() == raviMemberId }
        assertThat(roster.path("passedAway").asBoolean()).isTrue()
        assertThat(roster.path("memorialisedAt").isNull).isFalse()

        val basis = db.queryForObject(
            "select basis from member_memorials where member_id = ?::uuid", String::class.java, raviMemberId,
        )
        assertThat(basis).isEqualTo("admin")
        assertThat(
            db.queryForObject(
                "select count(*) from activity_log where action = 'member.memorial.mark' and entity_id = ?::uuid",
                Int::class.java, raviMemberId,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `the person is told once, and then messages to them stop`() {
        stepUp(ishwarya)
        mark(ishwarya)
        val raviId = userId(ravi)
        assertThat(templatesFor(raviId)).containsExactly(MemorialService.MARKED_TEMPLATE)

        // Naming him as someone's emergency contact would ordinarily tell him.
        post(
            "/api/v1/households/$householdId/emergency/contacts", meera,
            mapOf("trustedMemberId" to raviMemberId, "waitDays" to 14),
        )
        assertThat(templatesFor(raviId)).containsExactly(MemorialService.MARKED_TEMPLATE)
        assertThat(
            db.queryForObject(
                "select count(*) from outbound_messages where user_id = ?::uuid and channel <> 'in_app'",
                Int::class.java, raviId,
            ),
        ).describedAs("only the one notice was queued on any channel").isLessThanOrEqualTo(3)
    }

    @Test
    fun `an emergency request against someone marked still reaches them, so they can say no`() {
        // Ishwarya is Ravi's trusted contact and an admin: she can mark him and then ask.
        post(
            "/api/v1/households/$householdId/emergency/contacts", ravi,
            mapOf("trustedMemberId" to ishwaryaMemberId, "waitDays" to 14),
        )
        stepUp(ishwarya)
        assertThat(mark(ishwarya).status()).isEqualTo(HttpStatus.OK)
        val raviId = userId(ravi)

        val asked = post(
            "/api/v1/households/$householdId/emergency/requests", ishwarya,
            mapOf("subjectMemberId" to raviMemberId, "reason" to "Travelling"),
        )
        assertThat(asked.status().is2xxSuccessful).describedAs(asked.body).isTrue()
        assertThat(templatesFor(raviId)).contains("emergency.requested")

        // Checked where the message is written, not only on this path.
        assertThat(
            db.queryForObject(
                "select app.record_in_app_message(?::uuid, ?::uuid, 'auth.new_sign_in', 't', gen_random_uuid()::text) is not null",
                Boolean::class.java, householdId, raviId,
            ),
        ).isTrue()
        assertThat(
            db.queryForObject(
                "select app.record_in_app_message(?::uuid, ?::uuid, 'emergency.named', 't', gen_random_uuid()::text) is null",
                Boolean::class.java, householdId, raviId,
            ),
        ).describedAs("news that is not a warning still stops").isTrue()
    }

    @Test
    fun `a memorialised account can still see, cannot change anything, and says why`() {
        capture(ravi, householdId, "gold_physical", "Ravi's gold", BigDecimal(100_000))
        stepUp(ishwarya)
        mark(ishwarya)

        assertThat(household(ravi, householdId).path("readOnly").asBoolean()).isTrue()
        assertThat(get("/api/v1/households/$householdId/investments", ravi).json().map { it.path("title").asText() })
            .contains("Ravi's gold")

        val refused = post(
            "/api/v1/households/$householdId/investments", ravi,
            mapOf("typeId" to typeId(ishwarya, householdId, "gold_physical"), "title" to "New", "investedAmount" to 1),
        )
        assertThat(refused.status()).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(refused.errorCode()).isEqualTo("memorial_read_only")
    }

    @Test
    fun `the database refuses the write too, not only the request guard`() {
        stepUp(ishwarya)
        mark(ishwarya)
        val raviId = userId(ravi)
        assertThat(asUser(raviId, "select app.can_write_household('$householdId'::uuid)")).isFalse()
        assertThat(asUser(raviId, "select app.is_household_member('$householdId'::uuid)"))
            .describedAs("sight is not a capability, so it is not taken away").isTrue()
    }

    @Test
    fun `only the person it names can take it away, and then everything works again`() {
        stepUp(ishwarya)
        mark(ishwarya)

        val notTheirs = delete("/api/v1/households/$householdId/members/$raviMemberId/memorial", meera)
        assertThat(notTheirs.status()).isEqualTo(HttpStatus.FORBIDDEN)

        val reversed = delete("/api/v1/households/$householdId/members/$raviMemberId/memorial", ravi)
        assertThat(reversed.status()).describedAs(reversed.body).isEqualTo(HttpStatus.OK)
        assertThat(reversed.json().path("passedAway").asBoolean()).isFalse()
        assertThat(household(ravi, householdId).path("readOnly").asBoolean()).isFalse()
        capture(ravi, householdId, "gold_physical", "After", BigDecimal(1))
        assertThat(templatesFor(userId(ishwarya))).contains("lifecycle.memorial.reversed")
    }

    @Test
    fun `an editor who is not a trusted contact cannot mark anyone, and nobody can mark themselves`() {
        stepUp(meera)
        assertThat(mark(meera).status()).isEqualTo(HttpStatus.FORBIDDEN)
        stepUp(ishwarya)
        assertThat(mark(ishwarya, ishwaryaMemberId).errorCode()).isEqualTo("cannot_mark_self")
    }

    @Test
    fun `a trusted contact can mark someone once an emergency window on them has opened`() {
        val raviId = userId(ravi)
        post(
            "/api/v1/households/$householdId/emergency/contacts", ravi,
            mapOf("trustedMemberId" to meeraMemberId, "waitDays" to 14),
        )
        post(
            "/api/v1/households/$householdId/emergency/requests", meera,
            mapOf("subjectMemberId" to raviMemberId, "reason" to "Hospital"),
        )
        stepUp(meera)
        assertThat(mark(meera).status()).describedAs("still waiting").isEqualTo(HttpStatus.FORBIDDEN)

        db.update(
            """
            update emergency_requests set requested_at = now() - interval '20 days', unlock_at = now() - interval '6 days'
             where household_id = ?::uuid
            """.trimIndent(),
            householdId,
        )
        db.update(
            "update user_sessions set last_used_at = now() - interval '30 days' where user_id = ?::uuid",
            raviId,
        )
        val marked = mark(meera)
        assertThat(marked.status()).describedAs(marked.body).isEqualTo(HttpStatus.OK)
        assertThat(
            db.queryForObject(
                "select basis from member_memorials where member_id = ?::uuid", String::class.java, raviMemberId,
            ),
        ).isEqualTo("trusted_contact")
    }
}
