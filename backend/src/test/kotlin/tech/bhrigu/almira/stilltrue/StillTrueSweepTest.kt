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
    @Autowired private lateinit var outbox: tech.bhrigu.almira.provider.NotificationOutbox

    private lateinit var owner: String
    private lateinit var spouse: String
    private lateinit var householdId: String
    private lateinit var ownerMemberId: String
    private lateinit var spouseMemberId: String
    private lateinit var ownerUserId: String
    private lateinit var spouseUserId: String

    @BeforeEach
    fun setUp() {
        outbox.drain()
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
        assertThat(title).isEqualTo("Is this still right? 2 records haven't been confirmed in a while")
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
        // A month has passed, so the last message is a month old too (at most one a week, below).
        aWeekPasses()
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
        assertThat(inApp(ownerUserId).map { it.title })
            .containsExactly("Is this still right? 1 record hasn't been confirmed in a while")
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
        consentToMessages(owner)
        faults.always("push", SandboxFault.REJECTED)
        faults.always("sms", SandboxFault.TIMEOUT)
        dueFd("SBI FD")

        sweep.run()
        outbox.drain()
        sweep.run()
        outbox.drain()

        val sent = sentTo(ownerUserId)
        assertThat(sent.filter { it.channel == "in_app" }.map { it.status }).containsExactly("sent")
        assertThat(sent.filter { it.channel == "push" }.map { it.status to it.failure })
            .containsExactly("failed" to "rejected")
        assertThat(sent.filter { it.channel == "sms" }.map { it.status to it.failure })
            .describedAs("a timed-out text may already have landed; sending it again would be the nag")
            .containsExactly("failed" to "timeout")
    }

    // --- docs/21 §6: gentle, and not on the wrong day -------------------------

    /** Ages this person's Still true? messages past the one-a-week limit. */
    private fun aWeekPasses() {
        db.update(
            "update outbound_messages set created_at = created_at - interval '8 days' where user_id = ?::uuid and template = ?",
            ownerUserId, StillTrue.DIGEST_TEMPLATE,
        )
    }

    private fun householdToday(): java.time.LocalDate = java.time.LocalDate.parse(
        db.queryForObject(
            "select ((now() at time zone time_zone)::date)::text from households where id = ?::uuid",
            String::class.java, householdId,
        ),
    )

    @Test
    fun `at most one Still true? message a week, however many records fall due`() {
        dueFd("SBI FD")
        sweep.run()
        assertThat(inApp(ownerUserId)).hasSize(1)

        dueFd("ICICI FD, due a day later")
        sweep.run()
        assertThat(inApp(ownerUserId))
            .describedAs("a second record falling due inside the week waits")
            .hasSize(1)

        aWeekPasses()
        sweep.run()
        assertThat(inApp(ownerUserId))
            .describedAs("and is asked about once the week is up, because it was never marked as asked")
            .hasSize(2)
    }

    @Test
    fun `ask me later holds every Still true? message for a week, and the question is still there after`() {
        dueFd("SBI FD")
        val paused = post("/api/v1/me/notification-preferences/still-true/ask-later", owner)
        assertThat(paused.statusCode.value()).describedAs(paused.body).isEqualTo(200)
        assertThat(paused.json().path("stillTruePausedUntil").asText()).isNotBlank()

        sweep.run()
        assertThat(inApp(ownerUserId)).isEmpty()
        assertThat(get("/api/v1/households/$householdId/still-true", owner).json().path("items"))
            .describedAs("the records are still in the app, to answer whenever")
            .hasSize(1)

        db.update(
            "update notification_preferences set still_true_paused_until = current_date - 1 where user_id = ?::uuid",
            ownerUserId,
        )
        sweep.run()
        assertThat(inApp(ownerUserId)).hasSize(1)
    }

    @Test
    fun `nothing is asked on a family member's birthday`() {
        val today = householdToday()
        post(
            "/api/v1/households/$householdId/members", owner,
            mapOf("displayName" to "Nanna", "relationship" to "parent", "dateOfBirth" to today.minusYears(71).toString()),
        )
        dueFd("Nanna's LIC")

        sweep.run()
        assertThat(inApp(ownerUserId)).isEmpty()

        db.update(
            "update members set date_of_birth = ?::date where household_id = ?::uuid and display_name = 'Nanna'",
            today.minusYears(71).plusDays(1).toString(), householdId,
        )
        sweep.run()
        assertThat(inApp(ownerUserId)).describedAs("the day after, it is asked").hasSize(1)
    }

    @Test
    fun `nothing is asked on the anniversary of a death the family recorded`() {
        val today = householdToday()
        val added = post(
            "/api/v1/households/$householdId/members", owner,
            mapOf("displayName" to "Thatha", "relationship" to "other", "diedOn" to today.minusYears(3).toString()),
        )
        assertThat(added.statusCode.value()).describedAs(added.body).isEqualTo(201)
        assertThat(added.json().path("diedOn").asText()).isEqualTo(today.minusYears(3).toString())
        dueFd("Thatha's FD")

        sweep.run()
        assertThat(inApp(ownerUserId)).isEmpty()
    }

    @Test
    fun `a date of death in the future, or before the birth, is refused`() {
        val today = householdToday()
        val future = post(
            "/api/v1/households/$householdId/members", owner,
            mapOf("displayName" to "Someone", "diedOn" to today.plusDays(2).toString()),
        )
        assertThat(future.statusCode.value()).isEqualTo(400)
        assertThat(future.errorCode()).isEqualTo("died_on_future")
        val beforeBirth = post(
            "/api/v1/households/$householdId/members", owner,
            mapOf("displayName" to "Someone", "dateOfBirth" to "1950-01-01", "diedOn" to "1949-12-31"),
        )
        assertThat(beforeBirth.errorCode()).isEqualTo("died_on_before_birth")
    }

    @Test
    fun `a 29 February birthday is kept on 28 February in a year without one`() {
        db.update(
            "insert into members (household_id, display_name, date_of_birth) values (?::uuid, 'Leap', date '2000-02-29')",
            householdId,
        )
        fun remembered(day: String) = db.queryForObject(
            "select app.is_remembrance_day(?::uuid, ?::date)", Boolean::class.java, householdId, day,
        )
        assertThat(remembered("2027-02-28")).isTrue()
        assertThat(remembered("2028-02-28")).describedAs("2028 has a 29th").isFalse()
        assertThat(remembered("2028-02-29")).isTrue()
        assertThat(remembered("2027-03-01")).isFalse()
    }

    @Test
    fun `the message asks gently, and gives the reason on the same line`() {
        val id = capture(
            owner, householdId, "fd", "Matured FD", BigDecimal(240_000),
            attributes = mapOf("interest_rate" to 7.1),
        ).path("id").asText()
        db.update(
            """
            update investments set created_at = now() - interval '2 months', last_verified_at = null,
                   maturity_date = current_date - 10
             where id = ?::uuid
            """.trimIndent(),
            id,
        )

        sweep.run()

        val title = inApp(ownerUserId).single().title
        assertThat(title).isEqualTo("Is this still right? A date on 1 record has passed")
        assertThat(title).doesNotContain("Matured", "2,40,000", "240000")
    }
}
