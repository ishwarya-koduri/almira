package tech.bhrigu.almira.household

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
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

data class CreateHouseholdBody(
    @field:Size(max = 120) val name: String? = null,
    /** "just_me" or "family" — onboarding's choice cards (docs/03 §1). */
    val mode: String? = null,
    val defaultVisibility: String? = null,
    @field:Size(max = 80) val displayName: String? = null,
)

data class UpdateHouseholdBody(
    @field:Size(max = 120) val name: String? = null,
    val defaultVisibility: String? = null,
    val version: Int? = null,
)

data class AddMemberBody(
    @field:NotBlank(message = "Give this person a name")
    @field:Size(max = 80) val displayName: String,
    val relationship: String? = null,
    val dateOfBirth: LocalDate? = null,
    val notes: String? = null,
    /**
     * When this person died, if the family chooses to record it. Optional, and
     * used for one thing only: no Still true? reminder is sent on the anniversary
     * (docs/21 §6, "Not on a remembrance day").
     */
    val diedOn: LocalDate? = null,
)

data class UpdateMemberBody(
    @field:Size(max = 80) val displayName: String? = null,
    val relationship: String? = null,
    val dateOfBirth: LocalDate? = null,
    /** See [AddMemberBody.diedOn]. */
    val diedOn: LocalDate? = null,
    val version: Int? = null,
)

data class HouseholdResponse(
    val id: UUID,
    val name: String,
    val baseCurrency: String,
    val defaultVisibility: String,
    val myRole: String,
    val myMemberId: UUID?,
    val memberCount: Int,
    val version: Int,
    /**
     * You have been marked as having passed away in this household. Everything
     * you could see is still there; nothing can be changed until you say the
     * label is wrong (docs/05 §12).
     */
    val readOnly: Boolean = false,
)

data class MemberResponse(
    val id: UUID,
    val displayName: String,
    val relationship: String?,
    val dateOfBirth: LocalDate?,
    val isMinor: Boolean,
    /** No login of their own — a child or an elderly parent tracked by someone else. */
    val isManaged: Boolean,
    val isMe: Boolean,
    val role: String?,
    val version: Int,
    /** Marked as having passed away. A quiet label on the roster, nothing more. */
    val passedAway: Boolean = false,
    val memorialisedAt: java.time.Instant? = null,
    /** See [AddMemberBody.diedOn]. Null when not recorded. */
    val diedOn: LocalDate? = null,
)

@RestController
@RequestMapping("/api/v1/households")
class HouseholdController(private val service: HouseholdService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody @Valid body: CreateHouseholdBody): HouseholdResponse =
        service.create(
            name = body.name,
            defaultVisibility = body.defaultVisibility,
            displayName = body.displayName,
        ).toResponse()

    @GetMapping
    fun listMine(): List<HouseholdResponse> = service.listMine().map { it.toResponse() }

    @GetMapping("/{householdId}")
    fun get(@PathVariable householdId: UUID): HouseholdResponse =
        service.get(householdId).toResponse()

    @PatchMapping("/{householdId}")
    fun update(
        @PathVariable householdId: UUID,
        @RequestBody @Valid body: UpdateHouseholdBody,
    ): HouseholdResponse =
        service.update(householdId, body.name, body.defaultVisibility, body.version).toResponse()

    @GetMapping("/{householdId}/members")
    fun members(@PathVariable householdId: UUID): List<MemberResponse> =
        service.members(householdId).map { it.toResponse() }

    @PostMapping("/{householdId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    fun addMember(
        @PathVariable householdId: UUID,
        @RequestBody @Valid body: AddMemberBody,
    ): MemberResponse = service.addMember(
        householdId, body.displayName, body.relationship, body.dateOfBirth, body.notes, body.diedOn,
    ).toResponse()

    @PatchMapping("/{householdId}/members/{memberId}")
    fun updateMember(
        @PathVariable householdId: UUID,
        @PathVariable memberId: UUID,
        @RequestBody @Valid body: UpdateMemberBody,
    ): MemberResponse = service.updateMember(
        householdId, memberId, body.displayName, body.relationship, body.dateOfBirth, body.version, body.diedOn,
    ).toResponse()

    @DeleteMapping("/{householdId}/members/{memberId}")
    fun removeMember(
        @PathVariable householdId: UUID,
        @PathVariable memberId: UUID,
    ): ResponseEntity<Void> {
        service.removeMember(householdId, memberId)
        return ResponseEntity.noContent().build()
    }

    private fun HouseholdRow.toResponse() = HouseholdResponse(
        id, name, baseCurrency, defaultVisibility, myRole, myMemberId, memberCount, version, readOnly,
    )

    private fun MemberRow.toResponse() = MemberResponse(
        id, displayName, relationship, dateOfBirth, isMinor, isManaged, isMe, role, version,
        passedAway = memorialisedAt != null, memorialisedAt = memorialisedAt, diedOn = diedOn,
    )
}
