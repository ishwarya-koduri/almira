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
import tech.bhrigu.almira.provider.AccountAggregatorClient
import tech.bhrigu.almira.provider.ConsentHandle
import tech.bhrigu.almira.provider.ConsentRequest
import tech.bhrigu.almira.provider.DiscoveredHolding
import tech.bhrigu.almira.provider.DocumentVaultProvider
import tech.bhrigu.almira.provider.ProviderSession
import tech.bhrigu.almira.provider.SandboxAccountAggregator
import tech.bhrigu.almira.provider.SandboxDocumentVault
import tech.bhrigu.almira.provider.VaultDocument
import tech.bhrigu.almira.support.ApiTestBase
import java.util.concurrent.CopyOnWriteArrayList
import java.util.UUID
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
        val deletes = CopyOnWriteArrayList<String>()
        override fun put(key: String, ciphertext: ByteArray) {
            puts += key
            real.put(key, ciphertext)
        }
        override fun delete(key: String) {
            deletes += key
            real.delete(key)
        }
    }

    class CountingKeys(private val real: KeyManagementService) : KeyManagementService by real {
        val wraps = AtomicInteger()
        override fun wrap(dataKey: ByteArray): ByteArray {
            wraps.incrementAndGet()
            return real.wrap(dataKey)
        }
    }

    class CountingVault(private val real: DocumentVaultProvider) : DocumentVaultProvider by real {
        val calls = CopyOnWriteArrayList<String>()
        /** When set, fetches after this many fail, as a timeout partway through an import would. */
        @Volatile var fetchesBeforeFailing: Int? = null
        override fun exchange(householdId: UUID, code: String): ProviderSession {
            calls += "exchange"
            return real.exchange(householdId, code)
        }
        override fun list(session: ProviderSession): List<VaultDocument> {
            calls += "list"
            return real.list(session)
        }
        override fun fetch(session: ProviderSession, uri: String): ByteArray {
            calls += "fetch"
            fetchesBeforeFailing?.let { if (calls.count { it == "fetch" } > it) throw IllegalStateException("DigiLocker went away") }
            return real.fetch(session, uri)
        }
    }

    class CountingAggregator(private val real: AccountAggregatorClient) : AccountAggregatorClient by real {
        val calls = CopyOnWriteArrayList<String>()
        override fun requestConsent(householdId: UUID, request: ConsentRequest): ConsentHandle {
            calls += "consent"
            return real.requestConsent(householdId, request)
        }
        override fun fetch(handle: String): List<DiscoveredHolding> {
            calls += "fetch"
            return real.fetch(handle)
        }
    }

    @TestConfiguration
    class Counting {
        @Bean @Primary
        fun countingVault(real: SandboxDocumentVault) = CountingVault(real)

        @Bean @Primary
        fun countingAggregator(real: SandboxAccountAggregator) = CountingAggregator(real)

        @Bean @Primary
        fun countingStorage(real: FilesystemDocumentStorage) = CountingStorage(real)

        @Bean @Primary
        fun countingKeys(real: LocalKeyManagement) = CountingKeys(real)
    }

    @Autowired private lateinit var storage: CountingStorage
    @Autowired private lateinit var keys: CountingKeys
    @Autowired private lateinit var vault: CountingVault
    @Autowired private lateinit var aggregator: CountingAggregator

    private lateinit var owner: String
    private lateinit var viewer: String
    private lateinit var editor: String
    private lateinit var householdId: String

    @BeforeEach
    fun setUp() {
        owner = signIn()
        viewer = signIn()
        householdId = createHousehold(owner, "Koduri", "household", "Ishwarya").path("id").asText()
        val viewerMemberId = addMember(owner, householdId, "Ravi").path("id").asText()
        joinHousehold(owner, householdId, viewerMemberId, viewer, role = "viewer")
        editor = signIn()
        val editorMemberId = addMember(owner, householdId, "Meera").path("id").asText()
        joinHousehold(owner, householdId, editorMemberId, editor, role = "editor")
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

    // --- providers --------------------------------------------------------------

    private fun connect(path: String) = "/api/v1/households/$householdId/connect/$path"

    @Test
    fun `an editor cannot spend a DigiLocker authorisation code`() {
        post(connect("digilocker/start"), owner)
        vault.calls.clear()

        val refused = post(connect("digilocker/complete"), editor, mapOf("code" to "sandbox-code"))

        assertThat(refused.status()).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(vault.calls).describedAs("DigiLocker calls made for a refused complete").isEmpty()
    }

    @Test
    fun `an editor cannot create a consent at the Account Aggregator`() {
        aggregator.calls.clear()

        val refused = post(connect("aa/consent"), editor)

        assertThat(refused.status()).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(aggregator.calls).describedAs("aggregator calls made for a refused consent").isEmpty()
    }

    @Test
    fun `a viewer's DigiLocker import fetches nothing and stores nothing`() {
        post(connect("digilocker/start"), owner)
        val offered = post(connect("digilocker/complete"), owner, mapOf("code" to "sandbox-code")).json()
        vault.calls.clear()

        val refused = post(connect("digilocker/import"), viewer, mapOf("uris" to listOf(offered[0].path("uri").asText())))

        assertThat(refused.status()).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(vault.calls).describedAs("DigiLocker calls made for a refused import").isEmpty()
        assertThat(storage.puts.filter { it.startsWith("$householdId/") }).isEmpty()
    }

    /**
     * Not a refusal but the same class: storage is outside the transaction, and
     * its clean-up used to run only when the insert itself failed. Here the
     * first document is fully stored and indexed, the second fetch fails, the
     * import rolls back — and the first file must not outlive its row.
     */
    @Test
    fun `an import that fails partway leaves no file without a row`() {
        post(connect("digilocker/start"), owner)
        val offered = post(connect("digilocker/complete"), owner, mapOf("code" to "sandbox-code")).json()
        vault.fetchesBeforeFailing = 1
        try {
            val failed = post(
                connect("digilocker/import"), owner,
                mapOf("uris" to offered.take(2).map { it.path("uri").asText() }),
            )
            assertThat(failed.status().is2xxSuccessful).isFalse()
        } finally {
            vault.fetchesBeforeFailing = null
        }

        val stored = storage.puts.filter { it.startsWith("$householdId/") }
        assertThat(stored).describedAs("the first document was stored before the second fetch failed").isNotEmpty()
        val indexed = db.queryForList(
            "select storage_key from documents where household_id = ?::uuid", String::class.java, householdId,
        ).toSet()
        assertThat(stored.filter { it !in indexed && it !in storage.deletes })
            .describedAs("files left in storage with no documents row")
            .isEmpty()
    }

    @Test
    fun `a viewer's Account Aggregator import fetches nothing`() {
        post(connect("aa/consent"), owner)
        get(connect("aa/consent"), owner) // the sandbox approves on the first status check
        aggregator.calls.clear()

        val refused = post(connect("aa/import"), viewer)

        assertThat(refused.status()).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(aggregator.calls).describedAs("aggregator calls made for a refused import").isEmpty()
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
