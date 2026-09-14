package tech.bhrigu.almira.lifecycle

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.auth.StepUpService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.household.HouseholdRow
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.reminder.Notifier
import tech.bhrigu.almira.reminder.OutboundNotification
import tech.bhrigu.almira.security.RequestUserContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

data class StartDepartureBody(
    /** Someone else, when an admin asks them to leave. Omit to leave yourself. */
    val memberId: UUID? = null,
    /** take | export_and_erase. Only the person leaving chooses; defaults to take. */
    val privateRecords: String? = null,
)

data class JointDecisionInput(val recordType: String, val recordId: UUID, val decision: String)

data class UpdateDepartureBody(
    val privateRecords: String? = null,
    val decisions: List<@Valid JointDecisionInput>? = null,
)

data class JointRecordLine(
    val recordType: String,
    val recordId: UUID,
    val title: String,
    val otherHolders: List<String>,
    /** stays | take_my_share, or null while undecided (which means stays). */
    val decision: String?,
)

data class DeparturePreview(
    val householdId: UUID,
    val householdName: String,
    /** What is solely yours. It goes with you, or is erased once you have your copy. */
    val goesWithYou: List<LifecycleLine>,
    /** What stays: records you hold with someone, and the household itself. */
    val staysWithHousehold: List<LifecycleLine>,
    /** Joint records, each needing a decision. Only ever the leaver's own. */
    val joint: List<JointRecordLine>,
    val blockers: List<LifecycleBlocker>,
    /** Sealed fields on records that would move. They cannot be re-sealed by the server. */
    val sealedFieldsThatStayBehind: Int,
    val waitDays: Int,
)

data class DepartureView(
    val id: UUID,
    val memberId: UUID,
    val memberName: String?,
    val isMe: Boolean,
    val startedByAdmin: Boolean,
    val requestedAt: Instant,
    val effectiveAt: Instant,
    /** pending | cancelled | completed */
    val status: String,
    /** The leaver's choice. Null for anyone else: it is not the household's business. */
    val privateRecords: String?,
    /** The leaver's own decisions. Empty for anyone else. */
    val decisions: List<JointDecisionInput>,
    val canCancel: Boolean,
)

/**
 * Leaving a household (docs/05 §3.5, §8, §12).
 *
 * Anyone can leave, and an admin can ask someone to. Either way nothing moves for
 * seven days, and both sides are told in words that say who and when and nothing
 * else. What is solely the person's goes with them into a household of their
 * own, or is erased once they have downloaded their copy — their choice, and only
 * theirs. Records they hold with someone else are listed for them to decide
 * about; the decision is visible to the other holders, who can already see the
 * record, and to nobody else. An admin who asked them to go never sees what goes.
 *
 * This service only records the intention. [DepartureCompletion] carries it out
 * when the seven days are up.
 */
