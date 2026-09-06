package tech.bhrigu.almira.investment

import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.auth.AuthService
import tech.bhrigu.almira.catalog.CatalogRepository
import tech.bhrigu.almira.catalog.CatalogService
import tech.bhrigu.almira.catalog.FieldOption
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.security.RequestUserContext
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class OwnerInput(val memberId: UUID, val sharePct: BigDecimal? = null)

data class CustomFieldInput(
    val key: String,
    val label: String,
    val dataType: String,
    val unit: String? = null,
    val options: List<FieldOption>? = null,
    val required: Boolean = false,
    /** At most one per record may count toward value — enforced by the database. */
    val countsTowardValue: Boolean = false,
)

/**
 * A nominee is either a household member or a plain name — an aunt who will
 * never use this app is still a nominee, and the record has to hold her.
 */
data class NomineeInput(
    val memberId: UUID? = null,
    val name: String? = null,
    val relationship: String? = null,
    val sharePct: BigDecimal? = null,
)

data class ValuationInput(
    val value: BigDecimal,
    val asOfDate: LocalDate? = null,
    val quantity: BigDecimal? = null,
    val note: String? = null,
)

data class CreateInvestment(
    /** Client-supplied so an offline capture keeps its identity and retries are idempotent. */
    val id: UUID? = null,
    val typeId: UUID,
    val title: String,
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
    /**
     * Set only by the spreadsheet import: save what the sheet had, and let the
     * completeness report ask for the rest. Not reachable from the API body —
     * the controller builds this object field by field.
     */
    val allowMissingRequired: Boolean = false,
)

/**
 * What to change about the copy. Everything not named here is carried over.
 */
data class DuplicateInvestment(
    /** Client-supplied, so a retry of the same tap does not make a third record. */
    val id: UUID? = null,
    val title: String? = null,
    val investedAmount: BigDecimal? = null,
    val quantity: BigDecimal? = null,
    val startDate: LocalDate? = null,
    val maturityDate: LocalDate? = null,
    val visibility: String? = null,
    /** The nominees are usually the same people; the option exists for when they aren't. */
    val copyNominees: Boolean = true,
    /**
     * False for a renewal. Carrying the old maturity date forward would create a
     * record that matured before it started, and it would show on the dashboard
     * as overdue the day it was made.
     */
    val carryMaturityDate: Boolean = true,
)

data class RolledOver(val previous: InvestmentRow, val created: CreatedInvestment)

data class UpdateInvestment(
    val version: Int,
    val title: String? = null,
    val investedAmount: BigDecimal? = null,
    val quantity: BigDecimal? = null,
    val unit: String? = null,
    val startDate: LocalDate? = null,
    val maturityDate: LocalDate? = null,
    val storageLocation: String? = null,
    val institutionId: UUID? = null,
    val accountId: UUID? = null,
    val attributes: Map<String, Any?>? = null,
    val notes: String? = null,
    val status: String? = null,
    val isInContinuity: Boolean? = null,
    val owners: List<OwnerInput>? = null,
)

/** A record saved but not visible to its creator is a legitimate outcome, so it is reported. */
data class CreatedInvestment(val id: UUID, val visibleToYou: Boolean, val record: InvestmentRow?)

