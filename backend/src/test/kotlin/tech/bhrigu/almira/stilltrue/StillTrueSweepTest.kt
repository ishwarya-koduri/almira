package tech.bhrigu.almira.stilltrue

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tech.bhrigu.almira.provider.SandboxFault
import tech.bhrigu.almira.provider.SandboxFaults
import tech.bhrigu.almira.reminder.Notifier
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource

/**
 * The sweep, against the real database, through the real notifiers.
 *
 * The first test is the one that matters most. A sweep wired to the runtime pool,
 * or one that forgets to say whose view it is reading, finds nothing — and a
 * sweep that finds nothing passes every test that only checks it did not fail.
 * So this one puts a due record in place and requires the owner to have been
 * told.
 */
@DisplayName("Still true? — the sweep")
class StillTrueSweepTest : ApiTestBase() {

    @Autowired private lateinit var sweep: StillTrueSweep
    @Autowired private lateinit var faults: SandboxFaults
    @Autowired private lateinit var notifiers: List<Notifier>
    @Autowired private lateinit var runtimeDataSource: DataSource

    private lateinit var owner: String
    private lateinit var spouse: String
    private lateinit var householdId: String
    private lateinit var ownerMemberId: String
    private lateinit var spouseMemberId: String
    private lateinit var ownerUserId: String
    private lateinit var spouseUserId: String

    @BeforeEach
    fun setUp() {
        faults.clear()
        owner = signIn()
        spouse = signIn()
        val household = createHousehold(owner, "Koduri", "private", "Ishwarya")
        householdId = household.path("id").asText()
        ownerMemberId = household.path("myMemberId").asText()
        spouseMemberId = addMember(owner, householdId, "Ravi").path("id").asText()
        joinHousehold(owner, householdId, spouseMemberId, spouse, role = "admin")
        ownerUserId = userOf(ownerMemberId)
        spouseUserId = userOf(spouseMemberId)
        livesAt(localHour = 12)
    }

    @AfterEach
    fun tearDown() = faults.clear()

    private fun userOf(memberId: String) =
        db.queryForObject("select user_id::text from members where id = ?::uuid", String::class.java, memberId)!!

    /**
     * Puts the household in a zone where it is [localHour] o'clock now, so quiet
     * hours are exercised without depending on when the suite runs.
     * `Etc/GMT-5` is UTC+5: the sign is POSIX's, the opposite of what it looks.
     */
    private fun livesAt(localHour: Int) {
        val utcHour = Instant.now().atOffset(ZoneOffset.UTC).hour
        var offset = localHour - utcHour
        if (offset < -12) offset += 24
        if (offset > 14) offset -= 24
        val zone = if (offset >= 0) "Etc/GMT-$offset" else "Etc/GMT+${-offset}"
        db.update("update households set time_zone = ? where id = ?::uuid", zone, householdId)
    }

    private fun dueFd(title: String, owners: List<Map<String, Any>> = emptyList()): String {
        val id = capture(
            owner, householdId, "fd", title, BigDecimal(250_000),
            attributes = mapOf("interest_rate" to 7.1), owners = owners,
        ).path("id").asText()
        db.update(
            "update investments set created_at = now() - interval '13 months', last_verified_at = null where id = ?::uuid",
            id,
        )
        return id
    }

    private data class Sent(val channel: String, val status: String, val failure: String?, val title: String)

    private fun sentTo(userId: String): List<Sent> = db.query(
        """
        select channel, status, failure, title from outbound_messages
        where user_id = ?::uuid and template = ?
        order by created_at, channel
        """.trimIndent(),
        { rs, _ -> Sent(rs.getString("channel"), rs.getString("status"), rs.getString("failure"), rs.getString("title")) },
        userId, StillTrue.DIGEST_TEMPLATE,
    )

    private fun inApp(userId: String) = sentTo(userId).filter { it.channel == "in_app" }

    @Test
    fun `a due record reaches its owner, and nobody who does not own it`() {
        dueFd("HDFC FD 2025 ₹2,50,000")

        val result = sweep.run()

        assertThat(inApp(ownerUserId))
            .describedAs(
                "the sweep must find a due record. If this is empty, check that it reads on the " +
                    "owner connection (@Qualifier(\"ownerDataSource\")) and sets app.user_id per person",
            )
            .hasSize(1)
        assertThat(inApp(ownerUserId).single().status).isEqualTo("sent")
        assertThat(result.peopleNudged).isGreaterThanOrEqualTo(1)
        assertThat(inApp(spouseUserId))
            .describedAs("an admin who can write in the household still owns nothing here")
            .isEmpty()
    }

    /** Recorded by the owner for a member who never signs in, and thirteen months old. */
    private fun ammasFd(title: String, visibility: String) {
        val amma = addMember(owner, householdId, "Amma").path("id").asText()
        post(
            "/api/v1/households/$householdId/investments", owner,
            mapOf(
                "typeId" to typeId(owner, householdId, "fd"), "title" to title,
                "investedAmount" to 300_000, "visibility" to visibility,
                "attributes" to mapOf("interest_rate" to 7.0),
                "owners" to listOf(mapOf("memberId" to amma, "sharePct" to 100)),
            ),
        )
        db.update(
            """
            update investments set created_at = now() - interval '13 months', last_verified_at = null
             where household_id = ?::uuid and title = ?
            """.trimIndent(),
            householdId, title,
        )
    }

