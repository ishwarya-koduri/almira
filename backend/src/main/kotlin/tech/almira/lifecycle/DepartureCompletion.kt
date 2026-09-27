package tech.almira.lifecycle

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tech.almira.crypto.EnvelopeCipher
import tech.almira.document.DocumentStorage
import tech.almira.reminder.Notifier
import tech.almira.reminder.OutboundNotification
import tech.almira.security.RequestUserContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** What one completed departure did. Counts only. */
data class DepartureResult(
    val departureId: UUID,
    val moved: Int,
    val erased: Int,
    val sharesPassedOn: Int,
    val copiesTaken: Int,
    val destinationHouseholdId: UUID?,
)

/**
 * Carries out a departure whose seven days are up (docs/05 §12).
 *
 * **Two steps, two connections, and why.** Anything encrypted server-side is
 * bound to its household (EnvelopeCipher's AAD), so a record that changes
 * household has to be decrypted under the old key and encrypted under the new.
 * That is done first, AS THE PERSON LEAVING, on the runtime connection with
 * their identity: they create their own household the ordinary way
 * (`bootstrap_household`), the new household's key is provisioned for them as
 * its owner, and every read of what they are taking goes through row-level
 * security — they can only carry out what they could see. The new document bytes
 * are written to storage under new keys.
 *
 * Then, on the OWNER connection in one transaction, the rows move: the
 * household id changes, holders become the person's member in the new household,
 * links to things that stay behind are cut, and their part of every joint record
 * passes to the other holders. If that transaction fails, the new document bytes
 * are removed and nothing in the old household has changed; the next sweep tries
 * again. Old document bytes are removed only after it commits, from keys queued
 * in that transaction and retried by the sweep until storage confirms (V109).
 *
 * **What cannot move.** A sealed field is bound to its household in the AAD
 * the client wrote (docs/12), and the server cannot re-seal what it cannot read.
 * Sealed values on moved records are dropped from the old household; the
 * person's download has them, and the preview says how many.
 *
 * **What waits.** An owner leaving a household that would be left with no owner
 * able to act, other people and records in it (`app.going_leaves_household_ownerless`,
 * V120), is not carried out at all: the household is made dormant, the
 * departure stays pending, and nothing is prepared — no household of their own
 * is made and no bytes are copied, because that check comes before step one.
 * Once someone takes the household on, the next sweep carries it out.
 *
 * **Visibility.** Moved records arrive private. They were shared with people who
 * are not in the new household, and "shared with the household" should never
 * quietly come to mean different people.
 */
