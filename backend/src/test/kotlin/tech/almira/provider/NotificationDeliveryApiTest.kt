package tech.almira.provider

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import tech.almira.reminder.OutboundNotification
import tech.almira.support.ApiTestBase
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Who a notification reaches, and when (docs/13 "Who a message is for",
 * "Pacing"; known-issues 13).
 *
 * The real notifier, outbox, directory and sandbox channels, and the real
 * database. Pacing applies to live channels; these tests switch it on for the
 * sandbox ([DeliveryPacing.paceSandboxChannels]) and give it a clock, so the
 * rules are exercised whatever time the suite runs.
 */
@DisplayName("Notification delivery: recipients, preferences, pacing")
class NotificationDeliveryApiTest : ApiTestBase() {

    @Autowired private lateinit var outbox: NotificationOutbox
    @Autowired private lateinit var notifier: RecordingNotifier
    @Autowired private lateinit var pacing: DeliveryPacing
    @Autowired private lateinit var faults: SandboxFaults
    @Autowired private lateinit var sms: SandboxSmsSender
    @Autowired private lateinit var push: SandboxPushSender

    private lateinit var phone: String
    private lateinit var owner: String
    private lateinit var householdId: String
    private lateinit var userId: UUID

    private val india = ZoneId.of("Asia/Kolkata")

    @BeforeEach
    fun setUp() {
        outbox.drain()
        faults.clear()
        phone = uniquePhone()
        owner = signIn(phone)
        // How a message reaches someone who said yes to messages (V125).
        consentToMessages(owner)
        householdId = createHousehold(owner, "Koduri", "private", "Ishwarya").path("id").asText()
        userId = UUID.fromString(db.queryForObject("select id::text from users where phone = ?", String::class.java, phone))
    }

    @AfterEach
    fun tearDown() {
        pacing.paceSandboxChannels = false
        pacing.clock = Clock.systemUTC()
        faults.clear()
        outbox.drain()
    }

    private fun tell(template: String = "reminder.maturity", key: String = "test:${UUID.randomUUID()}"): String {
        notifier.deliver(
            OutboundNotification(
                userId = userId, householdId = UUID.fromString(householdId), reminderId = null,
                template = template, title = "SBI FD matures", body = "Due on Thursday.", idempotencyKey = key,
            ),
        )
        return key
    }

    private data class Row(val status: String, val failure: String?, val deferredFor: String?, val notBefore: java.time.Instant?)

