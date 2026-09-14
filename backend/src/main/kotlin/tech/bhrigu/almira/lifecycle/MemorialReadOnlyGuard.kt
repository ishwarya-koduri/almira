package tech.bhrigu.almira.lifecycle

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import tech.bhrigu.almira.common.ApiErrorBody
import tech.bhrigu.almira.common.ApiErrorEnvelope
import tech.bhrigu.almira.security.RequestUserContext
import java.util.UUID

/**
 * A memorialised account changes nothing in that household, and is told so in
 * words (docs/05 §12).
 *
 * The database is what makes it true: both capability functions refuse a
 * memorialised caller (V40), so every write policy does. But a refused write
 * reaches the person as "You don't have access to do that", or as an update
 * that quietly changed nothing — which, for someone who has just signed in to
 * find their family believes they have died, is the wrong sentence. This says
 * the right one before any service runs.
 *
 * The single exception is the way out: taking the label away.
 */
@Component
class MemorialReadOnlyGuard(
    private val jdbc: NamedParameterJdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val userContext: RequestUserContext,
    private val mapper: ObjectMapper,
) : HandlerInterceptor {

    private val readOnly = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (request.method in READS) return true
        userContext.currentUserId() ?: return true
        if (userContext.currentGuestShareId() != null) return true
        val match = HOUSEHOLD_PATH.matchEntire(request.requestURI) ?: return true
        if (MEMORIAL_PATH.matches(match.groupValues[2]) && request.method == "DELETE") return true
        val householdId = runCatching { UUID.fromString(match.groupValues[1]) }.getOrNull() ?: return true

        val memorialised = readOnly.execute {
            jdbc.queryForObject(
                "select app.is_memorialised_in(:hid)", mapOf("hid" to householdId), Boolean::class.java,
            )
        } == true
        if (!memorialised) return true

        response.status = HttpStatus.FORBIDDEN.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        mapper.writeValue(
            response.outputStream,
            ApiErrorEnvelope(
                ApiErrorBody(
                    "memorial_read_only",
                    "Your account here is marked as passed away, so nothing can be changed. " +
                        "If that's wrong, choose \"I'm here\" on the Family screen.",
                ),
            ),
        )
        return false
    }

    private companion object {
        val READS = setOf("GET", "HEAD", "OPTIONS")
        val HOUSEHOLD_PATH = Regex("^/api/v1/households/([^/]+)(/.*)?$")
        val MEMORIAL_PATH = Regex("^/members/[^/]+/memorial/?$")
    }
}

@Configuration
class MemorialReadOnlyGuardConfig(private val guard: MemorialReadOnlyGuard) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(guard).addPathPatterns("/api/v1/households/**")
    }
}
