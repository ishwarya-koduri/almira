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
 *
 * The startup line says how many addresses are listed and never which.
 */
@Component
class SignInChannels(props: AlmiraProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    val enabled: List<OtpChannel> = parseChannels(props.auth.signInChannels)
    private val allowlist: Set<String> = parseAllowlist(props.auth.emailAllowlist)

    init {
        require(OtpChannel.EMAIL !in enabled || allowlist.isNotEmpty()) {
            "Email sign-in is enabled but almira.auth.email-allowlist (ALMIRA_ALPHA_EMAIL_ALLOWLIST) " +
                "is empty, so nobody could sign in by email. List the testers' addresses, or " +
                "take email out of almira.auth.sign-in-channels to end the email alpha. Taking email " +
                "out signs every email-only account out when this server starts (docs/13 §5)."
        }

        log.info(
            "Sign-in channels: {}{}",
            enabled.joinToString(",") { it.key },
            if (OtpChannel.EMAIL in enabled) "  (email allowlist: ${allowlist.size} address(es))" else "",
        )
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
