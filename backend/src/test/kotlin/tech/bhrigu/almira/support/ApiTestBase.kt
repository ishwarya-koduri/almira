package tech.bhrigu.almira.support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestTemplate
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Full-stack tests over real HTTP.
 *
 * Deliberately not MockMvc: the privacy model depends on the JWT filter setting
 * the request identity and RlsDataSource stamping it onto a pooled connection.
 * A test that bypasses the filter chain, or that shares a thread differently
 * from the real server, would prove nothing about the guarantee that matters.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class ApiTestBase {

    @LocalServerPort protected var port: Int = 0

    @Autowired protected lateinit var mapper: ObjectMapper

    // JdkClientHttpRequestFactory, not the default: SimpleClientHttpRequestFactory
    // is built on HttpURLConnection, which rejects PATCH outright -- and PATCH is
    // how visibility changes, the single most privacy-critical write.
    private val rest = RestTemplate(org.springframework.http.client.JdkClientHttpRequestFactory()).apply {
        // Non-2xx responses are assertions, not failures -- 404 on a private
        // record is the behaviour under test.
        errorHandler = object : org.springframework.web.client.ResponseErrorHandler {
            override fun hasError(response: org.springframework.http.client.ClientHttpResponse) = false
            override fun handleError(response: org.springframework.http.client.ClientHttpResponse) = Unit
        }
    }

    protected fun url(path: String) = "http://localhost:$port$path"

    // --- HTTP ---------------------------------------------------------------

    protected fun call(
        method: HttpMethod,
        path: String,
        token: String? = null,
        body: Any? = null,
    ): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            token?.let { setBearerAuth(it) }
        }
        val entity = HttpEntity(body?.let(mapper::writeValueAsString), headers)
        return rest.exchange(url(path), method, entity, String::class.java)
    }

    protected fun get(path: String, token: String? = null) = call(HttpMethod.GET, path, token)
    protected fun post(path: String, token: String? = null, body: Any? = null) =
        call(HttpMethod.POST, path, token, body)
    protected fun patch(path: String, token: String? = null, body: Any? = null) =
        call(HttpMethod.PATCH, path, token, body)
    protected fun delete(path: String, token: String? = null) = call(HttpMethod.DELETE, path, token)

    protected fun ResponseEntity<String>.json(): JsonNode = mapper.readTree(body ?: "{}")
    protected fun ResponseEntity<String>.status(): HttpStatusCode = statusCode
    protected fun ResponseEntity<String>.errorCode(): String? =
        json().path("error").path("code").asText(null)

    // --- fixtures ------------------------------------------------------------

    /** Signs up (or signs in) a user and returns their access token. */
    protected fun signIn(phone: String = uniquePhone()): String {
        val response = post("/api/auth/otp/request", body = mapOf("phone" to phone))
        val code = response.json().path("developmentCode").asText()
        // Reporting the body matters: an OTP request can fail for reasons that
        // have nothing to do with the test (rate limits, config), and a bare
        // "no code" sends you looking in the wrong place.
        check(code.isNotEmpty()) {
            "no OTP code in the response (${response.statusCode}): ${response.body}"
        }
        return post(
            "/api/auth/otp/verify",
            body = mapOf("phone" to phone, "code" to code),
        ).json().path("accessToken").asText()
    }

    protected fun createHousehold(
        token: String,
        name: String = "Test household",
        defaultVisibility: String = "private",
        displayName: String = "Owner",
    ): JsonNode = post(
        "/api/households", token,
        mapOf(
            "name" to name,
            "defaultVisibility" to defaultVisibility,
            "displayName" to displayName,
        ),
    ).json()

    protected fun addMember(token: String, householdId: String, name: String): JsonNode =
        post(
            "/api/households/$householdId/members", token,
            mapOf("displayName" to name, "relationship" to "spouse"),
        ).json()

    /** Invites [memberToken]'s user to claim an existing managed member row. */
    protected fun joinHousehold(
        ownerToken: String,
        householdId: String,
        memberId: String,
        joinerToken: String,
        role: String = "admin",
    ) {
        val invite = post(
            "/api/households/$householdId/invitations", ownerToken,
            mapOf("memberId" to memberId, "phone" to uniquePhone(), "role" to role),
        ).json()
        post(
            "/api/invitations/accept", joinerToken,
            mapOf("token" to invite.path("token").asText()),
        )
    }

    protected fun typeId(token: String, householdId: String, code: String): String {
        val taxonomy = get("/api/households/$householdId/taxonomy", token).json()
        for (category in taxonomy) {
            for (type in category.path("types")) {
                if (type.path("code").asText() == code) return type.path("id").asText()
            }
        }
        error("no investment type with code '$code' in the seeded taxonomy")
    }

    protected fun capture(
        token: String,
        householdId: String,
        typeCode: String,
        title: String,
        amount: BigDecimal,
        visibility: String = "private",
        owners: List<Map<String, Any>> = emptyList(),
        visibleTo: List<String> = emptyList(),
        attributes: Map<String, Any?> = emptyMap(),
    ): JsonNode {
        val response = post(
            "/api/households/$householdId/investments", token,
            buildMap {
                put("typeId", typeId(token, householdId, typeCode))
                put("title", title)
                put("investedAmount", amount)
                put("visibility", visibility)
                put("attributes", attributes)
                if (owners.isNotEmpty()) put("owners", owners)
                if (visibleTo.isNotEmpty()) put("visibleToMemberIds", visibleTo)
            },
        )
        // A silently failed capture shows up much later as a total that is
        // simply too small -- which reads like a privacy bug rather than a
        // fixture problem. Fail here instead, with the reason.
        check(response.statusCode.is2xxSuccessful) {
            "capture of '$title' failed (${response.statusCode}): ${response.body}"
        }
        return response.json()
    }

    protected fun dashboardTotal(
        token: String,
        householdId: String,
        scope: String = "household",
        member: String? = null,
    ): BigDecimal {
        val query = "?scope=$scope" + (member?.let { "&member=$it" } ?: "")
        return get("/api/households/$householdId/dashboard$query", token)
            .json().path("totalAssets").decimalValue()
    }

    companion object {
        private val counter = AtomicLong(System.nanoTime() % 1_000_000)

        /** Unique per test run, so signups never collide across classes. */
        fun uniquePhone(): String = "+91" + (7_000_000_000L + counter.incrementAndGet())

        fun uuid(): String = UUID.randomUUID().toString()

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("almira.db.url") { TestInfra.dbUrl }
            registry.add("almira.db.owner-user") { TestInfra.dbOwnerUser }
            registry.add("almira.db.owner-password") { TestInfra.dbOwnerPassword }
            registry.add("almira.db.app-user") { TestInfra.dbAppUser }
            registry.add("almira.db.app-password") { TestInfra.dbAppPassword }
            registry.add("spring.data.redis.host") { TestInfra.redisHost }
            registry.add("spring.data.redis.port") { TestInfra.redisPort }
            registry.add("almira.otp.provider") { "log" }
            // Every test signs in from 127.0.0.1, so the per-IP hourly limit
            // (a real and wanted control) would throttle the suite itself.
            // Raised here, and exercised deliberately in OtpRateLimitTest.
            registry.add("almira.otp.max-per-ip-per-hour") { 100_000 }
            registry.add("almira.otp.max-per-hour") { 1_000 }
            registry.add("almira.jwt.secret") { "test-only-secret-that-is-long-enough-for-hmac256-signing" }
        }
    }
}
