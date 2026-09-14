package tech.bhrigu.almira.lifecycle

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.reminder.Notifier
import tech.bhrigu.almira.reminder.OutboundNotification
import tech.bhrigu.almira.security.RequestUserContext
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import javax.sql.DataSource

data class ComingOfAgeNotice(
    val memberId: UUID,
    val memberName: String,
    val turnsAdultOn: LocalDate,
    /** Whether they have their own login yet. Until they do, the next step is an invitation. */
    val hasLogin: Boolean,
    val isMe: Boolean,
    val welcomedAt: Instant?,
)

data class WelcomeRecord(
    val recordType: String,
    val recordId: UUID,
    val title: String,
    val visibility: String,
    /** Anyone else on the record — a parent who holds it as guardian stays a holder. */
    val otherHolders: List<String>,
)

data class ComingOfAgeWelcome(
    val memberId: UUID,
    val turnsAdultOn: LocalDate,
    val records: List<WelcomeRecord>,
    val welcomedAt: Instant?,
)

data class VisibilityChoice(val recordType: String, val recordId: UUID, val visibility: String)

data class AcceptWelcomeBody(val choices: List<VisibilityChoice> = emptyList())

/**
 * Coming of age (docs/05 §3.5): "when a minor gains their own login at adulthood,
 * control transfers to them."
 *
 * Three moments. In the month a managed child turns eighteen, the people who run
 * the household are told once, so an invitation to their own login can follow
 * (the ordinary invitation, which claims the child's member row rather than
 * making a second one). When the young adult signs in as themselves, the records
 * held in their name are theirs by the read rule already — a holder always sees
 * what they hold. And then they are welcomed: "These are yours now", the list,
 * and a choice for each of whether it stays visible to the household or becomes
 * private to them. That choice is made through their own row-level security,
 * like any visibility change.
 */
@Service
class ComingOfAgeService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val audit: AuditService,
    private val notifiers: List<Notifier>,
    private val userContext: RequestUserContext,
) {
    private val records = LifecycleRecords(jdbc)

    @Transactional(readOnly = true)
    fun comingOfAgeNotices(householdId: UUID): List<ComingOfAgeNotice> {
        val userId = userContext.require()
        households.get(householdId)
        return jdbc.query(
            """
            select n.member_id, m.display_name, n.turns_adult_on, m.user_id, n.welcomed_at
              from coming_of_age_notices n join members m on m.id = n.member_id and m.deleted_at is null
             where n.household_id = :hid
             order by n.turns_adult_on desc
            """.trimIndent(),
            mapOf("hid" to householdId),
        ) { rs, _ ->
            val memberUser = rs.getObject("user_id", UUID::class.java)
            ComingOfAgeNotice(
                memberId = rs.getObject("member_id", UUID::class.java),
                memberName = rs.getString("display_name"),
                turnsAdultOn = rs.getDate("turns_adult_on").toLocalDate(),
                hasLogin = memberUser != null,
                isMe = memberUser == userId,
                welcomedAt = rs.getTimestamp("welcomed_at")?.toInstant(),
            )
        }
    }

    @Transactional(readOnly = true)
    fun comingOfAgeWelcome(householdId: UUID): ComingOfAgeWelcome {
        val userId = userContext.require()
        val notice = comingOfAgeNotices(householdId).firstOrNull { it.isMe } ?: throw ApiException.notFound()
        val mine = records.memberIds(householdId, userId)
        val held = records.solelyHeld(householdId, userId, mine).filter { it.type in HOLDABLE } +
            records.jointlyHeld(householdId, mine)
        return ComingOfAgeWelcome(
            memberId = notice.memberId,
            turnsAdultOn = notice.turnsAdultOn,
            records = held.map {
                WelcomeRecord(it.type, it.id, it.title, visibilityOf(it), records.otherHolders(it.type, it.id, mine))
            },
            welcomedAt = notice.welcomedAt,
        )
    }

    @Transactional
    fun acceptComingOfAgeWelcome(householdId: UUID, choices: List<VisibilityChoice>): ComingOfAgeWelcome {
        val userId = userContext.require()
        val household = households.get(householdId)
        val welcome = comingOfAgeWelcome(householdId)
        val theirs = welcome.records.associateBy { it.recordType to it.recordId }

        choices.forEach { choice ->
            val record = theirs[choice.recordType to choice.recordId] ?: throw ApiException.notFound()
            if (choice.visibility !in setOf("private", "household")) {
                throw ApiException.badRequest("visibility_invalid", "Choose private, or shared with the household.")
            }
            if (choice.visibility == record.visibility) return@forEach
            val table = TABLES.getValue(choice.recordType)
            val changed = jdbc.update(
                "update $table set visibility = :v where id = :id and household_id = :hid",
                mapOf("v" to choice.visibility, "id" to choice.recordId, "hid" to householdId),
            )
            if (changed == 0) {
                throw ApiException.forbidden(
                    "Your role here can't change who sees this yet. Ask an admin to make you an editor.",
                )
            }
            jdbc.update(
                "delete from record_visibility_grants where record_type = :type and record_id = :id",
                mapOf("type" to choice.recordType, "id" to choice.recordId),
            )
        }
        jdbc.update(
            "update coming_of_age_notices set welcomed_at = coalesce(welcomed_at, now()) where member_id = :mid",
            mapOf("mid" to welcome.memberId),
        )
        audit.record(
            householdId = householdId, actorUserId = userId, action = "member.coming_of_age.welcome",
            entityType = "member", entityId = welcome.memberId,
            diff = mapOf(
                "records" to welcome.records.size,
                "madePrivate" to choices.count { it.visibility == "private" },
            ),
        )
        val name = households.members(householdId).firstOrNull { it.isMe }?.displayName ?: "They"
        households.members(householdId)
            .filter { it.role in setOf("owner", "admin") && it.userId != userId }
            .mapNotNull { it.userId }
            .forEach { admin ->
                runCatching {
                    notifiers.forEach { notifier ->
                        notifier.deliver(
                            OutboundNotification(
                                userId = admin, householdId = householdId, reminderId = null,
                                template = "lifecycle.coming_of_age.welcomed",
                                title = "$name has signed in to ${household.name}",
                                body = "What is held in their name is theirs to manage now.",
                                idempotencyKey = "coming-of-age.welcomed:${welcome.memberId}:$admin",
                            ),
                        )
                    }
                }
            }
        return comingOfAgeWelcome(householdId)
    }

    private fun visibilityOf(record: HeldRecord): String = jdbc.queryForObject(
        "select visibility from ${TABLES.getValue(record.type)} where id = :id",
        mapOf("id" to record.id), String::class.java,
    ) ?: "private"

    private companion object {
        val HOLDABLE = setOf("investment", "liability", "account")
        val TABLES = mapOf("investment" to "investments", "liability" to "liabilities", "account" to "accounts")
    }
}

