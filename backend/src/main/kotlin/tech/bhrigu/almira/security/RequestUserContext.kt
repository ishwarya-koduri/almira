package tech.bhrigu.almira.security

import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The authenticated user for the current thread.
 *
 * This is the single input to [tech.bhrigu.almira.config.RlsDataSource], which
 * stamps it onto every database connection as the `app.user_id` GUC. Every
 * row-level security policy reads that GUC, so this class is effectively the
 * handle the database uses to decide what the caller may see.
 *
 * It fails closed: when nothing is set, `app.user_id` is empty, every policy
 * predicate evaluates to NULL, and the database returns no rows at all.
 */
@Component
class RequestUserContext {

    private val holder = ThreadLocal<UUID?>()

    fun set(userId: UUID?) = holder.set(userId)

    fun currentUserId(): UUID? = holder.get()

    fun require(): UUID =
        holder.get() ?: throw IllegalStateException("no authenticated user on this thread")

    fun clear() = holder.remove()

    /** Runs [block] as [userId], restoring the previous value afterwards. */
    fun <T> runAs(userId: UUID?, block: () -> T): T {
        val previous = holder.get()
        holder.set(userId)
        try {
            return block()
        } finally {
            holder.set(previous)
        }
    }
}
