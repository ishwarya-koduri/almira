package tech.bhrigu.almira.liability

import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.security.RequestUserContext
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class ResponsibilityInput(val memberId: UUID, val responsibilityPct: BigDecimal? = null)

data class CreateLiability(
    val id: UUID? = null,
    val kind: String,
    val title: String,
    val lenderId: UUID? = null,
    val principal: BigDecimal? = null,
    val outstanding: BigDecimal,
    val interestRate: BigDecimal? = null,
    val emiAmount: BigDecimal? = null,
    val emiDay: Int? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val attributes: Map<String, Any?> = emptyMap(),
    val notes: String? = null,
    val holders: List<ResponsibilityInput> = emptyList(),
    val visibility: String? = null,
    val visibleToMemberIds: List<UUID> = emptyList(),
    /** The asset this loan is secured against, if any. */
    val securedByInvestmentId: UUID? = null,
)

data class UpdateLiability(
    val version: Int,
    val title: String? = null,
    val lenderId: UUID? = null,
    val principal: BigDecimal? = null,
    val interestRate: BigDecimal? = null,
    val emiAmount: BigDecimal? = null,
    val emiDay: Int? = null,
    val endDate: LocalDate? = null,
    val attributes: Map<String, Any?>? = null,
    val notes: String? = null,
    val status: String? = null,
    val holders: List<ResponsibilityInput>? = null,
)

data class BalanceInput(
    val outstanding: BigDecimal,
    val asOfDate: LocalDate? = null,
    val note: String? = null,
)

