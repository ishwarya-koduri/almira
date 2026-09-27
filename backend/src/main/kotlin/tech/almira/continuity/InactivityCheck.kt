package tech.almira.continuity

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
import tech.almira.security.RequestUserContext
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The inactivity trigger's timeline, as pure arithmetic (docs/27 §1).
 *
 *     day 0   after [periodDays] with no sign of you: "Are you there?" — one tap says yes
 *     day 30  no answer: we ask once more
 *     day 60  still none: the request your trusted contacts could make begins,
 *             and then your own waiting period, your veto and V25's silence rule apply
 *
 * Each step is counted from the step before it actually happening, not from the
 * calendar. A server that was down for a month does not jump from nothing to a
 * request: it sends the first reminder, and the clock starts there. And every
 * stamp older than the person's last presence belongs to a finished cycle — a
 * sign-in or a tap starts over.
 */
object InactivityTimeline {
    val ASK_AGAIN_AFTER: Duration = Duration.ofDays(30)
    val RAISE_AFTER: Duration = Duration.ofDays(30)
    val PERIOD_CHOICES = listOf(60, 90, 180, 365)
    const val DEFAULT_PERIOD = 90
    const val MIN_PERIOD = 60
    const val MAX_PERIOD = 365

    enum class Step { FIRST_REMINDER, SECOND_REMINDER, RAISE }

    fun next(
        presence: Instant,
        periodDays: Int,
        firstReminderAt: Instant?,
        secondReminderAt: Instant?,
        raisedAt: Instant?,
        now: Instant,
    ): Step? {
        val first = firstReminderAt?.takeIf { it.isAfter(presence) }
        val second = secondReminderAt?.takeIf { first != null && it.isAfter(first) }
        val raised = raisedAt?.takeIf { second != null && it.isAfter(second) }
        return when {
            raised != null -> null
            second != null -> if (!now.isBefore(second.plus(RAISE_AFTER))) Step.RAISE else null
            first != null -> if (!now.isBefore(first.plus(ASK_AGAIN_AFTER))) Step.SECOND_REMINDER else null
            !now.isBefore(presence.plus(Duration.ofDays(periodDays.toLong()))) -> Step.FIRST_REMINDER
            else -> null
        }
    }

    /** `quiet` | `reminded_once` | `reminded_twice` | `raised`, for the current cycle. */
    fun stage(presence: Instant, firstReminderAt: Instant?, secondReminderAt: Instant?, raisedAt: Instant?): String {
        val first = firstReminderAt?.takeIf { it.isAfter(presence) } ?: return "quiet"
        val second = secondReminderAt?.takeIf { it.isAfter(first) } ?: return "reminded_once"
        raisedAt?.takeIf { it.isAfter(second) } ?: return "reminded_twice"
        return "raised"
    }
}

data class InactivityStep(
    /** 0, 30 or 60: days after the quiet period ends. */
    val day: Int,
    /** `ask` | `ask_again` | `request` */
    val code: String,
    val sentence: String,
    /** When it happened, or when it would happen if nothing changes. Null while off. */
    val at: Instant?,
    val done: Boolean,
)

data class InactivityCheckView(
    val enabled: Boolean,
    val periodDays: Int,
    val periodChoices: List<Int> = InactivityTimeline.PERIOD_CHOICES,
    /** The later of your last sign-in, your last "I'm here", and turning this on. */
    val lastPresenceAt: Instant?,
    val lastCheckInAt: Instant?,
    /** `off` | `quiet` | `reminded_once` | `reminded_twice` | `raised` */
    val stage: String,
    val steps: List<InactivityStep>,
    /** Everyone you named who could be asked: they have a login and are still in the household. */
    val contactsWhoCanAsk: List<String>,
    /** The shortest waiting period among them; nothing opens before it. */
    val shortestWaitDays: Int?,
    val explanation: String,
)

data class InactivitySettingsBody(
    val enabled: Boolean,
    val periodDays: Int = InactivityTimeline.DEFAULT_PERIOD,
)

/**
 * "If I go quiet" — an inactivity trigger beside request-based emergency access
 * (docs/27 §1).
 *
 * It adds no new way in. When the timeline runs out it raises the same
 * emergency request a trusted contact could have made by hand, in their name,
 * so the owner's waiting period, the veto and the silence rule of V25 all apply
 * unchanged; any sign of the owner — a sign-in, or one tap on "I'm here" —
 * keeps it shut. It is off unless the owner turns it on, turning it on or
 * changing the period needs a fresh step-up, and turning it off never does.
 */
