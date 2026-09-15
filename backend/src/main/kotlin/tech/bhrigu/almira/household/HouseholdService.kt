package tech.bhrigu.almira.household

import tech.bhrigu.almira.measurement.ProductEvent
import tech.bhrigu.almira.measurement.ProductMeasurement
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.reminder.Notifier
import tech.bhrigu.almira.reminder.OutboundNotification
import tech.bhrigu.almira.security.RequestUserContext
import java.time.LocalDate
import java.util.UUID

@Service
class HouseholdService(
    private val repo: HouseholdRepository,
    private val audit: AuditService,
    private val userContext: RequestUserContext,
    private val notifiers: List<Notifier>,
    private val measurement: ProductMeasurement,
) {
    private val visibilities = setOf("private", "household")

    /**
     * "Just me" and "Me + my family" differ only in what happens next, not in
     * the shape of the data: both create a household with one member. Someone
     * who starts alone and later adds a spouse should never have to migrate.
     */
    @Transactional
    fun create(name: String?, defaultVisibility: String?, displayName: String?): HouseholdRow {
        val userId = userContext.require()
        val visibility = (defaultVisibility ?: "private").also(::requireVisibility)
        val (householdId, _) = repo.bootstrap(
            name = name?.trim()?.ifEmpty { null } ?: "My household",
            defaultVisibility = visibility,
            displayName = displayName?.trim()?.ifEmpty { null } ?: "Me",
        )
        measurement.record(ProductEvent.HOUSEHOLD_CREATED)
        return repo.find(householdId, userId)
            ?: throw IllegalStateException("household vanished immediately after creation")
    }

    @Transactional(readOnly = true)
    fun listMine(): List<HouseholdRow> = repo.listMine(userContext.require())

    /**
     * Not found rather than forbidden when the caller is not a member. A 403
     * would confirm the household exists, which is a leak in itself.
     */
    @Transactional(readOnly = true)
    fun get(householdId: UUID): HouseholdRow =
        repo.find(householdId, userContext.require())
            ?: throw ApiException.notFound("We couldn't find that household.")

    @Transactional
    fun update(
        householdId: UUID,
        name: String?,
        defaultVisibility: String?,
        version: Int?,
    ): HouseholdRow {
        val userId = userContext.require()
        val current = get(householdId)
        requireAdmin(current)
        defaultVisibility?.let(::requireVisibility)

        val updated = repo.update(householdId, name, defaultVisibility, version ?: current.version)
        if (updated == 0) throw staleWrite(current.version)

        audit.record(
            householdId = householdId, actorUserId = userId, action = "household.update",
            entityType = "household", entityId = householdId,
            diff = mapOf("name" to name, "defaultVisibility" to defaultVisibility),
        )
        return get(householdId)
    }

    // --- members -------------------------------------------------------------

    @Transactional(readOnly = true)
    fun members(householdId: UUID): List<MemberRow> {
        val userId = userContext.require()
        get(householdId) // membership check
        return repo.members(householdId, userId).map { it.copy(isMe = it.userId == userId) }
    }

    @Transactional
    fun addMember(
        householdId: UUID,
        displayName: String,
        relationship: String?,
        dateOfBirth: LocalDate?,
        notes: String?,
        diedOn: LocalDate? = null,
    ): MemberRow {
        val userId = userContext.require()
        requireAdmin(get(householdId))

        if (displayName.isBlank()) {
            throw ApiException.badRequest("name_required", "Give this person a name.")
        }
        if (dateOfBirth != null && dateOfBirth.isAfter(LocalDate.now())) {
            throw ApiException.badRequest("dob_future", "That date of birth is in the future.")
        }
        checkDiedOn(diedOn, dateOfBirth)

        val memberId = repo.addMember(householdId, displayName.trim(), relationship, dateOfBirth, notes, diedOn)
        audit.record(
            householdId = householdId, actorUserId = userId, action = "member.create",
            entityType = "member", entityId = memberId,
        )
        return repo.member(householdId, memberId, userId)!!.copy(isMe = false)
    }

    @Transactional
    fun updateMember(
        householdId: UUID,
        memberId: UUID,
        displayName: String?,
        relationship: String?,
        dateOfBirth: LocalDate?,
        version: Int?,
        diedOn: LocalDate? = null,
    ): MemberRow {
        val userId = userContext.require()
        val household = get(householdId)
        val member = repo.member(householdId, memberId, userId)
            ?: throw ApiException.notFound("We couldn't find that person.")

        // You may always edit your own entry; editing anyone else needs admin.
        if (member.userId != userId) requireAdmin(household)

        checkDiedOn(diedOn, dateOfBirth ?: member.dateOfBirth)
        val updated = repo.updateMember(
            memberId, displayName, relationship, dateOfBirth, version ?: member.version, diedOn,
        )
        if (updated == 0) throw staleWrite(member.version)

        audit.record(
            householdId = householdId, actorUserId = userId, action = "member.update",
            entityType = "member", entityId = memberId,
        )
        return repo.member(householdId, memberId, userId)!!.copy(isMe = member.userId == userId)
    }

    /**
     * Removing a member is refused while records still name them. Deleting the
     * person would orphan the holding and silently change everyone's totals;
     * the honest answer is to say what is in the way (docs/07 §1).
     *
     * Only for someone without a login. A person with their own login leaves
     * through a departure — seven days, and what is theirs goes with them — so an
     * admin asks them to go rather than deleting them (docs/05 §12).
     *
     * The dead end this used to be: the count ran under the admin's own
     * row-level security, so a holding private to whoever recorded it was
     * invisible to the admin, counted as nothing, and the member was removed
     * from under it. Now the admin is told how many they cannot see, and the
     * people who recorded those are told that something of theirs is in the way
     * — without the admin learning what, or the recorder learning anything the
     * roster does not already say.
     *
     * Not rolled back by the refusal: the refusal is the answer, and the notes to
     * the recorders and the audit line are what makes it more than a wall.
     */
    @Transactional(noRollbackFor = [ApiException::class])
    fun removeMember(householdId: UUID, memberId: UUID) {
        val userId = userContext.require()
        val household = get(householdId)
        requireAdmin(household)
        val member = repo.member(householdId, memberId, userId)
            ?: throw ApiException.notFound("We couldn't find that person.")

        if (member.userId == userId) {
            throw ApiException.badRequest(
                "cannot_remove_self",
                "To leave this household, choose Leave household. What is yours goes with you.",
            )
        }
        if (member.userId != null) {
            throw ApiException.conflict(
                "member_has_login",
                "${member.displayName} has their own login. Ask them to leave instead — what is " +
                    "theirs goes with them, and nothing changes for seven days.",
            )
        }
        val (visible, hidden) = repo.managedMemberHoldings(memberId)
        val total = visible + hidden
        if (total > 0) {
            if (hidden > 0) tellRecorders(householdId, member)
            throw ApiException.conflict(
                "member_has_holdings",
                "${member.displayName} still has $total " + (if (total == 1) "record" else "records") +
                    " in their name. " +
                    when {
                        hidden == 0 -> "Reassign or remove those first."
                        visible == 0 -> "They're private to the people who added them. We've asked " +
                            "those people to move them; we haven't told you what they are."
                        else -> "You can reassign $visible. The other $hidden " +
                            (if (hidden == 1) "is" else "are") + " private to the people who added " +
                            "them, and we've asked those people to move them."
                    },
                mapOf("holdings" to total, "visible" to visible, "hidden" to hidden),
            )
        }
        repo.softDeleteMember(memberId)
        audit.record(
            householdId = householdId, actorUserId = userId, action = "member.delete",
            entityType = "member", entityId = memberId,
        )
    }

    /**
     * One neutral line to each person who recorded something the admin cannot
     * see. Keyed by the member and the day, so an admin trying three times in an
     * afternoon asks once.
     */
    private fun tellRecorders(householdId: UUID, member: MemberRow) {
        val day = LocalDate.now()
        repo.recordersOfHiddenHoldings(member.id).forEach { recorder ->
            notifiers.forEach { notifier ->
                runCatching {
                    notifier.deliver(
                        OutboundNotification(
                            userId = recorder, householdId = householdId, reminderId = null,
                            template = "household.member_removal_blocked",
                            title = "Something you added is still in ${member.displayName}'s name",
                            body = "An admin would like to remove ${member.displayName} from the household. " +
                                "Open Almira and move or remove what you added for them.",
                            idempotencyKey = "member-removal-blocked:${member.id}:$recorder:$day",
                        ),
                    )
                }
            }
        }
        audit.record(
            householdId = householdId, actorUserId = userContext.currentUserId(),
            action = "member.delete_blocked", entityType = "member", entityId = member.id,
        )
    }

    // --- guards ---------------------------------------------------------------

    /**
     * Refuses a caller who may not write to this household, with the answer
     * the database policy gives when it refuses a write (the same 403 body).
     *
     * For a service to call BEFORE anything with an effect the policy cannot
     * undo — provisioning or caching a data key, writing to storage, calling a
     * provider. The policy still refuses at the insert; this makes sure nothing
     * happened first (docs/known-issues.md, "A guard runs before the action it
     * guards").
     */
    @Transactional(readOnly = true)
    fun requireWriter(householdId: UUID) {
        if (!repo.canWrite(householdId)) throw ApiException.forbidden()
    }

    /** As [requireWriter], for what only an owner or admin may do. */
    @Transactional(readOnly = true)
    fun requireAdministrator(householdId: UUID) {
        if (!repo.canAdminister(householdId)) {
            repo.find(householdId, userContext.require())
                ?.takeIf { it.dormant && it.myRole in setOf("owner", "admin") }
                ?.let { throw dormant(it) }
            throw ApiException.forbidden()
        }
    }

    /**
     * For a service that checks an owner or admin role itself: a dormant
     * household has nobody who may act as one (V120), and says so in words
     * rather than as a policy's bare refusal.
     */
    fun refuseWhileDormant(household: HouseholdRow) {
        if (household.dormant) throw dormant(household)
    }

    /**
     * Capability check only. It gates who may MANAGE the household; it never
     * widens what anyone may SEE. Reads are decided entirely by RLS.
     */
    private fun requireAdmin(household: HouseholdRow) {
        if (household.myRole !in setOf("owner", "admin")) {
            throw ApiException.forbidden("Only the household owner or an admin can do that.")
        }
        refuseWhileDormant(household)
    }

    private fun requireVisibility(value: String) {
        if (value !in visibilities) {
            throw ApiException.badRequest(
                "visibility_invalid",
                "Choose either private or household.",
            )
        }
    }

    /** A date of death is optional; when given it is neither in the future nor before the birth. */
    private fun checkDiedOn(diedOn: LocalDate?, dateOfBirth: LocalDate?) {
        if (diedOn == null) return
        if (diedOn.isAfter(LocalDate.now())) {
            throw ApiException.badRequest("died_on_future", "That date is in the future.")
        }
        if (dateOfBirth != null && diedOn.isBefore(dateOfBirth)) {
            throw ApiException.badRequest("died_on_before_birth", "That date is before their date of birth.")
        }
    }

    companion object {
        const val DORMANT_CODE = "household_dormant"

        fun dormant(household: HouseholdRow) = ApiException(
            org.springframework.http.HttpStatus.FORBIDDEN, DORMANT_CODE,
            "Nobody runs ${household.name} at the moment, so this has to wait until someone takes it on. " +
                "An adult in the household can, on the Family screen. You can still see everything, open " +
                "the handbook and download everything.",
        )
    }

    private fun staleWrite(currentVersion: Int) = ApiException.conflict(
        "stale_write",
        "Someone else changed this while you were editing. Reload and try again.",
        mapOf("currentVersion" to currentVersion),
    )
}
