package tech.bhrigu.almira.provider

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import tech.bhrigu.almira.reminder.Notifier
import tech.bhrigu.almira.reminder.OutboundNotification
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
     * Whether the PROVIDER behind this adapter guarantees that two sends with the
     * same idempotency key deliver once. Deliberately no default: every adapter,
     * and every live adapter above all, has to say.
     *
     * It decides the notification outbox's promise for this channel
     * ([NotificationOutbox], docs/13 "Interactive and background"):
     *  - `true` — **at-least-once to the provider, once to the person**: a
     *    timeout is retried, and a send cut off by a crash is sent again, with
     *    the same key, and the provider drops the repeat;
     *  - `false` — **at-most-once**: a timeout is not retried, and a send that
     *    was started and never recorded is marked unconfirmed, never re-sent. A
     *    message can be lost; it cannot arrive twice.
     */
    val honoursIdempotencyKey: Boolean

    /**
     * Returns the provider's own name for the audit row.
     *
     * Throws [ProviderFailure] to say how it failed — timed out, rejected, out of
     * balance — which decides whether it is retried and what is recorded. It is
     * always called through [ProviderCalls], never directly.
     *
     * `recipientHint` is who it is for, when the caller knows. A one-time code
     * by email passes the complete address (auth/EmailOtpSender.kt); notifications
     * still pass null (docs/known-issues.md 13). Never log it whole.
     *
     * `idempotencyKey` is the same for every attempt at one logical message on
     * this channel, across retries, restarts and workers. A live adapter MUST
     * pass it to its provider (an `Idempotency-Key` header, a client reference
     * the provider de-duplicates on) whenever [honoursIdempotencyKey] is true —
     * that is what makes the claim true. It carries no personal data and may be
     * logged.
     */
    fun send(notification: OutboundNotification, recipientHint: String?, idempotencyKey: String): String
}

/**
 * What a sandbox provider did with each key: how many times it actually
 * "delivered", as opposed to answering a repeat. A provider that honours keys
 * delivers once per key; one that does not delivers every time it is called.
 * Test-visible, so "never twice" is counted at the provider, not inferred.
 */
class SandboxDeliveries(private val honoursKeys: Boolean) {
    private val delivered = ConcurrentHashMap<String, AtomicInteger>()

    /** Runs [send] unless this key was already delivered and keys are honoured. */
    fun deliver(key: String, send: () -> Unit): Boolean {
        if (honoursKeys && (delivered[key]?.get() ?: 0) > 0) return false
        send()
        if (delivered.size > MAX_REMEMBERED) delivered.clear()
        delivered.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
        return true
    }

    fun times(key: String): Int = delivered[key]?.get() ?: 0

    private companion object {
        /** A sandbox is not a database; forgetting old keys bounds its memory. */
        const val MAX_REMEMBERED = 10_000
    }
}

/** Honours keys, as the live SMS provider must (docs/providers/sms.md). */
@Component
@ConditionalOnProperty(name = ["almira.providers.sms.mode"], havingValue = "sandbox", matchIfMissing = true)
class SandboxSmsSender(private val faults: SandboxFaults) : ChannelSender {
    private val log = LoggerFactory.getLogger(javaClass)
    override val channel = "sms"
    override val mode = ProviderMode.SANDBOX
    override val honoursIdempotencyKey = true
    val deliveries = SandboxDeliveries(honoursIdempotencyKey)

    override fun send(notification: OutboundNotification, recipientHint: String?, idempotencyKey: String): String {
        val fresh = deliveries.deliver(idempotencyKey) {
            faults.apply(channel)
            // No body, ever: an SMS body carries the amount and the institution, and
            // logs are the least protected thing here (docs/05 §5).
            log.info("sandbox SMS: template={} to=…{}", notification.template, recipientHint ?: "?")
        }
        if (!fresh) log.info("sandbox SMS: key already delivered, not sent again: {}", idempotencyKey)
        return "sandbox-sms"
    }
}

/** Honours keys: the email providers worth using take an idempotency key. */
@Component
@ConditionalOnProperty(name = ["almira.providers.email.mode"], havingValue = "sandbox", matchIfMissing = true)
class SandboxEmailSender(private val faults: SandboxFaults) : ChannelSender {
    private val log = LoggerFactory.getLogger(javaClass)
    override val channel = "email"
    override val mode = ProviderMode.SANDBOX
    override val honoursIdempotencyKey = true
    val deliveries = SandboxDeliveries(honoursIdempotencyKey)

    override fun send(notification: OutboundNotification, recipientHint: String?, idempotencyKey: String): String {
        val fresh = deliveries.deliver(idempotencyKey) {
            faults.apply(channel)
            log.info("sandbox email: template={} subject={}", notification.template, notification.title)
        }
        if (!fresh) log.info("sandbox email: key already delivered, not sent again: {}", idempotencyKey)
        return "sandbox-email"
    }
}

