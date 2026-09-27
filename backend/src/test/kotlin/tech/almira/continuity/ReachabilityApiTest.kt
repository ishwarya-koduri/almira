package tech.almira.continuity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

@DisplayName("Still reachable: a yearly tap from each trusted contact, and a dated tick for the owner")
class ReachabilityApiTest : ContinuitySignalsTestBase() {

    private fun contacts(token: String) = get("/api/v1/households/$householdId/emergency/contacts", token).json()

    @Test
    fun `only the person named can say they are reachable, and the owner sees the date`() {
        val contactId = nameContact()
        assertThat(contacts(owner).first().has("reachableConfirmedAt")).isFalse()

        val byOwner = post("/api/v1/households/$householdId/emergency/contacts/$contactId/reachable", owner)
        assertThat(byOwner.status()).describedAs("nobody can vouch for someone else").isEqualTo(HttpStatus.NOT_FOUND)

        val outsider = signIn()
        createHousehold(outsider, "Elsewhere")
        assertThat(post("/api/v1/households/$householdId/emergency/contacts/$contactId/reachable", outsider).status())
            .isEqualTo(HttpStatus.NOT_FOUND)

        val confirmed = post("/api/v1/households/$householdId/emergency/contacts/$contactId/reachable", trusted)
        assertThat(confirmed.status()).isEqualTo(HttpStatus.OK)
        assertThat(confirmed.json().path("current").asBoolean()).isTrue()

        assertThat(contacts(owner).first().path("reachableConfirmedAt").asText()).isNotBlank()
        assertThat(audited("continuity.reachable")).isEqualTo(1)
    }

    @Test
    fun `the owner can ask now, but not twice in a week`() {
        val contactId = nameContact()
        val asked = post("/api/v1/households/$householdId/emergency/contacts/$contactId/reachable/ask", owner)
        assertThat(asked.status()).isEqualTo(HttpStatus.OK)
        assertThat(asked.json().path("askedAt").asText()).isNotBlank()
        assertThat(inAppMessages(trustedMemberId, ContinuityNotices.REACHABLE)).isEqualTo(1)

        val again = post("/api/v1/households/$householdId/emergency/contacts/$contactId/reachable/ask", owner)
        assertThat(again.errorCode()).isEqualTo("asked_recently")

        assertThat(post("/api/v1/households/$householdId/emergency/contacts/$contactId/reachable/ask", trusted).status())
            .describedAs("the person named does not ask themselves").isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `the sweep asks once a year, and a one-tap link leaves the tick`() {
        val contactId = nameContact()
        sweep.run()
        assertThat(inAppMessages(trustedMemberId, ContinuityNotices.REACHABLE))
            .describedAs("a contact named today was told today; the year starts now").isZero()

        db.update("update emergency_contacts set created_at = now() - interval '400 days' where id = ?::uuid", contactId)
        sweep.run()
        assertThat(inAppMessages(trustedMemberId, ContinuityNotices.REACHABLE)).isEqualTo(1)
        sweep.run()
        assertThat(inAppMessages(trustedMemberId, ContinuityNotices.REACHABLE)).describedAs("once a year").isEqualTo(1)

        val (token, hash) = newToken()
        db.update(
            """
            insert into continuity_links (purpose, token_hash, household_id, user_id, emergency_contact_id, expires_at)
            values ('reachable', ?, ?::uuid, ?::uuid, ?::uuid, now() + interval '30 days')
            """.trimIndent(),
            hash, householdId, userOf(trustedMemberId), contactId,
        )
        val tapped = post("/api/v1/continuity-links/redeem", body = mapOf("token" to token))
        assertThat(tapped.status()).isEqualTo(HttpStatus.OK)
        assertThat(tapped.json().path("purpose").asText()).isEqualTo("reachable")
        assertThat(contacts(owner).first().path("reachableConfirmedAt").asText()).isNotBlank()
        assertThat(
            db.queryForObject(
                "select via from trusted_contact_confirmations where emergency_contact_id = ?::uuid",
                String::class.java, contactId,
            ),
        ).isEqualTo("link")
    }

    @Test
    fun `a link for someone who is no longer the contact confirms nothing`() {
        val contactId = nameContact()
        val (token, hash) = newToken()
        db.update(
            """
            insert into continuity_links (purpose, token_hash, household_id, user_id, emergency_contact_id, expires_at)
            values ('reachable', ?, ?::uuid, ?::uuid, ?::uuid, now() + interval '30 days')
            """.trimIndent(),
            hash, householdId, userOf(ownerMemberId), contactId,
        )
        assertThat(post("/api/v1/continuity-links/redeem", body = mapOf("token" to token)).status())
            .isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(
            db.queryForObject(
                "select count(*) from trusted_contact_confirmations where emergency_contact_id = ?::uuid",
                Int::class.java, contactId,
            ),
        ).isZero()
    }
}
