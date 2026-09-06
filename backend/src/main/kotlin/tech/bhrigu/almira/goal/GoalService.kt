package tech.bhrigu.almira.goal

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.common.IndianNumbers
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.investment.InvestmentService
import tech.bhrigu.almira.security.RequestUserContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

data class UnallocatedHolding(
    val investmentId: UUID,
    val title: String,
    val value: BigDecimal?,
    val valueFormatted: String?,
    /** How much of it is still free to point at a goal. */
    val unallocatedPct: BigDecimal,
)

/**
 * Progress towards a goal, with the honesty rules from docs/01 §7 attached.
 *
 * `onTrack` is a neutral observation, not a verdict and not advice: it compares
 * what is set aside against how much of the time has passed. It says nothing
 * about whether the plan is wise, and the product does not get to have an
 * opinion about that (docs/08 §6).
 */
data class GoalProgress(
    val percentComplete: BigDecimal,
    val shortfall: BigDecimal,
    val monthsRemaining: Long?,
    /** What would need setting aside each month to close the gap on time. */
    val monthlyToClose: BigDecimal?,
    val onTrack: Boolean?,
    val note: String?,
)

@Service
class GoalService(
    private val repo: GoalRepository,
    private val households: HouseholdService,
    private val investments: InvestmentService,
    private val audit: AuditService,
    private val userContext: RequestUserContext,
) {
    private val visibilities = setOf("private", "household", "scoped")
    private val statuses = setOf("active", "achieved", "archived")

    @Transactional
    fun create(
        householdId: UUID,
        name: String,
        targetAmount: BigDecimal,
        targetDate: LocalDate?,
        priority: Int,
        memberId: UUID?,
        icon: String?,
        notes: String?,
        visibility: String?,
    ): GoalRow {
        val userId = userContext.require()
        val household = households.get(householdId)

        if (name.isBlank()) {
            throw ApiException.badRequest("name_required", "What are you saving for?")
        }
        if (targetAmount.signum() <= 0) {
            throw ApiException.badRequest("target_required", "How much do you need?")
        }
        if (priority !in 1..3) {
            throw ApiException.badRequest("priority_invalid", "Priority runs from 1 to 3.")
        }
        memberId?.let { target ->
            if (households.members(householdId).none { it.id == target }) {
                throw ApiException.badRequest("member_unknown", "That person isn't in this household.")
            }
        }
        val resolved = (visibility ?: household.defaultVisibility).also {
            if (it !in visibilities) {
                throw ApiException.badRequest(
                    "visibility_invalid", "Visibility must be private, household or scoped.",
                )
            }
        }

        val id = UUID.randomUUID()
        repo.insert(
            id, householdId, name.trim(), targetAmount, targetDate, priority,
            memberId, icon, notes, resolved, userId,
        )
        audit.record(
            householdId = householdId, actorUserId = userId, action = "goal.create",
            entityType = "goal", entityId = id, diff = mapOf("name" to name),
        )
        return repo.find(householdId, id)
            ?: throw ApiException.forbidden("Saved, but it's private to whoever it's for.")
    }

    @Transactional(readOnly = true)
    fun list(householdId: UUID, status: String?): List<GoalRow> {
        households.get(householdId)
        return repo.list(householdId, status)
    }

    @Transactional(readOnly = true)
    fun get(householdId: UUID, id: UUID): GoalRow {
        households.get(householdId)
        return repo.find(householdId, id) ?: throw ApiException.notFound()
    }

    @Transactional
    fun update(
        householdId: UUID,
        id: UUID,
        version: Int,
        name: String?,
        targetAmount: BigDecimal?,
        targetDate: LocalDate?,
        priority: Int?,
        notes: String?,
        status: String?,
    ): GoalRow {
        val userId = userContext.require()
        households.get(householdId)
        val current = get(householdId, id)
        status?.let {
            if (it !in statuses) {
                throw ApiException.badRequest("status_invalid", "Choose one of: ${statuses.joinToString()}.")
            }
        }
        if (repo.update(id, version, name?.trim(), targetAmount, targetDate, priority, notes, status) == 0) {
            // A refusal and a race look identical from here — zero rows — and
            // telling someone "reload and try again" when the answer is "not
            // yours to change" sends them round the same loop forever.
            if (version == current.version) {
                throw ApiException.forbidden("You can read this, but it isn't yours to change.")
            }
            throw ApiException.conflict(
                "stale_write",
                "Someone else changed this while you were editing. Reload and try again.",
                mapOf("currentVersion" to current.version),
            )
        }
        audit.record(
            householdId = householdId, actorUserId = userId, action = "goal.update",
            entityType = "goal", entityId = id,
        )
        return get(householdId, id)
    }

    /**
     * Points a holding at a goal.
     *
     * The allocation is per holding across ALL goals, not per goal: one SIP can
     * be half a house deposit and half a retirement, but it cannot be 80% of
     * both. The database enforces that too, so a race cannot slip past.
     */
    @Transactional
    fun mapInvestment(
        householdId: UUID,
        goalId: UUID,
        investmentId: UUID,
        allocationPct: BigDecimal,
    ): GoalRow {
        val userId = userContext.require()
        households.get(householdId)
        get(householdId, goalId)
        investments.get(householdId, investmentId)   // visibility check on the other end

        if (allocationPct.signum() <= 0 || allocationPct > BigDecimal(100)) {
            throw ApiException.badRequest(
                "allocation_invalid", "An allocation is between 1 and 100 percent.",
            )
        }

        // Checked here so the message can say what is already spoken for and by
        // how much this overshoots. The deferred database constraint stays as
        // the backstop against a race, but a constraint violation cannot explain
        // itself to a person.
        val elsewhere = repo.allocatedElsewhere(investmentId, goalId)
        val total = elsewhere + allocationPct
        if (total > BigDecimal(100)) {
            val available = BigDecimal(100) - elsewhere
            throw ApiException.badRequest(
                "allocation_exceeds_holding",
                if (available.signum() <= 0) {
                    "That holding is already fully allocated to other goals."
                } else {
                    "That holding is already ${elsewhere.stripTrailingZeros().toPlainString()}% " +
                        "allocated to other goals — ${available.stripTrailingZeros().toPlainString()}% " +
                        "is left."
                },
                mapOf("allocatedElsewhere" to elsewhere, "available" to available),
            )
        }

        repo.map(goalId, investmentId, allocationPct)
        audit.record(
            householdId = householdId, actorUserId = userId, action = "goal.map_investment",
            entityType = "goal", entityId = goalId,
            diff = mapOf("investmentId" to investmentId.toString(), "allocationPct" to allocationPct),
        )
        return get(householdId, goalId)
    }

    @Transactional
    fun unmapInvestment(householdId: UUID, goalId: UUID, investmentId: UUID): GoalRow {
        households.get(householdId)
        get(householdId, goalId)
        repo.unmap(goalId, investmentId)
        return get(householdId, goalId)
    }

    @Transactional
    fun changeVisibility(
        householdId: UUID,
        id: UUID,
        visibility: String,
        visibleToMemberIds: List<UUID>,
    ): GoalRow {
        val userId = userContext.require()
        households.get(householdId)
        val current = get(householdId, id)
        if (visibility !in visibilities) {
            throw ApiException.badRequest(
                "visibility_invalid", "Visibility must be private, household or scoped.",
            )
        }
        val grants = if (visibility == "scoped") {
            if (visibleToMemberIds.isEmpty()) {
                throw ApiException.badRequest("scope_empty", "Choose who you'd like to share this with.")
            }
            val known = households.members(householdId).map { it.id }.toSet()
            visibleToMemberIds.forEach {
                if (it !in known) {
                    throw ApiException.badRequest(
                        "scope_unknown", "One of those people isn't part of this household.",
                    )
                }
            }
            visibleToMemberIds
        } else {
            emptyList()
        }

        repo.updateVisibility(id, visibility)
        repo.replaceVisibilityGrants(householdId, id, grants, userId)
        audit.record(
            householdId = householdId, actorUserId = userId, action = "goal.visibility_change",
            entityType = "goal", entityId = id,
            diff = mapOf("from" to current.visibility, "to" to visibility),
        )
        return get(householdId, id)
    }

    @Transactional
    fun archive(householdId: UUID, id: UUID) {
        val userId = userContext.require()
        households.get(householdId)
        get(householdId, id)
        repo.softDelete(householdId, id)
        audit.record(
            householdId = householdId, actorUserId = userId, action = "goal.delete",
            entityType = "goal", entityId = id,
        )
    }

    @Transactional(readOnly = true)
    fun unallocated(householdId: UUID): List<UnallocatedHolding> {
        households.get(householdId)
        return repo.unallocated(householdId).map {
            UnallocatedHolding(
                investmentId = it.investmentId, title = it.title, value = it.value,
                valueFormatted = it.value?.let(IndianNumbers::rupees),
                unallocatedPct = it.unallocatedPct,
            )
        }
    }

    /**
     * Progress, stated as an observation rather than a judgement.
     *
     * There is no "you are behind, save more" here. The figures are what the
     * records say; what to do about them is the household's business, and the
     * moment this product starts advising it invites a different kind of
     * regulation entirely (docs/08 §5-6).
     */
    fun progress(goal: GoalRow, today: LocalDate = LocalDate.now()): GoalProgress {
        val percent = if (goal.targetAmount.signum() > 0) {
            goal.funded.multiply(BigDecimal(100))
                .divide(goal.targetAmount, 1, RoundingMode.HALF_UP)
                .min(BigDecimal(100))
        } else {
            BigDecimal.ZERO
        }
        val shortfall = (goal.targetAmount - goal.funded).max(BigDecimal.ZERO)

        val months = goal.targetDate?.let { ChronoUnit.MONTHS.between(today, it) }
        val monthlyToClose = months?.takeIf { it > 0 && shortfall.signum() > 0 }
            ?.let { shortfall.divide(BigDecimal(it), 0, RoundingMode.UP) }

        // Compares the share of the money set aside against the share of the
        // time gone. Nothing cleverer: no return assumption, no projection —
        // just "you are a third of the way there, and a third of the way
        // through". Null when there is no deadline, because without one there
        // is nothing to be on track FOR.
        val onTrack = goal.targetDate?.let { deadline ->
            val totalDays = ChronoUnit.DAYS.between(goal.startedOn, deadline).toDouble()
            if (totalDays <= 0) return@let shortfall.signum() == 0
            val elapsed = ChronoUnit.DAYS.between(goal.startedOn, today)
                .coerceAtLeast(0).toDouble() / totalDays
            val fundedShare = if (goal.targetAmount.signum() > 0) {
                goal.funded.toDouble() / goal.targetAmount.toDouble()
            } else {
                0.0
            }
            fundedShare >= elapsed
        }

        val note = when {
            goal.holdingCount == 0 ->
                "Nothing is pointed at this goal yet — map a holding to see progress."
            shortfall.signum() == 0 -> "Fully funded."
            months != null && months <= 0 && shortfall.signum() > 0 ->
                "The target date has passed and there's still a shortfall."
            monthlyToClose != null ->
                "About ₹${tech.bhrigu.almira.common.IndianNumbers.group(monthlyToClose)} a month " +
                    "would close the gap by then."
            else -> null
        }

        return GoalProgress(
            percentComplete = percent,
            shortfall = shortfall,
            monthsRemaining = months?.takeIf { it > 0 },
            monthlyToClose = monthlyToClose,
            onTrack = onTrack,
            note = note,
        )
    }
}
