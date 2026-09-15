package tech.bhrigu.almira.lifecycle

import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.auth.StepUpService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.household.MemberResponse
import tech.bhrigu.almira.household.MemberRow
import tech.bhrigu.almira.reminder.Notifier
import tech.bhrigu.almira.reminder.OutboundNotification
import tech.bhrigu.almira.security.RequestUserContext
import java.util.UUID

data class MarkPassedAwayBody(
    /** Optional, and seen by the household. A date, or who to call. */
    @field:Size(max = 500) val note: String? = null,
)

/**
 * Someone in the household has died (docs/05 §12).
 *
 * Nothing is erased, and nothing is opened. The account keeps sight of what it
 * could see and loses the ability to change anything in this household, the
 * roster carries a quiet label, and messages to the person stop. What their
 * family may now see is exactly what it could see before: the records they
 * shared, and — through emergency access — what they marked for the family.
 *
 * Two doors in, both recorded: an admin of the household, or the person's
 * trusted contact once an emergency window on them is genuinely open. Both
 * need a step-up, because this is a statement about a person that the database
 * acts on. And one door out: the person it names, signing in. They are told,
 * once, the moment it is made — the message the stop is written to let through
 * — so a mistake, or a lie, reaches the one person who can correct it.
 */
@Service
class MemorialService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val stepUp: StepUpService,
    private val audit: AuditService,
    private val notifiers: List<Notifier>,
    private val userContext: RequestUserContext,
    private val dormancy: DormancyNotices,
) {

    @Transactional
    fun markPassedAway(householdId: UUID, memberId: UUID, note: String?): MemberRow {
        val userId = userContext.require()
        val household = households.get(householdId)
        val member = findMember(householdId, memberId)

        if (member.userId == userId) {
            throw ApiException.badRequest("cannot_mark_self", "This can't be said about yourself.")
        }
        if (member.memorialisedAt != null) return member

        val basis = when {
            household.myRole in setOf("owner", "admin") && !household.readOnly && !household.dormant -> "admin"
            openWindowOn(householdId, memberId) -> "trusted_contact"
            household.myRole in setOf("owner", "admin") && household.dormant -> throw HouseholdService.dormant(household)
            else -> throw ApiException.forbidden(
                "Only a household admin, or ${member.displayName}'s trusted contact once emergency " +
                    "access has opened, can do this.",
            )
        }
        stepUp.requireElevated(userId, userContext.currentSessionId())

        // Told first, and before the stop exists. The template is also exempt from
        // the stop in the database (V40), so the safeguard does not rest on order.
        member.userId?.let { theirs ->
            notifiers.forEach { notifier ->
                runCatching {
                    notifier.deliver(
                        OutboundNotification(
                            userId = theirs, householdId = householdId, reminderId = null,
                            template = MARKED_TEMPLATE,
                            title = "Your account in ${household.name} has been marked as passed away",
                            body = "If this is wrong, sign in and choose \"I'm here\". Nothing has been " +
                                "erased, and it is undone the moment you do.",
                            idempotencyKey = "memorial.marked:$householdId:$memberId:${UUID.randomUUID()}",
                        ),
                    )
                }
            }
        }

        jdbc.update(
            """
            insert into member_memorials (household_id, member_id, user_id, marked_by, basis, note)
            values (:hid, :mid, :uid, :by, :basis, :note)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("hid", householdId).addValue("mid", memberId)
                .addValue("uid", member.userId).addValue("by", userId)
                .addValue("basis", basis).addValue("note", note?.trim()?.ifEmpty { null }),
        )
        audit.record(
            householdId = householdId, actorUserId = userId, action = "member.memorial.mark",
            entityType = "member", entityId = memberId,
            // Whether it came through the admin door or the trusted contact's — the
            // two are different claims, and the log keeps which was made.
            diff = mapOf("basis" to basis, "hadLogin" to (member.userId != null)),
        )
        // The last owner able to act: V120's trigger has made the household dormant.
        jdbc.query(
            """
            select d.id from household_dormancies d
              join member_memorials mm on mm.id = d.memorial_id
             where mm.member_id = :mid and mm.reversed_at is null and d.ended_at is null
            """.trimIndent(),
            mapOf("mid" to memberId),
        ) { rs, _ -> rs.getObject("id", UUID::class.java) }.forEach { dormancy.opened(it, jdbc) }
        return findMember(householdId, memberId)
    }

    /** Only the person it names. Signing in and saying so is the whole correction. */
    @Transactional
    fun reverseMemorial(householdId: UUID, memberId: UUID): MemberRow {
        val userId = userContext.require()
        households.get(householdId)
        val member = findMember(householdId, memberId)
        if (member.userId != userId) {
            throw ApiException.forbidden("Only the person it names can take this label away.")
        }
        val dormant = jdbc.query(
            "select id from member_memorials where member_id = :mid and reversed_at is null",
            mapOf("mid" to memberId),
        ) { rs, _ -> rs.getObject("id", UUID::class.java) }.flatMap { dormancy.causedBy(memorialId = it) }
        val markedBy = jdbc.query(
            """
            update member_memorials set reversed_at = now(), reversed_by = :uid
            where member_id = :mid and reversed_at is null
            returning marked_by
            """.trimIndent(),
            mapOf("uid" to userId, "mid" to memberId),
        ) { rs, _ -> rs.getObject("marked_by", UUID::class.java) }
        if (markedBy.isEmpty()) return member

        audit.record(
            householdId = householdId, actorUserId = userId, action = "member.memorial.reverse",
            entityType = "member", entityId = memberId,
        )
        dormancy.returned(dormant)
        markedBy.filterNotNull().distinct().forEach { marker ->
            notifiers.forEach { notifier ->
                runCatching {
                    notifier.deliver(
                        OutboundNotification(
                            userId = marker, householdId = householdId, reminderId = null,
                            template = "lifecycle.memorial.reversed",
                            title = "${member.displayName} has signed in",
                            body = "${member.displayName} has taken away the passed-away label. Their " +
                                "account works as it did before.",
                            idempotencyKey = "memorial.reversed:$memberId:${UUID.randomUUID()}",
                        ),
                    )
                }
            }
        }
        return findMember(householdId, memberId)
    }

    private fun findMember(householdId: UUID, memberId: UUID): MemberRow =
        households.members(householdId).firstOrNull { it.id == memberId }
            ?: throw ApiException.notFound("We couldn't find that person.")

    private fun openWindowOn(householdId: UUID, memberId: UUID): Boolean = jdbc.queryForObject(
        "select app.has_open_emergency_window(:hid, :mid)",
        mapOf("hid" to householdId, "mid" to memberId),
        Boolean::class.java,
    ) == true

    companion object {
        /** Let through the stop (V40); the other warnings it lets through are listed in V103. */
        const val MARKED_TEMPLATE = "lifecycle.memorial.marked"
    }
}

@RestController
@RequestMapping("/api/v1/households/{householdId}/members/{memberId}/memorial")
class MemorialController(private val service: MemorialService) {

    /** Needs a recent step-up on this session (403 step_up_required otherwise). */
    @PostMapping
    fun markPassedAway(
        @PathVariable householdId: UUID,
        @PathVariable memberId: UUID,
        @RequestBody(required = false) @Valid body: MarkPassedAwayBody?,
    ): MemberResponse = service.markPassedAway(householdId, memberId, body?.note).toResponse()

    /** "I'm here." Only the person the label names. */
    @DeleteMapping
    fun reverseMemorial(
        @PathVariable householdId: UUID,
        @PathVariable memberId: UUID,
    ): MemberResponse = service.reverseMemorial(householdId, memberId).toResponse()
}

internal fun MemberRow.toResponse() = MemberResponse(
    id, displayName, relationship, dateOfBirth, isMinor, isManaged, isMe, role, version,
    passedAway = memorialisedAt != null, memorialisedAt = memorialisedAt,
)
