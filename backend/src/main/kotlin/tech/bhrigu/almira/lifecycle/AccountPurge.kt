package tech.bhrigu.almira.lifecycle

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tech.bhrigu.almira.document.DocumentStorage
import tech.bhrigu.almira.security.SessionRevocationCache
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** What one erasure did. Counts only: this is logged. */
data class PurgeResult(
    val closureId: UUID,
    val householdsErased: Int,
    val householdsLeft: Int,
    val recordsErased: Int,
    val sharesPassedOn: Int,
    /**
     * Households where the person is the last owner and records remain: made
     * dormant, and not carried out (docs/05 §12.7). While any is held the
     * account itself is not erased and the closure stays pending.
     */
    val householdsHeld: Int = 0,
)

/**
 * Carries out a closure whose thirty days are up: the hard purge docs/05 §8
 * promises, with a record that it happened (docs/05 §12).
 *
 * **Which connection.** The OWNER data source, by explicit qualifier, as every
 * sweep here does (see StillTrueSweep). Erasing an account deletes rows no
 * policy lets anyone delete — other people's grants that name the person, their
 * sessions, their membership — and runs with no user, for whom row-level
 * security would show nothing at all.
 *
 * **What goes.** Everything [AccountClosureService.closurePreview] listed under
 * "erased", decided by the same [LifecycleRecords] rule: the user row and all
 * that hangs from it, every record they solely hold, the documents that went
 * with those, and every household in which nobody else signs in.
 *
 * **What stays.** Joint records, with the person's part passed to the other
 * holders. Records they typed in for other people, with their name taken off.
 * Other people's records that named them — a nomination, a will — keep the name
 * as text, because those are other people's records. The household, handed to
 * whoever was named to carry it on.
 *
 * **What is kept, and why.** The audit log. Rows the person wrote lose their
 * actor (the foreign key sets it null); rows in a household that is erased lose
 * the household id first, so deleting the household does not cascade them away.
 * DPDP Rules 2025 rule 8(3) asks for a year of these, and they cannot be edited by
 * the application in any case (R__grants). The closure row stays too, with
 * `user_id` null and `purged_at` set: it is the record that the promise was kept.
 *
 * **Order.** One transaction for the database. Document bytes are deleted only
 * after it commits, and sessions are revoked in Redis only then — a purge that
 * rolls back must leave a person able to sign in to an account that still exists.
 * The storage keys are written to `pending_storage_deletions` in that
 * transaction, so bytes storage fails to delete are tried again on every sweep
 * until they are gone (V109).
 *
 * **What waits.** Before anything is carried out, each household is asked
 * whether this person is its last owner while other people and records remain
 * in it (`app.going_leaves_household_ownerless`, V120). Such a household is made
 * dormant and left exactly as it is: the membership, what the person holds
 * there, and the account itself, which a membership hangs from. The rest of the
 * closure is carried out — households nobody else signs in to are erased, and
 * the person leaves the others (their membership marked `left`, so the next run
 * does not do it twice) — and the closure stays pending. Sessions are not
 * revoked, since the person may yet choose Keep my account. Once someone takes
 * the household on, the next sweep finishes the purge as above.
 */
