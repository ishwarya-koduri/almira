package tech.almira.auth

import tech.almira.measurement.ProductEvent
import tech.almira.measurement.ProductMeasurement
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.almira.audit.AuditService
import tech.almira.common.ApiException
import tech.almira.common.EmailAddress
import tech.almira.common.PhoneNumber
import tech.almira.security.JwtService
import tech.almira.security.SessionRevocationCache
import java.time.Instant
import java.util.UUID

data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val tokenType: String = "Bearer",
)

data class LoginResult(val tokens: TokenPair, val isNewUser: Boolean, val user: UserRow)

@Service
class AuthService(
    private val repo: AuthRepository,
    private val otp: OtpService,
    private val jwt: JwtService,
    private val audit: AuditService,
    private val revocations: SessionRevocationCache,
    private val sessionRevoker: SessionRevoker,
    private val channels: SignInChannels,
    private val alpha: AlphaAllowlistAccess,
    private val secondFactors: SecondFactorService,
    private val passkeys: PasskeyService,
    private val stepUp: StepUpService,
    private val notices: AccountNotices,
    private val measurement: ProductMeasurement,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun requestOtp(rawPhone: String, ip: String?): OtpChallenge {
        channels.requireEnabled(OtpChannel.PHONE)
        val phone = PhoneNumber.normalize(rawPhone)
        // Deliberately does not reveal whether the number is already registered.
        // Enumerating who has an account is itself a privacy leak.
        return otp.request(phone, ip)
    }

    @Transactional
    fun verifyOtp(
        rawPhone: String,
        code: String,
        requestId: String?,
        deviceName: String?,
        userAgent: String?,
        ip: String?,
    ): LoginResult {
        channels.requireEnabled(OtpChannel.PHONE)
        val phone = PhoneNumber.normalize(rawPhone)
        otp.verify(phone, code, requestId, ip = ip)

        val existing = repo.findByPhone(phone)
        existing?.let { requireNoSecondFactor(it, deviceName, userAgent, ip) }
        val user = existing ?: repo.createWithPhone(phone)
        return completeLogin(user, existing == null, deviceName, userAgent, ip)
            .also { log.info("login ok for {} (new={})", PhoneNumber.mask(phone), it.isNewUser) }
            .also { measurement.record(ProductEvent.SIGN_IN_COMPLETED, actor = it.user.id) }
    }

    /**
     * Sign-in by email, for the closed alpha: only allowlisted addresses.
     *
     * Enumeration is the whole difficulty. Every refusal that could depend on
     * the address — "not invited", "we couldn't deliver", even a response that
     * arrives a provider round trip later — tells a stranger who is testing a
     * finance app. So an address that is not allowed walks exactly the same
     * path as one that is: same validation, same cooldown, same per-address and
     * per-network counts, a stored challenge with a request id and a lifetime,
     * the same response, the same queued email. The only differences are
     * invisible from outside: the stored value matches no code
     * (OtpDelivery.DECOY), and the outbox worker drops that address's email
     * without calling the provider (SignInEmailOutbox). For the same reason
     * nothing on this path talks to a provider for any address, and
     * [emailDelivery] says `sent` at the same moment for every request, however
     * the worker decides (docs/13 §5 classifies every signal).
     *
     * Checked after normalisation, so `ASHA@Example.com ` is the listed address.
     */
    fun requestEmailOtp(rawEmail: String, ip: String?): OtpChallenge {
        channels.requireEnabled(OtpChannel.EMAIL)
        val email = EmailAddress.normalize(rawEmail)
        val delivery = if (channels.isAllowed(email)) OtpDelivery.DEFERRED else OtpDelivery.DECOY
        return otp.requestByEmail(email, ip, OtpService.LOGIN, delivery)
    }

    /** How the email for [requestId] went. Needs nothing but the id; see OtpService.emailDelivery. */
    fun emailDelivery(requestId: String): OtpDeliveryStatus {
        channels.requireEnabled(OtpChannel.EMAIL)
        return otp.emailDelivery(requestId)
    }

    /**
     * Creates the account on first success, with the email as its only
     * identifier. An unlisted address's challenge cannot reach here: it matches no code.
     */
    @Transactional
    fun verifyEmailOtp(
        rawEmail: String,
        code: String,
        requestId: String?,
        deviceName: String?,
        userAgent: String?,
        ip: String?,
    ): LoginResult {
        channels.requireEnabled(OtpChannel.EMAIL)
        val email = EmailAddress.normalize(rawEmail)
        otp.verifyByEmail(email, code, requestId, OtpService.LOGIN, ip)
        // Taken off the list between asking and answering: the code was real,
        // but the invitation is gone. Answered as an expired code, which is
        // what a listed address sees for a code it can no longer use.
        if (!channels.isAllowed(email)) {
            throw ApiException.badRequest(
                "otp_expired",
                "That code has expired. Ask for a new one and we'll email it right away.",
            )
        }

        val existing = repo.findByEmail(email)
        existing?.let { requireNoSecondFactor(it, deviceName, userAgent, ip) }
        val user = existing ?: repo.createWithEmail(email)
        return completeLogin(user, existing == null, deviceName, userAgent, ip)
            .also { log.info("login ok for {} by email (new={})", EmailAddress.mask(email), it.isNewUser) }
            .also { measurement.record(ProductEvent.SIGN_IN_COMPLETED, actor = it.user.id) }
    }

    /**
     * The recycled-number defence. A correct one-time code proves who receives
     * the texts (or the mail) today, which is not who owns the account: a
     * disconnected number is given to somebody else. When the account has a
     * second factor, the code earns only a short-lived token for the second
     * step, and no session (SecondFactorService). Accounts without one sign in
     * exactly as before.
     */
    private fun requireNoSecondFactor(user: UserRow, deviceName: String?, userAgent: String?, ip: String?) {
        val factors = secondFactors.factors(user.id)
        if (factors.any) {
            // In a transaction of its own: the throw below rolls back the
            // sign-in's transaction, and would take this row with it.
            secondFactors.asUser(user.id) {
                audit.record(null, user.id, "auth.second_factor_requested", "user", user.id, ip = ip, userAgent = userAgent)
            }
            throw secondFactors.challenge(user.id, factors, deviceName, userAgent, ip)
        }
    }

    /** The second step of a sign-in, with the authenticator app's code. */
    @Transactional
    fun completeWithAuthenticator(token: String, code: String): LoginResult =
        finishPending(secondFactors.complete(token) { secondFactors.verifyAuthenticator(it.userId, code) }, "authenticator")

    /** The second step of a sign-in, with a recovery code, which is used up. */
    @Transactional
    fun completeWithRecoveryCode(token: String, code: String): LoginResult =
        finishPending(secondFactors.complete(token) { secondFactors.useRecoveryCode(it.userId, code, it.ip) }, "recovery_code")

    /** The challenge for a passkey, for the person a pending sign-in is for. Uses nothing up. */
    fun startSecondFactorPasskey(token: String): PasskeyCeremony =
        passkeys.startAssertion(secondFactors.pending(token).userId, PasskeyService.Purpose.SIGN_IN)

    @Transactional
    fun completeWithPasskey(token: String, requestId: String, credential: Map<String, Any?>): LoginResult =
        finishPending(
            secondFactors.complete(token) {
                passkeys.verifyAssertion(it.userId, PasskeyService.Purpose.SIGN_IN, requestId, credential)
            },
            "passkey",
        )

    private fun finishPending(pending: PendingSignIn, factor: String): LoginResult {
        val user = repo.findById(pending.userId) ?: throw ApiException.unauthorized()
        return completeLogin(user, false, pending.deviceName, pending.userAgent, pending.ip, factor)
            .also { log.info("login ok with a second factor ({})", factor) }
    }

    private fun completeLogin(
        user: UserRow,
        isNewUser: Boolean,
        deviceName: String?,
        userAgent: String?,
        ip: String?,
        secondFactor: String? = null,
    ): LoginResult {
        repo.markLogin(user.id)
        val (tokens, sessionId) = startSession(user.id, deviceName, userAgent, ip)

        audit.record(
            householdId = null,
            actorUserId = user.id,
            action = if (isNewUser) "auth.signup" else "auth.login",
            entityType = "user",
            entityId = user.id,
            diff = secondFactor?.let { mapOf("secondFactor" to it) },
            ip = ip,
            userAgent = userAgent,
        )
        // Every sign-in to an existing account is news to its other devices.
        // Queued in this transaction, so a sign-in that fails tells nobody.
        if (!isNewUser) notices.newSignIn(user.id, sessionId, AccountNotices.describe(deviceName, userAgent))
        return LoginResult(tokens, isNewUser, user)
    }

    private fun startSession(
        userId: UUID,
        deviceName: String?,
        userAgent: String?,
        ip: String?,
    ): Pair<TokenPair, UUID> {
        val expiry = Instant.now().plus(jwt.refreshTtl)
        val sessionId = repo.createSession(userId, deviceName, userAgent, ip, expiry)
        val refresh = jwt.newRefreshToken()
        repo.storeRefresh(sessionId, jwt.hash(refresh), expiry)
        return TokenPair(
            accessToken = jwt.issueAccessToken(userId, sessionId),
            refreshToken = refresh,
            expiresInSeconds = jwt.accessTtlSeconds,
        ) to sessionId
    }

    // --- changing the phone number ----------------------------------------------------

    /**
     * Step one of changing (or adding) the number on an account: a code to the
     * NEW number, which proves the person receives its texts.
     *
     * Only from a signed-in session that has just confirmed it's you — by a
     * code to the channel the account already has, or by a second factor
     * (StepUpService). Losing the old number is not a way to lose the account,
     * and knowing a new one is not a way to take it.
     */
    fun requestPhoneChange(userId: UUID, sessionId: UUID?, rawPhone: String, ip: String?): OtpChallenge {
        channels.requireEnabled(OtpChannel.PHONE)
        stepUp.requireElevated(userId, sessionId, PHONE_CHANGE_STEP_UP)
        val phone = PhoneNumber.normalize(rawPhone)
        val user = repo.findById(userId) ?: throw ApiException.unauthorized()
        if (user.phone == phone) {
            throw ApiException.badRequest("phone_unchanged", "That's already the number on your account.")
        }
        return otp.request(phone, ip, OtpService.PHONE_CHANGE)
    }

    /**
     * Step two: the code from the new number. The number is changed, the
     * elevation it rested on is spent, and every device is told.
     *
     * A number that belongs to another account is refused only after its code
     * is proven, so the refusal says nothing to anyone but the person holding
     * that phone.
     */
    @Transactional
    fun verifyPhoneChange(
        userId: UUID,
        sessionId: UUID?,
        rawPhone: String,
        code: String,
        requestId: String?,
        ip: String?,
        userAgent: String?,
    ): UserRow {
        channels.requireEnabled(OtpChannel.PHONE)
        stepUp.requireElevated(userId, sessionId, PHONE_CHANGE_STEP_UP)
        val phone = PhoneNumber.normalize(rawPhone)
        otp.verify(phone, code, requestId, OtpService.PHONE_CHANGE, ip)
        val before = repo.findById(userId) ?: throw ApiException.unauthorized()
        if (before.phone == phone) {
            throw ApiException.badRequest("phone_unchanged", "That's already the number on your account.")
        }
        val updated = try {
            repo.updatePhone(userId, phone)
        } catch (e: DuplicateKeyException) {
            throw ApiException.conflict(
                "phone_in_use",
                "That number is already used by another Almira account. Sign in with it there, or use a different number.",
            )
        }
        audit.record(
            householdId = null, actorUserId = userId, action = "auth.phone_changed",
            entityType = "user", entityId = userId,
            diff = mapOf("from" to before.phone?.let(PhoneNumber::mask), "to" to PhoneNumber.mask(phone)),
            ip = ip, userAgent = userAgent,
        )
        sessionId?.let(stepUp::spend)
        notices.phoneChanged(userId, before.phone)
        log.info("phone number changed for user {}", userId)
        return updated
    }

    /**
     * Rotating refresh with reuse detection.
     *
     * Each refresh token is single-use. Presenting one that has already been
     * rotated means two parties hold it — the legitimate device and someone who
     * copied it — and we cannot tell which is which. The only safe response is
     * to distrust both: the whole session is revoked and the user signs in
     * again. Losing a session beats leaving a stolen one alive.
     */
    @Transactional
    fun refresh(refreshToken: String, ip: String?, userAgent: String?): TokenPair {
        val row = repo.findRefresh(jwt.hash(refreshToken))
            ?: throw ApiException.unauthorized("Your session has ended. Please sign in again.")

        if (row.rotatedTo != null) {
            // Committed in its own transaction -- the throw below would roll an
            // inline revocation back. See SessionRevoker. The audit row goes in
            // that transaction too: written here, the same throw rolled it back
            // and the reuse was never on the record (known-issues 14).
            sessionRevoker.revokeNow(row.sessionId, "refresh_token_reuse") {
                audit.record(
                    householdId = null, actorUserId = row.userId,
                    action = "auth.refresh_reuse_detected",
                    entityType = "session", entityId = row.sessionId,
                    ip = ip, userAgent = userAgent,
                )
            }
            log.warn("refresh token reuse on session {} — session revoked", row.sessionId)
            throw ApiException.unauthorized(
                "For your security we ended that session. Please sign in again.",
            )
        }
        if (row.revokedAt != null || row.sessionRevokedAt != null) {
            throw ApiException.unauthorized("That session was signed out. Please sign in again.")
        }
        if (row.expiresAt.isBefore(Instant.now())) {
            throw ApiException.unauthorized("Your session has expired. Please sign in again.")
        }
        // Revoked in its own transaction (SessionRevoker), so the throw keeps it.
        if (!alpha.allowsRefresh(row.userId, row.sessionId)) {
            throw ApiException.unauthorized(
                "This address is no longer part of the Almira alpha, so this device was signed out.",
            )
        }

        val next = jwt.newRefreshToken()
        val expiry = Instant.now().plus(jwt.refreshTtl)
        val newId = repo.storeRefresh(row.sessionId, jwt.hash(next), expiry)
        repo.markRotated(row.id, newId)
        repo.touchSession(row.sessionId)

        return TokenPair(
            accessToken = jwt.issueAccessToken(row.userId, row.sessionId),
            refreshToken = next,
            expiresInSeconds = jwt.accessTtlSeconds,
        )
    }

    @Transactional
    fun logout(userId: UUID, sessionId: UUID) {
        repo.revokeAllForSession(sessionId)
        repo.revokeSession(userId, sessionId, "logout")
        revocations.revoke(sessionId)
        audit.record(
            householdId = null, actorUserId = userId, action = "auth.logout",
            entityType = "session", entityId = sessionId,
        )
    }

    fun sessions(userId: UUID) = repo.listSessions(userId)

    @Transactional
    fun revokeSession(userId: UUID, sessionId: UUID) {
        val updated = repo.revokeSession(userId, sessionId, "revoked_by_user")
        if (updated == 0) throw ApiException.notFound("We couldn't find that session.")
        repo.revokeAllForSession(sessionId)
        // Kills the access token too, not just the refresh token: a lost phone
        // should stop working now, not in fifteen minutes.
        revocations.revoke(sessionId)
        audit.record(
            householdId = null, actorUserId = userId, action = "auth.session_revoked",
            entityType = "session", entityId = sessionId,
        )
    }

    fun me(userId: UUID): UserRow =
        repo.findById(userId) ?: throw ApiException.unauthorized()

    fun updatePreferences(userId: UUID, fullName: String?, defaultVisibility: String?): UserRow {
        if (defaultVisibility != null && defaultVisibility !in setOf("private", "household")) {
            throw ApiException.badRequest(
                "visibility_invalid", "Choose either private or household.",
            )
        }
        return repo.updatePreferences(userId, fullName, defaultVisibility)
    }

    private companion object {
        const val PHONE_CHANGE_STEP_UP = "For your security, confirm it's you before changing your phone number."
    }
}
