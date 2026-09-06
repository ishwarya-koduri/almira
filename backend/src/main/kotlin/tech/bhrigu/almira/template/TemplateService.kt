package tech.bhrigu.almira.template

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.catalog.CatalogService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.investment.CreateInvestment
import tech.bhrigu.almira.investment.CreatedInvestment
import tech.bhrigu.almira.investment.InvestmentService
import tech.bhrigu.almira.investment.OwnerInput
import tech.bhrigu.almira.investment.ValuationInput
import tech.bhrigu.almira.security.RequestUserContext
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class CreateTemplate(
    val id: UUID? = null,
    val name: String,
    /** Either describe the shape directly… */
    val typeId: UUID? = null,
    /** …or save an existing record as one. */
    val fromInvestmentId: UUID? = null,
    val institutionId: UUID? = null,
    val accountId: UUID? = null,
    val title: String? = null,
    val investedAmount: BigDecimal? = null,
    val quantity: BigDecimal? = null,
    val unit: String? = null,
    val storageLocation: String? = null,
    val attributes: Map<String, Any?>? = null,
    val notes: String? = null,
    val visibility: String = "private",
)

data class UpdateTemplate(
    val version: Int,
    val name: String? = null,
    val title: String? = null,
    val investedAmount: BigDecimal? = null,
    val quantity: BigDecimal? = null,
    val unit: String? = null,
    val storageLocation: String? = null,
    val institutionId: UUID? = null,
    val accountId: UUID? = null,
    val attributes: Map<String, Any?>? = null,
    val notes: String? = null,
    val visibility: String? = null,
)

/** The parts of a capture the template does not decide. */
data class ApplyTemplate(
    val id: UUID? = null,
    val title: String? = null,
    val investedAmount: BigDecimal? = null,
    val quantity: BigDecimal? = null,
    val startDate: LocalDate? = null,
    val maturityDate: LocalDate? = null,
    val attributes: Map<String, Any?>? = null,
    val owners: List<OwnerInput> = emptyList(),
    val visibility: String? = null,
    val visibleToMemberIds: List<UUID> = emptyList(),
    val notes: String? = null,
    val initialValuation: ValuationInput? = null,
)

/**
 * A template is a saved shape of a capture form — the fourth FD of the year
 * typed once rather than four times (docs/07 §2 Phase 2).
 *
 * It holds no money and belongs to no member, but it is not innocent: saving
 * "LIC term plan" as a template puts the policy's own details into it. So a
 * template is private to whoever made it until they deliberately share it, and
 * one saved from a record can never be shared more widely than that record —
 * otherwise "save as template" would be a quiet way to republish a private
 * holding to the household.
 */
