package tech.bhrigu.almira.lifecycle

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/** One line of a "what goes, what stays" preview. Titles only ever the caller's own. */
data class LifecycleLine(
    val householdId: UUID?,
    val householdName: String?,
    /** account | household | investment | liability | account_record | goal | estate_document | contact | document | people | note */
    val kind: String,
    val recordId: UUID?,
    val title: String,
    val detail: String? = null,
)

/** Something that has to happen first, said as what to do. */
data class LifecycleBlocker(
    val householdId: UUID,
    val householdName: String,
    val code: String,
    val message: String,
)

/** A record and the table it lives in. */
data class HeldRecord(val type: String, val id: UUID, val title: String)

/**
 * Which records are whose, asked the same way by every lifecycle path.
 *
 * "Solely held" means every holder row names the person — the rule
 * `record_holder_member_ids` uses for sharing (V18), applied to leaving. "Jointly
 * held" means the person is one holder among others. Nothing else is theirs to
 * take or to erase: a record they typed in for somebody else belongs to that
 * somebody.
 *
 * The same queries run in two places. On the runtime connection, as the person,
 * they build a preview — and row-level security means every title they return is
 * one the person can already see, which for records they hold is all of them. On
 * the owner connection they drive the sweep that carries the preview out, and
 * there the answer is simply the truth. The two agree because a holder always
 * sees what they hold (docs/05 §3.1).
 */
internal class LifecycleRecords(private val jdbc: NamedParameterJdbcTemplate) {

    fun memberIds(householdId: UUID, userId: UUID): List<UUID> = jdbc.query(
        "select id from members where household_id = :hid and user_id = :uid and deleted_at is null",
        mapOf("hid" to householdId, "uid" to userId),
    ) { rs, _ -> rs.getObject("id", UUID::class.java) }

    fun solelyHeld(householdId: UUID, userId: UUID, memberIds: List<UUID>): List<HeldRecord> {
        val p = params(householdId, userId, memberIds)
        return jdbc.query(
            """
            select 'investment' as type, i.id, i.title from investments i
             where i.household_id = :hid
               and exists (select 1 from investment_ownerships o where o.investment_id = i.id and o.member_id in (:mids))
               and not exists (select 1 from investment_ownerships o where o.investment_id = i.id and o.member_id not in (:mids))
            union all
            select 'liability', l.id, l.title from liabilities l
             where l.household_id = :hid
               and exists (select 1 from liability_holders h where h.liability_id = l.id and h.member_id in (:mids))
               and not exists (select 1 from liability_holders h where h.liability_id = l.id and h.member_id not in (:mids))
            union all
            select 'account', a.id, a.label from accounts a
             where a.household_id = :hid
               and exists (select 1 from account_holders h where h.account_id = a.id and h.member_id in (:mids))
               and not exists (select 1 from account_holders h where h.account_id = a.id and h.member_id not in (:mids))
            union all
            select 'goal', g.id, g.name from goals g
             where g.household_id = :hid
               and (g.member_id in (:mids) or (g.member_id is null and g.created_by = :uid))
            union all
            select 'estate_document', e.id, e.title from estate_documents e
             where e.household_id = :hid and e.member_id in (:mids)
            union all
            select 'contact', c.id, c.name from contacts c
             where c.household_id = :hid and c.created_by = :uid
            order by 1, 3
            """.trimIndent(),
            p,
        ) { rs, _ -> HeldRecord(rs.getString("type"), rs.getObject("id", UUID::class.java), rs.getString("title")) }
    }

    fun jointlyHeld(householdId: UUID, memberIds: List<UUID>): List<HeldRecord> = jdbc.query(
        """
        select 'investment' as type, i.id, i.title from investments i
         where i.household_id = :hid
           and exists (select 1 from investment_ownerships o where o.investment_id = i.id and o.member_id in (:mids))
           and exists (select 1 from investment_ownerships o where o.investment_id = i.id and o.member_id not in (:mids))
        union all
        select 'liability', l.id, l.title from liabilities l
         where l.household_id = :hid
           and exists (select 1 from liability_holders h where h.liability_id = l.id and h.member_id in (:mids))
           and exists (select 1 from liability_holders h where h.liability_id = l.id and h.member_id not in (:mids))
        union all
        select 'account', a.id, a.label from accounts a
         where a.household_id = :hid
           and exists (select 1 from account_holders h where h.account_id = a.id and h.member_id in (:mids))
           and exists (select 1 from account_holders h where h.account_id = a.id and h.member_id not in (:mids))
        order by 1, 3
        """.trimIndent(),
        params(householdId, null, memberIds),
    ) { rs, _ -> HeldRecord(rs.getString("type"), rs.getObject("id", UUID::class.java), rs.getString("title")) }

