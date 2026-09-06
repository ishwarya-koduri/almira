package tech.bhrigu.almira.goal

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
import java.time.LocalDate
import java.util.UUID

data class CreateGoalBody(
    @field:NotBlank(message = "What are you saving for?")
    @field:Size(max = 120) val name: String,
    val targetAmount: BigDecimal,
    val targetDate: LocalDate? = null,
    val priority: Int = 2,
    val memberId: UUID? = null,
    val icon: String? = null,
    val notes: String? = null,
    val visibility: String? = null,
)

data class UpdateGoalBody(
    val version: Int,
    val name: String? = null,
    val targetAmount: BigDecimal? = null,
    val targetDate: LocalDate? = null,
    val priority: Int? = null,
    val notes: String? = null,
    val status: String? = null,
)

data class MapInvestmentBody(val investmentId: UUID, val allocationPct: BigDecimal = BigDecimal(100))

data class ChangeGoalVisibilityBody(
    val visibility: String,
    val visibleToMemberIds: List<UUID> = emptyList(),
)

data class FundingSourceResponse(
    val investmentId: UUID,
    val title: String,
    val allocationPct: BigDecimal,
    val contribution: BigDecimal,
    val contributionFormatted: String,
)

data class GoalResponse(
    val id: UUID,
    val name: String,
    val targetAmount: BigDecimal,
    val targetAmountFormatted: String,
    val targetDate: LocalDate?,
    val priority: Int,
    val memberId: UUID?,
    val memberName: String?,
    val icon: String?,
    val notes: String?,
    val status: String,
    val visibility: String,
    val visibleToMemberIds: List<UUID>,
    val funded: BigDecimal,
    val fundedFormatted: String,
    val progress: GoalProgress,
    val fundedBy: List<FundingSourceResponse>,
    val version: Int,
    /** Informational, never advice (docs/08 §6). */
    val disclaimer: String,
)

@RestController
@RequestMapping("/api/v1/households/{householdId}/goals")
class GoalController(private val service: GoalService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable householdId: UUID,
        @RequestBody @Valid body: CreateGoalBody,
    ): GoalResponse = service.create(
        householdId, body.name, body.targetAmount, body.targetDate, body.priority,
        body.memberId, body.icon, body.notes, body.visibility,
    ).toResponse()

    @GetMapping
    fun list(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) status: String?,
    ): List<GoalResponse> = service.list(householdId, status).map { it.toResponse() }

    @GetMapping("/{id}")
    fun get(@PathVariable householdId: UUID, @PathVariable id: UUID): GoalResponse =
        service.get(householdId, id).toResponse()

    @PatchMapping("/{id}")
    fun update(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @RequestBody @Valid body: UpdateGoalBody,
    ): GoalResponse = service.update(
        householdId, id, body.version, body.name, body.targetAmount,
        body.targetDate, body.priority, body.notes, body.status,
    ).toResponse()

    @PostMapping("/{id}/investments")
    fun mapInvestment(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @RequestBody @Valid body: MapInvestmentBody,
    ): GoalResponse =
        service.mapInvestment(householdId, id, body.investmentId, body.allocationPct).toResponse()

    @DeleteMapping("/{id}/investments/{investmentId}")
    fun unmapInvestment(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @PathVariable investmentId: UUID,
    ): GoalResponse = service.unmapInvestment(householdId, id, investmentId).toResponse()

    @PatchMapping("/{id}/visibility")
    fun changeVisibility(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @RequestBody @Valid body: ChangeGoalVisibilityBody,
    ): GoalResponse =
        service.changeVisibility(householdId, id, body.visibility, body.visibleToMemberIds).toResponse()

    @DeleteMapping("/{id}")
    fun archive(@PathVariable householdId: UUID, @PathVariable id: UUID): ResponseEntity<Void> {
        service.archive(householdId, id)
        return ResponseEntity.noContent().build()
    }

    private fun GoalRow.toResponse(): GoalResponse = GoalResponse(
        id = id, name = name,
        targetAmount = targetAmount, targetAmountFormatted = IndianNumbers.rupees(targetAmount),
        targetDate = targetDate, priority = priority,
        memberId = memberId, memberName = memberName, icon = icon, notes = notes,
        status = status, visibility = visibility, visibleToMemberIds = visibleToMemberIds,
        funded = funded, fundedFormatted = IndianNumbers.rupees(funded),
        progress = service.progress(this),
        fundedBy = fundedBy.map {
            FundingSourceResponse(
                it.investmentId, it.title, it.allocationPct, it.contribution,
                IndianNumbers.rupees(it.contribution),
            )
        },
        version = version,
        disclaimer = "Based on what you've recorded. Informational — not financial advice.",
    )
}

/** Holdings pointed at nothing — surfaced, not forced (docs/10 Epic 2.1). */
@RestController
@RequestMapping("/api/v1/households/{householdId}")
class UnallocatedController(private val service: GoalService) {

    @GetMapping("/goals-unallocated")
    fun unallocated(@PathVariable householdId: UUID): List<UnallocatedHolding> =
        service.unallocated(householdId)
}
