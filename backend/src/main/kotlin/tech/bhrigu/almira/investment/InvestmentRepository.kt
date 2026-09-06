package tech.bhrigu.almira.investment

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class OwnerShare(val memberId: UUID, val memberName: String?, val sharePct: BigDecimal, val holderType: String)

data class NomineeShare(
    val id: UUID,
    val memberId: UUID?,
    val name: String,
    val relationship: String?,
    val sharePct: BigDecimal,
)

data class InvestmentRow(
    val id: UUID,
    val householdId: UUID,
    val typeId: UUID,
    val typeCode: String,
    val typeLabel: String,
    val typeIcon: String?,
    val categoryCode: String,
    val categoryLabel: String,
    val color: String,
    val title: String,
    val status: String,
    val investedAmount: BigDecimal?,
    val currency: String,
    val quantity: BigDecimal?,
    val unit: String?,
    /** fifo | average | manual — how a disposal's cost is attributed. */
    val costBasisMethod: String,
    val startDate: LocalDate?,
    val maturityDate: LocalDate?,
    val storageLocation: String?,
    val institutionId: UUID?,
    val institutionName: String?,
    val accountId: UUID?,
    val accountLabel: String?,
    val attributes: Map<String, Any?>,
    val notes: String?,
    val visibility: String,
    val isInContinuity: Boolean,
    val effectiveValue: BigDecimal?,
    val valueBasis: String,
    val valuedOn: LocalDate?,
    val lastVerifiedAt: Instant?,
    val version: Int,
    val createdAt: Instant,
    val owners: List<OwnerShare> = emptyList(),
    val nominees: List<NomineeShare> = emptyList(),
    val visibleToMemberIds: List<UUID> = emptyList(),
    /**
     * What is owed against this asset. You own the flat; not all of its value is
     * yours yet. Null when nothing is secured against it.
     */
    val encumbrance: BigDecimal? = null,
)

data class ValuationRow(
    val id: UUID,
    val asOfDate: LocalDate,
    val value: BigDecimal,
    val quantity: BigDecimal?,
    val source: String,
    val note: String?,
)

data class InvestmentFilter(
    val typeIds: List<UUID>? = null,
    val categoryCode: String? = null,
    val memberId: UUID? = null,
    val institutionId: UUID? = null,
    val accountId: UUID? = null,
    val unlinked: Boolean = false,
    val status: String? = null,
    val visibility: String? = null,
    val query: String? = null,
    val limit: Int = 200,
    val offset: Int = 0,
)

