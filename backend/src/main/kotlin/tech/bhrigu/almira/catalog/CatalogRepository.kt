package tech.bhrigu.almira.catalog

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

data class CategoryRow(
    val id: UUID,
    val code: String,
    val label: String,
    val icon: String,
    val color: String,
    val sort: Int,
)

data class InvestmentTypeRow(
    val id: UUID,
    val categoryId: UUID,
    val categoryCode: String,
    val categoryLabel: String,
    val color: String,
    val code: String,
    val label: String,
    val icon: String?,
    val isCustom: Boolean,
    val householdId: UUID?,
    val schemaVersion: Int,
    val schema: TypeSchema,
    val sort: Int,
)

data class InstitutionRow(
    val id: UUID,
    val name: String,
    val kind: String,
    val logoUrl: String?,
    val isCustom: Boolean,
)

data class CustomFieldRow(
    val id: UUID,
    val ownerType: String,
    val ownerId: UUID,
    val key: String,
    val label: String,
    val dataType: String,
    val unit: String?,
    val options: List<FieldOption>?,
    val required: Boolean,
    val countsTowardValue: Boolean,
    val sort: Int,
)

@Repository
class CatalogRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val mapper: ObjectMapper,
) {

    fun categories(): List<CategoryRow> = jdbc.query(
        "select * from asset_categories order by sort", emptyMap<String, Any>(),
    ) { rs, _ ->
        CategoryRow(
            rs.getObject("id", UUID::class.java), rs.getString("code"), rs.getString("label"),
            rs.getString("icon"), rs.getString("color"), rs.getInt("sort"),
        )
    }

    /**
     * Global types plus this household's own. RLS already hides other
     * households' custom types, so the query does not need to say so.
     */
    fun types(householdId: UUID?): List<InvestmentTypeRow> = jdbc.query(
        """
        select t.*, c.code as category_code, c.label as category_label, c.color as category_color
        from investment_types t
        join asset_categories c on c.id = t.category_id
        where t.deleted_at is null
          and (t.household_id is null or t.household_id = :hid)
        order by c.sort, t.sort, t.label
        """.trimIndent(),
        mapOf("hid" to householdId),
    ) { rs, _ ->
        InvestmentTypeRow(
            id = rs.getObject("id", UUID::class.java),
            categoryId = rs.getObject("category_id", UUID::class.java),
            categoryCode = rs.getString("category_code"),
            categoryLabel = rs.getString("category_label"),
            color = rs.getString("color") ?: rs.getString("category_color"),
            code = rs.getString("code"),
            label = rs.getString("label"),
            icon = rs.getString("icon"),
            isCustom = rs.getBoolean("is_custom"),
            householdId = rs.getObject("household_id", UUID::class.java),
            schemaVersion = rs.getInt("schema_version"),
            schema = mapper.readValue(rs.getString("field_schema")),
            sort = rs.getInt("sort"),
        )
    }

    fun type(typeId: UUID, householdId: UUID?): InvestmentTypeRow? =
        types(householdId).firstOrNull { it.id == typeId }

    fun createCustomType(
        householdId: UUID,
        categoryId: UUID,
        code: String,
        label: String,
        icon: String?,
        color: String?,
        schema: TypeSchema,
    ): UUID = jdbc.queryForObject(
        """
        insert into investment_types
          (household_id, category_id, code, label, icon, color, is_custom, field_schema)
        values (:hid, :categoryId, :code, :label, :icon, :color, true, cast(:schema as jsonb))
        returning id
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("hid", householdId)
            .addValue("categoryId", categoryId)
            .addValue("code", code)
            .addValue("label", label)
            .addValue("icon", icon)
            .addValue("color", color)
            .addValue("schema", mapper.writeValueAsString(schema)),
        UUID::class.java,
    )!!

    // --- institutions --------------------------------------------------------

    fun institutions(householdId: UUID?, query: String?, kind: String?): List<InstitutionRow> =
        jdbc.query(
            """
            select id, name, kind, logo_url, household_id is not null as is_custom
            from institutions
            where deleted_at is null
              and (household_id is null or household_id = :hid)
              and (:q::text is null or name ilike '%' || :q || '%')
              and (:kind::text is null or kind = :kind)
            order by (household_id is not null) desc, name
            limit 100
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("hid", householdId)
                .addValue("q", query?.trim()?.ifEmpty { null })
                .addValue("kind", kind),
        ) { rs, _ ->
            InstitutionRow(
                rs.getObject("id", UUID::class.java), rs.getString("name"),
                rs.getString("kind"), rs.getString("logo_url"), rs.getBoolean("is_custom"),
            )
        }

    fun createInstitution(householdId: UUID, name: String, kind: String): UUID =
        jdbc.queryForObject(
            """
            insert into institutions (household_id, name, kind)
            values (:hid, :name, :kind) returning id
            """.trimIndent(),
            mapOf("hid" to householdId, "name" to name, "kind" to kind),
            UUID::class.java,
        )!!

    // --- custom fields -------------------------------------------------------

    fun customFields(ownerType: String, ownerIds: Collection<UUID>): List<CustomFieldRow> {
        if (ownerIds.isEmpty()) return emptyList()
        return jdbc.query(
            """
            select * from custom_fields
            where owner_type = :ownerType and owner_id in (:ownerIds)
            order by sort, label
            """.trimIndent(),
            mapOf("ownerType" to ownerType, "ownerIds" to ownerIds),
        ) { rs, _ ->
            CustomFieldRow(
                id = rs.getObject("id", UUID::class.java),
                ownerType = rs.getString("owner_type"),
                ownerId = rs.getObject("owner_id", UUID::class.java),
                key = rs.getString("key"),
                label = rs.getString("label"),
                dataType = rs.getString("data_type"),
                unit = rs.getString("unit"),
                options = rs.getString("options")?.let { mapper.readValue(it) },
                required = rs.getBoolean("required"),
                countsTowardValue = rs.getBoolean("counts_toward_value"),
                sort = rs.getInt("sort"),
            )
        }
    }

    fun createCustomField(
        householdId: UUID,
        ownerType: String,
        ownerId: UUID,
        key: String,
        label: String,
        dataType: String,
        unit: String?,
        options: List<FieldOption>?,
        required: Boolean,
        countsTowardValue: Boolean,
    ): UUID = jdbc.queryForObject(
        """
        insert into custom_fields
          (household_id, owner_type, owner_id, key, label, data_type, unit, options,
           required, counts_toward_value)
        values (:hid, :ownerType, :ownerId, :key, :label, :dataType, :unit,
                cast(:options as jsonb), :required, :counts)
        returning id
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("hid", householdId)
            .addValue("ownerType", ownerType)
            .addValue("ownerId", ownerId)
            .addValue("key", key)
            .addValue("label", label)
            .addValue("dataType", dataType)
            .addValue("unit", unit)
            .addValue("options", options?.let { mapper.writeValueAsString(it) })
            .addValue("required", required)
            .addValue("counts", countsTowardValue),
        UUID::class.java,
    )!!
}
