package tech.bhrigu.almira.security

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/**
 * Makes "sign this device out" take effect now rather than in fifteen minutes.
 *
 * Access tokens are stateless by design — that is what keeps them cheap. The
 * cost is that a revoked session's existing access token stays cryptographically
 * valid until it expires, which is the wrong answer for a phone someone left in
 * a taxi (docs/05 §2).
 *
 * Checking the database on every request would fix it and undo the benefit. So
 * revoked session ids go into Redis with a TTL equal to the access-token
 * lifetime — after that the token has expired on its own and the entry is
 * pointless. One O(1) lookup per request, immediate revocation, no session
 * table in the hot path.
 */
@Component
class SessionRevocationCache(
    private val redis: StringRedisTemplate,
    jwtService: JwtService,
) {
    private val ttl: Duration = Duration.ofSeconds(jwtService.accessTtlSeconds + 60)

    fun revoke(sessionId: UUID) {
        redis.opsForValue().set(key(sessionId), "1", ttl)
    }

    fun isRevoked(sessionId: UUID): Boolean =
        redis.hasKey(key(sessionId))

    private fun key(sessionId: UUID) = "session:revoked:$sessionId"
}
