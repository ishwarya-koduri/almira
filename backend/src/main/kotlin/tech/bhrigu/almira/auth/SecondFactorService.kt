package tech.bhrigu.almira.auth

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.common.EmailAddress
import tech.bhrigu.almira.common.PhoneNumber
import tech.bhrigu.almira.crypto.UserSecretCipher
import tech.bhrigu.almira.security.JwtService
import tech.bhrigu.almira.security.RequestUserContext
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/** What a person has beyond the one-time code. */
data class SecondFactors(val authenticator: Boolean, val passkeys: Int, val recoveryCodesLeft: Int) {
    val any: Boolean get() = authenticator || passkeys > 0
}

/** A sign-in that passed its one-time code and waits for the second factor. */
data class PendingSignIn(
    val userId: UUID,
    val deviceName: String?,
    val userAgent: String?,
    val ip: String?,
)

/** Shown once, while the person sets their authenticator app up. */
data class TotpEnrolment(val secret: String, val otpauthUri: String, val expiresInSeconds: Long) {
    override fun toString() = "TotpEnrolment(secret=[redacted], expiresInSeconds=$expiresInSeconds)"
}

/**
 * The second factor: an authenticator app (RFC 6238) and recovery codes, and
 * the second step of a sign-in that needs one (docs/05 §2, docs/15 §5).
 *
 * **Why sign-in asks for it.** A one-time code proves who receives the texts
 * for a number, not who owns the account. A number that is disconnected is
 * reassigned to somebody else, and a SIM can be swapped. So once an account has
 * a second factor, a correct code is only the first step: it earns a
 * short-lived token, and the session is started only when the factor is given
 * too ([challenge], [complete]). Old clients cannot enrol a factor, so they
 * never meet the new step.
 *
 * **Identity while nobody is signed in.** The factor tables are under
 * row-level security as the person they belong to (V50). In the second step
 * of a sign-in there is no session, so [asUser] binds the person the first
 * factor proved — and only that person — to a transaction of its own, the way
 * the request filter binds a verified token. It is never called with an id the
 * caller typed.
 *
 * **What is counted.** Five wrong answers end a pending sign-in, and after ten
 * wrong answers in an hour an account takes no more — at sign-in or when
 * confirming it's you — so a one-time code that leaks does not become a long
 * run of guesses at a six-digit authenticator code.
 */
