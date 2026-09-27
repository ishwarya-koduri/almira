package tech.almira.continuity

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.almira.audit.AuditService
import tech.almira.common.ApiException
import tech.almira.household.HouseholdService
import tech.almira.reminder.Notifier
import tech.almira.reminder.OutboundNotification
import tech.almira.security.RequestUserContext
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
    /** The last dated "Yes, I can still be reached" (docs/27 §3). */
    val reachableConfirmedAt: Instant? = null,
    /** When they were last asked that. */
    val reachabilityAskedAt: Instant? = null,
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
    /** What happens, when, and to whom — the same five steps every time (docs/05 §6.1). */
    val timeline: List<EmergencyTimelineStep> = emptyList(),
    /**
     * `request` when someone asked; `inactivity` when the owner's own "if I go
     * quiet" setting began it in that person's name (docs/27 §1).
     */
    val raisedBy: String = "request",
)

/**
 * One step of an emergency request, dated.
 *
 * [state] is `done`, `now` (the step the request is on), `next`, or `skipped`
 * (a step that will not happen because the request was stopped).
 */
data class EmergencyTimelineStep(
    /** asked | told | say_no | opens | closes */
    val key: String,
    val title: String,
    val detail: String,
    val at: Instant?,
    val state: String,
)

/**
 * The picture of what naming someone means, before anyone asks: the same steps,
 * dated as if they asked today, and what they would and would never see.
 */
