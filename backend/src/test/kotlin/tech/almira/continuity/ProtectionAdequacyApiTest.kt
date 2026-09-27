package tech.almira.continuity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.math.BigDecimal

@DisplayName("Protection adequacy: term cover against expenses, in words, as information")
class ProtectionAdequacyApiTest : ContinuitySignalsTestBase() {

    private val path get() = "/api/v1/households/$householdId/continuity/protection"

    private fun termPolicy(token: String, title: String, sumAssured: Long, visibility: String = "household") =
        capture(
            token, householdId, "insurance_term", title, BigDecimal("30000"), visibility = visibility,
            attributes = mapOf("policy_no" to "T-1", "sum_assured" to sumAssured, "premium_amount" to 12000),
        )

    private fun inputs(token: String, body: Map<String, Any?>) = call(HttpMethod.PUT, "$path/inputs", token, body)

    @Test
    fun `without expenses it says what to add, and never shows a number it did not earn`() {
        termPolicy(owner, "LIC Tech Term", 10_000_000)
        val view = get(path, owner).json()
        assertThat(view.path("status").asText()).isEqualTo("needs_expenses")
        assertThat(view.has("multiple")).isFalse()
        assertThat(view.path("termCoverFormatted").asText()).isEqualTo("₹1,00,00,000")
        assertThat(view.path("termCoverInWords").asText()).isEqualTo("One Crore")
    }

    @Test
    fun `term cover against annual expenses, words first, with the people who depend on it`() {
        termPolicy(owner, "LIC Tech Term", 10_000_000)
        val aarav = addMember(owner, householdId, "Aarav").path("id").asText()

        val saved = inputs(owner, mapOf("annualExpenses" to 2_400_000, "dependantMemberIds" to listOf(aarav)))
        assertThat(saved.status()).describedAs(saved.body).isEqualTo(HttpStatus.OK)
        val view = saved.json()
        assertThat(view.path("status").asText()).isEqualTo("ready")
        assertThat(view.path("headline").asText()).isEqualTo("Term cover is about 4.1× annual expenses.")
        assertThat(view.path("multiple").decimalValue()).isEqualByComparingTo("4.1")
        assertThat(view.path("gaugePercent").asInt()).isEqualTo(20)
        assertThat(view.path("annualExpensesFormatted").asText()).isEqualTo("₹24,00,000")
        assertThat(view.path("details").map { it.asText() })
            .anyMatch { it.contains("roughly 4 years") }
            .anyMatch { it.contains("Aarav") }
        assertThat(view.path("caveats").first().asText()).startsWith("This is information, not advice.")
        assertThat(view.toString()).doesNotContainIgnoringCase("enough").doesNotContainIgnoringCase("should")
    }

    @Test
    fun `each person's view is their own`() {
        termPolicy(owner, "Her term plan", 10_000_000)
        termPolicy(trusted, "Ravi's private term plan", 50_000_000, visibility = "private")
        inputs(owner, mapOf("annualExpenses" to 1_000_000))

        val hers = get(path, owner).json()
        assertThat(hers.path("policies").map { it.path("title").asText() }).containsExactly("Her term plan")
        assertThat(hers.path("termCover").decimalValue()).isEqualByComparingTo("10000000")

        val his = get(path, trusted).json()
        assertThat(his.path("status").asText()).describedAs("her expenses are hers").isEqualTo("needs_expenses")
        assertThat(his.path("termCover").decimalValue()).isEqualByComparingTo("60000000")
    }

    @Test
    fun `ended cover is not cover, and the inputs are checked`() {
        val ended = termPolicy(owner, "Old term plan", 5_000_000).path("id").asText()
        db.update("update investments set maturity_date = current_date - 1 where id = ?::uuid", ended)
        assertThat(get(path, owner).json().path("termCover").decimalValue()).isEqualByComparingTo("0")

        assertThat(inputs(owner, mapOf("annualExpenses" to -5)).errorCode()).isEqualTo("expenses_invalid")
        assertThat(inputs(owner, mapOf("dependantMemberIds" to listOf(ownerMemberId))).errorCode())
            .isEqualTo("dependant_is_you")
        assertThat(inputs(owner, mapOf("dependantMemberIds" to listOf(java.util.UUID.randomUUID().toString()))).errorCode())
            .isEqualTo("member_unknown")
    }
}