/**
 * Does NOT honour keys, like FCM and APNs, which have no send-side
 * de-duplication (a collapse id replaces a notification on screen; it does not
 * stop a second one arriving). So push is at-most-once.
 */
@Component
@ConditionalOnProperty(name = ["almira.providers.push.mode"], havingValue = "sandbox", matchIfMissing = true)
class SandboxPushSender(private val faults: SandboxFaults) : ChannelSender {
    private val log = LoggerFactory.getLogger(javaClass)
    override val channel = "push"
    override val mode = ProviderMode.SANDBOX
    override val honoursIdempotencyKey = false
    val deliveries = SandboxDeliveries(honoursIdempotencyKey)

    override fun send(notification: OutboundNotification, recipientHint: String?, idempotencyKey: String): String {
        deliveries.deliver(idempotencyKey) {
            faults.apply(channel)
            log.info("sandbox push: template={}", notification.template)
        }
        return "sandbox-push"
    }
}

/**
 * The notifier that makes "it was logged" checkable — and, since V32, the
 * front of the notification outbox.
 *
 * Notifications stay a stand-in until real provider accounts exist, but a log
 * line is unverifiable and disappears on rotation. Recording every outbound
 * message means the in-app list works today, the tests can assert that the
 * person who should have been told was told, and turning a channel on later
 * changes where a row goes rather than whether it exists.
 *
 * **Nothing here waits for a provider.** Nobody is sitting in front of a
 * notification, so it is background work (docs/13 "Interactive and
 * background"). [deliver] writes the in-app row as `sent` and one `queued` row
 * per configured channel, each with an idempotency key, and returns; the
 * [NotificationOutbox] worker sends them with the provider's retry policy and
 * records how each went. Called inside a request's transaction, the rows commit
 * or roll back with the thing that caused them, and the worker is woken after
 * the commit.
 *
 * It never records the body in `outbound_messages`. The body waits in
 * `outbound_message_bodies`, which the runtime role cannot read, and is deleted
 * once the message is sent or has failed.
 */
@Component
class RecordingNotifier(
    private val jdbc: NamedParameterJdbcTemplate,
    private val channels: List<ChannelSender>,
    private val outbox: NotificationOutbox,
) : Notifier {

    private val log = LoggerFactory.getLogger(javaClass)

    override val channel = "in_app"

    override fun deliver(notification: OutboundNotification) {
        val logical = notification.idempotencyKey ?: "${notification.template}:${UUID.randomUUID()}"
        record(notification, keyFor(logical, channel))
        var queued = 0
        channels.forEach { sender ->
            runCatching {
                jdbc.query(
                    """
                    select app.enqueue_outbound_message(:hid, :uid, :channel, :template, :title, :body, :key)
                    """.trimIndent(),
                    MapSqlParameterSource()
                        .addValue("hid", notification.householdId)
                        .addValue("uid", notification.userId)
                        .addValue("channel", sender.channel)
                        .addValue("template", notification.template)
                        .addValue("title", notification.title)
                        .addValue("body", notification.body)
                        .addValue("key", keyFor(logical, sender.channel)),
                ) { _, _ -> }
                queued++
            }.onFailure {
                log.warn("could not queue an outbound message on {}: {}", sender.channel, it.javaClass.simpleName)
            }
        }
        if (queued > 0) wakeAfterCommit()
    }

    /**
     * The in-app row: no provider, so it is sent the moment it is written. It has the
     * logical message's key (V35), so the same message asked for twice is listed once.
     */
    private fun record(notification: OutboundNotification, key: String) {
        runCatching {
            // query, not update: the statement is a SELECT, and executeUpdate on
            // it wrote the row and then threw "a result was returned when none
            // was expected" — a WARN for every notification that had in fact
            // been recorded.
            jdbc.query(
                """
                select app.record_in_app_message(:hid, :uid, :template, :title, :key)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("hid", notification.householdId)
                    .addValue("uid", notification.userId)
                    .addValue("template", notification.template)
                    .addValue("title", notification.title)
                    .addValue("key", key),
            ) { _, _ -> }
        }.onFailure {
            // Never fails the thing that triggered it. A reminder that fired is
            // worth more than a tidy record of having mentioned it.
            log.warn("could not record an outbound message: {}", it.javaClass.simpleName)
        }
    }

    /** Woken after commit: before it, the worker cannot see the rows. The poll catches anything missed. */
    private fun wakeAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = outbox.wake()
                },
            )
        } else {
            outbox.wake()
        }
    }

    companion object {
        /** A failure the adapter did not classify: a bug, not a provider outcome. */
        const val UNCLASSIFIED = "error"

        /** One logical message on one channel. */
        fun keyFor(logical: String, channel: String) = "$logical:$channel"
    }
}