/**
 * The monthly notice, run by [LifecycleSweep] on the OWNER connection: it reads
 * dates of birth across every household, which no user may do. The table's
 * primary key is the member, so a child is noticed once, however many times the
 * sweep runs in their birthday month.
 */
@Component
class ComingOfAgeNotices(
    @Qualifier("ownerDataSource") ownerDataSource: DataSource,
    private val notifiers: List<Notifier>,
) {
    private val owner = NamedParameterJdbcTemplate(ownerDataSource)

    fun run(today: LocalDate): Int {
        val noticed = owner.query(
            """
            insert into coming_of_age_notices (member_id, household_id, turns_adult_on)
            select m.id, m.household_id, (m.date_of_birth + interval '18 years')::date
              from members m join households h on h.id = m.household_id and h.deleted_at is null
             where m.deleted_at is null and m.user_id is null and m.date_of_birth is not null
               -- The birthday month; and a month's grace after it, so a sweep that did
               -- not run on the last days of a month does not miss someone for good.
               and (m.date_of_birth + interval '18 years')::date
                   between date_trunc('month', cast(:today as date) - interval '1 month')::date
                       and (date_trunc('month', cast(:today as date)) + interval '1 month - 1 day')::date
            on conflict (member_id) do nothing
            returning member_id, household_id, turns_adult_on
            """.trimIndent(),
            mapOf("today" to today),
        ) { rs, _ ->
            Triple(
                rs.getObject("member_id", UUID::class.java),
                rs.getObject("household_id", UUID::class.java),
                rs.getDate("turns_adult_on").toLocalDate(),
            )
        }
        noticed.forEach { (memberId, householdId, date) ->
            val name = owner.queryForObject(
                "select display_name from members where id = :id", mapOf("id" to memberId), String::class.java,
            )
            val admins = owner.query(
                """
                select user_id from household_memberships
                 where household_id = :hid and status = 'active' and role in ('owner', 'admin')
                """.trimIndent(),
                mapOf("hid" to householdId),
            ) { rs, _ -> rs.getObject("user_id", UUID::class.java) }
            val day = date.format(DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH))
            admins.forEach { admin ->
                notifiers.forEach { notifier ->
                    runCatching {
                        notifier.deliver(
                            OutboundNotification(
                                userId = admin, householdId = householdId, reminderId = null,
                                template = "lifecycle.coming_of_age.guardian",
                                title = "$name turns 18 on $day",
                                body = "Invite $name to their own login, so what is held in their name " +
                                    "becomes theirs to manage.",
                                idempotencyKey = "coming-of-age:$memberId:$admin",
                            ),
                        )
                    }
                }
            }
        }
        return noticed.size
    }
}

@RestController
@RequestMapping("/api/v1/households/{householdId}/coming-of-age")
class ComingOfAgeController(private val service: ComingOfAgeService) {

    @GetMapping
    fun comingOfAgeNotices(@PathVariable householdId: UUID): List<ComingOfAgeNotice> =
        service.comingOfAgeNotices(householdId)

    /** For the young adult, signed in as themselves: "These are yours now." 404 for anyone else. */
    @GetMapping("/welcome")
    fun comingOfAgeWelcome(@PathVariable householdId: UUID): ComingOfAgeWelcome =
        service.comingOfAgeWelcome(householdId)

    /** Their choice of what stays visible to the household, and that they have seen it. */
    @PostMapping("/welcome")
    fun acceptComingOfAgeWelcome(
        @PathVariable householdId: UUID,
        @RequestBody body: AcceptWelcomeBody,
    ): ComingOfAgeWelcome = service.acceptComingOfAgeWelcome(householdId, body.choices)
}
