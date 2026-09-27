package tech.almira.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tech.almira.security.RequestUserContext
import java.time.Instant
import java.util.UUID

// --- requests ---------------------------------------------------------------

data class AuthenticatorCodeBody(
    @field:Pattern(regexp = "^[0-9]{6}$", message = "Enter the 6-digit code from your authenticator app")
    val code: String,
) {
    override fun toString() = "AuthenticatorCodeBody(code=[redacted])"
}

data class RecoveryCodeBody(
    @field:NotBlank(message = "Enter one of your recovery codes")
    @field:Size(max = 40)
    val code: String,
) {
    override fun toString() = "RecoveryCodeBody(code=[redacted])"
}

data class PasskeyRegistrationBody(
    @field:NotBlank @field:Size(max = 100)
    val requestId: String,
    @field:NotNull
    val credential: Map<String, Any?>,
    @field:Size(max = 60, message = "Keep the name under 60 characters")
    val name: String? = null,
)

data class PasskeyAssertionBody(
    @field:NotBlank @field:Size(max = 100)
    val requestId: String,
    @field:NotNull
    val credential: Map<String, Any?>,
)

data class PhoneChangeRequestBody(
    @field:NotBlank(message = "Enter your new phone number")
    val phone: String,
)

data class PhoneChangeVerifyBody(
    @field:NotBlank(message = "Enter your new phone number")
    val phone: String,
    @field:Pattern(regexp = "^[0-9]{4,8}$", message = "Enter the code we texted you")
    val code: String,
    val requestId: String? = null,
) {
    override fun toString() = "PhoneChangeVerifyBody(phone=${tech.almira.common.PhoneNumber.mask(phone)}, " +
        "code=[redacted], requestId=$requestId)"
}

// --- responses --------------------------------------------------------------

data class PasskeyResponse(val id: UUID, val name: String, val createdAt: Instant, val lastUsedAt: Instant?)

data class SignInMethodsResponse(
    /** Masked, e.g. `+91····4321`. Null when the account has no number. */
    val phone: String?,
    /** Whether that number can be used to sign in on this server. */
    val phoneSignIn: Boolean,
    val email: String?,
    val emailSignIn: Boolean,
    val authenticator: Boolean,
    val passkeys: List<PasskeyResponse>,
    /** False when this server has no passkey domain configured. */
    val passkeysAvailable: Boolean,
    val recoveryCodesLeft: Int,
    /** Ways in, counted: phone and email when usable here, the authenticator, each passkey. */
    val count: Int,
    /** The number the card asks for. */
    val minimum: Int,
    val meetsMinimum: Boolean,
)

data class AuthenticatorSetupResponse(
    /** Base32, for typing into an app that cannot scan. Shown once. */
    val secret: String,
    /** For the QR code. */
    val otpauthUri: String,
    val expiresInSeconds: Long,
) {
    override fun toString() = "AuthenticatorSetupResponse(secret=[redacted])"
}

data class RecoveryCodesResponse(
    /** Shown once, never again. Empty when the codes already written down still stand. */
    val recoveryCodes: List<String>,
) {
    override fun toString() = "RecoveryCodesResponse([${recoveryCodes.size} codes])"
}

data class PasskeyCeremonyResponse(
    val requestId: String,
    /** Pass to `navigator.credentials.create()` or `.get()` after decoding its base64url fields. */
    val options: Map<String, Any?>,
)

data class PasskeyAddedResponse(val passkey: PasskeyResponse, val recoveryCodes: List<String>) {
    override fun toString() = "PasskeyAddedResponse(passkey=${passkey.id}, [${recoveryCodes.size} codes])"
}

