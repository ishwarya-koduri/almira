package tech.bhrigu.almira.account

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

data class AccountHolder(val memberId: UUID, val memberName: String?, val holderType: String)

data class AccountRow(
    val id: UUID,
    val householdId: UUID,
    val institutionId: UUID?,
    val institutionName: String?,
    val accountKind: String,
    val label: String,
    val numberMasked: String?,
    /** True when the full number is stored encrypted and can be revealed. */
    val hasFullNumber: Boolean,
    val ifsc: String?,
    val notes: String?,
    val visibility: String,
    val version: Int,
    val createdAt: Instant,
    val holders: List<AccountHolder> = emptyList(),
    val visibleToMemberIds: List<UUID> = emptyList(),
    val linkedInvestmentCount: Int = 0,
)

@Repository
class AccountRepository(private val jdbc: NamedParameterJdbcTemplate) {

    /**
     * Client-supplied id, for the same reason investments use one: a Private
     * account has no holders for the moment between this statement and the next,
     * so RETURNING — which is subject to the SELECT policy — would find nothing.
     */
    fun insert(
        id: UUID,
        householdId: UUID,
        institutionId: UUID?,
        accountKind: String,
        label: String,
        numberMasked: String?,
        numberEnc: ByteArray?,
        ifsc: String?,
        notes: String?,
        visibility: String,
        createdBy: UUID,
    ) {
        jdbc.update(
            """
            insert into accounts
              (id, household_id, institution_id, account_kind, label,
               number_masked, number_enc, ifsc, notes, visibility, created_by)
            values
              (:id, :hid, :institutionId, :kind, :label,
               :masked, :enc, :ifsc, :notes, :visibility, :createdBy)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("hid", householdId)
                .addValue("institutionId", institutionId)
                .addValue("kind", accountKind)
                .addValue("label", label)
                .addValue("masked", numberMasked)
                .addValue("enc", numberEnc)
                .addValue("ifsc", ifsc)
                .addValue("notes", notes)
                .addValue("visibility", visibility)
                .addValue("createdBy", createdBy),
        )
    }

    fun replaceHolders(accountId: UUID, holders: List<Pair<UUID, String>>) {
        jdbc.update("delete from account_holders where account_id = :id", mapOf("id" to accountId))
        holders.forEach { (memberId, holderType) ->
            jdbc.update(
                """
                insert into account_holders (account_id, member_id, holder_type)
                values (:id, :memberId, :holderType)
                """.trimIndent(),
                mapOf("id" to accountId, "memberId" to memberId, "holderType" to holderType),
            )
        }
    }

    fun update(
        id: UUID,
        version: Int,
        label: String?,
        institutionId: UUID?,
        ifsc: String?,
        notes: String?,
        numberMasked: String?,
        numberEnc: ByteArray?,
        clearFullNumber: Boolean,
    ): Int = jdbc.update(
        """
        update accounts set
          label          = coalesce(:label, label),
          institution_id = coalesce(:institutionId, institution_id),
          ifsc           = coalesce(:ifsc, ifsc),
          notes          = coalesce(:notes, notes),
          number_masked  = coalesce(cast(:masked as text), number_masked),
          -- Casts are required, not stylistic: PostgreSQL cannot infer a type
          -- for a bare parameter next to an untyped NULL inside a CASE, and
          -- fails the whole statement as a grammar error.
          number_enc     = case
                             when cast(:clearEnc as boolean) then null
                             when cast(:enc as bytea) is not null then cast(:enc as bytea)
                             else number_enc
                           end
        where id = :id and version = :version and deleted_at is null
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("id", id)
            .addValue("version", version)
            .addValue("label", label)
            .addValue("institutionId", institutionId)
            .addValue("ifsc", ifsc)
            .addValue("notes", notes)
            .addValue("masked", numberMasked)
            .addValue("enc", numberEnc)
            .addValue("clearEnc", clearFullNumber),
    )

    fun updateVisibility(id: UUID, visibility: String): Int = jdbc.update(
        "update accounts set visibility = :visibility where id = :id and deleted_at is null",
        mapOf("id" to id, "visibility" to visibility),
    )

    fun softDelete(id: UUID): Int = jdbc.update(
        "update accounts set deleted_at = now() where id = :id and deleted_at is null",
        mapOf("id" to id),
    )

    fun replaceVisibilityGrants(householdId: UUID, accountId: UUID, memberIds: List<UUID>, actor: UUID) {
        jdbc.update(
            "delete from record_visibility_grants where record_type = 'account' and record_id = :id",
            mapOf("id" to accountId),
        )
        memberIds.distinct().forEach { memberId ->
            jdbc.update(
                """
                insert into record_visibility_grants
                  (household_id, record_type, record_id, member_id, created_by)
                values (:hid, 'account', :id, :memberId, :actor)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("hid", householdId)
                    .addValue("id", accountId)
                    .addValue("memberId", memberId)
                    .addValue("actor", actor),
            )
        }
    }

    // --- reads ---------------------------------------------------------------

    fun list(householdId: UUID): List<AccountRow> =
        withRelations(jdbc.query("$SELECT order by a.created_at", mapOf("hid" to householdId), mapper))

    fun find(householdId: UUID, id: UUID): AccountRow? =
        withRelations(
            jdbc.query("$SELECT and a.id = :id", mapOf("hid" to householdId, "id" to id), mapper),
        ).firstOrNull()

    /**
     * The encrypted number, fetched only when someone has re-authenticated to
     * see it. Kept off the row mapper on purpose: a ciphertext that is never
     * loaded cannot be logged, serialised into a response, or captured in a
     * heap dump by accident.
     */
    fun encryptedNumber(householdId: UUID, id: UUID): ByteArray? = jdbc.query(
        """
        select number_enc from accounts
        where household_id = :hid and id = :id and deleted_at is null
        """.trimIndent(),
        mapOf("hid" to householdId, "id" to id),
    ) { rs, _ -> rs.getBytes("number_enc") }.firstOrNull()

    private fun withRelations(rows: List<AccountRow>): List<AccountRow> {
        if (rows.isEmpty()) return rows
        val ids = rows.map { it.id }

        val holders = jdbc.query(
            """
            select h.account_id, h.member_id, h.holder_type, m.display_name
            from account_holders h
            left join members m on m.id = h.member_id
            where h.account_id in (:ids)
            """.trimIndent(),
            mapOf("ids" to ids),
        ) { rs, _ ->
            rs.getObject("account_id", UUID::class.java) to AccountHolder(
                rs.getObject("member_id", UUID::class.java),
                rs.getString("display_name"),
                rs.getString("holder_type"),
            )
        }.groupBy({ it.first }, { it.second })

        val grants = jdbc.query(
            """
            select record_id, member_id from record_visibility_grants
            where record_type = 'account' and record_id in (:ids)
            """.trimIndent(),
            mapOf("ids" to ids),
        ) { rs, _ ->
            rs.getObject("record_id", UUID::class.java) to rs.getObject("member_id", UUID::class.java)
        }.groupBy({ it.first }, { it.second })

        // Counted through RLS, so it reflects the investments this viewer can
        // actually see — an account does not disclose how many private holdings
        // someone else has attached to it.
        val linked = jdbc.query(
            """
            select account_id, count(*) as n from investments
            where account_id in (:ids) and deleted_at is null
            group by account_id
            """.trimIndent(),
            mapOf("ids" to ids),
        ) { rs, _ -> rs.getObject("account_id", UUID::class.java) to rs.getInt("n") }.toMap()

        return rows.map {
            it.copy(
                holders = holders[it.id].orEmpty(),
                visibleToMemberIds = grants[it.id].orEmpty(),
                linkedInvestmentCount = linked[it.id] ?: 0,
            )
        }
    }

    private val mapper = { rs: ResultSet, _: Int ->
        AccountRow(
            id = rs.getObject("id", UUID::class.java),
            householdId = rs.getObject("household_id", UUID::class.java),
            institutionId = rs.getObject("institution_id", UUID::class.java),
            institutionName = rs.getString("institution_name"),
            accountKind = rs.getString("account_kind"),
            label = rs.getString("label"),
            numberMasked = rs.getString("number_masked"),
            hasFullNumber = rs.getBoolean("has_full_number"),
            ifsc = rs.getString("ifsc"),
            notes = rs.getString("notes"),
            visibility = rs.getString("visibility"),
            version = rs.getInt("version"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
    }

    private companion object {
        /**
         * number_enc is never selected here — only whether one exists. The
         * ciphertext leaves the database exactly once, on the reveal path.
         */
        const val SELECT = """
            select a.id, a.household_id, a.institution_id, a.account_kind, a.label,
                   a.number_masked, a.ifsc, a.notes, a.visibility, a.version, a.created_at,
                   (a.number_enc is not null) as has_full_number,
                   inst.name as institution_name
            from accounts a
            left join institutions inst on inst.id = a.institution_id
            where a.household_id = :hid and a.deleted_at is null
        """
    }
}
