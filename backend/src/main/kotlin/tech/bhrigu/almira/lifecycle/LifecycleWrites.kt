package tech.bhrigu.almira.lifecycle

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * The writes that end someone's part in a household, on the OWNER connection.
 *
 * Only [AccountPurge] and [DepartureCompletion] construct this, and only with the
 * owner data source: these statements delete other people's view of records and
 * rewrite who holds what, which no request is allowed to do and no row-level
 * security policy describes. What makes them safe is what feeds them — the
 * record lists come from [LifecycleRecords], the same rule the person saw in
 * their preview — and that each runs inside one transaction, so a failure
 * leaves the household exactly as it was.
 */
internal class LifecycleWrites(private val jdbc: NamedParameterJdbcTemplate) {

    /**
     * Hard-deletes records and everything that points at them without a foreign
     * key. Returns the storage keys of deleted documents, which the caller
     * removes from storage only after the transaction commits.
     */
    fun erase(records: List<HeldRecord>, documents: List<HeldRecord>): List<String> {
        val byType = records.groupBy({ it.type }, { it.id })
        val allIds = (records + documents).map { it.id }
        if (allIds.isEmpty()) return emptyList()

        // Rows that name a record by id alone. Cleared first, so nothing is left
        // pointing at a record that no longer exists.
        val ids = mapOf("ids" to allIds)
        jdbc.update("delete from record_visibility_grants where record_id in (:ids)", ids)
        jdbc.update("delete from sealed_values where record_id in (:ids)", ids)
        jdbc.update("delete from guest_share_items where record_id in (:ids)", ids)
        jdbc.update("delete from document_links where entity_id in (:ids)", ids)
        jdbc.update("delete from contact_links where entity_id in (:ids)", ids)
        jdbc.update("delete from record_confirmations where record_id in (:ids)", ids)
        jdbc.update("delete from record_confirmation_nudges where record_id in (:ids)", ids)
        jdbc.update("delete from custom_fields where owner_type = 'record' and owner_id in (:ids)", ids)

        byType["investment"]?.let {
            // A renewal made from one of these keeps its own history; it just no
            // longer says where it came from.
            jdbc.update(
                "update investments set rolled_from_id = null where rolled_from_id in (:ids) and id not in (:ids)",
                mapOf("ids" to it),
            )
            jdbc.update("delete from investments where id in (:ids)", mapOf("ids" to it))
        }
        byType["liability"]?.let { jdbc.update("delete from liabilities where id in (:ids)", mapOf("ids" to it)) }
        byType["account"]?.let { jdbc.update("delete from accounts where id in (:ids)", mapOf("ids" to it)) }
        byType["goal"]?.let { jdbc.update("delete from goals where id in (:ids)", mapOf("ids" to it)) }
        byType["estate_document"]?.let {
            jdbc.update("delete from estate_documents where id in (:ids)", mapOf("ids" to it))
        }
        byType["contact"]?.let { jdbc.update("delete from contacts where id in (:ids)", mapOf("ids" to it)) }

        if (documents.isEmpty()) return emptyList()
        val docIds = mapOf("ids" to documents.map { it.id })
        val keys = jdbc.query("select storage_key from documents where id in (:ids)", docIds) { rs, _ ->
            rs.getString("storage_key")
        }
        jdbc.update("update documents set supersedes_id = null where supersedes_id in (:ids)", docIds)
        jdbc.update("delete from documents where id in (:ids)", docIds)
        return keys
    }

    /**
     * Before a member row goes, the records that name them and belong to other
     * people keep the name as text. Ravi's nomination of his wife is Ravi's
     * record, and it should still say who he nominated.
     *
     * Owner's decision (2026-09-15), on an erasure: *the name stays — a
     * nomination naming Lakshmi is a fact about the record owner's arrangement.
     * Two conditions: it must not stay linked to the erased account, and no
     * contact details survive — the name only, as the other person wrote it.*
     * So: the name the record itself was given, or else the name the household
     * knew them by on its member list — never anything from their account; the
     * member link cleared. [removeContactTraces] does the rest of it for an
     * erasure. Flagged for counsel (docs/23).
     */
    fun keepNamesOnOthersRecords(memberIds: List<UUID>) {
        if (memberIds.isEmpty()) return
        val p = mapOf("mids" to memberIds)
        jdbc.update(
            """
            update investment_nominees n
               set nominee_name = coalesce(n.nominee_name, m.display_name), member_id = null
              from members m
             where m.id = n.member_id and n.member_id in (:mids)
            """.trimIndent(),
            p,
        )
        jdbc.update(
            """
            update estate_roles r
               set person_name = coalesce(r.person_name, m.display_name), member_id = null
              from members m
             where m.id = r.member_id and r.member_id in (:mids)
            """.trimIndent(),
            p,
        )
        jdbc.update(
            """
            update estate_beneficiaries b
               set person_name = coalesce(b.person_name, m.display_name), member_id = null
              from members m
             where m.id = b.member_id and b.member_id in (:mids)
            """.trimIndent(),
            p,
        )
    }

