package tech.bhrigu.almira.auth

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tech.bhrigu.almira.reminder.Notifier
import tech.bhrigu.almira.reminder.OutboundNotification
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Telling a person that something happened to how their account is opened.
 *
 * A new sign-in, a changed number, a second factor added or taken away: each
 * is the moment an account is taken over, if it is being taken over, and the
 * owner's other phone is where they will notice. So each goes to the in-app
 * list every signed-in device reads, and through the notification outbox to
 * whatever channels are configured (docs/13). Nothing here waits for a
 * provider, and nothing here can fail the sign-in it describes.
 *
 * Titles are stored and listed; they carry a device description and never an
 * address, a number or a code.
 */
@Component
class AccountNotices(private val notifiers: List<Notifier>) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun newSignIn(userId: UUID, sessionId: UUID, device: String, at: Instant = Instant.now()) = send(
        userId,
        template = "auth.new_sign_in",
        title = "New sign-in on $device",
        body = "Your Almira account was opened on $device at ${ist(at)}. If this was you, there is " +
            "nothing to do. If it wasn't, open How you sign in, sign that device out, and add a " +
            "second way to sign in.",
        key = "auth.new_sign_in:$sessionId",
    )

    fun changed(userId: UUID, change: Change, at: Instant = Instant.now()) = send(
        userId,
        template = change.template,
        title = change.title,
        body = "${change.title} at ${ist(at)}. If this wasn't you, sign in and open How you sign in straight away.",
        key = null,
    )

    enum class Change(val template: String, val title: String) {
        PHONE_CHANGED("auth.phone_changed", "The phone number on your account was changed"),
        AUTHENTICATOR_ADDED("auth.authenticator_added", "An authenticator app was added to your account"),
        AUTHENTICATOR_REMOVED("auth.authenticator_removed", "The authenticator app was removed from your account"),
        PASSKEY_ADDED("auth.passkey_added", "A passkey was added to your account"),
        PASSKEY_REMOVED("auth.passkey_removed", "A passkey was removed from your account"),
        RECOVERY_CODES_REPLACED("auth.recovery_codes_replaced", "New recovery codes were made for your account"),
        RECOVERY_CODE_USED("auth.recovery_code_used", "A recovery code was used on your account"),
    }

    private fun send(userId: UUID, template: String, title: String, body: String, key: String?) {
        val notification = OutboundNotification(
            userId = userId, householdId = null, reminderId = null,
            template = template, title = title, body = body, idempotencyKey = key,
        )
        notifiers.forEach {
            runCatching { it.deliver(notification) }
                .onFailure { e -> log.warn("could not queue account notice {}: {}", template, e.javaClass.simpleName) }
        }
    }

    private fun ist(at: Instant): String = TIME.format(at.atZone(INDIA))

    companion object {
        private val INDIA: ZoneId = ZoneId.of("Asia/Kolkata")
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, h:mm a 'IST'", Locale.ENGLISH)

        /**
         * A plain description of a device: the name the app sent, or what the
         * browser says it is. Never the raw user agent, which is long, and
         * never a place: that would need a location lookup this server does
         * not make.
         */
        fun describe(deviceName: String?, userAgent: String?): String {
            deviceName?.trim()?.filter { !it.isISOControl() }?.take(60)?.takeIf { it.isNotEmpty() }?.let { return it }
            val ua = userAgent.orEmpty()
            val platform = when {
                "iPhone" in ua -> "an iPhone"
                "iPad" in ua -> "an iPad"
                "Android" in ua -> "an Android phone"
                "Windows" in ua -> "a Windows computer"
                "Macintosh" in ua || "Mac OS" in ua -> "a Mac"
                "Linux" in ua -> "a Linux computer"
                else -> null
            }
            val browser = when {
                "Edg/" in ua -> "Edge"
                "Firefox/" in ua -> "Firefox"
                "Chrome/" in ua -> "Chrome"
                "Safari/" in ua -> "Safari"
                else -> null
            }
            return when {
                platform != null && browser != null -> "$browser on ${platform.substringAfter(' ')}"
                platform != null -> platform
                browser != null -> browser
                else -> "a new device"
            }
        }
    }
}
