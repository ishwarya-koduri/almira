package tech.bhrigu.almira.continuity

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.security.RequestUserContext
import tech.bhrigu.almira.sharing.ShareService
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

data class StartHeirPlanBody(
    /** passed_away | cannot_manage */
    val situation: String,
)

data class HeirTaskStatusBody(
    /** todo | done | later */
    val status: String,
)

data class AssignHeirTaskBody(
    /** Who is doing it. Absent or null: nobody but you. */
    val helperId: UUID? = null,
)

data class AddHeirHelperBody(
    @field:Size(max = 80) val name: String,
    @field:Size(max = 40) val relationship: String? = null,
)

/**
 * One thing to do. Deliberately without an amount: heir mode never shows a
 * value or a total (X-40) — nobody arranging a funeral needs to be told what
 * the estate is worth on the same screen as what to do next.
 */
data class HeirTask(
    val id: UUID,
    val key: String,
    val recordType: String?,
    val recordId: UUID?,
    val title: String,
    val why: String,
    val steps: List<TransmissionStep>,
    /** The institution or person to begin with, when there is one. */
    val whereToStart: String?,
    /** todo | done | later */
    val status: String,
    val doneAt: Instant?,
    val helperId: UUID?,
    val helperName: String?,
)

data class HeirHelper(
    val id: UUID,
    val name: String,
    val relationship: String?,
    val taskCount: Int,
    val addedAt: Instant,
    /** Returned once, when the helper is added. Never stored. */
    val url: String? = null,
)

data class HeirPlan(
    val id: UUID,
    val emergencyRequestId: UUID,
    val subjectName: String?,
    val situation: String,
    val paused: Boolean,
    val pausedAt: Instant?,
    val tasks: List<HeirTask>,
    val helpers: List<HeirHelper>,
    val doneCount: Int,
    val taskCount: Int,
    /** The task to show next: the first not done, "later" ones after the rest. Null when all are done. */
    val nextTaskId: UUID?,
    val maxHelpers: Int,
    val windowClosesAt: Instant,
)

/** What a relative sees from their link: their tasks, and nothing else. */
data class HeirHelperView(
    val helperName: String,
    val sharedBy: String,
    val subjectName: String?,
    val tasks: List<HeirTask>,
    val expiresAt: Instant,
    val note: String = HELPER_NOTE,
)

private const val HELPER_NOTE =
    "Only the tasks handed to you, read-only. The person who sent this can see when it is opened, and can " +
        "turn it off."

/**
 * Heir mode (X-40, docs/03 §8.3).
 *
 * The person holding an open emergency window gets the family plan as a list of
 * tasks, one per screen: get the certificates, find the will, claim this, tell
 * that lender. They can stop at any point and come back to the same place, and
 * hand a task to up to five relatives by a link of its own.
 *
 * Everything is read under the reader's own row-level security. A task names a
 * record by id; its words — the title, the institution, how to claim — are
 * worked out on each read, so the plan can never say more than the window
 * shows, and says nothing at all once the window shuts (V90).
 */
