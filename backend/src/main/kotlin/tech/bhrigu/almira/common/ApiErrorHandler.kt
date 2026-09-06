package tech.bhrigu.almira.common

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
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
)

data class ApiErrorEnvelope(val error: ApiErrorBody)

@RestControllerAdvice
class ApiErrorHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handleApi(e: ApiException): ResponseEntity<ApiErrorEnvelope> =
        ResponseEntity.status(e.status).body(
            ApiErrorEnvelope(ApiErrorBody(e.code, e.message, e.details.ifEmpty { null })),
        )

    /** Field validation. Messages are per-field so the form can show them inline. */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ApiErrorEnvelope> {
        val fields = e.bindingResult.fieldErrors.associate {
            it.field to (it.defaultMessage ?: "is not valid")
        }
        return ResponseEntity.badRequest().body(
            ApiErrorEnvelope(
                ApiErrorBody(
                    "validation_failed",
                    "Some details need a second look.",
                    mapOf("fields" to fields),
                ),
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
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                ApiErrorEnvelope(ApiErrorBody("forbidden", "You don't have access to do that.")),
            )
        }
        log.error("bad SQL", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ApiErrorEnvelope(
                ApiErrorBody(
                    "internal_error",
                    "Something went wrong on our side. Your data is safe — please try again.",
                ),
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
        return ResponseEntity.status(status).body(ApiErrorEnvelope(ApiErrorBody(code, message)))
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
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiErrorEnvelope(ApiErrorBody("not_found", "We couldn't find that.")),
        )

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException::class)
    fun handleWrongMethod(
        e: org.springframework.web.HttpRequestMethodNotSupportedException,
    ): ResponseEntity<ApiErrorEnvelope> =
        ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(
            ApiErrorEnvelope(ApiErrorBody("method_not_allowed", "That isn't something you can do here.")),
        )

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception, request: HttpServletRequest): ResponseEntity<ApiErrorEnvelope> {
        log.error("unhandled error on {} {}", request.method, request.requestURI, e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ApiErrorEnvelope(
                ApiErrorBody(
                    "internal_error",
                    "Something went wrong on our side. Your data is safe — please try again.",
                ),
            ),
        )
    }
}
