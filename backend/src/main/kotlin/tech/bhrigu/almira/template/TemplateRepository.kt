package tech.bhrigu.almira.template

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

data class TemplateRow(
    val id: UUID,
    val householdId: UUID,
    val name: String,
    val typeId: UUID,
    val typeCode: String,
    val typeLabel: String,
    val typeIcon: String?,
    val categoryCode: String,
    val institutionId: UUID?,
    val institutionName: String?,
    val accountId: UUID?,
    val accountLabel: String?,
    val title: String?,
    val investedAmount: BigDecimal?,
    val currency: String,
    val quantity: BigDecimal?,
    val unit: String?,
    val storageLocation: String?,
    val attributes: Map<String, Any?>,
    val notes: String?,
    val visibility: String,
    /** The visibility of the record it was saved from, when it was saved from one. */
    val sourceVisibility: String?,
    val useCount: Int,
    val createdBy: UUID,
    val mine: Boolean,
    val version: Int,
    val createdAt: Instant,
)

@Repository
class TemplateRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val mapper: ObjectMapper,
) {

    fun insert(row: NewTemplate) = jdbc.update(
        """
        insert into investment_templates
          (id, household_id, name, type_id, institution_id, account_id, title,
           invested_amount, currency, quantity, unit, storage_location,
           attributes, notes, visibility, source_visibility, created_by)
        values (:id, :hid, :name, :typeId, :institutionId, :accountId, :title,
                :amount, :currency, :quantity, :unit, :storage,
                cast(:attributes as jsonb), :notes, :visibility, :sourceVisibility, :createdBy)
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("id", row.id).addValue("hid", row.householdId).addValue("name", row.name)
            .addValue("typeId", row.typeId).addValue("institutionId", row.institutionId)
            .addValue("accountId", row.accountId).addValue("title", row.title)
            .addValue("amount", row.investedAmount).addValue("currency", row.currency)
            .addValue("quantity", row.quantity).addValue("unit", row.unit)
            .addValue("storage", row.storageLocation)
            .addValue("attributes", mapper.writeValueAsString(row.attributes))
            .addValue("notes", row.notes).addValue("visibility", row.visibility)
            .addValue("sourceVisibility", row.sourceVisibility)
            .addValue("createdBy", row.createdBy),
    )

    fun update(
        id: UUID,
        version: Int,
        name: String?,
        title: String?,
        investedAmount: BigDecimal?,
        quantity: BigDecimal?,
        unit: String?,
        storageLocation: String?,
        institutionId: UUID?,
        accountId: UUID?,
        attributes: Map<String, Any?>?,
        notes: String?,
        visibility: String?,
    ): Int = jdbc.update(
        """
        update investment_templates set
          name             = coalesce(:name, name),
          title            = coalesce(:title, title),
          invested_amount  = coalesce(:amount, invested_amount),
          quantity         = coalesce(:quantity, quantity),
          unit             = coalesce(:unit, unit),
          storage_location = coalesce(:storage, storage_location),
          institution_id   = coalesce(:institutionId, institution_id),
          account_id       = coalesce(:accountId, account_id),
          attributes       = coalesce(cast(:attributes as jsonb), attributes),
          notes            = coalesce(:notes, notes),
          visibility       = coalesce(:visibility, visibility),
          version          = version + 1
        where id = :id and version = :version and deleted_at is null
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("id", id).addValue("version", version).addValue("name", name)
            .addValue("title", title).addValue("amount", investedAmount)
            .addValue("quantity", quantity).addValue("unit", unit)
            .addValue("storage", storageLocation).addValue("institutionId", institutionId)
            .addValue("accountId", accountId)
            .addValue("attributes", attributes?.let { mapper.writeValueAsString(it) })
            .addValue("notes", notes).addValue("visibility", visibility),
    )

    /**
     * Through the function, not the table: using a shared template is not
     * editing it, and the update policy rightly keeps it in its creator's hands.
     */
    fun recordUse(id: UUID): Int? = jdbc.queryForObject(
        "select app.record_template_use(:id)", mapOf("id" to id), Int::class.javaObjectType,
    )

    fun softDelete(householdId: UUID, id: UUID): Int = jdbc.update(
        """
        update investment_templates set deleted_at = now()
        where id = :id and household_id = :hid and deleted_at is null
        """.trimIndent(),
        mapOf("id" to id, "hid" to householdId),
    )

    fun find(householdId: UUID, id: UUID): TemplateRow? =
        jdbc.query("$SELECT and t.id = :id", mapOf("hid" to householdId, "id" to id), mapper())
            .firstOrNull()

    fun list(householdId: UUID): List<TemplateRow> = jdbc.query(
        "$SELECT order by t.use_count desc, lower(t.name)",
        mapOf("hid" to householdId),
        mapper(),
    )

    private fun mapper() = { rs: ResultSet, _: Int ->
        TemplateRow(
            id = rs.getObject("id", UUID::class.java),
            householdId = rs.getObject("household_id", UUID::class.java),
            name = rs.getString("name"),
            typeId = rs.getObject("type_id", UUID::class.java),
            typeCode = rs.getString("type_code"),
            typeLabel = rs.getString("type_label"),
            typeIcon = rs.getString("type_icon"),
            categoryCode = rs.getString("category_code"),
            institutionId = rs.getObject("institution_id", UUID::class.java),
            institutionName = rs.getString("institution_name"),
            accountId = rs.getObject("account_id", UUID::class.java),
            accountLabel = rs.getString("account_label"),
            title = rs.getString("title"),
            investedAmount = rs.getBigDecimal("invested_amount"),
            currency = rs.getString("currency"),
            quantity = rs.getBigDecimal("quantity"),
            unit = rs.getString("unit"),
            storageLocation = rs.getString("storage_location"),
            attributes = this.mapper.readValue(rs.getString("attributes")),
            notes = rs.getString("notes"),
            visibility = rs.getString("visibility"),
            sourceVisibility = rs.getString("source_visibility"),
            useCount = rs.getInt("use_count"),
            createdBy = rs.getObject("created_by", UUID::class.java),
            mine = rs.getBoolean("mine"),
            version = rs.getInt("version"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
    }

    private companion object {
        const val SELECT = """
            select t.*, ty.code as type_code, ty.label as type_label, ty.icon as type_icon,
                   c.code as category_code, inst.name as institution_name, acc.label as account_label,
                   (t.created_by = app.current_user_id()) as mine
            from investment_templates t
            join investment_types ty on ty.id = t.type_id
            join asset_categories c on c.id = ty.category_id
            left join institutions inst on inst.id = t.institution_id
            left join accounts acc on acc.id = t.account_id
            where t.household_id = :hid and t.deleted_at is null
        """
    }
}

data class NewTemplate(
    val id: UUID,
    val householdId: UUID,
    val name: String,
    val typeId: UUID,
    val institutionId: UUID?,
    val accountId: UUID?,
    val title: String?,
    val investedAmount: BigDecimal?,
    val currency: String,
    val quantity: BigDecimal?,
    val unit: String?,
    val storageLocation: String?,
    val attributes: Map<String, Any?>,
    val notes: String?,
    val visibility: String,
    val sourceVisibility: String?,
    val createdBy: UUID,
)
