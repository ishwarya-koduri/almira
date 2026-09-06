package tech.bhrigu.almira.continuity

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class NameTrustedContactBody(
    val trustedMemberId: UUID,
    /** How long you have to say no. Default a fortnight — long enough to notice. */
    val waitDays: Int = 14,
    val note: String? = null,
)

data class RequestAccessBody(val subjectMemberId: UUID, val reason: String? = null)

@RestController
@RequestMapping("/api/v1/households/{householdId}/emergency")
class EmergencyController(private val service: EmergencyService) {

    @GetMapping("/contacts")
    fun contacts(@PathVariable householdId: UUID): List<TrustedContact> =
        service.listTrustedContacts(householdId)

    @PostMapping("/contacts")
    @ResponseStatus(HttpStatus.CREATED)
    fun nameContact(
        @PathVariable householdId: UUID,
        @RequestBody @Valid body: NameTrustedContactBody,
    ): TrustedContact =
        service.nameTrustedContact(householdId, body.trustedMemberId, body.waitDays, body.note)

    @DeleteMapping("/contacts/{trustedMemberId}")
    fun removeContact(
        @PathVariable householdId: UUID,
        @PathVariable trustedMemberId: UUID,
    ): ResponseEntity<Void> {
        service.removeTrustedContact(householdId, trustedMemberId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/requests")
    fun requests(@PathVariable householdId: UUID): List<EmergencyRequestRow> = service.list(householdId)

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    fun request(
        @PathVariable householdId: UUID,
        @RequestBody @Valid body: RequestAccessBody,
    ): EmergencyRequestRow = service.request(householdId, body.subjectMemberId, body.reason)

    /** The owner saying no. Available at any point before the window opens. */
    @PostMapping("/requests/{id}/veto")
    fun veto(@PathVariable householdId: UUID, @PathVariable id: UUID): EmergencyRequestRow =
        service.veto(householdId, id)

    @PostMapping("/requests/{id}/withdraw")
    fun withdraw(@PathVariable householdId: UUID, @PathVariable id: UUID): EmergencyRequestRow =
        service.withdraw(householdId, id)
}
