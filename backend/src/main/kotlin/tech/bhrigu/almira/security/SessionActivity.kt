package tech.bhrigu.almira.security

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * "When was this person last actually here?"
 *
 * Two things depend on the answer, and one of them is a safety property:
 * the sessions list a person reviews when they suspect a lost device, and the
 * emergency-access window, which opens only if the person it concerns has not
 * used Almira since somebody asked about them. Signing in is the plainest
 * possible statement that someone is reachable, and it should stop that clock
 * without them having to understand what a veto is.
 *
 * Refreshing a token was the only signal before this, which is far too coarse:
 * an access token lasts fifteen minutes, so somebody reading their records for
 * ten of them looked, to the database, exactly like somebody who had vanished.
 *
 * Written at most once every few minutes per session — a row per request would
 * turn every read into a write, which is a poor trade for a timestamp nobody
 * reads to the second.
 */
@Component
class SessionActivity(private val jdbc: NamedParameterJdbcTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val lastWritten = ConcurrentHashMap<UUID, Instant>()

    fun seen(sessionId: UUID) {
        val now = Instant.now()
        val previous = lastWritten[sessionId]
        if (previous != null && Duration.between(previous, now) < THROTTLE) return
        lastWritten[sessionId] = now

        // Never fails a request. Losing a "last seen" is a cosmetic problem;
        // failing the read somebody actually asked for is not.
        runCatching {
            jdbc.update(
                "update user_sessions set last_used_at = now() where id = :id and revoked_at is null",
                mapOf("id" to sessionId),
            )
        }.onFailure { log.debug("could not record session activity: {}", it.javaClass.simpleName) }

        // The map is bounded by pruning entries that can no longer be throttling
        // anything: a session unseen for an hour will write on its next request
        // regardless.
        if (lastWritten.size > MAX_TRACKED) {
            lastWritten.entries.removeIf { Duration.between(it.value, now) > Duration.ofHours(1) }
        }
    }

    private companion object {
        val THROTTLE: Duration = Duration.ofMinutes(2)
        const val MAX_TRACKED = 10_000
    }
}
