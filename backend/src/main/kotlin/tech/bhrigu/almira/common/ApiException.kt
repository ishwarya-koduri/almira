package tech.bhrigu.almira.common

import org.springframework.http.HttpStatus

/**
 * Every failure the API returns on purpose.
 *
 * `code` is the stable, machine-readable handle a client branches on.
 * `message` is shown to a person, so it follows the voice in docs/02 §10:
 * plain, short, kind, and never alarmist. No stack traces, no SQL, no raw
 * codes — and nothing that would reveal whether a record the caller may not
 * see actually exists.
 */
class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
    val details: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    companion object {
        fun badRequest(code: String, message: String, details: Map<String, Any?> = emptyMap()) =
            ApiException(HttpStatus.BAD_REQUEST, code, message, details)

        fun unauthorized(message: String = "Please sign in again.") =
            ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", message)

        fun forbidden(message: String = "You don't have access to do that.") =
            ApiException(HttpStatus.FORBIDDEN, "forbidden", message)

        /**
         * Used for "not visible to you" as well as "does not exist" — on
         * purpose. A 403 would confirm the record exists, which is itself the
         * leak the privacy model is designed to prevent (docs/05 §3.3).
         */
        fun notFound(what: String = "We couldn't find that.") =
            ApiException(HttpStatus.NOT_FOUND, "not_found", what)

        fun conflict(code: String, message: String, details: Map<String, Any?> = emptyMap()) =
            ApiException(HttpStatus.CONFLICT, code, message, details)

        fun tooManyRequests(message: String, retryAfterSeconds: Long) =
            ApiException(
                HttpStatus.TOO_MANY_REQUESTS, "rate_limited", message,
                mapOf("retryAfterSeconds" to retryAfterSeconds),
            )
    }
}
