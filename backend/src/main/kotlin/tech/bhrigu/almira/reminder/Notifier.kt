package tech.bhrigu.almira.reminder

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.UUID

data class OutboundNotification(
    val userId: UUID,
    val householdId: UUID?,
    val reminderId: UUID?,
    val template: String,
    val title: String,
    val body: String,
)

/**
 * Delivery. Notifications are recorded in the database whether or not a channel
 * is configured, so the in-app list works from day one and push or email is an
 * addition rather than a prerequisite (docs/09 §1).
 */
interface Notifier {
    fun deliver(notification: OutboundNotification)
    val channel: String
}

@Component
@ConditionalOnProperty(
    name = ["almira.notifications.provider"],
    havingValue = "log",
    matchIfMissing = true,
)
class LoggingNotifier : Notifier {
    private val log = LoggerFactory.getLogger(javaClass)
    override val channel = "in_app"

    override fun deliver(notification: OutboundNotification) {
        // No user data beyond the title: a notification body can contain an
        // amount or a policy name, and logs are the least protected place we
        // write anything (docs/05 §5).
        log.info(
            "notification queued: {} for user {}",
            notification.template, notification.userId,
        )
    }
}
