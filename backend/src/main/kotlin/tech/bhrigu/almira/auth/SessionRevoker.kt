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
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun revokeNow(sessionId: UUID, reason: String) {
        repo.revokeAllForSession(sessionId)
        repo.revokeSessionById(sessionId, reason)
        revocations.revoke(sessionId)
    }
}
