package tech.bhrigu.almira.household

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class HouseholdRow(
    val id: UUID,
    val name: String,
    val baseCurrency: String,
    val defaultVisibility: String,
    val myRole: String,
    val myMemberId: UUID?,
    val memberCount: Int,
    val version: Int,
    /** The caller is memorialised here: they can see, and change nothing (docs/05 §12). */
    val readOnly: Boolean = false,
)

data class MemberRow(
    val id: UUID,
    val householdId: UUID,
    val userId: UUID?,
    val displayName: String,
    val relationship: String?,
    val dateOfBirth: LocalDate?,
    val isMinor: Boolean,
    val isManaged: Boolean,
    val isMe: Boolean,
    val role: String?,
    val version: Int,
    /** When they were marked as having passed away, while that stands. */
    val memorialisedAt: Instant? = null,
    val diedOn: LocalDate? = null,
)

@Repository
class HouseholdRepository(private val jdbc: NamedParameterJdbcTemplate) {

    /**
     * Creation goes through a SECURITY DEFINER function rather than plain
     * inserts. Bootstrapping is a chicken-and-egg problem for RLS -- you cannot
     * insert the membership row that would authorise inserting it -- and this
     * is the single, auditable place that resolves it atomically.
     */
    fun bootstrap(name: String, defaultVisibility: String, displayName: String): Pair<UUID, UUID> =
        jdbc.queryForObject(
            """
            select household_id, member_id
            from app.bootstrap_household(:name, :visibility, :displayName)
            """.trimIndent(),
            mapOf(
                "name" to name,
                "visibility" to defaultVisibility,
                "displayName" to displayName,
            ),
        ) { rs, _ ->
            rs.getObject("household_id", UUID::class.java) to
                rs.getObject("member_id", UUID::class.java)
        }!!

    /** RLS restricts this to households the caller actually belongs to. */
    fun listMine(userId: UUID): List<HouseholdRow> = jdbc.query(
        HOUSEHOLD_SELECT + " order by h.created_at",
        mapOf("userId" to userId),
        householdMapper,
    )

    /**
     * The database's own write predicate, the one every insert and update
     * policy checks — asked directly, so a service can refuse before it does
     * anything the policy would only refuse afterwards. Guest sessions are
     * refused by it too, which a role check alone would miss.
     */
    fun canWrite(householdId: UUID): Boolean = jdbc.queryForObject(
        "select app.can_write_household(:hid)", mapOf("hid" to householdId), Boolean::class.java,
    ) == true

    /** The same for app.can_administer_household (owner or admin, never a guest). */
    fun canAdminister(householdId: UUID): Boolean = jdbc.queryForObject(
        "select app.can_administer_household(:hid)", mapOf("hid" to householdId), Boolean::class.java,
    ) == true

    fun find(householdId: UUID, userId: UUID): HouseholdRow? = jdbc.query(
        "$HOUSEHOLD_SELECT and h.id = :hid",
        mapOf("userId" to userId, "hid" to householdId),
        householdMapper,
    ).firstOrNull()

    fun update(householdId: UUID, name: String?, defaultVisibility: String?, version: Int): Int =
        jdbc.update(
            """
            update households set
              name               = coalesce(:name, name),
              default_visibility = coalesce(:visibility, default_visibility)
            where id = :id and version = :version and deleted_at is null
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", householdId)
                .addValue("name", name)
                .addValue("visibility", defaultVisibility)
                .addValue("version", version),
        )

    // --- members -------------------------------------------------------------

    fun members(householdId: UUID, userId: UUID): List<MemberRow> = jdbc.query(
        MEMBER_SELECT + " order by (m.user_id = :userId) desc, m.created_at",
        mapOf("hid" to householdId, "userId" to userId),
        memberMapper,
    )

    fun member(householdId: UUID, memberId: UUID, userId: UUID): MemberRow? = jdbc.query(
        "$MEMBER_SELECT and m.id = :mid",
        mapOf("hid" to householdId, "userId" to userId, "mid" to memberId),
        memberMapper,
    ).firstOrNull()

    fun addMember(
        householdId: UUID,
        displayName: String,
        relationship: String?,
        dateOfBirth: LocalDate?,
        notes: String?,
        diedOn: LocalDate? = null,
    ): UUID = jdbc.queryForObject(
        """
        insert into members (household_id, display_name, relationship, date_of_birth, notes, died_on)
        values (:hid, :name, :relationship, :dob, :notes, :diedOn)
        returning id
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("hid", householdId)
            .addValue("name", displayName)
            .addValue("relationship", relationship)
            .addValue("dob", dateOfBirth)
            .addValue("notes", notes)
            .addValue("diedOn", diedOn),
        UUID::class.java,
    )!!

