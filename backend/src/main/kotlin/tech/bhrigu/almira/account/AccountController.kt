package tech.bhrigu.almira.account

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
import java.time.Instant
import java.util.UUID

data class CreateAccountBody(
    val id: UUID? = null,
    val institutionId: UUID? = null,
    val accountKind: String = "savings",
    @field:NotBlank(message = "Give the account a name you'll recognise")
    @field:Size(max = 120) val label: String,
    val number: String? = null,
    /** Opt-in. Without it only the last four digits are kept. */
    val storeFullNumber: Boolean = false,
    val ifsc: String? = null,
    val notes: String? = null,
    val holders: List<HolderInput> = emptyList(),
    val visibility: String? = null,
    val visibleToMemberIds: List<UUID> = emptyList(),
)

data class ChangeAccountVisibilityBody(
    val visibility: String,
    val visibleToMemberIds: List<UUID> = emptyList(),
)

data class AccountHolderResponse(val memberId: UUID, val name: String?, val holderType: String)

data class AccountResponse(
    val id: UUID,
    val label: String,
    val accountKind: String,
    val institutionId: UUID?,
    val institutionName: String?,
    /** "••••7890", or null when no number was given. Never the full number. */
    val numberMasked: String?,
    /** Whether a full number is stored, and so whether it can be revealed. */
    val hasFullNumber: Boolean,
    val ifsc: String?,
    val notes: String?,
    val visibility: String,
    val visibleToMemberIds: List<UUID>,
    val holders: List<AccountHolderResponse>,
    val linkedInvestmentCount: Int,
    val version: Int,
    val createdAt: Instant,
)

data class RevealedNumberResponse(val number: String)

@RestController
@RequestMapping("/api/v1/households/{householdId}/accounts")
class AccountController(private val service: AccountService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable householdId: UUID,
        @RequestBody @Valid body: CreateAccountBody,
    ): AccountResponse = service.create(
        householdId,
        CreateAccount(
            id = body.id, institutionId = body.institutionId, accountKind = body.accountKind,
            label = body.label, number = body.number, storeFullNumber = body.storeFullNumber,
            ifsc = body.ifsc, notes = body.notes, holders = body.holders,
            visibility = body.visibility, visibleToMemberIds = body.visibleToMemberIds,
        ),
    ).toResponse()

    @GetMapping
    fun list(@PathVariable householdId: UUID): List<AccountResponse> =
        service.list(householdId).map { it.toResponse() }

    @GetMapping("/{id}")
    fun get(@PathVariable householdId: UUID, @PathVariable id: UUID): AccountResponse =
        service.get(householdId, id).toResponse()

    @PatchMapping("/{id}")
    fun update(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @RequestBody @Valid body: UpdateAccount,
    ): AccountResponse = service.update(householdId, id, body).toResponse()

    @PatchMapping("/{id}/visibility")
    fun changeVisibility(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @RequestBody @Valid body: ChangeAccountVisibilityBody,
    ): AccountResponse =
        service.changeVisibility(householdId, id, body.visibility, body.visibleToMemberIds)
            .toResponse()

    /**
     * POST, not GET: revealing a number is an event worth recording, and GETs
     * end up in browser history, proxy logs and referrers in ways a POST does
     * not. It also needs a recent step-up on this session (403 otherwise).
     */
    @PostMapping("/{id}/reveal-number")
    fun revealNumber(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
    ): RevealedNumberResponse = RevealedNumberResponse(service.revealNumber(householdId, id))

    @DeleteMapping("/{id}")
    fun archive(@PathVariable householdId: UUID, @PathVariable id: UUID): ResponseEntity<Void> {
        service.archive(householdId, id)
        return ResponseEntity.noContent().build()
    }

    private fun AccountRow.toResponse() = AccountResponse(
        id = id, label = label, accountKind = accountKind,
        institutionId = institutionId, institutionName = institutionName,
        numberMasked = numberMasked, hasFullNumber = hasFullNumber,
        ifsc = ifsc, notes = notes, visibility = visibility,
        visibleToMemberIds = visibleToMemberIds,
        holders = holders.map { AccountHolderResponse(it.memberId, it.memberName, it.holderType) },
        linkedInvestmentCount = linkedInvestmentCount, version = version, createdAt = createdAt,
    )
}
