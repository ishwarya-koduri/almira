package tech.bhrigu.almira.lifecycle

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import tech.bhrigu.almira.document.DocumentStorage

/**
 * Document bytes an erasure or a move still has to delete (V109).
 *
 * [queue] is called inside the transaction that deletes the rows, so the key is
 * recorded exactly when nothing else names it any more. [deleteQueued] runs
 * after that commits, and again on every sweep: a key leaves the table only once
 * storage has deleted it, so a storage outage delays the erasure and does not
 * lose it.
 *
 * Owner connection only; the table has no policy and no grant.
 */
class PendingStorageDeletions(
    private val jdbc: NamedParameterJdbcTemplate,
    private val storage: DocumentStorage,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun queue(keys: Collection<String>) {
        if (keys.isEmpty()) return
        jdbc.batchUpdate(
            "insert into pending_storage_deletions (storage_key) values (:key) on conflict (storage_key) do nothing",
            keys.distinct().map { mapOf("key" to it) }.toTypedArray(),
        )
    }

    /** Deletes [keys], or every queued key when null. Returns how many are gone. */
    fun deleteQueued(keys: Collection<String>? = null): Int {
        val queued = if (keys == null) {
            jdbc.query("select storage_key from pending_storage_deletions order by queued_at", emptyMap<String, Any>()) { rs, _ ->
                rs.getString("storage_key")
            }
        } else {
            keys.distinct()
        }
        var deleted = 0
        queued.forEach { key ->
            // A key a document row names again is not an erased document's.
            val stillNamed = jdbc.queryForObject(
                "select exists (select 1 from documents where storage_key = :key)", mapOf("key" to key), Boolean::class.java,
            ) == true
            if (!stillNamed) {
                val failure = runCatching { storage.delete(key) }.exceptionOrNull()
                if (failure != null) {
                    jdbc.update(
                        """
                        update pending_storage_deletions
                           set attempts = attempts + 1, last_attempt_at = now()
                         where storage_key = :key
                        """.trimIndent(),
                        mapOf("key" to key),
                    )
                    log.warn("could not delete erased document bytes; will retry: {}", failure.javaClass.simpleName)
                    return@forEach
                }
                deleted++
            }
            jdbc.update("delete from pending_storage_deletions where storage_key = :key", mapOf("key" to key))
        }
        return deleted
    }
}
