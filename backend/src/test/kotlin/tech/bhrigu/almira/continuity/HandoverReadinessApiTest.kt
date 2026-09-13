package tech.bhrigu.almira.continuity

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestTemplate
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal
import java.security.SecureRandom
import java.util.Base64

/**
 * Handover readiness through the API, as each person sees it (docs/22).
 *
 * The owner connection is used only to put stored state in place — an aged
 * emergency request, a deleted scan — never to stand in for what someone sees.
 */
@DisplayName("Handover readiness — one number, every gap named")
class HandoverReadinessApiTest : ApiTestBase() {

    private lateinit var owner: String
    private lateinit var spouse: String
    private lateinit var householdId: String
    private lateinit var ownerMemberId: String
    private lateinit var spouseMemberId: String
    private val random = SecureRandom()

    @BeforeEach
    fun setUp() {
        owner = signIn()
        spouse = signIn()
        val household = createHousehold(owner, "Koduri", "private", "Ishwarya")
        householdId = household.path("id").asText()
        ownerMemberId = household.path("myMemberId").asText()
        spouseMemberId = addMember(owner, householdId, "Ravi").path("id").asText()
        joinHousehold(owner, householdId, spouseMemberId, spouse, role = "admin")
    }

    // --- fixtures --------------------------------------------------------------

    private fun readiness(token: String): JsonNode {
        val response = get("/api/v1/households/$householdId/continuity/readiness", token)
        assertThat(response.status()).describedAs(response.body).isEqualTo(HttpStatus.OK)
        return response.json()
    }

    /** The API leaves null fields out rather than sending them. */
    private fun noScore(body: JsonNode) = body.path("score").let { it.isMissingNode || it.isNull }

    private fun check(body: JsonNode, code: String) = body.path("checks").first { it.path("code").asText() == code }

    private fun gaps(body: JsonNode) = body.path("gaps").map {
        Triple(it.path("check").asText(), it.path("reason").asText(), it.path("recordId").asText(null))
    }

    private fun policy(token: String = owner, title: String = "LIC Jeevan Anand", visibility: String = "private") =
        capture(
            token, householdId, "insurance_term", title, BigDecimal(500_000), visibility = visibility,
            attributes = mapOf("policy_no" to "123456789", "sum_assured" to 1_000_000, "premium_amount" to 12_000),
        )
            .path("id").asText()

    private fun nominee(id: String, token: String = owner) {
        val response = call(
            HttpMethod.PUT, "/api/v1/households/$householdId/investments/$id/nominees", token,
            mapOf("nominees" to listOf(mapOf("memberId" to spouseMemberId, "sharePct" to 100))),
        )
        check(response.statusCode.is2xxSuccessful) { "nominee: ${response.body}" }
    }

    private val upload = RestTemplate().apply {
        errorHandler = object : org.springframework.web.client.ResponseErrorHandler {
            override fun hasError(r: org.springframework.http.client.ClientHttpResponse) = false
            override fun handleError(r: org.springframework.http.client.ClientHttpResponse) = Unit
        }
    }

