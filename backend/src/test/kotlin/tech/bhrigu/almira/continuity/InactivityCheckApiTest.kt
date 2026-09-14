package tech.bhrigu.almira.continuity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.math.BigDecimal

@DisplayName("If I go quiet: two gentle check-ins, then the ordinary request, with the wait and the veto")
class InactivityCheckApiTest : ContinuitySignalsTestBase() {

    private val path get() = "/api/v1/households/$householdId/emergency/inactivity"

    private fun turnOn(periodDays: Int = 90) {
        stepUp(owner)
        val on = call(org.springframework.http.HttpMethod.PUT, path, owner, mapOf("enabled" to true, "periodDays" to periodDays))
        assertThat(on.status()).describedAs(on.body).isEqualTo(HttpStatus.OK)
    }

    /** Nobody has seen the owner for [days]: no sessions, no taps, and it was turned on before that. */
    private fun quietFor(days: Int) {
        db.update(
            "update user_sessions set last_used_at = now() - make_interval(days => ?) where user_id = ?::uuid",
            days, userOf(ownerMemberId),
        )
        db.update(
            """
            update inactivity_checks set enabled_at = now() - make_interval(days => ?), last_check_in_at = null
            where household_id = ?::uuid
            """.trimIndent(),
            days, householdId,
        )
    }

    private fun check(column: String): String? = db.queryForObject(
        "select $column::text from inactivity_checks where household_id = ?::uuid",
        String::class.java, householdId,
    )

    private fun ageStamp(column: String, days: Int) {
        db.update(
            "update inactivity_checks set $column = $column - make_interval(days => ?) where household_id = ?::uuid",
            days, householdId,
        )
    }

    private fun raisedRequests(): List<Map<String, Any>> = db.queryForList(
        "select requested_by::text as by, raised_by, vetoed_at from emergency_requests where household_id = ?::uuid",
        householdId,
    )

    @Test
    fun `it is off unless the owner turns it on, and turning it on needs a step-up and someone who can ask`() {
        val initial = get(path, owner).json()
        assertThat(initial.path("enabled").asBoolean()).isFalse()
        assertThat(initial.path("stage").asText()).isEqualTo("off")
        assertThat(initial.path("steps").map { it.path("day").asInt() }).containsExactly(0, 30, 60)

        val nobody = call(org.springframework.http.HttpMethod.PUT, path, owner, mapOf("enabled" to true))
        assertThat(nobody.errorCode()).describedAs("going quiet with nobody to ask could start nothing").isEqualTo("nobody_to_ask")

        nameContact()
        val unconfirmed = call(org.springframework.http.HttpMethod.PUT, path, owner, mapOf("enabled" to true))
        assertThat(unconfirmed.errorCode()).isEqualTo("step_up_required")

        turnOn(90)
        val on = get(path, owner).json()
        assertThat(on.path("enabled").asBoolean()).isTrue()
        assertThat(on.path("stage").asText()).isEqualTo("quiet")
        assertThat(on.path("contactsWhoCanAsk").map { it.asText() }).containsExactly("Ravi")
        assertThat(on.path("steps")[2].path("sentence").asText()).contains("14-day wait")
        assertThat(audited("continuity.inactivity.on")).isEqualTo(1)

        val tooShort = call(org.springframework.http.HttpMethod.PUT, path, owner, mapOf("enabled" to true, "periodDays" to 7))
        assertThat(tooShort.errorCode()).isEqualTo("period_invalid")

        assertThat(get(path, trusted).json().path("enabled").asBoolean())
            .describedAs("each person's setting is their own; Ravi sees his, which is off")
            .isFalse()
    }

    @Test
    fun `nothing happens while the owner is around`() {
        nameContact()
        turnOn(60)
        quietFor(30)
        sweep.run()
        assertThat(check("first_reminder_at")).isNull()
        assertThat(inAppMessages(ownerMemberId, ContinuityNotices.CHECK_IN)).isZero()
    }

    @Test
    fun `two check-ins, a month apart, then the request begins in the contact's name`() {
        nameContact(waitDays = 14)
        turnOn(90)

        quietFor(91)
        sweep.run()
        assertThat(check("first_reminder_at")).isNotNull()
        assertThat(inAppMessages(ownerMemberId, ContinuityNotices.CHECK_IN)).isEqualTo(1)
        sweep.run()
        assertThat(inAppMessages(ownerMemberId, ContinuityNotices.CHECK_IN))
            .describedAs("a re-run asks nothing new").isEqualTo(1)
        assertThat(check("second_reminder_at")).isNull()

        ageStamp("first_reminder_at", 31)
        sweep.run()
        assertThat(check("second_reminder_at")).isNotNull()
        assertThat(inAppMessages(ownerMemberId, ContinuityNotices.CHECK_IN)).isEqualTo(2)
        assertThat(raisedRequests()).isEmpty()

        ageStamp("first_reminder_at", 31)
        ageStamp("second_reminder_at", 31)
        sweep.run()
        assertThat(check("raised_at")).isNotNull()
        val raised = raisedRequests()
        assertThat(raised).hasSize(1)
        assertThat(raised.first()["by"]).isEqualTo(userOf(trustedMemberId))
        assertThat(raised.first()["raised_by"]).isEqualTo("inactivity")
        assertThat(inAppMessages(trustedMemberId, ContinuityNotices.RAISED)).isEqualTo(1)
        assertThat(inAppMessages(ownerMemberId, ContinuityNotices.REQUESTED)).isEqualTo(1)
        assertThat(audited("continuity.inactivity.raised")).isEqualTo(1)

        sweep.run()
        assertThat(raisedRequests()).describedAs("raised once per cycle").hasSize(1)

        // The ordinary request: waiting, visible to both, and nothing has opened.
        val theirs = get("/api/v1/households/$householdId/emergency/requests", trusted).json()
        assertThat(theirs).hasSize(1)
        assertThat(theirs.first().path("status").asText()).isEqualTo("waiting")
        assertThat(theirs.first().path("raisedBy").asText()).isEqualTo("inactivity")

        // "I'm here" from inside the app stops it, like a veto.
        val here = post("$path/check-in", owner)
        assertThat(here.status()).isEqualTo(HttpStatus.OK)
        assertThat(here.json().path("stage").asText()).isEqualTo("quiet")
        val after = get("/api/v1/households/$householdId/emergency/requests", owner).json()
        assertThat(after.first().path("status").asText()).isEqualTo("vetoed")
    }

