package tech.bhrigu.almira.template

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tech.bhrigu.almira.common.IndianNumbers
import tech.bhrigu.almira.investment.CreateInvestmentResponse
import tech.bhrigu.almira.investment.toResponse
import java.math.BigDecimal
import java.util.UUID

data class TemplateResponse(
    val id: UUID,
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
    val investedAmountFormatted: String?,
    val currency: String,
    val quantity: BigDecimal?,
    val unit: String?,
    val storageLocation: String?,
    val attributes: Map<String, Any?>,
    val notes: String?,
    val visibility: String,
    /** False for a template someone else shared: you may use it, not change it. */
    val mine: Boolean,
    val useCount: Int,
    val version: Int,
)

private fun TemplateRow.toResponse() = TemplateResponse(
    id = id, name = name, typeId = typeId, typeCode = typeCode, typeLabel = typeLabel,
    typeIcon = typeIcon, categoryCode = categoryCode,
    institutionId = institutionId, institutionName = institutionName,
    accountId = accountId, accountLabel = accountLabel,
    title = title, investedAmount = investedAmount,
    investedAmountFormatted = investedAmount?.let { IndianNumbers.rupees(it) },
    currency = currency, quantity = quantity, unit = unit, storageLocation = storageLocation,
    attributes = attributes, notes = notes, visibility = visibility, mine = mine,
    useCount = useCount, version = version,
)

@RestController
@RequestMapping("/api/v1/households/{householdId}/templates")
class TemplateController(private val service: TemplateService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable householdId: UUID,
        @RequestBody @Valid body: CreateTemplate,
    ): TemplateResponse = service.create(householdId, body).toResponse()

    @GetMapping
    fun list(@PathVariable householdId: UUID): List<TemplateResponse> =
        service.list(householdId).map { it.toResponse() }

    @GetMapping("/{id}")
    fun get(@PathVariable householdId: UUID, @PathVariable id: UUID): TemplateResponse =
        service.get(householdId, id).toResponse()

    @PatchMapping("/{id}")
    fun update(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @RequestBody @Valid body: UpdateTemplate,
    ): TemplateResponse = service.update(householdId, id, body).toResponse()

    /** Fill in what's different this time, and save. */
    @PostMapping("/{id}/apply")
    @ResponseStatus(HttpStatus.CREATED)
    fun apply(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @RequestBody(required = false) body: ApplyTemplate?,
    ): CreateInvestmentResponse {
        val created = service.apply(householdId, id, body ?: ApplyTemplate())
        return CreateInvestmentResponse(created.id, created.visibleToYou, created.record?.toResponse())
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable householdId: UUID, @PathVariable id: UUID): ResponseEntity<Void> {
        service.delete(householdId, id)
        return ResponseEntity.noContent().build()
    }
}
