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
    /**
     * Someone the departed owner named is asked first, alone, until then; nobody
     * else may take it on before it. Who, only when it is you. Null when nobody
     * is asked first (docs/05 §12.7, V135).
     */
    val askedFirstUntil: Instant? = null,
    /** You are the successor asked first. */
    val youAreAskedFirst: Boolean = false,
    /** You may decline, which opens it to the other adults in the household. */
    val canDecline: Boolean = false,
    /**
     * Nobody took it on for 90 days after it was open to everyone, so it can no longer be
     * taken on in the app: only an operator, on a documented request, and after telling
     * everyone here (docs/05 §12.7, V147). Null otherwise.
     */
    val handedToOperatorsAt: Instant? = null,
)

/**
 * A household whose last owner goes while it holds records (docs/05 §12.7,
 * V120, V135).
 *
 * The owner's decisions: never leave the household's records unreachable
 * because of an account rule; move it to dormant, tell the members, and require
 * an explicit transfer. So the sweep asks [DormancyRecords.leavesOwnerless]
 * before it carries out a closure or a departure, and a memorial on the last
 * owner opens one in the database. While it is dormant, who is in the household
 * and with what role is frozen; everything else stays as it was, and everyone
 * keeps exactly the sight they had.
 *
 * The offer is ordered, never automated: the successor the owner named, if they
 * may take it on, is asked first and alone, for the configured window
 * ([DormancyProperties]); they accept or decline; on a decline or when the
 * window passes, every adult member with a login may, and is told then
 * ([DormancyOffers]). It ends when someone takes it on, or the owner comes back.
 * A household with nobody who may take it on stays dormant until an operator
 * repair on a documented request (V137, scripts/dormancy-repair.sh).
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
                "dormancy_successor_first" in text -> successorFirst(current.view.askedFirstUntil)
                "dormancy_routed_to_repair" in text -> handedToOperators(household)
                else -> ApiException(HttpStatus.FORBIDDEN, "ownership_not_eligible", NOT_ELIGIBLE)
            }
        }
        // The function wrote the audit line, and the dormancy ended with the role change.
        val me = households.members(householdId).firstOrNull { it.isMe }?.displayName ?: "Someone"
        notices.accepted(householdId, household.name, me, userId)
        return decide(households.get(householdId), userId).view
    }

    /**
     * The successor asked first says no. Audited in the database, and from then
     * every adult member may take it on — and is told so now. No step-up: saying
     * no gives nothing to anyone, and the successor may still take it on later
     * the way everyone else may.
     */
    @Transactional
    fun decline(householdId: UUID): DormancyView {
        val userId = userContext.require()
        val household = households.get(householdId)
        val current = decide(household, userId)
        if (!current.view.dormant) throw notDormant(household)
        if (!current.view.canDecline) throw notAsked()
        val dormancyId = try {
            jdbc.queryForObject(
                "select app.decline_household_ownership(:hid)", mapOf("hid" to householdId), UUID::class.java,
            )!!
        } catch (e: DataAccessException) {
            val text = e.mostSpecificCause.message ?: ""
            throw when {
                "dormancy_not_found" in text -> ApiException.notFound("We couldn't find that household.")
                "household_not_dormant" in text -> notDormant(household)
                else -> notAsked()
            }
        }
        notices.openedToOthers(dormancyId, jdbc, except = userId)
        return decide(households.get(householdId), userId).view
    }

    private fun notDormant(household: HouseholdRow) =
        ApiException.conflict("household_not_dormant", "${household.name} already has someone running it.")

    private fun notAsked() = ApiException(
        HttpStatus.FORBIDDEN, "dormancy_not_asked",
        "Only the person named to carry the household on can decline, while they're the one asked.",
    )

    private fun successorFirst(until: Instant?) = ApiException.conflict(
        "dormancy_successor_first",
        "The person named to carry the household on is asked first" +
            (until?.let { ", until ${day(it)}" } ?: "") + ". If they decline, you'll be told.",
    )

    private fun handedToOperators(household: HouseholdRow) = ApiException.conflict(
        "dormancy_routed_to_repair",
        "Nobody took ${household.name} on in the app for 90 days, so it can't be taken on here any more. " +
            "Almira's operators act only on a documented request, and tell everyone here before they do anything.",
    )

    private fun refusal(decision: Decision) = when {
        decision.routed != null -> ApiException.conflict("dormancy_routed_to_repair", decision.view.explanation ?: "")
        decision.waiting -> ApiException.conflict("dormancy_not_yet", WAIT_EXPLANATION)
        decision.reserved -> successorFirst(decision.view.askedFirstUntil)
        else -> ApiException(HttpStatus.FORBIDDEN, "ownership_not_eligible", decision.view.explanation ?: NOT_ELIGIBLE)
    }

    private data class Decision(
        val view: DormancyView, val waiting: Boolean, val reserved: Boolean = false, val routed: Instant? = null,
    )

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

        val order = jdbc.query(
            "select asked_first_until, you_are_asked_first from app.dormancy_order_for_me(:hid)",
            mapOf("hid" to household.id),
        ) { rs, _ -> rs.getTimestamp("asked_first_until")?.toInstant() to rs.getBoolean("you_are_asked_first") }
            .firstOrNull() ?: (null to false)
        val (askedFirstUntil, youAreAskedFirst) = order

        // Handed to operators (V147): nobody may take it on here, whoever they are.
        val routed = jdbc.queryForObject(
            "select app.dormancy_routed_to_repair(:hid)", mapOf("hid" to household.id), java.sql.Timestamp::class.java,
        )?.toInstant()
        if (routed != null) {
            return Decision(
                DormancyView(
                    dormant = true, since = open.startedAt, reason = open.reason,
                    ownerName = open.ownerName.takeIf { open.ownerUserId != null }, acceptFrom = open.acceptFrom,
                    canAccept = false,
                    explanation = "Nobody took ${household.name} on in the app for 90 days, so it can't be taken on " +
                        "here any more. Almira's operators act only on a documented request, and tell everyone here " +
                        "before they do anything. Private records stay private.",
                    handedToOperatorsAt = routed,
                ),
                waiting = false, routed = routed,
            )
        }

        val why = eligibility(household, userId, open)
        val waiting = why == null && Instant.now().isBefore(open.acceptFrom)
        val reserved = why == null && askedFirstUntil != null && !youAreAskedFirst
        val view = DormancyView(
            dormant = true,
            since = open.startedAt,
            reason = open.reason,
            // Once the purge has erased them, the row is a former member's: no name to give.
            ownerName = open.ownerName.takeIf { open.ownerUserId != null },
            acceptFrom = open.acceptFrom,
            canAccept = why == null && !waiting && !reserved,
            explanation = when {
                why != null -> why
                youAreAskedFirst && waiting -> "You were named to carry ${household.name} on, so you're asked " +
                    "first. A week after a memorial, so it can be corrected if it's wrong; then until " +
                    "${day(askedFirstUntil!!)} only you can take it on."
                waiting -> WAIT_EXPLANATION
                youAreAskedFirst -> "You were named to carry ${household.name} on, so you're asked first: until " +
                    "${day(askedFirstUntil!!)} only you can take it on. If you decline, the other adults here " +
                    "are asked. Private records stay private."
                reserved -> "The person named to carry ${household.name} on is asked first, until " +
                    "${day(askedFirstUntil!!)}. If they decline or don't answer by then, you'll be told and can " +
                    "take it on."
                else -> "Nobody runs ${household.name} at the moment. You can take it on. Private records " +
                    "stay private."
            },
            askedFirstUntil = askedFirstUntil,
            youAreAskedFirst = youAreAskedFirst,
            canDecline = youAreAskedFirst,
        )
        return Decision(view, waiting, reserved)
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

    private fun day(instant: Instant) =
        LocalDate.ofInstant(instant, ZoneId.of("Asia/Kolkata")).format(DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH))

    private companion object {
        const val NOT_ELIGIBLE =
            "An adult in the household with their own login (an admin, an editor or a viewer) can take it on."
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
     * Told when a dormancy opens. When a successor is asked first, only they
     * are told they are asked, and when; the other adults are told when the
     * offer opens to them ([openedToOthers]). Otherwise every other member with
     * a login who could take it on (admins, editors, viewers) is told now. The
     * owner whose departure is held learns it is waiting. Idempotent per dormancy.
     */
    fun opened(dormancyId: UUID, systemJdbc: NamedParameterJdbcTemplate) {
        val row = load(dormancyId, systemJdbc) ?: return

        if (row.successorUserId != null && row.successorUntil != null) {
            val from = if (Instant.now().isBefore(row.acceptFrom)) "From ${day(row.acceptFrom)} until" else "Until"
            tell(
                row.successorUserId, row.householdId, "lifecycle.household.asked_first", "household.asked_first:$dormancyId",
                "You're asked first to take on ${row.householdName}",
                "${row.who} no longer runs ${row.householdName}, and you were named to carry it on. " +
                    "$from ${day(row.successorUntil)} only you can take it on, on the Family screen, or decline. " +
                    "Nothing has been erased, and private records stay private.",
            )
        } else {
            openedToOthers(dormancyId, systemJdbc)
        }
        if (row.reason == "owner_leaving" && row.ownerUserId != null) {
            tell(
                row.ownerUserId, row.householdId, "lifecycle.household.dormant.you", "household.dormant:$dormancyId",
                "${row.householdName} is waiting for someone to take it on",
                "Nobody else runs ${row.householdName}, so what you hold there stays as it is until someone " +
                    "takes it on. You can still change your mind in Almira.",
            )
        }
    }

    /**
     * The offer is open to every adult member with a login: nobody was asked
     * first, the successor declined, or their window passed. They are told now,
     * once per dormancy.
     */
    fun openedToOthers(dormancyId: UUID, on: NamedParameterJdbcTemplate, except: UUID? = null) {
        val row = load(dormancyId, on) ?: return
        val from = if (Instant.now().isBefore(row.acceptFrom)) " from ${day(row.acceptFrom)}" else ""
        recipients(on, row.householdId, row.ownerUserId)
            .filter { it != except && it != row.successorUserIdEvenIfPassed }
            .forEach { other ->
                tell(
                    other, row.householdId, "lifecycle.household.dormant", "household.dormant:$dormancyId",
                    "${row.householdName} needs someone to run it",
                    "${row.who} no longer runs ${row.householdName}. Nothing has been erased, and private " +
                        "records stay private. An adult in the household can take it on$from, on the Family " +
                        "screen. Until then, nobody can join or be removed.",
                )
            }
    }

    private fun load(dormancyId: UUID, on: NamedParameterJdbcTemplate): Opened? = on.query(
        """
        select d.household_id, d.owner_user_id, d.reason, d.accept_from, d.successor_until, h.name,
               m.display_name, s.user_id as successor_user_id,
               (d.successor_declined_at is null and d.successor_until > now()) as successor_still_asked
          from household_dormancies d
          join households h on h.id = d.household_id
          left join members m on m.id = d.owner_member_id
          left join members s on s.id = d.successor_member_id
         where d.id = :id
        """.trimIndent(),
        mapOf("id" to dormancyId),
    ) { rs, _ ->
        val owner = rs.getObject("owner_user_id", UUID::class.java)
        val successor = rs.getObject("successor_user_id", UUID::class.java)
        val still = rs.getBoolean("successor_still_asked")
        Opened(
            householdId = rs.getObject("household_id", UUID::class.java), ownerUserId = owner,
            reason = rs.getString("reason"), acceptFrom = rs.getTimestamp("accept_from").toInstant(),
            householdName = rs.getString("name"),
            // A person erased by the purge has no name here any more (V136).
            who = if (owner != null) rs.getString("display_name") ?: "The owner" else "The person who ran it",
            successorUserId = successor.takeIf { still },
            successorUserIdEvenIfPassed = successor,
            successorUntil = rs.getTimestamp("successor_until")?.toInstant(),
        )
    }.firstOrNull()

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

    /**
     * Handed to operators (V147): everyone in the household with a login, and the
     * owner whose going made it dormant if they still have an account, are told that
     * it can no longer be taken on in the app. Once per dormancy.
     */
    fun routedToRepair(dormancyId: UUID, on: NamedParameterJdbcTemplate) {
        val row = load(dormancyId, on) ?: return
        val people = recipients(on, row.householdId, null, everyone = true) + listOfNotNull(row.ownerUserId)
        people.distinct().forEach { person ->
            tell(
                person, row.householdId, "lifecycle.household.routed_to_repair", "household.routed_to_repair:$dormancyId",
                "${row.householdName} can no longer be taken on in the app",
                "Nobody took ${row.householdName} on for 90 days, so it has been handed to Almira's operators. They " +
                    "act only on a documented request, and will tell everyone here before they do anything. " +
                    "Nothing has been erased, and private records stay private.",
            )
        }
    }

    private data class Opened(
        val householdId: UUID, val ownerUserId: UUID?, val reason: String, val acceptFrom: Instant,
        val householdName: String, val who: String, val successorUserId: UUID?,
        val successorUserIdEvenIfPassed: UUID?, val successorUntil: Instant?,
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

/**
 * The sweep's part in the order (V135): a successor's window that has passed,
 * or a successor who can no longer take it on, opens the offer to every adult
 * member, who are told then. On the OWNER connection, with no user.
 */
@Component
class DormancyOffers(
    @org.springframework.beans.factory.annotation.Qualifier("ownerDataSource") ownerDataSource: javax.sql.DataSource,
    private val notices: DormancyNotices,
) {
    private val jdbc = NamedParameterJdbcTemplate(ownerDataSource)

    /**
     * The dormancies opened to others by this run. Counted against the
     * database's clock, as the accept function counts it, not the sweep's
     * `asOf`: a window must close for the sweep exactly when it closes for
     * the person who would take the household on.
     */
    fun openExpired(): Int {
        val opened = jdbc.query(
            """
            update household_dormancies d set opened_to_others_at = now()
             where d.ended_at is null and d.opened_to_others_at is null
               and d.successor_member_id is not null
               and (d.successor_declined_at is not null or d.successor_until <= now()
                    or app.dormancy_asks_first(d.id) is null)
            returning d.id
            """.trimIndent(),
            emptyMap<String, Any>(),
        ) { rs, _ -> rs.getObject("id", UUID::class.java) }
        opened.forEach { notices.openedToOthers(it, jdbc) }
        return opened.size
    }

    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    /**
     * Owner's decision, 2026-09-15 (V147): *after 90 days with nobody accepting,
     * route to operator repair rather than leaving it claimable forever — "dormant
     * forever" should be a decision, never something reached by drift.* Stamps each
     * such dormancy, tells its household, and raises one operator alert each.
     */
    fun routeStaleToRepair(): Int {
        val routed = jdbc.query(
            "select dormancy_id, household_id, dormant_since, open_since from app.route_stale_dormancies_to_repair()",
            emptyMap<String, Any>(),
        ) { rs, _ ->
            listOf(
                rs.getObject("dormancy_id", UUID::class.java), rs.getObject("household_id", UUID::class.java),
                rs.getTimestamp("dormant_since").toInstant(), rs.getTimestamp("open_since").toInstant(),
            )
        }
        routed.forEach { (dormancyId, householdId, since, openSince) ->
            runCatching { notices.routedToRepair(dormancyId as UUID, jdbc) }
                .onFailure { log.warn("telling a household it was handed to operators failed: {}", it.javaClass.simpleName) }
            log.error(
                "{}: household {} has been dormant since {} and open to everyone since {} with nobody taking it on; " +
                    "it can no longer be taken on in the app. Decide: a documented repair " +
                    "(scripts/dormancy-repair.sh list), or leave it dormant on purpose (docs/05 §12.7).",
                ROUTED_ALERT, householdId, since, openSince,
            )
        }
        return routed.size
    }

    companion object {
        /** The operator alert's event name (docs/17 §8). Carries a household id, never a name or a record. */
        const val ROUTED_ALERT = "DORMANT HOUSEHOLD NEEDS AN OPERATOR"
    }
}

@RestController
@RequestMapping("/api/v1/households/{householdId}/dormancy")
class DormancyController(private val service: DormancyService) {

    /** Whether nobody runs the household at the moment, and whether you may take it on. */
    @GetMapping
    fun householdDormancy(@PathVariable householdId: UUID): DormancyView = service.dormancy(householdId)

    /**
     * Take the household on as its owner. An adult member with a login (admin,
     * editor or viewer); a week after a memorial (409 dormancy_not_yet); while the
     * successor the departed owner named is asked first, only them (409
     * dormancy_successor_first); not while
     * you are closing your account or leaving (403 ownership_not_eligible). Needs a
     * recent step-up (403 step_up_required). Opens none of the departed owner's
     * private records.
     */
    @PostMapping("/accept")
    fun takeOnDormantHousehold(@PathVariable householdId: UUID): DormancyView = service.accept(householdId)

    /**
     * Decline, as the successor the departed owner named, while you are the one
     * asked first (403 dormancy_not_asked otherwise). Every adult member may then
     * take it on, and is told. Audited.
     */
    @PostMapping("/decline")
    fun declineDormantHousehold(@PathVariable householdId: UUID): DormancyView = service.decline(householdId)
}
