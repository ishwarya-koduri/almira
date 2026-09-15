package tech.bhrigu.almira.lifecycle

import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tech.bhrigu.almira.auth.StepUpService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.household.HouseholdRow
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.reminder.Notifier
import tech.bhrigu.almira.reminder.OutboundNotification
import tech.bhrigu.almira.security.RequestUserContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

data class DormancyView(
    /** Nobody runs the household at the moment. Everything else below is null when false. */
    val dormant: Boolean,
    val since: Instant? = null,
    /** owner_closing_account | owner_leaving | owner_passed_away */
    val reason: String? = null,
    /** The owner whose going left it without anyone to run it. */
    val ownerName: String? = null,
    /** The earliest anyone may take it on: a week after a memorial, at once otherwise. */
    val acceptFrom: Instant? = null,
    /** You may take it on now (a step-up is still asked). */
    val canAccept: Boolean = false,
    /** What this means for you, in words. */
    val explanation: String? = null,
)

/**
 * A household whose last owner goes while it holds records (docs/05 §12.7, V120).
 *
 * The owner's decision: never purge the last owner while the household holds
 * records; move it to dormant, tell the remaining members, and require an
 * explicit transfer first. So the sweep asks [DormancyRecords.leavesOwnerless] before it
 * carries out a closure or a departure, and a memorial on the last owner opens
 * one in the database. While it is dormant nothing that needs an owner or admin
 * can be done (the capability functions say so), everyone keeps exactly the
 * sight they had, and the handbook and Download everything work as before.
 *
 * It ends one of two ways, both in the database: an adult member with a login
 * takes it on here, or the owner comes back — cancels the closure or the
 * departure, or says "I'm here". A household with nobody who may take it on
 * stays dormant; it is never erased for that.
 */
