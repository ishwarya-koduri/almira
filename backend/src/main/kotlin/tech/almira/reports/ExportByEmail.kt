package tech.almira.reports

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tech.almira.auth.VerifiedEmailService
import tech.almira.common.ApiException
import tech.almira.household.HouseholdService
import tech.almira.security.RequestUserContext
import java.util.UUID

data class EmailExportBody(
    @field:NotBlank(message = "Choose an address to send to")
    @field:Size(max = 254, message = "That address is too long")
    val address: String,
    @field:Pattern(regexp = "^(csv|xlsx|pdf)$", message = "Choose csv, xlsx or pdf")
    val format: String = "csv",
)

/**
 * Emailing an export — the guard, before the sending it guards.
 *
 * Nothing is sent here yet: the delivery is the next stage, and a destination
 * that gets past this answers a deliberate 501 until it lands. The order is on
 * purpose. A guard written after the action it protects has to be retro-fitted
 * onto every path that already reaches the action, and one path is always
 * missed — this repository has an entry about it (docs/known-issues, "A guard
 * runs before the action it guards"), and it is worse here than in most places:
 * the thing being guarded is a household's whole financial record leaving the
 * building.
 *
 * Two refusals, in this order, and the order is the design:
 *
 *  1. **The household**, first. One you cannot see is not found, exactly as it
 *     is everywhere else — asking for it to be *emailed* must not be a way to
 *     learn it exists. This is why the check is here and not after the address:
 *     a stranger holding a perfectly good address of their own would otherwise
 *     get a different answer for a household that exists than for one that does
 *     not.
 *  2. **The address**, which must be one this account has proved
 *     ([VerifiedEmailService.requireProved]). That guard is unconditional and
 *     not part of `almira.exports.email.enabled`: the flag decides whether this
 *     controller exists at all, and nothing decides whether the destination has
 *     to be proved.
 *
 * The service is an ordinary bean; only the controller is conditional. So the
 * guard is compiled in and enforced whatever the flag says, and a later caller
 * that finds its way to this service — a scheduled send, say — meets the same
 * refusals rather than a new copy of them.
 */
@Service
class ExportByEmailService(
    private val households: HouseholdService,
    private val destinations: VerifiedEmailService,
) {

    /**
     * Transactional because both checks read under row-level security, and a
     * statement outside a transaction carries no identity: the household would
     * be "not found" for its own owner (RlsTransactionManager).
     *
     * `readOnly` while this only refuses. The stage that adds the sending will
     * have to take the delivery out of this transaction rather than widen it —
     * holding a database connection open across an SMTP conversation ties a
     * pooled resource to somebody else's mail server.
     */
    @Transactional(readOnly = true)
    fun send(userId: UUID, householdId: UUID, rawAddress: String, format: String): Nothing {
        households.get(householdId)
        destinations.requireProved(userId, rawAddress)
        throw ApiException(
            HttpStatus.NOT_IMPLEMENTED,
            "not_implemented",
            "Sending an export by email isn't finished yet.",
        )
    }
}

@RestController
@RequestMapping("/api/v1/households/{householdId}/reports/export")
@ConditionalOnProperty(name = ["almira.exports.email.enabled"], havingValue = "true")
class ExportByEmailController(
    private val emails: ExportByEmailService,
    private val userContext: RequestUserContext,
) {

    @PostMapping("/email")
    fun email(
        @PathVariable householdId: UUID,
        @RequestBody @Valid body: EmailExportBody,
    ): ResponseEntity<Void> = emails.send(userContext.require(), householdId, body.address, body.format)
}