@Service
class DepartureService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val stepUp: StepUpService,
    private val audit: AuditService,
    private val notifiers: List<Notifier>,
    private val userContext: RequestUserContext,
) {
    private val records = LifecycleRecords(jdbc)

    @Transactional(readOnly = true)
    fun departurePreview(householdId: UUID): DeparturePreview {
        val userId = userContext.require()
        val household = households.get(householdId)
        val mine = records.memberIds(householdId, userId)
        val sole = records.solelyHeld(householdId, userId, mine)
        val documents = records.documentsFollowing(householdId, userId, sole)
        val pending = pendingFor(householdId, userId)
        val decided = pending?.let { decisionsOf(it) }?.associateBy { it.recordType to it.recordId } ?: emptyMap()

        val joint = records.jointlyHeld(householdId, mine).map {
            JointRecordLine(
                it.type, it.id, it.title,
                records.otherHolders(it.type, it.id, mine), decided[it.type to it.id]?.decision,
            )
        }
        val stays = joint.map {
            LifecycleLine(
                householdId, household.name, AccountClosureService.lineKind(it.recordType), it.recordId, it.title,
                "Stays with ${it.otherHolders.joinToString(" and ")}. You can take a copy of your part.",
            )
        } + LifecycleLine(
            householdId, household.name, "household", householdId, household.name,
            "Everything else stays: what other people hold, and what you added for them.",
        )

        val sealed = if (sole.isEmpty()) 0 else jdbc.queryForObject(
            "select count(*) from sealed_values where record_id in (:ids)",
            mapOf("ids" to sole.map { it.id }), Int::class.java,
        ) ?: 0

        return DeparturePreview(
            householdId = householdId,
            householdName = household.name,
            goesWithYou = sole.map { LifecycleLine(householdId, household.name, AccountClosureService.lineKind(it.type), it.id, it.title) } +
                documents.map { LifecycleLine(householdId, household.name, "document", it.id, it.title) },
            staysWithHousehold = stays,
            joint = joint,
            blockers = blockers(household, userId),
            sealedFieldsThatStayBehind = sealed,
            waitDays = WAIT.toDays().toInt(),
        )
    }

    @Transactional(readOnly = true)
    fun listDepartures(householdId: UUID): List<DepartureView> {
        households.get(householdId)
        return views("where d.household_id = :hid", mapOf("hid" to householdId))
    }

    @Transactional
    fun startDeparture(householdId: UUID, memberId: UUID?, privateRecords: String?): DepartureView {
        val userId = userContext.require()
        val household = households.get(householdId)
        val members = households.members(householdId)
        val me = members.firstOrNull { it.isMe }
            ?: throw ApiException.badRequest("no_member", "You aren't a person in this household yet.")
        val target = memberId?.let { id -> members.firstOrNull { it.id == id } ?: throw ApiException.notFound("We couldn't find that person.") } ?: me
        val self = target.id == me.id
        privateRecords?.let(::requireChoice)

        if (!self) {
            if (household.myRole !in setOf("owner", "admin")) {
                throw ApiException.forbidden("Only the household owner or an admin can ask someone to leave.")
            }
            if (target.userId == null) {
                throw ApiException.badRequest(
                    "member_has_no_login",
                    "${target.displayName} doesn't sign in. Remove them from the household instead.",
                )
            }
            if (target.role == "owner") {
                throw ApiException.forbidden("The household owner can't be asked to leave.")
            }
            if (privateRecords != null) {
                throw ApiException.badRequest(
                    "not_your_choice", "Only ${target.displayName} decides what happens to what is theirs.",
                )
            }
        } else {
            blockers(household, userId).firstOrNull()?.let {
                throw ApiException.conflict("departure_blocked", it.message, mapOf("blockers" to listOf(it)))
            }
        }

        val leaverUserId = target.userId!!
        pendingFor(householdId, leaverUserId)?.let { return view(it) }
        stepUp.requireElevated(userId, userContext.currentSessionId())

        val id = UUID.randomUUID()
        val now = Instant.now()
        val effectiveAt = now.plus(WAIT)
        jdbc.update(
            """
            insert into household_departures (id, household_id, member_id, user_id, started_by, started_by_admin,
                                              private_records, requested_at, effective_at)
            values (:id, :hid, :mid, :uid, :by, :byAdmin, :choice, :now, :effective)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id).addValue("hid", householdId).addValue("mid", target.id)
                .addValue("uid", leaverUserId).addValue("by", userId).addValue("byAdmin", !self)
                .addValue("choice", privateRecords ?: "take")
                .addValue("now", java.sql.Timestamp.from(now))
                .addValue("effective", java.sql.Timestamp.from(effectiveAt)),
        )
        audit.record(
            householdId = householdId, actorUserId = userId, action = "household.departure.start",
            entityType = "member", entityId = target.id,
            diff = mapOf("startedByAdmin" to !self, "effectiveAt" to effectiveAt.toString()),
        )

        val date = day(effectiveAt)
        if (!self) {
            tell(
                householdId, listOf(leaverUserId), "lifecycle.departure.asked", "departure.asked:$id",
                "You've been asked to leave ${household.name}",
                "On $date what is yours goes with you into a household of your own. Until then you can " +
                    "choose what happens to it, and download your copy.",
            )
        }
        tell(
            householdId, others(householdId, leaverUserId), "lifecycle.departure.started", "departure.started:$id",
            "${target.displayName} is leaving ${household.name}",
            "${target.displayName} leaves the household on $date. Anything you hold together stays with you.",
        )
        return view(id)
    }

    @Transactional
    fun updateDeparture(householdId: UUID, departureId: UUID, privateRecords: String?, decisions: List<JointDecisionInput>?): DepartureView {
        val userId = userContext.require()
        households.get(householdId)
        val current = view(departureId).takeIf { it.status == "pending" && householdOf(departureId) == householdId }
            ?: throw ApiException.notFound()
        if (!current.isMe) throw ApiException.forbidden("Only the person leaving decides this.")

        privateRecords?.let {
            requireChoice(it)
            jdbc.update(
                "update household_departures set private_records = :choice where id = :id",
                mapOf("choice" to it, "id" to departureId),
            )
        }
        val choice = privateRecords ?: current.privateRecords
        decisions?.let { inputs ->
            val mine = records.memberIds(householdId, userId)
            val joint = records.jointlyHeld(householdId, mine).map { it.type to it.id }.toSet()
            inputs.forEach { input ->
                if ((input.recordType to input.recordId) !in joint) throw ApiException.notFound()
                if (input.decision !in DECISIONS) {
                    throw ApiException.badRequest("decision_invalid", "Choose to leave it, or to take a copy of your part.")
                }
            }
            jdbc.update("delete from departure_joint_decisions where departure_id = :id", mapOf("id" to departureId))
            inputs.forEach { input ->
                jdbc.update(
                    """
                    insert into departure_joint_decisions (departure_id, record_type, record_id, decision)
                    values (:id, :type, :rid, :decision)
                    """.trimIndent(),
                    mapOf("id" to departureId, "type" to input.recordType, "rid" to input.recordId, "decision" to input.decision),
                )
            }
        }
        if (choice == "export_and_erase") {
            // A copy of your part goes into a household of your own; there is none
            // when you have chosen to erase rather than take.
            jdbc.update(
                "update departure_joint_decisions set decision = 'stays' where departure_id = :id",
                mapOf("id" to departureId),
            )
        }
        audit.record(
            householdId = householdId, actorUserId = userId, action = "household.departure.update",
            entityType = "household_departure", entityId = departureId,
            diff = mapOf("privateRecords" to choice, "decisions" to (decisions?.size)),
        )
        return view(departureId)
    }

    @Transactional
    fun cancelDeparture(householdId: UUID, departureId: UUID): DepartureView {
        val userId = userContext.require()
        val household = households.get(householdId)
        val current = view(departureId).takeIf { householdOf(departureId) == householdId } ?: throw ApiException.notFound()
        if (current.status != "pending") return current
        if (!current.canCancel) {
            throw ApiException.forbidden(
                if (current.startedByAdmin) "An admin asked for this, so an admin can withdraw it."
                else "Only the person leaving can change their mind.",
            )
        }
        jdbc.update("update household_departures set cancelled_at = now() where id = :id", mapOf("id" to departureId))
        audit.record(
            householdId = householdId, actorUserId = userId, action = "household.departure.cancel",
            entityType = "household_departure", entityId = departureId,
        )
        tell(
            householdId, others(householdId, null), "lifecycle.departure.cancelled", "departure.cancelled:$departureId",
            "${current.memberName} is staying in ${household.name}", "Nothing has changed.",
        )
        return view(departureId)
    }

    // --- helpers --------------------------------------------------------------

    private fun blockers(household: HouseholdRow, userId: UUID): List<LifecycleBlocker> {
        if (records.otherPeopleWithLogin(household.id, userId) == 0) {
            return listOf(
                LifecycleBlocker(
                    household.id, household.name, "nobody_else_here",
                    "Nobody else signs in to ${household.name}, so there is nobody to leave it with. " +
                        "To end it, close your account instead.",
                ),
            )
        }
        if (household.myRole != "owner") return emptyList()
        return when (records.nextOwner(household.id, userId)) {
            LifecycleRecords.Handover.NobodyChosen ->
                listOf(
                    LifecycleBlocker(
                        household.id, household.name, "owner_needs_successor",
                        "You're the only one who runs ${household.name}. Name who carries it on first.",
                    ),
                )
            else -> emptyList()
        }
    }

    private fun pendingFor(householdId: UUID, userId: UUID): UUID? = jdbc.query(
        """
        select id from household_departures
         where household_id = :hid and user_id = :uid and cancelled_at is null and completed_at is null
        """.trimIndent(),
        mapOf("hid" to householdId, "uid" to userId),
    ) { rs, _ -> rs.getObject("id", UUID::class.java) }.firstOrNull()

    private fun householdOf(departureId: UUID): UUID? = jdbc.query(
        "select household_id from household_departures where id = :id", mapOf("id" to departureId),
    ) { rs, _ -> rs.getObject("household_id", UUID::class.java) }.firstOrNull()

    private fun decisionsOf(departureId: UUID) = jdbc.query(
        "select record_type, record_id, decision from departure_joint_decisions where departure_id = :id",
        mapOf("id" to departureId),
    ) { rs, _ -> JointDecisionInput(rs.getString("record_type"), rs.getObject("record_id", UUID::class.java), rs.getString("decision")) }

    private fun view(id: UUID): DepartureView =
        views("where d.id = :id", mapOf("id" to id)).firstOrNull() ?: throw ApiException.notFound()

    private fun views(where: String, params: Map<String, Any?>): List<DepartureView> {
        val userId = userContext.require()
        return jdbc.query(
            """
            select d.*, m.display_name, app.can_administer_household(d.household_id) as i_administer
              from household_departures d
              left join members m on m.id = d.member_id
            $where
             order by d.requested_at desc
            """.trimIndent(),
            params,
        ) { rs, _ ->
            val id = rs.getObject("id", UUID::class.java)
            val isMe = rs.getObject("user_id", UUID::class.java) == userId
            val byAdmin = rs.getBoolean("started_by_admin")
            val status = when {
                rs.getTimestamp("cancelled_at") != null -> "cancelled"
                rs.getTimestamp("completed_at") != null -> "completed"
                else -> "pending"
            }
            DepartureView(
                id = id,
                memberId = rs.getObject("member_id", UUID::class.java),
                memberName = rs.getString("display_name"),
                isMe = isMe,
                startedByAdmin = byAdmin,
                requestedAt = rs.getTimestamp("requested_at").toInstant(),
                effectiveAt = rs.getTimestamp("effective_at").toInstant(),
                status = status,
                privateRecords = if (isMe) rs.getString("private_records") else null,
                decisions = emptyList(),
                // Whoever started it: the person, or any admin other than the person
                // (V41's trigger holds the same line in the database).
                canCancel = status == "pending" &&
                    (if (byAdmin) !isMe && rs.getBoolean("i_administer") else isMe),
            )
        }.map { if (it.isMe) it.copy(decisions = decisionsOf(it.id)) else it }
    }

    private fun others(householdId: UUID, except: UUID?): List<UUID> =
        households.members(householdId).mapNotNull { it.userId }.filter { it != except }

    private fun tell(householdId: UUID, userIds: List<UUID>, template: String, key: String, title: String, body: String) {
        userIds.forEach { recipient ->
            notifiers.forEach { notifier ->
                runCatching {
                    notifier.deliver(
                        OutboundNotification(
                            userId = recipient, householdId = householdId, reminderId = null,
                            template = template, title = title, body = body, idempotencyKey = "$key:$recipient",
                        ),
                    )
                }
            }
        }
    }

    private fun requireChoice(value: String) {
        if (value !in CHOICES) {
            throw ApiException.badRequest(
                "private_records_invalid",
                "Choose to take your records with you, or to download them and have them erased.",
            )
        }
    }

    private fun day(instant: Instant) =
        LocalDate.ofInstant(instant, ZoneId.of("Asia/Kolkata")).format(DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH))

    companion object {
        /** On the screen as "7 days". The database refuses anything shorter (V41). */
        val WAIT: Duration = Duration.ofDays(7)
        val CHOICES = setOf("take", "export_and_erase")
        val DECISIONS = setOf("stays", "take_my_share")
    }
}

@RestController
@RequestMapping("/api/v1/households/{householdId}/departures")
class DepartureController(private val service: DepartureService) {

    @GetMapping
    fun listDepartures(@PathVariable householdId: UUID): List<DepartureView> = service.listDepartures(householdId)

    /** What would go with you and what would stay, if you left. Changes nothing. */
    @GetMapping("/preview")
    fun departurePreview(@PathVariable householdId: UUID): DeparturePreview = service.departurePreview(householdId)

    /**
     * Leave, or (as an admin, with `memberId`) ask someone to. Seven days, then it
     * happens. Needs a recent step-up (403 step_up_required).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun startDeparture(
        @PathVariable householdId: UUID,
        @RequestBody(required = false) body: StartDepartureBody?,
    ): DepartureView = service.startDeparture(householdId, body?.memberId, body?.privateRecords)

    /** The leaver's choices: what happens to what is theirs, and to each joint record. */
    @PatchMapping("/{departureId}")
    fun updateDeparture(
        @PathVariable householdId: UUID,
        @PathVariable departureId: UUID,
        @RequestBody @Valid body: UpdateDepartureBody,
    ): DepartureView = service.updateDeparture(householdId, departureId, body.privateRecords, body.decisions)

    /** Undo, any time in the seven days. Whoever started it withdraws it. */
    @PostMapping("/{departureId}/cancel")
    fun cancelDeparture(@PathVariable householdId: UUID, @PathVariable departureId: UUID): DepartureView =
        service.cancelDeparture(householdId, departureId)
}