@Service
class InactivityCheckService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val audit: AuditService,
    private val stepUp: StepUpService,
    private val userContext: RequestUserContext,
) {

    @Transactional(readOnly = true)
    fun view(householdId: UUID): InactivityCheckView {
        userContext.require()
        households.get(householdId)
        val me = meIn(householdId)
        return build(householdId, me)
    }

    @Transactional
    fun update(householdId: UUID, body: InactivitySettingsBody): InactivityCheckView {
        val userId = userContext.require()
        households.get(householdId)
        val me = meIn(householdId)
        if (body.periodDays !in InactivityTimeline.MIN_PERIOD..InactivityTimeline.MAX_PERIOD) {
            throw ApiException.badRequest(
                "period_invalid", "The quiet period is between sixty days and a year.",
            )
        }
        val current = row(householdId, me)
        val turningOn = body.enabled && current?.enabled != true
        val changing = body.enabled && current?.enabled == true && current.periodDays != body.periodDays
        if (turningOn || changing) {
            if (contactsWhoCanAsk(householdId, me).isEmpty()) {
                throw ApiException.badRequest(
                    "nobody_to_ask",
                    "Name someone who can sign in first. Without them, going quiet could start nothing.",
                )
            }
            // It makes a way in that needs nobody to ask, so it is confirmed
            // like revealing a number. Turning it off needs nothing.
            stepUp.requireElevated(
                userId, userContext.currentSessionId(),
                "Confirm it's you before you change what happens if you go quiet.",
            )
        }

        jdbc.update(
            """
            insert into inactivity_checks (household_id, member_id, user_id, enabled, period_days, enabled_at)
            values (:hid, :member, :uid, :enabled, :period, case when :enabled then now() end)
            on conflict (household_id, member_id) do update set
              enabled = excluded.enabled,
              period_days = excluded.period_days,
              -- Turning it on is being here: a cycle starts from that moment,
              -- never from a quiet spell that happened while it was off.
              enabled_at = case when excluded.enabled and not inactivity_checks.enabled then now()
                                else inactivity_checks.enabled_at end
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("hid", householdId).addValue("member", me).addValue("uid", userId)
                .addValue("enabled", body.enabled).addValue("period", body.periodDays),
        )
        audit.record(
            householdId = householdId, actorUserId = userId,
            action = when {
                turningOn -> "continuity.inactivity.on"
                !body.enabled && current?.enabled == true -> "continuity.inactivity.off"
                else -> "continuity.inactivity.change"
            },
            entityType = "member", entityId = me,
            diff = mapOf("enabled" to body.enabled, "periodDays" to body.periodDays),
        )
        return build(householdId, me)
    }

    /**
     * "I'm here", from inside the app. The same as the one-tap link: it resets the
     * clock, and it stops any request the sweep raised in your name.
     */
    @Transactional
    fun checkIn(householdId: UUID): InactivityCheckView {
        val userId = userContext.require()
        households.get(householdId)
        val me = meIn(householdId)
        val updated = jdbc.update(
            "update inactivity_checks set last_check_in_at = now() where household_id = :hid and member_id = :member",
            mapOf("hid" to householdId, "member" to me),
        )
        if (updated == 0) throw ApiException.notFound()
        val stopped = jdbc.update(
            """
            update emergency_requests set vetoed_at = now(), vetoed_by = :uid
            where household_id = :hid and subject_member_id = :member and raised_by = 'inactivity'
              and vetoed_at is null and revoked_at is null and now() < access_expires_at
            """.trimIndent(),
            mapOf("hid" to householdId, "member" to me, "uid" to userId),
        )
        audit.record(
            householdId = householdId, actorUserId = userId, action = "continuity.check_in",
            entityType = "member", entityId = me, diff = mapOf("via" to "app", "requestsStopped" to stopped),
        )
        return build(householdId, me)
    }

    private fun meIn(householdId: UUID): UUID =
        households.members(householdId).firstOrNull { it.isMe }?.id
            ?: throw ApiException(HttpStatus.BAD_REQUEST, "no_member", "You aren't a person in this household yet.")

    private data class Row(
        val enabled: Boolean,
        val periodDays: Int,
        val enabledAt: Instant?,
        val lastCheckInAt: Instant?,
        val lastSessionAt: Instant?,
        val firstReminderAt: Instant?,
        val secondReminderAt: Instant?,
        val raisedAt: Instant?,
    )

    private fun row(householdId: UUID, me: UUID): Row? = jdbc.query(
        """
        select c.*,
               (select max(s.last_used_at) from user_sessions s where s.user_id = c.user_id) as last_session_at
        from inactivity_checks c
        where c.household_id = :hid and c.member_id = :member
        """.trimIndent(),
        mapOf("hid" to householdId, "member" to me),
    ) { rs, _ ->
        Row(
            enabled = rs.getBoolean("enabled"),
            periodDays = rs.getInt("period_days"),
            enabledAt = rs.getTimestamp("enabled_at")?.toInstant(),
            lastCheckInAt = rs.getTimestamp("last_check_in_at")?.toInstant(),
            lastSessionAt = rs.getTimestamp("last_session_at")?.toInstant(),
            firstReminderAt = rs.getTimestamp("first_reminder_at")?.toInstant(),
            secondReminderAt = rs.getTimestamp("second_reminder_at")?.toInstant(),
            raisedAt = rs.getTimestamp("raised_at")?.toInstant(),
        )
    }.firstOrNull()

    private data class Contact(val name: String, val waitDays: Int)

    private fun contactsWhoCanAsk(householdId: UUID, me: UUID): List<Contact> = jdbc.query(
        """
        select t.display_name, c.wait_days
        from emergency_contacts c
        join members t on t.id = c.trusted_member_id and t.deleted_at is null and t.user_id is not null
        join household_memberships hm
          on hm.household_id = c.household_id and hm.user_id = t.user_id and hm.status = 'active'
        where c.household_id = :hid and c.member_id = :member
        order by lower(t.display_name)
        """.trimIndent(),
        mapOf("hid" to householdId, "member" to me),
    ) { rs, _ -> Contact(rs.getString("display_name"), rs.getInt("wait_days")) }

    private fun build(householdId: UUID, me: UUID): InactivityCheckView {
        val row = row(householdId, me)
        val contacts = contactsWhoCanAsk(householdId, me)
        val shortestWait = contacts.minOfOrNull { it.waitDays }
        val period = row?.periodDays ?: InactivityTimeline.DEFAULT_PERIOD
        val enabled = row?.enabled == true
        val presence = listOfNotNull(row?.enabledAt, row?.lastCheckInAt, row?.lastSessionAt).maxOrNull()

        val stage = if (!enabled || presence == null) "off"
        else InactivityTimeline.stage(presence, row.firstReminderAt, row.secondReminderAt, row.raisedAt)
        val first = row?.firstReminderAt?.takeIf { presence != null && it.isAfter(presence) }
        val second = row?.secondReminderAt?.takeIf { first != null && it.isAfter(first) }
        val raised = row?.raisedAt?.takeIf { second != null && it.isAfter(second) }
        val askAt = if (enabled && presence != null) first ?: presence.plus(Duration.ofDays(period.toLong())) else null
        val againAt = if (askAt != null) second ?: askAt.plus(InactivityTimeline.ASK_AGAIN_AFTER) else null
        val requestAt = if (againAt != null) raised ?: againAt.plus(InactivityTimeline.RAISE_AFTER) else null

        val waitWords = shortestWait?.let { "your $it-day wait" } ?: "your waiting period"
        val steps = listOf(
            InactivityStep(
                0, "ask",
                "After $period days without a sign of you, we ask whether you're there. One tap says yes.",
                askAt, first != null,
            ),
            InactivityStep(
                30, "ask_again",
                "If there's no answer, we ask once more, thirty days later.",
                againAt, second != null,
            ),
            InactivityStep(
                60, "request",
                "If there's still no answer, the request the people you named could make begins. " +
                    "Nothing opens for $waitWords, and any sign of you stops it.",
                requestAt, raised != null,
            ),
        )
        val explanation = when {
            contacts.isEmpty() -> "Nobody you named can sign in, so going quiet would start nothing. Name someone first."
            !enabled -> "Off. Only a request from someone you named can start emergency access."
            stage == "raised" -> "The request has begun. Tap \"I'm here\" to stop it. Nothing has opened."
            stage == "quiet" -> "On. Signing in, or tapping \"I'm here\", starts the count again."
            else -> "We've asked whether you're there. Tap \"I'm here\" and nothing more happens."
        }
        return InactivityCheckView(
            enabled = enabled,
            periodDays = period,
            lastPresenceAt = presence,
            lastCheckInAt = row?.lastCheckInAt,
            stage = stage,
            steps = steps,
            contactsWhoCanAsk = contacts.map { it.name },
            shortestWaitDays = shortestWait,
            explanation = explanation,
        )
    }
}

@RestController
@RequestMapping("/api/v1/households/{householdId}/emergency/inactivity")
class InactivityCheckController(private val service: InactivityCheckService) {

    @GetMapping
    fun inactivityCheck(@PathVariable householdId: UUID): InactivityCheckView = service.view(householdId)

    /** Turning it on, or changing the period, needs a step-up; turning it off does not. */
    @PutMapping
    fun updateInactivityCheck(@PathVariable householdId: UUID, @RequestBody body: InactivitySettingsBody): InactivityCheckView =
        service.update(householdId, body)

    /** "I'm here." */
    @PostMapping("/check-in")
    fun checkInImHere(@PathVariable householdId: UUID): InactivityCheckView = service.checkIn(householdId)
}
