package tech.bhrigu.almira.common

import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver

/**
 * Spring logs every exception it hands to [ApiErrorHandler] at DEBUG as
 * "Resolved [<exception.toString()>]". For a validation failure that string
 * carries each rejected value verbatim — so with web debugging on, a mistyped
 * one-time code ("27020424a", or the right eight digits with a stray space)
 * went straight into the log. OtpCodeNeverLeaksTest caught it.
 *
 * The line keeps its place and names the exception type, which is what anyone
 * debugging needs to know which handler ran; the message, which is where the
 * caller's input lives, is left out. Everything [ApiErrorHandler] decides to
 * log it still logs deliberately.
 */
@Configuration
class ExceptionResolverLogging : WebMvcRegistrations {
    override fun getExceptionHandlerExceptionResolver(): ExceptionHandlerExceptionResolver =
        object : ExceptionHandlerExceptionResolver() {
            override fun buildLogMessage(ex: Exception, request: HttpServletRequest): String =
                "Resolved [${ex.javaClass.name}]"
        }
}
