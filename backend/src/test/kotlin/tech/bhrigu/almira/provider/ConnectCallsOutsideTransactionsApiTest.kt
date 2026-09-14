package tech.bhrigu.almira.provider

import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource
import tech.bhrigu.almira.support.ApiTestBase
import java.time.Duration
import java.util.concurrent.CompletableFuture
import javax.sql.DataSource

/**
 * Known-issues 21: a DigiLocker or Account Aggregator call a person is waiting
 * on used to run inside a `@Transactional` method, so a hanging provider held
 * the request AND an app-pool connection for the provider's whole retry budget
 * — about 46 seconds at DigiLocker's defaults. Ten people waiting on an outage
 * would have been the whole pool.
 *
 * Now the call runs with no transaction open, and the whole call — retries
 * included — is bounded by `connect-budget`. Short timeouts here so the hang is
 * seconds, not minutes.
 */
@TestPropertySource(
    properties = [
        "almira.providers.aa.mode=sandbox",
        "almira.providers.digilocker.timeout=1s",
        "almira.providers.digilocker.max-attempts=3",
        "almira.providers.aa.timeout=1s",
        "almira.providers.connect-budget=2s",
    ],
)
@DisplayName("Connect calls hold no database connection while they wait")
class ConnectCallsOutsideTransactionsApiTest : ApiTestBase() {

    @Autowired private lateinit var faults: SandboxFaults
    @Autowired private lateinit var runtimeDataSource: DataSource

    private lateinit var owner: String
    private lateinit var householdId: String

    @BeforeEach
    fun setUp() {
        faults.clear()
        owner = signIn()
        householdId = createHousehold(owner, "Koduri", "private", "Ishwarya").path("id").asText()
    }

    @AfterEach
    fun tearDown() = faults.clear()

    private fun connect(path: String) = "/api/v1/households/$householdId/connect/$path"

    private val pool get() = (runtimeDataSource as HikariDataSource).hikariPoolMXBean

    /** Runs [request] while a provider hangs, and returns the busiest the app pool was mid-wait. */
    private fun busiestWhileHanging(request: () -> org.springframework.http.ResponseEntity<String>): Pair<Int, org.springframework.http.ResponseEntity<String>> {
        val started = System.nanoTime()
        val response = CompletableFuture.supplyAsync(request)
        var busiest = 0
        // Sample the middle of the wait, after the short reads before the call are done.
        Thread.sleep(400)
        while (System.nanoTime() - started < Duration.ofMillis(1_500).toNanos() && !response.isDone) {
            busiest = maxOf(busiest, pool.activeConnections)
            Thread.sleep(25)
        }
        return busiest to response.get()
    }

    @Test
    fun `listing documents from a hanging DigiLocker holds no connection, and gives up within the budget`() {
        post(connect("digilocker/start"), owner)
        post(connect("digilocker/complete"), owner, mapOf("code" to "one-time"))
        faults.always("digilocker", SandboxFault.HANG)

        val started = System.nanoTime()
        val (busiest, response) = busiestWhileHanging { get(connect("digilocker/documents"), owner) }

        assertThat(busiest).describedAs("app-pool connections in use while DigiLocker hangs").isZero()
        assertThat(response.status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT)
        assertThat(response.errorCode()).isEqualTo("provider_timeout")
        assertThat(Duration.ofNanos(System.nanoTime() - started))
            .describedAs("the 2s budget, not 3 × 1s plus backoff").isLessThan(Duration.ofMillis(3_500))
    }

    @Test
    fun `importing from a hanging DigiLocker holds no connection, and stores nothing`() {
        post(connect("digilocker/start"), owner)
        post(connect("digilocker/complete"), owner, mapOf("code" to "one-time"))
        faults.succeedFirst("digilocker", 1)
        faults.always("digilocker", SandboxFault.HANG)

        val (busiest, response) = busiestWhileHanging {
            post(connect("digilocker/import"), owner, mapOf("uris" to listOf("in.lic-POLICY-5567123456")))
        }
        assertThat(busiest).isZero()
        assertThat(response.status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT)
        assertThat(db.queryForObject("select count(*) from documents where household_id = ?::uuid", Int::class.java, householdId))
            .isZero()
    }

    @Test
    fun `asking for consent from a hanging aggregator holds no connection, and writes no pending consent`() {
        faults.always("aa", SandboxFault.HANG)
        val (busiest, response) = busiestWhileHanging { post(connect("aa/consent"), owner) }
        assertThat(busiest).isZero()
        assertThat(response.status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT)
        assertThat(
            db.queryForObject(
                "select count(*) from provider_connections where household_id = ?::uuid and provider = 'account_aggregator'",
                Int::class.java, householdId,
            ),
        ).isZero()
    }

    @Test
    fun `a household that is not yours is still 404, before any provider is called`() {
        faults.always("digilocker", SandboxFault.HANG)
        val stranger = signIn()
        val started = System.nanoTime()
        val response = get(connect("digilocker/documents"), stranger)
        assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(900))
    }

    @Test
    fun `a working connect still imports`() {
        post(connect("digilocker/start"), owner)
        post(connect("digilocker/complete"), owner, mapOf("code" to "one-time"))
        val imported = post(connect("digilocker/import"), owner, mapOf("uris" to listOf("in.lic-POLICY-5567123456")))
        assertThat(imported.status()).isEqualTo(HttpStatus.OK)
        assertThat(imported.json().path("imported").asInt()).isEqualTo(1)

        post(connect("aa/consent"), owner)
        get(connect("aa/consent"), owner)
        val holdings = post(connect("aa/import"), owner)
        assertThat(holdings.status()).describedAs(holdings.body).isEqualTo(HttpStatus.OK)
        assertThat(holdings.json().path("imported").asInt()).isEqualTo(3)
    }
}
