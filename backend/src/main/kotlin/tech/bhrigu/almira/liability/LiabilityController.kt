package tech.bhrigu.almira.liability

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

data class CreateLiabilityBody(
    val id: UUID? = null,
    val kind: String = "other",
    @field:NotBlank(message = "Give this a name you'll recognise")
    @field:Size(max = 160) val title: String,
    val lenderId: UUID? = null,
    val principal: BigDecimal? = null,
    val outstanding: BigDecimal = BigDecimal.ZERO,
    val interestRate: BigDecimal? = null,
    val emiAmount: BigDecimal? = null,
    val emiDay: Int? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val attributes: Map<String, Any?> = emptyMap(),
    val notes: String? = null,
    val holders: List<ResponsibilityInput> = emptyList(),
    val visibility: String? = null,
    val visibleToMemberIds: List<UUID> = emptyList(),
    val securedByInvestmentId: UUID? = null,
)

data class ChangeLiabilityVisibilityBody(
    val visibility: String,
    val visibleToMemberIds: List<UUID> = emptyList(),
)

data class LinkAssetBody(val investmentId: UUID, val note: String? = null)

data class LiabilityHolderResponse(
    val memberId: UUID,
    val name: String?,
    val responsibilityPct: BigDecimal,
    val holderType: String,
)

data class SecuredAssetResponse(val investmentId: UUID, val title: String, val note: String?)

data class LiabilityResponse(
    val id: UUID,
    val title: String,
    val kind: String,
    val lenderId: UUID?,
    val lenderName: String?,
    val principal: BigDecimal?,
    val outstanding: BigDecimal,
    val outstandingFormatted: String,
    val balanceAsOf: LocalDate?,
    val interestRate: BigDecimal?,
    val emiAmount: BigDecimal?,
    val emiDay: Int?,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val status: String,
    val attributes: Map<String, Any?>,
    val notes: String?,
    val visibility: String,
    val visibleToMemberIds: List<UUID>,
    val holders: List<LiabilityHolderResponse>,
    /** What this loan is secured against — the other half of "encumbered". */
    val securedBy: List<SecuredAssetResponse>,
    val version: Int,
    val createdAt: Instant,
)

@RestController
@RequestMapping("/api/households/{householdId}/liabilities")
class LiabilityController(private val service: LiabilityService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable householdId: UUID,
        @RequestBody @Valid body: CreateLiabilityBody,
    ): LiabilityResponse = service.create(
        householdId,
        CreateLiability(
            id = body.id, kind = body.kind, title = body.title, lenderId = body.lenderId,
            principal = body.principal, outstanding = body.outstanding,
            interestRate = body.interestRate, emiAmount = body.emiAmount, emiDay = body.emiDay,
            startDate = body.startDate, endDate = body.endDate, attributes = body.attributes,
            notes = body.notes, holders = body.holders, visibility = body.visibility,
            visibleToMemberIds = body.visibleToMemberIds,
            securedByInvestmentId = body.securedByInvestmentId,
        ),
    ).toResponse()

    @GetMapping
    fun list(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) status: String?,
    ): List<LiabilityResponse> = service.list(householdId, status).map { it.toResponse() }

    @GetMapping("/{id}")
    fun get(@PathVariable householdId: UUID, @PathVariable id: UUID): LiabilityResponse =
        service.get(householdId, id).toResponse()

    @PatchMapping("/{id}")
    fun update(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @RequestBody @Valid body: UpdateLiability,
    ): LiabilityResponse = service.update(householdId, id, body).toResponse()

    @PostMapping("/{id}/balances")
    @ResponseStatus(HttpStatus.CREATED)
    fun recordBalance(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @RequestBody @Valid body: BalanceInput,
    ): LiabilityResponse = service.recordBalance(householdId, id, body).toResponse()

    @GetMapping("/{id}/balances")
    fun balances(@PathVariable householdId: UUID, @PathVariable id: UUID): List<BalanceRow> =
        service.balances(householdId, id)

    @PatchMapping("/{id}/visibility")
    fun changeVisibility(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @RequestBody @Valid body: ChangeLiabilityVisibilityBody,
    ): LiabilityResponse =
        service.changeVisibility(householdId, id, body.visibility, body.visibleToMemberIds)
            .toResponse()

    @PostMapping("/{id}/secured-by")
    fun linkAsset(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @RequestBody @Valid body: LinkAssetBody,
    ): LiabilityResponse = service.linkAsset(householdId, id, body.investmentId, body.note).toResponse()

    @DeleteMapping("/{id}/secured-by/{investmentId}")
    fun unlinkAsset(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @PathVariable investmentId: UUID,
    ): LiabilityResponse = service.unlinkAsset(householdId, id, investmentId).toResponse()

    @DeleteMapping("/{id}")
    fun archive(@PathVariable householdId: UUID, @PathVariable id: UUID): ResponseEntity<Void> {
        service.archive(householdId, id)
        return ResponseEntity.noContent().build()
    }

    private fun LiabilityRow.toResponse() = LiabilityResponse(
        id = id, title = title, kind = kind, lenderId = lenderId, lenderName = lenderName,
        principal = principal, outstanding = outstanding,
        outstandingFormatted = IndianNumbers.rupees(outstanding),
        balanceAsOf = balanceAsOf, interestRate = interestRate, emiAmount = emiAmount,
        emiDay = emiDay, startDate = startDate, endDate = endDate, status = status,
        attributes = attributes, notes = notes, visibility = visibility,
        visibleToMemberIds = visibleToMemberIds,
        holders = holders.map {
            LiabilityHolderResponse(it.memberId, it.memberName, it.responsibilityPct, it.holderType)
        },
        securedBy = securedBy.map { SecuredAssetResponse(it.investmentId, it.title, it.note) },
        version = version, createdAt = createdAt,
    )
}
