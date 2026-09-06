package tech.bhrigu.almira.config

import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionDefinition
import tech.bhrigu.almira.security.RequestUserContext
import java.sql.Connection
import javax.sql.DataSource

/**
 * Binds the authenticated user to each transaction, for row-level security.
 *
 * The identity is set with `set_config(..., is_local => true)`, which PostgreSQL
 * discards at COMMIT or ROLLBACK. That property is the whole point:
 *
 *   - Nothing ever writes a session-scoped `app.user_id`, so there is no state
 *     for a connection to carry back into the pool. A connection that escapes
 *     the normal close path — evicted while idle, leaked by a bug, killed
 *     mid-request — cannot hand the next borrower the last borrower's identity,
 *     because PostgreSQL itself cleared it at transaction end.
 *   - The previous design set the identity at connection checkout and reset it
 *     at close. That was safe in practice (every checkout overwrites), but it
 *     depended on our own bookkeeping being correct on two paths instead of on
 *     the database's transaction semantics on one.
 *   - It is also one round trip fewer per request.
 *
 * The trade is real and worth stating: a statement that runs OUTSIDE a
 * transaction carries no identity, so every RLS predicate denies and the query
 * returns nothing. That fails closed, which is the right direction, but it fails
 * *quietly* — an unannotated read method returns an empty list rather than an
 * error. The guard against that is coverage: the end-to-end and integration
 * suites read through every endpoint and assert on the contents, so a missing
 * `@Transactional` surfaces as a failing test rather than an empty screen.
 */
class RlsTransactionManager(
    dataSource: DataSource,
    private val userContext: RequestUserContext,
) : DataSourceTransactionManager(dataSource) {

    override fun prepareTransactionalConnection(
        connection: Connection,
        definition: TransactionDefinition,
    ) {
        super.prepareTransactionalConnection(connection, definition)

        // Empty rather than skipped when unauthenticated: an unset GUC and an
        // empty one both deny, but writing it makes the intent explicit and
        // keeps the statement shape identical on every path.
        val userId = userContext.currentUserId()?.toString() ?: ""
        connection.prepareStatement(SET_LOCAL_USER).use { statement ->
            statement.setString(1, userId)
            statement.execute()
        }

        // A guest link narrows the same identity to the records it names. It is
        // transaction-scoped for the same reason the user id is: nothing can
        // leak into the next borrower of this connection, and a guest scope
        // cannot outlive the request that set it.
        val guestShareId = userContext.currentGuestShareId()?.toString() ?: ""
        connection.prepareStatement(SET_LOCAL_GUEST).use { statement ->
            statement.setString(1, guestShareId)
            statement.execute()
        }

        // And a guest transaction is read-only, in the database's own terms.
        //
        // The scope clamp lives on the READ policies, which left a gap worth
        // closing structurally rather than by remembering: a guest session
        // borrows the sharer's identity, so the write policies would have
        // happily let it edit the very record it was allowed to see. Adding a
        // guest clause to forty write policies would work until someone adds
        // the forty-first table. This cannot be forgotten, and it covers
        // statements nobody has written yet.
        if (guestShareId.isNotEmpty()) {
            connection.createStatement().use { it.execute("set transaction read only") }
        }
    }

    private companion object {
        /** is_local => true: discarded by PostgreSQL at commit or rollback. */
        const val SET_LOCAL_USER = "select set_config('app.user_id', ?, true)"
        const val SET_LOCAL_GUEST = "select set_config('app.guest_share_id', ?, true)"
    }
}
