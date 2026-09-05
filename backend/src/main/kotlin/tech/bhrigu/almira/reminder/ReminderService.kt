package tech.bhrigu.almira.reminder

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.investment.InvestmentRow
import tech.bhrigu.almira.liability.LiabilityRow
import tech.bhrigu.almira.security.RequestUserContext
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** What a completed maturity suggests doing next (docs/03 §10 "Renewal"). */
data class RolloverDraft(
    val investmentId: UUID,
    val typeId: UUID,
    val title: String,
    val suggestedAmount: BigDecimal?,
    val suggestedStartDate: LocalDate,
    val message: String,
)

data class CompletedReminder(val reminder: ReminderRow?, val rollover: RolloverDraft?)

@Service
class ReminderService(
    private val repo: ReminderRepository,
    private val households: HouseholdService,
    private val audit: AuditService,
    private val userContext: RequestUserContext,
) {
    private val kinds = setOf(
        "maturity", "premium_due", "renewal", "sip", "emi", "review", "verify", "custom",
    )
    private val recurrences = setOf("none", "monthly", "quarterly", "half_yearly", "yearly")

    @Transactional(readOnly = true)
    fun list(householdId: UUID, withinDays: Int?, includeDone: Boolean): List<ReminderRow> {
        households.get(householdId)
        val until = withinDays?.let { LocalDate.now().plusDays(it.coerceIn(1, 3650).toLong()) }
        return repo.list(householdId, until, includeDone)
    }

    @Transactional
    fun create(
        householdId: UUID,
        investmentId: UUID?,
        liabilityId: UUID?,
        kind: String,
        title: String,
        dueDate: LocalDate,
        leadDays: Int,
        recurrence: String,
        amount: BigDecimal?,
        note: String?,
    ): ReminderRow {
        val userId = userContext.require()
        households.get(householdId)
        if (title.isBlank()) {
            throw ApiException.badRequest("title_required", "What should we remind you about?")
        }
        if (kind !in kinds) {
            throw ApiException.badRequest("kind_invalid", "Choose one of: ${kinds.joinToString()}.")
        }
        if (recurrence !in recurrences) {
            throw ApiException.badRequest(
                "recurrence_invalid", "Choose one of: ${recurrences.joinToString()}.",
            )
        }
        repo.insertManual(
            householdId, investmentId, liabilityId, kind, title.trim(),
            dueDate, leadDays, recurrence, amount, note, userId,
        )
        audit.record(
            householdId = householdId, actorUserId = userId, action = "reminder.create",
            entityType = "reminder", entityId = null, diff = mapOf("title" to title),
        )
        return repo.list(householdId, null, true).first { it.title == title.trim() }
    }

    @Transactional
    fun snooze(householdId: UUID, id: UUID, until: LocalDate): ReminderRow {
        households.get(householdId)
        val reminder = repo.find(householdId, id) ?: throw ApiException.notFound()
        if (until.isBefore(LocalDate.now())) {
            throw ApiException.badRequest("snooze_past", "Choose a date in the future.")
        }
        repo.updateStatus(reminder.id, "snoozed", until)
        return repo.find(householdId, id)!!
    }

    /**
     * Marking a reminder done.
     *
     * A recurring one advances to its next occurrence rather than disappearing —
     * an EMI paid this month is due again next month, and making someone
     * re-create it is how a registry stops being trusted.
     *
     * A maturity that is done is the moment to offer the renewal, because it is
     * the one moment the person is holding the paperwork (docs/03 §10).
     */
    @Transactional
    fun complete(householdId: UUID, id: UUID, investment: InvestmentRow?): CompletedReminder {
        val userId = userContext.require()
        households.get(householdId)
        val reminder = repo.find(householdId, id) ?: throw ApiException.notFound()

        if (reminder.recurrence != "none") {
            val next = DueDates.advance(reminder.dueDate, reminder.recurrence)
            repo.advance(reminder.id, next)
        } else {
            repo.updateStatus(reminder.id, "done", null)
        }

        audit.record(
            householdId = householdId, actorUserId = userId, action = "reminder.complete",
            entityType = "reminder", entityId = reminder.id,
        )

        val rollover = if (reminder.kind == "maturity" && investment != null) {
            RolloverDraft(
                investmentId = investment.id,
                typeId = investment.typeId,
                title = investment.title,
                suggestedAmount = investment.effectiveValue ?: investment.investedAmount,
                suggestedStartDate = reminder.dueDate,
                message = "Renewing ${investment.title}? We'll pre-fill it with the same " +
                    "details so you only change what's different.",
            )
        } else {
            null
        }

        return CompletedReminder(repo.find(householdId, id), rollover)
    }

    @Transactional
    fun delete(householdId: UUID, id: UUID) {
        households.get(householdId)
        if (repo.softDelete(householdId, id) == 0) throw ApiException.notFound()
    }

    // -------------------------------------------------------------------------
    // Keeping reminders in step with the records that imply them.
    // -------------------------------------------------------------------------

    /**
     * A holding with a maturity date, a premium or a SIP implies a reminder.
     * Generating it automatically is the difference between a registry that
     * tells you things and one you have to remember to ask.
     */
    @Transactional
    fun syncForInvestment(row: InvestmentRow) {
        val userId = userContext.currentUserId()

        row.maturityDate?.takeIf { row.status == "active" }?.let { maturity ->
            repo.upsertAuto(
                householdId = row.householdId, investmentId = row.id, liabilityId = null,
                kind = "maturity", title = "${row.title} matures",
                dueDate = maturity, leadDays = 10, recurrence = "none",
                amount = row.effectiveValue ?: row.investedAmount, createdBy = userId,
            )
        } ?: repo.deleteAuto(row.id, null, "maturity")

        attributeDate(row, "premium_due_date")?.let { due ->
            repo.upsertAuto(
                householdId = row.householdId, investmentId = row.id, liabilityId = null,
                kind = "premium_due", title = "${row.title} premium due",
                dueDate = due, leadDays = 7,
                recurrence = when (row.attributes["premium_frequency"]) {
                    "monthly" -> "monthly"
                    "quarterly" -> "quarterly"
                    "half_yearly" -> "half_yearly"
                    "single" -> "none"
                    else -> "yearly"
                },
                amount = attributeAmount(row, "premium_amount"), createdBy = userId,
            )
        } ?: repo.deleteAuto(row.id, null, "premium_due")

        attributeDate(row, "renewal_date")?.let { due ->
            repo.upsertAuto(
                householdId = row.householdId, investmentId = row.id, liabilityId = null,
                kind = "renewal", title = "${row.title} renews",
                dueDate = due, leadDays = 14, recurrence = "yearly",
                amount = attributeAmount(row, "premium_amount"), createdBy = userId,
            )
        } ?: repo.deleteAuto(row.id, null, "renewal")

        attributeInt(row, "sip_day")?.let { day ->
            repo.upsertAuto(
                householdId = row.householdId, investmentId = row.id, liabilityId = null,
                kind = "sip", title = "${row.title} SIP",
                dueDate = DueDates.nextMonthly(day.coerceIn(1, 31), LocalDate.now()),
                leadDays = 2, recurrence = "monthly",
                amount = attributeAmount(row, "sip_amount"), createdBy = userId,
            )
        } ?: repo.deleteAuto(row.id, null, "sip")
    }

    @Transactional
    fun syncForLiability(row: LiabilityRow) {
        val userId = userContext.currentUserId()
        row.emiDay?.takeIf { row.status == "active" }?.let { day ->
            repo.upsertAuto(
                householdId = row.householdId, investmentId = null, liabilityId = row.id,
                kind = "emi", title = "${row.title} EMI",
                dueDate = DueDates.nextMonthly(day.coerceIn(1, 31), LocalDate.now()),
                leadDays = 3, recurrence = "monthly",
                amount = row.emiAmount, createdBy = userId,
            )
        } ?: repo.deleteAuto(null, row.id, "emi")
    }

    private fun attributeDate(row: InvestmentRow, key: String): LocalDate? =
        (row.attributes[key] as? String)?.takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun attributeAmount(row: InvestmentRow, key: String): BigDecimal? =
        (row.attributes[key] as? String)?.takeIf { it.isNotBlank() }
            ?.let { runCatching { BigDecimal(it) }.getOrNull() }

    private fun attributeInt(row: InvestmentRow, key: String): Int? =
        (row.attributes[key] as? String)?.toIntOrNull()
            ?: (row.attributes[key] as? Number)?.toInt()
}
