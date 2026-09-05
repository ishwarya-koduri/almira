package tech.bhrigu.almira.liability

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

data class LiabilityHolderShare(
    val memberId: UUID,
    val memberName: String?,
    val responsibilityPct: BigDecimal,
    val holderType: String,
)

data class SecuredAsset(val investmentId: UUID, val title: String, val note: String?)

data class LiabilityRow(
    val id: UUID,
    val householdId: UUID,
    val kind: String,
    val title: String,
    val lenderId: UUID?,
    val lenderName: String?,
    val principal: BigDecimal?,
    val outstanding: BigDecimal,
    val balanceAsOf: LocalDate?,
    val interestRate: BigDecimal?,
    val emiAmount: BigDecimal?,
    val emiDay: Int?,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val status: String,
    val attributes: Map<String, Any?>,
    val notes: String?,
    val visibility: String,
    val version: Int,
    val createdAt: Instant,
    val holders: List<LiabilityHolderShare> = emptyList(),
    val securedBy: List<SecuredAsset> = emptyList(),
    val visibleToMemberIds: List<UUID> = emptyList(),
)

data class BalanceRow(
    val id: UUID,
    val asOfDate: LocalDate,
    val outstanding: BigDecimal,
    val source: String,
    val note: String?,
)

@Repository
class LiabilityRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val mapper: ObjectMapper,
) {

    /** Client-supplied id, for the same reason as investments and accounts. */
    fun insert(
        id: UUID,
        householdId: UUID,
        kind: String,
        title: String,
        lenderId: UUID?,
        principal: BigDecimal?,
        outstanding: BigDecimal,
        interestRate: BigDecimal?,
        emiAmount: BigDecimal?,
        emiDay: Int?,
        startDate: LocalDate?,
        endDate: LocalDate?,
        attributes: Map<String, Any?>,
        notes: String?,
        visibility: String,
        createdBy: UUID,
    ) {
        jdbc.update(
            """
            insert into liabilities
              (id, household_id, kind, title, institution_id, principal, outstanding,
               interest_rate, emi_amount, emi_day, start_date, end_date,
               attributes, notes, visibility, created_by)
            values
              (:id, :hid, :kind, :title, :lenderId, :principal, :outstanding,
               :rate, :emiAmount, :emiDay, :startDate, :endDate,
               cast(:attributes as jsonb), :notes, :visibility, :createdBy)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("hid", householdId)
                .addValue("kind", kind)
                .addValue("title", title)
                .addValue("lenderId", lenderId)
                .addValue("principal", principal)
                .addValue("outstanding", outstanding)
                .addValue("rate", interestRate)
                .addValue("emiAmount", emiAmount)
                .addValue("emiDay", emiDay)
                .addValue("startDate", startDate)
                .addValue("endDate", endDate)
                .addValue("attributes", mapper.writeValueAsString(attributes))
                .addValue("notes", notes)
                .addValue("visibility", visibility)
                .addValue("createdBy", createdBy),
        )
    }

    fun replaceHolders(liabilityId: UUID, holders: List<Pair<UUID, BigDecimal>>) {
        jdbc.update(
            "delete from liability_holders where liability_id = :id",
            mapOf("id" to liabilityId),
        )
        holders.forEach { (memberId, pct) ->
            jdbc.update(
                """
                insert into liability_holders
                  (liability_id, member_id, responsibility_pct, holder_type)
                values (:id, :memberId, :pct, :holderType)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("id", liabilityId)
                    .addValue("memberId", memberId)
                    .addValue("pct", pct)
                    .addValue("holderType", if (holders.size > 1) "joint" else "primary"),
            )
        }
    }

    fun update(
        id: UUID,
        version: Int,
        title: String?,
        lenderId: UUID?,
        principal: BigDecimal?,
        interestRate: BigDecimal?,
        emiAmount: BigDecimal?,
        emiDay: Int?,
        endDate: LocalDate?,
        attributes: Map<String, Any?>?,
        notes: String?,
        status: String?,
    ): Int = jdbc.update(
        """
        update liabilities set
          title          = coalesce(cast(:title as text), title),
          institution_id = coalesce(cast(:lenderId as uuid), institution_id),
          principal      = coalesce(cast(:principal as numeric), principal),
          interest_rate  = coalesce(cast(:rate as numeric), interest_rate),
          emi_amount     = coalesce(cast(:emiAmount as numeric), emi_amount),
          emi_day        = coalesce(cast(:emiDay as int), emi_day),
          end_date       = coalesce(cast(:endDate as date), end_date),
          attributes     = coalesce(cast(:attributes as jsonb), attributes),
          notes          = coalesce(cast(:notes as text), notes),
          status         = coalesce(cast(:status as text), status)
        where id = :id and version = :version and deleted_at is null
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("id", id)
            .addValue("version", version)
            .addValue("title", title)
            .addValue("lenderId", lenderId)
            .addValue("principal", principal)
            .addValue("rate", interestRate)
            .addValue("emiAmount", emiAmount)
            .addValue("emiDay", emiDay)
            .addValue("endDate", endDate)
            .addValue("attributes", attributes?.let { mapper.writeValueAsString(it) })
            .addValue("notes", notes)
            .addValue("status", status),
    )

    /**
     * A balance is recorded as a dated snapshot AND written back to the row, so
     * the trend has history and the current figure is never stale.
     */
    fun recordBalance(
        liabilityId: UUID,
        asOf: LocalDate,
        outstanding: BigDecimal,
        note: String?,
        createdBy: UUID,
    ) {
        jdbc.update(
            """
            insert into liability_balances (liability_id, as_of_date, outstanding, note, created_by)
            values (:id, :asOf, :outstanding, :note, :by)
            on conflict (liability_id, as_of_date)
              do update set outstanding = excluded.outstanding, note = excluded.note
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", liabilityId)
                .addValue("asOf", asOf)
                .addValue("outstanding", outstanding)
                .addValue("note", note)
                .addValue("by", createdBy),
        )
        jdbc.update(
            """
            update liabilities set outstanding = :outstanding
            where id = :id
              and not exists (select 1 from liability_balances b
                              where b.liability_id = :id and b.as_of_date > :asOf)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", liabilityId)
                .addValue("outstanding", outstanding)
                .addValue("asOf", asOf),
        )
    }

    fun balances(liabilityId: UUID): List<BalanceRow> = jdbc.query(
        """
        select id, as_of_date, outstanding, source, note from liability_balances
        where liability_id = :id order by as_of_date desc
        """.trimIndent(),
        mapOf("id" to liabilityId),
    ) { rs, _ ->
        BalanceRow(
            rs.getObject("id", UUID::class.java),
            rs.getDate("as_of_date").toLocalDate(),
            rs.getBigDecimal("outstanding"),
            rs.getString("source"),
            rs.getString("note"),
        )
    }

    fun updateVisibility(id: UUID, visibility: String): Int = jdbc.update(
        "update liabilities set visibility = :visibility where id = :id and deleted_at is null",
        mapOf("id" to id, "visibility" to visibility),
    )

    fun replaceVisibilityGrants(householdId: UUID, id: UUID, memberIds: List<UUID>, actor: UUID) {
        jdbc.update(
            "delete from record_visibility_grants where record_type = 'liability' and record_id = :id",
            mapOf("id" to id),
        )
        memberIds.distinct().forEach { memberId ->
            jdbc.update(
                """
                insert into record_visibility_grants
                  (household_id, record_type, record_id, member_id, created_by)
                values (:hid, 'liability', :id, :memberId, :actor)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("hid", householdId).addValue("id", id)
                    .addValue("memberId", memberId).addValue("actor", actor),
            )
        }
    }

    fun softDelete(id: UUID): Int = jdbc.update(
        "update liabilities set deleted_at = now() where id = :id and deleted_at is null",
        mapOf("id" to id),
    )

    fun linkAsset(liabilityId: UUID, investmentId: UUID, note: String?): Int = jdbc.update(
        """
        insert into asset_liability_links (liability_id, investment_id, note)
        values (:lid, :iid, :note)
        on conflict (liability_id, investment_id) do update set note = excluded.note
        """.trimIndent(),
        mapOf("lid" to liabilityId, "iid" to investmentId, "note" to note),
    )

    fun unlinkAsset(liabilityId: UUID, investmentId: UUID): Int = jdbc.update(
        "delete from asset_liability_links where liability_id = :lid and investment_id = :iid",
        mapOf("lid" to liabilityId, "iid" to investmentId),
    )

    /**
     * What is owed against one asset — the "encumbered" line on its detail page.
     * Scoped by RLS on both ends, so a loan the viewer may not see contributes
     * nothing and is not hinted at.
     */
    fun encumbranceOf(investmentIds: Collection<UUID>): Map<UUID, BigDecimal> {
        if (investmentIds.isEmpty()) return emptyMap()
        return jdbc.query(
            """
            select k.investment_id, sum(lc.outstanding) as owed
            from asset_liability_links k
            join liability_current lc on lc.liability_id = k.liability_id
            where k.investment_id in (:ids) and lc.status = 'active'
            group by k.investment_id
            """.trimIndent(),
            mapOf("ids" to investmentIds),
        ) { rs, _ ->
            rs.getObject("investment_id", UUID::class.java) to rs.getBigDecimal("owed")
        }.toMap()
    }

    // --- reads ---------------------------------------------------------------

    fun list(householdId: UUID, status: String?): List<LiabilityRow> {
        val sql = buildString {
            append(SELECT)
            if (status != null) append(" and l.status = :status")
            append(" order by lc.outstanding desc nulls last")
        }
        return withRelations(
            jdbc.query(sql, mapOf("hid" to householdId, "status" to status), mapper()),
        )
    }

    fun find(householdId: UUID, id: UUID): LiabilityRow? = withRelations(
        jdbc.query("$SELECT and l.id = :id", mapOf("hid" to householdId, "id" to id), mapper()),
    ).firstOrNull()

    private fun withRelations(rows: List<LiabilityRow>): List<LiabilityRow> {
        if (rows.isEmpty()) return rows
        val ids = rows.map { it.id }

        val holders = jdbc.query(
            """
            select h.liability_id, h.member_id, h.responsibility_pct, h.holder_type,
                   m.display_name
            from liability_holders h
            left join members m on m.id = h.member_id
            where h.liability_id in (:ids)
            order by h.responsibility_pct desc
            """.trimIndent(),
            mapOf("ids" to ids),
        ) { rs, _ ->
            rs.getObject("liability_id", UUID::class.java) to LiabilityHolderShare(
                rs.getObject("member_id", UUID::class.java),
                rs.getString("display_name"),
                rs.getBigDecimal("responsibility_pct"),
                rs.getString("holder_type"),
            )
        }.groupBy({ it.first }, { it.second })

        val secured = jdbc.query(
            """
            select k.liability_id, k.investment_id, k.note, i.title
            from asset_liability_links k
            join investments i on i.id = k.investment_id
            where k.liability_id in (:ids)
            """.trimIndent(),
            mapOf("ids" to ids),
        ) { rs, _ ->
            rs.getObject("liability_id", UUID::class.java) to SecuredAsset(
                rs.getObject("investment_id", UUID::class.java),
                rs.getString("title"),
                rs.getString("note"),
            )
        }.groupBy({ it.first }, { it.second })

        val grants = jdbc.query(
            """
            select record_id, member_id from record_visibility_grants
            where record_type = 'liability' and record_id in (:ids)
            """.trimIndent(),
            mapOf("ids" to ids),
        ) { rs, _ ->
            rs.getObject("record_id", UUID::class.java) to rs.getObject("member_id", UUID::class.java)
        }.groupBy({ it.first }, { it.second })

        return rows.map {
            it.copy(
                holders = holders[it.id].orEmpty(),
                securedBy = secured[it.id].orEmpty(),
                visibleToMemberIds = grants[it.id].orEmpty(),
            )
        }
    }

    private fun mapper() = { rs: ResultSet, _: Int ->
        LiabilityRow(
            id = rs.getObject("id", UUID::class.java),
            householdId = rs.getObject("household_id", UUID::class.java),
            kind = rs.getString("kind"),
            title = rs.getString("title"),
            lenderId = rs.getObject("institution_id", UUID::class.java),
            lenderName = rs.getString("lender_name"),
            principal = rs.getBigDecimal("principal"),
            outstanding = rs.getBigDecimal("current_outstanding") ?: BigDecimal.ZERO,
            balanceAsOf = rs.getDate("balance_as_of")?.toLocalDate(),
            interestRate = rs.getBigDecimal("interest_rate"),
            emiAmount = rs.getBigDecimal("emi_amount"),
            emiDay = rs.getObject("emi_day")?.let { rs.getInt("emi_day") },
            startDate = rs.getDate("start_date")?.toLocalDate(),
            endDate = rs.getDate("end_date")?.toLocalDate(),
            status = rs.getString("status"),
            attributes = this.mapper.readValue(rs.getString("attributes")),
            notes = rs.getString("notes"),
            visibility = rs.getString("visibility"),
            version = rs.getInt("version"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
    }

    private companion object {
        const val SELECT = """
            select l.*, inst.name as lender_name,
                   lc.outstanding as current_outstanding, lc.balance_as_of
            from liabilities l
            join liability_current lc on lc.liability_id = l.id
            left join institutions inst on inst.id = l.institution_id
            where l.household_id = :hid and l.deleted_at is null
        """
    }
}
