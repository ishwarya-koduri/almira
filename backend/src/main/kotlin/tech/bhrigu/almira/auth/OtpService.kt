package tech.bhrigu.almira.auth

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.config.AlmiraProperties
import tech.bhrigu.almira.provider.FailureKind
import tech.bhrigu.almira.provider.ProviderCallFailed
import tech.bhrigu.almira.provider.ProviderCalls
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class OtpChallenge(
    val requestId: String,
    val expiresInSeconds: Long,
    val resendAfterSeconds: Long,
    /** Only when development was explicitly chosen AND the sender is the log one. */
    val developmentCode: String?,
) {
    /** A data class prints every field. This one must never print the code. */
    override fun toString() =
        "OtpChallenge(requestId=$requestId, expiresInSeconds=$expiresInSeconds, " +
            "resendAfterSeconds=$resendAfterSeconds, developmentCode=${if (developmentCode == null) "null" else "[redacted]"})"
}

/**
 * Phone OTP, per docs/09 §9.2.
 *
 * Challenges are namespaced by PURPOSE. Signing in and confirming yourself
 * before a number is revealed are different flows that can be in flight at the
 * same time on the same phone: sharing one Redis key would let a step-up code
 * silently invalidate a login code, and sharing the 30-second cooldown would
 * refuse a legitimate step-up moments after sign-in. The per-phone hourly cap
 * is deliberately NOT namespaced — that one exists to stop a number being
 * flooded with texts, and every flow's messages count toward it.
 *
 * Redis rather than Postgres because every value here is short-lived and
 * write-heavy: a code lives five minutes, counters reset hourly, and none of it
 * belongs in a backup.
 *
 * What it guarantees, each with a test in OtpServiceTest:
 *  - nothing is generated unless the sender can actually deliver it;
 *  - the code is echoed only in explicitly chosen development;
 *  - a stored code cannot be recovered from a Redis dump (keyed, not a bare hash);
 *  - a code works once, even when two correct attempts race;
 *  - wrong guesses are counted atomically, so racing guesses cannot exceed the cap;
 *  - resend cooldown, per-number and per-network hourly caps on requests, and a
 *    per-network hourly cap on wrong codes across all numbers;
 *  - lifetime, length and attempts are bounded, so configuration cannot quietly
 *    turn a six-digit five-minute code into something guessable;
 *  - a send that fails says which way it failed, and a failure that is ours
 *    does not lock the person out (see [request]).
 */
