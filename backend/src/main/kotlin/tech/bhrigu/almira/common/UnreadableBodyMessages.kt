package tech.bhrigu.almira.common

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.MissingKotlinParameterException
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpInputMessage
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import java.lang.reflect.Type

/**
 * A request body that is not valid JSON must not have its contents repeated
 * into the log.
 *
 * Jackson redacts the source document from its error locations, but its
 * messages still quote what it choked on — `Unrecognized token 'x27020424'`.
 * Spring copies that message into HttpMessageNotReadableException, keeps the
 * Jackson exception as its cause, and from there it is logged at DEBUG by the
 * argument resolver and at ERROR by [ApiErrorHandler]'s catch-all. ERROR is on
 * in production. Somebody typing their one-time code into a client that built
 * the JSON badly would have had it written to the application log.
 * OtpCodeNeverLeaksTest caught it.
 *
 * So the exception is replaced where it is made: same type, so every handler
 * treats it exactly as before, but the message names only what kind of
 * failure it was and the cause is dropped. Replacing Boot's converter bean keeps
 * Boot's configured ObjectMapper; nothing else about how JSON is read changes.
 *
 * The one thing kept from Jackson's exception is the NAME of a required field
 * that was missing (or sent as null), so [ApiErrorHandler] can answer
 * validation_failed for it. A name comes from the Kotlin class, never from the
 * input — except a map key, which the caller wrote, so a key is replaced with
 * `[*]` and never carried. The value is never looked at.
 */
@Configuration
class UnreadableBodyMessages {

    @Bean
    fun mappingJackson2HttpMessageConverter(mapper: ObjectMapper): MappingJackson2HttpMessageConverter =
        object : MappingJackson2HttpMessageConverter(mapper) {
            override fun read(type: Type, contextClass: Class<*>?, inputMessage: HttpInputMessage): Any =
                try {
                    super.read(type, contextClass, inputMessage)
                } catch (e: HttpMessageNotReadableException) {
                    throw withoutInput(e, inputMessage)
                }

            override fun readInternal(clazz: Class<*>, inputMessage: HttpInputMessage): Any =
                try {
                    super.readInternal(clazz, inputMessage)
                } catch (e: HttpMessageNotReadableException) {
                    throw withoutInput(e, inputMessage)
                }
        }

    private fun withoutInput(e: HttpMessageNotReadableException, input: HttpInputMessage) =
        UnreadableBodyException(
            "Request body could not be read as JSON (${(e.cause ?: e).javaClass.simpleName}); " +
                "its content is deliberately not repeated here",
            input,
            missingRequiredField(e.cause),
            (e.cause ?: e).javaClass.simpleName,
        )
}

/**
 * The same type Spring throws, so every handler treats it as before, carrying
 * only a declared field path when the failure was a missing required field.
 */
class UnreadableBodyException(
    message: String,
    input: HttpInputMessage,
    val missingField: String?,
    /** The simple class name of what Jackson threw — a kind, never content. */
    val failure: String,
) : HttpMessageNotReadableException(message, input)

/**
 * `owners[0].memberId` for a missing (or null) non-nullable constructor
 * parameter, in the form field validation uses; null for any other failure.
 * Map keys are the caller's text and are written as `[*]`.
 */
internal fun missingRequiredField(cause: Throwable?): String? {
    if (cause !is MissingKotlinParameterException) return null
    val path = StringBuilder()
    for (ref in cause.path) {
        when {
            ref.from is Map<*, *> -> path.append("[*]")
            ref.index >= 0 -> path.append('[').append(ref.index).append(']')
            ref.fieldName != null -> {
                if (path.isNotEmpty()) path.append('.')
                path.append(ref.fieldName)
            }
            else -> return null
        }
    }
    return path.toString().ifEmpty { null }
}
