package tech.bhrigu.almira.provider

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tech.bhrigu.almira.auth.EmailOtpSender
import tech.bhrigu.almira.reminder.OutboundNotification
import tech.bhrigu.almira.support.ApiTestBase
import tech.bhrigu.almira.support.FakeSmtpServer
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Live email, end to end: the real application with `email: live`, sending
 * through [SmtpEmailSender] to an SMTP server on loopback (docs/13 §5).
 *
 * The only live adapter in the product, so this is the one place the promise
 * "a notification reaches a person" is checked against something that speaks a
 * real protocol: the address comes from the person's account, the words say
 * why it came, a live channel with no address records that rather than
 * pretending, and each way the relay says no becomes the right kind.
 */
@DisplayName("Live email over SMTP")
class LiveEmailDeliveryApiTest : ApiTestBase() {

    @Autowired private lateinit var outbox: NotificationOutbox
    @Autowired private lateinit var notifier: RecordingNotifier
    @Autowired private lateinit var channels: List<ChannelSender>
    @Autowired private lateinit var pacing: DeliveryPacing
    @Autowired private lateinit var emailOtp: EmailOtpSender

    private lateinit var owner: String
    private lateinit var householdId: String
    private lateinit var userId: UUID
    private lateinit var address: String

    @BeforeEach
    fun setUp() {
        outbox.drain()
        smtp.clear()
        val phone = uniquePhone()
        owner = signIn(phone)
        householdId = createHousehold(owner, "Koduri", "private", "Ishwarya").path("id").asText()
        userId = UUID.fromString(db.queryForObject("select id::text from users where phone = ?", String::class.java, phone))
        address = "ishwarya.${UUID.randomUUID().toString().take(8)}@example.test"
    }

    @AfterEach
    fun tearDown() {
        pacing.clock = Clock.systemUTC()
        outbox.drain()
    }

    private fun giveEmail() = db.update("update users set email = ? where id = ?::uuid", address, userId.toString())

    private fun tell(template: String = "reminder.maturity"): String {
        val key = "live-test:${UUID.randomUUID()}"
        notifier.deliver(
            OutboundNotification(
                userId = userId, householdId = UUID.fromString(householdId), reminderId = null,
                template = template, title = "SBI FD matures Thursday",
                body = "Due on Thursday, 17 September 2026.\n₹2,40,000\nTwo Lakh Forty Thousand Rupees",
                idempotencyKey = key,
            ),
        )
        return key
    }

    private fun email(key: String): Map<String, Any?> = db.queryForMap(
        "select status, failure, provider, attempts from outbound_messages where idempotency_key = ?", "$key:email",
    )

    @Test
    fun `the live adapter is the email channel, and it does not claim to de-duplicate`() {
        val email = channels.single { it.channel == "email" }
        assertThat(email).isInstanceOf(SmtpEmailSender::class.java)
        assertThat(email.mode).isEqualTo(ProviderMode.LIVE)
        assertThat(email.honoursIdempotencyKey).describedAs("SMTP delivers a repeat twice").isFalse()
        assertThat(emailOtp.available).describedAs("email sign-in can deliver once email is live").isTrue()
    }

    @Test
    fun `a reminder reaches the address on the account, in words that say why it came`() {
        giveEmail()
        // Mid-morning in the household's zone, so quiet hours do not hold it.
        val india = ZoneId.of("Asia/Kolkata")
        pacing.clock = Clock.fixed(LocalDate.now(india).atTime(10, 0).atZone(india).toInstant(), ZoneOffset.UTC)
        val key = tell()
        outbox.drain()

        assertThat(email(key)).containsEntry("status", "sent").containsEntry("provider", "smtp")
        val received = smtp.received.single()
        assertThat(received.to).containsExactly(address)
        assertThat(received.from).isEqualTo("reminders@almira.test")
        assertThat(received.headers).contains("Auto-Submitted: auto-generated")
        assertThat(received.headers).containsPattern("Message-ID: <[0-9a-f]{32}@notifications.almira>")
        val decoded = jakarta.mail.internet.MimeMessage(
            jakarta.mail.Session.getInstance(java.util.Properties()),
            received.data.byteInputStream(),
        )
        assertThat(decoded.subject).isEqualTo("SBI FD matures Thursday")
        val text = decoded.content.toString()
        assertThat(text).contains("₹2,40,000", "Two Lakh Forty Thousand Rupees", "You're getting this because", "at most one reminder a day")
    }

