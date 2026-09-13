package tech.bhrigu.almira.auth

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.common.EmailAddress
import tech.bhrigu.almira.security.SessionRevocationCache
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Taking an address off the alpha allowlist ends that tester's access, not only
 * their next sign-in. (Owner's decision, 2026-09: "Removing a tester from the
 * allowlist ends their sessions." Before this, the allowlist gated signing in
 * and nothing else, and a removed tester's sessions lasted 30 days.)
 *
 * **Who it applies to.** An account whose only identifier is an email address
 * that is not on the list, on a server where email sign-in is on — which today
 * always means allowlisted email. An account with a phone number is a phone
 * account and is never touched. On a server that does not offer email at all,
 * the allowlist is not in force and nothing here runs (not even a query).
 *
 * **When it takes effect.** The list is configuration, so it changes only when
 * the server starts. Three places enforce it, so no single one has to be
 * perfect:
 *
 *  1. **At startup** ([afterSingletonsInstantiated], before the web server
 *     accepts a connection): every live session of an account that is no longer
 *     listed is revoked — its refresh tokens in the database and its session id
 *     in [tech.bhrigu.almira.security.SessionRevocationCache], so the access
 *     token it holds stops working too — and each one is audited. Nobody has to
 *     make a request, or sign in, for it to end.
 *  2. **On every authenticated request** ([allowsRequest], from JwtAuthFilter):
 *     the account is checked (cached per account for [VERDICT_TTL]) and a
 *     session that should not exist is revoked on the spot. This is what covers
 *     a session the startup pass could not see — one signed in on an old server
 *     still running during a rolling deploy, or a startup pass that failed.
 *  3. **On refresh** ([allowsRefresh]), uncached, for the same reason.
 *
 * The list cannot change while a server runs, so an account's verdict cannot
 * go stale inside one; the cache only saves a query per request. On a single
 * server a session outlives a removal by nothing beyond the restart that makes
 * the removal. During a rolling deploy an old server, still holding the old
 * list, can sign the tester in and serve them until it stops; every new server
 * refuses that session on its first request or refresh.
 *
 * The allowlist stays in configuration rather than the database: see
 * docs/13 §5 for why, and known-issues 14 for what is left.
 */
@Component
class AlphaAllowlistAccess(
    private val channels: SignInChannels,
    private val repo: AuthRepository,
    private val sessionRevoker: SessionRevoker,
    private val audit: AuditService,
    private val revocations: SessionRevocationCache,
    /**
     * The startup pass has no user and reads every account's sessions: the owner
     * connection, asked for by name (DatabaseConfig). `users` and
     * `user_sessions` carry no row-level security today, so the app connection
     * would also see them — but a sweep that silently sees nothing is exactly
     * the failure this repository has had once, and AlphaAllowlistRemovalApiTest
     * checks which role this template really connects as.
     */
    @Qualifier("systemJdbcBypassingRls") private val system: NamedParameterJdbcTemplate,
) : SmartInitializingSingleton {

    private val log = LoggerFactory.getLogger(javaClass)

    private data class Verdict(val allowed: Boolean, val at: Long)
    private val verdicts = ConcurrentHashMap<UUID, Verdict>()

    /** Whether the allowlist decides anything on this server. */
    val inForce: Boolean get() = channels.isEnabled(OtpChannel.EMAIL)

    /**
     * True for an account the allowlist shuts out: email is its only way in,
     * and that address is not listed. A stored address that no longer
     * normalises is not listed either.
     */
    fun shutsOut(phone: String?, email: String?): Boolean =
        inForce && phone == null && email != null &&
            EmailAddress.canonicalOrNull(email)?.let(channels::isAllowed) != true

    /** For JwtAuthFilter. Costs nothing when the allowlist is not in force. */
    fun allowsRequest(userId: UUID, sessionId: UUID): Boolean {
        if (!inForce) return true
        val now = System.nanoTime()
        val cached = verdicts[userId]?.takeIf { now - it.at < VERDICT_TTL.toNanos() }
        val allowed = cached?.allowed ?: decide(userId).also {
            if (verdicts.size > MAX_CACHED) verdicts.clear()
            verdicts[userId] = Verdict(it, now)
        }
        if (!allowed) end(userId, sessionId, "request")
        return allowed
    }

    /** For AuthService.refresh: always read fresh. */
    fun allowsRefresh(userId: UUID, sessionId: UUID): Boolean {
        if (!inForce) return true
        val allowed = decide(userId)
        if (!allowed) end(userId, sessionId, "refresh")
        return allowed
    }

    /** A deleted account has no sessions worth deciding about here; other checks refuse it. */
    private fun decide(userId: UUID): Boolean {
        val user = repo.findById(userId) ?: return true
        return !shutsOut(user.phone, user.email)
    }

    private fun end(userId: UUID, sessionId: UUID, via: String) {
        // Audited inside the revocation's own transaction: a refresh throws
        // straight after this, and would roll an audit row in its own back.
        sessionRevoker.revokeNow(sessionId, REASON) { audited(userId, sessionId, via) }
    }

    private fun audited(userId: UUID, sessionId: UUID, via: String) {
        audit.record(
            householdId = null, actorUserId = userId, action = AUDIT_ACTION,
            entityType = "session", entityId = sessionId,
            // Never the address: the account id already says whose it was.
            diff = mapOf("reason" to REASON, "via" to via),
        )
        log.info("ended a session of an account no longer on the email allowlist (via {})", via)
    }

    /**
     * The startup pass. Runs after every singleton exists (so migrations have
     * run) and before the web server starts taking requests.
     *
     * A failure here is logged at ERROR and does not stop the server: the
     * request and refresh checks enforce the same rule without it, so refusing
     * to start would only take the alpha down for everyone else.
     */
    override fun afterSingletonsInstantiated() {
        if (!inForce) return
        try {
            sweep()
        } catch (e: Exception) {
            log.error(
                "EMAIL ALLOWLIST SWEEP FAILED ({}). Sessions of removed testers were not revoked at startup; " +
                    "each is still refused on its next request or refresh.",
                e.javaClass.simpleName,
            )
        }
    }

    /** Returns how many sessions it ended. */
    fun sweep(): Int {
        if (!inForce) return 0
        data class Live(val sessionId: UUID, val userId: UUID, val email: String)
        val live = system.query(
            """
            select s.id as session_id, u.id as user_id, u.email
            from user_sessions s join users u on u.id = s.user_id
            where s.revoked_at is null and s.expires_at > now()
              and u.deleted_at is null and u.phone is null and u.email is not null
            """.trimIndent(),
            emptyMap<String, Any>(),
        ) { rs, _ ->
            Live(
                rs.getObject("session_id", UUID::class.java),
                rs.getObject("user_id", UUID::class.java),
                rs.getString("email"),
            )
        }
        var ended = 0
        live.filter { shutsOut(null, it.email) }.forEach {
            // On the same owner connection as the read, and each statement its
            // own commit: a failure part-way leaves every earlier one ended.
            val params = mapOf("sid" to it.sessionId, "reason" to REASON)
            system.update(
                "update refresh_tokens set revoked_at = now() where session_id = :sid and revoked_at is null",
                params,
            )
            val updated = system.update(
                "update user_sessions set revoked_at = now(), revoked_reason = :reason " +
                    "where id = :sid and revoked_at is null",
                params,
            )
            // The access token it already holds, which the database cannot stop.
            revocations.revoke(it.sessionId)
            if (updated > 0) {
                ended++
                audited(it.userId, it.sessionId, "startup")
            }
        }
        log.info(
            "Email allowlist: {} live email-only session(s) checked, {} ended because the address is no longer listed",
            live.size, ended,
        )
        return ended
    }

    companion object {
        const val REASON = "removed_from_email_allowlist"
        const val AUDIT_ACTION = "auth.session_ended_not_allowlisted"
        val VERDICT_TTL: Duration = Duration.ofSeconds(60)
        private const val MAX_CACHED = 10_000
    }
}
