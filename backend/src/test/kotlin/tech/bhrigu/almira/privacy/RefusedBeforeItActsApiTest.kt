package tech.bhrigu.almira.privacy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestTemplate
import tech.bhrigu.almira.crypto.KeyManagementService
import tech.bhrigu.almira.crypto.LocalKeyManagement
import tech.bhrigu.almira.document.DocumentStorage
import tech.bhrigu.almira.document.FilesystemDocumentStorage
import tech.bhrigu.almira.support.ApiTestBase
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A caller the database would refuse is refused before the request does
 * anything the refusal cannot take back.
 *
 * Each of these used to be refused only by a row-level security policy at an
 * insert — after a data key had been provisioned and cached, bytes written to
 * storage, or an outside provider called. A rollback undoes the row; it does
 * not undo the cache, the file or the provider. So every test here asserts that
 * the thing did not happen, not only that the answer was 403: the storage, the
 * key service and the sandbox providers are wrapped to count what reaches them.
 */
@DisplayName("A refused write is refused before it acts")
@Import(RefusedBeforeItActsApiTest.Counting::class)
@TestPropertySource(properties = ["almira.providers.aa.mode=sandbox"])
class RefusedBeforeItActsApiTest : ApiTestBase() {

    class CountingStorage(private val real: DocumentStorage) : DocumentStorage by real {
        val puts = CopyOnWriteArrayList<String>()
        override fun put(key: String, ciphertext: ByteArray) {
            puts += key
            real.put(key, ciphertext)
        }
    }

    class CountingKeys(private val real: KeyManagementService) : KeyManagementService by real {
        val wraps = AtomicInteger()
        override fun wrap(dataKey: ByteArray): ByteArray {
            wraps.incrementAndGet()
            return real.wrap(dataKey)
        }
    }

    @TestConfiguration
    class Counting {
        @Bean @Primary
        fun countingStorage(real: FilesystemDocumentStorage) = CountingStorage(real)

        @Bean @Primary
        fun countingKeys(real: LocalKeyManagement) = CountingKeys(real)
    }

    @Autowired private lateinit var storage: CountingStorage
    @Autowired private lateinit var keys: CountingKeys

    private lateinit var owner: String
    private lateinit var viewer: String
    private lateinit var householdId: String

    @BeforeEach
    fun setUp() {
        owner = signIn()
        viewer = signIn()
        householdId = createHousehold(owner, "Koduri", "household", "Ishwarya").path("id").asText()
        val viewerMemberId = addMember(owner, householdId, "Ravi").path("id").asText()
        joinHousehold(owner, householdId, viewerMemberId, viewer, role = "viewer")
    }

    private val multipart = RestTemplate().apply {
        errorHandler = object : org.springframework.web.client.ResponseErrorHandler {
            override fun hasError(r: org.springframework.http.client.ClientHttpResponse) = false
            override fun handleError(r: org.springframework.http.client.ClientHttpResponse) = Unit
        }
    }

    private fun upload(token: String) = multipart.exchange(
        url("/api/v1/households/$householdId/documents?docType=policy"),
        HttpMethod.POST,
        HttpEntity(
            LinkedMultiValueMap<String, Any>().apply {
                add("file", object : ByteArrayResource("policy 5567123456".toByteArray()) {
                    override fun getFilename() = "policy.pdf"
                })
            },
            HttpHeaders().apply {
                contentType = MediaType.MULTIPART_FORM_DATA
                setBearerAuth(token)
            },
        ),
        String::class.java,
    )

    private fun keyRows() = db.queryForObject(
        "select count(*) from encryption_keys where household_id = ?::uuid", Int::class.java, householdId,
    )

    // --- account numbers --------------------------------------------------------

    @Test
    fun `a viewer's new account with its full number provisions no key`() {
        val wrapsBefore = keys.wraps.get()
        val refused = post(
            "/api/v1/households/$householdId/accounts", viewer,
            mapOf(
                "label" to "Savings", "accountKind" to "savings", "visibility" to "household",
                "number" to "50100234567890", "storeFullNumber" to true,
            ),
        )
        assertThat(refused.status()).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(keys.wraps.get() - wrapsBefore).describedAs("data keys wrapped for a refused create").isZero()
        assertThat(keyRows()).isZero()
    }

    @Test
    fun `a viewer's edit storing a full number provisions no key`() {
        val account = post(
            "/api/v1/households/$householdId/accounts", owner,
            mapOf("label" to "Savings", "accountKind" to "savings", "visibility" to "household"),
        ).json()
        val wrapsBefore = keys.wraps.get()
        val refused = patch(
            "/api/v1/households/$householdId/accounts/${account.path("id").asText()}", viewer,
            mapOf(
                "version" to account.path("version").asInt(),
                "number" to "50100234567890", "storeFullNumber" to true,
            ),
        )
        assertThat(refused.status()).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(keys.wraps.get() - wrapsBefore).describedAs("data keys wrapped for a refused edit").isZero()
    }

    // --- documents ------------------------------------------------------------

    @Test
    fun `a viewer's upload writes nothing to storage and provisions no key`() {
        val wrapsBefore = keys.wraps.get()

        val refused = upload(viewer)

        assertThat(refused.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(storage.puts.filter { it.startsWith("$householdId/") })
            .describedAs("files written to storage for a refused upload")
            .isEmpty()
        assertThat(keys.wraps.get() - wrapsBefore)
            .describedAs("data keys wrapped for a refused upload")
            .isZero()

        // And the household's first real write still works end to end.
        assertThat(upload(owner).statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(keyRows()).isEqualTo(1)
    }
}
