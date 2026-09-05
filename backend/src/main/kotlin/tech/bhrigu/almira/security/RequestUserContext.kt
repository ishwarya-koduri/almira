package tech.bhrigu.almira.security

import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Who is making this request, and on which session.
 *
 * The user id is the single input to [tech.bhrigu.almira.config.RlsTransactionManager],
 * which stamps it onto every transaction as `app.user_id` — so this class is
 * effectively the handle the database uses to decide what the caller may see.
 *
 * It fails closed: when nothing is set, no identity reaches the database, every
 * policy predicate evaluates to NULL, and no rows come back.
 *
 * The session id is separate because some decisions are about the *session*
 * rather than the person — a step-up re-authentication elevates one device for a
 * few minutes, not the account everywhere.
 */
@Component
class RequestUserContext {

    data class Principal(val userId: UUID, val sessionId: UUID?)

    private val holder = ThreadLocal<Principal?>()

    fun set(userId: UUID?, sessionId: UUID? = null) {
        holder.set(userId?.let { Principal(it, sessionId) })
    }

    fun currentUserId(): UUID? = holder.get()?.userId

    fun currentSessionId(): UUID? = holder.get()?.sessionId

    fun require(): UUID =
        holder.get()?.userId ?: throw IllegalStateException("no authenticated user on this thread")

    fun requireSession(): UUID =
        holder.get()?.sessionId ?: throw IllegalStateException("no session on this thread")

    fun clear() = holder.remove()

    /** Runs [block] as [userId], restoring the previous principal afterwards. */
    fun <T> runAs(userId: UUID?, sessionId: UUID? = null, block: () -> T): T {
        val previous = holder.get()
        holder.set(userId?.let { Principal(it, sessionId) })
        try {
            return block()
        } finally {
            holder.set(previous)
        }
    }
}
