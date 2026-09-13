package tech.bhrigu.almira.provider

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource
import tech.bhrigu.almira.support.ApiTestBase

/**
 * Each way a provider fails, through the real wiring — real service, real retry
 * policy, real HTTP — with the sandbox adapters told to fail (docs/13 "When a
 * provider fails").
 *
 * The owner's requirement this answers: a timeout, a delivery failure and an
 * empty balance are three different situations, and each gets its own outcome
 * rather than one generic error.
 */
// Account Aggregator is cut from v1 and disabled by default. Its sandbox is
// kept, and this suite is what keeps it honest, so it asks for it by name
// (ProviderDisabledApiTest covers the default). One shared context for both
// provider suites — the same properties are the same cache key.
@TestPropertySource(properties = ["almira.providers.aa.mode=sandbox"])
@DisplayName("Provider failures, end to end")
class ProviderFailureApiTest : ApiTestBase() {

    @Autowired private lateinit var faults: SandboxFaults

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

    // --- notifications ---------------------------------------------------------

    private fun nameEmergencyContact(): Pair<String, String> {
        val trustedMemberId = post(
            "/api/v1/households/$householdId/members", owner,
            mapOf("displayName" to "Meera", "relationship" to "sibling"),
        ).json().path("id").asText()
        val trusted = signIn()
        joinHousehold(owner, householdId, trustedMemberId, trusted)
        post(
            "/api/v1/households/$householdId/emergency/contacts", owner,
            mapOf("trustedMemberId" to trustedMemberId, "waitDays" to 14),
        )
        return trustedMemberId to trusted
    }

    @Test
    fun `each channel records which way it failed and after how many attempts`() {
        faults.always("sms", SandboxFault.TIMEOUT)
        faults.always("email", SandboxFault.REJECTED)
        faults.always("push", SandboxFault.INSUFFICIENT_BALANCE)
        val (_, trusted) = nameEmergencyContact()

        val rows = db.queryForList(
            """
            select channel, status, failure, attempts from outbound_messages
            where household_id = ?::uuid and template = 'emergency.named'
            """.trimIndent(),
            householdId,
        ).associateBy { it["channel"] as String }

        assertThat(rows["in_app"]!!["status"]).isEqualTo("sent")
        assertThat(rows["sms"]!!).containsEntry("status", "failed").containsEntry("failure", "timeout")
            .containsEntry("attempts", 3)
        assertThat(rows["email"]!!).containsEntry("status", "failed").containsEntry("failure", "rejected")
            .containsEntry("attempts", 1)
        assertThat(rows["push"]!!).containsEntry("status", "failed")
            .containsEntry("failure", "insufficient_balance").containsEntry("attempts", 1)

        // And the person it was for can see it, in words.
        val mine = get("/api/v1/me/messages", trusted).json()
            .filter { it.path("template").asText() == "emergency.named" }
            .associateBy { it.path("channel").asText() }
        assertThat(mine.keys).containsExactlyInAnyOrder("in_app", "sms", "email", "push")
        assertThat(mine["in_app"]!!.path("failureMessage").let { it.isNull || it.isMissingNode }).isTrue()
        val messages = listOf("sms", "email", "push").map { mine[it]!!.path("failureMessage").asText() }
        assertThat(messages).doesNotHaveDuplicates().allMatch { it.isNotBlank() }
        assertThat(mine["push"]!!.path("failureMessage").asText())
            .describedAs("our unpaid bill is not blamed on the person")
            .contains("on our side")
        assertThat(mine["email"]!!.path("failureMessage").asText()).contains("Check your contact details")
    }

    @Test
    fun `a channel that recovers within its attempts is recorded as sent, with the attempts it took`() {
        faults.script("sms", SandboxFault.UNAVAILABLE, SandboxFault.TIMEOUT)
        nameEmergencyContact()
        val sms = db.queryForMap(
            """
            select status, failure, attempts from outbound_messages
            where household_id = ?::uuid and template = 'emergency.named' and channel = 'sms'
            """.trimIndent(),
            householdId,
        )
        assertThat(sms).containsEntry("status", "sent").containsEntry("attempts", 3)
        assertThat(sms["failure"]).isNull()
    }

    @Test
    fun `nobody else's messages appear in my list`() {
        nameEmergencyContact()
        assertThat(
            get("/api/v1/me/messages", owner).json().map { it.path("template").asText() },
        ).describedAs("the owner named the contact; only the contact was told").doesNotContain("emergency.named")
    }

