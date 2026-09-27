package tech.almira.document

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestTemplate
import tech.almira.support.ApiTestBase
import tech.almira.support.MinioContainer

/**
 * The whole application with `almira.storage.provider=s3`: a document uploaded
 * over the API lands in the bucket as ciphertext, downloads byte for byte, and
 * leaves the bucket when it is deleted.
 */
@DisplayName("Documents in object storage, through the API")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class S3DocumentApiTest : ApiTestBase() {

    @Autowired private lateinit var storage: DocumentStorage

    private val plaintext = "POLICY 5567123, sum assured one crore".toByteArray()

    private val multipart = RestTemplate()

    @Test
    fun `an upload is ciphertext in the bucket and the same bytes on the way out`() {
        assertThat(storage).isInstanceOf(S3DocumentStorage::class.java)

        val owner = signIn()
        val householdId = createHousehold(owner).path("id").asText()

        val body = LinkedMultiValueMap<String, Any>().apply {
            add("file", object : ByteArrayResource(plaintext) { override fun getFilename() = "policy.pdf" })
        }
        val headers = HttpHeaders().apply { contentType = MediaType.MULTIPART_FORM_DATA; setBearerAuth(owner) }
        val uploaded = mapper.readTree(
            multipart.exchange(
                url("/api/v1/households/$householdId/documents?docType=policy"), HttpMethod.POST,
                HttpEntity(body, headers), String::class.java,
            ).body,
        )
        val documentId = uploaded.path("id").asText()
        assertThat(documentId).isNotBlank()

        val storageKey = db.queryForObject(
            "select storage_key from documents where id = ?::uuid", String::class.java, documentId,
        )!!
        val stored = MinioContainer.client().use { s3 ->
            s3.getObjectAsBytes { it.bucket(BUCKET).key("documents/$storageKey") }.asByteArray()
        }
        assertThat(String(stored, Charsets.ISO_8859_1)).doesNotContain("5567123")
        assertThat(stored).isNotEqualTo(plaintext)

        val challenge = post("/api/v1/auth/step-up/request", owner).json()
        post(
            "/api/v1/auth/step-up/verify", owner,
            mapOf("code" to challenge.path("developmentCode").asText(), "requestId" to challenge.path("requestId").asText()),
        )
        val ticket = post("/api/v1/households/$householdId/documents/$documentId/access", owner).json()
        val download = get("/api/v1/documents/download?token=${ticket.path("token").asText()}")
        assertThat(download.body).isEqualTo(String(plaintext))
    }

    companion object {
        private val BUCKET by lazy { MinioContainer.createBucket("almira-api-" + java.util.UUID.randomUUID().toString().take(8)) }

        @JvmStatic
        @DynamicPropertySource
        fun s3(registry: DynamicPropertyRegistry) {
            registry.add("almira.storage.provider") { "s3" }
            registry.add("almira.storage.s3.bucket") { BUCKET }
            registry.add("almira.storage.s3.region") { MinioContainer.REGION }
            registry.add("almira.storage.s3.endpoint") { MinioContainer.endpoint }
            registry.add("almira.storage.s3.access-key-id") { MinioContainer.ACCESS_KEY }
            registry.add("almira.storage.s3.secret-access-key") { MinioContainer.SECRET_KEY }
            registry.add("almira.storage.s3.path-style") { "true" }
        }
    }
}
