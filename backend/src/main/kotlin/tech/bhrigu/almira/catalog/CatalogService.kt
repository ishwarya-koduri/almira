package tech.bhrigu.almira.catalog

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.security.RequestUserContext
import java.util.Locale
import java.util.UUID

data class CategoryWithTypes(val category: CategoryRow, val types: List<InvestmentTypeRow>)

@Service
class CatalogService(
    private val repo: CatalogRepository,
    private val households: HouseholdService,
    private val audit: AuditService,
    private val userContext: RequestUserContext,
) {
    private val institutionKinds = setOf(
        "bank", "amc", "broker", "insurer", "post_office", "govt", "exchange", "lender", "other",
    )

    /** Powers the type picker: category grid, then types within (docs/02 §6.9). */
    fun taxonomy(householdId: UUID): List<CategoryWithTypes> {
        households.get(householdId)
        val types = repo.types(householdId).groupBy { it.categoryId }
        return repo.categories()
            .map { CategoryWithTypes(it, types[it.id].orEmpty()) }
            .filter { it.types.isNotEmpty() }
    }

    fun type(householdId: UUID, typeId: UUID): InvestmentTypeRow =
        repo.type(typeId, householdId)
            ?: throw ApiException.badRequest("type_unknown", "We don't recognise that type.")

    /**
     * A saved custom type behaves exactly like a built-in one — same table, same
     * schema shape. Promoting it later is a change of one column, not a
     * migration (docs/01 §5).
     */
    @Transactional
    fun createCustomType(
        householdId: UUID,
        categoryCode: String?,
        label: String,
        icon: String?,
        color: String?,
        fields: List<FieldDef>,
    ): InvestmentTypeRow {
        val userId = userContext.require()
        households.get(householdId)

        if (label.isBlank()) {
            throw ApiException.badRequest("label_required", "Give your type a name.")
        }
        val category = repo.categories().firstOrNull { it.code == (categoryCode ?: "universal") }
            ?: throw ApiException.badRequest("category_unknown", "We don't recognise that category.")

        fields.forEach(::validateFieldDefinition)
        val duplicates = fields.groupBy { it.key }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw ApiException.badRequest(
                "duplicate_field", "These fields are listed twice: ${duplicates.joinToString()}",
            )
        }

        val code = slug(label)
        val existing = repo.types(householdId).any { it.code == code }
        if (existing) {
            throw ApiException.conflict("type_exists", "You already have a type called “$label”.")
        }

        val id = repo.createCustomType(
            householdId, category.id, code, label.trim(), icon, color,
            TypeSchema(common = emptyMap(), fields = fields.sortedBy { it.sort }),
        )
        audit.record(
            householdId = householdId, actorUserId = userId, action = "type.create",
            entityType = "investment_type", entityId = id, diff = mapOf("label" to label),
        )
        return repo.type(id, householdId)!!
    }

    fun institutions(householdId: UUID, query: String?, kind: String?): List<InstitutionRow> {
        households.get(householdId)
        return repo.institutions(householdId, query, kind)
    }

    /** "+ Add '<query>'" from the picker — capture must never dead-end (docs/02 §6.3). */
    @Transactional
    fun createInstitution(householdId: UUID, name: String, kind: String): InstitutionRow {
        val userId = userContext.require()
        households.get(householdId)
        if (name.isBlank()) {
            throw ApiException.badRequest("name_required", "Give the institution a name.")
        }
        if (kind !in institutionKinds) {
            throw ApiException.badRequest(
                "kind_invalid", "Choose one of: ${institutionKinds.joinToString()}.",
            )
        }
        val trimmed = name.trim()
        repo.institutions(householdId, trimmed, null)
            .firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
            ?.let { return it } // already known: reuse rather than duplicate

        val id = repo.createInstitution(householdId, trimmed, kind)
        audit.record(
            householdId = householdId, actorUserId = userId, action = "institution.create",
            entityType = "institution", entityId = id,
        )
        return repo.institutions(householdId, trimmed, null).first { it.id == id }
    }

    fun validateFieldDefinition(field: FieldDef) {
        if (!KEY.matches(field.key)) {
            throw ApiException.badRequest(
                "field_key_invalid",
                "“${field.key}” can't be used as a field name — use lowercase letters, " +
                    "numbers and underscores.",
            )
        }
        if (DataType.from(field.dataType) == null) {
            throw ApiException.badRequest(
                "field_type_invalid", "“${field.dataType}” isn't a field type we support.",
            )
        }
        if (field.dataType == DataType.SELECT.wire && field.options.isNullOrEmpty()) {
            throw ApiException.badRequest(
                "field_options_required", "“${field.label}” is a choice field, so it needs options.",
            )
        }
    }

    private fun slug(label: String): String =
        label.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(40)
            .ifEmpty { "custom_type" }
            .let { if (it.first().isDigit()) "t_$it" else it }

    private companion object {
        val KEY = Regex("^[a-z][a-z0-9_]{0,48}$")
    }
}