    /**
     * Documents that go where these records go: uploaded by the person, and
     * attached to nothing that stays behind. A statement they uploaded for a
     * holding that stays is part of that holding's proof and stays with it.
     */
    fun documentsFollowing(householdId: UUID, userId: UUID, records: List<HeldRecord>): List<HeldRecord> {
        val ids = records.map { it.id }.ifEmpty { listOf(NOBODY) }
        return jdbc.query(
            """
            select 'document' as type, d.id, d.file_name as title from documents d
             where d.household_id = :hid and d.uploaded_by = :uid
               and not exists (select 1 from document_links dl
                               where dl.document_id = d.id and dl.entity_id not in (:ids))
            order by d.file_name
            """.trimIndent(),
            mapOf("hid" to householdId, "uid" to userId, "ids" to ids),
        ) { rs, _ -> HeldRecord(rs.getString("type"), rs.getObject("id", UUID::class.java), rs.getString("title")) }
    }

    /**
     * Of these records, the ones private to their holders (docs/05 §3.1): what
     * nobody but the person could see. Everything else was shared with the
     * household or with named people, and belongs to them too.
     */
    fun privateOnes(records: List<HeldRecord>): List<HeldRecord> {
        if (records.isEmpty()) return emptyList()
        val private = mutableSetOf<UUID>()
        records.groupBy { it.type }.forEach { (type, rows) ->
            val table = RECORD_TABLES[type] ?: return@forEach
            private += jdbc.query(
                "select id from $table where id in (:ids) and visibility = 'private'",
                mapOf("ids" to rows.map { it.id }),
            ) { rs, _ -> rs.getObject("id", UUID::class.java) }
        }
        return records.filter { it.id in private }
    }

    /**
     * Documents that go with erased private records when what else the person
     * held stays with the household: uploaded by the person, attached to nothing
     * that stays, and either private or attached to something erased. A paper
     * shared with the household and attached to nothing is the household's.
     */
    fun documentsFollowingPrivate(householdId: UUID, userId: UUID, erased: List<HeldRecord>): List<HeldRecord> {
        val ids = erased.map { it.id }.ifEmpty { listOf(NOBODY) }
        return jdbc.query(
            """
            select 'document' as type, d.id, d.file_name as title from documents d
             where d.household_id = :hid and d.uploaded_by = :uid
               and not exists (select 1 from document_links dl
                               where dl.document_id = d.id and dl.entity_id not in (:ids))
               and (d.visibility = 'private'
                    or exists (select 1 from document_links dl where dl.document_id = d.id))
            order by d.file_name
            """.trimIndent(),
            mapOf("hid" to householdId, "uid" to userId, "ids" to ids),
        ) { rs, _ -> HeldRecord(rs.getString("type"), rs.getObject("id", UUID::class.java), rs.getString("title")) }
    }

    /** Other people who hold a joint record, by name, for "your share passes to …". */
    fun otherHolders(type: String, recordId: UUID, memberIds: List<UUID>): List<String> {
        val (table, column) = holderTable(type)
        return jdbc.query(
            """
            select m.display_name from $table h join members m on m.id = h.member_id
             where h.$column = :rid and h.member_id not in (:mids)
             order by m.display_name
            """.trimIndent(),
            mapOf("rid" to recordId, "mids" to memberIds.ifEmpty { listOf(NOBODY) }),
        ) { rs, _ -> rs.getString("display_name") }
    }

    /** Active people with a login, other than [userId]. */
    fun otherPeopleWithLogin(householdId: UUID, userId: UUID): Int = jdbc.queryForObject(
        """
        select count(*) from household_memberships
         where household_id = :hid and status = 'active' and user_id <> :uid
        """.trimIndent(),
        mapOf("hid" to householdId, "uid" to userId),
        Int::class.java,
    ) ?: 0