@Service
class DormancyService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val stepUp: StepUpService,
    private val notices: DormancyNotices,
    private val userContext: RequestUserContext,
) {

    @Transactional(readOnly = true)
    fun dormancy(householdId: UUID): DormancyView {
        val userId = userContext.require()
        return decide(households.get(householdId), userId).view
    }

    /**
     * Take the household on. Every refusal that can be said in words is said
     * before the step-up is asked for; the step-up comes before the database
     * function, which checks it all again and is the one that decides.
     */
    @Transactional
    fun accept(householdId: UUID): DormancyView {
        val userId = userContext.require()
        val household = households.get(householdId)
        val current = decide(household, userId)
        if (!current.view.dormant) throw notDormant(household)
        if (!current.view.canAccept) throw refusal(current)
        stepUp.requireElevated(userId, userContext.currentSessionId())

        try {
            jdbc.queryForList("select app.accept_household_ownership(:hid)", mapOf("hid" to householdId))
        } catch (e: DataAccessException) {
            val text = e.mostSpecificCause.message ?: ""
            throw when {
                "dormancy_not_found" in text -> ApiException.notFound("We couldn't find that household.")
                "household_not_dormant" in text -> notDormant(household)
                "dormancy_not_yet" in text -> ApiException.conflict("dormancy_not_yet", WAIT_EXPLANATION)
                else -> ApiException(HttpStatus.FORBIDDEN, "ownership_not_eligible", NOT_ELIGIBLE)
            }
        }
        // The function wrote the audit line, and the dormancy ended with the role change.
        val me = households.members(householdId).firstOrNull { it.isMe }?.displayName ?: "Someone"
        notices.accepted(householdId, household.name, me, userId)
        return decide(households.get(householdId), userId).view
    }

    private fun notDormant(household: HouseholdRow) =
        ApiException.conflict("household_not_dormant", "${household.name} already has someone running it.")

    private fun refusal(decision: Decision) =
        if (decision.waiting) ApiException.conflict("dormancy_not_yet", WAIT_EXPLANATION)
        else ApiException(HttpStatus.FORBIDDEN, "ownership_not_eligible", decision.view.explanation ?: NOT_ELIGIBLE)

    private data class Decision(val view: DormancyView, val waiting: Boolean)

    private fun decide(household: HouseholdRow, userId: UUID): Decision {
        val open = jdbc.query(
            """
            select d.started_at, d.reason, d.accept_from, d.owner_user_id, m.display_name
              from household_dormancies d
              left join members m on m.id = d.owner_member_id
             where d.household_id = :hid and d.ended_at is null
            """.trimIndent(),
            mapOf("hid" to household.id),
        ) { rs, _ ->
            Open(
                rs.getTimestamp("started_at").toInstant(), rs.getString("reason"),
                rs.getTimestamp("accept_from").toInstant(), rs.getObject("owner_user_id", UUID::class.java),
                rs.getString("display_name"),
            )
        }.firstOrNull() ?: return Decision(DormancyView(dormant = false), waiting = false)

        val why = eligibility(household, userId, open)
        val waiting = why == null && Instant.now().isBefore(open.acceptFrom)
        val view = DormancyView(
            dormant = true,
            since = open.startedAt,
            reason = open.reason,
            ownerName = open.ownerName,
            acceptFrom = open.acceptFrom,
            canAccept = why == null && !waiting,
            explanation = when {
                why != null -> why
                waiting -> WAIT_EXPLANATION
                else -> "Nobody runs ${household.name} at the moment. You can take it on. Private records " +
                    "stay private."
            },
        )
        return Decision(view, waiting)
    }

    /** Null when the caller may take it on; otherwise why not, in words. Same rules as V120. */
    private fun eligibility(household: HouseholdRow, userId: UUID, open: Open): String? {
        if (open.ownerUserId == userId) {
            return when (open.reason) {
                "owner_passed_away" -> "Your account here is marked as passed away. If that's wrong, choose " +
                    "\"I'm here\" on the Family screen."
                "owner_closing_account" -> "Nobody else runs ${household.name}, so it waits for someone to take " +
                    "it on. To keep running it, choose Keep my account."
                else -> "Nobody else runs ${household.name}, so it waits for someone to take it on. To keep " +
                    "running it, stay in the household."
            }
        }
        if (household.readOnly) return NOT_ELIGIBLE
        if (household.myRole !in setOf("admin", "editor", "viewer")) return NOT_ELIGIBLE
        val adult = jdbc.queryForObject(
            """
            select exists (select 1 from members m
                            where m.household_id = :hid and m.user_id = :uid and m.deleted_at is null
                              and not app.is_minor(m.date_of_birth))
            """.trimIndent(),
            mapOf("hid" to household.id, "uid" to userId), Boolean::class.java,
        ) == true
        if (!adult) return NOT_ELIGIBLE
        val leaving = jdbc.queryForObject(
            """
            select exists (select 1 from account_closures c
                            where c.user_id = :uid and c.cancelled_at is null and c.purged_at is null)
                or exists (select 1 from household_departures d
                            where d.household_id = :hid and d.user_id = :uid
                              and d.cancelled_at is null and d.completed_at is null)
            """.trimIndent(),
            mapOf("hid" to household.id, "uid" to userId), Boolean::class.java,
        ) == true
        if (leaving) {
            return "You're closing your account or leaving ${household.name}, so you can't take it on. " +
                "Change your mind about that first."
        }
        return null
    }

    private data class Open(
        val startedAt: Instant, val reason: String, val acceptFrom: Instant,
        val ownerUserId: UUID?, val ownerName: String?,
    )

    private companion object {
        const val NOT_ELIGIBLE =
            "An adult in the household with their own login — an admin, an editor or a viewer — can take it on."
        const val WAIT_EXPLANATION =
            "A week after a memorial, so it can be corrected if it's wrong. Until then nothing that needs " +
                "an owner can be done."
    }
}

/**
 * The neutral words (docs/05 §12.3, "Neutral words"): who no longer runs the
 * household and what can be done about it — never what they held, and never
 * whether they are closing their account.
 */
