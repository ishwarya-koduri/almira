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
) {
    /**
     * A data class prints every field, and Spring MVC prints the resolved body
     * at DEBUG ("Read ... to [OtpVerifyBody(phone=…, code=123456…)]"). Turning
     * on web debugging to chase a sign-in problem would have written every
     * submitted code to the log.
     */
    override fun toString() =
        "OtpVerifyBody(phone=${tech.bhrigu.almira.common.PhoneNumber.mask(phone)}, code=[redacted], " +
            "requestId=$requestId, deviceName=$deviceName)"
}

data class EmailOtpRequestBody(
    @field:NotBlank(message = "Enter your email address")
    val email: String,
) {
    /** An address is personal data; Spring MVC prints resolved bodies at DEBUG. */
    override fun toString() = "EmailOtpRequestBody(email=${tech.bhrigu.almira.common.EmailAddress.mask(email)})"
}

data class EmailOtpVerifyBody(
    @field:NotBlank(message = "Enter your email address")
    val email: String,
    @field:Pattern(regexp = "^[0-9]{4,8}$", message = "Enter the code we emailed you")
    val code: String,
    val requestId: String? = null,
    val deviceName: String? = null,
) {
    /** See [OtpVerifyBody.toString]. */
    override fun toString() =
        "EmailOtpVerifyBody(email=${tech.bhrigu.almira.common.EmailAddress.mask(email)}, code=[redacted], " +
            "requestId=$requestId, deviceName=$deviceName)"
}

data class RefreshBody(@field:NotBlank val refreshToken: String)

data class StepUpVerifyBody(
    @field:Pattern(regexp = "^[0-9]{4,8}$", message = "Enter the code we texted you")
    val code: String,
    val requestId: String? = null,
) {
    /** See [OtpVerifyBody.toString]. */
    override fun toString() = "StepUpVerifyBody(code=[redacted], requestId=$requestId)"
}

data class StepUpStatusResponse(val elevated: Boolean, val expiresInSeconds: Long)

data class PreferencesBody(val fullName: String? = null, val defaultVisibility: String? = null)

// --- responses --------------------------------------------------------------

data class OtpChallengeResponse(
    val requestId: String,
    val expiresInSeconds: Long,
    val resendAfterSeconds: Long,
    val developmentCode: String?,
    /** `phone` or `email`: where the code went. Added after v1 froze, so optional to clients. */
    val channel: String? = null,
)

/** Which sign-in endpoints this server answers, so a client shows only those. */
data class SignInChannelsResponse(val channels: List<String>)

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
@RequestMapping("/api/v1")
@Validated
class AuthController(
    private val auth: AuthService,
    private val stepUp: StepUpService,
    private val userContext: RequestUserContext,
    private val channels: SignInChannels,
) {

    /**
     * Public, and the same for everybody: it describes the server, not a person.
     * Under /auth/otp so it is reachable before sign-in like the rest of it.
     */
    @GetMapping("/auth/otp/channels")
    fun signInChannels(): SignInChannelsResponse = SignInChannelsResponse(channels.enabled.map { it.key })

    @PostMapping("/auth/otp/request")
    fun requestOtp(
        @RequestBody @jakarta.validation.Valid body: OtpRequestBody,
        request: HttpServletRequest,
    ): OtpChallengeResponse {
        return auth.requestOtp(body.phone, clientIp(request)).toResponse()
    }

    @PostMapping("/auth/otp/verify")
    fun verifyOtp(
        @RequestBody @jakarta.validation.Valid body: OtpVerifyBody,
        request: HttpServletRequest,
    ): LoginResponse {
        return auth.verifyOtp(
            rawPhone = body.phone,
            code = body.code,
            requestId = body.requestId,
            deviceName = body.deviceName,
            userAgent = request.getHeader("User-Agent"),
            ip = clientIp(request),
        ).toResponse()
    }

    /**
     * Sign-in by email. Answers the same for an address that is not allowed to
     * sign in as for one that is — see AuthService.requestEmailOtp.
     */
    @PostMapping("/auth/otp/email/request")
    fun requestEmailOtp(
        @RequestBody @jakarta.validation.Valid body: EmailOtpRequestBody,
        request: HttpServletRequest,
    ): OtpChallengeResponse = auth.requestEmailOtp(body.email, clientIp(request)).toResponse()

    @PostMapping("/auth/otp/email/verify")
    fun verifyEmailOtp(
        @RequestBody @jakarta.validation.Valid body: EmailOtpVerifyBody,
        request: HttpServletRequest,
    ): LoginResponse = auth.verifyEmailOtp(
        rawEmail = body.email,
        code = body.code,
        requestId = body.requestId,
        deviceName = body.deviceName,
        userAgent = request.getHeader("User-Agent"),
        ip = clientIp(request),
    ).toResponse()

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
        return stepUp.request(userContext.require(), clientIp(request)).toResponse()
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

    private fun OtpChallenge.toResponse() = OtpChallengeResponse(
        requestId, expiresInSeconds, resendAfterSeconds, developmentCode, channel.key,
    )

    private fun LoginResult.toResponse() = LoginResponse(
        accessToken = tokens.accessToken,
        refreshToken = tokens.refreshToken,
        expiresInSeconds = tokens.expiresInSeconds,
        tokenType = tokens.tokenType,
        isNewUser = isNewUser,
        user = user.toResponse(),
    )

    private fun UserRow.toResponse() = MeResponse(
        id = id, phone = phone, email = email, fullName = fullName,
        defaultVisibility = defaultVisibility, locale = locale, currency = currencyPref,
    )

    private fun currentSessionId(request: HttpServletRequest): UUID? =
        userContext.currentSessionId()

    /**
     * The caller's address, for rate limiting and audit only.
     *
     * This used to take the first X-Forwarded-For entry from anyone. That
     * header is whatever the caller types, so the per-network sign-in limit was
     * a limit on how many different strings an attacker could be bothered to
     * invent. The address now comes from the servlet container, which honours
     * X-Forwarded-For only when the connection itself arrives from a trusted
     * proxy (`server.forward-headers-strategy: native`, application.yml) and
     * then takes the right-most address the proxy chain vouches for — never
     * the left-most one the client wrote.
     */
    private fun clientIp(request: HttpServletRequest): String? = request.remoteAddr
}
