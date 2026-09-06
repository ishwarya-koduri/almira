package tech.bhrigu.almira.continuity

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.reminder.Notifier
import tech.bhrigu.almira.reminder.OutboundNotification
import tech.bhrigu.almira.security.RequestUserContext
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class TrustedContact(
    val id: UUID,
    val memberId: UUID,
    val memberName: String?,
    val trustedMemberId: UUID,
    val trustedMemberName: String?,
    val waitDays: Int,
    val note: String?,
    /** True when this row is about you being trusted, rather than you trusting. */
    val theyTrustMe: Boolean,
)

data class EmergencyRequestRow(
    val id: UUID,
    val subjectMemberId: UUID,
    val subjectName: String?,
    val requestedByName: String?,
    val requestedByMe: Boolean,
    val reason: String?,
    val requestedAt: Instant,
    val unlockAt: Instant,
    val accessExpiresAt: Instant,
    val vetoedAt: Instant?,
    val revokedAt: Instant?,
    /** waiting | open | vetoed | withdrawn | ended */
    val status: String,
    val secondsUntilUnlock: Long?,
    /**
     * True when the person it concerns has used Almira since the request. The
     * window stays shut while this holds — signing in is the plainest possible
     * statement that someone is reachable, and it stops the clock without them
     * having to understand what a veto is.
     */
    val subjectHasBeenActive: Boolean,
    val explanation: String,
)

/**
 * Emergency access: humane by design, and safe by construction (docs/05 §6).
 *
 * Someone the owner has named can ask to see the continuity records. Then
 * nothing happens for a while — long enough for an owner who is merely
 * travelling to notice and say no. Both sides are told at every step, the whole
 * thing is audited, and when the window does open it reveals only records
 * marked for continuity, never everything.
 *
 * The state is **derived from timestamps**, not stored in a status column. That
 * matters: there is no worker whose failure leaves an unlock switched on, and no
 * moment where the database and the truth disagree.
 */
