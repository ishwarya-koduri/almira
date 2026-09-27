package tech.almira.lifecycle

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
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream

@DisplayName("Download everything: one zip, exactly what you can see")
class ExportEverythingApiTest : LifecycleTestSupport() {

    private lateinit var ishwarya: String
    private lateinit var ravi: String
    private lateinit var householdId: String
    private lateinit var ishwaryaMemberId: String

    private val http = RestTemplate(org.springframework.http.client.JdkClientHttpRequestFactory()).apply {
        errorHandler = object : org.springframework.web.client.ResponseErrorHandler {
            override fun hasError(response: org.springframework.http.client.ClientHttpResponse) = false
            override fun handleError(response: org.springframework.http.client.ClientHttpResponse) = Unit
        }
    }

    @BeforeEach
    fun setUp() {
        ishwarya = signIn()
        ravi = signIn()
        val household = createHousehold(ishwarya, "Koduri", "private", "Ishwarya")
        householdId = household.path("id").asText()
        ishwaryaMemberId = household.path("myMemberId").asText()
        val raviMemberId = addMember(ishwarya, householdId, "Ravi").path("id").asText()
        joinHousehold(ishwarya, householdId, raviMemberId, ravi, role = "admin")
    }

    private fun download(token: String): Pair<HttpStatus, ByteArray> {
        val headers = HttpHeaders().apply { setBearerAuth(token) }
        val response = http.exchange(url("/api/v1/me/export"), HttpMethod.GET, HttpEntity<Void>(headers), ByteArray::class.java)
        return HttpStatus.valueOf(response.statusCode.value()) to (response.body ?: ByteArray(0))
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { out[it.name] = zip.readAllBytes() }
        }
        return out
    }

    private fun upload(token: String, bytes: ByteArray, linkTo: String) {
        val body = LinkedMultiValueMap<String, Any>().apply {
            add("file", object : ByteArrayResource(bytes) { override fun getFilename() = "statement.txt" })
        }
        val headers = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
            setBearerAuth(token)
        }
        val response = http.exchange(
            url("/api/v1/households/$householdId/documents?docType=statement&entityType=investment&entityId=$linkTo"),
            HttpMethod.POST, HttpEntity(body, headers), String::class.java,
        )
        check(response.statusCode.is2xxSuccessful) { "upload failed: ${response.body}" }
    }

    @Test
    fun `it needs a step-up`() {
        val (status, body) = download(ishwarya)
        assertThat(status).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(String(body)).contains("step_up_required")
    }

    @Test
    fun `it holds a PDF, CSV and JSON of what you can see, your documents, and sealed values as ciphertext`() {
        val mine = capture(ishwarya, householdId, "gold_physical", "Ishwarya's private gold", BigDecimal(1_76_875))
        capture(ravi, householdId, "gold_physical", "Ravi's private gold", BigDecimal(90_000))
        capture(ravi, householdId, "gold_physical", "Shared by Ravi", BigDecimal(10_000), visibility = "household")
        post(
            "/api/v1/households/$householdId/accounts", ishwarya,
            mapOf("label" to "SBI savings", "accountKind" to "savings", "number" to "123456787890", "storeFullNumber" to true),
        ).also { check(it.statusCode.is2xxSuccessful) { it.body!! } }
        val secretBytes = "Statement for the gold, page one".toByteArray()
        upload(ishwarya, secretBytes, mine.path("id").asText())
        val ciphertext = "AQAAAAGsealedciphertextthatserverneverreads0123456789abcdef"
        db.update(
            """
            insert into sealed_values (household_id, record_type, record_id, field_key, ciphertext, sealed_by)
            values (?::uuid, 'investment', ?::uuid, 'where_it_is', ?, ?::uuid)
            """.trimIndent(),
            householdId, mine.path("id").asText(), ciphertext, userId(ishwarya),
        )

        stepUp(ishwarya)
        val (status, bytes) = download(ishwarya)
        assertThat(status).isEqualTo(HttpStatus.OK)
        val files = unzip(bytes)

        assertThat(files.keys).contains("README.txt", "almira-everything.pdf", "almira-everything.json", "csv/profile.csv")
        assertThat(String(files.getValue("almira-everything.pdf").copyOfRange(0, 4))).isEqualTo("%PDF")

        val json = mapper.readTree(files.getValue("almira-everything.json"))
        val tables = json.path("households").first().path("tables")
        val titles = tables.path("investments").map { it.path("title").asText() }
        assertThat(titles).contains("Ishwarya's private gold", "Shared by Ravi").doesNotContain("Ravi's private gold")
        assertThat(tables.path("sealed_values").first().path("ciphertext").asText()).isEqualTo(ciphertext)
        assertThat(tables.path("accounts").first().path("number_masked").asText()).endsWith("7890")

        val everything = files.values.joinToString("") { String(it, Charsets.ISO_8859_1) }
        assertThat(everything).describedAs("no full account number anywhere").doesNotContain("123456787890")
        assertThat(everything).doesNotContain("Ravi's private gold")

        val document = files.entries.single { it.key.startsWith("documents/") }
        assertThat(document.value).isEqualTo(secretBytes)

        val csvName = files.keys.single { it.endsWith("/investments.csv") }
        assertThat(String(files.getValue(csvName), Charsets.UTF_8)).contains("Ishwarya's private gold")

        assertThat(
            db.queryForObject(
                "select count(*) from activity_log where action = 'account.export_everything' and actor_user_id = ?::uuid",
                Int::class.java, userId(ishwarya),
            ),
        ).isEqualTo(1)
    }
}