    // --- one-time codes --------------------------------------------------------

    private fun requestCode() = post("/api/v1/auth/otp/request", body = mapOf("phone" to uniquePhone()))

    @Test
    fun `a timeout, a failed delivery and an empty balance each give their own code`() {
        val outcomes = mapOf(
            SandboxFault.TIMEOUT to (HttpStatus.GATEWAY_TIMEOUT to "otp_delivery_delayed"),
            SandboxFault.REJECTED to (HttpStatus.UNPROCESSABLE_ENTITY to "otp_delivery_failed"),
            SandboxFault.INSUFFICIENT_BALANCE to (HttpStatus.SERVICE_UNAVAILABLE to "otp_service_unavailable"),
            SandboxFault.UNAVAILABLE to (HttpStatus.SERVICE_UNAVAILABLE to "otp_provider_unavailable"),
        )
        val seen = outcomes.map { (fault, expected) ->
            faults.clear()
            faults.always("otp", fault)
            val response = requestCode()
            assertThat(response.status()).describedAs(fault.name).isEqualTo(expected.first)
            assertThat(response.errorCode()).describedAs(fault.name).isEqualTo(expected.second)
            assertThat(response.json().path("developmentCode").isMissingNode)
                .describedAs("no code in a failed send, even in development").isTrue()
            response.errorCode()
        }
        assertThat(seen).doesNotHaveDuplicates()
        assertThat(seen).doesNotContain("otp_unavailable")
    }

    @Test
    fun `a delayed code answers with its request id, because the challenge still stands`() {
        faults.always("otp", SandboxFault.TIMEOUT)
        val delayed = requestCode().json().path("error").path("details")
        assertThat(delayed.path("requestId").asText()).isNotBlank()
        assertThat(delayed.path("resendAfterSeconds").asInt()).isPositive()
    }

    // --- DigiLocker ------------------------------------------------------------