@Service
class TemplateService(
    private val repo: TemplateRepository,
    private val households: HouseholdService,
    private val catalog: CatalogService,
    private val investments: InvestmentService,
    private val audit: AuditService,
    private val userContext: RequestUserContext,
) {

    @Transactional
    fun create(householdId: UUID, input: CreateTemplate): TemplateRow {
        val userId = userContext.require()
        val household = households.get(householdId)
        if (input.name.isBlank()) {
            throw ApiException.badRequest("name_required", "Give the template a name you'll recognise.")
        }
        requireVisibility(input.visibility)

        val source = input.fromInvestmentId?.let { investments.get(householdId, it) }
        val typeId = input.typeId ?: source?.typeId ?: throw ApiException.badRequest(
            "type_required", "Choose a type, or save an existing record as a template.",
        )
        catalog.type(householdId, typeId)

        if (source != null && input.visibility == "household" && source.visibility != "household") {
            throw ApiException.badRequest(
                "template_would_widen",
                "This record isn't shared with the household, so a template made from it " +
                    "can't be either. Keep the template private, or share the record first.",
            )
        }

        val id = input.id ?: UUID.randomUUID()
        repo.find(householdId, id)?.let { return it }

        try {
            repo.insert(
                NewTemplate(
                    id = id, householdId = householdId, name = input.name.trim(), typeId = typeId,
                    institutionId = input.institutionId ?: source?.institutionId,
                    accountId = input.accountId ?: source?.accountId,
                    title = input.title ?: source?.title,
                    investedAmount = input.investedAmount ?: source?.investedAmount,
                    currency = source?.currency ?: household.baseCurrency,
                    quantity = input.quantity ?: source?.quantity,
                    unit = input.unit ?: source?.unit,
                    storageLocation = input.storageLocation ?: source?.storageLocation,
                    attributes = input.attributes ?: source?.attributes ?: emptyMap(),
                    notes = input.notes ?: source?.notes,
                    visibility = input.visibility,
                    sourceVisibility = source?.visibility,
                    createdBy = userId,
                ),
            )
        } catch (_: DuplicateKeyException) {
            throw ApiException.conflict(
                "name_taken", "You already have a template called “${input.name.trim()}”.",
            )
        }

        audit.record(
            householdId = householdId, actorUserId = userId, action = "template.create",
            entityType = "template", entityId = id, diff = mapOf("name" to input.name),
        )
        return get(householdId, id)
    }

    @Transactional(readOnly = true)
    fun list(householdId: UUID): List<TemplateRow> {
        households.get(householdId)
        return repo.list(householdId)
    }

    @Transactional(readOnly = true)
    fun get(householdId: UUID, id: UUID): TemplateRow {
        households.get(householdId)
        return repo.find(householdId, id) ?: throw ApiException.notFound()
    }

    @Transactional
    fun update(householdId: UUID, id: UUID, input: UpdateTemplate): TemplateRow {
        val current = get(householdId, id)
        input.visibility?.let(::requireVisibility)

        val updated = try {
            repo.update(
                id = id, version = input.version, name = input.name?.trim(), title = input.title,
                investedAmount = input.investedAmount, quantity = input.quantity, unit = input.unit,
                storageLocation = input.storageLocation, institutionId = input.institutionId,
                accountId = input.accountId, attributes = input.attributes, notes = input.notes,
                visibility = input.visibility,
            )
        } catch (e: DataIntegrityViolationException) {
            // The database is the backstop for the same rule the service states
            // at creation: a template cannot outgrow the record it came from.
            if (e.message?.contains("template_cannot_outgrow_its_source") == true) {
                throw ApiException.badRequest(
                    "template_would_widen",
                    "This template was made from a record that isn't shared with the " +
                        "household, so it can't be shared either.",
                )
            }
            throw e
        }
        if (updated == 0) {
            // Someone else's template is readable when shared, but not writable —
            // RLS refuses the row, which arrives here as "nothing was updated".
            if (!current.mine) throw ApiException.forbidden("This template belongs to someone else.")
            throw ApiException.conflict(
                "stale_write", "Someone else changed this while you were editing it.",
                mapOf("currentVersion" to current.version),
            )
        }
        return get(householdId, id)
    }

    @Transactional
    fun delete(householdId: UUID, id: UUID) {
        val current = get(householdId, id)
        if (repo.softDelete(householdId, id) == 0) {
            if (!current.mine) throw ApiException.forbidden("This template belongs to someone else.")
            throw ApiException.notFound()
        }
    }

    /**
     * Fill the form and save it. The template supplies the shape; the caller
     * supplies what is actually different this time — usually the amount and
     * the date, which is the whole saving.
     */
    @Transactional
    fun apply(householdId: UUID, id: UUID, input: ApplyTemplate): CreatedInvestment {
        val template = get(householdId, id)
        val created = investments.create(
            householdId,
            CreateInvestment(
                id = input.id,
                typeId = template.typeId,
                title = input.title?.trim()?.takeIf { it.isNotBlank() }
                    ?: template.title
                    ?: template.name,
                investedAmount = input.investedAmount ?: template.investedAmount,
                currency = template.currency,
                quantity = input.quantity ?: template.quantity,
                unit = template.unit,
                startDate = input.startDate,
                maturityDate = input.maturityDate,
                storageLocation = template.storageLocation,
                institutionId = template.institutionId,
                accountId = template.accountId,
                attributes = template.attributes + (input.attributes ?: emptyMap()),
                owners = input.owners,
                visibility = input.visibility,
                visibleToMemberIds = input.visibleToMemberIds,
                notes = input.notes ?: template.notes,
                initialValuation = input.initialValuation,
            ),
        )
        repo.recordUse(id)
        return created
    }

    private fun requireVisibility(value: String) {
        if (value !in setOf("private", "household")) {
            throw ApiException.badRequest(
                "visibility_invalid",
                "A template is either private to you or shared with the household.",
            )
        }
    }
}
