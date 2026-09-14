package tech.bhrigu.almira.plans

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.RestTemplate
import tech.bhrigu.almira.lifecycle.LifecycleTestSupport
import java.math.BigDecimal
import java.util.UUID

/**
 * A lapsed plan makes a household read-only, and never locks it (docs/27 §2).
 *
 * Writes are refused, and nothing is written; reads, the handbook, Download
 * everything and closing an account still work; and the allowlist is checked
 * against every write the live API serves, so a new endpoint cannot slip past
 * the rule, or be caught by it by accident, without this failing.
 */
@DisplayName("Plans: a lapsed household is read-only, never held hostage")
class PlanReadOnlyApiTest : LifecycleTestSupport() {

    private lateinit var owner: String
    private lateinit var householdId: String

    private val http = RestTemplate(org.springframework.http.client.JdkClientHttpRequestFactory()).apply {
        errorHandler = object : org.springframework.web.client.ResponseErrorHandler {
            override fun hasError(response: org.springframework.http.client.ClientHttpResponse) = false
            override fun handleError(response: org.springframework.http.client.ClientHttpResponse) = Unit
        }
    }

    @BeforeEach
    fun setUp() {
        owner = signIn()
        householdId = createHousehold(owner, "Koduri", "private", "Ishwarya").path("id").asText()
        capture(owner, householdId, "gold_physical", "Family gold", BigDecimal("500000"))
    }

    /** As an operator would, through the one door there is: the owner role and ops.set_household_plan. */
    private fun setPlan(paidThroughDaysAgo: Int?, graceDays: Int = 30) {
        val paidThrough = paidThroughDaysAgo?.let { "current_date - $it" } ?: "null::date"
        db.queryForList(
            "select ops.set_household_plan(?::uuid, 'family', $paidThrough, ?, 'test operator', null)",
            householdId, graceDays,
        )
    }

    private fun lapse() = setPlan(paidThroughDaysAgo = 40, graceDays = 30)

    private fun plan(token: String = owner) = get("/api/v1/households/$householdId/plan", token)

    private fun investmentCount(): Long = db.queryForObject(
        "select count(*) from investments where household_id = ?::uuid", Long::class.java, householdId,
    )!!

    private fun newHolding(token: String = owner) = post(
        "/api/v1/households/$householdId/investments", token,
        mapOf(
            "typeId" to typeId(owner, householdId, "gold_physical"), "title" to "Silver coins",
            "investedAmount" to 100000, "visibility" to "private", "attributes" to emptyMap<String, Any>(),
        ),
    )

    // --- the plan and the price page --------------------------------------------

    @Test
    fun `a household with no plan row is on the default plan, active, with no price decided`() {
        val view = plan().json()
        assertThat(view.path("planCode").asText()).isEqualTo("family")
        assertThat(view.path("state").asText()).isEqualTo("active")
        assertThat(view.path("readOnly").asBoolean()).isFalse()
        assertThat(view.path("paidThrough").isNull || view.path("paidThrough").isMissingNode).isTrue()
        assertThat(view.path("price").path("decided").asBoolean()).isFalse()
        assertThat(view.path("price").path("amountInr").let { it.isNull || it.isMissingNode }).isTrue()
        assertThat(view.path("price").path("perHousehold").asBoolean()).isTrue()
        assertThat(view.path("promise").asText()).isEqualTo("Your family's record is never held hostage.")
    }

    @Test
    fun `the price page says no price is decided, takes no payments, and lists what we never do`() {
        val catalogue = get("/api/v1/plans", owner).json()
        assertThat(catalogue.path("paymentsEnabled").asBoolean()).isFalse()
        assertThat(catalogue.path("plans").map { it.path("code").asText() }).containsExactly("family")
        assertThat(catalogue.path("plans")[0].path("price").path("summary").asText())
            .contains("No price has been decided")
        assertThat(catalogue.path("neverDo").map { it.asText() }).hasSize(3)
            .anyMatch { it.contains("Sell") }.anyMatch { it.contains("advice") }.anyMatch { it.contains("money") }
    }

    @Test
    fun `a non-member is told the household does not exist, not that its plan has lapsed`() {
        lapse()
        val outsider = signIn()
        assertThat(plan(outsider).status()).isEqualTo(HttpStatus.NOT_FOUND)
        val write = newHolding(outsider)
        assertThat(write.status()).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(write.errorCode()).isNotEqualTo(PlanReadOnlyGuard.CODE)
    }

    // --- grace, then read-only ----------------------------------------------------

