package tech.bhrigu.almira.e2e

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestTemplate
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal
import javax.crypto.spec.SecretKeySpec

/**
 * A sealed line in the handbook and the emergency view is never blank
 * (docs/20 §1.2, docs/12 §10.5). It says who sealed it and who holds a way to
 * open it, so an heir reads "ask Ravi" rather than concluding nothing was
 * written down — and it says so only to someone who could already see that the
 * value exists.
 */
@DisplayName("Sealed lines say who could open them")
class SealedAccessApiTest : ApiTestBase() {

    private val r = RecoveryReference

    private lateinit var owner: String
    private lateinit var spouse: String
    private lateinit var householdId: String
    private lateinit var ownerMemberId: String
    private lateinit var spouseMemberId: String
    private lateinit var contentKey: ByteArray

    @BeforeEach
    fun setUp() {
        owner = signIn()
        spouse = signIn()
        val household = createHousehold(owner, "Koduri", "private", "Ishwarya")
        householdId = household.path("id").asText()
        ownerMemberId = household.path("myMemberId").asText()
        spouseMemberId = addMember(owner, householdId, "Ravi").path("id").asText()
        joinHousehold(owner, householdId, spouseMemberId, spouse)

        contentKey = r.randomBytes(32)
        val salt = r.randomBytes(16)
        val key = call(
            HttpMethod.PUT, "/api/v1/households/$householdId/e2e/key", owner,
            mapOf(
                "kdfSalt" to r.b64(salt), "iterations" to 100_000,
                // Not a real passphrase wrap: these tests never unlock with one.
                "wrappedKey" to r.seal(SecretKeySpec(r.randomBytes(32), "AES"), contentKey),
                "verifier" to r.seal(SecretKeySpec(contentKey, "AES"), "almira".toByteArray()),
                "contentKeyId" to r.contentKeyId(contentKey),
            ),
        )
        check(key.statusCode.is2xxSuccessful) { "key: ${key.body}" }
    }

    private fun seal(recordType: String, recordId: String, fieldKey: String = WhereAndWho.ORIGINAL_LOCATION) {
        val sealed = call(
            HttpMethod.PUT, "/api/v1/households/$householdId/e2e/values/$recordType/$recordId/$fieldKey", owner,
            mapOf("ciphertext" to r.seal(SecretKeySpec(contentKey, "AES"), "Steel almirah".toByteArray())),
        )
        check(sealed.statusCode.is2xxSuccessful) { "seal: ${sealed.body}" }
    }

    private fun shares(holders: List<String>) {
        val challenge = post("/api/v1/auth/step-up/request", owner).json()
        post(
            "/api/v1/auth/step-up/verify", owner,
            mapOf("code" to challenge.path("developmentCode").asText(), "requestId" to challenge.path("requestId").asText()),
        )
        val salt = r.randomBytes(16)
        val made = call(
            HttpMethod.PUT, "/api/v1/households/$householdId/e2e/recovery/${Recovery.SHARES}", owner,
            mapOf(
                "kdfSalt" to r.b64(salt),
                "wrappedKey" to r.seal(r.wrappingKey(r.randomBytes(21), salt, Recovery.SHARES), contentKey),
                "verifier" to r.seal(SecretKeySpec(contentKey, "AES"), "almira".toByteArray()),
                "contentKeyId" to r.contentKeyId(contentKey),
                "holders" to holders,
            ),
        )
        check(made.statusCode.is2xxSuccessful) { "shares: ${made.body}" }
    }

    private fun openWindow() {
        post("/api/v1/households/$householdId/emergency/contacts", owner, mapOf("trustedMemberId" to spouseMemberId, "waitDays" to 14))
        val asked = post("/api/v1/households/$householdId/emergency/requests", spouse, mapOf("subjectMemberId" to ownerMemberId))
        check(asked.statusCode.is2xxSuccessful) { "request: ${asked.body}" }
        db.update(
            "update emergency_requests set requested_at = now() - interval '20 days', unlock_at = now() - interval '6 days' where household_id = ?::uuid",
            householdId,
        )
        db.update(
            "update user_sessions set last_used_at = now() - interval '30 days' where user_id in (select user_id from members where household_id = ?::uuid and user_id is not null)",
            householdId,
        )
    }

    @Test
    fun `the sentence names the sealer and who can open it, and never a pronoun`() {
        assertThat(SealedAccessService.sentence("Ishwarya", null)).isEqualTo("Sealed by Ishwarya · only Ishwarya's passphrase opens it")
        assertThat(SealedAccessService.sentence(null, null))
            .isEqualTo("Sealed by you · only your passphrase opens it · make a recovery sheet so your family can")
        assertThat(SealedAccessService.sentence("Ishwarya", RecoveryPresence(true, "Ravi", false, emptyList())))
            .isEqualTo("Sealed by Ishwarya · Ravi keeps the recovery sheet · ask Ravi")
        assertThat(SealedAccessService.sentence("Ishwarya", RecoveryPresence(false, null, true, listOf("Amma", "Ravi", "our lawyer"))))
            .isEqualTo("Sealed by Ishwarya · Amma, Ravi and our lawyer each hold a recovery share · any two open it")
        assertThat(SealedAccessService.sentence("Ishwarya", RecoveryPresence(true, null, true, emptyList())))
            .isEqualTo(
                "Sealed by Ishwarya · Ishwarya made a recovery sheet · look for it with Ishwarya's papers" +
                    " · or three recovery shares exist · any two open it",
            )
    }

