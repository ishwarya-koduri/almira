package tech.bhrigu.almira.provider

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tech.bhrigu.almira.reminder.OutboundNotification
import tech.bhrigu.almira.support.ApiTestBase
import java.util.UUID

/**
 * Consent to messages is opt-in (V125; docs/23 "What you agree to"): nothing but an
 * essential account or security notice leaves the app for someone who has not said
 * yes, on that channel.
 *
 * The guard runs before the action (docs/known-issues.md "A guard runs before the
 * action it guards"): these tests look for the queued row and the provider's count,
 * not for a skip recorded afterwards. A message nobody agreed to must never be
 * queued, because a queued row is already a body waiting to go.
 */
@DisplayName("Consent to messages: asked for, never assumed")
class MessagesConsentTest : ApiTestBase() {

    @Autowired private lateinit var outbox: NotificationOutbox
    @Autowired private lateinit var notifier: RecordingNotifier
    @Autowired private lateinit var faults: SandboxFaults
    @Autowired private lateinit var sms: SandboxSmsSender
    @Autowired private lateinit var email: SandboxEmailSender
    @Autowired private lateinit var push: SandboxPushSender

    private lateinit var owner: String
    private lateinit var userId: UUID
    private lateinit var householdId: UUID

    @BeforeEach
    fun setUp() {
        outbox.drain()
        faults.clear()
        val phone = uniquePhone()
        owner = signIn(phone)
        householdId = UUID.fromString(createHousehold(owner, "Koduri", "private", "Ishwarya").path("id").asText())
        userId = UUID.fromString(db.queryForObject("select id::text from users where phone = ?", String::class.java, phone))
    }

    @AfterEach
    fun tearDown() {
        faults.clear()
        outbox.drain()
    }

    private fun tell(template: String): String {
        val key = "consent-test:$template:${UUID.randomUUID()}"
        notifier.deliver(
            OutboundNotification(
                userId = userId, householdId = householdId, reminderId = null,
                template = template, title = "SBI FD matures", body = "Due on Thursday.", idempotencyKey = key,
            ),
        )
        return key
    }

    private fun outside(key: String): List<String> = db.queryForList(
        "select channel from outbound_messages where idempotency_key like ? and channel <> 'in_app' order by channel",
        String::class.java, "$key:%",
    )

    private fun inApp(key: String): Int = db.queryForObject(
        "select count(*) from outbound_messages where idempotency_key = ?", Int::class.java, "$key:in_app",
    )!!

    private fun bodies(key: String): Int = db.queryForObject(
        """
        select count(*) from outbound_message_bodies b join outbound_messages o on o.id = b.message_id
         where o.idempotency_key like ?
        """.trimIndent(),
        Int::class.java, "$key:%",
    )!!

    private fun delivered(key: String): Map<String, Int> = mapOf(
        "sms" to sms.deliveries.times("$key:sms"),
        "email" to email.deliveries.times("$key:email"),
        "push" to push.deliveries.times("$key:push"),
    )

    /** Everything sent outside the app that is not an essential notice. */
    private val nonEssential = listOf(
        "reminder.maturity", "reminder.premium_due", "reminder.emi", "still_true.digest",
        "continuity.reachable", "continuity.key_holder_ask", "emergency.named",
        "lifecycle.coming_of_age.welcomed", "lifecycle.departure.started", "household.member_removal_blocked",
        "something.added_later",
    )

    @Test
    fun `someone never asked is sent nothing outside the app - nothing is queued, so nothing can be sent`() {
        val keys = nonEssential.associateWith { tell(it) }

        keys.forEach { (template, key) ->
            assertThat(outside(key)).describedAs("$template: no email, text or push row was written").isEmpty()
            assertThat(bodies(key)).describedAs("$template: no body is waiting to go").isZero()
            assertThat(inApp(key)).describedAs("$template: the in-app copy is kept").isEqualTo(1)
        }
        outbox.drain()
        keys.forEach { (template, key) ->
            assertThat(delivered(key).values).describedAs("$template: no provider was called").containsOnly(0)
        }
        assertThat(get("/api/v1/me/privacy", owner).json().path("consents")[1].path("given").let { it.isNull || it.isMissingNode })
            .describedAs("and nobody was migrated into consent").isTrue()
    }

