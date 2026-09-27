package tech.almira.lifecycle

import org.springframework.dao.DataAccessException
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tech.almira.audit.AuditService
import tech.almira.auth.StepUpService
import tech.almira.common.ApiException
import tech.almira.household.HouseholdService
import tech.almira.reminder.Notifier
import tech.almira.reminder.OutboundNotification
import tech.almira.security.RequestUserContext
import java.time.Instant
import java.util.UUID

data class NameSuccessorBody(val memberId: UUID)

data class SuccessorView(
    /** False when there is none — or none you are one of the two people allowed to know about. */
    val named: Boolean,
    val memberId: UUID? = null,
    val memberName: String? = null,
    val namedAt: Instant? = null,
    val claimedAt: Instant? = null,
    /** passed_away | emergency_access */
    val claimedBasis: String? = null,
    val youAreTheSuccessor: Boolean = false,
    /** The event has happened and you are the one named: claiming would succeed now. */
    val canClaim: Boolean = false,
    val explanation: String? = null,
)

/**
 * "If I can't manage this": the owner names who carries the household on
 * (docs/05 §12).
 *
 * Naming changes nothing today, and only the owner and the person named can see
 * it. Claiming is the one audited way the successor becomes able to run the
 * household (`app.claim_household_succession`, V41), and the database refuses it
 * unless the named event has happened: the owner has been marked as passed away
 * for at least a week, or the successor's own emergency window on the owner is
 * open. On a memorial they become the owner; on an emergency window, an admin.
 *
 * What it never does is open anything. Role is capability, not sight (docs/05
 * §3): the owner's private records stay private, and what reaches the family is
 * what the owner marked for it, through the emergency window, as they chose.
 */