    @Test
    fun `a live channel with no address for the person records that, and nothing is sent`() {
        val key = tell()
        outbox.drain()
        assertThat(email(key)).containsEntry("status", "skipped").containsEntry("failure", "no_recipient")
        assertThat(smtp.received).isEmpty()
    }

    @Test
    fun `a quiet hour holds a live email until morning`() {
        giveEmail()
        val india = ZoneId.of("Asia/Kolkata")
        pacing.clock = Clock.fixed(LocalDate.now(india).atTime(22, 0).atZone(india).toInstant(), ZoneOffset.UTC)
        val key = tell()
        outbox.drain()
        assertThat(email(key)).containsEntry("status", "queued")
        assertThat(smtp.received).isEmpty()
    }

    @Test
    fun `a refused recipient is rejected, not retried, and the person is told to check their details`() {
        giveEmail()
        smtp.refuse(address)
        val key = tell(template = "emergency.named")
        outbox.drain()
        assertThat(email(key)).containsEntry("status", "failed").containsEntry("failure", "rejected").containsEntry("attempts", 1)
    }

    @Test
    fun `a relay that refuses our from-address is ours to fix, not the person's`() {
        giveEmail()
        smtp.refuseSender = true
        val key = tell(template = "emergency.named")
        outbox.drain()
        assertThat(email(key)).containsEntry("status", "failed").containsEntry("failure", "insufficient_balance")
    }

    @Test
    fun `a relay that cannot be reached is unavailable`() {
        val sender = SmtpEmailSender(
            tech.bhrigu.almira.config.AlmiraProperties(
                db = tech.bhrigu.almira.config.AlmiraProperties.Db("jdbc:postgresql://x/y", "u", "p", "u2", "p2"),
                jwt = tech.bhrigu.almira.config.AlmiraProperties.Jwt("test-only-secret-that-is-long-enough-for-hmac256-signing"),
                otp = tech.bhrigu.almira.config.AlmiraProperties.Otp(),
                providers = tech.bhrigu.almira.config.AlmiraProperties.Providers(
                    email = tech.bhrigu.almira.config.AlmiraProperties.Provider(
                        mode = "live",
                        timeout = java.time.Duration.ofSeconds(2),
                        smtp = tech.bhrigu.almira.config.AlmiraProperties.Smtp(
                            host = "127.0.0.1", port = closedPort(), from = "reminders@almira.test", startTls = false,
                        ),
                    ),
                ),
            ),
        )
        val failure = runCatching {
            sender.send(
                OutboundNotification(userId, null, null, "reminder.maturity", "t", "b"),
                address, "k",
            )
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(ProviderFailure::class.java)
        assertThat((failure as ProviderFailure).kind).isEqualTo(FailureKind.UNAVAILABLE)
    }

    private fun closedPort(): Int = java.net.ServerSocket(0).use { it.localPort }

    companion object {
        private val smtp = FakeSmtpServer()

        @JvmStatic
        @DynamicPropertySource
        fun liveEmail(registry: DynamicPropertyRegistry) {
            registry.add("almira.providers.email.mode") { "live" }
            registry.add("almira.providers.email.smtp.host") { "127.0.0.1" }
            registry.add("almira.providers.email.smtp.port") { smtp.port.toString() }
            registry.add("almira.providers.email.smtp.from") { "Almira <reminders@almira.test>" }
            // The fake speaks no TLS. Every real deployment keeps the default, true.
            registry.add("almira.providers.email.smtp.start-tls") { "false" }
            registry.add("almira.providers.email.timeout") { "3s" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = smtp.close()
    }
}