@Component
class AccountPurge(
    @Qualifier("ownerDataSource") ownerDataSource: DataSource,
    private val storage: DocumentStorage,
    private val revocations: SessionRevocationCache,
    // Null only where a test builds this by hand to fail storage: nothing is told then.
    private val notices: DormancyNotices? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jdbc = NamedParameterJdbcTemplate(ownerDataSource)
    private val transactions = TransactionTemplate(DataSourceTransactionManager(ownerDataSource))
    private val records = LifecycleRecords(jdbc)
    private val writes = LifecycleWrites(jdbc)
    private val dormancy = DormancyRecords(jdbc)
    val pendingDeletions = PendingStorageDeletions(jdbc, storage)

    fun due(asOf: Instant): List<UUID> = jdbc.query(
        """
        select id from account_closures
         where cancelled_at is null and purged_at is null and purge_after <= :asOf
         order by purge_after
        """.trimIndent(),
        mapOf("asOf" to java.sql.Timestamp.from(asOf)),
    ) { rs, _ -> rs.getObject("id", UUID::class.java) }

    fun purge(closureId: UUID, asOf: Instant): PurgeResult? {
        var sessions: List<UUID> = emptyList()
        var storageKeys: List<String> = emptyList()
        var opened: List<UUID> = emptyList()
        val result = transactions.execute {
            val userId = jdbc.query(
                """
                select user_id from account_closures
                 where id = :id and cancelled_at is null and purged_at is null and purge_after <= :asOf
                   and user_id is not null
                 for update
                """.trimIndent(),
                mapOf("id" to closureId, "asOf" to java.sql.Timestamp.from(asOf)),
            ) { rs, _ -> rs.getObject("user_id", UUID::class.java) }.firstOrNull()
                ?: return@execute null

            sessions = jdbc.query("select id from user_sessions where user_id = :uid", mapOf("uid" to userId)) { rs, _ ->
                rs.getObject("id", UUID::class.java)
            }
            val keys = mutableListOf<String>()
            var erasedHouseholds = 0
            var leftHouseholds = 0
            var erasedRecords = 0
            var passed = 0

            val memberships = jdbc.query(
                "select household_id, role from household_memberships where user_id = :uid and status = 'active'",
                mapOf("uid" to userId),
            ) { rs, _ -> rs.getObject("household_id", UUID::class.java) to rs.getString("role") }

            // The guard, before anything is carried out (docs/known-issues.md, "A guard
            // runs before the action it guards"): a household this person is the last
            // owner of, with other people and records in it, is not purged — it waits.
            val held = memberships
                .filter { (householdId, role) -> role == "owner" && dormancy.leavesOwnerless(householdId, userId) }
                .map { it.first }.toSet()
            opened = held.mapNotNull { dormancy.open(it, userId, "owner_closing_account", closureId, null) }

            memberships.filter { it.first !in held }.forEach { (householdId, role) ->
                if (records.otherPeopleWithLogin(householdId, userId) == 0) {
                    keys += jdbc.query(
                        "select storage_key from documents where household_id = :hid",
                        mapOf("hid" to householdId),
                    ) { rs, _ -> rs.getString("storage_key") }
                    // Kept, without the household to hang from: see the class comment.
                    jdbc.update(
                        "update activity_log set household_id = null where household_id = :hid",
                        mapOf("hid" to householdId),
                    )
                    jdbc.update("delete from households where id = :hid", mapOf("hid" to householdId))
                    erasedHouseholds++
                    return@forEach
                }

                val mine = records.memberIds(householdId, userId)
                if (role == "owner") handOver(householdId, userId)

                val sole = records.solelyHeld(householdId, userId, mine)
                val documents = records.documentsFollowing(householdId, userId, sole)
                records.jointlyHeld(householdId, mine).forEach {
                    records.passShare(it.type, it.id, mine)
                    passed++
                }
                keys += writes.erase(sole, documents)
                erasedRecords += sole.size + documents.size
                writes.keepNamesOnOthersRecords(mine)
                writes.detachPerson(householdId, userId)
                if (mine.isNotEmpty()) {
                    jdbc.update("delete from members where id in (:mids)", mapOf("mids" to mine))
                }
                writes.systemAudit(
                    householdId, "member.erased", "household", householdId,
                    mapOf("recordsErased" to sole.size + documents.size),
                )
                if (held.isNotEmpty()) {
                    // The account outlives this run, so the membership it still has here
                    // is ended by hand rather than by deleting the user.
                    jdbc.update(
                        "update household_memberships set status = 'left' where household_id = :hid and user_id = :uid",
                        mapOf("hid" to householdId, "uid" to userId),
                    )
                }
                leftHouseholds++
            }

            if (held.isNotEmpty()) {
                writes.systemAudit(
                    null, "account.purge.held", "account_closure", closureId,
                    mapOf(
                        "householdsHeld" to held.size, "householdsErased" to erasedHouseholds,
                        "householdsLeft" to leftHouseholds, "recordsErased" to erasedRecords, "sharesPassedOn" to passed,
                    ),
                )
                pendingDeletions.queue(keys)
                storageKeys = keys
                sessions = emptyList()
                return@execute PurgeResult(closureId, erasedHouseholds, leftHouseholds, erasedRecords, passed, held.size)
            }

            // Rows about them in households they had already left, and the
            // invitations that carried their number or address.
            // A row still holding something is left for the foreign key to unlink
            // (members.user_id is set null with the user) rather than deleted from
            // under the record, which would break its shares at commit.
            jdbc.update(
                """
                delete from members m
                 where m.user_id = :uid
                   and not exists (select 1 from investment_ownerships o where o.member_id = m.id)
                   and not exists (select 1 from liability_holders h where h.member_id = m.id)
                   and not exists (select 1 from account_holders h where h.member_id = m.id)
                """.trimIndent(),
                mapOf("uid" to userId),
            )
            jdbc.update(
                """
                delete from invitations i using users u
                 where u.id = :uid and (i.phone = u.phone or (i.email is not null and i.email = u.email))
                """.trimIndent(),
                mapOf("uid" to userId),
            )
            jdbc.update("update households set created_by = null where created_by = :uid", mapOf("uid" to userId))

            jdbc.update("update account_closures set purged_at = now() where id = :id", mapOf("id" to closureId))
            jdbc.update("delete from users where id = :uid", mapOf("uid" to userId))

            writes.systemAudit(
                null, "account.purge", "account_closure", closureId,
                mapOf(
                    "householdsErased" to erasedHouseholds, "householdsLeft" to leftHouseholds,
                    "recordsErased" to erasedRecords, "sharesPassedOn" to passed,
                ),
            )
            pendingDeletions.queue(keys)
            storageKeys = keys
            PurgeResult(closureId, erasedHouseholds, leftHouseholds, erasedRecords, passed)
        } ?: return null

        opened.forEach { id ->
            runCatching { notices?.opened(id, jdbc) }
                .onFailure { log.warn("could not tell a dormant household: {}", it.javaClass.simpleName) }
        }
        sessions.forEach { runCatching { revocations.revoke(it) } }
        // A key storage refuses stays queued, for the next sweep.
        runCatching { pendingDeletions.deleteQueued(storageKeys) }
            .onFailure { log.warn("could not delete stored documents after purge: {}", it.javaClass.simpleName) }
        log.info(
            "account purge {}: {} household(s) erased, {} left, {} held dormant, {} record(s) erased, {} share(s) passed on",
            closureId, result.householdsErased, result.householdsLeft, result.householdsHeld, result.recordsErased,
            result.sharesPassedOn,
        )
        return result
    }

    private fun handOver(householdId: UUID, userId: UUID) {
        when (val next = records.nextOwner(householdId, userId)) {
            is LifecycleRecords.Handover.To ->
                writes.makeOwner(householdId, next.userId, if (next.named) "successor" else "admin")
            LifecycleRecords.Handover.NobodyChosen ->
                writes.longestStanding(householdId, userId)?.let { writes.makeOwner(householdId, it, "longest_standing") }
            LifecycleRecords.Handover.NotNeeded -> Unit
        }
    }
}
