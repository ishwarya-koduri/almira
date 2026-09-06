package tech.bhrigu.almira.goal

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID

data class FundingSource(
    val investmentId: UUID,
    val title: String,
    val allocationPct: BigDecimal,
    val contribution: BigDecimal,
)

data class GoalRow(
    val id: UUID,
    val householdId: UUID,
    val name: String,
    val targetAmount: BigDecimal,
    val targetDate: LocalDate?,
    val priority: Int,
    val memberId: UUID?,
    val memberName: String?,
    val icon: String?,
    val notes: String?,
    val status: String,
    val visibility: String,
    /** When the goal was set — the start of the window `onTrack` measures. */
    val startedOn: LocalDate,
    val funded: BigDecimal,
    val holdingCount: Int,
    val version: Int,
    val fundedBy: List<FundingSource> = emptyList(),
    val visibleToMemberIds: List<UUID> = emptyList(),
)

@Repository
class GoalRepository(private val jdbc: NamedParameterJdbcTemplate) {

    fun insert(
        id: UUID,
        householdId: UUID,
        name: String,
        targetAmount: BigDecimal,
        targetDate: LocalDate?,
        priority: Int,
        memberId: UUID?,
        icon: String?,
        notes: String?,
        visibility: String,
        createdBy: UUID,
    ) = jdbc.update(
        """
        insert into goals
          (id, household_id, name, target_amount, target_date, priority,
           member_id, icon, notes, visibility, created_by)
        values (:id, :hid, :name, :target, :date, :priority,
                :memberId, :icon, :notes, :visibility, :createdBy)
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("id", id).addValue("hid", householdId).addValue("name", name)
            .addValue("target", targetAmount).addValue("date", targetDate)
            .addValue("priority", priority).addValue("memberId", memberId)
            .addValue("icon", icon).addValue("notes", notes)
            .addValue("visibility", visibility).addValue("createdBy", createdBy),
    )

    fun update(
        id: UUID,
        version: Int,
        name: String?,
        targetAmount: BigDecimal?,
        targetDate: LocalDate?,
        priority: Int?,
        notes: String?,
        status: String?,
    ): Int = jdbc.update(
        """
        update goals set
          name          = coalesce(cast(:name as text), name),
          target_amount = coalesce(cast(:target as numeric), target_amount),
          target_date   = coalesce(cast(:date as date), target_date),
          priority      = coalesce(cast(:priority as int), priority),
          notes         = coalesce(cast(:notes as text), notes),
          status        = coalesce(cast(:status as text), status)
        where id = :id and version = :version and deleted_at is null
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("id", id).addValue("version", version).addValue("name", name)
            .addValue("target", targetAmount).addValue("date", targetDate)
            .addValue("priority", priority).addValue("notes", notes).addValue("status", status),
    )

    fun updateVisibility(id: UUID, visibility: String): Int = jdbc.update(
        "update goals set visibility = :visibility where id = :id and deleted_at is null",
        mapOf("id" to id, "visibility" to visibility),
    )

    fun softDelete(householdId: UUID, id: UUID): Int = jdbc.update(
        """
        update goals set deleted_at = now()
        where id = :id and household_id = :hid and deleted_at is null
        """.trimIndent(),
        mapOf("id" to id, "hid" to householdId),
    )

    fun map(goalId: UUID, investmentId: UUID, allocationPct: BigDecimal): Int = jdbc.update(
        """
        insert into investment_goals (goal_id, investment_id, allocation_pct)
        values (:goal, :investment, :pct)
        on conflict (goal_id, investment_id) do update set allocation_pct = excluded.allocation_pct
        """.trimIndent(),
        mapOf("goal" to goalId, "investment" to investmentId, "pct" to allocationPct),
    )

    /**
     * How much of a holding is already spoken for, ignoring one goal.
     *
     * Excluding the goal being edited matters: re-allocating a holding from 40%
     * to 60% within the same goal must not count the existing 40% against
     * itself.
     */
    fun allocatedElsewhere(investmentId: UUID, excludingGoalId: UUID): BigDecimal = jdbc.queryForObject(
        """
        select coalesce(sum(allocation_pct), 0) from investment_goals
        where investment_id = :investment and goal_id <> :goal
        """.trimIndent(),
        mapOf("investment" to investmentId, "goal" to excludingGoalId),
        BigDecimal::class.java,
    ) ?: BigDecimal.ZERO

    fun unmap(goalId: UUID, investmentId: UUID): Int = jdbc.update(
        "delete from investment_goals where goal_id = :goal and investment_id = :investment",
        mapOf("goal" to goalId, "investment" to investmentId),
    )

