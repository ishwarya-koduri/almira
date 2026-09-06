package tech.bhrigu.almira.estate

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.security.RequestUserContext
import java.time.LocalDate
import java.util.UUID

data class CreateContact(
    val id: UUID? = null,
    val kind: String = "other",
    val name: String,
    val organisation: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    val notes: String? = null,
    val visibility: String = "household",
    val links: List<ContactLinkInput> = emptyList(),
)

data class ContactLinkInput(val entityType: String, val entityId: UUID, val role: String? = null)

data class UpdateContact(
    val version: Int,
    val kind: String? = null,
    val name: String? = null,
    val organisation: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    val notes: String? = null,
    val visibility: String? = null,
)

data class CreateEstateDocument(
    val id: UUID? = null,
    val memberId: UUID,
    val kind: String,
    val title: String,
    val executedOn: LocalDate? = null,
    val location: String? = null,
    val registered: Boolean = false,
    val status: String = "executed",
    val notes: String? = null,
    val documentId: UUID? = null,
    val visibility: String = "private",
    val roles: List<EstateRoleInput> = emptyList(),
    val beneficiaries: List<BeneficiaryInput> = emptyList(),
)

data class UpdateEstateDocument(
    val version: Int,
    val title: String? = null,
    val executedOn: LocalDate? = null,
    val location: String? = null,
    val registered: Boolean? = null,
    val status: String? = null,
    val notes: String? = null,
    val documentId: UUID? = null,
    val visibility: String? = null,
    val roles: List<EstateRoleInput>? = null,
    val beneficiaries: List<BeneficiaryInput>? = null,
)

/**
 * A mismatch, phrased the way someone would say it out loud.
 *
 * This is the single most valuable thing in the estate module and the reason it
 * exists: in India a nominee receives, and an heir inherits, and they are
 * routinely different people. Nobody discovers that on a good day.
 */
data class NomineeWillMismatch(
    val investmentId: UUID,
    val title: String,
    val nominees: List<String>,
    val heirs: List<String>,
    val estateDocumentId: UUID?,
    val explanation: String,
)

