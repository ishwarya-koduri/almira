package tech.bhrigu.almira.auth

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import tech.bhrigu.almira.common.PhoneNumber
import tech.bhrigu.almira.config.AlmiraProperties

/**
 * Delivery only. The code itself is generated, stored and checked by
 * [OtpService], so identity stays ours and swapping providers changes nothing
 * about how authentication works (docs/09 §9.3).
 */
interface OtpSender {
    fun send(phone: String, code: String)

    /**
     * Whether this sender can deliver a code at all, here and now.
     *
     * Checked by [OtpService] before a code is even generated, so an unavailable
     * sender produces an honest refusal rather than a challenge the person can
     * never complete — and there is no code in existence to leak.
     */
    val available: Boolean get() = true

    /**
     * True when the code may be echoed in the API response. [OtpService] also
     * requires the environment to be explicitly development before it does,
     * so no sender can turn this on for a real deployment by itself.
     */
    val exposesCodeForDevelopment: Boolean get() = false
}

/**
 * Writes the code to the log, which is the whole point of it, and therefore
 * works ONLY when development was explicitly chosen.
 *
 * It used to work everywhere. It was the only sender that existed, the default
 * in every environment including production, and it both logged each code and
 * — via [exposesCodeForDevelopment] — returned it in the API response. So a
 * production server handed any caller the code for any phone number: reproduced
 * on 2026-09-13 with ALMIRA_ENV=production, an anonymous request for someone
 * else's number came back with `"developmentCode":"036524"`. Sign in as anyone.
 *
 * Outside development it is now [available] = false. [OtpService] refuses the
 * request with a 503 before generating anything, so the code is never created,
 * never stored, never logged and never echoed. The honest consequence: with no
 * SMS sender implemented, a non-development deployment cannot sign anybody in.
 * That was already true of every real user; it is now also true of an attacker.
 */
@Component
@ConditionalOnProperty(name = ["almira.otp.provider"], havingValue = "log", matchIfMissing = true)
class LoggingOtpSender(props: AlmiraProperties) : OtpSender {
    private val log = LoggerFactory.getLogger(javaClass)
    private val development = props.isDevelopment

    override val available: Boolean = development

    override fun send(phone: String, code: String) {
        // Defensive: OtpService checks [available] first, so this should be
        // unreachable outside development. If it is ever reached, it refuses to
        // write the code rather than trusting the caller to have checked.
        check(development) { "LoggingOtpSender.send called outside development" }
        log.warn("=== DEV OTP for {} : {} ===", PhoneNumber.mask(phone), code)
    }

    override val exposesCodeForDevelopment: Boolean = development
}