/**
 * How someone signs in, and changing it (docs/05 §2).
 *
 * Every change here is a change to how the account is opened, so every change
 * needs a session that has just confirmed it's you, and adding, replacing or
 * removing a second factor on an account that already has one needs it
 * confirmed WITH that factor (StepUpService.requireElevatedBySecondFactor).
 * Each is audited and each tells every device (AccountNotices).
 *
 * Secrets and recovery codes are answered with `Cache-Control: no-store`: they
 * are shown once and must not live on in a browser or proxy cache.
 */
@RestController
@RequestMapping("/api/v1/auth")
class SignInMethodsController(
    private val methods: SignInMethodsService,
    private val factors: SecondFactorService,
    private val passkeys: PasskeyService,
    private val stepUp: StepUpService,
    private val auth: AuthService,
    private val userContext: RequestUserContext,
) {

    @GetMapping("/sign-in-methods")
    fun signInMethods(): SignInMethodsResponse = methods.of(userContext.require()).let { m ->
        SignInMethodsResponse(
            phone = m.phone, phoneSignIn = m.phoneSignIn, email = m.email, emailSignIn = m.emailSignIn,
            authenticator = m.authenticator, passkeys = m.passkeys.map { it.toResponse() },
            passkeysAvailable = m.passkeysAvailable, recoveryCodesLeft = m.recoveryCodesLeft,
            count = m.count, minimum = SignInMethods.MINIMUM, meetsMinimum = m.meetsMinimum,
        )
    }

    // --- authenticator app -----------------------------------------------------------

    @PostMapping("/authenticator")
    fun beginAuthenticator(): ResponseEntity<AuthenticatorSetupResponse> {
        val userId = userContext.require()
        stepUp.requireElevatedBySecondFactor(userId, userContext.currentSessionId(), CHANGE_MESSAGE)
        val enrolment = factors.beginAuthenticator(auth.me(userId))
        return noStore(AuthenticatorSetupResponse(enrolment.secret, enrolment.otpauthUri, enrolment.expiresInSeconds))
    }

    @PostMapping("/authenticator/confirm")
    fun confirmAuthenticator(
        @RequestBody @Valid body: AuthenticatorCodeBody,
        request: HttpServletRequest,
    ): ResponseEntity<RecoveryCodesResponse> =
        noStore(RecoveryCodesResponse(factors.confirmAuthenticator(userContext.require(), body.code, request.remoteAddr)))

    @DeleteMapping("/authenticator")
    fun removeAuthenticator(request: HttpServletRequest): ResponseEntity<Void> {
        val userId = userContext.require()
        stepUp.requireElevatedBySecondFactor(userId, userContext.currentSessionId(), CHANGE_MESSAGE)
        if (!factors.factors(userId).authenticator) {
            throw tech.almira.common.ApiException.notFound("There's no authenticator app on this account.")
        }
        methods.requireKeepsMinimum(userId)
        factors.removeAuthenticator(userId, request.remoteAddr)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/recovery-codes")
    fun replaceRecoveryCodes(request: HttpServletRequest): ResponseEntity<RecoveryCodesResponse> {
        val userId = userContext.require()
        stepUp.requireElevatedBySecondFactor(userId, userContext.currentSessionId(), CHANGE_MESSAGE)
        return noStore(RecoveryCodesResponse(factors.replaceRecoveryCodes(userId, request.remoteAddr)))
    }

    // --- passkeys ----------------------------------------------------------------------

    @PostMapping("/passkeys/options")
    fun passkeyRegistrationOptions(): PasskeyCeremonyResponse {
        val userId = userContext.require()
        stepUp.requireElevatedBySecondFactor(userId, userContext.currentSessionId(), CHANGE_MESSAGE)
        return passkeys.startRegistration(auth.me(userId)).let { PasskeyCeremonyResponse(it.requestId, it.options) }
    }

    @PostMapping("/passkeys")
    fun addPasskey(
        @RequestBody @Valid body: PasskeyRegistrationBody,
        request: HttpServletRequest,
    ): ResponseEntity<PasskeyAddedResponse> {
        val added = passkeys.finishRegistration(
            userContext.require(), body.requestId, body.credential, body.name, request.remoteAddr,
        )
        return noStore(PasskeyAddedResponse(added.passkey.toResponse(), added.recoveryCodes))
    }

    @DeleteMapping("/passkeys/{id}")
    fun removePasskey(@PathVariable id: UUID, request: HttpServletRequest): ResponseEntity<Void> {
        val userId = userContext.require()
        stepUp.requireElevatedBySecondFactor(userId, userContext.currentSessionId(), CHANGE_MESSAGE)
        // Someone else's passkey and no passkey read the same, before any other answer.
        if (passkeys.list(userId).none { it.id == id }) {
            throw tech.almira.common.ApiException.notFound("We couldn't find that passkey.")
        }
        methods.requireKeepsMinimum(userId)
        passkeys.remove(userId, id, request.remoteAddr)
        return ResponseEntity.noContent().build()
    }

    // --- confirming it's you with a second factor -------------------------------------------

    @PostMapping("/step-up/authenticator")
    fun stepUpWithAuthenticator(@RequestBody @Valid body: AuthenticatorCodeBody): StepUpStatusResponse {
        val sessionId = userContext.requireSession()
        stepUp.verifyAuthenticator(userContext.require(), sessionId, body.code)
        return StepUpStatusResponse(true, stepUp.remainingSeconds(sessionId))
    }

    @PostMapping("/step-up/recovery-code")
    fun stepUpWithRecoveryCode(
        @RequestBody @Valid body: RecoveryCodeBody,
        request: HttpServletRequest,
    ): StepUpStatusResponse {
        val sessionId = userContext.requireSession()
        stepUp.verifyRecoveryCode(userContext.require(), sessionId, body.code, request.remoteAddr)
        return StepUpStatusResponse(true, stepUp.remainingSeconds(sessionId))
    }

    @PostMapping("/step-up/passkey/options")
    fun stepUpPasskeyOptions(): PasskeyCeremonyResponse =
        stepUp.startPasskey(userContext.require()).let { PasskeyCeremonyResponse(it.requestId, it.options) }

    @PostMapping("/step-up/passkey")
    fun stepUpWithPasskey(@RequestBody @Valid body: PasskeyAssertionBody): StepUpStatusResponse {
        val sessionId = userContext.requireSession()
        stepUp.verifyPasskey(userContext.require(), sessionId, body.requestId, body.credential)
        return StepUpStatusResponse(true, stepUp.remainingSeconds(sessionId))
    }

    // --- phone number ----------------------------------------------------------------------

    @PostMapping("/phone/request")
    fun requestPhoneChange(
        @RequestBody @Valid body: PhoneChangeRequestBody,
        request: HttpServletRequest,
    ): OtpChallengeResponse = auth.requestPhoneChange(
        userContext.require(), userContext.currentSessionId(), body.phone, request.remoteAddr,
    ).let { OtpChallengeResponse(it.requestId, it.expiresInSeconds, it.resendAfterSeconds, it.developmentCode, it.channel.key) }

    @PostMapping("/phone/verify")
    fun verifyPhoneChange(
        @RequestBody @Valid body: PhoneChangeVerifyBody,
        request: HttpServletRequest,
    ): MeResponse = auth.verifyPhoneChange(
        userContext.require(), userContext.currentSessionId(), body.phone, body.code, body.requestId,
        request.remoteAddr, request.getHeader("User-Agent"),
    ).let {
        MeResponse(it.id, it.phone, it.email, it.fullName, it.defaultVisibility, it.locale, it.currencyPref)
    }

    private fun PasskeyRow.toResponse() = PasskeyResponse(id, name, createdAt, lastUsedAt)

    private fun <T> noStore(body: T): ResponseEntity<T> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)

    private companion object {
        const val CHANGE_MESSAGE = "For your security, confirm it's you before changing how you sign in."
    }
}