@Service
class InvestmentService(
    private val repo: InvestmentRepository,
    private val catalog: CatalogService,
    private val catalogRepo: CatalogRepository,
    private val households: HouseholdService,
    private val validator: AttributeValidator,
    private val auth: AuthService,
    private val audit: AuditService,
    private val reminders: tech.bhrigu.almira.reminder.ReminderService,
    private val userContext: RequestUserContext,
) {
    private val visibilities = setOf("private", "household", "scoped")
    private val statuses = setOf("active", "matured", "closed", "draft", "archived")

    @Transactional
    fun create(householdId: UUID, input: CreateInvestment): CreatedInvestment {
        val userId = userContext.require()
        val household = households.get(householdId)
        val type = catalog.type(householdId, input.typeId)

        if (input.title.isBlank()) {
            throw ApiException.badRequest("title_required", "Give this a name you'll recognise.")
        }

        val id = input.id ?: UUID.randomUUID()
        // A retry of an offline capture arrives with the same id. If we can see
        // the earlier one, it succeeded and this is a duplicate delivery.
        repo.find(householdId, id)?.let {
            return CreatedInvestment(it.id, visibleToYou = true, record = it)
        }

        val owners = resolveOwners(householdId, household.myMemberId, input.owners)
        val visibility = resolveVisibility(input.visibility, household.defaultVisibility, userId)
        val grants = resolveGrants(householdId, visibility, input.visibleToMemberIds, owners)

        input.customFields.forEach { catalog.validateFieldDefinition(it.toFieldDef()) }
        // Definitions must exist before the values are validated against them.
        val recordFields = input.customFields.map { field ->
            catalogRepo.createCustomField(
                householdId = householdId, ownerType = "record", ownerId = id,
                key = field.key, label = field.label, dataType = field.dataType,
                unit = field.unit, options = field.options, required = field.required,
                countsTowardValue = field.countsTowardValue,
            )
            field
        }
        val customDefs = if (recordFields.isEmpty()) emptyList()
        else catalogRepo.customFields("record", listOf(id))

        val attributes = validator.validate(
            type.schema, customDefs, input.attributes,
            requireEssentials = !input.allowMissingRequired,
        )

        try {
            repo.insert(
                id = id, householdId = householdId, typeId = type.id, title = input.title.trim(),
                investedAmount = input.investedAmount, currency = input.currency ?: household.baseCurrency,
                quantity = input.quantity, unit = input.unit,
                startDate = input.startDate, maturityDate = input.maturityDate,
                storageLocation = input.storageLocation,
                institutionId = input.institutionId, accountId = input.accountId,
                attributes = attributes, notes = input.notes,
                visibility = visibility, isInContinuity = input.isInContinuity,
                createdBy = userId,
            )
        } catch (_: DuplicateKeyException) {
            throw ApiException.conflict(
                "already_exists", "This one is already saved.", mapOf("id" to id),
            )
        }

        repo.replaceOwners(id, owners, holderType = "primary")
        if (grants.isNotEmpty()) repo.replaceVisibilityGrants(householdId, id, grants, userId)
        input.initialValuation?.let {
            repo.addValuation(id, it.asOfDate ?: LocalDate.now(), it.value, it.quantity, it.note, userId)
        }

        audit.record(
            householdId = householdId, actorUserId = userId, action = "investment.create",
            entityType = "investment", entityId = id,
            diff = mapOf("title" to input.title, "type" to type.code, "visibility" to visibility),
        )

        val readBack = repo.find(householdId, id)
        // A maturity date, a premium or a SIP implies a reminder. Creating it
        // automatically is the difference between a registry that tells you
        // things and one you have to remember to interrogate.
        readBack?.let(reminders::syncForInvestment)
        return CreatedInvestment(id, visibleToYou = readBack != null, record = readBack)
    }

    /**
     * The fourth FD of the year, typed once.
     *
     * A duplicate copies the *shape* — type, institution, account, unit,
     * attributes, custom field definitions, owners, nominees, visibility — and
     * none of the history. Valuations, transactions, tax lots and documents
     * belong to the record that actually happened; copying them would invent a
     * second purchase that never took place, and every return figure downstream
     * would be wrong in the same direction.
     */
    @Transactional
    fun duplicate(householdId: UUID, id: UUID, input: DuplicateInvestment): CreatedInvestment {
        val source = get(householdId, id)
        val customFields = catalogRepo.customFields("record", listOf(id))

        val created = create(
            householdId,
            CreateInvestment(
                id = input.id,
                typeId = source.typeId,
                title = input.title?.trim()?.takeIf { it.isNotBlank() } ?: "${source.title} (copy)",
                investedAmount = input.investedAmount ?: source.investedAmount,
                currency = source.currency,
                quantity = input.quantity ?: source.quantity,
                unit = source.unit,
                startDate = input.startDate ?: source.startDate,
                maturityDate = input.maturityDate
                    ?: source.maturityDate.takeIf { input.carryMaturityDate },
                storageLocation = source.storageLocation,
                institutionId = source.institutionId,
                accountId = source.accountId,
                attributes = source.attributes,
                owners = source.owners.map { OwnerInput(it.memberId, it.sharePct) },
                visibility = input.visibility ?: source.visibility,
                visibleToMemberIds = source.visibleToMemberIds,
                isInContinuity = source.isInContinuity,
                notes = source.notes,
                customFields = customFields.map {
                    CustomFieldInput(
                        key = it.key, label = it.label, dataType = it.dataType, unit = it.unit,
                        options = it.options, required = it.required,
                        countsTowardValue = it.countsTowardValue,
                    )
                },
            ),
        )

        if (input.copyNominees && source.nominees.isNotEmpty()) {
            repo.replaceNominees(
                created.id,
                source.nominees.map {
                    Triple(it.memberId, it.name, it.relationship to it.sharePct)
                },
            )
        }
        return created.copy(record = repo.find(householdId, created.id))
    }

    /**
     * A maturity, renewed.
     *
     * The old record is not edited into the new one: it is marked matured and
     * kept, and the new one points back at it. Overwriting would lose the years
     * of valuations and interest that make the renewal worth recording — and
     * "what did that FD actually earn?" is exactly the question a registry is
     * for (docs/07 §1 "rollover without losing history").
     */
    @Transactional
    fun rollover(householdId: UUID, id: UUID, input: DuplicateInvestment): RolledOver {
        val userId = userContext.require()
        val source = get(householdId, id)
        if (source.status == "closed") {
            throw ApiException.badRequest(
                "already_closed", "This one is closed. Add it as a new record instead.",
            )
        }

        val created = duplicate(
            householdId, id,
            input.copy(
                title = input.title ?: source.title,
                // The new term starts where the old one ended, unless told otherwise.
                startDate = input.startDate ?: source.maturityDate ?: LocalDate.now(),
                // Ask for the new maturity date rather than guessing at it.
                maturityDate = input.maturityDate,
                carryMaturityDate = false,
            ),
        )
        repo.setRolledFrom(created.id, id)
        repo.copyGoalLinks(id, created.id)

        val previous = repo.update(
            id = id, version = source.version, status = "matured",
            title = null, investedAmount = null, quantity = null, unit = null,
            startDate = null, maturityDate = null, storageLocation = null,
            institutionId = null, accountId = null, attributes = null, notes = null,
            isInContinuity = null,
        )
        if (previous == 0) {
            throw ApiException.conflict(
                "stale_write",
                "Someone else changed this while you were renewing it. Reload and try again.",
                mapOf("currentVersion" to source.version),
            )
        }

        audit.record(
            householdId = householdId, actorUserId = userId, action = "investment.rollover",
            entityType = "investment", entityId = id,
            diff = mapOf("renewedAs" to created.id.toString()),
        )
        return RolledOver(previous = get(householdId, id), created = created.copy(record = repo.find(householdId, created.id)))
    }

    @Transactional(readOnly = true)
    fun list(householdId: UUID, filter: InvestmentFilter): List<InvestmentRow> {
        households.get(householdId)
        return repo.list(householdId, filter)
    }

    @Transactional(readOnly = true)
    fun get(householdId: UUID, id: UUID): InvestmentRow {
        households.get(householdId)
        return repo.find(householdId, id) ?: throw ApiException.notFound()
    }

    @Transactional
    fun update(householdId: UUID, id: UUID, input: UpdateInvestment): InvestmentRow {
        val userId = userContext.require()
        val household = households.get(householdId)
        val current = get(householdId, id)
        input.status?.let(::requireStatus)

        val attributes = input.attributes?.let {
            val type = catalog.type(householdId, current.typeId)
            val customDefs = catalogRepo.customFields("record", listOf(id))
            validator.validate(type.schema, customDefs, it)
        }

        val updated = repo.update(
            id = id, version = input.version, title = input.title?.trim(),
            investedAmount = input.investedAmount, quantity = input.quantity, unit = input.unit,
            startDate = input.startDate, maturityDate = input.maturityDate,
            storageLocation = input.storageLocation, institutionId = input.institutionId,
            accountId = input.accountId, attributes = attributes, notes = input.notes,
            status = input.status, isInContinuity = input.isInContinuity,
        )
        if (updated == 0) {
            // Nothing was written for one of two very different reasons, and
            // saying the wrong one is worse than unhelpful: a viewer or an
            // advisor told "someone else changed this" will reload, try again,
            // and see the same thing forever. If the version they sent is still
            // the current one, nobody changed anything — the write was refused.
            if (input.version == current.version) {
                throw ApiException.forbidden("You can read this, but it isn't yours to change.")
            }
            throw ApiException.conflict(
                "stale_write",
                "Someone else changed this while you were editing. Reload and try again.",
                mapOf("currentVersion" to current.version),
            )
        }

        input.owners?.let {
            repo.replaceOwners(id, resolveOwners(householdId, household.myMemberId, it), "primary")
        }

        audit.record(
            householdId = householdId, actorUserId = userId, action = "investment.update",
            entityType = "investment", entityId = id,
        )
        return get(householdId, id).also(reminders::syncForInvestment)
    }

    /**
     * Changing visibility is audited separately from an ordinary edit. Moving a
     * record from household to private retroactively hides it from everyone
     * else — from lists, search, reports and totals alike — and that is exactly
     * the kind of change someone may need to account for later (docs/05 §3.5).
     */
    @Transactional
    fun changeVisibility(
        householdId: UUID,
        id: UUID,
        visibility: String,
        visibleToMemberIds: List<UUID>,
    ): InvestmentRow {
        val userId = userContext.require()
        households.get(householdId)
        val current = get(householdId, id)
        requireVisibility(visibility)

        val grants =
            if (visibility == "scoped") {
                resolveGrants(householdId, visibility, visibleToMemberIds, current.owners.map {
                    it.memberId to it.sharePct
                })
            } else {
                emptyList()
            }

        repo.updateVisibility(id, visibility)
        repo.replaceVisibilityGrants(householdId, id, grants, userId)

        audit.record(
            householdId = householdId, actorUserId = userId, action = "investment.visibility_change",
            entityType = "investment", entityId = id,
            diff = mapOf(
                "from" to current.visibility, "to" to visibility,
                "sharedWith" to grants.size,
            ),
        )
        return get(householdId, id)
    }

    @Transactional
    fun addValuation(householdId: UUID, id: UUID, input: ValuationInput): InvestmentRow {
        val userId = userContext.require()
        households.get(householdId)
        get(householdId, id)
        if (input.value.signum() < 0) {
            throw ApiException.badRequest("value_negative", "A value can't be negative.")
        }
        val asOf = input.asOfDate ?: LocalDate.now()
        if (asOf.isAfter(LocalDate.now())) {
            throw ApiException.badRequest("value_future", "That date is in the future.")
        }
        repo.addValuation(id, asOf, input.value, input.quantity, input.note, userId)
        repo.markVerified(id)
        audit.record(
            householdId = householdId, actorUserId = userId, action = "investment.valuation_add",
            entityType = "investment", entityId = id,
        )
        return get(householdId, id)
    }

    @Transactional(readOnly = true)
    fun valuations(householdId: UUID, id: UUID): List<ValuationRow> {
        get(householdId, id)
        return repo.valuations(id)
    }

    /**
     * Records who is nominated, and for how much.
     *
     * A nominee is NOT an heir. In India a nominee receives an asset as a
     * custodian; who ends up owning it is decided by a will or by succession law
     * (docs/01 §10). Almira records both so the mismatch can be surfaced later,
     * which is precisely the thing families discover too late.
     */
    @Transactional
    fun replaceNominees(householdId: UUID, id: UUID, nominees: List<NomineeInput>): InvestmentRow {
        val userId = userContext.require()
        households.get(householdId)
        get(householdId, id)

        if (nominees.isEmpty()) {
            repo.replaceNominees(id, emptyList())
            audit.record(
                householdId = householdId, actorUserId = userId, action = "investment.nominees_cleared",
                entityType = "investment", entityId = id,
            )
            return get(householdId, id)
        }

        val known = households.members(householdId).associateBy { it.id }
        val resolved = nominees.map { nominee ->
            val name = when {
                nominee.memberId != null ->
                    known[nominee.memberId]?.displayName ?: throw ApiException.badRequest(
                        "nominee_unknown", "One of those people isn't part of this household.",
                    )
                !nominee.name.isNullOrBlank() -> nominee.name.trim()
                else -> throw ApiException.badRequest(
                    "nominee_required", "Give each nominee a name, or pick someone in the household.",
                )
            }
            Triple(
                nominee.memberId,
                if (nominee.memberId == null) name else null,
                (nominee.relationship to (nominee.sharePct ?: BigDecimal(100))),
            )
        }

        val total = resolved.fold(BigDecimal.ZERO) { acc, it -> acc + it.third.second }
        if (total.compareTo(BigDecimal(100)) != 0) {
            throw ApiException.badRequest(
                "nominee_shares_must_total_100",
                "Nominee shares add up to $total% — they need to total 100%.",
                mapOf("total" to total),
            )
        }

        repo.replaceNominees(id, resolved)
        audit.record(
            householdId = householdId, actorUserId = userId, action = "investment.nominees_set",
            entityType = "investment", entityId = id, diff = mapOf("count" to resolved.size),
        )
        return get(householdId, id)
    }

    /** Soft delete: the record moves to Trash and can be restored (docs/01 §11). */
    @Transactional
    fun archive(householdId: UUID, id: UUID) {
        val userId = userContext.require()
        households.get(householdId)
        get(householdId, id)
        repo.softDelete(id)
        audit.record(
            householdId = householdId, actorUserId = userId, action = "investment.delete",
            entityType = "investment", entityId = id,
        )
    }

    @Transactional(readOnly = true)
    fun trash(householdId: UUID): List<InvestmentRow> {
        households.get(householdId)
        return repo.listTrash(householdId)
    }

    @Transactional
    fun restore(householdId: UUID, id: UUID): InvestmentRow {
        val userId = userContext.require()
        households.get(householdId)
        if (repo.restore(id) == 0) throw ApiException.notFound("That isn't in the trash.")
        audit.record(
            householdId = householdId, actorUserId = userId, action = "investment.restore",
            entityType = "investment", entityId = id,
        )
        return get(householdId, id)
    }

    // --- resolution helpers ---------------------------------------------------

    /**
     * Owner defaults to "me" because that is right almost every time, and an
     * unowned record would be invisible to everyone including its author.
     */
    private fun resolveOwners(
        householdId: UUID,
        myMemberId: UUID?,
        requested: List<OwnerInput>,
    ): List<Pair<UUID, BigDecimal>> {
        if (requested.isEmpty()) {
            val me = myMemberId ?: throw ApiException.badRequest(
                "owner_required", "Choose who this belongs to.",
            )
            return listOf(me to BigDecimal(100))
        }

        val known = households.members(householdId).associateBy { it.id }
        requested.forEach {
            if (it.memberId !in known) {
                throw ApiException.badRequest(
                    "owner_unknown", "One of the owners isn't part of this household.",
                )
            }
        }
        if (requested.map { it.memberId }.toSet().size != requested.size) {
            throw ApiException.badRequest("owner_duplicate", "The same person is listed twice.")
        }

        // A single owner without an explicit share means all of it.
        val shares = if (requested.size == 1 && requested[0].sharePct == null) {
            listOf(requested[0].memberId to BigDecimal(100))
        } else {
            requested.map {
                it.memberId to (
                    it.sharePct ?: throw ApiException.badRequest(
                        "share_required", "Give each owner a share when there's more than one.",
                    )
                    )
            }
        }
        val total = shares.fold(BigDecimal.ZERO) { acc, (_, s) -> acc + s }
        if (total.compareTo(BigDecimal(100)) != 0) {
            throw ApiException.badRequest(
                "shares_must_total_100",
                "Ownership shares add up to $total% — they need to total 100%.",
                mapOf("total" to total),
            )
        }
        return shares
    }

    /** Per-user default wins over the household's (docs/05 §3.2). */
    private fun resolveVisibility(
        requested: String?,
        householdDefault: String,
        userId: UUID,
    ): String {
        if (requested != null) {
            requireVisibility(requested)
            return requested
        }
        return auth.me(userId).defaultVisibility.takeIf { it.isNotBlank() } ?: householdDefault
    }

    /**
     * Co-owners are always added to the grant list. It costs nothing — they can
     * see the record by ownership regardless — but it keeps "who can see this"
     * in the UI honest rather than quietly incomplete.
     */
    private fun resolveGrants(
        householdId: UUID,
        visibility: String,
        requested: List<UUID>,
        owners: List<Pair<UUID, BigDecimal>>,
    ): List<UUID> {
        if (visibility != "scoped") return emptyList()
        if (requested.isEmpty()) {
            throw ApiException.badRequest(
                "scope_empty", "Choose who you'd like to share this with.",
            )
        }
        val known = households.members(householdId).map { it.id }.toSet()
        requested.forEach {
            if (it !in known) {
                throw ApiException.badRequest(
                    "scope_unknown", "One of those people isn't part of this household.",
                )
            }
        }
        return (requested + owners.map { it.first }).distinct()
    }

    private fun requireVisibility(value: String) {
        if (value !in visibilities) {
            throw ApiException.badRequest(
                "visibility_invalid", "Visibility must be private, household or scoped.",
            )
        }
    }

    private fun requireStatus(value: String) {
        if (value !in statuses) {
            throw ApiException.badRequest(
                "status_invalid", "Status must be one of: ${statuses.joinToString()}.",
            )
        }
    }

    private fun CustomFieldInput.toFieldDef() = tech.bhrigu.almira.catalog.FieldDef(
        key = key, label = label, dataType = dataType, options = options, required = required,
    )
}
