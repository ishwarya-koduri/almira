package tech.bhrigu.almira.household

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.security.RequestUserContext
import java.time.LocalDate
import java.util.UUID

@Service
class HouseholdService(
    private val repo: HouseholdRepository,
    private val audit: AuditService,
    private val userContext: RequestUserContext,
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
        return repo.find(householdId, userId)
            ?: throw IllegalStateException("household vanished immediately after creation")
    }

    fun listMine(): List<HouseholdRow> = repo.listMine(userContext.require())

    /**
     * Not found rather than forbidden when the caller is not a member. A 403
     * would confirm the household exists, which is a leak in itself.
     */
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
    ): MemberRow {
        val userId = userContext.require()
        requireAdmin(get(householdId))

        if (displayName.isBlank()) {
            throw ApiException.badRequest("name_required", "Give this person a name.")
        }
        if (dateOfBirth != null && dateOfBirth.isAfter(LocalDate.now())) {
            throw ApiException.badRequest("dob_future", "That date of birth is in the future.")
        }

        val memberId = repo.addMember(householdId, displayName.trim(), relationship, dateOfBirth, notes)
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
    ): MemberRow {
        val userId = userContext.require()
        val household = get(householdId)
        val member = repo.member(householdId, memberId, userId)
            ?: throw ApiException.notFound("We couldn't find that person.")

        // You may always edit your own entry; editing anyone else needs admin.
        if (member.userId != userId) requireAdmin(household)

        val updated = repo.updateMember(
            memberId, displayName, relationship, dateOfBirth, version ?: member.version,
        )
        if (updated == 0) throw staleWrite(member.version)

        audit.record(
            householdId = householdId, actorUserId = userId, action = "member.update",
            entityType = "member", entityId = memberId,
        )
        return repo.member(householdId, memberId, userId)!!.copy(isMe = member.userId == userId)
    }

    /**
     * Removing a member is refused while they still own something. Deleting the
     * person would orphan the holding and silently change everyone's totals;
     * the honest answer is to say what is in the way (docs/07 §1).
     */
    @Transactional
    fun removeMember(householdId: UUID, memberId: UUID) {
        val userId = userContext.require()
        requireAdmin(get(householdId))
        val member = repo.member(householdId, memberId, userId)
            ?: throw ApiException.notFound("We couldn't find that person.")

        if (member.userId == userId) {
            throw ApiException.badRequest(
                "cannot_remove_self",
                "You can't remove yourself from your own household.",
            )
        }
        val holdings = repo.countHoldings(memberId)
        if (holdings > 0) {
            throw ApiException.conflict(
                "member_has_holdings",
                "${member.displayName} still owns $holdings " +
                    (if (holdings == 1) "holding" else "holdings") +
                    ". Reassign or remove those first.",
                mapOf("holdings" to holdings),
            )
        }
        repo.softDeleteMember(memberId)
        audit.record(
            householdId = householdId, actorUserId = userId, action = "member.delete",
            entityType = "member", entityId = memberId,
        )
    }

    // --- guards ---------------------------------------------------------------

    /**
     * Capability check only. It gates who may MANAGE the household; it never
     * widens what anyone may SEE. Reads are decided entirely by RLS.
     */
    private fun requireAdmin(household: HouseholdRow) {
        if (household.myRole !in setOf("owner", "admin")) {
            throw ApiException.forbidden("Only the household owner or an admin can do that.")
        }
    }

    private fun requireVisibility(value: String) {
        if (value !in visibilities) {
            throw ApiException.badRequest(
                "visibility_invalid",
                "Choose either private or household.",
            )
        }
    }

    private fun staleWrite(currentVersion: Int) = ApiException.conflict(
        "stale_write",
        "Someone else changed this while you were editing. Reload and try again.",
        mapOf("currentVersion" to currentVersion),
    )
}