@Service
class SecondFactorService(
    private val repo: SecondFactorRepository,
    private val cipher: UserSecretCipher,
    private val redis: StringRedisTemplate,
    private val jwt: JwtService,
    private val userContext: RequestUserContext,
    private val audit: AuditService,
    private val notices: AccountNotices,
    transactionManager: PlatformTransactionManager,
) {
    private val random = SecureRandom()
    private val clock: Clock = Clock.systemUTC()

    private val ownTransaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    /** Runs [block] in a transaction of its own, under row-level security, as [userId]. */
    fun <T> asUser(userId: UUID, block: () -> T): T =
        userContext.runAs(userId, userContext.currentSessionId()) { ownTransaction.execute { block() } as T }

    fun factors(userId: UUID): SecondFactors = asUser(userId) {
        SecondFactors(
            authenticator = repo.totp(userId)?.confirmedAt != null,
            passkeys = repo.passkeys(userId).size,
            recoveryCodesLeft = repo.unusedRecoveryCodes(userId).size,
        )
    }

    // --- the second step of a sign-in ---------------------------------------------

    /**
     * The answer to a correct one-time code on an account with a second factor:
     * no session, a token for the second step, and which factors will do.
     *
     * 401, because the caller is not signed in yet. The token is 256 random
     * bits, kept only as a hash, and good for [PENDING_TTL].
     */
    fun challenge(userId: UUID, factors: SecondFactors, deviceName: String?, userAgent: String?, ip: String?): ApiException {
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val key = pendingKey(token)
        redis.opsForHash<String, String>().putAll(
            key,
            buildMap {
                put("userId", userId.toString())
                put("attempts", "0")
                deviceName?.let { put("deviceName", it.take(100)) }
                userAgent?.let { put("userAgent", it.take(500)) }
                ip?.let { put("ip", it) }
            },
        )
        redis.expire(key, PENDING_TTL)
        val methods = buildList {
            if (factors.authenticator) add("authenticator")
            if (factors.passkeys > 0) add("passkey")
            if (factors.recoveryCodesLeft > 0) add("recovery_code")
        }
        return ApiException(
            HttpStatus.UNAUTHORIZED, "second_factor_required",
            if (factors.authenticator) {
                "One more step. Enter the 6-digit code from your authenticator app."
            } else {
                "One more step. Confirm it's you with your passkey."
            },
            mapOf(
                "secondFactorToken" to token,
                "methods" to methods,
                "expiresInSeconds" to PENDING_TTL.seconds,
            ),
        )
    }

    /** Who a pending sign-in is for, without using it up. For a passkey's options. */
    fun pending(token: String): PendingSignIn {
        val entries = redis.opsForHash<String, String>().entries(pendingKey(token))
        if (entries.isEmpty()) throw pendingExpired()
        return PendingSignIn(
            userId = UUID.fromString(entries.getValue("userId")),
            deviceName = entries["deviceName"],
            userAgent = entries["userAgent"],
            ip = entries["ip"],
        )
    }

    /**
     * Ends a pending sign-in with [verify]'s verdict. True: the pending sign-in
     * is used up — by exactly one caller, however many race — and returned.
     * False: counted, and after [MAX_ATTEMPTS] it is gone.
     */
    fun complete(token: String, verify: (PendingSignIn) -> Boolean): PendingSignIn {
        val pending = pending(token)
        enforceAccountAllowance(pending.userId)
        if (verify(pending)) {
            if (redis.delete(pendingKey(token)) != true) throw pendingExpired()
            return pending
        }
        recordAccountMiss(pending.userId)
        val attempts = redis.execute(COUNT_MISS, listOf(pendingKey(token))) ?: -1L
        when {
            attempts < 0 -> throw pendingExpired()
            attempts >= MAX_ATTEMPTS -> {
                redis.delete(pendingKey(token))
                throw ApiException.badRequest(
                    "second_factor_locked",
                    "Too many tries. Start signing in again to get a new code.",
                )
            }
            else -> throw ApiException.badRequest(
                "second_factor_invalid",
                "That code doesn't match. ${MAX_ATTEMPTS - attempts} tries left.",
                mapOf("attemptsRemaining" to (MAX_ATTEMPTS - attempts).toInt()),
            )
        }
    }

    // --- checking a factor ----------------------------------------------------------

    /**
     * [verify] under the account's hourly allowance of wrong answers, for a
     * factor checked outside a pending sign-in (confirming it's you). A wrong
     * answer counts toward the same ten as a sign-in's.
     */
    fun guarded(userId: UUID, verify: () -> Boolean): Boolean {
        enforceAccountAllowance(userId)
        return verify().also { if (!it) recordAccountMiss(userId) }
    }

    /** True, once per code, when [code] is the authenticator's current code. */
    fun verifyAuthenticator(userId: UUID, code: String): Boolean = asUser(userId) {
        val row = repo.totp(userId)?.takeIf { it.confirmedAt != null } ?: return@asUser false
        val secret = cipher.open(userId, TOTP_FIELD, row.secretEnc)
        try {
            val step = Totp.matchingStep(secret, code.trim(), clock.instant(), row.lastUsedStep)
            step != null && repo.claimTotpStep(userId, step) == 1
        } finally {
            secret.fill(0)
        }
    }

    /** True, once per code, when [typed] is one of the person's unused recovery codes. */
    fun useRecoveryCode(userId: UUID, typed: String, ip: String? = null): Boolean {
        val normalised = RecoveryCodes.normalise(typed) ?: return false
        val used = asUser(userId) {
            val match = repo.unusedRecoveryCodes(userId).firstOrNull {
                RecoveryCodes.matches(normalised, it.salt, it.hash)
            }
            match != null && repo.useRecoveryCode(userId, match.id) == 1
        }
        if (used) {
            audit.record(null, userId, "auth.recovery_code_used", "user", userId, ip = ip)
            notices.changed(userId, AccountNotices.Change.RECOVERY_CODE_USED)
        }
        return used
    }

    // --- setting an authenticator up ---------------------------------------------------

    /**
     * A new secret, shown once. It is not a factor until a code from it is typed
     * back ([confirmAuthenticator]); until then it waits sealed in Redis, so
     * starting over never disturbs an authenticator that already works.
     */
    fun beginAuthenticator(user: UserRow): TotpEnrolment {
        val secret = Totp.newSecret()
        try {
            val sealed = cipher.seal(user.id, TOTP_FIELD, secret)
            redis.opsForValue().set(
                enrolKey(user.id),
                sealed.kekId + "|" + Base64.getEncoder().encodeToString(sealed.blob),
                ENROLMENT_TTL,
            )
            val label = user.phone?.let(PhoneNumber::mask) ?: user.email?.let(EmailAddress::mask) ?: "account"
            return TotpEnrolment(Base32.encode(secret), Totp.otpauthUri(secret, label), ENROLMENT_TTL.seconds)
        } finally {
            secret.fill(0)
        }
    }

    /**
     * Makes the authenticator a factor, replacing any earlier one. Returns new
     * recovery codes when the person has none left, and none otherwise: codes
     * already written down stay good.
     */
    fun confirmAuthenticator(userId: UUID, code: String, ip: String?): List<String> {
        val stored = redis.opsForValue().get(enrolKey(userId))
            ?: throw ApiException.badRequest(
                "authenticator_setup_expired",
                "Setting up took too long. Start again and scan the new code.",
            )
        val kekId = stored.substringBefore('|')
        val blob = Base64.getDecoder().decode(stored.substringAfter('|'))
        val secret = cipher.open(userId, TOTP_FIELD, blob)
        val codes = try {
            val step = Totp.matchingStep(secret, code.trim(), clock.instant(), null)
                ?: throw ApiException.badRequest(
                    "authenticator_code_invalid",
                    "That code doesn't match. Check the time on your phone is set automatically, and try the newest code.",
                )
            asUser(userId) {
                repo.saveConfirmedTotp(userId, blob, kekId, step)
                if (repo.unusedRecoveryCodes(userId).isEmpty()) newRecoveryCodes(userId) else emptyList()
            }
        } finally {
            secret.fill(0)
        }
        redis.delete(enrolKey(userId))
        audit.record(null, userId, "auth.authenticator_added", "user", userId, ip = ip)
        notices.changed(userId, AccountNotices.Change.AUTHENTICATOR_ADDED)
        return codes
    }

    fun removeAuthenticator(userId: UUID, ip: String?) {
        val removed = asUser(userId) {
            val n = repo.deleteTotp(userId)
            if (repo.passkeys(userId).isEmpty()) repo.deleteRecoveryCodes(userId)
            n
        }
        if (removed == 0) throw ApiException.notFound("There's no authenticator app on this account.")
        audit.record(null, userId, "auth.authenticator_removed", "user", userId, ip = ip)
        notices.changed(userId, AccountNotices.Change.AUTHENTICATOR_REMOVED)
    }

    /** Replaces every recovery code. Only for an account with a second factor to recover. */
    fun replaceRecoveryCodes(userId: UUID, ip: String?): List<String> {
        if (!factors(userId).any) {
            throw ApiException.badRequest(
                "no_second_factor",
                "Recovery codes are for an authenticator app or a passkey. Add one of those first.",
            )
        }
        val codes = asUser(userId) { newRecoveryCodes(userId) }
        audit.record(null, userId, "auth.recovery_codes_replaced", "user", userId, ip = ip)
        notices.changed(userId, AccountNotices.Change.RECOVERY_CODES_REPLACED)
        return codes
    }

    /** Called inside [asUser]. */
    internal fun newRecoveryCodes(userId: UUID): List<String> {
        val codes = RecoveryCodes.generate()
        repo.replaceRecoveryCodes(userId, codes.map(RecoveryCodes::store))
        return codes
    }

    // --- counting ------------------------------------------------------------------

    private fun enforceAccountAllowance(userId: UUID) {
        val key = accountMissKey(userId)
        val misses = redis.opsForValue().get(key)?.toLongOrNull() ?: 0
        if (misses >= MAX_MISSES_PER_ACCOUNT_PER_HOUR) {
            val retry = redis.getExpire(key, TimeUnit.SECONDS).coerceAtLeast(60)
            throw ApiException.tooManyRequests(
                "Too many incorrect codes for this account. Please try again later.", retry,
            )
        }
    }

    private fun recordAccountMiss(userId: UUID) {
        val key = accountMissKey(userId)
        val count = redis.opsForValue().increment(key) ?: 1
        if (count == 1L) redis.expire(key, Duration.ofHours(1))
    }

    private fun pendingKey(token: String) = "mfa:pending:${jwt.hash(token)}"
    private fun enrolKey(userId: UUID) = "mfa:totp-enrol:$userId"
    private fun accountMissKey(userId: UUID) = "mfa:miss:user:$userId"

    private fun pendingExpired() = ApiException.badRequest(
        "second_factor_expired",
        "That sign-in timed out. Start again and we'll send a new code.",
    )

    companion object {
        const val TOTP_FIELD = "user_totp_factors.secret_enc"
        const val MAX_ATTEMPTS = 5
        const val MAX_MISSES_PER_ACCOUNT_PER_HOUR = 10L
        val PENDING_TTL: Duration = Duration.ofMinutes(5)
        val ENROLMENT_TTL: Duration = Duration.ofMinutes(10)

        /** Counts a miss on a pending sign-in that still exists; -1 when it does not. */
        private val COUNT_MISS = DefaultRedisScript(
            """
            if redis.call('EXISTS', KEYS[1]) == 0 then
              return -1
            end
            return redis.call('HINCRBY', KEYS[1], 'attempts', 1)
            """.trimIndent(),
            Long::class.javaObjectType,
        )
    }
}