    /**
     * Who carries a household on when its only owner goes. The successor the
     * owner named, if they are still here and able to act; otherwise the admin
     * who has been here longest. Null when there is nobody it would be right to
     * hand it to without asking — and then the owner is asked to choose.
     */
    fun nextOwner(householdId: UUID, userId: UUID): Handover {
        val otherOwner = jdbc.queryForObject(
            """
            select exists (select 1 from household_memberships hm
                            where hm.household_id = :hid and hm.status = 'active' and hm.role = 'owner'
                              and hm.user_id <> :uid
                              and not exists (select 1 from member_memorials mm
                                              where mm.household_id = hm.household_id
                                                and mm.user_id = hm.user_id and mm.reversed_at is null))
            """.trimIndent(),
            mapOf("hid" to householdId, "uid" to userId), Boolean::class.java,
        ) == true
        if (otherOwner) return Handover.NotNeeded

        val candidates = jdbc.query(
            """
            select hm.user_id, m.display_name,
                   (s.successor_member_id is not null) as named
              from household_memberships hm
              join members m on m.household_id = hm.household_id and m.user_id = hm.user_id
                            and m.deleted_at is null
              left join household_successors s
                     on s.household_id = hm.household_id and s.successor_member_id = m.id
                    and s.named_by = :uid
             where hm.household_id = :hid and hm.status = 'active' and hm.user_id <> :uid
               and (s.successor_member_id is not null or hm.role = 'admin')
               and hm.role <> 'advisor'
               and not exists (select 1 from member_memorials mm
                               where mm.household_id = hm.household_id
                                 and mm.user_id = hm.user_id and mm.reversed_at is null)
             order by (s.successor_member_id is not null) desc, hm.joined_at, hm.created_at
             limit 1
            """.trimIndent(),
            mapOf("hid" to householdId, "uid" to userId),
        ) { rs, _ ->
            Handover.To(rs.getObject("user_id", UUID::class.java), rs.getString("display_name"), rs.getBoolean("named"))
        }
        return candidates.firstOrNull() ?: Handover.NobodyChosen
    }

    sealed interface Handover {
        data object NotNeeded : Handover
        data object NobodyChosen : Handover
        data class To(val userId: UUID, val name: String, val named: Boolean) : Handover
    }

    /**
     * Passes one holder's part of a joint record to the others, in proportion to
     * what they already hold, so the whole still adds to a hundred — the database
     * checks that at commit (V3, V10). Rounding leftovers go to the largest
     * remaining holder, which is the smallest change anyone would notice.
     */
    fun passShare(type: String, recordId: UUID, memberIds: List<UUID>) {
        val (table, column) = holderTable(type)
        val pct = when (type) {
            "investment" -> "share_pct"
            "liability" -> "responsibility_pct"
            else -> null
        }
        if (pct == null) {
            jdbc.update(
                "delete from $table where $column = :rid and member_id in (:mids)",
                mapOf("rid" to recordId, "mids" to memberIds),
            )
            return
        }
        val remaining = jdbc.query(
            "select id, $pct as pct from $table where $column = :rid and member_id not in (:mids) order by $pct desc, id",
            mapOf("rid" to recordId, "mids" to memberIds),
        ) { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getBigDecimal("pct") }
        if (remaining.isEmpty()) return
        val held = remaining.fold(BigDecimal.ZERO) { sum, (_, value) -> sum + value }
        val hundred = BigDecimal("100.00")
        // Never below a hundredth: a holder is a holder, and the column refuses zero.
        val shares = remaining.map { (id, value) ->
            id to value.multiply(hundred).divide(held, 2, RoundingMode.DOWN).max(BigDecimal("0.01"))
        }.toMutableList()
        val leftover = hundred - shares.fold(BigDecimal.ZERO) { sum, (_, value) -> sum + value }
        shares[0] = shares[0].first to shares[0].second + leftover

        jdbc.update(
            "delete from $table where $column = :rid and member_id in (:mids)",
            mapOf("rid" to recordId, "mids" to memberIds),
        )
        shares.forEach { (id, value) ->
            jdbc.update("update $table set $pct = :pct where id = :id", mapOf("pct" to value, "id" to id))
        }
    }

    private fun holderTable(type: String) = when (type) {
        "investment" -> "investment_ownerships" to "investment_id"
        "liability" -> "liability_holders" to "liability_id"
        "account" -> "account_holders" to "account_id"
        else -> throw IllegalArgumentException("no holders for $type")
    }

    private fun params(householdId: UUID, userId: UUID?, memberIds: List<UUID>) = mapOf(
        "hid" to householdId,
        "uid" to (userId ?: NOBODY),
        "mids" to memberIds.ifEmpty { listOf(NOBODY) },
    )

    companion object {
        /** Stands in for an empty list, which `in ()` cannot take. Matches no row. */
        val NOBODY: UUID = UUID(0, 0)

        private val RECORD_TABLES = mapOf(
            "investment" to "investments", "liability" to "liabilities", "account" to "accounts",
            "goal" to "goals", "estate_document" to "estate_documents", "contact" to "contacts",
            "document" to "documents",
        )
    }
}