    @Test
    fun `DigiLocker's failures are told apart, and a code is not redeemed twice`() {
        post(connect("digilocker/start"), owner)

        faults.always("digilocker", SandboxFault.TIMEOUT)
        val timedOut = post(connect("digilocker/complete"), owner, mapOf("code" to "c"))
        assertThat(timedOut.status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT)
        assertThat(timedOut.errorCode()).isEqualTo("provider_timeout")
        assertThat(timedOut.json().path("error").path("details").path("attempts").asInt())
            .describedAs("an authorisation code redeems once; retrying after a timeout would be refused")
            .isEqualTo(1)

        faults.clear(); faults.always("digilocker", SandboxFault.REJECTED)
        val rejected = post(connect("digilocker/complete"), owner, mapOf("code" to "c"))
        assertThat(rejected.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(rejected.errorCode()).isEqualTo("provider_rejected")

        faults.clear()
        post(connect("digilocker/complete"), owner, mapOf("code" to "c"))
        faults.always("digilocker", SandboxFault.UNAVAILABLE)
        val unavailable = post(connect("digilocker/import"), owner, mapOf("uris" to listOf("x")))
        assertThat(unavailable.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(unavailable.errorCode()).isEqualTo("provider_unavailable")
        assertThat(unavailable.json().path("error").path("details").path("attempts").asInt()).isEqualTo(3)

        faults.clear(); faults.always("digilocker", SandboxFault.INSUFFICIENT_BALANCE)
        val balance = post(connect("digilocker/import"), owner, mapOf("uris" to listOf("x")))
        assertThat(balance.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(balance.errorCode()).isEqualTo("provider_account_unavailable")
        assertThat(balance.json().path("error").path("message").asText()).contains("isn't anything you did")
    }

    @Test
    fun `a list that fails after the code redeemed keeps the connection, and lists again without a new code`() {
        listOf(
            SandboxFault.TIMEOUT to (HttpStatus.GATEWAY_TIMEOUT to "provider_timeout"),
            SandboxFault.UNAVAILABLE to (HttpStatus.SERVICE_UNAVAILABLE to "provider_unavailable"),
        ).forEach { (fault, expected) ->
            // Each fault in its own household, so nothing here reads another's row.
            val hid = createHousehold(owner, "Koduri ${fault.name}", "private", "Ishwarya").path("id").asText()
            val connectTo = { path: String -> "/api/v1/households/$hid/connect/$path" }
            faults.clear()
            post(connectTo("digilocker/start"), owner)

            // The exchange succeeds — the code is spent — and every list attempt fails.
            faults.succeedFirst("digilocker", 1)
            faults.always("digilocker", fault)
            val failed = post(connectTo("digilocker/complete"), owner, mapOf("code" to "one-time"))
            assertThat(failed.status()).describedAs(fault.name).isEqualTo(expected.first)
            assertThat(failed.errorCode()).describedAs(fault.name).isEqualTo(expected.second)
            assertThat(failed.json().path("error").path("details").path("connected").asBoolean())
                .describedAs("${fault.name}: the client must be told the connection stands")
                .isTrue()

            val connection = db.queryForList(
                "select status from provider_connections where household_id = ?::uuid and provider = 'digilocker'",
                hid,
            )
            assertThat(connection.map { it["status"] })
                .describedAs("${fault.name}: a list failure must not roll back the connection the spent code bought")
                .containsExactly("active")

            faults.clear()
            val listed = get(connectTo("digilocker/documents"), owner)
            assertThat(listed.status()).describedAs(fault.name).isEqualTo(HttpStatus.OK)
            assertThat(listed.json().map { it.path("name").asText() }).contains("LIC term policy")
        }
    }

    @Test
    fun `listing DigiLocker documents before connecting is refused, not sent with a pending state`() {
        post(connect("digilocker/start"), owner)
        val listed = get(connect("digilocker/documents"), owner)
        assertThat(listed.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(listed.errorCode()).isEqualTo("not_connected")
    }

    // --- Account Aggregator ----------------------------------------------------

    @Test
    fun `an Account Aggregator failure is not mistaken for a consent that needs approving`() {
        post(connect("aa/consent"), owner)
        get(connect("aa/consent"), owner) // approves

        val codes = mapOf(
            SandboxFault.TIMEOUT to "provider_timeout",
            SandboxFault.UNAVAILABLE to "provider_unavailable",
            SandboxFault.REJECTED to "provider_rejected",
            SandboxFault.INSUFFICIENT_BALANCE to "provider_account_unavailable",
        ).map { (fault, code) ->
            faults.clear(); faults.always("aa", fault)
            val response = post(connect("aa/import"), owner)
            assertThat(response.errorCode()).describedAs(fault.name).isEqualTo(code)
            response.errorCode()
        }
        assertThat(codes).doesNotHaveDuplicates().doesNotContain("consent_not_active")

        faults.clear()
        assertThat(post(connect("aa/import"), owner).json().path("imported").asInt())
            .describedAs("and nothing was half-imported by the failures")
            .isEqualTo(3)
    }

    @Test
    fun `a consent request that times out is not sent twice`() {
        faults.always("aa", SandboxFault.TIMEOUT)
        val response = post(connect("aa/consent"), owner)
        assertThat(response.errorCode()).isEqualTo("provider_timeout")
        assertThat(response.json().path("error").path("details").path("attempts").asInt()).isEqualTo(1)
    }

    // --- WhatsApp --------------------------------------------------------------

    @Test
    fun `a reply that cannot be sent does not fail the capture, and says why`() {
        val webhook = mapOf(
            "entry" to listOf(
                mapOf(
                    "changes" to listOf(
                        mapOf(
                            "value" to mapOf(
                                "messages" to listOf(
                                    mapOf("from" to "919876543210", "text" to mapOf("body" to "1L gold at ICICI")),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val seen = mapOf(
            null to null,
            SandboxFault.TIMEOUT to "provider_timeout",
            SandboxFault.UNAVAILABLE to "provider_unavailable",
            SandboxFault.REJECTED to "provider_rejected",
            SandboxFault.INSUFFICIENT_BALANCE to "provider_account_unavailable",
        ).map { (fault, code) ->
            faults.clear(); fault?.let { faults.always("whatsapp", it) }
            val response = post(connect("whatsapp/inbound"), owner, webhook)
            assertThat(response.status()).describedAs("${fault ?: "no fault"}").isEqualTo(HttpStatus.OK)
            val capture = response.json()
            assertThat(capture.path("understood").asBoolean()).isTrue()
            val failure = capture.path("replyFailure").takeUnless { it.isNull || it.isMissingNode }?.asText()
            assertThat(failure).describedAs("${fault ?: "no fault"}").isEqualTo(code)
            failure
        }
        assertThat(seen.filterNotNull()).hasSize(4).doesNotHaveDuplicates()
    }
}
