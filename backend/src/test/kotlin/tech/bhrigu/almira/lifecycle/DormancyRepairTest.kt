package tech.bhrigu.almira.lifecycle

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tech.bhrigu.almira.provider.NotificationOutbox
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The owner's answers (D8b, V137; and 2026-09-15, V147): a dormant household
 * with nobody who may take it on gets an operator path — only on a documented
 * request that records what evidence was seen and by whom (never the document),
 * audited, telling the household before anything is done and after, with a wait
 * that starts when the notice is actually sent, and designed for two operators
 * with a single-operator mode that states its reason.
 *
 * The functions are called as the operator script calls them, as the schema
 * owner. Each refusal is proven by the role that did not change.
 */
@DisplayName("An operator repair: evidence seen, a notice sent, a wait, a second operator, then the change")
class DormancyRepairTest : LifecycleTestSupport() {

    @Autowired private lateinit var sweep: LifecycleSweep
    @Autowired private lateinit var outbox: NotificationOutbox

    private lateinit var advisor: String
    private lateinit var teen: String
    private lateinit var householdId: String
    private lateinit var advisorMemberId: String

    @BeforeEach
    fun setUp() {
        outbox.drain()
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

    private fun request(
        wait: String = "14 days",
        reason: String = "The family's lawyer asks for the CA to run it until probate.",
        kind: String = "written_request_from_legal_representative",
        seenBy: String = "support-1",
    ) = db.queryForObject(
        "select ops.request_dormancy_repair(?::uuid, ?::uuid, 'support-1', ?, 'Suresh Rao', 'brother of the owner', ?, ?, 'ticket 2231', ?::interval)",
        UUID::class.java, householdId, advisorMemberId, reason, kind, seenBy, wait,
    )!!

    private fun carryOut(requestId: UUID, alone: String? = null): String =
        db.queryForObject("select ops.carry_out_dormancy_repair(?::uuid, 'support-2', ?)", String::class.java, requestId, alone)!!

    private fun approve(requestId: UUID, operator: String) =
        db.queryForList("select ops.approve_dormancy_repair(?::uuid, ?)", requestId, operator)

    /** The before-notice really goes out: the worker sends the queued messages (sandbox providers). */
    private fun sendNotices() = outbox.drain()

    /** The wait has passed since the notice was sent. */
    private fun waitOver(requestId: UUID) = db.update(
        "update dormancy_repair_requests set notified_before_at = now() - interval '15 days', act_after = now() - interval '1 day' where id = ?::uuid",
        requestId,
    )

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
        // In its own connection, so no earlier call in this session has given
        // plpgsql a cached plan: an unassigned record only raises on the first
        // call that reaches the expression (V151).
        assertThat(
            db.execute(
                org.springframework.jdbc.core.ConnectionCallback { connection ->
                    connection.prepareStatement("select ops.carry_out_dormancy_repair(?::uuid, 'support-2', null)").use {
                        it.setString(1, made.toString())
                        it.executeQuery().use { rs -> rs.next(); rs.getString(1) }
                    }
                },
            ),
        ).describedAs("a request id nobody has is answered, not raised").isEqualTo("no_request")
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

    /** Owner's decision (V147): the evidence that fits the trigger, recorded as seen and by whom — never stored. */
    @Test
    fun `the evidence must fit what made it dormant, and who saw it is recorded`() {
        assertThatThrownBy { request(kind = "death_certificate_or_equivalent") }
            .describedAs("dormant because of a closure, not a death").hasMessageContaining("not a death")
        assertThatThrownBy { request(kind = "a scan") }.hasMessageContaining("name the evidence seen")
        assertThatThrownBy { request(seenBy = "") }.hasMessageContaining("who saw the evidence")
        assertThat(count("select count(*) from dormancy_repair_requests where household_id = ?::uuid", householdId))
            .describedAs("each refused before anything was written or anyone told").isZero()
        assertThat(notices("lifecycle.household.repair_requested", inApp = true)).isZero()

        val requestId = request()
        val row = db.queryForMap(
            "select evidence_kind, evidence_seen_by, evidence_seen_at is not null as seen, evidence_reference from dormancy_repair_requests where id = ?::uuid",
            requestId,
        )
        assertThat(row).containsEntry("evidence_kind", "written_request_from_legal_representative")
            .containsEntry("evidence_seen_by", "support-1").containsEntry("seen", true).containsEntry("evidence_reference", "ticket 2231")
    }

    @Test
    fun `the household is told before, the wait starts when the notice is sent, a second operator approves, and it is told after`() {
        val requestId = request()

        // Filed and queued, but not yet sent: no clock, nothing to carry out.
        assertThat(
            db.queryForMap("select notified_before_at, act_after, before_notice_queued_at is not null as queued from dormancy_repair_requests where id = ?::uuid", requestId),
        ).containsEntry("notified_before_at", null).containsEntry("act_after", null).containsEntry("queued", true)
        assertThat(count("select count(*) from activity_log where action = 'ops.dormancy_repair.request' and household_id = ?::uuid and diff ->> 'operator' = 'support-1'", householdId))
            .isEqualTo(1)
        assertThat(notices("lifecycle.household.repair_requested", inApp = true)).isEqualTo(2)
        assertThat(notices("lifecycle.household.repair_requested", inApp = false)).isGreaterThanOrEqualTo(2)
        assertThat(carryOut(requestId)).describedAs("the wait has not started").isEqualTo("not_told_before")
        assertThat(role()).isEqualTo("advisor")

        // Sent: the clock starts from the moment it went, not from filing.
        sendNotices()
        assertThat(carryOut(requestId)).isEqualTo("waiting")
        assertThat(
            db.queryForMap(
                "select notified_before_at is not null as told, act_after > now() + interval '13 days' as waits, " +
                    "notified_before_at >= before_notice_queued_at as after_queued from dormancy_repair_requests where id = ?::uuid",
                requestId,
            ),
        ).containsEntry("told", true).containsEntry("waits", true).containsEntry("after_queued", true)
        assertThat(attempts("waiting")).isEqualTo(1)
        assertThat(role()).isEqualTo("advisor")
        assertThat(notices("lifecycle.household.repair_done", inApp = true)).isZero()

        // The wait is over, but one operator alone may not act without saying why.
        waitOver(requestId)
        assertThat(carryOut(requestId)).isEqualTo("needs_second_operator")
        assertThat(role()).isEqualTo("advisor")
        assertThatThrownBy { approve(requestId, "support-1") }.describedAs("not the one who asked")
            .hasMessageContaining("someone other than the one who asked")
        approve(requestId, "support-3")

        assertThat(carryOut(requestId)).isEqualTo("done")
        assertThat(attempts("done")).isEqualTo(1)
        assertThat(role()).isEqualTo("owner")
        assertThat(
            db.queryForMap(
                "select ended_reason, transferred_to::text as to from household_dormancies where household_id = ?::uuid",
                householdId,
            ),
        ).containsEntry("ended_reason", "transferred").containsEntry("to", userId(advisor))
        assertThat(
            count(
                "select count(*) from dormancy_repair_requests where id = ?::uuid and carried_out_by_operator = 'support-2' " +
                    "and approved_by_operator = 'support-3' and single_operator_reason is null and notified_after_at is not null",
                requestId,
            ),
        ).isEqualTo(1)
        assertThat(notices("lifecycle.household.repair_done", inApp = true)).isEqualTo(2)
        assertThat(notices("lifecycle.household.repair_done", inApp = false)).isGreaterThanOrEqualTo(2)

        // Done once.
        assertThat(carryOut(requestId)).isEqualTo("already_done")
    }

    /** One operator today: allowed, with the reason stored and audited — not a rule broken silently. */
    @Test
    fun `a single operator may act alone only with a reason, which is kept`() {
        val requestId = request()
        sendNotices()
        waitOver(requestId)
        assertThat(carryOut(requestId, alone = "short")).describedAs("a reason is a sentence").isEqualTo("needs_second_operator")
        assertThat(role()).isEqualTo("advisor")

        val why = "Only one operator exists today; the request and evidence were checked twice."
        assertThat(carryOut(requestId, alone = why)).isEqualTo("done")
        assertThat(role()).isEqualTo("owner")
        assertThat(db.queryForObject("select single_operator_reason from dormancy_repair_requests where id = ?::uuid", String::class.java, requestId))
            .isEqualTo(why)
        assertThat(
            count("select count(*) from activity_log where action = 'ops.dormancy_repair.carry_out' and entity_id = ?::uuid and diff ->> 'singleOperatorReason' = ?", requestId, why),
        ).isEqualTo(1)
    }

    private fun noticeGiven(
        requestId: UUID, operator: String = "support-1", method: String = "post",
        what: String = "Posted the notice to the Rao house in Guntur, speed post EK1234, receipt on ticket 2231.",
        whenGiven: String = "now() - interval '15 days'",
    ) = db.queryForObject(
        "select ops.record_dormancy_repair_notice_given(?::uuid, ?, ?, ?, $whenGiven)",
        java.sql.Timestamp::class.java, requestId, operator, method, what,
    )

    /**
     * Owner's decision (2026-09-16, V148): *a household frozen permanently because
     * an email bounced is "dormant forever by drift" wearing a different hat.* The
     * notice can be given another way — and the bar goes up, not down.
     */
    @Test
    fun `a notice given by post starts the clock, and then nobody acts alone`() {
        val requestId = request()
        // Nothing was sent: these members have no address a notice can reach.
        assertThat(carryOut(requestId)).isEqualTo("not_told_before")
        val why = "Only one operator exists today; the request and evidence were checked twice."
        assertThat(carryOut(requestId, alone = why)).describedAs("not even with a reason").isEqualTo("not_told_before")

        assertThatThrownBy { noticeGiven(requestId, method = "carrier pigeon") }.hasMessageContaining("post, phone or in_person")
        assertThatThrownBy { noticeGiven(requestId, what = "posted it") }.hasMessageContaining("what was done")
        assertThatThrownBy { noticeGiven(requestId, whenGiven = "now() + interval '1 day'") }.hasMessageContaining("cannot be in the future")
        assertThat(
            count("select count(*) from dormancy_repair_requests where id = ?::uuid and notified_before_at is null", requestId),
        ).describedAs("each refused before anything was written").isEqualTo(1)

        assertThat(noticeGiven(requestId)).describedAs("the wait runs from when it was given").isNotNull()
        assertThat(
            db.queryForMap(
                """
                select notice_given_method, notice_given_recorded_by, notice_given_detail,
                       notified_before_at = notice_given_another_way_at as clock_from_it,
                       act_after < now() as wait_over
                  from dormancy_repair_requests where id = ?::uuid
                """.trimIndent(),
                requestId,
            ),
        ).containsEntry("notice_given_method", "post").containsEntry("notice_given_recorded_by", "support-1")
            .containsEntry("clock_from_it", true).containsEntry("wait_over", true)
        assertThat(
            count(
                "select count(*) from activity_log where action = 'ops.dormancy_repair.notice_given' and entity_id = ?::uuid " +
                    "and diff ->> 'method' = 'post'",
                requestId,
            ),
        ).isEqualTo(1)

        // The higher bar: a reason no longer buys a single operator the change.
        assertThat(carryOut(requestId, alone = why)).isEqualTo("needs_second_operator")
        assertThat(role()).isEqualTo("advisor")
        approve(requestId, "support-3")
        assertThat(carryOut(requestId, alone = why)).isEqualTo("done")
        assertThat(role()).isEqualTo("owner")
        assertThat(
            count(
                "select count(*) from dormancy_repair_requests where id = ?::uuid and approved_by_operator = 'support-3' " +
                    "and single_operator_reason is null",
                requestId,
            ),
        ).describedAs("two operators, and no reason stored for acting alone").isEqualTo(1)
    }

    /** A notice that did go out is the record; an operator's account does not replace it. */
    @Test
    fun `when the notice was sent, recording one given another way changes nothing`() {
        val requestId = request()
        sendNotices()
        val sentClock = db.queryForObject(
            "select ops.dormancy_repair_start_clock(?::uuid)", java.sql.Timestamp::class.java, requestId,
        )
        assertThat(sentClock).describedAs("the sent notice started it").isNotNull()
        assertThat(noticeGiven(requestId)).isEqualTo(sentClock)
        assertThat(
            count("select count(*) from dormancy_repair_requests where id = ?::uuid and notice_given_another_way_at is null", requestId),
        ).isEqualTo(1)
        waitOver(requestId)
        assertThat(carryOut(requestId, alone = "Only one operator exists today; both checks were done twice."))
            .describedAs("the ordinary path still allows it").isEqualTo("done")
    }

    @Test
    fun `a withdrawn request is never carried out`() {
        val requestId = request()
        sendNotices()
        db.queryForList("select ops.withdraw_dormancy_repair(?::uuid, 'support-1', 'the family found the will')", requestId)
        waitOver(requestId)
        assertThatThrownBy { approve(requestId, "support-3") }.hasMessageContaining("no open repair request")
        assertThat(carryOut(requestId)).isEqualTo("withdrawn")
        assertThat(role()).isEqualTo("advisor")
    }
}