@Service
class EmergencyService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val audit: AuditService,
    private val notifiers: List<Notifier>,
    private val userContext: RequestUserContext,
) {

    @Transactional
    fun nameTrustedContact(
        householdId: UUID,
        trustedMemberId: UUID,
        waitDays: Int,
        note: String?,
    ): TrustedContact {
        val userId = userContext.require()
        households.get(householdId)
        val members = households.members(householdId)
        val me = members.firstOrNull { it.isMe }
            ?: throw ApiException.badRequest("no_member", "You aren't a person in this household yet.")
        if (members.none { it.id == trustedMemberId }) {
            throw ApiException.badRequest("member_unknown", "That person isn't in this household.")
        }
        if (trustedMemberId == me.id) {
            throw ApiException.badRequest(
                "trusted_is_you", "Choose someone else — this is who acts if you can't.",
            )
        }
        if (waitDays !in 1..90) {
            throw ApiException.badRequest(
                "wait_invalid", "The waiting period is between one and ninety days.",
            )
        }

        jdbc.update(
            """
            insert into emergency_contacts (household_id, member_id, trusted_member_id, wait_days,
                                            note, created_by)
            values (:hid, :memberId, :trustedId, :waitDays, :note, :createdBy)
            on conflict (household_id, member_id, trusted_member_id)
              do update set wait_days = excluded.wait_days, note = excluded.note
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("hid", householdId).addValue("memberId", me.id)
                .addValue("trustedId", trustedMemberId).addValue("waitDays", waitDays)
                .addValue("note", note).addValue("createdBy", userId),
        )

        audit.record(
            householdId = householdId, actorUserId = userId, action = "emergency.contact.set",
            entityType = "member", entityId = trustedMemberId,
            diff = mapOf("waitDays" to waitDays),
        )
        notify(
            householdId, trustedMemberId, "emergency.named",
            "You've been named as an emergency contact",
            "${me.displayName} has asked you to be the person who can reach their records " +
                "if they can't. Nothing changes today.",
        )
        return listTrustedContacts(householdId).first {
            it.memberId == me.id && it.trustedMemberId == trustedMemberId
        }
    }

    @Transactional
    fun removeTrustedContact(householdId: UUID, trustedMemberId: UUID) {
        val userId = userContext.require()
        households.get(householdId)
        val me = households.members(householdId).firstOrNull { it.isMe } ?: throw ApiException.notFound()
        val removed = jdbc.update(
            """
            delete from emergency_contacts
            where household_id = :hid and member_id = :memberId and trusted_member_id = :trustedId
            """.trimIndent(),
            mapOf("hid" to householdId, "memberId" to me.id, "trustedId" to trustedMemberId),
        )
        if (removed == 0) throw ApiException.notFound()
        audit.record(
            householdId = householdId, actorUserId = userId, action = "emergency.contact.remove",
            entityType = "member", entityId = trustedMemberId,
        )
    }

    @Transactional(readOnly = true)
    fun listTrustedContacts(householdId: UUID): List<TrustedContact> {
        households.get(householdId)
        val mine = households.members(householdId).filter { it.isMe }.map { it.id }.toSet()
        return jdbc.query(
            """
            select c.*, m.display_name as member_name, t.display_name as trusted_name
            from emergency_contacts c
            left join members m on m.id = c.member_id
            left join members t on t.id = c.trusted_member_id
            where c.household_id = :hid
            order by m.display_name, t.display_name
            """.trimIndent(),
            mapOf("hid" to householdId),
        ) { rs, _ ->
            val memberId = rs.getObject("member_id", UUID::class.java)
            TrustedContact(
                id = rs.getObject("id", UUID::class.java),
                memberId = memberId,
                memberName = rs.getString("member_name"),
                trustedMemberId = rs.getObject("trusted_member_id", UUID::class.java),
                trustedMemberName = rs.getString("trusted_name"),
                waitDays = rs.getInt("wait_days"),
                note = rs.getString("note"),
                theyTrustMe = memberId !in mine,
            )
        }
    }

    // --- requesting -----------------------------------------------------------

    @Transactional
    fun request(householdId: UUID, subjectMemberId: UUID, reason: String?): EmergencyRequestRow {
        val userId = userContext.require()
        households.get(householdId)

        val waitDays = jdbc.query(
            """
            select c.wait_days from emergency_contacts c
            where c.household_id = :hid and c.member_id = :subject
              and c.trusted_member_id = any(app.current_member_ids(:hid))
            """.trimIndent(),
            mapOf("hid" to householdId, "subject" to subjectMemberId),
        ) { rs, _ -> rs.getInt("wait_days") }.firstOrNull()
            ?: throw ApiException.forbidden(
                "You haven't been named as this person's emergency contact.",
            )

        val open = openRequestFor(householdId, subjectMemberId, userId)
        if (open != null) return open

        val id = UUID.randomUUID()
        val now = Instant.now()
        val unlockAt = now.plus(Duration.ofDays(waitDays.toLong()))
        jdbc.update(
            """
            insert into emergency_requests (id, household_id, subject_member_id, requested_by,
                                            reason, requested_at, unlock_at, access_expires_at)
            values (:id, :hid, :subject, :by, :reason, :now, :unlockAt, :expiresAt)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id).addValue("hid", householdId)
                .addValue("subject", subjectMemberId).addValue("by", userId)
                .addValue("reason", reason)
                .addValue("now", java.sql.Timestamp.from(now))
                .addValue("unlockAt", java.sql.Timestamp.from(unlockAt))
                .addValue("expiresAt", java.sql.Timestamp.from(unlockAt.plus(Duration.ofDays(30)))),
        )

        audit.record(
            householdId = householdId, actorUserId = userId, action = "emergency.request",
            entityType = "member", entityId = subjectMemberId,
            diff = mapOf("unlockAt" to unlockAt.toString(), "reason" to reason),
        )
        // The subject is told first and loudest. A request they never hear about
        // is a backdoor with extra steps.
        notify(
            householdId, subjectMemberId, "emergency.requested",
            "Someone has asked for emergency access to your records",
            "If this wasn't expected, you can stop it. Nothing opens until $unlockAt.",
        )
        return get(householdId, id)
    }

    @Transactional
    fun veto(householdId: UUID, requestId: UUID): EmergencyRequestRow {
        val userId = userContext.require()
        val request = get(householdId, requestId)
        val mine = households.members(householdId).filter { it.isMe }.map { it.id }.toSet()
        if (request.subjectMemberId !in mine) {
            throw ApiException.forbidden("Only the person it concerns can stop it.")
        }
        jdbc.update(
            """
            update emergency_requests set vetoed_at = now(), vetoed_by = :by
            where id = :id and vetoed_at is null
            """.trimIndent(),
            mapOf("id" to requestId, "by" to userId),
        )
        audit.record(
            householdId = householdId, actorUserId = userId, action = "emergency.veto",
            entityType = "emergency_request", entityId = requestId,
        )
        notify(
            householdId, null, "emergency.vetoed",
            "An emergency access request was stopped",
            "The person it concerned has stopped it. Nothing was opened.",
        )
        return get(householdId, requestId)
    }

    /** The requester changing their mind. Kept distinct from a veto in the log. */
    @Transactional
    fun withdraw(householdId: UUID, requestId: UUID): EmergencyRequestRow {
        val userId = userContext.require()
        val request = get(householdId, requestId)
        if (!request.requestedByMe) {
            throw ApiException.forbidden("Only the person who asked can withdraw it.")
        }
        jdbc.update(
            "update emergency_requests set revoked_at = now() where id = :id and revoked_at is null",
            mapOf("id" to requestId),
        )
        audit.record(
            householdId = householdId, actorUserId = userId, action = "emergency.withdraw",
            entityType = "emergency_request", entityId = requestId,
        )
        return get(householdId, requestId)
    }

    @Transactional(readOnly = true)
    fun list(householdId: UUID): List<EmergencyRequestRow> {
        households.get(householdId)
        return query("where r.household_id = :hid order by r.requested_at desc", mapOf("hid" to householdId))
    }

    @Transactional(readOnly = true)
    fun get(householdId: UUID, id: UUID): EmergencyRequestRow =
        query(
            "where r.household_id = :hid and r.id = :id",
            mapOf("hid" to householdId, "id" to id),
        ).firstOrNull() ?: throw ApiException.notFound()

    private fun openRequestFor(householdId: UUID, subject: UUID, userId: UUID) = query(
        """
        where r.household_id = :hid and r.subject_member_id = :subject and r.requested_by = :by
          and r.vetoed_at is null and r.revoked_at is null and now() < r.access_expires_at
        """.trimIndent(),
        mapOf("hid" to householdId, "subject" to subject, "by" to userId),
    ).firstOrNull()

    private fun query(where: String, params: Map<String, Any?>): List<EmergencyRequestRow> {
        val userId = userContext.currentUserId()
        return jdbc.query(
            """
            select r.*, m.display_name as subject_name, u.full_name as requester_name,
                   exists (
                     select 1 from user_sessions s
                     where s.user_id = m.user_id and s.last_used_at > r.requested_at
                   ) as subject_active
            from emergency_requests r
            left join members m on m.id = r.subject_member_id
            left join users u on u.id = r.requested_by
            $where
            """.trimIndent(),
            params,
        ) { rs, _ ->
            val unlockAt = rs.getTimestamp("unlock_at").toInstant()
            val expiresAt = rs.getTimestamp("access_expires_at").toInstant()
            val vetoedAt = rs.getTimestamp("vetoed_at")?.toInstant()
            val revokedAt = rs.getTimestamp("revoked_at")?.toInstant()
            val now = Instant.now()
            val subjectActive = rs.getBoolean("subject_active")
            val status = when {
                vetoedAt != null -> "vetoed"
                revokedAt != null -> "withdrawn"
                now.isBefore(unlockAt) -> "waiting"
                !now.isBefore(expiresAt) -> "ended"
                // Past the wait, still inside the window, but the person has
                // been here since: it does not open, and it is not a refusal
                // either — they simply turned out to be reachable.
                subjectActive -> "waiting"
                else -> "open"
            }
            EmergencyRequestRow(
                id = rs.getObject("id", UUID::class.java),
                subjectMemberId = rs.getObject("subject_member_id", UUID::class.java),
                subjectName = rs.getString("subject_name"),
                requestedByName = rs.getString("requester_name"),
                requestedByMe = rs.getObject("requested_by", UUID::class.java) == userId,
                reason = rs.getString("reason"),
                requestedAt = rs.getTimestamp("requested_at").toInstant(),
                unlockAt = unlockAt,
                accessExpiresAt = expiresAt,
                vetoedAt = vetoedAt,
                revokedAt = revokedAt,
                status = status,
                secondsUntilUnlock = if (status == "waiting" && now.isBefore(unlockAt)) {
                    Duration.between(now, unlockAt).seconds
                } else {
                    null
                },
                subjectHasBeenActive = subjectActive,
                explanation = explain(status, rs.getString("subject_name"), subjectActive),
            )
        }
    }

    private fun explain(status: String, subject: String?, subjectActive: Boolean) = when (status) {
        "waiting" -> if (subjectActive) {
            "${subject ?: "The person it concerns"} has used Almira since you asked, so nothing " +
                "will open. They are reachable — talk to them."
        } else {
            "Waiting. ${subject ?: "The person it concerns"} can stop this at any time " +
                "before it opens, and has been told."
        }
        "open" -> "Open. Only records marked for the family summary are visible, and every " +
            "view is logged."
        "vetoed" -> "Stopped by ${subject ?: "the person it concerns"}. Nothing was opened."
        "withdrawn" -> "Withdrawn by whoever asked. Nothing was opened."
        else -> "Ended. The window has closed."
    }

    /**
     * Through the interface, not around it. Notifications are a logging stand-in
     * until a provider is configured; routing every one of them through
     * [Notifier] is what makes that a one-line change later rather than a hunt.
     */
    private fun notify(householdId: UUID, memberId: UUID?, template: String, title: String, body: String) {
        val recipients = households.members(householdId)
            .filter { memberId == null || it.id == memberId }
            .mapNotNull { it.userId }
        recipients.forEach { userId ->
            notifiers.forEach { notifier ->
                notifier.deliver(
                    OutboundNotification(
                        userId = userId, householdId = householdId, reminderId = null,
                        template = template, title = title, body = body,
                    ),
                )
            }
        }
    }
}