@Service
class HeirModeService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val emergency: EmergencyService,
    private val transmission: TransmissionService,
    private val shares: ShareService,
    private val audit: AuditService,
    private val userContext: RequestUserContext,
    transactions: TransactionTemplate,
) {

    private val readOnly = TransactionTemplate(transactions.transactionManager!!).apply { isReadOnly = true }

    @Transactional
    fun start(householdId: UUID, requestId: UUID, situation: String): HeirPlan {
        val userId = userContext.require()
        if (situation !in SITUATIONS) {
            throw ApiException.badRequest("situation_invalid", "Choose what has happened.")
        }
        val request = openRequest(householdId, requestId)

        val existing = planRow(householdId, requestId)
        val planId = if (existing == null) {
            val id = UUID.randomUUID()
            jdbc.update(
                """
                insert into heir_plans (id, household_id, emergency_request_id, subject_member_id, situation, created_by)
                values (:id, :hid, :rid, :subject, :situation, :me)
                on conflict (emergency_request_id) do nothing
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("id", id).addValue("hid", householdId).addValue("rid", requestId)
                    .addValue("subject", request.subjectMemberId).addValue("situation", situation)
                    .addValue("me", userId),
            )
            audit.record(
                householdId = householdId, actorUserId = userId, action = "heir.plan.start",
                entityType = "emergency_request", entityId = requestId, diff = mapOf("situation" to situation),
            )
            planRow(householdId, requestId)?.id ?: throw ApiException.notFound()
        } else {
            if (existing.situation != situation) {
                jdbc.update(
                    "update heir_plans set situation = :situation where id = :id",
                    mapOf("situation" to situation, "id" to existing.id),
                )
            }
            existing.id
        }
        sync(householdId, planId, request.subjectMemberId, situation)
        return view(householdId, requestId)
    }

    @Transactional(readOnly = true)
    fun get(householdId: UUID, requestId: UUID): HeirPlan {
        households.get(householdId)
        return view(householdId, requestId)
    }

    /** "You can stop here." Coming back clears it; nothing else changes. */
    @Transactional
    fun pause(householdId: UUID, requestId: UUID, paused: Boolean): HeirPlan {
        val plan = requirePlan(householdId, requestId)
        jdbc.update(
            "update heir_plans set paused_at = ${if (paused) "now()" else "null"} where id = :id",
            mapOf("id" to plan.id),
        )
        return view(householdId, requestId)
    }

    @Transactional
    fun setStatus(householdId: UUID, requestId: UUID, taskId: UUID, status: String): HeirPlan {
        if (status !in STATUSES) {
            throw ApiException.badRequest("status_invalid", "A task is to do, done, or for later.")
        }
        val plan = requirePlan(householdId, requestId)
        val changed = jdbc.update(
            """
            update heir_tasks
            set status = :status, done_at = case when :status = 'done' then coalesce(done_at, now()) else null end
            where id = :task and plan_id = :plan
            """.trimIndent(),
            mapOf("status" to status, "task" to taskId, "plan" to plan.id),
        )
        if (changed == 0) throw ApiException.notFound()
        return view(householdId, requestId)
    }

    @Transactional
    fun assign(householdId: UUID, requestId: UUID, taskId: UUID, helperId: UUID?): HeirPlan {
        val userId = userContext.require()
        val plan = requirePlan(householdId, requestId)
        val before = jdbc.query(
            "select helper_id from heir_tasks where id = :task and plan_id = :plan",
            mapOf("task" to taskId, "plan" to plan.id),
        ) { rs, _ -> rs.getObject("helper_id", UUID::class.java) }
        if (before.isEmpty()) throw ApiException.notFound()
        if (helperId != null && helpers(plan.id).none { it.id == helperId }) {
            throw ApiException.notFound("We couldn't find that person.")
        }
        jdbc.update(
            "update heir_tasks set helper_id = :helper where id = :task and plan_id = :plan",
            MapSqlParameterSource().addValue("helper", helperId).addValue("task", taskId).addValue("plan", plan.id),
        )
        listOfNotNull(before.single(), helperId).distinct().forEach { refreshLink(plan.id, it) }
        audit.record(
            householdId = householdId, actorUserId = userId, action = "heir.task.assign",
            entityType = "heir_task", entityId = taskId, diff = mapOf("helperId" to helperId?.toString()),
        )
        return view(householdId, requestId)
    }

    /**
     * Up to five people. Each gets a link that shows the tasks handed to them
     * and nothing else, ending when the window does.
     */
    @Transactional
    fun addHelper(householdId: UUID, requestId: UUID, input: AddHeirHelperBody, baseUrl: String): HeirHelper {
        val userId = userContext.require()
        val name = input.name.trim()
        if (name.isEmpty() || name.length > 80) {
            throw ApiException.badRequest("name_required", "Who is helping? A first name is enough.")
        }
        val relationship = input.relationship?.trim()?.takeIf { it.isNotEmpty() }
        val plan = requirePlan(householdId, requestId)
        // One at a time per plan, so two quick taps cannot make a sixth.
        jdbc.query("select id from heir_plans where id = :id for update", mapOf("id" to plan.id)) { _, _ -> }
        if (helpers(plan.id).size >= MAX_HELPERS) {
            throw ApiException.badRequest(
                "helpers_full", "You can share tasks with up to five people. Remove someone to add another.",
            )
        }
        val request = emergency.get(householdId, requestId)
        val link = shares.issueHelperLink(
            householdId, "Helping ${request.subjectName ?: "the family"}: $name", request.accessExpiresAt, baseUrl,
        )
        val id = UUID.randomUUID()
        try {
            jdbc.update(
                """
                insert into heir_helpers (id, plan_id, household_id, name, relationship, share_id)
                values (:id, :plan, :hid, :name, :relationship, :share)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("id", id).addValue("plan", plan.id).addValue("hid", householdId)
                    .addValue("name", name).addValue("relationship", relationship).addValue("share", link.shareId),
            )
        } catch (full: DataIntegrityViolationException) {
            throw ApiException.badRequest(
                "helpers_full", "You can share tasks with up to five people. Remove someone to add another.",
            )
        }
        audit.record(
            householdId = householdId, actorUserId = userId, action = "heir.helper.add",
            entityType = "guest_share", entityId = link.shareId,
        )
        return helpers(plan.id).first { it.id == id }.copy(url = link.url)
    }

    /** Their link stops working at once, and their tasks come back to you. */
    @Transactional
    fun removeHelper(householdId: UUID, requestId: UUID, helperId: UUID): HeirPlan {
        val userId = userContext.require()
        val plan = requirePlan(householdId, requestId)
        val shareId = jdbc.query(
            "select share_id from heir_helpers where id = :id and plan_id = :plan and removed_at is null",
            mapOf("id" to helperId, "plan" to plan.id),
        ) { rs, _ -> rs.getObject("share_id", UUID::class.java) }.firstOrNull() ?: throw ApiException.notFound()
        jdbc.update("update heir_tasks set helper_id = null where helper_id = :id", mapOf("id" to helperId))
        jdbc.update("update heir_helpers set removed_at = now() where id = :id", mapOf("id" to helperId))
        shares.withdraw(householdId, listOf(shareId))
        audit.record(
            householdId = householdId, actorUserId = userId, action = "heir.helper.remove",
            entityType = "guest_share", entityId = shareId,
        )
        return view(householdId, requestId)
    }

    /**
     * A relative opening their link. Admitted like any guest link — same
     * checks, view count and audit — then read inside that link's guest
     * session, where the policies narrow the tasks to theirs and the records to
     * the ones those tasks name.
     */
    fun helperView(token: String, ipHash: String?, userAgent: String?): HeirHelperView {
        val link = shares.admitHelperLink(token, ipHash, userAgent)
        return userContext.runAs(link.createdBy, guestShareId = link.shareId) {
            readOnly.execute {
                val helper = jdbc.query(
                    "select id, name, plan_id from heir_helpers where share_id = :share and removed_at is null",
                    mapOf("share" to link.shareId),
                ) { rs, _ -> Triple(rs.getObject("id", UUID::class.java), rs.getString("name"), rs.getObject("plan_id", UUID::class.java)) }
                    .firstOrNull() ?: throw ApiException.notFound("That link doesn't work. It may have expired or been withdrawn.")
                val plan = jdbc.query(
                    """
                    select p.situation, m.display_name as subject_name
                    from heir_plans p left join members m on m.id = p.subject_member_id
                    where p.id = :id
                    """.trimIndent(),
                    mapOf("id" to helper.third),
                ) { rs, _ -> rs.getString("situation") to rs.getString("subject_name") }
                    .firstOrNull() ?: throw ApiException.notFound("That link doesn't work. It may have expired or been withdrawn.")
                HeirHelperView(
                    helperName = helper.second,
                    sharedBy = link.sharedBy,
                    subjectName = plan.second,
                    tasks = tasks(link.householdId, helper.third)
                        .filter { it.helperId == helper.first }
                        .map { it.copy(helperId = null, helperName = null) },
                    expiresAt = link.expiresAt,
                )
            }!!
        }
    }

    // --- building the list ------------------------------------------------------

    private data class PlanRow(val id: UUID, val situation: String, val pausedAt: Instant?)

    private fun planRow(householdId: UUID, requestId: UUID): PlanRow? = jdbc.query(
        "select id, situation, paused_at from heir_plans where household_id = :hid and emergency_request_id = :rid",
        mapOf("hid" to householdId, "rid" to requestId),
    ) { rs, _ -> PlanRow(rs.getObject("id", UUID::class.java), rs.getString("situation"), rs.getTimestamp("paused_at")?.toInstant()) }
        .firstOrNull()

    private fun requirePlan(householdId: UUID, requestId: UUID): PlanRow {
        userContext.require()
        households.get(householdId)
        return planRow(householdId, requestId) ?: throw ApiException.notFound()
    }

    /** Yours, and open — otherwise it does not exist as far as this caller is concerned. */
    private fun openRequest(householdId: UUID, requestId: UUID): EmergencyRequestRow {
        households.get(householdId)
        val request = emergency.get(householdId, requestId)
        if (!request.requestedByMe || request.status != "open") throw ApiException.notFound()
        return request
    }

    /**
     * Brings the stored list in line with what the window shows now: new
     * records get a task, a task no longer wanted goes unless it was done, and
     * the order is the order of [HeirTasks.plan].
     */
    private fun sync(householdId: UUID, planId: UUID, subjectMemberId: UUID, situation: String) {
        val wanted = HeirTasks.plan(situation, sources(householdId, subjectMemberId))
        val stored = jdbc.query(
            "select id, task_key, record_id, status from heir_tasks where plan_id = :plan",
            mapOf("plan" to planId),
        ) { rs, _ ->
            Triple(rs.getObject("id", UUID::class.java), rs.getString("task_key") to rs.getObject("record_id", UUID::class.java), rs.getString("status"))
        }
        val wantedKeys = wanted.map { it.key to it.recordId }.toSet()
        stored.filter { it.second !in wantedKeys && it.third != "done" }.forEach {
            jdbc.update("delete from heir_tasks where id = :id", mapOf("id" to it.first))
        }
        wanted.forEachIndexed { index, task ->
            jdbc.update(
                """
                insert into heir_tasks (plan_id, household_id, task_key, record_type, record_id, sort)
                values (:plan, :hid, :key, :type, :record, :sort)
                on conflict (plan_id, task_key, coalesce(record_id, '00000000-0000-0000-0000-000000000000'::uuid))
                  do update set sort = excluded.sort
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("plan", planId).addValue("hid", householdId).addValue("key", task.key)
                    .addValue("type", task.recordType).addValue("record", task.recordId).addValue("sort", index),
            )
        }
    }

    /** What the caller can see of the person's own things, read under their RLS. */
    private fun sources(householdId: UUID, subjectMemberId: UUID): HeirTasks.Sources {
        val params = mapOf("hid" to householdId, "subject" to subjectMemberId)
        val holdings = jdbc.query(
            """
            select i.id from investments i
            join investment_types t on t.id = i.type_id
            join asset_categories c on c.id = t.category_id
            where i.household_id = :hid and i.deleted_at is null
              and i.status in ('active','matured') and i.is_in_continuity
              and exists (select 1 from investment_ownerships o
                          where o.investment_id = i.id and o.member_id = :subject)
            order by c.sort, lower(i.title)
            """.trimIndent(),
            params,
        ) { rs, _ -> rs.getObject("id", UUID::class.java) }
        val insured = jdbc.query(
            """
            select i.id from investments i
            join investment_types t on t.id = i.type_id
            join asset_categories c on c.id = t.category_id
            where i.id in (:ids) and c.code = 'insurance'
            """.trimIndent(),
            mapOf("ids" to holdings.ifEmpty { listOf(UUID(0, 0)) }),
        ) { rs, _ -> rs.getObject("id", UUID::class.java) }.toSet()
        val debts = jdbc.query(
            """
            select l.id from liabilities l
            where l.household_id = :hid and l.deleted_at is null and l.status = 'active'
              and exists (select 1 from liability_holders h where h.liability_id = l.id and h.member_id = :subject)
            order by lower(l.title)
            """.trimIndent(),
            params,
        ) { rs, _ -> rs.getObject("id", UUID::class.java) }
        val paperwork = jdbc.query(
            """
            select e.id from estate_documents e
            where e.household_id = :hid and e.member_id = :subject
              and e.deleted_at is null and e.status = 'executed'
            order by e.executed_on desc nulls last
            """.trimIndent(),
            params,
        ) { rs, _ -> rs.getObject("id", UUID::class.java) }
        return HeirTasks.Sources(holdings = holdings, insured = insured, debts = debts, paperwork = paperwork)
    }

    private fun view(householdId: UUID, requestId: UUID): HeirPlan {
        val request = emergency.get(householdId, requestId)
        val plan = planRow(householdId, requestId) ?: throw ApiException.notFound()
        val tasks = tasks(householdId, plan.id)
        val remaining = tasks.filter { it.status == "todo" } + tasks.filter { it.status == "later" }
        return HeirPlan(
            id = plan.id,
            emergencyRequestId = requestId,
            subjectName = request.subjectName,
            situation = plan.situation,
            paused = plan.pausedAt != null,
            pausedAt = plan.pausedAt,
            tasks = tasks,
            helpers = helpers(plan.id),
            doneCount = tasks.count { it.status == "done" },
            taskCount = tasks.size,
            nextTaskId = remaining.firstOrNull()?.id,
            maxHelpers = MAX_HELPERS,
            windowClosesAt = request.accessExpiresAt,
        )
    }

    private fun helpers(planId: UUID): List<HeirHelper> = jdbc.query(
        """
        select h.id, h.name, h.relationship, h.created_at,
               (select count(*) from heir_tasks t where t.helper_id = h.id) as task_count
        from heir_helpers h
        where h.plan_id = :plan and h.removed_at is null
        order by h.created_at
        """.trimIndent(),
        mapOf("plan" to planId),
    ) { rs, _ ->
        HeirHelper(
            id = rs.getObject("id", UUID::class.java),
            name = rs.getString("name"),
            relationship = rs.getString("relationship"),
            taskCount = rs.getInt("task_count"),
            addedAt = rs.getTimestamp("created_at").toInstant(),
        )
    }

    /** The helper's link names exactly the records of the tasks handed to them. */
    private fun refreshLink(planId: UUID, helperId: UUID) {
        val shareId = jdbc.query(
            "select share_id from heir_helpers where id = :id and plan_id = :plan",
            mapOf("id" to helperId, "plan" to planId),
        ) { rs, _ -> rs.getObject("share_id", UUID::class.java) }.firstOrNull() ?: return
        val items = jdbc.query(
            "select record_type, record_id from heir_tasks where helper_id = :id and record_id is not null",
            mapOf("id" to helperId),
        ) { rs, _ -> rs.getString("record_type") to rs.getObject("record_id", UUID::class.java) }
        shares.replaceItems(shareId, items)
    }

    /**
     * The stored tasks with their words. A task whose record the caller can no
     * longer see is left out rather than shown blank.
     */
    private fun tasks(householdId: UUID, planId: UUID): List<HeirTask> {
        val rows = jdbc.query(
            """
            select t.id, t.task_key, t.record_type, t.record_id, t.status, t.done_at, t.helper_id,
                   h.name as helper_name
            from heir_tasks t
            left join heir_helpers h on h.id = t.helper_id and h.removed_at is null
            where t.plan_id = :plan
            order by t.sort
            """.trimIndent(),
            mapOf("plan" to planId),
        ) { rs, _ ->
            HeirTasks.Stored(
                id = rs.getObject("id", UUID::class.java),
                key = rs.getString("task_key"),
                recordType = rs.getString("record_type"),
                recordId = rs.getObject("record_id", UUID::class.java),
                status = rs.getString("status"),
                doneAt = rs.getTimestamp("done_at")?.toInstant(),
                helperId = rs.getObject("helper_id", UUID::class.java),
                helperName = rs.getString("helper_name"),
            )
        }
        val records = describe(householdId, rows)
        return rows.mapNotNull { HeirTasks.words(it, records) }
    }

    private fun describe(householdId: UUID, rows: List<HeirTasks.Stored>): Map<UUID, HeirTasks.Record> {
        fun ids(type: String) = rows.filter { it.recordType == type }.mapNotNull { it.recordId }.ifEmpty { listOf(UUID(0, 0)) }
        val out = HashMap<UUID, HeirTasks.Record>()
        jdbc.query(
            """
            select i.id, i.title, coalesce(inst.name, acct_inst.name) as institution
            from investments i
            left join institutions inst on inst.id = i.institution_id
            left join accounts acct on acct.id = i.account_id
            left join institutions acct_inst on acct_inst.id = acct.institution_id
            where i.household_id = :hid and i.id in (:ids) and i.deleted_at is null
            """.trimIndent(),
            mapOf("hid" to householdId, "ids" to ids("investment")),
        ) { rs, _ ->
            val id = rs.getObject("id", UUID::class.java)
            val guide = runCatching { transmission.forInvestment(householdId, id) }.getOrNull()
            out[id] = HeirTasks.Record(
                title = rs.getString("title"),
                whereToStart = rs.getString("institution"),
                summary = guide?.summary,
                steps = guide?.steps.orEmpty(),
            )
        }
        jdbc.query(
            """
            select l.id, l.title, inst.name as lender
            from liabilities l left join institutions inst on inst.id = l.institution_id
            where l.household_id = :hid and l.id in (:ids) and l.deleted_at is null
            """.trimIndent(),
            mapOf("hid" to householdId, "ids" to ids("liability")),
        ) { rs, _ ->
            out[rs.getObject("id", UUID::class.java)] = HeirTasks.Record(rs.getString("title"), rs.getString("lender"))
        }
        jdbc.query(
            """
            select e.id, e.title,
                   coalesce(array_agg(coalesce(rm.display_name, rc.name, r.person_name))
                            filter (where r.id is not null), '{}') as executors
            from estate_documents e
            left join estate_roles r on r.estate_document_id = e.id
                 and r.role in ('executor','alternate_executor','attorney')
            left join members rm on rm.id = r.member_id
            left join contacts rc on rc.id = r.contact_id
            where e.household_id = :hid and e.id in (:ids) and e.deleted_at is null
            group by e.id, e.title
            """.trimIndent(),
            mapOf("hid" to householdId, "ids" to ids("estate_document")),
        ) { rs, _ ->
            val executors = (rs.getArray("executors").array as Array<*>).mapNotNull { it?.toString() }
            out[rs.getObject("id", UUID::class.java)] = HeirTasks.Record(
                title = rs.getString("title"),
                whereToStart = executors.takeIf { it.isNotEmpty() }?.joinToString(", "),
            )
        }
        return out
    }

    companion object {
        const val MAX_HELPERS = 5
        private val SITUATIONS = setOf("passed_away", "cannot_manage")
        private val STATUSES = setOf("todo", "done", "later")
    }
}

/**
 * Which tasks, in which order, and what they say. Pure, so the words can be
 * read and tested without a database.
 */
object HeirTasks {

    data class Sources(
        val holdings: List<UUID>,
        val insured: Set<UUID>,
        val debts: List<UUID>,
        val paperwork: List<UUID>,
    )

    data class Wanted(val key: String, val recordType: String?, val recordId: UUID?)

    data class Stored(
        val id: UUID,
        val key: String,
        val recordType: String?,
        val recordId: UUID?,
        val status: String,
        val doneAt: Instant?,
        val helperId: UUID?,
        val helperName: String?,
    )

    data class Record(
        val title: String,
        val whereToStart: String?,
        val summary: String? = null,
        val steps: List<TransmissionStep> = emptyList(),
    )

    fun plan(situation: String, sources: Sources): List<Wanted> = when (situation) {
        "passed_away" -> buildList {
            add(Wanted("certificates", null, null))
            sources.paperwork.forEach { add(Wanted("find_original", "estate_document", it)) }
            sources.holdings.forEach { add(Wanted("claim", "investment", it)) }
            sources.debts.forEach { add(Wanted("tell_lender", "liability", it)) }
            add(Wanted("heir_certificate", null, null))
        }
        else -> buildList {
            add(Wanted("authority", null, null))
            sources.paperwork.forEach { add(Wanted("find_paperwork", "estate_document", it)) }
            sources.debts.forEach { add(Wanted("keep_paid", "liability", it)) }
            sources.holdings.filter { it in sources.insured }.forEach { add(Wanted("keep_cover", "investment", it)) }
            add(Wanted("regular_payments", null, null))
        }
    }

    fun words(task: Stored, records: Map<UUID, Record>): HeirTask? {
        val record = task.recordId?.let { records[it] ?: return null }
        fun make(title: String, why: String, steps: List<TransmissionStep>, start: String? = record?.whereToStart) = HeirTask(
            id = task.id, key = task.key, recordType = task.recordType, recordId = task.recordId,
            title = title, why = why, steps = steps, whereToStart = start, status = task.status,
            doneAt = task.doneAt, helperId = task.helperId, helperName = task.helperName,
        )
        return when (task.key) {
            "certificates" -> make(
                "Get copies of the death certificate",
                "Almost every claim that follows asks for one.",
                listOf(
                    TransmissionStep("Register the death", "The hospital or crematorium gives you the form; the municipal office registers it."),
                    TransmissionStep("Ask for ten copies", "Attested copies cost little, and save a second trip for each claim."),
                    TransmissionStep("Keep them together", "With a few copies of your own ID and address proof."),
                ),
            )
            "find_original", "find_paperwork" -> make(
                "Find the original: ${record!!.title}",
                if (task.key == "find_original") "Banks and courts ask for the original, not a copy."
                else "It says who may act, and for what.",
                listOf(
                    TransmissionStep(
                        "Where it is kept was sealed",
                        "If you were given a recovery copy, open it from Family plan. Otherwise ask the people who were told.",
                    ),
                    TransmissionStep("Keep it as it is", "Do not unstaple it, write on it, or post the original."),
                ),
                start = record.whereToStart?.let { "Named in it: $it" },
            )
            "claim" -> make(
                "Claim ${record!!.title}",
                record.summary ?: "There is no standard route for this kind of holding. Start with the institution.",
                record.steps,
            )
            "tell_lender" -> make(
                "Tell ${record!!.whereToStart ?: "the lender"} about ${record.title}",
                "An EMI does not stop by itself, and some loans carry insurance that settles them.",
                listOf(
                    TransmissionStep("Say what has happened", "Call or visit the branch. Ask for the loan's account statement."),
                    TransmissionStep("Ask whether it is insured", "Many home and personal loans are, and the insurer settles the balance."),
                    TransmissionStep("Ask them to hold charges", "While the claim is made, so penalties do not build up."),
                ),
            )
            "heir_certificate" -> make(
                "If someone asks who the heirs are",
                "Where there was no nominee, or the amount is large, an institution may ask for proof.",
                listOf(
                    TransmissionStep("Legal heir certificate", "From the tahsildar or the revenue office."),
                    TransmissionStep("Succession certificate", "From a civil court, usually for debts owed to them and for securities."),
                    TransmissionStep("Ask first", "Ask the institution which one it wants before applying for either."),
                ),
                start = null,
            )
            "authority" -> make(
                "Check whether you can act for them",
                "Banks deal only with the account holder, or with someone they have authorised.",
                listOf(
                    TransmissionStep("Look for a power of attorney", "It will be in the paperwork, if there is one."),
                    TransmissionStep("If there is none", "Ask each bank what it accepts while they cannot sign."),
                ),
                start = null,
            )
            "keep_paid" -> make(
                "Keep ${record!!.title} paid",
                "Missed EMIs add charges, and they are hard to undo later.",
                listOf(
                    TransmissionStep("Find the account it comes from", "And check there is enough in it before the date."),
                    TransmissionStep("If it will run short", "Call the lender before the date, not after."),
                ),
            )
            "keep_cover" -> make(
                "Check ${record!!.title} is paid up",
                "A policy that lapses may not pay when it is needed most.",
                listOf(
                    TransmissionStep("Find the next premium date", "It is on the last premium receipt, or ask the insurer."),
                    TransmissionStep("Tell the insurer if it is a health policy", "Some ask to be told of a hospital stay within days."),
                ),
            )
            "regular_payments" -> make(
                "Keep the regular payments going",
                "Rent, school fees, electricity: the things that stop quietly if nobody notices.",
                listOf(
                    TransmissionStep("List what comes out each month", "The bank statement shows it."),
                    TransmissionStep("Decide who watches for each", "You do not have to do them all yourself."),
                ),
                start = null,
            )
            else -> null
        }
    }
}

@RestController
@RequestMapping("/api/v1/households/{householdId}/emergency/requests/{requestId}/heir")
class HeirModeController(private val service: HeirModeService) {

    @GetMapping
    fun heirPlan(@PathVariable householdId: UUID, @PathVariable requestId: UUID): HeirPlan =
        service.get(householdId, requestId)

    /** Starts heir mode, or returns to it. Safe to call every time the screen opens. */
    @PostMapping
    fun startHeirPlan(
        @PathVariable householdId: UUID,
        @PathVariable requestId: UUID,
        @RequestBody @Valid body: StartHeirPlanBody,
    ): HeirPlan = service.start(householdId, requestId, body.situation)

    @PostMapping("/pause")
    fun pauseHeirPlan(@PathVariable householdId: UUID, @PathVariable requestId: UUID): HeirPlan =
        service.pause(householdId, requestId, paused = true)

    @PostMapping("/resume")
    fun resumeHeirPlan(@PathVariable householdId: UUID, @PathVariable requestId: UUID): HeirPlan =
        service.pause(householdId, requestId, paused = false)

    @PatchMapping("/tasks/{taskId}")
    fun updateHeirTask(
        @PathVariable householdId: UUID,
        @PathVariable requestId: UUID,
        @PathVariable taskId: UUID,
        @RequestBody @Valid body: HeirTaskStatusBody,
    ): HeirPlan = service.setStatus(householdId, requestId, taskId, body.status)

    @PutMapping("/tasks/{taskId}/helper")
    fun assignHeirTask(
        @PathVariable householdId: UUID,
        @PathVariable requestId: UUID,
        @PathVariable taskId: UUID,
        @RequestBody body: AssignHeirTaskBody,
    ): HeirPlan = service.assign(householdId, requestId, taskId, body.helperId)

    @PostMapping("/helpers")
    @ResponseStatus(HttpStatus.CREATED)
    fun addHeirHelper(
        @PathVariable householdId: UUID,
        @PathVariable requestId: UUID,
        @RequestBody @Valid body: AddHeirHelperBody,
    ): HeirHelper = service.addHelper(
        householdId, requestId, body, ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString(),
    )

    @DeleteMapping("/helpers/{helperId}")
    fun removeHeirHelper(
        @PathVariable householdId: UUID,
        @PathVariable requestId: UUID,
        @PathVariable helperId: UUID,
    ): HeirPlan = service.removeHelper(householdId, requestId, helperId)
}

/** A relative's own door: the token is the credential, like any guest link (SecurityConfig). */
@RestController
@RequestMapping("/api/v1/share")
class HeirHelperController(private val service: HeirModeService) {

    @GetMapping("/{token}/tasks")
    fun helperTasks(@PathVariable token: String, request: HttpServletRequest): ResponseEntity<HeirHelperView> =
        ResponseEntity.ok()
            .header("Cache-Control", "no-store")
            .body(service.helperView(token, ipHash(request), request.getHeader("User-Agent")))

    /** As GuestShareController: the container's address, hashed, never stored raw. */
    private fun ipHash(request: HttpServletRequest): String? {
        val ip = request.remoteAddr ?: return null
        return MessageDigest.getInstance("SHA-256").digest(ip.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
    }
}
