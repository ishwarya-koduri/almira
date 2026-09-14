package tech.bhrigu.almira.privacy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.support.ApiTestBase
import java.time.LocalDate

/**
 * A parent's consent for a child's records (DPDP Act s.9, Rule 10; docs/05 §6).
 * Aarav is ten and has no login; Ishwarya adds him as his parent.
 */
@DisplayName("Parental consent for a child's records")
class ParentalConsentApiTest : ApiTestBase() {

    private lateinit var owner: String
    private lateinit var spouse: String
    private lateinit var outsider: String
    private lateinit var householdId: String
    private lateinit var aaravId: String

    @BeforeEach
    fun setUp() {
        owner = signIn()
        spouse = signIn()
        outsider = signIn()
        createHousehold(outsider, "Strangers")
        householdId = createHousehold(owner, "Koduri", "private", "Ishwarya").path("id").asText()
        val spouseMember = addMember(owner, householdId, "Ravi").path("id").asText()
        joinHousehold(owner, householdId, spouseMember, spouse, role = "admin")
        aaravId = person("Aarav", LocalDate.now().minusYears(10))
    }

    private fun person(name: String, dateOfBirth: LocalDate?): String = post(
        "/api/v1/households/$householdId/members", owner,
        buildMap {
            put("displayName", name)
            put("relationship", "child")
            if (dateOfBirth != null) put("dateOfBirth", dateOfBirth.toString())
        },
    ).json().path("id").asText()

    private fun stepUp(token: String) {
        val challenge = post("/api/v1/auth/step-up/request", token).json()
        post(
            "/api/v1/auth/step-up/verify", token,
            mapOf("code" to challenge.path("developmentCode").asText(),
                  "requestId" to challenge.path("requestId").asText()),
        )
    }

    private fun consent(token: String = owner, memberId: String = aaravId, confirmAdult: Boolean = true) = post(
        "/api/v1/households/$householdId/members/$memberId/parental-consent", token,
        mapOf("capacity" to "parent", "confirmAdult" to confirmAdult),
    )

    @Test
    fun `consent needs the one-time check before it is recorded`() {
        val refused = consent()
        assertThat(refused.status()).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(refused.errorCode()).isEqualTo("step_up_required")
        assertThat(get("/api/v1/households/$householdId/parental-consents", owner).json().size()).isZero()

        stepUp(owner)
        val given = consent()
        assertThat(given.status()).isEqualTo(HttpStatus.CREATED)
        val line = given.json()
        assertThat(line.path("memberId").asText()).isEqualTo(aaravId)
        assertThat(line.path("capacity").asText()).isEqualTo("parent")
        assertThat(line.path("verification").asText()).isEqualTo("step_up_code")
        assertThat(line.path("givenByName").asText()).isEqualTo("Ishwarya")
        assertThat(line.path("givenAt").asText()).isNotEmpty()
        assertThat(line.path("noticeVersion").asText()).isNotEmpty()

        val audited = db.queryForObject(
            "select count(*) from activity_log where action = 'privacy.parental_consent_give' and entity_id = ?::uuid",
            Int::class.java, aaravId,
        )
        assertThat(audited).isEqualTo(1)
    }

    @Test
    fun `the household sees the consent line on the child, and nobody outside it does`() {
        stepUp(owner)
        consent()

        val seenBySpouse = get("/api/v1/households/$householdId/parental-consents", spouse).json()
        assertThat(seenBySpouse.size()).isEqualTo(1)
        assertThat(seenBySpouse[0].path("givenByMe").asBoolean()).isFalse()

        assertThat(get("/api/v1/households/$householdId/parental-consents", outsider).status())
            .isEqualTo(HttpStatus.NOT_FOUND)
        stepUp(outsider)
        assertThat(consent(token = outsider).status())
            .describedAs("an outsider learns nothing, not even that the child exists")
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `it is only for a child, and only with the declaration`() {
        stepUp(owner)
        val adult = person("Amma", LocalDate.now().minusYears(60))
        assertThat(consent(memberId = adult).errorCode()).isEqualTo("not_a_minor")

        val noBirthday = person("Someone", null)
        assertThat(consent(memberId = noBirthday).errorCode())
            .describedAs("without a date of birth nobody is a minor on record").isEqualTo("not_a_minor")

        assertThat(consent(confirmAdult = false).errorCode()).isEqualTo("adult_confirmation_required")

        assertThat(consent().status()).isEqualTo(HttpStatus.CREATED)
        assertThat(consent().errorCode()).isEqualTo("consent_exists")
    }

    @Test
    fun `only the adult who gave consent withdraws it, and it stays on the timeline`() {
        stepUp(owner)
        val id = consent().json().path("id").asText()

        val bySpouse = post("/api/v1/households/$householdId/parental-consents/$id/withdraw", spouse)
        assertThat(bySpouse.status()).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(bySpouse.errorCode()).isEqualTo("not_your_consent")

        val withdrawn = post("/api/v1/households/$householdId/parental-consents/$id/withdraw", owner)
        assertThat(withdrawn.status()).isEqualTo(HttpStatus.OK)
        assertThat(withdrawn.json().path("withdrawnAt").isNull).isFalse()

        val history = get("/api/v1/me/privacy/history", owner).json()
            .filter { it.path("kind").asText() == "parental_consent" }
        assertThat(history.map { it.path("action").asText() }).containsExactlyInAnyOrder("given", "withdrawn")
        assertThat(history.all { it.path("subject").asText() == "Aarav" }).isTrue()

        // A second parent may now give theirs.
        stepUp(spouse)
        assertThat(consent(token = spouse).status()).isEqualTo(HttpStatus.CREATED)
    }
}