@Service
class OtpService(
    private val redis: StringRedisTemplate,
    private val sender: OtpSender,
    props: AlmiraProperties,
    /** SMS's timeout and retry policy. One-time codes go out through the SMS provider's account. */
    private val calls: ProviderCalls = ProviderCalls(props),
) {
    private val cfg = props.otp
    private val development = props.isDevelopment
    private val random = SecureRandom()

    /**
     * The stored value used to be an unsalted SHA-256 of the code. A six-digit
     * code has a million possible values, so the "hash" was a lookup away from
     * the code itself — anyone holding a Redis dump or a replica could read
     * every live code and sign in. It is now an HMAC under a key derived from
     * the JWT secret, over purpose, phone and code together: the dump alone
     * gives nothing, and a value moved to another number or flow does not match.
     */
    private val codeKey: SecretKeySpec = run {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(props.jwt.secret.toByteArray(Charsets.UTF_8), HMAC))
        SecretKeySpec(mac.doFinal("almira/otp-code/v1".toByteArray(Charsets.UTF_8)), HMAC)
    }

    init {
        // Bounds, not preferences. Each of these is a config value that, set
        // wrongly, turns sign-in into something an attacker can guess.
        require(cfg.length in 6..8) {
            "almira.otp.length must be 6 to 8 digits (is ${cfg.length}). Fewer is guessable."
        }
        require(!cfg.ttl.isNegative && !cfg.ttl.isZero && cfg.ttl <= MAX_TTL) {
            "almira.otp.ttl must be more than zero and at most $MAX_TTL (is ${cfg.ttl})."
        }
        require(cfg.maxAttempts in 1..10) {
            "almira.otp.max-attempts must be 1 to 10 (is ${cfg.maxAttempts})."
        }
    }

    /**
     * When the send fails, what happens to the challenge, the cooldown and the
     * hourly counts depends on whether the text could have gone out — because
     * two things must both hold: a person must not be locked out by our failure,
     * and an attacker must not get unthrottled requests out of it.
     *
     *  - **Timeout** (`otp_delivery_delayed`): it may still arrive. Everything
     *    stands — the challenge, so a late text still works; the cooldown and
     *    both counts, because as far as anyone can tell a text was sent.
     *  - **Rejected** (`otp_delivery_failed`), **unavailable**
     *    (`otp_provider_unavailable`), **insufficient balance**
     *    (`otp_service_unavailable`): nothing was delivered. The challenge is
     *    removed (no live code with nobody to receive it), the cooldown is
     *    lifted and the per-number count is given back, so fixing a typo or
     *    trying again once we are topped up is not refused as "too many". The
     *    per-network count is NOT given back: that is the limit that stops one
     *    network hammering this endpoint, and it holds whether or not our
     *    provider is working.
     *
     * No code, phone number or provider detail appears in any of these errors.
     */
    fun request(phone: String, ip: String?, purpose: String = LOGIN): OtpChallenge {
        // First, before a counter moves or a code exists. A sender that cannot
        // deliver would otherwise leave a live code with nobody to receive it —
        // and, for the log sender, a code in a production log.
        if (!sender.available) {
            throw ApiException.serviceUnavailable(
                "otp_unavailable",
                "Sign-in by text message isn't available on this server yet. No code was sent.",
            )
        }
        enforceCooldown(phone, purpose)
        enforceHourlyLimit("otp:rate:phone:$phone", cfg.maxPerHour, "phone")
        ip?.let { enforceHourlyLimit("otp:rate:ip:$it", cfg.maxPerIpPerHour, "network") }

        val code = (1..cfg.length).map { random.nextInt(10) }.joinToString("")
        val requestId = UUID.randomUUID().toString()

        redis.opsForHash<String, String>().putAll(
            challengeKey(phone, purpose),
            mapOf("hash" to mac(purpose, phone, code), "requestId" to requestId, "attempts" to "0"),
        )
        redis.expire(challengeKey(phone, purpose), cfg.ttl)
        // Redis rejects a zero or negative TTL outright, so a misconfigured
        // cooldown would turn every sign-in attempt into a 500 rather than
        // simply disabling the cooldown. Guard the config, not the user.
        if (!cfg.resendCooldown.isZero && !cfg.resendCooldown.isNegative) {
            redis.opsForValue().set(cooldownKey(phone, purpose), "1", cfg.resendCooldown)
        }

        try {
            calls.call(SMS, "otp") { sender.send(phone, code) }
        } catch (failure: ProviderCallFailed) {
            if (failure.kind != FailureKind.TIMEOUT) {
                // CONSUME removes it only if it is still this request's challenge.
                redis.execute(CONSUME, listOf(challengeKey(phone, purpose)), requestId)
                redis.delete(cooldownKey(phone, purpose))
                redis.execute(GIVE_BACK, listOf("otp:rate:phone:$phone"))
            }
            throw sendFailed(failure.kind, requestId)
        }

        return OtpChallenge(
            requestId = requestId,
            expiresInSeconds = cfg.ttl.seconds,
            resendAfterSeconds = cfg.resendCooldown.seconds,
            developmentCode = code.takeIf { development && sender.exposesCodeForDevelopment },
        )
    }

    /**
     * Consumes the challenge on success so a code can never be used twice.
     * Wrong codes burn an attempt; after [AlmiraProperties.Otp.maxAttempts] the
     * challenge is destroyed and a fresh one must be requested — this is what
     * makes a 6-digit code safe to use at all.
     *
     * Both outcomes finish in one atomic Redis step that also re-checks the
     * request id. Reading, comparing and then writing separately let two
     * correct attempts both succeed, and let a burst of parallel wrong guesses
     * all be judged against the same attempt count.
     */
    fun verify(phone: String, code: String, requestId: String?, purpose: String = LOGIN, ip: String? = null) {
        // Before the challenge is even read: a network over its allowance gets
        // no verdict at all, not even on a correct code, or the limit would only
        // slow a lucky guess down rather than stop it. The challenge is left
        // untouched, so its owner can still use it from their own network.
        val missKey = ip?.let { "otp:verify-miss:ip:$it" }
        missKey?.let(::enforceVerifyAllowance)

        val key = challengeKey(phone, purpose)
        val stored = redis.opsForHash<String, String>().entries(key)
        if (stored.isEmpty()) throw expired()
        val storedRequestId = stored["requestId"].orEmpty()
        if (requestId != null && storedRequestId != requestId) {
            throw ApiException.badRequest("otp_stale", "Please use the most recent code we sent.")
        }

        val matches = MessageDigest.isEqual(
            stored["hash"].orEmpty().toByteArray(Charsets.US_ASCII),
            mac(purpose, phone, code).toByteArray(Charsets.US_ASCII),
        )
        if (matches) {
            // 1 only if this call removed this exact challenge. A concurrent
            // winner, an expiry or a newer challenge all leave nothing to take.
            val consumed = redis.execute(CONSUME, listOf(key), storedRequestId) ?: 0L
            if (consumed != 1L) throw expired()
            return
        }

        val attempts = redis.execute(
            RECORD_MISS, listOf(key), storedRequestId, cfg.maxAttempts.toString(),
        ) ?: -1L
        if (attempts >= 0) missKey?.let(::recordNetworkMiss)
        when {
            attempts < 0 -> throw expired()
            attempts >= cfg.maxAttempts -> throw ApiException.badRequest(
                "otp_locked",
                "Too many tries. Ask for a new code to continue.",
            )
            else -> throw ApiException.badRequest(
                "otp_invalid",
                "That code doesn't match. ${cfg.maxAttempts - attempts} tries left.",
                mapOf("attemptsRemaining" to (cfg.maxAttempts - attempts).toInt()),
            )
        }
    }

    /** One code per way of failing, so a client can say the right thing. See docs/13. */
    private fun sendFailed(kind: FailureKind, requestId: String): ApiException = when (kind) {
        FailureKind.TIMEOUT -> ApiException(
            HttpStatus.GATEWAY_TIMEOUT, "otp_delivery_delayed",
            "Your code is taking longer than usual to send. If it arrives, it will work. " +
                "If it doesn't, you can ask for another in ${cfg.resendCooldown.seconds} seconds.",
            mapOf(
                "requestId" to requestId,
                "expiresInSeconds" to cfg.ttl.seconds,
                "resendAfterSeconds" to cfg.resendCooldown.seconds,
            ),
        )
        FailureKind.REJECTED -> ApiException(
            HttpStatus.UNPROCESSABLE_ENTITY, "otp_delivery_failed",
            "We couldn't deliver a code to that number. Please check it and try again.",
        )
        FailureKind.UNAVAILABLE -> ApiException(
            HttpStatus.SERVICE_UNAVAILABLE, "otp_provider_unavailable",
            "We couldn't reach our text message service just now, so no code was sent. " +
                "Please try again in a few minutes.",
        )
        FailureKind.INSUFFICIENT_BALANCE -> ApiException(
            HttpStatus.SERVICE_UNAVAILABLE, "otp_service_unavailable",
            "Sign-in codes can't be sent right now. This is a problem on our side, not with " +
                "your number, and we've been alerted. Please try again later.",
        )
    }

    private fun expired() = ApiException.badRequest(
        "otp_expired",
        "That code has expired. Ask for a new one and we'll text it right away.",
    )

    private fun enforceCooldown(phone: String, purpose: String) {
        val ttl = redis.getExpire(cooldownKey(phone, purpose), TimeUnit.SECONDS)
        if (ttl > 0) {
            throw ApiException.tooManyRequests(
                "We just sent a code. You can ask for another in $ttl seconds.", ttl,
            )
        }
    }

    private fun enforceVerifyAllowance(key: String) {
        val misses = redis.opsForValue().get(key)?.toLongOrNull() ?: 0
        if (misses >= cfg.maxVerifyFailuresPerIpPerHour) {
            val retry = redis.getExpire(key, TimeUnit.SECONDS).coerceAtLeast(60)
            throw ApiException.tooManyRequests(
                "Too many incorrect codes from this network. Please try again later.", retry,
            )
        }
    }

    /** INCR is atomic; parallel misses can overshoot by at most the number in flight. */
    private fun recordNetworkMiss(key: String) {
        val count = redis.opsForValue().increment(key) ?: 1
        if (count == 1L) redis.expire(key, Duration.ofHours(1))
    }

    private fun enforceHourlyLimit(key: String, max: Int, subject: String) {
        val count = redis.opsForValue().increment(key) ?: 1
        if (count == 1L) redis.expire(key, Duration.ofHours(1))
        if (count > max) {
            val retry = redis.getExpire(key, TimeUnit.SECONDS).coerceAtLeast(60)
            throw ApiException.tooManyRequests(
                "Too many sign-in attempts from this $subject. Please try again later.", retry,
            )
        }
    }

    private fun challengeKey(phone: String, purpose: String) = "otp:challenge:$purpose:$phone"
    private fun cooldownKey(phone: String, purpose: String) = "otp:cooldown:$purpose:$phone"

    private fun mac(purpose: String, phone: String, code: String): String {
        val mac = Mac.getInstance(HMAC)
        mac.init(codeKey)
        return HexFormat.of().formatHex(mac.doFinal("$purpose|$phone|$code".toByteArray(Charsets.UTF_8)))
    }

    companion object {
        const val LOGIN = "login"
        const val STEP_UP = "step_up"

        /** The provider configuration one-time codes are sent under. */
        private const val SMS = "sms"

        /** Returns one request to the hourly count, never below zero. */
        private val GIVE_BACK = DefaultRedisScript(
            """
            local n = tonumber(redis.call('GET', KEYS[1]) or '0')
            if n > 0 then
              return redis.call('DECR', KEYS[1])
            end
            return 0
            """.trimIndent(),
            Long::class.javaObjectType,
        )

        private const val HMAC = "HmacSHA256"
        private val MAX_TTL: Duration = Duration.ofMinutes(10)

        private val CONSUME = DefaultRedisScript(
            """
            if redis.call('HGET', KEYS[1], 'requestId') == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """.trimIndent(),
            Long::class.javaObjectType,
        )

        /** -1 when the challenge is gone or replaced; otherwise the new count. */
        private val RECORD_MISS = DefaultRedisScript(
            """
            if redis.call('HGET', KEYS[1], 'requestId') ~= ARGV[1] then
              return -1
            end
            local n = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
            if n >= tonumber(ARGV[2]) then
              redis.call('DEL', KEYS[1])
            end
            return n
            """.trimIndent(),
            Long::class.javaObjectType,
        )
    }
}
