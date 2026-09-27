package tech.almira.invitation

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
import tech.almira.lifecycle.ComingOfAgeNotices
import tech.almira.security.RequestUserContext
import java.util.UUID

data class CreateInvitationBody(
    /** Naming a managed member makes acceptance CLAIM that person rather than add a new one. */
    val memberId: UUID? = null,
    val phone: String? = null,
    val email: String? = null,
    val role: String = "editor",
)

@RestController
@RequestMapping("/api/v1")
class InvitationController(
    private val service: InvitationService,
    private val comingOfAge: ComingOfAgeNotices,
    private val userContext: RequestUserContext,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)


    @PostMapping("/households/{householdId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable householdId: UUID,
        @RequestBody @Valid body: CreateInvitationBody,
    ): CreatedInvitation =
        service.create(householdId, body.memberId, body.phone, body.email, body.role)

    @GetMapping("/households/{householdId}/invitations")
    fun list(@PathVariable householdId: UUID): List<InvitationRow> = service.list(householdId)

    @DeleteMapping("/households/{householdId}/invitations/{invitationId}")
    fun revoke(
        @PathVariable householdId: UUID,
        @PathVariable invitationId: UUID,
    ): ResponseEntity<Void> {
        service.revoke(householdId, invitationId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/invitations/accept")
    fun accept(@RequestBody body: Map<String, String>): AcceptedInvitation {
        val token = body["token"]
            ?: throw tech.almira.common.ApiException.badRequest(
                "token_required", "That invitation link isn't valid.",
            )
        val accepted = service.accept(token)
        // Once the claim has committed: a young adult who came of age with no login is told
        // now, at their first sign-in as themselves (V146). Nothing if they already were.
        accepted.memberId?.let { memberId ->
            runCatching { comingOfAge.tellOnFirstSignIn(memberId, userContext.require()) }
                .onFailure { log.warn("coming of age: could not tell member {} at first sign-in: {}", memberId, it.javaClass.simpleName) }
        }
        return accepted
    }
}
