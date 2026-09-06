package tech.bhrigu.almira.estate

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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class ContactLinkResponse(
    val entityType: String,
    val entityId: UUID,
    /** Null when the record at the far end is not visible to you. */
    val entityTitle: String?,
    val role: String?,
)

data class ContactResponse(
    val id: UUID,
    val kind: String,
    val name: String,
    val organisation: String?,
    val phone: String?,
    val email: String?,
    val address: String?,
    val notes: String?,
    val visibility: String,
    val links: List<ContactLinkResponse>,
    val version: Int,
)

data class EstateRoleResponse(
    val id: UUID,
    val role: String,
    val memberId: UUID?,
    val contactId: UUID?,
    val name: String,
    val note: String?,
)

data class BeneficiaryResponse(
    val id: UUID,
    val investmentId: UUID?,
    val investmentTitle: String?,
    val liabilityId: UUID?,
    val memberId: UUID?,
    val name: String,
    val relationship: String?,
    val sharePct: BigDecimal,
    val note: String?,
)

data class EstateDocumentResponse(
    val id: UUID,
    val memberId: UUID,
    val memberName: String?,
    val kind: String,
    val title: String,
    val executedOn: LocalDate?,
    val location: String?,
    val registered: Boolean,
    val status: String,
    val notes: String?,
    val documentId: UUID?,
    val visibility: String,
    val roles: List<EstateRoleResponse>,
    val beneficiaries: List<BeneficiaryResponse>,
    val version: Int,
    /** Informational, never legal advice (docs/08 §6). */
    val disclaimer: String = DISCLAIMER,
)

internal const val DISCLAIMER =
    "Almira records that these documents exist and where they are. It does not draft " +
        "them, and nothing here is legal advice."

internal fun ContactRow.toResponse() = ContactResponse(
    id = id, kind = kind, name = name, organisation = organisation, phone = phone,
    email = email, address = address, notes = notes, visibility = visibility,
    links = links.map { ContactLinkResponse(it.entityType, it.entityId, it.entityTitle, it.role) },
    version = version,
)

internal fun EstateDocumentRow.toResponse() = EstateDocumentResponse(
    id = id, memberId = memberId, memberName = memberName, kind = kind, title = title,
    executedOn = executedOn, location = location, registered = registered, status = status,
    notes = notes, documentId = documentId, visibility = visibility,
    roles = roles.map { EstateRoleResponse(it.id, it.role, it.memberId, it.contactId, it.name, it.note) },
    beneficiaries = beneficiaries.map {
        BeneficiaryResponse(
            it.id, it.investmentId, it.investmentTitle, it.liabilityId, it.memberId,
            it.name, it.relationship, it.sharePct, it.note,
        )
    },
    version = version,
)

@RestController
@RequestMapping("/api/v1/households/{householdId}/contacts")
class ContactController(private val service: EstateService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable householdId: UUID,
        @RequestBody @Valid body: CreateContact,
    ): ContactResponse = service.createContact(householdId, body).toResponse()

    @GetMapping
    fun list(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) kind: String?,
        @RequestParam(required = false) entityType: String?,
        @RequestParam(required = false) entityId: UUID?,
    ): List<ContactResponse> {
        val entity = if (entityType != null && entityId != null) entityType to entityId else null
        return service.listContacts(householdId, kind, entity).map { it.toResponse() }
    }

    @GetMapping("/{id}")
    fun get(@PathVariable householdId: UUID, @PathVariable id: UUID): ContactResponse =
        service.getContact(householdId, id).toResponse()

    @PatchMapping("/{id}")
    fun update(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @RequestBody @Valid body: UpdateContact,
    ): ContactResponse = service.updateContact(householdId, id, body).toResponse()

    /** "Who do I call about this?" — answered from the record's own side too. */
    @PostMapping("/{id}/links")
    fun link(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @RequestBody @Valid body: ContactLinkInput,
    ): ContactResponse = service.link(householdId, id, body).toResponse()

    @DeleteMapping("/{id}/links")
    fun unlink(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @RequestParam entityType: String,
        @RequestParam entityId: UUID,
    ): ContactResponse = service.unlink(householdId, id, entityType, entityId).toResponse()

    @DeleteMapping("/{id}")
    fun delete(@PathVariable householdId: UUID, @PathVariable id: UUID): ResponseEntity<Void> {
        service.deleteContact(householdId, id)
        return ResponseEntity.noContent().build()
    }
}

@RestController
@RequestMapping("/api/v1/households/{householdId}/estate")
class EstateController(private val service: EstateService) {

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable householdId: UUID,
        @RequestBody @Valid body: CreateEstateDocument,
    ): EstateDocumentResponse = service.createDocument(householdId, body).toResponse()

    @GetMapping("/documents")
    fun list(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) member: UUID?,
    ): List<EstateDocumentResponse> = service.listDocuments(householdId, member).map { it.toResponse() }

    @GetMapping("/documents/{id}")
    fun get(@PathVariable householdId: UUID, @PathVariable id: UUID): EstateDocumentResponse =
        service.getDocument(householdId, id).toResponse()

    @PatchMapping("/documents/{id}")
    fun update(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @RequestBody @Valid body: UpdateEstateDocument,
    ): EstateDocumentResponse = service.updateDocument(householdId, id, body).toResponse()

    @DeleteMapping("/documents/{id}")
    fun delete(@PathVariable householdId: UUID, @PathVariable id: UUID): ResponseEntity<Void> {
        service.deleteDocument(householdId, id)
        return ResponseEntity.noContent().build()
    }

    /**
     * Where a nominee and a will disagree. Computed through your own visibility,
     * so you are shown a mismatch only on a holding you can see, named in an
     * instrument you can see.
     */
    @GetMapping("/mismatches")
    fun mismatches(@PathVariable householdId: UUID): List<NomineeWillMismatch> =
        service.mismatches(householdId)
}
