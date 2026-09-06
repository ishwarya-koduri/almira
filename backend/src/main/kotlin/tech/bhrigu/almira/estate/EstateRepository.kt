package tech.bhrigu.almira.estate

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID

data class EstateRoleRow(
    val id: UUID,
    val role: String,
    val memberId: UUID?,
    val contactId: UUID?,
    val name: String,
    val note: String?,
)

data class BeneficiaryRow(
    val id: UUID,
    val investmentId: UUID?,
    val investmentTitle: String?,
    val liabilityId: UUID?,
    val memberId: UUID?,
    val name: String,
    val relationship: String?,
    val sharePct: BigDecimal,
    val note: String?,
)

data class EstateDocumentRow(
    val id: UUID,
    val householdId: UUID,
    val memberId: UUID,
    val memberName: String?,
    val kind: String,
    val title: String,
    val executedOn: LocalDate?,
    val location: String?,
    val registered: Boolean,
    val status: String,
    val notes: String?,
    val documentId: UUID?,
    val visibility: String,
    val version: Int,
    val roles: List<EstateRoleRow> = emptyList(),
    val beneficiaries: List<BeneficiaryRow> = emptyList(),
)

data class MismatchRow(
    val investmentId: UUID,
    val title: String,
    val nomineeNames: List<String>,
    val heirNames: List<String>,
    val estateDocumentId: UUID?,
)

@Repository
class EstateRepository(private val jdbc: NamedParameterJdbcTemplate) {

    fun insert(
        id: UUID,
        householdId: UUID,
        memberId: UUID,
        kind: String,
        title: String,
        executedOn: LocalDate?,
        location: String?,
        registered: Boolean,
        status: String,
        notes: String?,
        documentId: UUID?,
        visibility: String,
        createdBy: UUID,
    ) = jdbc.update(
        """
        insert into estate_documents (id, household_id, member_id, kind, title, executed_on,
                                      location, registered, status, notes, document_id,
                                      visibility, created_by)
        values (:id, :hid, :memberId, :kind, :title, :executedOn, :location, :registered,
                :status, :notes, :documentId, :visibility, :createdBy)
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("id", id).addValue("hid", householdId).addValue("memberId", memberId)
            .addValue("kind", kind).addValue("title", title).addValue("executedOn", executedOn)
            .addValue("location", location).addValue("registered", registered)
            .addValue("status", status).addValue("notes", notes).addValue("documentId", documentId)
            .addValue("visibility", visibility).addValue("createdBy", createdBy),
    )

    fun update(
        id: UUID,
        version: Int,
        title: String?,
        executedOn: LocalDate?,
        location: String?,
        registered: Boolean?,
        status: String?,
        notes: String?,
        documentId: UUID?,
        visibility: String?,
    ): Int = jdbc.update(
        """
        update estate_documents set
          title       = coalesce(:title, title),
          executed_on = coalesce(:executedOn, executed_on),
          location    = coalesce(:location, location),
          registered  = coalesce(:registered, registered),
          status      = coalesce(:status, status),
          notes       = coalesce(:notes, notes),
          document_id = coalesce(:documentId, document_id),
          visibility  = coalesce(:visibility, visibility),
          version     = version + 1
        where id = :id and version = :version and deleted_at is null
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("id", id).addValue("version", version).addValue("title", title)
            .addValue("executedOn", executedOn).addValue("location", location)
            .addValue("registered", registered).addValue("status", status)
            .addValue("notes", notes).addValue("documentId", documentId)
            .addValue("visibility", visibility),
    )

    fun softDelete(householdId: UUID, id: UUID): Int = jdbc.update(
        """
        update estate_documents set deleted_at = now()
        where id = :id and household_id = :hid and deleted_at is null
        """.trimIndent(),
        mapOf("id" to id, "hid" to householdId),
    )

    fun replaceRoles(documentId: UUID, roles: List<EstateRoleInput>) {
        jdbc.update("delete from estate_roles where estate_document_id = :id", mapOf("id" to documentId))
        roles.forEach { role ->
            jdbc.update(
                """
                insert into estate_roles (estate_document_id, role, member_id, contact_id,
                                          person_name, note)
                values (:id, :role, :memberId, :contactId, :name, :note)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("id", documentId).addValue("role", role.role)
                    .addValue("memberId", role.memberId).addValue("contactId", role.contactId)
                    .addValue("name", role.name).addValue("note", role.note),
            )
        }
    }

    fun replaceBeneficiaries(documentId: UUID, beneficiaries: List<BeneficiaryInput>) {
        jdbc.update(
            "delete from estate_beneficiaries where estate_document_id = :id",
            mapOf("id" to documentId),
        )
        beneficiaries.forEach { b ->
            jdbc.update(
                """
                insert into estate_beneficiaries (estate_document_id, investment_id, liability_id,
                                                  member_id, person_name, relationship, share_pct, note)
                values (:id, :investmentId, :liabilityId, :memberId, :name, :relationship, :share, :note)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("id", documentId).addValue("investmentId", b.investmentId)
                    .addValue("liabilityId", b.liabilityId).addValue("memberId", b.memberId)
                    .addValue("name", b.name).addValue("relationship", b.relationship)
                    .addValue("share", b.sharePct).addValue("note", b.note),
            )
        }
    }

