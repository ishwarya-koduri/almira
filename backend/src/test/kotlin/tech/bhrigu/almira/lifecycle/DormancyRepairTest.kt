package tech.bhrigu.almira.lifecycle

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The owner's answer (D8b, docs/05 §12.7, V137): a dormant household with
 * nobody who may take it on gets an operator path — only on a documented
 * request, audited, telling the household before anything is done and after,
 * with a wait between.
 *
 * The functions are called as the operator script calls them, as the schema
 * owner. Each refusal is proven by the role that did not change.
 */
@DisplayName("An operator repair: a request, a notice, a wait, then the change, and a notice after")
class DormancyRepairTest : LifecycleTestSupport() {

    @Autowired private lateinit var sweep: LifecycleSweep

    private lateinit var advisor: String
    private lateinit var teen: String
    private lateinit var householdId: String
    private lateinit var advisorMemberId: String

    @BeforeEach
    fun setUp() {
        val owner = signIn()
        advisor = signIn()
        teen = signIn()
        householdId = createHousehold(owner, "Rao", "private", "Lakshmi").path("id").asText()
        capture(owner, householdId, "gold_physical", "Rao gold", BigDecimal(5), visibility = "household")
        advisorMemberId = addMember(owner, householdId, "Chandra").path("id").asText()
        joinHousehold(owner, householdId, advisorMemberId, advisor, role = "advisor")
        joinHousehold(owner, householdId, addMember(owner, householdId, "Teen").path("id").asText(), teen, role = "restricted")
        // Her closure, as the sweep would find it with nobody left who may take the household on.
        db.update(
            "insert into account_closures (user_id, requested_at, purge_after) values (?::uuid, now() - interval '31 days', now() - interval '1 day')",
            userId(owner),
        )
        sweep.run(Instant.now())
        check(count("select count(*) from household_dormancies where household_id = ?::uuid and ended_at is null", householdId) == 1)
    }

    private fun count(sql: String, vararg args: Any): Int = db.queryForObject(sql, Int::class.java, *args)!!

    private fun role() = household(advisor, householdId).path("myRole").asText()

    private fun request(wait: String = "14 days", reason: String = "The family's lawyer asks for the CA to run it until probate.") =
        db.queryForObject(
            "select ops.request_dormancy_repair(?::uuid, ?::uuid, 'support-1', ?, 'Suresh Rao', 'brother of the late owner', 'ticket 2231', ?::interval)",
            UUID::class.java, householdId, advisorMemberId, reason, wait,
        )!!

    private fun carryOut(requestId: UUID): String =
        db.queryForObject("select ops.carry_out_dormancy_repair(?::uuid, 'support-2')", String::class.java, requestId)!!

    private fun notices(template: String, inApp: Boolean) = count(
        "select count(*) from outbound_messages where household_id = ?::uuid and template = ? and (channel = 'in_app') = ?",
        householdId, template, inApp,
    )

    private fun attempts(outcome: String) = count(
        "select count(*) from activity_log where action = 'ops.dormancy_repair.carry_out' and diff ->> 'outcome' = ? " +
            "and diff ->> 'operator' = 'support-2' and (household_id = ?::uuid or household_id is null)",
        outcome, householdId,
    )

    @Test
    fun `without a request record nothing is done, and the attempt is audited`() {
        val made = UUID.randomUUID()
        assertThat(carryOut(made)).isEqualTo("no_request")
        assertThat(count("select count(*) from activity_log where action = 'ops.dormancy_repair.carry_out' and entity_id = ?::uuid and diff ->> 'outcome' = 'no_request'", made))
            .isEqualTo(1)
        assertThat(role()).isEqualTo("advisor")
        assertThat(count("select count(*) from household_dormancies where household_id = ?::uuid and ended_at is null", householdId)).isEqualTo(1)

        // A request is itself refused without its documentation or with too short a wait.
        assertThatThrownBy { request(reason = "") }.hasMessageContaining("say why")
        assertThatThrownBy { request(wait = "3 days") }.hasMessageContaining("seven days")
        assertThat(count("select count(*) from dormancy_repair_requests where household_id = ?::uuid", householdId)).isZero()
        assertThat(notices("lifecycle.household.repair_requested", inApp = true)).isZero()
    }

