package tech.bhrigu.almira.config

import org.springframework.jdbc.datasource.AbstractDataSource
import tech.bhrigu.almira.security.RequestUserContext
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import javax.sql.DataSource

/**
 * Binds the authenticated user to every database connection.
 *
 * PostgreSQL row-level security policies read `current_setting('app.user_id')`.
 * Something has to put the value there, and it has to be impossible to forget:
 * a service method that forgets would not fail, it would silently query as
 * nobody — or worse, inherit whoever used the pooled connection last.
 *
 * So it happens at connection checkout rather than in application code:
 *
 *   checkout -> set app.user_id = <current user, or '' when unauthenticated>
 *   close    -> reset it to '' before the connection returns to the pool
 *
 * The reset is the important half. Without it, a pooled connection would carry
 * one user's identity into the next request that borrowed it — a cross-account
 * data leak with no code path you could point at.
 *
 * Unauthenticated requests get an empty string, not a skipped statement. An
 * unset GUC would make `current_setting(..., true)` return NULL, every policy
 * evaluate to NULL, and every query return zero rows: fail closed either way,
 * but being explicit makes it deliberate rather than incidental.
 *
 * Cost is one extra round trip per checkout. That is a fair price for a
 * guarantee that cannot be forgotten.
 */
class RlsDataSource(
    private val delegate: DataSource,
    private val userContext: RequestUserContext,
) : AbstractDataSource() {

    override fun getConnection(): Connection = prepare(delegate.connection)

    override fun getConnection(username: String, password: String): Connection =
        prepare(delegate.getConnection(username, password))

    private fun prepare(connection: Connection): Connection {
        val userId = userContext.currentUserId()?.toString() ?: ""
        applySetting(connection, userId)
        return guard(connection)
    }

    private fun applySetting(connection: Connection, value: String) {
        connection.prepareStatement(SET_USER).use { statement ->
            statement.setString(1, value)
            statement.execute()
        }
    }

    /** Intercepts close() so the identity never outlives the borrow. */
    private fun guard(target: Connection): Connection =
        Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            if (method.name == "close" && args.isNullOrEmpty()) {
                runCatching { if (!target.isClosed) applySetting(target, "") }
                target.close()
                null
            } else {
                try {
                    method.invoke(target, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        } as Connection

    private companion object {
        const val SET_USER = "select set_config('app.user_id', ?, false)"
    }
}