    /**
     * What an erasure takes off other people's records besides the link: the
     * contact card and the free text.
     *
     * Owner's decision (2026-09-16), setting the default while counsel is asked:
     * *the contact card should go — that's contact data, which we already decided
     * doesn't survive. Free-text notes are different from a name: a name is one
     * fact the other person recorded, a note can contain health, money or a
     * family dispute. Until counsel answers, redact free text that names the
     * erased person rather than keep it. Easier to restore than to un-disclose.*
     *
     * So, on the records that named them: the contact card an estate role
     * pointed at is deleted (with what it was linked to, by cascade), and a
     * relationship or note that spells their name is cleared. The name itself
     * stays, kept by [keepNamesOnOthersRecords]. Free text that does not name
     * them is the other person's writing about their own arrangement and is left
     * alone; text elsewhere in the household is not searched for their name.
     *
     * Runs BEFORE the link is cleared — it needs `member_id` to know which rows
     * are about this person, and the member row to know the name to look for.
     */
    fun removeContactTraces(memberIds: List<UUID>) {
        if (memberIds.isEmpty()) return
        val p = mapOf("mids" to memberIds)
        // The card is where a number or an address would be, so the card goes.
        jdbc.update(
            """
            delete from contacts
             where id in (select contact_id from estate_roles
                           where member_id in (:mids) and contact_id is not null)
            """.trimIndent(),
            p,
        )
        // Anything naming the person, in text the other person wrote on a record about them.
        NAMED_TEXT.forEach { (table, alias, columns) ->
            columns.forEach { column ->
                jdbc.update(
                    """
                    update $table $alias
                       set $column = null
                      from members m
                     where m.id = $alias.member_id and $alias.member_id in (:mids)
                       and $alias.$column is not null
                       and ($alias.$column ilike '%' || m.display_name || '%'
                            or ($alias.${nameColumn(table)} is not null
                                and $alias.$column ilike '%' || $alias.${nameColumn(table)} || '%'))
                    """.trimIndent(),
                    p,
                )
            }
        }
    }

    private fun nameColumn(table: String) = if (table == "investment_nominees") "nominee_name" else "person_name"

    /**
     * What a person made in a household that is not a record of theirs: the
     * links, requests and templates that only make sense with them in it. Their
     * name comes off anything that stays.
     */
    fun detachPerson(householdId: UUID, userId: UUID) {
        val p = mapOf("hid" to householdId, "uid" to userId)
        jdbc.update("delete from guest_shares where household_id = :hid and created_by = :uid", p)
        jdbc.update("delete from investment_templates where household_id = :hid and created_by = :uid", p)
        // Unreadable to anyone but them: a sealed field is sealed with their key.
        jdbc.update("delete from sealed_values where household_id = :hid and sealed_by = :uid", p)
        jdbc.update("delete from e2e_keys where household_id = :hid and user_id = :uid", p)
        jdbc.update("delete from emergency_requests where household_id = :hid and requested_by = :uid", p)
        jdbc.update("delete from household_successors where household_id = :hid and named_by = :uid", p)
        jdbc.update("delete from record_confirmation_nudges where household_id = :hid and user_id = :uid", p)
        CREATED_BY.forEach { (table, column) ->
            jdbc.update(
                "update $table set $column = null where household_id = :hid and $column = :uid",
                p,
            )
        }
        CHILD_CREATED_BY.forEach { (table, column, parentJoin) ->
            jdbc.update("update $table set $column = null where $column = :uid and $parentJoin", p)
        }
    }