    @Test
    fun `an heir with an open window reads who to ask, in the handbook, its PDF and the index`() {
        val gold = capture(owner, householdId, "gold_physical", "Wedding gold", BigDecimal("500000"), visibility = "private")
            .path("id").asText()
        val will = post(
            "/api/v1/households/$householdId/estate/documents", owner,
            mapOf("memberId" to ownerMemberId, "kind" to "will", "title" to "Ishwarya's will", "executedOn" to "2024-01-10"),
        ).json().path("id").asText()
        seal("investment", gold)
        seal("estate_document", will)
        shares(listOf("Ravi", "Amma"))

        assertThat(get("/api/v1/households/$householdId/continuity/handbook", spouse).json().path("entries"))
            .describedAs("before any window, a private holding is not in the spouse's handbook at all")
            .isEmpty()

        val expected = "Sealed by Ishwarya · Ravi and Amma each hold a recovery share · any two open it"
        openWindow()

        val handbook = get("/api/v1/households/$householdId/continuity/handbook", spouse).json()
        val line = handbook.path("entries").single().path("sealed").single()
        assertThat(line.path("fieldKey").asText()).isEqualTo("original_location")
        assertThat(line.path("sealedByMemberId").asText()).isEqualTo(ownerMemberId)
        assertThat(line.path("sealedByMe").asBoolean()).isFalse()
        assertThat(line.path("hasRecoveryShares").asBoolean()).isTrue()
        assertThat(line.path("shareHolders").map { it.asText() }).containsExactly("Ravi", "Amma")
        assertThat(line.path("sentence").asText()).isEqualTo(expected)
        assertThat(line.toString()).describedAs("the words stay sealed").doesNotContain("Steel almirah").doesNotContain("ciphertext")

        val instrument = handbook.path("instruments").single()
        assertThat(instrument.path("estateDocumentId").asText()).isEqualTo(will)
        assertThat(instrument.path("sealed").single().path("sentence").asText()).isEqualTo(expected)

        val index = get("/api/v1/households/$householdId/where-and-who?recordType=investment&recordId=$gold", spouse).json()
        assertThat(index.path("records").single().path("originalLocation").path("access").path("sentence").asText())
            .isEqualTo(expected)

        val pdf = RestTemplate().exchange(
            url("/api/v1/households/$householdId/continuity/handbook.pdf"), HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { setBearerAuth(spouse) }), ByteArray::class.java,
        ).body!!
        Loader.loadPDF(pdf).use { document ->
            val text = PDFTextStripper().getText(document).replace(Regex("\\s+"), " ")
            assertThat(text).contains("Where the original is: Sealed by Ishwarya")
            assertThat(text).contains("any two open it")
            assertThat(text).doesNotContain("Steel almirah")
        }
    }

    @Test
    fun `the owner's own line says what to do, and a member who cannot see the record hears nothing`() {
        val shared = capture(owner, householdId, "gold_physical", "Family gold", BigDecimal("1"), visibility = "household")
            .path("id").asText()
        val private = capture(owner, householdId, "gold_physical", "Her gold", BigDecimal("1"), visibility = "private")
            .path("id").asText()
        seal("investment", shared)
        seal("investment", private)

        val mine = get("/api/v1/households/$householdId/continuity/handbook", owner).json().path("entries")
            .associate { it.path("title").asText() to it.path("sealed").single().path("sentence").asText() }
        assertThat(mine["Her gold"]).isEqualTo("Sealed by you · only your passphrase opens it · make a recovery sheet so your family can")

        val theirs = get("/api/v1/households/$householdId/continuity/handbook", spouse).json().path("entries")
        assertThat(theirs.map { it.path("title").asText() }).containsExactly("Family gold")
        assertThat(theirs.single().path("sealed").single().path("sentence").asText())
            .isEqualTo("Sealed by Ishwarya · only Ishwarya's passphrase opens it")
        assertThat(get("/api/v1/households/$householdId/e2e/recovery/members/$ownerMemberId", spouse).status())
            .isEqualTo(HttpStatus.NOT_FOUND)

        // The raw list says whose it is too, so a client can say "sealed by someone else" rather than "corrupt".
        assertThat(get("/api/v1/households/$householdId/e2e/values?recordType=investment&recordId=$shared", spouse).json()
            .single().path("sealedByMe").asBoolean()).isFalse()
        assertThat(get("/api/v1/households/$householdId/e2e/values?recordType=investment&recordId=$shared", owner).json()
            .single().path("sealedByMe").asBoolean()).isTrue()
    }
}