    @Test
    fun `the household is told before, nothing is done during the wait, and it is told after`() {
        val requestId = request()

        // Before: the request is recorded, audited, and everyone with a login is told —
        // in the app and at their addresses, whether or not they said yes to messages.
        val row = db.queryForMap(
            "select requester_name, requester_relationship, evidence_reference, notified_before_at is not null as told, " +
                "act_after > now() + interval '13 days' as waits from dormancy_repair_requests where id = ?::uuid",
            requestId,
        )
        assertThat(row).containsEntry("requester_name", "Suresh Rao").containsEntry("evidence_reference", "ticket 2231")
            .containsEntry("told", true).containsEntry("waits", true)
        assertThat(count("select count(*) from activity_log where action = 'ops.dormancy_repair.request' and household_id = ?::uuid and diff ->> 'operator' = 'support-1'", householdId))
            .isEqualTo(1)
        assertThat(notices("lifecycle.household.repair_requested", inApp = true)).isEqualTo(2)
        assertThat(notices("lifecycle.household.repair_requested", inApp = false)).isGreaterThanOrEqualTo(2)
        assertThat(get("/api/v1/households/$householdId/dormancy", teen).json().path("dormant").asBoolean()).isTrue()

        // During the wait: refused, audited, nothing changed, nobody told it was done.
        assertThat(carryOut(requestId)).isEqualTo("waiting")
        assertThat(attempts("waiting")).isEqualTo(1)
        assertThat(role()).isEqualTo("advisor")
        assertThat(count("select count(*) from household_dormancies where household_id = ?::uuid and ended_at is null", householdId)).isEqualTo(1)
        assertThat(count("select count(*) from dormancy_repair_requests where id = ?::uuid and carried_out_at is null", requestId)).isEqualTo(1)
        assertThat(notices("lifecycle.household.repair_done", inApp = true)).isZero()

        // The wait is over.
        db.update(
            "update dormancy_repair_requests set created_at = now() - interval '15 days', act_after = now() - interval '1 day' where id = ?::uuid",
            requestId,
        )
        assertThat(carryOut(requestId)).isEqualTo("done")
        assertThat(attempts("done")).isEqualTo(1)
        assertThat(role()).isEqualTo("owner")
        assertThat(
            db.queryForMap(
                "select ended_reason, transferred_to::text as to from household_dormancies where household_id = ?::uuid",
                householdId,
            ),
        ).containsEntry("ended_reason", "transferred").containsEntry("to", userId(advisor))
        assertThat(count("select count(*) from dormancy_repair_requests where id = ?::uuid and carried_out_by_operator = 'support-2' and notified_after_at is not null", requestId))
            .isEqualTo(1)
        assertThat(notices("lifecycle.household.repair_done", inApp = true)).isEqualTo(2)
        assertThat(notices("lifecycle.household.repair_done", inApp = false)).isGreaterThanOrEqualTo(2)

        // Done once.
        assertThat(carryOut(requestId)).isEqualTo("already_done")
    }

    @Test
    fun `a withdrawn request is never carried out`() {
        val requestId = request()
        db.queryForList("select ops.withdraw_dormancy_repair(?::uuid, 'support-1', 'the family found the will')", requestId)
        db.update(
            "update dormancy_repair_requests set created_at = now() - interval '15 days', act_after = now() - interval '1 day' where id = ?::uuid",
            requestId,
        )
        assertThat(carryOut(requestId)).isEqualTo("withdrawn")
        assertThat(role()).isEqualTo("advisor")
    }
}
