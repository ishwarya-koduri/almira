package tech.bhrigu.almira.estate

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

data class ContactLinkRow(
    val entityType: String,
    val entityId: UUID,
    val entityTitle: String?,
    val role: String?,
)

data class ContactRow(
    val id: UUID,
    val householdId: UUID,
    val kind: String,
    val name: String,
    val organisation: String?,
    val phone: String?,
    val email: String?,
    val address: String?,
    val notes: String?,
    val visibility: String,
    val version: Int,
    val createdBy: UUID?,
    val links: List<ContactLinkRow> = emptyList(),
)

@Repository
class ContactRepository(private val jdbc: NamedParameterJdbcTemplate) {

    fun insert(
        id: UUID,
        householdId: UUID,
        kind: String,
        name: String,
        organisation: String?,
        phone: String?,
        email: String?,
        address: String?,
        notes: String?,
        visibility: String,
        createdBy: UUID,
    ) = jdbc.update(
        """
        insert into contacts (id, household_id, kind, name, organisation, phone, email,
                              address, notes, visibility, created_by)
        values (:id, :hid, :kind, :name, :org, :phone, :email, :address, :notes,
                :visibility, :createdBy)
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("id", id).addValue("hid", householdId).addValue("kind", kind)
            .addValue("name", name).addValue("org", organisation).addValue("phone", phone)
            .addValue("email", email).addValue("address", address).addValue("notes", notes)
            .addValue("visibility", visibility).addValue("createdBy", createdBy),
    )

    fun update(
        id: UUID,
        version: Int,
        kind: String?,
        name: String?,
        organisation: String?,
        phone: String?,
        email: String?,
        address: String?,
        notes: String?,
        visibility: String?,
    ): Int = jdbc.update(
        """
        update contacts set
          kind         = coalesce(:kind, kind),
          name         = coalesce(:name, name),
          organisation = coalesce(:org, organisation),
          phone        = coalesce(:phone, phone),
          email        = coalesce(:email, email),
          address      = coalesce(:address, address),
          notes        = coalesce(:notes, notes),
          visibility   = coalesce(:visibility, visibility),
          version      = version + 1
        where id = :id and version = :version and deleted_at is null
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("id", id).addValue("version", version).addValue("kind", kind)
            .addValue("name", name).addValue("org", organisation).addValue("phone", phone)
            .addValue("email", email).addValue("address", address).addValue("notes", notes)
            .addValue("visibility", visibility),
    )

    fun softDelete(householdId: UUID, id: UUID): Int = jdbc.update(
        """
        update contacts set deleted_at = now()
        where id = :id and household_id = :hid and deleted_at is null
        """.trimIndent(),
        mapOf("id" to id, "hid" to householdId),
    )

    fun link(contactId: UUID, entityType: String, entityId: UUID, role: String?) = jdbc.update(
        """
        insert into contact_links (contact_id, entity_type, entity_id, role)
        values (:cid, :type, :eid, :role)
        on conflict (contact_id, entity_type, entity_id) do update set role = excluded.role
        """.trimIndent(),
        mapOf("cid" to contactId, "type" to entityType, "eid" to entityId, "role" to role),
    )

    fun unlink(contactId: UUID, entityType: String, entityId: UUID): Int = jdbc.update(
        """
        delete from contact_links
        where contact_id = :cid and entity_type = :type and entity_id = :eid
        """.trimIndent(),
        mapOf("cid" to contactId, "type" to entityType, "eid" to entityId),
    )

    fun find(householdId: UUID, id: UUID): ContactRow? =
        jdbc.query("$SELECT and c.id = :id", mapOf("hid" to householdId, "id" to id), mapper())
            .firstOrNull()?.let { withLinks(listOf(it)).first() }

    fun list(householdId: UUID, kind: String?, forEntity: Pair<String, UUID>?): List<ContactRow> {
        val sql = buildString {
            append(SELECT)
            if (kind != null) append(" and c.kind = :kind")
            if (forEntity != null) {
                append(
                    """
                     and exists (select 1 from contact_links l
                                 where l.contact_id = c.id
                                   and l.entity_type = :entityType and l.entity_id = :entityId)
                    """,
                )
            }
            append(" order by lower(c.name)")
        }
        val params = MapSqlParameterSource()
            .addValue("hid", householdId).addValue("kind", kind)
            .addValue("entityType", forEntity?.first).addValue("entityId", forEntity?.second)
        return withLinks(jdbc.query(sql, params, mapper()))
    }

    /**
     * Link rows are read under the caller's own RLS, and so are the records at
     * the far end — a link to a holding you cannot see comes back without a
     * title rather than not at all, so "the CA handles three things, one of
     * which isn't yours to see" stays honest without naming it.
     */
    private fun withLinks(rows: List<ContactRow>): List<ContactRow> {
        if (rows.isEmpty()) return rows
        val links = jdbc.query(
            """
            select l.contact_id, l.entity_type, l.entity_id, l.role,
                   coalesce(i.title, li.title, a.label, e.title, g.name) as entity_title
            from contact_links l
            left join investments      i  on l.entity_type = 'investment'      and i.id  = l.entity_id
            left join liabilities      li on l.entity_type = 'liability'       and li.id = l.entity_id
            left join accounts         a  on l.entity_type = 'account'         and a.id  = l.entity_id
            left join estate_documents e  on l.entity_type = 'estate_document' and e.id  = l.entity_id
            left join goals            g  on l.entity_type = 'goal'            and g.id  = l.entity_id
            where l.contact_id in (:ids)
            """.trimIndent(),
            mapOf("ids" to rows.map { it.id }),
        ) { rs, _ ->
            rs.getObject("contact_id", UUID::class.java) to ContactLinkRow(
                entityType = rs.getString("entity_type"),
                entityId = rs.getObject("entity_id", UUID::class.java),
                entityTitle = rs.getString("entity_title"),
                role = rs.getString("role"),
            )
        }.groupBy({ it.first }, { it.second })

        return rows.map { it.copy(links = links[it.id].orEmpty()) }
    }

    /**
     * Does this record exist *for you*? Read under the caller's own RLS, so a
     * link can never be used to confirm that something you may not see exists.
     */
    fun entityExists(householdId: UUID, entityType: String, entityId: UUID): Boolean {
        val table = when (entityType) {
            "investment" -> "investments"
            "liability" -> "liabilities"
            "account" -> "accounts"
            "estate_document" -> "estate_documents"
            "goal" -> "goals"
            else -> return false
        }
        return jdbc.queryForObject(
            "select exists (select 1 from $table where id = :id and household_id = :hid)",
            mapOf("id" to entityId, "hid" to householdId),
            Boolean::class.javaObjectType,
        ) == true
    }

    private fun mapper() = { rs: ResultSet, _: Int ->
        ContactRow(
            id = rs.getObject("id", UUID::class.java),
            householdId = rs.getObject("household_id", UUID::class.java),
            kind = rs.getString("kind"),
            name = rs.getString("name"),
            organisation = rs.getString("organisation"),
            phone = rs.getString("phone"),
            email = rs.getString("email"),
            address = rs.getString("address"),
            notes = rs.getString("notes"),
            visibility = rs.getString("visibility"),
            version = rs.getInt("version"),
            createdBy = rs.getObject("created_by", UUID::class.java),
        )
    }

    private companion object {
        const val SELECT = """
            select c.* from contacts c
            where c.household_id = :hid and c.deleted_at is null
        """
    }
}
