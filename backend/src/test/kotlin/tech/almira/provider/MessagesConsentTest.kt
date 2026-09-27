package tech.almira.provider

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import tech.almira.reminder.OutboundNotification
import tech.almira.support.ApiTestBase
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
        "continuity.reachable", "continuity.key_holder_ask",
        "lifecycle.coming_of_age.welcomed", "lifecycle.coming_of_age.guardian", "lifecycle.departure.started",
        "lifecycle.departure.completed", "lifecycle.departure.cancelled", "household.member_removal_blocked",
        "something.added_later",
    )

    /**
     * The owner's test (V140): "does it change YOUR rights or obligations?" These do,
     * for the one person each is sent to.
     */
    private val changesYourRights = listOf(
        "emergency.named", "lifecycle.departure.completed.you", "lifecycle.memorial.reversed", "lifecycle.successor.named",
        // V143: a household with nobody running it, or running again, changes your access.
        "lifecycle.household.dormant", "lifecycle.household.dormant.you", "lifecycle.household.running_again",
        "lifecycle.household.ownership_accepted",
    )

    /** Someone else joined or left: household news, and under consent. */
    private val householdNews = listOf(
        "lifecycle.departure.started", "lifecycle.departure.completed", "lifecycle.departure.cancelled",
        "lifecycle.coming_of_age.welcomed",
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
        // Both exclusions are templates that are never queued: a sign-in code
        // and an emailed export are sent on the request path, with the outcome
        // in the response. They are on the essential list so the classification
        // is complete, not because this worker ever carries them.
        val neverQueued = setOf("otp_email", "export.link")
        val keys = (MessageTemplates.ESSENTIAL_TEMPLATES - neverQueued).associateWith { tell(it) }
        keys.forEach { (template, key) ->
            assertThat(outside(key)).describedAs(template).containsExactly("email", "push", "sms")
        }
        outbox.drain()
        keys.forEach { (template, key) ->
            assertThat(delivered(key)).describedAs(template).containsEntry("sms", 1).containsEntry("email", 1)
        }
    }

    @Test
    fun `a notice that changes your own rights goes outside the app without consent, household news does not`() {
        val yours = changesYourRights.associateWith { tell(it) }
        val news = householdNews.associateWith { tell(it) }

        yours.forEach { (template, key) ->
            assertThat(MessageTemplates.isEssential(template)).describedAs(template).isTrue()
            assertThat(outside(key)).describedAs("$template: queued on every channel, never asked").containsExactly("email", "push", "sms")
        }
        news.forEach { (template, key) ->
            assertThat(MessageTemplates.isEssential(template)).describedAs(template).isFalse()
            assertThat(outside(key)).describedAs("$template: nothing queued without a yes").isEmpty()
            assertThat(inApp(key)).describedAs("$template: still in the app").isEqualTo(1)
        }
        outbox.drain()
        yours.forEach { (template, key) ->
            assertThat(delivered(key)).describedAs(template).containsEntry("sms", 1).containsEntry("email", 1)
        }
        news.forEach { (template, key) ->
            assertThat(delivered(key).values).describedAs(template).containsOnly(0)
        }
    }

    // --- a yes from before channels were chosen (V142) ------------------------------

    /**
     * A `given` event with no channels, as a tap on "Give" before V125 wrote. V142 refuses
     * new ones, so the fixture lifts that check for its own insert and puts it back in the
     * same transaction: no other session ever sees the table without it.
     */
    private fun yesFromBeforeChannels() {
        db.execute(org.springframework.jdbc.core.ConnectionCallback { connection ->
            val autoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.execute("alter table consent_events drop constraint consent_events_messages_yes_names_channels")
                    statement.execute(
                        "insert into consent_events (user_id, purpose, action, notice_version, created_at) " +
                            "values ('$userId', 'messages', 'given', app.current_privacy_notice_version(), now() - interval '1 year')",
                    )
                    statement.execute(
                        "alter table consent_events add constraint consent_events_messages_yes_names_channels " +
                            "check (purpose <> 'messages' or action <> 'given' or channels is not null) not valid",
                    )
                }
                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = autoCommit
            }
        })
    }

    @Test
    fun `a yes from before channels were chosen sends nothing until it is given again`() {
        yesFromBeforeChannels()

        val keys = listOf("reminder.maturity", "still_true.digest", "continuity.reachable").associateWith { tell(it) }
        keys.forEach { (template, key) ->
            assertThat(outside(key)).describedAs("$template: not queued on email, text or push").isEmpty()
            assertThat(inApp(key)).describedAs("$template: in the app as ever").isEqualTo(1)
        }
        outbox.drain()
        keys.forEach { (template, key) ->
            assertThat(delivered(key).values).describedAs("$template: no provider was called").containsOnly(0)
        }
        // An essential notice still goes: it never needed the yes.
        assertThat(outside(tell("auth.new_sign_in"))).containsExactly("email", "push", "sms")

        val messages = get("/api/v1/me/privacy", owner).json().path("consents")[1]
        assertThat(messages.path("given").let { it.isNull || it.isMissingNode }).describedAs("not shown as given").isTrue()
        assertThat(messages.path("askingAgain").asBoolean()).isTrue()
        assertThat(messages.path("changedAt").asText()).describedAs("when they said it is kept").isNotEmpty()
        val ask = get("/api/v1/me/privacy/messages-ask", owner).json()
        assertThat(ask.path("ask").asBoolean()).describedAs("asked again").isTrue()
        assertThat(ask.path("askingAgain").asBoolean()).describedAs("and told why").isTrue()
        val history = get("/api/v1/me/privacy/history", owner).json()
        assertThat(history.map { it.path("action").asText() }).describedAs("the old yes is not deleted").containsExactly("given")
        assertThat(history[0].path("channels").let { it.isNull || it.isMissingNode }).describedAs("and claims no channels").isTrue()
    }

    @Test
    fun `given again with channels, exactly those channels are sent on, and the answer is a new event`() {
        yesFromBeforeChannels()
        val notNow = post("/api/v1/me/privacy/messages-ask/not-now", owner)
        assertThat(notNow.json().path("askingAgain").asBoolean()).isTrue()
        assertThat(outside(tell("reminder.maturity"))).describedAs("not now is not a yes").isEmpty()

        val given = post(
            "/api/v1/me/privacy/consents", owner,
            mapOf("purpose" to "messages", "given" to true, "channels" to listOf("email"), "askedIn" to "in_context"),
        )
        assertThat(given.json().path("consents")[1].path("channels").map { it.asText() }).containsExactly("email")
        assertThat(given.json().path("consents")[1].path("askingAgain").asBoolean()).isFalse()

        val key = tell("reminder.maturity")
        assertThat(outside(key)).containsExactly("email")
        outbox.drain()
        assertThat(delivered(key)).containsExactlyInAnyOrderEntriesOf(mapOf("sms" to 0, "email" to 1, "push" to 0))

        val history = get("/api/v1/me/privacy/history", owner).json()
        assertThat(history.map { it.path("action").asText() }).containsExactly("given", "given")
        assertThat(history.map { it.path("askedAgain").asBoolean() }).describedAs("the new answer is marked").containsExactly(true, false)
        assertThat(get("/api/v1/me/privacy/messages-ask", owner).json().path("askingAgain").asBoolean()).isFalse()
        assertThat(
            db.queryForObject(
                "select diff::text from activity_log where actor_user_id = ?::uuid and action = 'privacy.consent_give'",
                String::class.java, userId,
            ),
        ).contains("askedAgain")
    }

    @Test
    fun `a message waiting on a yes that is replaced by one from before channels is not sent`() {
        consentToMessages(owner, listOf("email"))
        val waiting = outbox.whilePaused {
            tell("reminder.maturity").also {
                assertThat(outside(it)).containsExactly("email")
                yesFromBeforeChannels()
            }
        }
        outbox.drain()
        assertThat(delivered(waiting).values).containsOnly(0)
    }

    @Test
    fun `no new yes is written without channels`() {
        val blocked = runCatching {
            db.update(
                "insert into consent_events (user_id, purpose, action, notice_version) " +
                    "values (?::uuid, 'messages', 'given', app.current_privacy_notice_version())",
                userId.toString(),
            )
        }
        assertThat(blocked.exceptionOrNull()).describedAs("V142 refuses it").isNotNull()
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
    fun `a yes that names no channels is refused, empty or left out, and nothing is written or sent`() {
        val before = db.queryForObject("select count(*) from consent_events where user_id = ?::uuid", Int::class.java, userId.toString())
        for (body in listOf(
            mapOf("purpose" to "messages", "given" to true),
            mapOf("purpose" to "messages", "given" to true, "channels" to emptyList<String>()),
            mapOf("purpose" to "messages", "given" to true, "channels" to listOf(" ")),
        )) {
            val refused = post("/api/v1/me/privacy/consents", owner, body)
            assertThat(refused.status()).describedAs("refused: %s", body).isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(refused.errorCode()).isEqualTo("channels_required")
        }
        assertThat(db.queryForObject("select count(*) from consent_events where user_id = ?::uuid", Int::class.java, userId.toString()))
            .describedAs("no event written, so nobody is left looking handled").isEqualTo(before)
        assertThat(outside(tell("reminder.maturity"))).describedAs("and nothing leaves the app").isEmpty()
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
