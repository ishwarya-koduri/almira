package tech.bhrigu.almira.provider

import jakarta.mail.MessagingException
import jakarta.mail.SendFailedException
import jakarta.mail.Session
import jakarta.mail.internet.AddressException
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.mail.MailAuthenticationException
import org.springframework.mail.MailException
import org.springframework.mail.MailSendException
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component
import tech.bhrigu.almira.config.AlmiraProperties
import tech.bhrigu.almira.reminder.OutboundNotification
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest

/**
 * Live email, through any SMTP relay (docs/13 §5).
 *
 * The first live adapter in the product, and deliberately the plainest: SMTP is
 * what every email provider worth using offers, so which provider is a
 * deployment's choice of host and credentials, not a vendor SDK in the build.
 * Only when `almira.providers.email.mode` is `live`; [ProviderModeCheck]
 * refuses to start without a host and a from-address, so this class never runs
 * half-configured, and nothing here opens a connection until a message is sent.
 *
 * **Idempotency: not honoured.** SMTP has no send-side de-duplication — a
 * message handed over twice arrives twice — so this adapter declares `false`,
 * and email through it is at-most-once (docs/13 "Idempotency keys"): a timeout
 * is recorded, not retried, and a send cut off by a crash is never re-sent. The
 * key still becomes the Message-ID, which lets a mail client thread a repeat
 * and lets the operator find a message in the relay's log without a recipient.
 *
 * **Classification**, into the four kinds everything else speaks:
 *  - the relay refused our credentials, or refused our from-address →
 *    `INSUFFICIENT_BALANCE`, the account-level kind: nothing will go until the
 *    operator fixes it, and the person must not be blamed;
 *  - the relay refused this recipient (a 5xx on RCPT, an address that does not
 *    parse) → `REJECTED`;
 *  - no answer within the provider's `timeout` → `TIMEOUT`;
 *  - could not connect, or a temporary 4xx → `UNAVAILABLE`.
 *
 * Never logged: the recipient, the subject or the body. A reminder's body holds
 * an amount and an institution.
 */
@Component
@ConditionalOnProperty(name = ["almira.providers.email.mode"], havingValue = "live")
class SmtpEmailSender(props: AlmiraProperties) : ChannelSender {

    private val log = LoggerFactory.getLogger(javaClass)
    private val config = props.providers.email
    private val smtp = config.smtp

    override val channel = "email"
    override val mode = ProviderMode.LIVE
    override val honoursIdempotencyKey = false

    private val from: InternetAddress = InternetAddress(smtp.from, true)

    private val mailer = JavaMailSenderImpl().apply {
        host = smtp.host
        port = smtp.port
        if (smtp.username.isNotBlank()) {
            username = smtp.username
            password = smtp.password
        }
        defaultEncoding = "UTF-8"
        val millis = config.timeout.toMillis().toString()
        javaMailProperties.putAll(
            mapOf(
                "mail.transport.protocol" to "smtp",
                "mail.smtp.auth" to smtp.username.isNotBlank().toString(),
                // Required, not merely enabled: a relay that does not offer TLS is refused
                // rather than sent a reminder's amount in plain text.
                "mail.smtp.starttls.enable" to smtp.startTls.toString(),
                "mail.smtp.starttls.required" to smtp.startTls.toString(),
                "mail.smtp.ssl.checkserveridentity" to "true",
                "mail.smtp.connectiontimeout" to millis,
                "mail.smtp.timeout" to millis,
                "mail.smtp.writetimeout" to millis,
                // Say which recipient failed rather than failing the whole message silently.
                "mail.smtp.reportsuccess" to "false",
            ),
        )
    }

    init {
        log.info("live email: SMTP relay configured (port {}, STARTTLS {})", smtp.port, smtp.startTls)
    }