@Service
class LiabilityService(
    private val repo: LiabilityRepository,
    private val households: HouseholdService,
    private val audit: AuditService,
    private val userContext: RequestUserContext,
) {
    private val kinds = setOf(
        "home", "car", "personal", "education", "gold", "credit_card",
        "lap", "las", "loan_against_insurance", "family", "other",
    )
    private val visibilities = setOf("private", "household", "scoped")

    @Transactional
    fun create(householdId: UUID, input: CreateLiability): LiabilityRow {
        val userId = userContext.require()
        val household = households.get(householdId)

        if (input.title.isBlank()) {
            throw ApiException.badRequest("title_required", "Give this a name you'll recognise.")
        }
        if (input.kind !in kinds) {
            throw ApiException.badRequest("kind_invalid", "Choose one of: ${kinds.joinToString()}.")
        }
        if (input.outstanding.signum() < 0) {
            throw ApiException.badRequest("outstanding_negative", "An amount owed can't be negative.")
        }
        if (input.emiDay != null && input.emiDay !in 1..31) {
            throw ApiException.badRequest("emi_day_invalid", "Pick a day between 1 and 31.")
        }

        val id = input.id ?: UUID.randomUUID()
        repo.find(householdId, id)?.let { return it }   // idempotent retry

        val holders = resolveHolders(householdId, household.myMemberId, input.holders)
        val visibility = resolveVisibility(input.visibility, household.defaultVisibility)
        val grants = resolveGrants(householdId, visibility, input.visibleToMemberIds, holders)

        try {
            repo.insert(
                id = id, householdId = householdId, kind = input.kind, title = input.title.trim(),
                lenderId = input.lenderId, principal = input.principal,
                outstanding = input.outstanding, interestRate = input.interestRate,
                emiAmount = input.emiAmount, emiDay = input.emiDay,
                startDate = input.startDate, endDate = input.endDate,
                attributes = input.attributes, notes = input.notes,
                visibility = visibility, createdBy = userId,
            )
        } catch (_: DuplicateKeyException) {
            throw ApiException.conflict("already_exists", "This one is already saved.")
        }

        repo.replaceHolders(id, holders)
        if (grants.isNotEmpty()) repo.replaceVisibilityGrants(householdId, id, grants, userId)
        repo.recordBalance(id, LocalDate.now(), input.outstanding, "Opening balance", userId)
        input.securedByInvestmentId?.let { linkAsset(householdId, id, it, null) }

        audit.record(
            householdId = householdId, actorUserId = userId, action = "liability.create",
            entityType = "liability", entityId = id,
            diff = mapOf("title" to input.title, "kind" to input.kind, "visibility" to visibility),
        )
        return repo.find(householdId, id)
            ?: throw ApiException.forbidden("Saved, but it's private to whoever owes it.")
    }

    @Transactional(readOnly = true)
    fun list(householdId: UUID, status: String?): List<LiabilityRow> {
        households.get(householdId)
        return repo.list(householdId, status)
    }

    @Transactional(readOnly = true)
    fun get(householdId: UUID, id: UUID): LiabilityRow {
        households.get(householdId)
        return repo.find(householdId, id) ?: throw ApiException.notFound()
    }

    @Transactional
    fun update(householdId: UUID, id: UUID, input: UpdateLiability): LiabilityRow {
        val userId = userContext.require()
        val household = households.get(householdId)
        val current = get(householdId, id)
        input.status?.let {
            if (it !in setOf("active", "closed")) {
                throw ApiException.badRequest("status_invalid", "A loan is either active or closed.")
            }
        }

        val updated = repo.update(
            id = id, version = input.version, title = input.title?.trim(),
            lenderId = input.lenderId, principal = input.principal,
            interestRate = input.interestRate, emiAmount = input.emiAmount,
            emiDay = input.emiDay, endDate = input.endDate,
            attributes = input.attributes, notes = input.notes, status = input.status,
        )
        if (updated == 0) {
            throw ApiException.conflict(
                "stale_write",
                "Someone else changed this while you were editing. Reload and try again.",
                mapOf("currentVersion" to current.version),
            )
        }
        input.holders?.let {
            repo.replaceHolders(id, resolveHolders(householdId, household.myMemberId, it))
        }
        audit.record(
            householdId = householdId, actorUserId = userId, action = "liability.update",
            entityType = "liability", entityId = id,
        )
        return get(householdId, id)
    }

    /**
     * Recording a payment is recording a balance. A snapshot keeps the history
     * the net-worth trend is drawn from, so the line reflects what was actually
     * recorded rather than being interpolated from an EMI schedule.
     */
    @Transactional
    fun recordBalance(householdId: UUID, id: UUID, input: BalanceInput): LiabilityRow {
        val userId = userContext.require()
        households.get(householdId)
        get(householdId, id)

        if (input.outstanding.signum() < 0) {
            throw ApiException.badRequest("outstanding_negative", "An amount owed can't be negative.")
        }
        val asOf = input.asOfDate ?: LocalDate.now()
        if (asOf.isAfter(LocalDate.now())) {
            throw ApiException.badRequest("balance_future", "That date is in the future.")
        }

        repo.recordBalance(id, asOf, input.outstanding, input.note, userId)
        audit.record(
            householdId = householdId, actorUserId = userId, action = "liability.balance_recorded",
            entityType = "liability", entityId = id,
        )
        return get(householdId, id)
    }

    @Transactional(readOnly = true)
    fun balances(householdId: UUID, id: UUID): List<BalanceRow> {
        get(householdId, id)
        return repo.balances(id)
    }

    @Transactional
    fun changeVisibility(
        householdId: UUID,
        id: UUID,
        visibility: String,
        visibleToMemberIds: List<UUID>,
    ): LiabilityRow {
        val userId = userContext.require()
        households.get(householdId)
        val current = get(householdId, id)
        if (visibility !in visibilities) {
            throw ApiException.badRequest(
                "visibility_invalid", "Visibility must be private, household or scoped.",
            )
        }
        val grants = if (visibility == "scoped") {
            resolveGrants(
                householdId, visibility, visibleToMemberIds,
                current.holders.map { it.memberId to it.responsibilityPct },
            )
        } else {
            emptyList()
        }
        repo.updateVisibility(id, visibility)
        repo.replaceVisibilityGrants(householdId, id, grants, userId)
        audit.record(
            householdId = householdId, actorUserId = userId, action = "liability.visibility_change",
            entityType = "liability", entityId = id,
            diff = mapOf("from" to current.visibility, "to" to visibility),
        )
        return get(householdId, id)
    }

    /**
     * Links a loan to what secures it. Both ends must be visible to the caller:
     * otherwise linking would be a way to learn that a private asset exists.
     */
    @Transactional
    fun linkAsset(householdId: UUID, id: UUID, investmentId: UUID, note: String?): LiabilityRow {
        val userId = userContext.require()
        households.get(householdId)
        get(householdId, id)
        if (repo.linkAsset(id, investmentId, note) == 0) {
            throw ApiException.notFound("We couldn't find that holding.")
        }
        audit.record(
            householdId = householdId, actorUserId = userId, action = "liability.link_asset",
            entityType = "liability", entityId = id,
            diff = mapOf("investmentId" to investmentId.toString()),
        )
        return get(householdId, id)
    }

    @Transactional
    fun unlinkAsset(householdId: UUID, id: UUID, investmentId: UUID): LiabilityRow {
        val userId = userContext.require()
        households.get(householdId)
        get(householdId, id)
        repo.unlinkAsset(id, investmentId)
        audit.record(
            householdId = householdId, actorUserId = userId, action = "liability.unlink_asset",
            entityType = "liability", entityId = id,
        )
        return get(householdId, id)
    }

    @Transactional
    fun archive(householdId: UUID, id: UUID) {
        val userId = userContext.require()
        households.get(householdId)
        get(householdId, id)
        repo.softDelete(id)
        audit.record(
            householdId = householdId, actorUserId = userId, action = "liability.delete",
            entityType = "liability", entityId = id,
        )
    }

    // --- helpers --------------------------------------------------------------

    private fun resolveHolders(
        householdId: UUID,
        myMemberId: UUID?,
        requested: List<ResponsibilityInput>,
    ): List<Pair<UUID, BigDecimal>> {
        if (requested.isEmpty()) {
            val me = myMemberId
                ?: throw ApiException.badRequest("holder_required", "Choose who owes this.")
            return listOf(me to BigDecimal(100))
        }
        val known = households.members(householdId).map { it.id }.toSet()
        requested.forEach {
            if (it.memberId !in known) {
                throw ApiException.badRequest(
                    "holder_unknown", "One of the borrowers isn't part of this household.",
                )
            }
        }
        if (requested.map { it.memberId }.toSet().size != requested.size) {
            throw ApiException.badRequest("holder_duplicate", "The same person is listed twice.")
        }

        val shares = if (requested.size == 1 && requested[0].responsibilityPct == null) {
            listOf(requested[0].memberId to BigDecimal(100))
        } else {
            requested.map {
                it.memberId to (
                    it.responsibilityPct ?: throw ApiException.badRequest(
                        "responsibility_required",
                        "Say who's responsible for how much when more than one person owes it.",
                    )
                    )
            }
        }
        val total = shares.fold(BigDecimal.ZERO) { acc, (_, s) -> acc + s }
        if (total.compareTo(BigDecimal(100)) != 0) {
            throw ApiException.badRequest(
                "responsibility_must_total_100",
                "Responsibility adds up to $total% — it needs to total 100%.",
                mapOf("total" to total),
            )
        }
        return shares
    }

    private fun resolveVisibility(requested: String?, householdDefault: String): String {
        if (requested != null) {
            if (requested !in visibilities) {
                throw ApiException.badRequest(
                    "visibility_invalid", "Visibility must be private, household or scoped.",
                )
            }
            return requested
        }
        return householdDefault
    }

    private fun resolveGrants(
        householdId: UUID,
        visibility: String,
        requested: List<UUID>,
        holders: List<Pair<UUID, BigDecimal>>,
    ): List<UUID> {
        if (visibility != "scoped") return emptyList()
        if (requested.isEmpty()) {
            throw ApiException.badRequest("scope_empty", "Choose who you'd like to share this with.")
        }
        val known = households.members(householdId).map { it.id }.toSet()
        requested.forEach {
            if (it !in known) {
                throw ApiException.badRequest(
                    "scope_unknown", "One of those people isn't part of this household.",
                )
            }
        }
        return (requested + holders.map { it.first }).distinct()
    }
}