    @Test
    fun `essential account and security notices go without consent, on every channel`() {
        val keys = (MessageTemplates.ESSENTIAL_TEMPLATES - "otp_email").associateWith { tell(it) }
        keys.forEach { (template, key) ->
            assertThat(outside(key)).describedAs(template).containsExactly("email", "push", "sms")
        }
        outbox.drain()
        keys.forEach { (template, key) ->
            assertThat(delivered(key)).describedAs(template).containsEntry("sms", 1).containsEntry("email", 1)
        }
    }

    @Test
    fun `a yes covers the channels ticked and no others`() {
        consentToMessages(owner, listOf("email"))
        val key = tell("reminder.maturity")
        assertThat(outside(key)).containsExactly("email")
        outbox.drain()
        assertThat(delivered(key)).containsExactlyInAnyOrderEntriesOf(mapOf("sms" to 0, "email" to 1, "push" to 0))
    }

    @Test
    fun `a yes from a client that named no channels covers email and text, what its button said`() {
        val given = post("/api/v1/me/privacy/consents", owner, mapOf("purpose" to "messages", "given" to true))
        assertThat(given.json().path("consents")[1].path("channels").map { it.asText() }).containsExactly("email", "sms")
        assertThat(outside(tell("reminder.maturity"))).containsExactly("email", "sms")
    }

    @Test
    fun `a withdrawal stops what comes after it, and a message already waiting is not sent`() {
        consentToMessages(owner)
        val waiting = outbox.whilePaused {
            tell("reminder.maturity").also {
                assertThat(outside(it)).containsExactly("email", "push", "sms")
                db.update(
                    "insert into consent_events (user_id, purpose, action, notice_version) " +
                        "values (?::uuid, 'messages', 'withdrawn', app.current_privacy_notice_version())",
                    userId.toString(),
                )
            }
        }
        outbox.drain()
        assertThat(delivered(waiting).values).containsOnly(0)
        assertThat(
            db.queryForList(
                "select distinct failure from outbound_messages where idempotency_key like ? and channel <> 'in_app'",
                String::class.java, "$waiting:%",
            ),
        ).containsExactly(NotificationOutbox.NO_CONSENT)
        assertThat(outside(tell("still_true.digest"))).isEmpty()
    }

    @Test
    fun `pacing and consent ask the database the same question the wording does`() {
        val probes = MessageTemplates.ESSENTIAL_TEMPLATES + nonEssential + listOf(
            "auth.something_new", "emergency.something_new", "lifecycle.something_new",
            "lifecycle.memorial.reversed", "lifecycle.successor.named", "lifecycle.departure.completed.you",
            "lifecycle.departure.cancelled", "lifecycle.departure.completed", "lifecycle.coming_of_age.guardian",
            "continuity.key_holder_answer", "otp_email",
        )
        probes.forEach { template ->
            val inDatabase = db.queryForObject("select app.message_is_essential(?)", Boolean::class.java, template)
            assertThat(inDatabase).describedAs(template).isEqualTo(MessageTemplates.isEssential(template))
        }
        // And the list itself, read out of the function: nothing essential there that is not here.
        val definition = db.queryForObject(
            "select pg_get_functiondef('app.message_is_essential(text)'::regprocedure)", String::class.java,
        )!!
        val listed = Regex("'([a-z_]+(?:\\.[a-z_]+)*)'").findAll(definition.substringAfter("in (")).map { it.groupValues[1] }.toSet()
        assertThat(listed).describedAs("app.message_is_essential").isEqualTo(MessageTemplates.ESSENTIAL_TEMPLATES)
    }
}
