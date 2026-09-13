package tech.bhrigu.almira.provider

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import tech.bhrigu.almira.auth.EmailOtpSender
import tech.bhrigu.almira.support.ApiTestBase

/**
 * Every provider disabled at once, through the real server over real HTTP.
 *
 * The owner's rule: a provider being absent is a normal state, not an error. So
 * the application starts (this class would not run otherwise), the status says
 * DISABLED rather than "sandbox" or "connected", every call to a disabled
 * provider answers one plain code instead of a 500, and a disabled notification
 * channel is skipped without leaving a row that says something was attempted.
 *
 * Closed after the class: it is a context of its own, and the database has a
 * connection budget the rest of the suite shares.
 */
@TestPropertySource(
    properties = [
        "almira.providers.sms.mode=disabled",
        "almira.providers.email.mode=disabled",
        "almira.providers.push.mode=disabled",
        "almira.providers.digilocker.mode=disabled",
        "almira.providers.aa.mode=disabled",
        "almira.providers.whatsapp.mode=disabled",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Providers, disabled: absent without error")
class ProviderDisabledApiTest : ApiTestBase() {

    // The context, not a List<ChannelSender>: field injection of an empty list
    // fails, where the notifier's single constructor is handed an empty one.
    @Autowired private lateinit var context: org.springframework.context.ApplicationContext
    @Autowired private lateinit var emailOtp: EmailOtpSender

    private lateinit var owner: String
    private lateinit var householdId: String

    @BeforeEach
    fun setUp() {
        owner = signIn()
        householdId = createHousehold(owner, "Koduri", "private", "Ishwarya").path("id").asText()
    }

    private fun connect(path: String) = "/api/v1/households/$householdId/connect/$path"

    private val webhook = mapOf(
        "entry" to listOf(
            mapOf(
                "changes" to listOf(
                    mapOf("value" to mapOf("messages" to listOf(mapOf("from" to "919876543210", "text" to mapOf("body" to "1L gold"))))),
                ),
            ),
        ),
    )

    @Test
    fun `the status reports each provider as disabled, never connected, with no sandbox note`() {
        // A connection left over from when the provider was on must not make a
        // disabled provider look connected.
        db.update(
            """
            insert into provider_connections (household_id, provider, mode, status, external_ref, created_by)
            select ?::uuid, 'account_aggregator', 'sandbox', 'active', 'old-consent', created_by
            from households where id = ?::uuid
            """.trimIndent(),
            householdId, householdId,
        )

        val response = get(connect("providers"), owner)
        assertThat(response.statusCode.value()).describedAs(response.body).isEqualTo(200)
        val providers = response.json().associateBy { it.path("provider").asText() }

        assertThat(providers.keys).containsExactlyInAnyOrder("digilocker", "account_aggregator", "whatsapp")
        providers.forEach { (name, status) ->
            assertThat(status.path("mode").asText()).describedAs("$name mode").isEqualTo("DISABLED")
            assertThat(status.path("connected").asBoolean()).describedAs("$name connected").isFalse()
            assertThat(status.path("sandboxNote").let { it.isNull || it.isMissingNode })
                .describedAs("$name must not describe a sandbox that is not running").isTrue()
        }
    }

    @Test
    fun `every call to a disabled provider answers 409 provider_disabled and writes nothing`() {
        val calls = listOf(
            "digilocker" to { post(connect("digilocker/start"), owner) },
            "digilocker" to { post(connect("digilocker/complete"), owner, mapOf("code" to "any")) },
            "digilocker" to { get(connect("digilocker/documents"), owner) },
            "digilocker" to { post(connect("digilocker/import"), owner, mapOf("uris" to listOf("x"))) },
            "aa" to { post(connect("aa/consent"), owner) },
            "aa" to { get(connect("aa/consent"), owner) },
            "aa" to { post(connect("aa/import"), owner) },
            "whatsapp" to { post(connect("whatsapp/inbound"), owner, webhook) },
        )
        calls.forEachIndexed { index, (provider, call) ->
            val response = call()
            assertThat(response.statusCode.value()).describedAs("call $index: ${response.body}").isEqualTo(409)
            assertThat(response.errorCode()).describedAs("call $index").isEqualTo("provider_disabled")
            assertThat(response.json().path("error").path("details").path("provider").asText())
                .describedAs("call $index").isEqualTo(provider)
            assertThat(response.json().path("error").path("message").asText())
                .describedAs("call $index: a sentence a person can read")
                .contains("isn't offered on this server")
        }

        assertThat(
            db.queryForObject(
                "select count(*) from provider_connections where household_id = ?::uuid", Int::class.java, householdId,
            ),
        ).describedAs("no pending connection is left behind by a refused call").isZero()
        assertThat(
            db.queryForObject(
                "select count(*) from activity_log where household_id = ?::uuid and action like 'provider.%'",
                Int::class.java, householdId,
            ),
        ).describedAs("nothing was connected or imported, so nothing is audited as if it were").isZero()
    }

    @Test
    fun `someone outside the household is told it does not exist, not how the server is configured`() {
        val stranger = signIn()
        val response = post(connect("aa/consent"), stranger)
        assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `a notification skips every disabled channel and records only what was delivered`() {
        assertThat(context.getBeansOfType(ChannelSender::class.java))
            .describedAs("a disabled channel has no sender at all").isEmpty()

        val trustedMemberId = post(
            "/api/v1/households/$householdId/members", owner,
            mapOf("displayName" to "Meera", "relationship" to "sibling"),
        ).json().path("id").asText()
        val trusted = signIn()
        joinHousehold(owner, householdId, trustedMemberId, trusted)
        val named = post(
            "/api/v1/households/$householdId/emergency/contacts", owner,
            mapOf("trustedMemberId" to trustedMemberId, "waitDays" to 14),
        )
        assertThat(named.statusCode.is2xxSuccessful).describedAs(named.body).isTrue()

        val rows = db.queryForList(
            "select channel, status from outbound_messages where household_id = ?::uuid and template = 'emergency.named'",
            householdId,
        )
        assertThat(rows.map { it["channel"] })
            .describedAs("in-app only: no sms, email or push row, and so no 'failed' to explain")
            .containsExactly("in_app")
        assertThat(rows.single()["status"]).isEqualTo("sent")

        val mine = get("/api/v1/me/messages", trusted).json()
            .filter { it.path("template").asText() == "emergency.named" }
        assertThat(mine.map { it.path("channel").asText() }).containsExactly("in_app")
    }

    @Test
    fun `email sign-in cannot claim to deliver through a disabled email channel`() {
        assertThat(emailOtp.available).isFalse()
        assertThat(emailOtp.exposesCodeForDevelopment).isFalse()
    }
}