@Component
class DepartureCompletion(
    @Qualifier("ownerDataSource") ownerDataSource: DataSource,
    transactionManager: PlatformTransactionManager,
    // The runtime template, named: there are two of this type (DatabaseConfig).
    @Qualifier("jdbc") runtimeJdbc: NamedParameterJdbcTemplate,
    private val cipher: EnvelopeCipher,
    private val storage: DocumentStorage,
    private val notifiers: List<Notifier>,
    private val userContext: RequestUserContext,
    private val notices: DormancyNotices,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val owner = NamedParameterJdbcTemplate(ownerDataSource)
    private val ownerTransactions = TransactionTemplate(DataSourceTransactionManager(ownerDataSource))
    private val asPerson = TransactionTemplate(transactionManager)
    private val runtime = runtimeJdbc
    private val records = LifecycleRecords(owner)
    private val writes = LifecycleWrites(owner)
    private val pendingDeletions = PendingStorageDeletions(owner, storage)
    private val dormancy = DormancyRecords(owner)

    private data class Due(
        val id: UUID, val householdId: UUID, val memberId: UUID, val userId: UUID,
        val privateRecords: String, val householdName: String, val memberName: String,
    )

    fun due(asOf: Instant): List<UUID> = owner.query(
        """
        select id from household_departures
         where cancelled_at is null and completed_at is null and effective_at <= :asOf
         order by effective_at
        """.trimIndent(),
        mapOf("asOf" to java.sql.Timestamp.from(asOf)),
    ) { rs, _ -> rs.getObject("id", UUID::class.java) }

    fun complete(departureId: UUID, asOf: Instant): DepartureResult? {
        val departure = load(departureId, asOf) ?: return null
        val taking = departure.privateRecords == "take"

        // The guard, before step one makes a household or copies a byte: the last
        // owner of a household with people and records in it does not leave it
        // ownerless. It waits, dormant, for someone to take it on.
        if (dormancy.leavesOwnerless(departure.householdId, departure.userId)) {
            val opened = ownerTransactions.execute {
                dormancy.open(departure.householdId, departure.userId, "owner_leaving", null, departure.id)
            }
            opened?.let { id ->
                runCatching { notices.opened(id, owner) }
                    .onFailure { log.warn("could not tell a dormant household: {}", it.javaClass.simpleName) }
            }
            return null
        }

        // Step one, as the person: their household, and what has to be re-encrypted.
        val prepared = if (taking) prepare(departure) else null
        val newKeys = prepared?.documents?.values?.map { it.second } ?: emptyList()

        var oldKeys: List<String> = emptyList()
        val result = try {
            ownerTransactions.execute {
                val locked = owner.query(
                    """
                    select id from household_departures
                     where id = :id and cancelled_at is null and completed_at is null for update
                    """.trimIndent(),
                    mapOf("id" to departureId),
                ) { rs, _ -> rs.getObject("id", UUID::class.java) }.isNotEmpty()
                if (!locked) return@execute null
                // Asked again under the lock: another owner may have gone in the meantime.
                if (dormancy.leavesOwnerless(departure.householdId, departure.userId)) return@execute null
                carryOut(departure, prepared).also {
                    oldKeys = it.second
                    pendingDeletions.queue(oldKeys)
                }.first
            }
        } catch (e: Exception) {
            newKeys.forEach { key -> runCatching { storage.delete(key) } }
            throw e
        }
        if (result == null) {
            newKeys.forEach { key -> runCatching { storage.delete(key) } }
            return null
        }
        // Queued in the transaction: a key storage refuses is tried again by the sweep.
        runCatching { pendingDeletions.deleteQueued(oldKeys) }
            .onFailure { log.warn("could not delete a moved document's old bytes: {}", it.javaClass.simpleName) }

        tell(
            departure.userId, result.destinationHouseholdId ?: departure.householdId, "lifecycle.departure.completed.you",
            "departure.completed:${departure.id}",
            "You've left ${departure.householdName}",
            if (taking) "What was private to you is in your own household now, set to private, with a copy of what you shared."
            else "What was private to you has been erased from it. What you shared stays with them.",
        )
        householdUsers(departure.householdId).forEach { other ->
            tell(
                other, departure.householdId, "lifecycle.departure.completed",
                "departure.completed:${departure.id}",
                "${departure.memberName} has left ${departure.householdName}",
                "Anything you held together stays with you.",
            )
        }
        return result
    }

    // --- step one ---------------------------------------------------------------

    private data class Prepared(
        val destination: UUID,
        val destinationMember: UUID,
        /** account id -> number re-encrypted for the destination */
        val accountNumbers: Map<UUID, ByteArray>,
        /** document id -> (old storage key, new storage key) */
        val documents: Map<UUID, Pair<String, String>>,
    )

    private fun prepare(departure: Due): Prepared = userContext.runAs(departure.userId) {
        val destination = existingDestination(departure) ?: asPerson.execute {
            runtime.queryForObject(
                "select household_id from app.bootstrap_household(:name, 'private', :displayName)",
                mapOf("name" to "${departure.memberName}'s household", "displayName" to departure.memberName),
                UUID::class.java,
            )
        }!!
        owner.update(
            "update household_departures set destination_household_id = :dest where id = :id",
            mapOf("dest" to destination, "id" to departure.id),
        )

        asPerson.execute {
            val destinationMember = runtime.queryForObject(
                "select id from members where household_id = :hid and user_id = :uid and deleted_at is null",
                mapOf("hid" to destination, "uid" to departure.userId), UUID::class.java,
            )!!
            val mine = records.memberIds(departure.householdId, departure.userId)
            val sole = records.solelyHeld(departure.householdId, departure.userId, mine)
            val documents = records.documentsFollowing(departure.householdId, departure.userId, sole)

            val accountIds = sole.filter { it.type == "account" }.map { it.id }
            val numbers = if (accountIds.isEmpty()) emptyMap() else runtime.query(
                "select id, number_enc from accounts where id in (:ids) and number_enc is not null",
                mapOf("ids" to accountIds),
            ) { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getBytes("number_enc") }
                .associate { (id, blob) ->
                    id to cipher.encrypt(destination, ACCOUNT_NUMBER, cipher.decrypt(departure.householdId, ACCOUNT_NUMBER, blob))
                }

            val moved = if (documents.isEmpty()) emptyMap() else runtime.query(
                "select id, storage_key from documents where id in (:ids)",
                mapOf("ids" to documents.map { it.id }),
            ) { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getString("storage_key") }
                .associate { (id, oldKey) ->
                    val newKey = "$destination/$id"
                    val plain = cipher.decryptBytes(departure.householdId, DOCUMENT_CONTENT, storage.get(oldKey))
                    storage.put(newKey, cipher.encryptBytes(destination, DOCUMENT_CONTENT, plain))
                    id to (oldKey to newKey)
                }
            Prepared(destination, destinationMember, numbers, moved)
        }!!
    }

    /** A retry after a failed second step reuses the household the first one made. */
    private fun existingDestination(departure: Due): UUID? = owner.query(
        """
        select d.destination_household_id from household_departures d
          join household_memberships hm on hm.household_id = d.destination_household_id
                                       and hm.user_id = d.user_id and hm.status = 'active'
         where d.id = :id
        """.trimIndent(),
        mapOf("id" to departure.id),
    ) { rs, _ -> rs.getObject("destination_household_id", UUID::class.java) }.firstOrNull()

    // --- step two ---------------------------------------------------------------

    private fun carryOut(departure: Due, prepared: Prepared?): Pair<DepartureResult, List<String>> {
        val hid = departure.householdId
        val uid = departure.userId
        val mine = records.memberIds(hid, uid).ifEmpty { listOf(departure.memberId) }

        val role = owner.query(
            "select role from household_memberships where household_id = :hid and user_id = :uid and status = 'active'",
            mapOf("hid" to hid, "uid" to uid),
        ) { rs, _ -> rs.getString("role") }.firstOrNull()
        if (role == "owner") {
            when (val next = records.nextOwner(hid, uid)) {
                is LifecycleRecords.Handover.To -> writes.makeOwner(hid, next.userId, if (next.named) "successor" else "admin")
                LifecycleRecords.Handover.NobodyChosen ->
                    writes.longestStanding(hid, uid)?.let { writes.makeOwner(hid, it, "longest_standing") }
                LifecycleRecords.Handover.NotNeeded -> Unit
            }
        }

        val sole = records.solelyHeld(hid, uid, mine)
        // Owner's decision (2026-09-16): leaving is not an erasure — the person keeps
        // their account and takes a copy of what is theirs — but what they shared with
        // the household stays with it, held by the same "Former member" as an erasure
        // leaves behind, "because they're shared: other members contributed to them and
        // depend on them". Only what was private to them alone goes with them or is erased.
        val goes = records.privateOnes(sole)
        val sharedStays = sole - goes.toSet()
        val documents = records.documentsFollowingPrivate(hid, uid, goes)
        val joint = records.jointlyHeld(hid, mine)
        val decisions = owner.query(
            "select record_type, record_id, decision from departure_joint_decisions where departure_id = :id",
            mapOf("id" to departure.id),
        ) { rs, _ -> (rs.getString("record_type") to rs.getObject("record_id", UUID::class.java)) to rs.getString("decision") }
            .toMap()

        var copies = 0
        val oldKeys: List<String>
        if (prepared != null) {
            move(hid, mine, goes, documents, prepared)
            joint.filter { decisions[it.type to it.id] == "take_my_share" }.forEach {
                if (copyShare(hid, it, mine, prepared)) copies++
            }
            // What stays is still theirs to have a copy of: the whole of it, since
            // they held all of it. The household's record is untouched.
            sharedStays.forEach { if (copyShare(hid, it, mine, prepared, ofMyPart = false)) copies++ }
            oldKeys = documents.mapNotNull { prepared.documents[it.id]?.first }
        } else {
            oldKeys = writes.erase(goes, documents)
        }
        joint.forEach { records.passShare(it.type, it.id, mine) }

        // Whatever of theirs the household keeps is held from here by a former member,
        // exactly as an erasure leaves it (V136). Their member rows stay, soft-deleted,
        // because the departure's own history points at them.
        val former = writes.moveHoldingsToFormerMember(hid, mine)
        writes.keepNamesOnOthersRecords(mine)
        writes.detachPerson(hid, uid)
        val m = mapOf("mids" to mine)
        owner.update("delete from record_visibility_grants where member_id in (:mids)", m)
        owner.update("delete from emergency_contacts where member_id in (:mids) or trusted_member_id in (:mids)", m)
        owner.update("delete from emergency_requests where subject_member_id in (:mids)", m)
        owner.update("delete from household_successors where successor_member_id in (:mids)", m)
        owner.update("delete from coming_of_age_notices where member_id in (:mids)", m)
        owner.update("update goals set member_id = null where member_id in (:mids)", m)
        owner.update("update members set deleted_at = now() where id in (:mids)", m)
        owner.update(
            "update household_memberships set status = 'left' where household_id = :hid and user_id = :uid",
            mapOf("hid" to hid, "uid" to uid),
        )
        owner.update(
            """
            update household_departures
               set completed_at = now(), destination_household_id = :dest
             where id = :id
            """.trimIndent(),
            mapOf("id" to departure.id, "dest" to prepared?.destination),
        )

        val result = DepartureResult(
            departure.id,
            moved = if (prepared != null) goes.size + documents.size else 0,
            erased = if (prepared == null) goes.size + documents.size else 0,
            sharesPassedOn = joint.size, copiesTaken = copies,
            destinationHouseholdId = prepared?.destination,
        )
        writes.systemAudit(
            hid, "household.departure.complete", "household_departure", departure.id,
            mapOf(
                "privateRecords" to departure.privateRecords, "moved" to result.moved, "erased" to result.erased,
                "sharesPassedOn" to result.sharesPassedOn, "copiesTaken" to copies,
                "recordsKeptForHousehold" to sharedStays.size, "formerMember" to former,
            ),
        )
        prepared?.let {
            writes.systemAudit(
                it.destination, "household.records_arrived", "household_departure", departure.id,
                mapOf("moved" to result.moved, "copiesTaken" to copies),
            )
        }
        return result to oldKeys
    }

    private fun move(hid: UUID, mine: List<UUID>, sole: List<HeldRecord>, documents: List<HeldRecord>, to: Prepared) {
        val ids = (sole + documents).map { it.id }
        if (ids.isEmpty()) return
        val byType = sole.groupBy({ it.type }, { it.id })
        val investments = byType["investment"].orEmpty()
        val liabilities = byType["liability"].orEmpty()
        val accounts = byType["account"].orEmpty()
        val goals = byType["goal"].orEmpty()
        val estate = byType["estate_document"].orEmpty()
        val contacts = byType["contact"].orEmpty()
        val docs = documents.map { it.id }
        val dest = to.destination
        val nobody = listOf(LifecycleRecords.NOBODY)
        fun p(vararg pairs: Pair<String, Any?>) = pairs.toMap()
        fun orNobody(list: List<UUID>) = list.ifEmpty { nobody }

        // What only makes sense in the old household: grants to its members,
        // sealed values bound to its id, guest links, nudges, tags.
        owner.update("delete from record_visibility_grants where record_id in (:ids)", p("ids" to ids))
        owner.update("delete from sealed_values where record_id in (:ids)", p("ids" to ids))
        owner.update("delete from guest_share_items where record_id in (:ids)", p("ids" to ids))
        owner.update("delete from record_confirmation_nudges where record_id in (:ids)", p("ids" to ids))
        owner.update("update record_confirmations set household_id = :dest where record_id in (:ids)", p("dest" to dest, "ids" to ids))
        owner.update(
            "update custom_fields set household_id = :dest where owner_type = 'record' and owner_id in (:ids)",
            p("dest" to dest, "ids" to ids),
        )
        owner.update(
            "delete from document_links where document_id in (:docs) and entity_id not in (:ids)",
            p("docs" to orNobody(docs), "ids" to ids),
        )
        owner.update(
            "delete from contact_links where contact_id in (:contacts) and entity_id not in (:ids)",
            p("contacts" to orNobody(contacts), "ids" to ids),
        )
        owner.update(
            "delete from contact_links where entity_id in (:ids) and contact_id not in (:contacts)",
            p("contacts" to orNobody(contacts), "ids" to ids),
        )

        if (investments.isNotEmpty()) {
            val inv = p("ids" to investments, "dest" to dest, "hid" to hid, "accounts" to orNobody(accounts))
            copyReferenceData(hid, dest, "investments", investments)
            owner.update("delete from investment_tags where investment_id in (:ids)", inv)
            owner.update(
                "delete from investment_goals where investment_id in (:ids) and goal_id not in (:goals)",
                inv + p("goals" to orNobody(goals)),
            )
            owner.update(
                "delete from asset_liability_links where investment_id in (:ids) and liability_id not in (:liabilities)",
                inv + p("liabilities" to orNobody(liabilities)),
            )
            owner.update("update investments set rolled_from_id = null where rolled_from_id in (:ids) and id not in (:ids)", inv)
            owner.update("update investments set rolled_from_id = null where id in (:ids) and rolled_from_id not in (:ids)", inv)
            owner.update("update estate_beneficiaries set investment_id = null where investment_id in (:ids) and estate_document_id not in (:estate)", inv + p("estate" to orNobody(estate)))
            owner.update("update transactions set from_account_id = null where investment_id in (:ids) and from_account_id not in (:accounts)", inv)
            nomineesKeepNames(investments)
            owner.update("update investment_ownerships set member_id = :member where investment_id in (:ids)", inv + p("member" to to.destinationMember))
            owner.update("update reminders set household_id = :dest where investment_id in (:ids)", inv)
            owner.update(
                """
                update investments set household_id = :dest, visibility = 'private',
                       account_id = case when account_id in (:accounts) then account_id end
                 where id in (:ids)
                """.trimIndent(),
                inv,
            )
        }

        if (liabilities.isNotEmpty()) {
            val lia = p("ids" to liabilities, "dest" to dest, "investments" to orNobody(investments))
            copyReferenceData(hid, dest, "liabilities", liabilities)
            owner.update("delete from asset_liability_links where liability_id in (:ids) and investment_id not in (:investments)", lia)
            owner.update("update estate_beneficiaries set liability_id = null where liability_id in (:ids) and estate_document_id not in (:estate)", lia + p("estate" to orNobody(estate)))
            owner.update("update liability_holders set member_id = :member where liability_id in (:ids)", lia + p("member" to to.destinationMember))
            owner.update("update reminders set household_id = :dest where liability_id in (:ids)", lia)
            owner.update("update liabilities set household_id = :dest, visibility = 'private' where id in (:ids)", lia)
        }

        if (accounts.isNotEmpty()) {
            val acc = p("ids" to accounts, "dest" to dest, "investments" to orNobody(investments))
            copyReferenceData(hid, dest, "accounts", accounts)
            owner.update("update investments set account_id = null where account_id in (:ids) and id not in (:investments)", acc)
            owner.update("update transactions set from_account_id = null where from_account_id in (:ids) and investment_id not in (:investments)", acc)
            owner.update("update investment_templates set account_id = null where account_id in (:ids)", acc)
            owner.update("update account_holders set member_id = :member where account_id in (:ids)", acc + p("member" to to.destinationMember))
            owner.update("update accounts set household_id = :dest, visibility = 'private', number_enc = null where id in (:ids)", acc)
            to.accountNumbers.forEach { (id, blob) ->
                owner.update("update accounts set number_enc = :blob where id = :id", p("blob" to blob, "id" to id))
            }
        }

        if (goals.isNotEmpty()) {
            val g = p("ids" to goals, "dest" to dest, "investments" to orNobody(investments), "mids" to mine)
            owner.update("delete from investment_goals where goal_id in (:ids) and investment_id not in (:investments)", g)
            owner.update(
                """
                update goals set household_id = :dest, visibility = 'private',
                       member_id = case when member_id in (:mids) then cast(:member as uuid) end
                 where id in (:ids)
                """.trimIndent(),
                g + p("member" to to.destinationMember),
            )
        }

        if (estate.isNotEmpty()) {
            val e = p("ids" to estate, "dest" to dest, "mids" to mine, "contacts" to orNobody(contacts), "docs" to orNobody(docs))
            owner.update(
                """
                update estate_roles r set person_name = coalesce(r.person_name, m.display_name), member_id = null
                  from members m
                 where m.id = r.member_id and r.estate_document_id in (:ids) and r.member_id not in (:mids)
                """.trimIndent(),
                e,
            )
            owner.update("update estate_roles set member_id = :member where estate_document_id in (:ids) and member_id in (:mids)", e + p("member" to to.destinationMember))
            owner.update("update estate_roles set contact_id = null where estate_document_id in (:ids) and contact_id not in (:contacts)", e)
            owner.update(
                """
                update estate_beneficiaries b set person_name = coalesce(b.person_name, m.display_name), member_id = null
                  from members m
                 where m.id = b.member_id and b.estate_document_id in (:ids) and b.member_id not in (:mids)
                """.trimIndent(),
                e,
            )
            owner.update("update estate_beneficiaries set member_id = :member where estate_document_id in (:ids) and member_id in (:mids)", e + p("member" to to.destinationMember))
            owner.update(
                "update estate_beneficiaries set investment_id = null where estate_document_id in (:ids) and investment_id not in (:investments)",
                e + p("investments" to orNobody(investments)),
            )
            owner.update(
                "update estate_beneficiaries set liability_id = null where estate_document_id in (:ids) and liability_id not in (:liabilities)",
                e + p("liabilities" to orNobody(liabilities)),
            )
            owner.update(
                """
                update estate_documents set household_id = :dest, visibility = 'private', member_id = :member,
                       document_id = case when document_id in (:docs) then document_id end
                 where id in (:ids)
                """.trimIndent(),
                e + p("member" to to.destinationMember),
            )
        }

        if (contacts.isNotEmpty()) {
            owner.update("update estate_roles set contact_id = null where contact_id in (:ids) and estate_document_id not in (:estate)", p("ids" to contacts, "estate" to orNobody(estate)))
            owner.update("update contacts set household_id = :dest, visibility = 'private' where id in (:ids)", p("ids" to contacts, "dest" to dest))
        }

        if (docs.isNotEmpty()) {
            owner.update("update estate_documents set document_id = null where document_id in (:docs) and id not in (:estate)", p("docs" to docs, "estate" to orNobody(estate)))
            owner.update("update documents set supersedes_id = null where supersedes_id in (:docs) and id not in (:docs)", p("docs" to docs))
            owner.update("update documents set supersedes_id = null where id in (:docs) and supersedes_id not in (:docs)", p("docs" to docs))
            to.documents.forEach { (id, keys) ->
                owner.update(
                    "update documents set household_id = :dest, visibility = 'private', storage_key = :key where id = :id",
                    p("dest" to dest, "key" to keys.second, "id" to id),
                )
            }
        }
    }

    /**
     * A record that points at a household's own type or institution cannot keep
     * pointing at it from another household. The destination gets its own copy,
     * made once per source row and reused.
     */
    private fun copyReferenceData(from: UUID, to: UUID, table: String, ids: List<UUID>) {
        if (table == "investments") {
            owner.query(
                """
                select distinct t.id from investments i join investment_types t on t.id = i.type_id
                 where i.id in (:ids) and t.household_id = :from
                """.trimIndent(),
                mapOf("ids" to ids, "from" to from),
            ) { rs, _ -> rs.getObject("id", UUID::class.java) }.forEach { typeId ->
                val copy = owner.queryForObject(
                    """
                    insert into investment_types (category_id, household_id, code, label, icon, color, field_schema,
                                                  schema_version, is_custom, sort)
                    select category_id, :to, code, label, icon, color, field_schema, schema_version, is_custom, sort
                      from investment_types where id = :id
                    on conflict do nothing
                    returning id
                    """.trimIndent(),
                    mapOf("to" to to, "id" to typeId), UUID::class.java,
                ) ?: owner.queryForObject(
                    """
                    select t2.id from investment_types t2 join investment_types t on t.code = t2.code
                     where t.id = :id and t2.household_id = :to
                    """.trimIndent(),
                    mapOf("to" to to, "id" to typeId), UUID::class.java,
                )
                owner.update(
                    """
                    insert into custom_fields (household_id, owner_type, owner_id, key, label, data_type, unit, options,
                                               required, counts_toward_value, sort)
                    select :to, 'type', :copy, key, label, data_type, unit, options, required, counts_toward_value, sort
                      from custom_fields where owner_type = 'type' and owner_id = :id
                    on conflict do nothing
                    """.trimIndent(),
                    mapOf("to" to to, "copy" to copy, "id" to typeId),
                )
                owner.update(
                    "update investments set type_id = :copy where id in (:ids) and type_id = :id",
                    mapOf("copy" to copy, "ids" to ids, "id" to typeId),
                )
            }
        }
        owner.query(
            """
            select distinct inst.id from $table r join institutions inst on inst.id = r.institution_id
             where r.id in (:ids) and inst.household_id = :from
            """.trimIndent(),
            mapOf("ids" to ids, "from" to from),
        ) { rs, _ -> rs.getObject("id", UUID::class.java) }.forEach { institutionId ->
            val copy = owner.queryForObject(
                """
                insert into institutions (household_id, name, kind, logo_url, website)
                select :to, name, kind, logo_url, website from institutions where id = :id
                returning id
                """.trimIndent(),
                mapOf("to" to to, "id" to institutionId), UUID::class.java,
            )
            owner.update(
                "update $table set institution_id = :copy where id in (:ids) and institution_id = :id",
                mapOf("copy" to copy, "ids" to ids, "id" to institutionId),
            )
        }
    }

    private fun nomineesKeepNames(investments: List<UUID>) {
        owner.update(
            """
            update investment_nominees n set nominee_name = coalesce(n.nominee_name, m.display_name), member_id = null
              from members m
             where m.id = n.member_id and n.investment_id in (:ids)
            """.trimIndent(),
            mapOf("ids" to investments),
        )
    }

    /**
     * "Take a copy of my part": a new private record in the person's own
     * household, sized to their share of the joint one on the day they left. The
     * joint record itself stays whole with the others. No documents, no history:
     * those belong to the record that stays.
     */
    /**
     * A copy of the person's part of a record that stays, in their own household,
     * private. [ofMyPart] is false for a record they held alone and shared with the
     * household: the copy is the whole of it, under its own name, because all of it
     * was theirs (owner's decision, 2026-09-16).
     */
    private fun copyShare(hid: UUID, record: HeldRecord, mine: List<UUID>, to: Prepared, ofMyPart: Boolean = true): Boolean {
        val p = mapOf(
            "id" to record.id, "mids" to mine, "dest" to to.destination, "member" to to.destinationMember,
            "uid" to null, "suffix" to if (ofMyPart) " (my part)" else "",
            "note" to if (ofMyPart) "Your part, when you left" else "Your copy, when you left",
        )
        return when (record.type) {
            "investment" -> {
                val share = owner.queryForObject(
                    "select coalesce(sum(share_pct), 0) from investment_ownerships where investment_id = :id and member_id in (:mids)",
                    p, BigDecimal::class.java,
                ) ?: BigDecimal.ZERO
                val fraction = share.divide(BigDecimal(100), 6, RoundingMode.HALF_UP)
                val typeHousehold = owner.queryForObject(
                    "select t.household_id from investments i join investment_types t on t.id = i.type_id where i.id = :id",
                    p, UUID::class.java,
                )
                val newId = UUID.randomUUID()
                owner.update(
                    """
                    insert into investments (id, household_id, type_id, title, status, invested_amount, currency, quantity,
                                             unit, cost_basis_method, start_date, maturity_date, attributes, notes,
                                             is_in_continuity, visibility)
                    select :newId, :dest, type_id, title || cast(:suffix as text), status, invested_amount * :fraction, currency,
                           quantity * :fraction, unit, cost_basis_method, start_date, maturity_date, attributes, notes,
                           is_in_continuity, 'private'
                      from investments where id = :id
                    """.trimIndent(),
                    p + mapOf("newId" to newId, "fraction" to fraction),
                )
                if (typeHousehold == hid) copyReferenceData(hid, to.destination, "investments", listOf(newId))
                owner.update(
                    "update investments set institution_id = null where id = :newId",
                    mapOf("newId" to newId),
                )
                owner.update(
                    "insert into investment_ownerships (investment_id, member_id, share_pct) values (:newId, :member, 100)",
                    p + mapOf("newId" to newId),
                )
                owner.update(
                    """
                    insert into valuations (investment_id, as_of_date, value, quantity, source, note,
                                            price_source, unit_price, instrument)
                    select :newId, as_of_date, value * :fraction, quantity * :fraction, source, cast(:note as text),
                           -- A price-fed figure keeps its labels (V66 price_feed_valuation_is_labelled):
                           -- the price per unit is the same, only the units held are his part.
                           price_source, unit_price, instrument
                      from valuations where investment_id = :id order by as_of_date desc limit 1
                    """.trimIndent(),
                    p + mapOf("newId" to newId, "fraction" to fraction),
                )
                true
            }
            "liability" -> {
                val share = owner.queryForObject(
                    "select coalesce(sum(responsibility_pct), 0) from liability_holders where liability_id = :id and member_id in (:mids)",
                    p, BigDecimal::class.java,
                ) ?: BigDecimal.ZERO
                val fraction = share.divide(BigDecimal(100), 6, RoundingMode.HALF_UP)
                val newId = UUID.randomUUID()
                owner.update(
                    """
                    insert into liabilities (id, household_id, kind, title, principal, outstanding, interest_rate,
                                             emi_amount, emi_day, start_date, end_date, status, attributes, notes,
                                             visibility, is_in_continuity)
                    select :newId, :dest, kind, title || cast(:suffix as text), principal * :fraction, outstanding * :fraction,
                           interest_rate, emi_amount * :fraction, emi_day, start_date, end_date, status, attributes, notes,
                           'private', is_in_continuity
                      from liabilities where id = :id
                    """.trimIndent(),
                    p + mapOf("newId" to newId, "fraction" to fraction),
                )
                owner.update(
                    "insert into liability_holders (liability_id, member_id, responsibility_pct) values (:newId, :member, 100)",
                    p + mapOf("newId" to newId),
                )
                true
            }
            "account" -> {
                val newId = UUID.randomUUID()
                owner.update(
                    """
                    insert into accounts (id, household_id, account_kind, label, number_masked, ifsc, notes, visibility)
                    select :newId, :dest, account_kind, label, number_masked, ifsc, notes, 'private'
                      from accounts where id = :id
                    """.trimIndent(),
                    p + mapOf("newId" to newId),
                )
                owner.update(
                    "insert into account_holders (account_id, member_id, holder_type) values (:newId, :member, 'joint')",
                    p + mapOf("newId" to newId),
                )
                true
            }
            else -> false
        }
    }

    // --- helpers ----------------------------------------------------------------

    private fun load(id: UUID, asOf: Instant): Due? = owner.query(
        """
        select d.id, d.household_id, d.member_id, d.user_id, d.private_records, h.name as household_name,
               m.display_name
          from household_departures d
          join households h on h.id = d.household_id
          join members m on m.id = d.member_id
         where d.id = :id and d.cancelled_at is null and d.completed_at is null and d.effective_at <= :asOf
        """.trimIndent(),
        mapOf("id" to id, "asOf" to java.sql.Timestamp.from(asOf)),
    ) { rs, _ ->
        Due(
            rs.getObject("id", UUID::class.java), rs.getObject("household_id", UUID::class.java),
            rs.getObject("member_id", UUID::class.java), rs.getObject("user_id", UUID::class.java),
            rs.getString("private_records"), rs.getString("household_name"), rs.getString("display_name"),
        )
    }.firstOrNull()

    private fun householdUsers(householdId: UUID): List<UUID> = owner.query(
        "select user_id from household_memberships where household_id = :hid and status = 'active'",
        mapOf("hid" to householdId),
    ) { rs, _ -> rs.getObject("user_id", UUID::class.java) }

    private fun tell(userId: UUID, householdId: UUID, template: String, key: String, title: String, body: String) {
        notifiers.forEach { notifier ->
            runCatching {
                notifier.deliver(
                    OutboundNotification(
                        userId = userId, householdId = householdId, reminderId = null,
                        template = template, title = title, body = body, idempotencyKey = "$key:$userId",
                    ),
                )
            }
        }
    }

    private companion object {
        const val ACCOUNT_NUMBER = "accounts.number_enc"
        const val DOCUMENT_CONTENT = "documents.content"
    }
}