@Service
class SuccessionService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val stepUp: StepUpService,
    private val audit: AuditService,
    private val notifiers: List<Notifier>,
    private val userContext: RequestUserContext,
) {

    @Transactional(readOnly = true)
    fun successor(householdId: UUID): SuccessorView {
        val userId = userContext.require()
        households.get(householdId)
        return jdbc.query(
            """
            select s.*, m.display_name, (m.user_id = :uid) as is_me,
                   owner_member.id as owner_member_id,
                   exists (select 1 from member_memorials mm
                            where mm.household_id = s.household_id and mm.user_id = s.named_by
                              and mm.reversed_at is null and mm.marked_at <= now() - interval '7 days') as owner_gone,
                   exists (select 1 from member_memorials mm
                            where mm.household_id = s.household_id and mm.user_id = s.named_by
                              and mm.reversed_at is null) as owner_marked
              from household_successors s
              join members m on m.id = s.successor_member_id
              left join members owner_member
                     on owner_member.household_id = s.household_id and owner_member.user_id = s.named_by
                    and owner_member.deleted_at is null
             where s.household_id = :hid
            """.trimIndent(),
            mapOf("hid" to householdId, "uid" to userId),
        ) { rs, _ ->
            val isMe = rs.getBoolean("is_me")
            val claimed = rs.getTimestamp("claimed_at")?.toInstant()
            val ownerMember = rs.getObject("owner_member_id", UUID::class.java)
            val window = isMe && ownerMember != null && openWindow(householdId, ownerMember)
            val canClaim = isMe && claimed == null && (rs.getBoolean("owner_gone") || window)
            SuccessorView(
                named = true,
                memberId = rs.getObject("successor_member_id", UUID::class.java),
                memberName = rs.getString("display_name"),
                namedAt = rs.getTimestamp("named_at").toInstant(),
                claimedAt = claimed,
                claimedBasis = rs.getString("claimed_basis"),
                youAreTheSuccessor = isMe,
                canClaim = canClaim,
                explanation = when {
                    claimed != null -> "Carried on since ${claimed.toString().take(10)}."
                    !isMe -> "Nothing changes unless you can't manage the household."
                    canClaim -> "You can take over running the household now."
                    rs.getBoolean("owner_marked") ->
                        "A week after a memorial, so it can be corrected if it's wrong."
                    else -> "Nothing changes today. This opens only if they pass away, or if your " +
                        "emergency access to their records opens."
                },
            )
        }.firstOrNull() ?: SuccessorView(named = false)
    }

    @Transactional
    fun nameSuccessor(householdId: UUID, memberId: UUID): SuccessorView {
        val userId = userContext.require()
        val household = households.get(householdId)
        if (household.myRole != "owner" || household.readOnly) {
            throw ApiException.forbidden("Only the household owner names who carries it on.")
        }
        households.refuseWhileDormant(household)
        val member = households.members(householdId).firstOrNull { it.id == memberId }
            ?: throw ApiException.notFound("We couldn't find that person.")
        when {
            member.userId == userId -> throw ApiException.badRequest("successor_is_you", "Choose someone else.")
            member.userId == null -> throw ApiException.badRequest(
                "successor_needs_login", "${member.displayName} needs their own login to carry the household on.",
            )
            member.role == "advisor" -> throw ApiException.badRequest(
                "successor_is_advisor", "An advisor can't carry the household on. Choose someone in the family.",
            )
        }
        jdbc.update(
            """
            insert into household_successors (household_id, named_by, successor_member_id)
            values (:hid, :uid, :mid)
            on conflict (household_id) do update
               set successor_member_id = excluded.successor_member_id, named_by = excluded.named_by,
                   named_at = now()
            """.trimIndent(),
            mapOf("hid" to householdId, "uid" to userId, "mid" to memberId),
        )
        audit.record(
            householdId = householdId, actorUserId = userId, action = "household.successor.name",
            entityType = "member", entityId = memberId,
        )
        val owner = households.members(householdId).firstOrNull { it.isMe }?.displayName ?: "The owner"
        tell(
            member.userId!!, householdId, "lifecycle.successor.named", "successor.named:$householdId:$memberId",
            "$owner has named you to carry ${household.name} on",
            "Nothing changes today. If $owner can't manage the household, you'll be able to run it. " +
                "Their private records stay private.",
        )
        return successor(householdId)
    }

    @Transactional
    fun removeSuccessor(householdId: UUID) {
        val userId = userContext.require()
        val household = households.get(householdId)
        if (household.myRole != "owner" || household.readOnly) {
            throw ApiException.forbidden("Only the household owner names who carries it on.")
        }
        households.refuseWhileDormant(household)
        val removed = jdbc.update(
            "delete from household_successors where household_id = :hid and claimed_at is null",
            mapOf("hid" to householdId),
        )
        if (removed == 0) throw ApiException.notFound()
        audit.record(
            householdId = householdId, actorUserId = userId, action = "household.successor.remove",
            entityType = "household", entityId = householdId,
        )
    }

    @Transactional
    fun claimSuccession(householdId: UUID): SuccessorView {
        val userId = userContext.require()
        val household = households.get(householdId)
        val view = successor(householdId)
        if (!view.youAreTheSuccessor) throw ApiException.notFound()
        if (view.claimedAt != null) {
            throw ApiException.conflict("succession_already_claimed", "The household has already been carried on.")
        }
        if (!view.canClaim) throw ApiException.conflict("succession_not_yet", view.explanation ?: "Not yet.")
        stepUp.requireElevated(userId, userContext.currentSessionId())

        val basis = try {
            jdbc.queryForObject(
                "select app.claim_household_succession(:hid)", mapOf("hid" to householdId), String::class.java,
            )
        } catch (e: DataAccessException) {
            val text = e.mostSpecificCause.message ?: ""
            throw when {
                "succession_not_named" in text -> ApiException.notFound()
                "succession_already_claimed" in text ->
                    ApiException.conflict("succession_already_claimed", "The household has already been carried on.")
                else -> ApiException.conflict("succession_not_yet", view.explanation ?: "Not yet.")
            }
        }
        // The function wrote its own audit line; the household is told in one sentence.
        households.members(householdId).mapNotNull { it.userId }.filter { it != userId }.forEach { other ->
            tell(
                other, householdId, "lifecycle.successor.claimed", "successor.claimed:$householdId",
                "${view.memberName} is now running ${household.name}",
                "They were named to carry the household on. Private records stay private.",
            )
        }
        return successor(householdId).copy(claimedBasis = basis)
    }

    private fun openWindow(householdId: UUID, memberId: UUID): Boolean = jdbc.queryForObject(
        "select app.has_open_emergency_window(:hid, :mid)",
        mapOf("hid" to householdId, "mid" to memberId), Boolean::class.java,
    ) == true

    private fun tell(userId: UUID, householdId: UUID, template: String, key: String, title: String, body: String) {
        notifiers.forEach { notifier ->
            runCatching {
                notifier.deliver(
                    OutboundNotification(
                        userId = userId, householdId = householdId, reminderId = null,
                        template = template, title = title, body = body, idempotencyKey = "$key:$userId",
                    ),
                )
            }
        }
    }
}

@RestController
@RequestMapping("/api/v1/households/{householdId}/successor")
class SuccessionController(private val service: SuccessionService) {

    /** Visible to the owner and to the person named; `named: false` for anyone else. */
    @GetMapping
    fun successor(@PathVariable householdId: UUID): SuccessorView = service.successor(householdId)

    /** The owner names someone with a login. Changes nothing today. */
    @PutMapping
    fun nameSuccessor(@PathVariable householdId: UUID, @RequestBody body: NameSuccessorBody): SuccessorView =
        service.nameSuccessor(householdId, body.memberId)

    @DeleteMapping
    fun removeSuccessor(@PathVariable householdId: UUID): ResponseEntity<Void> {
        service.removeSuccessor(householdId)
        return ResponseEntity.noContent().build()
    }

    /** Only once the named event has happened (409 succession_not_yet). Needs a step-up. */
    @PostMapping("/claim")
    fun claimSuccession(@PathVariable householdId: UUID): SuccessorView = service.claimSuccession(householdId)
}