    @Test
    fun `inside the grace period everything still works, and the plan says so`() {
        setPlan(paidThroughDaysAgo = 5, graceDays = 30)
        val view = plan().json()
        assertThat(view.path("state").asText()).isEqualTo("grace")
        assertThat(view.path("readOnly").asBoolean()).isFalse()
        assertThat(newHolding().status()).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `once the grace period is over, a write is refused in words and nothing is written`() {
        lapse()
        val view = plan().json()
        assertThat(view.path("state").asText()).isEqualTo("read_only")
        assertThat(view.path("readOnly").asBoolean()).isTrue()

        val before = investmentCount()
        val refused = newHolding()
        assertThat(refused.status()).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(refused.errorCode()).isEqualTo("plan_read_only")
        assertThat(refused.json().path("error").path("message").asText())
            .contains("never held hostage").contains("download everything")
        assertThat(investmentCount()).isEqualTo(before)

        val renamed = patch("/api/v1/households/$householdId", owner, mapOf("name" to "Renamed"))
        assertThat(renamed.errorCode()).isEqualTo("plan_read_only")
        assertThat(get("/api/v1/households/$householdId", owner).json().path("name").asText()).isEqualTo("Koduri")
    }

    @Test
    fun `renewing lifts it at once, with nothing to run`() {
        lapse()
        assertThat(newHolding().errorCode()).isEqualTo("plan_read_only")
        setPlan(paidThroughDaysAgo = null)
        assertThat(newHolding().status()).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `reads, the handbook and its PDF all still work`() {
        lapse()
        val holdings = get("/api/v1/households/$householdId/investments", owner)
        assertThat(holdings.status()).isEqualTo(HttpStatus.OK)
        assertThat(holdings.json().toString()).contains("Family gold")
        assertThat(get("/api/v1/households/$householdId/dashboard", owner).status().is2xxSuccessful).isTrue()
        assertThat(get("/api/v1/households/$householdId/continuity/handbook", owner).status())
            .isEqualTo(HttpStatus.OK)

        val headers = HttpHeaders().apply { setBearerAuth(owner) }
        val pdf = http.exchange(
            url("/api/v1/households/$householdId/continuity/handbook.pdf"), HttpMethod.GET,
            HttpEntity<Void>(headers), ByteArray::class.java,
        )
        assertThat(pdf.statusCode.value()).isEqualTo(200)
        assertThat(String(pdf.body!!.copyOf(4), Charsets.US_ASCII)).isEqualTo("%PDF")
    }

    @Test
    fun `download everything still works`() {
        lapse()
        stepUp(owner)
        val headers = HttpHeaders().apply { setBearerAuth(owner) }
        val zip = http.exchange(url("/api/v1/me/export"), HttpMethod.GET, HttpEntity<Void>(headers), ByteArray::class.java)
        assertThat(zip.statusCode.value()).isEqualTo(200)
        assertThat(zip.body!!.copyOf(2)).isEqualTo(byteArrayOf(0x50, 0x4b)) // "PK"
    }

    @Test
    fun `closing the account still works, and so does changing your mind`() {
        lapse()
        assertThat(get("/api/v1/me/closure/preview", owner).status()).isEqualTo(HttpStatus.OK)
        stepUp(owner)
        val closing = post("/api/v1/me/closure", owner)
        assertThat(closing.status().is2xxSuccessful).describedAs(closing.body).isTrue()
        assertThat(post("/api/v1/me/closure/cancel", owner).status().is2xxSuccessful).isTrue()
    }

    // --- every write the API serves -----------------------------------------------

    /**
     * Every POST, PUT, PATCH and DELETE under `/households/{householdId}` in the
     * live contract, sent at a lapsed household. The allowlisted ones must not
     * be refused by the plan (they may fail for their own reasons — random ids,
     * an empty body); every other one must be. And every allowlist line must
     * match something the API serves, so none is left guarding nothing.
     */
    @Test
    fun `every household write is refused unless it is a way to see, leave, protect or hand on`() {
        lapse()
        val spec = mapper.readTree(get("/v3/api-docs").body)
        val operations = spec.path("paths").fields().asSequence().map { it.key to it.value }.toList()
            .filter { (path, _) -> path.startsWith("/api/v1/households/{householdId}/") }
            .flatMap { (path, item) ->
                item.fields().asSequence().map { it.key.uppercase() to it.value }.toList()
                    .filter { (method, _) -> method in setOf("POST", "PUT", "PATCH", "DELETE") }
                    .map { (method, op) -> Triple(method, path, op) }
            }
        assertThat(operations).describedAs("the walk must find the writes").hasSizeGreaterThan(60)

        val wronglyRefused = mutableListOf<String>()
        val wronglyAllowed = mutableListOf<String>()
        val used = mutableSetOf<Int>()

        operations.forEach { (method, path, op) ->
            val rest = path.removePrefix("/api/v1/households/{householdId}").replace(Regex("\\{[^}]+}"), UUID.randomUUID().toString())
            val allowedAt = PlanReadOnlyGuard.ALWAYS_ALLOWED.indexOfFirst { (m, p) -> (m == null || m == method) && p.matches(rest) }
            if (allowedAt >= 0) used += allowedAt

            val multipart = op.path("requestBody").path("content").has("multipart/form-data")
            val code = send(method, "/api/v1/households/$householdId$rest", multipart)
            val refused = code.second == PlanReadOnlyGuard.CODE
            if (allowedAt >= 0 && refused) wronglyRefused += "$method $path"
            if (allowedAt < 0 && !(refused && code.first == 403)) wronglyAllowed += "$method $path -> ${code.first} ${code.second}"
        }

        assertThat(wronglyRefused).describedAs("allowed during a lapse, but refused").isEmpty()
        assertThat(wronglyAllowed).describedAs("a write that a lapsed plan let through").isEmpty()
        val unused = PlanReadOnlyGuard.ALWAYS_ALLOWED.indices.filter { it !in used }
            .map { PlanReadOnlyGuard.ALWAYS_ALLOWED[it] }
        assertThat(unused).describedAs("allowlist lines that match no endpoint").isEmpty()
    }

    private fun send(method: String, path: String, multipart: Boolean): Pair<Int, String?> {
        val headers = HttpHeaders().apply {
            setBearerAuth(owner)
            contentType = if (multipart) MediaType.parseMediaType("multipart/form-data; boundary=x") else MediaType.APPLICATION_JSON
        }
        val body = if (multipart) "--x--\r\n" else "{}"
        val response = http.exchange(url(path), HttpMethod.valueOf(method), HttpEntity(body, headers), String::class.java)
        val json: JsonNode? = runCatching { mapper.readTree(response.body ?: "") }.getOrNull()
        return response.statusCode.value() to json?.path("error")?.path("code")?.asText(null)
    }
}