data class EmergencyPreview(
    val trustedMemberId: UUID,
    val trustedMemberName: String,
    val waitDays: Int,
    val steps: List<EmergencyTimelineStep>,
    /** Plain sentences, counts only — never a title or an amount. */
    val willSee: List<String>,
    val neverSee: List<String>,
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
                "trusted_is_you", "Choose someone else. This is who acts if you can't.",
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
        val contactId = jdbc.queryForObject(
            """
            select id from emergency_contacts
            where household_id = :hid and member_id = :memberId and trusted_member_id = :trustedId
            """.trimIndent(),
            mapOf("hid" to householdId, "memberId" to me.id, "trustedId" to trustedMemberId),
            UUID::class.java,
        )
        notify(
            householdId, trustedMemberId, "emergency.named:$contactId", "emergency.named",
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
            select c.*, m.display_name as member_name, t.display_name as trusted_name,
                   (select max(x.confirmed_at) from trusted_contact_confirmations x
                    where x.emergency_contact_id = c.id) as reachable_confirmed_at,
                   (select a.last_asked_at from trusted_contact_asks a
                    where a.emergency_contact_id = c.id) as reachability_asked_at
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
                reachableConfirmedAt = rs.getTimestamp("reachable_confirmed_at")?.toInstant(),
                reachabilityAskedAt = rs.getTimestamp("reachability_asked_at")?.toInstant(),
            )
        }
    }

    /**
     * What naming [trustedMemberId] would mean, drawn before it is done (X-41).
     * Counted under the caller's own row-level security, so it describes only
     * what the caller can see — and says so in counts, never in titles.
     */
    @Transactional(readOnly = true)
    fun preview(householdId: UUID, trustedMemberId: UUID, waitDays: Int): EmergencyPreview {
        households.get(householdId)
        val members = households.members(householdId)
        val me = members.firstOrNull { it.isMe }
            ?: throw ApiException.badRequest("no_member", "You aren't a person in this household yet.")
        val trusted = members.firstOrNull { it.id == trustedMemberId && it.id != me.id }
            ?: throw ApiException.notFound("We couldn't find that person.")
        if (waitDays !in 1..90) {
            throw ApiException.badRequest(
                "wait_invalid", "The waiting period is between one and ninety days.",
            )
        }

        val counts = jdbc.queryForMap(
            """
            select
              (select count(*) from investments
                where household_id = :hid and deleted_at is null
                  and status in ('active','matured') and is_in_continuity) as included,
              (select count(*) from investments
                where household_id = :hid and deleted_at is null
                  and status in ('active','matured') and not is_in_continuity) as left_out,
              (select count(*) from liabilities
                where household_id = :hid and deleted_at is null and status = 'active'
                  and is_in_continuity) as debts,
              (select count(*) from estate_documents
                where household_id = :hid and deleted_at is null) as paperwork
            """.trimIndent(),
            mapOf("hid" to householdId),
        )
        fun count(key: String) = (counts[key] as Number).toInt()

        val now = Instant.now()
        val unlockAt = now.plus(Duration.ofDays(waitDays.toLong()))
        return EmergencyPreview(
            trustedMemberId = trusted.id,
            trustedMemberName = trusted.displayName,
            waitDays = waitDays,
            steps = EmergencyTimeline.preview(trusted.displayName, now, unlockAt, unlockAt.plus(WINDOW), waitDays),
            willSee = EmergencyTimeline.willSee(count("included"), count("debts"), count("paperwork")),
            neverSee = EmergencyTimeline.neverSee(count("left_out")),
        )
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
                .addValue("expiresAt", java.sql.Timestamp.from(unlockAt.plus(WINDOW))),
        )

        audit.record(
            householdId = householdId, actorUserId = userId, action = "emergency.request",
            entityType = "member", entityId = subjectMemberId,
            diff = mapOf("unlockAt" to unlockAt.toString(), "reason" to reason),
        )
        // The subject is told first and loudest. A request they never hear about
        // is a backdoor with extra steps.
        notify(
            householdId, subjectMemberId, "emergency.requested:$id", "emergency.requested",
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
            householdId, null, "emergency.vetoed:$requestId", "emergency.vetoed",
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
            select r.*, m.display_name as subject_name,
                   -- The name the household knows them by, before the account's.
                   coalesce(rm.display_name, u.full_name) as requester_name,
                   -- A sign-in or an "I'm here" since the request (V95), asked
                   -- through the request itself so presence is never a
                   -- question about just anyone.
                   app.emergency_subject_present(r.id) as subject_active
            from emergency_requests r
            left join members m on m.id = r.subject_member_id
            left join users u on u.id = r.requested_by
            left join members rm on rm.household_id = r.household_id and rm.user_id = r.requested_by
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
                timeline = EmergencyTimeline.forRequest(
                    status = status,
                    requestedByMe = rs.getObject("requested_by", UUID::class.java) == userId,
                    requesterName = rs.getString("requester_name"),
                    subjectName = rs.getString("subject_name"),
                    requestedAt = rs.getTimestamp("requested_at").toInstant(),
                    unlockAt = unlockAt,
                    expiresAt = expiresAt,
                    stoppedAt = vetoedAt ?: revokedAt,
                    subjectActive = subjectActive,
                ),
                raisedBy = rs.getString("raised_by"),
            )
        }
    }

    private fun explain(status: String, subject: String?, subjectActive: Boolean) = when (status) {
        "waiting" -> if (subjectActive) {
            "${subject ?: "The person it concerns"} has used Almira since you asked, so nothing " +
                "will open. They are reachable, so talk to them."
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
    /**
     * [logicalKey] names the event, not the call: the same contact named again, or a
     * request vetoed twice, is the same message, and each person is told it once.
     */
    private fun notify(householdId: UUID, memberId: UUID?, logicalKey: String, template: String, title: String, body: String) {
        val recipients = households.members(householdId)
            .filter { memberId == null || it.id == memberId }
            .mapNotNull { it.userId }
        recipients.forEach { userId ->
            notifiers.forEach { notifier ->
                notifier.deliver(
                    OutboundNotification(
                        userId = userId, householdId = householdId, reminderId = null,
                        template = template, title = title, body = body,
                        idempotencyKey = "$logicalKey:$userId",
                    ),
                )
            }
        }
    }

    private companion object {
        /** How long a window stays open once it opens. */
        val WINDOW: Duration = Duration.ofDays(30)
    }
}
