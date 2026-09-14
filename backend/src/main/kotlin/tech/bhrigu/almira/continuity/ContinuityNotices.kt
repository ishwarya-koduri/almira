package tech.bhrigu.almira.continuity

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tech.bhrigu.almira.reminder.Notifier
import tech.bhrigu.almira.reminder.OutboundNotification
import java.util.UUID

/**
 * What the continuity signals say, and how they are handed over (docs/27 §5).
 *
 * Everything goes through the notification outbox (docs/13): the in-app row,
 * and a queued row per configured channel, each with the logical key so a sweep
 * run twice says it once. Which channels exist is the delivery settings'
 * business — with none configured, only the in-app row is written. An in-app
 * row keeps the title and never the body, so a link lives only in the queued
 * body, which is deleted once sent.
 *
 * The words are calm on purpose. "Are you there?" to someone who is merely busy
 * should read like a friend's text, not an alarm.
 */
@Component
class ContinuityNotices(private val notifiers: List<Notifier>) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun send(userId: UUID, householdId: UUID, template: String, title: String, body: String, key: String) {
        val notification = OutboundNotification(
            userId = userId, householdId = householdId, reminderId = null,
            template = template, title = title, body = body, idempotencyKey = key,
        )
        notifiers.forEach { notifier ->
            runCatching { notifier.deliver(notification) }.onFailure {
                log.warn("continuity notice not delivered on {}: {}", notifier.channel, it.javaClass.simpleName)
            }
        }
    }

    companion object {
        /** Essential (docs/13): a question that precedes emergency access always comes. */
        const val CHECK_IN = "emergency.check_in"
        const val RAISED = "emergency.raised"
        const val REQUESTED = "emergency.requested"
        /** Not essential: paced by quiet hours and the daily limit like any other. */
        const val REACHABLE = "continuity.reachable"
        const val KEY_HOLDER_ASK = "continuity.key_holder_ask"
        const val KEY_HOLDER_ANSWER = "continuity.key_holder_answer"

        fun tap(url: String?, action: String): String =
            if (url != null) "$action: $url\nThe link works once, for thirty days, and shows nothing about anyone's records."
            else "Open Almira and tap \"$action\"."

        fun checkInTitle(second: Boolean) =
            if (second) "Just checking in again" else "A quick hello from Almira"

        fun checkInBody(second: Boolean, url: String?, contacts: List<String>, waitDays: Int?): String = buildString {
            append("We haven't seen you in Almira for a while. If all is well, one tap is enough.\n\n")
            append(tap(url, "I'm here"))
            append("\n\n")
            if (!second) {
                append("If we don't hear from you, we'll ask once more in thirty days. Nothing else happens.")
            } else {
                val who = contacts.joinToString(", ").ifEmpty { "the people you named" }
                val wait = waitDays?.let { "for at least $it days" } ?: "for your waiting period"
                append(
                    "If we still don't hear from you in thirty days, the request $who could make will begin. " +
                        "Nothing opens $wait, you'll be told, and any sign of you stops it.",
                )
            }
        }

        fun reachableTitle(ownerName: String) = "$ownerName still counts on you"

        fun reachableBody(ownerName: String, url: String?) =
            "$ownerName named you as the person who can ask to see what's marked for the family if they can't " +
                "be reached. Once a year we check that you still can be.\n\n" +
                tap(url, "Yes, I can still be reached") +
                "\n\nNothing else happens, and nothing is shared with you today."
    }
}