    /**
     * What an erased person held for a household that stays (docs/05 §12.7,
     * V136): a new member row marked `former_since`, named "Former member" and
     * carrying nothing else about anyone, takes over their holder rows; then
     * their own member rows go, and with them everything that was about them as
     * a person there — emergency contacts either way, requests and heir plans
     * about them, grants to them, their tax pack links, memorial, departure and
     * key-holder asks. Returns the former member's id, or null when they held
     * nothing that stays.
     */
    fun becomeFormerMember(householdId: UUID, memberIds: List<UUID>): UUID? {
        if (memberIds.isEmpty()) return null
        val p = mapOf("mids" to memberIds)
        val holds = jdbc.queryForObject(
            """
            select exists (select 1 from investment_ownerships where member_id in (:mids))
                or exists (select 1 from liability_holders where member_id in (:mids))
                or exists (select 1 from account_holders where member_id in (:mids))
                or exists (select 1 from goals where member_id in (:mids))
                or exists (select 1 from estate_documents where member_id in (:mids))
                or exists (select 1 from lost_money_checks where member_id in (:mids))
            """.trimIndent(),
            p, Boolean::class.java,
        ) == true
        var former: UUID? = null
        if (holds) {
            former = jdbc.queryForObject(
                """
                insert into members (household_id, display_name, former_since)
                values (:hid, 'Former member', now()) returning id
                """.trimIndent(),
                mapOf("hid" to householdId), UUID::class.java,
            )
            val moved = mapOf("mids" to memberIds, "former" to former)
            listOf(
                "investment_ownerships", "liability_holders", "account_holders", "goals", "estate_documents",
                "lost_money_checks",
            ).forEach { table ->
                jdbc.update("update $table set member_id = :former where member_id in (:mids)", moved)
            }
        }
        keepNamesOnOthersRecords(memberIds)
        jdbc.update("delete from members where id in (:mids)", p)
        return former
    }

    /** Hands the owner role on, and says so in the log. */
    fun makeOwner(householdId: UUID, userId: UUID, reason: String) {
        jdbc.update(
            """
            update household_memberships set role = 'owner'
             where household_id = :hid and user_id = :uid and status = 'active'
            """.trimIndent(),
            mapOf("hid" to householdId, "uid" to userId),
        )
        systemAudit(householdId, "household.owner.handover", "household", householdId, mapOf("to" to userId, "reason" to reason))
    }

    /**
     * When nobody was named and there is no admin, the longest-standing person
     * still here. A household with people in it is never left with nobody who
     * can run it; the alternative would be erasing their records with it.
     */
    fun longestStanding(householdId: UUID, excluding: UUID): UUID? = jdbc.query(
        """
        select hm.user_id from household_memberships hm
         where hm.household_id = :hid and hm.status = 'active' and hm.user_id <> :uid
           and hm.role <> 'advisor'
           and not exists (select 1 from member_memorials mm
                           where mm.household_id = hm.household_id and mm.user_id = hm.user_id
                             and mm.reversed_at is null)
         order by hm.joined_at, hm.created_at limit 1
        """.trimIndent(),
        mapOf("hid" to householdId, "uid" to excluding),
    ) { rs, _ -> rs.getObject("user_id", UUID::class.java) }.firstOrNull()

    /** An audit line with no actor: the sweep did this, on a promise the person made. */
    fun systemAudit(householdId: UUID?, action: String, entityType: String, entityId: UUID?, diff: Map<String, Any?>) {
        jdbc.update(
            """
            insert into activity_log (household_id, actor_user_id, action, entity_type, entity_id, diff)
            values (:hid, null, :action, :type, :id, cast(:diff as jsonb))
            """.trimIndent(),
            mapOf(
                "hid" to householdId, "action" to action, "type" to entityType, "id" to entityId,
                "diff" to com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(diff),
            ),
        )
    }

    private companion object {
        /** Free text another person wrote on a record about someone, with the row's own name column. */
        val NAMED_TEXT = listOf(
            Triple("investment_nominees", "n", listOf("relationship")),
            Triple("estate_roles", "r", listOf("note")),
            Triple("estate_beneficiaries", "b", listOf("relationship", "note")),
        )

        /** Nullable "who made this" columns on household-scoped tables. */
        val CREATED_BY = listOf(
            "accounts" to "created_by", "contacts" to "created_by", "custom_fields" to "created_by",
            "emergency_contacts" to "created_by", "estate_documents" to "created_by",
            "exchange_rates" to "created_by", "goals" to "created_by", "investment_types" to "created_by",
            "investments" to "created_by", "invitations" to "invited_by", "liabilities" to "created_by",
            "provider_connections" to "created_by", "record_visibility_grants" to "created_by",
            "reminders" to "created_by", "documents" to "uploaded_by", "emergency_requests" to "vetoed_by",
            "record_confirmations" to "confirmed_by", "record_confirmations" to "snoozed_by",
        )

        /** The same, on tables that reach their household through a parent. */
        val CHILD_CREATED_BY = listOf(
            Triple("valuations", "created_by",
                "investment_id in (select id from investments where household_id = :hid)"),
            Triple("transactions", "created_by",
                "investment_id in (select id from investments where household_id = :hid)"),
            Triple("liability_balances", "created_by",
                "liability_id in (select id from liabilities where household_id = :hid)"),
        )
    }
}
