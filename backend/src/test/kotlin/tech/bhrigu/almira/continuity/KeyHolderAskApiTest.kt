package tech.bhrigu.almira.continuity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.math.BigDecimal

@DisplayName("Ask the key holder: one gentle question, Yes or Not sure, and nothing sealed in it")
class KeyHolderAskApiTest : ContinuitySignalsTestBase() {

    private val asks get() = "/api/v1/households/$householdId/key-holder-asks"

    private fun locker(visibility: String = "household", token: String = owner) =
        capture(token, householdId, "gold_physical", "the SBI locker key", BigDecimal("100000"), visibility = visibility)
            .path("id").asText()

    private fun ask(recordId: String, memberId: String = trustedMemberId, token: String = owner) =
        post(asks, token, mapOf("recordType" to "investment", "recordId" to recordId, "askedMemberId" to memberId))

    @Test
    fun `the question carries the record's title and nothing else, and the answer lights the owner's readiness`() {
        val id = locker()
        val asked = ask(id)
        assertThat(asked.status()).isEqualTo(HttpStatus.CREATED)
        assertThat(asked.json().path("question").asText()).isEqualTo("Do you know where the SBI locker key is?")
        assertThat(inAppMessages(trustedMemberId, ContinuityNotices.KEY_HOLDER_ASK)).isEqualTo(1)
        assertThat(ask(id).json().path("id").asText())
            .describedAs("asking again while it is open is the same question").isEqualTo(asked.json().path("id").asText())

        val forRavi = get(asks, trusted).json()
        assertThat(forRavi.path("forMe")).hasSize(1)
        assertThat(forRavi.path("asked")).isEmpty()

        val askId = asked.json().path("id").asText()
        assertThat(post("$asks/$askId/answer", owner, mapOf("answer" to "yes")).status())
            .describedAs("the asker does not answer for them").isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(post("$asks/$askId/answer", trusted, mapOf("answer" to "maybe")).errorCode()).isEqualTo("answer_invalid")

        val answered = post("$asks/$askId/answer", trusted, mapOf("answer" to "yes"))
        assertThat(answered.status()).isEqualTo(HttpStatus.OK)
        assertThat(answered.json().path("answer").asText()).isEqualTo("yes")
        assertThat(inAppMessages(ownerMemberId, ContinuityNotices.KEY_HOLDER_ANSWER)).isEqualTo(1)
        assertThat(audited("continuity.key_holder.answer")).isEqualTo(1)

        val readiness = get("/api/v1/households/$householdId/continuity/readiness", owner).json()
        val answers = readiness.path("keyHolderAnswers")
        assertThat(answers.path("knows").asInt()).isEqualTo(1)
        assertThat(answers.path("items").first().path("askedName").asText()).isEqualTo("Ravi")
        assertThat(answers.path("explanation").asText()).contains("does not change the score")
        assertThat(get("/api/v1/households/$householdId/continuity/readiness", trusted).json().has("keyHolderAnswers"))
            .describedAs("it is on the asker's readiness, not the person asked").isFalse()
    }

    @Test
    fun `nobody else in the household learns who was asked`() {
        val sita = signIn()
        val sitaMember = addMember(owner, householdId, "Sita").path("id").asText()
        joinHousehold(owner, householdId, sitaMember, sita)
        ask(locker())

        val hers = get(asks, sita).json()
        assertThat(hers.path("asked")).isEmpty()
        assertThat(hers.path("forMe")).isEmpty()
    }

    @Test
    fun `a record you cannot see cannot be asked about, and someone who cannot sign in cannot be asked`() {
        val ravisPrivate = capture(
            trusted, householdId, "gold_physical", "Ravi's own locker", BigDecimal("1"), visibility = "private",
        ).path("id").asText()
        assertThat(ask(ravisPrivate, memberId = trustedMemberId).status())
            .describedAs("a private record reads as one that does not exist").isEqualTo(HttpStatus.NOT_FOUND)

        val aarav = addMember(owner, householdId, "Aarav").path("id").asText()
        assertThat(ask(locker(), memberId = aarav).errorCode()).isEqualTo("cannot_sign_in")
        assertThat(ask(locker(), memberId = ownerMemberId).errorCode()).isEqualTo("asked_is_you")
    }

    @Test
    fun `someone a record is shared with cannot carry its title to a member it was never shared with`() {
        val sita = signIn()
        val sitaMember = addMember(owner, householdId, "Sita").path("id").asText()
        joinHousehold(owner, householdId, sitaMember, sita)
        val shared = capture(
            owner, householdId, "gold_physical", "the loan papers", BigDecimal("1"),
            visibility = "scoped", visibleTo = listOf(trustedMemberId),
        ).path("id").asText()

        val toSita = ask(shared, memberId = sitaMember, token = trusted)
        assertThat(toSita.status())
            .describedAs("Ravi sees it, Sita does not, and Ravi does not hold it").isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(get(asks, sita).json().path("forMe")).isEmpty()
        assertThat(inAppMessages(sitaMember, ContinuityNotices.KEY_HOLDER_ASK)).isEqualTo(0)
        assertThat(
            db.queryForObject("select count(*) from key_holder_asks where record_id = ?::uuid", Int::class.java, shared),
        ).isEqualTo(0)

        assertThat(ask(shared, memberId = ownerMemberId, token = trusted).status())
            .describedAs("asking someone who already sees it is fine").isEqualTo(HttpStatus.CREATED)
        assertThat(ask(shared, memberId = sitaMember, token = owner).status())
            .describedAs("the owner chooses whom to ask").isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `the asker can withdraw a question, and nobody else can`() {
        val askId = ask(locker()).json().path("id").asText()
        assertThat(delete("$asks/$askId", trusted).status()).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(delete("$asks/$askId", owner).status()).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(get(asks, trusted).json().path("forMe")).isEmpty()
    }
}
