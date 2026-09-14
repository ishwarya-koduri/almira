package tech.bhrigu.almira.auth

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.common.EmailAddress
import tech.bhrigu.almira.config.AlmiraProperties

/** The two ways a one-time code can reach somebody. */
enum class OtpChannel(val key: String, val provider: String) {
    PHONE("phone", "sms"),
    EMAIL("email", "email"),
}

/**
 * Which sign-in channels this server offers, and who may use email.
 *
 * Read once at startup and checked there, the same way ProviderModeCheck treats
 * provider modes: a value that is not understood refuses to start with a
 * sentence, rather than silently falling back to a channel nobody chose.
 *
 *  - `almira.auth.sign-in-channels` must be a non-empty subset of phone, email.
 *    `emial` would otherwise leave a server with no sign-in and no explanation.
 *  - Every allowlist entry must be an address. A typo in one tester's address
 *    would lock that tester out without a trace, because a non-listed address
 *    is deliberately indistinguishable from a listed one.
 *  - Email enabled with an empty allowlist refuses: nobody could sign in. The
 *    way to end the alpha is to take email out of the channels, which
 *    AlphaAllowlistAccess treats as removing every address.
 *  - Email enabled with `almira.providers.email.mode=disabled` refuses. Email
 *    codes go out through the email provider (ChannelEmailOtpSender), so the
 *    two settings contradict each other: one offers email sign-in, the other
 *    says there is no email on this server. A disabled provider is a normal
 *    state and starts; offering sign-in through it is a choice nobody can
 *    have meant, and the alternative — quietly dropping email from the
 *    channels — would, through AlphaAllowlistAccess, sign every email-only
 *    tester out at startup because of a provider switch (docs/13, "The switch").
 *    Phone has no such rule: phone codes go through `almira.otp.provider`,
 *    not the `sms` provider (known-issues 12).
 *  - Email enabled with a `live` email provider and no decoy sink refuses, and
 *    a sink that is not an address or is on the allowlist refuses. Decoys send
 *    to the sink so that an unlisted address's answer follows the provider as
 *    it is at that moment (docs/13 §5); with no sink, a decoy could only guess.
 *
 * The startup line says how many addresses are listed and never which.
 */
