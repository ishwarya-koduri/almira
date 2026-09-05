package tech.bhrigu.almira.catalog

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class CreateTypeBody(
    val categoryCode: String? = null,
    @field:NotBlank(message = "Give your type a name")
    @field:Size(max = 60) val label: String,
    val icon: String? = null,
    val color: String? = null,
    val fields: List<FieldDef> = emptyList(),
)

data class CreateInstitutionBody(
    @field:NotBlank(message = "Give the institution a name")
    @field:Size(max = 120) val name: String,
    val kind: String = "other",
)

data class TypeResponse(
    val id: UUID,
    val code: String,
    val label: String,
    val icon: String?,
    val color: String,
    val categoryCode: String,
    val categoryLabel: String,
    val isCustom: Boolean,
    val schemaVersion: Int,
    val schema: TypeSchema,
)

data class TaxonomyResponse(
    val categoryCode: String,
    val categoryLabel: String,
    val icon: String,
    val color: String,
    val types: List<TypeResponse>,
)

@RestController
@RequestMapping("/api/households/{householdId}")
class CatalogController(private val service: CatalogService) {

    @GetMapping("/taxonomy")
    fun taxonomy(@PathVariable householdId: UUID): List<TaxonomyResponse> =
        service.taxonomy(householdId).map { (category, types) ->
            TaxonomyResponse(
                categoryCode = category.code,
                categoryLabel = category.label,
                icon = category.icon,
                color = category.color,
                types = types.map { it.toResponse() },
            )
        }

    @PostMapping("/types")
    @ResponseStatus(HttpStatus.CREATED)
    fun createType(
        @PathVariable householdId: UUID,
        @RequestBody @Valid body: CreateTypeBody,
    ): TypeResponse = service.createCustomType(
        householdId, body.categoryCode, body.label, body.icon, body.color, body.fields,
    ).toResponse()

    @GetMapping("/institutions")
    fun institutions(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) kind: String?,
    ): List<InstitutionRow> = service.institutions(householdId, q, kind)

    @PostMapping("/institutions")
    @ResponseStatus(HttpStatus.CREATED)
    fun createInstitution(
        @PathVariable householdId: UUID,
        @RequestBody @Valid body: CreateInstitutionBody,
    ): InstitutionRow = service.createInstitution(householdId, body.name, body.kind)

    private fun InvestmentTypeRow.toResponse() = TypeResponse(
        id = id, code = code, label = label, icon = icon, color = color,
        categoryCode = categoryCode, categoryLabel = categoryLabel,
        isCustom = isCustom, schemaVersion = schemaVersion, schema = schema,
    )
}
