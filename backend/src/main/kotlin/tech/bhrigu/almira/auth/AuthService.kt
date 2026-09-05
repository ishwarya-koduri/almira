package tech.bhrigu.almira.auth

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.common.PhoneNumber
import tech.bhrigu.almira.security.JwtService
import tech.bhrigu.almira.security.SessionRevocationCache
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
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun requestOtp(rawPhone: String, ip: String?): OtpChallenge {
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
        val phone = PhoneNumber.normalize(rawPhone)
        otp.verify(phone, code, requestId)

        val existing = repo.findByPhone(phone)
        val user = existing ?: repo.createWithPhone(phone)
        val isNewUser = existing == null

        repo.markLogin(user.id)
        val tokens = startSession(user.id, deviceName, userAgent, ip)

        audit.record(
            householdId = null,
            actorUserId = user.id,
            action = if (isNewUser) "auth.signup" else "auth.login",
            entityType = "user",
            entityId = user.id,
            ip = ip,
            userAgent = userAgent,
        )
        log.info("login ok for {} (new={})", PhoneNumber.mask(phone), isNewUser)
        return LoginResult(tokens, isNewUser, user)
    }

    private fun startSession(
        userId: UUID,
        deviceName: String?,
        userAgent: String?,
        ip: String?,
    ): TokenPair {
        val expiry = Instant.now().plus(jwt.refreshTtl)
        val sessionId = repo.createSession(userId, deviceName, userAgent, ip, expiry)
        val refresh = jwt.newRefreshToken()
        repo.storeRefresh(sessionId, jwt.hash(refresh), expiry)
        return TokenPair(
            accessToken = jwt.issueAccessToken(userId, sessionId),
            refreshToken = refresh,
            expiresInSeconds = jwt.accessTtlSeconds,
        )
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
            // inline revocation back. See SessionRevoker.
            sessionRevoker.revokeNow(row.sessionId, "refresh_token_reuse")
            audit.record(
                householdId = null, actorUserId = row.userId,
                action = "auth.refresh_reuse_detected",
                entityType = "session", entityId = row.sessionId,
                ip = ip, userAgent = userAgent,
            )
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
}