    fun replaceVisibilityGrants(householdId: UUID, id: UUID, memberIds: List<UUID>, actor: UUID) {
        jdbc.update(
            "delete from record_visibility_grants where record_type = 'goal' and record_id = :id",
            mapOf("id" to id),
        )
        memberIds.distinct().forEach { memberId ->
            jdbc.update(
                """
                insert into record_visibility_grants
                  (household_id, record_type, record_id, member_id, created_by)
                values (:hid, 'goal', :id, :memberId, :actor)
                """.trimIndent(),
                MapSqlParameterSource().addValue("hid", householdId).addValue("id", id)
                    .addValue("memberId", memberId).addValue("actor", actor),
            )
        }
    }

    // --- reads ---------------------------------------------------------------

    fun list(householdId: UUID, status: String?): List<GoalRow> {
        val sql = buildString {
            append(SELECT)
            if (status != null) append(" and g.status = :status")
            append(" order by g.priority, g.target_date nulls last, g.name")
        }
        return withRelations(
            jdbc.query(sql, mapOf("hid" to householdId, "status" to status), mapper),
        )
    }

    fun find(householdId: UUID, id: UUID): GoalRow? = withRelations(
        jdbc.query("$SELECT and g.id = :id", mapOf("hid" to householdId, "id" to id), mapper),
    ).firstOrNull()

    /**
     * Holdings the caller can see that no goal they can see points at.
     *
     * Both halves matter: a holding allocated only to someone else's private
     * goal reads as unallocated here, which is correct — telling them otherwise
     * would reveal that the private goal exists.
     */
    fun unallocated(householdId: UUID): List<Pair<UUID, String>> = jdbc.query(
        """
        select i.id, i.title
        from investments i
        where i.household_id = :hid and i.deleted_at is null and i.status = 'active'
          and not exists (
            select 1 from investment_goals ig where ig.investment_id = i.id
          )
        order by i.title
        """.trimIndent(),
        mapOf("hid" to householdId),
    ) { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getString("title") }

    private fun withRelations(rows: List<GoalRow>): List<GoalRow> {
        if (rows.isEmpty()) return rows
        val ids = rows.map { it.id }

        val sources = jdbc.query(
            """
            select ig.goal_id, ig.investment_id, ig.allocation_pct, i.title,
                   round(coalesce(iv.effective_value, 0) * ig.allocation_pct / 100.0, 4) as contribution
            from investment_goals ig
            join investments i on i.id = ig.investment_id
            left join investment_value iv on iv.investment_id = ig.investment_id
            where ig.goal_id in (:ids)
            order by contribution desc
            """.trimIndent(),
            mapOf("ids" to ids),
        ) { rs, _ ->
            rs.getObject("goal_id", UUID::class.java) to FundingSource(
                rs.getObject("investment_id", UUID::class.java),
                rs.getString("title"),
                rs.getBigDecimal("allocation_pct"),
                rs.getBigDecimal("contribution"),
            )
        }.groupBy({ it.first }, { it.second })

        val grants = jdbc.query(
            """
            select record_id, member_id from record_visibility_grants
            where record_type = 'goal' and record_id in (:ids)
            """.trimIndent(),
            mapOf("ids" to ids),
        ) { rs, _ ->
            rs.getObject("record_id", UUID::class.java) to rs.getObject("member_id", UUID::class.java)
        }.groupBy({ it.first }, { it.second })

        return rows.map {
            it.copy(fundedBy = sources[it.id].orEmpty(), visibleToMemberIds = grants[it.id].orEmpty())
        }
    }

    private val mapper = { rs: ResultSet, _: Int ->
        GoalRow(
            id = rs.getObject("id", UUID::class.java),
            householdId = rs.getObject("household_id", UUID::class.java),
            name = rs.getString("name"),
            targetAmount = rs.getBigDecimal("target_amount"),
            targetDate = rs.getDate("target_date")?.toLocalDate(),
            priority = rs.getInt("priority"),
            memberId = rs.getObject("member_id", UUID::class.java),
            memberName = rs.getString("member_name"),
            icon = rs.getString("icon"),
            notes = rs.getString("notes"),
            status = rs.getString("status"),
            visibility = rs.getString("visibility"),
            startedOn = rs.getTimestamp("created_at").toInstant()
                .atZone(java.time.ZoneOffset.UTC).toLocalDate(),
            funded = rs.getBigDecimal("funded") ?: BigDecimal.ZERO,
            holdingCount = rs.getInt("holding_count"),
            version = rs.getInt("version"),
        )
    }

    private companion object {
        const val SELECT = """
            select g.*, m.display_name as member_name,
                   f.funded, f.holding_count
            from goals g
            left join members m on m.id = g.member_id
            left join goal_funding f on f.goal_id = g.id
            where g.household_id = :hid and g.deleted_at is null
        """
    }
}
