package tech.bhrigu.almira.investment

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tech.bhrigu.almira.common.IndianNumbers
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class CreateInvestmentBody(
    val id: UUID? = null,
    val typeId: UUID,
    @field:NotBlank(message = "Give this a name you'll recognise")
    @field:Size(max = 160) val title: String,
    val investedAmount: BigDecimal? = null,
    val currency: String? = null,
    val quantity: BigDecimal? = null,
    val unit: String? = null,
    val startDate: LocalDate? = null,
    val maturityDate: LocalDate? = null,
    val storageLocation: String? = null,
    val institutionId: UUID? = null,
    val accountId: UUID? = null,
    val attributes: Map<String, Any?> = emptyMap(),
    val owners: List<OwnerInput> = emptyList(),
    val visibility: String? = null,
    val visibleToMemberIds: List<UUID> = emptyList(),
    val isInContinuity: Boolean = true,
    val notes: String? = null,
    val customFields: List<CustomFieldInput> = emptyList(),
    val initialValuation: ValuationInput? = null,
)

data class ChangeVisibilityBody(
    val visibility: String,
    val visibleToMemberIds: List<UUID> = emptyList(),
)

data class SetNomineesBody(val nominees: List<NomineeInput> = emptyList())

data class NomineeResponse(
    val id: UUID,
    val memberId: UUID?,
    val name: String,
    val relationship: String?,
    val sharePct: BigDecimal,
)

data class OwnerResponse(
    val memberId: UUID,
    val name: String?,
    val sharePct: BigDecimal,
    val holderType: String,
)

data class InvestmentResponse(
    val id: UUID,
    val title: String,
    val typeId: UUID,
    val typeCode: String,
    val typeLabel: String,
    val typeIcon: String?,
    val categoryCode: String,
    val categoryLabel: String,
    val color: String,
    val status: String,
    val investedAmount: BigDecimal?,
    val quantity: BigDecimal?,
    val unit: String?,
    val currency: String,
    val value: BigDecimal?,
    val valueFormatted: String?,
    /**
     * How the value is known: valued | at_cost | custom_field | unknown.
     * The client shows this rather than implying a current market figure we
     * do not have (docs/07 §1).
     */
    val valueBasis: String,
    val valuedOn: LocalDate?,
    val startDate: LocalDate?,
    val maturityDate: LocalDate?,
    val storageLocation: String?,
    val institutionId: UUID?,
    val institutionName: String?,
    val accountId: UUID?,
    val accountLabel: String?,
    val attributes: Map<String, Any?>,
    val notes: String?,
    val visibility: String,
    val visibleToMemberIds: List<UUID>,
    val isInContinuity: Boolean,
    val owners: List<OwnerResponse>,
    val nominees: List<NomineeResponse>,
    /** Outstanding debt secured against this asset, if any. */
    val encumbrance: BigDecimal?,
    val encumbranceFormatted: String?,
    /** Value minus what is owed against it — what is actually yours today. */
    val netEquity: BigDecimal?,
    val lastVerifiedAt: Instant?,
    /** Set when this record renewed an earlier one. */
    val rolledFromId: UUID? = null,
    val version: Int,
    val createdAt: Instant,
)

data class CreateInvestmentResponse(
    val id: UUID,
    /**
     * False when the record was saved as Private for someone else: it exists,
     * but by the rules of the privacy model its creator cannot read it back.
     * Surfaced so the client can say so instead of appearing to have lost it.
     */
    val visibleToYou: Boolean,
    val investment: InvestmentResponse?,
)

data class RolloverResponse(
    /** The matured record, kept. */
    val previous: InvestmentResponse,
    val created: CreateInvestmentResponse,
)