    override fun send(notification: OutboundNotification, recipientHint: String?, idempotencyKey: String): String {
        val to = try {
            InternetAddress(recipientHint ?: throw ProviderFailure(FailureKind.REJECTED, "no address"), true)
        } catch (e: AddressException) {
            throw ProviderFailure(FailureKind.REJECTED, "the address does not parse")
        }
        val message = KeyedMimeMessage(mailer.session, idempotencyKey)
        MimeMessageHelper(message, false, "UTF-8").apply {
            setFrom(from)
            setTo(to)
            setSubject(headerSafe(notification.title))
            setText(notification.body, false)
        }
        message.setHeader("Auto-Submitted", "auto-generated")
        try {
            mailer.send(message)
        } catch (e: MailException) {
            throw classify(e)
        }
        return PROVIDER
    }

    /**
     * A subject is a header, and a reminder's title is typed by a household
     * member. A title of "FD\r\nBcc: someone@elsewhere" must never become a
     * header — or a recipient, since the envelope is read from the headers.
     * Angus Mail 2.0 folds a bare CR LF into a continuation line, so it does not
     * today (seen in LiveEmailDeliveryApiTest); every control character is made a
     * space anyway, so the guarantee does not rest on one library's folding.
     */
    internal fun headerSafe(value: String): String =
        value.replace(CONTROL, " ").trim().take(MAX_SUBJECT)

    internal fun classify(e: MailException): ProviderFailure {
        if (e is MailAuthenticationException) {
            return ProviderFailure(FailureKind.INSUFFICIENT_BALANCE, "the relay refused our credentials")
        }
        val causes = generateSequence<Throwable>(e) { it.cause }.toList() +
            ((e as? MailSendException)?.failedMessages?.values?.flatMap { generateSequence<Throwable>(it) { c -> c.cause }.toList() }
                ?: emptyList())
        val code = causes.firstNotNullOfOrNull { returnCode(it) }
        return when {
            causes.any { it is SocketTimeoutException } ->
                ProviderFailure(FailureKind.TIMEOUT, "no answer from the relay in time")
            causes.any { it is ConnectException || it is UnknownHostException } ->
                ProviderFailure(FailureKind.UNAVAILABLE, "could not reach the relay")
            causes.any { it.javaClass.simpleName == "SMTPSenderFailedException" } ->
                ProviderFailure(FailureKind.INSUFFICIENT_BALANCE, "the relay refused our from-address")
            causes.any { it is SendFailedException && !it.invalidAddresses.isNullOrEmpty() } &&
                (code == null || code >= 500) ->
                ProviderFailure(FailureKind.REJECTED, "the relay refused this recipient")
            code != null && code in 400..499 ->
                ProviderFailure(FailureKind.UNAVAILABLE, "the relay asked us to try later")
            code == 530 || code == 535 || code == 554 ->
                ProviderFailure(FailureKind.INSUFFICIENT_BALANCE, "the relay refused us")
            code != null && code >= 500 ->
                ProviderFailure(FailureKind.REJECTED, "the relay refused this message")
            causes.any { it is MessagingException } ->
                ProviderFailure(FailureKind.UNAVAILABLE, "the relay conversation failed")
            else -> ProviderFailure(FailureKind.UNAVAILABLE, "email could not be handed over")
        }
    }

    /** Angus Mail's SMTP exceptions carry the server's reply code; read it without depending on the class. */
    private fun returnCode(t: Throwable): Int? = runCatching {
        t.javaClass.methods.firstOrNull { it.name == "getReturnCode" && it.parameterCount == 0 }
            ?.invoke(t) as? Int
    }.getOrNull()?.takeIf { it > 0 }

    /**
     * A Message-ID made from the idempotency key, so a repeat carries the same one.
     * Hashed: the key names a reminder or a household, and headers are read by
     * every relay on the way.
     */
    private class KeyedMimeMessage(session: Session, private val key: String) : MimeMessage(session) {
        override fun updateMessageID() {
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
                .take(16).joinToString("") { "%02x".format(it) }
            setHeader("Message-ID", "<$digest@notifications.almira>")
        }
    }

    private companion object {
        const val PROVIDER = "smtp"
        val CONTROL = Regex("\\p{Cntrl}")
        /** Long subjects are folded by relays anyway; a bound keeps a pasted essay out of the header. */
        const val MAX_SUBJECT = 200
    }
}