    fun updateMember(
        memberId: UUID,
        displayName: String?,
        relationship: String?,
        dateOfBirth: LocalDate?,
        version: Int,
        diedOn: LocalDate? = null,
    ): Int = jdbc.update(
        """
        update members set
          display_name  = coalesce(:name, display_name),
          relationship  = coalesce(:relationship, relationship),
          date_of_birth = coalesce(:dob, date_of_birth),
          died_on       = coalesce(:diedOn, died_on)
        where id = :id and version = :version and deleted_at is null
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("id", memberId)
            .addValue("name", displayName)
            .addValue("relationship", relationship)
            .addValue("dob", dateOfBirth)
            .addValue("diedOn", diedOn)
            .addValue("version", version),
    )

    fun softDeleteMember(memberId: UUID): Int = jdbc.update(
        "update members set deleted_at = now() where id = :id and deleted_at is null",
        mapOf("id" to memberId),
    )

    /**
     * What stands in the way of removing a managed member: the records naming
     * them that the caller can see, and the number they cannot. Counted by a
     * definer function (V41), because under row-level security the second number
     * is always zero -- which is how a member whose holdings nobody present could
     * see used to be removed from under them.
     */
    fun managedMemberHoldings(memberId: UUID): Pair<Int, Int> = jdbc.queryForObject(
        "select visible_count, hidden_count from app.managed_member_holdings(:id)",
        mapOf("id" to memberId),
    ) { rs, _ -> rs.getInt("visible_count") to rs.getInt("hidden_count") } ?: (0 to 0)

    /** Who recorded the records the caller cannot see: people who can move them. */
    fun recordersOfHiddenHoldings(memberId: UUID): List<UUID> = jdbc.query(
        "select app.recorders_of_hidden_holdings(:id) as user_id",
        mapOf("id" to memberId),
    ) { rs, _ -> rs.getObject("user_id", UUID::class.java) }

    private companion object {
        /**
         * `my_role` comes from the caller's own membership row. Role is a
         * capability, never a visibility grant, so it is reported but never used
         * to widen a read.
         */
        const val HOUSEHOLD_SELECT = """
            select h.id, h.name, h.base_currency, h.default_visibility, h.version,
                   hm.role as my_role, app.is_memorialised_in(h.id) as read_only,
                   (select m.id from members m
                     where m.household_id = h.id and m.user_id = :userId
                       and m.deleted_at is null limit 1) as my_member_id,
                   (select count(*) from members m
                     where m.household_id = h.id and m.deleted_at is null) as member_count
            from households h
            join household_memberships hm
              on hm.household_id = h.id and hm.user_id = :userId and hm.status = 'active'
            where h.deleted_at is null
        """

        const val MEMBER_SELECT = """
            select m.*, app.is_minor(m.date_of_birth) as is_minor,
                   hm.role as role,
                   (select mm.marked_at from member_memorials mm
                     where mm.member_id = m.id and mm.reversed_at is null) as memorialised_at
            from members m
            left join household_memberships hm
              on hm.household_id = m.household_id and hm.user_id = m.user_id
                 and hm.status = 'active'
            where m.household_id = :hid and m.deleted_at is null
        """
    }

    private val householdMapper = { rs: ResultSet, _: Int ->
        HouseholdRow(
            id = rs.getObject("id", UUID::class.java),
            name = rs.getString("name"),
            baseCurrency = rs.getString("base_currency"),
            defaultVisibility = rs.getString("default_visibility"),
            myRole = rs.getString("my_role"),
            myMemberId = rs.getObject("my_member_id", UUID::class.java),
            memberCount = rs.getInt("member_count"),
            version = rs.getInt("version"),
            readOnly = rs.getBoolean("read_only"),
        )
    }

    private val memberMapper = { rs: ResultSet, _: Int ->
        val userId = rs.getObject("user_id", UUID::class.java)
        MemberRow(
            id = rs.getObject("id", UUID::class.java),
            householdId = rs.getObject("household_id", UUID::class.java),
            userId = userId,
            displayName = rs.getString("display_name"),
            relationship = rs.getString("relationship"),
            dateOfBirth = rs.getDate("date_of_birth")?.toLocalDate(),
            isMinor = rs.getBoolean("is_minor"),
            isManaged = userId == null,
            isMe = false,
            role = rs.getString("role"),
            version = rs.getInt("version"),
            memorialisedAt = rs.getTimestamp("memorialised_at")?.toInstant(),
            diedOn = rs.getDate("died_on")?.toLocalDate(),
        )
    }
}
