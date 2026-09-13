package tech.bhrigu.almira.provider

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import tech.bhrigu.almira.reminder.Notifier
import tech.bhrigu.almira.reminder.OutboundNotification
import java.util.UUID

/**
 * A message on its way out, on one channel.
 *
 * Three interfaces rather than one because the constraints differ: SMS in India
 * needs DLT-registered templates, email needs a verified sending domain, push
 * needs a device token this product does not yet collect. Each can go live on
 * its own.
 */
interface ChannelSender {
    val channel: String
    val mode: ProviderMode

    /**
     * Returns the provider's own name for the audit row.
     *
     * Throws [ProviderFailure] to say how it failed — timed out, rejected, out of
     * balance — which decides whether it is retried and what is recorded. It is
     * always called through [ProviderCalls], never directly.
     */
    fun send(notification: OutboundNotification, recipientHint: String?): String
}

@Component
@ConditionalOnProperty(name = ["almira.providers.sms.mode"], havingValue = "sandbox", matchIfMissing = true)
class SandboxSmsSender(private val faults: SandboxFaults) : ChannelSender {
    private val log = LoggerFactory.getLogger(javaClass)
    override val channel = "sms"
    override val mode = ProviderMode.SANDBOX

    override fun send(notification: OutboundNotification, recipientHint: String?): String {
        faults.apply(channel)
        // No body, ever: an SMS body carries the amount and the institution, and
        // logs are the least protected thing here (docs/05 §5).
        log.info("sandbox SMS: template={} to=…{}", notification.template, recipientHint ?: "?")
        return "sandbox-sms"
    }
}

@Component
@ConditionalOnProperty(name = ["almira.providers.email.mode"], havingValue = "sandbox", matchIfMissing = true)
class SandboxEmailSender(private val faults: SandboxFaults) : ChannelSender {
    private val log = LoggerFactory.getLogger(javaClass)
    override val channel = "email"
    override val mode = ProviderMode.SANDBOX

    override fun send(notification: OutboundNotification, recipientHint: String?): String {
        faults.apply(channel)
        log.info("sandbox email: template={} subject={}", notification.template, notification.title)
        return "sandbox-email"
    }
}

@Component
@ConditionalOnProperty(name = ["almira.providers.push.mode"], havingValue = "sandbox", matchIfMissing = true)
class SandboxPushSender(private val faults: SandboxFaults) : ChannelSender {
    private val log = LoggerFactory.getLogger(javaClass)
    override val channel = "push"
    override val mode = ProviderMode.SANDBOX

    override fun send(notification: OutboundNotification, recipientHint: String?): String {
        faults.apply(channel)
        log.info("sandbox push: template={}", notification.template)
        return "sandbox-push"
    }
}

/**
 * The notifier that makes "it was logged" checkable.
 *
 * Notifications stay a stand-in until real provider accounts exist — that was
 * and is the right call — but a log line is unverifiable and disappears on
 * rotation. Recording every outbound message means the in-app list works today,
 * the tests can assert that the person who should have been told was told, and
 * turning a channel on later changes where a row goes rather than whether it
 * exists.
 *
 * It never records the body. The title is enough to say what happened.
 *
 * Each channel's send goes through [ProviderCalls], so it is timed out and
 * retried by the same rules as every other provider, and the row says which way
 * it failed — `timeout`, `unavailable`, `rejected`, `insufficient_balance`, or
 * `error` for anything an adapter did not classify — and after how many
 * attempts. That is what lets the in-app list (`GET /me/messages`) tell a person
 * "the text did not go, but not because of anything you did".
 */
@Component
class RecordingNotifier(
    private val jdbc: NamedParameterJdbcTemplate,
    private val channels: List<ChannelSender>,
    private val calls: ProviderCalls,
) : Notifier {

    private val log = LoggerFactory.getLogger(javaClass)

    override val channel = "in_app"

    override fun deliver(notification: OutboundNotification) {
        record(notification, "in_app", "almira", "sent", null, 1)

        // Every configured channel is attempted, and each records its own
        // outcome: one provider failing must not silently swallow the rest.
        channels.forEach { sender ->
            try {
                val sent = calls.execute(sender.channel, "notify") { sender.send(notification, null) }
                record(notification, sender.channel, sent.value, "sent", null, sent.attempts)
            } catch (failure: ProviderCallFailed) {
                log.warn(
                    "delivery failed on {}: {} after {} attempt(s)",
                    sender.channel, failure.kind.code, failure.attempts,
                )
                record(notification, sender.channel, "unknown", "failed", failure.kind.code, failure.attempts)
            } catch (failure: Exception) {
                log.warn("delivery failed on {}: {}", sender.channel, failure.javaClass.simpleName)
                record(notification, sender.channel, "unknown", "failed", UNCLASSIFIED, 1)
            }
        }
    }

    private fun record(
        notification: OutboundNotification,
        channel: String,
        provider: String,
        status: String,
        failure: String?,
        attempts: Int,
    ) {
        runCatching {
            jdbc.update(
                """
                select app.record_outbound_message(:hid, :uid, :channel, :provider, :template,
                                                   :title, :hint, :status, :failure, :attempts)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("hid", notification.householdId)
                    .addValue("uid", notification.userId)
                    .addValue("channel", channel)
                    .addValue("provider", provider)
                    .addValue("template", notification.template)
                    .addValue("title", notification.title)
                    .addValue("hint", null as String?)
                    .addValue("status", status)
                    .addValue("failure", failure)
                    .addValue("attempts", attempts),
            )
        }.onFailure {
            // Never fails the thing that triggered it. A reminder that fired is
            // worth more than a tidy record of having mentioned it.
            log.warn("could not record an outbound message: {}", it.javaClass.simpleName)
        }
    }

    companion object {
        /** A failure the adapter did not classify: a bug, not a provider outcome. */
        const val UNCLASSIFIED = "error"
    }
}
