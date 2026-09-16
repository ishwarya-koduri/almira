package tech.bhrigu.almira.sharing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestTemplate
import tech.bhrigu.almira.config.AlmiraProperties
import tech.bhrigu.almira.security.JwtService
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * A guest link's token is the whole credential: no password behind it, no
 * account to lock, nothing to ask for a second factor. docs/05 §7 has always
 * described these links as rate limited, and they were not — two hundred rapid
 * opens of one link all answered 200 — so a token in a forwarded email or a
 * browser history could be read at whatever speed a script could ask.
 *
 * The caps are the real ones (`AlmiraProperties.Share`), not cut-down ones: a
 * class with properties of its own is a Spring context of its own, and each
 * cached context holds two connection pools against the one test database,
 * which has room for about eight. So each test spends the counter down to its
 * last one in Redis and then asks the boundary question in two requests — the
 * counting either side of it is the real thing, over real HTTP.
 *
 * Every open carries its own X-Forwarded-For, honoured because the default
 * proxy configuration trusts the loopback address the test client comes from
 * (ShareAuditAddressTest walks the same seam). Without it every test in the
 * suite would share one per-network counter.
 */
@DisplayName("A guest link cannot be read at machine speed")
class GuestShareRateLimitApiTest : ApiTestBase() {

    @Autowired private lateinit var redis: StringRedisTemplate

    /** The link counter is keyed by the token's hash, exactly as the database is. */
    @Autowired private lateinit var jwt: JwtService

    @Autowired private lateinit var props: AlmiraProperties

    private val rest = RestTemplate(org.springframework.http.client.JdkClientHttpRequestFactory()).apply {
        errorHandler = object : org.springframework.web.client.ResponseErrorHandler {
            override fun hasError(response: org.springframework.http.client.ClientHttpResponse) = false
            override fun handleError(response: org.springframework.http.client.ClientHttpResponse) = Unit
        }
    }

    /**
     * An address of this test's own, so one test's opens never count against
     * another's. Counted rather than random, and out of 198.18/15 rather than
     * the documentation ranges the rest of the suite draws from: Share-
     * AuditAddressTest opens a guest link from a random 203.0.113 address.
     */
    private fun someNetwork(): String {
        val n = networks.incrementAndGet()
        return "198.18.${n / 254}.${n % 254 + 1}"
    }

    /** As GuestShareController: the address, hashed, never stored raw. */
    private fun ipHash(ip: String) = MessageDigest.getInstance("SHA-256").digest(ip.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(32)

    /** Leaves [key] one use short of [cap], with an hour on it, as an hour of opens would. */
    private fun spendAllBut(key: String, cap: Int) =
        redis.opsForValue().set(key, "${cap - 1}", Duration.ofHours(1))

    private fun linkKey(token: String) = "share:opens:link:${jwt.hash(token)}"
    private fun networkKey(ip: String) = "share:opens:network:${ipHash(ip)}"

    private fun open(token: String, network: String): ResponseEntity<String> {
        val headers = HttpHeaders().apply { set("X-Forwarded-For", network) }
        return rest.exchange(
            url("/api/v1/share/$token"), HttpMethod.GET, HttpEntity<Void>(headers), String::class.java,
        )
    }

    private fun ResponseEntity<String>.error(field: String) =
        mapper.readTree(body).path("error").let {
            if (field == "retryAfterSeconds") it.path("details").path(field) else it.path(field)
        }

    /** A link to one household record: its id, and the token that opens it. */
    private fun aLink(): Pair<String, String> {
        val owner = signIn()
        val householdId = createHousehold(owner, "Koduri", "household", "Ishwarya").path("id").asText()
        capture(owner, householdId, "gold_physical", "Gold", BigDecimal("100000"), visibility = "household")
        val created = post(
            "/api/v1/households/$householdId/shares", owner,
            mapOf("label" to "For the CA", "scope" to "handbook", "expiresInDays" to 7),
        ).json()
        assertThat(created.path("url").asText()).describedAs(created.toString()).contains("/share/")
        return created.path("id").asText() to created.path("url").asText().substringAfterLast('/')
    }

    @Test
    fun `one link stops answering when it is opened faster than anyone reads`() {
        val (shareId, token) = aLink()
        val network = someNetwork()
        spendAllBut(linkKey(token), props.share.maxOpensPerLinkPerHour)

        assertThat(open(token, network).statusCode)
            .describedAs("the last open of the hour is still a link that works")
            .isEqualTo(HttpStatus.OK)

        val refused = open(token, network)
        assertThat(refused.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(refused.error("code").asText()).isEqualTo("rate_limited")
        assertThat(refused.error("retryAfterSeconds").asLong())
            .describedAs("a refusal a person can act on says when to come back")
            .isGreaterThan(0)

        assertThat(
            db.queryForObject("select view_count from guest_shares where id = ?::uuid", Int::class.java, shareId),
        ).describedAs("the cap is spent before the view is counted, so a refused open is not a view").isEqualTo(1)
        assertThat(
            db.queryForObject(
                "select count(*) from guest_share_views where share_id = ?::uuid", Int::class.java, shareId,
            ),
        ).isEqualTo(1)
    }

    /**
     * The link cap follows the token, so a host holding several leaked ones
     * would be under none of them. The network cap is what bounds that, and an
     * unauthenticated endpoint being hammered with nonsense.
     */
    @Test
    fun `one network cannot spend its way through link after link`() {
        val network = someNetwork()
        val (_, first) = aLink()
        val (_, second) = aLink()
        spendAllBut(networkKey(network), props.share.maxOpensPerNetworkPerHour)

        assertThat(open(first, network).statusCode)
            .describedAs("the network's last open of the hour")
            .isEqualTo(HttpStatus.OK)
        assertThat(open(second, network).statusCode)
            .describedAs("a different link, on its own first open, and this network is done")
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
    }

    /**
     * The whole point of the 404s in this area is that a withdrawn link, an
     * expired one and a string of nonsense are indistinguishable. A 429 that
     * arrived only for real tokens would answer that question instead.
     */
    @Test
    fun `being refused for going too fast says nothing about whether the token is real`() {
        val (_, real) = aLink()
        val nonsense = "definitely-not-a-real-token-${someNetwork()}"

        assertThat(open(real, someNetwork()).statusCode)
            .describedAs("under the cap the two are told apart, which is what makes the rest worth asking")
            .isEqualTo(HttpStatus.OK)
        assertThat(open(nonsense, someNetwork()).statusCode).isEqualTo(HttpStatus.NOT_FOUND)

        val cap = props.share.maxOpensPerLinkPerHour
        redis.opsForValue().set(linkKey(real), "$cap", Duration.ofHours(1))
        redis.opsForValue().set(linkKey(nonsense), "$cap", Duration.ofHours(1))

        val refusedReal = open(real, someNetwork())
        val refusedNonsense = open(nonsense, someNetwork())

        assertThat(refusedReal.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(refusedNonsense.statusCode).isEqualTo(refusedReal.statusCode)
        assertThat(refusedNonsense.error("message").asText())
            .describedAs("telling them apart would confirm that a token is worth guessing at")
            .isEqualTo(refusedReal.error("message").asText())
    }

    private companion object {
        /** Shared across instances: JUnit builds a new one for every test method. */
        val networks = AtomicInteger(0)
    }
}
