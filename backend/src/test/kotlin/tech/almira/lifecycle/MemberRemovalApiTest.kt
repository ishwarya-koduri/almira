package tech.almira.lifecycle

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.math.BigDecimal

@DisplayName("Removing someone: what is in the way is counted, never shown")
class MemberRemovalApiTest : LifecycleTestSupport() {

    private lateinit var ishwarya: String
    private lateinit var ravi: String
    private lateinit var householdId: String
    private lateinit var raviMemberId: String
    private lateinit var aaravMemberId: String

    @BeforeEach
    fun setUp() {
        ishwarya = signIn()
        ravi = signIn()
        householdId = createHousehold(ishwarya, "Koduri", "private", "Ishwarya").path("id").asText()
        raviMemberId = addMember(ishwarya, householdId, "Ravi").path("id").asText()
        joinHousehold(ishwarya, householdId, raviMemberId, ravi, role = "admin")
        aaravMemberId = addMember(ishwarya, householdId, "Aarav").path("id").asText()
    }

    @Test
    fun `a holding the admin cannot see still blocks removal, and its recorder is asked to move it`() {
        // Ishwarya records a private gold coin in Aarav's name. Ravi, an admin,
        // cannot see it — and before, his count of Aarav's holdings was zero.
        capture(
            ishwarya, householdId, "gold_physical", "Aarav's coin", BigDecimal(50_000),
            visibility = "private", owners = listOf(mapOf("memberId" to aaravMemberId, "sharePct" to 100)),
        )

        val refused = delete("/api/v1/households/$householdId/members/$aaravMemberId", ravi)
        assertThat(refused.status()).describedAs(refused.body).isEqualTo(HttpStatus.CONFLICT)
        assertThat(refused.errorCode()).isEqualTo("member_has_holdings")
        assertThat(refused.json().path("error").path("details").path("hidden").asInt()).isEqualTo(1)
        assertThat(refused.body).doesNotContain("coin")

        assertThat(members(ravi, householdId).map { it.path("id").asText() }).contains(aaravMemberId)
        assertThat(templatesFor(userId(ishwarya))).contains("household.member_removal_blocked")
    }

    @Test
    fun `a person with their own login is asked to leave rather than deleted`() {
        val refused = delete("/api/v1/households/$householdId/members/$raviMemberId", ishwarya)
        assertThat(refused.status()).isEqualTo(HttpStatus.CONFLICT)
        assertThat(refused.errorCode()).isEqualTo("member_has_login")
    }

    @Test
    fun `a managed member with nothing in their name is removed as before`() {
        val removed = delete("/api/v1/households/$householdId/members/$aaravMemberId", ravi)
        assertThat(removed.status()).isEqualTo(HttpStatus.NO_CONTENT)
    }
}