@RestController
@RequestMapping("/api/v1/households/{householdId}/investments")
class InvestmentController(private val service: InvestmentService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable householdId: UUID,
        @RequestBody @Valid body: CreateInvestmentBody,
    ): CreateInvestmentResponse {
        val created = service.create(
            householdId,
            CreateInvestment(
                id = body.id, typeId = body.typeId, title = body.title,
                investedAmount = body.investedAmount, currency = body.currency,
                quantity = body.quantity, unit = body.unit,
                startDate = body.startDate, maturityDate = body.maturityDate,
                storageLocation = body.storageLocation,
                institutionId = body.institutionId, accountId = body.accountId,
                attributes = body.attributes, owners = body.owners,
                visibility = body.visibility, visibleToMemberIds = body.visibleToMemberIds,
                isInContinuity = body.isInContinuity, notes = body.notes,
                customFields = body.customFields, initialValuation = body.initialValuation,
            ),
        )
        return CreateInvestmentResponse(created.id, created.visibleToYou, created.record?.toResponse())
    }

    @GetMapping
    fun list(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) type: List<UUID>?,
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) member: UUID?,
        @RequestParam(required = false) institution: UUID?,
        @RequestParam(required = false) account: UUID?,
        @RequestParam(defaultValue = "false") unlinked: Boolean,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) visibility: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "200") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): List<InvestmentResponse> = service.list(
        householdId,
        InvestmentFilter(
            typeIds = type, categoryCode = category, memberId = member,
            institutionId = institution, accountId = account, unlinked = unlinked,
            status = status, visibility = visibility,
            query = q?.trim()?.ifEmpty { null },
            limit = limit.coerceIn(1, 500), offset = offset.coerceAtLeast(0),
        ),
    ).map { it.toResponse() }

    /** Same shape, new record — the fourth FD of the year, typed once. */
    @PostMapping("/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    fun duplicate(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @RequestBody(required = false) body: DuplicateInvestment?,
    ): CreateInvestmentResponse {
        val created = service.duplicate(householdId, id, body ?: DuplicateInvestment())
        return CreateInvestmentResponse(created.id, created.visibleToYou, created.record?.toResponse())
    }

    /**
     * Renew a maturity. The old record is kept and marked matured; the new one
     * points back at it, so the history survives the renewal.
     */
    @PostMapping("/{id}/rollover")
    @ResponseStatus(HttpStatus.CREATED)
    fun rollover(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @RequestBody(required = false) body: DuplicateInvestment?,
    ): RolloverResponse {
        val result = service.rollover(householdId, id, body ?: DuplicateInvestment())
        return RolloverResponse(
            previous = result.previous.toResponse(),
            created = CreateInvestmentResponse(
                result.created.id, result.created.visibleToYou, result.created.record?.toResponse(),
            ),
        )
    }

    @GetMapping("/{id}")
    fun get(@PathVariable householdId: UUID, @PathVariable id: UUID): InvestmentResponse =
        service.get(householdId, id).toResponse()

    @PatchMapping("/{id}")
    fun update(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @RequestBody @Valid body: UpdateInvestment,
    ): InvestmentResponse = service.update(householdId, id, body).toResponse()

    @PatchMapping("/{id}/visibility")
    fun changeVisibility(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @RequestBody @Valid body: ChangeVisibilityBody,
    ): InvestmentResponse =
        service.changeVisibility(householdId, id, body.visibility, body.visibleToMemberIds)
            .toResponse()

    /**
     * Replaces the whole nominee list. A nominee list is a legal instruction, so
     * it is set as a unit rather than patched a name at a time.
     */
    @org.springframework.web.bind.annotation.PutMapping("/{id}/nominees")
    fun setNominees(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @RequestBody @Valid body: SetNomineesBody,
    ): InvestmentResponse = service.replaceNominees(householdId, id, body.nominees).toResponse()

    @PostMapping("/{id}/valuations")
    @ResponseStatus(HttpStatus.CREATED)
    fun addValuation(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @RequestBody @Valid body: ValuationInput,
    ): InvestmentResponse = service.addValuation(householdId, id, body).toResponse()

    @GetMapping("/{id}/valuations")
    fun valuations(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
    ): List<ValuationRow> = service.valuations(householdId, id)

    @DeleteMapping("/{id}")
    fun archive(@PathVariable householdId: UUID, @PathVariable id: UUID): ResponseEntity<Void> {
        service.archive(householdId, id)
        return ResponseEntity.noContent().build()
    }
}

@RestController
@RequestMapping("/api/v1/households/{householdId}/trash")
class TrashController(private val service: InvestmentService) {

    @GetMapping
    fun list(@PathVariable householdId: UUID): List<InvestmentResponse> =
        service.trash(householdId).map { it.toResponse() }

    @PostMapping("/investments/{id}/restore")
    fun restore(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
    ): InvestmentResponse = service.restore(householdId, id).toResponse()
}

internal fun InvestmentRow.toResponse() = InvestmentResponse(
    id = id, title = title, typeId = typeId, typeCode = typeCode, typeLabel = typeLabel,
    typeIcon = typeIcon, categoryCode = categoryCode, categoryLabel = categoryLabel,
    color = color, status = status, investedAmount = investedAmount, quantity = quantity,
    unit = unit, currency = currency,
    value = effectiveValue,
    valueFormatted = effectiveValue?.let { IndianNumbers.rupees(it) },
    valueBasis = valueBasis, valuedOn = valuedOn,
    startDate = startDate, maturityDate = maturityDate, storageLocation = storageLocation,
    institutionId = institutionId, institutionName = institutionName,
    accountId = accountId, accountLabel = accountLabel,
    attributes = attributes, notes = notes,
    visibility = visibility, visibleToMemberIds = visibleToMemberIds,
    isInContinuity = isInContinuity,
    owners = owners.map { OwnerResponse(it.memberId, it.memberName, it.sharePct, it.holderType) },
    nominees = nominees.map {
        NomineeResponse(it.id, it.memberId, it.name, it.relationship, it.sharePct)
    },
    encumbrance = encumbrance,
    encumbranceFormatted = encumbrance?.let { IndianNumbers.rupees(it) },
    netEquity = if (encumbrance != null && effectiveValue != null) {
        effectiveValue - encumbrance
    } else {
        null
    },
    lastVerifiedAt = lastVerifiedAt, version = version, createdAt = createdAt,
    rolledFromId = rolledFromId,
)
