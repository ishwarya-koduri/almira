package tech.bhrigu.almira.auth

import org.springframework.stereotype.Component
import tech.bhrigu.almira.config.AlmiraProperties
import tech.bhrigu.almira.provider.ChannelSender
import tech.bhrigu.almira.provider.ProviderMode
import tech.bhrigu.almira.reminder.OutboundNotification
import java.util.UUID

/**
 * Delivery of a one-time code by email. The same contract as [OtpSender] — the
 * code is generated, stored and checked by [OtpService], never here — with an
 * address where [OtpSender] has a number.
 *
 * A separate interface rather than a channel flag on [OtpSender], so a phone
 * sender can never be handed an email address or the other way round, and so
 * swapping one never rewires the other.
 */
interface EmailOtpSender {
    /** Throws [tech.bhrigu.almira.provider.ProviderFailure]; always called through ProviderCalls. */
    fun send(email: String, code: String)

    /** Checked before a code is generated. See [OtpSender.available]. */
    val available: Boolean

    /** See [OtpSender.exposesCodeForDevelopment]; [OtpService] also requires development. */
    val exposesCodeForDevelopment: Boolean get() = false

    companion object {
        /** For a service built without email at all, as unit tests of the phone flow are. */
        val NONE = object : EmailOtpSender {
            override fun send(email: String, code: String) =
                error("no email sender configured")
            override val available = false
        }
    }
}

/**
 * Sends the code through the email [ChannelSender] — whichever one the
 * `almira.providers.email.mode` switch selected — so email sign-in goes live on
 * exactly the day email does, with no second integration.
 *
 * **When it can deliver.** A live email adapter can. The sandbox can only in
 * explicitly chosen development, the same rule as [LoggingOtpSender]: the
 * sandbox sends nothing, so anywhere else a "sent" code would be a code nobody
 * can receive — and the request is refused with 503 `otp_unavailable` before a
 * code exists. With `mode: disabled` there is no email sender at all, and a
 * server that offers email sign-in that way refuses to start (`SignInChannels`);
 * [available] is false for it anyway, so a service built without that check
 * still cannot claim to deliver.
 *
 * **Where the code goes.** Into the message body only. The subject a sandbox
 * logs, and the title `outbound_messages` would record, carry no code. This does
 * not go through RecordingNotifier at all, so no row is written: a sign-in code
 * is not a notification, often has no user yet to belong to, and a table of
 * "codes we sent" is a table worth stealing.
 *
 * **Who it is for.** The address goes in `recipientHint`, the one place
 * [ChannelSender] has for it. For a one-time code the hint is the complete
 * address; notifications still pass null (docs/known-issues.md 13).
 */
@Component
class ChannelEmailOtpSender(
    props: AlmiraProperties,
    channels: List<ChannelSender>,
) : EmailOtpSender {

    private val development = props.isDevelopment
    private val email: ChannelSender? = channels.firstOrNull { it.channel == "email" }

    override val available: Boolean = when (email?.mode) {
        ProviderMode.LIVE -> true
        ProviderMode.SANDBOX -> development
        // Disabled leaves no email sender, so null is what arrives; DISABLED and
        // OFF are listed so a sender that ever reports them cannot deliver.
        ProviderMode.DISABLED, ProviderMode.OFF, null -> false
    }

    /** Only the sandbox, and only in development: nothing real would arrive. */
    override val exposesCodeForDevelopment: Boolean = development && email?.mode == ProviderMode.SANDBOX

    override fun send(email: String, code: String) {
        val sender = checkNotNull(this.email) { "ChannelEmailOtpSender.send called with no email channel" }
        // Defensive, as in LoggingOtpSender: OtpService checks [available] first.
        check(available) { "ChannelEmailOtpSender.send called when it cannot deliver" }
        sender.send(
            OutboundNotification(
                // Nobody, on purpose: at sign-in there may be no user yet, and
                // this message is never recorded against one.
                userId = NO_USER,
                householdId = null,
                reminderId = null,
                template = TEMPLATE,
                title = SUBJECT,
                body = "Your Almira code is $code. It works once, for a few minutes. " +
                    "If you didn't ask for it, you can ignore this email.",
            ),
            email,
            // One attempt per code (OtpService), so the key only has to cover
            // a provider's own internal retry of this one call.
            "$TEMPLATE:${UUID.randomUUID()}",
        )
    }

    companion object {
        const val TEMPLATE = "otp_email"
        /** No code in it. Subjects are logged by the sandbox and shown in inbox lists. */
        const val SUBJECT = "Your Almira sign-in code"
        val NO_USER: UUID = UUID(0, 0)
    }
}
