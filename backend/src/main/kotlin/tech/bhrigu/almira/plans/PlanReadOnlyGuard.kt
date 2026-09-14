package tech.bhrigu.almira.plans

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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
 * When a household's plan has lapsed, it is read-only — and never locked
 * (docs/27 §2).
 *
 * Every GET, HEAD and OPTIONS passes, so everything a person could see they
 * still see: the screens, the handbook, the reports, the tax pack. Everything
 * outside a household passes too: `GET /me/export` (Download everything),
 * `/me/closure` (closing an account), sign-in, preferences, rights requests and
 * support codes are not under `/households/{id}` and are never checked here.
 *
 * A write inside the household is refused with 403 `plan_read_only`, before any
 * service runs, unless it is on [ALWAYS_ALLOWED]: the ways to leave, to protect
 * someone's privacy, to hand the record on, or to reach it in an emergency. A
 * lapsed plan must never be the reason a family cannot close an account, remove
 * a person, withdraw consent, veto an emergency request or look at a document.
 *
 * The allowlist is proven against every write the API serves, not a sample:
 * PlanReadOnlyApiTest walks the live OpenAPI document.
 *
 * Why here and not in the database: a lapsed plan is a commercial state, not a
 * privacy rule. Row-level security still decides who sees and changes what;
 * this only decides whether a household in good standing is being changed. A
 * non-member's plan row is invisible to them, so this never answers for a
 * household they may not see — the 404 comes from the service as always.
 */
@Component
class PlanReadOnlyGuard(
    private val plans: HouseholdPlanService,
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
        val rest = match.groupValues[2]
        if (ALWAYS_ALLOWED.any { (method, path) -> (method == null || method == request.method) && path.matches(rest) }) {
            return true
        }
        val householdId = runCatching { UUID.fromString(match.groupValues[1]) }.getOrNull() ?: return true

        if (readOnly.execute { plans.isReadOnly(householdId) } != true) return true

        response.status = HttpStatus.FORBIDDEN.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        mapper.writeValue(response.outputStream, ApiErrorEnvelope(ApiErrorBody(CODE, MESSAGE)))
        return false
    }

    companion object {
        const val CODE = "plan_read_only"
        const val MESSAGE =
            "Your family's plan has ended, so nothing here can be changed for now. You can still see " +
                "everything, open the handbook, download everything, or close your account. " +
                HouseholdPlanService.PROMISE

        private val READS = setOf("GET", "HEAD", "OPTIONS")
        private val HOUSEHOLD_PATH = Regex("^/api/v1/households/([^/]+)(/.*)?$")
        private const val ID = "[^/]+"

        /**
         * (method, path after `/households/{id}`) — a null method is any. Each
         * line says why a lapsed plan must not stop it.
         */
        val ALWAYS_ALLOWED: List<Pair<String?, Regex>> = listOf(
            // Seeing: these are reads that are POSTs because they are audited.
            "POST" to Regex("^/accounts/$ID/reveal-number$"),
            "POST" to Regex("^/documents/$ID/access$"),
            "POST" to Regex("^/e2e/recovery/$ID/practice$"),
            // Leaving, and making sure someone carries the household on, which
            // closing an owner's account can require first.
            null to Regex("^/departures(/.*)?$"),
            null to Regex("^/successor(/.*)?$"),
            "PATCH" to Regex("^/members/$ID$"),
            "DELETE" to Regex("^/members/$ID$"),
            // A death, a child coming of age, and consent for a minor.
            null to Regex("^/members/$ID/memorial$"),
            null to Regex("^/coming-of-age(/.*)?$"),
            null to Regex("^/members/$ID/parental-consent$"),
            "POST" to Regex("^/parental-consents/$ID/withdraw$"),
            // Continuity: emergency access, and who can open sealed values.
            null to Regex("^/emergency(/.*)?$"),
            null to Regex("^/e2e/recovery/$ID$"),
            // Who may see the record: changing a record's visibility, a guest
            // link for the CA or an heir, or taking back an invitation. Deciding
            // who sees the family's record is never paused.
            "PATCH" to Regex("^/(investments|liabilities|accounts|goals)/$ID/visibility$"),
            null to Regex("^/shares(/$ID)?$"),
            "DELETE" to Regex("^/invitations/$ID$"),
        )
    }
}

@Configuration
class PlanReadOnlyGuardConfig(private val guard: PlanReadOnlyGuard) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(guard).addPathPatterns("/api/v1/households/**")
    }
}
