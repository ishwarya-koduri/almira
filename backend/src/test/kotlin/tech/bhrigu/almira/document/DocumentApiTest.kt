package tech.bhrigu.almira.document

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
import java.util.UUID

/**
 * The vault. What matters is that a proof is never more visible than the thing
 * it proves, and that the bytes are unreadable everywhere they rest.
 */
@DisplayName("Documents")
class DocumentApiTest : ApiTestBase() {

    private lateinit var owner: String
    private lateinit var spouse: String
    private lateinit var householdId: String
    private lateinit var ownerMemberId: String
    private lateinit var spouseMemberId: String

    private val secretText = "POLICY 5567123 — sum assured one crore".toByteArray()

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

    private val upload = RestTemplate().apply {
        errorHandler = object : org.springframework.web.client.ResponseErrorHandler {
            override fun hasError(r: org.springframework.http.client.ClientHttpResponse) = false
            override fun handleError(r: org.springframework.http.client.ClientHttpResponse) = Unit
        }
    }

    private fun uploadFile(
        token: String,
        name: String = "policy.pdf",
        bytes: ByteArray = secretText,
        linkTo: Pair<String, String>? = null,
    ): org.springframework.http.ResponseEntity<String> {
        val body = LinkedMultiValueMap<String, Any>().apply {
            add(
                "file",
                object : ByteArrayResource(bytes) {
                    override fun getFilename() = name
                },
            )
        }
        val headers = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
            setBearerAuth(token)
        }
        val query = buildString {
            append("?docType=policy")
            linkTo?.let { append("&entityType=${it.first}&entityId=${it.second}") }
        }
        return upload.exchange(
            url("/api/v1/households/$householdId/documents$query"),
            HttpMethod.POST, HttpEntity(body, headers), String::class.java,
        )
    }

    private fun stepUp(token: String) {
        val challenge = post("/api/v1/auth/step-up/request", token).json()
        post(
            "/api/v1/auth/step-up/verify", token,
            mapOf(
                "code" to challenge.path("developmentCode").asText(),
                "requestId" to challenge.path("requestId").asText(),
            ),
        )
    }

    // -------------------------------------------------------------------------

    @Test
    fun `the stored bytes are ciphertext, not the file`() {
        val response = uploadFile(owner)
        assertThat(response.statusCode).describedAs(response.body).isEqualTo(HttpStatus.CREATED)

        val key = db.queryForObject(
            "select storage_key from documents where id = ?",
            String::class.java, UUID.fromString(mapper.readTree(response.body).path("id").asText()),
        )!!
        val stored = java.nio.file.Path.of("./var/documents").resolve(key)
        val onDisk = java.nio.file.Files.readAllBytes(stored)

        assertThat(String(onDisk, Charsets.ISO_8859_1))
            .describedAs("a misconfigured bucket must leak ciphertext, not policies")
            .doesNotContain("POLICY")
            .doesNotContain("5567123")
        assertThat(onDisk).isNotEqualTo(secretText)
    }

    @Test
    fun `viewing a document needs a confirmation, then works once`() {
        val id = mapper.readTree(uploadFile(owner).body).path("id").asText()

        assertThat(post("/api/v1/households/$householdId/documents/$id/access", owner).errorCode())
            .isEqualTo("step_up_required")

        stepUp(owner)
        val ticket = post("/api/v1/households/$householdId/documents/$id/access", owner).json()
        val token = ticket.path("token").asText()
        assertThat(token).isNotBlank()

        val first = get("/api/v1/documents/download?token=$token")
        assertThat(first.status()).isEqualTo(HttpStatus.OK)
        assertThat(first.body).contains("POLICY 5567123")

        // Single use: a token that leaks through a log or a screenshot is spent.
        assertThat(get("/api/v1/documents/download?token=$token").status())
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `a made-up ticket gets nothing`() {
        assertThat(get("/api/v1/documents/download?token=not-a-real-ticket").status())
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    /**
     * The inheritance rule, both ways round: a proof is exactly as visible as
     * what it proves, so it can never be the thing that gives a private holding
     * away.
     */
    @Test
    fun `a document attached to a private holding is invisible to others`() {
        val privateFd = capture(
            owner, householdId, "fd", "Private FD", BigDecimal(500_000), "private",
            attributes = mapOf("interest_rate" to "7.1"),
        ).path("id").asText()

        uploadFile(owner, "fd-receipt.pdf", linkTo = "investment" to privateFd)

        assertThat(get("/api/v1/households/$householdId/documents", spouse).json())
            .describedAs("the receipt must not announce the deposit")
            .isEmpty()
    }

    @Test
    fun `a document attached to a shared holding is visible to the household`() {
        val sharedGold = capture(
            owner, householdId, "gold_physical", "Family gold", BigDecimal(100_000), "household",
        ).path("id").asText()

        uploadFile(owner, "gold-bill.pdf", linkTo = "investment" to sharedGold)

        val visible = get("/api/v1/households/$householdId/documents", spouse).json()
            .map { it.path("fileName").asText() }
        assertThat(visible).containsExactly("gold-bill.pdf")
    }

    @Test
    fun `a standalone document stays with whoever uploaded it`() {
        uploadFile(owner, "my-will.pdf")
        assertThat(get("/api/v1/households/$householdId/documents", spouse).json())
            .describedAs("attached to nothing, it carries its own privacy")
            .isEmpty()
        assertThat(get("/api/v1/households/$householdId/documents", owner).json()).hasSize(1)
    }

    @Test
    fun `the missing-proof list only covers what the caller can see`() {
        capture(
            owner, householdId, "gold_physical", "Family gold", BigDecimal(100_000), "household",
        )
        capture(
            owner, householdId, "fd", "Private FD", BigDecimal(500_000), "private",
            attributes = mapOf("interest_rate" to "7.1"),
        )

        val mine = get("/api/v1/households/$householdId/documents/missing-proof", owner).json()
            .map { it.path("title").asText() }
        assertThat(mine).containsExactlyInAnyOrder("Family gold", "Private FD")

        val theirs = get("/api/v1/households/$householdId/documents/missing-proof", spouse).json()
            .map { it.path("title").asText() }
        assertThat(theirs)
            .describedAs("a checklist must not reveal that someone else's record lacks a proof")
            .containsExactly("Family gold")
    }

    @Test
    fun `an empty file is refused`() {
        val response = uploadFile(owner, "empty.pdf", ByteArray(0))
        assertThat(mapper.readTree(response.body).path("error").path("code").asText())
            .isEqualTo("file_empty")
    }
}
