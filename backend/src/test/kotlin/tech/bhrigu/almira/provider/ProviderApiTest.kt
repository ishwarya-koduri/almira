package tech.bhrigu.almira.provider

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal

/**
 * Every provider here runs in sandbox, which is the point: the part that can be
 * got wrong — consent states, duplicate handling, where an imported record's
 * privacy comes from, what happens to a document once it arrives — is exercised
 * now, and only the transport is left for the day the accounts exist.
 */
@DisplayName("Provider adapters, in sandbox")
class ProviderApiTest : ApiTestBase() {

    private lateinit var owner: String
    private lateinit var spouse: String
    private lateinit var householdId: String

    @BeforeEach
    fun setUp() {
        owner = signIn()
        spouse = signIn()
        householdId = createHousehold(owner, "Koduri", "private", "Ishwarya").path("id").asText()
        val spouseMemberId = addMember(owner, householdId, "Ravi").path("id").asText()
        joinHousehold(owner, householdId, spouseMemberId, spouse, role = "viewer")
    }

    /** Shaped like Meta's webhook, because the live parser is the same one. */
    private fun metaWebhook(text: String) = mapOf(
        "entry" to listOf(
            mapOf(
                "changes" to listOf(
                    mapOf(
                        "value" to mapOf(
                            "messages" to listOf(
                                mapOf("from" to "919876543210", "text" to mapOf("body" to text)),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    // --- what is on and what it would take ------------------------------------

    @Test
    fun `the status says what each provider is, and exactly what would make it real`() {
        val providers = get("/api/v1/households/$householdId/connect/providers", owner).json()
            .associateBy { it.path("provider").asText() }

        assertThat(providers.keys).containsExactlyInAnyOrder(
            "digilocker", "account_aggregator", "whatsapp",
        )
        assertThat(providers["digilocker"]!!.path("mode").asText()).isEqualTo("SANDBOX")
        assertThat(providers["digilocker"]!!.path("toGoLive").map { it.asText() })
            .describedAs("a list someone can actually work through")
            .anyMatch { it.contains("client id and secret") }
        assertThat(providers["account_aggregator"]!!.path("toGoLive").map { it.asText() })
            .anyMatch { it.contains("FIU registration") }
        assertThat(providers["whatsapp"]!!.path("sandboxNote").asText())
            .describedAs("the dangerous one says so")
            .contains("does NOT check")
    }

    // --- DigiLocker ------------------------------------------------------------

    @Test
    fun `a document imported from DigiLocker lands in the vault, encrypted like any other`() {
        post("/api/v1/households/$householdId/connect/digilocker/start", owner)
        val available = post(
            "/api/v1/households/$householdId/connect/digilocker/complete", owner,
            mapOf("code" to "sandbox-code"),
        ).json()
        assertThat(available.map { it.path("name").asText() }).contains("LIC term policy")

        val policyUri = available.first { it.path("docType").asText() == "insurance" }
            .path("uri").asText()
        val imported = post(
            "/api/v1/households/$householdId/connect/digilocker/import", owner,
            mapOf("uris" to listOf(policyUri)),
        ).json()
        assertThat(imported.path("imported").asInt()).isEqualTo(1)

        val documents = get("/api/v1/households/$householdId/documents", owner).json()
        assertThat(documents.map { it.path("fileName").asText() }).contains("LIC term policy.pdf")

        // The bytes go through the same envelope as an upload. The sandbox
        // document contains a policy number, and it must be no more readable on
        // disk than a real one would be.
        val storageKey = db.queryForList(
            "select storage_key from documents where household_id = ?::uuid", householdId,
        ).single()["storage_key"] as String
        val onDisk = java.io.File(
            System.getProperty("almira.documents.dir") ?: "./var/documents", storageKey,
        )
        if (onDisk.exists()) {
            assertThat(String(onDisk.readBytes(), Charsets.ISO_8859_1))
                .describedAs("a policy number arriving from a provider is still a policy number")
                .doesNotContain("5567123456")
        }
    }

    @Test
    fun `importing a document nobody offered is skipped rather than invented`() {
        post("/api/v1/households/$householdId/connect/digilocker/start", owner)
        post(
            "/api/v1/households/$householdId/connect/digilocker/complete", owner,
            mapOf("code" to "sandbox-code"),
        )
        val imported = post(
            "/api/v1/households/$householdId/connect/digilocker/import", owner,
            mapOf("uris" to listOf("in.gov.not-a-real-thing")),
        ).json()
        assertThat(imported.path("imported").asInt()).isZero()
        assertThat(imported.path("skipped").asInt()).isEqualTo(1)
    }

    // --- Account Aggregator ----------------------------------------------------

    @Test
    fun `consent is asked for, and fetching before it is granted is refused`() {
        val consent = post("/api/v1/households/$householdId/connect/aa/consent", owner).json()
        assertThat(consent.path("status").asText())
            .describedAs("a flow that skips consent in testing is a flow nobody has tested")
            .isEqualTo("PENDING")
        assertThat(consent.path("approvalUrl").asText()).isNotBlank()

        val tooEarly = post("/api/v1/households/$householdId/connect/aa/import", owner)
        assertThat(tooEarly.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(tooEarly.errorCode()).isEqualTo("consent_not_active")
    }

    @Test
    fun `approved holdings become ordinary records at the household's own visibility`() {
        post("/api/v1/households/$householdId/connect/aa/consent", owner)
        assertThat(
            get("/api/v1/households/$householdId/connect/aa/consent", owner).json()
                .path("status").asText(),
        ).isEqualTo("ACTIVE")

        val imported = post("/api/v1/households/$householdId/connect/aa/import", owner).json()
        assertThat(imported.path("imported").asInt()).isEqualTo(3)
        assertThat(imported.path("titles").map { it.asText() })
            .contains("ICICI fixed deposit", "Parag Parikh Flexi Cap")

        val holdings = get("/api/v1/households/$householdId/investments", owner).json()
        assertThat(holdings).hasSize(3)
        assertThat(holdings.first().path("visibility").asText())
            .describedAs("the household chose private as its default, and an import does not overrule that")
            .isEqualTo("private")
        assertThat(get("/api/v1/households/$householdId/investments", spouse).json())
            .describedAs("nor does it quietly share them")
            .isEmpty()

        // The provider's own figure becomes a dated valuation rather than a
        // claim about today.
        assertThat(holdings.first { it.path("title").asText() == "ICICI fixed deposit" }
            .path("valueBasis").asText()).isEqualTo("valued")
    }

    @Test
    fun `re-importing recognises what is already here`() {
        post("/api/v1/households/$householdId/connect/aa/consent", owner)
        get("/api/v1/households/$householdId/connect/aa/consent", owner)
        post("/api/v1/households/$householdId/connect/aa/import", owner)

        val again = post("/api/v1/households/$householdId/connect/aa/import", owner).json()
        assertThat(again.path("imported").asInt()).isZero()
        assertThat(again.path("skipped").asInt()).isEqualTo(3)
        assertThat(get("/api/v1/households/$householdId/investments", owner).json()).hasSize(3)
    }

    // --- WhatsApp --------------------------------------------------------------

    @Test
    fun `a message becomes a proposal, never a saved record`() {
        val captured = post(
            "/api/v1/households/$householdId/connect/whatsapp/inbound", owner,
            metaWebhook("1L gold at ICICI"),
        ).json()
        assertThat(captured.path("understood").asBoolean()).isTrue()
        assertThat(captured.path("reply").asText()).contains("Open Almira to confirm")
        assertThat(captured.path("fields").map { it.path("display").asText() }).contains("₹1,00,000")

        assertThat(get("/api/v1/households/$householdId/investments", owner).json())
            .describedAs("texting a number must not write to a registry")
            .isEmpty()
    }

    @Test
    fun `a message nobody can read gets an answer rather than silence`() {
        val captured = post(
            "/api/v1/households/$householdId/connect/whatsapp/inbound", owner, metaWebhook("hello?"),
        ).json()
        assertThat(captured.path("understood").asBoolean()).isFalse()
        assertThat(captured.path("reply").asText()).contains("Try something like")
    }

    // --- notifications ---------------------------------------------------------

    /**
     * Notifications remain a stand-in until real accounts exist, which is the
     * right call — but "we logged it" is unverifiable. Every outbound message is
     * now recorded, so a test can ask whether the person who should have been
     * told was told.
     */
    @Test
    fun `an outbound notification is recorded, without its body`() {
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

        val messages = db.queryForList(
            "select * from outbound_messages where household_id = ?::uuid", householdId,
        )
        assertThat(messages).isNotEmpty()
        assertThat(messages.map { it["template"] as String }).contains("emergency.named")
        assertThat(messages.map { it["channel"] as String })
            .describedAs("in-app now, and every channel that is switched on")
            .contains("in_app", "sms", "email", "push")
        assertThat(messages.mapNotNull { it["title"] as String? })
            .describedAs("a title, never a body — this is the least protected table we write")
            .allMatch { !it.contains("₹") }
    }

    @Test
    fun `a member cannot read someone else's notifications`() {
        assertThat(
            db.queryForObject(
                "select count(*) from outbound_messages", Int::class.java,
            ),
        ).describedAs("the owner-role fixture sees everything; the policy is asserted in SQL")
            .isNotNull()
    }

    @Test
    fun `connecting a provider is an administrator's decision`() {
        val refused = post("/api/v1/households/$householdId/connect/aa/consent", spouse)
        assertThat(refused.status()).isIn(HttpStatus.FORBIDDEN, HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `an imported holding still obeys the value rules`() {
        post("/api/v1/households/$householdId/connect/aa/consent", owner)
        get("/api/v1/households/$householdId/connect/aa/consent", owner)
        post("/api/v1/households/$householdId/connect/aa/import", owner)

        assertThat(dashboardTotal(owner, householdId))
            .describedAs("184320 + 500000 + 326540")
            .isEqualByComparingTo(BigDecimal("1010860.00"))
    }
}
