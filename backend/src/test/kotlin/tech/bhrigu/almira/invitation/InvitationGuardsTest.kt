package tech.bhrigu.almira.invitation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.support.ApiTestBase

/**
 * An invitation is for somebody who does not sign in here yet.
 *
 * Found by driving the real app (2026-09-16): an owner who typed their own
 * number into "Invite Ravi" got a link, and accepting it handed back the member
 * row they already had, reported a role they do not have, and spent a
 * single-use invitation on nothing. Ravi stayed unclaimed, while the household's
 * list showed the invitation as used.
 *
 * Both ends are guarded, and each guard runs before the thing it guards: the
 * invitation is never written, and the link is never spent.
 */
@DisplayName("An invitation is refused for somebody who already signs in to the household")
class InvitationGuardsTest : ApiTestBase() {

    private fun invitations(token: String, householdId: String) =
        get("/api/v1/households/$householdId/invitations", token).json()

    private fun myRole(token: String, householdId: String) =
        get("/api/v1/households/$householdId", token).json().path("myRole").asText()

    @Test
    fun `inviting a number that already signs in here is refused, and nothing is written`() {
        val herPhone = uniquePhone()
        val owner = signIn(herPhone)
        val householdId = createHousehold(owner, "Koduri", "private", "Ishwarya").path("id").asText()
        val ravi = addMember(owner, householdId, "Ravi").path("id").asText()

        val refused = post(
            "/api/v1/households/$householdId/invitations", owner,
            mapOf("memberId" to ravi, "phone" to herPhone, "role" to "editor"),
        )
        assertThat(refused.status()).describedAs(refused.body).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(refused.errorCode()).isEqualTo("already_a_member")
        assertThat(refused.body).describedAs("said by the name the household knows").contains("Ishwarya")
        assertThat(invitations(owner, householdId)).describedAs("no invitation was written").isEmpty()

        // The same call for somebody who does not sign in here still works.
        val issued = post(
            "/api/v1/households/$householdId/invitations", owner,
            mapOf("memberId" to ravi, "phone" to uniquePhone(), "role" to "editor"),
        )
        assertThat(issued.status()).describedAs(issued.body).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `accepting one when you already sign in here is refused, and the link still works for the right person`() {
        val owner = signIn()
        val householdId = createHousehold(owner, "Rao", "private", "Lakshmi").path("id").asText()
        val chandra = addMember(owner, householdId, "Chandra").path("id").asText()
        val token = post(
            "/api/v1/households/$householdId/invitations", owner,
            mapOf("memberId" to chandra, "phone" to uniquePhone(), "role" to "editor"),
        ).json().path("token").asText()

        val refused = post("/api/v1/invitations/accept", owner, mapOf("token" to token))
        assertThat(refused.status()).describedAs(refused.body).isEqualTo(HttpStatus.CONFLICT)
        assertThat(refused.errorCode()).isEqualTo("already_a_member")
        assertThat(myRole(owner, householdId)).describedAs("her own role is untouched").isEqualTo("owner")
        // A null field is left out of the JSON entirely (spring.jackson non_null).
        val stillOpen = invitations(owner, householdId).first().path("acceptedAt")
        assertThat(stillOpen.isMissingNode || stillOpen.isNull)
            .describedAs("the link was not spent: $stillOpen").isTrue()

        // And the person it was for can still use it.
        val chandraToken = signIn()
        val accepted = post("/api/v1/invitations/accept", chandraToken, mapOf("token" to token))
        assertThat(accepted.status()).describedAs(accepted.body).isEqualTo(HttpStatus.OK)
        assertThat(accepted.json().path("memberId").asText()).isEqualTo(chandra)
        assertThat(myRole(chandraToken, householdId)).isEqualTo("editor")
    }
}
