package tech.almira.common

import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The one rate limiter in the product: a fixed window counted in Redis
 * (docs/16 "Redis for one-time codes and rate limits").
 *
 * It was the private inside of `auth/OtpService.kt`, where per-number and
 * per-network caps have always been counted this way. Guest
 * links needed the same thing, and a second mechanism beside this one would
 * mean two sets of keys, two answers to "am I limited", and two places to get
 * the 429 wrong. So the counting moved here and both callers share it.
 *
 * Why a counter rather than a token bucket: the caps here are of the form
 * "nobody real does this many in an hour", not "smooth the traffic out". INCR
 * with an expiry is atomic in one round trip, needs no script and no stored
 * state beyond the key, and its worst case — a window boundary letting through
 * up to twice the cap across two adjacent hours — is far below what any of
 * these caps is defending against.
 *
 * Deliberately NOT a Spring bean: the services that use it build their own
 * (`OtpService` is constructed directly in a dozen tests), and it holds nothing
 * but the template.
 */
class RateLimit(private val redis: StringRedisTemplate) {

    /**
     * Counts one use of [key] and refuses it if that takes the window past
     * [max]. The count moves first, so parallel callers can overshoot by at
     * most the number in flight and never by more — a check-then-increment
     * would let any number of them through at once.
     *
     * [message] is what the person reads; it must say the same thing for every
     * caller under one key, or the refusal itself becomes a signal about what
     * was asked for.
     */
    fun take(key: String, max: Int, window: Duration, message: String) {
        val count = redis.opsForValue().increment(key) ?: 1
        if (count == 1L) redis.expire(key, window)
        if (count > max) throw refusal(key, message)
    }

    /**
     * The refusal on its own, for a caller whose counting is not one-per-call:
     * sign-in's wrong-code cap spends a use only on a WRONG code, so it counts
     * elsewhere and reads the total here.
     */
    fun refusal(key: String, message: String): ApiException {
        // At least a minute, so a key whose expiry has just been read away
        // still tells the caller something usable rather than "retry now".
        val retry = redis.getExpire(key, TimeUnit.SECONDS).coerceAtLeast(60)
        return ApiException.tooManyRequests(message, retry)
    }
}
