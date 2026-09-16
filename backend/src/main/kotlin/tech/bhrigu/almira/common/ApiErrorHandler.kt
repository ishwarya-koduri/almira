package tech.bhrigu.almira.common

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant

data class ApiErrorBody(
    val code: String,
    val message: String,
    val details: Map<String, Any?>? = null,
    val at: Instant = Instant.now(),
) {
    /**
     * Spring MVC prints a response body at DEBUG and TRACE. An error can carry
     * a credential for its next step (`secondFactorToken`), which must not be
     * written down with it.
     */
    override fun toString() = "ApiErrorBody(code=$code, message=$message, details=" +
        details?.mapValues { (key, value) -> if (key.endsWith("token", ignoreCase = true)) "[redacted]" else value } +
        ", at=$at)"
}

data class ApiErrorEnvelope(val error: ApiErrorBody)

@RestControllerAdvice
class ApiErrorHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Every error leaves here as JSON, whatever the request asked to be sent.
     *
     * Setting the content type on the response tells Spring what to write and
     * skips content negotiation for it. Without that, an error answering a
     * request whose `Accept` excludes JSON cannot be written at all: the write
     * throws, the throw escapes the dispatcher, and the container re-dispatches
     * to /error — where the JWT filter does not run, because a
     * OncePerRequestFilter skips an error dispatch by default — so the caller is
     * told 401 unauthorized with a live session (docs/api/README.md, changelog
     * 2026-09-16).
     *
     * A caller who cannot read JSON still cannot read this. But the status is
     * then the truth (406 below), and an error is never mistaken for a dead
     * session.
     */
    private fun envelope(status: HttpStatus, body: ApiErrorBody): ResponseEntity<ApiErrorEnvelope> =
        ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(ApiErrorEnvelope(body))

    @ExceptionHandler(ApiException::class)
    fun handleApi(e: ApiException): ResponseEntity<ApiErrorEnvelope> =
        envelope(e.status, ApiErrorBody(e.code, e.message, e.details.ifEmpty { null }))

    /** Field validation. Messages are per-field so the form can show them inline. */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ApiErrorEnvelope> {
        val fields = e.bindingResult.fieldErrors.associate {
            it.field to (it.defaultMessage ?: "is not valid")
        }
        return envelope(
            HttpStatus.BAD_REQUEST,
            ApiErrorBody(
                "validation_failed",
                "Some details need a second look.",
                mapOf("fields" to fields),
            ),
        )
    }

    /**
     * A write refused by row-level security arrives as SQLSTATE 42501, which
     * Spring translates to BadSqlGrammarException — nothing to do with grammar.
     * Left unhandled it becomes a 500, telling the caller we are broken when in
     * fact we correctly declined. Anything else in that class really is a bug on
     * our side and stays a 500.
     */
    @ExceptionHandler(org.springframework.jdbc.BadSqlGrammarException::class)
    fun handleSqlRefusal(
        e: org.springframework.jdbc.BadSqlGrammarException,
    ): ResponseEntity<ApiErrorEnvelope> {
        val text = (e.mostSpecificCause.message ?: "").lowercase()
        if ("row-level security" in text || "permission denied" in text) {
            log.warn("write refused by database policy: {}", text)
            return envelope(
                HttpStatus.FORBIDDEN,
                ApiErrorBody("forbidden", "You don't have access to do that."),
            )
        }
        log.error("bad SQL", e)
        return envelope(
            HttpStatus.INTERNAL_SERVER_ERROR,
            ApiErrorBody(
                "internal_error",
                "Something went wrong on our side. Your data is safe. Please try again.",
            ),
        )
    }

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleIntegrity(e: DataIntegrityViolationException): ResponseEntity<ApiErrorEnvelope> {
        val text = (e.mostSpecificCause.message ?: "").lowercase()
        val (code, message) = when {
            "must total 100" in text ->
                "ownership_shares_invalid" to "Ownership shares need to add up to 100%."
            "more than it is" in text ->
                "allocation_exceeds_holding" to
                    "That holding can't be allocated to goals more than once over."
            "responsibility for liability" in text ->
                "responsibility_invalid" to "Responsibility for a loan needs to add up to 100%."
            "row-level security" in text ->
                "forbidden" to "You don't have access to do that."
            "duplicate key" in text ->
                "duplicate" to "That already exists."
            else -> "invalid_data" to "Something about that didn't fit. Please check the details."
        }
        // Logged at debug: the driver message can echo user data back into logs.
        log.debug("data integrity violation: {}", text)
        val status = if (code == "forbidden") HttpStatus.FORBIDDEN else HttpStatus.BAD_REQUEST
        return envelope(status, ApiErrorBody(code, message))
    }

    /**
     * A URL that matches no handler is a 404, not a 500. Without this it falls
     * through to the catch-all below and reports an internal error, which sends
     * a client hunting for a server fault that does not exist.
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException::class)
    fun handleNoRoute(
        e: org.springframework.web.servlet.resource.NoResourceFoundException,
    ): ResponseEntity<ApiErrorEnvelope> =
        envelope(HttpStatus.NOT_FOUND, ApiErrorBody("not_found", "We couldn't find that."))

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException::class)
    fun handleWrongMethod(
        e: org.springframework.web.HttpRequestMethodNotSupportedException,
    ): ResponseEntity<ApiErrorEnvelope> =
        envelope(
            HttpStatus.METHOD_NOT_ALLOWED,
            ApiErrorBody("method_not_allowed", "That isn't something you can do here."),
        )

    /**
     * A request the server cannot read is the caller's mistake, answered as one.
     *
     * These all used to fall through to the catch-all: a 500 internal_error and
     * an ERROR log for input we had correctly refused (docs/api/README.md,
     * changelog 2026-09-13). Logged at INFO by exception type and route
     * pattern only — the exception messages quote the rejected value, and a
     * concrete URL can carry it in a path segment. Nothing the caller sent is
     * repeated in the response: a field or parameter NAME at most, which comes
     * from our own declarations.
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException::class)
    fun handleUnreadableBody(
        e: org.springframework.http.converter.HttpMessageNotReadableException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorEnvelope> {
        logRefused(e, request)
        val missing = (e as? UnreadableBodyException)?.missingField
            ?: return malformed()
        return envelope(
            HttpStatus.BAD_REQUEST,
            ApiErrorBody(
                "validation_failed",
                "Some details need a second look.",
                mapOf("fields" to mapOf(missing to "This is required")),
            ),
        )
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException::class)
    fun handleParameterType(
        e: org.springframework.web.method.annotation.MethodArgumentTypeMismatchException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorEnvelope> {
        logRefused(e, request)
        return malformed(e.name)
    }

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException::class)
    fun handleMissingParameter(
        e: org.springframework.web.bind.MissingServletRequestParameterException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorEnvelope> {
        logRefused(e, request)
        return malformed(e.parameterName)
    }

    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException::class)
    fun handleMissingPart(
        e: org.springframework.web.multipart.support.MissingServletRequestPartException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorEnvelope> {
        logRefused(e, request)
        return malformed(e.requestPartName)
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException::class)
    fun handleMediaType(
        e: org.springframework.web.HttpMediaTypeNotSupportedException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorEnvelope> {
        logRefused(e, request)
        return envelope(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            ApiErrorBody("unsupported_media_type", "We couldn't read that request in the format it was sent."),
        )
    }

    /**
     * The mirror of the 415 above: the caller's `Accept` leaves nothing this
     * endpoint can send. Thrown two ways, and both used to reach the catch-all.
     *
     * Before the handler runs, when the URL matches only routes that produce
     * something else: `GET /api/v1/me/export` (`application/zip`) asked for as
     * JSON answered 500, a server fault for a correct refusal, on the door DPDP
     * makes us keep open.
     *
     * After it runs, when the answer cannot be written in any accepted type:
     * `Accept: text/html` on any JSON endpoint answered **401 unauthorized**
     * with a perfectly good session, because the failed write escaped into an
     * error dispatch (see [envelope]). A client that treats 401 as "signed out"
     * would have wiped a live session over a request header.
     *
     * 406 both times, rather than sending JSON anyway: the caller stated what it
     * can read, and an endpoint that produces a zip has no JSON to fall back to.
     * A status that says "not in that format" is one a client can act on; a body
     * in a format it just said it cannot parse is not.
     */
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotAcceptableException::class)
    fun handleNotAcceptable(
        e: org.springframework.web.HttpMediaTypeNotAcceptableException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorEnvelope> {
        logRefused(e, request)
        return envelope(
            HttpStatus.NOT_ACCEPTABLE,
            ApiErrorBody("not_acceptable", "We can't send that in the format you asked for."),
        )
    }

    private fun logRefused(e: Exception, request: HttpServletRequest) {
        val route = request.getAttribute(
            org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
        ) ?: "(no route matched)"
        val type = when (e) {
            is UnreadableBodyException -> "HttpMessageNotReadableException (${e.failure})"
            else -> e.javaClass.simpleName
        }
        log.info("request refused on {} {}: {}", request.method, route, type)
    }

    private fun malformed(parameter: String? = null): ResponseEntity<ApiErrorEnvelope> =
        envelope(
            HttpStatus.BAD_REQUEST,
            ApiErrorBody(
                "malformed_request",
                "We couldn't read that request.",
                parameter?.let { mapOf("parameter" to it) },
            ),
        )

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception, request: HttpServletRequest): ResponseEntity<ApiErrorEnvelope> {
        log.error("unhandled error on {} {}", request.method, request.requestURI, e)
        return envelope(
            HttpStatus.INTERNAL_SERVER_ERROR,
            ApiErrorBody(
                "internal_error",
                "Something went wrong on our side. Your data is safe. Please try again.",
            ),
        )
    }
}
