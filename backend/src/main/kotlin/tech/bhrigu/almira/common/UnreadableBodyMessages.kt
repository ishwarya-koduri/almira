package tech.bhrigu.almira.common

import com.fasterxml.jackson.databind.ObjectMapper
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
        HttpMessageNotReadableException(
            "Request body could not be read as JSON (${(e.cause ?: e).javaClass.simpleName}); " +
                "its content is deliberately not repeated here",
            input,
        )
}
