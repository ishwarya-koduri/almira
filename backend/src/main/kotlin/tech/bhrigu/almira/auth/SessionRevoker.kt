package tech.bhrigu.almira.auth

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.security.SessionRevocationCache
import java.util.UUID

/**
 * Revokes a session in its OWN transaction.
 *
 * This exists because of a bug worth remembering. Reuse detection used to
 * revoke the session and then throw, inline, inside the caller's transaction —
 * and the throw rolled the revocation straight back. The API said "we ended
 * that session for your security" while the database said nothing of the sort,
 * and the attacker's freshly rotated token kept working. Only the Redis half of
 * the revocation survived, because Redis does not participate in the rollback,
 * which is precisely what made the hole easy to miss.
 *
 * REQUIRES_NEW commits the revocation before the caller unwinds. It lives in a
 * separate bean because Spring's transaction proxy does not intercept a call a
 * class makes to itself — an inline `@Transactional(REQUIRES_NEW)` method would
 * have silently kept the old, broken behaviour.
 */
@Component
class SessionRevoker(
    private val repo: AuthRepository,
    private val revocations: SessionRevocationCache,
) {
    /**
     * True when this call ended the session; false when it had already ended.
     *
     * [onEnded] runs inside the same new transaction, only when this call ended
     * it — for the audit row, which would otherwise be written in the caller's
     * transaction and rolled back by the very throw that follows.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun revokeNow(sessionId: UUID, reason: String, onEnded: () -> Unit = {}): Boolean {
        repo.revokeAllForSession(sessionId)
        val ended = repo.revokeSessionById(sessionId, reason) > 0
        revocations.revoke(sessionId)
        if (ended) onEnded()
        return ended
    }
}