    fun find(householdId: UUID, id: UUID): EstateDocumentRow? =
        jdbc.query("$SELECT and e.id = :id", mapOf("hid" to householdId, "id" to id), mapper())
            .firstOrNull()?.let { withRelations(listOf(it)).first() }

    fun list(householdId: UUID, memberId: UUID?): List<EstateDocumentRow> {
        val sql = buildString {
            append(SELECT)
            if (memberId != null) append(" and e.member_id = :memberId")
            append(" order by e.executed_on desc nulls last, lower(e.title)")
        }
        return withRelations(
            jdbc.query(
                sql,
                MapSqlParameterSource().addValue("hid", householdId).addValue("memberId", memberId),
                mapper(),
            ),
        )
    }

    /** Reads the view, which is security_invoker: only mismatches you may see. */
    fun mismatches(householdId: UUID): List<MismatchRow> = jdbc.query(
        """
        select investment_id, title, nominee_names, heir_names, estate_document_id
        from nominee_will_mismatch where household_id = :hid
        order by lower(title)
        """.trimIndent(),
        mapOf("hid" to householdId),
    ) { rs, _ ->
        MismatchRow(
            investmentId = rs.getObject("investment_id", UUID::class.java),
            title = rs.getString("title"),
            nomineeNames = (rs.getArray("nominee_names").array as Array<*>).map { it.toString() },
            heirNames = (rs.getArray("heir_names").array as Array<*>).map { it.toString() },
            estateDocumentId = rs.getObject("estate_document_id", UUID::class.java),
        )
    }

    private fun withRelations(rows: List<EstateDocumentRow>): List<EstateDocumentRow> {
        if (rows.isEmpty()) return rows
        val ids = rows.map { it.id }

        val roles = jdbc.query(
            """
            select r.*, coalesce(m.display_name, c.name, r.person_name) as resolved_name
            from estate_roles r
            left join members m on m.id = r.member_id
            left join contacts c on c.id = r.contact_id
            where r.estate_document_id in (:ids)
            """.trimIndent(),
            mapOf("ids" to ids),
        ) { rs, _ ->
            rs.getObject("estate_document_id", UUID::class.java) to EstateRoleRow(
                id = rs.getObject("id", UUID::class.java),
                role = rs.getString("role"),
                memberId = rs.getObject("member_id", UUID::class.java),
                contactId = rs.getObject("contact_id", UUID::class.java),
                name = rs.getString("resolved_name") ?: "Someone",
                note = rs.getString("note"),
            )
        }.groupBy({ it.first }, { it.second })

        val beneficiaries = jdbc.query(
            """
            select b.*, coalesce(m.display_name, b.person_name) as resolved_name, i.title as investment_title
            from estate_beneficiaries b
            left join members m on m.id = b.member_id
            left join investments i on i.id = b.investment_id
            where b.estate_document_id in (:ids)
            """.trimIndent(),
            mapOf("ids" to ids),
        ) { rs, _ ->
            rs.getObject("estate_document_id", UUID::class.java) to BeneficiaryRow(
                id = rs.getObject("id", UUID::class.java),
                investmentId = rs.getObject("investment_id", UUID::class.java),
                investmentTitle = rs.getString("investment_title"),
                liabilityId = rs.getObject("liability_id", UUID::class.java),
                memberId = rs.getObject("member_id", UUID::class.java),
                name = rs.getString("resolved_name") ?: "Someone",
                relationship = rs.getString("relationship"),
                sharePct = rs.getBigDecimal("share_pct"),
                note = rs.getString("note"),
            )
        }.groupBy({ it.first }, { it.second })

        return rows.map {
            it.copy(roles = roles[it.id].orEmpty(), beneficiaries = beneficiaries[it.id].orEmpty())
        }
    }

    private fun mapper() = { rs: ResultSet, _: Int ->
        EstateDocumentRow(
            id = rs.getObject("id", UUID::class.java),
            householdId = rs.getObject("household_id", UUID::class.java),
            memberId = rs.getObject("member_id", UUID::class.java),
            memberName = rs.getString("member_name"),
            kind = rs.getString("kind"),
            title = rs.getString("title"),
            executedOn = rs.getDate("executed_on")?.toLocalDate(),
            location = rs.getString("location"),
            registered = rs.getBoolean("registered"),
            status = rs.getString("status"),
            notes = rs.getString("notes"),
            documentId = rs.getObject("document_id", UUID::class.java),
            visibility = rs.getString("visibility"),
            version = rs.getInt("version"),
        )
    }

    private companion object {
        const val SELECT = """
            select e.*, m.display_name as member_name
            from estate_documents e
            left join members m on m.id = e.member_id
            where e.household_id = :hid and e.deleted_at is null
        """
    }
}

data class EstateRoleInput(
    val role: String,
    val memberId: UUID? = null,
    val contactId: UUID? = null,
    val name: String? = null,
    val note: String? = null,
)

data class BeneficiaryInput(
    val investmentId: UUID? = null,
    val liabilityId: UUID? = null,
    val memberId: UUID? = null,
    val name: String? = null,
    val relationship: String? = null,
    val sharePct: BigDecimal = BigDecimal(100),
    val note: String? = null,
)
