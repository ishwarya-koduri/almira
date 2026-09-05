package tech.bhrigu.almira.auth

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import tech.bhrigu.almira.common.PhoneNumber

/**
 * Delivery only. The code itself is generated, stored and checked by
 * [OtpService], so identity stays ours and swapping providers changes nothing
 * about how authentication works (docs/09 §9.3).
 */
interface OtpSender {
    fun send(phone: String, code: String)

    /**
     * True when the code may be echoed in the API response. Development only —
     * it is what lets the whole login flow be exercised without an SMS bill or
     * a DLT registration.
     */
    val exposesCodeForDevelopment: Boolean get() = false
}

@Component
@ConditionalOnProperty(name = ["almira.otp.provider"], havingValue = "log", matchIfMissing = true)
class LoggingOtpSender : OtpSender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(phone: String, code: String) {
        log.warn("=== DEV OTP for {} : {} ===", PhoneNumber.mask(phone), code)
    }

    override val exposesCodeForDevelopment = true
}