@Service
class EstateService(
    private val contacts: ContactRepository,
    private val estates: EstateRepository,
    private val households: HouseholdService,
    private val audit: AuditService,
    private val userContext: RequestUserContext,
) {

    // --- contacts -------------------------------------------------------------

    @Transactional
    fun createContact(householdId: UUID, input: CreateContact): ContactRow {
        val userId = userContext.require()
        households.get(householdId)
        if (input.name.isBlank()) {
            throw ApiException.badRequest("name_required", "Who is it? A name is enough to start.")
        }
        requireVisibility(input.visibility)
        requireKind(input.kind)

        val id = input.id ?: UUID.randomUUID()
        contacts.find(householdId, id)?.let { return it }

        contacts.insert(
            id = id, householdId = householdId, kind = input.kind, name = input.name.trim(),
            organisation = input.organisation, phone = input.phone, email = input.email,
            address = input.address, notes = input.notes, visibility = input.visibility,
            createdBy = userId,
        )
        input.links.forEach { link(householdId, id, it) }

        audit.record(
            householdId = householdId, actorUserId = userId, action = "contact.create",
            entityType = "contact", entityId = id, diff = mapOf("kind" to input.kind),
        )
        return getContact(householdId, id)
    }

    @Transactional(readOnly = true)
    fun listContacts(householdId: UUID, kind: String?, entity: Pair<String, UUID>?): List<ContactRow> {
        households.get(householdId)
        return contacts.list(householdId, kind, entity)
    }

    @Transactional(readOnly = true)
    fun getContact(householdId: UUID, id: UUID): ContactRow {
        households.get(householdId)
        return contacts.find(householdId, id) ?: throw ApiException.notFound()
    }

    @Transactional
    fun updateContact(householdId: UUID, id: UUID, input: UpdateContact): ContactRow {
        val current = getContact(householdId, id)
        input.visibility?.let(::requireVisibility)
        input.kind?.let(::requireKind)
        val updated = contacts.update(
            id = id, version = input.version, kind = input.kind, name = input.name?.trim(),
            organisation = input.organisation, phone = input.phone, email = input.email,
            address = input.address, notes = input.notes, visibility = input.visibility,
        )
        if (updated == 0) throw staleWrite(current.version)
        return getContact(householdId, id)
    }

    /**
     * Linking is a write on the contact, not on the record — deliberately. You
     * may note that your CA handles a holding you can see; you may not use a
     * link to discover one you cannot, so the far end is read under your own
     * RLS first and simply refused if it is not there.
     */
    @Transactional
    fun link(householdId: UUID, contactId: UUID, input: ContactLinkInput): ContactRow {
        getContact(householdId, contactId)
        requireEntityType(input.entityType)
        if (!entityVisible(householdId, input.entityType, input.entityId)) throw ApiException.notFound()
        contacts.link(contactId, input.entityType, input.entityId, input.role)
        return getContact(householdId, contactId)
    }

    @Transactional
    fun unlink(householdId: UUID, contactId: UUID, entityType: String, entityId: UUID): ContactRow {
        getContact(householdId, contactId)
        contacts.unlink(contactId, entityType, entityId)
        return getContact(householdId, contactId)
    }

    @Transactional
    fun deleteContact(householdId: UUID, id: UUID) {
        getContact(householdId, id)
        if (contacts.softDelete(householdId, id) == 0) throw ApiException.notFound()
    }

    // --- estate documents -----------------------------------------------------

    @Transactional
    fun createDocument(householdId: UUID, input: CreateEstateDocument): EstateDocumentRow {
        val userId = userContext.require()
        households.get(householdId)
        if (input.title.isBlank()) {
            throw ApiException.badRequest("title_required", "Give it a name — “Ishwarya's will” is fine.")
        }
        requireVisibility(input.visibility)
        requireDocumentKind(input.kind)
        requireStatus(input.status)
        if (households.members(householdId).none { it.id == input.memberId }) {
            throw ApiException.badRequest("member_unknown", "That person isn't in this household.")
        }

        val id = input.id ?: UUID.randomUUID()
        estates.find(householdId, id)?.let { return it }

        estates.insert(
            id = id, householdId = householdId, memberId = input.memberId, kind = input.kind,
            title = input.title.trim(), executedOn = input.executedOn, location = input.location,
            registered = input.registered, status = input.status, notes = input.notes,
            documentId = input.documentId, visibility = input.visibility, createdBy = userId,
        )
        estates.replaceRoles(id, input.roles.map(::validateRole))
        estates.replaceBeneficiaries(id, input.beneficiaries.map(::validateBeneficiary))

        audit.record(
            householdId = householdId, actorUserId = userId, action = "estate.create",
            entityType = "estate_document", entityId = id,
            diff = mapOf("kind" to input.kind, "visibility" to input.visibility),
        )
        return getDocument(householdId, id)
    }

    @Transactional(readOnly = true)
    fun listDocuments(householdId: UUID, memberId: UUID?): List<EstateDocumentRow> {
        households.get(householdId)
        return estates.list(householdId, memberId)
    }

    @Transactional(readOnly = true)
    fun getDocument(householdId: UUID, id: UUID): EstateDocumentRow {
        households.get(householdId)
        return estates.find(householdId, id) ?: throw ApiException.notFound()
    }

    @Transactional
    fun updateDocument(householdId: UUID, id: UUID, input: UpdateEstateDocument): EstateDocumentRow {
        val userId = userContext.require()
        val current = getDocument(householdId, id)
        input.visibility?.let(::requireVisibility)
        input.status?.let(::requireStatus)

        val updated = estates.update(
            id = id, version = input.version, title = input.title?.trim(),
            executedOn = input.executedOn, location = input.location, registered = input.registered,
            status = input.status, notes = input.notes, documentId = input.documentId,
            visibility = input.visibility,
        )
        if (updated == 0) {
            // The instrument belongs to the person it is for. An admin can see a
            // shared will; changing it is not theirs to do.
            if (!ownsDocument(current)) {
                throw ApiException.forbidden("This is someone else's instrument to change.")
            }
            throw staleWrite(current.version)
        }
        input.roles?.let { estates.replaceRoles(id, it.map(::validateRole)) }
        input.beneficiaries?.let { estates.replaceBeneficiaries(id, it.map(::validateBeneficiary)) }

        audit.record(
            householdId = householdId, actorUserId = userId, action = "estate.update",
            entityType = "estate_document", entityId = id,
        )
        return getDocument(householdId, id)
    }

    @Transactional
    fun deleteDocument(householdId: UUID, id: UUID) {
        getDocument(householdId, id)
        if (estates.softDelete(householdId, id) == 0) throw ApiException.notFound()
    }

    // --- the mismatch ---------------------------------------------------------

    @Transactional(readOnly = true)
    fun mismatches(householdId: UUID): List<NomineeWillMismatch> {
        households.get(householdId)
        return estates.mismatches(householdId).map {
            NomineeWillMismatch(
                investmentId = it.investmentId,
                title = it.title,
                nominees = it.nomineeNames,
                heirs = it.heirNames,
                estateDocumentId = it.estateDocumentId,
                explanation = explain(it),
            )
        }
    }

    /**
     * Said plainly, and without telling anyone what to do about it. Which of
     * the two is wrong is a decision for the person and, usually, their lawyer.
     */
    private fun explain(row: MismatchRow): String {
        val nominees = row.nomineeNames.joinToString(" and ")
        val heirs = row.heirNames.joinToString(" and ")
        return "${row.title} pays $nominees as nominee, but the will leaves it to $heirs. " +
            "A nominee receives the money; an heir inherits it. Worth checking that this is deliberate."
    }

    // --- helpers --------------------------------------------------------------

    private fun ownsDocument(row: EstateDocumentRow): Boolean {
        val userId = userContext.require()
        return households.members(row.householdId)
            .any { it.id == row.memberId && it.userId == userId }
    }

    private fun entityVisible(householdId: UUID, type: String, id: UUID): Boolean =
        contacts.entityExists(householdId, type, id)

    private fun validateRole(role: EstateRoleInput): EstateRoleInput {
        if (role.role !in ROLES) {
            throw ApiException.badRequest(
                "role_invalid", "A role must be one of: ${ROLES.joinToString()}.",
            )
        }
        if (role.memberId == null && role.contactId == null && role.name.isNullOrBlank()) {
            throw ApiException.badRequest("role_needs_a_person", "Say who fills that role.")
        }
        return role.copy(name = role.name?.trim())
    }

    private fun validateBeneficiary(b: BeneficiaryInput): BeneficiaryInput {
        if (b.memberId == null && b.name.isNullOrBlank()) {
            throw ApiException.badRequest("beneficiary_needs_a_person", "Say who inherits it.")
        }
        if (b.sharePct <= java.math.BigDecimal.ZERO || b.sharePct > java.math.BigDecimal(100)) {
            throw ApiException.badRequest("share_invalid", "A share is between 1% and 100%.")
        }
        return b.copy(name = b.name?.trim())
    }

    private fun requireVisibility(value: String) {
        if (value !in setOf("private", "household", "scoped")) {
            throw ApiException.badRequest(
                "visibility_invalid", "Visibility must be private, household or scoped.",
            )
        }
    }

    private fun requireKind(value: String) {
        if (value !in CONTACT_KINDS) {
            throw ApiException.badRequest(
                "kind_invalid", "A contact is one of: ${CONTACT_KINDS.joinToString()}.",
            )
        }
    }

    private fun requireDocumentKind(value: String) {
        if (value !in DOCUMENT_KINDS) {
            throw ApiException.badRequest(
                "kind_invalid", "That has to be one of: ${DOCUMENT_KINDS.joinToString()}.",
            )
        }
    }

    private fun requireStatus(value: String) {
        if (value !in setOf("draft", "executed", "superseded", "revoked")) {
            throw ApiException.badRequest(
                "status_invalid", "Status must be draft, executed, superseded or revoked.",
            )
        }
    }

    private fun requireEntityType(value: String) {
        if (value !in setOf("investment", "liability", "account", "estate_document", "goal")) {
            throw ApiException.badRequest("entity_type_invalid", "That isn't something to link to.")
        }
    }

    private fun staleWrite(version: Int) = ApiException.conflict(
        "stale_write", "Someone else changed this while you were editing.",
        mapOf("currentVersion" to version),
    )

    private companion object {
        val CONTACT_KINDS = setOf(
            "ca", "advisor", "agent", "lawyer", "banker", "broker",
            "doctor", "executor", "witness", "other",
        )
        val DOCUMENT_KINDS = setOf(
            "will", "codicil", "poa", "living_will", "trust",
            "nomination_letter", "succession_certificate", "other",
        )
        val ROLES = setOf("executor", "alternate_executor", "attorney", "guardian", "witness", "trustee")
    }
}