@Repository
class InvestmentRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val mapper: ObjectMapper,
) {

    /**
     * The id is supplied by the caller rather than returned by the database.
     *
     * `INSERT ... RETURNING` is subject to the SELECT policy, and a new Private
     * record has no ownership rows for the instant between this statement and
     * the next — so by the rules of the privacy model nobody may read it, not
     * even the person creating it. Generating the id up front avoids the
     * problem, and gives offline capture a stable id and a natural idempotency
     * key at the same time (docs/05 §10).
     */
    fun insert(
        id: UUID,
        householdId: UUID,
        typeId: UUID,
        title: String,
        investedAmount: BigDecimal?,
        currency: String,
        quantity: BigDecimal?,
        unit: String?,
        startDate: LocalDate?,
        maturityDate: LocalDate?,
        storageLocation: String?,
        institutionId: UUID?,
        accountId: UUID?,
        attributes: Map<String, Any?>,
        notes: String?,
        visibility: String,
        isInContinuity: Boolean,
        createdBy: UUID,
    ) {
        jdbc.update(
            """
            insert into investments
              (id, household_id, type_id, title, invested_amount, currency, quantity, unit,
               start_date, maturity_date, storage_location, institution_id, account_id,
               attributes, notes, visibility, is_in_continuity, created_by)
            values
              (:id, :hid, :typeId, :title, :amount, :currency, :quantity, :unit,
               :startDate, :maturityDate, :storage, :institutionId, :accountId,
               cast(:attributes as jsonb), :notes, :visibility, :continuity, :createdBy)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("hid", householdId)
                .addValue("typeId", typeId)
                .addValue("title", title)
                .addValue("amount", investedAmount)
                .addValue("currency", currency)
                .addValue("quantity", quantity)
                .addValue("unit", unit)
                .addValue("startDate", startDate)
                .addValue("maturityDate", maturityDate)
                .addValue("storage", storageLocation)
                .addValue("institutionId", institutionId)
                .addValue("accountId", accountId)
                .addValue("attributes", mapper.writeValueAsString(attributes))
                .addValue("notes", notes)
                .addValue("visibility", visibility)
                .addValue("continuity", isInContinuity)
                .addValue("createdBy", createdBy),
        )
    }

    fun replaceOwners(investmentId: UUID, owners: List<Pair<UUID, BigDecimal>>, holderType: String) {
        jdbc.update(
            "delete from investment_ownerships where investment_id = :id",
            mapOf("id" to investmentId),
        )
        owners.forEach { (memberId, share) ->
            jdbc.update(
                """
                insert into investment_ownerships (investment_id, member_id, share_pct, holder_type)
                values (:id, :memberId, :share, :holderType)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("id", investmentId)
                    .addValue("memberId", memberId)
                    .addValue("share", share)
                    .addValue("holderType", if (owners.size > 1) "joint" else holderType),
            )
        }
    }

    fun replaceVisibilityGrants(householdId: UUID, investmentId: UUID, memberIds: List<UUID>, actor: UUID) {
        jdbc.update(
            """
            delete from record_visibility_grants
            where record_type = 'investment' and record_id = :id
            """.trimIndent(),
            mapOf("id" to investmentId),
        )
        memberIds.distinct().forEach { memberId ->
            jdbc.update(
                """
                insert into record_visibility_grants
                  (household_id, record_type, record_id, member_id, created_by)
                values (:hid, 'investment', :id, :memberId, :actor)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("hid", householdId)
                    .addValue("id", investmentId)
                    .addValue("memberId", memberId)
                    .addValue("actor", actor),
            )
        }
    }

    fun update(
        id: UUID,
        version: Int,
        title: String?,
        investedAmount: BigDecimal?,
        quantity: BigDecimal?,
        unit: String?,
        startDate: LocalDate?,
        maturityDate: LocalDate?,
        storageLocation: String?,
        institutionId: UUID?,
        accountId: UUID?,
        attributes: Map<String, Any?>?,
        notes: String?,
        status: String?,
        isInContinuity: Boolean?,
    ): Int = jdbc.update(
        """
        update investments set
          title            = coalesce(:title, title),
          invested_amount  = coalesce(:amount, invested_amount),
          quantity         = coalesce(:quantity, quantity),
          unit             = coalesce(:unit, unit),
          start_date       = coalesce(:startDate, start_date),
          maturity_date    = coalesce(:maturityDate, maturity_date),
          storage_location = coalesce(:storage, storage_location),
          institution_id   = coalesce(:institutionId, institution_id),
          account_id       = coalesce(:accountId, account_id),
          attributes       = coalesce(cast(:attributes as jsonb), attributes),
          notes            = coalesce(:notes, notes),
          status           = coalesce(:status, status),
          is_in_continuity = coalesce(:continuity, is_in_continuity)
        where id = :id and version = :version and deleted_at is null
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("id", id)
            .addValue("version", version)
            .addValue("title", title)
            .addValue("amount", investedAmount)
            .addValue("quantity", quantity)
            .addValue("unit", unit)
            .addValue("startDate", startDate)
            .addValue("maturityDate", maturityDate)
            .addValue("storage", storageLocation)
            .addValue("institutionId", institutionId)
            .addValue("accountId", accountId)
            .addValue("attributes", attributes?.let { mapper.writeValueAsString(it) })
            .addValue("notes", notes)
            .addValue("status", status)
            .addValue("continuity", isInContinuity),
    )

    /**
     * Nominees are replaced wholesale rather than patched. A nominee list is a
     * legal instruction; "add one and hope the rest are still right" is how
     * shares end up totalling 130%.
     */
    fun replaceNominees(
        investmentId: UUID,
        nominees: List<Triple<UUID?, String?, Pair<String?, BigDecimal>>>,
    ) {
        jdbc.update(
            "delete from investment_nominees where investment_id = :id",
            mapOf("id" to investmentId),
        )
        nominees.forEach { (memberId, name, rest) ->
            val (relationship, share) = rest
            jdbc.update(
                """
                insert into investment_nominees
                  (investment_id, member_id, nominee_name, relationship, share_pct)
                values (:id, :memberId, :name, :relationship, :share)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("id", investmentId)
                    .addValue("memberId", memberId)
                    .addValue("name", name)
                    .addValue("relationship", relationship)
                    .addValue("share", share),
            )
        }
    }

    fun updateVisibility(id: UUID, visibility: String): Int = jdbc.update(
        "update investments set visibility = :visibility where id = :id and deleted_at is null",
        mapOf("id" to id, "visibility" to visibility),
    )

    fun softDelete(id: UUID): Int = jdbc.update(
        "update investments set deleted_at = now() where id = :id and deleted_at is null",
        mapOf("id" to id),
    )

    fun restore(id: UUID): Int = jdbc.update(
        "update investments set deleted_at = null where id = :id and deleted_at is not null",
        mapOf("id" to id),
    )

    fun markVerified(id: UUID): Int = jdbc.update(
        "update investments set last_verified_at = now() where id = :id and deleted_at is null",
        mapOf("id" to id),
    )

    // --- reads ---------------------------------------------------------------

    fun find(householdId: UUID, id: UUID): InvestmentRow? =
        jdbc.query("$SELECT and i.id = :id", mapOf("hid" to householdId, "id" to id), mapper())
            .firstOrNull()
            ?.let { withRelations(listOf(it)).first() }

    /** Exists but is invisible to the caller: RLS returns nothing, so this is null. */
    fun list(householdId: UUID, filter: InvestmentFilter): List<InvestmentRow> {
        val sql = buildString {
            append(SELECT)
            filter.typeIds?.takeIf { it.isNotEmpty() }?.let { append(" and i.type_id in (:typeIds)") }
            filter.categoryCode?.let { append(" and c.code = :categoryCode") }
            filter.institutionId?.let { append(" and i.institution_id = :institutionId") }
            filter.accountId?.let { append(" and i.account_id = :accountId") }
            if (filter.unlinked) append(" and i.account_id is null")
            filter.status?.let { append(" and i.status = :status") }
            filter.visibility?.let { append(" and i.visibility = :visibility") }
            filter.memberId?.let {
                append(
                    """
                     and exists (select 1 from investment_ownerships o
                                 where o.investment_id = i.id and o.member_id = :memberId)
                    """,
                )
            }
            filter.query?.let {
                append(
                    """
                     and (i.title ilike '%' || :query || '%'
                          or coalesce(inst.name,'') ilike '%' || :query || '%'
                          or i.attributes::text ilike '%' || :query || '%')
                    """,
                )
            }
            append(" order by i.created_at desc limit :limit offset :offset")
        }
        val params = MapSqlParameterSource()
            .addValue("hid", householdId)
            .addValue("typeIds", filter.typeIds)
            .addValue("categoryCode", filter.categoryCode)
            .addValue("institutionId", filter.institutionId)
            .addValue("accountId", filter.accountId)
            .addValue("status", filter.status)
            .addValue("visibility", filter.visibility)
            .addValue("memberId", filter.memberId)
            .addValue("query", filter.query)
            .addValue("limit", filter.limit)
            .addValue("offset", filter.offset)
        return withRelations(jdbc.query(sql, params, mapper()))
    }

    fun listTrash(householdId: UUID): List<InvestmentRow> =
        withRelations(
            jdbc.query(
                SELECT_BASE + " where i.household_id = :hid and i.deleted_at is not null" +
                    " order by i.deleted_at desc limit 200",
                mapOf("hid" to householdId),
                mapper(),
            ),
        )

    fun addValuation(
        investmentId: UUID,
        asOf: LocalDate,
        value: BigDecimal,
        quantity: BigDecimal?,
        note: String?,
        createdBy: UUID,
    ) = jdbc.update(
        """
        insert into valuations (investment_id, as_of_date, value, quantity, note, created_by)
        values (:id, :asOf, :value, :quantity, :note, :by)
        on conflict (investment_id, as_of_date)
          do update set value = excluded.value, quantity = excluded.quantity,
                        note = excluded.note, created_by = excluded.created_by
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("id", investmentId)
            .addValue("asOf", asOf)
            .addValue("value", value)
            .addValue("quantity", quantity)
            .addValue("note", note)
            .addValue("by", createdBy),
    )

    fun valuations(investmentId: UUID): List<ValuationRow> = jdbc.query(
        """
        select id, as_of_date, value, quantity, source, note from valuations
        where investment_id = :id order by as_of_date desc
        """.trimIndent(),
        mapOf("id" to investmentId),
    ) { rs, _ ->
        ValuationRow(
            rs.getObject("id", UUID::class.java),
            rs.getDate("as_of_date").toLocalDate(),
            rs.getBigDecimal("value"),
            rs.getBigDecimal("quantity"),
            rs.getString("source"),
            rs.getString("note"),
        )
    }

    // --- relations -----------------------------------------------------------

    /**
     * Owners and grants are fetched in one round trip for the whole page rather
     * than per row. RLS already scoped the parent set, and these child tables
     * inherit that scoping, so there is nothing extra to filter here.
     */
    private fun withRelations(rows: List<InvestmentRow>): List<InvestmentRow> {
        if (rows.isEmpty()) return rows
        val ids = rows.map { it.id }

        val owners = jdbc.query(
            """
            select o.investment_id, o.member_id, o.share_pct, o.holder_type, m.display_name
            from investment_ownerships o
            left join members m on m.id = o.member_id
            where o.investment_id in (:ids)
            order by o.share_pct desc
            """.trimIndent(),
            mapOf("ids" to ids),
        ) { rs, _ ->
            rs.getObject("investment_id", UUID::class.java) to OwnerShare(
                memberId = rs.getObject("member_id", UUID::class.java),
                memberName = rs.getString("display_name"),
                sharePct = rs.getBigDecimal("share_pct"),
                holderType = rs.getString("holder_type"),
            )
        }.groupBy({ it.first }, { it.second })

        val nominees = jdbc.query(
            """
            select n.id, n.investment_id, n.member_id, n.relationship, n.share_pct,
                   coalesce(m.display_name, n.nominee_name) as name
            from investment_nominees n
            left join members m on m.id = n.member_id
            where n.investment_id in (:ids)
            order by n.share_pct desc
            """.trimIndent(),
            mapOf("ids" to ids),
        ) { rs, _ ->
            rs.getObject("investment_id", UUID::class.java) to NomineeShare(
                id = rs.getObject("id", UUID::class.java),
                memberId = rs.getObject("member_id", UUID::class.java),
                name = rs.getString("name"),
                relationship = rs.getString("relationship"),
                sharePct = rs.getBigDecimal("share_pct"),
            )
        }.groupBy({ it.first }, { it.second })

        val grants = jdbc.query(
            """
            select record_id, member_id from record_visibility_grants
            where record_type = 'investment' and record_id in (:ids)
            """.trimIndent(),
            mapOf("ids" to ids),
        ) { rs, _ ->
            rs.getObject("record_id", UUID::class.java) to rs.getObject("member_id", UUID::class.java)
        }.groupBy({ it.first }, { it.second })

        // Both ends are RLS-scoped, so a loan the viewer cannot see adds nothing
        // here — an encumbrance figure must not become a way to infer that a
        // private debt exists, or roughly how large it is.
        val encumbrances = jdbc.query(
            """
            select k.investment_id, sum(lc.outstanding) as owed
            from asset_liability_links k
            join liability_current lc on lc.liability_id = k.liability_id
            where k.investment_id in (:ids) and lc.status = 'active'
            group by k.investment_id
            """.trimIndent(),
            mapOf("ids" to ids),
        ) { rs, _ ->
            rs.getObject("investment_id", UUID::class.java) to rs.getBigDecimal("owed")
        }.toMap()

        return rows.map {
            it.copy(
                owners = owners[it.id].orEmpty(),
                nominees = nominees[it.id].orEmpty(),
                visibleToMemberIds = grants[it.id].orEmpty(),
                encumbrance = encumbrances[it.id],
            )
        }
    }

    private fun mapper() = { rs: ResultSet, _: Int ->
        InvestmentRow(
            id = rs.getObject("id", UUID::class.java),
            householdId = rs.getObject("household_id", UUID::class.java),
            typeId = rs.getObject("type_id", UUID::class.java),
            typeCode = rs.getString("type_code"),
            typeLabel = rs.getString("type_label"),
            typeIcon = rs.getString("type_icon"),
            categoryCode = rs.getString("category_code"),
            categoryLabel = rs.getString("category_label"),
            color = rs.getString("category_color"),
            title = rs.getString("title"),
            status = rs.getString("status"),
            investedAmount = rs.getBigDecimal("invested_amount"),
            currency = rs.getString("currency"),
            quantity = rs.getBigDecimal("quantity"),
            unit = rs.getString("unit"),
            costBasisMethod = rs.getString("cost_basis_method") ?: "fifo",
            startDate = rs.getDate("start_date")?.toLocalDate(),
            maturityDate = rs.getDate("maturity_date")?.toLocalDate(),
            storageLocation = rs.getString("storage_location"),
            institutionId = rs.getObject("institution_id", UUID::class.java),
            institutionName = rs.getString("institution_name"),
            accountId = rs.getObject("account_id", UUID::class.java),
            accountLabel = rs.getString("account_label"),
            attributes = this.mapper.readValue(rs.getString("attributes")),
            notes = rs.getString("notes"),
            visibility = rs.getString("visibility"),
            isInContinuity = rs.getBoolean("is_in_continuity"),
            effectiveValue = rs.getBigDecimal("effective_value"),
            valueBasis = rs.getString("value_basis") ?: "unknown",
            valuedOn = rs.getDate("valued_on")?.toLocalDate(),
            lastVerifiedAt = rs.getTimestamp("last_verified_at")?.toInstant(),
            version = rs.getInt("version"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
    }

    private companion object {
        const val SELECT_BASE = """
            select i.*, t.code as type_code, t.label as type_label, t.icon as type_icon,
                   c.code as category_code, c.label as category_label,
                   coalesce(t.color, c.color) as category_color,
                   -- Falls back to the linked account's institution: a folio at
                   -- Zerodha is held at Zerodha whether or not the holding
                   -- names it directly.
                   coalesce(inst.name, acc_inst.name) as institution_name,
                   acc.label as account_label,
                   v.effective_value, v.value_basis, v.valued_on
            from investments i
            join investment_types t on t.id = i.type_id
            join asset_categories c on c.id = t.category_id
            left join institutions inst on inst.id = i.institution_id
            left join accounts acc on acc.id = i.account_id
            left join institutions acc_inst on acc_inst.id = acc.institution_id
            left join investment_value v on v.investment_id = i.id
        """
        const val SELECT = "$SELECT_BASE where i.household_id = :hid and i.deleted_at is null"
    }
}