    private fun scan(entityType: String, entityId: String, token: String = owner): String {
        val body = LinkedMultiValueMap<String, Any>().apply {
            add("file", object : ByteArrayResource("a scanned policy".toByteArray()) {
                override fun getFilename() = "policy.pdf"
            })
        }
        val headers = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
            setBearerAuth(token)
        }
        val response = upload.exchange(
            url("/api/v1/households/$householdId/documents?docType=policy&entityType=$entityType&entityId=$entityId"),
            HttpMethod.POST, HttpEntity(body, headers), String::class.java,
        )
        check(response.statusCode.is2xxSuccessful) { "upload: ${response.body}" }
        return mapper.readTree(response.body).path("id").asText()
    }

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Envelope-shaped random bytes. The server checks shape only; it cannot open anything. */
    private fun envelope(): String = b64(byteArrayOf(1, 0, 0, 0, 1) + ByteArray(40).also(random::nextBytes))

    private val vaults = mutableSetOf<String>()

    private fun sealLocation(recordType: String, recordId: String, token: String = owner) {
        if (vaults.add(token)) {
            val key = call(
                HttpMethod.PUT, "/api/v1/households/$householdId/e2e/key", token,
                mapOf(
                    "kdfSalt" to b64(ByteArray(16).also(random::nextBytes)), "iterations" to 600_000,
                    "wrappedKey" to envelope(), "verifier" to envelope(),
                ),
            )
            check(key.statusCode.is2xxSuccessful) { "key: ${key.body}" }
        }
        val sealed = call(
            HttpMethod.PUT,
            "/api/v1/households/$householdId/e2e/values/$recordType/$recordId/original_location",
            token, mapOf("ciphertext" to envelope()),
        )
        check(sealed.statusCode.is2xxSuccessful) { "seal: ${sealed.body}" }
    }

    private fun nameTrusted(memberId: String = spouseMemberId, token: String = owner) {
        val response = post(
            "/api/v1/households/$householdId/emergency/contacts", token,
            mapOf("trustedMemberId" to memberId, "waitDays" to 14),
        )
        check(response.statusCode.is2xxSuccessful) { "trusted: ${response.body}" }
    }

    /** A PATCH at the record's current version, which nominee and scan writes may have moved. */
    private fun edit(id: String, fields: Map<String, Any?>, token: String = owner) {
        val version = get("/api/v1/households/$householdId/investments/$id", token).json().path("version").asInt()
        val response = patch("/api/v1/households/$householdId/investments/$id", token, fields + ("version" to version))
        check(response.statusCode.is2xxSuccessful) { "edit: ${response.body}" }
    }

    /** A policy with every check done: nominee, scan, sealed location. */
    private fun completePolicy(title: String = "LIC Jeevan Anand", visibility: String = "private"): String {
        val id = policy(title = title, visibility = visibility)
        nominee(id)
        scan("investment", id)
        sealLocation("investment", id)
        return id
    }

    // --- the promise: no 100 while a gap remains ---------------------------------

    @Test
    fun `everything done is 100 and complete, and the four checks come in order`() {
        completePolicy()
        nameTrusted()

        val body = readiness(owner)
        assertThat(body.path("score").asInt()).isEqualTo(100)
        assertThat(body.path("complete").asBoolean()).isTrue()
        assertThat(body.path("gaps")).isEmpty()
        assertThat(body.path("recordCount").asInt()).isEqualTo(1)
        assertThat(body.path("checks").map { it.path("code").asText() })
            .containsExactly("nominee", "document", "location", "trusted_contact")
        assertThat(body.path("caveats").map { it.asText() })
            .describedAs("a sealed location counted, so the passphrase caveat is said")
            .anyMatch { it.contains("passphrase") }
    }

    @Test
    fun `a trusted contact who cannot sign in does not count, so the score cannot reach 100`() {
        completePolicy()
        val amma = addMember(owner, householdId, "Amma").path("id").asText()
        nameTrusted(amma)

        val body = readiness(owner)
        assertThat(body.path("complete").asBoolean()).isFalse()
        assertThat(body.path("score").asInt()).isLessThan(100).isEqualTo(75)
        assertThat(gaps(body)).containsExactly(Triple("trusted_contact", "trusted_contact_cannot_ask", null))
        assertThat(check(body, "trusted_contact").path("done").asInt()).isZero()
    }

    @Test
    fun `without a sealed location the score cannot reach 100, and there is no unsealed note to count`() {
        val id = policy()
        nominee(id)
        scan("investment", id)
        nameTrusted()

        val body = readiness(owner)
        assertThat(body.path("complete").asBoolean()).isFalse()
        assertThat(body.path("score").asInt()).isEqualTo(75)
        // The plaintext note is retired (V33, docs/20 §1): the only gap is the
        // sealed location, and `location_unsealed` can no longer be said.
        assertThat(gaps(body)).containsExactly(Triple("location", "no_location", id))
    }

    @Test
    fun `a deleted scan is not a scan`() {
        val id = completePolicy()
        nameTrusted()
        db.update("update documents set deleted_at = now() where household_id = ?::uuid", householdId)

        val body = readiness(owner)
        assertThat(body.path("complete").asBoolean()).isFalse()
        assertThat(gaps(body)).containsExactly(Triple("document", "no_document", id))
    }

    @Test
    fun `leaving the records with gaps out of the family summary cannot reach 100 or complete`() {
        completePolicy()
        val gappy = policy(title = "Policy with every gap")
        nameTrusted()
        val before = readiness(owner)
        assertThat(before.path("complete").asBoolean()).isFalse()
        assertThat(before.path("score").asInt()).isLessThan(100)

        // The gaming attempt: fix nothing, just leave the record with gaps out.
        edit(gappy, mapOf("isInContinuity" to false))

        val body = readiness(owner)
        assertThat(gaps(body)).describedAs("its gaps are not listed as gaps").isEmpty()
        assertThat(body.path("leftOutCount").asInt()).isEqualTo(1)
        assertThat(body.path("score").asInt()).describedAs(body.toString()).isEqualTo(99)
        assertThat(body.path("complete").asBoolean()).isFalse()
        assertThat(body.path("leftOut").map {
            Triple(it.path("recordType").asText(), it.path("recordId").asText(), it.path("title").asText())
        }).containsExactly(Triple("investment", gappy, "Policy with every gap"))
        assertThat(body.path("scoreExplanation").asText()).contains("stops at 99")
        assertThat(body.path("caveats").map { it.asText() }).anyMatch { it.contains("Check this is on purpose") }

        // Putting it back is the way out of the to-do; then its real gaps show again.
        edit(gappy, mapOf("isInContinuity" to true))
        val back = readiness(owner)
        assertThat(back.path("leftOut")).isEmpty()
        assertThat(gaps(back).map { it.third }).contains(gappy)
    }

    // --- gaps, named and in order -----------------------------------------------------

    @Test
    fun `gaps read as a to-do list - yours first, then each record's in check order`() {
        val zebra = policy(title = "Zebra policy")
        val gold = capture(owner, householdId, "gold_physical", "Almond gold", BigDecimal(100_000)).path("id").asText()
        val fd = capture(owner, householdId, "fd", "Middle FD", BigDecimal(100_000),
            attributes = mapOf("interest_rate" to 7.1)).path("id").asText()

        val body = readiness(owner)
        assertThat(gaps(body)).containsExactly(
            Triple("trusted_contact", "no_trusted_contact", null),
            // Gold: no nominee to record.
            Triple("document", "no_document", gold),
            Triple("location", "no_location", gold),
            // An FD: the bank pays on the account number; no original to find.
            Triple("nominee", "no_nominee", fd),
            Triple("document", "no_document", fd),
            Triple("nominee", "no_nominee", zebra),
            Triple("document", "no_document", zebra),
            Triple("location", "no_location", zebra),
        )
        assertThat(check(body, "nominee").path("applicable").asInt()).isEqualTo(2)
        assertThat(check(body, "location").path("applicable").asInt()).isEqualTo(2)
        assertThat(body.path("score").asInt()).isZero()
        assertThat(body.path("gaps").first { it.path("recordId").asText() == zebra }.path("title").asText())
            .isEqualTo("Zebra policy")
    }

    @Test
    fun `a will counts for its scan and where the original is`() {
        val will = post(
            "/api/v1/households/$householdId/estate/documents", owner,
            mapOf("memberId" to ownerMemberId, "kind" to "will", "title" to "Ishwarya's will", "visibility" to "private"),
        ).also { check(it.statusCode.is2xxSuccessful) { it.body!! } }.json().path("id").asText()
        db.update("update estate_documents set status = 'executed' where id = ?::uuid", will)
        sealLocation("estate_document", will)
        nameTrusted()

        val body = readiness(owner)
        assertThat(gaps(body)).containsExactly(Triple("document", "no_document", will))
        assertThat(body.path("score").asInt()).describedAs("location 1/1, document 0/1, contact 1/1").isEqualTo(66)
    }

    @Test
    fun `nobody to name is said differently from nobody named`() {
        val alone = signIn()
        val household = createHousehold(alone, "Alone", "private", "Solo")
        capture(alone, household.path("id").asText(), "fd", "FD", BigDecimal(1_000),
            attributes = mapOf("interest_rate" to 7.1))
        val body = get("/api/v1/households/${household.path("id").asText()}/continuity/readiness", alone).json()
        assertThat(body.path("gaps").first().path("reason").asText()).isEqualTo("nobody_to_name")
    }

    // --- never a number the data did not earn ------------------------------------------

    @Test
    fun `nothing recorded is no score, and still says why and what to do`() {
        val body = readiness(owner)
        assertThat(noScore(body)).isTrue()
        assertThat(body.path("complete").asBoolean()).isFalse()
        assertThat(body.path("scoreExplanation").asText()).contains("Nothing is recorded")
        assertThat(gaps(body)).containsExactly(Triple("trusted_contact", "no_trusted_contact", null))
    }

    @Test
    fun `a trusted contact and an IPO application do not add up to 100`() {
        capture(owner, householdId, "ipo_application", "Some IPO", BigDecimal(15_000),
            attributes = mapOf("company_name" to "Some Company"))
        nameTrusted()

        val body = readiness(owner)
        assertThat(noScore(body)).describedAs(body.toString()).isTrue()
        assertThat(body.path("complete").asBoolean()).isFalse()
        assertThat(body.path("scoreExplanation").asText()).contains("None of the checks applies")
    }

    @Test
    fun `leaving everything out of the family summary is no score, not a perfect one`() {
        val id = policy()
        edit(id, mapOf("isInContinuity" to false))
        nameTrusted()

        val body = readiness(owner)
        assertThat(noScore(body)).describedAs(body.toString()).isTrue()
        assertThat(body.path("leftOutCount").asInt()).isEqualTo(1)
        assertThat(body.path("scoreExplanation").asText()).contains("left out of the family summary")
        assertThat(gaps(body)).describedAs("its missing items are not listed as gaps").isEmpty()
        assertThat(body.path("complete").asBoolean()).isFalse()
        assertThat(body.path("leftOut").map { it.path("recordId").asText() })
            .describedAs("but the record itself is named, to check it is left out on purpose")
            .containsExactly(id)
    }

    // --- per viewer ---------------------------------------------------------------------

    @Test
    fun `another member's private record is not counted, named or hinted at`() {
        nameTrusted(ownerMemberId, token = spouse)
        val shared = completePolicy("Household policy", visibility = "household")
        val spouseBefore = readiness(spouse)
        assertThat(spouseBefore.path("score").asInt()).isEqualTo(100)

        // The owner adds a private holding with every gap there is, and one left out.
        val secret = policy(title = "Secret policy")
        val secretLeftOut = policy(title = "Secret left out")
        edit(secretLeftOut, mapOf("isInContinuity" to false))

        val spouseAfter = readiness(spouse)
        assertThat(spouseAfter.toString()).doesNotContain("Secret").doesNotContain(secret)
        assertThat(spouseAfter.path("recordCount").asInt()).isEqualTo(1)
        assertThat(spouseAfter.path("leftOutCount").asInt()).isZero()
        assertThat(spouseAfter.path("leftOut")).isEmpty()
        assertThat(spouseAfter.path("score").asInt()).isEqualTo(100)
        assertThat(spouseAfter.path("checks")).isEqualTo(spouseBefore.path("checks"))

        // The owner's own view has them.
        val ownersView = readiness(owner)
        assertThat(ownersView.path("recordCount").asInt()).isEqualTo(2)
        assertThat(ownersView.path("leftOutCount").asInt()).isEqualTo(1)
        assertThat(gaps(ownersView).map { it.third }).contains(secret).doesNotContain(shared)
    }

    @Test
    fun `what an open emergency window reveals is not counted in the trusted contact's own score`() {
        policy(title = "Her continuity policy")
        nameTrusted()
        post("/api/v1/households/$householdId/emergency/requests", spouse,
            mapOf("subjectMemberId" to ownerMemberId, "reason" to "Hospital"))
            .also { check(it.statusCode.is2xxSuccessful) { "request: ${it.body}" } }
        db.update(
            """
            update emergency_requests set requested_at = now() - interval '20 days',
                                          unlock_at = now() - interval '6 days'
            where household_id = ?::uuid
            """.trimIndent(),
            householdId,
        )
        db.update(
            """
            update user_sessions set last_used_at = now() - interval '30 days'
            where user_id in (select m.user_id from members m where m.household_id = ?::uuid and m.user_id is not null)
            """.trimIndent(),
            householdId,
        )
        // The window really is open: the trusted contact can see the policy.
        assertThat(get("/api/v1/households/$householdId/investments", spouse).json().map { it.path("title").asText() })
            .contains("Her continuity policy")

        val theirs = readiness(spouse)
        assertThat(theirs.path("recordCount").asInt()).isZero()
        assertThat(theirs.toString()).doesNotContain("Her continuity policy")
    }
}
