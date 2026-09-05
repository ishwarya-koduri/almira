package tech.bhrigu.almira.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tech.bhrigu.almira.security.RequestUserContext
import java.time.Instant
import java.util.UUID

// --- requests ---------------------------------------------------------------

data class OtpRequestBody(
    @field:NotBlank(message = "Enter your phone number")
    val phone: String,
)

data class OtpVerifyBody(
    @field:NotBlank(message = "Enter your phone number")
    val phone: String,
    @field:Pattern(regexp = "^[0-9]{4,8}$", message = "Enter the code we texted you")
    val code: String,
    val requestId: String? = null,
    val deviceName: String? = null,
)

data class RefreshBody(@field:NotBlank val refreshToken: String)

data class StepUpVerifyBody(
    @field:Pattern(regexp = "^[0-9]{4,8}$", message = "Enter the code we texted you")
    val code: String,
    val requestId: String? = null,
)

data class StepUpStatusResponse(val elevated: Boolean, val expiresInSeconds: Long)

data class PreferencesBody(val fullName: String? = null, val defaultVisibility: String? = null)

// --- responses --------------------------------------------------------------

data class OtpChallengeResponse(
    val requestId: String,
    val expiresInSeconds: Long,
    val resendAfterSeconds: Long,
    val developmentCode: String?,
)

data class SessionResponse(
    val id: UUID,
    val deviceName: String?,
    val createdAt: Instant,
    val lastUsedAt: Instant,
    val expiresAt: Instant,
)

data class MeResponse(
    val id: UUID,
    val phone: String?,
    val email: String?,
    val fullName: String?,
    val defaultVisibility: String,
    val locale: String,
    val currency: String,
)

data class LoginResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val tokenType: String,
    val isNewUser: Boolean,
    val user: MeResponse,
)

@RestController
@RequestMapping("/api")
@Validated
class AuthController(
    private val auth: AuthService,
    private val stepUp: StepUpService,
    private val userContext: RequestUserContext,
) {

    @PostMapping("/auth/otp/request")
    fun requestOtp(
        @RequestBody @jakarta.validation.Valid body: OtpRequestBody,
        request: HttpServletRequest,
    ): OtpChallengeResponse {
        val c = auth.requestOtp(body.phone, clientIp(request))
        return OtpChallengeResponse(
            c.requestId, c.expiresInSeconds, c.resendAfterSeconds, c.developmentCode,
        )
    }

    @PostMapping("/auth/otp/verify")
    fun verifyOtp(
        @RequestBody @jakarta.validation.Valid body: OtpVerifyBody,
        request: HttpServletRequest,
    ): LoginResponse {
        val result = auth.verifyOtp(
            rawPhone = body.phone,
            code = body.code,
            requestId = body.requestId,
            deviceName = body.deviceName,
            userAgent = request.getHeader("User-Agent"),
            ip = clientIp(request),
        )
        return LoginResponse(
            accessToken = result.tokens.accessToken,
            refreshToken = result.tokens.refreshToken,
            expiresInSeconds = result.tokens.expiresInSeconds,
            tokenType = result.tokens.tokenType,
            isNewUser = result.isNewUser,
            user = result.user.toResponse(),
        )
    }

    @PostMapping("/auth/refresh")
    fun refresh(
        @RequestBody @jakarta.validation.Valid body: RefreshBody,
        request: HttpServletRequest,
    ): TokenPair = auth.refresh(body.refreshToken, clientIp(request), request.getHeader("User-Agent"))

    @PostMapping("/auth/logout")
    fun logout(request: HttpServletRequest): ResponseEntity<Void> {
        currentSessionId(request)?.let { auth.logout(userContext.require(), it) }
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/auth/sessions")
    fun sessions(): List<SessionResponse> =
        auth.sessions(userContext.require()).map {
            SessionResponse(it.id, it.deviceName, it.createdAt, it.lastUsedAt, it.expiresAt)
        }

    @DeleteMapping("/auth/sessions/{id}")
    fun revokeSession(@PathVariable id: UUID): ResponseEntity<Void> {
        auth.revokeSession(userContext.require(), id)
        return ResponseEntity.noContent().build()
    }

    /**
     * Re-authentication before a full account or policy number is shown
     * (docs/05 §5). Elevation is granted to this SESSION for a few minutes, not
     * to the account — proving yourself on your phone does not unlock a browser
     * someone else is sitting in front of.
     */
    @PostMapping("/auth/step-up/request")
    fun requestStepUp(request: HttpServletRequest): OtpChallengeResponse {
        val c = stepUp.request(userContext.require(), clientIp(request))
        return OtpChallengeResponse(
            c.requestId, c.expiresInSeconds, c.resendAfterSeconds, c.developmentCode,
        )
    }

    @PostMapping("/auth/step-up/verify")
    fun verifyStepUp(@RequestBody @jakarta.validation.Valid body: StepUpVerifyBody): StepUpStatusResponse {
        val sessionId = userContext.requireSession()
        stepUp.verify(userContext.require(), sessionId, body.code, body.requestId)
        return StepUpStatusResponse(true, stepUp.remainingSeconds(sessionId))
    }

    @GetMapping("/auth/step-up")
    fun stepUpStatus(): StepUpStatusResponse {
        val remaining = stepUp.remainingSeconds(userContext.currentSessionId())
        return StepUpStatusResponse(remaining > 0, remaining)
    }

    @GetMapping("/me")
    fun me(): MeResponse = auth.me(userContext.require()).toResponse()

    @PatchMapping("/me")
    fun updateMe(@RequestBody body: PreferencesBody): MeResponse =
        auth.updatePreferences(userContext.require(), body.fullName, body.defaultVisibility)
            .toResponse()

    // --- helpers ------------------------------------------------------------

    private fun UserRow.toResponse() = MeResponse(
        id = id, phone = phone, email = email, fullName = fullName,
        defaultVisibility = defaultVisibility, locale = locale, currency = currencyPref,
    )

    private fun currentSessionId(request: HttpServletRequest): UUID? =
        userContext.currentSessionId()

    /**
     * Behind a load balancer the socket address is the proxy, so the first hop
     * in X-Forwarded-For is the real client. Only ever used for rate limiting
     * and audit — never for authorisation, because the header is caller-supplied
     * and trivially spoofed.
     */
    private fun clientIp(request: HttpServletRequest): String? =
        request.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: request.remoteAddr
}