@Component
class SignInChannels(props: AlmiraProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val checked: Checked = check(props)
    val enabled: List<OtpChannel> = checked.enabled
    private val allowlist: Set<String> = checked.allowlist

    init {
        // The refusals are in [check], which StartupSettingsCheck also runs
        // before the application context exists, so a server configured wrongly
        // is refused before its database is migrated (docs/known-issues.md,
        // "A guard runs before the action it guards").
        log.info(
            "Sign-in channels: {}{}",
            enabled.joinToString(",") { it.key },
            if (OtpChannel.EMAIL in enabled) "  (email allowlist: ${allowlist.size} address(es))" else "",
        )
    }

    /** What [check] accepted. */
    class Checked(val enabled: List<OtpChannel>, val allowlist: Set<String>)

    companion object {
        /** Every refusal this class makes at startup, with no side effects. */
        fun check(props: AlmiraProperties): Checked {
            val enabled = parseChannels(props.auth.signInChannels)
            val allowlist = parseAllowlist(props.auth.emailAllowlist)
            require(OtpChannel.EMAIL !in enabled || allowlist.isNotEmpty()) {
                "Email sign-in is enabled but almira.auth.email-allowlist (ALMIRA_ALPHA_EMAIL_ALLOWLIST) " +
                    "is empty, so nobody could sign in by email. List the testers' addresses, or " +
                    "take email out of almira.auth.sign-in-channels to end the email alpha. Taking email " +
                    "out signs every email-only account out when this server starts (docs/13 §5)."
            }
            require(OtpChannel.EMAIL !in enabled || props.providers.email.mode.trim().lowercase() != "disabled") {
                "Email sign-in is enabled in almira.auth.sign-in-channels (ALMIRA_SIGN_IN_CHANNELS), but " +
                    "almira.providers.email.mode (ALMIRA_PROVIDER_EMAIL_MODE) is 'disabled', so no sign-in " +
                    "code could ever be emailed. Set the email provider to sandbox or live, or take email " +
                    "out of almira.auth.sign-in-channels. Taking email out signs every email-only account " +
                    "out when this server starts (docs/13 §5)."
            }

            val sink = props.auth.emailDecoySink.trim()
            require(OtpChannel.EMAIL !in enabled || sink.isNotEmpty() ||
                props.providers.email.mode.trim().lowercase() != "live") {
                "Email sign-in is enabled with a live email provider, but almira.auth.email-decoy-sink " +
                    "(ALMIRA_ALPHA_EMAIL_DECOY_SINK) is empty. An address off the allowlist has its email " +
                    "sent there instead, so that its answer fails or succeeds with the provider as a listed " +
                    "address's does; without it the answer would tell anyone who is in the alpha. Set it to " +
                    "an address that accepts and discards mail, such as the provider's mailbox simulator (docs/13 §5)."
            }
            if (OtpChannel.EMAIL in enabled && sink.isNotEmpty()) {
                val canonical = EmailAddress.canonicalOrNull(sink)
                require(canonical != null) {
                    "almira.auth.email-decoy-sink (ALMIRA_ALPHA_EMAIL_DECOY_SINK) is not an email address."
                }
                require(canonical !in allowlist) {
                    "almira.auth.email-decoy-sink (ALMIRA_ALPHA_EMAIL_DECOY_SINK) is also on the email " +
                        "allowlist. The sink is sent an email for every unlisted address anyone asks a code " +
                        "for, so it must be a mailbox that discards them, not a tester's."
                }
            }
            return Checked(enabled, allowlist)
        }

        private fun parseChannels(configured: List<String>): List<OtpChannel> {
            val raw = configured.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            val unknown = raw.filter { name -> OtpChannel.entries.none { it.key == name } }
            require(unknown.isEmpty()) {
                "almira.auth.sign-in-channels has ${unknown.joinToString { "'$it'" }}, which is not " +
                    "phone or email. Refusing rather than guessing which was meant."
            }
            require(raw.isNotEmpty()) {
                "almira.auth.sign-in-channels is empty, so nobody could sign in. Set phone, email, or both."
            }
            return OtpChannel.entries.filter { channel -> channel.key in raw }
        }

        private fun parseAllowlist(configured: List<String>): Set<String> {
            val entries = configured.map { it.trim() }.filter { it.isNotEmpty() }
            val malformed = entries.withIndex().filter { EmailAddress.canonicalOrNull(it.value) == null }
            require(malformed.isEmpty()) {
                // Positions, not the entries: this message goes to a log.
                "almira.auth.email-allowlist entr${if (malformed.size == 1) "y" else "ies"} " +
                    "${malformed.joinToString { "#${it.index + 1}" }} " +
                    "${if (malformed.size == 1) "is not an email address" else "are not email addresses"}. " +
                    "A mistyped address would lock that tester out without any sign of why."
            }
            return entries.mapNotNull(EmailAddress::canonicalOrNull).toSet()
        }
    }

    fun isEnabled(channel: OtpChannel): Boolean = channel in enabled

    /**
     * Refuses a channel this server does not offer, before anything else
     * happens — before the body's address is even looked at, so the refusal
     * says nothing about anybody.
     */
    fun requireEnabled(channel: OtpChannel) {
        if (isEnabled(channel)) return
        throw ApiException(
            HttpStatus.FORBIDDEN,
            "sign_in_channel_disabled",
            when (channel) {
                OtpChannel.PHONE -> "This server doesn't offer sign-in by phone. Sign in with your email address."
                OtpChannel.EMAIL -> "This server doesn't offer sign-in by email. Sign in with your phone number."
            },
            mapOf("channel" to channel.key, "enabledChannels" to enabled.map { it.key }),
        )
    }

    /** [canonical] must already be normalised by [EmailAddress]. */
    fun isAllowed(canonical: String): Boolean = canonical in allowlist
}
