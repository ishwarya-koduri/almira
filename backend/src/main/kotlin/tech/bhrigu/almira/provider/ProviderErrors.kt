package tech.bhrigu.almira.provider

import org.springframework.http.HttpStatus
import tech.bhrigu.almira.common.ApiException

/**
 * What a person is told when a connect call to DigiLocker, the Account
 * Aggregator or WhatsApp fails — one code per [FailureKind], because "try again
 * in a minute", "start again" and "it's us, not you" are different instructions.
 *
 * One-time codes have their own wording in OtpService: signing in is a
 * different moment, and "check your number" only makes sense there.
 *
 * Details carry the provider and the attempt count, never the adapter's detail
 * string: that is for the operator's log.
 */
object ProviderErrors {

    fun forConnect(failure: ProviderCallFailed): ApiException {
        val label = labelFor(failure.provider)
        val details = mapOf("provider" to failure.provider, "attempts" to failure.attempts)
        return when (failure.kind) {
            FailureKind.TIMEOUT -> ApiException(
                HttpStatus.GATEWAY_TIMEOUT, "provider_timeout",
                "$label is taking too long to answer. Please try again in a minute.",
                details,
            )
            FailureKind.UNAVAILABLE -> ApiException(
                HttpStatus.SERVICE_UNAVAILABLE, "provider_unavailable",
                "$label isn't answering right now. Please try again in a few minutes.",
                details,
            )
            FailureKind.REJECTED -> ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY, "provider_rejected",
                "$label turned that request down. Please start again from the beginning.",
                details,
            )
            FailureKind.INSUFFICIENT_BALANCE -> ApiException(
                HttpStatus.SERVICE_UNAVAILABLE, "provider_account_unavailable",
                "$label isn't available on our side right now. It isn't anything you did, " +
                    "and we've been alerted.",
                details,
            )
        }
    }

    private fun labelFor(provider: String) = when (provider) {
        "digilocker" -> "DigiLocker"
        "aa" -> "The Account Aggregator"
        "whatsapp" -> "WhatsApp"
        "sms" -> "Text messaging"
        "email" -> "Email"
        "push" -> "Push notifications"
        else -> "That service"
    }
}