    private fun row(key: String, channel: String): Row = db.queryForObject(
        "select status, failure, deferred_for, not_before from outbound_messages where idempotency_key = ?",
        { rs, _ -> Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4)?.toInstant()) },
        "$key:$channel",
    )!!

    /** Today in India, at [time] — the household's own zone. */
    private fun at(time: LocalTime, plusDays: Long = 0) {
        val instant = LocalDate.now(india).plusDays(plusDays).atTime(time).atZone(india).toInstant()
        pacing.clock = Clock.fixed(instant, ZoneOffset.UTC)
    }

    // --- who ---------------------------------------------------------------------

    @Test
    fun `a text goes to the number the person signed in with`() {
        val key = tell()
        outbox.drain()
        assertThat(row(key, "sms").status).isEqualTo("sent")
        assertThat(sms.deliveries.recipientEnding("$key:sms"))
            .describedAs("known-issues 13: the channel is told who it is for")
            .isEqualTo(phone.takeLast(4))
    }

    @Test
    fun `a push goes to every registered device, and a device the platform refuses is forgotten`() {
        listOf("install-phone-one", "install-phone-two").forEach { id ->
            val registered = call(
                HttpMethod.PUT, "/api/v1/me/devices/$id", owner,
                mapOf("platform" to "android", "token" to "fcm-token-for-$id-0123456789"),
            )
            assertThat(registered.statusCode.value()).describedAs(registered.body).isEqualTo(200)
        }

        val key = tell()
        outbox.drain()
        assertThat(row(key, "push").status).isEqualTo("sent")
        listOf("install-phone-one", "install-phone-two").forEach { id ->
            assertThat(push.deliveries.times("$key:push:$id")).describedAs(id).isEqualTo(1)
            assertThat(push.deliveries.recipientEnding("$key:push:$id")).isEqualTo("6789")
        }

        faults.always("push", SandboxFault.REJECTED)
        val refused = tell()
        outbox.drain()
        assertThat(row(refused, "push")).extracting("status", "failure").containsExactly("failed", "rejected")
        assertThat(get("/api/v1/me/devices", owner).json()).isEmpty()
    }

    @Test
    fun `a device is registered once per installation, and its token never comes back`() {
        val body = mapOf("platform" to "ios", "token" to "a1b2c3d4e5f6a7b8c9d0", "environment" to "production", "appVersion" to "1.4.0")
        repeat(2) { call(HttpMethod.PUT, "/api/v1/me/devices/iphone-install-1", owner, body) }
        val listed = get("/api/v1/me/devices", owner)
        assertThat(listed.json()).hasSize(1)
        assertThat(listed.body).doesNotContain("a1b2c3d4e5f6a7b8c9d0")
        assertThat(listed.json()[0].path("environment").asText()).isEqualTo("production")
        assertThat(db.queryForObject("select count(*) from activity_log where action = 'device.registered' and actor_user_id = ?", Int::class.java, userId))
            .isEqualTo(2)
    }

    @Test
    fun `a device body that is not a device is refused, field by field`() {
        val bad = call(
            HttpMethod.PUT, "/api/v1/me/devices/x", owner,
            mapOf("platform" to "windows-phone", "token" to "has spaces in it"),
        )
        assertThat(bad.statusCode.value()).isEqualTo(400)
        assertThat(bad.json().path("error").path("details").path("fields").fieldNames().asSequence().toList())
            .contains("installationId", "platform", "token")

        val ios = call(HttpMethod.PUT, "/api/v1/me/devices/iphone-install-2", owner, mapOf("platform" to "ios", "token" to "a1b2c3d4e5f6a7b8c9d0"))
        assertThat(ios.json().path("error").path("details").path("fields").has("environment")).isTrue()
    }

    @Test
    fun `someone else's device is not there to remove`() {
        call(HttpMethod.PUT, "/api/v1/me/devices/owner-install-1", owner, mapOf("platform" to "android", "token" to "fcm-token-0123456789abcdef"))
        val stranger = signIn()
        assertThat(delete("/api/v1/me/devices/owner-install-1", stranger).statusCode.value()).isEqualTo(404)
        assertThat(get("/api/v1/me/devices", stranger).json()).isEmpty()
        assertThat(get("/api/v1/me/devices", owner).json()).hasSize(1)
        assertThat(delete("/api/v1/me/devices/owner-install-1", owner).statusCode.value()).isEqualTo(204)
    }

    // --- what they chose ---------------------------------------------------------

    @Test
    fun `preferences start at the defaults and say where each channel can reach`() {
        val prefs = get("/api/v1/me/notification-preferences", owner).json()
        assertThat(prefs.path("quietFrom").asText()).isEqualTo("21:00")
        assertThat(prefs.path("quietUntil").asText()).isEqualTo("08:00")
        assertThat(prefs.path("smsEnabled").asBoolean()).isTrue()
        val channels = prefs.path("channels").associate { it.path("channel").asText() to it.path("reachable").asBoolean() }
        assertThat(channels).containsEntry("sms", true).containsEntry("email", false).containsEntry("push", false)
    }

    @Test
    fun `preferences are saved, checked, and audited`() {
        val saved = call(
            HttpMethod.PUT, "/api/v1/me/notification-preferences", owner,
            mapOf("smsEnabled" to false, "quietFrom" to "22:30", "quietUntil" to "06:30"),
        )
        assertThat(saved.statusCode.value()).describedAs(saved.body).isEqualTo(200)
        assertThat(saved.json().path("smsEnabled").asBoolean()).isFalse()
        assertThat(saved.json().path("emailEnabled").asBoolean()).isTrue()
        assertThat(saved.json().path("quietFrom").asText()).isEqualTo("22:30")

        val badTime = call(HttpMethod.PUT, "/api/v1/me/notification-preferences", owner, mapOf("quietFrom" to "25:00"))
        assertThat(badTime.statusCode.value()).isEqualTo(400)
        val empty = call(HttpMethod.PUT, "/api/v1/me/notification-preferences", owner, mapOf("quietFrom" to "06:30"))
        assertThat(empty.errorCode()).isEqualTo("quiet_hours_empty")

        assertThat(get("/api/v1/me/notification-preferences", signIn()).json().path("smsEnabled").asBoolean())
            .describedAs("one person's choice is theirs").isTrue()
        assertThat(
            db.queryForObject(
                "select count(*) from activity_log where action = 'notifications.preferences_changed' and actor_user_id = ?",
                Int::class.java, userId,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `a channel switched off is skipped and says so, but an emergency notice still comes`() {
        call(HttpMethod.PUT, "/api/v1/me/notification-preferences", owner, mapOf("smsEnabled" to false))
        val reminder = tell()
        val emergency = tell(template = "emergency.requested")
        outbox.drain()

        assertThat(row(reminder, "sms")).extracting("status", "failure").containsExactly("skipped", "turned_off")
        assertThat(row(reminder, "email").status).isEqualTo("sent")
        assertThat(row(emergency, "sms").status).isEqualTo("sent")
        val mine = get("/api/v1/me/messages", owner).json()
            .first { it.path("channel").asText() == "sms" && it.path("status").asText() == "skipped" }
        assertThat(mine.path("failureMessage").asText()).contains("turned this off")
    }

    // --- when --------------------------------------------------------------------

    @Test
    fun `in quiet hours a message waits for the morning, and an emergency notice does not`() {
        pacing.paceSandboxChannels = true
        at(LocalTime.of(23, 0))
        val reminder = tell()
        val emergency = tell(template = "emergency.requested")
        outbox.drain()

        val waiting = row(reminder, "sms")
        assertThat(waiting.status).isEqualTo("queued")
        assertThat(waiting.deferredFor).isEqualTo("quiet_hours")
        assertThat(waiting.notBefore).isEqualTo(LocalDate.now(india).plusDays(1).atTime(8, 0).atZone(india).toInstant())
        assertThat(row(emergency, "sms").status).isEqualTo("sent")

        at(LocalTime.of(8, 1), plusDays = 1)
        outbox.drain()
        assertThat(row(reminder, "sms").status).isEqualTo("sent")
    }

    @Test
    fun `a person's own quiet hours are the ones kept`() {
        pacing.paceSandboxChannels = true
        call(HttpMethod.PUT, "/api/v1/me/notification-preferences", owner, mapOf("quietFrom" to "13:00", "quietUntil" to "15:00"))
        at(LocalTime.of(14, 0))
        val key = tell()
        outbox.drain()
        assertThat(row(key, "email").deferredFor).isEqualTo("quiet_hours")
        at(LocalTime.of(23, 0))
        outbox.drain()
        assertThat(row(key, "email").status).describedAs("23:00 is not quiet for this person").isEqualTo("sent")
    }

    @Test
    fun `one non-essential message a day, so a second waits for tomorrow on every channel together`() {
        pacing.paceSandboxChannels = true
        at(LocalTime.of(10, 0))
        val first = tell()
        outbox.drain()
        listOf("sms", "email", "push").forEach { assertThat(row(first, it).status).describedAs(it).isEqualTo("sent") }

        val second = tell()
        val essential = tell(template = "emergency.requested")
        outbox.drain()
        listOf("sms", "email", "push").forEach {
            assertThat(row(second, it)).describedAs(it).extracting("status", "deferredFor").containsExactly("queued", "daily_limit")
        }
        assertThat(row(essential, "sms").status).describedAs("essential messages do not count, and are not held").isEqualTo("sent")

        at(LocalTime.of(9, 0), plusDays = 1)
        outbox.drain()
        listOf("sms", "email", "push").forEach { assertThat(row(second, it).status).describedAs(it).isEqualTo("sent") }
    }

    @Test
    fun `two different messages queued together are still one a day`() {
        pacing.paceSandboxChannels = true
        at(LocalTime.of(11, 0))
        val a = tell()
        val b = tell()
        outbox.drain()
        val statuses = listOf(a, b).map { row(it, "sms").status }
        assertThat(statuses).containsExactlyInAnyOrder("sent", "queued")
    }

    @Test
    fun `a message waiting out quiet hours does not go once consent to messages is withdrawn`() {
        pacing.paceSandboxChannels = true
        at(LocalTime.of(22, 0))
        val reminder = tell()
        val emergency = tell(template = "emergency.requested")
        outbox.drain()
        assertThat(row(reminder, "sms").deferredFor).isEqualTo("quiet_hours")

        at(LocalTime.of(23, 0))
        val withdrawn = post("/api/v1/me/privacy/consents", owner, mapOf("purpose" to "messages", "given" to false))
        assertThat(withdrawn.statusCode.is2xxSuccessful).describedAs(withdrawn.body).isTrue()

        at(LocalTime.of(8, 1), plusDays = 1)
        outbox.drain()
        listOf("sms", "email", "push").forEach {
            assertThat(row(reminder, it)).describedAs(it).extracting("status", "failure")
                .containsExactly("skipped", "no_consent")
        }
        assertThat(row(reminder, "in_app").status).isEqualTo("sent")
        assertThat(row(emergency, "sms").status).describedAs("a safety notice is not under consent to messages").isEqualTo("sent")
    }

    @Test
    fun `a message waiting out quiet hours does not go to someone marked as passed away meanwhile`() {
        pacing.paceSandboxChannels = true
        at(LocalTime.of(22, 0))
        val reminder = tell()
        outbox.drain()
        assertThat(row(reminder, "email").deferredFor).isEqualTo("quiet_hours")

        db.update(
            """
            insert into member_memorials (household_id, member_id, user_id, marked_by, basis)
            select household_id, id, user_id, user_id, 'admin' from members
             where household_id = ?::uuid and user_id = ?::uuid
            """.trimIndent(),
            householdId, userId.toString(),
        )

        at(LocalTime.of(8, 1), plusDays = 1)
        outbox.drain()
        listOf("sms", "email", "push").forEach {
            assertThat(row(reminder, it)).describedAs(it).extracting("status", "failure")
                .containsExactly("skipped", "notifications_stopped")
        }
    }

    @Test
    fun `a message held back for a week is dropped from the channel and kept in the app`() {
        pacing.paceSandboxChannels = true
        at(LocalTime.of(10, 0))
        tell()
        outbox.drain()
        // Queued and aged while the worker is held, so the wake after queueing cannot decide it first.
        val stale = outbox.whilePaused {
            tell().also {
                db.update("update outbound_messages set created_at = now() - interval '8 days' where idempotency_key like ?", "$it:%")
            }
        }
        outbox.drain()
        assertThat(row(stale, "sms")).extracting("status", "failure").containsExactly("skipped", "daily_limit")
        assertThat(row(stale, "in_app").status).isEqualTo("sent")
    }
}
