package tech.almira.lifecycle

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tech.almira.document.DocumentStorage
import tech.almira.security.SessionRevocationCache
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** What one erasure did. Counts only: this is logged. */
data class PurgeResult(
    val closureId: UUID,
    val householdsErased: Int,
    val householdsLeft: Int,
    val recordsErased: Int,
    /** Shared or jointly held records that stayed, held by a former member. */
    val recordsKept: Int,
    /**
     * Households where the person was the last owner and records remain: made
     * dormant, with what the person held for the household kept under a former
     * member (docs/05 §12.7, V136). The account is erased all the same.
     */
    val householdsLeftDormant: Int = 0,
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
 * that hangs from it, every record that was private to them alone, the papers
 * that went only with those, and every household in which nobody else signs in.
 *
 * **What stays.** What they shared with the household or with named people, and
 * their part of what they held jointly, held from then on by a **former member**
 * (V136) that carries no name or date of anyone. Other people's records that
 * named them — a nomination, a will — keep the name as text, as that record
 * wrote it, unlinked from them and with no contact details (owner's decision,
 * 2026-09-15; flagged for counsel in docs/23). The household, handed to whoever
 * was named to carry it on.
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
 * **One rule, every household.** The owner's answer (D8, 2026-09-15): "The
 * departed person's own personal data is erased on schedule — their right
 * doesn't wait on absent relatives. The household's records belong to the other
 * members and survive." And, asked whether that is only for a household left
 * without an owner: *apply the split to every erasure. A special case that only
 * fires on dormancy will drift out of step with the general path.* So each
 * household is first asked whether this person is its last owner while other
 * people and records remain (`app.going_leaves_household_ownerless`, V120); such
 * a household is made dormant before a row of theirs is touched, and any other
 * they own is handed on. Then every household they leave goes through the same
 * lines below. The account, sessions, factors, consents, drafts and snapshots go
 * in the same transaction, on the same day as anyone's.
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
            var keptRecords = 0

            val memberships = jdbc.query(
                "select household_id, role from household_memberships where user_id = :uid and status = 'active'",
                mapOf("uid" to userId),
            ) { rs, _ -> rs.getObject("household_id", UUID::class.java) to rs.getString("role") }

            // Asked before anything is carried out (docs/known-issues.md, "A guard runs
            // before the action it guards"): a household this person is the last owner
            // of, with other people and records in it, is made dormant before a row of
            // theirs is touched, so it is never left ownerless without saying so.
            val held = memberships
                .filter { (householdId, role) -> role == "owner" && dormancy.leavesOwnerless(householdId, userId) }
                .map { it.first }.toSet()
            opened = held.mapNotNull { dormancy.open(it, userId, "owner_closing_account", closureId, null) }

            memberships.forEach { (householdId, role) ->
                if (householdId !in held && records.otherPeopleWithLogin(householdId, userId) == 0) {
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

                // Handed on before anything of theirs is touched; a dormant one was opened above.
                if (householdId !in held && role == "owner") handOver(householdId, userId)

                // One rule for every household a person is erased from (owner's decision,
                // 2026-09-15): what was private to them goes, with its papers; what they
                // shared, and their part of what they held jointly, stays under a former member.
                val mine = records.memberIds(householdId, userId)
                val sole = records.solelyHeld(householdId, userId, mine)
                val joint = records.jointlyHeld(householdId, mine)
                val erased = records.privateOnes(sole)
                val documents = records.documentsFollowingPrivate(householdId, userId, erased)
                keys += writes.erase(erased, documents)
                erasedRecords += erased.size + documents.size
                keptRecords += sole.size - erased.size + joint.size
                writes.detachPerson(householdId, userId)
                // Before the link goes, since it says which rows are about them.
                writes.removeContactTraces(mine)
                val former = writes.becomeFormerMember(householdId, mine)
                writes.systemAudit(
                    householdId, "member.erased", "household", householdId,
                    mapOf(
                        "recordsErased" to erased.size + documents.size,
                        "recordsKeptForHousehold" to sole.size - erased.size,
                        "jointRecordsKept" to joint.size,
                        "formerMember" to former,
                    ),
                )
                leftHouseholds++
            }

            // Rows about them in households they had already left, and the
            // invitations that carried their number or address.
            // A row still holding something is left for the foreign key to unlink
            // (members.user_id is set null with the user) rather than deleted from
            // under the record, which would break its shares at commit.
            val leftBehind = jdbc.query(
                """
                select m.id from members m
                 where m.user_id = :uid
                   and not exists (select 1 from investment_ownerships o where o.member_id = m.id)
                   and not exists (select 1 from liability_holders h where h.member_id = m.id)
                   and not exists (select 1 from account_holders h where h.member_id = m.id)
                """.trimIndent(),
                mapOf("uid" to userId),
            ) { rs, _ -> rs.getObject("id", UUID::class.java) }
            // A nomination there that named them keeps the name, unlinked, as anywhere else.
            writes.removeContactTraces(leftBehind)
            writes.keepNamesOnOthersRecords(leftBehind)
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
                    "recordsErased" to erasedRecords, "recordsKeptUnderFormerMember" to keptRecords,
                    "householdsLeftDormant" to held.size,
                ),
            )
            pendingDeletions.queue(keys)
            storageKeys = keys
            PurgeResult(closureId, erasedHouseholds, leftHouseholds, erasedRecords, keptRecords, held.size)
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
            "account purge {}: {} household(s) erased, {} left ({} of them dormant), {} record(s) erased, {} kept under a former member",
            closureId, result.householdsErased, result.householdsLeft, result.householdsLeftDormant, result.recordsErased,
            result.recordsKept,
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
