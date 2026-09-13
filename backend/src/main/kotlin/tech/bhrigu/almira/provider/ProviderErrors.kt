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

    /**
     * The provider is not offered on this server ([ProviderMode.DISABLED]).
     *
     * 409, not 503 and not 404. 503 is what a provider outage answers, and
     * clients are told they may retry it; this will not change by retrying. 404
     * is how this API says "no such household, or not yours" (docs/05 §3.3), and
     * a client reading it would conclude the household had gone. 403 would say
     * the person lacks a permission they could be given. What is true is that
     * the request conflicts with how this server is configured — and the code,
     * not the status, is what a client branches on.
     */
    fun disabled(provider: String): ApiException = ApiException(
        HttpStatus.CONFLICT, "provider_disabled",
        "${labelFor(provider)} isn't offered on this server.",
        mapOf("provider" to provider),
    )

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
