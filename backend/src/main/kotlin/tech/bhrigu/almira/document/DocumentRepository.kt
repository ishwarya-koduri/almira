package tech.bhrigu.almira.document

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class DocumentLink(val entityType: String, val entityId: UUID, val entityTitle: String?)

data class DocumentRow(
    val id: UUID,
    val householdId: UUID,
    val storageKey: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val docType: String,
    val expiresOn: LocalDate?,
    val visibility: String,
    val notes: String?,
    val uploadedBy: UUID?,
    val createdAt: Instant,
    val links: List<DocumentLink> = emptyList(),
)

@Repository
class DocumentRepository(private val jdbc: NamedParameterJdbcTemplate) {

    fun insert(
        id: UUID,
        householdId: UUID,
        storageKey: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        contentSha256: String,
        docType: String,
        expiresOn: LocalDate?,
        visibility: String,
        notes: String?,
        uploadedBy: UUID,
    ) = jdbc.update(
        """
        insert into documents
          (id, household_id, storage_key, file_name, mime_type, size_bytes,
           content_sha256, doc_type, expires_on, visibility, notes, uploaded_by)
        values
          (:id, :hid, :key, :fileName, :mime, :size,
           :sha, :docType, :expiresOn, :visibility, :notes, :uploadedBy)
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("id", id).addValue("hid", householdId).addValue("key", storageKey)
            .addValue("fileName", fileName).addValue("mime", mimeType)
            .addValue("size", sizeBytes).addValue("sha", contentSha256)
            .addValue("docType", docType).addValue("expiresOn", expiresOn)
            .addValue("visibility", visibility).addValue("notes", notes)
            .addValue("uploadedBy", uploadedBy),
    )

    fun link(documentId: UUID, entityType: String, entityId: UUID): Int = jdbc.update(
        """
        insert into document_links (document_id, entity_type, entity_id)
        values (:doc, :type, :entity)
        on conflict (document_id, entity_type, entity_id) do nothing
        """.trimIndent(),
        mapOf("doc" to documentId, "type" to entityType, "entity" to entityId),
    )

    fun unlink(documentId: UUID, entityType: String, entityId: UUID): Int = jdbc.update(
        """
        delete from document_links
        where document_id = :doc and entity_type = :type and entity_id = :entity
        """.trimIndent(),
        mapOf("doc" to documentId, "type" to entityType, "entity" to entityId),
    )

    fun list(householdId: UUID, entityType: String?, entityId: UUID?): List<DocumentRow> {
        val sql = buildString {
            append(SELECT)
            if (entityType != null && entityId != null) {
                append(
                    """
                     and exists (select 1 from document_links dl
                                 where dl.document_id = d.id
                                   and dl.entity_type = :entityType
                                   and dl.entity_id = :entityId)
                    """,
                )
            }
            append(" order by d.created_at desc")
        }
        return withLinks(
            jdbc.query(
                sql,
                MapSqlParameterSource()
                    .addValue("hid", householdId)
                    .addValue("entityType", entityType)
                    .addValue("entityId", entityId),
                mapper,
            ),
        )
    }

    fun find(householdId: UUID, id: UUID): DocumentRow? =
        withLinks(jdbc.query("$SELECT and d.id = :id", mapOf("hid" to householdId, "id" to id), mapper))
            .firstOrNull()

    fun softDelete(householdId: UUID, id: UUID): Int = jdbc.update(
        """
        update documents set deleted_at = now()
        where id = :id and household_id = :hid and deleted_at is null
        """.trimIndent(),
        mapOf("id" to id, "hid" to householdId),
    )

    /**
     * Holdings with no proof attached — the "missing proof" checklist
     * (docs/10 Epic 1.7). Runs through RLS, so it lists only what the caller
     * can see and never hints that someone else's record is missing something.
     */
    fun holdingsWithoutProof(householdId: UUID): List<Pair<UUID, String>> = jdbc.query(
        """
        select i.id, i.title
        from investments i
        where i.household_id = :hid and i.deleted_at is null and i.status = 'active'
          and not exists (
            select 1 from document_links dl
            join documents d on d.id = dl.document_id and d.deleted_at is null
            where dl.entity_type = 'investment' and dl.entity_id = i.id
          )
        order by i.title
        """.trimIndent(),
        mapOf("hid" to householdId),
    ) { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getString("title") }

    private fun withLinks(rows: List<DocumentRow>): List<DocumentRow> {
        if (rows.isEmpty()) return rows
        val links = jdbc.query(
            """
            select dl.document_id, dl.entity_type, dl.entity_id,
                   coalesce(i.title, l.title, a.label, m.display_name) as entity_title
            from document_links dl
            left join investments i on dl.entity_type = 'investment' and i.id = dl.entity_id
            left join liabilities l on dl.entity_type = 'liability'  and l.id = dl.entity_id
            left join accounts    a on dl.entity_type = 'account'    and a.id = dl.entity_id
            left join members     m on dl.entity_type = 'member'     and m.id = dl.entity_id
            where dl.document_id in (:ids)
            """.trimIndent(),
            mapOf("ids" to rows.map { it.id }),
        ) { rs, _ ->
            rs.getObject("document_id", UUID::class.java) to DocumentLink(
                rs.getString("entity_type"),
                rs.getObject("entity_id", UUID::class.java),
                rs.getString("entity_title"),
            )
        }.groupBy({ it.first }, { it.second })
        return rows.map { it.copy(links = links[it.id].orEmpty()) }
    }

    private val mapper = { rs: ResultSet, _: Int ->
        DocumentRow(
            id = rs.getObject("id", UUID::class.java),
            householdId = rs.getObject("household_id", UUID::class.java),
            storageKey = rs.getString("storage_key"),
            fileName = rs.getString("file_name"),
            mimeType = rs.getString("mime_type"),
            sizeBytes = rs.getLong("size_bytes"),
            docType = rs.getString("doc_type"),
            expiresOn = rs.getDate("expires_on")?.toLocalDate(),
            visibility = rs.getString("visibility"),
            notes = rs.getString("notes"),
            uploadedBy = rs.getObject("uploaded_by", UUID::class.java),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
    }

    private companion object {
        const val SELECT = """
            select d.* from documents d
            where d.household_id = :hid and d.deleted_at is null
        """
    }
}