@Component
class DormancyNotices(
    private val jdbc: NamedParameterJdbcTemplate,
    private val notifiers: List<Notifier>,
) {

    /**
     * Told when a dormancy opens: every other member with a login who could take
     * it on (admins, editors, viewers), and the owner whose going caused it, who
     * learns their closure or departure is waiting. Idempotent per dormancy.
     */
    fun opened(dormancyId: UUID, systemJdbc: NamedParameterJdbcTemplate) {
        val row = systemJdbc.query(
            """
            select d.household_id, d.owner_user_id, d.reason, d.accept_from, h.name, m.display_name
              from household_dormancies d
              join households h on h.id = d.household_id
              left join members m on m.id = d.owner_member_id
             where d.id = :id
            """.trimIndent(),
            mapOf("id" to dormancyId),
        ) { rs, _ ->
            Opened(
                rs.getObject("household_id", UUID::class.java), rs.getObject("owner_user_id", UUID::class.java),
                rs.getString("reason"), rs.getTimestamp("accept_from").toInstant(), rs.getString("name"),
                rs.getString("display_name") ?: "The owner",
            )
        }.firstOrNull() ?: return

        val from = if (Instant.now().isBefore(row.acceptFrom)) " from ${day(row.acceptFrom)}" else ""
        recipients(systemJdbc, row.householdId, row.ownerUserId).forEach { other ->
            tell(
                other, row.householdId, "lifecycle.household.dormant", "household.dormant:$dormancyId",
                "${row.householdName} needs someone to run it",
                "${row.ownerName} no longer runs ${row.householdName}. Nothing has been erased, and private " +
                    "records stay private. An adult in the household can take it on$from, on the Family screen. " +
                    "Until then, nobody can invite or remove people.",
            )
        }
        if (row.reason != "owner_passed_away" && row.ownerUserId != null) {
            tell(
                row.ownerUserId, row.householdId, "lifecycle.household.dormant.you", "household.dormant:$dormancyId",
                "${row.householdName} is waiting for someone to take it on",
                "Nobody else runs ${row.householdName}, so what you hold there stays as it is until someone " +
                    "takes it on. You can still change your mind in Almira.",
            )
        }
    }

    /**
     * The open dormancies this closure, departure or memorial caused, asked
     * before it is withdrawn, so that [returned] can tell the household after.
     */
    fun causedBy(closureId: UUID? = null, departureId: UUID? = null, memorialId: UUID? = null): List<UUID> = jdbc.query(
        """
        select id from household_dormancies
         where ended_at is null
           and (closure_id = :closure or departure_id = :departure or memorial_id = :memorial)
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("closure", closureId ?: LifecycleRecords.NOBODY)
            .addValue("departure", departureId ?: LifecycleRecords.NOBODY)
            .addValue("memorial", memorialId ?: LifecycleRecords.NOBODY),
    ) { rs, _ -> rs.getObject("id", UUID::class.java) }

    /** The owner came back and the dormancy ended with it: the household is told it runs as before. */
    fun returned(dormancyIds: List<UUID>) {
        if (dormancyIds.isEmpty()) return
        jdbc.query(
            """
            select d.id, d.household_id, d.owner_user_id, h.name, m.display_name
              from household_dormancies d
              join households h on h.id = d.household_id
              left join members m on m.id = d.owner_member_id
             where d.id in (:ids) and d.ended_reason = 'owner_returned'
            """.trimIndent(),
            mapOf("ids" to dormancyIds),
        ) { rs, _ ->
            listOf(
                rs.getObject("id", UUID::class.java), rs.getObject("household_id", UUID::class.java),
                rs.getObject("owner_user_id", UUID::class.java), rs.getString("name"),
                rs.getString("display_name") ?: "The owner",
            )
        }.forEach { (id, householdId, owner, name, ownerName) ->
            recipients(jdbc, householdId as UUID, owner as UUID?, everyone = true).forEach { other ->
                tell(
                    other, householdId, "lifecycle.household.running_again", "household.returned:$id",
                    "$name is running as before", "$ownerName runs the household again. Nothing has changed.",
                )
            }
        }
    }

    /** Someone took it on: the rest of the household is told who. */
    fun accepted(householdId: UUID, householdName: String, newOwnerName: String, newOwner: UUID) {
        recipients(jdbc, householdId, newOwner, everyone = true).forEach { other ->
            tell(
                other, householdId, "lifecycle.household.ownership_accepted", "household.accepted:$householdId:$newOwner",
                "$newOwnerName is now running $householdName",
                "They took the household on. Private records stay private.",
            )
        }
    }

    /**
     * People with a login still active in the household, other than [except].
     * Advisors are colleagues, not family; a restricted member cannot take it on.
     */
    private fun recipients(
        on: NamedParameterJdbcTemplate, householdId: UUID, except: UUID?, everyone: Boolean = false,
    ): List<UUID> = on.query(
        """
        select hm.user_id from household_memberships hm
         where hm.household_id = :hid and hm.status = 'active' and hm.user_id <> :except
           and (:everyone or hm.role in ('admin', 'editor', 'viewer'))
        """.trimIndent(),
        MapSqlParameterSource().addValue("hid", householdId)
            .addValue("except", except ?: LifecycleRecords.NOBODY).addValue("everyone", everyone),
    ) { rs, _ -> rs.getObject("user_id", UUID::class.java) }

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

    private data class Opened(
        val householdId: UUID, val ownerUserId: UUID?, val reason: String, val acceptFrom: Instant,
        val householdName: String, val ownerName: String,
    )

    private fun day(instant: Instant) =
        LocalDate.ofInstant(instant, ZoneId.of("Asia/Kolkata")).format(DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH))
}

/**
 * The sweep's side, on the OWNER connection: the rule asked before anything is
 * carried out, and the dormancy opened when it says so. Only [AccountPurge] and
 * [DepartureCompletion] construct this.
 */
internal class DormancyRecords(private val jdbc: NamedParameterJdbcTemplate) {

    /** app.going_leaves_household_ownerless: V120 is the one statement of the rule. */
    fun leavesOwnerless(householdId: UUID, userId: UUID): Boolean = jdbc.queryForObject(
        "select app.going_leaves_household_ownerless(:hid, :uid)",
        mapOf("hid" to householdId, "uid" to userId), Boolean::class.java,
    ) == true

    /** Opens the dormancy; the id when it opened one now, null when it was already open (or not needed). */
    fun open(householdId: UUID, userId: UUID, reason: String, closureId: UUID?, departureId: UUID?): UUID? =
        jdbc.queryForObject(
            "select app.open_household_dormancy(:hid, :uid, :reason, :closure, :departure, null, null)",
            MapSqlParameterSource()
                .addValue("hid", householdId).addValue("uid", userId).addValue("reason", reason)
                .addValue("closure", closureId).addValue("departure", departureId),
            UUID::class.java,
        )
}

@RestController
@RequestMapping("/api/v1/households/{householdId}/dormancy")
class DormancyController(private val service: DormancyService) {

    /** Whether nobody runs the household at the moment, and whether you may take it on. */
    @GetMapping
    fun householdDormancy(@PathVariable householdId: UUID): DormancyView = service.dormancy(householdId)

    /**
     * Take the household on as its owner. An adult member with a login (admin,
     * editor or viewer); a week after a memorial (409 dormancy_not_yet); not while
     * you are closing your account or leaving (403 ownership_not_eligible). Needs a
     * recent step-up (403 step_up_required). Opens none of the departed owner's
     * private records.
     */
    @PostMapping("/accept")
    fun takeOnDormantHousehold(@PathVariable householdId: UUID): DormancyView = service.accept(householdId)
}
