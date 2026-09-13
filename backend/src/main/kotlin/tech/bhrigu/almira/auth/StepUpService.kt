package tech.bhrigu.almira.auth

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.common.EmailAddress
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Re-authentication before a full account or policy number is shown
 * (docs/05 §5).
 *
 * A long-lived session is convenient precisely because it does not keep asking
 * who you are — which is fine for reading a balance and not fine for revealing
 * the number that identifies a bank account. A borrowed unlocked phone should
 * not be enough.
 *
 * Elevation is granted to a SESSION, not to the account: proving yourself on
 * your phone does not elevate a browser session someone else is sitting in
 * front of. It lasts five minutes and lives only in Redis, so it cannot outlive
 * a restart or be reconstructed from a database dump.
 */
@Service
class StepUpService(
    private val redis: StringRedisTemplate,
    private val otp: OtpService,
    private val repo: AuthRepository,
    private val audit: AuditService,
    private val channels: SignInChannels,
) {

    fun request(userId: UUID, ip: String?): OtpChallenge {
        val user = repo.findById(userId) ?: throw ApiException.unauthorized()
        return when (val to = destination(user)) {
            is Destination.Phone -> otp.request(to.phone, ip, OtpService.STEP_UP)
            // Reported, unlike email sign-in: the caller is already signed in as
            // the owner of this address, so how the send went tells them nothing
            // they do not know — and "it didn't go" is worth saying.
            is Destination.Email -> otp.requestByEmail(to.email, ip, OtpService.STEP_UP, OtpDelivery.REPORTED)
        }
    }

    fun verify(userId: UUID, sessionId: UUID, code: String, requestId: String?) {
        val user = repo.findById(userId) ?: throw ApiException.unauthorized()
        // The same choice as [request] makes, from the same row. This used to be
        // `user.phone!!`, which was a 500 for every account without a number.
        when (val to = destination(user)) {
            is Destination.Phone -> otp.verify(to.phone, code, requestId, OtpService.STEP_UP)
            is Destination.Email -> otp.verifyByEmail(to.email, code, requestId, OtpService.STEP_UP)
        }
        redis.opsForValue().set(key(sessionId), userId.toString(), ELEVATION)
        audit.record(
            householdId = null, actorUserId = userId, action = "auth.step_up",
            entityType = "session", entityId = sessionId,
        )
    }

    /**
     * Consumes nothing: within the window a caller may reveal several numbers
     * without re-confirming each one. Each reveal is audited separately, so the
     * record shows what was actually looked at, not just that a step-up
     * happened.
     */
    fun requireElevated(userId: UUID, sessionId: UUID?) {
        if (sessionId == null) throw stepUpRequired(0)
        val holder = redis.opsForValue().get(key(sessionId))
        if (holder != userId.toString()) throw stepUpRequired(0)
    }

    fun remainingSeconds(sessionId: UUID?): Long =
        sessionId?.let { redis.getExpire(key(it), TimeUnit.SECONDS).coerceAtLeast(0) } ?: 0

    private fun stepUpRequired(retryAfter: Long) = ApiException(
        org.springframework.http.HttpStatus.FORBIDDEN,
        "step_up_required",
        "For your security, confirm it's you before we show the full number.",
        mapOf("retryAfterSeconds" to retryAfter),
    )

    private fun key(sessionId: UUID) = "session:elevated:$sessionId"

    private sealed interface Destination {
        data class Phone(val phone: String) : Destination
        data class Email(val email: String) : Destination
    }

    /**
     * Where a step-up code goes: the way this person could sign in here.
     *
     * Only channels this server offers — proving yourself by a channel that
     * sign-in has switched off would be a second, unreviewed way in. Phone
     * first when both are possible, because that is what every existing
     * account has always used.
     */
    private fun destination(user: UserRow): Destination {
        val phone = user.phone
        val email = user.email?.let { EmailAddress.canonicalOrNull(it) }
        return when {
            phone != null && channels.isEnabled(OtpChannel.PHONE) -> Destination.Phone(phone)
            email != null && channels.isEnabled(OtpChannel.EMAIL) -> Destination.Email(email)
            else -> throw ApiException.badRequest(
                "no_step_up_channel",
                "We can't confirm it's you on this account: there's no " +
                    "${channels.enabled.joinToString(" or ") { if (it == OtpChannel.EMAIL) "email address" else "phone number" }} " +
                    "on it that this server can send a code to.",
            )
        }
    }

    private companion object {
        val ELEVATION: Duration = Duration.ofMinutes(5)
    }
}