    @Test
    fun `a record whose owner has no login reaches whoever recorded it`() {
        ammasFd("Amma's LIC", visibility = "household")

        sweep.run()

        assertThat(inApp(ownerUserId))
            .describedAs("nobody else can answer for a member who never signs in")
            .hasSize(1)
        assertThat(inApp(spouseUserId)).isEmpty()
    }

    @Test
    fun `the recorder is not nudged about a record they cannot open`() {
        // The sweep reads on the owner connection, which row-level security does
        // not restrict, so sight has to be checked in the predicate itself.
        ammasFd("Amma's private FD", visibility = "private")

        sweep.run()

        assertThat(inApp(ownerUserId))
            .describedAs("a private record for someone else is not the recorder's to see")
            .isEmpty()
    }

    @Test
    fun `the notification carries a count, not a name or an amount`() {
        dueFd("HDFC FD 2025 ₹2,50,000")
        dueFd("Amma's LIC")

        sweep.run()

        val title = inApp(ownerUserId).single().title
        assertThat(title).isEqualTo("Still true? 2 records to check")
        assertThat(title).doesNotContain("HDFC", "LIC", "2,50,000", "250000", "Amma")
    }

    @Test
    fun `run on the runtime pool, the same sweep finds nothing — which is why it is not`() {
        dueFd("Would be missed")

        // The hazard, demonstrated rather than described: identical code, the
        // pool an unqualified DataSource resolves to.
        val misWired = StillTrueSweep(runtimeDataSource, notifiers)
        val result = misWired.run()

        assertThat(result.householdsWithDueRecords).isZero()
        assertThat(inApp(ownerUserId)).isEmpty()
    }

    @Test
    fun `a second sweep does not nag, and thirty days of silence asks once more`() {
        dueFd("SBI FD")

        sweep.run()
        sweep.run()
        assertThat(inApp(ownerUserId)).hasSize(1)

        db.update(
            "update record_confirmation_nudges set nudged_at = now() - interval '31 days' where user_id = ?::uuid",
            ownerUserId,
        )
        sweep.run()
        assertThat(inApp(ownerUserId)).hasSize(2)
    }

    @Test
    fun `a snoozed or confirmed record is not nudged, and one that falls due again is`() {
        val snoozed = dueFd("Snoozed FD")
        val confirmed = dueFd("Confirmed FD")
        val base = "/api/v1/households/$householdId/still-true"
        val tomorrow = db.queryForObject(
            "select ((now() at time zone time_zone)::date + 1)::text from households where id = ?::uuid",
            String::class.java, householdId,
        )
        post("$base/investment/$snoozed/snooze", owner, mapOf("until" to tomorrow))
        post("$base/investment/$confirmed/confirm", owner)

        sweep.run()
        assertThat(inApp(ownerUserId)).isEmpty()

        // The snooze runs out.
        db.update("update record_confirmations set snoozed_until = snoozed_until - 1 where record_id = ?::uuid", snoozed)
        sweep.run()
        assertThat(inApp(ownerUserId).map { it.title }).containsExactly("Still true? 1 record to check")
    }

    @Test
    fun `joint owners are each told once`() {
        dueFd(
            "Joint FD",
            owners = listOf(
                mapOf("memberId" to ownerMemberId, "sharePct" to 50),
                mapOf("memberId" to spouseMemberId, "sharePct" to 50),
            ),
        )

        sweep.run()
        sweep.run()

        assertThat(inApp(ownerUserId)).hasSize(1)
        assertThat(inApp(spouseUserId))
            .describedAs("telling one owner must not count as having told the other")
            .hasSize(1)
    }

    @Test
    fun `a household where it is the middle of the night is left alone until morning`() {
        livesAt(localHour = 3)
        dueFd("Night FD")

        sweep.run()
        assertThat(inApp(ownerUserId)).isEmpty()

        livesAt(localHour = 10)
        sweep.run()
        assertThat(inApp(ownerUserId)).hasSize(1)
    }

    @Test
    fun `a failed channel is recorded as it failed, and the next sweep does not resend it`() {
        faults.always("push", SandboxFault.REJECTED)
        faults.always("sms", SandboxFault.TIMEOUT)
        dueFd("SBI FD")

        sweep.run()
        sweep.run()

        val sent = sentTo(ownerUserId)
        assertThat(sent.filter { it.channel == "in_app" }.map { it.status }).containsExactly("sent")
        assertThat(sent.filter { it.channel == "push" }.map { it.status to it.failure })
            .containsExactly("failed" to "rejected")
        assertThat(sent.filter { it.channel == "sms" }.map { it.status to it.failure })
            .describedAs("a timed-out text may already have landed; sending it again would be the nag")
            .containsExactly("failed" to "timeout")
    }
}
