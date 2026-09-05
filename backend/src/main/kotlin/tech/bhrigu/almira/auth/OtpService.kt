package tech.bhrigu.almira.auth

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.config.AlmiraProperties
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

data class OtpChallenge(
    val requestId: String,
    val expiresInSeconds: Long,
    val resendAfterSeconds: Long,
    /** Populated only by the development sender; null in every real environment. */
    val developmentCode: String?,
)

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
 * belongs in a backup. Codes are stored HASHED — a Redis dump reveals nothing
 * that can be replayed.
 */
@Service
class OtpService(
    private val redis: StringRedisTemplate,
    private val sender: OtpSender,
    props: AlmiraProperties,
) {
    private val cfg = props.otp
    private val random = SecureRandom()

    fun request(phone: String, ip: String?, purpose: String = LOGIN): OtpChallenge {
        enforceCooldown(phone, purpose)
        enforceHourlyLimit("otp:rate:phone:$phone", cfg.maxPerHour, "phone")
        ip?.let { enforceHourlyLimit("otp:rate:ip:$it", cfg.maxPerIpPerHour, "network") }

        val code = (1..cfg.length).map { random.nextInt(10) }.joinToString("")
        val requestId = UUID.randomUUID().toString()

        redis.opsForHash<String, String>().putAll(
            challengeKey(phone, purpose),
            mapOf("hash" to hash(code), "requestId" to requestId, "attempts" to "0"),
        )
        redis.expire(challengeKey(phone, purpose), cfg.ttl)
        // Redis rejects a zero or negative TTL outright, so a misconfigured
        // cooldown would turn every sign-in attempt into a 500 rather than
        // simply disabling the cooldown. Guard the config, not the user.
        if (!cfg.resendCooldown.isZero && !cfg.resendCooldown.isNegative) {
            redis.opsForValue().set(cooldownKey(phone, purpose), "1", cfg.resendCooldown)
        }

        sender.send(phone, code)

        return OtpChallenge(
            requestId = requestId,
            expiresInSeconds = cfg.ttl.seconds,
            resendAfterSeconds = cfg.resendCooldown.seconds,
            developmentCode = code.takeIf { sender.exposesCodeForDevelopment },
        )
    }

    /**
     * Consumes the challenge on success so a code can never be used twice.
     * Wrong codes burn an attempt; after [AlmiraProperties.Otp.maxAttempts] the
     * challenge is destroyed and a fresh one must be requested — this is what
     * makes a 6-digit code safe to use at all.
     */
    fun verify(phone: String, code: String, requestId: String?, purpose: String = LOGIN) {
        val key = challengeKey(phone, purpose)
        val stored = redis.opsForHash<String, String>().entries(key)
        if (stored.isEmpty()) {
            throw ApiException.badRequest(
                "otp_expired",
                "That code has expired. Ask for a new one and we'll text it right away.",
            )
        }
        if (requestId != null && stored["requestId"] != requestId) {
            throw ApiException.badRequest("otp_stale", "Please use the most recent code we sent.")
        }

        val attempts = (stored["attempts"]?.toIntOrNull() ?: 0) + 1
        if (!constantTimeEquals(stored["hash"].orEmpty(), hash(code))) {
            if (attempts >= cfg.maxAttempts) {
                redis.delete(key)
                throw ApiException.badRequest(
                    "otp_locked",
                    "Too many tries. Ask for a new code to continue.",
                )
            }
            redis.opsForHash<String, String>().put(key, "attempts", attempts.toString())
            throw ApiException.badRequest(
                "otp_invalid",
                "That code doesn't match. ${cfg.maxAttempts - attempts} tries left.",
                mapOf("attemptsRemaining" to (cfg.maxAttempts - attempts)),
            )
        }
        redis.delete(key)
    }

    private fun enforceCooldown(phone: String, purpose: String) {
        val ttl = redis.getExpire(cooldownKey(phone, purpose), TimeUnit.SECONDS)
        if (ttl > 0) {
            throw ApiException.tooManyRequests(
                "We just sent a code. You can ask for another in $ttl seconds.", ttl,
            )
        }
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

    private fun hash(code: String) =
        MessageDigest.getInstance("SHA-256").digest(code.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val LOGIN = "login"
        const val STEP_UP = "step_up"
    }

    /** Comparison time must not depend on how much of the code was right. */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