    @Test
    fun `an I'm here starts the count again`() {
        nameContact()
        turnOn(90)
        quietFor(91)
        sweep.run()
        assertThat(check("first_reminder_at")).isNotNull()

        post("$path/check-in", owner)
        ageStamp("first_reminder_at", 31)
        sweep.run()
        assertThat(check("second_reminder_at"))
            .describedAs("the first reminder belonged to a cycle the tap finished").isNull()
    }

    @Test
    fun `the one-tap link says I'm here once, needs no password and reveals nothing`() {
        nameContact()
        turnOn(90)
        val checkId = db.queryForObject(
            "select id::text from inactivity_checks where household_id = ?::uuid", String::class.java, householdId,
        )!!
        val (token, hash) = newToken()
        db.update(
            """
            insert into continuity_links (purpose, token_hash, household_id, user_id, inactivity_check_id, expires_at)
            values ('check_in', ?, ?::uuid, ?::uuid, ?::uuid, now() + interval '30 days')
            """.trimIndent(),
            hash, householdId, userOf(ownerMemberId), checkId,
        )

        val tapped = post("/api/v1/continuity-links/redeem", body = mapOf("token" to token))
        assertThat(tapped.status()).isEqualTo(HttpStatus.OK)
        assertThat(tapped.json().path("purpose").asText()).isEqualTo("check_in")
        assertThat(tapped.body).doesNotContain("Ishwarya", "Ravi", "Koduri", householdId)
        assertThat(check("last_check_in_at")).isNotNull()
        assertThat(audited("continuity.check_in")).isEqualTo(1)

        val again = post("/api/v1/continuity-links/redeem", body = mapOf("token" to token))
        assertThat(again.status()).describedAs("single use").isEqualTo(HttpStatus.NOT_FOUND)

        val (expired, expiredHash) = newToken()
        db.update(
            """
            insert into continuity_links (purpose, token_hash, household_id, user_id, inactivity_check_id,
                                          created_at, expires_at)
            values ('check_in', ?, ?::uuid, ?::uuid, ?::uuid, now() - interval '31 days', now() - interval '1 day')
            """.trimIndent(),
            expiredHash, householdId, userOf(ownerMemberId), checkId,
        )
        val late = post("/api/v1/continuity-links/redeem", body = mapOf("token" to expired))
        assertThat(late.status()).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(late.json().path("error").path("message").asText())
            .describedAs("used and expired read the same")
            .isEqualTo(again.json().path("error").path("message").asText())

        assertThat(post("/api/v1/continuity-links/redeem", body = mapOf("token" to "not-a-token")).status())
            .isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(db.queryForObject("select count(*) from continuity_links where token_hash = ?", Int::class.java, token))
            .describedAs("only the hash is stored").isZero()
    }

    @Test
    fun `a request raised by going quiet opens only in silence, and an I'm here keeps it shut`() {
        val gold = capture(owner, householdId, "gold_physical", "Her gold", BigDecimal("500000"), visibility = "private")
        nameContact(waitDays = 14)
        turnOn(90)
        quietFor(200)
        sweep.run()
        ageStamp("first_reminder_at", 31)
        sweep.run()
        ageStamp("first_reminder_at", 31)
        ageStamp("second_reminder_at", 31)
        sweep.run()
        assertThat(raisedRequests()).hasSize(1)

        // Past the wait, in silence: it opens, exactly as a request made by hand does.
        db.update(
            """
            update emergency_requests set requested_at = now() - interval '20 days', unlock_at = now() - interval '6 days'
            where household_id = ?::uuid
            """.trimIndent(),
            householdId,
        )
        db.update(
            "update user_sessions set last_used_at = now() - interval '30 days' where user_id = ?::uuid",
            userOf(ownerMemberId),
        )
        assertThat(get("/api/v1/households/$householdId/investments", trusted).json().map { it.path("id").asText() })
            .contains(gold.path("id").asText())

        // A tap is presence, like a sign-in (V95): the window shuts again.
        db.update("update inactivity_checks set last_check_in_at = now() where household_id = ?::uuid", householdId)
        assertThat(get("/api/v1/households/$householdId/investments", trusted).json()).isEmpty()
    }
}
